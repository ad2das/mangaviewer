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
import ml.melun.mangaview.core.PageDimensions
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
import ml.melun.mangaview.engine.api.WorkMetadata
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

    private fun note(phase: String, startedAtNanos: Long, detail: String = "") {
        val observer = observer ?: return
        val elapsedMs = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L
        observer("$phase elapsedMs=$elapsedMs $detail")
    }

    fun request(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority): WorkRequest<StoredPage> {
        require(plan.manifest.id.seriesId.sourceId == planner.sourceId)
        plan.page(pageId)
        val identity = PageWorkIdentity(principal, plan, pageId)
        return identity.request("page", StoredPage::class.java, WorkDomain.CONTROL, priority,
            finishWhenOrphaned = true) { context ->
            val startedAtNanos = System.nanoTime()
            note("page-start", startedAtNanos, "priority=$priority candidates=${plan.page(pageId).candidates.size}")
            val cached = context.dependency(identity.request(
                "lookup", PinnedPage::class.java, WorkDomain.STORAGE_READ, context.priority.value,
                dispose = { it.close() },
            ) { PinnedPage(storage.find(pageId, plan.contentRevision)) })
            val result = cached.page ?: if (plan.localOnly) throw IOException("Complete cached episode is no longer available")
                else load(context, identity, plan, pageId)
            note("page-done", startedAtNanos, "cached=${cached.page != null}")
            result
        }
    }

    private suspend fun load(
        context: WorkContext,
        identity: PageWorkIdentity,
        plan: EpisodeAccessPlan,
        pageId: PageId,
    ): StoredPage {
        val loadStartedAtNanos = System.nanoTime()
        for (requirement in plan.prerequisites) {
            context.dependency(prerequisite(plan, requirement, context.priority.value))
        }
        val prepared = context.dependency(identity.request(
            "body", PreparedPage::class.java, WorkDomain.BODY, context.priority.value,
            dispose = { storage.discard(it) },
        ) { transfer(it, plan, pageId) { dimensions ->
            context.publishMetadata(WorkMetadata.PageGeometry(pageId, plan.contentRevision, dimensions))
        } })
        note("body-done", loadStartedAtNanos)
        val committed = context.dependency(identity.request(
            "publish", PinnedPage::class.java, WorkDomain.STORAGE_PUBLISH, context.priority.value,
            dispose = { it.close() },
        ) { PinnedPage(storage.publish(prepared)) })
        note("published", loadStartedAtNanos, "bytes=${committed.page?.byteCount ?: -1L}")
        return checkNotNull(committed.page)
    }

    private suspend fun transfer(context: WorkContext, plan: EpisodeAccessPlan, pageId: PageId,
        reportGeometry: suspend (PageDimensions) -> Unit,
    ): PreparedPage {
        val candidates = plan.page(pageId).candidates
        // Every candidate must be attempted and every attempt must have answered 404/410 before the
        // provider's silence is definitive: a 5xx, a transport failure or a body that died mid-stream
        // is transient, never evidence that the original does not exist.
        val failures = TransferFailures()
        for (candidate in candidates.indices) {
            attemptTransfer(context, plan, pageId, candidate, reportGeometry, failures)?.let { return it }
        }
        val last = checkNotNull(failures.last)
        System.err.println("EnginePageWork page-failed id=$pageId error=${last.message}")
        throw if (failures.definitiveMiss) last.asDefinitiveMiss() else last
    }

    private suspend fun attemptTransfer(
        context: WorkContext,
        plan: EpisodeAccessPlan,
        pageId: PageId,
        candidate: Int,
        reportGeometry: suspend (PageDimensions) -> Unit,
        failures: TransferFailures,
    ): PreparedPage? {
        val opened = try {
            openPage(plan, pageId, candidate, context.priority.value, freshRoute = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            // Authentication and throttling are not evidence that an image mirror is missing.
            if (error is PageHttpException && error.statusCode !in setOf(404, 410, 502, 503, 504)) throw error
            failures.recordOpenFailure(error)
            return null
        }
        try {
            return prepareWithPromotion(context, plan, pageId, opened, reportGeometry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            // The connection can die while the body streams (an HTTP/2 reset): that is not the
            // mirror's answer, so this candidate gets one fresh-connection attempt before the
            // next mirror. prepareWithPromotion already released the failed body.
            failures.recordTransient(error)
            System.err.println("EnginePageWork page-body-fail id=$pageId candidate=$candidate " +
                "url=${planner.pageRequest(plan, pageId, candidate, context.priority.value).url} error=${error.message}")
        }
        val retried = try {
            openPage(plan, pageId, candidate, context.priority.value, freshRoute = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            failures.recordOpenFailure(error)
            return null
        }
        return try {
            prepareWithPromotion(context, plan, pageId, retried, reportGeometry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            failures.recordTransient(error)
            null
        }
    }

    /** Opens one candidate; [freshRoute] retries it over a new connection instead of the pooled one. */
    private suspend fun openPage(
        plan: EpisodeAccessPlan,
        pageId: PageId,
        candidate: Int,
        priority: WorkPriority,
        freshRoute: Boolean,
    ): OpenedPage {
        val request = planner.pageRequest(plan, pageId, candidate, priority)
        val response = checkedResponse(if (freshRoute) transport.executeOnFreshRoute(request) else transport.execute(request))
        return OpenedPage(response.body, response.contentLength, response.contentType,
            response.header("ETag"), response.header("Last-Modified"))
    }

    /** Accumulates candidate failures across one transfer, keeping the newest as the primary. */
    private class TransferFailures {
        var last: IOException? = null
        var definitiveMiss = true

        fun recordOpenFailure(error: IOException) {
            if (error !is PageHttpException || error.statusCode !in MISSING_PAGE_STATUSES) definitiveMiss = false
            record(error)
        }

        fun recordTransient(error: IOException) {
            definitiveMiss = false
            record(error)
        }

        private fun record(error: IOException) {
            val previous = last
            if (previous != null && previous !== error) error.addSuppressed(previous)
            last = error
        }
    }

    /**
     * Re-keys a 404/410 failure as [PageMissingException] without losing the accumulated suppressed
     * chain: the message and the full attempt history stay exactly what they were.
     */
    private fun IOException.asDefinitiveMiss(): IOException {
        val http = this as? PageHttpException ?: return this
        return PageMissingException(http.statusCode).also { missing ->
            http.suppressed?.forEach(missing::addSuppressed)
        }
    }

    private suspend fun prepareWithPromotion(
        context: WorkContext,
        plan: EpisodeAccessPlan,
        pageId: PageId,
        opened: OpenedPage,
        reportGeometry: suspend (PageDimensions) -> Unit,
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
                    prepared = storage.prepareWithGeometry(pageId, plan.contentRevision, opened, reportGeometry)
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

    /** Chains a suppressed cause without ever replacing the newest failure. */
    private fun suppressed(previous: IOException?, error: IOException): IOException {
        previous?.let { if (it !== error) error.addSuppressed(it) }
        return error
    }

    private class PinnedPage(private val lease: StoredPageLease?) : Closeable {
        val page: StoredPage? get() = lease?.page
        override fun close() { lease?.close() }
    }

    companion object {
        /** Optional process-local timing hook; the app wires it to logcat and engine-v2 stays JVM-only. */
        @Volatile var observer: ((String) -> Unit)? = null
    }
}

open class PageHttpException(val statusCode: Int) : IOException("Page request returned HTTP $statusCode")

/**
 * Every candidate was attempted and every attempt answered 404/410: the provider has no original
 * for this page. A definitive miss lets the reader treat the page as unavailable at the first
 * round trip instead of waiting out the transient-failure bound.
 */
class PageMissingException(statusCode: Int) : PageHttpException(statusCode)

private val MISSING_PAGE_STATUSES = setOf(404, 410)

private class PageWorkIdentity(
    private val principal: String,
    private val plan: EpisodeAccessPlan,
    pageId: PageId,
) {
    private val resource = hashFields(listOf(pageId.episodeId.seriesId.sourceId.value,
        pageId.episodeId.seriesId.remoteKey, pageId.episodeId.remoteKey, pageId.remoteKey))
    // Access documents may change without changing image identity. Never mix authorization plans.
    private val revision = hashFields(listOf(plan.authEpoch.toString(), plan.contentRevision,
        plan.documentSha256, plan.finalDocumentUrl.toString(), plan.localOnly.toString()))

    fun <T : Any> request(
        operation: String,
        type: Class<T>,
        domain: WorkDomain,
        priority: WorkPriority,
        dispose: suspend (T) -> Unit = {},
        finishWhenOrphaned: Boolean = false,
        execute: suspend (WorkContext) -> T,
    ) = WorkRequest(WorkKey(principal, resource, "content.$operation", revision, type), domain, priority,
        authEpoch = plan.authEpoch, execute = execute, dispose = dispose,
        finishWhenOrphaned = finishWhenOrphaned)

    /**
     * Identical digest to hashing the concatenated `"${length}:$field"` sequence, but each field
     * is fed straight into the digest so no large joined String or intermediate byte array is
     * materialised. This path runs per work request, so the temporaries are pure young-gen churn.
     */
    private fun hashFields(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            digest.update(field.length.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(field.toByteArray(Charsets.UTF_8))
        }
        val bytes = digest.digest()
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 255
                append(alphabet[value ushr 4])
                append(alphabet[value and 15])
            }
        }
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
