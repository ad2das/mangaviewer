package ml.melun.mangaview.source.ntk

import java.net.URI
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPageServiceTest {
    @Test
    fun openSelfHealsAnEpisodeEvictedFromTheManifestLru() = runTest {
        val transport = SelfHealTransport()
        val documents = NtkDocumentClient(
            NtkConfig("https://ntk.test", "agent"),
            transport,
        )
        val service = NtkPageService(
            transport = transport,
            documents = documents,
            gateway = SelfHealGateway(),
            parser = NtkDocumentParser(),
            preparedEpisodes = NtkPreparedEpisodeStore(maximumEpisodes = 1),
        )
        val first = episode("first")
        val second = episode("second")
        service.resolve(first)
        service.resolve(second)

        service.open(PageId.at(first, 0), null).stream.close()

        assertEquals(2, transport.documentLoads[first.remoteKey])
        assertEquals(1, transport.imageLoads)
    }

    @Test
    fun openRejectsAHttp200DecoyAndReplaysTheVerifiedImagePrefix() = runTest {
        val transport = SelfHealTransport()
        val documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport)
        val service = NtkPageService(
            transport = transport,
            documents = documents,
            gateway = SelfHealGateway(decoyFirst = true),
            parser = NtkDocumentParser(),
        )
        val episode = episode("replicas")
        service.resolve(episode)

        val opened = service.open(PageId.at(episode, 0), null)
        val prefix = ByteArray(3)
        assertEquals(3, opened.stream.readAtMost(prefix, 0, prefix.size))
        opened.close()

        assertEquals(listOf(0xff, 0xd8, 0xff), prefix.map { it.toInt() and 0xff })
        assertEquals(1, transport.decoyLoads)
        assertEquals(1, transport.imageLoads)
        assertEquals(
            listOf(
                "https://decoy.test${episode.remoteKey}/viewer.js",
                "https://images.test${episode.remoteKey}/p0000.jpg",
            ),
            transport.warmedUrls,
        )
    }

    @Test
    fun openAcceptsAProviderImageWithAnOpaqueExtensionAndMime() = runTest {
        val transport = SelfHealTransport()
        val documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport)
        val service = NtkPageService(
            transport = transport,
            documents = documents,
            gateway = SelfHealGateway(opaqueImage = true),
            parser = NtkDocumentParser(),
        )
        val episode = episode("opaque")
        service.resolve(episode)

        val opened = service.open(PageId.at(episode, 0), null)
        val prefix = ByteArray(12)
        assertEquals(prefix.size, opened.stream.readAtMost(prefix, 0, prefix.size))
        opened.close()

        assertEquals(listOf(0xff, 0xd8, 0xff), prefix.take(3).map { it.toInt() and 0xff })
        assertEquals(1, transport.opaqueImageLoads)
    }

    @Test
    fun openCombinesTheVerifiedPrefixWithFollowingBodyBytes() = runTest {
        val transport = SelfHealTransport()
        val documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport)
        val service = NtkPageService(
            transport = transport,
            documents = documents,
            gateway = SelfHealGateway(),
            parser = NtkDocumentParser(),
        )
        val episode = episode("combined-prefix")
        service.resolve(episode)

        val opened = service.open(PageId.at(episode, 0), null)
        val bytes = ByteArray(16)
        assertEquals(bytes.size, opened.stream.readAtMost(bytes, 0, bytes.size))
        opened.close()

        assertEquals(SelfHealTransport.JPEG_BYTES.toList(), bytes.toList())
    }

    @Test
    fun replicaLeaseRemainsBusyUntilTheEntireDeclaredBodyReachesEof() = runTest {
        val selector = NtkReplicaSelector()
        val candidates = selector.prepare(listOf(
            "https://a.test/page",
            "https://b.test/page",
        ))
        val first = selector.acquirePrepared(candidates)
        val stream = ReplicaTrackedPageByteStream(
            upstream = SelfHealBytes(SelfHealTransport.JPEG_BYTES),
            expectedLength = SelfHealTransport.JPEG_BYTES.size.toLong(),
            succeeded = { selector.completed(first, 40L) },
            failed = { selector.failedAndReleased(first) },
            abandoned = { selector.abandoned(first) },
        )

        val second = selector.acquirePrepared(candidates)
        assertEquals("b.test", second.candidate.host)
        selector.abandoned(second)

        val buffer = ByteArray(SelfHealTransport.JPEG_BYTES.size)
        assertEquals(buffer.size, stream.readAtMost(buffer, 0, buffer.size))
        assertEquals(-1, stream.readAtMost(buffer, 0, buffer.size))

        val afterCompletion = selector.acquirePrepared(candidates)
        assertEquals("a.test", afterCompletion.candidate.host)
        selector.abandoned(afterCompletion)
    }

    @Test
    fun shortReplicaBodyFailsAtEofAndMovesBehindItsAlternative() = runTest {
        val selector = NtkReplicaSelector()
        val candidates = selector.prepare(listOf(
            "https://a.test/page",
            "https://b.test/page",
        ))
        val first = selector.acquirePrepared(candidates)
        val stream = ReplicaTrackedPageByteStream(
            upstream = SelfHealBytes(SelfHealTransport.JPEG_BYTES),
            expectedLength = SelfHealTransport.JPEG_BYTES.size.toLong() + 1L,
            succeeded = { selector.completed(first, 40L) },
            failed = { selector.failedAndReleased(first) },
            abandoned = { selector.abandoned(first) },
        )
        val buffer = ByteArray(SelfHealTransport.JPEG_BYTES.size)
        assertEquals(buffer.size, stream.readAtMost(buffer, 0, buffer.size))

        val failure = runCatching { stream.readAtMost(buffer, 0, buffer.size) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        val afterFailure = selector.acquirePrepared(candidates)
        assertEquals("b.test", afterFailure.candidate.host)
        selector.abandoned(afterFailure)
    }

    private fun episode(key: String): EpisodeId = EpisodeId(
        SeriesId(SourceId("ntk"), "/webtoon/work"),
        "/webtoon/work/$key",
    )
}

private class SelfHealGateway(
    private val decoyFirst: Boolean = false,
    private val opaqueImage: Boolean = false,
) : NtkAccessGateway {
    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) = Unit

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> {
        val image = if (opaqueImage) {
            "https://opaque.test${document.path}/signed-page.woff2"
        } else {
            "https://images.test${document.path}/p0000.jpg"
        }
        val request = if (decoyFirst) {
            NtkPageRequest("https://decoy.test${document.path}/viewer.js", listOf(image))
        } else {
            NtkPageRequest(image)
        }
        return listOf(request)
    }
}

private class SelfHealTransport : SourceTransport {
    val documentLoads = mutableMapOf<String, Int>()
    var imageLoads = 0
    var decoyLoads = 0
    var opaqueImageLoads = 0
    val warmedUrls = mutableListOf<String>()

    override fun warmConnections(urls: List<String>) {
        warmedUrls += urls
    }

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val uri = URI(request.url)
        return if (uri.host == "images.test") {
            imageLoads += 1
            response(request.url, JPEG_BYTES, "image/jpeg")
        } else if (uri.host == "opaque.test") {
            opaqueImageLoads += 1
            response(request.url, JPEG_BYTES, "font/woff2")
        } else if (uri.host == "decoy.test") {
            decoyLoads += 1
            response(request.url, "console.log('decoy')".toByteArray(), "application/javascript")
        } else {
            val path = uri.path
            documentLoads[path] = documentLoads.getOrDefault(path, 0) + 1
            response(request.url, viewerDocument(path.substringAfterLast('/')).toByteArray(), "text/html")
        }
    }

    private fun response(url: String, bytes: ByteArray, contentType: String): SourceResponse =
        SourceResponse(
            statusCode = 200,
            finalUrl = url,
            headers = emptyMap(),
            body = SelfHealBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = contentType,
        )

    companion object {
        val JPEG_BYTES = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
            0, 12, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4,
        )
        fun viewerDocument(episodeId: String): String = """
            <script>{"sourceWorkId":"work","episodeId":"$episodeId","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images","imageCount":1}</script>
        """.trimIndent()
    }
}

private class SelfHealBytes(private val bytes: ByteArray) : PageByteStream {
    private var position = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
