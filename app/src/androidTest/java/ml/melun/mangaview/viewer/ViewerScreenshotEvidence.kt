package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Process
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.viewer.runtime.NativePresentationEvidencePacking
import ml.melun.mangaview.viewer.runtime.ViewerSurfaceHost
import org.json.JSONArray
import org.json.JSONObject

/** Captures immediately, without idle waits, input, frame requests, or display timestamp claims. */
internal class ViewerScreenshotEvidence(private val instrumentation: Instrumentation, private val directory: File) {
    fun capture(activity: ViewerActivity, requested: LiveEpisode, name: String): File {
        val before = snapshot(activity)
        val file = directory.resolve("$name.png")
        val record = JSONObject().put("schemaVersion", 1).put("name", name)
            .put("processPid", Process.myPid()).put("packageName", activity.packageName)
            .put("requestedEpisode", JSONObject().put("sourceId", requested.sourceId)
                .put("seriesKey", requested.seriesKey).put("episodeKey", requested.episodeKey))
            .put("captureApi", "UiAutomation.takeScreenshot")
            .put("captureIntervalMeaning", "CLOCK_MONOTONIC brackets the screenshot API call; encoding excluded; not an exact physical presentation timestamp")
            .put("sceneBinding", "UNVERIFIED_NATIVE_SUBMISSION_CANDIDATES")
            .put("limitations", JSONArray(listOf("Screenshot API returns no native buffer/token identity",
                "Main-thread snapshots cannot exclude an in-flight native submission",
                "Visible sibling bounds are potential occlusions; screenshot pixels require independent source-image comparison",
                "No source-row or physical-display credit is assigned by this sidecar")))
            .put("before", before)
        var pixels: Bitmap? = null
        try {
            record.put("captureStartedAtNanos", System.nanoTime())
            try { pixels = instrumentation.uiAutomation.takeScreenshot() }
            finally { record.put("captureCompletedAtNanos", System.nanoTime()) }
            record.put("after", snapshot(activity))
            val captured = checkNotNull(pixels) { "Screenshot API returned no pixels" }
            record.put("imageWidthPx", captured.width).put("imageHeightPx", captured.height)
            record.put("encodingStartedAtNanos", System.nanoTime())
            file.outputStream().use { check(captured.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            record.put("encodingCompletedAtNanos", System.nanoTime()).put("file", file.name)
                .put("pngSha256", sha256(file)).put("captureStatus", "CAPTURED")
            return file
        } catch (failure: Throwable) {
            record.put("captureStatus", "FAILED").put("failure", failure.stackTraceToString())
            throw failure
        } finally {
            pixels?.recycle()
            directory.resolve("$name.capture.json").writeText(record.toString(2))
        }
    }

    private fun snapshot(activity: ViewerActivity): JSONObject = onMain {
        val started = System.nanoTime()
        val telemetry = activity.viewerTelemetrySnapshot()
        val frames = NativePresentationEvidencePacking.decode(activity.presentationEvidenceSnapshot())
        val regions = activity.presentedRegionsSince(0L)
        val decor = activity.window.decorView
        val surface = descendants(decor).filterIsInstance<ViewerSurfaceHost>().singleOrNull()
        JSONObject().put("snapshotStartedAtNanos", started)
            .put("activityIdentity", System.identityHashCode(activity)).put("hasWindowFocus", activity.hasWindowFocus())
            .put("displayId", decor.display?.displayId).put("displayRotation", decor.display?.rotation)
            .put("surface", surface?.let(::viewJson))
            .put("potentialOccluders", JSONArray(surface?.let(::potentialOccluders).orEmpty()))
            .put("session", telemetry?.let(::telemetryJson))
            .put("candidateFrames", ViewerScreenshotSceneEvidence.candidates(frames, regions.regions))
            .put("candidateLimit", 32).put("candidateSelection", "Latest observed native submissions by submittedAtNanos; not a completeness assertion")
            .put("regionSequence", regions.nextSequence).put("olderRegionHistoryDropped", regions.dropped)
            .put("snapshotCompletedAtNanos", System.nanoTime())
    }

    private fun telemetryJson(value: ViewerTelemetrySnapshot) = JSONObject()
        .put("capturedAtNanos", value.capturedAtNanos)
        .put("sourceId", value.anchor.pageId.episodeId.seriesId.sourceId.value)
        .put("seriesKey", value.anchor.pageId.episodeId.seriesId.remoteKey)
        .put("episodeKey", value.anchor.pageId.episodeId.remoteKey).put("pageKey", value.anchor.pageId.remoteKey)
        .put("anchorOrdinal", value.anchorOrdinal).put("anchorOffsetUnits", value.anchor.offsetInPageUnits)
        .put("scrollOffsetUnits", value.scrollOffsetUnits).put("scrollRevision", value.scrollRevision)
        .put("userInputRevision", value.userInputRevision).put("scrollCause", value.scrollCause.name)
        .put("viewportHeightUnits", value.viewportHeightUnits).put("velocityUnitsPerSecond", value.velocityUnitsPerSecond)
        .put("manifests", JSONArray(value.manifests.map { manifest -> JSONObject()
            .put("sourceId", manifest.id.seriesId.sourceId.value).put("seriesKey", manifest.id.seriesId.remoteKey)
            .put("episodeKey", manifest.id.remoteKey).put("pages", JSONArray(manifest.pages.map { page ->
                JSONObject().put("pageKey", page.id.remoteKey).put("ordinal", page.ordinal)
                    .put("widthPx", page.dimensions?.widthPx).put("heightPx", page.dimensions?.heightPx)
                    .put("fingerprint", page.fingerprint)
            })) }))

    private fun potentialOccluders(surface: View): List<JSONObject> {
        val surfaceBounds = bounds(surface)
        val result = mutableListOf<JSONObject>()
        var branch = surface
        while (true) {
            val parent = branch.parent as? ViewGroup ?: break
            for (index in 0 until parent.childCount) {
                val sibling = parent.getChildAt(index)
                if (sibling !== branch && sibling.isShown && sibling.alpha > 0f && Rect.intersects(surfaceBounds, bounds(sibling))) {
                    result += viewJson(sibling).put("siblingIndex", index).put("branchIndex", parent.indexOfChild(branch))
                }
            }
            branch = parent
        }
        return result
    }

    private fun viewJson(view: View) = JSONObject()
        .put("class", view.javaClass.name).put("identity", System.identityHashCode(view))
        .put("description", view.contentDescription?.toString()).put("visibility", view.visibility)
        .put("alpha", view.alpha.toDouble()).put("z", view.z.toDouble()).put("isShown", view.isShown)
        .put("boundsCoordinates", "SCREEN_PIXELS_UNCLIPPED_VIEW_RECT")
        .put("bounds", bounds(view).let { JSONObject().put("left", it.left).put("top", it.top)
            .put("right", it.right).put("bottom", it.bottom) })

    private fun bounds(view: View): Rect {
        val screen = IntArray(2)
        view.getLocationOnScreen(screen)
        return Rect(screen[0], screen[1], screen[0] + view.width, screen[1] + view.height)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
