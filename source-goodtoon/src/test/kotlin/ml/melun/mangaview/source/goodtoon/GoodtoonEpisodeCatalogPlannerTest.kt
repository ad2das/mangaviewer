package ml.melun.mangaview.source.goodtoon

import java.net.URI
import ml.melun.mangaview.engine.api.SourceDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class GoodtoonEpisodeCatalogPlannerTest {
    private val planner = GoodtoonEpisodeCatalogPlanner("test-agent")
    private val series = goodtoonSeriesId("gt-21840")
    private val fragmentUrl = URI("https://www.goodtoon004.com/manga/gt-21840/ajax/chapters/?t=1")

    @Test
    fun `request uses the ajax chapter route`() {
        val request = planner.request(series, URI("https://www.goodtoon004.com"), 1)
        assertEquals(fragmentUrl.toString(), request.url)
    }

    @Test
    fun `parse returns the chapter fragment newest first`() {
        val document = SourceDocument(fragmentUrl, GoodtoonFixtures.text("chapters.html").toByteArray())
        val page = planner.parse(series, document)
        assertEquals(51, page.episodes.size)
        assertEquals("51", page.episodes.first().id.remoteKey)
        assertEquals("1", page.episodes.last().id.remoteKey)
    }

    @Test
    fun `merge deduplicates overlapped pages`() {
        val document = SourceDocument(fragmentUrl, GoodtoonFixtures.text("chapters.html").toByteArray())
        val page = planner.parse(series, document)
        val merged = planner.merge(listOf(page, page))
        assertEquals(51, merged.size)
        assertEquals("51", merged.first().id.remoteKey)
    }
}
