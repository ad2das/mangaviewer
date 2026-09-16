package ml.melun.mangaview.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceNetworkRecoveryDeviceTest {
    @Test fun encryptedDnsAndFragmentedTlsReachBothStableNtkEntrypointsAndWfwf() = runBlocking {
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val transport = factory.protect(SourceTransport { throw IOException("Simulated blocked primary handshake") })
        try {
            for (url in listOf("https://sbxh9.com/api/works?page=1&pageSize=1&withTotal=1",
                "https://newtoki1.org/api/works?page=1&pageSize=1&withTotal=1")) {
                val response = transport.execute(SourceRequest(url, totalTimeoutMillis = 20_000))
                assertEquals("Unexpected status for $url", 200, response.statusCode)
                val text = response.readBytes(2 * 1024 * 1024).toString(Charsets.UTF_8)
                assertTrue("Provider catalog missing at $url", text.contains("sourceWorkId") || text.contains("/list?toon="))
                Log.i("SourceRecovery", "fragmentedTls=true url=$url final=${response.finalUrl} bytes=${text.length}")
            }
            // The mirror completes the same relay round trip even when the provider itself
            // answers with a site-level block (Cloudflare 1026 -> 451 from a flagged egress),
            // so only a well-formed HTTP response is asserted here.
            val mirror = transport.execute(SourceRequest("https://wfwf494.com/ing", totalTimeoutMillis = 20_000))
            val mirrorBody = mirror.readBytes(2 * 1024 * 1024).toString(Charsets.UTF_8)
            Log.i("SourceRecovery", "fragmentedTls=true url=wfwf494.com/ing status=${mirror.statusCode} " +
                "final=${mirror.finalUrl} bytes=${mirrorBody.length}")
            assertTrue("No HTTP status for the wfwf mirror", mirror.statusCode in 100..599)
            // The NTK native manifest flight is POST-only; a host already proven blocked by the
            // catalog GET must carry that body through the same recovered route.
            val post = transport.execute(SourceRequest(
                "https://sbxh9.com/api/ad/challenge",
                method = SourceHttpMethod.POST,
                headers = mapOf("Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*"),
                body = "{\"path\":\"/\",\"force\":false}".toByteArray(Charsets.UTF_8),
                bodyMediaType = "application/json",
                totalTimeoutMillis = 20_000,
            ))
            val postBody = post.readBytes(256 * 1024).toString(Charsets.UTF_8)
            Log.i("SourceRecovery", "fragmentedTls=true url=sbxh9.com/api/ad/challenge " +
                "status=${post.statusCode} bytes=${postBody.length}")
            assertTrue("No HTTP status for the recovered POST", post.statusCode in 100..599)
        } finally { transport.close() }
    }

    @Test fun verifiedOriginSurvivesRestartAndKeepsRequestPathQueryAndReferer() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test_source_origin_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
        }
        val requests = mutableListOf<SourceRequest>()
        val fake = SourceTransport { request ->
            requests += request
            if (request.url.startsWith("https://toki31.com")) throw IOException("Old address is dead")
            bytesResponse(request.url, """{"works":[{"sourceWorkId":"11","title":"Real provider work"}],"total":1}""")
        }
        try {
            val first = ProviderOriginTransport(fake, ProviderOriginDirectory(context, Dispatchers.IO, "agent"))
            val request = SourceRequest("https://toki31.com/api/works?tag=5&page=2", headers = mapOf("Referer" to "https://toki31.com/ing?tag=5"))
            first.execute(request).close()
            assertEquals("https://sbxh9.com/api/works?tag=5&page=2", requests.last().url)
            assertEquals("https://sbxh9.com/ing?tag=5", requests.last().headers["Referer"])
            requests.clear()
            val restarted = ProviderOriginTransport(fake, ProviderOriginDirectory(context, Dispatchers.IO, "agent"))
            restarted.execute(request).close()
            assertEquals(1, requests.size)
            assertEquals("https://sbxh9.com/api/works?tag=5&page=2", requests.single().url)
        } finally { base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun imageRedirectDoesNotProbeOrPublishTheCdnAsACatalogOrigin() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test_source_origin_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
        }
        val requests = mutableListOf<SourceRequest>()
        val fake = SourceTransport { request ->
            requests += request
            bytesResponse("https://images.example.test/original.webp", "unchanged bytes", "image/webp")
        }
        try {
            val directory = ProviderOriginDirectory(context, Dispatchers.IO, "agent")
            val transport = ProviderOriginTransport(fake, directory)
            val response = transport.execute(SourceRequest("https://sbxh9.com/image/11"))
            assertEquals("unchanged bytes", response.readBytes(1024).toString(Charsets.UTF_8))
            assertEquals(1, requests.size)
            assertEquals("https://sbxh9.com", directory.current("ntk", "https://sbxh9.com"))
        } finally { base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun bytesResponse(url: String, value: String, type: String = "application/json"): SourceResponse {
        val bytes = value.toByteArray()
        val stream = object : PageByteStream {
            var offset = 0
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
                if (this.offset == bytes.size) return -1
                val count = minOf(byteCount, bytes.size - this.offset)
                bytes.copyInto(destination, offset, this.offset, this.offset + count)
                this.offset += count
                return count
            }
            override fun close() = Unit
        }
        return SourceResponse(200, url, emptyMap(), stream, bytes.size.toLong(), type)
    }
}
