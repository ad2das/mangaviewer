package ml.melun.mangaview.data

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
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
import ml.melun.mangaview.data.cache.ImageHeader
import ml.melun.mangaview.data.cache.PageTransferPreview
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun visibleWaiterKeepsTheSingleUnansweredBackgroundAttempt() = runTest {
        val source = UpgradeablePrioritySource()
        val repository = PageRepository(this, { source }, FakeCache())
        val background = async {
            repository.get(source.pageId, PageFetchPriority.BACKGROUND)
        }
        source.backgroundStarted.await()

        val visible = async {
            repository.get(source.pageId, PageFetchPriority.VISIBLE)
        }
        runCurrent()
        source.releaseBackground.complete(Unit)

        assertEquals(source.pageId, visible.await().pageId)
        assertEquals(source.pageId, background.await().pageId)
        assertFalse(background.isCancelled)
        assertFalse(source.backgroundCancelled.isCompleted)
        assertEquals(listOf(PageFetchPriority.BACKGROUND), source.started)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun schedulerPromotionKeepsTheSingleUnansweredBackgroundAttempt() = runTest {
        val source = UpgradeablePrioritySource()
        val repository = PageRepository(this, { source }, FakeCache())
        val background = async {
            repository.get(source.pageId, PageFetchPriority.BACKGROUND)
        }
        source.backgroundStarted.await()

        repository.promote(source.pageId, PageFetchPriority.FORWARD)
        source.releaseBackground.complete(Unit)

        assertEquals(source.pageId, background.await().pageId)
        assertFalse(source.backgroundCancelled.isCompleted)
        assertEquals(listOf(PageFetchPriority.BACKGROUND), source.started)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun visibleDemandKeepsTheSingleUnansweredForwardRoute() = runTest {
        val source = ForwardUpgradeablePrioritySource()
        val repository = PageRepository(this, { source }, FakeCache())
        val forward = async {
            repository.get(source.pageId, PageFetchPriority.FORWARD)
        }
        source.forwardStarted.await()

        val visible = async {
            repository.get(source.pageId, PageFetchPriority.VISIBLE)
        }

        runCurrent()
        assertFalse(source.forwardCancelled.isCompleted)
        assertEquals(listOf(PageFetchPriority.FORWARD), source.started)
        source.releaseForward.complete(Unit)
        assertEquals(source.pageId, visible.await().pageId)
        assertEquals(source.pageId, forward.await().pageId)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun schedulerPromotionKeepsAStreamingAttemptAfterItPublishedUsefulPixels() = runTest {
        val stream = PromotableBlockingPageStream()
        val source = StreamingPrioritySource(stream)
        val cache = PreviewHoldingCache()
        val repository = PageRepository(this, { source }, cache)
        val background = async {
            repository.get(
                source.pageId,
                PageFetchPriority.BACKGROUND,
                onTransferPreview = {},
            )
        }
        cache.previewPublished.await()

        repository.promote(source.pageId, PageFetchPriority.FORWARD)
        runCurrent()

        assertEquals(1, source.openCount.get())
        assertTrue(PageFetchPriority.FORWARD in stream.promotions)
        cache.release.complete(Unit)
        assertEquals(source.pageId, background.await().pageId)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun focusHandoffPromotesAnUnansweredVisibleRouteWithoutRestartingIt() = runTest {
        val source = ImmediateHandoffSource()
        val repository = PageRepository(this, { source }, FakeCache())
        val visible = async {
            repository.get(source.pageId, PageFetchPriority.VISIBLE)
        }
        source.visibleStarted.await()

        val focus = async {
            repository.get(source.pageId, PageFetchPriority.FOCUS)
        }
        runCurrent()

        assertTrue(!visible.isCancelled)
        assertTrue(!source.visibleCancelled.isCompleted)
        source.releaseVisible.complete(Unit)
        assertEquals(source.pageId, focus.await().pageId)
        assertEquals(source.pageId, visible.await().pageId)
        assertEquals(listOf(PageFetchPriority.VISIBLE), source.started)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun unansweredDetailWarmupYieldsToTheVisiblePageInTheSameEpisode() = runTest {
        val source = PreemptiblePrioritySource()
        val repository = PageRepository(this, { source }, FakeCache())
        val warmer = async {
            repository.get(source.warmPageId, PageFetchPriority.NORMAL)
        }
        source.warmStarted.await()

        val visible = async {
            repository.get(source.visiblePageId, PageFetchPriority.VISIBLE)
        }

        assertEquals(source.visiblePageId, visible.await().pageId)
        source.warmCancelled.await()
        warmer.cancelAndJoin()
        assertEquals(listOf(
            source.warmPageId to PageFetchPriority.NORMAL,
            source.visiblePageId to PageFetchPriority.VISIBLE,
        ), source.started)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun forwardViewerRequestIsNotRestartedWhenTheFollowingPageBecomesVisible() = runTest {
        val source = PreemptiblePrioritySource()
        val repository = PageRepository(this, { source }, FakeCache())
        val forward = async {
            repository.get(source.warmPageId, PageFetchPriority.FORWARD)
        }
        source.warmStarted.await()

        val visible = async {
            repository.get(source.visiblePageId, PageFetchPriority.VISIBLE)
        }

        assertEquals(source.visiblePageId, visible.await().pageId)
        assertEquals(false, source.warmCancelled.isCompleted)
        forward.cancelAndJoin()
        assertEquals(listOf(
            source.warmPageId to PageFetchPriority.FORWARD,
            source.visiblePageId to PageFetchPriority.VISIBLE,
        ), source.started)
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun visibleWaiterPromotesAnAlreadyStreamingForwardBodyWithoutRestartingIt() = runTest {
        val stream = PromotableBlockingPageStream()
        val source = StreamingPrioritySource(stream)
        val repository = PageRepository(this, { source }, FakeCache())
        val forward = async {
            repository.get(source.pageId, PageFetchPriority.FORWARD)
        }
        stream.readStarted.await()

        val visible = async {
            repository.get(source.pageId, PageFetchPriority.VISIBLE)
        }
        runCurrent()

        assertTrue(PageFetchPriority.VISIBLE in stream.promotions)
        stream.releaseRead.complete(Unit)
        awaitAll(forward, visible)
        assertEquals(1, source.openCount.get())
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun parkedForwardBodyResumesTheSameStreamAndSingleCacheWrite() = runTest {
        val stream = TwoStepPageStream()
        val source = StreamingPrioritySource(stream)
        val cache = FakeCache()
        val repository = PageRepository(this, { source }, cache)
        val forward = async { repository.get(source.pageId, PageFetchPriority.FORWARD) }
        stream.firstReadStarted.await()

        assertTrue(repository.park(source.pageId))
        forward.cancelAndJoin()
        stream.releaseFirstRead.complete(Unit)
        runCurrent()
        assertFalse(stream.secondReadStarted.isCompleted)

        val visible = async { repository.get(source.pageId, PageFetchPriority.VISIBLE) }
        assertEquals(source.pageId, visible.await().pageId)
        assertTrue(stream.secondReadStarted.isCompleted)
        assertEquals(1, source.openCount.get())
        assertEquals(1, cache.writeCount.get())
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun openingCohortRetiresOnlyAnUnclaimedNonForwardSpeculativeBody() = runTest {
        val stream = BlockingPageStream()
        val source = FakeSource(stream = stream)
        val repository = PageRepository(this, { source }, FakeCache())
        val prediction = async {
            repository.get(
                source.pageId,
                PageFetchPriority.NORMAL,
                speculative = true,
            )
        }
        stream.readStarted.await()

        repository.retireSpeculationOutside(source.pageId.episodeId, emptySet())
        prediction.join()
        runCurrent()

        assertTrue(prediction.isCancelled)
        assertEquals(1, stream.closeCount.get())
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun openingCohortKeepsAnUnclaimedForwardFlightForViewerHandoff() = runTest {
        val stream = BlockingPageStream()
        val source = FakeSource(stream = stream)
        val repository = PageRepository(this, { source }, FakeCache())
        val prediction = async {
            repository.get(
                source.pageId,
                PageFetchPriority.FORWARD,
                speculative = true,
            )
        }
        stream.readStarted.await()

        repository.retireSpeculationOutside(source.pageId.episodeId, emptySet())
        runCurrent()

        assertFalse(prediction.isCancelled)
        prediction.cancelAndJoin()
        assertEquals(1, stream.closeCount.get())
        assertEquals(0, repository.activeRequestCount())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun openingCohortKeepsASpeculativeBodyClaimedByRealDemand() = runTest {
        val stream = PromotableBlockingPageStream()
        val source = StreamingPrioritySource(stream)
        val repository = PageRepository(this, { source }, FakeCache())
        val prediction = async {
            repository.get(
                source.pageId,
                PageFetchPriority.FORWARD,
                speculative = true,
            )
        }
        stream.readStarted.await()
        val viewer = async { repository.get(source.pageId, PageFetchPriority.VISIBLE) }
        runCurrent()

        repository.retireSpeculationOutside(source.pageId.episodeId, emptySet())
        assertEquals(false, prediction.isCancelled)
        stream.releaseRead.complete(Unit)

        awaitAll(prediction, viewer)
        assertEquals(1, source.openCount.get())
        assertEquals(0, repository.activeRequestCount())
    }

    @Test
    fun visibleTransferRetriesOneTransientBodyFailureInsideTheSameLogicalFlight() = runTest {
        val source = FakeSource()
        val cache = TransientFailureCache()
        val repository = PageRepository(this, { source }, cache)

        val page = repository.get(source.pageId, PageFetchPriority.VISIBLE)

        assertEquals(source.pageId, page.pageId)
        assertEquals(2, source.openCount.get())
        assertEquals(2, cache.writeCount.get())
        assertEquals(0, repository.activeRequestCount())
    }
}

private class TransientFailureCache : RawPageCache {
    val writeCount = AtomicInteger()

    override suspend fun find(pageId: PageId): CachedPage? = null

    override suspend fun write(
        pageId: PageId,
        openedPage: OpenedPage,
        onPreview: ((ml.melun.mangaview.data.cache.PageTransferPreview) -> Unit)?,
    ): CachedPage {
        if (writeCount.incrementAndGet() == 1) throw IOException("transient body failure")
        return CachedPage(
            pageId,
            File("retry-${pageId.remoteKey}"),
            32L,
            "retry-sha",
            "image/png",
            PageDimensions(1_080, 1_920),
        )
    }

    override suspend fun remove(pageId: PageId) = Unit
}

private class PreviewHoldingCache : RawPageCache {
    val previewPublished = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun find(pageId: PageId): CachedPage? = null

    override suspend fun write(
        pageId: PageId,
        openedPage: OpenedPage,
        onPreview: ((PageTransferPreview) -> Unit)?,
    ): CachedPage {
        onPreview?.invoke(PageTransferPreview(
            pageId = pageId,
            file = File("preview-${pageId.remoteKey}"),
            byteCount = 128L * 1_024L,
            header = ImageHeader("image/png", PageDimensions(1_080, 1_920), true),
        ))
        previewPublished.complete(Unit)
        release.await()
        return CachedPage(
            pageId,
            File("page-${pageId.remoteKey}"),
            128L * 1_024L,
            "preview-sha",
            "image/png",
            PageDimensions(1_080, 1_920),
        )
    }

    override suspend fun remove(pageId: PageId) = Unit
}

private class FakeCache : RawPageCache {
    val writeCount = AtomicInteger()
    private val entries = mutableMapOf<PageId, CachedPage>()

    override suspend fun find(pageId: PageId): CachedPage? = entries[pageId]

    override suspend fun write(
        pageId: PageId,
        openedPage: OpenedPage,
        onPreview: ((ml.melun.mangaview.data.cache.PageTransferPreview) -> Unit)?,
    ): CachedPage {
        writeCount.incrementAndGet()
        val buffer = ByteArray(64)
        while (true) {
            openedPage.stream.awaitReadable()
            if (openedPage.stream.readAtMost(buffer, 0, buffer.size) < 0) break
        }
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

private class UpgradeablePrioritySource : FakeSource() {
    val backgroundStarted = CompletableDeferred<Unit>()
    val backgroundCancelled = CompletableDeferred<Unit>()
    val releaseBackground = CompletableDeferred<Unit>()
    val started = mutableListOf<PageFetchPriority>()

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        require(pageId == this.pageId)
        started += priority
        if (priority == PageFetchPriority.BACKGROUND) {
            backgroundStarted.complete(Unit)
            try {
                releaseBackground.await()
            } catch (cancelled: CancellationException) {
                backgroundCancelled.complete(Unit)
                throw cancelled
            }
        }
        return OpenedPage(ByteArrayPageStream(ByteArray(32)), 32L, "image/png", null, null)
    }
}

private class ForwardUpgradeablePrioritySource : FakeSource() {
    val forwardStarted = CompletableDeferred<Unit>()
    val forwardCancelled = CompletableDeferred<Unit>()
    val releaseForward = CompletableDeferred<Unit>()
    val started = mutableListOf<PageFetchPriority>()
    val stream = RecordingPriorityPageStream()

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        require(pageId == this.pageId)
        started += priority
        if (priority == PageFetchPriority.FORWARD) {
            forwardStarted.complete(Unit)
            try {
                releaseForward.await()
            } catch (cancelled: CancellationException) {
                forwardCancelled.complete(Unit)
                throw cancelled
            }
        }
        return OpenedPage(stream, 32L, "image/png", null, null)
    }
}

private class RecordingPriorityPageStream : PageByteStream {
    val promotions = mutableListOf<PageFetchPriority>()
    private val bytes = ByteArray(32)
    private var offset = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (this.offset >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - this.offset)
        bytes.copyInto(destination, offset, this.offset, this.offset + count)
        this.offset += count
        return count
    }

    override fun promote(priority: PageFetchPriority) {
        promotions += priority
    }

    override fun close() = Unit
}

private class ImmediateHandoffSource : FakeSource() {
    val visibleStarted = CompletableDeferred<Unit>()
    val visibleCancelled = CompletableDeferred<Unit>()
    val releaseVisible = CompletableDeferred<Unit>()
    val started = mutableListOf<PageFetchPriority>()

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        require(pageId == this.pageId)
        started += priority
        if (priority == PageFetchPriority.VISIBLE) {
            visibleStarted.complete(Unit)
            try {
                releaseVisible.await()
            } catch (cancelled: CancellationException) {
                visibleCancelled.complete(Unit)
                throw cancelled
            }
        }
        return OpenedPage(ByteArrayPageStream(ByteArray(32)), 32L, "image/png", null, null)
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

private class PromotableBlockingPageStream : PageByteStream {
    val readStarted = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()
    val promotions = mutableListOf<PageFetchPriority>()

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        readStarted.complete(Unit)
        releaseRead.await()
        return -1
    }

    override fun promote(priority: PageFetchPriority) {
        promotions += priority
    }

    override fun close() = Unit
}

private class TwoStepPageStream : PageByteStream {
    val firstReadStarted = CompletableDeferred<Unit>()
    val releaseFirstRead = CompletableDeferred<Unit>()
    val secondReadStarted = CompletableDeferred<Unit>()
    private var reads = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        reads += 1
        return if (reads == 1) {
            firstReadStarted.complete(Unit)
            releaseFirstRead.await()
            destination[offset] = 1
            1
        } else {
            secondReadStarted.complete(Unit)
            -1
        }
    }

    override fun close() = Unit
}

private class StreamingPrioritySource(private val stream: PageByteStream) : FakeSource() {
    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        require(pageId == this.pageId)
        openCount.incrementAndGet()
        return OpenedPage(stream, null, "image/png", null, null)
    }
}

private class PreemptiblePrioritySource : ContentSource {
    override val id = SourceId("source")
    private val episodeId = EpisodeId(SeriesId(id, "series"), "episode")
    val warmPageId = PageId(episodeId, "warm")
    val visiblePageId = PageId(episodeId, "visible")
    val warmStarted = CompletableDeferred<Unit>()
    val warmCancelled = CompletableDeferred<Unit>()
    val started = mutableListOf<Pair<PageId, PageFetchPriority>>()
    private val never = CompletableDeferred<Unit>()

    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage =
        openPage(pageId, validation, PageFetchPriority.NORMAL)

    override suspend fun openPage(
        pageId: PageId,
        validation: PageValidation?,
        priority: PageFetchPriority,
    ): OpenedPage {
        started += pageId to priority
        if (pageId == warmPageId) {
            warmStarted.complete(Unit)
            try {
                never.await()
            } catch (cancelled: CancellationException) {
                warmCancelled.complete(Unit)
                throw cancelled
            }
        }
        require(pageId == visiblePageId)
        return OpenedPage(ByteArrayPageStream(ByteArray(32)), 32L, "image/png", null, null)
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> = unsupported()
    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> = unsupported()
    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest = unsupported()
    override suspend fun adjacent(episodeId: EpisodeId): AdjacentEpisodes = unsupported()
    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent): Unit = unsupported()

    private fun <T> unsupported(): T = throw UnsupportedOperationException("Not used by this test")
}
