package ml.melun.mangaview.source.ntk

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDocumentOriginTest {
    @Test fun failedHttpResponseCannotChangeTheSourceOrigin() = runTest {
        val client = client(SourceTransport { response(503, "https://failed.test/catalog") })
        assertTrue(runCatching { client.text("/catalog", false) }.isFailure)
        assertEquals("https://initial.test", client.currentOrigin())
    }

    @Test fun wrongEpisodeRedirectCannotChangeTheSourceOrigin() = runTest {
        val client = client(SourceTransport { response(200, "https://wrong.test/other") })
        assertTrue(runCatching { client.episodeDocument("/episode", PageFetchPriority.VISIBLE) }.isFailure)
        assertEquals("https://initial.test", client.currentOrigin())
    }

    @Test fun incompleteSelectedBodyCannotChangeTheSourceOrigin() = runTest {
        val client = client(SourceTransport {
            response(200, "https://truncated.test/episode").copy(body = object : PageByteStream {
                override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int =
                    throw IOException("truncated document")
                override fun close() = Unit
            })
        })
        assertTrue(runCatching { client.episodeDocument("/episode", PageFetchPriority.VISIBLE) }.isFailure)
        assertEquals("https://initial.test", client.currentOrigin())
    }

    @Test fun selectedSuccessfulDocumentDefinesItsOwnOrigin() = runTest {
        val transport = object : SourceTransport {
            override fun supportsProtocolSelection() = true
            override suspend fun execute(request: SourceRequest): SourceResponse {
                delay(if (request.preferQuic) 5L else 100L)
                return if (request.preferQuic) response(503, "https://failed.test/episode")
                else response(200, "https://selected.test/episode")
            }
        }
        val client = client(transport)
        val document = client.episodeDocument("/episode", PageFetchPriority.VISIBLE)
        assertEquals("https://selected.test", document.origin)
        assertEquals("https://initial.test", client.currentOrigin())
        client.acceptDocument(document)
        assertEquals(document.origin, client.currentOrigin())
    }

    @Test fun olderSuccessfulDocumentCannotOverwriteANewerAcceptedOrigin() = runTest {
        val client = client(SourceTransport { request ->
            val path = URI(request.url).path
            delay(if (path == "/old") 100L else 1L)
            response(200, "https://${path.removePrefix("/")}.test$path")
        })
        val old = async { client.episodeDocument("/old", PageFetchPriority.VISIBLE) }
        yield()
        val current = client.episodeDocument("/new", PageFetchPriority.VISIBLE)
        client.acceptDocument(current)
        client.acceptDocument(old.await())
        assertEquals("https://new.test", client.currentOrigin())
    }

    @Test fun unacceptedDocumentCannotPublishItsRedirectOrigin() = runTest {
        val client = client(SourceTransport { response(200, "https://unaccepted.test/episode") })
        client.episodeDocument("/episode", PageFetchPriority.VISIBLE)
        assertEquals("https://initial.test", client.currentOrigin())
    }

    private fun client(transport: SourceTransport) = NtkDocumentClient(NtkConfig("https://initial.test", "fixture"), transport)
    private fun response(status: Int, url: String) = SourceResponse(status, url, emptyMap(), object : PageByteStream {
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int = -1
        override fun close() = Unit
    }, 0L, "text/html")
}
