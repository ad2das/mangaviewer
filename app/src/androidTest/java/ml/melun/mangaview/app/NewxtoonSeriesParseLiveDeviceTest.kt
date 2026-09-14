package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.newxtoon.NewxtoonHtmlParser
import ml.melun.mangaview.source.readBytes
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Dumps the live series page and records what the parser/source see. */
@RunWith(AndroidJUnit4::class)
class NewxtoonSeriesParseLiveDeviceTest {
    @Test fun dumpsSeriesPageAndParserOutcome() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ua = "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val result = JSONObject()
        val output = File(context.getExternalFilesDir(null), "newxtoon-series-parse").apply { mkdirs() }
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val transport = factory.protect(SourceTransport { throw java.io.IOException("blocked") })
        try {
            val response = transport.execute(SourceRequest(
                "https://newxtoon1.com/comics/17974",
                headers = mapOf("User-Agent" to ua),
                totalTimeoutMillis = 30_000,
            ))
            val html = response.readBytes(8 * 1024 * 1024).toString(Charsets.UTF_8)
            response.close()
            output.resolve("series-live.html").writeText(html)
            result.put("status", response.statusCode)
            result.put("bytes", html.length)
            result.put("hasDescriptionAttr", html.contains("data-comic-description"))
            result.put("hasComicTitleId", html.contains("id=\"comic-title\""))
            result.put("ongoingCount", Regex("연재중").findAll(html).count())
            val parser = NewxtoonHtmlParser("https://newxtoon1.com")
            val details = parser.seriesDetails(html)
            result.put("parserStatus", details.status?.name ?: "null")
            result.put("parserDescription", (details.description?.length ?: -1).toString())
            result.put("parserAuthors", details.authors ?: "null")
            result.put("parserChapters", parser.chapters(html).size)
        } finally {
            transport.close()
        }
        val application = context.applicationContext as ViewerApplication
        val source = application.graph.sources.require(SourceId("newxtoon"))
        try {
            source.episodes(SeriesId(SourceId("newxtoon"), "17974"))
            val details = source.seriesDetails(SeriesId(SourceId("newxtoon"), "17974"))
            result.put("sourceStatus", details?.status?.name ?: "null")
            result.put("sourceAuthors", details?.authors ?: "null")
            result.put("sourceDescriptionLength", (details?.description?.length ?: -1).toString())
        } catch (failure: Throwable) {
            result.put("sourceError", failure.stackTraceToString())
        }
        output.resolve("result.json").writeText(result.toString(2))
    }
}
