package ml.melun.mangaview.activity

import android.app.Instrumentation
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import java.io.File
import ml.melun.mangaview.viewer.runtime.ViewerSurfaceHost
import org.json.JSONObject

/** Compositor screenshots are separate evidence, never labeled as native renderer readbacks. */
internal fun captureEngineStoppedScreen(
    instrumentation: Instrumentation,
    activity: ViewerActivity,
    output: File,
    index: Int,
) {
    fun snapshot(): JSONObject {
        var result: JSONObject? = null
        instrumentation.runOnMainSync {
            fun surfaces(view: View): List<ViewerSurfaceHost> = when (view) {
                is ViewerSurfaceHost -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) }
                else -> emptyList()
            }
            val surface = surfaces(activity.window.decorView).single()
            check(surface.isShown && surface.isAttachedToWindow)
            val location = IntArray(2).also(surface::getLocationOnScreen)
            val frame = requireNotNull(activity.viewerEngineFrameSnapshot())
            result = JSONObject().apply {
                put("observedMonotonicNs", System.nanoTime())
                put("rendererId", frame.rendererId); put("sessionId", frame.identity.sessionId)
                put("rendererEpoch", frame.identity.rendererEpoch); put("surfaceEpoch", frame.identity.surfaceEpoch)
                put("token", frame.identity.token); put("eglFrameId", frame.eglFrameId)
                put("inputRevision", frame.identity.inputRevision); put("geometryRevision", frame.identity.geometryRevision)
                put("submittedAtNanos", frame.submittedAtNanos); put("swapSucceeded", frame.swapSucceeded)
                put("timestampKind", frame.timestampKind.name); put("timestampNanos", frame.timestampNanos)
                put("surfaceLeft", location[0]); put("surfaceTop", location[1])
                put("surfaceWidth", surface.width); put("surfaceHeight", surface.height)
                writeEngineSceneEvidence(this, frame.scene)
            }
        }
        return requireNotNull(result)
    }
    val before = snapshot()
    val started = System.nanoTime()
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Compositor screenshot failed" }
    val completed = System.nanoTime()
    try {
        val after = snapshot()
        val name = "stopped-screen-$index.png"
        File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(output, "stopped-screen-$index.json").writeText(JSONObject().apply {
            put("kind", "UI_AUTOMATION_COMPOSITED_SCREENSHOT"); put("imageFile", name)
            put("processId", android.os.Process.myPid()); put("processUid", android.os.Process.myUid())
            put("captureStartedMonotonicNs", started); put("captureCompletedMonotonicNs", completed)
            put("screenWidth", bitmap.width); put("screenHeight", bitmap.height)
            put("before", before); put("after", after)
            put("nativeReadback", false); put("forcedScene", false)
            put("physicalPresentationVerified", false); put("finalStopVerified", false); put("corpusCredit", 0)
        }.toString(2))
    } finally { bitmap.recycle() }
}
