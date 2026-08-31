package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class WfwfManifestStoreTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledOwnerIsRemovedAndAnIndependentWaiterReownsTheManifestFetch() = runTest {
        val episode = EpisodeId(SeriesId(SourceId("wfwf"), "webtoon:9"), "3")
        val payload = payload(episode)
        val firstStarted = CompletableDeferred<Unit>()
        var fetchCount = 0
        val store = WfwfManifestStore(2) {
            fetchCount += 1
            if (fetchCount == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            payload
        }

        val owner = async { store.load(episode) }
        firstStarted.await()
        val waiter = async { store.load(episode) }
        runCurrent()
        owner.cancelAndJoin()

        assertEquals(payload, waiter.await().payload)
        assertEquals(payload, store.load(episode).payload)
        assertEquals(2, fetchCount)
    }

    private fun payload(episodeId: EpisodeId): WfwfManifestPayload {
        val pageId = PageId.at(episodeId, 0)
        return WfwfManifestPayload(
            manifest = EpisodeManifest(
                id = episodeId,
                title = "3화",
                pages = listOf(PageSpec(pageId, 0)),
            ),
            pageUrls = mapOf(pageId to "https://images.example/3.webp"),
        )
    }
}
