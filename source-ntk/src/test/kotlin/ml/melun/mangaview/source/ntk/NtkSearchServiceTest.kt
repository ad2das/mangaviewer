package ml.melun.mangaview.source.ntk

import java.net.URI
import java.net.URLDecoder
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
            if (path.contains("kind=manhwa")) card("manhwa", "3648", "생존게임")
            else card("webtoon", "847568", "이과장 생존기") + next("생존", "webtoon", 2)
        }
        val page = search.search(SourceSearchQuery("생존"))
        assertEquals(setOf("/webtoon/847568", "/manhwa/3648"), page.items.map { it.id.remoteKey }.toSet())
        assertEquals("w2:m0", page.nextCursor)
        assertEquals(2, paths.size)
        assertTrue(paths.all { "kind=" in it })
    }

    @Test fun allSearchAdvancesEachCategoryIndependentlyAndStopsAtTheActualEnd() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            when {
                "kind=manhwa" in path -> card("manhwa", "3648", "생존게임")
                "page=1" in path -> card("webtoon", "1", "생존 1") + next("생존", "webtoon", 2)
                else -> card("webtoon", "2", "생존 2")
            }
        }
        val first = search.search(SourceSearchQuery("생존"))
        val second = search.search(SourceSearchQuery("생존", cursor = first.nextCursor))
        assertEquals(listOf("/webtoon/2"), second.items.map { it.id.remoteKey })
        assertNull(second.nextCursor)
        assertEquals(1, paths.count { "kind=manhwa" in it })
    }

    @Test fun authorPaginationKeepsUnicodeQueryAndCategoryAndIgnoresUnrelatedLinks() = runTest {
        val paths = mutableListOf<String>()
        val search = service { path ->
            paths += path
            if ("page=1" in path) card("manhwa", "1", "작품") + next("비가", "manhwa", 2, "author") +
                next("다른 검색어", "manhwa", 90) + next("비가", "webtoon", 70, "author")
            else card("manhwa", "2", "다음 작품")
        }
        val query = SourceSearchQuery("  비가  ", SeriesKind.COMIC, SearchField.AUTHOR)
        val first = search.search(query)
        assertEquals("2", first.nextCursor)
        assertNull(search.search(query.copy(cursor = first.nextCursor)).nextCursor)
        assertTrue(paths.all { URLDecoder.decode(URI(it).rawQuery, "UTF-8").contains("q=비가&field=author") })
    }

    @Test fun emptyIntermediatePageStillHasANextCursor() = runTest {
        val search = service { "<form class='search-page-form'></form>" + next("생존", "webtoon", 4) }
        val result = search.search(SourceSearchQuery("생존", SeriesKind.WEBTOON, cursor = "2"))
        assertTrue(result.items.isEmpty())
        assertEquals("3", result.nextCursor)
    }

    @Test fun challengePageIsFailureRatherThanNoResults() = runTest {
        val search = service { "<html><title>Just a moment</title></html>" }
        assertTrue(runCatching { search.search(SourceSearchQuery("생존", SeriesKind.COMIC)) }.isFailure)
    }

    @Test fun invalidCursorIsRejectedBeforeAnyRequest() = runTest {
        var count = 0
        val search = service { count++; "<div class='search-results-grid'></div>" }
        for (cursor in listOf("0", "-1", "02", "bad")) {
            assertTrue(runCatching { search.search(SourceSearchQuery("생존", SeriesKind.COMIC, cursor = cursor)) }.isFailure)
        }
        assertTrue(runCatching { search.search(SourceSearchQuery("생존", cursor = "w0:m0")) }.isFailure)
        assertEquals(0, count)
    }

    private fun service(load: suspend (String) -> String) = NtkSearchService(SourceId("ntk"), NtkDocumentParser(), load)
    private fun card(kind: String, id: String, title: String) = "<a href='/$kind/$id'><h3>$title</h3></a>"
    private fun next(query: String, kind: String, page: Int, field: String = "title") =
        "<a href='/search?q=$query&amp;kind=$kind&amp;field=$field&amp;page=$page'>다음</a>"
}
