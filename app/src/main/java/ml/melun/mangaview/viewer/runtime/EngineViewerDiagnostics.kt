package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import java.util.Collections

internal data class EngineFrameObservation(val ordinal: Long, val presentation: EngineSurfacePresentation)
internal data class EngineFrameObservationBatch(val observations: List<EngineFrameObservation>,
    val latestOrdinal: Long, val lostCount: Long)
internal data class EngineFrameCloseProof(val rendererId: Long, val submittedFrameCount: Long,
    val deliveredObservationCount: Long, val closedAtNanos: Long)

/** Observations retain the engine's coordinates and distinguish submission from physical display. */
internal class EngineViewerDiagnostics(private val frameCapacity: Int = 512) {
    init { require(frameCapacity > 0) }
    private val frames = ArrayDeque<EngineFrameObservation>()
    private var frameOrdinal = 0L
    private var frameClose: EngineFrameCloseProof? = null
    @Volatile var state: EngineRuntimeSnapshot? = null
        private set
    @Volatile var frame: EngineSurfacePresentation? = null
        private set
    private var opened = 0L
    private var manifestReady: Long? = null
    private var firstSubmitted: Long? = null
    private var firstPresented: Long? = null
    private var presentedPage: String? = null

    @Synchronized fun opened(atNanos: Long) { check(opened == 0L && atNanos > 0L); opened = atNanos }

    @Synchronized fun snapshot(value: EngineRuntimeSnapshot, atNanos: Long) {
        state = value
        if (opened > 0 && manifestReady == null && value.plans.isNotEmpty()) manifestReady = atNanos
    }

    @Synchronized fun presented(value: EngineSurfacePresentation) {
        check(frameClose == null) { "Frame observation arrived after renderer close" }
        require(value.rendererId > 0)
        frameOrdinal = Math.incrementExact(frameOrdinal)
        if (frames.size == frameCapacity) frames.removeFirst()
        // Scene/texture objects contain immutable coordinates and keys, not native resource leases.
        val snapshot = value.copy(scene = value.scene.copy(placements = Collections.unmodifiableList(value.scene.placements.toList())))
        frames.addLast(EngineFrameObservation(frameOrdinal, snapshot))
        frame = value
        if (opened == 0L || !value.swapSucceeded || value.scene.placements.isEmpty()) return
        if (firstSubmitted == null) firstSubmitted = value.submittedAtNanos
        if (firstPresented == null && value.timestampKind == PresentationTimestampKind.DISPLAY_PRESENT && value.timestampNanos > 0L) {
            firstPresented = value.timestampNanos
            presentedPage = value.scene.placements.first().texture.tile.pageId.remoteKey
        }
    }

    @Synchronized fun framesSince(afterOrdinal: Long): EngineFrameObservationBatch {
        require(afterOrdinal in 0..frameOrdinal)
        val first = frames.firstOrNull()?.ordinal ?: 1L
        return EngineFrameObservationBatch(frames.filter { it.ordinal > afterOrdinal }, frameOrdinal,
            (first - 1 - afterOrdinal).coerceAtLeast(0))
    }

    @Synchronized fun rendererClosed(rendererId: Long, submittedCount: Long, atNanos: Long) {
        check(frameClose == null)
        require(rendererId > 0 && submittedCount >= 0 && atNanos > 0)
        frameClose = EngineFrameCloseProof(rendererId, submittedCount, frameOrdinal, atNanos)
    }

    @Synchronized fun frameCloseProof(): EngineFrameCloseProof? = frameClose

    @Synchronized fun startup(): ViewerStartupTiming? = if (opened == 0L) null else ViewerStartupTiming(
        presentedPageKey = presentedPage, openStartedAtNanos = opened, manifestReadyAtNanos = manifestReady,
        initialResponseStartedAtNanos = null, initialVerifiedAtNanos = null, initialDecodedAtNanos = null,
        firstActualSubmittedAtNanos = firstSubmitted, firstActualPresentedAtNanos = firstPresented,
    )
}
