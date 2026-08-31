package ml.melun.mangaview.source.ntk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NtkPreparedEpisodeStoreTest {
    @Test
    fun concurrentPreparationConvergesOnOneLoad() = runTest {
        val store = NtkPreparedEpisodeStore()
        val episode = episode("a")
        var loads = 0

        val results = List(8) {
            async {
                store.resolve(episode) {
                    loads += 1
                    prepared(episode)
                }
            }
        }.awaitAll()

        assertEquals(1, loads)
        assertEquals(1, results.distinct().size)
        assertNotNull(store.request(PageId.at(episode, 0)))
    }

    @Test
    fun oldestEpisodeIsEvictedAsOneManifestUnit() = runTest {
        val store = NtkPreparedEpisodeStore(maximumEpisodes = 2)
        val first = episode("a")
        val second = episode("b")
        val third = episode("c")
        store.resolve(first) { prepared(first) }
        store.resolve(second) { prepared(second) }
        store.resolve(third) { prepared(third) }

        assertFalse(store.contains(first))
        assertNotNull(store.request(PageId.at(second, 0)))
        assertNotNull(store.request(PageId.at(third, 0)))
    }

    @Test
    fun defaultCapacityRetainsAContinuousForwardRunway() = runTest {
        val store = NtkPreparedEpisodeStore()
        val episodes = List(32) { episode("runway-$it") }
        episodes.forEach { value -> store.resolve(value) { prepared(value) } }

        episodes.forEach { value -> assertNotNull(store.request(PageId.at(value, 0))) }
    }

    @Test
    fun adjacentLoadDoesNotBlockCurrentEpisodeRequest() = runTest {
        val store = NtkPreparedEpisodeStore()
        val current = episode("current")
        val adjacent = episode("adjacent")
        store.resolve(current) { prepared(current) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adjacentLoad = async {
            store.resolve(adjacent) {
                started.complete(Unit)
                release.await()
                prepared(adjacent)
            }
        }
        started.await()

        assertNotNull(withTimeout(100L) { store.request(PageId.at(current, 0)) })
        release.complete(Unit)
        adjacentLoad.await()
    }

    @Test
    fun ownerCancellationLetsAWaitingCallerTakeOwnership() = runTest {
        val store = NtkPreparedEpisodeStore()
        val target = episode("cancelled-owner")
        val ownerStarted = CompletableDeferred<Unit>()
        var loads = 0
        val owner = launch {
            store.resolve(target) {
                loads += 1
                ownerStarted.complete(Unit)
                awaitCancellation()
            }
        }
        ownerStarted.await()
        val waiter = async {
            store.resolve(target) {
                loads += 1
                prepared(target)
            }
        }
        runCurrent()

        owner.cancelAndJoin()

        assertEquals(prepared(target), waiter.await())
        assertEquals(2, loads)
    }

    @Test
    fun failedOwnerRemovesItsFlightForRetry() = runTest {
        val store = NtkPreparedEpisodeStore()
        val target = episode("failed-owner")
        var loads = 0

        val failure = runCatching {
            store.resolve(target) {
                loads += 1
                error("manifest failure")
            }
        }.exceptionOrNull()
        val recovered = store.resolve(target) {
            loads += 1
            prepared(target)
        }

        assertTrue(failure is IllegalStateException)
        assertEquals(prepared(target), recovered)
        assertEquals(2, loads)
    }

    private fun episode(key: String): EpisodeId = EpisodeId(
        SeriesId(SourceId("ntk"), "/webtoon/work"),
        "/webtoon/work/$key",
    )

    private fun prepared(episodeId: EpisodeId): NtkPreparedEpisode {
        val page = PageId.at(episodeId, 0)
        return NtkPreparedEpisode(
            pages = listOf(PageSpec(page, 0)),
            requests = mapOf(page to NtkPageRequest("https://image.example/$page")),
        )
    }
}
