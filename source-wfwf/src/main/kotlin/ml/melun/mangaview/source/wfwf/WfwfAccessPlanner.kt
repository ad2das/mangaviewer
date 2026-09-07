package ml.melun.mangaview.source.wfwf

import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.EpisodeDocumentPlanner
import ml.melun.mangaview.engine.api.PageAccessPlan
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.SourceRequest
import org.jsoup.Jsoup

class WfwfAccessPlanner(
    private val userAgent: String,
) : EpisodeDocumentPlanner {
    override val sourceId: SourceId = SourceId("wfwf")
    private val parser = WfwfHtmlParser()

    override fun documentRequest(
        episodeId: EpisodeId,
        origin: URI,
        priority: WorkPriority,
    ): SourceRequest {
        val key = validateEpisode(episodeId)
        requireOrigin(origin)
        val path = when (key.kind) {
            WfwfKind.COMIC -> "/cv?toon=${key.titleId}&num=${episodeId.remoteKey}"
            WfwfKind.WEBTOON -> "/view?toon=${key.titleId}&num=${episodeId.remoteKey}"
        }
        return SourceRequest(
            url = origin.resolve(path).toString(),
            headers = requestHeaders(),
            totalTimeoutMillis = 45_000L,
            priority = priority.toPageFetchPriority(),
        )
    }

    override fun parseEpisode(
        episodeId: EpisodeId,
        document: SourceDocument,
        authEpoch: Long,
        catalogAdjacency: AdjacentEpisodes?,
    ): EpisodeAccessPlan {
        val key = validateEpisode(episodeId)
        val parsed = document.openBody().use { input ->
            parseDocument(input, document.finalUrl)
        }
        val images = WfwfAccessImages.select(parsed, document.finalUrl)
        require(images.isNotEmpty()) { "WFWF episode contains no page images" }
        val metadata = parser.viewerMetadata(parsed, key, episodeId.remoteKey)
        val navigation = navigation(episodeId, metadata, catalogAdjacency)
        val pageIds = images.mapIndexed { index, _ -> PageId.at(episodeId, index) }
        val manifest = EpisodeManifest(
            id = episodeId,
            title = metadata.title ?: episodeId.remoteKey,
            pages = pageIds.mapIndexed { index, pageId -> PageSpec(pageId, index) },
            previousEpisodeId = navigation.previous,
            nextEpisodeId = navigation.next,
        )
        return EpisodeAccessPlan(
            manifest = manifest,
            contentRevision = contentRevision(episodeId, images),
            documentSha256 = document.sha256,
            finalDocumentUrl = document.finalUrl,
            authEpoch = authEpoch,
            pages = images.mapIndexed { index, image ->
                PageAccessPlan(pageIds[index], image.sourceRecord, image.candidates)
            },
            navigationKnown = navigation.known,
        )
    }

    override fun pageRequest(
        plan: EpisodeAccessPlan,
        pageId: PageId,
        candidateIndex: Int,
        priority: WorkPriority,
        validation: PageValidation?,
    ): SourceRequest {
        require(plan.manifest.id.seriesId.sourceId == sourceId) {
            "Access plan belongs to another source"
        }
        require(pageId.episodeId.seriesId.sourceId == sourceId) {
            "Page belongs to another source"
        }
        val page = plan.page(pageId)
        require(candidateIndex in page.candidates.indices) { "Page candidate is out of bounds" }
        val headers = requestHeaders(plan.finalDocumentUrl.toString()).toMutableMap()
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        return SourceRequest(
            url = page.candidates[candidateIndex].toString(),
            headers = headers,
            priority = priority.toPageFetchPriority(),
        )
    }

    private fun validateEpisode(episodeId: EpisodeId): WfwfSeriesKey {
        require(episodeId.seriesId.sourceId == sourceId) { "Episode belongs to another source" }
        val key = WfwfSeriesKey.decode(episodeId.seriesId)
        require(episodeId.remoteKey.matches(NUMERIC_EPISODE)) {
            "WFWF episode key must be a positive number"
        }
        require(episodeId.remoteKey.toLongOrNull()?.let { it > 0L } == true) {
            "WFWF episode key must be a positive number"
        }
        return key
    }

    private fun requireOrigin(origin: URI) {
        require(origin.scheme.equals("http", ignoreCase = true) ||
            origin.scheme.equals("https", ignoreCase = true)) {
            "WFWF origin must use HTTP or HTTPS"
        }
        require(!origin.host.isNullOrBlank()) { "WFWF origin must include a host" }
    }

    private fun parseDocument(input: InputStream, finalUrl: URI) =
        Jsoup.parse(input, null, finalUrl.toString())

    private fun navigation(
        episodeId: EpisodeId,
        metadata: WfwfViewerMetadata,
        catalogAdjacency: AdjacentEpisodes?,
    ): Navigation {
        catalogAdjacency?.let { validateCatalogAdjacency(episodeId, it) }
        if (metadata.navigationKnown) {
            return Navigation(
                previous = metadata.previousEpisodeKey?.let { EpisodeId(episodeId.seriesId, it) },
                next = metadata.nextEpisodeKey?.let { EpisodeId(episodeId.seriesId, it) },
                known = true,
            )
        }
        if (catalogAdjacency == null) return Navigation(null, null, known = false)
        return Navigation(catalogAdjacency.previous, catalogAdjacency.next, known = true)
    }

    private fun validateCatalogAdjacency(episodeId: EpisodeId, adjacency: AdjacentEpisodes) {
        listOf(adjacency.previous, adjacency.next).filterNotNull().forEach { neighbor ->
            require(neighbor.seriesId == episodeId.seriesId) {
                "Catalog adjacency belongs to another series"
            }
            require(
                neighbor != episodeId &&
                    neighbor.remoteKey.toLongOrNull() != episodeId.remoteKey.toLongOrNull(),
            ) {
                "Catalog adjacency cannot point to the current episode"
            }
        }
    }

    private fun contentRevision(
        episodeId: EpisodeId,
        images: List<WfwfAccessImage>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appendField(digest, episodeId.seriesId.sourceId.value)
        appendField(digest, episodeId.seriesId.remoteKey)
        appendField(digest, episodeId.remoteKey)
        images.forEachIndexed { index, image ->
            appendField(digest, index.toString())
            appendField(digest, image.primary.toString())
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    private fun appendField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun requestHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,image/*,*/*;q=0.8")
        referer?.let { put("Referer", it) }
    }

    private fun WorkPriority.toPageFetchPriority(): PageFetchPriority = when (this) {
        WorkPriority.FOCUS -> PageFetchPriority.FOCUS
        WorkPriority.VISIBLE -> PageFetchPriority.VISIBLE
        WorkPriority.INTERACTIVE -> PageFetchPriority.NORMAL
        WorkPriority.NEXT_IMAGE -> PageFetchPriority.FORWARD
        WorkPriority.NEXT_EPISODE -> PageFetchPriority.ADJACENT_FORWARD
        WorkPriority.ARTWORK, WorkPriority.OFFLINE -> PageFetchPriority.BACKGROUND
    }

    private data class Navigation(
        val previous: EpisodeId?,
        val next: EpisodeId?,
        val known: Boolean,
    )

    private companion object {
        val NUMERIC_EPISODE = Regex("[0-9]+")
    }
}
