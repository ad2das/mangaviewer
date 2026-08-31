package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceEpisode
import org.junit.Assert.assertEquals
import org.junit.Test

class WfwfCatalogStoreTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingTheFlightOwnerLetsAnUncancelledWaiterRetryAndPopulateTheCache() = runTest {
        val series = SeriesId(SourceId("wfwf"), "webtoon:77")
        val catalog = listOf(SourceEpisode(EpisodeId(series, "1"), "1화"))
        val firstFetchStarted = CompletableDeferred<Unit>()
        var fetchCount = 0
        val store = WfwfCatalogStore {
            fetchCount += 1
            if (fetchCount == 1) {
                firstFetchStarted.complete(Unit)
                awaitCancellation()
            }
            catalog
        }

        val owner = async { store.load(series, refresh = false) }
        firstFetchStarted.await()
        val waiter = async { store.load(series, refresh = false) }
        runCurrent()

        owner.cancelAndJoin()

        assertEquals(catalog, waiter.await())
        assertEquals(catalog, store.load(series, refresh = false))
        assertEquals(2, fetchCount)
    }
}
