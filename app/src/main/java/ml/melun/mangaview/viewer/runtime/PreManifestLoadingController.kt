package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.FramePlan
import ml.melun.mangaview.viewer.LoadingFramePlanner
import ml.melun.mangaview.viewer.LoadingGeometry
import ml.melun.mangaview.viewer.Viewport
import ml.melun.mangaview.viewer.INVALID_FRAME_TIMELINE_VSYNC_ID

/** Main-thread-confined spatial state used only until the real manifest enters the coordinator. */
internal class PreManifestLoadingController(
    initialViewport: Viewport,
    private val planner: LoadingFramePlanner = LoadingFramePlanner(),
) {
    private var geometry: LoadingGeometry = planner.geometry(initialViewport)
    var offset: FixedPx = FixedPx.ZERO
        private set

    val hasDisplacedInput: Boolean
        get() = offset != FixedPx.ZERO

    fun resize(viewport: Viewport): Boolean {
        if (viewport == geometry.viewport) return false
        geometry = planner.geometry(viewport)
        offset = offset.coerceIn(FixedPx.ZERO, geometry.maximumOffset)
        return true
    }

    fun scrollBy(delta: FixedPx): Boolean {
        if (delta == FixedPx.ZERO) return false
        val next = (offset + delta).coerceIn(FixedPx.ZERO, geometry.maximumOffset)
        if (next == offset) return false
        offset = next
        return true
    }

    fun frame(
        frameTimelineVsyncId: Long = INVALID_FRAME_TIMELINE_VSYNC_ID,
        expectedPresentationTimeNanos: Long = 0L,
    ): FramePlan = planner.plan(
        geometry,
        offset,
        frameTimelineVsyncId,
        expectedPresentationTimeNanos,
    )
}
