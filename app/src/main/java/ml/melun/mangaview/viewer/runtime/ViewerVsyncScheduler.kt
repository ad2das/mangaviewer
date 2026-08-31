package ml.melun.mangaview.viewer.runtime

import android.os.Build
import android.view.Choreographer
import androidx.annotation.RequiresApi
import ml.melun.mangaview.viewer.INVALID_FRAME_TIMELINE_VSYNC_ID

/** Delivers the SurfaceFlinger frame timeline that produced a UI motion step. */
internal class ViewerVsyncScheduler(
    private val choreographer: Choreographer,
    private val callback: (
        frameTimeNanos: Long,
        vsyncId: Long,
        expectedPresentationTimeNanos: Long,
    ) -> Unit,
) {
    private var scheduled = false
    private val legacyCallback = Choreographer.FrameCallback { frameTimeNanos ->
        deliver(frameTimeNanos, INVALID_FRAME_TIMELINE_VSYNC_ID, frameTimeNanos)
    }
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val timelineCallback = Choreographer.VsyncCallback { frameData ->
        val timeline = frameData.preferredFrameTimeline
        deliver(frameData.frameTimeNanos, timeline.vsyncId, timeline.expectedPresentationTimeNanos)
    }

    fun post() {
        if (scheduled) return
        scheduled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            choreographer.postVsyncCallback(timelineCallback)
        } else {
            choreographer.postFrameCallback(legacyCallback)
        }
    }

    fun cancel() {
        if (!scheduled) return
        scheduled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            choreographer.removeVsyncCallback(timelineCallback)
        } else {
            choreographer.removeFrameCallback(legacyCallback)
        }
    }

    private fun deliver(frameTimeNanos: Long, vsyncId: Long, expectedPresentationTimeNanos: Long) {
        if (!scheduled) return
        scheduled = false
        callback(frameTimeNanos, vsyncId, expectedPresentationTimeNanos)
    }
}
