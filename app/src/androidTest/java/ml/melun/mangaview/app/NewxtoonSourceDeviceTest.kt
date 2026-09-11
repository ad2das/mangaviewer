package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SeriesKind
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end device check of the newxtoon source through the app's protected transport. */
@RunWith(AndroidJUnit4::class)
class NewxtoonSourceDeviceTest {
    @Test fun loadsCatalogEpisodesAndFirstPage() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val source = application.graph.sources.require(SourceId("newxtoon"))
        val output = File(context.getExternalFilesDir(null), "newxtoon-source").apply { mkdirs() }
        val result = JSONObject()
        try {
            val catalog = source.catalog(CatalogQuery(SeriesKind.COMIC, CatalogOrder.LATEST))
            result.put("catalogCount", catalog.items.size)
            result.put("catalogNextCursor", catalog.nextCursor)
            val series = SeriesId(SourceId("newxtoon"), "17974")
            val episodes = source.episodes(series).items
            result.put("episodeCount", episodes.size)
            result.put("hasTargetChapter", episodes.any { it.id.remoteKey == "1062717" })
            val manifest = source.manifest(EpisodeId(series, "1062717"))
            result.put("pageCount", manifest.pages.size)
            result.put("allDimensionsKnown", manifest.pages.all { it.dimensions != null })
            result.put("hasNeighbors", manifest.previousEpisodeId != null || manifest.nextEpisodeId != null)
            val opened = source.openPage(manifest.pages.first().id, null, PageFetchPriority.FOCUS)
            try {
                val buffer = ByteArray(64 * 1024)
                result.put("firstPageBytes", opened.stream.readAtMost(buffer, 0, buffer.size))
                result.put("firstPageType", opened.contentType)
            } finally {
                opened.close()
            }
            result.put("ok", true)
        } catch (failure: Throwable) {
            result.put("ok", false)
            result.put("error", failure.stackTraceToString())
        } finally {
            output.resolve("result.json").writeText(result.toString(2))
        }
    }
}
