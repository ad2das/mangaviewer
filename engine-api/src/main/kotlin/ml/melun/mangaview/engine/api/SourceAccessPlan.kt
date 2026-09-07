package ml.melun.mangaview.engine.api

import java.net.URI
import java.util.Collections
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.source.SourceRequest

/** One source record owns its alternatives. Equal addresses on two records remain two pages. */
class PageAccessPlan(
    val pageId: PageId,
    val sourceRecord: String,
    candidates: List<URI>,
) {
    val candidates: List<URI> = Collections.unmodifiableList(candidates.distinct())

    init {
        require(sourceRecord.isNotBlank() && candidates.isNotEmpty())
        require(candidates.all { it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() })
    }

    override fun toString(): String = "PageAccessPlan(pageId=" + pageId +
        ", alternativeCount=" + candidates.size + ")"
}

enum class AckReplayPolicy { IDEMPOTENT, CONFIRM_BEFORE_REPLAY }

sealed interface AccessPrerequisite {
    class HttpAck(
        val key: String,
        val request: SourceRequest,
        val replayPolicy: AckReplayPolicy,
    ) : AccessPrerequisite {
        init { require(key.isNotBlank()) }
        override fun toString(): String = "HttpAck(replayPolicy=" + replayPolicy + ")"
    }

    data class BrowserAuthorization(val episodeId: EpisodeId) : AccessPrerequisite
}

class EpisodeAccessPlan(
    manifest: EpisodeManifest,
    val contentRevision: String,
    val documentSha256: String,
    val finalDocumentUrl: URI,
    val authEpoch: Long,
    pages: List<PageAccessPlan>,
    prerequisites: List<AccessPrerequisite> = emptyList(),
    val navigationKnown: Boolean = true,
) {
    val manifest: EpisodeManifest = manifest.copy(
        pages = Collections.unmodifiableList(manifest.pages.toList()),
    )
    val pages: List<PageAccessPlan> = Collections.unmodifiableList(pages.toList())
    val prerequisites: List<AccessPrerequisite> = Collections.unmodifiableList(prerequisites.toList())

    init {
        require(contentRevision.isNotBlank() && authEpoch >= 0L)
        require(documentSha256.matches(Regex("[0-9a-f]{64}")))
        require(finalDocumentUrl.scheme in setOf("https", "http") && !finalDocumentUrl.host.isNullOrBlank())
        require(pages.map { it.pageId } == manifest.pages.map { it.id }) {
            "Access records must match the manifest exactly, including order"
        }
        require(pages.map { it.sourceRecord }.distinct().size == pages.size) {
            "A source record cannot own multiple manifest pages"
        }
        require(prerequisites.filterIsInstance<AccessPrerequisite.BrowserAuthorization>()
            .all { it.episodeId == manifest.id })
        val ackKeys = prerequisites.filterIsInstance<AccessPrerequisite.HttpAck>().map { it.key }
        require(ackKeys.distinct().size == ackKeys.size)
    }

    fun page(id: PageId): PageAccessPlan = pages.firstOrNull { it.pageId == id }
        ?: throw IllegalArgumentException("Page is not owned by this access plan")

    override fun toString(): String = "EpisodeAccessPlan(episodeId=" + manifest.id +
        ", pageCount=" + pages.size + ", authEpoch=" + authEpoch + ")"
}
