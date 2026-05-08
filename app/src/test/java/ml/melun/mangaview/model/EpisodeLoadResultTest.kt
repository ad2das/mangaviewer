package ml.melun.mangaview.model

import ml.melun.mangaview.mangaview.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeLoadResultTest {
    @Test
    fun storesCodeAndEpisodes() {
        val episodes = listOf(Manga(7, "episode", "", 1))
        val result = EpisodeLoadResult(0, episodes)

        assertEquals(0, result.resultCode)
        assertEquals(episodes, result.episodes)
    }
}
