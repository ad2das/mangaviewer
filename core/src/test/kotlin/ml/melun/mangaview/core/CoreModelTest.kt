package ml.melun.mangaview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreModelTest {
    @Test
    fun manifestRejectsPagesFromAnotherEpisode() {
        val source = SourceId("source")
        val series = SeriesId(source, "series")
        val episode = EpisodeId(series, "episode")
        val other = EpisodeId(series, "other")
        val page = PageSpec(PageId(other, "p0"), 0)

        assertThrows(IllegalArgumentException::class.java) {
            EpisodeManifest(episode, "Episode", listOf(page))
        }
    }

    @Test
    fun manifestPreservesProviderOpaqueKeys() {
        val source = SourceId("wfwf")
        val series = SeriesId(source, "series/a-b")
        val episode = EpisodeId(series, "chapter-slug-10.5")
        val pageId = PageId(episode, "signed/page?opaque=true")
        val manifest = EpisodeManifest(
            id = episode,
            title = "Chapter",
            pages = listOf(PageSpec(pageId, 0, PageDimensions(800, 12_000))),
        )

        assertEquals(pageId, manifest.pages.single().id)
    }
}
