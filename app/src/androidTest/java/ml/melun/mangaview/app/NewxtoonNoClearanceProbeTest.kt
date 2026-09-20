package ml.melun.mangaview.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.readBytes
import okhttp3.CookieJar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves newxtoon documents are served without any clearance cookie or WebView: the worker route
 * answers catalog and chapter pages while the direct relayed route with no cookie is still handed
 * the challenge. Every result is mirrored to disk for evidence.
 */
@RunWith(AndroidJUnit4::class)
class NewxtoonNoClearanceProbeTest {
    @Test fun workerRouteServesDocumentsWithoutClearance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val clearance = application.graph.newxtoonClearanceState
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val output = File(context.getExternalFilesDir(null), "newxtoon-no-clearance").apply { mkdirs() }
        val result = JSONObject()
        // The wired app route: the worker first, a plain no-cookie client as its fallback, so a
        // fallback answer can only be the challenge.
        val workerRoute = NewxtoonWorkerTransport(
            factory.create(CookieJar.NO_COOKIES),
            factory.create(CookieJar.NO_COOKIES),
        )
        val directRoute = factory.createRelayed(
            CookieJar.NO_COOKIES,
            OkHttpTransportFactory.browserHeaders(clearance.clientHints) +
                ("User-Agent" to clearance.sourceUserAgent),
        )
        try {
            for ((name, url) in listOf(
                "catalog" to "https://newxtoon1.com/comics?page=1&sort=latest",
                "episode" to "https://newxtoon1.com/comics/1876/chapters/139976",
            )) {
                val started = System.nanoTime()
                val response = workerRoute.execute(SourceRequest(url))
                val millis = (System.nanoTime() - started) / 1_000_000
                val mitigated = response.header("cf-mitigated")
                val body = response.readBytes(2 * 1024 * 1024)
                val html = String(body, Charsets.UTF_8)
                Log.i(
                    "NoClearanceProbe",
                    "worker $name status=${response.statusCode} mitigated=$mitigated ms=$millis bytes=${body.size}",
                )
                result.put(
                    name,
                    JSONObject()
                        .put("status", response.statusCode)
                        .put("mitigated", mitigated)
                        .put("millis", millis)
                        .put("bytes", body.size)
                        .put("head", html.take(160)),
                )
                assertEquals("$name worker status", 200, response.statusCode)
                assertNull("$name must not be challenged", mitigated)
                assertTrue("$name html", html.contains("/comics/"))
                response.close()
            }
            // For the record: the same catalog without the worker is still challenged.
            val direct = runCatching {
                val response = directRoute.execute(SourceRequest("https://newxtoon1.com/comics?page=1&sort=latest"))
                val body = response.readBytes(64 * 1024)
                JSONObject()
                    .put("status", response.statusCode)
                    .put("mitigated", response.header("cf-mitigated"))
                    .put("bytes", body.size)
                    .also { response.close() }
            }.getOrElse { failure ->
                JSONObject().put("error", failure.toString())
            }
            Log.i("NoClearanceProbe", "direct catalog $direct")
            result.put("directCatalog", direct)
            output.resolve("result.json").writeText(result.toString(2))
        } finally {
            workerRoute.close()
            (directRoute as? AutoCloseable)?.close()
        }
    }
}
