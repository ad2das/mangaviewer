package ml.melun.mangaview.app

import android.os.Build
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.readBytes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: identical provider-authorized URLs and hashes, fresh transport per ordered round. */
@RunWith(AndroidJUnit4::class)
class EngineEqualAddressTransportTest {
    @Test fun compareFreshProtocolPoolsAgainstTheSameVerifiedOriginals() = runBlocking<Unit> {
        check(Build.VERSION.SDK_INT >= 34)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoded = requireNotNull(InstrumentationRegistry.getArguments().getString("verifiedRequestsBase64"))
        val fixture = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        val pages = fixture.getJSONArray("pages")
        require(pages.length() in 1..14)
        val userAgent = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val headers = mapOf("User-Agent" to userAgent, "Referer" to fixture.getString("referer"),
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Dest" to "image", "Sec-Fetch-Mode" to "no-cors", "Sec-Fetch-Site" to "cross-site")
        val output = File(context.getExternalFilesDir(null), "engine-capture-equal-address-${System.nanoTime()}")
        check(output.mkdir())
        val rounds = JSONArray()
        try {
            for ((round, preferQuic) in listOf(true, false, false, true).withIndex()) {
                val started = System.nanoTime()
                val transport = HttpEngineSourceTransport(context, userAgent, maximumSimultaneousBodyReads = 14)
                try {
                    transport.warmConnections(listOf(fixture.getString("referer")), preferQuic = false)
                    val urls = (0 until pages.length()).map { pages.getJSONObject(it).getString("url") }
                    transport.warmConnections(urls, preferQuic)
                    val responses = coroutineScope {
                        (0 until pages.length()).map { index -> async(Dispatchers.IO) {
                            val page = pages.getJSONObject(index)
                            val requestAt = System.nanoTime()
                            transport.execute(SourceRequest(page.getString("url"), headers = headers,
                                preferQuic = preferQuic, priority = PageFetchPriority.FOCUS)).use { response ->
                                val headersAt = System.nanoTime()
                                assertEquals(200, response.statusCode)
                                val bytes = response.readBytes(32 * 1024 * 1024)
                                val completedAt = System.nanoTime()
                                val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                                    .joinToString("") { "%02x".format(it) }
                                assertEquals(page.getString("sha256"), sha)
                                assertEquals(page.getLong("bytes"), bytes.size.toLong())
                                JSONObject().put("ordinal", index).put("url", page.getString("url"))
                                    .put("finalUrl", response.finalUrl).put("bytes", bytes.size).put("sha256", sha)
                                    .put("requestAtNanos", requestAt).put("headersAtNanos", headersAt)
                                    .put("completedAtNanos", completedAt)
                            }
                        } }.awaitAll()
                    }
                    rounds.put(JSONObject().put("round", round).put("preferQuic", preferQuic)
                        .put("startedAtNanos", started).put("responses", JSONArray(responses)))
                } finally { transport.close() }
            }
        } finally {
            File(output, "equal-address.json").writeText(JSONObject()
                .put("scope", "TRANSPORT_DIAGNOSTIC_ONLY").put("performanceQualified", false)
                .put("fixture", fixture).put("rounds", rounds).toString(2))
        }
    }
}
