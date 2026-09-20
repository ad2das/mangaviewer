package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Warm-start proof that a persisted clearance serves newxtoon over the native transport: the
 * catalog and the episode 139976 document arrive without a challenge browser and without replay.
 */
@RunWith(AndroidJUnit4::class)
class NewxtoonNativeFetchDeviceTest {
    @Test fun catalogAndEpisodeServeNatively() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val graph = application.graph
        val clearance = graph.newxtoonClearanceState
        val source = graph.sources.require(SourceId("newxtoon"))
        val series = SeriesId(SourceId("newxtoon"), "1876")
        val episode = EpisodeId(series, "139976")
        val output = File(context.getExternalFilesDir(null), "newxtoon-native-fetch").apply { mkdirs() }
        val result = JSONObject()
        try {
            val persisted = clearance.persistedClearancePresent
            result.put("persistedClearance", persisted)
            result.put("solvedViewReadyAtStart", clearance.solvedViewReady)
            assertTrue("no persisted clearance on device; solve once before running this test", persisted)
            assertFalse("a solved WebView existed before the native fetch", clearance.solvedViewReady)

            val catalogStarted = System.nanoTime()
            val catalog = source.catalog(CatalogQuery(SeriesKind.COMIC, CatalogOrder.LATEST))
            val catalogMillis = (System.nanoTime() - catalogStarted) / 1_000_000
            result.put("catalogCount", catalog.items.size).put("catalogMillis", catalogMillis)

            val engineStarted = System.nanoTime()
            val spec = ViewerLaunchSpec(series.sourceId, series, episode)
            val session = graph.engine.session(spec)
            val engineCatalog = graph.engine.coordinator.submit(session.episodes(series, WorkPriority.FOCUS)).let { subscription ->
                try { subscription.await() } finally { subscription.close() }
            }
            val engineMillis = (System.nanoTime() - engineStarted) / 1_000_000
            result.put("engineEpisodeCount", engineCatalog.episodes.size).put("engineMillis", engineMillis)

            val manifestStarted = System.nanoTime()
            val manifest = source.manifest(episode)
            val manifestMillis = (System.nanoTime() - manifestStarted) / 1_000_000
            result.put("pageCount", manifest.pages.size).put("manifestMillis", manifestMillis)
            result.put("solvedViewReadyAtEnd", clearance.solvedViewReady)

            assertTrue("catalog returned no items", catalog.items.isNotEmpty())
            assertTrue("catalog fetch took ${catalogMillis}ms", catalogMillis < 5_000)
            assertTrue("engine episode catalog is empty", engineCatalog.episodes.isNotEmpty())
            assertTrue("episode manifest has no pages", manifest.pages.isNotEmpty())
            assertFalse("a challenge WebView was stood up during native fetches", clearance.solvedViewReady)
            result.put("ok", true)
        } catch (failure: Throwable) {
            result.put("ok", false).put("error", failure.stackTraceToString())
            throw failure
        } finally {
            output.resolve("result.json").writeText(result.toString(2))
        }
    }
}
