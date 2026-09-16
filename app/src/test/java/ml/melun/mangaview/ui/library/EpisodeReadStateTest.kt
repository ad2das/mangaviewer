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
        assertEquals(EpisodeReadState.RESUME, episodeReadState(remembered, remembered, emptySet()))
    }

    @Test
    fun onlyOpenedEpisodesAreMarkedRead() {
        val resume = episode("12", sequence = 12.0)
        val read = setOf(resume.id, EpisodeId(series, "3"))
        assertEquals(EpisodeReadState.READ, episodeReadState(episode("3", sequence = 3.0), resume, read))
        assertNull(episodeReadState(episode("8", sequence = 8.0), resume, read))
    }

    @Test
    fun sequenceOrderNoLongerMarksEarlierEpisodesRead() {
        val resume = episode("105", sequence = 105.0)
        assertNull(episodeReadState(episode("104", sequence = 104.0), resume, emptySet()))
        assertEquals(
            EpisodeReadState.READ,
            episodeReadState(episode("104", sequence = 104.0), resume, setOf(EpisodeId(series, "104"))),
        )
    }

    @Test
    fun readMarkWorksWithoutOrderingMetadata() {
        val read = setOf(EpisodeId(series, "a"))
        assertEquals(EpisodeReadState.READ, episodeReadState(episode("a"), null, read))
    }

    @Test
    fun resumeBadgeWinsOverReadMark() {
        val resume = episode("12", sequence = 12.0)
        assertEquals(EpisodeReadState.RESUME, episodeReadState(resume, resume, setOf(resume.id)))
    }

    @Test
    fun withoutReadMarksOrResumeNothingIsMarked() {
        assertNull(episodeReadState(episode("1", sequence = 1.0), null, emptySet()))
    }
}
