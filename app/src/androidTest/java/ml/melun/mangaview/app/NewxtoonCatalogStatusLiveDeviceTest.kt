package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.newxtoon.NewxtoonHtmlParser
import ml.melun.mangaview.source.readBytes
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Dumps the live completed catalog so the card status marker can be inspected. */
@RunWith(AndroidJUnit4::class)
class NewxtoonCatalogStatusLiveDeviceTest {
    @Test fun dumpsCompletedCatalogPage() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ua = "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val result = JSONObject()
        val output = File(context.getExternalFilesDir(null), "newxtoon-catalog-live").apply { mkdirs() }
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val transport = factory.protect(SourceTransport { throw java.io.IOException("blocked") })
        try {
            for ((name, url) in listOf(
                "completed" to "https://newxtoon1.com/comics?status=%EC%99%84%EA%B2%B0",
                "ongoing" to "https://newxtoon1.com/comics?status=%EC%97%B0%EC%9E%AC%EC%A4%91",
            )) {
                val response = transport.execute(SourceRequest(
                    url,
                    headers = mapOf("User-Agent" to ua),
                    totalTimeoutMillis = 30_000,
                ))
                val html = response.readBytes(8 * 1024 * 1024).toString(Charsets.UTF_8)
                response.close()
                output.resolve("catalog-$name.html").writeText(html)
                val parser = NewxtoonHtmlParser("https://newxtoon1.com")
                val cards = parser.seriesCards(html)
                result.put("$name.status", response.statusCode)
                result.put("$name.bytes", html.length)
                result.put("$name.cards", cards.size)
                result.put("$name.completeMentions", Regex("완결").findAll(html).count())
                result.put("$name.ongoingMentions", Regex("연재중").findAll(html).count())
                result.put("$name.badgeSamples", Regex("<span class=\"rounded-full[^\"]*\">([^<]{1,20})</span>")
                    .findAll(html).map { it.groupValues[1] }.take(30).joinToString("|"))
            }
            result.put("ok", true)
        } catch (failure: Throwable) {
            result.put("ok", false)
            result.put("error", failure.stackTraceToString())
        } finally {
            transport.close()
            output.resolve("result.json").writeText(result.toString(2))
        }
    }
}
