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

    private fun episode(key: String, sequence: Double?): SourceEpisode = SourceEpisode(
        id = EpisodeId(SeriesId(SourceId("test"), "series"), key),
        title = key,
        sequenceNumber = sequence,
    )
}
