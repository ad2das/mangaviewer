package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfSourceContractTest {
    @Test
    fun directManifestCallsExposePagesAndAdjacencyWithoutCatalogPriming() = runTest {
        listOf(WfwfKind.COMIC, WfwfKind.WEBTOON).forEach { kind ->
            val first = WfwfContractRuntime(kind, imageTag = "origin-a")
            val second = WfwfContractRuntime(kind, imageTag = "origin-b")
            val episode = first.episodeId("12")

            val firstManifest = first.source.manifest(episode)
            val repeatedManifest = first.source.manifest(episode)
            val differentOriginManifest = second.source.manifest(second.episodeId("12"))

            assertEquals(canonicalPages(episode, 3), firstManifest.pages)
            assertEquals(firstManifest.pages, repeatedManifest.pages)
            assertEquals(firstManifest.pages, differentOriginManifest.pages)
            assertEquals(3, firstManifest.pages.map(PageSpec::id).toSet().size)
            assertEquals(first.episodeId("11"), firstManifest.previousEpisodeId)
            assertEquals(first.episodeId("13"), firstManifest.nextEpisodeId)
            assertTrue(firstManifest.pages.all {
                it.dimensions == null && it.encodedLength == null && it.fingerprint == null
            })
            assertEquals(1, first.transport.catalogRequestCount)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentManifestAndAdjacentShareOneCatalogOwnerAndCache() = runTest {
        val transport = ConcurrentCatalogTransport()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val series = SeriesId(
            SourceId("wfwf"),
            WfwfSeriesKey(WfwfKind.WEBTOON, 10007).encode(),
        )
        val episode = EpisodeId(series, "12")

        val manifestRequest = async { source.manifest(episode) }
        val adjacentRequest = async { source.adjacent(episode) }
        transport.catalogStarted.await()
        runCurrent()

        assertEquals(1, transport.catalogRequestCount)
        assertEquals(1, transport.viewerRequestCount)
        transport.releaseCatalog.complete(Unit)
        val manifest = manifestRequest.await()
        val adjacent = adjacentRequest.await()

        assertEquals(EpisodeId(series, "11"), manifest.previousEpisodeId)
        assertEquals(EpisodeId(series, "13"), manifest.nextEpisodeId)
        assertEquals(manifest.previousEpisodeId, adjacent.previous)
        assertEquals(manifest.nextEpisodeId, adjacent.next)
        assertEquals(adjacent, source.adjacent(episode))
        assertEquals(1, transport.catalogRequestCount)
    }

    @Test
    fun imageUrlsAndConditionalHeadersRemainInternalToOpenPage() = runTest {
        val runtime = WfwfContractRuntime(WfwfKind.WEBTOON, imageTag = "signed")
        val episode = runtime.episodeId("12")
        runtime.source.episodes(episode.seriesId)
        val manifest = runtime.source.manifest(episode)

        runtime.source.openPage(
            manifest.pages[1].id,
            PageValidation(entityTag = "old-etag", lastModified = "yesterday"),
        ).close()

        val pageRequest = runtime.transport.requests.single { "/signed/002.webp" in it.url }
        assertEquals("agent", pageRequest.headers["User-Agent"])
        assertEquals("old-etag", pageRequest.headers["If-None-Match"])
        assertEquals("yesterday", pageRequest.headers["If-Modified-Since"])
        assertEquals(
            "https://wfwf.test/view?toon=10007&num=12",
            pageRequest.headers["Referer"],
        )
        assertEquals(PageId.at(episode, 1), manifest.pages[1].id)
        assertNull(manifest.pages[1].fingerprint)
    }

    private fun canonicalPages(episodeId: EpisodeId, count: Int): List<PageSpec> =
        List(count) { ordinal -> PageSpec(PageId.at(episodeId, ordinal), ordinal) }
}

private class WfwfContractRuntime(
    kind: WfwfKind,
    imageTag: String,
) {
    private val series = SeriesId(SourceId("wfwf"), WfwfSeriesKey(kind, 10007).encode())
    val transport = WfwfContractTransport(kind, imageTag)
    val source = WfwfContentSource(
        WfwfConfig("https://wfwf.test", "agent"),
        transport,
    )

    fun episodeId(remoteKey: String): EpisodeId = EpisodeId(series, remoteKey)
}

private class WfwfContractTransport(
    private val kind: WfwfKind,
    private val imageTag: String,
) : SourceTransport {
    val requests = mutableListOf<SourceRequest>()
    val catalogRequestCount: Int
        get() = requests.count { it.url == "https://wfwf.test${listPath()}" }

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        val payload = when {
            request.url == "https://wfwf.test${listPath()}" -> catalog()
            request.url == "https://wfwf.test${viewPath()}" -> viewer()
            request.url.startsWith("https://images.example/") -> "image"
            else -> error("Unexpected WFWF fixture request: ${request.url}")
        }.toByteArray()
        val image = request.url.startsWith("https://images.example/")
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf(
                "ETag" to listOf("new-etag"),
                "Last-Modified" to listOf("today"),
            ),
            body = WfwfContractBytes(payload),
            contentLength = payload.size.toLong(),
            contentType = if (image) "image/webp" else "text/html; charset=utf-8",
        )
    }

    private fun catalog(): String = listOf(13, 12, 11).joinToString("\n") { episode ->
        "<a href='${viewPath(episode.toString())}'><span class='subject'>${episode}화</span></a>"
    }

    private fun viewer(): String = """
        <div class="viewer-wrap">
          <img data-src="https://images.example/$imageTag/001.jpg?signature=a">
          <img data-original="https://images.example/$imageTag/002.webp?signature=b">
          <img src="/comic/$imageTag/003.png">
          <img data-src="https://images.example/$imageTag/001.jpg?signature=a">
        </div>
        <img src="https://images.example/banner-ad.jpg" class="banner">
    """.trimIndent()

    private fun listPath(): String = when (kind) {
        WfwfKind.COMIC -> "/cl?toon=10007"
        WfwfKind.WEBTOON -> "/list?toon=10007"
    }

    private fun viewPath(episode: String = "12"): String = when (kind) {
        WfwfKind.COMIC -> "/cv?toon=10007&num=$episode"
        WfwfKind.WEBTOON -> "/view?toon=10007&num=$episode"
    }
}

private class ConcurrentCatalogTransport : SourceTransport {
    val catalogStarted = CompletableDeferred<Unit>()
    val releaseCatalog = CompletableDeferred<Unit>()
    var catalogRequestCount = 0
        private set
    var viewerRequestCount = 0
        private set

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val body = when {
            request.url == "https://wfwf.test/list?toon=10007" -> {
                catalogRequestCount += 1
                catalogStarted.complete(Unit)
                releaseCatalog.await()
                listOf(13, 12, 11).joinToString("\n") { episode ->
                    "<a href='/view?toon=10007&num=$episode'><span>$episode</span></a>"
                }
            }
            request.url == "https://wfwf.test/view?toon=10007&num=12" -> {
                viewerRequestCount += 1
                """
                    <div class="viewer-wrap">
                      <img data-src="https://images.example/001.webp">
                    </div>
                """.trimIndent()
            }
            else -> error("Unexpected concurrent fixture request: ${request.url}")
        }.toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = WfwfContractBytes(body),
            contentLength = body.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class WfwfContractBytes(
    private val bytes: ByteArray,
) : PageByteStream {
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
