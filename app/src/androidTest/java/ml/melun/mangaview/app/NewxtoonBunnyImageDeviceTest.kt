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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the newxtoon artwork zone is served straight from Bunny's edge network: the public
 * hostname's address lookup is remapped, the origin rides along as the referer, and no
 * Cloudflare hop or clearance cookie is involved.
 */
@RunWith(AndroidJUnit4::class)
class NewxtoonBunnyImageDeviceTest {
    @Test fun artworkServedDirectlyFromTheBunnyEdge() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as ViewerApplication
        val clearance = application.graph.newxtoonClearanceState
        val transport = OkHttpTransportFactory(Dispatchers.IO).createForBunnyImages(
            headers = linkedMapOf(
                "Referer" to "https://newxtoon1.com/",
                "User-Agent" to clearance.sourceUserAgent,
            ),
        )
        val output = File(context.getExternalFilesDir(null), "newxtoon-bunny").apply { mkdirs() }
        val result = JSONObject()
        try {
            for ((name, url) in listOf(
                "cover" to "https://user281.quicksharefiles.top/x/75a24944e8aa730656f178d7f15219940958d542.jpg",
                "ad" to "https://user281.quicksharefiles.top/ads/general/01M18WVEEBZ944F84XETNKTTNQ.png",
            )) {
                val started = System.nanoTime()
                val response = transport.execute(SourceRequest(url))
                val millis = (System.nanoTime() - started) / 1_000_000
                val body = response.readBytes(4 * 1024 * 1024)
                val server = response.header("server")
                val ray = response.header("cf-ray")
                Log.i("BunnyImage", "$name status=${response.statusCode} type=${response.contentType} " +
                    "server=$server cf-ray=$ray ms=$millis bytes=${body.size}")
                result.put(
                    name,
                    JSONObject()
                        .put("status", response.statusCode)
                        .put("contentType", response.contentType)
                        .put("server", server)
                        .put("cfRay", ray)
                        .put("millis", millis)
                        .put("bytes", body.size),
                )
                response.close()
                assertEquals("$name status", 200, result.getJSONObject(name).getInt("status"))
                assertTrue("$name content type", response.contentType?.startsWith("image/") == true)
                assertTrue("$name bytes", body.size > 1_000)
                assertTrue("$name came from Bunny", server?.contains("BunnyCDN", ignoreCase = true) == true)
                assertNull("$name must not touch Cloudflare", ray)
            }
            output.resolve("result.json").writeText(result.toString(2))
        } finally {
            transport.close()
        }
    }
}
