package ml.melun.mangaview.source.newxtoon

import java.net.URI
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

/** Pure Newxtoon request construction and episode-plan parsing for the viewer engine. */
class NewxtoonAccessPlanner(private val userAgent: String) : EpisodeDocumentPlanner {
    override val sourceId = SourceId("newxtoon")
    private val parser = NewxtoonHtmlParser(DEFAULT_NEWXTOON_ORIGIN)

    override fun documentRequest(episodeId: EpisodeId, origin: URI, priority: WorkPriority): SourceRequest {
        require(episodeId.seriesId.sourceId == sourceId) { "Episode belongs to another source" }
        return SourceRequest(
            url = origin.resolve(chapterPath(episodeId)).toString(),
            headers = documentHeaders(),
            totalTimeoutMillis = 45_000L,
            priority = priority.fetchPriority(),
        )
    }

    override fun parseEpisode(
        episodeId: EpisodeId,
        document: SourceDocument,
        authEpoch: Long,
        catalogAdjacency: AdjacentEpisodes?,
    ): EpisodeAccessPlan {
        require(episodeId.seriesId.sourceId == sourceId) { "Episode belongs to another source" }
        val html = document.openBody().use { it.readBytes().toString(Charsets.UTF_8) }
        val pages = parser.pages(html)
        require(pages.isNotEmpty()) { "Newxtoon chapter contains no page images" }
        val pageIds = pages.mapIndexed { index, _ -> PageId.at(episodeId, index) }
        val manifest = EpisodeManifest(
            id = episodeId,
            title = parser.title(html)?.takeIf { it.isNotBlank() } ?: episodeId.remoteKey,
            pages = pageIds.mapIndexed { index, pageId -> PageSpec(pageId, index) },
            previousEpisodeId = catalogAdjacency?.previous,
            nextEpisodeId = catalogAdjacency?.next,
        )
        return EpisodeAccessPlan(
            manifest = manifest,
            contentRevision = contentRevision(pages),
            documentSha256 = document.sha256,
            finalDocumentUrl = document.finalUrl,
            authEpoch = authEpoch,
            pages = pages.mapIndexed { index, page ->
                PageAccessPlan(pageIds[index], "image:${index + 1}", listOf(URI(page.url)))
            },
            navigationKnown = catalogAdjacency != null,
        )
    }

    override fun pageRequest(
        plan: EpisodeAccessPlan,
        pageId: PageId,
        candidateIndex: Int,
        priority: WorkPriority,
        validation: PageValidation?,
    ): SourceRequest {
        require(plan.manifest.id.seriesId.sourceId == sourceId) { "Access plan belongs to another source" }
        val page = plan.page(pageId)
        require(candidateIndex in page.candidates.indices) { "Page candidate is out of bounds" }
        val headers = imageHeaders(plan.finalDocumentUrl.toString()).toMutableMap()
        validation?.entityTag?.let { headers["If-None-Match"] = it }
        validation?.lastModified?.let { headers["If-Modified-Since"] = it }
        return SourceRequest(
            url = page.candidates[candidateIndex].toString(),
            headers = headers,
            priority = priority.fetchPriority(),
        )
    }

    fun chapterPath(episodeId: EpisodeId): String =
        "/comics/${episodeId.seriesId.remoteKey}/chapters/${episodeId.remoteKey}"

    fun documentHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    fun imageHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
    )

    private fun contentRevision(pages: List<NewxtoonPage>): String =
        MessageDigest.getInstance("SHA-256").digest(pages.joinToString("|") { it.url }.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

internal fun WorkPriority.fetchPriority(): PageFetchPriority = when (this) {
    WorkPriority.FOCUS -> PageFetchPriority.FOCUS
    WorkPriority.VISIBLE -> PageFetchPriority.VISIBLE
    WorkPriority.INTERACTIVE -> PageFetchPriority.IMMINENT_FORWARD
    WorkPriority.NEXT_IMAGE -> PageFetchPriority.FORWARD
    WorkPriority.NEXT_EPISODE -> PageFetchPriority.ADJACENT_FORWARD
    else -> PageFetchPriority.NORMAL
}
