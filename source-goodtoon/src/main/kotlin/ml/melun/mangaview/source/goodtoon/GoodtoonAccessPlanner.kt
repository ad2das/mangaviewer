package ml.melun.mangaview.source.goodtoon

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

class GoodtoonAccessPlanner(
    private val userAgent: String,
) : EpisodeDocumentPlanner {
    override val sourceId: SourceId = goodtoonSourceId()
    private val parser = GoodtoonHtmlParser()

    override fun documentRequest(
        episodeId: EpisodeId,
        origin: URI,
        priority: WorkPriority,
    ): SourceRequest {
        val key = validateEpisode(episodeId)
        requireOrigin(origin)
        val path = key.chapterPath(GoodtoonEpisodeKey.decode(episodeId).encode())
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
        val parsed = document.openBody().use { input -> parseDocument(input, document.finalUrl) }
        val images = parser.pageImages(parsed, document.finalUrl)
        require(images.isNotEmpty()) { "GoodToon episode contains no page images" }
        val chapters = parser.viewerChapters(parsed, key)
        val navigation = navigation(episodeId, chapters, catalogAdjacency)
        val pageIds = images.mapIndexed { index, _ -> PageId.at(episodeId, index) }
        val title = chapters.firstOrNull { it.slug == episodeId.remoteKey }?.title ?: episodeId.remoteKey
        val manifest = EpisodeManifest(
            id = episodeId,
            title = title,
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
            pages = images.mapIndexed { index, url ->
                PageAccessPlan(pageIds[index], "img:$index", listOf(URI(url)))
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

    private fun validateEpisode(episodeId: EpisodeId): GoodtoonSeriesKey {
        require(episodeId.seriesId.sourceId == sourceId) { "Episode belongs to another source" }
        GoodtoonEpisodeKey.decode(episodeId)
        return GoodtoonSeriesKey.decode(episodeId.seriesId)
    }

    private fun requireOrigin(origin: URI) {
        require(
            origin.scheme.equals("http", ignoreCase = true) ||
                origin.scheme.equals("https", ignoreCase = true),
        ) { "GoodToon origin must use HTTP or HTTPS" }
        require(!origin.host.isNullOrBlank()) { "GoodToon origin must include a host" }
    }

    private fun parseDocument(input: InputStream, finalUrl: URI) =
        Jsoup.parse(input, null, finalUrl.toString())

    private fun navigation(
        episodeId: EpisodeId,
        chapters: List<GoodtoonViewerChapter>,
        catalogAdjacency: AdjacentEpisodes?,
    ): Navigation {
        catalogAdjacency?.let { validateCatalogAdjacency(episodeId, it) }
        val index = chapters.indexOfFirst { it.slug == episodeId.remoteKey }
        if (index >= 0) {
            return Navigation(
                previous = chapters.getOrNull(index + 1)?.let { neighbor(episodeId, it.slug) },
                next = chapters.getOrNull(index - 1)?.let { neighbor(episodeId, it.slug) },
                known = true,
            )
        }
        if (catalogAdjacency == null) return Navigation(null, null, known = false)
        return Navigation(catalogAdjacency.previous, catalogAdjacency.next, known = true)
    }

    private fun neighbor(episodeId: EpisodeId, slug: String): EpisodeId =
        EpisodeId(episodeId.seriesId, slug)

    private fun validateCatalogAdjacency(episodeId: EpisodeId, adjacency: AdjacentEpisodes) {
        listOf(adjacency.previous, adjacency.next).filterNotNull().forEach { neighbor ->
            require(neighbor.seriesId == episodeId.seriesId) {
                "Catalog adjacency belongs to another series"
            }
            require(neighbor.seriesId != episodeId.seriesId || neighbor.remoteKey != episodeId.remoteKey) {
                "Catalog adjacency cannot point to the current episode"
            }
        }
    }

    private fun contentRevision(episodeId: EpisodeId, images: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appendField(digest, episodeId.seriesId.sourceId.value)
        appendField(digest, episodeId.seriesId.remoteKey)
        appendField(digest, episodeId.remoteKey)
        images.forEachIndexed { index, url ->
            appendField(digest, index.toString())
            appendField(digest, url)
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
}
