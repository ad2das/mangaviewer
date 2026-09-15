package ml.melun.mangaview.ui.library

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeReadStateTest {
    private val series = SeriesId(SourceId("ntk"), "series-under-test")

    private fun episode(key: String, sequence: Double? = null, published: Long? = null) = SourceEpisode(
        id = EpisodeId(series, key),
        title = "episode $key",
        publishedAtEpochMillis = published,
        sequenceNumber = sequence,
    )

    @Test
    fun rememberedEpisodeIsMarkedForResume() {
        val remembered = episode("12", sequence = 12.0)
        assertEquals(EpisodeReadState.RESUME, episodeReadState(remembered, remembered))
    }

    @Test
    fun olderSequenceCountsAsRead() {
        val resume = episode("12", sequence = 12.0)
        assertEquals(EpisodeReadState.READ, episodeReadState(episode("8", sequence = 8.0), resume))
    }

    @Test
    fun newerSequenceStaysUnmarked() {
        val resume = episode("12", sequence = 12.0)
        assertNull(episodeReadState(episode("15", sequence = 15.0), resume))
    }

    @Test
    fun publicationDateOrdersWhenSequenceIsMissing() {
        val resume = episode("b", published = 2_000L)
        assertEquals(EpisodeReadState.READ, episodeReadState(episode("a", published = 1_000L), resume))
        assertNull(episodeReadState(episode("c", published = 3_000L), resume))
    }

    @Test
    fun unorderableEpisodesStayUnmarked() {
        val resume = episode("b", published = 2_000L)
        assertNull(episodeReadState(episode("a"), resume))
    }

    @Test
    fun withoutRememberedEpisodeNothingIsMarked() {
        assertNull(episodeReadState(episode("1", sequence = 1.0), null))
    }
}
