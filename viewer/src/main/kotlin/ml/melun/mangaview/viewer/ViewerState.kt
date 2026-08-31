package ml.melun.mangaview.viewer

import kotlinx.collections.immutable.PersistentMap
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId

enum class ViewerVisibility {
    FOREGROUND,
    BACKGROUND,
}

data class ViewerState(
    val generation: Long,
    val manifests: List<EpisodeManifest>,
    val pageOrder: List<PageId>,
    val pages: PersistentMap<PageId, PageRuntime>,
    /** The page the user was actually looking at when this generation opened. */
    val initialTargetPageId: PageId,
    val layout: LayoutLedger,
    val scroll: ScrollSnapshot,
    /** Monotonic proof of user events that produced an actual bounded scroll displacement. */
    val userInputRevision: Long,
    val viewport: Viewport,
    val visibility: ViewerVisibility,
    val surfaceAttached: Boolean,
    val velocityUnitsPerSecond: Long,
    val ownership: WorkOwnership,
    val episodeAppends: Map<EpisodeId, EpisodeAppendRuntime>,
    val episodeProgress: Map<EpisodeId, EpisodeProgress>,
    val coldFetchSweep: ColdFetchSweep,
    val residentPageIds: List<PageId>,
    val residentBytes: Long,
    val firstResponseReceived: Boolean,
    val networkConcurrency: Int,
    val retryDeadlines: PersistentMap<RetryWorkKey, Long>,
    val nextRetryDeadlineNanos: Long?,
    val lastEventNanos: Long,
    /** UI Choreographer timeline for this reduction only; never reused by unrelated events. */
    val frameTimelineVsyncId: Long = INVALID_FRAME_TIMELINE_VSYNC_ID,
    val expectedPresentationTimeNanos: Long = 0L,
    val interactionActive: Boolean = false,
    /** True only while the first pre-pixel gesture is still moving. */
    val startupMotionPending: Boolean = false,
    /** Monotonic proof that at least one actual page has reached presented state. */
    val hasPresentedContent: Boolean = false,
    /** False across first entry and every Surface reattach until RenderThread confirms a frame. */
    val surfacePresentationReady: Boolean = false,
    /** Prevents a fast first fling from repeatedly cancelling the only pre-response request. */
    val initialFetchRetargeted: Boolean = false,
) {
    val currentEpisodeId: EpisodeId
        get() = scroll.anchor.pageId.episodeId

    init {
        require(generation > 0L) { "Generation must be positive" }
        require(manifests.isNotEmpty()) { "Viewer needs at least one manifest" }
        require(pageOrder.isNotEmpty()) { "Viewer needs at least one page" }
        require(pages.size == pageOrder.size) { "Runtime page count must match page order" }
        require(initialTargetPageId in pages) { "Initial target page must belong to the viewer" }
        require(episodeProgress.size == manifests.size) { "Episode progress must match manifests" }
        require(residentBytes >= 0L) { "Resident byte count must not be negative" }
        require(networkConcurrency in 1..6) { "Network concurrency is outside its bounds" }
        require(firstResponseReceived || networkConcurrency == 1) {
            "Only one network lane is allowed before the first page response"
        }
        require(!firstResponseReceived || networkConcurrency >= 2) {
            "At least two network lanes are required after the first page response"
        }
        require(firstResponseReceived || ownership.fetches.keys.all { it == initialTargetPageId }) {
            "Only the initial visible target may fetch before the first response"
        }
        require(nextRetryDeadlineNanos == null || nextRetryDeadlineNanos >= 0L) {
            "Retry deadline must not be negative"
        }
        require(userInputRevision >= 0L) { "User input revision must not be negative" }
        require(lastEventNanos >= 0L) { "Event time must not be negative" }
        require(frameTimelineVsyncId >= INVALID_FRAME_TIMELINE_VSYNC_ID) {
            "Frame timeline VSYNC id is invalid"
        }
        require(expectedPresentationTimeNanos >= 0L) {
            "Expected presentation time must not be negative"
        }
    }
}
