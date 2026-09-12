package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Live evidence: newxtoon episode ordering, first-chapter selection and series status parsing. */
@RunWith(AndroidJUnit4::class)
class NewxtoonSeriesDetailsLiveDeviceTest {
    @Test fun readsEpisodesAndSeriesDetailsFromTheLiveSite() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val source = application.graph.sources.require(SourceId("newxtoon"))
        val seriesId = SeriesId(SourceId("newxtoon"), "17974")
        val result = JSONObject()
        try {
            val episodes = source.episodes(seriesId).items
            result.put("episodeCount", episodes.size)
            result.put("firstListTitle", episodes.firstOrNull()?.title)
            result.put("lastListTitle", episodes.lastOrNull()?.title)
            val oldest = episodes
                .filter { it.sequenceNumber != null }
                .minByOrNull { requireNotNull(it.sequenceNumber) }
            result.put("minSequenceTitle", oldest?.title)
            result.put("minSequenceRemoteKey", oldest?.id?.remoteKey)
            result.put("firstListRemoteKey", episodes.firstOrNull()?.id?.remoteKey)
            result.put("lastListRemoteKey", episodes.lastOrNull()?.id?.remoteKey)
        } catch (failure: Throwable) {
            result.put("episodesError", failure.stackTraceToString())
        }
        try {
            val details = source.seriesDetails(seriesId)
            result.put("status", details?.status?.name)
            result.put("authors", details?.authors)
            result.put("descriptionPresent", details?.description?.isNotBlank() == true)
            result.put("descriptionPreview", details?.description?.take(80))
        } catch (failure: Throwable) {
            result.put("detailsError", failure.stackTraceToString())
        }
        val output = File(context.getExternalFilesDir(null), "newxtoon-series-live").apply { mkdirs() }
        output.resolve("result.json").writeText(result.toString(2))
    }
}
