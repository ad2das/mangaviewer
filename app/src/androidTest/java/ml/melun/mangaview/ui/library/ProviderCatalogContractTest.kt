package ml.melun.mangaview.ui.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCatalogContractTest {
    @Test
    fun everyProviderKindExposesItsFullGenreSet() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ViewerApplication).graph
        val ntk = graph.sources.require(SourceId("ntk"))
        val wfwf = graph.sources.require(SourceId("wfwf"))

        val sets = listOf(
            "ntk-webtoon" to ntk.genres(SeriesKind.WEBTOON),
            "ntk-comic" to ntk.genres(SeriesKind.COMIC),
            "wfwf-webtoon" to wfwf.genres(SeriesKind.WEBTOON),
            "wfwf-comic" to wfwf.genres(SeriesKind.COMIC),
        )

        assertTrue("NTK webtoon tags were truncated", sets[0].second.size >= 21)
        assertTrue("NTK comic genres were truncated", sets[1].second.size >= 29)
        assertEquals(21, sets[2].second.size)
        assertEquals(34, sets[3].second.size)
        sets.forEach { (name, genres) ->
            assertEquals("$name has duplicate genre keys", genres.size, genres.distinctBy { it.key }.size)
            assertTrue("$name contains a blank genre", genres.all { it.key.isNotBlank() && it.label.isNotBlank() })
        }
    }

    @Test
    fun liveCatalogsNeverExposeNavigationActionsAsEpisodes() = runBlocking {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as ViewerApplication).graph
        val forbidden = listOf("최신화 보기", "첫화부터", "처음부터", "정주행", "이어보기", "전체보기")
        listOf(SourceId("ntk"), SourceId("wfwf")).forEach { sourceId ->
            val source = graph.sources.require(sourceId)
            listOf(SeriesKind.WEBTOON, SeriesKind.COMIC).forEach { kind ->
                val series = source.catalog(CatalogQuery(kind, CatalogOrder.POPULAR)).items.first()
                val episodes = source.episodes(series.id).items
                assertTrue("$sourceId/$kind returned no episodes", episodes.isNotEmpty())
                assertFalse(
                    "$sourceId/$kind exposed a navigation action as an episode",
                    episodes.any { episode -> forbidden.any(episode.title::contains) },
                )
                assertEquals(episodes.size, episodes.distinctBy { it.id }.size)
            }
        }
    }
}
