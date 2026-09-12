package ml.melun.mangaview.ui.library

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.app.SourceOption
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEpisodePriorityTest {
    @Test fun continuationIgnoresHomeTabAndSelectedSourceButHonorsExplicitSeriesSelection() {
        val series = ml.melun.mangaview.data.library.SavedSeries(
            SeriesId(SourceId("wfwf"), "last-read"), "Last read", null, false, 100L)
        val id = EpisodeId(series.id, "7")
        val recent = ml.melun.mangaview.data.library.RecentReading(series, id,
            ml.melun.mangaview.core.PageId.at(id, 3), 123L, 100L)
        val state = LibraryState("", listOf(SourceOption(SourceId("ntk"), "NTK")), SourceId("ntk"),
            destination = MainDestination.LIBRARY,
            saved = ml.melun.mangaview.data.library.UserLibrarySnapshot(recent = listOf(recent)))
        assertEquals(id, mostLikelyContinuation(state))
        assertEquals(id, mostLikelyContinuation(state.copy(destination = MainDestination.SEARCH)))
        org.junit.Assert.assertNull(mostLikelyContinuation(state.copy(content = LibraryContent.Episodes(
            SourceSeries(series.id, "Selected series"), emptyList()))))
    }

    @Test
    fun seriesOpenWarmsTheRememberedEpisodeOfThatSeriesOnly() {
        val series = ml.melun.mangaview.data.library.SavedSeries(
            SeriesId(SourceId("wfwf"), "opened"), "Opened", null, false, 200L)
        val other = ml.melun.mangaview.data.library.SavedSeries(
            SeriesId(SourceId("wfwf"), "other"), "Other", null, false, 100L)
        val openedEpisode = EpisodeId(series.id, "9")
        val otherEpisode = EpisodeId(other.id, "3")
        val saved = ml.melun.mangaview.data.library.UserLibrarySnapshot(recent = listOf(
            ml.melun.mangaview.data.library.RecentReading(other, otherEpisode,
                ml.melun.mangaview.core.PageId.at(otherEpisode, 1), 210L, 200L),
            ml.melun.mangaview.data.library.RecentReading(series, openedEpisode,
                ml.melun.mangaview.core.PageId.at(openedEpisode, 2), 200L, 190L),
        ))

        assertEquals(openedEpisode, recentEpisodeFor(saved, series.id))
        org.junit.Assert.assertNull(recentEpisodeFor(saved, SeriesId(SourceId("wfwf"), "unknown")))
    }

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
    fun quickReadResumesTheRememberedEpisodeForAReturningReader() {
        val series = SourceSeries(
            id = SeriesId(SourceId("test"), "series"),
            title = "series",
        )
        val resumed = EpisodeId(series.id, "12")
        val saved = ml.melun.mangaview.data.library.UserLibrarySnapshot(recent = listOf(
            ml.melun.mangaview.data.library.RecentReading(
                ml.melun.mangaview.data.library.SavedSeries(series.id, series.title, null, false, 100L),
                resumed,
                ml.melun.mangaview.core.PageId.at(resumed, 3),
                200L,
                100L,
            ),
        ))
        val episodes = listOf(episode("12", 12.0), episode("2", 2.0), episode("1", 1.0))

        val picked = quickReadEpisode(
            LibraryState(
                query = "",
                sources = listOf(SourceOption(series.id.sourceId, "test")),
                selectedSourceId = series.id.sourceId,
                saved = saved,
            ),
            series,
            episodes,
        )

        assertEquals("12", picked?.id?.remoteKey)
    }

    private fun episode(key: String, sequence: Double?): SourceEpisode = SourceEpisode(
        id = EpisodeId(SeriesId(SourceId("test"), "series"), key),
        title = key,
        sequenceNumber = sequence,
    )
}
