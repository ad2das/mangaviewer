package ml.melun.mangaview.source.wfwf

import java.net.URI
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceGenre
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.SeriesKind
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WfwfCatalogPaginationTest {
    @Test
    fun comicCatalogReachesASecondPageWithoutChangingItsRoute() = runTest {
        val firstPath = "/cm?o=n&pg=1&t3="
        val secondPath = "/cm?o=n&pg=2&t3="
        val transport = CatalogPaginationTransport(mapOf(
            firstPath to catalogPage("comic:10001", "대상 만화", "/cm?o=n&pg=2&t3="),
            secondPath to catalogPage("comic:10002", "다음 만화", "/cm?o=n&pg=1&t3="),
        ))
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val query = CatalogQuery(SeriesKind.COMIC, CatalogOrder.LATEST)

        val first = source.catalog(query)
        val second = source.catalog(query.copy(cursor = first.nextCursor))

        assertEquals(listOf("comic:10001"), first.items.map { it.id.remoteKey })
        assertEquals("2", first.nextCursor)
        assertEquals(listOf("comic:10002"), second.items.map { it.id.remoteKey })
        assertNull(second.nextCursor)
        assertEquals(listOf(firstPath, secondPath), transport.requestPaths)
    }

    @Test
    fun catalogCarriesSameFilterAndOrderAcrossEvidenceBackedPages() = runTest {
        val firstPath = "/ing?o=n&pg=1&t1=&t2=3&t3="
        val secondPath = "/ing?o=n&pg=2&t1=&t2=3&t3="
        val transport = CatalogPaginationTransport(mapOf(
            firstPath to catalogPage("webtoon:10001", "첫 작품", "/ing?t1=&t2=3&t3=&o=n&pg=2"),
            secondPath to catalogPage("webtoon:10002", "둘째 작품", "/ing?t1=&t2=3&t3=&o=n&pg=1"),
        ))
        val source = WfwfContentSource(WfwfConfig("https://wfwf.test", "agent"), transport)
        val query = CatalogQuery(
            kind = SeriesKind.WEBTOON,
            order = CatalogOrder.LATEST,
            genre = SourceGenre("t2:3", "성인"),
        )

        val first = source.catalog(query)
        val second = source.catalog(query.copy(cursor = first.nextCursor))

        assertEquals(listOf("webtoon:10001"), first.items.map { it.id.remoteKey })
        assertEquals("2", first.nextCursor)
        assertEquals(listOf("webtoon:10002"), second.items.map { it.id.remoteKey })
        assertNull(second.nextCursor)
        assertEquals(listOf(firstPath, secondPath), transport.requestPaths)
    }

    @Test
    fun nextCursorRejectsForeignRouteAndGenreEvidence() {
        val document = Jsoup.parse(
            """
            <div class="pagi">
              <a href="/ing?t1=&amp;t2=3&amp;t3=&amp;o=n&amp;pg=6">same query</a>
              <a href="/ing?t1=&amp;t2=4&amp;t3=&amp;o=n&amp;pg=2">other genre</a>
              <a href="/cm?o=n&amp;pg=3&amp;t3=">other route</a>
              <a href="https://foreign.example/ing?t1=&amp;t2=3&amp;t3=&amp;o=n&amp;pg=4">foreign host</a>
            </div>
            """.trimIndent(),
            "https://wfwf.test/ing?o=n&pg=1&t1=&t2=3&t3=",
        )

        val failure = runCatching {
            WfwfCatalogPagination.nextPageCursor(
                document,
                "/ing?o=n&pg=1&t1=&t2=3&t3=",
                currentPage = 1,
            )
        }.exceptionOrNull()
        assertTrue("Expected missing immediate page to fail closed", failure is IllegalStateException)
    }

    @Test
    fun nextCursorRejectsMissingImmediatePageInsteadOfInventingIt() {
        val document = Jsoup.parse(
            """
            <div class="pagi">
              <a href="/ing?t1=&amp;t2=3&amp;t3=&amp;o=n&amp;pg=1">1</a>
              <a href="/ing?t1=&amp;t2=3&amp;t3=&amp;o=n&amp;pg=101">101</a>
            </div>
            """.trimIndent(),
        )

        val failure = runCatching {
            WfwfCatalogPagination.nextPageCursor(
                document,
                "/ing?o=n&pg=1&t1=&t2=3&t3=",
                currentPage = 1,
            )
        }.exceptionOrNull()
        assertTrue("Expected missing page 2 to fail closed", failure is IllegalStateException)
    }

    @Test
    fun nextCursorAcceptsObservedPageBeyondOneHundredWhenImmediate() {
        val document = Jsoup.parse(
            """
            <div class="pagi">
              <a href="/ing?t1=&amp;t2=3&amp;t3=&amp;o=n&amp;pg=101">101</a>
            </div>
            """.trimIndent(),
        )

        assertEquals(
            "101",
            WfwfCatalogPagination.nextPageCursor(
                document,
                "/ing?o=n&pg=1&t1=&t2=3&t3=",
                currentPage = 100,
            ),
        )
    }

    @Test
    fun nextCursorAcceptsSchemeRelativeSameOriginEvidence() {
        val document = Jsoup.parse(
            """
            <div class="pagi">
              <a href="//wfwf.test/ing?t1=&amp;t2=3&amp;t3=&amp;o=n&amp;pg=2">2</a>
            </div>
            """.trimIndent(),
            "https://wfwf.test/ing?o=n&pg=1&t1=&t2=3&t3=",
        )

        assertEquals(
            "2",
            WfwfCatalogPagination.nextPageCursor(
                document,
                "/ing?o=n&pg=1&t1=&t2=3&t3=",
                currentPage = 1,
            ),
        )
    }

    @Test
    fun cursorMustBePositiveAndCanonical() {
        assertEquals(1, WfwfCatalogPagination.page(null))
        assertEquals(2, WfwfCatalogPagination.page("2"))
        listOf("", "0", "02", "+2", "2147483648").forEach { cursor ->
            assertTrue(
                "Expected invalid cursor $cursor",
                runCatching { WfwfCatalogPagination.page(cursor) }.isFailure,
            )
        }
    }

    private fun catalogPage(seriesKey: String, title: String, nextHref: String): String {
        val kindPath = if (seriesKey.startsWith("comic:")) "/cl" else "/list"
        val id = seriesKey.substringAfter(':')
        return """
            <a href="$kindPath?toon=$id"><h3>$title</h3></a>
            <div class="pagi"><a href="$nextHref">next</a></div>
        """.trimIndent()
    }
}

private class CatalogPaginationTransport(
    private val routes: Map<String, String>,
) : SourceTransport {
    val requestPaths = mutableListOf<String>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        require(request.method == SourceHttpMethod.GET)
        val uri = URI(request.url)
        val path = uri.rawPath + uri.rawQuery?.let { "?$it" }.orEmpty()
        requestPaths += path
        val bytes = requireNotNull(routes[path]) { "Unexpected WFWF route: $path" }
            .toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
            body = CatalogPaginationBytes(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class CatalogPaginationBytes(
    private val bytes: ByteArray,
) : PageByteStream {
    private var position = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
