package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Enumerates newxtoon catalog entries whose title search and episode list are unique for UI entry. */
@RunWith(AndroidJUnit4::class)
class NewxtoonSampleMetadataDeviceTest {
    @Test fun collectCandidates() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val source = application.graph.sources.require(SourceId("newxtoon"))
        val output = File(context.getExternalFilesDir(null), "wfnew-sample").apply { mkdirs() }
        val cases = JSONArray()
        val skipped = JSONArray()
        val catalog = source.catalog(CatalogQuery(SeriesKind.COMIC, CatalogOrder.LATEST))
        for (series in catalog.items.take(12)) {
            try {
                val results = source.search(series.title, null).items
                val identity = results.filter { it.id == series.id }
                val sameTitle = results.filter { it.title == series.title }
                if (identity.size != 1 || sameTitle.size != 1 || identity.first().title != series.title) {
                    skipped.put(JSONObject().put("title", series.title).put("reason", "search-ambiguous")
                        .put("results", results.size).put("sameTitle", sameTitle.size).put("identity", identity.size))
                    continue
                }
                val episodes = source.episodes(series.id).items
                if (episodes.size < 3) {
                    skipped.put(JSONObject().put("title", series.title).put("reason", "too-few-episodes"))
                    continue
                }
                val pick = episodes.drop(1).dropLast(1).firstOrNull { candidate ->
                    candidate.title.isNotBlank() && episodes.count { it.title == candidate.title } == 1
                }
                if (pick == null) {
                    skipped.put(JSONObject().put("title", series.title).put("reason", "no-unique-mid-episode")
                        .put("episodes", episodes.size))
                    continue
                }
                cases.put(JSONObject()
                    .put("sourceId", "newxtoon")
                    .put("kind", "COMIC")
                    .put("seriesKey", series.id.remoteKey)
                    .put("seriesTitle", series.title)
                    .put("episodeKey", pick.id.remoteKey)
                    .put("episodeTitle", pick.title)
                    .put("episodeCount", episodes.size))
            } catch (failure: Throwable) {
                skipped.put(JSONObject().put("title", series.title).put("reason", failure.javaClass.simpleName)
                    .put("message", failure.message ?: ""))
            }
        }
        output.resolve("newxtoon-candidates.json").writeText(
            JSONObject().put("cases", cases).put("skipped", skipped).toString(2))
    }
}
