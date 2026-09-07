package ml.melun.mangaview.source.ntk

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkBrowserDocumentPayloadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun completeDocumentCrossesParcelWithoutEmbeddingItsBody() = runBlocking {
        val document = document("<html>한글-page</html>".repeat(80_000))
        val before = stagedFiles()
        NtkBrowserDocumentPayload.create(context.cacheDir, document).use { original ->
            val message = Bundle().also(original::writeTo)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(message)
                assertTrue("IPC must contain a file handle, not HTML bytes", parcel.dataSize() < 16_384)
                parcel.setDataPosition(0)
                NtkBrowserDocumentPayload.receive(requireNotNull(parcel.readBundle())).use { received ->
                    original.close()
                    val response = received.response()
                    assertEquals(document.origin + document.path, received.key)
                    assertArrayEquals(document.html.toByteArray(), response.data.use(InputStream::readBytes))
                }
            } finally {
                parcel.recycle()
            }
        }
        assertEquals(before, stagedFiles())
    }

    @Test
    fun overlappingReplaysOwnIndependentReadPositions() = runBlocking {
        val bytes = "<html>0123456789한글</html>".repeat(10_000).toByteArray()
        NtkBrowserDocumentPayload.create(context.cacheDir, document(bytes.toString(Charsets.UTF_8))).use { payload ->
            payload.response().data.use { first ->
                payload.response().data.use { second ->
                    val prefix = ByteArray(37)
                    assertEquals(prefix.size, first.read(prefix))
                    assertArrayEquals(bytes.copyOfRange(0, prefix.size), prefix)
                    payload.close()
                    assertArrayEquals(bytes, second.readBytes())
                    assertArrayEquals(bytes.copyOfRange(prefix.size, bytes.size), first.readBytes())
                }
            }
        }
    }

    @Test
    fun securityHeadersSurviveButCompressedTransportHeadersDoNot() = runBlocking {
        val document = document("<html>안녕</html>").copy(responseHeaders = mapOf(
            "Content-Encoding" to listOf("gzip"),
            "content-length" to listOf("17"),
            "Transfer-Encoding" to listOf("chunked"),
            "Content-Security-Policy" to listOf("default-src 'self'"),
            "Set-Cookie" to listOf("one=1; Secure", "two=2; Secure"),
        ))
        NtkBrowserDocumentPayload.create(context.cacheDir, document).use { payload ->
            val response = payload.response()
            response.data.use {
                assertEquals("default-src 'self'", response.responseHeaders["Content-Security-Policy"])
                assertEquals(document.html.toByteArray().size.toString(), response.responseHeaders["Content-Length"])
                assertFalse(response.responseHeaders.keys.any { name -> name.equals("Content-Encoding", true) })
                assertFalse(response.responseHeaders.keys.any { name -> name.equals("Set-Cookie", true) })
                assertEquals(listOf("one=1; Secure", "two=2; Secure"), payload.cookies)
            }
        }
    }

    @Test
    fun metadataHandoffAndClosePerformNoMainThreadDiskIo() = runBlocking {
        val payload = NtkBrowserDocumentPayload.create(context.cacheDir, document("<html>ok</html>"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build())
            try {
                payload.writeTo(Bundle())
                payload.close()
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        assertTrue(runCatching { payload.response() }.isFailure)
    }

    @Test
    fun redirectedDocumentCannotBeStagedForAnotherEpisode() = runBlocking {
        val invalid = document("<html>wrong</html>").copy(finalUrl = "https://provider.example/webtoon/work/other")
        assertTrue(runCatching { NtkBrowserDocumentPayload.create(context.cacheDir, invalid) }.isFailure)
    }

    private fun stagedFiles(): Set<String> = context.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("ntk-document-") }.map { it.name }.toSet()

    private fun document(html: String) = NtkEpisodeDocument(
        origin = "https://provider.example", path = "/webtoon/work/episode", html = html,
    )
}
