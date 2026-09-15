package ml.melun.mangaview.source.goodtoon

import java.net.URI
import ml.melun.mangaview.engine.api.SourceDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodtoonEpisodeCatalogPlannerTest {
    private val planner = GoodtoonEpisodeCatalogPlanner("test-agent")
    private val series = goodtoonSeriesId("gt-21840")
    private val fragmentUrl = URI("https://www.goodtoon004.com/manga/gt-21840/ajax/chapters/?t=1")

    private fun fragment(name: String, page: Int): SourceDocument = SourceDocument(
        URI("https://www.goodtoon004.com/manga/gt-21840/ajax/chapters/?t=$page"),
        GoodtoonFixtures.text(name).toByteArray(),
    )

    @Test
    fun `request uses the ajax chapter route`() {
        val request = planner.request(series, URI("https://www.goodtoon004.com"), 1)
        assertEquals(fragmentUrl.toString(), request.url)
    }

    @Test
    fun `request carries the provider page number`() {
        val request = planner.request(series, URI("https://www.goodtoon004.com"), 3)
        assertEquals("https://www.goodtoon004.com/manga/gt-21840/ajax/chapters/?t=3", request.url)
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

    @Test
    fun `merge concatenates pages in provider order and keeps the first occurrence`() {
        val page1 = planner.parse(series, fragment("chapters-page1.html", 1))
        val page2 = planner.parse(series, fragment("chapters-page2.html", 2))
        val merged = planner.merge(listOf(page1, page2))
        assertEquals(listOf("51", "50", "49", "48", "47", "46", "1"), merged.map { it.id.remoteKey })
        assertEquals(51.0, merged.first().sequenceNumber!!, 0.0)
        assertEquals("마왕의 빛나는 별 1화", merged.last().title)
        assertEquals(1.0, merged.last().sequenceNumber!!, 0.0)
    }

    @Test
    fun `a page without unseen episodes terminates the walk`() {
        val page1 = planner.parse(series, fragment("chapters-page1.html", 1))
        val page2 = planner.parse(series, fragment("chapters-page2.html", 2))
        val accumulator = GoodtoonEpisodeCatalogAccumulator()
        assertTrue(accumulator.absorb(page1.episodes))
        assertTrue(accumulator.absorb(page2.episodes))
        assertFalse(accumulator.absorb(page1.episodes))
        assertFalse(accumulator.absorb(page2.episodes))
        assertEquals(listOf("51", "50", "49", "48", "47", "46", "1"), accumulator.episodes().map { it.id.remoteKey })
    }
}
