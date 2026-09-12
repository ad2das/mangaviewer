package ml.melun.mangaview.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.readBytes
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: reproduce the newxtoon search failure through the app's protected transport. */
@RunWith(AndroidJUnit4::class)
class NewxtoonSearchDeviceTest {
    @Test fun searchesThroughSourceAndRawTransport() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val source = application.graph.sources.require(SourceId("newxtoon"))
        val output = File(context.getExternalFilesDir(null), "newxtoon-search").apply { mkdirs() }
        val result = JSONObject()
        try {
            val page = source.search(SourceSearchQuery("로맨스"))
            result.put("sourceOk", true)
            result.put("sourceCount", page.items.size)
            result.put("sourceNextCursor", page.nextCursor)
            result.put("sourceTitles", page.items.take(5).joinToString(" | ") { it.title })
        } catch (failure: Throwable) {
            result.put("sourceOk", false)
            result.put("sourceError", failure.stackTraceToString())
        }
        val ua = "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val transport = factory.protect(ml.melun.mangaview.source.SourceTransport { throw java.io.IOException("Simulated blocked primary handshake") })
        try {
            for (path in listOf(
                "/search?q=%EB%A1%9C%EB%A7%A8%EC%8A%A4",
                "/search?q=%EB%A1%9C%EB%A7%A8%EC%8A%A4&page=1",
            )) {
                val name = "search" + path.hashCode().toString().replace("-", "n")
                try {
                    val response = transport.execute(SourceRequest(
                        "https://newxtoon1.com$path",
                        headers = mapOf("User-Agent" to ua),
                        totalTimeoutMillis = 30_000,
                    ))
                    val body = response.readBytes(3 * 1024 * 1024).toString(Charsets.UTF_8)
                    output.resolve("$name.html").writeText(body)
                    output.resolve("$name.json").writeText(
                        """{"status":${response.statusCode},"finalUrl":"${response.finalUrl}","bytes":${body.length}}""")
                    Log.i("NewxtoonSearch", "$path status=${response.statusCode} bytes=${body.length}")
                    response.close()
                } catch (failure: Throwable) {
                    output.resolve("$name-error.txt").writeText(failure.stackTraceToString())
                    Log.w("NewxtoonSearch", "$path failed", failure)
                }
            }
        } finally {
            transport.close()
        }
        output.resolve("result.json").writeText(result.toString(2))
    }
}
