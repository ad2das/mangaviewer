package ml.melun.mangaview.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import ml.melun.mangaview.runtime.AppDispatchers
import ml.melun.mangaview.runtime.PerfTrace
import ml.melun.mangaview.runtime.ViewerTelemetry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.TreeMap
import kotlin.math.ceil
import kotlin.math.max

private const val LATCH_PROOF_LATCHED = 3
private const val LATCH_PROOF_LOST = 4
private const val EGL_SUCCESS = 0x3000
private const val FIXED_PHASE_SWAP_DURATION_INVALID = 7
private const val FIXED_PHASE_SWAP_MISSED_CUTOFF = 8
private const val FIXED_PHASE_TELEMETRY_SCHEMA_VERSION = 11
private const val BACKEND_COMPLETION_CLOCK_MONOTONIC = 1

internal data class NtkPublishedSurfaceIdentity(
    val engineGeneration: Long,
    val attachGeneration: Long,
    val surfaceEpoch: Long,
    val geometryRevision: Long,
    val width: Int,
    val height: Int,
    val demandGeneration: Long = 0L
)

internal data class NtkSurfaceInstallPermit(
    val demandGeneration: Long,
    val engineGeneration: Long,
    val warmProof: NtkDetachedWarmProof
) {
    val isExact: Boolean
        get() = demandGeneration > 0L &&
            engineGeneration == warmProof.engineGeneration &&
            warmProof.isExactDetachedWarm
}

internal fun ntkExactEpisodeEndFromPresentation(
    explicitEnd: Boolean,
    contentHeightPx: Long,
    intervals: List<NtkPresentedContentInterval>
): Boolean {
    if (explicitEnd) return true
    if (contentHeightPx <= 0L) return false
    val merged = NtkEpisodeProofSnapshot.mergePresentedIntervals(intervals)
    return merged.size == 1 && merged[0].startPx == 0L &&
        merged[0].endPx >= contentHeightPx
}

/**
 * Linearization boundary between native presentation callbacks and host lifecycle teardown.
 *
 * A volatile pre-check keeps background callbacks cheap. The second check and publication run
 * under the same monitor as [setEnabled], so once disabling returns no telemetry/proof callback
 * can still begin. The lifecycle owner then resets ViewerTelemetry to invalidate a UI Runnable
 * that an already-running publication may have queued before the disable acquired this monitor.
 */
internal class NtkHostPresentationGate(initiallyEnabled: Boolean = true) {
    private val lock = Any()
    @Volatile private var enabled = initiallyEnabled

    val isEnabled: Boolean
        get() = enabled

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            enabled = value
        }
    }

    fun runIfEnabled(block: () -> Unit): Boolean = synchronized(lock) {
        if (!enabled) return@synchronized false
        block()
        true
    }
}

/**
 * One-slot hand-off for the first authoritative frame of a replacement Surface.
 *
 * Holder teardown and native frame delivery run on different threads. A frame from the retiring
 * holder can therefore pass its outer identity check immediately before teardown and arrive after
 * the replacement holder is published. The lifecycle-qualified identity rejects that stale offer,
 * while the reservation prevents its already-posted Runnable from consuming a successor request.
 */
internal class NtkCompositorRevealDispatchGate {
    data class Identity(
        val engineGeneration: Long,
        val attachGeneration: Long,
        val surfaceEpoch: Long,
    )

    data class OfferResult(
        val shouldPost: Boolean,
        val reservation: Long,
    )

    private var acceptedIdentity: Identity? = null
    private var latestIdentity: Identity? = null
    private var scheduledReservation = 0L
    private var nextReservation = 1L

    /** Starts a new holder lifecycle and invalidates every callback from its predecessor. */
    @Synchronized
    fun activate(identity: Identity) {
        acceptedIdentity = identity
        latestIdentity = null
        scheduledReservation = 0L
    }

    /** Retires the current holder without reusing its callback reservation. */
    @Synchronized
    fun clear() {
        acceptedIdentity = null
        latestIdentity = null
        scheduledReservation = 0L
    }

    /** Keeps the latest exact identity and requests at most one main-thread owner. */
    @Synchronized
    fun offer(identity: Identity): OfferResult {
        if (identity != acceptedIdentity) return OfferResult(false, 0L)
        latestIdentity = identity
        if (scheduledReservation != 0L) {
            return OfferResult(false, scheduledReservation)
        }
        val reservation = nextReservation
        nextReservation = if (nextReservation == Long.MAX_VALUE) 1L else nextReservation + 1L
        scheduledReservation = reservation
        return OfferResult(true, reservation)
    }

    /** A stale Runnable is inert and cannot clear or consume a successor lifecycle reservation. */
    @Synchronized
    fun take(reservation: Long): Identity? {
        if (reservation <= 0L || scheduledReservation != reservation) return null
        scheduledReservation = 0L
        return latestIdentity.also { latestIdentity = null }
    }
}

/**
 * Reservation owner for one published-Surface resize commit.
 *
 * Owner matching is referential on purpose. Two resize requests may have equal geometry across a
 * holder replacement, but a callback from the retired request must never clear the replacement's
 * reservation or consume its queued successor geometry.
 */
internal class NtkPublishedResizeCommitGate<T : Any> {
    data class Reservation(val token: Long)

    private var activeOwner: T? = null
    private var reservedOwner: T? = null
    private var reservationToken = 0L
    private var running = false
    private var nextToken = 1L

    @Synchronized
    fun activate(owner: T) {
        activeOwner = owner
        reservedOwner = null
        reservationToken = 0L
        running = false
    }

    @Synchronized
    fun clear() {
        activeOwner = null
        reservedOwner = null
        reservationToken = 0L
        running = false
    }

    /** Returns null when this exact owner already has a scheduled or running commit. */
    @Synchronized
    fun reserve(owner: T): Reservation? {
        if (activeOwner !== owner || reservedOwner != null) return null
        val token = nextToken
        nextToken = if (nextToken == Long.MAX_VALUE) 1L else nextToken + 1L
        reservedOwner = owner
        reservationToken = token
        return Reservation(token)
    }

    /** Claims only the still-current exact owner; stale callbacks leave successor state intact. */
    @Synchronized
    fun begin(owner: T, reservation: Reservation): Boolean {
        if (activeOwner !== owner || reservedOwner !== owner || running ||
            reservationToken != reservation.token
        ) return false
        running = true
        return true
    }

    /** Completes only the matching running owner and cannot deactivate a replacement owner. */
    @Synchronized
    fun finish(owner: T, reservation: Reservation) {
        if (activeOwner !== owner || reservedOwner !== owner || !running ||
            reservationToken != reservation.token
        ) return
        activeOwner = null
        reservedOwner = null
        reservationToken = 0L
        running = false
    }
}

class NtkStripSurfaceView private constructor(
    context: Context,
    prewarmedEngine: NtkStripRenderEngine,
    installPermit: NtkSurfaceInstallPermit
) : SurfaceView(context), SurfaceHolder.Callback {
    internal data class TargetLifecycleDebugSnapshot(
        val demandGeneration: Long,
        val engineGeneration: Long,
        val holderCreatedNanos: Long,
        val surfaceLeaseAcquiredNanos: Long,
        val attachReadyNanos: Long,
        val surfacePublishedNanos: Long,
        val holderCreatedCount: Int,
        val surfaceLeaseAcquireCount: Int,
        val attachReadyCount: Int,
        val surfacePublishCount: Int
    )

    internal data class SurfaceLossEvent(
        val identity: NtkPublishedSurfaceIdentity?,
        val engineGeneration: Long,
        val attachGeneration: Long,
        val surfaceEpoch: Long,
        val authority: Long,
        val crossedStageBoundary: Boolean,
        val stageNonce: Long,
        val resourcesPreserved: Boolean,
        val detachResult: NtkNativeDetachResult,
        val demandGeneration: Long = 0L
    )

    internal data class SurfaceRevocationEvent(
        val identity: NtkPublishedSurfaceIdentity,
        val authority: Long,
        val crossedStageBoundary: Boolean,
        val stageNonce: Long,
        val reason: NtkSurfaceLossReason
    )

    internal data class SurfaceFailureEvent(
        val engineGeneration: Long,
        val attachGeneration: Long,
        val surfaceEpoch: Long,
        val reason: NtkSurfaceAttachFailure,
        val demandGeneration: Long = 0L
    )

    internal interface SurfaceLifecycleListener {
        fun onSurfaceAvailable(identity: NtkPublishedSurfaceIdentity)
        fun onSurfaceRevoked(event: SurfaceRevocationEvent)
        fun onSurfaceLost(event: SurfaceLossEvent)
        fun onSurfaceAttachFailed(event: SurfaceFailureEvent)
        fun onPreSubmitViewportGap(count: Long) {}
    }

    private val activity = context.requireActivity()
    private data class HolderSurfaceCandidate(
        val demandGeneration: Long,
        val surfaceEpoch: Long,
        val refreshPeriodNanos: Long,
        var width: Int,
        var height: Int,
        var geometryRevision: Long,
        var lease: NtkNativeSurfaceLease?,
        var holderAlive: Boolean
    )
    private sealed interface EngineSurfaceState {
        data class Attaching(
            val demandGeneration: Long,
            val key: NtkSurfaceAttachKey,
            var requestedGeometryRevision: Long
        ) : EngineSurfaceState

        data class Ready(
            val demandGeneration: Long,
            val ready: NtkStripRenderEngine.SurfaceAttachReady
        ) : EngineSurfaceState

        data class Published(
            val identity: NtkPublishedSurfaceIdentity
        ) : EngineSurfaceState

        data class Detaching(
            val demandGeneration: Long,
            val key: NtkSurfaceAttachKey,
            val wasPublished: Boolean
        ) : EngineSurfaceState
    }
    private sealed interface EngineSlot {
        data class Live(val engine: NtkStripRenderEngine) : EngineSlot
        data class Failed(val generation: Long, val cause: Throwable) : EngineSlot
    }
    private val engineSlot = AtomicReference<EngineSlot>(EngineSlot.Live(prewarmedEngine))
    private val installPermit = AtomicReference(installPermit)
    private val engine: NtkStripRenderEngine
        get() = when (val slot = engineSlot.get()) {
            is EngineSlot.Live -> slot.engine
            is EngineSlot.Failed -> throw IllegalStateException(
                "NTK successor generation ${slot.generation} failed",
                slot.cause
            )
        }
    private fun liveEngineOrNull(): NtkStripRenderEngine? =
        (engineSlot.get() as? EngineSlot.Live)?.engine
    private data class PublishedEngineTarget(
        val engine: NtkStripRenderEngine,
        val identity: NtkPublishedSurfaceIdentity
    )
    private fun publishedEngineTargetOrNull(): PublishedEngineTarget? {
        repeat(3) {
            val identity = publishedSurface.get() ?: return null
            val slot = engineSlot.get() as? EngineSlot.Live ?: return null
            if (slot.engine.engineGeneration != identity.engineGeneration) return@repeat
            if (identity === publishedSurface.get() && slot === engineSlot.get()) {
                return PublishedEngineTarget(slot.engine, identity)
            }
        }
        return null
    }
    private val currentBinding = AtomicReference<NtkNativeAuthorityToken?>()
    private val retiringEngines = ConcurrentHashMap<Long, NtkStripRenderEngine>()
    @Volatile private var latestContextLossDetachResult: NtkNativeDetachResult? = null
    private data class RetiringTelemetryBucket(
        val everPublishedTiles: MutableSet<NtkStripRenderEngine.TileKey>,
        val traversedPages: Set<Int>,
        val presentedContent: List<NtkPresentedContentInterval>,
        val presentSampleCount: Int,
        val viewportDefectFrames: Long,
        val runwayDefectFrames: Long,
        val structureEpoch: Long
    )
    private val retiringTelemetry =
        ConcurrentHashMap<NtkNativeAuthorityToken, RetiringTelemetryBucket>()
    private val nextSurfaceEpoch = AtomicLong(0L)
    private val publishedSurface = AtomicReference<NtkPublishedSurfaceIdentity?>()
    private data class PublishedResizeGeometry(
        val geometryRevision: Long,
        val width: Int,
        val height: Int
    )
    private data class PublishedResizeInFlight(
        val engineGeneration: Long,
        val attachGeneration: Long,
        val surfaceEpoch: Long,
        val geometry: PublishedResizeGeometry,
        val predecessorBackendSurfaceSerial: Long
    )
    private val publishedResizeInFlight =
        AtomicReference<PublishedResizeInFlight?>()
    private val publishedResizeCommitGate =
        NtkPublishedResizeCommitGate<PublishedResizeInFlight>()
    private var queuedPublishedResize: PublishedResizeGeometry? = null
    // Android main thread only except [viewClosing], which lifecycle completion reads to suppress
    // successor creation after final View teardown.
    private var holderCandidate: HolderSurfaceCandidate? = null
    private var engineSurfaceState: EngineSurfaceState? = null
    @Volatile private var viewClosing = false
    private var terminalSurfaceFailure: SurfaceFailureEvent? = null
    @Volatile private var surfaceLifecycleListener: SurfaceLifecycleListener? = null
    private var hostDispatchOwned = false
    @Volatile private var geometry: NtkStripGeometry? = null
    private val everPublishedTiles = ConcurrentHashMap.newKeySet<NtkStripRenderEngine.TileKey>()
    private val preparedResidency = NtkPreparedSurfaceResidencyTracker(everPublishedTiles)
    private val traversedPages = ConcurrentHashMap.newKeySet<Int>()
    private val presentedContent = NtkStripIntervalSet()
    private val viewportDefectFrames = AtomicLong(0L)
    private val runwayDefectFrames = AtomicLong(0L)
    private val schema10EvidenceFrames = AtomicLong(0L)
    private val schema10IdentityInvalidFrames = AtomicLong(0L)
    private val surfaceControlLatchInvalidFrames = AtomicLong(0L)
    private val externalSubmissionInvalidFrames = AtomicLong(0L)
    private val hardwareBufferIdentityInvalidFrames = AtomicLong(0L)
    private val schema11QualificationAccumulator =
        NtkSchema11QualificationAccumulator()
    // Store the already-published snapshot itself. Re-copying the exhaustive NTK evidence
    // through a Kotlin constructor can exceed the Android DEX invocation-register limit.
    // Frame-timestamp proofs resolve round-robin. Key by submission identity so callback order
    // can neither regress the newest sample nor manufacture a pacing delta between non-neighbors.
    private val presentTimes =
        TreeMap<NtkFrameOrderKey, NtkStripRenderEngine.FrameSnapshot>()
    private val presentLock = Any()
    private val lastMergedFrameKey = AtomicReference<NtkFrameOrderKey?>()
    private val structureEpoch = AtomicLong(0L)
    @Volatile private var compositorAlpha = 0f
    @Volatile private var compositorTemporarilyHiddenForSurfaceLoss = false
    private val compositorRevealDispatchGate = NtkCompositorRevealDispatchGate()
    private val hostPresentationGate = NtkHostPresentationGate()
    @Volatile internal var frameListener: ((NtkStripRenderEngine.FrameSnapshot) -> Unit)? = null
    private var holderCreatedNanos = 0L
    private var surfaceLeaseAcquiredNanos = 0L
    private var attachReadyNanos = 0L
    private var surfacePublishedNanos = 0L
    private var holderCreatedCount = 0
    private var surfaceLeaseAcquireCount = 0
    private var attachReadyCount = 0
    private var surfacePublishCount = 0

    init {
        require(installPermit.isExact)
        require(prewarmedEngine.engineGeneration == installPermit.engineGeneration)
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888)
        // Keep the already-presented strip in its own compositor layer. Alpha, unlike moving or
        // invalidating the episode RecyclerView, can reveal the latched buffer on ACTION_DOWN
        // without disturbing the row that owns the in-flight press.
        setZOrderOnTop(true)
        setWillNotDraw(true)
        isClickable = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        engine.frameListener = ::onFramePresented
        engine.preSubmitViewportGapListener = ::onPreSubmitViewportGap
    }

    companion object {
        // This is a rolling diagnostic window, not the formal schema11 qualification ledger.
        // 2,048 samples retain more than 17 seconds even at 120 Hz while preventing a reader
        // session from retaining FrameSnapshot payloads (including schema arrays) indefinitely.
        internal const val MAX_PRESENT_DIAGNOSTIC_FRAMES = 2_048

        internal fun create(
            context: Context,
            engine: NtkStripRenderEngine,
            permit: NtkSurfaceInstallPermit
        ): NtkStripSurfaceView = NtkStripSurfaceView(context, engine, permit)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        checkMainThread()
        if (viewClosing || terminalSurfaceFailure != null) return
        holderCreatedNanos = System.nanoTime()
        holderCreatedCount++
        val permit = installPermit.get()
        val target = liveEngineOrNull()
        val surfaceState = engineSurfaceState
        val waitingForExactDetach = surfaceState is EngineSurfaceState.Detaching &&
            target != null && surfaceState.key.engineGeneration == target.engineGeneration
        if (permit == null || !permit.isExact || target == null ||
            permit.engineGeneration != target.engineGeneration ||
            (target.protocolPhaseSnapshot() != ProtocolPhase.LIVE_DETACHED &&
                !waitingForExactDetach)
        ) {
            terminalSurfaceFailure(
                SurfaceFailureEvent(
                    target?.engineGeneration ?: 0L,
                    0L,
                    0L,
                    NtkSurfaceAttachFailure.PROTOCOL_REJECTED
                )
            )
            return
        }
        val displayRefreshRate = display?.refreshRate?.takeIf { it > 0f } ?: 60f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            holder.surface.setFrameRate(
                displayRefreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(
                displayRefreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            )
        }
        val refreshPeriodNanos = (1_000_000_000.0 / displayRefreshRate).toLong()
        val epoch = nextSurfaceEpoch.incrementAndGet()
        setDesiredCompositorAlpha(compositorAlpha)
        val lease = NtkNativeSurfaceLease.acquire(holder.surface, epoch)
        if (lease == null) {
            terminalSurfaceFailure(
                SurfaceFailureEvent(
                    liveEngineOrNull()?.engineGeneration ?: 0L,
                    0L,
                    epoch,
                    NtkSurfaceAttachFailure.SURFACE_LEASE_ACQUIRE_FAILED
                )
            )
            return
        }
        surfaceLeaseAcquiredNanos = System.nanoTime()
        surfaceLeaseAcquireCount++
        val frame = holder.surfaceFrame
        holderCandidate?.lease?.close()
        holderCandidate = HolderSurfaceCandidate(
            demandGeneration = permit.demandGeneration,
            surfaceEpoch = epoch,
            refreshPeriodNanos = refreshPeriodNanos,
            width = maxOf(width, frame.width()),
            height = maxOf(height, frame.height()),
            geometryRevision = 1L,
            lease = lease,
            holderAlive = true
        )
        driveSurfaceHandoff()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        checkMainThread()
        if (width <= 0 || height <= 0 || viewClosing ||
            terminalSurfaceFailure != null
        ) return
        val candidate = holderCandidate ?: return
        if (!candidate.holderAlive) return
        if (candidate.width == width && candidate.height == height) return
        candidate.width = width
        candidate.height = height
        candidate.geometryRevision++
        when (val state = engineSurfaceState) {
            null -> driveSurfaceHandoff()
            is EngineSurfaceState.Attaching -> {
                if (state.key.surfaceEpoch != candidate.surfaceEpoch) return
                state.requestedGeometryRevision = candidate.geometryRevision
                liveEngineForGeneration(state.key.engineGeneration)?.updateAttachGeometry(
                    state.key,
                    width,
                    height,
                    candidate.geometryRevision
                )
            }
            is EngineSurfaceState.Ready -> {
                if (state.ready.key.surfaceEpoch != candidate.surfaceEpoch) return
                liveEngineForGeneration(state.ready.key.engineGeneration)?.updateAttachGeometry(
                    state.ready.key,
                    width,
                    height,
                    candidate.geometryRevision
                )
            }
            is EngineSurfaceState.Published -> {
                if (state.identity.surfaceEpoch != candidate.surfaceEpoch) return
                val requested = PublishedResizeGeometry(
                    geometryRevision = candidate.geometryRevision,
                    width = width,
                    height = height
                )
                if (publishedResizeInFlight.get() == null) {
                    startPublishedResize(state.identity, requested)
                } else {
                    // Serialize physical backend rebuilds. The corresponding schema identity is
                    // committed only after a frame from the new backend surface serial arrives.
                    queuedPublishedResize = requested
                }
            }
            is EngineSurfaceState.Detaching -> Unit
        }
    }

    private fun startPublishedResize(
        identity: NtkPublishedSurfaceIdentity,
        requested: PublishedResizeGeometry
    ) {
        checkMainThread()
        val target = liveEngineForGeneration(identity.engineGeneration) ?: return
        val predecessorBackendSerial = target.latestFrameSnapshot()
            ?.takeIf { it.surfaceEpoch == identity.surfaceEpoch }
            ?.backendSurfaceSerial
            ?: 0L
        val inFlight = PublishedResizeInFlight(
            engineGeneration = identity.engineGeneration,
            attachGeneration = identity.attachGeneration,
            surfaceEpoch = identity.surfaceEpoch,
            geometry = requested,
            predecessorBackendSurfaceSerial = predecessorBackendSerial
        )
        if (!publishedResizeInFlight.compareAndSet(null, inFlight)) {
            queuedPublishedResize = requested
            return
        }
        publishedResizeCommitGate.activate(inFlight)
        target.resize(identity.toAttachKey(), requested.width, requested.height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        checkMainThread()
        revokeCurrentSurface(NtkSurfaceLossReason.HOLDER_DESTROYED)
    }

    internal fun setSurfaceLifecycleListener(listener: SurfaceLifecycleListener?) {
        surfaceLifecycleListener = listener
        if (listener != null) {
            AppDispatchers.runOnMain {
                if (surfaceLifecycleListener !== listener) return@runOnMain
                publishedSurface.get()?.let(listener::onSurfaceAvailable)
                    ?: terminalSurfaceFailure?.let(listener::onSurfaceAttachFailed)
            }
        }
    }

    /**
     * Host lifecycle gate for physical-frame publication. The native engine may finish an already
     * submitted frame after Home/onPause; that buffer must not republish accessibility, p0 or
     * viewport semantics while the Activity is backgrounded. Re-enabling requests one fresh,
     * foreground-owned frame rather than reusing the background latch.
     */
    internal fun setHostPresentationEnabled(enabled: Boolean) {
        hostPresentationGate.setEnabled(enabled)
        if (enabled) liveEngineOrNull()?.requestRender()
    }

    private fun driveSurfaceHandoff() {
        checkMainThread()
        val candidate = holderCandidate ?: return
        val permit = installPermit.get() ?: return
        if (viewClosing || terminalSurfaceFailure != null || !candidate.holderAlive ||
            candidate.width <= 0 || candidate.height <= 0 || engineSurfaceState != null
        ) return
        val target = liveEngineOrNull() ?: return
        if (!permit.isExact ||
            candidate.demandGeneration != permit.demandGeneration ||
            target.engineGeneration != permit.engineGeneration ||
            target.protocolPhaseSnapshot() != ProtocolPhase.LIVE_DETACHED
        ) return
        val ownedLease = candidate.lease ?: return
        val transfer = ownedLease.transfer()
        if (transfer == null) {
            terminalSurfaceFailure(
                SurfaceFailureEvent(
                    target.engineGeneration,
                    0L,
                    candidate.surfaceEpoch,
                    NtkSurfaceAttachFailure.PROTOCOL_REJECTED
                )
            )
            return
        }
        candidate.lease = null
        val key = target.beginAttachAsync(
            lease = transfer,
            surfaceEpoch = candidate.surfaceEpoch,
            width = candidate.width,
            height = candidate.height,
            geometryRevision = candidate.geometryRevision,
            refreshPeriodNanos = candidate.refreshPeriodNanos,
            completion = ::onAttachCompletion
        )
        if (key == null) {
            terminalSurfaceFailure(
                SurfaceFailureEvent(
                    target.engineGeneration,
                    0L,
                    candidate.surfaceEpoch,
                    NtkSurfaceAttachFailure.PROTOCOL_REJECTED
                )
            )
            return
        }
        engineSurfaceState = EngineSurfaceState.Attaching(
            candidate.demandGeneration,
            key,
            candidate.geometryRevision
        )
    }

    private fun onAttachCompletion(completion: NtkStripRenderEngine.SurfaceAttachCompletion) {
        AppDispatchers.runOnMain {
            when (completion) {
                is NtkStripRenderEngine.SurfaceAttachCompletion.Ready ->
                    onAttachReady(completion.value)
                is NtkStripRenderEngine.SurfaceAttachCompletion.CancelledBeforeClaim -> {
                    val state = engineSurfaceState
                    if (state is EngineSurfaceState.Attaching &&
                        state.key == completion.key
                    ) {
                        engineSurfaceState = null
                    }
                }
                is NtkStripRenderEngine.SurfaceAttachCompletion.Failed -> {
                    val stateKey = when (val state = engineSurfaceState) {
                        is EngineSurfaceState.Attaching -> state.key
                        is EngineSurfaceState.Ready -> state.ready.key
                        is EngineSurfaceState.Detaching -> state.key
                        else -> null
                    }
                    if (stateKey == completion.key) {
                        terminalSurfaceFailure(
                            SurfaceFailureEvent(
                                completion.key.engineGeneration,
                                completion.key.attachGeneration,
                                completion.key.surfaceEpoch,
                                completion.reason
                            )
                        )
                    }
                }
            }
        }
    }

    private fun onAttachReady(ready: NtkStripRenderEngine.SurfaceAttachReady) {
        checkMainThread()
        val state = engineSurfaceState
        if (state !is EngineSurfaceState.Attaching || state.key != ready.key) return
        val permit = installPermit.get() ?: return
        val candidate = holderCandidate
        if (candidate == null || !candidate.holderAlive ||
            candidate.surfaceEpoch != ready.key.surfaceEpoch ||
            candidate.demandGeneration != state.demandGeneration ||
            candidate.demandGeneration != permit.demandGeneration ||
            ready.key.engineGeneration != permit.engineGeneration
        ) return
        engineSurfaceState = EngineSurfaceState.Ready(state.demandGeneration, ready)
        attachReadyNanos = System.nanoTime()
        attachReadyCount++
        if (candidate.geometryRevision != ready.appliedGeometryRevision) {
            liveEngineForGeneration(ready.key.engineGeneration)?.updateAttachGeometry(
                ready.key,
                candidate.width,
                candidate.height,
                candidate.geometryRevision
            )
            return
        }
        val target = liveEngineForGeneration(ready.key.engineGeneration) ?: return
        if (!target.publishAttachedSurface(ready.key, ready.appliedGeometryRevision)) {
            terminalSurfaceFailure(
                SurfaceFailureEvent(
                    ready.key.engineGeneration,
                    ready.key.attachGeneration,
                    ready.key.surfaceEpoch,
                    NtkSurfaceAttachFailure.PROTOCOL_REJECTED
                )
            )
            return
        }
        val identity = NtkPublishedSurfaceIdentity(
            engineGeneration = ready.key.engineGeneration,
            attachGeneration = ready.key.attachGeneration,
            surfaceEpoch = ready.key.surfaceEpoch,
            geometryRevision = ready.appliedGeometryRevision,
            width = ready.width,
            height = ready.height,
            demandGeneration = state.demandGeneration
        )
        if (!applyPublishedCompositorAlphaRequired()) {
            terminalSurfaceFailure(
                SurfaceFailureEvent(
                    ready.key.engineGeneration,
                    ready.key.attachGeneration,
                    ready.key.surfaceEpoch,
                    NtkSurfaceAttachFailure.COMPOSITOR_ALPHA_FAILED
                )
            )
            return
        }
        compositorRevealDispatchGate.activate(identity.compositorRevealIdentity())
        publishedSurface.set(identity)
        engineSurfaceState = EngineSurfaceState.Published(identity)
        surfacePublishedNanos = System.nanoTime()
        surfacePublishCount++
        surfaceLifecycleListener?.onSurfaceAvailable(identity)
    }

    private fun revokeCurrentSurface(reason: NtkSurfaceLossReason) {
        checkMainThread()
        publishedResizeInFlight.set(null)
        publishedResizeCommitGate.clear()
        queuedPublishedResize = null
        val candidate = holderCandidate
        candidate?.holderAlive = false
        holderCandidate = null
        candidate?.lease?.close()
        candidate?.lease = null
        val state = engineSurfaceState ?: run {
            hideCompositorForSurfaceLoss()
            return
        }
        val key = when (state) {
            is EngineSurfaceState.Attaching -> state.key
            is EngineSurfaceState.Ready -> state.ready.key
            is EngineSurfaceState.Published -> state.identity.toAttachKey()
            is EngineSurfaceState.Detaching -> return
        }
        val publishedIdentity = (state as? EngineSurfaceState.Published)?.identity
        if (publishedIdentity != null) {
            publishedSurface.compareAndSet(publishedIdentity, null)
        }
        hideCompositorForSurfaceLoss()
        val authority = geometry?.episode?.value ?: 0L
        val detachedEngine = liveEngineForGeneration(key.engineGeneration) ?: return
        val revocation = detachedEngine.beginSurfaceLoss(key, reason) { finalRevocation ->
            onSurfaceLossCompleted(
                detachedEngine,
                key,
                authority,
                publishedIdentity,
                finalRevocation
            )
        } ?: run {
            terminalSurfaceFailure(
                SurfaceFailureEvent(
                    key.engineGeneration,
                    key.attachGeneration,
                    key.surfaceEpoch,
                    NtkSurfaceAttachFailure.PROTOCOL_REJECTED
                )
            )
            return
        }
        engineSurfaceState = EngineSurfaceState.Detaching(
            installPermit.get()?.demandGeneration ?: 0L,
            key,
            publishedIdentity != null
        )
        if (publishedIdentity != null) {
            surfaceLifecycleListener?.onSurfaceRevoked(
                SurfaceRevocationEvent(
                    identity = publishedIdentity,
                    authority = authority,
                    crossedStageBoundary = revocation.crossedStageBoundary,
                    stageNonce = revocation.proof?.stageNonce ?: 0L,
                    reason = reason
                )
            )
        }
    }

    private fun onSurfaceLossCompleted(
        detachedEngine: NtkStripRenderEngine,
        key: NtkSurfaceAttachKey,
        authority: Long,
        publishedIdentity: NtkPublishedSurfaceIdentity?,
        revocation: NtkStripRenderEngine.StageRevocation
    ) {
        val detachResult = revocation.detachResult
        when (detachResult.disposition) {
            NtkNativeDetachDisposition.SURFACE_PRESERVED -> AppDispatchers.runOnMain {
                completeSurfaceLossOnMain(
                    detachedEngine,
                    key,
                    authority,
                    publishedIdentity,
                    revocation
                )
            }
            NtkNativeDetachDisposition.CONTEXT_LOST_RETIRED -> {
                check(detachResult.engineGeneration == detachedEngine.engineGeneration)
                check(detachResult.surfaceEpoch == key.surfaceEpoch)
                check(detachResult.hasCompleteRetirementBarrier) {
                    "Context-loss detach retained old backend ownership"
                }
                latestContextLossDetachResult = detachResult
                val previous = retiringEngines.putIfAbsent(
                    detachedEngine.engineGeneration,
                    detachedEngine
                )
                check(previous == null || previous === detachedEngine)
                val successorGeneration = NtkStripRenderEngine.allocateEngineGeneration()
                val successorSlot = if (viewClosing) {
                    null
                } else try {
                    val successor = NtkStripRenderEngine(activity, successorGeneration).also {
                        it.frameListener = ::onFramePresented
                        it.preSubmitViewportGapListener = ::onPreSubmitViewportGap
                    }
                    val proof = successor.awaitDetachedWarmBlocking()
                    val previousPermit = installPermit.get()
                    if (proof == null || previousPermit == null) {
                        successor.closeAfterSurfaceTerminal()
                        EngineSlot.Failed(
                            successorGeneration,
                            IllegalStateException("Successor detached warm proof unavailable")
                        )
                    } else {
                        installPermit.set(
                            NtkSurfaceInstallPermit(
                                previousPermit.demandGeneration,
                                successorGeneration,
                                proof
                            )
                        )
                        EngineSlot.Live(successor)
                    }
                } catch (failure: Throwable) {
                    EngineSlot.Failed(successorGeneration, failure)
                }
                AppDispatchers.runOnMain {
                    if (successorSlot != null) engineSlot.set(successorSlot)
                    completeSurfaceLossOnMain(
                        detachedEngine,
                        key,
                        authority,
                        publishedIdentity,
                        revocation
                    )
                    AppDispatchers.submitNtkSurfaceLifecycleStrict {
                        detachedEngine.finishContextLossHandoff()
                        closeRetiredProofAfterCompletions(detachedEngine)
                    }
                }
            }
            NtkNativeDetachDisposition.FAILED -> AppDispatchers.runOnMain {
                completeSurfaceLossOnMain(
                    detachedEngine,
                    key,
                    authority,
                    publishedIdentity,
                    revocation
                )
            }
        }
    }

    private fun completeSurfaceLossOnMain(
        detachedEngine: NtkStripRenderEngine,
        key: NtkSurfaceAttachKey,
        authority: Long,
        publishedIdentity: NtkPublishedSurfaceIdentity?,
        revocation: NtkStripRenderEngine.StageRevocation
    ) {
        checkMainThread()
        val state = engineSurfaceState
        if (state !is EngineSurfaceState.Detaching || state.key != key) return
        val detachResult = revocation.detachResult
        val lossEvent = SurfaceLossEvent(
            identity = publishedIdentity,
            engineGeneration = key.engineGeneration,
            attachGeneration = key.attachGeneration,
            surfaceEpoch = key.surfaceEpoch,
            authority = authority,
            crossedStageBoundary = revocation.crossedStageBoundary,
            stageNonce = revocation.proof?.stageNonce ?: 0L,
            resourcesPreserved = revocation.resourcesPreserved,
            detachResult = detachResult,
            demandGeneration = state.demandGeneration
        )
        engineSurfaceState = null
        surfaceLifecycleListener?.onSurfaceLost(lossEvent)
        when (detachResult.disposition) {
            NtkNativeDetachDisposition.SURFACE_PRESERVED -> {
                if (viewClosing) detachedEngine.closeAfterSurfaceTerminal()
                else driveSurfaceHandoff()
            }
            NtkNativeDetachDisposition.CONTEXT_LOST_RETIRED -> {
                when (val slot = engineSlot.get()) {
                    is EngineSlot.Live -> if (!viewClosing) driveSurfaceHandoff()
                    is EngineSlot.Failed -> terminalSurfaceFailure(
                        SurfaceFailureEvent(
                            slot.generation,
                            0L,
                            key.surfaceEpoch,
                            NtkSurfaceAttachFailure.LIFECYCLE_TASK_FAILED
                        )
                    )
                }
            }
            NtkNativeDetachDisposition.FAILED -> terminalSurfaceFailure(
                SurfaceFailureEvent(
                    key.engineGeneration,
                    key.attachGeneration,
                    key.surfaceEpoch,
                    NtkSurfaceAttachFailure.NATIVE_ATTACH_FAILED
                )
            )
        }
    }

    private fun terminalSurfaceFailure(event: SurfaceFailureEvent) {
        checkMainThread()
        if (terminalSurfaceFailure != null) return
        terminalSurfaceFailure = event
        publishedSurface.set(null)
        holderCandidate?.lease?.close()
        holderCandidate = null
        setCompositorAlpha(0f)
        surfaceLifecycleListener?.onSurfaceAttachFailed(event)
    }

    private fun liveEngineForGeneration(generation: Long): NtkStripRenderEngine? =
        liveEngineOrNull()?.takeIf { it.engineGeneration == generation }

    private fun NtkPublishedSurfaceIdentity.toAttachKey() = NtkSurfaceAttachKey(
        engineGeneration,
        attachGeneration,
        surfaceEpoch
    )

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Surface handoff reducer must run on Android main"
        }
    }

    private fun rotateTelemetryForBinding(
        previous: NtkNativeAuthorityToken?,
        next: NtkNativeAuthorityToken
    ) {
        if (previous == null || previous == next) return
        val bucket = synchronized(presentLock) {
            RetiringTelemetryBucket(
                everPublishedTiles = ConcurrentHashMap.newKeySet<NtkStripRenderEngine.TileKey>()
                    .also { it.addAll(everPublishedTiles) },
                traversedPages = traversedPages.toSet(),
                presentedContent = presentedContent.snapshot().map {
                    NtkPresentedContentInterval(it.startPx, it.endPx)
                },
                presentSampleCount = presentTimes.size,
                viewportDefectFrames = viewportDefectFrames.get(),
                runwayDefectFrames = runwayDefectFrames.get(),
                structureEpoch = structureEpoch.get()
            )
        }
        retiringTelemetry[previous] = bucket
        everPublishedTiles.clear()
        traversedPages.clear()
        synchronized(presentLock) {
            presentTimes.clear()
            presentedContent.clear()
        }
        lastMergedFrameKey.set(null)
        viewportDefectFrames.set(0L)
        runwayDefectFrames.set(0L)
        structureEpoch.incrementAndGet()
    }

    fun bind(authority: Long, value: NtkStripGeometry, scrollTopPx: Long): Boolean {
        if (authority <= 0L || value.episode.value != authority || value.pages.isEmpty()) return false
        val target = publishedEngineTargetOrNull() ?: return false
        val previous = currentBinding.get()
        PerfTrace.begin("ViewerItemBind")
        val token = try {
            target.engine.bind(
                authority,
                value,
                height.coerceAtLeast(1),
                scrollTopPx
            )
        } finally {
            PerfTrace.end()
        }
        if (token != null) {
            rotateTelemetryForBinding(previous, token)
            preparedResidency.resetForLegacyBinding()
            geometry = value
            currentBinding.set(token)
        }
        return token != null
    }

    fun bind(
        authority: Long,
        value: NtkStripGeometry,
        scrollTopPx: Long,
        manifestRevision: Long,
        manifestDigest: String,
        geometryDigest: String
    ): Boolean {
        if (authority <= 0L || value.episode.value != authority || value.pages.isEmpty()) {
            return false
        }
        return bindAuthority(
            authority,
            value,
            scrollTopPx,
            manifestRevision,
            manifestDigest,
            geometryDigest
        ) != null
    }

    @Synchronized
    internal fun bindAuthority(
        authority: Long,
        value: NtkStripGeometry,
        scrollTopPx: Long,
        manifestRevision: Long,
        manifestDigest: String,
        geometryDigest: String
    ): NtkNativeAuthorityToken? {
        if (authority <= 0L || value.episode.value != authority || value.pages.isEmpty()) {
            return null
        }
        val target = publishedEngineTargetOrNull() ?: return null
        val previous = currentBinding.get()
        val token = target.engine.bind(
            authority,
            value,
            height.coerceAtLeast(1),
            scrollTopPx,
            manifestRevision,
            manifestDigest,
            geometryDigest
        )
        if (token != null) {
            rotateTelemetryForBinding(previous, token)
            preparedResidency.resetForLegacyBinding()
            geometry = value
            currentBinding.set(token)
        }
        return token
    }

    internal fun installSurfacePrepared(
        install: NtkPreparedTileInstall,
        surfaceToken: NtkSurfacePreparationToken,
        completion: (NtkPreparedTileResidentAck?) -> Unit
    ): Boolean {
        val target = publishedEngineTargetOrNull() ?: return false
        if (surfaceToken.detached != install.token ||
            surfaceToken.surfaceEpoch != target.identity.surfaceEpoch ||
            surfaceToken.attachGeneration != target.identity.attachGeneration ||
            surfaceToken.demandGeneration != target.identity.demandGeneration ||
            surfaceToken.geometryRevision != target.identity.geometryRevision ||
            surfaceToken.width != target.identity.width ||
            surfaceToken.height != target.identity.height
        ) return false
        return target.engine.installSurfacePrepared(install, surfaceToken) { ack ->
            if (ack != null) {
                check(preparedResidency.record(install.token, install.identity, ack)) {
                    "Prepared resident ACK escaped its admitted Surface authority"
                }
            }
            completion(ack)
        }
    }

    @Synchronized
    internal fun adoptDetachedPreparationToPublishedSurface(
        request: NtkGeometryBindRequest,
        value: NtkStripGeometry,
        scrollTopPx: Long
    ): NtkGeometryBindProof? {
        val target = publishedEngineTargetOrNull() ?: return null
        if (request.token.engineGeneration != target.identity.engineGeneration ||
            request.token.authority != value.episode.value ||
            target.identity.demandGeneration <= 0L
        ) return null
        val previous = currentBinding.get()
        val proof = target.engine.adoptDetachedPreparationToSurface(
            request,
            value,
            target.identity,
            scrollTopPx
        ) ?: return null
        val token = target.engine.currentToken() ?: return null
        rotateTelemetryForBinding(previous, token)
        check(preparedResidency.adoptDetached(request.token, request.preparedTileKeys)) {
            "Prepared surface adoption had invalid detached resident inventory"
        }
        geometry = value
        currentBinding.set(token)
        return proof
    }

    fun disarm(authority: Long): Boolean = engine.disarm(authority)

    internal fun releaseAuthority(
        request: NtkAuthorityReleaseRequest,
        completion: (NtkNativeAuthorityReleaseAck) -> Unit
    ): Boolean {
        val currentEngine = liveEngineOrNull()
        val target = if (currentEngine?.engineGeneration == request.token.engineGeneration) {
            currentEngine
        } else {
            retiringEngines[request.token.engineGeneration]
                ?: NtkRetiredProofRegistry.find(request.token.engineGeneration)
        } ?: return false
        return target.releaseAuthority(request) { ack ->
            if (ack.success) {
                retiringTelemetry.remove(request.token)
                val wasCurrent = currentBinding.compareAndSet(request.token, null)
                preparedResidency.release(request.token)
                if (wasCurrent) {
                    geometry = null
                    traversedPages.clear()
                    synchronized(presentLock) {
                        presentTimes.clear()
                        presentedContent.clear()
                    }
                    lastMergedFrameKey.set(null)
                    viewportDefectFrames.set(0L)
                    runwayDefectFrames.set(0L)
                    structureEpoch.incrementAndGet()
                }
            }
            try {
                completion(ack)
            } finally {
                if (ack.success &&
                    ack.disposition == NtkPhysicalReleaseDisposition.CONTEXT_LOST
                ) {
                    NtkReleaseCompletion.dispatch {
                        closeRetiredProofAfterCompletions(target)
                    }
                }
            }
        }
    }

    private fun closeRetiredProofAfterCompletions(target: NtkStripRenderEngine) {
        if (!target.canCloseRetiredProof()) return
        if (target.closeRetiredProofIfComplete()) {
            retiringEngines.remove(target.engineGeneration, target)
            NtkRetiredProofRegistry.remove(target.engineGeneration, target)
        }
    }

    internal fun currentNativeAuthorityToken(): NtkNativeAuthorityToken? = currentBinding.get()

    internal fun currentNativeReleaseToken(): NtkNativeAuthorityToken? =
        liveEngineOrNull()?.currentReleaseToken()

    internal fun currentSurfaceEpoch(): Long =
        publishedSurface.get()?.surfaceEpoch ?: 0L

    /**
     * Samples one coherent active-token/renderer-frame/Surface tuple. A handoff racing any read
     * returns null instead of combining evidence from different authority generations.
     */
    internal fun nativeAuthorityEvidenceSnapshot(): NtkNativeAuthorityEvidenceSnapshot? {
        repeat(3) {
            val token = currentBinding.get() ?: return null
            val target = publishedEngineTargetOrNull() ?: return null
            val sampledEngine = target.engine
            val sampledSurface = target.identity
            if (sampledEngine.engineGeneration != token.engineGeneration) return@repeat
            val frame = sampledEngine.latestFrameSnapshot() ?: return null
            if (sampledEngine !== liveEngineOrNull() || token != currentBinding.get() ||
                sampledSurface !== publishedSurface.get()
            ) return@repeat
            return NtkNativeAuthorityEvidenceSnapshot(
                tokenEngineGeneration = token.engineGeneration,
                tokenAuthorityGeneration = token.authorityGeneration,
                tokenAuthority = token.authority,
                tokenManifestRevision = token.manifestRevision,
                tokenManifestDigest = token.manifestDigest,
                tokenGeometryDigest = token.geometryDigest,
                frameEngineGeneration = frame.engineGeneration,
                frameAuthorityGeneration = frame.authorityGeneration,
                frameAuthority = frame.authority,
                frameSequence = frame.frameSequence,
                frameSceneVersion = frame.sceneVersion,
                surfaceAttached = true,
                surfaceEpoch = sampledSurface.surfaceEpoch,
                frameSurfaceEpoch = frame.surfaceEpoch,
                residentContinuousStartPx = frame.residentContinuousStartPx,
                residentContinuousEndPx = frame.residentContinuousEndPx
            )
        }
        return null
    }

    internal fun nativeHandleIdentityForTesting(): Long =
        liveEngineOrNull()?.nativeHandleIdentityForTesting() ?: 0L

    internal fun currentNativeTokenForTesting(): NtkNativeAuthorityToken? =
        liveEngineOrNull()?.currentToken()

    internal fun engineGenerationForTesting(): Long = when (val slot = engineSlot.get()) {
        is EngineSlot.Live -> slot.engine.engineGeneration
        is EngineSlot.Failed -> slot.generation
    }

    internal fun engineSlotFailedForTesting(): Boolean = engineSlot.get() is EngineSlot.Failed

    internal fun retiringAuthorityCountForTesting(): Int = retiringTelemetry.size

    internal fun lifecycleDebugSnapshotForTesting():
        NtkStripRenderEngine.LifecycleDebugSnapshot? =
        liveEngineOrNull()?.lifecycleDebugSnapshot()

    internal fun schedulerDebugSnapshotForTesting():
        NtkSchedulerDebugSnapshot? =
        liveEngineOrNull()?.schedulerDebugSnapshot()

    internal fun retiredBackendDebugSnapshotForTesting(
        engineGeneration: Long
    ): NtkStripRenderEngine.RetiredBackendDebugSnapshot? {
        val currentEngine = liveEngineOrNull()
        val target = if (engineGeneration == currentEngine?.engineGeneration) {
            currentEngine
        } else {
            retiringEngines[engineGeneration]
                ?: NtkRetiredProofRegistry.find(engineGeneration)
        } ?: return null
        return target.retiredBackendDebugSnapshot()
    }

    internal fun latestContextLossDetachResultForTesting(): NtkNativeDetachResult? =
        latestContextLossDetachResult

    internal fun frozenRetiredTokensForTesting(
        engineGeneration: Long
    ): List<NtkNativeAuthorityToken> {
        val currentEngine = liveEngineOrNull()
        val target = if (engineGeneration == currentEngine?.engineGeneration) {
            currentEngine
        } else {
            retiringEngines[engineGeneration]
                ?: NtkRetiredProofRegistry.find(engineGeneration)
        } ?: return emptyList()
        return target.frozenRetiredTokensForTesting()
    }

    internal fun authorityInventoryDebugSnapshotForTesting(
        token: NtkNativeAuthorityToken
    ): NtkStripRenderEngine.AuthorityInventoryDebugSnapshot? {
        val currentEngine = liveEngineOrNull()
        val target = if (token.engineGeneration == currentEngine?.engineGeneration) {
            currentEngine
        } else {
            retiringEngines[token.engineGeneration]
                ?: NtkRetiredProofRegistry.find(token.engineGeneration)
        } ?: return null
        return target.authorityInventoryDebugSnapshot(token)
    }

    internal fun hasReleasedAuthorityProofForTesting(token: NtkNativeAuthorityToken): Boolean {
        val currentEngine = liveEngineOrNull()
        val target = if (token.engineGeneration == currentEngine?.engineGeneration) {
            currentEngine
        } else {
            retiringEngines[token.engineGeneration]
                ?: NtkRetiredProofRegistry.find(token.engineGeneration)
        } ?: return false
        return target.hasReleasedAuthorityProofForTesting(token)
    }

    internal fun upload(
        command: NtkStripTileInstall,
        completion: (NtkStripTileResidentAck) -> Unit
    ): Boolean {
        val target = publishedEngineTargetOrNull()
        val current = geometry
        val binding = currentBinding.get()
        val tileGeometry = current?.tile(command.key)
        val pageGeometry = current?.pages?.getOrNull(command.key.pageIndex)
        val tile = command.tile
        val valid = command.authority > 0L && command.authority == current?.episode?.value &&
            binding != null && binding.authority == command.authority &&
            tileGeometry != null && pageGeometry != null &&
            ReaderPreparedStore.isCanonicalOriginalProof(
                command.proof,
                pageGeometry.asset.canonicalAsset,
                pageGeometry.asset.sourceWidth,
                pageGeometry.asset.sourceHeight
            ) && tile.sourceTop == tileGeometry.sourceTop &&
            tile.sourceBottom == tileGeometry.sourceBottom &&
            tile.sourceWidth == pageGeometry.asset.sourceWidth &&
            tile.sourceHeight == pageGeometry.asset.sourceHeight &&
            !tile.bitmap.isRecycled && tile.bitmap.config == android.graphics.Bitmap.Config.ARGB_8888 &&
            !tile.bitmap.isMutable && tile.bitmap.width == tile.sourceWidth &&
            tile.bitmap.height == tile.sourceBottom - tile.sourceTop &&
            command.resourceRevision > 0L && command.installLease > 0L &&
            target != null &&
            binding.engineGeneration == target.identity.engineGeneration &&
            command.surfaceEpoch == target.identity.surfaceEpoch &&
            command.admissionId > 0L &&
            command.rgbaBytes == tile.bitmap.width.toLong() * tile.bitmap.height * 4L
        if (!valid) {
            Log.e(
                "NtkStripRenderer",
                "tile validation rejected authority=${command.authority}," +
                    "current=${current?.episode?.value},key=${command.key}," +
                    "geometry=${tileGeometry != null},page=${pageGeometry != null}," +
                    "proof=${pageGeometry != null && ReaderPreparedStore.isCanonicalOriginalProof(command.proof, pageGeometry.asset.canonicalAsset, pageGeometry.asset.sourceWidth, pageGeometry.asset.sourceHeight)}," +
                    "tile=${tile.sourceTop}:${tile.sourceBottom}/${tile.sourceWidth}x${tile.sourceHeight}," +
                    "bitmap=${tile.bitmap.width}x${tile.bitmap.height},config=${tile.bitmap.config}," +
                    "mutable=${tile.bitmap.isMutable},recycled=${tile.bitmap.isRecycled}"
            )
            completion(NtkStripTileResidentAck(
                command.key,
                command.resourceRevision,
                command.installLease,
                command.rgbaBytes,
                0L,
                false,
                command.surfaceEpoch,
                command.admissionId
            ))
            return false
        }
        val boundToken = binding!!
        val identity = NtkStripRenderEngine.InstallIdentity(
            NtkStripRenderEngine.TileKey(
                command.authority,
                command.key.pageIndex,
                command.key.slotIndex
            ),
            boundToken.engineGeneration,
            boundToken.authorityGeneration,
            command.surfaceEpoch,
            command.admissionId,
            command.resourceRevision,
            command.installLease,
            command.rgbaBytes
        )
        return target!!.engine.upload(
            identity,
            tile.bitmap,
            tileGeometry!!.contentTopPx,
            tileGeometry.contentBottomPx,
        ) { result ->
            val callbackBinding = currentBinding.get()
            if (result.success) {
                if (callbackBinding == boundToken) {
                    everPublishedTiles += result.identity.key
                } else {
                    retiringTelemetry[boundToken]?.everPublishedTiles?.add(result.identity.key)
                }
            }
            completion(NtkStripTileResidentAck(
                command.key,
                result.identity.resourceRevision,
                result.identity.installLease,
                result.identity.rgbaBytes,
                result.sceneVersion,
                result.success,
                result.identity.surfaceEpoch,
                result.identity.admissionId
            ))
        }
    }

    internal fun setDesiredCompositorAlpha(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        compositorAlpha = clamped
        // SurfaceView can issue its own layer transaction when a new BLAST buffer arrives. Keep
        // the View's persistent alpha in sync so that transaction cannot restore a staged native
        // layer to alpha=1 after the one-shot SurfaceControl hide below. The direct transaction is
        // still required for the immediate ACTION_DOWN reveal; the View property owns durability.
        if (!compositorTemporarilyHiddenForSurfaceLoss && alpha != clamped) {
            super.setAlpha(clamped)
        }
    }

    internal fun applyPublishedCompositorAlphaRequired(): Boolean {
        val clamped = if (compositorTemporarilyHiddenForSurfaceLoss) {
            0f
        } else {
            compositorAlpha
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isAttachedToWindow) {
            Log.e(
                "NtkStripRenderer",
                "fatal compositor alpha fallback forbidden attached=$isAttachedToWindow " +
                    "surfaceValid=false api=${Build.VERSION.SDK_INT}"
            )
            return false
        }
        if (alpha != clamped) super.setAlpha(clamped)
        return applyPublishedCompositorAlphaApi29(clamped)
    }

    /**
     * A holder loss hides only the retiring physical layer.  It must not overwrite the desired
     * ACTIVE/STAGED alpha because the next holder belongs to the same logical strip session.
     * Keeping the View property at zero until publish also prevents the replacement BLAST layer
     * from flashing black before the first authoritative child buffer is attached.
     */
    private fun hideCompositorForSurfaceLoss() {
        compositorTemporarilyHiddenForSurfaceLoss = true
        compositorRevealDispatchGate.clear()
        if (alpha != 0f) super.setAlpha(0f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isAttachedToWindow) return
        val control: SurfaceControl? = surfaceControl
        val validControl = control?.takeIf { it.isValid } ?: return
        val transaction = SurfaceControl.Transaction()
        try {
            transaction.setAlpha(validControl, 0f).apply()
        } catch (failure: RuntimeException) {
            // surfaceDestroyed can invalidate the old control between the validity check and
            // apply. The persistent View alpha above still owns replacement-layer visibility.
            Log.d("NtkStripRenderer", "surface-loss alpha transaction raced teardown", failure)
        } finally {
            transaction.close()
        }
    }

    private fun scheduleCompositorRevealAfterFreshFrame(
        identity: NtkPublishedSurfaceIdentity
    ) {
        if (!compositorTemporarilyHiddenForSurfaceLoss) return
        val offer = compositorRevealDispatchGate.offer(identity.compositorRevealIdentity())
        if (!offer.shouldPost) return
        AppDispatchers.runOnMain {
            val requestedIdentity = compositorRevealDispatchGate.take(offer.reservation)
                ?: return@runOnMain
            if (!compositorTemporarilyHiddenForSurfaceLoss ||
                publishedSurface.get()?.let {
                    it.engineGeneration == requestedIdentity.engineGeneration &&
                        it.attachGeneration == requestedIdentity.attachGeneration &&
                        it.surfaceEpoch == requestedIdentity.surfaceEpoch
                } != true
            ) return@runOnMain
            compositorTemporarilyHiddenForSurfaceLoss = false
            val desired = compositorAlpha
            if (alpha != desired) super.setAlpha(desired)
            if (!applyPublishedCompositorAlphaApi29(desired)) {
                terminalSurfaceFailure(
                    SurfaceFailureEvent(
                        requestedIdentity.engineGeneration,
                        requestedIdentity.attachGeneration,
                        requestedIdentity.surfaceEpoch,
                        NtkSurfaceAttachFailure.COMPOSITOR_ALPHA_FAILED,
                        publishedSurface.get()?.demandGeneration ?: 0L
                    )
                )
            }
        }
    }

    private fun NtkPublishedSurfaceIdentity.compositorRevealIdentity() =
        NtkCompositorRevealDispatchGate.Identity(
            engineGeneration = engineGeneration,
            attachGeneration = attachGeneration,
            surfaceEpoch = surfaceEpoch,
        )

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun applyPublishedCompositorAlphaApi29(clamped: Float): Boolean {
        // SurfaceView.getSurfaceControl() is a platform type and can transiently return null
        // after detach even though the SDK signature is exposed to Kotlin as non-null.
        val control: SurfaceControl? = surfaceControl
        val validControl = control?.takeIf { it.isValid }
        val surfaceValid = validControl != null
        if (validControl != null) {
            val transaction = SurfaceControl.Transaction()
            try {
                transaction.setAlpha(validControl, clamped).apply()
                return true
            } catch (failure: RuntimeException) {
                Log.e("NtkStripRenderer", "compositor alpha transaction failed", failure)
            } finally {
                transaction.close()
            }
        }
        Log.e(
            "NtkStripRenderer",
            "fatal compositor alpha fallback forbidden attached=$isAttachedToWindow " +
                "surfaceValid=$surfaceValid api=${Build.VERSION.SDK_INT}"
        )
        return false
    }

    internal fun setCompositorAlpha(value: Float): Boolean {
        setDesiredCompositorAlpha(value)
        return if (publishedSurface.get() == null) true
        else applyPublishedCompositorAlphaRequired()
    }

    internal fun currentDemandGeneration(): Long =
        installPermit.get()?.demandGeneration ?: 0L

    internal fun retargetReusableDemand(permit: NtkSurfaceInstallPermit): Boolean {
        checkMainThread()
        val target = liveEngineOrNull() ?: return false
        val identity = publishedSurface.get() ?: return false
        if (!permit.isExact || permit.engineGeneration != target.engineGeneration ||
            identity.engineGeneration != target.engineGeneration ||
            engineSurfaceState !is EngineSurfaceState.Published ||
            currentBinding.get() != null || target.currentReleaseToken() != null ||
            viewClosing || terminalSurfaceFailure != null
        ) return false
        installPermit.set(permit)
        val retargeted = identity.copy(demandGeneration = permit.demandGeneration)
        if (!publishedSurface.compareAndSet(identity, retargeted)) return false
        engineSurfaceState = EngineSurfaceState.Published(retargeted)
        surfaceLifecycleListener?.onSurfaceAvailable(retargeted)
        return true
    }

    internal fun liveEngineGeneration(): Long =
        liveEngineOrNull()?.engineGeneration ?: 0L

    internal fun livePreparationEngine(expectedGeneration: Long): NtkStripRenderEngine? =
        liveEngineOrNull()?.takeIf { it.engineGeneration == expectedGeneration }

    internal fun targetLifecycleDebugSnapshot(): TargetLifecycleDebugSnapshot {
        val permit = installPermit.get()
        return TargetLifecycleDebugSnapshot(
            demandGeneration = permit?.demandGeneration ?: 0L,
            engineGeneration = liveEngineGeneration(),
            holderCreatedNanos = holderCreatedNanos,
            surfaceLeaseAcquiredNanos = surfaceLeaseAcquiredNanos,
            attachReadyNanos = attachReadyNanos,
            surfacePublishedNanos = surfacePublishedNanos,
            holderCreatedCount = holderCreatedCount,
            surfaceLeaseAcquireCount = surfaceLeaseAcquireCount,
            attachReadyCount = attachReadyCount,
            surfacePublishCount = surfacePublishCount
        )
    }

    internal fun startupLifecycleDebugSnapshot():
        NtkStripRenderEngine.StartupLifecycleDebugSnapshot? =
        liveEngineOrNull()?.startupLifecycleDebugSnapshot()

    internal fun revokeInstallPermit(demandGeneration: Long): Boolean {
        val current = installPermit.get() ?: return false
        if (current.demandGeneration != demandGeneration) return false
        return installPermit.compareAndSet(current, null)
    }

    internal fun commitProtection(
        commit: NtkStripProtectionCommit,
        completion: (NtkStripProtectionAck) -> Unit
    ): Boolean {
        val target = publishedEngineTargetOrNull()
        val current = geometry
        if (target == null || current == null ||
            commit.authority != current.episode.value ||
            commit.surfaceEpoch != target.identity.surfaceEpoch
        ) {
            completion(NtkStripProtectionAck(commit, 0L, false))
            return false
        }
        return target.engine.commitProtection(commit, completion)
    }

    internal fun retire(
        intent: NtkStripRetireIntent,
        result: (NtkStripRetireResultAck) -> Unit,
        freed: (NtkStripTileFreedAck) -> Unit
    ): Boolean {
        val target = publishedEngineTargetOrNull()
        val current = geometry
        if (target == null || current == null ||
            intent.authority != current.episode.value ||
            intent.policySurfaceEpoch != target.identity.surfaceEpoch ||
            current.tile(intent.key) == null
        ) {
            result(NtkStripRetireResult(
                intent,
                NtkStripRetireResultCode.FAILED,
                target?.engine?.latestFrameSnapshot()?.sceneVersion ?: 0L
            ))
            return false
        }
        return target.engine.retire(intent, result, freed)
    }

    /**
     * Native-boundary race hook. Production callers use [retire], which duplicates the current
     * Surface epoch check before JNI. This hook deliberately skips only that Java precheck so
     * instrumentation can prove the native epoch gate rejects an old positive epoch without
     * mutating the recreated scene.
     */
    internal fun retireUncheckedForTesting(
        intent: NtkStripRetireIntent,
        result: (NtkStripRetireResultAck) -> Unit,
        freed: (NtkStripTileFreedAck) -> Unit
    ): Boolean = engine.retire(intent, result, freed)

    fun stage(
        authority: Long,
        corridorStartPx: Long,
        corridorEndPx: Long,
        stageNonce: Long,
        manifestRevision: Long,
        manifestDigest: String,
        geometryDigest: String,
        completion: (NtkStageProof?) -> Unit
    ): Boolean = engine.stage(
        authority,
        corridorStartPx,
        corridorEndPx,
        stageNonce,
        manifestRevision,
        manifestDigest,
        geometryDigest,
        completion
    )

    fun activate(authority: Long, stageNonce: Long): Boolean =
        engine.activate(authority, stageNonce)

    internal fun latestFrameSnapshot(): NtkStripRenderEngine.FrameSnapshot? =
        engine.latestFrameSnapshot()

    internal fun setContextLossForTesting() = engine.setContextLossForTesting()

    internal fun setContextLossDuringDetachForTesting() =
        engine.setContextLossDuringDetachForTesting()

    internal fun resetInputTelemetry() = engine.resetInputTelemetry()

    internal fun firstMainIngressNanos(): Long = engine.firstMainIngressNanos()

    internal fun latestSuccessfulSwapInputEventNanos(): Long =
        engine.latestSuccessfulSwapInputEventNanos()

    internal fun latestDeliveredLatchedInputEventNanos(): Long =
        engine.latestDeliveredLatchedInputEventNanos()

    internal fun progressPosition(): ReaderSurfaceView.ProgressPosition? {
        val currentGeometry = geometry ?: return null
        val scroll = engine.latestFrameSnapshot()?.scrollTopPx ?: 0L
        val page = currentGeometry.pages.indexOfLast { it.contentTopPx <= scroll }
            .coerceAtLeast(0)
            .coerceAtMost(currentGeometry.pages.lastIndex)
        val offset = (scroll - currentGeometry.pages[page].contentTopPx)
            .coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ReaderSurfaceView.ProgressPosition(page, offset)
    }

    internal fun scrollPositionSnapshot(): ReaderSurfaceView.ScrollPositionSnapshot? {
        val currentGeometry = geometry ?: return null
        val frame = engine.latestFrameSnapshot()
        val progress = progressPosition() ?: return null
        val scroll = frame?.scrollTopPx ?: 0L
        return ReaderSurfaceView.ScrollPositionSnapshot(
            progress.page,
            progress.offset,
            scroll.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            currentGeometry.contentHeightPx.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            max(0L, currentGeometry.contentHeightPx - height.coerceAtLeast(1))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            false
        )
    }

    internal fun currentResidencySnapshot(): NtkCurrentResidencySnapshot {
        val currentGeometry = geometry
        val frame = engine.latestFrameSnapshot()
        if (currentGeometry == null) {
            return NtkCurrentResidencySnapshot(
                emptySet(),
                NtkResidencyAccounting(),
                0L,
                0L
            )
        }
        // The pipeline reducer owns exact tile cycles/accounting. Surface telemetry exposes only
        // the renderer's merged continuous interval and must never reconstruct scheduling state.
        return NtkCurrentResidencySnapshot(
            emptySet(),
            NtkResidencyAccounting(),
            frame?.residentContinuousStartPx ?: 0L,
            frame?.residentContinuousEndPx ?: 0L
        )
    }

    internal fun isPageRangeFullyResident(startPage: Int, count: Int): Boolean {
        val currentGeometry = geometry ?: return false
        if (startPage < 0 || count <= 0 || startPage + count > currentGeometry.pages.size) {
            return false
        }
        val frame = engine.latestFrameSnapshot() ?: return false
        if (frame.authority != currentGeometry.episode.value) return false
        val start = currentGeometry.pages[startPage].contentTopPx
        val end = currentGeometry.pages[startPage + count - 1].contentBottomPx
        return frame.residentContinuousStartPx <= start && frame.residentContinuousEndPx >= end
    }

    /** Compatibility projection; terminal qualification must use [episodeProofSnapshot]. */
    internal fun pageReadinessSnapshot(): ReaderSurfaceView.PageReadinessSnapshot {
        val currentGeometry = geometry
            ?: return ReaderSurfaceView.PageReadinessSnapshot(0, 0, 0, 0, 0, 0)
        val unresolved = ArrayList<Int>()
        for (page in currentGeometry.pages) {
            if (!page.tiles.all { tile ->
                    everPublishedTiles.contains(
                        NtkStripRenderEngine.TileKey(
                            currentGeometry.episode.value,
                            tile.key.pageIndex,
                            tile.key.slotIndex
                        )
                    )
                }
            ) unresolved += page.asset.pageIndex
        }
        return ReaderSurfaceView.PageReadinessSnapshot(
            currentGeometry.pages.size,
            currentGeometry.pages.size - unresolved.size,
            0,
            0,
            0,
            unresolved.size,
            "",
            unresolved.joinToString(",")
        )
    }

    internal fun episodeProofSnapshot(): NtkEpisodeProofSnapshot {
        val currentGeometry = geometry
        if (currentGeometry == null) {
            val emptyDigest = NtkStripDigests.sha256Tokens("empty-native-strip-proof")
            return NtkEpisodeProofSnapshot(
                manifestRevision = 0L,
                manifestDigest = emptyDigest,
                geometryDigest = emptyDigest,
                geometryTileCount = 0,
                contentHeightPx = 0L,
                manifestPages = 0,
                metadataPages = 0,
                sourceOriginalProofPages = 0,
                drawableProofPages = 0,
                everDecodedTiles = emptySet(),
                everPublishedTiles = emptySet(),
                presentedContentIntervals = emptyList(),
                presentedPages = emptySet(),
                traversalCommittedPages = 0,
                traversalMissingPages = emptySet(),
                viewportDefectFrames = viewportDefectFrames.get(),
                runwayDefectFrames = runwayDefectFrames.get(),
                preSubmitViewportGap = getPreSubmitViewportGap(),
                currentAccounting = NtkResidencyAccounting(),
                peakCpuChargedBytes = 0L,
                peakCpuDecodedBytes = 0L,
                cpuTransientHardCapBytes = Long.MAX_VALUE,
                gpuSceneCapacityProof = null,
                exactEpisodeEnd = false
            )
        }
        val everKeys = everPublishedTiles.mapTo(LinkedHashSet()) { key ->
            NtkStripTileKey(currentGeometry.episode, key.page, key.slot)
        }
        val drawablePages = currentGeometry.pages.count { page ->
            page.tiles.all { it.key in everKeys }
        }
        val intervals = synchronized(presentLock) {
            presentedContent.snapshot().map {
                NtkPresentedContentInterval(it.startPx, it.endPx)
            }
        }
        return NtkEpisodeProofSnapshot(
            manifestRevision = currentGeometry.manifestRevision,
            manifestDigest = currentGeometry.manifestDigest,
            geometryDigest = currentGeometry.geometryDigest,
            geometryTileCount = currentGeometry.tileCount,
            contentHeightPx = currentGeometry.contentHeightPx,
            manifestPages = currentGeometry.pages.size,
            metadataPages = 0,
            sourceOriginalProofPages = 0,
            drawableProofPages = drawablePages,
            everDecodedTiles = emptySet(),
            everPublishedTiles = everKeys,
            presentedContentIntervals = intervals,
            presentedPages = traversedPages.toSet(),
            traversalCommittedPages = traversedPages.size,
            traversalMissingPages = currentGeometry.pages.indices
                .filterNot { it in traversedPages }
                .toSet(),
            viewportDefectFrames = viewportDefectFrames.get(),
            runwayDefectFrames = runwayDefectFrames.get(),
            preSubmitViewportGap = getPreSubmitViewportGap(),
            currentAccounting = NtkResidencyAccounting(),
            peakCpuChargedBytes = 0L,
            peakCpuDecodedBytes = 0L,
            cpuTransientHardCapBytes = Long.MAX_VALUE,
            gpuSceneCapacityProof = null,
            exactEpisodeEnd = ntkExactEpisodeEndFromPresentation(
                false,
                currentGeometry.contentHeightPx,
                intervals
            )
        )
    }

    internal fun visibleCoverageSnapshot(): ReaderSurfaceView.VisibleCoverageSnapshot? {
        val currentGeometry = geometry ?: return null
        val frame = engine.latestFrameSnapshot() ?: return null
        if (frame.authority != currentGeometry.episode.value) return null
        val viewport = height.coerceAtLeast(1)
        val viewportBottom = minOf(currentGeometry.contentHeightPx, frame.scrollTopPx + viewport)
        val physicalViewport = (viewportBottom - frame.scrollTopPx).coerceAtLeast(0L).toInt()
        val drawable = (minOf(frame.residentContinuousEndPx, viewportBottom) - frame.scrollTopPx)
            .coerceAtLeast(0L).toInt()
        val visibleTiles = currentGeometry.pages.asSequence().flatMap { it.tiles.asSequence() }
            .filter { it.contentBottomPx > frame.scrollTopPx && it.contentTopPx < viewportBottom }
            .toList()
        val drawableItems = visibleTiles.count { tile ->
            tile.contentTopPx >= frame.residentContinuousStartPx &&
                tile.contentBottomPx <= frame.residentContinuousEndPx
        }
        val visiblePages = currentGeometry.pages.filter {
            it.contentBottomPx > frame.scrollTopPx && it.contentTopPx < viewportBottom
        }
        return ReaderSurfaceView.VisibleCoverageSnapshot(
            viewport,
            drawable,
            max(0, physicalViewport - drawable),
            0,
            drawableItems,
            visibleTiles.size,
            0,
            0,
            0,
            false,
            currentGeometry.pages.size,
            if (drawable == physicalViewport) 0 else 1,
            0,
            visiblePages.minOfOrNull { it.asset.sourceWidth } ?: 0,
            physicalViewport
        )
    }

    internal fun forwardRunwaySnapshot(aheadViewports: Float): ReaderSurfaceView.ForwardRunwaySnapshot? {
        val currentGeometry = geometry ?: return null
        val frame = engine.latestFrameSnapshot() ?: return null
        val viewport = height.coerceAtLeast(1)
        val required = ceil(viewport * aheadViewports.coerceAtLeast(0f)).toInt()
        val viewportBottom = minOf(currentGeometry.contentHeightPx, frame.scrollTopPx + viewport)
        val available = (currentGeometry.contentHeightPx - viewportBottom)
            .coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val effectiveRequired = minOf(required, available)
        val drawable = (frame.residentContinuousEndPx - viewportBottom)
            .coerceAtLeast(0L).coerceAtMost(effectiveRequired.toLong()).toInt()
        return ReaderSurfaceView.ForwardRunwaySnapshot(
            effectiveRequired,
            available,
            drawable,
            max(0, effectiveRequired - drawable),
            0,
            if (drawable < effectiveRequired) progressPosition()?.page ?: -1 else -1,
            available < required
        )
    }

    internal fun traversalSnapshot(): ReaderSurfaceView.TraversalSnapshot {
        val currentGeometry = geometry
        val expected = currentGeometry?.pages?.size ?: 0
        val committed = traversedPages.filter { it in 0 until expected }.sorted()
        val missing = (0 until expected).filterNot { it in traversedPages }
        val frames = synchronized(presentLock) { presentTimes.size.toLong() }
        return ReaderSurfaceView.TraversalSnapshot(
            structureEpoch.get(),
            expected,
            committed.size,
            committed.joinToString(","),
            missing.joinToString(","),
            frames,
            frames,
            0,
            0,
            0,
            0
        )
    }

    internal fun resetFrameStats() {
        beginSchema11InteractionWindow()
        synchronized(presentLock) { presentTimes.clear() }
        schema10EvidenceFrames.set(0L)
        schema10IdentityInvalidFrames.set(0L)
        surfaceControlLatchInvalidFrames.set(0L)
        externalSubmissionInvalidFrames.set(0L)
        hardwareBufferIdentityInvalidFrames.set(0L)
    }

    internal fun beginSchema11InteractionWindow() {
        schema11QualificationAccumulator.beginInteractionWindow()
    }

    internal fun schema11QualificationSnapshot(
        acceptedTerminalInputSequences: LongArray,
        acceptedTerminalInputCount: Int,
        acceptedTerminalInputOverflow: Boolean
    ): NtkSchema11QualificationSnapshot = schema11QualificationAccumulator.snapshot(
        acceptedTerminalInputSequences,
        acceptedTerminalInputCount,
        acceptedTerminalInputOverflow
    )

    internal fun frameStatsSnapshot(): ReaderSurfaceView.FrameStatsSnapshot {
        val times = synchronized(presentLock) { presentTimes.values.toList() }
        val refreshRate = display?.refreshRate?.takeIf { it > 0f } ?: 60f
        val intervalMs = 1000f / refreshRate
        val intervalNanos = (1_000_000_000.0 / refreshRate).toLong()
        val deltaSamples = times.zipWithNext().mapNotNull { (first, second) ->
            if (!areAdjacentNtkFrames(
                    NtkFrameOrderKey(first.surfaceEpoch, first.frameSequence),
                    NtkFrameOrderKey(second.surfaceEpoch, second.frameSequence)
                ) || first.gestureId <= 0L || first.gestureId != second.gestureId ||
                first.presentationSupported != second.presentationSupported
            ) return@mapNotNull null
            val firstPacingTime = if (first.presentationSupported) {
                first.presentedAtNanos
            } else {
                first.compositionLatchNanos
            }
            val secondPacingTime = if (second.presentationSupported) {
                second.presentedAtNanos
            } else {
                second.compositionLatchNanos
            }
            if (firstPacingTime <= 0L || secondPacingTime <= firstPacingTime) null
            else {
                val deltaMs = (secondPacingTime - firstPacingTime) / 1_000_000f
                val hasBackgroundWork = second.integratedTiles > 0 ||
                    second.uploadCommandsSubmitting > 0 ||
                    second.uploadGpuFencesPending > 0 ||
                    second.timestampQueryWorkNanos > 0L || !second.gpuInvariantValid
                if (deltaMs > intervalMs * 1.5f) {
                    fun phaseMs(end: Long, start: Long): Float =
                        if (start <= 0L || end < start) -1f
                        else (end - start) / 1_000_000f
                    Log.e(
                        "ViewerPerf",
                        "ntk_strip_pacing_miss firstSeq=${first.frameSequence}" +
                            ",secondSeq=${second.frameSequence},deltaMs=$deltaMs" +
                            ",gesture=${second.gestureId},scroll=${second.scrollTopPx}" +
                            ",integrated=${second.integratedTiles}" +
                            ",uploadSubmitting=${second.uploadCommandsSubmitting}" +
                            ",uploadFences=${second.uploadGpuFencesPending}" +
                            ",timestampQueryMs=${second.timestampQueryWorkNanos / 1_000_000f}" +
                            ",swapMs=${phaseMs(second.postSwapNanos, second.preSwapNanos)}" +
                            ",targetWaitMs=${phaseMs(second.targetReachedNanos, second.preWaitNanos)}" +
                            ",drawToBackendCompletionCallbackMs=${phaseMs(second.fenceCompleteNanos, second.drawBeginNanos)}" +
                            ",backendCompletionCallbackToPreSwapMs=${phaseMs(second.preSwapNanos, second.fenceCompleteNanos)}" +
                            ",mutationToQueueMs=${phaseMs(second.queueSubmitNanos, second.mutationNewestNanos)}" +
                            ",queueToLatchMs=${phaseMs(second.compositionLatchNanos, second.queueSubmitNanos)}" +
                            ",surfaceEpoch=${second.surfaceEpoch},frameId=${second.frameId}" +
                            ",latchProofState=${second.latchProofState}" +
                            ",logicalUnlatched=${second.logicalUnlatchedSubmissions}" +
                            ",maxLogicalUnlatched=${second.maxLogicalUnlatchedSubmissions}" +
                            ",oldestUnlatchedAgeMs=${second.oldestUnlatchedAgeNanos / 1_000_000f}" +
                            ",latchQueryError=${second.latchQueryError}" +
                            ",latchEvidenceDeadlineNs=${second.latchEvidenceDeadlineNanos}" +
                            ",cadenceQualificationFailed=${second.cadenceQualificationFailed}"
                    )
                }
                Pair(deltaMs, hasBackgroundWork)
            }
        }
        // Composition-latch proof is retrospective and may become LOST on a backend with a
        // short EGL history window. Functional UX cadence therefore has its own honest signal:
        // spacing between successful native swap submissions for adjacent frames of the same
        // physical gesture. It neither substitutes for nor modifies the hard latch verdict.
        val functionalSubmissionPairs = times.zipWithNext().filter { (first, second) ->
            first.gestureId > 0L && first.gestureId == second.gestureId
        }
        val functionalFramePairs = functionalSubmissionPairs.mapNotNull { (first, second) ->
            functionalFramePair(
                first,
                NtkFrameOrderKey(first.surfaceEpoch, first.frameSequence),
                first.gestureId,
                first.postSwapNanos,
                second,
                NtkFrameOrderKey(second.surfaceEpoch, second.frameSequence),
                second.gestureId,
                second.postSwapNanos
            )
        }
        val functionalSubmissionDeltaNanos = functionalFramePairs.map { it.submissionDeltaNanos }
        val functionalSubmissionDeltas = functionalSubmissionDeltaNanos.map { it / 1_000_000f }
        val functionalSubmissionInvalidPairs =
            functionalSubmissionPairs.size - functionalSubmissionDeltaNanos.size
        val functionalInputGestures = times.asSequence()
            .map { it.gestureId }
            .filter { it > 0L }
            .distinct()
            .count()
        val functionalGesturesWithValidPair = functionalFramePairs.asSequence()
            .map { it.first.gestureId }
            .distinct()
            .count()
        val functionalInputFrames = times.filter { it.gestureId > 0L }
        // Legacy FrameStatsSnapshot wire fields retain the functionalCpuSubmitWork name, but
        // drawBegin -> postSwap includes backend work and is only a draw-to-swap-return diagnostic.
        val functionalDrawToSwapReturnDiagnostic = functionalInputFrames.mapNotNull { frame ->
            if (frame.drawBeginNanos <= 0L || frame.postSwapNanos < frame.drawBeginNanos) null
            else (frame.postSwapNanos - frame.drawBeginNanos) / 1_000_000f
        }
        val functionalDrawIssue = functionalInputFrames.mapNotNull { frame ->
            if (frame.drawBeginNanos <= 0L || frame.preSwapNanos < frame.drawBeginNanos) null
            else (frame.preSwapNanos - frame.drawBeginNanos) / 1_000_000f
        }
        val functionalSwapCallNanos = functionalInputFrames.mapNotNull { frame ->
            if (frame.preSwapNanos <= 0L || frame.postSwapNanos < frame.preSwapNanos) null
            else frame.postSwapNanos - frame.preSwapNanos
        }
        val functionalSwapCall = functionalSwapCallNanos.map { it / 1_000_000f }
        val functionalPhaseDecompositions = functionalFramePairs.mapNotNull { pair ->
            functionalPhaseDecompositionNanos(
                FunctionalFramePhaseTimestamps(
                    pair.first.drawBeginNanos,
                    pair.first.backendWaitReturnNanos,
                    pair.first.preSwapNanos,
                    pair.first.postSwapNanos,
                    pair.first.postWaitNanos
                ),
                FunctionalFramePhaseTimestamps(
                    pair.second.drawBeginNanos,
                    pair.second.backendWaitReturnNanos,
                    pair.second.preSwapNanos,
                    pair.second.postSwapNanos,
                    pair.second.postWaitNanos
                ),
                pair.submissionDeltaNanos
            )
        }
        val functionalRendererReadyToQueue = functionalPhaseDecompositions.map {
            it.rendererReadyToQueueNanos / 1_000_000f
        }
        val functionalNextWorkStartDelay = functionalPhaseDecompositions.map {
            it.nextWorkStartDelayNanos / 1_000_000f
        }
        val functionalBackendPreparation = functionalPhaseDecompositions.map {
            it.backendPreparationNanos / 1_000_000f
        }
        val functionalResidualPriorTargetGate = functionalPhaseDecompositions.map {
            it.residualPriorTargetGateNanos / 1_000_000f
        }
        val functionalPhaseAdmissionAfterBothReady = functionalPhaseDecompositions.map {
            it.phaseAdmissionAfterBothReadyNanos / 1_000_000f
        }
        val functionalPreparationOverlap = functionalPhaseDecompositions.map {
            it.preparationOverlapNanos / 1_000_000f
        }
        val functionalTargetRetirement = functionalInputFrames.mapNotNull { frame ->
            if (frame.postSwapNanos <= 0L || frame.postWaitNanos < frame.postSwapNanos) null
            else (frame.postWaitNanos - frame.postSwapNanos) / 1_000_000f
        }
        val rendererReadyFrameDebt = functionalRendererReadyFrameDebt(
            functionalPhaseDecompositions.map { it.rendererReadyToQueueNanos }
        )
        val deltas = deltaSamples.map { it.first }
        val missed = deltas.count { it > intervalMs * 1.5f }
        val dropped = deltas.sumOf { delta ->
            max(0, kotlin.math.round(delta / intervalMs).toInt() - 1)
        }
        val sorted = deltas.sorted()
        fun percentile(values: List<Float>, percentile: Int): Float = if (values.isEmpty()) 0f
            else values.sorted()[(kotlin.math.ceil(values.size * percentile / 100.0).toInt() - 1)
                .coerceIn(values.indices)]
        fun percentile95(values: List<Float>): Float = percentile(values, 95)
        val maximum = sorted.lastOrNull() ?: 0f
        val functionalSubmissionMissedFrames = functionalSubmissionDeltas.count { delta ->
            delta > intervalMs * 1.5f
        }
        val functionalSubmissionDroppedFrames = functionalSubmissionDeltas.sumOf { delta ->
            max(0, kotlin.math.round(delta / intervalMs).toInt() - 1)
        }
        val functionalSubmissionPauseFrames = countFunctionalSubmissionPauses(
            functionalSubmissionDeltaNanos,
            50_000_000L
        )
        val functionalSubmissionMaxOverBudgetStreak =
            functionalSubmissionMaxOverBudgetStreak(
                functionalFramePairs.map {
                    FunctionalGestureDelta(it.first.gestureId, it.submissionDeltaNanos)
                },
                (intervalNanos * 3L) / 2L
            )
        fun causalAgeMs(sampleTime: Long, causeTime: Long): Float? {
            if (causeTime <= 0L || sampleTime < causeTime) return null
            return (sampleTime - causeTime) / 1_000_000f
        }
        fun positiveDeltaMs(endTime: Long, startTime: Long): Float? {
            if (startTime <= 0L || endTime < startTime) return null
            return (endTime - startTime) / 1_000_000f
        }
        val inputOldest = times.mapNotNull {
            causalAgeMs(it.presentedAtNanos, it.inputOldestNanos)
        }
        val inputNewest = times.mapNotNull {
            causalAgeMs(it.presentedAtNanos, it.inputNewestNanos)
        }
        val mutationOldest = times.mapNotNull {
            causalAgeMs(it.presentedAtNanos, it.mutationOldestNanos)
        }
        val mutationNewest = times.mapNotNull {
            causalAgeMs(it.presentedAtNanos, it.mutationNewestNanos)
        }
        val eventToReceipt = times.mapNotNull {
            positiveDeltaMs(it.receiptNewestNanos, it.inputNewestNanos)
        }
        val eventToMainIngress = times.mapNotNull {
            positiveDeltaMs(it.mainIngressNewestNanos, it.inputNewestNanos)
        }
        val mainIngressToReceipt = times.mapNotNull {
            positiveDeltaMs(it.receiptNewestNanos, it.mainIngressNewestNanos)
        }
        val receiptToMutation = times.mapNotNull {
            positiveDeltaMs(it.mutationNewestNanos, it.receiptNewestNanos)
        }
        val mutationToQueue = times.mapNotNull {
            positiveDeltaMs(it.queueSubmitNanos, it.mutationNewestNanos)
        }
        val queueToComposition = times.mapNotNull {
            positiveDeltaMs(it.compositionLatchNanos, it.queueSubmitNanos)
        }
        val backendCompletionToQueue = times.mapNotNull {
            positiveDeltaMs(it.queueSubmitNanos, it.backendCompletionSignalNanos)
        }
        val compositionToPresent = times.mapNotNull {
            positiveDeltaMs(it.presentedAtNanos, it.compositionLatchNanos)
        }
        val unsupportedPresentationFrames = times.count { !it.presentationSupported }
        val invalidSwapIntervalFrames = times.count {
            it.swapIntervalNanos <= 0L ||
                kotlin.math.abs(it.swapIntervalNanos - intervalNanos) > 100_000L
        }
        val latest = times.lastOrNull()
        val lastQueryError = times.lastOrNull {
            it.latchQueryError != EGL_SUCCESS
        }?.latchQueryError ?: latest?.latchQueryError ?: 0
        val fixedPhaseTelemetryInvalidFrames = times.count {
            !it.fixedPhaseTelemetryValid || !it.fixedPhasePlanValid ||
                it.fixedBackendReadyNanos <= 0L ||
                it.fixedFirstCommitAttemptNanos < it.fixedBackendReadyNanos ||
                it.fixedTimestampQueryBeforeFirstCommitCount != 0
        }
        val fixedPhaseHardPostFailureFrames = times.count {
            it.fixedPhaseFatalReason == FIXED_PHASE_SWAP_DURATION_INVALID ||
                it.fixedPhaseFatalReason == FIXED_PHASE_SWAP_MISSED_CUTOFF
        }
        val fixedPhaseUnexpectedFatalFrames = times.count {
            it.fixedPhaseFatalReason != 0 &&
                it.fixedPhaseFatalReason != FIXED_PHASE_SWAP_DURATION_INVALID &&
                it.fixedPhaseFatalReason != FIXED_PHASE_SWAP_MISSED_CUTOFF
        }
        val physicalTargetWait = times.mapNotNull {
            if (it.preWaitNanos <= 0L || it.targetReachedNanos <= 0L) null
            else max(0L, it.targetReachedNanos - it.preWaitNanos) / 1_000_000f
        }
        val retirementPublication = times.mapNotNull {
            if (it.targetReachedNanos <= 0L ||
                it.fixedRetirementPublishNanos < it.targetReachedNanos
            ) null else (it.fixedRetirementPublishNanos - it.targetReachedNanos) / 1_000_000f
        }
        val opportunityReceiptToDecision = times.mapNotNull {
            if (it.fixedPhaseOpportunityReceiptNanos <= 0L ||
                it.fixedPhaseDecisionNanos < it.fixedPhaseOpportunityReceiptNanos
            ) null else (it.fixedPhaseDecisionNanos -
                it.fixedPhaseOpportunityReceiptNanos) / 1_000_000f
        }
        fun demandInvalid(issued: Long, satisfied: Long, cancelled: Long): Boolean {
            if (issued < satisfied || issued - satisfied < cancelled) return true
            return issued - satisfied - cancelled !in 0L..1L
        }
        val fixedDemandConservationInvalidFrames = times.count {
            demandInvalid(
                it.fixedRetirementDemandIssued,
                it.fixedRetirementDemandSatisfied,
                it.fixedRetirementDemandCancelled
            ) || demandInvalid(
                it.fixedOpportunityDemandIssued,
                it.fixedOpportunityDemandSatisfied,
                it.fixedOpportunityDemandCancelled
            ) || it.fixedRetirementRecordDemandIssued !=
                it.fixedRetirementRecordDemandSatisfied +
                    it.fixedRetirementRecordDemandCancelled
        }
        val fixedOpportunityIdentityInvalidFrames = times.count {
            it.fixedPhaseReservationSequence <= 0L ||
                it.fixedPhaseOpportunitySequence <= 0L ||
                it.fixedPhaseOpportunityKind !in 1..2 ||
                it.fixedPhaseReservationNanos <= 0L ||
                it.fixedPhaseOpportunityReceiptNanos <= 0L ||
                NtkFixedPredecessorIdentity.invalid(
                    it.swappyWorkGeneration,
                    it.swappyAdmissionSequence,
                    it.fixedPriorRetirementWorkGeneration,
                    it.fixedPriorRetirementAdmissionSequence,
                    it.fixedPriorRetirementSequence
                )
        }
        val fixedOpportunityWakeLostFrames = times.count {
            it.fixedPhaseOpportunityPublishNanos <
                it.fixedPhaseOpportunityReceiptNanos ||
                it.fixedPhaseOpportunityPublishNanos <
                    it.fixedPhaseReservationNanos ||
                it.fixedPhaseRendererWakeObservedNanos <
                    it.fixedPhaseOpportunityPublishNanos ||
                it.fixedPhaseDecisionNanos <
                    it.fixedPhaseRendererWakeObservedNanos
        }
        val fixedRetirementClockInvalidFrames = times.count {
            it.targetReachedNanos <= 0L ||
                it.fixedRetirementPublishNanos < it.targetReachedNanos ||
                it.postWaitNanos != it.fixedRetirementPublishNanos ||
                it.fixedRendererWakePublishNanos < it.fixedRetirementPublishNanos
        }
        val fixedCallbackAuthorityInvalidFrames = times.count {
            it.fixedPhasePhysicalCallbackSequence <= 0L ||
                it.fixedTargetPhysicalCallbackSequence <= 0L ||
                it.fixedTargetFrameTimeNanos <= 0L ||
                it.fixedTargetFrameIndex < it.fixedPhasePlannedTargetFrame
        }
        val fixedSupersededBeforeClaimCount =
            times.maxOfOrNull { it.fixedSupersededBeforeClaimCount } ?: 0L
        val fixedCase1Frames = times.count {
            it.fixedPhasePlannedTargetFrame - it.fixedPhaseAcceptedFrameIndex == 1L
        }
        val fixedCase2Frames = times.count {
            it.fixedPhasePlannedTargetFrame - it.fixedPhaseAcceptedFrameIndex == 2L
        }
        val causalTimestampIdentityOrOrderInvalidFrames = times.count {
            it.telemetrySchemaVersion != FIXED_PHASE_TELEMETRY_SCHEMA_VERSION ||
                it.backendCompletionToken <= 0L ||
                it.backendSurfaceSerial <= 0L ||
                it.backendCompletionWorkGeneration <= 0L ||
                it.backendCompletionFrameId != it.frameId ||
                it.backendCompletionGfxstreamFrameNumber <= 0L ||
                it.backendCompletionClockDomain != BACKEND_COMPLETION_CLOCK_MONOTONIC ||
                it.backendCompletionIssueCount != 1 ||
                it.backendCompletionCommitCount != 1 ||
                it.backendCompletionPublishCount != 1 ||
                it.drawBeginNanos <= 0L ||
                it.backendPrepareBeginNanos < it.drawBeginNanos ||
                it.backendCompletionSignalNanos < it.backendPrepareBeginNanos ||
                it.backendWaitReturnNanos < it.backendCompletionSignalNanos ||
                it.compositionLatchNanos < it.queueSubmitNanos ||
                it.fixedPhaseDecisionNanos <= 0L ||
                it.fixedPhasePreSwapNanos <= 0L ||
                it.fixedPhaseDecisionNanos != it.fixedPhasePreSwapNanos ||
                it.backendWaitReturnNanos > it.fixedPhaseDecisionNanos ||
                it.fixedPhasePreSwapNanos > it.queueSubmitNanos ||
                it.fixedCandidateSequence <= 0L ||
                it.fixedCandidateRawSequence <= 0L ||
                it.fixedCandidateCaptureNanos <= 0L ||
                it.fixedCandidateClaimNanos < it.fixedCandidateCaptureNanos ||
                it.fixedOpportunityClaimNanos != it.fixedCandidateClaimNanos ||
                it.fixedRefreshIssued != 1 ||
                (it.fixedRefreshDelivered == 0 &&
                    (it.fixedRefreshPhysicalCallbackSequence != 0L ||
                        it.fixedRefreshCapturedRawSequence != 0L)) ||
                (it.fixedRefreshDelivered == 1 &&
                    (it.fixedRefreshPhysicalCallbackSequence <= 0L ||
                        it.fixedRefreshCapturedRawSequence <= 0L)) ||
                (it.fixedRefreshDelivered != 0 && it.fixedRefreshDelivered != 1) ||
                (it.fixedShadowRawSequence != 0L &&
                    it.fixedShadowRawSequence <= it.fixedCandidateRawSequence) ||
                it.fixedShadowPromotionCount < 0L ||
                it.fixedWakeNoticeSequence <= 0L ||
                it.fixedJoinNoticeSequence != it.fixedWakeNoticeSequence ||
                it.fixedJoinOpenNanos != it.fixedPhaseOpportunityPublishNanos ||
                it.fixedJoinPriorRetirementSequence !=
                    it.fixedPriorRetirementSequence ||
                it.fixedCommonCommitEntryNanos != it.fixedFinalCorridorBeginNanos ||
                it.fixedCommonCommitEntryNanos <
                    it.fixedPhaseRendererWakeObservedNanos ||
                it.fixedFinalCorridorBeginNanos < it.fixedPhaseDecisionNanos ||
                it.fixedQueueMarkNanos < it.fixedFinalCorridorBeginNanos ||
                it.fixedEglSwapEnterNanos < it.fixedQueueMarkNanos ||
                it.fixedDecisionToEglEnterNanos !=
                    it.fixedEglSwapEnterNanos - it.fixedPhaseDecisionNanos ||
                (it.fixedPriorRetirementSequence == 0L &&
                    (it.fixedLatchCreditWorkGeneration != 0L ||
                        it.fixedLatchCreditAdmissionSequence != 0L ||
                        it.fixedLatchCreditFrameId != 0L ||
                        it.fixedLatchCreditQueueNanos != 0L ||
                        it.fixedLatchCreditLatchNanos != 0L ||
                        it.fixedLatchCreditQueryCount != 0)) ||
                (it.fixedPriorRetirementSequence != 0L &&
                    (it.fixedLatchCreditWorkGeneration !=
                        it.fixedPriorRetirementWorkGeneration ||
                        it.fixedLatchCreditAdmissionSequence !=
                        it.fixedPriorRetirementAdmissionSequence ||
                        it.fixedLatchCreditFrameId <= 0L ||
                        it.fixedLatchCreditQueueNanos <= 0L ||
                        it.fixedLatchCreditLatchNanos <
                            it.fixedLatchCreditQueueNanos ||
                        it.fixedLatchCreditQueryCount != 1))
        }
        val causalLatchHorizonViolationFrames = times.count {
            val horizon = (intervalNanos * 3L) / 2L
            (it.queueSubmitNanos > 0L &&
                it.compositionLatchNanos >= it.queueSubmitNanos &&
                it.compositionLatchNanos - it.queueSubmitNanos > horizon) ||
                (it.fixedPriorRetirementSequence != 0L &&
                    it.fixedLatchCreditLatchNanos >=
                        it.fixedLatchCreditQueueNanos &&
                    it.fixedLatchCreditLatchNanos -
                        it.fixedLatchCreditQueueNanos > horizon)
        }
        fun positiveDurationMs(value: Long): Float? =
            value.takeIf { it > 0L }?.div(1_000_000f)
        val postSwapCritical = times.mapNotNull {
            positiveDurationMs(it.postSwapCriticalNanos)
        }
        val postSwapToNextReservation = times.mapNotNull {
            positiveDurationMs(it.postSwapToNextReservationNanos)
        }
        val pureDrawIssue = times.mapNotNull {
            positiveDeltaMs(it.drawIssueEndNanos, it.drawBeginNanos)
        }
        val frameIdReservation = times.mapNotNull {
            positiveDeltaMs(
                it.frameIdReservedNanos,
                it.frameIdReservationBeginNanos
            )
        }
        val backendPrepareToSignal = times.mapNotNull {
            positiveDeltaMs(
                it.backendCompletionSignalNanos,
                it.backendPrepareBeginNanos
            )
        }
        val backendSignalToReturn = times.mapNotNull {
            positiveDeltaMs(
                it.backendWaitReturnNanos,
                it.backendCompletionSignalNanos
            )
        }
        val commonCallbackTransaction = times.mapNotNull {
            positiveDurationMs(it.commonCallbackTransactionNanos)
        }
        val wakeDispatchToRendererCallback = times.mapNotNull {
            positiveDurationMs(it.wakeDispatchToRendererCallbackNanos)
        }
        val rendererCallbackToCommitEntry = times.mapNotNull {
            positiveDurationMs(it.rendererCallbackToCommitEntryNanos)
        }
        val commonCommitEntryToClaim = times.mapNotNull {
            positiveDurationMs(it.commonCommitEntryToClaimNanos)
        }
        val readyCommitPriorityViolationFrames =
            times.maxOfOrNull { it.readyCommitPriorityViolationFrames } ?: 0L
        val preCommitRetirementObservationFrames =
            times.maxOfOrNull { it.preCommitRetirementObservationFrames } ?: 0L
        val retainedQueryRequiredCount =
            times.maxOfOrNull { it.retainedQueryRequiredCount } ?: 0L
        val retainedQueryExecutedCount =
            times.maxOfOrNull { it.retainedQueryExecutedCount } ?: 0L
        val retainedQueryWrongSelectionCount =
            times.maxOfOrNull { it.retainedQueryWrongSelectionCount } ?: 0L
        val commitBeforeRetainedQueryCount =
            times.maxOfOrNull { it.commitBeforeRetainedQueryCount } ?: 0L
        val callbackArrivedDuringQueryCount =
            times.maxOfOrNull { it.callbackArrivedDuringQueryCount } ?: 0L
        val evidenceCapsuleMaxDepth =
            times.maxOfOrNull { it.evidenceCapsuleMaxDepth } ?: 0
        val evidenceCapsuleInvalidFrames =
            times.maxOfOrNull { it.evidenceCapsuleInvalidFrames } ?: 0L
        val backendPhasePartitionInvalidFrames = times.count {
            !it.backendPhasePartitionValid
        }
        val fixedBackendConservationInvalidFrames = times.count {
            !NtkSchema11PostApplyConservation.isExact(it)
        }
        val mutationBudgetMs = 16.67f
        return ReaderSurfaceView.FrameStatsSnapshot(
            samples = deltas.size,
            strictOverBudget = missed,
            missedIntervals = missed,
            missedFrames = dropped,
            droppedFrames = dropped,
            droppedFrameDebt = dropped,
            callbackP95 = percentile95(deltas),
            callbackMax = maximum,
            prepP95 = 0f,
            prepMax = 0f,
            drawP95 = 0f,
            drawMax = 0f,
            totalP95 = percentile95(deltas),
            totalMax = maximum,
            maxMissingPx = 0,
            maxPlaceholderPx = 0,
            maxVisibleLoading = 0,
            noCanvas = 0,
            coalesced = 0,
            inputFrames = inputNewest.size,
            inputOldestToPostP95 = percentile95(inputOldest),
            inputOldestToPostMax = inputOldest.maxOrNull() ?: 0f,
            inputNewestToPostP95 = percentile95(inputNewest),
            inputNewestToPostMax = inputNewest.maxOrNull() ?: 0f,
            mutationFrames = mutationOldest.size,
            mutationCallbackOverBudget = mutationOldest.count { it > mutationBudgetMs },
            mutationCallbackMax = mutationOldest.maxOrNull() ?: 0f,
            mutationPostOverBudget = mutationOldest.count { it > mutationBudgetMs },
            mutationPostMax = mutationOldest.maxOrNull() ?: 0f,
            mutationNewestCallbackMax = mutationNewest.maxOrNull() ?: 0f,
            mutationNewestPostMax = mutationNewest.maxOrNull() ?: 0f,
            presentationUnsupportedFrames = unsupportedPresentationFrames,
            invalidSwapIntervalFrames = invalidSwapIntervalFrames,
            pipelineFrames = times.count { it.swappyMode == 1 },
            nonPipelineFrames = times.count { it.swappyMode == 2 },
            unknownPipelineFrames = times.count { it.swappyMode == 0 },
            unpacedFrames = times.count {
                it.preWaitNanos <= 0L || it.postWaitNanos <= 0L || it.postSwapNanos <= 0L ||
                    !it.gpuInvariantValid
            },
            eventToMainIngressP95 = percentile95(eventToMainIngress),
            eventToMainIngressMax = eventToMainIngress.maxOrNull() ?: 0f,
            mainIngressToReceiptMax = mainIngressToReceipt.maxOrNull() ?: 0f,
            eventToReceiptP95 = percentile95(eventToReceipt),
            eventToReceiptMax = eventToReceipt.maxOrNull() ?: 0f,
            receiptToMutationP95 = percentile95(receiptToMutation),
            receiptToMutationMax = receiptToMutation.maxOrNull() ?: 0f,
            mutationToQueueMax = mutationToQueue.maxOrNull() ?: 0f,
            backendCompletionToQueueMax =
                backendCompletionToQueue.maxOrNull() ?: 0f,
            queueToCompositionMax = queueToComposition.maxOrNull() ?: 0f,
            compositionToPresentMax = compositionToPresent.maxOrNull() ?: 0f,
            readyTileFrames = times.count { it.integratedTiles > 0 },
            readyTileMissedIntervals = deltaSamples.count {
                it.second && it.first > intervalMs * 1.5f
            },
            cleanMissedIntervals = deltaSamples.count {
                !it.second && it.first > intervalMs * 1.5f
            },
            readyTilePacingMax = deltaSamples.filter { it.second }
                .maxOfOrNull { it.first } ?: 0f,
            cleanPacingMax = deltaSamples.filter { !it.second }
                .maxOfOrNull { it.first } ?: 0f,
            surfaceEpoch = latest?.surfaceEpoch ?: 0L,
            frameId = latest?.frameId ?: 0L,
            latchProofState = latest?.latchProofState ?: 0,
            logicalUnlatchedSubmissions = latest?.logicalUnlatchedSubmissions ?: 0,
            maxLogicalUnlatchedSubmissions = times.maxOfOrNull {
                max(it.logicalUnlatchedSubmissions, it.maxLogicalUnlatchedSubmissions)
            } ?: 0,
            oldestUnlatchedAgeNanos = times.maxOfOrNull { it.oldestUnlatchedAgeNanos } ?: 0L,
            latchQueryError = lastQueryError,
            latchEvidenceDeadlineNanos = latest?.latchEvidenceDeadlineNanos ?: 0L,
            cadenceQualificationFailed = times.any { it.cadenceQualificationFailed },
            causalTimestampIdentityOrOrderInvalidFrames =
                causalTimestampIdentityOrOrderInvalidFrames,
            causalLatchHorizonViolationFrames =
                causalLatchHorizonViolationFrames,
            latchLostFrames = times.count { it.latchProofState == LATCH_PROOF_LOST },
            latchInvalidStateFrames = times.count {
                it.latchProofState != LATCH_PROOF_LATCHED
            },
            latchQueryErrorFrames = times.count {
                it.latchQueryError != EGL_SUCCESS
            },
            functionalSubmissionSamples = functionalSubmissionDeltas.size,
            functionalSubmissionP95 = percentile(functionalSubmissionDeltas, 95),
            functionalSubmissionP99 = percentile(functionalSubmissionDeltas, 99),
            functionalSubmissionMax = functionalSubmissionDeltas.maxOrNull() ?: 0f,
            functionalSubmissionMissedFrames = functionalSubmissionMissedFrames,
            functionalSubmissionDroppedFrames = functionalSubmissionDroppedFrames,
            functionalSubmissionPauseFrames = functionalSubmissionPauseFrames,
            functionalSubmissionMaxOverBudgetStreak = functionalSubmissionMaxOverBudgetStreak,
            functionalSubmissionEligiblePairs = functionalSubmissionPairs.size,
            functionalSubmissionInvalidPairs = functionalSubmissionInvalidPairs,
            functionalInputGestures = functionalInputGestures,
            functionalGesturesWithValidPair = functionalGesturesWithValidPair,
            functionalCpuSubmitWorkSamples = functionalDrawToSwapReturnDiagnostic.size,
            functionalCpuSubmitWorkEligibleFrames = functionalInputFrames.size,
            functionalCpuSubmitWorkInvalidFrames =
                functionalInputFrames.size - functionalDrawToSwapReturnDiagnostic.size,
            functionalCpuSubmitWorkP95 = percentile(functionalDrawToSwapReturnDiagnostic, 95),
            functionalCpuSubmitWorkMax =
                functionalDrawToSwapReturnDiagnostic.maxOrNull() ?: 0f,
            functionalDrawIssueSamples = functionalDrawIssue.size,
            functionalDrawIssueEligibleFrames = functionalInputFrames.size,
            functionalDrawIssueInvalidFrames = functionalInputFrames.size - functionalDrawIssue.size,
            functionalDrawIssueP95 = percentile(functionalDrawIssue, 95),
            functionalDrawIssueMax = functionalDrawIssue.maxOrNull() ?: 0f,
            functionalSwapCallSamples = functionalSwapCall.size,
            functionalSwapCallEligibleFrames = functionalInputFrames.size,
            functionalSwapCallInvalidFrames = functionalInputFrames.size - functionalSwapCall.size,
            functionalSwapCallP95 = percentile(functionalSwapCall, 95),
            functionalSwapCallP99 = percentile(functionalSwapCall, 99),
            functionalSwapCallMax = functionalSwapCall.maxOrNull() ?: 0f,
            functionalSwapCallPauseFrames = functionalSwapCallNanos.count { it >= 50_000_000L },
            functionalRendererReadyToQueueSamples = functionalRendererReadyToQueue.size,
            functionalRendererReadyToQueueEligiblePairs = functionalFramePairs.size,
            functionalRendererReadyToQueueInvalidPairs =
                functionalFramePairs.size - functionalRendererReadyToQueue.size,
            functionalRendererReadyToQueueP95 = percentile(functionalRendererReadyToQueue, 95),
            functionalRendererReadyToQueueMax =
                functionalRendererReadyToQueue.maxOrNull() ?: 0f,
            functionalRendererReadyToQueueMissedFrames =
                rendererReadyFrameDebt.missedFrames,
            functionalRendererReadyToQueueDroppedFrames =
                rendererReadyFrameDebt.droppedFrames,
            functionalPhaseDecompositionInvalidPairs =
                functionalFramePairs.size - functionalPhaseDecompositions.size,
            functionalNextWorkStartDelayMax =
                functionalNextWorkStartDelay.maxOrNull() ?: 0f,
            functionalBackendPreparationP95 =
                percentile(functionalBackendPreparation, 95),
            functionalBackendPreparationMax =
                functionalBackendPreparation.maxOrNull() ?: 0f,
            functionalResidualPriorTargetGateP95 =
                percentile(functionalResidualPriorTargetGate, 95),
            functionalResidualPriorTargetGateMax =
                functionalResidualPriorTargetGate.maxOrNull() ?: 0f,
            functionalPhaseAdmissionAfterBothReadyP95 =
                percentile(functionalPhaseAdmissionAfterBothReady, 95),
            functionalPhaseAdmissionAfterBothReadyMax =
                functionalPhaseAdmissionAfterBothReady.maxOrNull() ?: 0f,
            functionalPreparationOverlapSamples =
                functionalPreparationOverlap.size,
            functionalPreparationOverlapP95 =
                percentile(functionalPreparationOverlap, 95),
            functionalPreparationOverlapMax =
                functionalPreparationOverlap.maxOrNull() ?: 0f,
            functionalTargetRetirementSamples =
                functionalTargetRetirement.size,
            functionalTargetRetirementP95 =
                percentile(functionalTargetRetirement, 95),
            functionalTargetRetirementMax =
                functionalTargetRetirement.maxOrNull() ?: 0f,
            functionalGpuInvariantFailedFrames = times.count {
                !it.functionalGpuInvariantValid
            },
            fixedPhaseTelemetryInvalidFrames = fixedPhaseTelemetryInvalidFrames,
            fixedPhaseHardPostFailureFrames = fixedPhaseHardPostFailureFrames,
            fixedPhaseUnexpectedFatalFrames = fixedPhaseUnexpectedFatalFrames,
            physicalTargetWaitP95 = percentile(physicalTargetWait, 95),
            physicalTargetWaitMax = physicalTargetWait.maxOrNull() ?: 0f,
            retirementPublicationP95 = percentile(retirementPublication, 95),
            retirementPublicationMax = retirementPublication.maxOrNull() ?: 0f,
            opportunityReceiptToDecisionP95 =
                percentile(opportunityReceiptToDecision, 95),
            opportunityReceiptToDecisionMax =
                opportunityReceiptToDecision.maxOrNull() ?: 0f,
            fixedDemandConservationInvalidFrames =
                fixedDemandConservationInvalidFrames,
            fixedOpportunityIdentityInvalidFrames =
                fixedOpportunityIdentityInvalidFrames,
            fixedOpportunityWakeLostFrames = fixedOpportunityWakeLostFrames,
            fixedRetirementClockInvalidFrames = fixedRetirementClockInvalidFrames,
            fixedCallbackAuthorityInvalidFrames = fixedCallbackAuthorityInvalidFrames,
            fixedSupersededBeforeClaimCount = fixedSupersededBeforeClaimCount,
            fixedCase1Frames = fixedCase1Frames,
            fixedCase2Frames = fixedCase2Frames,
            readyCommitPriorityViolationFrames =
                readyCommitPriorityViolationFrames,
            preCommitRetirementObservationFrames =
                preCommitRetirementObservationFrames,
            postSwapCriticalP95 = percentile(postSwapCritical, 95),
            postSwapCriticalMax = postSwapCritical.maxOrNull() ?: 0f,
            postSwapToNextReservationP95 =
                percentile(postSwapToNextReservation, 95),
            postSwapToNextReservationMax =
                postSwapToNextReservation.maxOrNull() ?: 0f,
            retainedQueryRequiredCount = retainedQueryRequiredCount,
            retainedQueryExecutedCount = retainedQueryExecutedCount,
            retainedQueryWrongSelectionCount =
                retainedQueryWrongSelectionCount,
            commitBeforeRetainedQueryCount =
                commitBeforeRetainedQueryCount,
            callbackArrivedDuringQueryCount =
                callbackArrivedDuringQueryCount,
            pureDrawIssueP95 = percentile(pureDrawIssue, 95),
            pureDrawIssueMax = pureDrawIssue.maxOrNull() ?: 0f,
            frameIdReservationP95 = percentile(frameIdReservation, 95),
            frameIdReservationMax = frameIdReservation.maxOrNull() ?: 0f,
            backendPrepareToSignalP95 =
                percentile(backendPrepareToSignal, 95),
            backendPrepareToSignalMax =
                backendPrepareToSignal.maxOrNull() ?: 0f,
            backendSignalToReturnP95 =
                percentile(backendSignalToReturn, 95),
            backendSignalToReturnMax =
                backendSignalToReturn.maxOrNull() ?: 0f,
            commonCallbackTransactionP95 =
                percentile(commonCallbackTransaction, 95),
            commonCallbackTransactionMax =
                commonCallbackTransaction.maxOrNull() ?: 0f,
            wakeDispatchToRendererCallbackP95 =
                percentile(wakeDispatchToRendererCallback, 95),
            wakeDispatchToRendererCallbackMax =
                wakeDispatchToRendererCallback.maxOrNull() ?: 0f,
            rendererCallbackToCommitEntryP95 =
                percentile(rendererCallbackToCommitEntry, 95),
            rendererCallbackToCommitEntryMax =
                rendererCallbackToCommitEntry.maxOrNull() ?: 0f,
            commonCommitEntryToClaimP95 =
                percentile(commonCommitEntryToClaim, 95),
            commonCommitEntryToClaimMax =
                commonCommitEntryToClaim.maxOrNull() ?: 0f,
            backendPhasePartitionInvalidFrames =
                backendPhasePartitionInvalidFrames,
            fixedBackendConservationInvalidFrames =
                fixedBackendConservationInvalidFrames,
            evidenceCapsuleMaxDepth = evidenceCapsuleMaxDepth,
            evidenceCapsuleInvalidFrames = evidenceCapsuleInvalidFrames,
            schema10EvidenceFrames = schema10EvidenceFrames.get().toInt(),
            schema10IdentityInvalidFrames = schema10IdentityInvalidFrames.get().toInt(),
            surfaceControlLatchInvalidFrames =
                surfaceControlLatchInvalidFrames.get().toInt(),
            externalSubmissionInvalidFrames =
                externalSubmissionInvalidFrames.get().toInt(),
            hardwareBufferIdentityInvalidFrames =
                hardwareBufferIdentityInvalidFrames.get().toInt()
        )
    }

    /** Native monotonic count; unlike frame feedback this survives a rejected pre-submit frame. */
    fun getPreSubmitViewportGap(): Long = engine.preSubmitViewportGap()

    private fun onFramePresented(frame: NtkStripRenderEngine.FrameSnapshot) {
        if (!hostPresentationGate.isEnabled) return
        hostPresentationGate.runIfEnabled { onHostFramePresented(frame) }
    }

    /** Runs wholly inside the host gate so pause cannot split proof aggregation from publication. */
    private fun onHostFramePresented(frame: NtkStripRenderEngine.FrameSnapshot) {
        val target = publishedEngineTargetOrNull() ?: return
        val currentGeometry = geometry ?: return
        val binding = currentBinding.get() ?: return
        if (frame.engineGeneration != target.engine.engineGeneration ||
            frame.engineGeneration != binding.engineGeneration ||
            frame.authorityGeneration != binding.authorityGeneration ||
            frame.authority != currentGeometry.episode.value ||
            frame.surfaceEpoch != target.identity.surfaceEpoch
        ) return
        scheduleCompositorRevealAfterFreshFrame(target.identity)
        schedulePublishedResizeCommit(target.identity, frame)
        val frameKey = NtkFrameOrderKey(frame.surfaceEpoch, frame.frameSequence)
        while (true) {
            val previous = lastMergedFrameKey.get()
            if (previous != null && !isStrictlyNewerNtkFrame(frameKey, previous)) return
            if (lastMergedFrameKey.compareAndSet(previous, frameKey)) break
        }
        // Accessibility/idling and first-image timing are qualification signals. Publish them
        // only for the exact SurfaceControl buffer identity whose transaction commit and
        // compositor latch have both been observed; a merely rendered frame is not sufficient.
        val presentationIdentityExact = frame.evidenceQualified &&
            frame.telemetrySchemaVersion == FIXED_PHASE_TELEMETRY_SCHEMA_VERSION &&
            frame.engineGeneration > 0L && frame.surfaceEpoch > 0L &&
            frame.authorityGeneration > 0L && frame.authority > 0L &&
            frame.workGeneration > 0L && frame.frameId > 0L &&
            frame.frameSequence > 0L && frame.admissionSequence > 0L &&
            frame.capsuleSequence > 0L && frame.transactionSerial > 0L &&
            frame.bufferSlot in 0L..7L && frame.bufferGeneration > 0L &&
            frame.frameTimelineVsyncId > 0L && frame.setBufferCount == 1 &&
            frame.transactionApplyCount == 1 && frame.onCommitCallbackCount == 1 &&
            frame.latchSource == 1 && frame.latchEventSequence > 0L &&
            frame.compositionLatchNanos > 0L &&
            frame.latchCallbackObservedNanos >= frame.compositionLatchNanos
        synchronized(presentLock) {
            if (!presentTimes.containsKey(frameKey)) {
                schema11QualificationAccumulator.accept(frame)
                schema10EvidenceFrames.incrementAndGet()
                val identityExact = frame.evidenceQualified &&
                    frame.telemetrySchemaVersion == FIXED_PHASE_TELEMETRY_SCHEMA_VERSION &&
                    frame.engineGeneration > 0L && frame.surfaceEpoch > 0L &&
                    frame.authorityGeneration > 0L && frame.authority > 0L &&
                    frame.workGeneration > 0L && frame.frameId > 0L &&
                    frame.frameSequence > 0L && frame.admissionSequence > 0L &&
                    frame.capsuleSequence > 0L && frame.transactionSerial > 0L
                if (!identityExact) schema10IdentityInvalidFrames.incrementAndGet()
                val hardwareBufferExact = frame.bufferSlot in 0L..7L &&
                    frame.bufferGeneration > 0L && frame.frameTimelineVsyncId > 0L
                if (!hardwareBufferExact) {
                    hardwareBufferIdentityInvalidFrames.incrementAndGet()
                }
                val latchExact = identityExact && hardwareBufferExact &&
                    frame.setBufferCount == 1 && frame.transactionApplyCount == 1 &&
                    frame.onCommitCallbackCount == 1 && frame.latchSource == 1 &&
                    frame.latchEventSequence > 0L && frame.compositionLatchNanos > 0L &&
                    frame.latchCallbackObservedNanos >= frame.compositionLatchNanos
                if (!latchExact) surfaceControlLatchInvalidFrames.incrementAndGet()
                val externalExact = identityExact &&
                    frame.retirementSequence > 0L &&
                    frame.retirementState == NTK_FIXED_RETIREMENT_RETIRED &&
                    frame.retirementFatalReason == 0 && frame.targetWaitCount == 1 &&
                    frame.targetRebaseCount == 0 &&
                    frame.retirementCallbackPublishedNanos >= frame.targetReachedNanos &&
                    frame.fixedExternalWorkGeneration == frame.workGeneration &&
                    frame.fixedExternalFrameId == frame.frameId &&
                    frame.onCompleteCallbackCount == 1 &&
                    NtkSchema11PostApplyConservation.isExact(frame)
                if (!externalExact) externalSubmissionInvalidFrames.incrementAndGet()
                val inputCapable = frame.gpuPhase == 3 || frame.gpuPhase == 4
                val sealedEvidencePhase = frame.gpuPhase == 2 || inputCapable
                // Native emits a frame callback exactly once, after its ledger entry reaches a
                // terminal state. QUEUED/PENDING are therefore invalid on this boundary too.
                val functionalGpuInvariantValid = sealedEvidencePhase &&
                    frame.sealedScene && frame.resourceSubmitSerial > 0L &&
                    frame.resourceSubmitSerial == frame.sealedResourceSubmitSerial &&
                    !frame.uploadContextAlive &&
                    frame.uploadCommandsSubmitting == 0 &&
                    frame.uploadGpuFencesPending == 0 &&
                    frame.readyTileQueueDepth == 0 &&
                    frame.nativePublicationsOutstanding == 0 &&
                    frame.pendingPublishAcks == 0 &&
                    frame.retireQueueDepth == 0 && frame.retirementCount == 0 &&
                    frame.sealedSceneVersion == frame.sceneVersion &&
                    frame.resourceWorkerState == 3 &&
                    frame.resourceWorkerCreateCount == frame.resourceWorkerDestroyCount &&
                    frame.activeResourceWorkerCount == 0 &&
                    frame.activeUploadContextCount == 0 &&
                    frame.sceneMutationCountSinceSeal == 0L &&
                    frame.lastGpuResourceCompletionNanos > 0L &&
                    frame.offscreenWarmDrawCount > 0L &&
                    frame.offscreenWarmFenceCompletionNanos >=
                        frame.lastGpuResourceCompletionNanos &&
                    frame.predecessorPhysicalCompleteNanos <=
                        frame.sealFenceCompletionNanos &&
                    frame.sealFenceCompletionNanos >=
                        frame.offscreenWarmFenceCompletionNanos &&
                    frame.uploadContextDestroyedNanos >= frame.sealFenceCompletionNanos &&
                    frame.stageBackbufferReadyNanos >=
                        frame.uploadContextDestroyedNanos &&
                    frame.stageLatchNanos >= frame.stageBackbufferReadyNanos &&
                    frame.admissionSequence > 0L &&
                    frame.plannerInvocationCount > 0L &&
                    frame.backendPresentPrepareCount == frame.swapAttemptCount &&
                    frame.firstVisibleGapPx < 0L && frame.viewportOriginalComplete &&
                    (frame.firstDownIngressNanos == 0L ||
                        frame.stageLatchNanos <= frame.firstDownIngressNanos)
                // GPU/Swappy resource quiescence and compositor-latch qualification are
                // independent evidence. Hard mode asserts the latch tuple separately below;
                // do not mislabel an EGL history loss as unpaced GPU resource work.
                val gpuInvariantValid = functionalGpuInvariantValid
                presentTimes[frameKey] = frame.withSurfaceQualification(
                    functionalGpuInvariantValid,
                    gpuInvariantValid
                )
                if (presentTimes.size > MAX_PRESENT_DIAGNOSTIC_FRAMES) {
                    presentTimes.pollFirstEntry()
                }
            }
        }
        if (frame.viewportOriginalComplete) {
            synchronized(presentLock) {
                if (frame.visibleContentEndPx > frame.visibleContentStartPx) {
                    presentedContent.add(
                        frame.visibleContentStartPx,
                        frame.visibleContentEndPx
                    )
                }
            }
            if (frame.firstVisiblePage >= 0 && frame.lastVisiblePage >= frame.firstVisiblePage) {
                for (page in frame.firstVisiblePage..frame.lastVisiblePage) {
                    if (page in currentGeometry.pages.indices) traversedPages += page
                }
            }
        } else {
            viewportDefectFrames.incrementAndGet()
        }
        if ((frame.gpuPhase == 3 || frame.gpuPhase == 4) &&
            !frame.runwayOriginalComplete
        ) runwayDefectFrames.incrementAndGet()
        if (presentationIdentityExact) {
            PerfTrace.begin("ViewerFramePresent")
            try {
                ViewerTelemetry.actualFramePresented(
                    this,
                    frame.authority,
                    frame.firstVisiblePage,
                    frame.lastVisiblePage,
                    frame.presentedAtNanos,
                    frame.viewportOriginalComplete,
                    frame.firstVisibleGapPx,
                    frame.velocityPxPerSecond,
                    frame.inputOldestNanos,
                    frame.inputNewestNanos
                )
            } finally {
                PerfTrace.end()
            }
        }
        frameListener?.invoke(frame)
    }

    private fun schedulePublishedResizeCommit(
        identity: NtkPublishedSurfaceIdentity,
        frame: NtkStripRenderEngine.FrameSnapshot
    ) {
        val inFlight = publishedResizeInFlight.get() ?: return
        if (inFlight.engineGeneration != identity.engineGeneration ||
            inFlight.attachGeneration != identity.attachGeneration ||
            inFlight.surfaceEpoch != identity.surfaceEpoch ||
            frame.backendSurfaceSerial <= inFlight.predecessorBackendSurfaceSerial
        ) return
        val reservation = publishedResizeCommitGate.reserve(inFlight) ?: return
        AppDispatchers.runOnMain {
            if (!publishedResizeCommitGate.begin(inFlight, reservation)) return@runOnMain
            try {
                if (publishedResizeInFlight.get() !== inFlight || viewClosing ||
                    terminalSurfaceFailure != null
                ) return@runOnMain
                val state = engineSurfaceState as? EngineSurfaceState.Published
                    ?: return@runOnMain
                val current = state.identity
                if (current.engineGeneration != inFlight.engineGeneration ||
                    current.attachGeneration != inFlight.attachGeneration ||
                    current.surfaceEpoch != inFlight.surfaceEpoch
                ) return@runOnMain
                val resized = current.copy(
                    geometryRevision = inFlight.geometry.geometryRevision,
                    width = inFlight.geometry.width,
                    height = inFlight.geometry.height
                )
                if (publishedSurface.compareAndSet(current, resized)) {
                    engineSurfaceState = EngineSurfaceState.Published(resized)
                    surfaceLifecycleListener?.onSurfaceAvailable(resized)
                }
            } finally {
                val releasedCurrent = publishedResizeInFlight.compareAndSet(inFlight, null)
                publishedResizeCommitGate.finish(inFlight, reservation)
                // Only the runnable that released the current exact owner may consume the
                // main-thread successor slot. A stale holder callback leaves both untouched.
                if (releasedCurrent) {
                    val state = engineSurfaceState as? EngineSurfaceState.Published
                    val queued = queuedPublishedResize
                    queuedPublishedResize = null
                    if (state != null && queued != null &&
                        queued.geometryRevision > state.identity.geometryRevision &&
                        (queued.width != state.identity.width ||
                            queued.height != state.identity.height)
                    ) {
                        startPublishedResize(state.identity, queued)
                    }
                }
            }
        }
    }

    private fun onPreSubmitViewportGap(
        callbackEngineGeneration: Long,
        authorityGeneration: Long,
        authority: Long,
        surfaceEpoch: Long,
        count: Long
    ) {
        val target = publishedEngineTargetOrNull() ?: return
        val currentGeometry = geometry ?: return
        val binding = currentBinding.get() ?: return
        if (callbackEngineGeneration != target.engine.engineGeneration ||
            callbackEngineGeneration != binding.engineGeneration ||
            authorityGeneration != binding.authorityGeneration ||
            authority != currentGeometry.episode.value ||
            surfaceEpoch != target.identity.surfaceEpoch || count <= 0L
        ) return
        viewportDefectFrames.incrementAndGet()
        surfaceLifecycleListener?.onPreSubmitViewportGap(count)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (hostDispatchOwned) return true
        val target = publishedEngineTargetOrNull() ?: return false
        // MotionEvent history is already a late, coalesced path ending at (x, y). Forwarding
        // every historical point through separate JNI calls lets the render thread drain a
        // partial batch before the freshest point is queued. Applying only the latest sample
        // preserves the full displacement from lastTouchY while minimizing input-to-present
        // latency. Delivery telemetry is collected by the controller before this handoff and
        // still accounts for every historical sample.
        val pointerId = if (event.pointerCount > 0) {
            event.getPointerId(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        } else {
            0
        }
        return target.engine.touch(
            event.actionMasked,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                event.eventTimeNanos
            } else {
                event.eventTime * 1_000_000L
            },
            event.x,
            event.y,
            pointerId
        )
    }

    override fun performClick(): Boolean = super.performClick()

    internal fun ingressHostTouch(
        authority: Long,
        action: Int,
        eventTimeNanos: Long,
        x: Float,
        y: Float,
        pointerId: Int
    ): Long {
        val target = publishedEngineTargetOrNull() ?: return 0L
        val binding = currentBinding.get()
        if (binding == null || binding.authority != authority ||
            binding.engineGeneration != target.identity.engineGeneration
        ) return 0L
        return target.engine.touchReceipt(action, eventTimeNanos, x, y, pointerId)
    }

    internal fun ingressHostTouch(
        action: Int,
        eventTimeNanos: Long,
        x: Float,
        y: Float,
        pointerId: Int
    ): Long = currentBinding.get()?.let {
        ingressHostTouch(it.authority, action, eventTimeNanos, x, y, pointerId)
    } ?: 0L

    internal fun beginHostOwnedDispatch() {
        hostDispatchOwned = true
    }

    internal fun endHostOwnedDispatch() {
        hostDispatchOwned = false
    }

    override fun onDetachedFromWindow() {
        checkMainThread()
        ViewerTelemetry.coverageSummary(
            viewportDefectFrames.get(),
            runwayDefectFrames.get(),
            runCatching { getPreSubmitViewportGap() }.getOrDefault(0L),
            schema10IdentityInvalidFrames.get()
        )
        viewClosing = true
        revokeCurrentSurface(NtkSurfaceLossReason.VIEW_CLOSED)
        surfaceLifecycleListener = null
        if (engineSurfaceState == null) {
            liveEngineOrNull()?.closeAfterSurfaceTerminal()
        }
        retiringEngines.values.toSet().forEach { retired ->
            AppDispatchers.submitNtkSurfaceLifecycleStrict {
                if (!retired.closeRetiredProofIfComplete()) retired.transferToProofRegistry()
            }
        }
        retiringEngines.clear()
        retiringTelemetry.clear()
        super.onDetachedFromWindow()
    }

    private fun Context.requireActivity(): Activity {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        error("NtkStripSurfaceView requires an Activity context")
    }
}
