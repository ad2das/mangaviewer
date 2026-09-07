package ml.melun.mangaview.source.ntk

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPageServiceTest {
    @Test
    fun unrelatedDomImageCannotReplaceTheProtectedApiPageByMatchingItsCount() = runTest {
        val path = episode("owned-page").remoteKey
        val html = SelfHealTransport.viewerDocument("owned-page") +
            """<img src="https://images.test/webtoon_uploads/unrelated.jpg">"""
        val transport = SourceTransport { request ->
            val bytes = html.toByteArray()
            SourceResponse(200, request.url, emptyMap(), SelfHealBytes(bytes), bytes.size.toLong(), "text/html")
        }
        var protectedLoads = 0
        val gateway = object : NtkAccessGateway by SelfHealGateway() {
            override suspend fun resolve(document: NtkEpisodeDocument, descriptor: NtkViewerDescriptor): List<NtkPageRequest> {
                protectedLoads += 1
                return listOf(NtkPageRequest("https://images.test$path/p0000.jpg"))
            }
        }
        val service = NtkPageService(transport, NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            gateway, NtkDocumentParser())
        val result = service.resolve(episode("owned-page"))
        assertEquals(1, protectedLoads)
        assertEquals("https://images.test$path/p0000.jpg", result.requests.values.single().url)
    }

    @Test
    fun aPreparedPageKeepsItsEpisodeOriginAfterOtherNavigation() = runTest {
        val delegate = SelfHealTransport(directManifest = true)
        val imageRequests = mutableListOf<SourceRequest>()
        val transport = object : SourceTransport by delegate {
            override suspend fun execute(request: SourceRequest): SourceResponse {
                if (URI(request.url).host == "images.test") imageRequests += request
                val response = delegate.execute(request)
                return if (request.url.endsWith("/switch")) response.copy(finalUrl = "https://new.test/switch")
                else response
            }
        }
        val gatewayOrigins = mutableListOf<String>()
        val gateway = object : NtkAccessGateway by SelfHealGateway() {
            override fun isAuthorizationReady(origin: String, episodePath: String): Boolean {
                gatewayOrigins += origin
                return true
            }
        }
        val documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport)
        val service = NtkPageService(transport, documents, gateway, NtkDocumentParser())
        val episode = episode("bound-origin")
        val prepared = service.resolve(episode)
        documents.text("/switch", false)
        assertEquals("https://new.test", documents.currentOrigin())
        service.open(prepared.pages.single().id, null).close()
        assertTrue(imageRequests.isNotEmpty())
        assertTrue(imageRequests.all { it.headers["Referer"] == "https://ntk.test${episode.remoteKey}" })
        assertEquals(listOf("https://ntk.test"), gatewayOrigins)
    }

    @Test
    fun adjacentManifestTransportCannotCompeteAtVisiblePriority() = runTest {
        val transport = SelfHealTransport(directManifest = true)
        val service = NtkPageService(
            transport = transport,
            documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            gateway = SelfHealGateway(),
            parser = NtkDocumentParser(),
        )
        val episode = episode("adjacent-priority")

        service.prepare(episode, PreparationIntent.ADJACENT_FORWARD)
        service.resolve(episode)

        assertEquals(PageFetchPriority.BACKGROUND, transport.documentPriorities.single())
    }

    @Test
    fun initialManifestTransportRetainsVisiblePriority() = runTest {
        val transport = SelfHealTransport(directManifest = true)
        val service = NtkPageService(
            transport = transport,
            documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            gateway = SelfHealGateway(),
            parser = NtkDocumentParser(),
        )

        service.resolve(episode("initial-priority"))

        assertEquals(PageFetchPriority.VISIBLE, transport.documentPriorities.single())
    }

    @Test
    fun currentViewportRouteProbeReachesTheFirstProgressiveCheckpoint() {
        assertEquals(128 * 1_024, routeProbeBytes(PageFetchPriority.FOCUS))
    }

    @Test
    fun forwardRouteProvesBodyThroughputBeforeItOwnsTheRunway() {
        assertEquals(128 * 1_024, routeProbeBytes(PageFetchPriority.VISIBLE))
        assertEquals(32 * 1_024, routeProbeBytes(PageFetchPriority.FORWARD))
        assertEquals(16 * 1_024, routeProbeBytes(PageFetchPriority.BACKGROUND))
    }

    @Test
    fun validatedPrefixReturnsWithoutWaitingForAnotherUpstreamRead() = runTest {
        var upstreamReads = 0
        val upstream = object : PageByteStream {
            override suspend fun readAtMost(
                destination: ByteArray,
                offset: Int,
                byteCount: Int,
            ): Int {
                upstreamReads += 1
                destination[offset] = 9
                return 1
            }

            override fun close() = Unit
        }
        val stream = PrefixedPageByteStream(byteArrayOf(1, 2, 3), 3, upstream)
        val output = ByteArray(16)

        assertEquals(3, stream.readAtMost(output, 0, output.size))
        assertEquals(0, upstreamReads)
        assertEquals(1, stream.readAtMost(output, 3, output.size - 3))
        assertEquals(1, upstreamReads)
    }

    @Test
    fun imageTransportStartsAlongsideProviderAuthorizationProof() = runTest {
        val authorization = CompletableDeferred<Boolean>()
        val transport = SelfHealTransport(directManifest = true)
        val gateway = SelfHealGateway(authorization = authorization)
        val service = NtkPageService(
            transport = transport,
            documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            gateway = gateway,
            parser = NtkDocumentParser(),
        )
        val episode = episode("authorization-order")
        service.resolve(episode)

        val opened = async { service.open(PageId.at(episode, 0), null) }
        testScheduler.runCurrent()
        assertEquals(1, transport.imageLoads)

        opened.await().stream.close()
        assertEquals(1, transport.imageLoads)
        assertEquals(1, gateway.authorizationWaits)
        assertTrue(!authorization.isCompleted)
    }

    @Test
    fun authorizationReplacementWaitsForPhysicalCancellationOfTheOldRequest() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val authorization = CompletableDeferred<Boolean>()
        val delegate = SelfHealTransport(directManifest = true)
        var starts = 0
        var active = 0
        var peak = 0
        val transport = object : SourceTransport by delegate {
            override suspend fun execute(request: SourceRequest): SourceResponse {
                if (URI(request.url).host != "images.test") return delegate.execute(request)
                val ordinal = ++starts
                active++
                peak = maxOf(peak, active)
                try {
                    if (ordinal == 1) awaitCancellation()
                    return delegate.execute(request)
                } finally {
                    if (ordinal == 1) withContext(NonCancellable) { cleanup.await() }
                    active--
                }
            }
        }
        val service = NtkPageService(transport,
            NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            SelfHealGateway(authorization = authorization), NtkDocumentParser())
        val episode = episode("authorization-cancellation")
        service.resolve(episode)
        val opened = async { service.open(PageId.at(episode, 0), null) }
        testScheduler.runCurrent()
        authorization.complete(true)
        testScheduler.runCurrent()
        val startsBeforeCleanup = starts
        cleanup.complete(Unit)
        opened.await().close()
        assertEquals(1, startsBeforeCleanup)
        assertEquals(1, peak)
        assertEquals(2, starts)
    }

    @Test
    fun authorizationWinningTheRaceReplacesAnUnansweredStaleImageRequest() = runTest {
        val imageGate = CompletableDeferred<Unit>()
        val authorization = CompletableDeferred<Boolean>()
        val transport = SelfHealTransport(directManifest = true, imageGate = imageGate)
        val gateway = SelfHealGateway(authorization = authorization)
        val service = NtkPageService(
            transport = transport,
            documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            gateway = gateway,
            parser = NtkDocumentParser(),
        )
        val episode = episode("authorization-first")
        service.resolve(episode)

        val opened = async { service.open(PageId.at(episode, 0), null) }
        testScheduler.runCurrent()
        assertEquals(1, transport.imageLoads)
        authorization.complete(true)
        testScheduler.runCurrent()
        assertEquals(2, transport.imageLoads)
        imageGate.complete(Unit)
        opened.await().stream.close()

        assertEquals(2, transport.imageLoads)
    }

    @Test
    fun directManifestKeepsAckAliveUntilARealImageIsAccepted() = runTest {
        val transport = SelfHealTransport(directManifest = true)
        val gateway = SelfHealGateway()
        val service = NtkPageService(
            transport = transport,
            documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            gateway = gateway,
            parser = NtkDocumentParser(),
        )
        val episode = episode("direct")

        service.resolve(episode)
        assertEquals(0, gateway.pageAccessEstablishedCalls)

        service.open(PageId.at(episode, 0), null).stream.close()
        assertEquals(1, gateway.pageAccessEstablishedCalls)
    }

    @Test
    fun concurrentEpisodeManifestsUseAtMostTheTwoProviderBrowserLanes() = runTest {
        val transport = SelfHealTransport()
        val gateway = YieldingGateway()
        val service = NtkPageService(
            transport = transport,
            documents = NtkDocumentClient(NtkConfig("https://ntk.test", "agent"), transport),
            gateway = gateway,
            parser = NtkDocumentParser(),
        )

        listOf(episode("lane-a"), episode("lane-b"), episode("lane-c"))
            .map { value -> async { service.resolve(value) } }
            .awaitAll()

        assertEquals(2, gateway.maximumConcurrentResolves)
    }

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
        val expectedRoutes = listOf(
            "https://decoy.test${episode.remoteKey}/viewer.js",
            "https://images.test${episode.remoteKey}/p0000.jpg",
        )
        assertEquals(
            listOf(
                listOf(expectedRoutes[0]) to true,
                listOf(expectedRoutes[1]) to true,
                listOf(expectedRoutes.first()) to false,
            ),
            transport.warmedRoutes,
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

    @Test
    fun replicaThatStopsProducingBytesIsFailedAtTheProgressDeadline() = runTest {
        var closed = false
        var failed = 0
        val stream = ReplicaTrackedPageByteStream(
            upstream = NoProgressPageStream { closed = true },
            expectedLength = 1_024L,
            initialPriority = ml.melun.mangaview.source.PageFetchPriority.VISIBLE,
            succeeded = {},
            failed = { failed += 1 },
            abandoned = {},
        )

        val failure = runCatching {
            stream.readAtMost(ByteArray(16), 0, 16)
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertTrue(failure?.message.orEmpty().contains("no progress"))
        assertEquals(1, failed)
        assertTrue(closed)
    }

    @Test
    fun canceledReplicaReadIsAbandonedWithoutPoisoningRouteHealth() = runTest {
        var failed = 0
        var abandoned = 0
        val stream = ReplicaTrackedPageByteStream(
            upstream = NoProgressPageStream {},
            expectedLength = 1_024L,
            initialPriority = PageFetchPriority.FORWARD,
            succeeded = {},
            failed = { failed += 1 },
            abandoned = { abandoned += 1 },
        )
        val reader = launch {
            stream.readAtMost(ByteArray(16), 0, 16)
        }
        yield()

        reader.cancelAndJoin()

        assertEquals(0, failed)
        assertEquals(1, abandoned)
    }

    @Test
    fun enclosingDeadlineIsCancellationNotAnUpstreamProgressFailure() = runTest {
        var failed = 0
        var abandoned = 0
        val stream = ReplicaTrackedPageByteStream(NoProgressPageStream {}, 1024L,
            PageFetchPriority.FORWARD, {}, { failed++ }, { abandoned++ })
        val failure = runCatching {
            kotlinx.coroutines.withTimeout(1L) { stream.readAtMost(ByteArray(16), 0, 16) }
        }.exceptionOrNull()
        stream.close()
        assertTrue("Outer deadline was incorrectly converted to route failure: $failure",
            failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(0, failed)
        assertEquals(1, abandoned)
    }

    private fun episode(key: String): EpisodeId = EpisodeId(
        SeriesId(SourceId("ntk"), "/webtoon/work"),
        "/webtoon/work/$key",
    )
}

private class YieldingGateway : NtkAccessGateway {
    override val parallelPreparationCapacity: Int = 2
    var maximumConcurrentResolves = 0
    private var activeResolves = 0

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) = Unit

    override suspend fun resolve(
        document: NtkEpisodeDocument,
        descriptor: NtkViewerDescriptor,
    ): List<NtkPageRequest> {
        activeResolves += 1
        maximumConcurrentResolves = maxOf(maximumConcurrentResolves, activeResolves)
        try {
            yield()
            return listOf(NtkPageRequest("https://images.test${document.path}/p0000.jpg"))
        } finally {
            activeResolves -= 1
        }
    }
}

private class SelfHealGateway(
    private val decoyFirst: Boolean = false,
    private val opaqueImage: Boolean = false,
    private val authorization: CompletableDeferred<Boolean>? = null,
) : NtkAccessGateway {
    var pageAccessEstablishedCalls = 0
    var authorizationWaits = 0

    override suspend fun prepare(origin: String, episodePath: String, intent: PreparationIntent) = Unit

    override suspend fun awaitAuthorization(origin: String, episodePath: String): Boolean {
        authorizationWaits += 1
        return authorization?.await() ?: true
    }

    override fun isAuthorizationReady(origin: String, episodePath: String): Boolean =
        authorization?.isCompleted ?: true

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

    override fun pageAccessEstablished(origin: String, episodePath: String) {
        pageAccessEstablishedCalls += 1
    }
}

private class SelfHealTransport(
    private val directManifest: Boolean = false,
    private val imageGate: CompletableDeferred<Unit>? = null,
) : SourceTransport {
    val documentLoads = mutableMapOf<String, Int>()
    var imageLoads = 0
    var decoyLoads = 0
    var opaqueImageLoads = 0
    val warmedRoutes = mutableListOf<Pair<List<String>, Boolean>>()
    val documentPriorities = mutableListOf<PageFetchPriority>()

    override fun warmConnections(urls: List<String>, preferQuic: Boolean) {
        warmedRoutes += urls to preferQuic
    }

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val uri = URI(request.url)
        return if (uri.host == "images.test") {
            imageLoads += 1
            imageGate?.await()
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
            documentPriorities += request.priority
            response(
                request.url,
                viewerDocument(path.substringAfterLast('/'), path, directManifest).toByteArray(),
                "text/html",
            )
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
        fun viewerDocument(episodeId: String, path: String = "", direct: Boolean = false): String = """
            <script>{"sourceWorkId":"work","episodeId":"$episodeId","imagesToken":"token",
              "imageApiPath":"/api/webtoon-images","imageCount":1
              ${if (direct) ",\"images\":[\"https://images.test$path/p0000.jpg\"]" else ""}}</script>
            ${if (direct) "<img src=\"https://images.test$path/p0000.jpg\">" else ""}
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

private class NoProgressPageStream(private val onClose: () -> Unit) : PageByteStream {
    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int =
        CompletableDeferred<Int>().await()

    override fun close() = onClose()
}
