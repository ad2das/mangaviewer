package ml.melun.mangaview.source.ntk

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceEpisode
import org.junit.Assert.assertEquals
import org.junit.Test

class NtkForwardEpisodeSequenceTest {
    private val series = SeriesId(SourceId("ntk"), "/manhwa/series")

    @Test
    fun returnsViewerNextDirectionFromNewestFirstCatalog() {
        val episodes = listOf("e5", "e4", "e3", "e2", "e1").map(::episode)

        assertEquals(
            listOf("e2", "e3", "e4"),
            ntkForwardEpisodeIds(episodes, id("e1"), limit = 3).map(EpisodeId::remoteKey),
        )
    }

    @Test
    fun clampsAtNewestAndRejectsMissingCurrent() {
        val episodes = listOf("e3", "e2", "e1").map(::episode)

        assertEquals(
            listOf("e2", "e3"),
            ntkForwardEpisodeIds(episodes, id("e1"), limit = 8).map(EpisodeId::remoteKey),
        )
        assertEquals(emptyList<EpisodeId>(), ntkForwardEpisodeIds(episodes, id("missing"), 8))
        assertEquals(emptyList<EpisodeId>(), ntkForwardEpisodeIds(episodes, id("e3"), 8))
    }

    private fun episode(key: String) = SourceEpisode(id(key), key)
    private fun id(key: String) = EpisodeId(series, key)
}
