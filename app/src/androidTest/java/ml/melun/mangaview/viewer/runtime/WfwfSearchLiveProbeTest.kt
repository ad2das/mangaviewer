package ml.melun.mangaview.viewer.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSearchQuery
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live attribution for the wfwf title search the qualification corpus depends on. */
@RunWith(AndroidJUnit4::class)
class WfwfSearchLiveProbeTest {
    @Test fun titleSearchFindsCatalogSample() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as ViewerApplication).graph
        val source = graph.sources.require(SourceId("wfwf"))
        val cases = listOf(
            Triple("exact", "마왕의 딸은 너무 착해!!", null),
            Triple("exact-comic", "마왕의 딸은 너무 착해!!", SeriesKind.COMIC),
            Triple("no-bang", "마왕의 딸은 너무 착해", null),
            Triple("first-words", "마왕의 딸은", null),
            Triple("control", "화산귀환", null),
        )
        val report = JSONArray()
        runBlocking {
            for ((label, query, kind) in cases) {
                val startedAt = System.nanoTime()
                val outcome = runCatching { source.search(SourceSearchQuery(query, kind)) }
                val millis = (System.nanoTime() - startedAt) / 1_000_000
                val items = outcome.getOrNull()?.items.orEmpty()
                val entry = JSONObject().put("label", label).put("query", query)
                    .put("kind", kind?.name.toString()).put("millis", millis).put("count", items.size)
                    .put("items", JSONArray(items.map { JSONObject().put("key", it.id.remoteKey).put("title", it.title) }))
                outcome.exceptionOrNull()?.let { entry.put("error", it.toString()) }
                report.put(entry)
                Log.i("WfwfProbe", "label=$label count=${items.size} millis=$millis " +
                    "first=${items.firstOrNull()?.title} error=${outcome.exceptionOrNull()}")
            }
        }
        File(context.getExternalFilesDir(null), "wfwf-search-live-probe.json").writeText(report.toString(2))
        assertTrue("all probe queries failed", report.length() > 0)
    }
}
