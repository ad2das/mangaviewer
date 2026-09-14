package ml.melun.mangaview.source.goodtoon

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class GoodtoonManifestStoreTest {
    private fun payload(episodeSlug: String, urls: List<String>): GoodtoonManifestPayload {
        val episodeId = goodtoonEpisodeId("gt-1", episodeSlug)
        val pageIds = urls.indices.map { PageId.at(episodeId, it) }
        return GoodtoonManifestPayload(
            manifest = EpisodeManifest(
                id = episodeId,
                title = episodeSlug,
                pages = pageIds.mapIndexed { index, pageId -> PageSpec(pageId, index) },
                previousEpisodeId = null,
                nextEpisodeId = null,
            ),
            pageUrls = pageIds.zip(urls).toMap(),
        )
    }

    @Test
    fun `pages are addressable only after a manifest is loaded`() = runTest {
        val store = GoodtoonManifestStore(capacity = 2) { payload(it.remoteKey, listOf("https://img/a.jpg")) }
        val episodeId = goodtoonEpisodeId("gt-1", "1")
        assertEquals(GoodtoonPageLookup.MissingEpisode, store.page(PageId.at(episodeId, 0)))
        val entry = store.load(episodeId)
        assertEquals("https://img/a.jpg", entry.payload.pageUrls[PageId.at(episodeId, 0)])
        assertEquals(entry.revision, (store.page(PageId.at(episodeId, 0)) as GoodtoonPageLookup.Found).revision)
        assertEquals(GoodtoonPageLookup.MissingPage(entry.revision), store.page(PageId.at(episodeId, 5)))
    }

    @Test
    fun `refreshing a stale revision replaces the entry once per stale revision`() = runTest {
        var version = 1
        val store = GoodtoonManifestStore(capacity = 2) {
            payload(it.remoteKey, listOf("https://img/$version.jpg"))
        }
        val episodeId = goodtoonEpisodeId("gt-1", "1")
        val first = store.load(episodeId)
        assertEquals(1L, first.revision)

        val refetched = store.refreshIfCurrent(episodeId, first.revision)
        assertEquals(2L, refetched.revision)
        assertEquals("https://img/1.jpg", refetched.payload.pageUrls[PageId.at(episodeId, 0)])

        version = 2
        val newest = store.refreshIfCurrent(episodeId, refetched.revision)
        assertEquals(3L, newest.revision)
        assertEquals("https://img/2.jpg", newest.payload.pageUrls[PageId.at(episodeId, 0)])

        val alreadyNewer = store.refreshIfCurrent(episodeId, first.revision)
        assertEquals(3L, alreadyNewer.revision)
    }

    @Test
    fun `capacity eviction drops the least recently used entry`() = runTest {
        var fetches = 0
        val store = GoodtoonManifestStore(capacity = 1) {
            fetches += 1
            payload(it.remoteKey, listOf("https://img/${it.remoteKey}.jpg"))
        }
        store.load(goodtoonEpisodeId("gt-1", "1"))
        store.load(goodtoonEpisodeId("gt-1", "2"))
        store.load(goodtoonEpisodeId("gt-1", "1"))
        assertEquals(3, fetches)
    }
}
