package ml.melun.mangaview.activity

import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.viewer.runtime.EngineCapturedFrame
import ml.melun.mangaview.viewer.runtime.EngineReadbackPacket
import ml.melun.mangaview.viewer.runtime.EngineSurfaceScene
import org.json.JSONObject

/** Real uninterrupted gestures, with an independent bounded natural-frame readback consumer. */
internal suspend fun traverseCapturedEpisode(
    activity: ViewerActivity,
    device: UiDevice,
    episode: EpisodeId,
    documents: EngineCapturedEpisodeDocuments,
    writeCapture: (EngineCapturedFrame) -> Unit,
    exportObservations: () -> Unit,
    captureStoppedScreen: (Int) -> Unit,
    injectGesture: (Int, Boolean) -> Unit,
    readbackEnabled: Boolean = true,
    fixedGestureDirections: List<Boolean>? = null,
    maximumDurationMillis: Long = 90_000,
    maximumCaptures: Long = 512,
): JSONObject = coroutineScope {
    require(maximumDurationMillis in 1_000L..300_000L && maximumCaptures in 1L..1024L)
    val startToken = AtomicLong(0)
    val endToken = AtomicLong(0)
    val lastCapturedToken = AtomicLong(0)
    val lastObservedToken = AtomicLong(0)
    val captures = AtomicLong(0)
    val deadline = SystemClock.elapsedRealtime() + maximumDurationMillis
    val reader = launch(Dispatchers.Default) {
        while (isActive) {
            val scene: EngineSurfaceScene
            val token: Long
            if (readbackEnabled) {
                val result = activity.captureNextEngineViewportFrame()
                check(result.pixels.status == EngineReadbackPacket.Status.OK) { "Natural viewport readback failed" }
                check(captures.incrementAndGet() <= maximumCaptures) { "Traversal capture capacity exceeded" }
                writeCapture(result)
                scene = result.scene
                token = result.identity.token
                lastCapturedToken.set(token)
            } else {
                val frame = activity.viewerEngineFrameSnapshot()
                if (frame == null || frame.identity.token == lastObservedToken.get()) {
                    delay(1)
                    continue
                }
                scene = frame.scene
                token = frame.identity.token
            }
            lastObservedToken.set(token)
            documents.pageBounds(episode)?.let { (first, last) ->
                val height = scene.viewport.heightPx * scene.coordinateUnitsPerPixel
                for (placement in scene.placements) {
                    val tile = placement.texture.tile
                    if (tile.pageId == first && tile.sourceTop == 0 && placement.topPx >= 0 && placement.topPx < height) {
                        startToken.compareAndSet(0, token)
                    }
                    if (tile.pageId == last && tile.sourceBottom == tile.dimensions.heightPx &&
                        placement.bottomPx > 0 && placement.bottomPx <= height) {
                        endToken.set(token)
                    }
                }
            }
        }
    }
    var gestures = 0
    fun swipe(forward: Boolean) {
        check(reader.isActive) { "Capture reader stopped during traversal" }
        check(SystemClock.elapsedRealtime() < deadline) { "Whole episode traversal deadline exceeded" }
        injectGesture(gestures, forward)
        gestures++
        exportObservations()
        activity.viewerFailureSnapshot()?.let { throw AssertionError("Traversal viewer failed", it) }
    }
    try {
        // Start input immediately, including when resuming in the middle of an existing episode.
        if (fixedGestureDirections != null) {
            require(fixedGestureDirections.isNotEmpty())
            fixedGestureDirections.forEach(::swipe)
        } else {
            swipe(true)
            while (startToken.get() == 0L) swipe(false)
            val forwardStartToken = lastObservedToken.get()
            while (endToken.get() <= forwardStartToken) swipe(true)
        }
        // This is the required final stopped interval, not a content-readiness gate.
        val stoppedAt = SystemClock.elapsedRealtimeNanos()
        // Fixed observation period for the ordinary fling tail, without probing content readiness.
        delay(2_000)
        captureStoppedScreen(0)
        delay(1_000)
        captureStoppedScreen(1)
        exportObservations()
        val finalFrame = activity.viewerEngineFrameSnapshot()
        JSONObject().apply {
            put("maximumDurationMillis", maximumDurationMillis); put("maximumCaptures", maximumCaptures)
            put("traversedDocumentEndpoints", startToken.get() > 0 && endToken.get() > startToken.get())
            put("gestures", gestures); put("readbackEnabled", readbackEnabled)
            put("fixedGestureMeasurement", fixedGestureDirections != null)
            put("gestureInjection", "PLATFORM_TOUCHSCREEN_WITH_FRACTIONAL_COORDINATES")
            put("firstPageStartToken", startToken.get()); put("lastPageEndToken", endToken.get())
            put("lastCapturedToken", lastCapturedToken.get()); put("stoppedAtNanos", stoppedAt)
            put("stopObservedUntilNanos", SystemClock.elapsedRealtimeNanos())
            put("lastSubmittedToken", finalFrame?.identity?.token ?: JSONObject.NULL)
            put("lastSubmittedFrameCaptured", finalFrame?.identity?.token == lastCapturedToken.get())
            put("allSourceRowsVerified", false); put("finalStopVerified", false); put("corpusCredit", 0)
        }
    } finally { reader.cancelAndJoin() }
}
