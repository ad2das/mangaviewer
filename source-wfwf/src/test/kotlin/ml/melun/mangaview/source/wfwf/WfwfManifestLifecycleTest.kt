package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfManifestLifecycleTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentManifestCallsHaveOneEpisodeDocumentOwner() = runTest {
        val runtime = LifecycleRuntime(blockedEpisode = "12")
        val episode = runtime.episode("12")

        val first = async { runtime.source.manifest(episode) }
        runtime.transport.blockedViewerStarted.await()
        val second = async { runtime.source.manifest(episode) }
        runCurrent()

        assertEquals(1, runtime.transport.viewerCount("12"))
        runtime.transport.releaseBlockedViewer.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, runtime.transport.viewerCount("12"))
    }

    @Test
    fun blockedEpisodeManifestDoesNotBlockAnotherEpisode() = runTest {
        val runtime = LifecycleRuntime(blockedEpisode = "12")
        val blocked = async { runtime.source.manifest(runtime.episode("12")) }
        runtime.transport.blockedViewerStarted.await()

        val other = runtime.source.manifest(runtime.episode("11"))

        assertEquals("11", other.id.remoteKey)
        assertEquals(1, runtime.transport.viewerCount("11"))
        runtime.transport.releaseBlockedViewer.complete(Unit)
        blocked.await()
    }

    @Test
    fun evictedEpisodeSelfHealsBeforeOpeningItsPage() = runTest {
        val runtime = LifecycleRuntime(cacheEpisodes = 2)
        runtime.source.manifest(runtime.episode("1"))
        val evicted = runtime.source.manifest(runtime.episode("2"))
        runtime.source.manifest(runtime.episode("1"))
        runtime.source.manifest(runtime.episode("3"))

        runtime.source.openPage(evicted.pages.first().id).close()

        assertEquals(2, runtime.transport.viewerCount("2"))
        assertTrue(runtime.transport.imageRequests.single().contains("episode-2-v2-p0"))
    }

    @Test
    fun concurrentExpiredPagesShareOneManifestRefreshAndRetryOnlyNewUrls() = runTest {
        val runtime = LifecycleRuntime(expireFirstUrls = true, synchronizeExpiredPair = true)
        val manifest = runtime.source.manifest(runtime.episode("12"))

        val first = async { runtime.source.openPage(manifest.pages[0].id).close() }
        val second = async { runtime.source.openPage(manifest.pages[1].id).close() }
        first.await()
        second.await()

        assertEquals(2, runtime.transport.viewerCount("12"))
        assertEquals(4, runtime.transport.imageRequests.size)
        assertEquals(2, runtime.transport.imageRequests.count { "-v1-" in it })
        assertEquals(2, runtime.transport.imageRequests.count { "-v2-" in it })
    }
}

private class LifecycleRuntime(
    cacheEpisodes: Int = 12,
    blockedEpisode: String? = null,
    expireFirstUrls: Boolean = false,
    synchronizeExpiredPair: Boolean = false,
) {
    private val series = SeriesId(
        SourceId("wfwf"),
        WfwfSeriesKey(WfwfKind.WEBTOON, 77).encode(),
    )
    val transport = ManifestLifecycleTransport(
        blockedEpisode,
        expireFirstUrls,
        synchronizeExpiredPair,
    )
    val source = WfwfContentSource(
        WfwfConfig(
            initialOrigin = "https://wfwf.test",
            userAgent = "agent",
            manifestCacheEpisodes = cacheEpisodes,
        ),
        transport,
    )

    fun episode(key: String): EpisodeId = EpisodeId(series, key)
}

private class ManifestLifecycleTransport(
    private val blockedEpisode: String?,
    private val expireFirstUrls: Boolean,
    private val synchronizeExpiredPair: Boolean,
) : SourceTransport {
    val blockedViewerStarted = CompletableDeferred<Unit>()
    val releaseBlockedViewer = CompletableDeferred<Unit>()
    val imageRequests = mutableListOf<String>()
    private val viewerCounts = mutableMapOf<String, Int>()
    private val expiredPairReady = CompletableDeferred<Unit>()
    private var expiredRequestCount = 0

    fun viewerCount(episode: String): Int = viewerCounts[episode] ?: 0

    override suspend fun execute(request: SourceRequest): SourceResponse = when {
        "/list?toon=77" in request.url -> response(request.url, catalog())
        "/view?toon=77&num=" in request.url -> viewerResponse(request)
        request.url.startsWith("https://images.example/") -> imageResponse(request)
        else -> error("Unexpected manifest lifecycle request: ${request.url}")
    }

    private suspend fun viewerResponse(request: SourceRequest): SourceResponse {
        val episode = request.url.substringAfter("num=").substringBefore('&')
        val version = (viewerCounts[episode] ?: 0) + 1
        viewerCounts[episode] = version
        if (episode == blockedEpisode && version == 1) {
            blockedViewerStarted.complete(Unit)
            releaseBlockedViewer.await()
        }
        val images = (0..1).joinToString("\n") { page ->
            "<img data-src='https://images.example/episode-$episode-v$version-p$page.webp'>"
        }
        return response(request.url, "<div class='viewer-wrap'>$images</div>")
    }

    private suspend fun imageResponse(request: SourceRequest): SourceResponse {
        imageRequests += request.url
        val expired = expireFirstUrls && "-v1-" in request.url
        if (expired && synchronizeExpiredPair) {
            expiredRequestCount += 1
            if (expiredRequestCount == 2) expiredPairReady.complete(Unit)
            expiredPairReady.await()
        }
        return response(request.url, if (expired) "expired" else "image", if (expired) 403 else 200)
    }

    private fun catalog(): String = (20 downTo 1).joinToString("\n") { episode ->
        "<a href='/view?toon=77&num=$episode'><span>${episode}화</span></a>"
    }

    private fun response(url: String, body: String, status: Int = 200): SourceResponse {
        val bytes = body.toByteArray()
        return SourceResponse(
            statusCode = status,
            finalUrl = url,
            headers = emptyMap(),
            body = LifecycleBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = if (url.startsWith("https://images.example/")) "image/webp" else "text/html",
        )
    }
}

private class LifecycleBytes(private val bytes: ByteArray) : PageByteStream {
    private var offset = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (this.offset >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - this.offset)
        bytes.copyInto(destination, offset, this.offset, this.offset + count)
        this.offset += count
        return count
    }

    override fun close() = Unit
}
