package ml.melun.mangaview.ui.library

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.activity.MainActivity
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceExchangeObserver
import ml.melun.mangaview.source.SourceExchangePhase
import ml.melun.mangaview.source.SourceSeries
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-provider measurements. Timings cover selection to usable episode state. */
@RunWith(AndroidJUnit4::class)
class EpisodeListLatencyDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun ntk() = audit("ntk", "/webtoon/769209")
    @Test fun wfwf() = audit("wfwf", "webtoon:5973")
    @Test fun newxtoon() = audit("newxtoon", "1876")
    @Test fun goodtoon() = audit("goodtoon", "gt-17070")

    private fun audit(provider: String, key: String) {
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ViewerApplication).graph
        val label = InstrumentationRegistry.getArguments().getString("episodeAuditLabel", "episode-latency")
        val output = File(context.getExternalFilesDir(null), "$label/$provider").apply { mkdirs() }
        val series = SourceSeries(SeriesId(SourceId(provider), key), "화산귀환")
        val network = ConcurrentLinkedQueue<JSONObject>()
        val previousObserver = graph.networkEvidenceObserver
        graph.networkEvidenceObserver = SourceExchangeObserver { record ->
            if (record.phase == SourceExchangePhase.HEADERS || record.phase == SourceExchangePhase.REQUEST_FAILED) {
                network.add(JSONObject().put("atMillis", SystemClock.elapsedRealtime())
                    .put("phase", record.phase.name).put("url", record.requestUrl)
                    .put("status", record.statusCode).put("error", record.errorType))
            }
        }
        val results = JSONArray()
        var scenario = launch()
        try {
            intent(scenario, LibraryIntent.DestinationSelected(MainDestination.SEARCH))
            intent(scenario, LibraryIntent.SourceSelected(series.id.sourceId))
            val first = open(scenario, series, "first", results, output)
            intent(scenario, LibraryIntent.Back)
            val reopened = open(scenario, series, "reopen", results, output)
            assertEquals("Reopening changed episode order or IDs", first, reopened)
            scenario.close()
            scenario = launch()
            val restored = open(scenario, series, "new-activity", results, output)
            assertEquals("New activity changed episode order or IDs", first, restored)
            device.takeScreenshot(output.resolve("episodes.png"))
            device.dumpWindowHierarchy(output.resolve("episodes.xml"))
        } finally {
            scenario.close()
            graph.networkEvidenceObserver = previousObserver
            output.resolve("network.json").writeText(JSONArray(network.toList()).toString(2))
        }
    }

    private fun open(
        scenario: ActivityScenario<MainActivity>, series: SourceSeries, name: String,
        results: JSONArray, output: File,
    ): List<String> {
        val started = SystemClock.elapsedRealtime()
        intent(scenario, LibraryIntent.SeriesSelected(series))
        intent(scenario, LibraryIntent.DetailTabSelected(DetailTab.EPISODES))
        val deadline = started + 150_000
        var firstVisible: Long? = null
        var firstCount = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = state(scenario)
            when (val content = snapshot.content) {
                is LibraryContent.Episodes -> if (content.series.id == series.id) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (firstVisible == null && content.items.isNotEmpty()) {
                        firstVisible = elapsed
                        firstCount = content.items.size
                        output.resolve("$name-first.json").writeText(JSONObject().put("elapsedMillis", elapsed)
                            .put("count", firstCount).put("complete", content.complete).toString(2))
                        Log.i("EpisodeLatency", "${series.id.sourceId.value} $name first visible: $elapsed ms, $firstCount episodes")
                    }
                    if (!content.complete || content.refreshing) {
                        check(content.refreshFailure == null) { "${series.id.sourceId.value}: ${content.refreshFailure}" }
                        SystemClock.sleep(10)
                        continue
                    }
                    assertTrue("${series.id.sourceId.value} has no chapters", content.items.isNotEmpty())
                    val ids = content.items.map { it.id.remoteKey }
                    assertEquals("Duplicate episode IDs", ids.size, ids.distinct().size)
                    results.put(JSONObject().put("mode", name).put("elapsedMillis", elapsed)
                        .put("firstVisibleMillis", firstVisible).put("firstCount", firstCount)
                        .put("count", ids.size).put("ids", JSONArray(ids)))
                    output.resolve("results.json").writeText(results.toString(2))
                    Log.i("EpisodeLatency", "${series.id.sourceId.value} $name: $elapsed ms, ${ids.size} episodes")
                    instrumentation.waitForIdleSync()
                    return ids
                }
                is LibraryContent.Failure -> error("${series.id.sourceId.value} $name: ${content.message}")
                else -> Unit
            }
            SystemClock.sleep(10)
        }
        error("${series.id.sourceId.value} $name episode request timed out")
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java))
        dismissAutomaticUpdateNotice(device)
        return scenario
    }

    private fun intent(scenario: ActivityScenario<MainActivity>, value: LibraryIntent) = scenario.onActivity {
        ViewModelProvider(it)[LibraryViewModel::class.java].accept(value)
    }

    private fun state(scenario: ActivityScenario<MainActivity>): LibraryState {
        lateinit var snapshot: LibraryState
        scenario.onActivity { snapshot = ViewModelProvider(it)[LibraryViewModel::class.java].state.value }
        return snapshot
    }
}
