package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.readBytes
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Probes whether Chromium's own network stack (HttpEngine) can serve newxtoon documents
 * directly once it presents the persisted cf_clearance cookie and the WebView's identity.
 * A pass means cold loads never touch the JavaScript fetch bridge.
 */
@RunWith(AndroidJUnit4::class)
class NewxtoonCronetRouteDeviceTest {
    @Test fun cronetDirectFetch() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "newxtoon-cronet").apply { mkdirs() }
        val result = JSONObject()
        try {
            val prefs = context.getSharedPreferences("newxtoon_clearance", android.content.Context.MODE_PRIVATE)
            val clearance = prefs.getString("cf_clearance", null)
            val expiresAt = prefs.getLong("cf_clearance_expires_at", 0L)
            result.put("clearancePresent", clearance != null)
            result.put("clearanceValid", expiresAt > System.currentTimeMillis())
            val userAgent = android.webkit.WebSettings.getDefaultUserAgent(context)
            val engine = HttpEngineSourceTransport(context, userAgent, protocolAlternatesEnabled = false)
            try {
                val headers = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                    "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                )
                if (clearance != null) headers["Cookie"] = "cf_clearance=$clearance"
                val response = engine.execute(SourceRequest(
                    url = "https://newxtoon1.com/comics/17974",
                    method = SourceHttpMethod.GET,
                    headers = headers,
                    totalTimeoutMillis = 20_000L,
                    priority = PageFetchPriority.FOCUS,
                ))
                result.put("status", response.statusCode)
                result.put("cfMitigated", response.header("cf-mitigated"))
                result.put("server", response.header("server"))
                result.put("contentType", response.contentType)
                val bytes = response.readBytes(8 * 1024 * 1024)
                response.close()
                result.put("bytes", bytes.size)
                result.put("titlePresent", bytes.toString(Charsets.UTF_8).contains("<title>"))
            } finally {
                engine.close()
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
