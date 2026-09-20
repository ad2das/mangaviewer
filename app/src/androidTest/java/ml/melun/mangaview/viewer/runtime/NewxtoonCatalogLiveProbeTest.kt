package ml.melun.mangaview.viewer.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.WorkPriority
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live attribution for the newxtoon chapter list: engine catalog and source catalog side by side. */
@RunWith(AndroidJUnit4::class)
class NewxtoonCatalogLiveProbeTest {
    @Test fun everyChapterPageArrivesOnBothCatalogPaths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val seriesKey = arguments.getString("seriesKey", "17974")
        val episodeKey = arguments.getString("episodeKey", "1062717")
        val context = instrumentation.targetContext
        val application = context.applicationContext as ViewerApplication
        val graph = application.graph
        val sourceId = SourceId("newxtoon")
        val seriesId = SeriesId(sourceId, seriesKey)
        val episodeId = EpisodeId(seriesId, episodeKey)
        val spec = ViewerLaunchSpec(sourceId, seriesId, episodeId)

        val output = File(context.getExternalFilesDir(null), "newxtoon-catalog-live-${System.currentTimeMillis()}")
            .apply { mkdirs() }

        val report = JSONObject().put("series", seriesKey).put("episode", episodeKey)
            .put("first", arguments.getString("first", "engine"))
        var engineIds: List<String> = emptyList()
        var sourceIds: List<String> = emptyList()
        val runEngine: suspend () -> Unit = {
            val engineStartedAt = System.nanoTime()
            val engineCatalog = runBlocking {
                val session = graph.engine.session(spec)
                val subscription = graph.engine.coordinator.submit(session.episodes(seriesId, WorkPriority.FOCUS))
                try { subscription.await() } finally { subscription.close() }
            }
            val engineMillis = (System.nanoTime() - engineStartedAt) / 1_000_000
            engineIds = engineCatalog.episodes.map { it.id.remoteKey }
            Log.i("NtkCatalog", "engine count=${engineIds.size} ms=$engineMillis unique=${engineIds.toSet().size} " +
                "first=${engineIds.firstOrNull()} last=${engineIds.lastOrNull()}")
            report.put("engineCount", engineIds.size).put("engineMillis", engineMillis)
                .put("engineUnique", engineIds.toSet().size)
        }
        val runSource: suspend () -> Unit = {
            val partialSizes = JSONArray()
            val sourceStartedAt = System.nanoTime()
            val sourceCatalog = runBlocking {
                graph.viewer(spec).source.episodeCatalog(seriesId) { partial -> partialSizes.put(partial.size) }
            }
            val sourceMillis = (System.nanoTime() - sourceStartedAt) / 1_000_000
            sourceIds = sourceCatalog.map { it.id.remoteKey }
            Log.i("NtkCatalog", "source count=${sourceIds.size} ms=$sourceMillis unique=${sourceIds.toSet().size} " +
                "first=${sourceIds.firstOrNull()} last=${sourceIds.lastOrNull()} partials=$partialSizes")
            report.put("sourceCount", sourceIds.size).put("sourceMillis", sourceMillis)
                .put("sourceUnique", sourceIds.toSet().size).put("partials", partialSizes)
        }
        try {
            runBlocking {
                if (arguments.getString("first", "engine") == "source") {
                    runSource(); runEngine()
                } else {
                    runEngine(); runSource()
                }
            }
            assertTrue("engine catalog is empty", engineIds.isNotEmpty())
            assertTrue("source catalog is empty", sourceIds.isNotEmpty())
        } finally {
            File(output, "report.json").writeText(report.toString(2))
        }
    }
}
