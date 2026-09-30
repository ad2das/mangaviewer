package ml.melun.mangaview.source.ntk

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSearchQuery
import org.junit.Assert.*
import org.junit.Test

class NtkSearchServiceTest {
    @Test fun survivalGameIsIncludedInAllSearchBeforeTheWebtoonPagesEnd() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            if ("kind=manhwa" in path) searchPage(card("manhwa", "3648", "생존게임"))
            else searchPage(card("webtoon", "847568", "이과장 생존기") + next("생존", "webtoon", 2))
        }
        val page = search.search(SourceSearchQuery("생존"))
        assertEquals(setOf("/webtoon/847568", "/manhwa/3648"), page.items.map { it.id.remoteKey }.toSet())
        assertEquals("w2:m0", page.nextCursor)
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.startsWith("/search?") })
    }

    @Test fun allSearchAdvancesEachCategoryIndependentlyAndStopsAtTheActualEnd() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            when {
                "kind=manhwa" in path -> searchPage(card("manhwa", "3648", "생존게임"))
                "page=1" in path -> searchPage(card("webtoon", "1", "생존 1") + next("생존", "webtoon", 2))
                else -> searchPage(card("webtoon", "2", "생존 2"))
            }
        }
        val first = search.search(SourceSearchQuery("생존"))
        val second = search.search(SourceSearchQuery("생존", cursor = first.nextCursor))
        assertEquals(listOf("/webtoon/2"), second.items.map { it.id.remoteKey })
        assertNull(second.nextCursor)
        assertEquals(1, paths.count { "kind=manhwa" in it })
    }

    @Test fun paginationKeepsUnicodeQueryAndCategoryAndIgnoresUnrelatedLinks() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            if ("page=2" in path) searchPage(card("manhwa", "2", "다음 작품"))
            else searchPage(
                card("manhwa", "1", "작품") +
                    next("비가", "manhwa", 2) +
                    next("다른 검색어", "manhwa", 90) +
                    next("비가", "webtoon", 70),
            )
        }
        val query = SourceSearchQuery("  비가  ", SeriesKind.COMIC)
        val first = search.search(query)
        assertEquals("2", first.nextCursor)
        assertNull(search.search(query.copy(cursor = first.nextCursor)).nextCursor)
        assertTrue(paths.all {
            it.startsWith("/search?") && URLDecoder.decode(URI(it).rawQuery, "UTF-8").contains("q=비가")
        })
    }

    @Test fun emptyIntermediatePageStillHasANextCursor() = runTest {
        val search = service { searchPage(next("생존", "webtoon", 3)) }
        val result = search.search(SourceSearchQuery("생존", SeriesKind.WEBTOON, cursor = "2"))
        assertTrue(result.items.isEmpty())
        assertEquals("3", result.nextCursor)
    }

    @Test fun challengePageIsFailureRatherThanNoResults() = runTest {
        val search = service { "<html><title>Just a moment</title></html>" }
        assertTrue(runCatching { search.search(SourceSearchQuery("생존", SeriesKind.COMIC)) }.isFailure)
    }

    @Test fun mirrorsWithoutTheSiteSearchFallBackToTheListingSearch() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            if (path.startsWith("/search")) "<html><body>home</body></html>"
            else listingPage(card("webtoon", "847568", "이과장 생존기") + listingNext("생존", "webtoon", 2))
        }
        val page = search.search(SourceSearchQuery("생존", SeriesKind.WEBTOON))
        assertEquals(listOf("/webtoon/847568"), page.items.map { it.id.remoteKey })
        assertEquals("2", page.nextCursor)
        assertEquals(2, paths.size)
        assertTrue(paths[0].startsWith("/search?"))
        assertTrue(paths[1].startsWith("/webtoon?stx="))
    }

    @Test fun failedSiteSearchRequestFallsBackToTheListingSearch() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            if (path.startsWith("/search")) throw IOException("NTK document request failed with 404")
            listingPage(card("manhwa", "1", "생존게임"))
        }
        val page = search.search(SourceSearchQuery("생존", SeriesKind.COMIC))
        assertEquals(listOf("/manhwa/1"), page.items.map { it.id.remoteKey })
        assertEquals(2, paths.size)
        assertTrue(paths[1].startsWith("/manhwa?stx="))
    }

    @Test fun authorFieldKeepsItsFieldOnTheSiteSearchRoute() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            searchPage(card("manhwa", "1", "비가 작가"))
        }
        search.search(SourceSearchQuery("비가", SeriesKind.COMIC, SearchField.AUTHOR))
        assertTrue(paths.single().contains("field=author"))
        assertTrue(paths.single().contains("kind=manhwa"))
        assertTrue(paths.single().contains("match=contains"))
    }

    @Test fun invalidCursorIsRejectedBeforeAnyRequest() = runTest {
        var count = 0
        val search = service { count++; searchPage("") }
        for (cursor in listOf("0", "-1", "02", "bad")) {
            assertTrue(runCatching { search.search(SourceSearchQuery("생존", SeriesKind.COMIC, cursor = cursor)) }.isFailure)
        }
        assertTrue(runCatching { search.search(SourceSearchQuery("생존", cursor = "w0:m0")) }.isFailure)
        assertEquals(0, count)
    }

    private fun service(load: suspend (String) -> String) = NtkSearchService(SourceId("ntk"), NtkDocumentParser(), load)
    private fun searchPage(content: String) = "<div class='search-results-grid'>$content</div>"
    private fun listingPage(content: String) = "<div class='list-page'>$content</div>"
    private fun card(kind: String, id: String, title: String) = "<a href='/$kind/$id'><h3>$title</h3></a>"
    private fun next(query: String, kind: String, page: Int) =
        "<a href='/search?q=${URLEncoder.encode(query, "UTF-8")}&amp;kind=$kind&amp;page=$page'>다음</a>"
    private fun listingNext(query: String, kind: String, page: Int) =
        "<a href='/$kind?stx=${URLEncoder.encode(query, "UTF-8")}&amp;page=$page'>다음</a>"
}
