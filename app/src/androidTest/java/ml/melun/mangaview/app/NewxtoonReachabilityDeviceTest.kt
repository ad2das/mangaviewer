package ml.melun.mangaview.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: fetch the newxtoon entry page through the app's protected transport. */
@RunWith(AndroidJUnit4::class)
class NewxtoonReachabilityDeviceTest {
    @Test fun fetchHomepageThroughProtectedTransport() = runBlocking<Unit> {
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val transport = factory.protect(SourceTransport { throw IOException("Simulated blocked primary handshake") })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "newxtoon-recon").apply { mkdirs() }
        try {
            for ((name, path) in listOf("home" to "/", "comics" to "/comics", "series" to "/comics/17974", "chapter" to "/comics/17974/chapters/1062717")) {
                try {
                    val response = transport.execute(SourceRequest(
                        "https://newxtoon1.com$path",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        ),
                        totalTimeoutMillis = 30_000,
                    ))
                    val body = response.readBytes(3 * 1024 * 1024).toString(Charsets.UTF_8)
                    output.resolve("$name.html").writeText(body)
                    output.resolve("$name.json").writeText(
                        """{"status":${response.statusCode},"finalUrl":"${response.finalUrl}","bytes":${body.length}}""")
                    Log.i("NewxtoonRecon", "$name status=${response.statusCode} bytes=${body.length}")
                    response.close()
                } catch (failure: Throwable) {
                    output.resolve("$name-error.txt").writeText(failure.stackTraceToString())
                    Log.w("NewxtoonRecon", "$name failed", failure)
                }
            }
        } finally {
            transport.close()
        }
    }
}
