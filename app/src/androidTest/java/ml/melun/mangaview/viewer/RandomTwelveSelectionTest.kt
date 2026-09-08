package ml.melun.mangaview.viewer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import ml.melun.mangaview.ViewerApplication
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Freezes twelve random live identities from metadata only; it never enters or prepares a viewer. */
@RunWith(AndroidJUnit4::class)
class RandomTwelveSelectionTest {
    @Test
    fun selectThreeDistinctSeriesAndOneEpisodePerSourceKind() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = requireNotNull(arguments.getString("random12RunId")) { "Missing random12RunId" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(runId)) { "Unsafe random12RunId" }
        val seedText = requireNotNull(arguments.getString("random12Seed")) { "Missing random12Seed" }
        val seed = parseRandomTwelveSeed(seedText)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir("random12-selection"))
        check(root.mkdirs() || root.isDirectory)
        val output = root.resolve(runId)
        check(!output.exists()) { "Random-12 run ID cannot be reused: $output" }
        check(output.mkdirs())
        output.resolve("selection-intent.json").writeText(JSONObject()
            .put("schema", 1).put("runId", runId).put("seed", seedText.lowercase())
            .put("requiredCases", 12).put("casesPerSourceKind", 3)
            .put("selectionOnly", true).put("manifestRequests", false)
            .put("pageRequests", false).put("viewerOpened", false).toString(2))

        val started = SystemClock.elapsedRealtime()
        var selected = 0
        var failure: Throwable? = null
        try {
            val application = context.applicationContext as ViewerApplication
            selected = RandomTwelveSelector(application.graph.sources, output, seed).select().size
            check(selected == 12)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            writeReceipt(output, runId, seedText.lowercase(), selected, started, failure)
        }
        failure?.let { throw it }
    }

    private fun writeReceipt(
        output: File,
        runId: String,
        seed: String,
        selected: Int,
        started: Long,
        failure: Throwable?,
    ) {
        output.resolve("receipt.json").writeText(JSONObject()
            .put("schema", 1).put("runId", runId).put("seed", seed)
            .put("status", if (failure == null) "SUCCESS" else "FAILURE")
            .put("selectedCases", selected).put("requiredCases", 12)
            .put("catalogPopulationFullyEnumerated", output.resolve("catalog-pool.json").isFile)
            .put("atomicPlanFrozen", output.resolve("cases.json").isFile)
            .put("selectionMetadataOnly", true).put("imageBodiesFetched", false)
            .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL).toString(2))
    }
}
