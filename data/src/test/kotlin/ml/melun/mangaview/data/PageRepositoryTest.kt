package ml.melun.mangaview.data

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.ByteArrayPageStream
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.cache.RawPageCache
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRepositoryTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentCallersShareOneSourceAndOneCacheWrite() = runTest {
        val source = FakeSource()
        val cache = FakeCache()
        val repository = PageRepository(this, { source }, cache)

        val results = List(50) { async { repository.get(source.pageId) } }.awaitAll()
        runCurrent()

        assertEquals(1, source.openCount.get())
        assertEquals(1, cache.writeCount.get())
        assertEquals(1, results.map(CachedPage::sha256).toSet().size)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingOneWaiterDoesNotCancelTheSharedDownload() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(gate)
        val repository = PageRepository(this, { source }, FakeCache())
        val cancelled = async { repository.get(source.pageId) }
        val survivor = async { repository.get(source.pageId) }
        runCurrent()

        cancelled.cancel()
        gate.complete(Unit)

        assertEquals(source.pageId, survivor.await().pageId)
        assertEquals(1, source.openCount.get())
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingTheLastWaiterCancelsAndClosesTheUnderlyingStream() = runTest {
        val stream = BlockingPageStream()
        val source = FakeSource(stream = stream)
        val repository = PageRepository(this, { source }, FakeCache())
        val waiter = async { repository.get(source.pageId) }
        stream.readStarted.await()

        waiter.cancelAndJoin()
        runCurrent()

        assertEquals(1, stream.closeCount.get())
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun networkResponseObserverRunsOnceBeforePublicationAndNotForCacheHits() = runTest {
        val source = FakeSource()
        val repository = PageRepository(this, { source }, FakeCache())
        val responseCount = AtomicInteger()

        repository.get(source.pageId) { responseCount.incrementAndGet() }
        repository.get(source.pageId) { responseCount.incrementAndGet() }

        assertEquals(1, responseCount.get())
        assertEquals(1, source.openCount.get())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun visibleWaiterPromotesAQueuedSharedFlightBeforeTransportStarts() = runTest {
        val source = PrioritySource()
        val repository = PageRepository(this, { source }, FakeCache())

        val background = async {
            repository.get(source.pageId, PageFetchPriority.BACKGROUND)
        }
        val visible = async {
            repository.get(source.pageId, PageFetchPriority.VISIBLE)
        }
        awaitAll(background, visible)

        assertEquals(PageFetchPriority.VISIBLE, source.observedPriority)
        assertEquals(1, source.openCount.get())
    }
}

private class FakeCache : RawPageCache {
    val writeCount = AtomicInteger()
    private val entries = mutableMapOf<PageId, CachedPage>()

    override suspend fun find(pageId: PageId): CachedPage? = entries[pageId]

    override suspend fun write(pageId: PageId, openedPage: OpenedPage): CachedPage {
        writeCount.incrementAndGet()
        val buffer = ByteArray(64)
        while (openedPage.stream.readAtMost(buffer, 0, buffer.size) >= 0) Unit
        return CachedPage(
            pageId,
            File("page-${pageId.remoteKey}"),
            32L,
            "sha-${pageId.remoteKey}",
            "image/png",
            PageDimensions(1_080, 1_920),
        ).also { entries[pageId] = it }
    }

    override suspend fun remove(pageId: PageId) {
        entries.remove(pageId)
    }
}

private open class FakeSource(
    private val gate: CompletableDeferred<Unit>? = null,
    private val stream: PageByteStream? = null,
) : ContentSource {
    override val id = SourceId("source")
    val pageId = PageId(EpisodeId(SeriesId(id, "series"), "episode"), "p0")
    val openCount = AtomicInteger()

    open override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage {
        require(pageId == this.pageId)
        openCount.incrementAndGet()
        gate?.await()
        val body = stream ?: ByteArrayPageStream(ByteArray(32))
        return OpenedPage(body, if (stream == null) 32L else null, "image/png", null, null)
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> =
        unsupported()

    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> =
        unsupported()

    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = unsupported()

    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = unsupported()

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent): Unit = unsupported()

    private fun <T> unsupported(): T = throw UnsupportedOperationException("Not used by this test")
}

private class PrioritySource : FakeSource() {
    var observedPriority: PageFetchPriority? = null

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        observedPriority = priority
        return super.openPage(pageId, validation)
    }
}

private class BlockingPageStream : PageByteStream {
    val readStarted = CompletableDeferred<Unit>()
    val closeCount = AtomicInteger()
    private val releaseRead = CompletableDeferred<Unit>()

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        readStarted.complete(Unit)
        releaseRead.await()
        return -1
    }

    override fun close() {
        closeCount.incrementAndGet()
        releaseRead.complete(Unit)
    }
}
