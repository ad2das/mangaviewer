package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Test

class WfwfSearchPaginationTest {
    @Test fun survivalGameOnTheSecondSearchPageIsNotLost() = runTest {
        val urls = mutableListOf<String>()
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "test"), SourceTransport { request ->
            urls += request.url
            val html = if ("pg=1" in request.url) """
                <a href='/list?toon=75888'><h3>이과장 생존기</h3></a>
                <div class='pagi'><a href='/sh?t2=&amp;t3=&amp;o=n&amp;pg=2&amp;q=%BB%FD%C1%B8'>2</a></div>
            """ else "<a href='/cl?toon=10173'><h3>생존게임</h3></a>"
            response(request.url, html)
        })
        val first = source.search(SourceSearchQuery("생존", SeriesKind.COMIC))
        assertTrue(first.items.isEmpty())
        assertEquals("2", first.nextCursor)
        val last = source.search(SourceSearchQuery("생존", SeriesKind.COMIC, cursor = first.nextCursor))
        assertEquals("생존게임", last.items.single().title)
        assertNull(last.nextCursor)
        assertTrue(urls.all { "/sh?" in it && "q=%BB%FD%C1%B8" in it })
    }

    @Test fun unsupportedAuthorSearchDoesNotPretendToReturnAnEmptyTitleSearch() = runTest {
        var requests = 0
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "test"), SourceTransport {
            requests++; error("unexpected request")
        })
        val failure = runCatching { source.search(SourceSearchQuery("비가", field = SearchField.AUTHOR)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, requests)
    }

    private fun response(url: String, html: String): SourceResponse {
        val bytes = ("<meta charset='EUC-KR'>" + html).toByteArray(charset("EUC-KR"))
        var position = 0
        return SourceResponse(200, url, emptyMap(), object : PageByteStream {
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
                if (position == bytes.size) return -1
                val count = minOf(byteCount, bytes.size - position)
                bytes.copyInto(destination, offset, position, position + count); position += count
                return count
            }
            override fun close() = Unit
        }, contentLength = bytes.size.toLong(), contentType = "text/html; charset=EUC-KR")
    }
}
