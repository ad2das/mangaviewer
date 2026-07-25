package ml.melun.mangaview.reader

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Choreographer
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.R
import ml.melun.mangaview.ui.DragHandleRecyclerView
import ml.melun.mangaview.runtime.AppDispatchers
import ml.melun.mangaview.runtime.ViewerTelemetry
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Owns the NTK reader layer attached to EpisodeActivity's ViewRoot.
 *
 * Episode-list rendering is metadata-only. Strict discovery, source acquisition, decode and EGL
 * preparation are admitted by the committed click and continue in this one controller generation.
 */
class NtkInlineReaderController private constructor(
    private val activity: Activity,
    private val episodeContent: View,
    private val hostView: NtkInlineReaderHostView,
    private val renderView: ReaderSurfaceView,
    private val surfaceSlot: FrameLayout,
    private val callbacks: Callbacks
) : ReaderSurfaceView.WindowListener {

    enum class EnterResult {
        ENTERED,
        ALREADY_ACTIVE,
        BINDING,
        DESTROYED,
        WRONG_THREAD,
        HOST_NOT_ATTACHED,
        HOST_NOT_LAID_OUT,
        PREPARED_NOT_READY,
        PREPARED_IDENTITY_MISMATCH,
        SURFACE_REJECTED,
        SESSION_START_FAILED
    }

    enum class StageResult {
        BINDING,
        STAGED,
        ALREADY_STAGED,
        ACTIVE,
        DESTROYED,
        WRONG_THREAD,
        HOST_NOT_ATTACHED,
        HOST_NOT_LAID_OUT,
        PREPARED_NOT_READY,
        PREPARED_IDENTITY_MISMATCH,
        SURFACE_REJECTED,
        SESSION_START_FAILED
    }

    /** Immutable authority handed to the episode row only after the attached native Surface is staged. */
    data class StageTicket internal constructor(
        val generation: Int,
        val authority: Long,
        val path: String,
        val preparedKey: String,
        val pageCount: Int,
        val stageNonce: Long = 0L,
        val manifestRevision: Long = 0L,
        val manifestDigest: String = "",
        val geometryDigest: String = "",
        val corridorStartPx: Long = 0L,
        val corridorEndPx: Long = 0L,
        val sceneVersion: Long = 0L,
        val compositionLatchNanos: Long = 0L
    ) {
        internal constructor(
            generation: Int,
            authority: Long,
            path: String,
            preparedKey: String,
            pageCount: Int,
            proof: NtkStageProof
        ) : this(
            generation,
            authority,
            path,
            preparedKey,
            pageCount,
            proof.stageNonce,
            proof.manifestRevision,
            proof.manifestDigest,
            proof.geometryDigest,
            proof.corridorStartPx,
            proof.corridorEndPx,
            proof.sceneVersion,
            proof.compositionLatchNanos
        )

        fun hasNativeStageProof(): Boolean = stageNonce > 0L &&
            manifestDigest.isNotEmpty() && geometryDigest.isNotEmpty() &&
            corridorEndPx > corridorStartPx && sceneVersion > 0L &&
            compositionLatchNanos > 0L
    }

    data class ActivationDrawProof(
        val activationEpoch: Long,
        val activatedUptimeNanos: Long,
        val drawSequence: Long,
        val completedUptimeNanos: Long,
        val hardwareAccelerated: Boolean,
        val windowFocusedAtActivation: Boolean,
        val hostAttachedAtActivation: Boolean,
        val coverage: ReaderSurfaceView.VisibleCoverageSnapshot,
        val readiness: ReaderSurfaceView.PageReadinessSnapshot,
        val runway: ReaderSurfaceView.ForwardRunwaySnapshot?
    )

    data class ActivationStateProof(
        val activationEpoch: Long,
        val activatedUptimeNanos: Long,
        val coverage: ReaderSurfaceView.VisibleCoverageSnapshot?,
        val readiness: ReaderSurfaceView.PageReadinessSnapshot,
        val runway: ReaderSurfaceView.ForwardRunwaySnapshot?,
        val readinessScrollLimitEnabled: Boolean,
        val stageTicket: StageTicket? = null
    )

    data class TouchDeliverySnapshot(
        val samples: Int,
        val moveSamples: Int,
        val dispatchEvents: Int,
        val moveDispatchEvents: Int,
        val historicalSamples: Int,
        val downEvents: Int,
        val upEvents: Int,
        val cancelEvents: Int,
        val invalidEventTimes: Int,
        val lastAcceptedPhysicalEventSequence: Long,
        val acceptedPhysicalGestureCount: Int,
        val maxLagMs: Long,
        val downMaxLagMs: Long,
        val moveMaxLagMs: Long,
        val upMaxLagMs: Long,
        val cancelMaxLagMs: Long,
        val currentMaxLagMs: Long,
        val downCurrentMaxLagMs: Long,
        val moveCurrentMaxLagMs: Long,
        val upCurrentMaxLagMs: Long,
        val historicalMaxAgeMs: Long
    )

    data class StrictPlanObservationSnapshot(
        val controllerState: String,
        val path: String,
        val controllerGeneration: Int,
        val discoveryGeneration: Long,
        val planProofDigest: String,
        val requestIdentityDigest: String,
        val pageCount: Int,
        val surfaceEpoch: Long,
        val planReservedNanos: Long,
        val shellFrameCommitted: Boolean,
        val shellFrameCommitNanos: Long,
        val surfaceDemandGeneration: Long,
        val detachedEngineCreated: Boolean,
        val detachedWarmReady: Boolean,
        val surfaceViewConstructed: Boolean,
        val surfaceViewInstalled: Boolean,
        val publishedSurfaceEpoch: Long,
        val manifestOwned: Boolean,
        val sourceClaimed: Boolean,
        val fullSceneBootstrapStartedThreads: Int,
        val fullSceneSubmittedTasks: Long,
        val sourcePipelineCreated: Boolean,
        val nativeAuthorityInstalled: Boolean,
        val nativeReleaseAuthorityInstalled: Boolean
    )

    data class TargetLifecycleSnapshot(
        val initialSurfaceViewCount: Int,
        val currentSurfaceViewCount: Int,
        val planReservedNanos: Long,
        val shellFrameCommitNanos: Long,
        val demandGeneration: Long,
        val detachedEngineGeneration: Long,
        val detachedWarmReady: Boolean,
        val surfaceViewConstructedNanos: Long,
        val surfaceViewInstalledNanos: Long,
        val holderCreatedNanos: Long,
        val surfaceLeaseAcquiredNanos: Long,
        val attachReadyNanos: Long,
        val surfacePublishedNanos: Long,
        val publishedSurfaceEpoch: Long,
        val manifestOwned: Boolean,
        val sourceClaimed: Boolean,
        val holderCreatedCount: Int,
        val surfaceLeaseAcquireCount: Int,
        val attachReadyCount: Int,
        val surfacePublishCount: Int,
        val nativeCreateBeginNanos: Long,
        val nativeCreateEndNanos: Long,
        val swappyInitBeginNanos: Long,
        val swappyInitEndNanos: Long,
        val eglInitBeginNanos: Long,
        val eglInitEndNanos: Long,
        val renderPbufferReadyNanos: Long,
        val uploadPbufferReadyNanos: Long,
        val programReadyNanos: Long,
        val eglReadyNanos: Long,
        val detachedWarmReadyNanos: Long,
        val attachLeaseQueuedNanos: Long,
        val attachLeaseClaimedNanos: Long,
        val swappyWindowBeginNanos: Long,
        val swappyWindowEndNanos: Long,
        val surfaceControlAttachBeginNanos: Long,
        val surfaceControlAttachEndNanos: Long,
        val nativeAttachReadyNanos: Long,
        val nativeAttachPublishedNanos: Long,
        val firstBackendPrepareNanos: Long,
        val firstTransactionApplyNanos: Long,
        val firstLatchNanos: Long,
        val surfaceControlAttachCount: Long,
        val windowFrameIdCount: Long,
        val windowSwapCount: Long
    )

    internal data class FatalInvariantPlan(
        val reason: String,
        val cleanupReason: String,
        val restoreEpisode: Boolean,
        val notifyExit: Boolean
    )

    /** Java callers can override only the production events they consume. */
    open class Callbacks {
        open fun onStageReady(
            controller: NtkInlineReaderController,
            path: String,
            preparedKey: String
        ) {}
        open fun onStageFailed(
            controller: NtkInlineReaderController,
            path: String,
            reason: String
        ) {}
        open fun onActivated(controller: NtkInlineReaderController, path: String, activationEpoch: Long) {}
        open fun onExited(controller: NtkInlineReaderController, path: String, reason: String) {}
        open fun onProgressChanged(
            controller: NtkInlineReaderController,
            path: String,
            progress: ReaderSurfaceView.ProgressPosition
        ) {}
        open fun onTap(controller: NtkInlineReaderController, path: String) {}
        open fun onBoundaryReached(
            controller: NtkInlineReaderController,
            path: String,
            direction: Int,
            anchorPage: Int
        ) {}
        open fun onFirstCompletedDraw(
            controller: NtkInlineReaderController,
            proof: ActivationDrawProof
        ) {}
        open fun onFatalReaderError(
            controller: NtkInlineReaderController,
            path: String,
            reason: String,
            manga: Manga?
        ) {}
    }

    private enum class State { IDLE, PLANNED, BINDING, STAGED, ACTIVE, EXITING, DESTROYED }

    @Volatile private var state = State.IDLE
    private val runtimeLock = Any()
    private val surfaceInstallQueue by lazy {
        ReaderSurfaceInstallQueue(
            framePoster = { callback ->
                postMain { Choreographer.getInstance().postFrameCallback { callback() } }
            },
            currentSurfaceEpoch = { bindingGeneration.toLong() },
            requiredPages = ::currentRequiredInstallPages,
            batchInstaller = ::installSessionTileBatchNow
        )
    }
    private val sessionGeneration = AtomicInteger()
    @Volatile private var stripPipeline: NtkEpisodeStripPipeline? = null
    private var stripTransport: NtkEpisodeStripPipeline.SourceTransport? = null
    @Volatile private var latestStripProof: NtkEpisodeProofSnapshot? = null
    private var lastStripTelemetryTop = 0L
    private var lastStripTelemetryAtMs = 0L
    @Volatile private var stripForwardLeadPx = Long.MAX_VALUE
    @Volatile private var stripPriorityDirection = 1
    private var launchLease: ReaderPreparedStore.LaunchRunwayLease? = null
    private var preparedStoreListener: ReaderPreparedStore.Listener? = null
    @Volatile private var session: ReaderSession? = null
    private var sessionListener: ReaderSessionListenerGate? = null
    private var sessionStartTask: AppDispatchers.TaskHandle? = null
    @Volatile private var sessionStartBegan = false
    private var sessionStartTicket: SessionStartTicket? = null
    private val drawableRegistry = AdoptedDrawableRegistry(
        policy = AdoptedDrawableRegistry.Policy.FIRST_VALID_FULL_QUALITY_TILE,
        structurePolicy = AdoptedDrawableRegistry.StructurePolicy.INVALIDATE_ALL
    )
    private val authoritativeBounds = LinkedHashMap<Int, Pair<Int, Int>>()
    private val pendingSessionTiles = LinkedHashMap<Int, PendingSessionTiles>()
    /** Canonical full-quality pages decoded and retained by the Surface or its install queue. */
    private val renderReadyPages = LinkedHashSet<Int>()
    private var renderReadyGeneration = 0
    private var manifestPageCount = 0
    private var launchStartPage = 0
    private var bindingGeneration = 0
    // A real ACTION_UP can race the final main-thread handoff after the exact Store tiles have
    // already been adopted. Preserve that one-shot intent for the same immutable path/key instead
    // of dropping the user's tap merely because ReaderSession.start() is returning concurrently.
    private var activateWhenBindingCompletes = false
    private var initialPrepareStats: ReaderSurfaceView.PrepareStats? = null
    private var requiredPreparedThroughY = 0f
    private val initialContinuousElapsedByRequired = LinkedHashMap<Int, Long>()
    private var activeManga: Manga? = null
    private var activeTitle: Title? = null
    private var activeImages: List<String> = emptyList()
    private var currentProgress: ReaderSurfaceView.ProgressPosition? = null
    private var lastPublishedProgress: ReaderSurfaceView.ProgressPosition? = null
    private var hostPaused = false
    private var activationSequence = 0L
    private var pressActivationPending = false
    private var activationCommitQueued = false
    private var pressPreviewEpochPublished = false
    private var hostUiOriginalPriority: Int? = null
    private val episodeList: DragHandleRecyclerView? = episodeContent.findViewById(R.id.EpisodeList)
    private var episodeListSuppressed = false
    private var originalEpisodeItemAnimator: RecyclerView.ItemAnimator? = null
    private var originalEpisodeContentAccessibility: Int? = null
    private var originalHostAccessibility: Int? = null
    private var originalSurfaceSlotAccessibility: Int? = null
    private var originalRenderAccessibility: Int? = null
    private var originalStripAccessibility: Int? = null
    private var lastTelemetryAtTop: Boolean? = null
    private var lastTelemetryAtBottom: Boolean? = null

    private data class PendingSessionTiles(
        val generation: Int,
        val pageWidth: Int,
        val pageHeight: Int,
        val tiles: List<ReaderTile>
    )

    private class SessionStartTicket {
        private val lock = Any()
        private var startReturned = false
        private var cancelRequested = false

        fun requestCancel(): Boolean = synchronized(lock) {
            cancelRequested = true
            startReturned
        }

        fun markStartReturned(): Boolean = synchronized(lock) {
            startReturned = true
            cancelRequested
        }
    }

    @Volatile private var activePath = ""
    @Volatile private var stagedPath = ""
    @Volatile private var stagedKey = ""
    @Volatile private var publishedStageTicket: StageTicket? = null
    @Volatile private var activationEpoch = 0L
    @Volatile private var activationUptimeNanos = 0L
    @Volatile private var activationElapsedRealtimeNanos = 0L
    @Volatile private var activationUptimeMs = 0L
    @Volatile private var windowFocusedAtActivation = false
    @Volatile private var hostAttachedAtActivation = false
    @Volatile private var activationDrawProof: ActivationDrawProof? = null
    @Volatile private var activationStateProof: ActivationStateProof? = null
    @Volatile private var firstDrawStrictRunwayReady = false
    @Volatile private var visibleCoverageSnapshot: ReaderSurfaceView.VisibleCoverageSnapshot? = null
    @Volatile private var stagedActivationCoverage: ReaderSurfaceView.VisibleCoverageSnapshot? = null
    @Volatile private var stagedActivationReadiness: ReaderSurfaceView.PageReadinessSnapshot? = null
    @Volatile private var stagedActivationRunway: ReaderSurfaceView.ForwardRunwaySnapshot? = null
    @Volatile private var blockingStatus = ""
    private var plannedManga: Manga? = null
    private var plannedTitle: Title? = null
    private var plannedPath = ""
    private var plannedKey = ""
    private var plannedStartPage = 0
    private var plannedStartOffset = 0
    @Volatile private var plannedControllerGeneration = 0
    @Volatile private var plannedDiscoveryGeneration = 0L
    @Volatile private var plannedPlanProofDigest = ""
    @Volatile private var plannedRequestIdentityDigest = ""
    @Volatile private var plannedPageCount = 0
    @Volatile private var plannedSurfaceEpoch = 0L
    @Volatile private var publishedSurfaceIdentity: NtkPublishedSurfaceIdentity? = null
    @Volatile private var planReservedNanos = 0L
    @Volatile private var shellFrameCommitNanos = 0L
    @Volatile private var surfaceViewConstructedNanos = 0L
    @Volatile private var surfaceViewInstalledNanos = 0L
    @Volatile private var strictPlanObservationSnapshot: StrictPlanObservationSnapshot? = null
    private var fullSceneExecutionBootstrap: NtkFullSceneExecutionBootstrap? = null
    private var targetInstallLayoutListener: View.OnLayoutChangeListener? = null
    private val targetReducer = NtkSurfaceDemandProtocol()
    private val strictPreparationLock = Any()
    private var nextStrictPreparationGeneration = 0L
    private var strictPreparationProtocol =
        NtkStrictPreparationProtocol(nextStrictPreparationGeneration)
    private var strictPlanIdentity: NtkStrictPlanIdentity? = null
    private var strictDemandIdentity: NtkStrictDemandIdentity? = null
    private val initialSurfaceViewCount = surfaceSlot.childCount
    private var currentSurfaceDemand: NtkSurfaceDemandProtocol.Demand? = null
    private var pendingAuthoritativeManifest: NtkAuthoritativeManifest? = null
    private var detachedEngine: NtkStripRenderEngine? = null
    private var preparationEngine: NtkStripRenderEngine? = null
    private var detachedPreparationPort: NtkEpisodeStripPipeline.DetachedPreparationPort? = null
    private val preparationEnginesByAuthority =
        ConcurrentHashMap<Long, NtkStripRenderEngine>()
    private var detachedWarmTask: AppDispatchers.TaskHandle? = null
    private var detachedWarmProof: NtkDetachedWarmProof? = null
    private var stripRenderViewTarget: NtkStripSurfaceView? = null
    private val stripRenderView: NtkStripSurfaceView
        get() = checkNotNull(stripRenderViewTarget) {
            "Strict NTK SurfaceView accessed before detached target installation"
        }
    private val provisionalPlanSubscription =
        NtkSourceSpoolRegistry.addProvisionalEpisodePlanListener { path, plan ->
            postMain { onProvisionalEpisodePlan(path, plan) }
        }
    private val authoritativeManifestSubscription =
        NtkSourceSpoolRegistry.addAuthoritativeManifestListener { path, manifest ->
            postMain { onAuthoritativeManifest(path, manifest) }
        }
    private val persistentSurfaceLifecycleListener =
        object : NtkStripSurfaceView.SurfaceLifecycleListener {
            override fun onSurfaceAvailable(identity: NtkPublishedSurfaceIdentity) {
                onNtkSurfaceAvailable(identity)
            }

            override fun onSurfaceRevoked(
                event: NtkStripSurfaceView.SurfaceRevocationEvent
            ) {
                onNtkSurfaceRevoked(event)
            }

            override fun onSurfaceLost(event: NtkStripSurfaceView.SurfaceLossEvent) {
                onNtkSurfaceLost(event)
            }

            override fun onSurfaceAttachFailed(
                event: NtkStripSurfaceView.SurfaceFailureEvent
            ) {
                onNtkSurfaceAttachFailed(event)
            }

            override fun onPreSubmitViewportGap(count: Long) {
                stripPipeline?.onPreSubmitViewportGap(count)
            }
        }

    @Volatile private var touchStatsArmed = false
    @Volatile private var touchRecordCount = 0
    @Volatile private var firstHostMainIngressNanos = 0L
    private var firstInputAdmissionRejectionLogged = false
    private val touchRecordAction = IntArray(TOUCH_RECORD_CAPACITY)
    private val touchRecordEventNanos = LongArray(TOUCH_RECORD_CAPACITY)
    private val touchRecordMainIngressNanos = LongArray(TOUCH_RECORD_CAPACITY)
    private val touchRecordHistorySize = IntArray(TOUCH_RECORD_CAPACITY)
    private val touchRecordHistoryMaxAgeNanos = LongArray(TOUCH_RECORD_CAPACITY)
    private val touchRecordNativeReceipt = LongArray(TOUCH_RECORD_CAPACITY)
    private var hostGestureOwned = false
    private var currentHostNativeReceipt = 0L
    private val acceptedTerminalInputSequences = LongArray(64)
    @Volatile private var acceptedTerminalInputCount = 0
    @Volatile private var acceptedTerminalInputOverflow = false

    companion object {
        private const val TOUCH_RECORD_CAPACITY = 512
        private const val ACTIVATION_AHEAD_VIEWPORTS =
            ReaderStrictPerformanceContract.ACTIVATION_AHEAD_VIEWPORTS
        private const val PRODUCTION_AHEAD_VIEWPORTS =
            ReaderStrictPerformanceContract.PRODUCTION_AHEAD_VIEWPORTS
        private const val REQUIRED_TILE_SOURCE_HEIGHT_PX =
            ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX

        internal fun requiredAheadPx(viewportHeight: Int): Int =
            ceil(viewportHeight.coerceAtLeast(0) * ACTIVATION_AHEAD_VIEWPORTS).toInt()

        internal fun strictRunwayReady(
            coverage: ReaderSurfaceView.VisibleCoverageSnapshot?,
            ahead: ReaderSurfaceView.ForwardRunwaySnapshot?,
            physicalHeight: Int
        ): Boolean {
            if (coverage == null || ahead == null || physicalHeight <= 0) return false
            val requiredAhead = requiredAheadPx(physicalHeight)
            val viewportReady = coverage.physicalViewportPx == physicalHeight &&
                coverage.viewportPx >= physicalHeight &&
                coverage.drawablePx >= physicalHeight &&
                coverage.missingPx == 0 &&
                coverage.placeholderPx == 0 &&
                coverage.visibleLoading == 0 &&
                coverage.visibleErrors == 0 &&
                coverage.visibleCards == 0 &&
                coverage.widthFillFailures == 0 &&
                coverage.lowResolutionItems == 0 &&
                coverage.minDrawableSourceWidth > 0
            val runwayReady = ahead.requiredAheadPx == requiredAhead &&
                ahead.missingAheadPx == 0 &&
                ahead.lowResolutionItems == 0 &&
                (ahead.drawableAheadPx >= requiredAhead ||
                    (ahead.contentExhausted && ahead.drawableAheadPx == ahead.availableAheadPx))
            return viewportReady && runwayReady
        }

        internal fun rollingRequestBounds(
            firstPage: Int,
            lastPage: Int,
            pixelAheadEndPage: Int,
            pageCount: Int
        ): IntArray {
            if (pageCount <= 0) return intArrayOf(-1, -1)
            val first = (firstPage - 1).coerceIn(0, pageCount - 1)
            val last = maxOf(lastPage, pixelAheadEndPage, first).coerceIn(first, pageCount - 1)
            return intArrayOf(first, last)
        }

        internal fun authoritativeTileBoundsCompatible(
            existingWidth: Int?,
            existingHeight: Int?,
            pageWidth: Int,
            pageHeight: Int
        ): Boolean {
            if (pageWidth <= 0 || pageHeight <= 0) return false
            if (existingWidth == null || existingHeight == null) return true
            return existingWidth == pageWidth && existingHeight == pageHeight
        }

        internal fun preparedImagesStageRejection(
            manga: Manga,
            path: String,
            images: List<String>?
        ): StageResult? {
            if (images.isNullOrEmpty()) return StageResult.PREPARED_NOT_READY
            return if (imagesMatchExactEpisode(manga, path, images)) null
            else StageResult.PREPARED_IDENTITY_MISMATCH
        }

        internal fun isTransientStageRejection(result: StageResult): Boolean =
            result == StageResult.HOST_NOT_ATTACHED ||
                result == StageResult.HOST_NOT_LAID_OUT ||
                result == StageResult.PREPARED_NOT_READY

        internal fun fatalInvariantPlan(reason: String, wasActive: Boolean): FatalInvariantPlan {
            val safeReason = reason.trim().ifEmpty { "reader_invariant" }
            return FatalInvariantPlan(
                reason = safeReason,
                cleanupReason = "fatal_$safeReason",
                restoreEpisode = true,
                notifyExit = wasActive
            )
        }

        @JvmStatic
        @JvmOverloads
        fun attach(activity: Activity, callbacks: Callbacks = Callbacks()): NtkInlineReaderController? {
            val episodeContent = activity.findViewById<View>(R.id.ntk_episode_content) ?: return null
            val host = activity.findViewById<NtkInlineReaderHostView>(R.id.ntk_inline_reader_host)
                ?: return null
            val surface = host.findViewById<ReaderSurfaceView>(R.id.strip) ?: return null
            val surfaceSlot = host.findViewById<FrameLayout>(R.id.ntk_strip_surface_slot)
                ?: return null
            return NtkInlineReaderController(
                activity,
                episodeContent,
                host,
                surface,
                surfaceSlot,
                callbacks
            ).also {
                host.controller = it
            }
        }

        @JvmStatic
        fun strictPreparedKey(path: String?): String {
            val normalized = NtkStripDigests.normalizeEpisodePath(path.orEmpty())
            return if (normalized.matches(
                    Regex(
                        """^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$""",
                        RegexOption.IGNORE_CASE,
                    )
                )
            ) {
                "ntk-strict:$normalized"
            } else ""
        }

        internal fun resolvePreparedKey(path: String?, preparedKey: String?): String {
            val normalized = NtkStripDigests.normalizeEpisodePath(path.orEmpty())
            return preparedKey.orEmpty().ifBlank { strictPreparedKey(normalized) }
        }

        /** Shared exact-episode validation for Activity and inline prepared-surface launches. */
        @JvmStatic
        fun imagesMatchExactEpisode(manga: Manga?, ntkPath: String?, images: List<String>?): Boolean {
            if (manga == null || images.isNullOrEmpty()) return false
            val path = ntkPath.orEmpty().trim()
            val normalizedPath = path.trim('/').lowercase(Locale.ROOT)
            if (normalizedPath.isEmpty()) return false
            val pathParts = normalizedPath.split('/').filter { it.isNotEmpty() }
            if (pathParts.size < 3 || (pathParts[0] != "webtoon" && pathParts[0] != "manhwa")) {
                return false
            }
            val imageWorkId = manga.ntkImageWorkId?.trim()?.lowercase(Locale.ROOT).orEmpty()
                .ifBlank { pathParts[1] }
            val imageEpisodeId = manga.ntkImageEpisodeId?.trim()?.lowercase(Locale.ROOT).orEmpty()
                .ifBlank { pathParts[2] }

            fun structurallyMatches(raw: String): Boolean {
                val normalized = raw.trim()
                    .replace("\\/", "/")
                    .replace("\\u002f", "/")
                    .lowercase(Locale.ROOT)
                if (normalized.isEmpty() ||
                    normalized.contains("/api/m/i?") ||
                    isDescriptorUrl(normalized)
                ) return false
                if (normalized.contains("/$normalizedPath/")) return true
                if (imageWorkId.isEmpty() || imageEpisodeId.isEmpty()) return false
                return normalized.contains("/episodes/$imageWorkId/$imageEpisodeId/") ||
                    normalized.contains("/${pathParts[0]}/$imageWorkId/$imageEpisodeId/")
            }

            if (images.all(::structurallyMatches)) return true
            if (!ReaderImageCache.hasAuthoritativeCompleteEarlyNtkImageUrls(path, images.size, 0L)) {
                return false
            }
            val authoritative = ReaderImageCache.earlyNtkImageUrls(path, 0L)
            return authoritative.size >= images.size && images.indices.all { index ->
                authoritative[index].trim().equals(images[index].trim(), ignoreCase = true)
            }
        }

        private fun isDescriptorUrl(raw: String): Boolean {
            val value = raw.replace("\\/", "/").replace("\\u002f", "/")
            return Regex(
                ".*(?:/webtoon_uploads/|/manhwa_uploads/|/comic_uploads/|messiimage\\.online/|" +
                    "aws-cdn\\d*\\.site/)[^/?#]+\\.(?:txt|xml|json|css|js)(?:[?#].*)?$"
            ).matches(value) || Regex(
                "^(?:https?://)?[^/?#]+/.*/(?:cv|mx|qc|rs)/[^/?#]+\\." +
                    "(?:txt|xml|json|css|js|woff|woff2)(?:[?#].*)?$"
            ).matches(value)
        }
    }

    @JvmOverloads
    fun planStrictEpisode(
        manga: Manga,
        title: Title?,
        preparedKey: String? = null,
        startPage: Int = 0,
        startOffset: Int = 0
    ): StageResult {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            return rejectStage(StageResult.WRONG_THREAD)
        }
        if (state == State.DESTROYED) return rejectStage(StageResult.DESTROYED)
        if (state == State.ACTIVE) return StageResult.ACTIVE
        if (state == State.EXITING) return rejectStage(StageResult.PREPARED_NOT_READY)
        val path = NtkStripDigests.normalizeEpisodePath(manga.ntkEpisodePath.orEmpty())
        val key = resolvePreparedKey(path, preparedKey)
        if (key.isEmpty() || startPage < 0 || startOffset < 0) {
            return rejectStage(StageResult.PREPARED_NOT_READY)
        }
        if ((state == State.PLANNED || state == State.BINDING) &&
            bindingMatches(manga, key)
        ) return StageResult.BINDING
        if (state == State.STAGED && stagedMatches(manga, key)) {
            return StageResult.ALREADY_STAGED
        }
        if (state != State.IDLE) {
            cleanupRuntime(
                "replace_strict_plan",
                restoreEpisode = true,
                destroy = false,
                notifyExit = false
            )
        }

        plannedManga = manga
        plannedTitle = title ?: manga.title
        plannedPath = path
        plannedKey = key
        plannedStartPage = startPage
        plannedStartOffset = startOffset
        activeManga = manga
        activeTitle = plannedTitle
        stagedPath = path
        stagedKey = key
        plannedControllerGeneration = sessionGeneration.incrementAndGet()
        bindingGeneration = plannedControllerGeneration
        synchronized(runtimeLock) {
            renderReadyPages.clear()
            renderReadyGeneration = plannedControllerGeneration
        }
        synchronized(strictPreparationLock) {
            strictPreparationProtocol =
                NtkStrictPreparationProtocol(nextStrictPreparationGeneration)
            strictPlanIdentity = null
            strictDemandIdentity = null
            detachedPreparationPort = null
        }
        publishedSurfaceIdentity = null
        state = State.PLANNED
        blockingStatus = ""
        /*
         * The qualification observer runs off-main. Publish the authority-free shell as one
         * immutable value before discovery callbacks can reserve a plan. reserveStrictEpisodePlan
         * replaces this value only after the reducer and execution bootstrap are complete, so an
         * observer can never cache a torn mixture of the two phases.
         */
        strictPlanObservationSnapshot = authorityFreeStrictPlanObservationSnapshot()

        NtkSourceSpoolRegistry.currentProvisionalEpisodePlan(path)?.let {
            onProvisionalEpisodePlan(path, it)
        }
        NtkSourceSpoolRegistry.currentAuthoritativeManifest(path)?.let {
            onAuthoritativeManifest(path, it)
        }
        return when (state) {
            State.STAGED -> StageResult.STAGED
            State.ACTIVE -> StageResult.ACTIVE
            else -> StageResult.BINDING
        }
    }

    private fun onProvisionalEpisodePlan(path: String, plan: NtkProvisionalEpisodePlan) {
        if (Looper.myLooper() !== Looper.getMainLooper()) return
        val current = NtkSourceSpoolRegistry.currentProvisionalEpisodePlan(path)
        if (state != State.PLANNED || path != plannedPath ||
            plan.proof.normalizedEpisodePath != plannedPath ||
            current == null ||
            current.proof.discoveryGeneration != plan.proof.discoveryGeneration ||
            current.proof.proofDigestSha256 != plan.proof.proofDigestSha256
        ) return
        reserveStrictEpisodePlan(plan)
    }

    fun onEpisodeShellFrameCommitted(commitNanos: Long) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            postMain { onEpisodeShellFrameCommitted(commitNanos) }
            return
        }
        if (state == State.DESTROYED || commitNanos <= 0L) return
        if (shellFrameCommitNanos == 0L) shellFrameCommitNanos = commitNanos
        val transition = targetReducer.onShellFrameCommitted(commitNanos)
        if (state == State.PLANNED && plannedPlanProofDigest.isEmpty()) {
            strictPlanObservationSnapshot = authorityFreeStrictPlanObservationSnapshot()
        }
        if (transition.startDetachedWarm) beginDetachedWarm(checkNotNull(transition.demand))
    }

    private fun beginDetachedWarm(demand: NtkSurfaceDemandProtocol.Demand) {
        if (state == State.DESTROYED || currentSurfaceDemand != demand ||
            detachedWarmTask != null || detachedEngine != null
        ) return
        val reusableTarget = stripRenderViewTarget
        val reusableProof = detachedWarmProof
        if (reusableTarget != null && reusableProof != null) {
            val engineGeneration = reusableTarget.liveEngineGeneration()
            if (engineGeneration == reusableProof.engineGeneration &&
                targetReducer.bindDetachedEngine(demand.generation, engineGeneration)
            ) {
                val warm = targetReducer.onDetachedWarmReady(
                    demand.generation,
                    engineGeneration
                )
                if (warm.installSurfaceView &&
                    targetReducer.onSurfaceViewConstructed(demand.generation) &&
                    targetReducer.onSurfaceViewInstalled(demand.generation)
                ) {
                    val permit = NtkSurfaceInstallPermit(
                        demand.generation,
                        engineGeneration,
                        reusableProof
                    )
                    if (reusableTarget.retargetReusableDemand(permit)) {
                        val reusableEngine = reusableTarget.livePreparationEngine(engineGeneration)
                        if (reusableEngine == null ||
                            !publishDetachedPreparationEngine(demand, reusableEngine)
                        ) {
                            failBindingNow(StageResult.SURFACE_REJECTED)
                            return
                        }
                        return
                    }
                }
            }
            failBindingNow(StageResult.SURFACE_REJECTED)
            return
        }
        detachedWarmTask = try {
            AppDispatchers.submitNtkSurfaceLifecycleStrict {
                val engine = runCatching { NtkStripRenderEngine(activity) }.getOrNull()
                postMain {
                    detachedWarmTask = null
                    if (engine == null) {
                        if (state == State.PLANNED || state == State.BINDING) {
                            failBindingNow(StageResult.SURFACE_REJECTED)
                        }
                        return@postMain
                    }
                    if (state == State.DESTROYED || currentSurfaceDemand != demand ||
                        !targetReducer.bindDetachedEngine(
                            demand.generation,
                            engine.engineGeneration
                        )
                    ) {
                        engine.closeAfterSurfaceTerminal()
                        return@postMain
                    }
                    detachedEngine = engine
                    detachedWarmTask = engine.awaitDetachedWarmAsync { proof ->
                        postMain { onDetachedWarmReady(demand, engine, proof) }
                    }
                }
            }
        } catch (_: Throwable) {
            failBindingNow(StageResult.SURFACE_REJECTED)
            null
        }
    }

    private fun onDetachedWarmReady(
        demand: NtkSurfaceDemandProtocol.Demand,
        engine: NtkStripRenderEngine,
        proof: NtkDetachedWarmProof?
    ) {
        detachedWarmTask = null
        if (state == State.DESTROYED || currentSurfaceDemand != demand ||
            detachedEngine !== engine
        ) {
            engine.closeAfterSurfaceTerminal()
            return
        }
        if (proof == null || !proof.isExactDetachedWarm) {
            detachedEngine = null
            engine.closeAfterSurfaceTerminal()
            failBindingNow(StageResult.SURFACE_REJECTED)
            return
        }
        val transition = targetReducer.onDetachedWarmReady(
            demand.generation,
            engine.engineGeneration
        )
        if (!transition.installSurfaceView) return
        detachedWarmProof = proof
        if (!publishDetachedPreparationEngine(demand, engine)) {
            failBindingNow(StageResult.SURFACE_REJECTED)
            return
        }
        installSurfaceTarget(demand, engine, proof)
    }

    private fun publishDetachedPreparationEngine(
        demand: NtkSurfaceDemandProtocol.Demand,
        engine: NtkStripRenderEngine
    ): Boolean {
        val strictDemand = strictDemandIdentity ?: return false
        if (strictDemand != NtkStrictDemandIdentity.from(demand)) return false
        val transition = synchronized(strictPreparationLock) {
            strictPreparationProtocol.onDetachedEngineReady(
                NtkStrictDetachedEngineIdentity(engine.engineGeneration, strictDemand)
            )
        }
        if (transition.failed) return false
        preparationEngine = engine
        val existing = detachedPreparationPort
        val port = if (existing != null) {
            if (existing.engineGeneration != engine.engineGeneration) return false
            existing
        } else {
            createDetachedPreparationPort(engine).also { detachedPreparationPort = it }
        }
        stripPipeline?.takeIf { pipeline ->
            preparationEnginesByAuthority.keys.all { it == pipeline.authority }
        }?.onDetachedPreparationAvailable(port)
        return true
    }

    private fun createDetachedPreparationPort(
        engine: NtkStripRenderEngine
    ): NtkEpisodeStripPipeline.DetachedPreparationPort =
        object : NtkEpisodeStripPipeline.DetachedPreparationPort {
            override val engineGeneration: Long = engine.engineGeneration

            override fun openDetachedPreparation(
                authority: Long,
                preparationGeneration: Long,
                manifestSeal: NtkEpisodeManifestSeal,
                completion: (NtkNativePreparationToken?) -> Unit
            ) {
                val allowed = synchronized(strictPreparationLock) {
                    val snapshot = strictPreparationProtocol.snapshot()
                    snapshot.preparationStarted &&
                        snapshot.detachedOpenDispatched &&
                        snapshot.preparationGeneration == preparationGeneration &&
                        snapshot.detachedEngine?.engineGeneration == engine.engineGeneration &&
                        snapshot.manifest?.manifestDigest == manifestSeal.digestSha256 &&
                        snapshot.manifest?.manifestRevision == manifestSeal.revision
                }
                if (!allowed || stripPipeline?.authority != authority) {
                    completion(null)
                    return
                }
                val token = engine.openDetachedPreparation(
                    authority,
                    preparationGeneration,
                    manifestSeal.revision,
                    manifestSeal.digestSha256
                )
                if (token == null) {
                    completion(null)
                    return
                }
                val opened = synchronized(strictPreparationLock) {
                    val manifest = strictPreparationProtocol.snapshot().manifest
                        ?: return@synchronized null
                    strictPreparationProtocol.onDetachedPreparationOpened(
                        NtkStrictDetachedPreparationIdentity(
                            engineGeneration = token.engineGeneration,
                            preparationGeneration = token.preparationGeneration,
                            manifest = manifest,
                            tokenNonce = token.tokenNonce
                        )
                    )
                }
                val accepted = token.takeIf { opened != null && !opened.failed }
                if (accepted != null) {
                    val previous = preparationEnginesByAuthority.putIfAbsent(authority, engine)
                    if (previous != null && previous !== engine) {
                        completion(null)
                        return
                    }
                }
                completion(accepted)
            }

            override fun installDetachedPrepared(
                install: NtkPreparedTileInstall,
                completion: (NtkPreparedTileResidentAck?) -> Unit
            ): Boolean = engine.installDetachedPrepared(install, completion)

            override fun closePreparationAdmissions(
                token: NtkNativePreparationToken
            ): Boolean = engine.closePreparationAdmissions(token)
        }

    private fun installSurfaceTarget(
        demand: NtkSurfaceDemandProtocol.Demand,
        engine: NtkStripRenderEngine,
        proof: NtkDetachedWarmProof
    ) {
        if (state == State.DESTROYED || currentSurfaceDemand != demand ||
            detachedEngine !== engine || stripRenderViewTarget != null
        ) return
        if (!surfaceSlot.isAttachedToWindow || surfaceSlot.width <= 0 ||
            surfaceSlot.height <= 0
        ) {
            if (targetInstallLayoutListener == null) {
                targetInstallLayoutListener = View.OnLayoutChangeListener {
                        view, left, top, right, bottom, _, _, _, _ ->
                    if (right <= left || bottom <= top || currentSurfaceDemand != demand) {
                        return@OnLayoutChangeListener
                    }
                    view.removeOnLayoutChangeListener(targetInstallLayoutListener)
                    targetInstallLayoutListener = null
                    installSurfaceTarget(demand, engine, proof)
                }
                surfaceSlot.addOnLayoutChangeListener(targetInstallLayoutListener)
            }
            return
        }
        if (!targetReducer.onSurfaceViewConstructed(demand.generation)) return
        val permit = NtkSurfaceInstallPermit(
            demandGeneration = demand.generation,
            engineGeneration = engine.engineGeneration,
            warmProof = proof
        )
        val target = NtkStripSurfaceView.create(activity, engine, permit).also {
            it.id = R.id.ntk_strip_surface
            it.setSurfaceLifecycleListener(persistentSurfaceLifecycleListener)
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        surfaceViewConstructedNanos = System.nanoTime()
        stripRenderViewTarget = target
        surfaceSlot.addView(target)
        check(targetReducer.onSurfaceViewInstalled(demand.generation)) {
            "Surface target installation reducer rejected the exact constructed target"
        }
        surfaceViewInstalledNanos = System.nanoTime()
        detachedEngine = null
        Log.d(
            "ViewerPerf",
            "ntk_surface_target_installed demand=${demand.generation}," +
                "engine=${engine.engineGeneration},constructedNs=$surfaceViewConstructedNanos," +
                "installedNs=$surfaceViewInstalledNanos"
        )
    }

    private fun onNtkSurfaceAvailable(identity: NtkPublishedSurfaceIdentity) {
        if (Looper.myLooper() !== Looper.getMainLooper() || state == State.DESTROYED) return
        val demand = currentSurfaceDemand ?: return
        if (identity.demandGeneration != demand.generation) return
        targetReducer.onSurfacePublished(
            demandGeneration = identity.demandGeneration,
            engineGeneration = identity.engineGeneration,
            surfaceEpoch = identity.surfaceEpoch
        )
        val surfaceLifecycle = targetReducer.snapshot()
        if (surfaceLifecycle.publishedSurfaceEpoch != identity.surfaceEpoch) {
            failBindingNow(StageResult.SURFACE_REJECTED)
            return
        }
        plannedSurfaceEpoch = identity.surfaceEpoch
        publishedSurfaceIdentity = identity
        val strictDemand = strictDemandIdentity
        if (strictDemand == null) {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return
        }
        val preparationTransition = synchronized(strictPreparationLock) {
            strictPreparationProtocol.onSurfacePublished(
                NtkStrictPublishedSurfaceIdentity.from(strictDemand, identity)
            )
        }
        if (preparationTransition.failed) {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return
        }
        stripRenderViewTarget?.livePreparationEngine(identity.engineGeneration)?.let {
            preparationEngine = it
        }
        when (state) {
            State.IDLE, State.EXITING, State.DESTROYED -> Unit
            State.PLANNED -> Unit
            State.BINDING -> {
                val pipeline = stripPipeline ?: return
                if (plannedSurfaceEpoch != identity.surfaceEpoch) {
                    noteInvariantViolation(
                        bindingGeneration,
                        "surface_available_identity_mismatch"
                    )
                    return
                }
                bindPipelinePresentationTarget(
                    checkNotNull(stripRenderViewTarget),
                    pipeline,
                    bindingGeneration,
                    identity.height
                )
                pipeline.onGeometrySeed(identity.width, identity.height, identity.geometryRevision)
                pipeline.onSurfaceAvailable(identity)
            }
            State.STAGED, State.ACTIVE -> noteInvariantViolation(
                bindingGeneration,
                "surface_available_without_revoke"
            )
        }
    }

    private fun onNtkSurfaceRevoked(event: NtkStripSurfaceView.SurfaceRevocationEvent) {
        if (Looper.myLooper() !== Looper.getMainLooper() || state == State.DESTROYED) return
        val pipeline = stripPipeline
        if (pipeline != null &&
            event.authority != 0L && event.authority != pipeline.authority
        ) return
        val mustFailClosed = event.crossedStageBoundary ||
            state == State.STAGED || state == State.ACTIVE
        publishedStageTicket = null
        activationStateProof = null
        firstDrawStrictRunwayReady = false
        pressActivationPending = false
        activationCommitQueued = false
        if (publishedSurfaceIdentity == event.identity) publishedSurfaceIdentity = null
        synchronized(strictPreparationLock) {
            strictPreparationProtocol.failClosed("published-surface-revoked")
        }
        pipeline?.onSurfaceRevoked(event.identity, mustFailClosed)
    }

    private fun onNtkSurfaceLost(event: NtkStripSurfaceView.SurfaceLossEvent) {
        if (Looper.myLooper() !== Looper.getMainLooper() || state == State.DESTROYED) return
        val pipeline = stripPipeline ?: return
        if (event.authority != 0L && event.authority != pipeline.authority) return
        val mustFailClosed = event.crossedStageBoundary ||
            state == State.STAGED || state == State.ACTIVE
        val identity = event.identity
        if (identity == null) {
            if (mustFailClosed || !event.resourcesPreserved) {
                noteInvariantViolation(bindingGeneration, "surface_loss_without_published_identity")
            }
            return
        }
        if (publishedSurfaceIdentity == identity) publishedSurfaceIdentity = null
        synchronized(strictPreparationLock) {
            strictPreparationProtocol.failClosed("published-surface-lost")
        }
        pipeline.onSurfaceLost(
            identity,
            mustFailClosed,
            event.resourcesPreserved
        )
    }

    private fun onNtkSurfaceAttachFailed(
        event: NtkStripSurfaceView.SurfaceFailureEvent
    ) {
        if (Looper.myLooper() !== Looper.getMainLooper() || state == State.DESTROYED) return
        val reason = "surface_attach_${event.reason.name.lowercase(Locale.ROOT)}"
        when (state) {
            State.PLANNED, State.BINDING -> failBindingNow(StageResult.SURFACE_REJECTED)
            State.STAGED, State.ACTIVE -> noteInvariantViolation(bindingGeneration, reason)
            State.IDLE, State.EXITING -> {
                blockingStatus = reason
                callbacks.onFatalReaderError(
                    this,
                    activePath.ifEmpty { stagedPath },
                    reason,
                    activeManga
                )
            }
            State.DESTROYED -> Unit
        }
    }

    private fun reserveStrictEpisodePlan(plan: NtkProvisionalEpisodePlan): StageResult {
        if (state != State.PLANNED ||
            plan.proof.normalizedEpisodePath != plannedPath ||
            plannedControllerGeneration <= 0 ||
            bindingGeneration != plannedControllerGeneration ||
            sessionGeneration.get() != plannedControllerGeneration
        ) return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        if (plannedPlanProofDigest.isNotEmpty()) {
            if (plannedDiscoveryGeneration != plan.proof.discoveryGeneration ||
                plannedPlanProofDigest != plan.proof.proofDigestSha256 ||
                plannedRequestIdentityDigest !=
                    plan.proof.requestIdentity.identityDigestSha256 ||
                plannedPageCount != plan.pageCount
            ) {
                failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
                return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
            }
            return StageResult.BINDING
        }
        plannedStartPage = plannedStartPage.coerceIn(0, plan.pageCount - 1)
        plannedDiscoveryGeneration = plan.proof.discoveryGeneration
        plannedPlanProofDigest = plan.proof.proofDigestSha256
        plannedRequestIdentityDigest =
            plan.proof.requestIdentity.identityDigestSha256
        plannedPageCount = plan.pageCount
        plannedSurfaceEpoch = 0L
        planReservedNanos = System.nanoTime()
        val transition = targetReducer.reservePlan(
            plannedControllerGeneration,
            plannedPlanProofDigest,
            planReservedNanos
        )
        currentSurfaceDemand = checkNotNull(transition.demand)
        val strictPlan = runCatching {
            NtkStrictPlanIdentity.from(plannedControllerGeneration, plan)
        }.getOrElse {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        val strictDemand = NtkStrictDemandIdentity.from(checkNotNull(transition.demand))
        val preparationTransition = synchronized(strictPreparationLock) {
            strictPlanIdentity = strictPlan
            strictDemandIdentity = strictDemand
            strictPreparationProtocol.onPlanReserved(strictPlan, strictDemand)
        }
        if (preparationTransition.failed) {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        if (fullSceneExecutionBootstrap == null) {
            val bootstrap = NtkFullSceneExecutionBootstrap()
            check(bootstrap.submittedTaskCount() == 0L) {
                "A document plan may not submit full-scene work"
            }
            fullSceneExecutionBootstrap = bootstrap
            check(
                stripPipeline == null &&
                    stripRenderViewTarget?.currentNativeAuthorityToken() == null &&
                    stripRenderViewTarget?.currentNativeReleaseToken() == null
            ) { "Document plan bootstrap acquired native/source authority" }
            val lifecycle = targetReducer.snapshot()
            strictPlanObservationSnapshot = StrictPlanObservationSnapshot(
                controllerState = State.PLANNED.name,
                path = plannedPath,
                controllerGeneration = plannedControllerGeneration,
                discoveryGeneration = plannedDiscoveryGeneration,
                planProofDigest = plannedPlanProofDigest,
                requestIdentityDigest = plannedRequestIdentityDigest,
                pageCount = plannedPageCount,
                surfaceEpoch = 0L,
                planReservedNanos = planReservedNanos,
                shellFrameCommitted = lifecycle.shellFrameCommitted,
                shellFrameCommitNanos = lifecycle.shellFrameCommitNanos,
                surfaceDemandGeneration = lifecycle.demandGeneration,
                detachedEngineCreated = false,
                detachedWarmReady = false,
                surfaceViewConstructed = false,
                surfaceViewInstalled = false,
                publishedSurfaceEpoch = 0L,
                manifestOwned = false,
                sourceClaimed = false,
                fullSceneBootstrapStartedThreads = bootstrap.startedThreadCount(),
                fullSceneSubmittedTasks = bootstrap.submittedTaskCount(),
                sourcePipelineCreated = false,
                nativeAuthorityInstalled = false,
                nativeReleaseAuthorityInstalled = false
            )
            Log.d(
                "ViewerPerf",
                "reader_full_scene_plan_reserved path=$plannedPath," +
                    "pages=${plan.pageCount},proof=${plan.proof.proofDigestSha256}," +
                    "prestartedThreads=${bootstrap.startedThreadCount()},submitted=0"
            )
        }
        if (transition.startDetachedWarm) beginDetachedWarm(checkNotNull(transition.demand))
        NtkSourceSpoolRegistry.currentAuthoritativeManifest(plannedPath)?.let {
            val proof = it.proof as? NtkViewerImageApiManifestProof
                ?: return@let
            promoteStrictEpisodePlan(proof.documentPlanProofDigestSha256, it)
        }
        return StageResult.BINDING
    }

    private fun onAuthoritativeManifest(path: String, manifest: NtkAuthoritativeManifest) {
        if (Looper.myLooper() !== Looper.getMainLooper()) return
        val current = NtkSourceSpoolRegistry.currentAuthoritativeManifest(path)
        if (state != State.PLANNED || path != plannedPath ||
            manifest.seal.normalizedEpisodePath != plannedPath ||
            current == null ||
            current.proof.discoveryGeneration != manifest.proof.discoveryGeneration ||
            current.proof.proofDigestSha256 != manifest.proof.proofDigestSha256
        ) return
        val proof = manifest.proof as? NtkViewerImageApiManifestProof
            ?: return failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
        pendingAuthoritativeManifest = manifest
        promoteStrictEpisodePlan(proof.documentPlanProofDigestSha256, manifest)
    }

    private fun promoteStrictEpisodePlan(
        planProofDigest: String,
        manifest: NtkAuthoritativeManifest
    ): StageResult {
        if (state != State.PLANNED) {
            return rejectStage(StageResult.PREPARED_NOT_READY)
        }
        val manga = plannedManga
            ?: return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        val proof = manifest.proof as? NtkViewerImageApiManifestProof
        val source = NtkSourceSpoolRegistry.currentSnapshot(plannedPath)
        if (!manifest.seal.isStructurallyComplete ||
            manifest.seal.normalizedEpisodePath != plannedPath ||
            plannedStartPage !in manifest.seal.normalizedCanonicalAssets.indices ||
            plannedControllerGeneration <= 0 ||
            bindingGeneration != plannedControllerGeneration ||
            sessionGeneration.get() != plannedControllerGeneration ||
            plannedDiscoveryGeneration <= 0L ||
            plannedPlanProofDigest.isEmpty() ||
            planProofDigest != plannedPlanProofDigest ||
            plannedRequestIdentityDigest.isEmpty() ||
            plannedPageCount != manifest.seal.pageCount ||
            proof == null ||
            proof.discoveryGeneration != plannedDiscoveryGeneration ||
            proof.documentPlanProofDigestSha256 != plannedPlanProofDigest ||
            proof.viewerImageRequestIdentityDigestSha256 != plannedRequestIdentityDigest ||
            proof.pageCount != plannedPageCount ||
            source == null ||
            source.generation != plannedDiscoveryGeneration ||
            source.planState != NtkPlanState.PROMOTED ||
            source.planProofDigest != plannedPlanProofDigest ||
            source.requestIdentityDigest != plannedRequestIdentityDigest ||
            source.plannedPageCount != plannedPageCount ||
            source.manifestDigest != manifest.seal.digestSha256 ||
            source.state != NtkSourceState.OWNED_PRECLAIM
        ) {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        val demand = currentSurfaceDemand
            ?: return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        targetReducer.onManifestOwned(demand.planGeneration, demand.planProofDigest)
        if (!targetReducer.snapshot().manifestOwned) {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        val strictPlan = strictPlanIdentity
            ?: return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        val strictManifest = runCatching {
            NtkStrictManifestIdentity.from(strictPlan, manifest.seal)
        }.getOrElse {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        val preparationTransition = synchronized(strictPreparationLock) {
            strictPreparationProtocol.onManifestPromoted(strictManifest)
        }
        if (preparationTransition.failed || !preparationTransition.startPreparation) {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        nextStrictPreparationGeneration = preparationTransition.preparationGeneration
        val bootstrap = checkNotNull(fullSceneExecutionBootstrap)
        check(bootstrap.submittedTaskCount() == 0L) {
            "Full-scene work started before exact manifest promotion"
        }
        check(
            stripPipeline == null &&
                stripRenderViewTarget?.currentNativeAuthorityToken() == null &&
                stripRenderViewTarget?.currentNativeReleaseToken() == null
        ) {
            "Native/source authority existed before exact controller promotion"
        }
        val result = stageContinuousStrip(
            manga = manga,
            title = plannedTitle,
            key = plannedKey,
            path = plannedPath,
            safeImages = manifest.seal.normalizedCanonicalAssets,
            startPage = plannedStartPage,
            startOffset = plannedStartOffset,
            executionBootstrap = bootstrap,
            preparationGeneration = preparationTransition.preparationGeneration
        )
        if (result == StageResult.BINDING || result == StageResult.STAGED) {
            fullSceneExecutionBootstrap = null
        }
        return result
    }

    @JvmOverloads
    fun stageProgressiveRunway(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startPage: Int = 0,
        startOffset: Int = 0
    ): StageResult {
        if (Looper.myLooper() !== Looper.getMainLooper()) return rejectStage(StageResult.WRONG_THREAD)
        if (state == State.DESTROYED) return rejectStage(StageResult.DESTROYED)
        if (state == State.ACTIVE) return StageResult.ACTIVE
        if (state == State.EXITING) return rejectStage(StageResult.PREPARED_NOT_READY)
        if (state == State.PLANNED || state == State.BINDING) {
            if (bindingMatches(manga, preparedKey)) return StageResult.BINDING
            cleanupRuntime("replace_binding", restoreEpisode = true, destroy = false, notifyExit = false)
        } else if (state == State.STAGED) {
            if (stagedMatches(manga, preparedKey)) return StageResult.ALREADY_STAGED
            cleanupRuntime("replace_staged", restoreEpisode = true, destroy = false, notifyExit = false)
        }
        val strictPath =
            NtkStripDigests.normalizeEpisodePath(manga.ntkEpisodePath.orEmpty())
        val strictKey = resolvePreparedKey(strictPath, preparedKey)
        if (strictPath.isEmpty() || strictKey.isEmpty()) {
            return rejectStage(StageResult.PREPARED_NOT_READY)
        }
        return planStrictEpisode(manga, title, strictKey, startPage, startOffset)
    }

    private fun stageContinuousStrip(
        manga: Manga,
        title: Title?,
        key: String,
        path: String,
        safeImages: List<String>,
        startPage: Int,
        startOffset: Int,
        executionBootstrap: NtkFullSceneExecutionBootstrap,
        preparationGeneration: Long
    ): StageResult {
        val manifestSeal = NtkSourceSpoolRegistry.currentManifestSeal(path)
            ?.takeIf { seal ->
                seal.isStructurallyComplete &&
                    seal.normalizedEpisodePath == NtkStripDigests.normalizeEpisodePath(path) &&
                    seal.pageCount == safeImages.size &&
                    seal.normalizedCanonicalAssets == safeImages.map(NtkStripDigests::canonicalAsset)
            }
            ?: return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        val generation = plannedControllerGeneration.takeIf {
            it > 0 && it == bindingGeneration && it == sessionGeneration.get()
        } ?: return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        bindingGeneration = generation
        // A click-owned cold entry may still be waiting for its authoritative manifest. Preserve
        // that one-shot activation intent while the continuous strip is constructed.
        launchLease = null
        activeManga = manga
        activeTitle = title ?: manga.title
        activeImages = ArrayList(safeImages)
        manifestPageCount = safeImages.size
        launchStartPage = startPage
        activePath = ""
        stagedPath = path
        stagedKey = key
        currentProgress = ReaderSurfaceView.ProgressPosition(startPage, startOffset)
        lastPublishedProgress = null
        visibleCoverageSnapshot = null
        activationDrawProof = null
        activationStateProof = null
        blockingStatus = ""
        hostPaused = false
        sessionStartBegan = false
        session = null
        sessionListener = null
        sessionStartTask = null
        sessionStartTicket = null
        drawableRegistry.clear()
        authoritativeBounds.clear()
        pendingSessionTiles.clear()
        latestStripProof = null
        stripForwardLeadPx = Long.MAX_VALUE
        stripPriorityDirection = 1
        state = State.BINDING

        // Quarantined source work may finish while the authoritative manifest is being bound.
        // Keep readiness already published for this generation and re-check now that the exact
        // page count is known. Clearing here loses early decoded pages and makes the all-ready
        // signal impossible even though every canonical tile is retained by the install queue.
        maybePublishAllImagesRenderReady(generation)

        manga.setImgs(ArrayList(safeImages))
        manga.ntkImageCount = safeImages.size
        manga.title = activeTitle

        renderView.setWindowListener(null)
        // SurfaceView stages a real buffer below the still-opaque episodeContent layer.
        hostView.visibility = View.VISIBLE
        hostView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        renderView.setFrameSchedulingSuppressed(true)
        renderView.setInlineRealPixelsOnly(true)
        renderView.setPageGapPx(0)
        // A fling must never outrun the contiguous real-pixel prefix. The renderer keeps the
        // last complete viewport moving smoothly, requests the blocked page immediately, and
        // releases the bound as soon as that drawable is installed. This prevents a large cold
        // manhwa from exposing transparent pages without substituting placeholders or hiding the
        // viewer behind a loading screen.
        renderView.setLimitScrollToDrawablePrefix(true)

        val controllerPort = object : NtkEpisodeStripPipeline.ControllerPort {
            override fun installSurfacePrepared(
                install: NtkPreparedTileInstall,
                surfaceToken: NtkSurfacePreparationToken,
                completion: (NtkPreparedTileResidentAck?) -> Unit
            ): Boolean {
                val target = stripRenderViewTarget ?: return false
                return target.installSurfacePrepared(install, surfaceToken, completion)
            }

            override fun adoptDetachedPreparation(
                request: NtkGeometryBindRequest,
                geometry: NtkStripGeometry,
                surface: NtkPublishedSurfaceIdentity,
                completion: (NtkGeometryBindProof?) -> Unit
            ) {
                val target = stripRenderViewTarget
                if (target == null || publishedSurfaceIdentity != surface) {
                    completion(null)
                    return
                }
                val gate = synchronized(strictPreparationLock) {
                    val protocol = strictPreparationProtocol
                    val opened = protocol.snapshot().detachedPreparation
                        ?: return@synchronized null
                    if (opened.engineGeneration != request.token.engineGeneration ||
                        opened.preparationGeneration != request.token.preparationGeneration ||
                        opened.tokenNonce != request.token.tokenNonce
                    ) return@synchronized null
                    val geometryTransition = protocol.onGeometrySeedAvailable(
                        NtkStrictGeometrySeedIdentity(
                            preparationGeneration = request.token.preparationGeneration,
                            geometryRevision = surface.geometryRevision,
                            viewportWidth = geometry.viewportWidthPx,
                            geometryDigest = geometry.geometryDigest,
                            geometryTileCount = geometry.tileCount
                        )
                    )
                    if (geometryTransition.failed) return@synchronized null
                    val inventoryTransition = protocol.onPreparedInventory(
                        NtkStrictPreparedInventoryIdentity(
                            preparation = opened,
                            preparedInventoryDigest = request.preparedInventoryDigest,
                            preparedTileCount = request.preparedTileKeys.size
                        )
                    )
                    if (inventoryTransition.failed) return@synchronized null
                    val surfaceTransition = protocol.onSurfacePublished(
                        NtkStrictPublishedSurfaceIdentity.from(
                            checkNotNull(strictDemandIdentity),
                            surface
                        )
                    )
                    if (surfaceTransition.failed) return@synchronized null
                    listOf(
                        geometryTransition,
                        inventoryTransition,
                        surfaceTransition
                    ).firstOrNull { it.adoptDetachedPreparationToSurface }
                }
                val join = gate?.joinIdentity
                if (gate == null || !gate.adoptDetachedPreparationToSurface || join == null) {
                    completion(null)
                    return
                }
                val proof = target.adoptDetachedPreparationToPublishedSurface(
                    request,
                    geometry,
                    (geometry.pages.getOrNull(startPage)?.contentTopPx ?: 0L) +
                        startOffset.coerceAtLeast(0)
                )
                if (proof == null) {
                    completion(null)
                    return
                }
                val bound = synchronized(strictPreparationLock) {
                    strictPreparationProtocol.onSurfacePreparationBound(join)
                }
                completion(proof.takeIf { !bound.failed })
            }

            override fun currentToken(): NtkNativeAuthorityToken? {
                val authority = generation.toLong()
                val direct = preparationEnginesByAuthority[authority]?.currentReleaseToken()
                if (direct?.authority == authority) return direct
                return stripRenderViewTarget?.currentNativeReleaseToken()
                    ?.takeIf { it.authority == authority }
            }

            override fun stage(
                authority: Long,
                corridorStartPx: Long,
                corridorEndPx: Long,
                stageNonce: Long,
                manifestRevision: Long,
                manifestDigest: String,
                geometryDigest: String,
                completion: (NtkStageProof?) -> Unit
            ) {
                val target = stripRenderViewTarget
                if (target == null) {
                    completion(null)
                    return
                }
                target.stage(
                    authority,
                    corridorStartPx,
                    corridorEndPx,
                    stageNonce,
                    manifestRevision,
                    manifestDigest,
                    geometryDigest,
                    completion
                )
            }

            override fun activate(authority: Long, stageNonce: Long): Boolean =
                stripRenderViewTarget?.activate(authority, stageNonce) == true

            override fun disarm(authority: Long): Boolean =
                stripRenderViewTarget?.disarm(authority) == true

            override fun releaseAuthority(
                request: NtkAuthorityReleaseRequest,
                completion: (NtkNativeAuthorityReleaseAck) -> Unit
            ): Boolean {
                val target = stripRenderViewTarget
                if (target != null && target.releaseAuthority(request, completion)) return true
                val engine = preparationEnginesByAuthority[generation.toLong()]
                return engine != null && engine.releaseAuthority(request, completion)
            }
        }
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ordinaryHeapClassBytes = activityManager.memoryClass.toLong() * 1024L * 1024L
        val cpuTransientPolicyBytes = minOf(
            ordinaryHeapClassBytes * 30L / 100L,
            192L * 1024L * 1024L
        )
        val transport = NtkSourceSpoolRegistry.claim(path, manifestSeal.digestSha256) ?: run {
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        stripTransport = transport
        if (!NtkSourceSpoolRegistry.markClaimPhase(
                path,
                manifestSeal.digestSha256,
                NtkManifestClaimPhase.BINDING
            )
        ) {
            transport.retire(NtkEpisodeToken(generation.toLong()))
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        val demand = currentSurfaceDemand
        if (demand == null || !targetReducer.markSourceClaimed(demand.generation)) {
            transport.retire(NtkEpisodeToken(generation.toLong()))
            failBindingNow(StageResult.PREPARED_IDENTITY_MISMATCH)
            return rejectStage(StageResult.PREPARED_IDENTITY_MISMATCH)
        }
        val pipeline = NtkEpisodeStripPipeline(
            authority = generation.toLong(),
            preparationGeneration = preparationGeneration,
            canonicalAssets = safeImages,
            sourceTransport = transport,
            controllerPort = controllerPort,
            listener = object : NtkEpisodeStripPipeline.Listener {
                override fun onStageReady(
                    pipeline: NtkEpisodeStripPipeline,
                    geometry: NtkStripGeometry
                ) {
                    onFailed(
                        pipeline,
                        IllegalStateException("Native stage callback omitted composition proof")
                    )
                }

                override fun onStageReady(
                    pipeline: NtkEpisodeStripPipeline,
                    geometry: NtkStripGeometry,
                    proof: NtkStageProof
                ) {
                    // The callback arrives only after every manifest source and geometry tile is
                    // proved, resident, drained, and sealed across [0, contentHeight). The upload
                    // context is retired and the decoder gate is permanently zero before this
                    // boundary, so STAGED/ACTIVE cannot admit invisible background mutations.
                    if (stripPipeline !== pipeline || !isSessionGenerationActive(generation) ||
                        state != State.BINDING
                    ) return
                    if (proof.corridorStartPx != 0L ||
                        proof.corridorEndPx != geometry.contentHeightPx
                    ) {
                        onFailed(
                            pipeline,
                            IllegalStateException("Native stage proof does not cover full scene")
                        )
                        return
                    }
                    if (!NtkSourceSpoolRegistry.markClaimPhase(
                            path,
                            manifestSeal.digestSha256,
                            NtkManifestClaimPhase.STAGED
                        )
                    ) {
                        onFailed(
                            pipeline,
                            IllegalStateException("Manifest authority could not enter STAGED")
                        )
                        return
                    }
                    stagedActivationCoverage = stripRenderView.visibleCoverageSnapshot()
                    stagedActivationReadiness = stripRenderView.pageReadinessSnapshot()
                    stagedActivationRunway =
                        stripRenderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
                    state = State.STAGED
                    publishedStageTicket = StageTicket(
                        generation,
                        pipeline.authority,
                        stagedPath,
                        stagedKey,
                        activeImages.size,
                        proof
                    )
                    blockingStatus = ""
                    postMain {
                        if (stripPipeline !== pipeline || !isSessionGenerationActive(generation) ||
                            state != State.STAGED
                        ) return@postMain
                        renderView.setWindowListener(this@NtkInlineReaderController)
                        freezeEpisodeParentBeforeReader()
                        enableUnbufferedPointerDispatch()
                        if (activateWhenBindingCompletes) {
                            activateWhenBindingCompletes = false
                            activateStaged()
                        } else {
                            // Keep the staged reader out of this ViewRoot's frame traversal.
                            // Real tile GPU preparation happens on the decode workers; drawing an
                            // entire second hierarchy behind the episode list creates avoidable
                            // ViewRoot/HWUI work immediately before the user's opening tap.
                            callbacks.onStageReady(this@NtkInlineReaderController, stagedPath, stagedKey)
                        }
                    }
                }

                override fun onContractState(
                    pipeline: NtkEpisodeStripPipeline,
                    state: NtkRunwayContractState
                ) = Unit

                override fun onProofSnapshot(
                    pipeline: NtkEpisodeStripPipeline,
                    proof: NtkEpisodeProofSnapshot
                ) {
                    if (stripPipeline === pipeline && isSessionGenerationActive(generation)) {
                        latestStripProof = proof
                    }
                }

                override fun onFailed(pipeline: NtkEpisodeStripPipeline, error: Throwable) = postMain {
                    if (stripPipeline !== pipeline || !isSessionGenerationActive(generation)) return@postMain
                    handleStripPipelineFailure(pipeline, error)
                }

                override fun onTerminalCleanupComplete(pipeline: NtkEpisodeStripPipeline) {
                    preparationEnginesByAuthority.remove(pipeline.authority)
                    postMain {
                        val current = stripPipeline ?: return@postMain
                        if (current === pipeline) return@postMain
                        val port = detachedPreparationPort ?: return@postMain
                        if (preparationEnginesByAuthority.isEmpty()) {
                            current.onDetachedPreparationAvailable(port)
                        }
                    }
                }
            },
            cpuTransientPolicyBytes = cpuTransientPolicyBytes,
            manifestSeal = manifestSeal,
            initialPageIndex = startPage,
            initialPageOffsetPx = startOffset,
            fullSceneExecutionBootstrap = executionBootstrap
        )
        stripPipeline = pipeline
        lastStripTelemetryTop = 0L
        lastStripTelemetryAtMs = 0L
        val controllerStartAt = SystemClock.elapsedRealtime()
        android.util.Log.d(
            "ViewerPerf",
            "reader_strip_controller_start path=$path,authority=${pipeline.authority}," +
                "sources=${safeImages.size},preparation=$preparationGeneration," +
                "controllerStartAt=$controllerStartAt"
        )
        pipeline.start()
        detachedPreparationPort?.takeIf {
            preparationEnginesByAuthority.isEmpty()
        }?.let(pipeline::onDetachedPreparationAvailable)
        publishedSurfaceIdentity?.let { identity ->
            val target = stripRenderViewTarget
            if (target != null) {
                bindPipelinePresentationTarget(target, pipeline, generation, identity.height)
                pipeline.onGeometrySeed(identity.width, identity.height, identity.geometryRevision)
                pipeline.onSurfaceAvailable(identity)
            }
        }
        return StageResult.BINDING
    }

    private fun bindPipelinePresentationTarget(
        target: NtkStripSurfaceView,
        pipeline: NtkEpisodeStripPipeline,
        generation: Int,
        viewportHeight: Int
    ) {
        target.frameListener = frameListener@{ frame ->
            val activePipeline = stripPipeline
            if (activePipeline !== pipeline ||
                activePipeline.authority != frame.authority ||
                !isSessionGenerationActive(generation)
            ) return@frameListener
            val previousTop = lastStripTelemetryTop
            lastStripTelemetryTop = frame.scrollTopPx
            stripPriorityDirection = when {
                frame.velocityPxPerSecond > 0.5f -> 1
                frame.velocityPxPerSecond < -0.5f -> -1
                frame.scrollTopPx > previousTop -> 1
                frame.scrollTopPx < previousTop -> -1
                else -> stripPriorityDirection
            }
            val viewportBottom = frame.scrollTopPx + viewportHeight
            stripForwardLeadPx = maxOf(0L, frame.residentContinuousEndPx - viewportBottom)
            currentProgress = target.progressPosition()
            if (state == State.ACTIVE) publishViewerEdge(target.scrollPositionSnapshot())
            // Presentation is a native frame fact, not a projection of the main-thread UI
            // state. In particular, the authoritative stage-frame callback may race the
            // onStageReady publication from the pipeline actor to main. Never erase that proof
            // merely because the controller still observes BINDING in this callback turn.
            val presentedProof = if (
                frame.sceneVersion > 0L && frame.firstVisiblePage >= 0 &&
                frame.lastVisiblePage >= frame.firstVisiblePage &&
                frame.visibleContentEndPx > frame.visibleContentStartPx
            ) {
                NtkPresentedFrameProof(
                    frame.authority,
                    frame.sceneVersion,
                    frame.viewportOriginalComplete,
                    frame.runwayOriginalComplete,
                    frame.visibleContentStartPx,
                    frame.visibleContentEndPx,
                    frame.firstVisiblePage,
                    frame.lastVisiblePage,
                    frame.firstVisibleGapPx,
                    frame.residentContinuousStartPx,
                    frame.residentContinuousEndPx,
                    frameSequence = frame.frameSequence
                )
            } else {
                null
            }
            activePipeline.onViewportSample(NtkViewportSample(
                surfaceEpoch = frame.surfaceEpoch,
                frameSequence = frame.frameSequence,
                gestureId = frame.gestureId,
                appliedInputSequence = frame.appliedInputSequence,
                topPx = frame.scrollTopPx,
                velocityPxPerSecond = frame.velocityPxPerSecond,
                predictedStopPx = frame.predictedStopPx,
                presentedProof = presentedProof
            ))
        }
    }

    private fun handleStripPipelineFailure(
        pipeline: NtkEpisodeStripPipeline,
        error: Throwable
    ) {
        android.util.Log.e(
            "ViewerPerf",
            "reader_strip_failed authority=${pipeline.authority},state=$state",
            error
        )
        if (state != State.BINDING) {
            noteInvariantViolation(bindingGeneration, "strip_pipeline_failure")
            return
        }
        // Exact authority is immutable after OWNED. Any source/pipeline failure is terminal; there
        // is no replacement/restart path that could create a second network producer.
        failBindingNow(StageResult.SURFACE_REJECTED)
    }

    /** Compatibility alias; the continuous strip pipeline is the authoritative NTK path. */
    @JvmOverloads
    fun stageWindowReady(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startPage: Int = 0,
        startOffset: Int = 0
    ): StageResult = stageProgressiveRunway(manga, title, preparedKey, startPage, startOffset)

    @JvmOverloads
    fun enterProgressiveRunway(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startPage: Int = 0,
        startOffset: Int = 0
    ): EnterResult {
        if (Looper.myLooper() !== Looper.getMainLooper()) return reject(EnterResult.WRONG_THREAD)
        if (state == State.DESTROYED) return reject(EnterResult.DESTROYED)
        if (state == State.ACTIVE) return EnterResult.ALREADY_ACTIVE
        if (state == State.PLANNED || state == State.BINDING) {
            if (bindingMatches(manga, preparedKey)) {
                activateWhenBindingCompletes = true
                return EnterResult.BINDING
            }
            cleanupRuntime("replace_binding_enter", restoreEpisode = true, destroy = false, notifyExit = false)
        } else if (state == State.STAGED) {
            if (stagedMatches(manga, preparedKey)) return activateStaged()
            cleanupRuntime("replace_staged_enter", restoreEpisode = true, destroy = false, notifyExit = false)
        }
        return when (stageProgressiveRunway(
            manga,
            title,
            preparedKey,
            startPage,
            startOffset
        )) {
            StageResult.BINDING -> {
                if (bindingMatches(manga, preparedKey)) activateWhenBindingCompletes = true
                EnterResult.BINDING
            }
            StageResult.STAGED, StageResult.ALREADY_STAGED -> activateStaged()
            StageResult.ACTIVE -> EnterResult.ALREADY_ACTIVE
            StageResult.DESTROYED -> reject(EnterResult.DESTROYED)
            StageResult.WRONG_THREAD -> reject(EnterResult.WRONG_THREAD)
            StageResult.HOST_NOT_ATTACHED -> reject(EnterResult.HOST_NOT_ATTACHED)
            StageResult.HOST_NOT_LAID_OUT -> reject(EnterResult.HOST_NOT_LAID_OUT)
            StageResult.PREPARED_NOT_READY -> reject(EnterResult.PREPARED_NOT_READY)
            StageResult.PREPARED_IDENTITY_MISMATCH -> reject(EnterResult.PREPARED_IDENTITY_MISMATCH)
            StageResult.SURFACE_REJECTED -> reject(EnterResult.SURFACE_REJECTED)
            StageResult.SESSION_START_FAILED -> reject(EnterResult.SESSION_START_FAILED)
        }
    }

    @JvmOverloads
    fun enterProgressiveRunwayForPress(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startPage: Int = 0,
        startOffset: Int = 0
    ): EnterResult {
        if (Looper.myLooper() !== Looper.getMainLooper()) return reject(EnterResult.WRONG_THREAD)
        if (state == State.STAGED && stagedMatches(manga, preparedKey)) {
            return activateStaged(preserveEpisodeTouchTarget = true)
        }
        // A physical DOWN is never a bind request. Callers may prepare through
        // stageProgressiveRunway(), then publish a row only after getStageTicket() succeeds.
        return reject(EnterResult.PREPARED_NOT_READY)
    }

    fun enterStageTicketForPress(manga: Manga, ticket: StageTicket?): EnterResult {
        if (Looper.myLooper() !== Looper.getMainLooper()) return reject(EnterResult.WRONG_THREAD)
        val current = publishedStageTicket
        if (state != State.STAGED || ticket == null || current == null || ticket != current ||
            ticket.generation != bindingGeneration || ticket.path != stagedPath ||
            ticket.preparedKey != stagedKey || ticket.pageCount != activeImages.size ||
            (stripPipeline != null && !ticket.hasNativeStageProof()) ||
            !stagedMatches(manga, ticket.preparedKey)
        ) {
            return reject(EnterResult.PREPARED_NOT_READY)
        }
        return activateStaged(preserveEpisodeTouchTarget = true)
    }

    fun commitPressActivation() {
        if (state != State.ACTIVE || !pressActivationPending) return
        pressActivationPending = false
        val committingPath = activePath
        activationCommitQueued = stripPipeline != null
        // The row is invoking its click only after it has consumed the physical UP. Commit the
        // already revealed compositor owner in this same dispatch turn so readiness publication
        // cannot be delayed by unrelated list/network messages queued on the main looper.
        completeActivation(
            committingPath,
            SystemClock.uptimeMillis(),
            currentUptimeNanos(),
            SystemClock.elapsedRealtimeNanos()
        )
    }

    fun cancelPressActivation(): Boolean {
        if (state != State.ACTIVE || !pressActivationPending || stagedPath.isEmpty() ||
            stagedKey.isEmpty() || publishedStageTicket == null
        ) return false
        pressActivationPending = false
        activationCommitQueued = false
        pressPreviewEpochPublished = false
        activePath = ""
        state = State.STAGED
        if (stripPipeline == null) boostHostUiThreadForInput()
        stripRenderView.setCompositorAlpha(0f)
        hostView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return true
    }

    /** Compatibility alias; never falls through to a second Activity. */
    @JvmOverloads
    fun enterWindowReady(
        manga: Manga,
        title: Title?,
        preparedKey: String?,
        startPage: Int = 0,
        startOffset: Int = 0
    ): EnterResult = enterProgressiveRunway(manga, title, preparedKey, startPage, startOffset)

    private fun activateStaged(preserveEpisodeTouchTarget: Boolean = false): EnterResult {
        if (state != State.STAGED || (launchLease == null && stripPipeline == null) || stagedPath.isEmpty()) {
            return reject(EnterResult.PREPARED_NOT_READY)
        }
        val path = stagedPath
        activePath = path
        visibleCoverageSnapshot = null
        activationDrawProof = null
        activationStateProof = null
        blockingStatus = ""
        hostPaused = false
        state = State.ACTIVE

        pressActivationPending = preserveEpisodeTouchTarget
        // Timestamp the reveal transaction boundary immediately before mutating the compositor
        // layer. The strip buffer was already presented while STAGED.
        val visibleAtUptimeMs = SystemClock.uptimeMillis()
        val visibleAtUptimeNanos = currentUptimeNanos()
        val visibleAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        // NtkStripSurfaceView is already a Z-order-on-top compositor layer. Parent View and
        // RenderNode state stays byte-for-byte unchanged; EpisodeActivity changes input routing
        // only after the opening row consumes its physical UP.
        if (!stripRenderView.setCompositorAlpha(1f)) {
            state = State.STAGED
            activePath = ""
            pressActivationPending = false
            return reject(EnterResult.SURFACE_REJECTED)
        }
        if (stripPipeline == null) {
            hostView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            hostView.visibility = View.VISIBLE
        }
        // This is the actual user-visible activation boundary. Coverage/proof snapshots below
        // are diagnostics over already-visible immutable pixels and must not redefine latency.
        if (preserveEpisodeTouchTarget) {
            // DOWN is the compositor reveal boundary. Publish the monotonic epoch now; owner
            // commit and callbacks still wait until the row consumes its physical UP.
            activationSequence++
            activationUptimeMs = visibleAtUptimeMs
            activationUptimeNanos = visibleAtUptimeNanos
            activationElapsedRealtimeNanos = visibleAtElapsedRealtimeNanos
            windowFocusedAtActivation = activity.hasWindowFocus()
            hostAttachedAtActivation = hostView.isAttachedToWindow && stripRenderView.isAttachedToWindow
            val previewEpoch = activationSequence
            val previewCoverage = stagedActivationCoverage
                ?: stripRenderView.visibleCoverageSnapshot()
            val previewReadiness = stagedActivationReadiness
                ?: stripRenderView.pageReadinessSnapshot()
            val previewRunway = stagedActivationRunway
                ?: stripRenderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
            activationStateProof = ActivationStateProof(
                previewEpoch,
                visibleAtUptimeNanos,
                previewCoverage,
                previewReadiness,
                previewRunway,
                false,
                publishedStageTicket
            )
            resetTouchDeliveryStats()
            pressPreviewEpochPublished = true
            // Volatile publication is last: readers observing this epoch also observe its proof.
            activationEpoch = previewEpoch
            return EnterResult.ENTERED
        }
        activationCommitQueued = stripPipeline != null
        completeActivation(
            path,
            visibleAtUptimeMs,
            visibleAtUptimeNanos,
            visibleAtElapsedRealtimeNanos
        )
        return EnterResult.ENTERED
    }

    /**
     * Commits input ownership only after the episode-row UP has been delivered. The compositor
     * preview is already visible from DOWN, but publishing the epoch sooner lets a following
     * physical stream overlap the still-open row gesture and Android correctly drops it.
     */
    private fun completeActivation(
        path: String,
        visibleAtUptimeMs: Long,
        visibleAtUptimeNanos: Long,
        visibleAtElapsedRealtimeNanos: Long
    ) {
        val continuousPipeline = stripPipeline
        if (continuousPipeline != null) {
            val ticket = publishedStageTicket
            if (ticket == null || !ticket.hasNativeStageProof() ||
                ticket.authority != continuousPipeline.authority
            ) {
                failBindingNow(StageResult.SURFACE_REJECTED)
                return
            }
            continuousPipeline.activate { accepted ->
                postMain activation@{
                    if (stripPipeline !== continuousPipeline || state != State.ACTIVE ||
                        publishedStageTicket != ticket ||
                        ticket.generation != bindingGeneration
                    ) return@activation
                    if (!accepted || !NtkSourceSpoolRegistry.markClaimPhase(
                            path,
                            ticket.manifestDigest,
                            NtkManifestClaimPhase.ACTIVE
                        )
                    ) {
                        failBindingNow(StageResult.SURFACE_REJECTED)
                        return@activation
                    }
                    finishActivation(
                        path,
                        visibleAtUptimeMs,
                        visibleAtUptimeNanos,
                        visibleAtElapsedRealtimeNanos
                    )
                }
            }
            return
        }
        finishActivation(
            path,
            visibleAtUptimeMs,
            visibleAtUptimeNanos,
            visibleAtElapsedRealtimeNanos
        )
    }

    private fun finishActivation(
        path: String,
        visibleAtUptimeMs: Long,
        visibleAtUptimeNanos: Long,
        visibleAtElapsedRealtimeNanos: Long
    ) {
        if (stripPipeline == null) renderView.setWindowListener(this)
        if (stripPipeline == null) {
            renderView.setFrameSchedulingSuppressed(false)
            renderView.setWindowListener(this)
            renderView.requestFocus()
        }

        if (!pressPreviewEpochPublished) {
            activationSequence++
            activationEpoch = activationSequence
            activationUptimeMs = visibleAtUptimeMs
            activationUptimeNanos = visibleAtUptimeNanos
            activationElapsedRealtimeNanos = visibleAtElapsedRealtimeNanos
        }
        val proofWasPublishedAtPreview = pressPreviewEpochPublished
        pressPreviewEpochPublished = false
        val nextActivationEpoch = activationEpoch
        val previewProof = if (proofWasPublishedAtPreview) activationStateProof else null
        val activationCoverage = previewProof?.coverage ?: if (stripPipeline != null) {
            stripRenderView.visibleCoverageSnapshot()
        } else renderView.refreshVisibleCoverageSnapshot()
        val activationReadiness = previewProof?.readiness ?: if (stripPipeline != null) {
            stripRenderView.pageReadinessSnapshot()
        } else renderView.pageReadinessSnapshot()
        val activationRunway = previewProof?.runway ?: if (stripPipeline != null) {
            stripRenderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
        } else renderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
        activationStateProof = ActivationStateProof(
            nextActivationEpoch,
            activationUptimeNanos,
            activationCoverage,
            activationReadiness,
            activationRunway,
            false,
            previewProof?.stageTicket ?: publishedStageTicket
        )
        windowFocusedAtActivation = activity.hasWindowFocus()
        hostAttachedAtActivation = hostView.isAttachedToWindow && if (stripPipeline != null) {
            stripRenderView.isAttachedToWindow
        } else renderView.isAttachedToWindow
        if (!proofWasPublishedAtPreview) resetTouchDeliveryStats()
        if (stripPipeline != null && activationCoverage != null) {
            val stagedFrame = stripRenderView.latestFrameSnapshot()
            val completed = ActivationDrawProof(
                nextActivationEpoch,
                activationUptimeNanos,
                stagedFrame?.sceneVersion ?: 0L,
                activationUptimeNanos,
                true,
                windowFocusedAtActivation,
                hostAttachedAtActivation,
                activationCoverage,
                activationReadiness,
                activationRunway
            )
            activationDrawProof = completed
            visibleCoverageSnapshot = activationCoverage
            firstDrawStrictRunwayReady = strictRunwayReady(
                activationCoverage,
                activationRunway,
                stripRenderView.height
            )
            callbacks.onFirstCompletedDraw(this, completed)
        }
        // ACTION_UP owns only the already-prepared visibility/epoch swap. Continuation demand is
        // production work, but enumerating and submitting its byte flights must never consume the
        // input turn. The same Session and same real assets run on the coordinator immediately.
        val activatedSession = session
        if (stripPipeline == null) {
            try {
                AppDispatchers.submitNtkViewerCritical {
                    if (activatedSession === session && state == State.ACTIVE) {
                        activatedSession?.onViewerActivated()
                    }
                }
            } catch (_: Throwable) {
                // The next physical window callback resubmits identical byte demand.
            }
        }
        if (stripPipeline == null) renderView.requestRender()
        stagedPath = ""
        stagedKey = ""
        publishedStageTicket = null
        pressActivationPending = false
        activationCommitQueued = false
        // Volatile publication is last: observers of the committed epoch also observe native
        // activation ACK, immutable proof, input ownership and the cleared commit gate.
        activationEpoch = nextActivationEpoch
        callbacks.onActivated(this, path, nextActivationEpoch)
    }

    private fun startProgressiveSession(
        generation: Int,
        manga: Manga,
        title: Title?,
        preparedKey: String,
        viewportWidth: Int,
        viewportHeight: Int,
        preparedLaunchRunwaySpec: ReaderPreparedStore.LaunchRunwaySpec,
        startPage: Int,
        startOffset: Int,
        listener: ReaderSession.Listener,
        startTicket: SessionStartTicket
    ) {
        check(manga.ntkEpisodePath.isNullOrBlank()) {
            "Release invariant: exact NTK current episode must never create ReaderSession"
        }
        var built: ReaderSession? = null
        try {
            built = ReaderSession(
                activity.applicationContext,
                manga,
                title,
                viewportWidth,
                viewportHeight,
                autoCut = false,
                reverse = MainApplication.p?.getReverse() == true,
                preparedKey = preparedKey,
                startAtFirstPage = startPage == 0 && startOffset == 0,
                listener = listener,
                preparedLaunchRunwaySpec = preparedLaunchRunwaySpec,
                pagePipeline = ReaderPagePipelineRegistry.get(preparedKey)
            )
            synchronized(runtimeLock) {
                if (!isSessionGenerationActive(generation)) {
                    built.cancel()
                    return
                }
                session = built
                sessionStartBegan = true
            }
            built.start()
            val cancelAfterStart = startTicket.markStartReturned() ||
                !isSessionGenerationActive(generation)
            if (cancelAfterStart) {
                built.cancel()
                return
            }
            requestCurrentWindow(generation, busy = false)
            postMain { completeProgressiveBinding(generation) }
        } catch (_: Throwable) {
            startTicket.markStartReturned()
            built?.cancel()
            postMain { failBindingAsync(generation, StageResult.SESSION_START_FAILED) }
        }
    }

    private fun completeProgressiveBinding(generation: Int) {
        if (!isSessionGenerationActive(generation) || state != State.BINDING) return
        val lease = launchLease ?: return failBindingAsync(
            generation,
            StageResult.PREPARED_NOT_READY
        )
        val manga = activeManga ?: return failBindingAsync(
            generation,
            StageResult.PREPARED_IDENTITY_MISMATCH
        )
        if (!sessionStartBegan || session == null || !initialPreparationReady(
                initialPrepareStats,
                requiredPreparedThroughY
            ) || !strictActivationReady()
        ) {
            return failBindingAsync(generation, StageResult.SURFACE_REJECTED)
        }
        state = State.STAGED
        stagedActivationCoverage = renderView.refreshVisibleCoverageSnapshot()
        stagedActivationReadiness = renderView.pageReadinessSnapshot()
        stagedActivationRunway = renderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
        boostHostUiThreadForInput()
        enableUnbufferedPointerDispatch()
        publishedStageTicket = StageTicket(
            generation,
            generation.toLong(),
            stagedPath,
            stagedKey,
            activeImages.size
        )
        blockingStatus = ""
        requestCurrentWindow(generation, busy = false)
        if (activateWhenBindingCompletes) {
            activateWhenBindingCompletes = false
            activateStaged()
        } else {
            callbacks.onStageReady(this, stagedPath, stagedKey)
        }
    }

    private fun preparedStoreRelay(generation: Int): ReaderPreparedStore.Listener {
        return object : ReaderPreparedStore.Listener {
            override fun onUrlsReady(images: List<String>, startPage: Int) {
                if (!isSessionGenerationActive(generation)) return
                val matches = images == activeImages &&
                    startPage == currentProgress?.page &&
                    imagesMatchExactEpisode(activeManga, stagedPath, images)
                if (!matches) postMain {
                    failBindingAsync(generation, StageResult.PREPARED_IDENTITY_MISMATCH)
                }
            }

            override fun onBitmapReady(index: Int, bitmap: android.graphics.Bitmap) {
                // Full bitmaps are never a substitute for the authoritative inline tile contract.
            }

            override fun onTilePageBatchReady(
                tilePages: Map<Int, ReaderPreparedStore.PreparedTilePage>
            ) {
                if (!isSessionGenerationActive(generation)) return
                val safeBatch = LinkedHashMap(tilePages)
                postMain {
                    if (!isSessionGenerationActive(generation)) return@postMain
                    for ((index, tilePage) in safeBatch) {
                        installPreparedStoreTiles(generation, index, tilePage)
                    }
                    requestCurrentWindow(generation, busy = false)
                    completeProgressiveBinding(generation)
                }
            }

            override fun onFailed() {
                if (!isSessionGenerationActive(generation) || sessionStartBegan) return
                postMain { failBindingAsync(generation, StageResult.PREPARED_NOT_READY) }
            }
        }
    }

    private fun progressiveSurfaceSink(generation: Int): ReaderSession.Listener {
        return object : ReaderSession.Listener {
            override fun onPagesReady(count: Int) {
                // The lease manifest is authoritative. A partial Session count never resets the
                // already-adopted 31-slot surface.
                requestCurrentWindow(generation, busy = false)
            }

            override fun onPagesAppended(count: Int) {
                if (count > manifestPageCount) noteInvariantViolation(
                    generation,
                    "adjacent_pages_appended"
                )
            }

            override fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int) {
                noteInvariantViolation(generation, "adjacent_pages_prepended")
            }

            override fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int) {
                noteInvariantViolation(generation, "manifest_pages_removed")
            }

            override fun onInitialPage(index: Int) {
                // Partial adoption already fixed the exact start anchor.
            }

            override fun onPageLoading(index: Int) {
                // Strict inline mode never mutates visual state for loading.
            }

            override fun onPageBoundsReady(index: Int, width: Int, height: Int) {
                if (Looper.myLooper() !== Looper.getMainLooper()) {
                    postMain { onPageBoundsReady(index, width, height) }
                    return
                }
                if (!isSessionGenerationActive(generation) ||
                    !canonicalOriginalManifestBound(generation, index) || width <= 0 || height <= 0
                ) return
                var pending: PendingSessionTiles? = null
                var accepted = false
                synchronized(runtimeLock) {
                    if (!isSessionGenerationActive(generation)) return@synchronized
                    val previous = authoritativeBounds[index]
                    if (previous != null && previous != (width to height)) {
                        pendingSessionTiles.remove(index)
                        noteInvariantViolation(generation, "encoded_bounds_changed_$index")
                        return@synchronized
                    }
                    authoritativeBounds[index] = width to height
                    accepted = true
                    pending = pendingSessionTiles.remove(index)
                    if (!renderView.hasPageDrawable(index)) {
                        renderView.setPageBounds(index, width, height)
                    }
                }
                if (!accepted) return
                val waiting = pending
                if (waiting != null && waiting?.generation == generation) {
                    installSessionTiles(
                        generation,
                        index,
                        waiting!!.pageWidth,
                        waiting!!.pageHeight,
                        waiting!!.tiles,
                        retainIfBoundsMissing = false
                    )
                }
                requestCurrentWindow(generation, busy = false)
            }

            override fun onPageReady(index: Int, bitmap: android.graphics.Bitmap) {
                // A whole bitmap lacks the generation-bound unsampled region proof.
            }

            override fun onPageProofReady(index: Int, bitmap: android.graphics.Bitmap) {
                // Same strict tile-only rule as onPageReady.
            }

            override fun onPageTilesReady(
                index: Int,
                pageWidth: Int,
                pageHeight: Int,
                tiles: List<ReaderTile>
            ) {
                installSessionTiles(generation, index, pageWidth, pageHeight, tiles)
            }

            override fun onPageDecodedRenderReady(
                index: Int,
                pageWidth: Int,
                pageHeight: Int,
                tiles: List<ReaderTile>
            ): Boolean = installSessionTiles(generation, index, pageWidth, pageHeight, tiles)

            override fun onPageLaunchRunwayTilesReady(
                index: Int,
                pageWidth: Int,
                pageHeight: Int,
                tiles: List<ReaderTile>
            ) {
                installSessionTiles(generation, index, pageWidth, pageHeight, tiles)
            }

            override fun onPageProofTilesReady(
                index: Int,
                pageWidth: Int,
                pageHeight: Int,
                tiles: List<ReaderTile>
            ) {
                installSessionTiles(generation, index, pageWidth, pageHeight, tiles)
            }

            override fun isPageDrawableInstalled(index: Int): Boolean =
                isSessionGenerationActive(generation) && renderView.hasPageDrawable(index)

            override fun isPageAuthoritativeDrawableInstalled(index: Int): Boolean {
                if (!isSessionGenerationActive(generation) || !renderView.hasPageDrawable(index)) {
                    return false
                }
                return synchronized(runtimeLock) {
                    val entry = drawableRegistry.entry(index)
                    entry?.identity?.kind == AdoptedDrawableIdentity.Kind.FULL_QUALITY_TILES &&
                        renderView.hasPageDrawable(index)
                }
            }

            override fun isPageAuthoritativeDrawableInstalled(
                index: Int,
                pageWidth: Int,
                pageHeight: Int,
                tiles: List<ReaderTile>
            ): Boolean {
                if (!isSessionGenerationActive(generation) || !renderView.hasPageDrawable(index)) {
                    return false
                }
                val candidate = AdoptedDrawableIdentity.fullQualityTiles(
                    pageWidth,
                    pageHeight,
                    tiles
                ) ?: return false
                return synchronized(runtimeLock) {
                    drawableRegistry.matches(index, candidate) && renderView.hasPageDrawable(index)
                }
            }

            override fun onInitialPageDecoded(
                index: Int,
                bitmap: android.graphics.Bitmap
            ): ReaderSession.InitialPrerenderResult = ReaderSession.InitialPrerenderResult.NOT_RENDERED

            override fun onInitialPageTilesDecoded(
                index: Int,
                pageWidth: Int,
                pageHeight: Int,
                tiles: List<ReaderTile>
            ): ReaderSession.InitialPrerenderResult {
                return if (installSessionTiles(generation, index, pageWidth, pageHeight, tiles)) {
                    ReaderSession.InitialPrerenderResult.RENDERED_AND_COMMIT
                } else {
                    ReaderSession.InitialPrerenderResult.NOT_RENDERED
                }
            }

            override fun onInitialContinuousPageDecoded(
                index: Int,
                bitmap: android.graphics.Bitmap
            ): ReaderSession.InitialPrerenderResult = ReaderSession.InitialPrerenderResult.NOT_RENDERED

            override fun onInitialContinuousPageTilesDecoded(
                index: Int,
                pageWidth: Int,
                pageHeight: Int,
                tiles: List<ReaderTile>
            ): ReaderSession.InitialPrerenderResult {
                return if (installSessionTiles(generation, index, pageWidth, pageHeight, tiles)) {
                    ReaderSession.InitialPrerenderResult.RENDERED_ONLY
                } else {
                    ReaderSession.InitialPrerenderResult.NOT_RENDERED
                }
            }

            override fun onPageCard(index: Int, title: String) {
                // Adjacent cards never replace exact current-episode pixels.
            }

            override fun onPageError(index: Int, message: String) {
                requestCurrentWindow(generation, busy = false)
            }

            override fun onPageCleared(index: Int) {
                if (Looper.myLooper() !== Looper.getMainLooper()) {
                    postMain { onPageCleared(index) }
                    return
                }
                synchronized(runtimeLock) {
                    if (!isSessionGenerationActive(generation)) return@synchronized
                    pendingSessionTiles.remove(index)
                    val adopted = drawableRegistry.entry(index)
                    if (adopted?.origin == DrawableOrigin.PREPARED_STORE) {
                        // The launch lease, not ReaderSession's rolling cache, owns these pixels.
                        // A producer eviction must not punch a hole in the attached runway.
                        return@synchronized
                    }
                    // Surface residency and the ownership registry are one invariant. Leaving a
                    // READER_SESSION entry behind made a later redelivery look already installed
                    // after clearPageBitmap had removed the actual pixels.
                    drawableRegistry.remove(index, DrawableOrigin.READER_SESSION)
                    renderView.clearPageBitmap(index)
                }
                requestCurrentWindow(generation, busy = false)
            }

            override fun onMessage(message: String) {
                // Producer status is deliberately not a visual placeholder.
            }

            override fun onCaptchaRequired(manga: Manga) {
                noteInvariantViolation(generation, "captcha_required", manga)
            }

            override fun onBoundaryAppendFinished(
                anchor: Int,
                direction: Int,
                silent: Boolean,
                suppressedCaptcha: Boolean
            ) {
                // Current-episode rolling production never uses boundary append completion.
            }
        }
    }

    private fun installPreparedStoreTiles(
        generation: Int,
        index: Int,
        page: ReaderPreparedStore.PreparedTilePage
    ): Boolean {
        synchronized(runtimeLock) {
            if (!isSessionGenerationActive(generation) ||
                !canonicalOriginalManifestBound(generation, index)
            ) return false
            val identity = AdoptedDrawableIdentity.fullQualityTiles(
                page.pageWidth,
                page.pageHeight,
                page.tiles
            ) ?: return false
            val proof = page.originalProof ?: return false
            if (proof.canonicalAsset != ReaderPreparedStore.canonicalOriginalAssetIdentity(
                    activeImages.getOrNull(index)
                ) || proof.variant != ReaderPreparedStore.PreparedAssetVariant.ORIGINAL ||
                proof.inSampleSize != 1 || proof.postDecodeResized ||
                proof.originalWidth != page.pageWidth || proof.originalHeight != page.pageHeight
            ) return false
            val previousBounds = authoritativeBounds[index]
            if (previousBounds != null && previousBounds != (page.pageWidth to page.pageHeight)) {
                noteInvariantViolation(generation, "prepared_bounds_changed_$index")
                return false
            }
            val existing = drawableRegistry.entry(index)
            if (existing != null) return existing.identity.sameAs(identity)
            if (renderView.hasPageDrawable(index)) return false
            authoritativeBounds[index] = page.pageWidth to page.pageHeight
            val surfaceInstalled = renderView.setPageAuthoritativeOriginalTiles(
                index,
                page.pageWidth,
                page.pageHeight,
                page.tiles,
                proof
            )
            if (!surfaceInstalled || !renderView.hasPageDrawable(index)) return false
            return drawableRegistry.adoptPreparedStoreTiles(
                index,
                page.pageWidth,
                page.pageHeight,
                page.tiles
            )
        }
    }

    private fun installSessionTiles(
        generation: Int,
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        tiles: List<ReaderTile>,
        retainIfBoundsMissing: Boolean = true
    ): Boolean {
        if (!isSessionGenerationActive(generation) ||
            !canonicalOriginalManifestBound(generation, index)
        ) return false
        val canonical = ReaderPreparedStore.canonicalOriginalAssetIdentity(activeImages.getOrNull(index))
        val proof = ReaderPreparedStore.PreparedOriginalProof(
            canonicalAsset = canonical,
            variant = ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
            originalWidth = pageWidth,
            originalHeight = pageHeight,
            inSampleSize = 1,
            postDecodeResized = false
        )
        val page = ReaderPreparedStore.PreparedTilePage(pageWidth, pageHeight, tiles, proof)
        // Session tiles are allowed to use any contiguous full-resolution split geometry. The
        // PreparedStore's fixed 512px boundary is a reservation format, not a Surface rendering
        // requirement. Exact manifest identity plus full-quality geometry is the authoritative
        // contract for this queue.
        if (AdoptedDrawableIdentity.fullQualityTiles(pageWidth, pageHeight, tiles) == null) {
            return false
        }
        val enqueued = surfaceInstallQueue.enqueue(
            ReaderSurfaceInstallQueue.InstallCommand(
                episodeEpoch = generation.toLong(),
                pageIndex = index,
                canonicalAsset = canonical,
                tilePage = page
            )
        )
        if (enqueued) markCanonicalPageRenderReady(generation, index)
        return enqueued
    }

    private fun markCanonicalPageRenderReady(generation: Int, index: Int) {
        val progress = synchronized(runtimeLock) {
            if (!isSessionGenerationActive(generation) ||
                !canonicalOriginalManifestBound(generation, index)
            ) return@synchronized null
            if (renderReadyGeneration != generation) {
                renderReadyPages.clear()
                renderReadyGeneration = generation
            }
            renderReadyPages.add(index)
            renderReadyPages.size to manifestPageCount
        }
        if (progress != null &&
            (progress.first == 1 || progress.first == progress.second / 2 ||
                progress.first == progress.second)
        ) {
            Log.d(
                "ViewerPerf",
                "reader_all_images_render_ready_progress generation=$generation," +
                    "count=${progress.first},pageCount=${progress.second},page=$index"
            )
        }
        maybePublishAllImagesRenderReady(generation)
    }

    private fun maybePublishAllImagesRenderReady(generation: Int) {
        val pageCount = synchronized(runtimeLock) {
            if (!isSessionGenerationActive(generation) ||
                renderReadyGeneration != generation || manifestPageCount <= 0 ||
                renderReadyPages.size != manifestPageCount ||
                !(0 until manifestPageCount).all(renderReadyPages::contains)
            ) return@synchronized 0
            manifestPageCount
        }
        if (pageCount > 0) ViewerTelemetry.allImagesRenderReady(hostView, pageCount)
    }

    private fun currentRequiredInstallPages(): Set<Int> {
        val progress = currentProgress ?: return emptySet()
        if (manifestPageCount <= 0) return emptySet()
        val first = (progress.page - 1).coerceIn(0, manifestPageCount - 1)
        val last = renderView.forwardRequestEndPage(PRODUCTION_AHEAD_VIEWPORTS)
            .coerceAtLeast(progress.page)
            .coerceIn(first, manifestPageCount - 1)
        return (first..last).toSet()
    }

    private fun installSessionTileBatchNow(
        commands: List<ReaderSurfaceInstallQueue.InstallCommand>
    ): Set<Int> {
        if (Looper.myLooper() !== Looper.getMainLooper() || commands.isEmpty()) return emptySet()
        val generation = bindingGeneration
        val alreadyInstalled = LinkedHashSet<Int>()
        val accepted = ArrayList<ReaderSurfaceView.AuthoritativeTileInstall>(commands.size)
        synchronized(runtimeLock) {
            if (!isSessionGenerationActive(generation)) return emptySet()
            for (command in commands) {
                val index = command.pageIndex
                val page = command.tilePage
                if (command.episodeEpoch != generation.toLong() ||
                    !canonicalOriginalManifestBound(generation, index) ||
                    command.canonicalAsset != ReaderPreparedStore.canonicalOriginalAssetIdentity(
                        activeImages.getOrNull(index)
                    ) || !ReaderPreparedStore.isCanonicalOriginalProof(
                        page.originalProof,
                        command.canonicalAsset,
                        page.pageWidth,
                        page.pageHeight
                    )
                ) continue
                val identity = AdoptedDrawableIdentity.fullQualityTiles(
                    page.pageWidth,
                    page.pageHeight,
                    page.tiles
                ) ?: continue
                val bounds = authoritativeBounds[index]
                if (!authoritativeTileBoundsCompatible(
                        bounds?.first,
                        bounds?.second,
                        page.pageWidth,
                        page.pageHeight
                    )
                ) {
                    noteInvariantViolation(generation, "session_tile_bounds_mismatch_$index")
                    continue
                }
                authoritativeBounds[index] = page.pageWidth to page.pageHeight
                pendingSessionTiles.remove(index)
                val existing = drawableRegistry.entry(index)
                if (existing != null) {
                    if (!existing.identity.sameAs(identity)) continue
                    if (renderView.hasPageDrawable(index)) {
                        alreadyInstalled.add(index)
                        continue
                    }
                    if (existing.origin != DrawableOrigin.READER_SESSION ||
                        !drawableRegistry.remove(index, DrawableOrigin.READER_SESSION)
                    ) continue
                }
                if (renderView.hasPageDrawable(index)) continue
                accepted.add(
                    ReaderSurfaceView.AuthoritativeTileInstall(
                        index,
                        page.pageWidth,
                        page.pageHeight,
                        page.tiles,
                        page.originalProof!!
                    )
                )
            }
            val result = renderView.installAuthoritativeTileBatch(accepted)
            for (install in accepted) {
                if (install.index !in result.installedPages) continue
                if (drawableRegistry.adoptReaderSessionTiles(
                        install.index,
                        install.pageWidth,
                        install.pageHeight,
                        install.tiles
                    )
                ) alreadyInstalled.add(install.index)
            }
        }
        if (alreadyInstalled.isNotEmpty()) requestCurrentWindow(generation, busy = false)
        return alreadyInstalled
    }

    private fun applyPreparedSnapshotTiles(
        generation: Int,
        snapshot: ReaderPreparedStore.Snapshot
    ) {
        for ((index, page) in snapshot.tilePages) {
            installPreparedStoreTiles(generation, index, page)
        }
    }

    private fun snapshotMatchesBinding(
        snapshot: ReaderPreparedStore.Snapshot,
        manga: Manga,
        images: List<String>
    ): Boolean {
        return Manga.sameEpisodeIdentity(snapshot.manga, manga) &&
            snapshot.images == images &&
            imagesMatchExactEpisode(manga, manga.ntkEpisodePath, images)
    }

    private fun canonicalOriginalManifestBound(generation: Int, index: Int): Boolean {
        if (generation != bindingGeneration || index !in 0 until manifestPageCount) return false
        val manifestUrl = activeImages.getOrNull(index)?.trim().orEmpty()
        if (manifestUrl.isEmpty()) return false
        val path = activePath.ifEmpty { stagedPath }
        val authority = NtkSourceSpoolRegistry.currentAuthoritativeManifest(path) ?: return false
        return authority.seal.isStructurallyComplete &&
            authority.seal.pageCount == manifestPageCount &&
            authority.seal.normalizedCanonicalAssets.getOrNull(index) ==
                NtkStripDigests.canonicalAsset(manifestUrl)
    }

    private fun contiguousLaunchTiles(
        tilePages: Map<Int, ReaderPreparedStore.PreparedTilePage>,
        startPage: Int
    ): Map<Int, ReaderPreparedStore.PreparedTilePage> {
        val result = LinkedHashMap<Int, ReaderPreparedStore.PreparedTilePage>()
        var index = startPage
        while (true) {
            val page = tilePages[index] ?: break
            result[index] = page
            index++
        }
        return result
    }

    private fun strictActivationReady(): Boolean {
        val physicalHeight = renderView.height
        return strictRunwayReady(
            renderView.refreshVisibleCoverageSnapshot(),
            renderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS),
            physicalHeight
        )
    }

    private fun initialPreparationReady(
        stats: ReaderSurfaceView.PrepareStats?,
        requiredThroughY: Float
    ): Boolean {
        if (stats == null) return false
        return stats.targetCount > 0 &&
            stats.successCount == stats.targetCount &&
            stats.stillValid &&
            stats.visualGeneration > 0L &&
            stats.preparedThroughY.toFloat() >= requiredThroughY
    }

    private fun logInitialPrepare(stats: ReaderSurfaceView.PrepareStats) {
        ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "reader_attached_root_software_prepare_count",
            stats.successCount.toLong()
        )
        ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "reader_attached_root_software_prepare_bytes",
            stats.bytes
        )
        ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "reader_attached_root_software_prepare_us",
            stats.elapsedMicros
        )
        android.util.Log.d(
            "ViewerPerf",
            "reader_attached_root_software_prepare targets=${stats.targetCount}," +
                "success=${stats.successCount},bytes=${stats.bytes},first=${stats.firstPage}," +
                "last=${stats.lastPage},through=${stats.preparedThroughY}," +
                "generation=${stats.visualGeneration},valid=${if (stats.stillValid) 1 else 0}," +
                "us=${stats.elapsedMicros}"
        )
    }

    private fun isSessionGenerationActive(generation: Int): Boolean {
        return generation > 0 && generation == sessionGeneration.get() &&
            state != State.IDLE && state != State.EXITING && state != State.DESTROYED
    }

    private fun requestCurrentWindow(generation: Int, busy: Boolean) {
        if (!isSessionGenerationActive(generation)) return
        surfaceInstallQueue.onRequiredPagesChanged()
        val position = renderView.currentScrollPositionSnapshot()
        val anchor = (position?.page ?: currentProgress?.page ?: 0)
            .coerceIn(0, (manifestPageCount - 1).coerceAtLeast(0))
        requestRollingWindow(
            generation,
            firstPage = anchor,
            lastPage = anchor,
            anchorPage = anchor,
            busy = busy
        )
    }

    private fun requestRollingWindow(
        generation: Int,
        firstPage: Int,
        lastPage: Int,
        anchorPage: Int,
        busy: Boolean
    ) {
        if (!isSessionGenerationActive(generation) || manifestPageCount <= 0) return
        val producer = session ?: return
        if (busy) producer.noteUserInteraction()
        val request = rollingRequestBounds(
            firstPage,
            lastPage,
            renderView.forwardRequestEndPage(PRODUCTION_AHEAD_VIEWPORTS),
            manifestPageCount
        )
        if (request[0] < 0) return
        producer.requestWindowAsync(
            request[0],
            request[1],
            anchorPage.coerceIn(0, manifestPageCount - 1),
            busy
        )
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else activity.runOnUiThread(block)
    }

    private fun noteInvariantViolation(
        generation: Int,
        reason: String,
        manga: Manga? = activeManga
    ) {
        if (!isSessionGenerationActive(generation)) return
        blockingStatus = reason
        postMain {
            if (!isSessionGenerationActive(generation)) return@postMain
            val path = activePath.ifEmpty { stagedPath }
            val plan = fatalInvariantPlan(reason, state == State.ACTIVE)
            if (state == State.ACTIVE) publishProgress(force = true)
            cleanupRuntime(
                reason = plan.cleanupReason,
                restoreEpisode = plan.restoreEpisode,
                destroy = false,
                notifyExit = plan.notifyExit
            )
            // Keep the exact terminal cause observable after cleanup. A later legitimate bind
            // clears it; no error overlay or fallback viewer is opened in the meantime.
            blockingStatus = plan.reason
            callbacks.onFatalReaderError(this, path, plan.reason, manga)
        }
    }

    private fun failBindingAsync(generation: Int, result: StageResult) {
        if (!isSessionGenerationActive(generation) || state != State.BINDING) return
        failBindingNow(result)
    }

    private fun failBindingNow(result: StageResult) {
        val path = stagedPath
        cleanupRuntime(
            reason = result.name.lowercase(Locale.ROOT),
            restoreEpisode = true,
            destroy = false,
            notifyExit = false
        )
        blockingStatus = result.name.lowercase(Locale.ROOT)
        callbacks.onStageFailed(this, path, blockingStatus)
    }

    fun isActive(): Boolean = state == State.ACTIVE &&
        !pressActivationPending && !activationCommitQueued
    fun isBinding(): Boolean = state == State.PLANNED || state == State.BINDING
    fun isStaged(): Boolean = state == State.STAGED
    fun getStageTicket(): StageTicket? = if (state == State.STAGED) publishedStageTicket else null
    fun getStrictPlanObservationSnapshot(): StrictPlanObservationSnapshot {
        strictPlanObservationSnapshot?.let { return it }
        return authorityFreeStrictPlanObservationSnapshot()
    }

    private fun authorityFreeStrictPlanObservationSnapshot(): StrictPlanObservationSnapshot {
        val controllerGeneration = plannedControllerGeneration
        val committedNanos = shellFrameCommitNanos
        return StrictPlanObservationSnapshot(
            controllerState = if (controllerGeneration > 0) State.PLANNED.name else state.name,
            path = plannedPath,
            controllerGeneration = controllerGeneration,
            discoveryGeneration = 0L,
            planProofDigest = "",
            requestIdentityDigest = "",
            pageCount = 0,
            surfaceEpoch = 0L,
            planReservedNanos = 0L,
            shellFrameCommitted = committedNanos > 0L,
            shellFrameCommitNanos = committedNanos,
            surfaceDemandGeneration = 0L,
            detachedEngineCreated = false,
            detachedWarmReady = false,
            surfaceViewConstructed = false,
            surfaceViewInstalled = false,
            publishedSurfaceEpoch = 0L,
            manifestOwned = false,
            sourceClaimed = false,
            fullSceneBootstrapStartedThreads = 0,
            fullSceneSubmittedTasks = 0L,
            sourcePipelineCreated = false,
            nativeAuthorityInstalled = false,
            nativeReleaseAuthorityInstalled = false
        )
    }

    fun getTargetLifecycleSnapshot(): TargetLifecycleSnapshot {
        val lifecycle = targetReducer.snapshot()
        val target = stripRenderViewTarget
        val surface = target?.targetLifecycleDebugSnapshot()
        val native = target?.startupLifecycleDebugSnapshot()
        return TargetLifecycleSnapshot(
            initialSurfaceViewCount = initialSurfaceViewCount,
            currentSurfaceViewCount = surfaceSlot.childCount,
            planReservedNanos = lifecycle.planReservedNanos,
            shellFrameCommitNanos = lifecycle.shellFrameCommitNanos,
            demandGeneration = lifecycle.demandGeneration,
            detachedEngineGeneration = lifecycle.detachedEngineGeneration,
            detachedWarmReady = lifecycle.detachedWarmReady,
            surfaceViewConstructedNanos = surfaceViewConstructedNanos,
            surfaceViewInstalledNanos = surfaceViewInstalledNanos,
            holderCreatedNanos = surface?.holderCreatedNanos ?: 0L,
            surfaceLeaseAcquiredNanos = surface?.surfaceLeaseAcquiredNanos ?: 0L,
            attachReadyNanos = surface?.attachReadyNanos ?: 0L,
            surfacePublishedNanos = surface?.surfacePublishedNanos ?: 0L,
            publishedSurfaceEpoch = lifecycle.publishedSurfaceEpoch,
            manifestOwned = lifecycle.manifestOwned,
            sourceClaimed = lifecycle.sourceClaimed,
            holderCreatedCount = surface?.holderCreatedCount ?: 0,
            surfaceLeaseAcquireCount = surface?.surfaceLeaseAcquireCount ?: 0,
            attachReadyCount = surface?.attachReadyCount ?: 0,
            surfacePublishCount = surface?.surfacePublishCount ?: 0,
            nativeCreateBeginNanos = native?.nativeCreateBeginNanos ?: 0L,
            nativeCreateEndNanos = native?.nativeCreateEndNanos ?: 0L,
            swappyInitBeginNanos = native?.swappyInitBeginNanos ?: 0L,
            swappyInitEndNanos = native?.swappyInitEndNanos ?: 0L,
            eglInitBeginNanos = native?.eglInitBeginNanos ?: 0L,
            eglInitEndNanos = native?.eglInitEndNanos ?: 0L,
            renderPbufferReadyNanos = native?.renderPbufferReadyNanos ?: 0L,
            uploadPbufferReadyNanos = native?.uploadPbufferReadyNanos ?: 0L,
            programReadyNanos = native?.programReadyNanos ?: 0L,
            eglReadyNanos = native?.eglReadyNanos ?: 0L,
            detachedWarmReadyNanos = native?.detachedWarmReadyNanos ?: 0L,
            attachLeaseQueuedNanos = native?.attachLeaseQueuedNanos ?: 0L,
            attachLeaseClaimedNanos = native?.attachLeaseClaimedNanos ?: 0L,
            swappyWindowBeginNanos = native?.swappyWindowBeginNanos ?: 0L,
            swappyWindowEndNanos = native?.swappyWindowEndNanos ?: 0L,
            surfaceControlAttachBeginNanos = native?.surfaceControlAttachBeginNanos ?: 0L,
            surfaceControlAttachEndNanos = native?.surfaceControlAttachEndNanos ?: 0L,
            nativeAttachReadyNanos = native?.attachReadyNanos ?: 0L,
            nativeAttachPublishedNanos = native?.attachPublishedNanos ?: 0L,
            firstBackendPrepareNanos = native?.firstBackendPrepareNanos ?: 0L,
            firstTransactionApplyNanos = native?.firstTransactionApplyNanos ?: 0L,
            firstLatchNanos = native?.firstLatchNanos ?: 0L,
            surfaceControlAttachCount = native?.surfaceControlAttachCount ?: 0L,
            windowFrameIdCount = native?.windowFrameIdCount ?: 0L,
            windowSwapCount = native?.windowSwapCount ?: 0L
        )
    }

    /** EpisodeActivity calls this before normal ViewRoot dispatch after the opening row owns UP. */
    fun dispatchActivePhysicalInput(event: MotionEvent): Boolean {
        if (Looper.myLooper() !== Looper.getMainLooper() || state != State.ACTIVE ||
            pressActivationPending || activationCommitQueued
        ) return false
        val offsetX = event.rawX - event.x - hostView.screenLeftPx
        val offsetY = event.rawY - event.y - hostView.screenTopPx
        event.offsetLocation(offsetX, offsetY)
        val mainIngressNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            SystemClock.uptimeNanos()
        } else {
            SystemClock.uptimeMillis() * 1_000_000L
        }
        return try {
            val nativeAccepted = ingressHostTouch(event, mainIngressNanos)
            if (!nativeAccepted && blockingStatus.isEmpty()) {
                // Input ownership has already moved atomically to the reader. A native mailbox
                // rejection is a qualification failure, never permission to redispatch the same
                // physical sample through the covered episode hierarchy.
                blockingStatus = "native_input_rejected"
            }
            true
        } finally {
            finishHostTouchAccounting(event, mainIngressNanos)
            event.offsetLocation(-offsetX, -offsetY)
        }
    }

    private fun freezeEpisodeParentBeforeReader() {
        if (episodeListSuppressed) return
        val list = checkNotNull(episodeList) {
            "Strict NTK reader requires DragHandleRecyclerView invalidation ownership"
        }
        originalEpisodeItemAnimator = list.itemAnimator
        originalEpisodeContentAccessibility = episodeContent.importantForAccessibility
        originalHostAccessibility = hostView.importantForAccessibility
        originalSurfaceSlotAccessibility = surfaceSlot.importantForAccessibility
        originalRenderAccessibility = renderView.importantForAccessibility
        originalStripAccessibility = stripRenderView.importantForAccessibility
        list.itemAnimator?.endAnimations()
        list.itemAnimator = null
        list.suppressLayout(true)
        list.setReaderFrameInvalidationsSuppressed(true)
        episodeListSuppressed = true
        episodeContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        hostView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        surfaceSlot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        renderView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        stripRenderView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun unfreezeEpisodeParentAfterReader() {
        if (!episodeListSuppressed) return
        val list = checkNotNull(episodeList)
        list.setReaderFrameInvalidationsSuppressed(false)
        list.suppressLayout(false)
        list.itemAnimator = originalEpisodeItemAnimator
        originalEpisodeItemAnimator = null
        episodeContent.importantForAccessibility = originalEpisodeContentAccessibility
            ?: View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        hostView.importantForAccessibility = originalHostAccessibility
            ?: View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        surfaceSlot.importantForAccessibility = originalSurfaceSlotAccessibility
            ?: View.IMPORTANT_FOR_ACCESSIBILITY_NO
        renderView.importantForAccessibility = originalRenderAccessibility
            ?: View.IMPORTANT_FOR_ACCESSIBILITY_NO
        stripRenderViewTarget?.importantForAccessibility = originalStripAccessibility
            ?: View.IMPORTANT_FOR_ACCESSIBILITY_NO
        originalEpisodeContentAccessibility = null
        originalHostAccessibility = null
        originalSurfaceSlotAccessibility = null
        originalRenderAccessibility = null
        originalStripAccessibility = null
        episodeListSuppressed = false
    }
    fun getActivePath(): String = activePath
    fun getStagedPath(): String = if (isBinding() || isStaged()) stagedPath else ""
    fun getStagedKey(): String = if (isBinding() || isStaged()) stagedKey else ""
    fun getActivationEpoch(): Long = activationEpoch
    fun getActivationUptimeNanos(): Long = activationUptimeNanos
    fun getActivationElapsedRealtimeNanos(): Long = activationElapsedRealtimeNanos
    fun getActivationUptimeMs(): Long = activationUptimeMs
    fun wasWindowFocusedAtActivation(): Boolean = windowFocusedAtActivation
    fun wasHostAttachedAtActivation(): Boolean = hostAttachedAtActivation
    fun getActivationDrawProof(): ActivationDrawProof? = activationDrawProof
    fun getActivationStateProof(): ActivationStateProof? = activationStateProof
    fun getVisibleCoverageSnapshot(): ReaderSurfaceView.VisibleCoverageSnapshot? =
        if (stripPipeline != null) stripRenderView.visibleCoverageSnapshot() ?: visibleCoverageSnapshot
        else renderView.refreshVisibleCoverageSnapshot() ?: visibleCoverageSnapshot
    fun getPageReadinessSnapshot(): ReaderSurfaceView.PageReadinessSnapshot =
        if (stripPipeline != null) stripRenderView.pageReadinessSnapshot()
        else renderView.pageReadinessSnapshot()
    fun getNativeAuthorityEvidenceSnapshot(): NtkNativeAuthorityEvidenceSnapshot? =
        if (stripPipeline != null) stripRenderView.nativeAuthorityEvidenceSnapshot() else null
    fun getSchedulerDebugSnapshot(): NtkSchedulerDebugSnapshot? =
        if (stripPipeline != null) stripRenderView.schedulerDebugSnapshotForTesting() else null
    fun getFrameStatsSnapshot(): ReaderSurfaceView.FrameStatsSnapshot? =
        if (stripPipeline != null) stripRenderView.frameStatsSnapshot()
        else renderView.frameStatsSnapshot()
    fun getSchema11QualificationSnapshot(): NtkSchema11QualificationSnapshot? =
        if (stripPipeline != null) {
            stripRenderView.schema11QualificationSnapshot(
                acceptedTerminalInputSequences.copyOf(),
                acceptedTerminalInputCount,
                acceptedTerminalInputOverflow
            )
        } else null
    fun getProgressPosition(): ReaderSurfaceView.ProgressPosition? =
        if (stripPipeline != null) stripRenderView.progressPosition() ?: currentProgress
        else renderView.currentProgressPosition() ?: currentProgress
    fun getScrollPositionSnapshot(): ReaderSurfaceView.ScrollPositionSnapshot? =
        if (stripPipeline != null) stripRenderView.scrollPositionSnapshot()
        else renderView.currentScrollPositionSnapshot()
    fun getForwardRunwaySnapshot(): ReaderSurfaceView.ForwardRunwaySnapshot? =
        if (stripPipeline != null) stripRenderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
        else renderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
    fun getTraversalSnapshot(): ReaderSurfaceView.TraversalSnapshot? =
        if (stripPipeline != null) stripRenderView.traversalSnapshot()
        else renderView.traversalSnapshot()
    fun getEpisodeProofSnapshot(): NtkEpisodeProofSnapshot? {
        val pipeline = stripPipeline ?: return null
        val pipelineProof = pipeline.terminalProofSnapshotBlocking(1_000L)
            ?: latestStripProof
            ?: return null
        latestStripProof = pipelineProof
        val surfaceProof = stripRenderView.episodeProofSnapshot()
        return pipelineProof.composeWithSurfacePresentation(surfaceProof)
    }
    fun takeFrameStatsSnapshots(): List<ReaderSurfaceView.FrameStatsSnapshot> =
        if (stripPipeline != null) listOf(stripRenderView.frameStatsSnapshot())
        else renderView.takeFrameStatsSnapshots()
    fun resetFrameStatsSnapshot() {
        if (stripPipeline != null) stripRenderView.resetFrameStats()
        else renderView.resetFrameStatsSnapshot()
    }
    fun isReadinessScrollLimitEnabled(): Boolean =
        stripPipeline == null && renderView.isReadinessScrollLimitEnabled()
    fun getCurrentNtkImageCount(): Int = if (isActive()) activeImages.size else 0
    fun getPageCount(): Int = if (isActive()) getPageReadinessSnapshot().pageCount else 0
    fun getCurrentPage(): Int = getProgressPosition()?.page ?: 0
    fun getFirstDrawableElapsedMs(): Long {
        val proof = activationDrawProof ?: return -1L
        return ((proof.completedUptimeNanos - proof.activatedUptimeNanos) / 1_000_000L).coerceAtLeast(0L)
    }
    fun getInitialContinuousDrawableElapsedMs(requiredPages: Int): Long {
        if (requiredPages <= 0 || activationDrawProof == null) return -1L
        val endExclusive = launchStartPage + requiredPages
        if (launchStartPage < 0 || endExclusive > manifestPageCount) return -1L
        if (stripPipeline != null) {
            val ticket = activationStateProof?.stageTicket ?: publishedStageTicket
            if (ticket == null || !ticket.hasNativeStageProof() ||
                !stripRenderView.isPageRangeFullyResident(launchStartPage, requiredPages)
            ) return -1L
            return getFirstDrawableElapsedMs()
        }
        for (index in launchStartPage until endExclusive) {
            if (!drawableRegistry.contains(index) || !renderView.hasPageDrawable(index)) return -1L
        }
        return synchronized(runtimeLock) {
            initialContinuousElapsedByRequired.getOrPut(requiredPages) {
                ((currentUptimeNanos() - activationUptimeNanos) / 1_000_000L)
                    .coerceAtLeast(getFirstDrawableElapsedMs())
            }
        }
    }
    fun getViewportPlusOneAndHalfRunwayElapsedMs(): Long =
        if (firstDrawStrictRunwayReady) getFirstDrawableElapsedMs() else -1L
    fun hasLoadedEpisode(manga: Manga?): Boolean =
        isActive() && manga != null && Manga.sameEpisodeIdentity(activeManga, manga)
    fun getStatusText(): String = ""
    fun getBlockingStatus(): String = blockingStatus
    fun getHostView(): NtkInlineReaderHostView = hostView
    fun getRenderView(): ReaderSurfaceView = renderView
    fun getActiveManga(): Manga? = if (isActive()) activeManga else null
    fun getActiveTitle(): Title? = if (isActive()) activeTitle else null

    fun testScrollByPixels(deltaPx: Float) {
        if (isActive()) renderView.testScrollByPixels(deltaPx)
    }

    fun onHostPause() {
        if (!isActive()) return
        hostPaused = true
        publishProgress(force = true)
        // Preserve the current physical window for resume while releasing decoded pixels that
        // are no longer visible. A subsequent destroy still owns the terminal cancel path.
        runCatching { session?.trimMemory(aggressive = true) }
            .onFailure { Log.d("ViewerPerf", "ntk_inline_background_trim_error", it) }
        if (stripPipeline == null) renderView.setFrameSchedulingSuppressed(true)
    }

    fun onHostResume() {
        if (!isActive()) return
        hostPaused = false
        if (stripPipeline == null) {
            renderView.setWindowListener(this)
            renderView.setFrameSchedulingSuppressed(false)
            requestCurrentWindow(bindingGeneration, busy = false)
            renderView.requestRender()
        }
    }

    fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (isActive() && hasFocus) renderView.requestRender()
    }

    fun handleBackPressed(): Boolean {
        if (!isActive()) return false
        exit("back")
        return true
    }

    @JvmOverloads
    fun exit(reason: String = "host") {
        if (state == State.PLANNED || state == State.BINDING || state == State.STAGED) {
            cleanupRuntime(reason, restoreEpisode = true, destroy = false, notifyExit = false)
            return
        }
        if (state != State.ACTIVE) return
        publishProgress(force = true)
        cleanupRuntime(reason, restoreEpisode = true, destroy = false, notifyExit = true)
    }

    fun onHostDestroy() {
        if (state == State.DESTROYED) return
        if (isActive()) publishProgress(force = true)
        cleanupRuntime("destroy", restoreEpisode = false, destroy = true, notifyExit = isActive())
    }

    private fun cleanupRuntime(
        reason: String,
        restoreEpisode: Boolean,
        destroy: Boolean,
        notifyExit: Boolean
    ) {
        if (state == State.DESTROYED) return
        val path = activePath.ifEmpty { stagedPath }
        val wasActive = state == State.ACTIVE
        val wasCommittedActive = wasActive && !pressActivationPending && !activationCommitQueued
        state = State.EXITING
        sessionGeneration.incrementAndGet()
        surfaceInstallQueue.clear()
        val continuousPipeline = stripPipeline
        stripPipeline = null
        val target = stripRenderViewTarget
        target?.frameListener = null
        if (destroy) target?.setSurfaceLifecycleListener(null)
        stripTransport = null
        latestStripProof = null
        targetInstallLayoutListener?.let(surfaceSlot::removeOnLayoutChangeListener)
        targetInstallLayoutListener = null
        fullSceneExecutionBootstrap?.close()
        fullSceneExecutionBootstrap = null
        detachedWarmTask?.cancel()
        detachedWarmTask = null

        var lease: ReaderPreparedStore.LaunchRunwayLease? = null
        var relay: ReaderPreparedStore.Listener? = null
        var producer: ReaderSession? = null
        var startTask: AppDispatchers.TaskHandle? = null
        synchronized(runtimeLock) {
            lease = launchLease
            relay = preparedStoreListener
            val startHasReturned = sessionStartTicket?.requestCancel() ?: true
            producer = if (!sessionStartBegan || startHasReturned) session else null
            startTask = sessionStartTask
            launchLease = null
            preparedStoreListener = null
            sessionListener = null
            session = null
            sessionStartTask = null
            sessionStartTicket = null
            pendingSessionTiles.clear()
        }
        if (lease != null && relay != null) lease.unsubscribe(relay)
        renderView.setWindowListener(null)
        hostView.visibility = if (destroy) View.INVISIBLE else View.VISIBLE
        hostView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        renderView.setFrameSchedulingSuppressed(true)
        target?.setCompositorAlpha(0f)
        if (continuousPipeline == null) {
            if (destroy) renderView.stopRenderingAndClearPages() else renderView.clearAllPages()
        }
        continuousPipeline?.retire(reason)
        producer?.cancel()
        startTask?.cancel()
        lease?.close()
        if (destroy) {
            targetReducer.destroy()
            if (target != null) {
                surfaceSlot.removeView(target)
                stripRenderViewTarget = null
            }
            detachedEngine?.closeAfterSurfaceTerminal()
            detachedEngine = null
            preparationEngine = null
            detachedWarmProof = null
        } else {
            targetReducer.normalExit()
            if (target == null) {
                detachedEngine?.closeAfterSurfaceTerminal()
                detachedEngine = null
                preparationEngine = null
                detachedWarmProof = null
            }
        }

        drawableRegistry.clear()
        authoritativeBounds.clear()
        renderReadyPages.clear()
        renderReadyGeneration = 0
        renderView.setInlineRealPixelsOnly(false)
        unfreezeEpisodeParentAfterReader()
        if (destroy) hostView.controller = null
        activeManga = null
        activeTitle = null
        activeImages = emptyList()
        plannedManga = null
        plannedTitle = null
        plannedPath = ""
        plannedKey = ""
        plannedStartPage = 0
        plannedStartOffset = 0
        plannedControllerGeneration = 0
        plannedDiscoveryGeneration = 0L
        plannedPlanProofDigest = ""
        plannedRequestIdentityDigest = ""
        plannedPageCount = 0
        plannedSurfaceEpoch = 0L
        publishedSurfaceIdentity = null
        synchronized(strictPreparationLock) {
            strictPreparationProtocol =
                NtkStrictPreparationProtocol(nextStrictPreparationGeneration)
            strictPlanIdentity = null
            strictDemandIdentity = null
            detachedPreparationPort = null
        }
        planReservedNanos = 0L
        surfaceViewConstructedNanos = 0L
        surfaceViewInstalledNanos = 0L
        strictPlanObservationSnapshot = null
        currentSurfaceDemand = null
        pendingAuthoritativeManifest = null
        manifestPageCount = 0
        launchStartPage = 0
        bindingGeneration = 0
        activateWhenBindingCompletes = false
        sessionStartBegan = false
        initialPrepareStats = null
        requiredPreparedThroughY = 0f
        initialContinuousElapsedByRequired.clear()
        currentProgress = null
        lastPublishedProgress = null
        visibleCoverageSnapshot = null
        stagedActivationCoverage = null
        stagedActivationReadiness = null
        stagedActivationRunway = null
        activationDrawProof = null
        activationStateProof = null
        firstDrawStrictRunwayReady = false
        activePath = ""
        stagedPath = ""
        stagedKey = ""
        publishedStageTicket = null
        hostPaused = false
        blockingStatus = ""
        pressActivationPending = false
        activationCommitQueued = false
        pressPreviewEpochPublished = false
        lastTelemetryAtTop = null
        lastTelemetryAtBottom = null
        state = if (destroy) State.DESTROYED else State.IDLE
        if (destroy) {
            provisionalPlanSubscription.close()
            authoritativeManifestSubscription.close()
        }
        restoreHostUiThreadPriority()
        ViewerTelemetry.viewerClosed(reason)
        if (notifyExit && wasCommittedActive) callbacks.onExited(this, path, reason)
    }

    private fun boostHostUiThreadForInput() {
        if (Looper.myLooper() !== Looper.getMainLooper() || hostUiOriginalPriority != null) return
        val current = try { Process.getThreadPriority(Process.myTid()) } catch (_: Throwable) { return }
        if (current <= Process.THREAD_PRIORITY_URGENT_DISPLAY) return
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            hostUiOriginalPriority = current
            Log.d("ViewerPerf", "ntk_inline_ui_qos before=$current,after=${Process.getThreadPriority(Process.myTid())}")
        } catch (_: Throwable) {
            hostUiOriginalPriority = null
        }
    }

    /** Arm source-scoped unbuffered delivery while STAGED, before the first physical DOWN. */
    private fun enableUnbufferedPointerDispatch() {
        if (!hostView.isAttachedToWindow) return
        hostView.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
    }

    private fun restoreHostUiThreadPriority() {
        val original = hostUiOriginalPriority ?: return
        hostUiOriginalPriority = null
        if (Looper.myLooper() !== Looper.getMainLooper()) return
        try { Process.setThreadPriority(original) } catch (_: Throwable) { }
    }

    fun resetTouchDeliveryStats() {
        touchRecordCount = 0
        firstHostMainIngressNanos = 0L
        firstInputAdmissionRejectionLogged = false
        touchStatsArmed = true
        hostGestureOwned = false
        currentHostNativeReceipt = 0L
        acceptedTerminalInputSequences.fill(0L)
        acceptedTerminalInputCount = 0
        acceptedTerminalInputOverflow = false
        stripRenderView.resetInputTelemetry()
    }

    fun getTerminalTouchEventTimeNanos(): Long {
        val count = touchRecordCount.coerceAtMost(TOUCH_RECORD_CAPACITY)
        return if (count <= 0) 0L else touchRecordEventNanos[(count - 1) % TOUCH_RECORD_CAPACITY]
    }

    fun getLatestNativeAppliedInputEventTimeNanos(): Long =
        if (stripPipeline != null) {
            // Input application is proven immediately after the causal Swappy submission.
            stripRenderView.latestSuccessfulSwapInputEventNanos()
        } else 0L

    fun getLatestNativeDeliveredPhysicalEvidenceInputEventTimeNanos(): Long =
        if (stripPipeline != null) {
            // The feedback lane publishes this only after the ordered target-retire + latch
            // callback has completed. Reading it never requests work or changes renderer state.
            stripRenderView.latestDeliveredLatchedInputEventNanos()
        } else 0L

    fun getFirstNativeMainIngressNanos(): Long {
        val nativeIngress = stripRenderView.firstMainIngressNanos()
        return if (nativeIngress > 0L) nativeIngress else firstHostMainIngressNanos
    }

    fun peekTouchDeliverySnapshot(): TouchDeliverySnapshot = touchDeliverySnapshot()

    fun takeTouchDeliverySnapshot(): TouchDeliverySnapshot {
        val snapshot = touchDeliverySnapshot()
        touchStatsArmed = false
        return snapshot
    }

    /** First reader work in HostView.dispatchTouchEvent; no logging, allocation or blocking. */
    internal fun ingressHostTouch(event: MotionEvent, mainIngressNanos: Long): Boolean {
        if (firstHostMainIngressNanos == 0L) firstHostMainIngressNanos = mainIngressNanos
        val pipeline = stripPipeline
        if (state != State.ACTIVE || pressActivationPending || activationCommitQueued ||
            pipeline == null || pipeline.authority <= 0L
        ) {
            if (!firstInputAdmissionRejectionLogged) {
                firstInputAdmissionRejectionLogged = true
                android.util.Log.e(
                    "ViewerPerf",
                    "reader_strip_input_rejected state=$state press=$pressActivationPending " +
                        "commit=$activationCommitQueued pipeline=${pipeline != null} " +
                        "authority=${pipeline?.authority ?: 0L}"
                )
            }
            return false
        }
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        ) return false
        if (action == MotionEvent.ACTION_DOWN) {
            hostGestureOwned = hostView.isInsideStripBounds(event.x, event.y)
        }
        if (!hostGestureOwned) return false
        val eventTimeNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            event.eventTimeNanos
        } else {
            event.eventTime * 1_000_000L
        }
        val pointerId = if (event.pointerCount > 0) {
            event.getPointerId(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        } else 0
        stripRenderView.beginHostOwnedDispatch()
        val receipt = stripRenderView.ingressHostTouch(
            pipeline.authority,
            action,
            eventTimeNanos,
            event.x - hostView.stripLeftPx,
            event.y - hostView.stripTopPx,
            pointerId
        )
        currentHostNativeReceipt = receipt
        return receipt < 0L
    }

    internal fun finishHostTouchAccounting(event: MotionEvent, mainIngressNanos: Long) {
        stripRenderView.endHostOwnedDispatch()
        if (touchStatsArmed && touchRecordCount < TOUCH_RECORD_CAPACITY) {
            val index = touchRecordCount
            val eventTimeNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventTimeNanos
            } else event.eventTime * 1_000_000L
            var historicalMaxAgeNanos = 0L
            for (historyIndex in 0 until event.historySize) {
                val historicalNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.getHistoricalEventTimeNanos(historyIndex)
                } else event.getHistoricalEventTime(historyIndex) * 1_000_000L
                historicalMaxAgeNanos = maxOf(
                    historicalMaxAgeNanos,
                    mainIngressNanos - historicalNanos
                )
            }
            touchRecordAction[index] = event.actionMasked
            touchRecordEventNanos[index] = eventTimeNanos
            touchRecordMainIngressNanos[index] = mainIngressNanos
            touchRecordHistorySize[index] = event.historySize
            touchRecordHistoryMaxAgeNanos[index] = historicalMaxAgeNanos
            touchRecordNativeReceipt[index] = currentHostNativeReceipt
            touchRecordCount = index + 1 // volatile publication is last
        }
        if (event.actionMasked == MotionEvent.ACTION_UP &&
            currentHostNativeReceipt < 0L
        ) {
            val sequence = currentHostNativeReceipt and 0xffff_ffffL
            val index = acceptedTerminalInputCount
            if (index < acceptedTerminalInputSequences.size) {
                acceptedTerminalInputSequences[index] = sequence
                acceptedTerminalInputCount = index + 1
            } else {
                acceptedTerminalInputOverflow = true
                blockingStatus = "accepted-terminal-input-overflow"
            }
        }
        currentHostNativeReceipt = 0L
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            hostGestureOwned = false
        }
    }

    override fun onWindowChanged(
        firstPage: Int,
        lastPage: Int,
        anchorPage: Int,
        progressPage: Int,
        progressOffset: Int,
        busy: Boolean
    ) {
        if (state != State.BINDING && state != State.STAGED && state != State.ACTIVE) return
        currentProgress = ReaderSurfaceView.ProgressPosition(
            progressPage.coerceAtLeast(0),
            progressOffset.coerceAtLeast(0)
        )
        if (state == State.ACTIVE) publishViewerEdge(getScrollPositionSnapshot())
        val continuous = stripPipeline
        if (continuous != null) {
            // The native input integrator is the sole velocity/predicted-stop authority.
            // NtkStripSurfaceView.frameListener forwards those samples directly to the actor.
            if (isActive() && !busy && !hostPaused) publishProgress(force = false)
            return
        }
        surfaceInstallQueue.onRequiredPagesChanged()
        requestRollingWindow(
            bindingGeneration,
            firstPage,
            lastPage,
            anchorPage,
            busy
        )
        if (isActive() && !busy && !hostPaused) publishProgress(force = false)
    }

    private fun publishViewerEdge(snapshot: ReaderSurfaceView.ScrollPositionSnapshot?) {
        snapshot ?: return
        val atTop = snapshot.scrollOffset <= 0
        val atBottom = snapshot.scrollOffset >= snapshot.maxScroll
        if (lastTelemetryAtTop == atTop && lastTelemetryAtBottom == atBottom) return
        lastTelemetryAtTop = atTop
        lastTelemetryAtBottom = atBottom
        ViewerTelemetry.viewerEdge(hostView, atTop, atBottom)
    }

    override fun onNearBoundary(direction: Int, anchorPage: Int) {
        // Preparation policy remains owned by EpisodeActivity; this callback is deliberately inert.
    }

    override fun onBoundaryReached(direction: Int, anchorPage: Int) {
        if (isActive()) callbacks.onBoundaryReached(this, activePath, direction, anchorPage)
    }

    override fun onTap() {
        if (isActive()) callbacks.onTap(this, activePath)
    }

    override fun onVisibleCoverageChanged(snapshot: ReaderSurfaceView.VisibleCoverageSnapshot) {
        if (isActive()) visibleCoverageSnapshot = snapshot
    }

    override fun onCompletedDraw(proof: ReaderSurfaceView.CompletedDrawProof) {
        if (!isActive() || activationDrawProof != null) return
        val armedAt = activationUptimeNanos
        if (armedAt <= 0L || proof.completedUptimeNanos < armedAt) return
        val activationRunway = renderView.forwardRunwaySnapshot(ACTIVATION_AHEAD_VIEWPORTS)
        val completed = ActivationDrawProof(
            activationEpoch,
            armedAt,
            proof.sequence,
            proof.completedUptimeNanos,
            proof.hardwareAccelerated,
            windowFocusedAtActivation,
            hostAttachedAtActivation,
            proof.coverage,
            renderView.pageReadinessSnapshot(),
            activationRunway
        )
        activationDrawProof = completed
        visibleCoverageSnapshot = proof.coverage
        firstDrawStrictRunwayReady = proof.hardwareAccelerated && strictRunwayReady(
            proof.coverage,
            activationRunway,
            renderView.height
        )
        if (!firstDrawStrictRunwayReady) {
            noteInvariantViolation(bindingGeneration, "first_draw_runway_invariant")
        }
        callbacks.onFirstCompletedDraw(this, completed)
    }

    override fun shouldReportVisibleStats(): Boolean = isActive() && activationDrawProof == null

    private fun publishProgress(force: Boolean) {
        val path = activePath
        if (!isActive() || path.isEmpty()) return
        val progress = renderView.currentProgressPosition() ?: currentProgress ?: return
        currentProgress = progress
        if (!force && progress == lastPublishedProgress) return
        lastPublishedProgress = progress
        callbacks.onProgressChanged(this, path, progress)
    }

    private fun reject(result: EnterResult): EnterResult {
        blockingStatus = result.name.lowercase(Locale.ROOT)
        return result
    }

    private fun currentUptimeNanos(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            SystemClock.uptimeNanos()
        } else {
            SystemClock.uptimeMillis() * 1_000_000L
        }

    private fun rejectStage(result: StageResult): StageResult {
        // Empty Store entries and an unfinished ViewRoot are expected while the authoritative
        // manifest is DISCOVERING. They are scheduling states, not fail-closed terminal causes.
        if (!isTransientStageRejection(result)) {
            blockingStatus = result.name.lowercase(Locale.ROOT)
        }
        return result
    }

    private fun stagedMatches(manga: Manga, preparedKey: String?): Boolean {
        val path = NtkStripDigests.normalizeEpisodePath(manga.ntkEpisodePath.orEmpty())
        val expectedKey = resolvePreparedKey(path, preparedKey)
        if (state != State.STAGED || stagedKey.isEmpty() || stagedKey != expectedKey) {
            return false
        }
        return path == stagedPath && Manga.sameEpisodeIdentity(activeManga, manga)
    }

    private fun bindingMatches(manga: Manga, preparedKey: String?): Boolean {
        val path = NtkStripDigests.normalizeEpisodePath(manga.ntkEpisodePath.orEmpty())
        val expectedKey = resolvePreparedKey(path, preparedKey)
        if ((state != State.PLANNED && state != State.BINDING) ||
            stagedKey.isEmpty() || stagedKey != expectedKey
        ) {
            return false
        }
        return path == stagedPath && Manga.sameEpisodeIdentity(activeManga, manga)
    }

    private fun touchDeliverySnapshot(): TouchDeliverySnapshot {
        val count = touchRecordCount.coerceIn(0, TOUCH_RECORD_CAPACITY)
        var moveSamples = 0
        var moveDispatches = 0
        var historicalSamples = 0
        var downs = 0
        var ups = 0
        var cancels = 0
        var invalid = 0
        var lastAcceptedPhysicalEventSequence = 0L
        var acceptedPhysicalGestures = 0
        var maxLag = 0L
        var downMax = 0L
        var moveMax = 0L
        var upMax = 0L
        var cancelMax = 0L
        var currentMax = 0L
        var historicalMax = 0L
        for (index in 0 until count) {
            val action = touchRecordAction[index]
            val deltaNanos = touchRecordMainIngressNanos[index] - touchRecordEventNanos[index]
            val lagMs = if (deltaNanos < 0L) {
                invalid++
                0L
            } else (deltaNanos + 999_999L) / 1_000_000L
            val history = touchRecordHistorySize[index]
            val nativeReceipt = touchRecordNativeReceipt[index]
            if (nativeReceipt < 0L) {
                lastAcceptedPhysicalEventSequence = maxOf(
                    lastAcceptedPhysicalEventSequence,
                    nativeReceipt and 0xffff_ffffL
                )
                if (action == MotionEvent.ACTION_UP) ++acceptedPhysicalGestures
            }
            historicalSamples += history
            historicalMax = maxOf(
                historicalMax,
                (touchRecordHistoryMaxAgeNanos[index].coerceAtLeast(0L) + 999_999L) / 1_000_000L
            )
            currentMax = maxOf(currentMax, lagMs)
            maxLag = maxOf(maxLag, lagMs, historicalMax)
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downs++
                    downMax = maxOf(downMax, lagMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    moveDispatches++
                    moveSamples += history + 1
                    moveMax = maxOf(moveMax, lagMs)
                }
                MotionEvent.ACTION_UP -> {
                    ups++
                    upMax = maxOf(upMax, lagMs)
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancels++
                    cancelMax = maxOf(cancelMax, lagMs)
                }
            }
        }
        return TouchDeliverySnapshot(
            count + historicalSamples,
            moveSamples,
            count,
            moveDispatches,
            historicalSamples,
            downs,
            ups,
            cancels,
            invalid,
            lastAcceptedPhysicalEventSequence,
            acceptedPhysicalGestures,
            maxLag,
            downMax,
            moveMax,
            upMax,
            cancelMax,
            currentMax,
            downMax,
            moveMax,
            upMax,
            historicalMax
        )
    }
}

/** Captures real input arrival at the inline reader layer without Activity-specific dispatch hooks. */
class NtkInlineReaderHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    internal var controller: NtkInlineReaderController? = null
    internal var stripLeftPx: Float = 0f
        private set
    internal var stripTopPx: Float = 0f
        private set
    private var stripRightPx: Float = 0f
    private var stripBottomPx: Float = 0f
    private val screenLocation = IntArray(2)
    @Volatile internal var screenLeftPx: Float = 0f
        private set
    @Volatile internal var screenTopPx: Float = 0f
        private set

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(screenLocation)
        screenLeftPx = screenLocation[0].toFloat()
        screenTopPx = screenLocation[1].toFloat()
        val strip = findViewById<View>(R.id.ntk_strip_surface)
        if (strip != null) {
            stripLeftPx = strip.left.toFloat()
            stripTopPx = strip.top.toFloat()
            stripRightPx = strip.right.toFloat()
            stripBottomPx = strip.bottom.toFloat()
        }
    }

    internal fun isInsideStripBounds(x: Float, y: Float): Boolean =
        x >= stripLeftPx && x < stripRightPx && y >= stripTopPx && y < stripBottomPx

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val activeController = controller
        val mainIngressNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            SystemClock.uptimeNanos()
        } else {
            SystemClock.uptimeMillis() * 1_000_000L
        }
        // The native mailbox handoff is the first reader operation in this dispatch turn.
        val nativeOwned = activeController?.ingressHostTouch(event, mainIngressNanos) == true
        val handled = try {
            super.dispatchTouchEvent(event)
        } finally {
            activeController?.finishHostTouchAccounting(event, mainIngressNanos)
        }
        return handled || nativeOwned
    }
}
