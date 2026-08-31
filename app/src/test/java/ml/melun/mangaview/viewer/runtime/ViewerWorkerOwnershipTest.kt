package ml.melun.mangaview.viewer.runtime

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.viewer.EpisodeOperationToken
import ml.melun.mangaview.viewer.OperationToken
import ml.melun.mangaview.viewer.VerifiedPageRef
import ml.melun.mangaview.viewer.WorkKind
import ml.melun.mangaview.viewer.WorkPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerWorkerOwnershipTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun replacementWaitsForThePreviousPageOwnerToFinish() = runTest {
        val ownership = ViewerWorkerOwnership()
        val pageId = pageId(episode = 0, page = 0)
        val inCriticalSection = AtomicInteger()
        val maximumConcurrency = AtomicInteger()

        repeat(200) { index ->
            val token = token(pageId, WorkKind.DECODE, index + 1L)
            val job = ownership.registerPage(token) { predecessor ->
                launch(start = CoroutineStart.LAZY) {
                    predecessor?.join()
                    val active = inCriticalSection.incrementAndGet()
                    maximumConcurrency.accumulateAndGet(active, ::maxOf)
                    try {
                        awaitCancellation()
                    } finally {
                        inCriticalSection.decrementAndGet()
                    }
                }
            }
            requireNotNull(job).start()
            runCurrent()
            assertEquals(1, ownership.snapshot().activePageSlots)
        }

        ownership.pauseDecodes()
        runCurrent()

        assertEquals(1, maximumConcurrency.get())
        assertEquals(0, inCriticalSection.get())
        assertEquals(0, ownership.snapshot().activePageSlots)
        assertEquals(0, ownership.snapshot().trackedJobs)
        assertFalse(ownership.snapshot().decodeEnabled)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun pausedDecodesAreRejectedWhileFetchesContinue() = runTest {
        val ownership = ViewerWorkerOwnership()
        ownership.pauseDecodes()
        val decodeCreates = AtomicInteger()
        val fetchStarted = AtomicInteger()
        val pageId = pageId(episode = 1, page = 3)

        val rejected = ownership.registerPage(token(pageId, WorkKind.DECODE, 1L)) {
            decodeCreates.incrementAndGet()
            launch(start = CoroutineStart.LAZY) { Unit }
        }
        val fetch = ownership.registerPage(token(pageId, WorkKind.FETCH, 2L)) { predecessor ->
            launch(start = CoroutineStart.LAZY) {
                predecessor?.join()
                fetchStarted.incrementAndGet()
            }
        }
        requireNotNull(fetch).start()
        runCurrent()

        assertNull(rejected)
        assertEquals(0, decodeCreates.get())
        assertEquals(1, fetchStarted.get())
        assertEquals(0, ownership.snapshot().activePageSlots)

        ownership.resumeDecodes()
        val resumed = ownership.registerPage(token(pageId, WorkKind.DECODE, 3L)) {
            launch(start = CoroutineStart.LAZY) { Unit }
        }
        requireNotNull(resumed).start()
        runCurrent()
        assertTrue(ownership.snapshot().decodeEnabled)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun shutdownClosesRegistrationAndDrainsEveryTrackedJob() = runTest {
        val ownership = ViewerWorkerOwnership()
        val page = pageId(episode = 2, page = 7)
        val pageJob = ownership.registerPage(token(page, WorkKind.FETCH, 1L)) {
            launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        }
        val episodeJob = ownership.registerEpisode(episodeToken(2, 3, 1)) {
            launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        }
        requireNotNull(pageJob).start()
        requireNotNull(episodeJob).start()
        runCurrent()

        val shutdownJobs = ownership.beginShutdown()
        var createdAfterShutdown = false
        val rejected = ownership.registerPage(token(page, WorkKind.FETCH, 2L)) {
            createdAfterShutdown = true
            launch(start = CoroutineStart.LAZY) { Unit }
        }
        shutdownJobs.joinAll()
        ownership.finishShutdown()

        assertNull(rejected)
        assertFalse(createdAfterShutdown)
        assertEquals(0, ownership.snapshot().activePageSlots)
        assertEquals(0, ownership.snapshot().activeEpisodeSlots)
        assertEquals(0, ownership.snapshot().trackedJobs)
        assertTrue(ownership.snapshot().stopping)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun tenEpisodeChurnLeavesNoOwnerOrJobHistory() = runTest {
        val ownership = ViewerWorkerOwnership()
        var sequence = 1L

        repeat(10) { episode ->
            repeat(50) { page ->
                val id = pageId(episode, page)
                repeat(3) {
                    val token = token(id, WorkKind.DECODE, sequence++)
                    ownership.registerPage(token) { predecessor ->
                        launch(start = CoroutineStart.LAZY) {
                            predecessor?.join()
                        }
                    }?.start()
                }
            }
            ownership.registerEpisode(episodeToken(episode, episode + 1, 1)) { predecessor ->
                launch(start = CoroutineStart.LAZY) { predecessor?.join() }
            }?.start()
            runCurrent()
        }

        val snapshot = ownership.snapshot()
        assertEquals(0, snapshot.activePageSlots)
        assertEquals(0, snapshot.activeDecodeSlots)
        assertEquals(0, snapshot.activeEpisodeSlots)
        assertEquals(0, snapshot.trackedJobs)
    }

    @Test
    fun verifiedHandoffsAreBoundedReusableAndRejectMismatchedContent() {
        val store = VerifiedPageHandoffStore(capacity = 8)
        repeat(500) { index -> store.remember(cached(index)) }

        assertEquals(8, store.size())
        val newest = cached(499)
        assertNull(store.find(newest.pageId, verified(newest, sha = "different")))
        assertEquals(7, store.size())

        val next = cached(498)
        assertEquals(next, store.find(next.pageId, verified(next)))
        assertEquals(next, store.find(next.pageId, verified(next)))
        assertEquals(7, store.size())
    }

    private fun token(pageId: PageId, kind: WorkKind, sequence: Long) = OperationToken(
        generation = 1L,
        pageId = pageId,
        kind = kind,
        attempt = 1,
        operationSequence = sequence,
        priority = WorkPriority.HARD,
    )

    private fun episodeToken(from: Int, target: Int, attempt: Int): EpisodeOperationToken =
        EpisodeOperationToken(
            generation = 1L,
            fromEpisodeId = episodeId(from),
            targetEpisodeId = episodeId(target),
            attempt = attempt,
        )

    private fun cached(index: Int): CachedPage {
        val pageId = pageId(index / 50, index % 50)
        return CachedPage(
            pageId = pageId,
            file = File("page-$index.webp"),
            byteCount = 1_000L + index,
            sha256 = "sha-$index",
            mediaType = "image/webp",
            dimensions = PageDimensions(1_000, 2_000 + index),
        )
    }

    private fun verified(cached: CachedPage, sha: String = cached.sha256) = VerifiedPageRef(
        cacheKey = "cache-${cached.pageId.remoteKey}",
        byteCount = cached.byteCount,
        sha256 = sha,
        dimensions = cached.dimensions,
    )

    private fun pageId(episode: Int, page: Int) = PageId(episodeId(episode), "page-$page")

    private fun episodeId(index: Int) = EpisodeId(SERIES_ID, "episode-$index")

    private companion object {
        val SERIES_ID = SeriesId(SourceId("source"), "series")
    }
}
