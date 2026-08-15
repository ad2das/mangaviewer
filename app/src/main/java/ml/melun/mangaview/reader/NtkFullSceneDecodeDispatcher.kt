package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Phaser
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Three physical NORMAL-priority lanes; every task owns exactly one source region. */
internal class NtkFullSceneDecodeDispatcher(
    private val eventSink: (Event) -> Boolean,
    private val setCurrentThreadPriority: (Int) -> Unit = { Process.setThreadPriority(it) },
    private val beforeDecode: (Request) -> Unit = {},
    private val afterActiveIncrementForTest: (Int) -> Unit = {},
    laneServicesOverride: Array<ExecutorService>? = null
) : Closeable {
    data class Request(
        val admission: NtkPreparationAdmissionIdentity,
        val leaseId: Long,
        val lease: NtkStrictBodyLease,
        val plan: NtkPreGeometryTilePlan,
        val expectedProof: NtkPreparedOriginalTileProof
    ) {
        init {
            require(leaseId > 0L)
            require(admission.key == plan.key)
            require(admission.pageArtifactDigest == expectedProof.pageArtifactDigest)
            require(plan.tilePlanDigest == expectedProof.tilePlanDigest)
            require(lease.sourceKey == expectedProof.sourceKey)
        }
    }

    sealed interface Event {
        data class Started(
            val request: Request,
            val workerThreadId: Long,
            val actualActiveTasks: Int
        ) : Event
        data class Completed(
            val request: Request,
            val payloadToken: Long,
            val tile: ReaderTile
        ) : Event
        data class Failed(val request: Request, val error: Throwable) : Event
    }

    data class Snapshot(
        val accepting: Boolean,
        val activeTasks: Int,
        val occupiedLanes: Int,
        val activeMax: Int,
        val normalPriorityTaskStarts: Long,
        val backgroundPriorityTaskStarts: Long,
        val threeWideEntryCount: Long,
        val threeWideOverlapNanos: Long,
        val workerThreadIds: Set<Long>,
        val undeliverableEvents: Long
    ) {
        val isDrained: Boolean get() = activeTasks == 0 && occupiedLanes == 0
    }

    // This must remain the same finite topology used by the state machine, CPU transient budget,
    // and prestarted production bootstrap. A wider local value rejects the production override at
    // construction time and strands the prepared next episode before it can ever attach.
    private val laneCount = NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY
    private val initialCohortLanes = laneCount
    private val lanes: Array<ExecutorService> = laneServicesOverride?.also {
        require(it.size == laneCount)
    } ?: Array(laneCount) { lane ->
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ntk-full-scene-decode-$lane")
        }
    }
    private val laneBusy = BooleanArray(laneCount)
    private val activeAccountingLock = Any()
    private val active = AtomicInteger(0)
    private val activeMax = AtomicInteger(0)
    private val normalStarts = AtomicLong(0L)
    private val backgroundStarts = AtomicLong(0L)
    private val threeWideEntries = AtomicLong(0L)
    private val threeWideStarted = AtomicLong(0L)
    private val threeWideOverlap = AtomicLong(0L)
    private val payloadTokens = AtomicLong(1L)
    private val threadIds = ConcurrentHashMap.newKeySet<Long>()
    private val undeliverable = AtomicLong(0L)
    private var accepting = true
    private val drainCompletions = ArrayList<() -> Unit>()

    @Synchronized
    fun startInitialThreeWideCohort(requests: List<Request>): Boolean {
        if (!accepting || requests.size != initialCohortLanes || laneBusy.any { it }) return false
        val gate = Phaser(requests.size)
        var submitted = 0
        try {
            requests.forEachIndexed { lane, request ->
                laneBusy[lane] = true
                lanes[lane].execute { decodeOne(lane, request, gate) }
                submitted++
            }
        } catch (_: RejectedExecutionException) {
            gate.forceTermination()
            for (lane in submitted until laneCount) laneBusy[lane] = false
            return false
        }
        return true
    }

    @Synchronized
    fun start(request: Request): Boolean {
        if (!accepting) return false
        val lane = laneBusy.indexOfFirst { !it }
        if (lane < 0) return false
        laneBusy[lane] = true
        try {
            lanes[lane].execute { decodeOne(lane, request, null) }
        } catch (_: RejectedExecutionException) {
            laneBusy[lane] = false
            return false
        }
        return true
    }

    fun shutdown(completion: (() -> Unit)? = null) {
        val completions = synchronized(this) {
            completion?.let(drainCompletions::add)
            if (accepting) {
                accepting = false
                lanes.forEach(ExecutorService::shutdown)
            }
            takeDrainCompletionsLocked()
        }
        completions.forEach { it() }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        accepting = accepting,
        activeTasks = active.get(),
        occupiedLanes = laneBusy.count { it },
        activeMax = activeMax.get(),
        normalPriorityTaskStarts = normalStarts.get(),
        backgroundPriorityTaskStarts = backgroundStarts.get(),
        threeWideEntryCount = threeWideEntries.get(),
        threeWideOverlapNanos = threeWideOverlap.get(),
        workerThreadIds = threadIds.toSet(),
        undeliverableEvents = undeliverable.get()
    )

    override fun close() = shutdown()

    private fun decodeOne(lane: Int, request: Request, initialCohortGate: Phaser?) {
        val threadId = Thread.currentThread().id
        setCurrentThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        threadIds += threadId
        normalStarts.incrementAndGet()
        val nowActive = enterActiveDecode()
        emit(Event.Started(request, threadId, nowActive))
        var bitmap: Bitmap? = null
        try {
            if (initialCohortGate != null &&
                initialCohortGate.arriveAndAwaitAdvance() < 0
            ) {
                throw RejectedExecutionException("Initial three-wide decode cohort aborted")
            }
            beforeDecode(request)
            val decoder = BitmapRegionDecoder.newInstance(
                request.lease.file.absolutePath,
                false
            ) ?: throw IOException("Cannot open exact source region decoder")
            try {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                    inMutable = false
                    inScaled = false
                }
                bitmap = decoder.decodeRegion(
                    Rect(
                        0,
                        request.plan.sourceTop,
                        request.expectedProof.sourceWidth,
                        request.plan.sourceBottom
                    ),
                    options
                ) ?: throw IOException("Exact source region decode returned null")
            } finally {
                decoder.recycle()
            }
            val value = checkNotNull(bitmap)
            val expectedHeight = request.plan.sourceBottom - request.plan.sourceTop
            if (value.config != Bitmap.Config.ARGB_8888 || value.isMutable ||
                value.width != request.expectedProof.sourceWidth ||
                value.height != expectedHeight
            ) throw NtkTileContractViolationException(
                "Full-scene decode violated ARGB_8888/sample-1/original dimensions"
            )
            val tile = ReaderTile(
                request.plan.sourceTop,
                request.plan.sourceBottom,
                request.expectedProof.sourceWidth,
                request.expectedProof.sourceHeight,
                value
            )
            bitmap = null
            if (!emit(Event.Completed(request, payloadTokens.getAndIncrement(), tile))) {
                if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
            }
        } catch (error: Throwable) {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            emit(Event.Failed(request, error))
        } finally {
            leaveActiveDecode()
            val completions = synchronized(this) {
                check(laneBusy[lane])
                laneBusy[lane] = false
                takeDrainCompletionsLocked()
            }
            completions.forEach { it() }
        }
    }

    private fun enterActiveDecode(): Int = synchronized(activeAccountingLock) {
        val nowActive = active.incrementAndGet()
        check(nowActive in 1..laneCount)
        afterActiveIncrementForTest(nowActive)
        activeMax.updateAndGet { maxOf(it, nowActive) }
        if (nowActive == initialCohortLanes) {
            threeWideEntries.incrementAndGet()
            val startedAt = System.nanoTime().coerceAtLeast(1L)
            check(threeWideStarted.compareAndSet(0L, startedAt))
        }
        nowActive
    }

    private fun leaveActiveDecode() = synchronized(activeAccountingLock) {
        val before = active.get()
        check(before in 1..laneCount)
        if (before == initialCohortLanes) {
            val started = threeWideStarted.getAndSet(0L)
            check(started > 0L)
            threeWideOverlap.addAndGet(
                (System.nanoTime() - started).coerceAtLeast(1L)
            )
        }
        check(active.decrementAndGet() == before - 1)
    }

    private fun emit(event: Event): Boolean {
        val accepted = runCatching { eventSink(event) }.getOrDefault(false)
        if (!accepted) undeliverable.incrementAndGet()
        return accepted
    }

    private fun takeDrainCompletionsLocked(): List<() -> Unit> {
        if (accepting || active.get() != 0 || laneBusy.any { it } || drainCompletions.isEmpty()) {
            return emptyList()
        }
        return drainCompletions.toList().also { drainCompletions.clear() }
    }
}
