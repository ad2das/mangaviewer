package ml.melun.mangaview.engine.content

import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.AccessPrerequisite
import ml.melun.mangaview.engine.api.EngineStoragePort
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.EpisodeDocumentPlanner
import ml.melun.mangaview.engine.api.PreparedPage
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.StoredPageLease
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.SourceResponse

/** Builds one explicit cache -> access -> transfer -> publication graph; owns no executor or queue. */
class EnginePageWork(
    private val principal: String,
    private val planner: EpisodeDocumentPlanner,
    private val transport: SourceTransport,
    private val storage: EngineStoragePort,
    private val prerequisite: (EpisodeAccessPlan, AccessPrerequisite, WorkPriority) -> WorkRequest<Unit>,
) {
    init { require(principal.isNotBlank()) }

    fun request(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority): WorkRequest<StoredPage> {
        require(plan.manifest.id.seriesId.sourceId == planner.sourceId)
        plan.page(pageId)
        val identity = PageWorkIdentity(principal, plan, pageId)
        return identity.request("page", StoredPage::class.java, WorkDomain.CONTROL, priority) { context ->
            val cached = context.dependency(identity.request(
                "lookup", PinnedPage::class.java, WorkDomain.STORAGE, context.priority.value,
                dispose = { it.close() },
            ) { PinnedPage(storage.find(pageId, plan.contentRevision)) })
            cached.page ?: load(context, identity, plan, pageId)
        }
    }

    private suspend fun load(
        context: WorkContext,
        identity: PageWorkIdentity,
        plan: EpisodeAccessPlan,
        pageId: PageId,
    ): StoredPage {
        for (requirement in plan.prerequisites) {
            context.dependency(prerequisite(plan, requirement, context.priority.value))
        }
        val prepared = context.dependency(identity.request(
            "body", PreparedPage::class.java, WorkDomain.BODY, context.priority.value,
            dispose = { storage.discard(it) },
        ) { transfer(it, plan, pageId) })
        val committed = context.dependency(identity.request(
            "publish", PinnedPage::class.java, WorkDomain.STORAGE, context.priority.value,
            dispose = { it.close() },
        ) { PinnedPage(storage.publish(prepared)) })
        return checkNotNull(committed.page)
    }

    private suspend fun transfer(context: WorkContext, plan: EpisodeAccessPlan, pageId: PageId): PreparedPage {
        val candidates = plan.page(pageId).candidates
        var failure: IOException? = null
        for (candidate in candidates.indices) {
            val response = try {
                val request = planner.pageRequest(plan, pageId, candidate, context.priority.value)
                checkedResponse(transport.execute(request))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                // Authentication and throttling are not evidence that an image mirror is missing.
                if (error is PageHttpException && error.statusCode !in setOf(404, 410, 502, 503, 504)) throw error
                failure?.let { if (it !== error) error.addSuppressed(it) }
                failure = error
                continue
            }
            val opened = OpenedPage(response.body, response.contentLength, response.contentType,
                response.header("ETag"), response.header("Last-Modified"))
            return prepareWithPromotion(context, plan, pageId, opened)
        }
        throw checkNotNull(failure)
    }

    private suspend fun prepareWithPromotion(
        context: WorkContext,
        plan: EpisodeAccessPlan,
        pageId: PageId,
        opened: OpenedPage,
    ): PreparedPage {
        var handedToStorage = false
        var prepared: PreparedPage? = null
        try {
            coroutineScope {
                val promotion = launch {
                    context.priority.collect { opened.stream.promote(it.toFetchPriority()) }
                }
                try {
                    handedToStorage = true
                    prepared = storage.prepare(pageId, plan.contentRevision, opened)
                } finally {
                    promotion.cancel()
                }
            }
            return checkNotNull(prepared)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { prepared?.let { storage.discard(it) } } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                if (!handedToStorage) try { opened.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    private fun checkedResponse(response: SourceResponse): SourceResponse {
        if (response.statusCode != 200) {
            val failure = PageHttpException(response.statusCode)
            try { response.close() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
        return response
    }

    private class PinnedPage(private val lease: StoredPageLease?) : Closeable {
        val page: StoredPage? get() = lease?.page
        override fun close() { lease?.close() }
    }
}

class PageHttpException(val statusCode: Int) : IOException("Page request returned HTTP $statusCode")

private class PageWorkIdentity(
    private val principal: String,
    private val plan: EpisodeAccessPlan,
    pageId: PageId,
) {
    private val resource = hashFields(listOf(pageId.episodeId.seriesId.sourceId.value,
        pageId.episodeId.seriesId.remoteKey, pageId.episodeId.remoteKey, pageId.remoteKey))
    // Access documents may change without changing image identity. Never mix authorization plans.
    private val revision = hashFields(listOf(plan.authEpoch.toString(), plan.contentRevision,
        plan.documentSha256, plan.finalDocumentUrl.toString()))

    fun <T : Any> request(
        operation: String,
        type: Class<T>,
        domain: WorkDomain,
        priority: WorkPriority,
        dispose: suspend (T) -> Unit = {},
        execute: suspend (WorkContext) -> T,
    ) = WorkRequest(WorkKey(principal, resource, "content.$operation", revision, type), domain, priority,
        authEpoch = plan.authEpoch, execute = execute, dispose = dispose)

    private fun hashFields(fields: List<String>): String {
        val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private fun WorkPriority.toFetchPriority(): PageFetchPriority = when (this) {
    WorkPriority.FOCUS -> PageFetchPriority.FOCUS
    WorkPriority.VISIBLE -> PageFetchPriority.VISIBLE
    WorkPriority.INTERACTIVE -> PageFetchPriority.NORMAL
    WorkPriority.NEXT_IMAGE -> PageFetchPriority.FORWARD
    WorkPriority.NEXT_EPISODE -> PageFetchPriority.ADJACENT_FORWARD
    WorkPriority.ARTWORK, WorkPriority.OFFLINE -> PageFetchPriority.BACKGROUND
}
