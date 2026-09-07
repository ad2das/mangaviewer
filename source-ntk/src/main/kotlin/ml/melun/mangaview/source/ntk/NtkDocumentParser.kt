package ml.melun.mangaview.source.ntk

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SeriesKind
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
    val recognized: Boolean,
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
    val directPagesOwnedByViewer: Boolean = false,
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
    fun genres(payload: String, kind: SeriesKind): List<SourceGenre> {
        val normalized = JsonObjects.normalizeEscapes(payload)
        val values = when (kind) {
            SeriesKind.WEBTOON -> parseWebtoonGenres(normalized)
            SeriesKind.COMIC -> parseComicGenres(normalized)
        }
        return values.distinctBy { it.key }
    }

    fun searchApi(
        payload: String,
        sourceId: SourceId,
        forcedKind: NtkKind? = null,
    ): NtkSearchResult {
        val root = JSONObject(payload)
        val works = root.optJSONArray("works")
            ?: root.optJSONObject("data")?.optJSONArray("works")
            ?: return NtkSearchResult(emptyList(), null, recognized = false)
        return NtkSearchResult(
            parseWorks(works, sourceId, forcedKind),
            nonNegativeInt(root, "total"),
            recognized = true,
        )
    }

    private fun parseWorks(
        works: JSONArray,
        sourceId: SourceId,
        forcedKind: NtkKind?,
    ): List<SourceSeries> {
        val found = linkedMapOf<String, SourceSeries>()
        for (index in 0 until works.length()) {
            val candidate = works.optJSONObject(index) ?: continue
            val workKey = string(candidate, "sourceWorkId", "workId") ?: continue
            val title = string(candidate, "title", "name") ?: continue
            val kind = forcedKind ?: kind(candidate, string(candidate, "path", "href", "url")) ?: continue
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
        return found.values.toList()
    }

    fun searchHtml(
        payload: String,
        sourceId: SourceId,
        forcedKind: NtkKind? = null,
    ): List<SourceSeries> {
        val normalized = JsonObjects.normalizeEscapes(payload)
        embeddedArray(normalized, "initialWorks")?.let { works ->
            parseWorks(works, sourceId, forcedKind).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val document = Jsoup.parse(normalized)
        val found = linkedMapOf<String, SourceSeries>()
        document.select("a[href]").forEach { link ->
            val path = normalizedSeriesPath(link.attr("href")) ?: return@forEach
            val key = NtkSeriesKey.decode(SeriesId(sourceId, path))
            val title = link.selectFirst("h1, h2, h3, h4, .title, .subject, strong")
                ?.text()?.clean() ?: link.ownText().clean()
            if (title.isBlank() || title in NON_SERIES_TITLES || NON_EPISODE_LABELS.any(title::contains)) {
                return@forEach
            }
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
        val started = System.nanoTime()
        val baseUrl = document.origin + document.path
        val parsed = Jsoup.parse(document.html, baseUrl)
        val domAt = System.nanoTime()
        val viewers = mutableListOf<NtkViewerMetadata>()
        val ownedPages = mutableListOf<List<NtkPageRequest>>()
        NtkDocumentJsonReader.read(parsed.select("script").map { it.data() }).forEach { root ->
            viewers += viewerMetadata(root, document.path) { owner ->
                NtkDirectPageReader.read(owner) { pageUrl(it, baseUrl) }
                    .takeIf { it.isNotEmpty() }?.let(ownedPages::add)
            }
        }
        val distinctViewers = viewers.distinct()
        require(distinctViewers.size <= 1) { "NTK document contains conflicting viewer identities" }
        val jsonAt = System.nanoTime()
        // Protected viewers require their owned sequence or the protected image API. The service
        // never admits DOM images for them, so avoid extracting unowned page chrome on this path.
        val selected = ownedPages.firstOrNull() ?: if (distinctViewers.isNotEmpty()) emptyList() else {
            parsed.select("img").filterNot(::hasBlockedContext).mapNotNull { image ->
                IMAGE_ATTRIBUTES.firstNotNullOfOrNull { pageUrl(image.attr(it), baseUrl) }?.let(::NtkPageRequest)
            }
        }
        require(ownedPages.all { it == selected }) { "NTK document contains conflicting page sequences" }
        logManifestParsing(started, domAt, jsonAt, System.nanoTime())
        return NtkManifestDocument(selected, distinctViewers.singleOrNull(), ownedPages.isNotEmpty())
    }

    fun episodePageCount(payload: String): Int {
        val normalized = JsonObjects.normalizeEscapes(payload).replace("&amp;", "&")
        return Regex("(?:[?&])epage=([0-9]{1,3})", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.coerceIn(1, MAX_EPISODE_PAGES) ?: 1
    }

    private fun parseWebtoonGenres(payload: String): List<SourceGenre> {
        val array = Regex(
            "\\\"tags\\\"\\s*:\\s*(\\[.*?])\\s*,\\s*\\\"platforms\\\"",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(payload)?.groupValues?.get(1) ?: return emptyList()
        return runCatching { JSONArray(array) }.getOrNull()?.let { tags ->
            (0 until tags.length()).mapNotNull { index ->
                val tag = tags.optJSONObject(index) ?: return@mapNotNull null
                val id = tag.optString("id").trim()
                val name = tag.optString("name").clean()
                if (id.isEmpty() || name.isEmpty()) null else SourceGenre(id, name)
            }
        }.orEmpty()
    }

    private fun parseComicGenres(payload: String): List<SourceGenre> {
        val array = Regex(
            "\\\"genres\\\"\\s*:\\s*(\\[.*?])",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(payload)?.groupValues?.get(1) ?: return emptyList()
        return runCatching { JSONArray(array) }.getOrNull()?.let { genres ->
            (0 until genres.length()).mapNotNull { index ->
                genres.optString(index).clean().takeIf(String::isNotEmpty)?.let { SourceGenre(it, it) }
            }
        }.orEmpty()
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
            if (title.isBlank() || NON_EPISODE_LABELS.any(title::contains)) return@forEach
            val episode = SourceEpisode(
                EpisodeId(seriesId, episodePath),
                title,
                sequenceNumber = episodeNumber(title),
            )
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
            sequenceNumber = fallbackNumber?.toDouble() ?: existing?.episode?.sequenceNumber,
        )
        records[path] = NtkEpisodeRecord(
            episode = episode,
            imageCount = imageCount,
            imageEpisodeId = string(candidate, "id") ?: sourceEpisodeId,
            sequenceNumber = fallbackNumber?.toLong() ?: existing?.sequenceNumber,
        )
    }

    private fun viewerMetadata(
        root: Any, episodePath: String, onViewer: (JSONObject) -> Unit,
    ): List<NtkViewerMetadata> {
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
            onViewer(candidate)
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

    private fun pageUrl(candidate: String, baseUrl: String): String? {
        val trimmed = candidate.trim().replace("\\/", "/")
        if (!isPageImage(trimmed)) return null
        return runCatching {
            URI(baseUrl).resolve(trimmed).takeIf {
                it.scheme in setOf("http", "https") && !it.host.isNullOrBlank()
            }?.toString()
        }.getOrNull()
    }

    private fun isPageImage(value: String): Boolean {
        val lower = value.lowercase()
        if (!lower.startsWith("http") && !lower.startsWith("//") && !lower.startsWith('/')) return false
        if (BLOCKED_IMAGE_TOKENS.any(lower::contains)) return false
        if (!lower.matches(IMAGE_FILE)) return false
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
        return Regex("^/(?:manhwa|webtoon)/[\\p{L}\\p{N}_-]{1,160}$").matchEntire(path)?.value
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

    private fun nonNegativeInt(candidate: JSONObject, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        candidate.optString(key, "").toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun imageAttribute(image: Element): String? =
        IMAGE_ATTRIBUTES.firstNotNullOfOrNull { image.attr(it).trim().takeIf(String::isNotEmpty) }

    private fun String.clean(): String = replace('\u00a0', ' ').replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun episodeNumber(title: String): Double? =
        Regex("([0-9]+(?:\\.[0-9]+)?)\\s*화").find(title)?.groupValues?.get(1)?.toDoubleOrNull()

    private companion object {
        val NON_EPISODE_LABELS = listOf(
            "목록", "최신화 보기", "첫화부터", "처음부터", "정주행", "이어보기", "전체보기",
        )
        val NON_SERIES_TITLES = setOf(
            "업데이트", "최신 업데이트", "전체", "웹툰", "만화", "목록", "더보기",
        )
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
        val IMAGE_FILE = Regex(".*\\.(?:jpe?g|png|webp)(?:[?#].*)?$")
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
                "^/(webtoon|manhwa)/([\\p{L}\\p{N}_-]{1,160})/([\\p{L}\\p{N}_.-]{1,200})$",
            )
        }
    }
}

private fun logManifestParsing(started: Long, dom: Long, json: Long, images: Long) {
    runCatching { android.util.Log.d("NtkNative",
        "phase=parser-stages normalizeMs=0 " +
            "domMs=${(dom - started) / 1_000_000L} imagesMs=${(images - json) / 1_000_000L} " +
            "jsonMs=${(json - dom) / 1_000_000L}",
    ) }
}

private fun embeddedArray(payload: String, key: String): JSONArray? {
    val marker = payload.indexOf("\"$key\"")
    if (marker < 0) return null
    val start = payload.indexOf('[', marker + key.length + 2)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until payload.length) {
        val character = payload[index]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }
        when (character) {
            '"' -> inString = true
            '[' -> depth += 1
            ']' -> {
                depth -= 1
                if (depth == 0) {
                    return runCatching { JSONArray(payload.substring(start, index + 1)) }.getOrNull()
                }
            }
        }
    }
    return null
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
