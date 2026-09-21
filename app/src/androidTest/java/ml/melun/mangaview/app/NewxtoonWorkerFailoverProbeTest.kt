package ml.melun.mangaview.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.readBytes
import okhttp3.CookieJar
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the document route survives a broken private deployment: a dead origin is configured
 * ahead of the public relay, and the catalog still has to arrive.
 */
@RunWith(AndroidJUnit4::class)
class NewxtoonWorkerFailoverProbeTest {
    @Test fun failsOverPastADeadWorkerOrigin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NewxtoonWorkerOrigins.store(context, "https://definitely-not-a-relay.invalid")
        val factory = OkHttpTransportFactory(Dispatchers.IO)
        val route = NewxtoonWorkerTransport(
            factory.create(CookieJar.NO_COOKIES),
            factory.create(CookieJar.NO_COOKIES),
        )
        try {
            val response = route.execute(
                SourceRequest("https://newxtoon1.com/comics?page=1&sort=latest"))
            val body = response.readBytes(2 * 1024 * 1024)
            val mitigated = response.header("cf-mitigated")
            assertTrue(
                "failover must reach the live relay (status=${response.statusCode} " +
                    "bytes=${body.size} mitigated=$mitigated)",
                response.statusCode == 200 && body.size > 1000 && mitigated == null,
            )
        } finally {
            NewxtoonWorkerOrigins.store(context, "")
        }
    }
}
