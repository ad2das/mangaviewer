package ml.melun.mangaview.reader

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.BitSet
import java.util.TreeMap

private class NtkPrestartedExecutionLane(
    threadCount: Int,
    threadName: (Int) -> String,
    private val submittedTasks: AtomicLong,
    startedThreads: AtomicInteger
) : ThreadPoolExecutor(
    threadCount,
    threadCount,
    0L,
    TimeUnit.MILLISECONDS,
    LinkedBlockingQueue(),
    object : java.util.concurrent.ThreadFactory {
        private val sequence = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread {
            val index = sequence.getAndIncrement()
            return Thread(
                runnable,
                threadName(index)
            ).apply { priority = Thread.NORM_PRIORITY }
        }
    }
) {
    init {
        // ThreadPoolExecutor reports only workers whose Thread.start() succeeded. Record that
        // synchronous prestart contract instead of racing the later worker-run entry point.
        startedThreads.addAndGet(prestartAllCoreThreads())
    }

    override fun execute(command: Runnable) {
        submittedTasks.incrementAndGet()
        super.execute(command)
    }
}

internal class NtkFullSceneExecutionBootstrap : Closeable {
    internal data class Engines(
        val actor: ExecutorService,
        val bodyLease: ExecutorService,
        val decodeLanes: Array<ExecutorService>
    )

    private enum class State { READY, ADOPTED, CLOSED }

    private val lock = Any()
    private val startedThreads = AtomicInteger()
    private val submittedTasks = AtomicLong()
    private var state = State.READY
    private val actor: ExecutorService = NtkPrestartedExecutionLane(
        1,
        { "ntk-full-scene-actor" },
        submittedTasks,
        startedThreads
    )
    private val bodyLease: ExecutorService = NtkPrestartedExecutionLane(
        NtkRollingResidencyConstants.PRE_STAGE_DECODE_CONCURRENCY,
        { "ntk-strip-body-lease-$it" },
        submittedTasks,
        startedThreads
    )
    private val decodeLanes: Array<ExecutorService> = Array(3) { lane ->
        NtkPrestartedExecutionLane(
            1,
            { "ntk-full-scene-decode-$lane" },
            submittedTasks,
            startedThreads
        )
    }

    fun adopt(): Engines = synchronized(lock) {
        check(state == State.READY) { "Full-scene bootstrap is not single-use" }
        state = State.ADOPTED
        Engines(actor, bodyLease, decodeLanes)
    }

    fun startedThreadCount(): Int = startedThreads.get()

    fun submittedTaskCount(): Long = submittedTasks.get()

    fun isAdopted(): Boolean = synchronized(lock) { state == State.ADOPTED }

    override fun close() {
        synchronized(lock) {
            if (state != State.READY) return
            state = State.CLOSED
            decodeLanes.forEach(ExecutorService::shutdownNow)
            bodyLease.shutdownNow()
            actor.shutdownNow()
        }
    }
}

/**
 * A sorted, non-overlapping union of content intervals.
 *
 * Its size follows distinct uncovered gaps in the finite episode, rather than the number of
 * presented frames.  This is the important distinction for a long reader session: repeatedly
 * presenting the same (or adjacent) pixels is allocation-free after the union has converged.
 */
internal class NtkMergedPresentedCoverage {
    private val intervalsByStart = TreeMap<Long, Long>()

    val intervalCount: Int
        get() = intervalsByStart.size

    fun add(startPx: Long, endPx: Long) {
        require(startPx >= 0L && endPx > startPx)
        var mergedStart = startPx
        var mergedEnd = endPx

        val preceding = intervalsByStart.floorEntry(mergedStart)
        if (preceding != null && preceding.value >= mergedStart) {
            mergedStart = preceding.key
            mergedEnd = maxOf(mergedEnd, preceding.value)
            intervalsByStart.remove(preceding.key)
        }

        var following = intervalsByStart.ceilingEntry(mergedStart)
        while (following != null && following.key <= mergedEnd) {
            mergedEnd = maxOf(mergedEnd, following.value)
            intervalsByStart.remove(following.key)
            following = intervalsByStart.ceilingEntry(mergedStart)
        }
        intervalsByStart[mergedStart] = mergedEnd
    }

    fun addAll(intervals: Iterable<NtkPresentedContentInterval>) {
        intervals.forEach { add(it.startPx, it.endPx) }
    }

    fun covers(startPx: Long, endPx: Long): Boolean {
        if (endPx <= startPx) return false
        val containing = intervalsByStart.floorEntry(startPx) ?: return false
        return containing.value >= endPx
    }

    fun snapshot(): List<NtkPresentedContentInterval> = intervalsByStart.entries.map {
        NtkPresentedContentInterval(it.key, it.value)
    }

    fun clear() = intervalsByStart.clear()
}

internal data class NtkViewportProofBatch(
    val surfaceEpoch: Long,
    val hasMixedSurfaceEpochs: Boolean,
    val hasBindingSeed: Boolean,
    val hasMissingProof: Boolean,
    val hasInvalidContentInterval: Boolean,
    val hasInvalidPageRange: Boolean,
    val offerCount: Long,
    val viewportDefectCount: Long,
    val runwayDefectCount: Long,
    val presentedIntervals: List<NtkPresentedContentInterval>,
    val presentedPages: BitSet
)

/** Unchecked producer evidence; validation belongs to the actor-owned proof transition. */
internal data class NtkViewportProofEvidence(
    val surfaceEpoch: Long,
    val isBindingSeed: Boolean,
    val proofPresent: Boolean,
    val visibleContentStartPx: Long,
    val visibleContentEndPx: Long,
    val firstVisiblePage: Int,
    val lastVisiblePage: Int,
    val viewportOriginalComplete: Boolean,
    val runwayOriginalComplete: Boolean
) {
    companion object {
        fun from(sample: NtkViewportSample): NtkViewportProofEvidence {
            val proof = sample.presentedProof
            return NtkViewportProofEvidence(
                surfaceEpoch = sample.surfaceEpoch,
                isBindingSeed = sample.isBindingSeed,
                proofPresent = proof != null,
                visibleContentStartPx = proof?.visibleContentStartPx ?: 0L,
                visibleContentEndPx = proof?.visibleContentEndPx ?: 0L,
                firstVisiblePage = proof?.firstVisiblePage ?: 0,
                lastVisiblePage = proof?.lastVisiblePage ?: 0,
                viewportOriginalComplete = proof?.viewportOriginalComplete ?: false,
                runwayOriginalComplete = proof?.runwayOriginalComplete ?: false
            )
        }
    }
}

/**
 * Lossless single-flight mailbox for native viewport proof samples.
 *
 * Producers merge intervals, page coverage, and counters under a small lock.  At most one actor
 * runnable is queued or draining.  The drain keeps the scheduled claim while invoking the
 * consumer and checks the pending aggregate again before releasing it, closing the usual
 * offer-versus-drain lost-wakeup race.
 */
internal class NtkViewportProofMailbox(
    private val pageCount: Int,
    private val scheduleDrain: (Runnable) -> Boolean,
    private val consumeBatch: (NtkViewportProofBatch) -> Unit,
    private val onScheduleFailure: (Throwable) -> Unit = {},
    private val onDrainFailure: (Throwable) -> Unit = {}
) {
    init {
        require(pageCount >= 0)
    }

    private class Pending(private val pageCount: Int) {
        var surfaceEpoch = 0L
        var hasMixedSurfaceEpochs = false
        var hasBindingSeed = false
        var hasMissingProof = false
        var hasInvalidContentInterval = false
        var hasInvalidPageRange = false
        var offerCount = 0L
        var viewportDefectCount = 0L
        var runwayDefectCount = 0L
        val intervals = NtkMergedPresentedCoverage()
        val pages = BitSet(pageCount)

        val isEmpty: Boolean
            get() = offerCount == 0L

        fun add(evidence: NtkViewportProofEvidence) {
            offerCount = Math.addExact(offerCount, 1L)
            if (surfaceEpoch == 0L) surfaceEpoch = evidence.surfaceEpoch
            else if (surfaceEpoch != evidence.surfaceEpoch) hasMixedSurfaceEpochs = true
            if (evidence.isBindingSeed) hasBindingSeed = true

            if (!evidence.proofPresent) {
                hasMissingProof = true
                return
            }
            if (evidence.visibleContentStartPx < 0L ||
                evidence.visibleContentEndPx <= evidence.visibleContentStartPx
            ) {
                hasInvalidContentInterval = true
            } else {
                intervals.add(evidence.visibleContentStartPx, evidence.visibleContentEndPx)
            }
            if (evidence.firstVisiblePage < 0 ||
                evidence.lastVisiblePage < evidence.firstVisiblePage ||
                evidence.lastVisiblePage >= pageCount
            ) {
                hasInvalidPageRange = true
            } else {
                pages.set(evidence.firstVisiblePage, evidence.lastVisiblePage + 1)
            }
            if (!evidence.viewportOriginalComplete) {
                viewportDefectCount = Math.addExact(viewportDefectCount, 1L)
            }
            if (!evidence.runwayOriginalComplete) {
                runwayDefectCount = Math.addExact(runwayDefectCount, 1L)
            }
        }

        fun snapshot(): NtkViewportProofBatch = NtkViewportProofBatch(
            surfaceEpoch = surfaceEpoch,
            hasMixedSurfaceEpochs = hasMixedSurfaceEpochs,
            hasBindingSeed = hasBindingSeed,
            hasMissingProof = hasMissingProof,
            hasInvalidContentInterval = hasInvalidContentInterval,
            hasInvalidPageRange = hasInvalidPageRange,
            offerCount = offerCount,
            viewportDefectCount = viewportDefectCount,
            runwayDefectCount = runwayDefectCount,
            presentedIntervals = intervals.snapshot(),
            presentedPages = pages.clone() as BitSet
        )
    }

    private val lock = Any()
    private var accepting = true
    private var drainScheduled = false
    private var pending = Pending(pageCount)
    private val drainRunnable = Runnable(::drainScheduledBatches)

    fun offer(sample: NtkViewportSample): Boolean = offerEvidence(
        NtkViewportProofEvidence.from(sample)
    )

    internal fun offerEvidence(evidence: NtkViewportProofEvidence): Boolean {
        val shouldSchedule = synchronized(lock) {
            if (!accepting) return false
            pending.add(evidence)
            if (drainScheduled) false else {
                drainScheduled = true
                true
            }
        }
        if (!shouldSchedule) return true
        val scheduled = runCatching { scheduleDrain(drainRunnable) }.getOrDefault(false)
        if (!scheduled) {
            synchronized(lock) {
                accepting = false
                pending = Pending(pageCount)
                drainScheduled = false
            }
            onScheduleFailure(
                RejectedExecutionException("Viewport proof actor rejected its single drain")
            )
        }
        return scheduled
    }

    /** Actor-thread barrier used immediately before an explicit proof snapshot. */
    fun flushPendingOnConsumerThread() {
        while (true) {
            val batch = synchronized(lock) { takePendingLocked() } ?: return
            consumeBatch(batch)
        }
    }

    fun cancel() {
        synchronized(lock) {
            accepting = false
            pending = Pending(pageCount)
            drainScheduled = false
            // Retirement deliberately discards proof that was not consumed before the explicit
            // terminal-snapshot barrier. A previously queued runnable observes empty and exits;
            // accepting=false prevents it or any later producer from scheduling another drain.
        }
    }

    internal fun hasScheduledDrainForTesting(): Boolean = synchronized(lock) { drainScheduled }

    private fun drainScheduledBatches() {
        try {
            while (true) {
                val batch = synchronized(lock) {
                    val next = takePendingLocked()
                    if (next == null) drainScheduled = false
                    next
                } ?: return
                consumeBatch(batch)
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                accepting = false
                pending = Pending(pageCount)
                drainScheduled = false
            }
            onDrainFailure(error)
        }
    }

    private fun takePendingLocked(): NtkViewportProofBatch? {
        if (pending.isEmpty) return null
        val batch = pending.snapshot()
        pending = Pending(pageCount)
        return batch
    }
}

/** Executes [NtkFullScenePreparationMachine] against the production source/decoder/native ports. */
internal class NtkFullScenePreparationRunner(
    private val owner: NtkEpisodeStripPipeline,
    private val preparationGeneration: Long,
    private val sourceTransport: NtkEpisodeStripPipeline.SourceTransport,
    private val controllerPort: NtkEpisodeStripPipeline.ControllerPort,
    private val listener: NtkEpisodeStripPipeline.Listener,
    private val cpuTransientPolicyBytes: Long,
    private val manifestSeal: NtkEpisodeManifestSeal,
    private val initialPageIndex: Int,
    private val initialPageOffsetPx: Int,
    executionBootstrap: NtkFullSceneExecutionBootstrap
) : Closeable {
    private val episode = NtkEpisodeToken(owner.authority)
    private val strictSource = sourceTransport as? NtkStrictSourceTransport
        ?: error("Full-scene preparation requires strict episode source transport")
    private val executionEngines = executionBootstrap.adopt()
    private val actor: ExecutorService = executionEngines.actor
    private val machine = NtkFullScenePreparationMachine()
    private val retired = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val nextStageNonce = AtomicLong(1L)
    private var detachedPreparationPort: NtkEpisodeStripPipeline.DetachedPreparationPort? = null
    private var detachedOpenRequested = false
    private var geometrySeed: NtkPreparationGeometrySeed? = null
    private var surfaceIdentity: NtkPublishedSurfaceIdentity? = null
    private var revokedSurfaceIdentity: NtkPublishedSurfaceIdentity? = null
    private var revokedCrossedStageBoundary = false
    private var state: NtkFullScenePreparationSnapshot? = null
    private var sourceBinding: Closeable? = null
    private var sourceDrainRequested = false
    private var stageProof: NtkStageProof? = null
    private var latestProof: NtkEpisodeProofSnapshot? = null
    private var active = false
    private var peakCpuReserved = 0L
    private var peakCpuDecoded = 0L
    private var peakCpuCharged = 0L
    private var preSubmitViewportGap = 0L
    private var viewportOffers = 0L
    private var viewportDefects = 0L
    private var runwayDefects = 0L
    private val presentedPages = BitSet(manifestSeal.pageCount)
    private val presentedIntervals = NtkMergedPresentedCoverage()
    private var cachedGeometryTileKeys: Set<NtkStripTileKey>? = null
    private var wholeContentCoverageObserved = false
    private var viewportDefectObserved = false
    private var runwayDefectObserved = false
    private val payloads = LinkedHashMap<Long, ReaderTile>()
    private val uploadingTiles = ConcurrentHashMap<NtkNativeInstallIdentity, ReaderTile>()
    private val leases = LinkedHashMap<Long, NtkBodyLeaseDispatcher.OpenedLease>()
    private var preparationCloseRequested = false
    private var preparationCloseCompleted = false
    private var terminalCleanupStarted = false
    private var terminalCleanupCompleted = false
    private var terminalReleaseRequest: NtkAuthorityReleaseRequest? = null
    private var failureNotified = false

    private val leaseDispatcher = NtkBodyLeaseDispatcher(
        eventSink = { event -> post { onLeaseEvent(event) } },
        serviceOverride = executionEngines.bodyLease
    )
    private val decodeDispatcher = NtkFullSceneDecodeDispatcher(
        eventSink = { event -> post { onDecodeEvent(event) } },
        laneServicesOverride = executionEngines.decodeLanes
    )
    private val viewportMailbox = NtkViewportProofMailbox(
        pageCount = manifestSeal.pageCount,
        scheduleDrain = { runnable -> postRunnable(runnable) },
        consumeBatch = { batch -> applyViewportBatch(batch) },
        onScheduleFailure = { error -> failViewportMailboxSchedule(error) },
        onDrainFailure = { error -> fail(error) }
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        post { beginPreparation() }
    }

    fun onDetachedPreparationAvailable(
        port: NtkEpisodeStripPipeline.DetachedPreparationPort
    ) {
        post {
            val current = detachedPreparationPort
            if (current != null && current !== port) {
                fail(IllegalStateException("Detached preparation engine changed"))
                return@post
            }
            detachedPreparationPort = port
            openDetachedPreparationIfReady()
        }
    }

    fun onGeometrySeed(width: Int, height: Int, revision: Long) {
        val seed = runCatching {
            NtkPreparationGeometrySeed(width, height, revision)
        }.getOrNull() ?: return
        post {
            val existing = geometrySeed
            if (existing != null && existing != seed) {
                fail(IllegalStateException("Geometry seed changed during preparation"))
                return@post
            }
            geometrySeed = seed
            if (state != null) {
                accept(NtkFullScenePreparationEvent.GeometrySeedAvailable(seed))
            }
        }
    }

    fun onSurfaceAvailable(identity: NtkPublishedSurfaceIdentity) {
        if (identity.surfaceEpoch <= 0L) return
        post {
            val current = surfaceIdentity
            if (current != null && current != identity) {
                fail(IllegalStateException("Published surface changed during full-scene preparation"))
                return@post
            }
            surfaceIdentity = identity
            revokedSurfaceIdentity = null
            revokedCrossedStageBoundary = false
            if (state != null) {
                accept(NtkFullScenePreparationEvent.SurfacePublished(identity))
            }
        }
    }

    fun onSurfaceRevoked(
        identity: NtkPublishedSurfaceIdentity,
        crossedStageBoundary: Boolean
    ) {
        post {
            if (surfaceIdentity != identity || retired.get()) return@post
            revokedSurfaceIdentity = identity
            revokedCrossedStageBoundary = crossedStageBoundary
            active = false
            stageProof = null
        }
    }

    fun onSurfaceLost(
        identity: NtkPublishedSurfaceIdentity,
        crossedStageBoundary: Boolean,
        resourcesPreserved: Boolean
    ) {
        post {
            if (identity != surfaceIdentity) {
                if (crossedStageBoundary || !resourcesPreserved) {
                    fail(IllegalStateException("Full-scene surface authority was lost"))
                }
                return@post
            }
            val current = state
            if (current != null && current.publishedSurface == identity) {
                accept(NtkFullScenePreparationEvent.SurfaceLost(identity))
            } else if (crossedStageBoundary || !resourcesPreserved) {
                fail(IllegalStateException("Full-scene surface authority was lost"))
            }
        }
    }

    fun onPreSubmitViewportGap(count: Long) {
        if (count <= 0L) return
        post {
            preSubmitViewportGap = Math.addExact(preSubmitViewportGap, count)
            publishProof()
            fail(IllegalStateException("Native rejected a pre-submit viewport gap (count=$count)"))
        }
    }

    fun onViewportSample(sample: NtkViewportSample) {
        if (retired.get()) return
        viewportMailbox.offer(sample)
    }

    fun activate(completion: (Boolean) -> Unit) {
        post {
            val proof = stageProof
            val activated = proof != null &&
                controllerPort.activate(owner.authority, proof.stageNonce)
            active = activated
            completion(activated)
        }
    }

    fun stageProofSnapshot(completion: (NtkStageProof?) -> Unit) = post {
        completion(stageProof)
    }

    fun terminalProofSnapshot(completion: (NtkEpisodeProofSnapshot?) -> Unit) = post {
        flushViewportMailbox()
        publishProof()
        completion(latestProof)
    }

    fun terminalProofSnapshotBlocking(timeoutMillis: Long): NtkEpisodeProofSnapshot? {
        if (timeoutMillis <= 0L) return null
        val latch = CountDownLatch(1)
        var result: NtkEpisodeProofSnapshot? = null
        if (!post {
                try {
                    flushViewportMailbox()
                    publishProof()
                    result = latestProof
                } finally {
                    latch.countDown()
                }
            }) return null
        return if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) result else null
    }

    fun snapshot(completion: (NtkEpisodeStripPipeline.Snapshot) -> Unit) = post {
        val current = state
        val geometry = current?.geometry
        val resident = current?.tileRecords?.values?.count {
            it.state == NtkPreparationTileState.RESIDENT
        } ?: 0
        completion(
            NtkEpisodeStripPipeline.Snapshot(
                sourceDemandRegistered = manifestSeal.pageCount,
                sourceReady = current?.pages?.count { it.artifact != null } ?: 0,
                decodedTiles = current?.counters?.decodeCompletedCount?.toInt() ?: 0,
                residentTiles = resident,
                residentBytes = current?.accounting?.gpuSceneResidentBytes ?: 0L,
                residentContinuousEndPx = if (resident == geometry?.tileCount) {
                    geometry.contentHeightPx
                } else 0L,
                mandatoryEndPx = geometry?.contentHeightPx ?: 0L,
                contractState = if (stageProof != null) {
                    NtkRunwayContractState.PROVABLE_UNDER_MEASURED_ENVELOPE
                } else NtkRunwayContractState.AT_RISK,
                stageReady = stageProof != null,
                retired = retired.get(),
                cpuChargedBytes = (current?.accounting?.cpuReservedBytes ?: 0L) +
                    (current?.accounting?.cpuDecodedBytes ?: 0L)
            )
        )
    }

    fun retire(reason: String) {
        if (!retired.compareAndSet(false, true)) return
        viewportMailbox.cancel()
        actor.execute {
            beginTerminalCleanup(reason, null)
        }
        android.util.Log.d("ViewerPerf", "reader_full_scene_retire reason=$reason")
    }

    override fun close() = retire("close")

    private fun beginPreparation() {
        if (retired.get() || state != null || !started.get()) return
        state = machine.initial(
            NtkFullScenePreparationConfig(
                episode = episode,
                preparationGeneration = preparationGeneration,
                manifestSeal = manifestSeal,
                initialPageIndex = initialPageIndex,
                cpuPolicy = NtkCpuTransientPolicy.create(cpuTransientPolicyBytes)
            )
        )
        sourceBinding = strictSource.bindEpisode(
            episode,
            manifestSeal,
            initialPageIndex,
            NtkSourceEventListener { event -> post { onSourceEvent(event) } }
        )
        strictSource.applyPreGeometryPlan(
            episode,
            NtkPreGeometrySourcePlanner.create(initialPageIndex, manifestSeal.pageCount)
        )
        geometrySeed?.let {
            accept(NtkFullScenePreparationEvent.GeometrySeedAvailable(it))
        }
        surfaceIdentity?.let {
            accept(NtkFullScenePreparationEvent.SurfacePublished(it))
        }
        openDetachedPreparationIfReady()
    }

    private fun openDetachedPreparationIfReady() {
        if (retired.get() || state == null || detachedOpenRequested) return
        val port = detachedPreparationPort ?: return
        detachedOpenRequested = true
        port.openDetachedPreparation(
            owner.authority,
            preparationGeneration,
            manifestSeal
        ) { token ->
            post {
                if (token == null) fail(IllegalStateException("Native preparation open rejected"))
                else accept(NtkFullScenePreparationEvent.DetachedPreparationOpened(token))
            }
        }
    }

    private fun onSourceEvent(event: SourceEvent) {
        when (event) {
            is SourceEvent.MetadataReady ->
                accept(NtkFullScenePreparationEvent.MetadataReady(event.metadata))
            is SourceEvent.BodyPublished ->
                accept(NtkFullScenePreparationEvent.BodyPublished(event.descriptor))
            is SourceEvent.TerminalFailure -> fail(event.error)
        }
        maybeRequestSourceDrain()
    }

    private fun onLeaseEvent(event: NtkBodyLeaseDispatcher.Event) {
        when (event) {
            is NtkBodyLeaseDispatcher.Event.LeaseOpened -> {
                val opened = leaseDispatcher.acknowledgeOpened(
                    event.opened.request.requestId,
                    event.opened.leaseId
                ) ?: return fail(IllegalStateException("Body lease ACK identity mismatch"))
                leases[opened.leaseId] = opened
                accept(
                    NtkFullScenePreparationEvent.BodyLeaseOpened(
                        opened.request.pageIndex,
                        opened.request.requestId,
                        opened.leaseId
                    )
                )
            }
            is NtkBodyLeaseDispatcher.Event.LeaseOpenFailed -> {
                leaseDispatcher.acknowledgeFailed(event.request.requestId)
                accept(
                    NtkFullScenePreparationEvent.BodyLeaseOpenFailed(
                        event.request.pageIndex,
                        event.request.requestId,
                        event.error.message ?: event.error.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun onDecodeEvent(event: NtkFullSceneDecodeDispatcher.Event) {
        when (event) {
            is NtkFullSceneDecodeDispatcher.Event.Started -> accept(
                NtkFullScenePreparationEvent.DecodeStarted(
                    event.request.admission,
                    NtkDecodePriority.NORMAL,
                    event.workerThreadId,
                    event.actualActiveTasks
                )
            )
            is NtkFullSceneDecodeDispatcher.Event.Completed -> {
                check(payloads.put(event.payloadToken, event.tile) == null)
                accept(
                    NtkFullScenePreparationEvent.DecodeCompleted(
                        event.request.admission,
                        event.request.expectedProof,
                        event.payloadToken
                    )
                )
            }
            is NtkFullSceneDecodeDispatcher.Event.Failed -> accept(
                NtkFullScenePreparationEvent.DecodeFailed(
                    event.request.admission,
                    event.error.message ?: event.error.javaClass.simpleName
                )
            )
        }
    }

    private fun accept(event: NtkFullScenePreparationEvent) {
        val current = state ?: return
        val transition = runCatching { machine.reduce(current, event) }.getOrElse {
            fail(it)
            return
        }
        state = transition.snapshot
        peakCpuReserved = maxOf(peakCpuReserved, transition.snapshot.accounting.cpuReservedBytes)
        peakCpuDecoded = maxOf(peakCpuDecoded, transition.snapshot.accounting.cpuDecodedBytes)
        peakCpuCharged = maxOf(
            peakCpuCharged,
            Math.addExact(
                transition.snapshot.accounting.cpuReservedBytes,
                transition.snapshot.accounting.cpuDecodedBytes
            )
        )
        if (transition.snapshot.phase == NtkFullScenePreparationPhase.FAILED) {
            fail(IllegalStateException(transition.snapshot.failureReason ?: "Preparation failed"))
            return
        }
        transition.commands.forEach(::execute)
        maybeRequestSourceDrain()
    }

    private fun execute(command: NtkFullScenePreparationCommand) {
        val surfaceRevoked = revokedSurfaceIdentity != null &&
            revokedSurfaceIdentity == surfaceIdentity
        if (surfaceRevoked && command is
                NtkFullScenePreparationCommand.InstallPrepared ||
            surfaceRevoked && command is
                NtkFullScenePreparationCommand.AdoptDetachedPreparation ||
            surfaceRevoked && command is
                NtkFullScenePreparationCommand.ClosePreparationAdmissions ||
            surfaceRevoked && command is
                NtkFullScenePreparationCommand.PublishSeal
        ) {
            return
        }
        when (command) {
            is NtkFullScenePreparationCommand.OpenBodyLease -> {
                val result = leaseDispatcher.open(
                    NtkBodyLeaseDispatcher.OpenRequest(
                        command.requestId,
                        command.pageIndex,
                        command.descriptor.metadata.canonicalAsset,
                        command.descriptor
                    )
                )
                if (result != NtkBodyLeaseDispatcher.OfferResult.ACCEPTED) {
                    fail(IllegalStateException("Body lease admission rejected: $result"))
                }
            }
            is NtkFullScenePreparationCommand.StartDecode -> {
                val opened = leases[command.leaseId]
                    ?: return fail(IllegalStateException("Decode lacks retained body lease"))
                val accepted = decodeDispatcher.start(
                    NtkFullSceneDecodeDispatcher.Request(
                        command.admission,
                        command.leaseId,
                        opened.lease,
                        command.tilePlan,
                        command.expectedProof
                    )
                )
                if (!accepted) fail(IllegalStateException("Three-lane decode admission rejected"))
            }
            is NtkFullScenePreparationCommand.StartDecodeCohort -> {
                val requests = command.decodes.map { decode ->
                    val opened = leases[decode.leaseId]
                        ?: return fail(IllegalStateException(
                            "Decode cohort lacks retained body lease"
                        ))
                    NtkFullSceneDecodeDispatcher.Request(
                        decode.admission,
                        decode.leaseId,
                        opened.lease,
                        decode.tilePlan,
                        decode.expectedProof
                    )
                }
                if (!decodeDispatcher.startInitialThreeWideCohort(requests)) {
                    fail(IllegalStateException(
                        "Initial three-wide NORMAL decode cohort admission rejected"
                    ))
                }
            }
            is NtkFullScenePreparationCommand.ReleaseBodyLease -> {
                if (leases.remove(command.leaseId) == null ||
                    !leaseDispatcher.release(command.leaseId)
                ) return fail(IllegalStateException("Body lease release mismatch"))
                accept(
                    NtkFullScenePreparationEvent.BodyLeaseReleased(
                        command.pageIndex,
                        command.leaseId
                    )
                )
            }
            is NtkFullScenePreparationCommand.InstallPrepared -> {
                val current = state ?: return
                val token = current.nativePreparationToken
                    ?: return fail(IllegalStateException("Install lacks native preparation token"))
                val tile = payloads.remove(command.payloadToken)
                    ?: return fail(IllegalStateException("Install lacks decoded payload"))
                val install = runCatching {
                    NtkPreparedTileInstall(
                        token,
                        command.identity,
                        command.tileProof,
                        command.tileProof.rgbaBytes,
                        tile
                    )
                }.getOrElse {
                    recycle(tile)
                    fail(it)
                    return
                }
                uploadingTiles[command.identity] = tile
                val callback: (NtkPreparedTileResidentAck?) -> Unit = { ack ->
                    uploadingTiles.remove(command.identity)?.let(::recycle)
                    post {
                        if (ack == null) fail(IllegalStateException("Native prepared install failed"))
                        else accept(NtkFullScenePreparationEvent.NativeInstallAck(ack))
                    }
                }
                val accepted = if (command.surfaceToken == null) {
                    val port = detachedPreparationPort
                        ?: return fail(IllegalStateException(
                            "Detached install lacks preparation port"
                        ))
                    port.installDetachedPrepared(install, callback)
                } else {
                    controllerPort.installSurfacePrepared(
                        install,
                        command.surfaceToken,
                        callback
                    )
                }
                if (!accepted) {
                    uploadingTiles.remove(command.identity)?.let(::recycle)
                    fail(IllegalStateException("Native prepared install admission rejected"))
                }
            }
            is NtkFullScenePreparationCommand.AdoptDetachedPreparation -> {
                val current = state ?: return
                val geometry = current.geometry
                    ?: return fail(IllegalStateException("Geometry bind command lacks geometry"))
                val surface = current.publishedSurface
                    ?: return fail(IllegalStateException("Surface adoption lacks surface"))
                controllerPort.adoptDetachedPreparation(
                    command.request,
                    geometry,
                    surface
                ) { proof ->
                    post {
                        if (proof == null) fail(IllegalStateException("Native geometry bind failed"))
                        else accept(
                            NtkFullScenePreparationEvent.SurfacePreparationBound(proof)
                        )
                    }
                }
            }
            is NtkFullScenePreparationCommand.NotifyGeometrySealed -> strictSource.onGeometrySealed(
                episode,
                command.geometry.geometryDigest,
                command.geometry.pages.map { it.asset.pageIndex }.toSet()
            )
            NtkFullScenePreparationCommand.ClosePreparationAdmissions -> closeAdmissions()
            is NtkFullScenePreparationCommand.PublishSeal -> publishSeal(command.seal)
            is NtkFullScenePreparationCommand.ReleasePreparationAuthority ->
                fail(IllegalStateException(command.reason))
        }
    }

    private fun maybeRequestSourceDrain() {
        val current = state ?: return
        if (sourceDrainRequested || !isSourceDrainReady(current)) return
        sourceDrainRequested = true
        strictSource.requestPreparationDrain(episode) { proof ->
            post { accept(NtkFullScenePreparationEvent.SourceDrained(proof)) }
        }
    }

    private fun closeAdmissions() {
        if (preparationCloseRequested) {
            fail(IllegalStateException("Duplicate preparation drain command"))
            return
        }
        preparationCloseRequested = true
        decodeDispatcher.shutdown { post { maybeFinishPreparationDrain() } }
        leaseDispatcher.shutdown { post { maybeFinishPreparationDrain() } }
        maybeFinishPreparationDrain()
    }

    private fun maybeFinishPreparationDrain() {
        if (!preparationCloseRequested || preparationCloseCompleted || retired.get()) return
        val current = state ?: return
        val token = current.nativePreparationToken
            ?: return fail(IllegalStateException("Drain lacks native preparation token"))
        val decode = decodeDispatcher.snapshot()
        val leaseSnapshot = leaseDispatcher.snapshot()
        if (!decode.isDrained || !leaseSnapshot.isDrained) return
        if (uploadingTiles.isNotEmpty()) {
            fail(IllegalStateException("Preparation drain retained native callbacks"))
            return
        }
        val port = detachedPreparationPort
            ?: return fail(IllegalStateException("Drain lacks detached preparation port"))
        if (!port.closePreparationAdmissions(token)) {
            fail(IllegalStateException("Native preparation drain rejected"))
            return
        }
        val source = current.sourceDrainProof
            ?: return fail(IllegalStateException("Drain lacks exact source proof"))
        val proof = NtkPreparationDrainProof(
            source = source,
            decoderAccepting = decode.accepting,
            decoderDrained = decode.isDrained,
            leaseDispatcherAccepting = leaseSnapshot.accepting,
            leaseDispatcherDrained = leaseSnapshot.isDrained,
            nativeResourceAdmissionsClosed = true,
            nativeResourceQueueDrained = true,
            callbacksPending = uploadingTiles.size,
            actualDecodeActiveMax = decode.activeMax,
            actualNormalPriorityTaskStarts = decode.normalPriorityTaskStarts,
            actualBackgroundPriorityTaskStarts = decode.backgroundPriorityTaskStarts,
            threeWideEntryCount = decode.threeWideEntryCount,
            threeWideOverlapNanos = decode.threeWideOverlapNanos,
            completedAtNanos = System.nanoTime().coerceAtLeast(1L)
        )
        preparationCloseCompleted = true
        accept(NtkFullScenePreparationEvent.DrainCompleted(proof))
    }

    private fun publishSeal(seal: NtkPreparedFullSceneSeal) {
        if (revokedSurfaceIdentity != null &&
            revokedSurfaceIdentity == surfaceIdentity
        ) return
        val geometry = state?.geometry
            ?: return fail(IllegalStateException("Prepared seal lacks geometry"))
        val nonce = nextStageNonce.getAndIncrement()
        controllerPort.stage(
            owner.authority,
            0L,
            geometry.contentHeightPx,
            nonce,
            manifestSeal.revision,
            manifestSeal.digestSha256,
            geometry.geometryDigest
        ) { proof ->
            post {
                if (revokedSurfaceIdentity != null &&
                    revokedSurfaceIdentity == surfaceIdentity
                ) return@post
                if (proof == null || proof.gpuSceneCapacityProof.residentLogicalBytes !=
                    seal.totalRgbaBytes
                ) {
                    fail(IllegalStateException("Native full-scene stage proof failed"))
                } else {
                    stageProof = proof
                    publishProof()
                    listener.onStageReady(owner, geometry, proof)
                }
            }
        }
    }

    private fun applyViewportBatch(batch: NtkViewportProofBatch) {
        val current = state
            ?: return fail(IllegalStateException("Viewport arrived before preparation"))
        if (batch.hasBindingSeed || batch.hasMixedSurfaceEpochs ||
            batch.surfaceEpoch != current.publishedSurface?.surfaceEpoch
        ) {
            fail(IllegalStateException("Invalid native viewport authority/sequence"))
            return
        }
        if (batch.hasMissingProof) {
            fail(IllegalStateException("Presented native frame lacks proof"))
            return
        }
        if (batch.hasInvalidContentInterval || batch.hasInvalidPageRange) {
            fail(IllegalStateException("Presented native frame proof is outside episode bounds"))
            return
        }

        viewportOffers = Math.addExact(viewportOffers, batch.offerCount)
        viewportDefects = Math.addExact(viewportDefects, batch.viewportDefectCount)
        runwayDefects = Math.addExact(runwayDefects, batch.runwayDefectCount)
        presentedIntervals.addAll(batch.presentedIntervals)
        presentedPages.or(batch.presentedPages)

        var meaningfulProofTransition = false
        if (!viewportDefectObserved && batch.viewportDefectCount > 0L) {
            viewportDefectObserved = true
            meaningfulProofTransition = true
        }
        if (!runwayDefectObserved && batch.runwayDefectCount > 0L) {
            runwayDefectObserved = true
            meaningfulProofTransition = true
        }
        val geometry = current.geometry
        if (!wholeContentCoverageObserved && geometry != null &&
            presentedIntervals.covers(0L, geometry.contentHeightPx)
        ) {
            wholeContentCoverageObserved = true
            meaningfulProofTransition = true
        }
        if (meaningfulProofTransition) publishProof()
    }

    private fun flushViewportMailbox() {
        runCatching { viewportMailbox.flushPendingOnConsumerThread() }
            .onFailure(::fail)
    }

    private fun publishProof() {
        val current = state ?: return
        val seal = current.preparedSeal ?: return
        val geometry = current.geometry ?: return
        val merged = presentedIntervals.snapshot()
        val whole = merged.size == 1 && merged[0].startPx == 0L &&
            merged[0].endPx >= geometry.contentHeightPx
        val allKeys = cachedGeometryTileKeys ?: geometry.pages
            .flatMap { page -> page.tiles.map { it.key } }
            .toSet()
            .also { cachedGeometryTileKeys = it }
        val pageSnapshot = presentedPages.toPageSet(manifestSeal.pageCount)
        val missingPages = (0 until manifestSeal.pageCount).filterNot {
            presentedPages.get(it)
        }.toSet()
        val counters = NtkResidencyCounters(
            viewportOffers = viewportOffers,
            viewportDelivered = viewportOffers,
            hardAdmissions = seal.tileCount.toLong(),
            decodeActiveMaxPreStage = seal.counters.actualDecodeActiveMax,
            nativeUploadMax = seal.counters.nativeUploadMax
        )
        latestProof = NtkEpisodeProofSnapshot(
            manifestRevision = manifestSeal.revision,
            manifestDigest = manifestSeal.digestSha256,
            geometryDigest = geometry.geometryDigest,
            geometryTileCount = geometry.tileCount,
            contentHeightPx = geometry.contentHeightPx,
            manifestPages = manifestSeal.pageCount,
            metadataPages = manifestSeal.pageCount,
            sourceOriginalProofPages = manifestSeal.pageCount,
            drawableProofPages = manifestSeal.pageCount,
            everDecodedTiles = allKeys,
            everPublishedTiles = allKeys,
            presentedContentIntervals = merged,
            presentedPages = pageSnapshot,
            traversalCommittedPages = presentedPages.cardinality(),
            traversalMissingPages = missingPages,
            viewportDefectFrames = viewportDefects,
            runwayDefectFrames = runwayDefects,
            preSubmitViewportGap = preSubmitViewportGap,
            currentAccounting = NtkResidencyAccounting(gpuResidentBytes = seal.totalRgbaBytes),
            peakCpuChargedBytes = peakCpuCharged,
            peakCpuDecodedBytes = peakCpuDecoded,
            cpuTransientHardCapBytes = current.config.cpuPolicy.hardCapBytes,
            gpuSceneCapacityProof = stageProof?.gpuSceneCapacityProof,
            exactEpisodeEnd = whole,
            residencyCounters = counters,
            resourceCycleAdmissionCount = seal.resourceCycleLedger.admissionCount,
            resourceCycleReleaseCount = seal.resourceCycleLedger.releaseCount,
            resourceCycleReentryCount = seal.resourceCycleLedger.reentryCount,
            resourceCyclePendingReentryCount = seal.resourceCycleLedger.pendingReentryCount,
            resourceCycleMemoryPressureReleaseCount =
                seal.resourceCycleLedger.memoryPressureReleaseCount,
            resourceCycleContextLossReleaseCount =
                seal.resourceCycleLedger.contextLossReleaseCount,
            resourceCycleAuthorityRestartReleaseCount =
                seal.resourceCycleLedger.authorityRestartReleaseCount,
            resourceCycleLedgerDigest = seal.resourceCycleLedger.digest,
            resourceCycleLedgerValid = seal.resourceCycleLedger.isValid
        )
        listener.onProofSnapshot(owner, checkNotNull(latestProof))
    }

    private fun fail(error: Throwable) {
        if (retired.compareAndSet(false, true)) {
            viewportMailbox.cancel()
            beginTerminalCleanup("failure", error)
        }
    }

    /**
     * Scheduling normally fails only after retirement. If an active executor rejects the sole
     * drain, retry the fail transition on the actor; if even that is impossible, close the source
     * and notify failure directly so no unowned aggregate or apparently valid proof survives.
     */
    private fun failViewportMailboxSchedule(error: Throwable) {
        if (retired.get()) return
        val actorAccepted = runCatching {
            actor.execute { fail(error) }
            true
        }.getOrDefault(false)
        if (actorAccepted || !retired.compareAndSet(false, true)) return
        viewportMailbox.cancel()
        runCatching { sourceBinding?.close() }
        sourceBinding = null
        failureNotified = true
        runCatching { listener.onFailed(owner, error) }
    }

    private fun beginTerminalCleanup(reason: String, error: Throwable?) {
        if (terminalCleanupStarted) return
        terminalCleanupStarted = true
        if (error != null && !failureNotified) {
            failureNotified = true
            runCatching { listener.onFailed(owner, error) }
        }
        runCatching { controllerPort.disarm(owner.authority) }
        runCatching { sourceBinding?.close() }
        sourceBinding = null
        decodeDispatcher.shutdown { executeTerminal(::continueTerminalCleanup) }
        leaseDispatcher.shutdown { executeTerminal(::continueTerminalCleanup) }
        continueTerminalCleanup()
        android.util.Log.d("ViewerPerf", "reader_full_scene_terminal reason=$reason")
    }

    private fun continueTerminalCleanup() {
        if (!terminalCleanupStarted || terminalCleanupCompleted ||
            !decodeDispatcher.snapshot().isDrained
        ) return
        leases.keys.toList().forEach { leaseId ->
            if (leaseDispatcher.release(leaseId)) leases.remove(leaseId)
        }
        if (!leaseDispatcher.snapshot().isDrained) return
        payloads.values.forEach(::recycle)
        payloads.clear()
        runCatching { sourceTransport.retire(episode) }
        if (terminalReleaseRequest != null) return
        val token = controllerPort.currentToken()
        if (token == null) {
            finishTerminalCleanup()
            return
        }
        val releaseSurfaceEpoch = surfaceIdentity?.surfaceEpoch ?: 0L
        if (token.authority != owner.authority) {
            notifyTerminalFailure(IllegalStateException("Terminal native release token mismatch"))
            finishTerminalCleanup()
            return
        }
        val request = NtkAuthorityReleaseRequest(
            token = token,
            reducerSurfaceEpoch = releaseSurfaceEpoch,
            releaseNonce = nextReleaseNonce.getAndIncrement()
        )
        terminalReleaseRequest = request
        val accepted = controllerPort.releaseAuthority(request) { ack ->
            executeTerminal { acceptTerminalRelease(ack) }
        }
        if (!accepted) {
            notifyTerminalFailure(IllegalStateException("Native controller rejected authority release"))
            finishTerminalCleanup()
        }
    }

    private fun acceptTerminalRelease(ack: NtkNativeAuthorityReleaseAck) {
        if (terminalCleanupCompleted) return
        val request = terminalReleaseRequest ?: return notifyTerminalFailure(
            IllegalStateException("Native authority release ACK lacks request")
        )
        val violation = NtkTerminalPhysicalReleaseProofValidator.violation(
            NtkTerminalPhysicalReleaseProof(ack, uploadingTiles.size),
            request
        )
        if (violation != null) {
            notifyTerminalFailure(IllegalStateException(violation))
            finishTerminalCleanup()
            return
        }
        finishTerminalCleanup()
    }

    private fun notifyTerminalFailure(error: Throwable) {
        if (!failureNotified) {
            failureNotified = true
            runCatching { listener.onFailed(owner, error) }
        }
    }

    private fun finishTerminalCleanup() {
        if (terminalCleanupCompleted) return
        terminalCleanupCompleted = true
        viewportMailbox.cancel()
        uploadingTiles.values.forEach(::recycle)
        uploadingTiles.clear()
        runCatching { listener.onTerminalCleanupComplete(owner) }
        actor.shutdown()
    }

    private fun executeTerminal(block: () -> Unit) {
        if (actor.isShutdown) return
        runCatching { actor.execute(block) }
    }

    private fun post(block: () -> Unit): Boolean {
        if (retired.get() || actor.isShutdown) return false
        return runCatching { actor.execute(block); true }.getOrDefault(false)
    }

    private fun postRunnable(runnable: Runnable): Boolean {
        if (retired.get() || actor.isShutdown) return false
        return runCatching { actor.execute(runnable); true }.getOrDefault(false)
    }

    private fun recycle(tile: ReaderTile) {
        if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
    }

    private fun BitSet.toPageSet(pageCount: Int): Set<Int> {
        val result = LinkedHashSet<Int>(cardinality())
        var page = nextSetBit(0)
        while (page >= 0 && page < pageCount) {
            result += page
            page = nextSetBit(page + 1)
        }
        return result
    }

    internal companion object {
        val nextReleaseNonce = AtomicLong(System.nanoTime().coerceAtLeast(1L))

        /** No future body capability may open after the source's exact zero-lease proof. */
        fun isSourceDrainReady(snapshot: NtkFullScenePreparationSnapshot): Boolean =
            snapshot.pages.all { it.artifact != null } &&
                snapshot.bodyLeaseLedger.activeCount == 0
    }
}
