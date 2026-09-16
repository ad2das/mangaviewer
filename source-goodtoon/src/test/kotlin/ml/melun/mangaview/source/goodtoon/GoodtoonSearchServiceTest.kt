package ml.melun.mangaview.source.goodtoon

import java.io.IOException
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.SourceSearchQuery
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class GoodtoonSearchServiceTest {
    @Test fun `moving boundary titles are recovered before reporting completion`() = runTest {
        var request = 0
        val service = service {
            listOf(listOf("a", "b"), listOf("b", "c"), listOf("a", "d"), listOf("c", "d"))[request++]
        }
        val first = service.search(SourceSearchQuery("사랑"))
        val second = service.search(SourceSearchQuery("사랑", cursor = first.nextCursor))
        assertEquals("r2:1", second.nextCursor)
        val repair = service.search(SourceSearchQuery("사랑", cursor = second.nextCursor))
        assertNull(repair.nextCursor)
        assertEquals(setOf("a", "b", "c", "d"), (first.items + second.items + repair.items).map { it.id.remoteKey }.toSet())
        assertEquals(4, request)
    }

    @Test fun `persistent duplicate pages pause with a resumable warning`() = runTest {
        val service = service { listOf("a", "b") }
        var page = service.search(SourceSearchQuery("사랑"))
        repeat(4) {
            if (page.nextWarning == null) page = service.search(SourceSearchQuery("사랑", cursor = page.nextCursor))
        }
        assertNotNull(page.nextWarning)
        assertEquals("r5:1", page.nextCursor)
        val resumed = service.search(SourceSearchQuery("사랑", cursor = page.nextCursor))
        assertNotNull(resumed.nextCursor)
    }

    @Test fun `queries keep independent reconciliation counts`() = runTest {
        var fail = true
        val service = service { path ->
            if ("pg=2" in path && !fail) listOf("b", "c") else listOf("a", "b")
        }
        val first = service.search(SourceSearchQuery("사랑"))
        val second = service.search(SourceSearchQuery("사랑", cursor = first.nextCursor))
        // Links belonging to the first query must not paginate the second query.
        assertNull(service.search(SourceSearchQuery("다른 검색")).nextCursor)
        fail = false
        val repaired = service.search(SourceSearchQuery("사랑", cursor = second.nextCursor))
        assertTrue(repaired.items.any { it.id.remoteKey == "c" })
        assertNotNull(repaired.nextCursor) // 3 unique IDs out of 4 slots remains incomplete.
    }

    @Test fun `repair failure preserves its cursor and retries every page atomically`() = runTest {
        var calls = 0
        var throwOnce = true
        val service = service { path ->
            calls++
            if (calls == 4 && throwOnce) { throwOnce = false; throw IOException("connection interrupted") }
            when {
                calls <= 2 -> listOf("a", "b")
                "pg=2" in path -> listOf("c", "d")
                else -> listOf("a", "c")
            }
        }
        val first = service.search(SourceSearchQuery("사랑"))
        val second = service.search(SourceSearchQuery("사랑", cursor = first.nextCursor))
        try { service.search(SourceSearchQuery("사랑", cursor = second.nextCursor)); fail("expected failure") }
        catch (_: IOException) { }
        val retry = service.search(SourceSearchQuery("사랑", cursor = second.nextCursor))
        assertEquals(setOf("a", "c", "d"), retry.items.map { it.id.remoteKey }.toSet())
        assertNull(retry.nextCursor)
    }

    private fun service(items: (String) -> List<String>) = GoodtoonSearchService(
        fetch = { path ->
            val cards = items(path).joinToString("") { "<a class='card' href='/manga/$it/'><div class='subject'>$it</div></a>" }
            val next = if ("pg=2" in path) "" else "<div class='pagination'><a href='/?q=%EC%82%AC%EB%9E%91&pg=2'>2</a></div>"
            Jsoup.parse(cards + next, "https://www.goodtoon004.com$path")
        },
        parse = { GoodtoonHtmlParser().series(it, { key -> goodtoonSeriesId(key.encode()) }) },
    )
}
