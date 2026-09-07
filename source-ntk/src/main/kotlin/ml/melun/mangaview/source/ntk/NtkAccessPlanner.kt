package ml.melun.mangaview.source.ntk

import java.net.URI
import java.security.MessageDigest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.AccessPrerequisite
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.EpisodeDocumentPlanner
import ml.melun.mangaview.engine.api.PageAccessPlan
import ml.melun.mangaview.engine.api.SourceDocument
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.SourceRequest

/** Bound immutable parse result. Browser/ACK execution is deliberately outside this object. */
class NtkAccessDocument internal constructor(
    val episodeId: EpisodeId,
    val sourceDocument: SourceDocument,
    val authEpoch: Long,
    val browserDocument: NtkEpisodeDocument,
    internal val parsed: NtkManifestDocument,
) {
    val descriptor: NtkViewerDescriptor? get() = parsed.descriptor
    val directPagesComplete: Boolean get() = parsed.directPages.isNotEmpty() &&
        (descriptor == null || parsed.directPagesOwnedByViewer) &&
        (descriptor?.expectedPageCount == null || descriptor?.expectedPageCount == parsed.directPages.size)
    val navigationKnown: Boolean get() = parsed.viewer?.let { it.previousKnown && it.nextKnown } == true
    override fun toString() = "NtkAccessDocument(episodeId=$episodeId, directPagesComplete=$directPagesComplete)"
}

class NtkImageManifestRequired(val document: NtkAccessDocument) :
    IllegalStateException("NTK requires an identity-bound protected image manifest")

/** Pure NTK request construction and response binding; owns no browser, transport, cache or queue. */
class NtkAccessPlanner(private val userAgent: String) : EpisodeDocumentPlanner {
    override val sourceId = SourceId("ntk")
    private val parser = NtkDocumentParser()
    private val protectedManifest = NtkBrowserManifestParser()

    override fun documentRequest(episodeId: EpisodeId, origin: URI, priority: WorkPriority): SourceRequest {
        validateEpisode(episodeId)
        require(origin.scheme in setOf("https", "http") && !origin.host.isNullOrBlank())
        return SourceRequest(origin.resolve(episodeId.remoteKey).toString(), headers = mapOf(
            "User-Agent" to userAgent, "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        ), priority = priority.fetchPriority())
    }

    fun parseDocument(episodeId: EpisodeId, document: SourceDocument, authEpoch: Long): NtkAccessDocument {
        validateEpisode(episodeId)
        require(authEpoch >= 0)
        val url = document.finalUrl
        val origin = URI(url.scheme, url.authority, null, null, null).toString()
        val native = NtkEpisodeDocument(origin, episodeId.remoteKey,
            document.openBody().bufferedReader(Charsets.UTF_8).use { it.readText() },
            responseHeaders = document.responseHeaders, finalUrl = url.toString())
        validateNtkDocumentIdentity(native, episodeId.remoteKey)
        val parsed = parser.manifest(native)
        require(parsed.descriptor != null || parsed.directPages.isNotEmpty()) { "NTK document has no owned page sequence" }
        return NtkAccessDocument(episodeId, document, authEpoch, native, parsed)
    }

    override fun parseEpisode(episodeId: EpisodeId, document: SourceDocument, authEpoch: Long,
        catalogAdjacency: AdjacentEpisodes?): EpisodeAccessPlan =
        complete(parseDocument(episodeId, document, authEpoch), catalogAdjacency = catalogAdjacency)

    fun completeAuthorized(document: NtkAccessDocument, proof: NtkEngineAuthorization,
        catalogAdjacency: AdjacentEpisodes? = null): EpisodeAccessPlan {
        require(proof.episodeId == document.episodeId && proof.documentSha256 == document.sourceDocument.sha256 &&
            proof.documentReplaySha256 == document.sourceDocument.replaySha256 &&
            proof.authEpoch == document.authEpoch) { "Browser proof belongs to another source document" }
        val plan = complete(document, proof.payload, catalogAdjacency)
        return EpisodeAccessPlan(plan.manifest, plan.contentRevision, plan.documentSha256, plan.finalDocumentUrl,
            plan.authEpoch, plan.pages, prerequisites = emptyList(), navigationKnown = plan.navigationKnown)
    }

    /** Captured API payload must prove endpoint, request identity/token and exact numbered sequence. */
    fun complete(document: NtkAccessDocument, protectedPayload: String? = null,
        catalogAdjacency: AdjacentEpisodes? = null): EpisodeAccessPlan {
        val requests = resolvePages(document, protectedPayload)
        val episode = document.episodeId
        val navigation = navigation(document, catalogAdjacency)
        val pageIds = requests.indices.map { PageId.at(episode, it) }
        val revision = revision(episode, requests)
        val recordKind = when {
            protectedPayload != null -> "image-api-page"
            document.descriptor != null -> "viewer-page"
            else -> "dom-sequence"
        }
        val manifest = EpisodeManifest(episode, document.parsed.viewer?.title ?: episode.remoteKey.substringAfterLast('/'),
            pageIds.mapIndexed { index, id -> PageSpec(id, index) }, navigation.previous, navigation.next)
        return EpisodeAccessPlan(manifest, revision, document.sourceDocument.sha256, document.sourceDocument.finalUrl,
            document.authEpoch, requests.mapIndexed { index, request ->
                PageAccessPlan(pageIds[index], "$recordKind:${index + 1}", request.candidates.map { value ->
                    URI(resolveNtkPageUrl(document.sourceDocument.finalUrl, value))
                })
            }, if (document.descriptor == null) emptyList() else listOf(AccessPrerequisite.BrowserAuthorization(episode)),
            navigationKnown = navigation.known)
    }

    private fun resolvePages(document: NtkAccessDocument, payload: String?): List<NtkPageRequest> {
        val pages = if (payload != null) {
            val descriptor = requireNotNull(document.descriptor) { "Unprotected document cannot adopt a protected response" }
            require(descriptor.expectedPageCount != null) { "Protected response needs an authoritative page count" }
            protectedManifest.parse(payload, document.browserDocument, descriptor)
        } else {
            if (!document.directPagesComplete) throw NtkImageManifestRequired(document)
            document.parsed.directPages
        }
        require(pages.isNotEmpty())
        document.descriptor?.expectedPageCount?.let { require(pages.size == it) { "NTK page sequence is incomplete" } }
        return pages
    }

    override fun pageRequest(plan: EpisodeAccessPlan, pageId: PageId, candidateIndex: Int,
        priority: WorkPriority, validation: PageValidation?): SourceRequest {
        validateEpisode(plan.manifest.id)
        val record = plan.page(pageId)
        require(candidateIndex in record.candidates.indices)
        val url = record.candidates[candidateIndex].toString()
        val referer = plan.finalDocumentUrl.toString()
        val headers = buildMap {
            put("User-Agent", userAgent)
            put("Referer", referer)
            put("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            put("Sec-Fetch-Dest", "image")
            put("Sec-Fetch-Mode", "no-cors")
            put("Sec-Fetch-Site", ntkFetchSite(referer, url))
            validation?.entityTag?.let { put("If-None-Match", it) }
            validation?.lastModified?.let { put("If-Modified-Since", it) }
        }
        return SourceRequest(url, headers = headers, priority = priority.fetchPriority(), preferQuic = true)
    }

    private fun navigation(document: NtkAccessDocument, catalog: AdjacentEpisodes?): Navigation {
        val id = document.episodeId
        val viewer = document.parsed.viewer
        val previous = viewer?.previousEpisodePath?.let { EpisodeId(id.seriesId, it) }
        val next = viewer?.nextEpisodePath?.let { EpisodeId(id.seriesId, it) }
        catalog?.let {
            listOfNotNull(it.previous, it.next).forEach { neighbor ->
                validateEpisode(neighbor)
                require(neighbor.seriesId == id.seriesId && neighbor != id)
            }
            if (viewer?.previousKnown == true) require(previous == it.previous) { "Catalog contradicts document's previous episode" }
            if (viewer?.nextKnown == true) require(next == it.next) { "Catalog contradicts document's next episode" }
        }
        return Navigation(if (viewer?.previousKnown == true) previous else catalog?.previous,
            if (viewer?.nextKnown == true) next else catalog?.next,
            (viewer?.previousKnown == true || catalog != null) && (viewer?.nextKnown == true || catalog != null))
    }

    private fun revision(episode: EpisodeId, pages: List<NtkPageRequest>): String {
        val fields = listOf(episode.seriesId.sourceId.value, episode.seriesId.remoteKey, episode.remoteKey) + pages.map { it.url }
        return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun validateEpisode(episode: EpisodeId) {
        require(episode.seriesId.sourceId == sourceId)
        NtkSeriesKey.episodeKey(episode)
    }

    private fun WorkPriority.fetchPriority(): PageFetchPriority = when (this) {
        WorkPriority.FOCUS -> PageFetchPriority.FOCUS
        WorkPriority.VISIBLE -> PageFetchPriority.VISIBLE
        WorkPriority.INTERACTIVE -> PageFetchPriority.NORMAL
        WorkPriority.NEXT_IMAGE -> PageFetchPriority.FORWARD
        WorkPriority.NEXT_EPISODE -> PageFetchPriority.ADJACENT_FORWARD
        WorkPriority.ARTWORK, WorkPriority.OFFLINE -> PageFetchPriority.BACKGROUND
    }
    private data class Navigation(val previous: EpisodeId?, val next: EpisodeId?, val known: Boolean)
}
