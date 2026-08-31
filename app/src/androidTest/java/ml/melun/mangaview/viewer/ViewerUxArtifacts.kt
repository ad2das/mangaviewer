package ml.melun.mangaview.viewer

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import ml.melun.mangaview.viewer.runtime.ViewerStartupTiming

internal data class GestureMeasurement(
    val name: String,
    val direction: String,
    val dispatched: Boolean,
    val dispatchMillis: Long,
    val startedAtNanos: Long,
    val completedAtNanos: Long,
    val requestedDistancePixels: Int,
    val beforeScrollOffsetUnits: Long? = null,
    val afterScrollOffsetUnits: Long? = null,
    val displacementUnits: Long? = null,
    val viewportHeightUnits: Long? = null,
    val idleStable: Boolean = false,
)

internal data class MemoryMeasurement(
    val stage: String,
    val elapsedMillis: Long,
    val totalPssKib: Int,
)

internal data class ScreenshotInspection(
    val name: String,
    val sampleCount: Int,
    val distinctColorBuckets: Int,
    val dominantColorRatio: Double,
    val nonBlackRatio: Double,
)

internal data class HomeRoundTripMeasurement(
    val previousDescriptionTimestampMillis: Long,
    val homePressedAtNanos: Long,
    val restoreRequestedAtNanos: Long,
    val presentedAtNanos: Long,
    val surfaceLayer: String,
)

internal data class ViewerUxResult(
    val sourceId: String,
    val seriesKey: String,
    val episodeKey: String,
    val startedAtMillis: Long,
    val surfaceReadyMillis: Long,
    val firstFrameMillis: Long,
    val fullEpisodeVerifiedMillis: Long,
    val startupTiming: ViewerStartupTiming?,
    val observedEpisode: LiveEpisode?,
    val safeBounds: Rect,
    val frameBounds: Rect,
    val gestures: List<GestureMeasurement>,
    val homeRoundTrip: HomeRoundTripMeasurement?,
    val frameStats: FrameStatsSnapshot,
    val memory: List<MemoryMeasurement>,
    val screenshotInspections: List<ScreenshotInspection>,
    val violations: List<String>,
)

internal class ViewerUxArtifacts(
    context: Context,
    prefix: String,
) {
    val directory: File = requireNotNull(context.getExternalFilesDir("ux-evidence"))
        .resolve("$prefix-${SystemClock.elapsedRealtime()}")
        .apply { check(mkdirs() || isDirectory) }

    fun screenshot(device: UiDevice, name: String): File = File(directory, "$name.png").also { file ->
        check(device.takeScreenshot(file)) { "Could not capture ${file.absolutePath}" }
    }

    fun write(result: ViewerUxResult): File = File(directory, "summary.json").apply {
        writeText(result.toJson().toString(2))
    }

    fun export(): String = directory.resolve("summary.json").absolutePath

    private fun ViewerUxResult.toJson(): JSONObject = JSONObject()
        .put("sourceId", sourceId)
        .put("seriesKey", seriesKey)
        .put("episodeKey", episodeKey)
        .put("startedAtMillis", startedAtMillis)
        .put("surfaceReadyMillis", surfaceReadyMillis)
        .put("firstFrameMillis", firstFrameMillis)
        .put("fullEpisodeVerifiedMillis", fullEpisodeVerifiedMillis)
        .put("startupTiming", startupTiming?.toJson())
        .put("observedEpisode", observedEpisode?.toJson())
        .put("safeBounds", safeBounds.toJson())
        .put("frameBounds", frameBounds.toJson())
        .put("gestures", JSONArray().apply {
            gestures.forEach { gesture ->
                put(JSONObject()
                    .put("name", gesture.name)
                    .put("direction", gesture.direction)
                    .put("dispatched", gesture.dispatched)
                    .put("dispatchMillis", gesture.dispatchMillis)
                    .put("startedAtNanos", gesture.startedAtNanos)
                    .put("completedAtNanos", gesture.completedAtNanos)
                    .put("requestedDistancePixels", gesture.requestedDistancePixels)
                    .put("beforeScrollOffsetUnits", gesture.beforeScrollOffsetUnits)
                    .put("afterScrollOffsetUnits", gesture.afterScrollOffsetUnits)
                    .put("displacementUnits", gesture.displacementUnits)
                    .put("viewportHeightUnits", gesture.viewportHeightUnits)
                    .put("idleStable", gesture.idleStable))
            }
        })
        .put("homeRoundTrip", homeRoundTrip?.toJson())
        .put("gfx", frameStats.gfx.toJson())
        .put("render", frameStats.render.toJson())
        .put("motion", frameStats.motion.toJson())
        .put("surface", frameStats.surface?.toJson())
        .put("surfaceLayer", frameStats.surfaceLayer)
        .put("memory", JSONArray().apply {
            memory.forEach { sample ->
                put(JSONObject()
                    .put("stage", sample.stage)
                    .put("elapsedMillis", sample.elapsedMillis)
                    .put("totalPssKib", sample.totalPssKib))
            }
        })
        .put("screenshots", JSONArray().apply {
            screenshotInspections.forEach { inspection ->
                put(JSONObject()
                    .put("name", inspection.name)
                    .put("sampleCount", inspection.sampleCount)
                    .put("distinctColorBuckets", inspection.distinctColorBuckets)
                    .put("dominantColorRatio", inspection.dominantColorRatio)
                    .put("nonBlackRatio", inspection.nonBlackRatio))
            }
        })
        .put("violations", JSONArray(violations))

    private fun ViewerStartupTiming.toJson(): JSONObject = JSONObject()
        .put("presentedPageKey", presentedPageKey)
        .put("openStartedAtNanos", openStartedAtNanos)
        .put("manifestReadyAtNanos", manifestReadyAtNanos)
        .put("initialResponseStartedAtNanos", initialResponseStartedAtNanos)
        .put("initialVerifiedAtNanos", initialVerifiedAtNanos)
        .put("initialDecodedAtNanos", initialDecodedAtNanos)
        .put("firstActualPresentedAtNanos", firstActualPresentedAtNanos)
        .put("manifestMillis", elapsedMillis(openStartedAtNanos, manifestReadyAtNanos))
        .put("firstResponseWaitMillis", elapsedMillis(manifestReadyAtNanos, initialResponseStartedAtNanos))
        .put("transferAndPersistMillis", elapsedMillis(initialResponseStartedAtNanos, initialVerifiedAtNanos))
        .put("decodeMillis", elapsedMillis(initialVerifiedAtNanos, initialDecodedAtNanos))
        .put("presentationMillis", elapsedMillis(initialDecodedAtNanos, firstActualPresentedAtNanos))

    private fun elapsedMillis(start: Long?, end: Long?): Double? =
        if (start == null || end == null || end < start) null else (end - start) / 1_000_000.0

    private fun LiveEpisode.toJson(): JSONObject = JSONObject()
        .put("sourceId", sourceId)
        .put("seriesKey", seriesKey)
        .put("episodeKey", episodeKey)

    private fun Rect.toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

    private fun FrameTimingSummary.toJson(): JSONObject = JSONObject()
        .put("source", source)
        .put("sampleCount", sampleCount)
        .put("refreshPeriodNanos", refreshPeriodNanos)
        .put("p95Nanos", p95Nanos)
        .put("p95Millis", p95Millis)
        .put("maximumNanos", maximumNanos)
        .put("maximumMillis", maximumMillis)
        .put("missedFrameCount", missedFrameCount)
        .put("missedFrameRatio", missedFrameRatio)
        .put("freezeCount", freezeCount)
        .put("interactionWindowCount", interactionWindowCount)
        .put("coveredInteractionWindowCount", coveredInteractionWindowCount)
        .put("responseSampleCount", responseSampleCount)
        .put("p95ResponseNanos", p95ResponseNanos)
        .put("maximumResponseNanos", maximumResponseNanos)
        .put("responseFreezeCount", responseFreezeCount)

    private fun HomeRoundTripMeasurement.toJson(): JSONObject = JSONObject()
        .put("previousDescriptionTimestampMillis", previousDescriptionTimestampMillis)
        .put("homePressedAtNanos", homePressedAtNanos)
        .put("restoreRequestedAtNanos", restoreRequestedAtNanos)
        .put("presentedAtNanos", presentedAtNanos)
        .put("surfaceLayer", surfaceLayer)
}
