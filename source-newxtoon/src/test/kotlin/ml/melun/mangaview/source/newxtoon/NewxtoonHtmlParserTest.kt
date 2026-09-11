package ml.melun.mangaview.source.newxtoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewxtoonHtmlParserTest {
    private val parser = NewxtoonHtmlParser("https://newxtoon1.com")

    private fun fixture(name: String): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("newxtoon/$name"),
    ) { "Missing fixture $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun parsesCatalogCardsAndPagination() {
        val html = fixture("comics.html")
        val cards = parser.seriesCards(html)
        assertTrue("catalog fixture produced no cards", cards.size > 10)
        assertTrue(cards.all { it.id.toIntOrNull() != null && it.title.isNotBlank() })
        assertTrue("first page must continue to page 2", parser.nextPage(html, 1) == 2)
    }

    @Test fun parsesChaptersWithIdentifiers() {
        val chapters = parser.chapters(fixture("series.html"))
        assertTrue(chapters.isNotEmpty())
        assertTrue("chapter 1062717 must be parsed", chapters.any { it.id == "1062717" })
        assertTrue(chapters.all { it.title.isNotBlank() })
    }

    @Test fun parsesGenresWithLabels() {
        val genres = parser.genres(fixture("comics.html"))
        assertTrue("expected many genres", genres.size > 10)
        assertTrue(genres.any { it.key == "genre:1" && it.label.isNotBlank() })
        assertEquals(genres.size, genres.map { it.key }.toSet().size)
    }

    @Test fun parsesReaderPagesInOrderWithDimensions() {
        val pages = parser.pages(fixture("chapter.html"))
        assertTrue("expected many reader pages", pages.size > 20)
        assertTrue(pages.all { it.url.startsWith("https://") })
        assertTrue(pages.any { it.url.contains("chapters/live_with_teacher") })
        assertEquals("dimensions must be known for the first page", true,
            pages.first().width != null && pages.first().height != null)
    }
}
