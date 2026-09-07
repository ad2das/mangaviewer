package ml.melun.mangaview.viewer.runtime

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineSwapBaselineTest {
    @Test fun emptySceneUsesTheSameSurfaceSubmissionPath() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val duration = arguments.getString("baselineDurationMillis")?.toLong() ?: 10_000L
        require(duration in 1_000..60_000)
        val interval = arguments.getString("baselineSwapInterval")?.toInt() ?: 1
        require(interval in 0..1)
        val maximumPending = arguments.getString("baselineMaximumPending")?.toInt() ?: 0
        require(maximumPending in 0..2)
        val pollMillis = arguments.getString("baselinePollMillis")?.toLong() ?: 0L
        require(pollMillis in 0L..16L)
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "engine-swap-baseline-${System.currentTimeMillis()}")
        check(output.mkdir())
        ActivityScenario.launch<EngineSwapBaselineActivity>(Intent(instrumentation.targetContext, EngineSwapBaselineActivity::class.java)
            .putExtra("baselineWidth", 1080).putExtra("baselineHeight", 2138)
            .putExtra("baselineSwapInterval", interval).putExtra("baselineMaximumPending", maximumPending)
            .putExtra("baselinePollMillis", pollMillis)).use { scenario ->
            var activity: EngineSwapBaselineActivity? = null
            scenario.onActivity { activity = it }
            val probe = requireNotNull(activity)
            withTimeout(15_000) { probe.ready.await() }
            scenario.onActivity { it.start() }
            delay(duration)
            withContext(Dispatchers.Main) { probe.stopAndClose() }
            val frames = probe.snapshot()
            check(frames.isNotEmpty() && frames.all { it.swapSucceeded })
            val costs = frames.map { it.renderLatencyNanos / 1_000_000.0 }.sorted()
            File(output, "summary.json").writeText(JSONObject().apply {
                put("classification", "SYNTHETIC_EMPTY_SCENE_EGL_CONTROL")
                put("swapInterval", interval)
                put("maximumPending", maximumPending)
                put("presentationPollMillis", pollMillis)
                put("width", 1080); put("height", 2138); put("durationMillis", duration)
                put("submittedFrameCount", frames.size); put("nativeP95Millis", costs[(costs.size * 0.95).toInt().coerceAtMost(costs.lastIndex)])
                put("nativeMaxMillis", costs.last()); put("atLeast100ms", costs.count { it >= 100 })
                put("physicalPresentationVerified", false); put("performanceQualified", false); put("corpusCredit", 0)
                put("frames", JSONArray().apply { frames.forEach { frame -> put(JSONObject().apply {
                    put("token", frame.identity.token); put("eglFrameId", frame.eglFrameId)
                    put("submittedAtNanos", frame.submittedAtNanos); put("nativeDurationNanos", frame.renderLatencyNanos)
                    put("timestampKind", frame.timestampKind.name); put("timestampNanos", frame.timestampNanos)
                    put("observationDeliveredNanos", probe.deliveredAt(frame.identity.token))
                }) } })
            }.toString(2))
        }
    }
}
