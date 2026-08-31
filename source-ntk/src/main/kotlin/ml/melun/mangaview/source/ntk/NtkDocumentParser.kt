package ml.melun.mangaview.source.ntk

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class NtkEpisodeRecord(
    val episode: SourceEpisode,
    val imageCount: Int?,
    val imageEpisodeId: String?,
    val sequenceNumber: Long? = null,
)

data class NtkSearchResult(
    val series: List<SourceSeries>,
    val total: Int?,
)

data class NtkEpisodeResult(
    val episodes: List<NtkEpisodeRecord>,
    val authoritativeTotal: Int?,
) {
    val isComplete: Boolean
        get() = authoritativeTotal == null || episodes.size == authoritativeTotal
}

data class NtkManifestDocument(
    val directPages: List<NtkPageRequest>,
    val viewer: NtkViewerMetadata?,
) {
    val descriptor: NtkViewerDescriptor?
        get() = viewer?.descriptor
}

data class NtkViewerMetadata(
    val descriptor: NtkViewerDescriptor,
    val title: String?,
    val previousEpisodePath: String?,
    val nextEpisodePath: String?,
    val previousKnown: Boolean,
    val nextKnown: Boolean,
)

class NtkDocumentParser {
    fun searchApi(
        payload: String,
        sourceId: SourceId,
        forcedKind: NtkKind? = null,
    ): NtkSearchResult {
        val root = JSONObject(payload)
        val found = linkedMapOf<String, SourceSeries>()
        JsonObjects.walk(root) { candidate ->
            val workKey = string(candidate, "sourceWorkId", "workId", "id") ?: return@walk
            val title = string(candidate, "title", "name", "subject") ?: return@walk
            val kind = forcedKind ?: kind(candidate, string(candidate, "path", "href", "url")) ?: return@walk
            val key = NtkSeriesKey(kind, workKey)
            found.putIfAbsent(
                key.path(),
                SourceSeries(
                    id = SeriesId(sourceId, key.path()),
                    title = title.clean(),
                    subtitle = string(candidate, "author", "writer", "genre"),
                    thumbnailKey = string(candidate, "thumbnailUrl", "thumbnail", "thumb", "image", "cover"),
                ),
            )
        }
        return NtkSearchResult(found.values.toList(), positiveInt(root, "total"))
    }

    fun searchHtml(payload: String, sourceId: SourceId): List<SourceSeries> {
        val normalized = JsonObjects.normalizeEscapes(payload)
        val document = Jsoup.parse(normalized)
        val found = linkedMapOf<String, SourceSeries>()
        document.select("a[href]").forEach { link ->
            val path = normalizedSeriesPath(link.attr("href")) ?: return@forEach
            val key = NtkSeriesKey.decode(SeriesId(sourceId, path))
            val title = link.selectFirst("h1, h2, h3, h4, .title, .subject, strong")
                ?.text()?.clean() ?: link.ownText().clean()
            if (title.isBlank()) return@forEach
            val thumbnail = link.selectFirst("img")?.let(::imageAttribute)
            found.putIfAbsent(path, SourceSeries(SeriesId(sourceId, key.path()), title, thumbnailKey = thumbnail))
        }
        return found.values.toList()
    }

    fun episodes(payload: String, seriesId: SeriesId): NtkEpisodeResult {
        val key = NtkSeriesKey.decode(seriesId)
        val normalized = JsonObjects.normalizeEscapes(payload)
        val records = linkedMapOf<String, NtkEpisodeRecord>()
        parseEpisodeAnchors(normalized, seriesId, key, records)
        JsonObjects.embedded(normalized).forEach { root ->
            JsonObjects.walk(root) { candidate -> mergeEpisodeObject(candidate, seriesId, key, records) }
        }
        return NtkEpisodeResult(authoritativeEpisodeOrder(records.values), null)
    }

    fun episodesApi(payload: String, seriesId: SeriesId): NtkEpisodeResult {
        val root = JSONObject(payload)
        val key = NtkSeriesKey.decode(seriesId)
        val records = linkedMapOf<String, NtkEpisodeRecord>()
        val array = root.optJSONArray("episodes") ?: JSONArray()
        for (index in 0 until array.length()) {
            val candidate = array.optJSONObject(index) ?: continue
            mergeEpisodeObject(candidate, seriesId, key, records)
        }
        return NtkEpisodeResult(
            authoritativeEpisodeOrder(records.values),
            positiveInt(root, "total"),
        )
    }

    fun manifest(document: NtkEpisodeDocument): NtkManifestDocument {
        val normalized = JsonObjects.normalizeEscapes(document.html)
        val baseUrl = document.origin + document.path
        val parsed = Jsoup.parse(normalized, baseUrl)
        val pages = linkedMapOf<String, NtkPageRequest>()
        parsed.select("img").forEach { image ->
            if (hasBlockedContext(image)) return@forEach
            IMAGE_ATTRIBUTES.forEach { attribute ->
                addPage(pages, image.attr(attribute), baseUrl)
            }
        }
        val viewers = mutableListOf<NtkViewerMetadata>()
        JsonObjects.embedded(normalized).forEach { root ->
            viewers += viewerMetadata(root, document.path)
            JsonObjects.strings(root).forEach { value -> addPage(pages, value, baseUrl) }
        }
        val distinctViewers = viewers.distinct()
        require(distinctViewers.size <= 1) { "NTK document contains conflicting viewer identities" }
        return NtkManifestDocument(pages.values.toList(), distinctViewers.singleOrNull())
    }

    fun episodePageCount(payload: String): Int {
        val normalized = JsonObjects.normalizeEscapes(payload).replace("&amp;", "&")
        return Regex("(?:[?&])epage=([0-9]{1,3})", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.coerceIn(1, MAX_EPISODE_PAGES) ?: 1
    }

    private fun parseEpisodeAnchors(
        payload: String,
        seriesId: SeriesId,
        key: NtkSeriesKey,
        records: MutableMap<String, NtkEpisodeRecord>,
    ) {
        val document = Jsoup.parse(payload)
        document.select("a[href]").forEach { link ->
            val episodePath = normalizedEpisodePath(link.attr("href"), key) ?: return@forEach
            val title = link.selectFirst(".subject, .episode-title, .title, strong, b")
                ?.text()?.clean() ?: link.text().clean()
            if (title.isBlank() || title.contains("목록")) return@forEach
            val episode = SourceEpisode(EpisodeId(seriesId, episodePath), title)
            records.putIfAbsent(episodePath, NtkEpisodeRecord(episode, null, null))
        }
    }

    private fun mergeEpisodeObject(
        candidate: JSONObject,
        seriesId: SeriesId,
        key: NtkSeriesKey,
        records: MutableMap<String, NtkEpisodeRecord>,
    ) {
        val sourceEpisodeId = string(candidate, "sourceEpisodeId", "episodeId")
            ?: string(candidate, "id")?.takeIf { candidate.has("epNo") || candidate.has("imageCount") }
            ?: return
        val path = runCatching { key.episodePath(sourceEpisodeId) }.getOrNull() ?: return
        val existing = records[path]
        val fallbackNumber = positiveInt(candidate, "epNo", "number")
        val title = string(candidate, "title", "name")?.clean()
            ?: existing?.episode?.title
            ?: fallbackNumber?.let { "${it}화" }
            ?: sourceEpisodeId
        val imageCount = positiveInt(candidate, "imageCount", "count") ?: existing?.imageCount
        val episode = SourceEpisode(
            id = EpisodeId(seriesId, path),
            title = title,
            pageCountHint = imageCount,
        )
        records[path] = NtkEpisodeRecord(
            episode = episode,
            imageCount = imageCount,
            imageEpisodeId = string(candidate, "id") ?: sourceEpisodeId,
            sequenceNumber = fallbackNumber?.toLong() ?: existing?.sequenceNumber,
        )
    }

    private fun viewerMetadata(root: JSONObject, episodePath: String): List<NtkViewerMetadata> {
        val path = EpisodePath.parse(episodePath) ?: return emptyList()
        val results = mutableListOf<NtkViewerMetadata>()
        JsonObjects.walk(root) { candidate ->
            val token = string(candidate, "imagesToken", "token") ?: return@walk
            val workId = string(candidate, "sourceWorkId", "workId", "w") ?: return@walk
            val episodeId = string(candidate, "episodeId", "sourceEpisodeId", "e") ?: return@walk
            require(workId == path.workId) { "NTK viewer work identity does not match the requested document" }
            require(episodeId == path.episodeId) {
                "NTK viewer episode identity does not match the requested document"
            }
            val expectedApiPath = path.kind.apiPath
            val apiPath = string(candidate, "imageApiPath", "apiPath") ?: expectedApiPath
            require(apiPath == expectedApiPath) { "NTK viewer API kind does not match the requested document" }
            val count = pageCount(candidate)
            val previousKnown = candidate.has("prevEpId") || candidate.has("previousEpisodeId")
            val nextKnown = candidate.has("nextEpId") || candidate.has("nextEpisodeId")
            val previousPath = neighborPath(
                path,
                string(candidate, "prevEpId", "previousEpisodeId"),
            )
            val nextPath = neighborPath(
                path,
                string(candidate, "nextEpId", "nextEpisodeId"),
            )
            require(previousPath == null || previousPath != nextPath) {
                "NTK viewer neighbors must be distinct"
            }
            results += NtkViewerMetadata(
                descriptor = NtkViewerDescriptor(workId, episodeId, token, apiPath, count),
                title = string(candidate, "epTitle", "epLabel", "title")?.clean(),
                previousEpisodePath = previousPath,
                nextEpisodePath = nextPath,
                previousKnown = previousKnown,
                nextKnown = nextKnown,
            )
        }
        return results.distinct()
    }

    private fun neighborPath(path: EpisodePath, rawEpisodeId: String?): String? {
        val value = rawEpisodeId ?: return null
        val neighbor = NtkSeriesKey(path.kind, path.workId).episodePath(value)
        require(neighbor != path.fullPath) { "NTK viewer neighbor points to the current episode" }
        return neighbor
    }

    private fun pageCount(candidate: JSONObject): Int? {
        positiveInt(candidate, "imageCount", "count")?.let { return it }
        val images = candidate.optJSONArray("images") ?: candidate.optJSONArray("imageMetas") ?: return null
        var maximum = images.length()
        for (index in 0 until images.length()) {
            maximum = maxOf(maximum, images.optJSONObject(index)?.optInt("page", 0) ?: 0)
        }
        return maximum.takeIf { it > 0 }
    }

    private fun addPage(target: MutableMap<String, NtkPageRequest>, candidate: String, baseUrl: String) {
        val trimmed = candidate.trim().replace("\\/", "/")
        if (!isPageImage(trimmed)) return
        val resolved = runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrNull() ?: return
        target.putIfAbsent(resolved, NtkPageRequest(resolved))
    }

    private fun isPageImage(value: String): Boolean {
        val lower = value.lowercase()
        if (!lower.startsWith("http") && !lower.startsWith("//") && !lower.startsWith('/')) return false
        if (BLOCKED_IMAGE_TOKENS.any(lower::contains)) return false
        if (!lower.matches(Regex(".*\\.(?:jpe?g|png|webp)(?:[?#].*)?$"))) return false
        return CONTENT_PATH_TOKENS.any(lower::contains) || HASH_FILE.containsMatchIn(lower)
    }

    private fun hasBlockedContext(image: Element): Boolean {
        var current: Element? = image
        while (current != null) {
            val marker = "${current.id()} ${current.className()}".lowercase()
            if (BLOCKED_CONTEXT_TOKENS.any(marker::contains)) return true
            current = current.parent()
        }
        return false
    }

    private fun normalizedSeriesPath(value: String): String? {
        val path = pathOnly(value)
        return Regex("^/(?:manhwa|webtoon)/[A-Za-z0-9_-]{1,160}$").matchEntire(path)?.value
    }

    private fun normalizedEpisodePath(value: String, key: NtkSeriesKey): String? {
        val path = pathOnly(value).trimEnd('/')
        val prefix = "${key.path()}/"
        if (!path.startsWith(prefix)) return null
        val episodeKey = path.removePrefix(prefix)
        return runCatching { key.episodePath(episodeKey) }.getOrNull()
    }

    private fun pathOnly(value: String): String = runCatching {
        val uri = URI(value.trim())
        (uri.path ?: "").ifBlank { value.substringBefore('?').substringBefore('#') }
    }.getOrDefault(value.substringBefore('?').substringBefore('#'))

    private fun kind(candidate: JSONObject, path: String?): NtkKind? {
        val value = string(candidate, "kind", "type", "category")?.lowercase().orEmpty()
        val combined = "$value ${path.orEmpty().lowercase()}"
        return when {
            "manhwa" in combined || "comic" in combined -> NtkKind.MANHWA
            "webtoon" in combined -> NtkKind.WEBTOON
            else -> null
        }
    }

    private fun string(candidate: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        if (!candidate.has(key) || candidate.isNull(key)) return@firstNotNullOfOrNull null
        candidate.optString(key, "")
            .trim()
            .takeIf(String::isNotEmpty)
            ?.takeUnless { it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true) }
    }

    private fun positiveInt(candidate: JSONObject, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        candidate.optString(key, "").toIntOrNull()?.takeIf { it > 0 }
    }

    private fun imageAttribute(image: Element): String? =
        IMAGE_ATTRIBUTES.firstNotNullOfOrNull { image.attr(it).trim().takeIf(String::isNotEmpty) }

    private fun String.clean(): String = replace('\u00a0', ' ').replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val MAX_EPISODE_PAGES = 100
        val IMAGE_ATTRIBUTES = listOf("data-original", "data-src", "data-lazy-src", "data-url", "src")
        val BLOCKED_CONTEXT_TOKENS = listOf("banner", "advert", "sponsor", "popup")
        val BLOCKED_IMAGE_TOKENS = BLOCKED_CONTEXT_TOKENS + listOf(
            "board_uploads", "logo", "sprite", "blank", "loading", "image-comic.pstatic.net",
        )
        val CONTENT_PATH_TOKENS = listOf(
            "/webtoon_uploads/", "/manhwa_uploads/", "/comic_uploads/", "/episodes/", "/token/",
        )
        val HASH_FILE = Regex("/[A-Za-z0-9_-]{16,}\\.(?:jpe?g|png|webp)(?:[?#].*)?$")
    }

    private data class EpisodePath(
        val kind: NtkKind,
        val workId: String,
        val episodeId: String,
        val fullPath: String,
    ) {
        companion object {
            fun parse(value: String): EpisodePath? {
                val match = PATTERN.matchEntire(value) ?: return null
                val kind = NtkKind.entries.first { it.pathSegment == match.groupValues[1] }
                return EpisodePath(kind, match.groupValues[2], match.groupValues[3], value)
            }

            private val PATTERN = Regex(
                "^/(webtoon|manhwa)/([A-Za-z0-9_-]{1,160})/([A-Za-z0-9_.-]{1,200})$",
            )
        }
    }
}

private val NtkKind.apiPath: String
    get() = when (this) {
        NtkKind.WEBTOON -> "/api/webtoon-images"
        NtkKind.MANHWA -> "/api/manhwa-images"
    }

/** NTK's epNo is the provider's sequence contract; display titles are not ordering metadata. */
internal fun authoritativeEpisodeOrder(
    records: Collection<NtkEpisodeRecord>,
): List<NtkEpisodeRecord> {
    if (records.none { it.sequenceNumber != null }) return records.toList()
    return records.sortedWith(
        compareByDescending<NtkEpisodeRecord> { it.sequenceNumber ?: Long.MIN_VALUE },
    )
}
