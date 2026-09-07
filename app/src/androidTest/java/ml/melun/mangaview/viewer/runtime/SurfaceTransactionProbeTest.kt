package ml.melun.mangaview.viewer.runtime

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SurfaceTransactionProbeTest {
    @Test fun reportsActualTransactionAndPresentFenceCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "surface-transaction-probe-${System.currentTimeMillis()}")
        check(output.mkdir())
        ActivityScenario.launch<SurfaceTransactionProbeActivity>(Intent(context, SurfaceTransactionProbeActivity::class.java)).use { scenario ->
            lateinit var activity: SurfaceTransactionProbeActivity
            scenario.onActivity { activity = it }
            val (surface, width, height) = activity.ready.get(15, TimeUnit.SECONDS)
            val values = requireNotNull(SurfaceTransactionProbe.run(surface, width, height))
            File(output, "result.json").writeText(JSONObject().apply {
                put("classification", "SURFACE_TRANSACTION_CAPABILITY_CONTROL")
                put("width", width); put("height", height)
                put("columns", JSONArray(listOf("frame", "bufferId", "applyStartedNs", "applyReturnedNs",
                    "callbackReceivedNs", "latchNs", "presentFenceAvailable", "presentFencePollStatus", "presentFenceSignalNs", "completed")))
                put("frames", JSONArray(values.toList().chunked(10).map { JSONArray(it) }))
                put("pendingCallbacks", SurfaceTransactionProbe.pendingCallbacks())
                put("physicalPresentationVerified", false); put("performanceQualified", false); put("corpusCredit", 0)
            }.toString(2))
            assertEquals(30, values.size)
            for (frame in values.toList().chunked(10)) {
                assertTrue(frame[1] > 0)
                assertTrue(frame[2] > 0 && frame[3] >= frame[2] && frame[4] >= frame[2])
                assertEquals(1L, frame[9])
            }
            assertEquals(0, SurfaceTransactionProbe.pendingCallbacks())
        }
    }
}
