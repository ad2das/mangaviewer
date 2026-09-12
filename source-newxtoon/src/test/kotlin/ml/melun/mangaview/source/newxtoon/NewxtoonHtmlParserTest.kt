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

    @Test fun parsesCardStatusFromEpisodeLineWithoutGenreTraps() {
        val html = """
            <a href="https://newxtoon1.com/comics/1" aria-label="끝난 작품, 일반만화, 탑툰">
              <div class="cover-shell">
                <img class="cover-image" src="https://cdn.test/1.webp">
                <div class="absolute left-2 top-2 z-10 flex gap-1">
                  <span class="rounded-full bg-black/75 px-2 py-1 text-[10px] font-bold leading-none text-white">로맨스</span>
                </div>
              </div>
              <h3>끝난 작품</h3>
              <p class="mt-2 truncate text-xs font-semibold text-ink" title="43화(완결)">43화(완결)</p>
            </a>
            <a href="https://newxtoon1.com/comics/2" aria-label="연재 작품, 일반만화, 네이버">
              <div class="cover-shell">
                <img class="cover-image" src="https://cdn.test/2.webp">
                <div class="absolute left-2 top-2 z-10 flex gap-1">
                  <span class="rounded-full bg-black/75 px-2 py-1 text-[10px] font-bold leading-none text-white">완결로맨스</span>
                </div>
              </div>
              <h3>연재 작품</h3>
              <p class="mt-2 truncate text-xs font-semibold text-ink">12화</p>
            </a>
        """.trimIndent()
        val cards = parser.seriesCards(html)
        assertEquals(SeriesStatus.COMPLETED, cards.first { it.id == "1" }.status)
        assertEquals("a 완결로맨스 genre tag is not a status", SeriesStatus.ONGOING,
            cards.first { it.id == "2" }.status)
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
