package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.ReadingPosition

sealed interface ViewerEvent {
    val atNanos: Long

    data class OpenEpisode(
        val generation: Long,
        val manifest: EpisodeManifest,
        val viewport: Viewport,
        override val atNanos: Long,
        val initialScroll: FixedPx = FixedPx.ZERO,
        val initialVelocityUnitsPerSecond: Long = 0L,
        val initialPosition: ReadingPosition? = null,
        val initialInteractionActive: Boolean = false,
        val preparedEpisode: PreparedViewerEpisode? = null,
    ) : ViewerEvent

    data class NextEpisodeSucceeded(
        val token: EpisodeOperationToken,
        val manifest: EpisodeManifest,
        override val atNanos: Long,
    ) : ViewerEvent

    data class NextEpisodeFailed(
        val token: EpisodeOperationToken,
        val reason: String,
        val retryDelayNanos: Long,
        override val atNanos: Long,
    ) : ViewerEvent

    data class UserScroll(
        val delta: FixedPx,
        val velocityUnitsPerSecond: Long,
        override val atNanos: Long,
        val frameTimelineVsyncId: Long = INVALID_FRAME_TIMELINE_VSYNC_ID,
        val expectedPresentationTimeNanos: Long = 0L,
    ) : ViewerEvent

    data class InteractionChanged(
        val active: Boolean,
        override val atNanos: Long,
    ) : ViewerEvent

    data class ViewportChanged(
        val viewport: Viewport,
        override val atNanos: Long,
    ) : ViewerEvent

    data class FetchSucceeded(
        val token: OperationToken,
        val encoded: VerifiedPageRef,
        val elapsedMillis: Long,
        override val atNanos: Long,
    ) : ViewerEvent

    /** The selected source returned valid response headers and the encoded body can now stream. */
    data class FetchResponseStarted(
        val token: OperationToken,
        override val atNanos: Long,
    ) : ViewerEvent

    data class FetchFailed(
        val token: OperationToken,
        val reason: String,
        val retryDelayNanos: Long,
        override val atNanos: Long,
    ) : ViewerEvent

    data class DecodeSucceeded(
        val token: OperationToken,
        val pixel: PixelRef,
        val elapsedMillis: Long,
        override val atNanos: Long,
    ) : ViewerEvent

    data class DecodeFailed(
        val token: OperationToken,
        val reason: String,
        val retryDelayNanos: Long,
        override val atNanos: Long,
    ) : ViewerEvent

    data class RetryWakeup(override val atNanos: Long) : ViewerEvent

    data class EnterBackground(override val atNanos: Long) : ViewerEvent

    data class ReturnForeground(override val atNanos: Long) : ViewerEvent

    data class SurfaceAttachmentChanged(
        val attached: Boolean,
        override val atNanos: Long,
    ) : ViewerEvent

    /** Render-thread confirmation that an actual page frame reached the display Surface. */
    data class ContentFramePresented(override val atNanos: Long) : ViewerEvent

    data class EvictPage(
        val generation: Long,
        val pageId: ml.melun.mangaview.core.PageId,
        override val atNanos: Long,
    ) : ViewerEvent
}

const val INVALID_FRAME_TIMELINE_VSYNC_ID = -1L
