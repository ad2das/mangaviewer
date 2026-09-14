package ml.melun.mangaview.source.goodtoon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import ml.melun.mangaview.source.SourceEpisode
import org.junit.Assert.assertEquals
import org.junit.Test

class GoodtoonCatalogStoreTest {
    private val series = goodtoonSeriesId("gt-1")
    private val episodes = listOf(SourceEpisode(goodtoonEpisodeId("gt-1", "1"), "1화"))

    @Test
    fun `cached loads skip the fetch and refresh refetches`() = runTest {
        var fetches = 0
        val store = GoodtoonCatalogStore {
            fetches += 1
            episodes
        }
        assertEquals(episodes, store.load(series, refresh = true))
        assertEquals(1, fetches)
        assertEquals(episodes, store.load(series, refresh = false))
        assertEquals(1, fetches)
        assertEquals(episodes, store.load(series, refresh = true))
        assertEquals(2, fetches)
    }

    @Test
    fun `concurrent loads share one flight`() = runTest {
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val store = GoodtoonCatalogStore {
            fetches += 1
            gate.await()
            episodes
        }
        val first = async { store.load(series, refresh = false) }
        val second = async { store.load(series, refresh = false) }
        repeat(5) { yield() }
        gate.complete(Unit)
        assertEquals(episodes, first.await())
        assertEquals(episodes, second.await())
        assertEquals(1, fetches)
    }
}
