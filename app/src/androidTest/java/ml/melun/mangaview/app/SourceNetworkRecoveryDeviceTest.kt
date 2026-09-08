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
                "https://newtoki1.org/api/works?page=1&pageSize=1&withTotal=1", "https://wfwf494.com/ing")) {
                val response = transport.execute(SourceRequest(url, totalTimeoutMillis = 20_000))
                assertEquals(200, response.statusCode)
                val text = response.readBytes(2 * 1024 * 1024).toString(Charsets.UTF_8)
                assertTrue("Provider catalog missing at $url", text.contains("sourceWorkId") || text.contains("/list?toon="))
                Log.i("SourceRecovery", "fragmentedTls=true url=$url final=${response.finalUrl} bytes=${text.length}")
            }
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
