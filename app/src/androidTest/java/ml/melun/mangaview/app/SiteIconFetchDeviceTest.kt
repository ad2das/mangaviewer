package ml.melun.mangaview.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.readBytes
import ml.melun.mangaview.source.wfwf.DEFAULT_WFWF_ORIGIN
import ml.melun.mangaview.source.wfwf.WfwfOriginResolver
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: download current WfWf site icon through the app's protected transport. */
@RunWith(AndroidJUnit4::class)
class SiteIconFetchDeviceTest {
    @Test fun fetchWfwfSiteIcon() = runBlocking<Unit> {
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val transport = factory.protect(SourceTransport { throw IOException("Simulated blocked primary handshake") })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "site-icons").apply { mkdirs() }
        val userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S928N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        try {
            val origin = WfwfOriginResolver(transport, userAgent).resolve(DEFAULT_WFWF_ORIGIN) ?: DEFAULT_WFWF_ORIGIN
            Log.i("SiteIcons", "wfwf origin=$origin")
            val home = transport.execute(SourceRequest(origin + "/", headers = mapOf("User-Agent" to userAgent), totalTimeoutMillis = 30_000))
            val html = home.readBytes(4 * 1024 * 1024).toString(Charsets.UTF_8)
            home.close()
            output.resolve("wfwf-home.html").writeText(html)
            val links = Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(html)
                .map { it.value }
                .filter { Regex("rel=[\"'](?:shortcut icon|icon|apple-touch-icon)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .toList()
            links.forEach { Log.i("SiteIcons", "link $it") }
            val hrefs = links.mapNotNull { Regex("href=[\"']([^\"']+)").find(it)?.groupValues?.get(1) }
            var index = 0
            for (href in hrefs + listOf("/favicon.ico", "/assets/img/favicon.png")) {
                val url = if (href.startsWith("http")) href else URI(origin).resolve(href).toString()
                try {
                    val response = transport.execute(SourceRequest(url, headers = mapOf("User-Agent" to userAgent), totalTimeoutMillis = 30_000))
                    val bytes = response.readBytes(4 * 1024 * 1024)
                    val status = response.statusCode
                    response.close()
                    val kind = when {
                        bytes.size >= 8 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() -> "ico"
                        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
                        bytes.size >= 12 && bytes[8] == 'W'.code.toByte() -> "webp"
                        else -> "other"
                    }
                    Log.i("SiteIcons", "fetch status=$status kind=$kind bytes=${bytes.size} url=$url")
                    if (status == 200 && kind != "other") {
                        output.resolve("wfwf-${index++}.$kind").writeBytes(bytes)
                    }
                } catch (failure: Throwable) {
                    Log.w("SiteIcons", "fetch failed url=$url", failure)
                }
            }
        } finally {
            transport.close()
        }
    }
}
