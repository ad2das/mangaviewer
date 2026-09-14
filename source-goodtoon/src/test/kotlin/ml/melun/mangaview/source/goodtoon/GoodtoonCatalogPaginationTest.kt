package ml.melun.mangaview.source.goodtoon

import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodtoonCatalogPaginationTest {
    private val fantasy = SourceGenre("genre:fantasy", "판타지")

    @Test
    fun `every order resolves to the single provider catalog route`() {
        assertEquals("/ongoing/", GoodtoonCatalogPagination.path(CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.LATEST), 1))
        assertEquals("/ongoing/", GoodtoonCatalogPagination.path(CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.POPULAR), 1))
        assertEquals("/ongoing/", GoodtoonCatalogPagination.path(CatalogQuery(SeriesKind.COMIC, CatalogOrder.NEW), 1))
    }

    @Test
    fun `completed status filter selects the end route`() {
        val query = CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.LATEST, statusFilter = SeriesStatus.COMPLETED)
        assertEquals("/end/", GoodtoonCatalogPagination.path(query, 1))
    }

    @Test
    fun `genre and page build the shared query string`() {
        val query = CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.LATEST, genre = fantasy)
        assertEquals("/ongoing/?genre=fantasy", GoodtoonCatalogPagination.path(query, 1))
        assertEquals("/ongoing/?genre=fantasy&pg=3", GoodtoonCatalogPagination.path(query, 3))
    }

    @Test
    fun `invalid genre key is rejected`() {
        val query = CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.LATEST, genre = SourceGenre("t3:1", "1"))
        assertThrows(IllegalArgumentException::class.java) { GoodtoonCatalogPagination.path(query, 1) }
    }

    @Test
    fun `cursor is a canonical positive page number`() {
        assertEquals(1, GoodtoonCatalogPagination.page(null))
        assertEquals(4, GoodtoonCatalogPagination.page("4"))
        assertThrows(IllegalArgumentException::class.java) { GoodtoonCatalogPagination.page("0") }
        assertThrows(IllegalArgumentException::class.java) { GoodtoonCatalogPagination.page("02") }
        assertThrows(IllegalArgumentException::class.java) { GoodtoonCatalogPagination.page("x") }
    }

    @Test
    fun `catalog pagination advances to the next observed page`() {
        val document = GoodtoonFixtures.document("catalog-ongoing.html")
        assertEquals(
            "2",
            GoodtoonCatalogPagination.nextPageCursor(document, "/ongoing/", 1),
        )
        assertEquals(
            "3",
            GoodtoonCatalogPagination.nextPageCursor(document, "/ongoing/", 2),
        )
        assertNull(GoodtoonCatalogPagination.nextPageCursor(document, "/ongoing/", 3))
    }

    @Test
    fun `genre pagination keeps the genre parameter`() {
        val document = GoodtoonFixtures.document("catalog-genre.html")
        assertEquals(
            "2",
            GoodtoonCatalogPagination.nextPageCursor(document, "/ongoing/?genre=fantasy", 1),
        )
        assertTrue(
            GoodtoonCatalogPagination.higherPages(document, "/ongoing/?genre=fantasy", 1).contains(3),
        )
    }

    @Test
    fun `pagination with a different query is ignored`() {
        val document = GoodtoonFixtures.document("catalog-ongoing.html")
        assertTrue(GoodtoonCatalogPagination.higherPages(document, "/ongoing/?genre=fantasy", 1).isEmpty())
        assertTrue(GoodtoonCatalogPagination.higherPages(document, "/end/", 1).isEmpty())
    }

    @Test
    fun `search results expose no pagination`() {
        val document = GoodtoonFixtures.document("search.html")
        assertNull(GoodtoonCatalogPagination.nextPageCursor(document, "/?q=%EA%B2%80%EC%83%89", 1))
    }
}
