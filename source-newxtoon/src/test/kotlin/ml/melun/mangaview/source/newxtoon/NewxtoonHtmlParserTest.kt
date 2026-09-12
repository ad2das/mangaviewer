package ml.melun.mangaview.source.newxtoon

import ml.melun.mangaview.source.SeriesStatus
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

    @Test fun parsesCatalogTitlesWithoutViewCounts() {
        val cards = parser.seriesCards(fixture("comics.html"))
        assertTrue(cards.any { it.id == "164" })
        val target = cards.first { it.id == "164" }
        assertEquals("나는 최약체 드래곤 테이머", target.title)
        assertTrue(cards.none { it.title.contains("16,567") })
        assertTrue(cards.all { it.thumbnailUrl?.startsWith("http") == true })
    }

    @Test fun parsesGenresWithLabels() {
        val genres = parser.genres(fixture("comics.html"))
        assertTrue("expected many genres", genres.size > 10)
        assertTrue(genres.any { it.key == "genre:1" && it.label.isNotBlank() })
        assertEquals(genres.size, genres.map { it.key }.toSet().size)
    }

    @Test fun parsesSeriesStatusDescriptionAndAuthors() {
        val details = parser.seriesDetails(fixture("series.html"))
        assertEquals(SeriesStatus.ONGOING, details.status)
        assertTrue("synopsis must be parsed", details.description?.contains("전세사기") == true)
        assertEquals("평형, 석지", details.authors)
    }

    @Test fun parsesCardSubtitlesFromAriaLabel() {
        val cards = parser.seriesCards(fixture("comics.html"))
        val target = cards.first { it.id == "334" }
        assertTrue("platform label must be kept", target.subtitle?.contains("탑툰") == true)
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
