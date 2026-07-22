package ml.melun.mangaview.reader

import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated retained-body capability opener. It owns no network path and cannot consume a decoder
 * lane. Actor-side callers retain/release one page lease through an external exact ref ledger.
 */
internal class NtkBodyLeaseDispatcher(
    private val eventSink: (Event) -> Boolean,
    laneCount: Int = NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY,
    private val setCurrentThreadPriority: (Int) -> Unit = { Process.setThreadPriority(it) },
    serviceOverride: ExecutorService? = null
) : Closeable {
    data class OpenRequest(
        val requestId: Long,
        val pageIndex: Int,
        val canonicalAsset: String,
        val descriptor: NtkStrictBodyDescriptor
    ) {
        init {
            require(requestId > 0L)
            require(pageIndex >= 0)
            require(canonicalAsset.isNotBlank())
            require(descriptor.sourceKey.pageIndex == pageIndex)
            require(descriptor.metadata.canonicalAsset == canonicalAsset)
        }
    }

    data class OpenedLease(
        val leaseId: Long,
        val request: OpenRequest,
        val lease: NtkStrictBodyLease
    ) {
        init {
            require(leaseId > 0L)
            require(lease.sourceKey == request.descriptor.sourceKey)
            require(lease.metadata == request.descriptor.metadata)
            require(lease.proof == request.descriptor.proof)
        }
    }

    sealed interface Event {
        data class LeaseOpened(val opened: OpenedLease) : Event
        data class LeaseOpenFailed(val request: OpenRequest, val error: Throwable) : Event
    }

    enum class OfferResult {
        ACCEPTED,
        CLOSED,
        CAPACITY_EXHAUSTED,
        DUPLICATE_REQUEST,
        DUPLICATE_PAGE
    }

    data class Snapshot(
        val accepting: Boolean,
        val openingCount: Int,
        val openCount: Int,
        val activeOpeners: Int,
        val maxOpeningOrOpen: Int,
        val activePageIndexes: Set<Int>,
        val undeliverableEvents: Long
    ) {
        val isDrained: Boolean
            get() = openingCount == 0 && openCount == 0 && activeOpeners == 0
    }

    private val laneCount: Int = laneCount.also {
        require(it == NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY)
    }
    private val service: ExecutorService = serviceOverride
        ?: Executors.newFixedThreadPool(laneCount) { runnable ->
            Thread(runnable, "ntk-strip-body-lease")
        }
    private val nextLeaseId = AtomicLong(1L)
    private val actualActiveOpeners = AtomicInteger(0)
    private val undeliverableEvents = AtomicLong(0L)
    private val openingByRequest = LinkedHashMap<Long, OpenRequest>()
    private val openingRequestByPage = LinkedHashMap<Int, Long>()
    private val completedByRequest = LinkedHashMap<Long, OpenedLease>()
    private val openByLease = LinkedHashMap<Long, OpenedLease>()
    private val openLeaseByPage = LinkedHashMap<Int, Long>()
    private var accepting = true
    private var maxOpeningOrOpen = 0
    private val drainCompletions = ArrayList<() -> Unit>()

    @Synchronized
    fun open(request: OpenRequest): OfferResult {
        if (!accepting) return OfferResult.CLOSED
        if (request.requestId in openingByRequest || request.requestId in completedByRequest) {
            return OfferResult.DUPLICATE_REQUEST
        }
        if (request.pageIndex in openingRequestByPage || request.pageIndex in openLeaseByPage) {
            return OfferResult.DUPLICATE_PAGE
        }
        if (openingByRequest.size + openByLease.size >= laneCount) {
            return OfferResult.CAPACITY_EXHAUSTED
        }
        openingByRequest[request.requestId] = request
        openingRequestByPage[request.pageIndex] = request.requestId
        recordMaximum()
        try {
            service.execute { runOpen(request) }
        } catch (error: RejectedExecutionException) {
            openingByRequest.remove(request.requestId)
            openingRequestByPage.remove(request.pageIndex)
            return OfferResult.CLOSED
        }
        return OfferResult.ACCEPTED
    }

    @Synchronized
    fun acknowledgeOpened(requestId: Long, leaseId: Long): OpenedLease? {
        val opened = completedByRequest.remove(requestId) ?: return null
        if (opened.leaseId != leaseId || openingByRequest.remove(requestId) != opened.request) {
            runCatching(opened.lease.release)
            throw IllegalStateException("Body lease completion identity mismatch")
        }
        check(openingRequestByPage.remove(opened.request.pageIndex) == requestId)
        check(openByLease.put(opened.leaseId, opened) == null)
        check(openLeaseByPage.put(opened.request.pageIndex, opened.leaseId) == null)
        recordMaximum()
        return opened
    }

    @Synchronized
    fun acknowledgeFailed(requestId: Long): OpenRequest? {
        if (requestId in completedByRequest) return null
        val request = openingByRequest.remove(requestId) ?: return null
        check(openingRequestByPage.remove(request.pageIndex) == requestId)
        dispatchDrainCompletionsLocked()
        return request
    }

    @Synchronized
    fun release(leaseId: Long): Boolean {
        val opened = openByLease.remove(leaseId) ?: return false
        check(openLeaseByPage.remove(opened.request.pageIndex) == leaseId)
        runCatching(opened.lease.release).getOrThrow()
        dispatchDrainCompletionsLocked()
        return true
    }

    fun shutdown(completion: (() -> Unit)? = null) {
        val completions = synchronized(this) {
            completion?.let(drainCompletions::add)
            if (accepting) {
                accepting = false
                service.shutdown()
            }
            takeDrainCompletionsLocked()
        }
        completions.forEach { it() }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        accepting = accepting,
        openingCount = openingByRequest.size,
        openCount = openByLease.size,
        activeOpeners = actualActiveOpeners.get(),
        maxOpeningOrOpen = maxOpeningOrOpen,
        activePageIndexes = (openingRequestByPage.keys + openLeaseByPage.keys).toSet(),
        undeliverableEvents = undeliverableEvents.get()
    )

    override fun close() = shutdown()

    private fun runOpen(request: OpenRequest) {
        val active = actualActiveOpeners.incrementAndGet()
        check(active in 1..laneCount)
        var opened: OpenedLease? = null
        val event = try {
            setCurrentThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            val lease = request.descriptor.openLease()
            if (lease.sourceKey != request.descriptor.sourceKey ||
                lease.metadata != request.descriptor.metadata ||
                lease.proof != request.descriptor.proof ||
                !lease.file.isFile || lease.file.length() <= 0L ||
                lease.sourceWidth != request.descriptor.metadata.sourceWidth ||
                lease.sourceHeight != request.descriptor.metadata.sourceHeight
            ) {
                runCatching(lease.release)
                throw IOException("Invalid retained body lease at page ${request.pageIndex}")
            }
            opened = OpenedLease(nextLeaseId.getAndIncrement(), request, lease)
            synchronized(this) { completedByRequest[request.requestId] = checkNotNull(opened) }
            Event.LeaseOpened(checkNotNull(opened))
        } catch (error: Throwable) {
            Event.LeaseOpenFailed(request, error)
        } finally {
            val remaining = actualActiveOpeners.decrementAndGet()
            check(remaining >= 0)
        }
        if (!emit(event)) {
            opened?.let { runCatching(it.lease.release) }
            synchronized(this) {
                completedByRequest.remove(request.requestId)
                openingByRequest.remove(request.requestId)
                openingRequestByPage.remove(request.pageIndex)
            }
        }
        val completions = synchronized(this) { takeDrainCompletionsLocked() }
        completions.forEach { it() }
    }

    private fun emit(event: Event): Boolean {
        val accepted = try {
            eventSink(event)
        } catch (_: Throwable) {
            false
        }
        if (!accepted) undeliverableEvents.incrementAndGet()
        return accepted
    }

    private fun recordMaximum() {
        val active = openingByRequest.size + openByLease.size
        check(active <= laneCount)
        maxOpeningOrOpen = maxOf(maxOpeningOrOpen, active)
    }

    private fun dispatchDrainCompletionsLocked() {
        takeDrainCompletionsLocked().forEach { it() }
    }

    private fun takeDrainCompletionsLocked(): List<() -> Unit> {
        if (accepting || actualActiveOpeners.get() != 0 || openingByRequest.isNotEmpty() ||
            openByLease.isNotEmpty() || drainCompletions.isEmpty()
        ) return emptyList()
        return drainCompletions.toList().also { drainCompletions.clear() }
    }
}
