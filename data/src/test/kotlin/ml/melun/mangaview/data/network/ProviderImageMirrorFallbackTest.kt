package ml.melun.mangaview.data.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderImageMirrorFallbackTest {
    @Test fun blockedHostFallsBackToAMirrorServingTheSameObject() = runBlocking {
        val requested = mutableListOf<String>()
        val relaxed = SourceTransport { request ->
            requested += request.url
            if (request.url.contains("aws-cdn9.site")) throw IOException("connection reset")
            artworkResponse(request.url)
        }
        val transport = ProviderImageTransport(
            SourceTransport { error("provider hosts never use the delegate") },
            relaxed,
        )
        val response = transport.execute(SourceRequest(THUMBNAIL_ON_CDN9))
        assertEquals(MIRROR_ON_CDN1, response.finalUrl)
        assertEquals(listOf(THUMBNAIL_ON_CDN9, MIRROR_ON_CDN1), requested)
        response.close()
    }

    @Test fun rememberedMirrorIsTriedBeforeTheBlockedHostOnLaterRequests() = runBlocking {
        val requested = mutableListOf<String>()
        val relaxed = SourceTransport { request ->
            requested += request.url
            if (request.url.contains("aws-cdn9.site")) throw IOException("connection reset")
            artworkResponse(request.url)
        }
        val transport = ProviderImageTransport(SourceTransport { error("delegate") }, relaxed)
        transport.execute(SourceRequest(THUMBNAIL_ON_CDN9)).close()
        requested.clear()
        transport.execute(SourceRequest(THUMBNAIL_ON_CDN9)).close()
        assertEquals(MIRROR_ON_CDN1, requested.first())
        assertFalse(requested.contains(THUMBNAIL_ON_CDN9))
    }

    @Test fun warningRedirectsAndNonImageAnswersAreRejected() = runBlocking {
        val relaxed = SourceTransport { request ->
            when {
                request.url.contains("aws-cdn9.site") -> warningResponse(request.url)
                request.url.contains("aws-cdn1.site") -> htmlResponse(request.url, 404)
                else -> artworkResponse(request.url)
            }
        }
        val transport = ProviderImageTransport(SourceTransport { error("delegate") }, relaxed)
        val response = transport.execute(SourceRequest(THUMBNAIL_ON_CDN9))
        assertEquals(MIRROR_ON_CDN2, response.finalUrl)
        response.close()
    }

    @Test fun originalResponseIsReturnedWhenEveryCandidateFails() = runBlocking {
        val transport = ProviderImageTransport(
            SourceTransport { error("delegate") },
            SourceTransport { warningResponse(it.url) },
        )
        val response = transport.execute(SourceRequest(THUMBNAIL_ON_CDN9))
        assertEquals(302, response.statusCode)
        assertEquals(THUMBNAIL_ON_CDN9, response.finalUrl)
        response.close()
    }

    @Test fun firstFailureIsRethrownWhenNoCandidateAnswers() = runBlocking {
        val failure = IOException("connection reset")
        val transport = ProviderImageTransport(
            SourceTransport { error("delegate") },
            SourceTransport { throw failure },
        )
        try {
            transport.execute(SourceRequest(THUMBNAIL_ON_CDN9))
            fail()
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
    }

    @Test fun ordinaryHostsStayOnTheDelegateTransport() = runBlocking {
        var delegateCalls = 0
        val transport = ProviderImageTransport(
            SourceTransport { delegateCalls++; artworkResponse(it.url) },
            SourceTransport { error("ordinary hosts never use the relaxed client") },
        )
        transport.execute(SourceRequest("https://example.com/cover.jpg")).close()
        assertEquals(1, delegateCalls)
    }

    @Test fun mirrorCandidatesKeepPathAndQueryAndSkipTheOriginalHost() {
        val candidates = ProviderImageTrust.mirrorCandidates(THUMBNAIL_ON_CDN9)
        assertEquals(11, candidates.size)
        assertTrue(candidates.contains(MIRROR_ON_CDN1))
        assertFalse(candidates.any { it.contains("aws-cdn9.site") })
        assertTrue(ProviderImageTrust.mirrorCandidates("https://example.com/cover.jpg").isEmpty())
    }

    private fun artworkResponse(url: String) =
        SourceResponse(200, url, emptyMap(), ByteStream(byteArrayOf(1, 2, 3)), 3, "image/jpeg")

    private fun warningResponse(url: String) =
        SourceResponse(302, url, emptyMap(), ByteStream(ByteArray(0)), 0, "text/html; charset=UTF-8")

    private fun htmlResponse(url: String, status: Int) =
        SourceResponse(status, url, emptyMap(), ByteStream(ByteArray(0)), 0, "text/html")

    private class ByteStream(private val bytes: ByteArray) : PageByteStream {
        private var position = 0
        override suspend fun awaitReadable() = Unit
        override fun promote(priority: PageFetchPriority) = Unit
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (position == bytes.size) return -1
            val count = minOf(byteCount, bytes.size - position)
            bytes.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }
        override fun close() = Unit
    }

    private companion object {
        const val THUMBNAIL_ON_CDN9 = "https://aws-cdn9.site/black/thumbs/12706.jpg?v2"
        const val MIRROR_ON_CDN1 = "https://aws-cdn1.site/black/thumbs/12706.jpg?v2"
        const val MIRROR_ON_CDN2 = "https://aws-cdn2.site/black/thumbs/12706.jpg?v2"
    }
}
