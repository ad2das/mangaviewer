package ml.melun.mangaview.source.newxtoon

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewxtoonSearchPaginationTest {
    private val parser = NewxtoonHtmlParser("https://newxtoon1.com")

    @Test fun maintenanceResponseCannotBeReportedAsNoSearchResults() = runTest {
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "test"),
            SearchPageTransport(1 to "<html><title>Maintenance</title><body>Try later</body></html>"))
        val failure = runCatching { source.search("생존") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("검색 응답"))
        assertTrue(parser.searchCards("<input id='page-search' name='q' value='없는작품'>").isEmpty())
    }

    @Test fun searchPaginationIgnoresOtherQueriesAndScriptStringsWithoutSkippingSparsePages() {
        val html = """
            <script>var unrelated = '?page=999';</script>
            <a href='/comics?page=90'>catalog</a>
            <a href='/search?q=other&amp;page=70'>other query</a>
            <a href='https://other.example/search?q=생존&amp;page=60'>external</a>
            <a href='/search?q=생존&amp;page=5'>last</a>
        """
        assertEquals(2, parser.nextSearchPage(html, "생존", 1))
        assertNull(parser.nextSearchPage(html, "생존", 5))
    }

    @Test fun oneCharacterQueryHasAnActionableFailureInsteadOfFalseNoResults() = runTest {
        val transport = SearchPageTransport()
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "test"), transport)
        val failure = runCatching { source.search("나") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("2~100자"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun escapedPaginationLinksAdvanceToTheNextSearchPage() {
        val html = """
            <a href="https://newxtoon1.com/comics/1"><h3>첫 작품</h3></a>
            <nav>
              <a href="https://newxtoon1.com/search?q=%EB%A1%9C%EB%A7%A8%EC%8A%A4&amp;page=2">2</a>
              <a href="https://newxtoon1.com/search?q=%EB%A1%9C%EB%A7%A8%EC%8A%A4&amp;page=239">239</a>
            </nav>
        """.trimIndent()

        assertEquals(2, parser.nextPage(html, 1))
        assertEquals(3, parser.nextPage(html, 2))
        assertNull("the last page has no further links", parser.nextPage(html, 239))
    }

    @Test fun searchFollowsTheProviderCursorAndReportsTheNextPage() = runTest {
        val transport = SearchPageTransport(
            1 to firstPage,
            2 to secondPage,
        )
        val source = NewxtoonContentSource(NewxtoonConfig(userAgent = "MangaViewer test"), transport)

        val first = source.search("로맨스", cursor = null)
        assertTrue(transport.requests.first().url.contains("q=%EB%A1%9C%EB%A7%A8%EC%8A%A4"))
        assertTrue(transport.requests.first().url.contains("page=1"))
        assertEquals(listOf("1", "2"), first.items.map { it.id.remoteKey })
        assertEquals("2", first.nextCursor)

        val second = source.search("로맨스", cursor = first.nextCursor)
        assertTrue(transport.requests.last().url.contains("page=2"))
        assertEquals(listOf("3"), second.items.map { it.id.remoteKey })
        assertNull(second.nextCursor)
    }

    private val firstPage = """
        <a href="https://newxtoon1.com/comics/1"><h3>첫 작품</h3></a>
        <a href="https://newxtoon1.com/comics/2"><h3>둘째 작품</h3></a>
        <a href="https://newxtoon1.com/search?q=%EB%A1%9C%EB%A7%A8%EC%8A%A4&amp;page=2">2</a>
    """.trimIndent()

    private val secondPage = """
        <a href="https://newxtoon1.com/comics/3"><h3>셋째 작품</h3></a>
    """.trimIndent()
}

private class SearchPageTransport(vararg pages: Pair<Int, String>) : SourceTransport {
    private val pages = pages.toMap()
    val requests = mutableListOf<SourceRequest>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        val page = Regex("""[?&](?:amp;)?page=(\d+)""").find(request.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val bytes = (pages[page] ?: error("unexpected page $page")).toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = SearchBytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class SearchBytesStream(private val bytes: ByteArray) : PageByteStream {
    private var position = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (position == bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
