package ml.melun.mangaview.source.newxtoon

import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.source.CatalogOrder
import ml.melun.mangaview.source.CatalogQuery
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewxtoonCatalogStatusTest {
    private val catalogHtml = """
        <a href="https://newxtoon1.com/comics/1" aria-label="연재 작품, 일반만화, 네이버">
          <h3>연재 작품</h3>
          <p>12화</p>
        </a>
        <a href="https://newxtoon1.com/comics/2" aria-label="끝난 작품, 일반만화, 탑툰">
          <h3>끝난 작품</h3>
          <p>43화(완결)</p>
        </a>
        <a href="https://newxtoon1.com/comics/3" aria-label="미표기 작품, 일반만화, 리디">
          <h3>미표기 작품</h3>
        </a>
        <a href="https://newxtoon1.com/comics/4" aria-label="휴재 작품, 일반만화, 카카오">
          <h3>휴재 작품</h3>
          <p>휴재</p>
        </a>
    """.trimIndent()

    private fun source(transport: SourceTransport) =
        NewxtoonContentSource(NewxtoonConfig(userAgent = "MangaViewer test"), transport)

    private fun query(status: SeriesStatus? = null) =
        CatalogQuery(SeriesKind.WEBTOON, CatalogOrder.LATEST, statusFilter = status)

    @Test fun ongoingFilterSendsTheEncodedProviderStatusParam() = runTest {
        val transport = CatalogUrlTransport(catalogHtml)

        source(transport).catalog(query(SeriesStatus.ONGOING))

        assertTrue(transport.requests.single().url.contains("status=%EC%97%B0%EC%9E%AC%EC%A4%91"))
    }

    @Test fun completedFilterSendsTheEncodedProviderStatusParam() = runTest {
        val transport = CatalogUrlTransport(catalogHtml)

        source(transport).catalog(query(SeriesStatus.COMPLETED))

        assertTrue(transport.requests.single().url.contains("status=%EC%99%84%EA%B2%B0"))
    }

    @Test fun noStatusParamWhenFilterIsNull() = runTest {
        val transport = CatalogUrlTransport(catalogHtml)

        source(transport).catalog(query())

        assertFalse(transport.requests.single().url.contains("status="))
    }

    @Test fun hiatusFilterUsesTheOngoingProviderSegment() = runTest {
        val transport = CatalogUrlTransport(catalogHtml)

        val items = source(transport).catalog(query(SeriesStatus.HIATUS)).items

        assertTrue(transport.requests.single().url.contains("status=%EC%97%B0%EC%9E%AC%EC%A4%91"))
        assertEquals(SeriesStatus.ONGOING, items.first { it.id.remoteKey == "3" }.status)
    }

    @Test fun hiatusCardsAreReportedAsOngoing() = runTest {
        val transport = CatalogUrlTransport(catalogHtml)

        val ongoing = source(transport).catalog(query(SeriesStatus.ONGOING)).items

        assertEquals(SeriesStatus.ONGOING, ongoing.first { it.id.remoteKey == "4" }.status)
    }

    @Test fun unstampedCardsReceiveTheRequestedSegmentStatus() = runTest {
        val transport = CatalogUrlTransport(catalogHtml, catalogHtml)
        val source = source(transport)

        val ongoing = source.catalog(query(SeriesStatus.ONGOING)).items
        val completed = source.catalog(query(SeriesStatus.COMPLETED)).items

        assertEquals(SeriesStatus.ONGOING, ongoing.first { it.id.remoteKey == "1" }.status)
        assertEquals(
            "an explicit parsed status must survive the segment stamp",
            SeriesStatus.COMPLETED,
            ongoing.first { it.id.remoteKey == "2" }.status,
        )
        assertEquals(SeriesStatus.ONGOING, ongoing.first { it.id.remoteKey == "3" }.status)
        assertEquals(
            "an explicit parsed status must survive the segment stamp",
            SeriesStatus.ONGOING,
            completed.first { it.id.remoteKey == "1" }.status,
        )
        assertEquals(SeriesStatus.COMPLETED, completed.first { it.id.remoteKey == "3" }.status)
    }
}

private class CatalogUrlTransport(vararg bodies: String) : SourceTransport {
    private val bodies = ArrayDeque(bodies.toList())
    val requests = mutableListOf<SourceRequest>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        val bytes = bodies.removeFirst().toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = CatalogBytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class CatalogBytesStream(private val bytes: ByteArray) : PageByteStream {
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
