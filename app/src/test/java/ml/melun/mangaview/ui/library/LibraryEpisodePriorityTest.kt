package ml.melun.mangaview.ui.library

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.app.SourceOption
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.PageFetchPriority
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEpisodePriorityTest {
    @Test
    fun firstTimeReaderWarmsTheEarliestEpisodeEvenWhenCatalogIsNewestFirst() {
        val episodes = listOf(episode("12", 12.0), episode("2", 2.0), episode("1", 1.0))

        assertEquals("1", firstEpisode(episodes)?.id?.remoteKey)
    }

    @Test
    fun providerOrderFallsBackToItsOldestFinalEntryWhenMetadataIsUnavailable() {
        val episodes = listOf(episode("latest", null), episode("first", null))

        assertEquals("first", firstEpisode(episodes)?.id?.remoteKey)
    }

    @Test
    fun quickReadUsesTheSameFirstEpisodeAsTheWarmerForANewSeries() {
        val series = SourceSeries(
            id = SeriesId(SourceId("test"), "series"),
            title = "series",
        )
        val episodes = listOf(episode("12", 12.0), episode("2", 2.0), episode("1", 1.0))

        assertEquals(
            "1",
            quickReadEpisode(
                LibraryState(
                    query = "",
                    sources = listOf(SourceOption(series.id.sourceId, "test")),
                    selectedSourceId = series.id.sourceId,
                ),
                series,
                episodes,
            )?.id?.remoteKey,
        )
    }

    @Test
    fun warmerTargetsTheSavedPageAndRejectsAStalePageIdentity() {
        val episode = episode("1", 1.0).id
        val pages = List(5) { PageSpec(PageId.at(episode, it), it) }
        val manifest = EpisodeManifest(episode, "episode", pages)

        val saved = ReadingPosition(pages[2].id, 900L)
        assertEquals(pages[2].id, warmTargetPage(manifest, saved))
        assertEquals(
            pages[0].id,
            warmTargetPage(
                manifest,
                ReadingPosition(PageId.at(episode("stale", 2.0).id, 2), 900L),
            ),
        )
    }

    @Test
    fun warmerSelectsTheAnchorAndUpToFiveForwardPagesAsOneOpeningGroup() {
        val episode = episode("1", 1.0).id
        val pages = List(6) { PageSpec(PageId.at(episode, it), it) }
        val manifest = EpisodeManifest(episode, "episode", pages)

        assertEquals(
            listOf(pages[2].id, pages[3].id, pages[4].id, pages[5].id),
            warmPageOrder(manifest, ReadingPosition(pages[2].id, 900L)),
        )
        assertEquals(
            listOf(pages[5].id),
            warmPageOrder(manifest, ReadingPosition(pages[5].id, 900L)),
        )
        assertEquals(
            pages.map { it.id },
            warmPageOrder(manifest, null),
        )
    }

    @Test
    fun warmerGivesOnlyTheLandingPageTransportFocus() {
        assertEquals(PageFetchPriority.FOCUS, warmPagePriority(0))
        assertEquals(PageFetchPriority.FORWARD, warmPagePriority(1))
        assertEquals(PageFetchPriority.FORWARD, warmPagePriority(2))
    }

    @Test
    fun adjacentWarmupRequiresTheEntireLikelyForwardTail() {
        val episode = episode("1", 1.0).id
        val pages = List(8) { PageSpec(PageId.at(episode, it), it) }
        val manifest = EpisodeManifest(episode, "episode", pages)

        assertEquals(false, warmCoversForwardTail(manifest, pages.take(6).map { it.id }))
        assertEquals(true, warmCoversForwardTail(manifest, pages.drop(2).map { it.id }))
    }

    private fun episode(key: String, sequence: Double?): SourceEpisode = SourceEpisode(
        id = EpisodeId(SeriesId(SourceId("test"), "series"), key),
        title = key,
        sequenceNumber = sequence,
    )
}
