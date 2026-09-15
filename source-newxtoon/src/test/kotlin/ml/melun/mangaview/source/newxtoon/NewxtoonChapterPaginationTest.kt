package ml.melun.mangaview.source.newxtoon

import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewxtoonChapterPaginationTest {
    private val sourceId = SourceId("newxtoon")

    private fun fixture(name: String): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("newxtoon/$name"),
    ) { "Missing fixture $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun source(transport: SourceTransport) =
        NewxtoonContentSource(NewxtoonConfig(userAgent = "MangaViewer test"), transport)

    @Test fun mergesEveryProviderPageAndDeduplicatesBoundaryChapters() = runBlocking {
        val transport = PaginationTransport(
            fixture("series-paged.html"),
            fixture("chapters-page-2.json"),
            fixture("chapters-page-tail.json"),
        )

        val seriesId = SeriesId(sourceId, "41")
        val episodes = source(transport).episodes(seriesId, cursor = null).items

        assertEquals("20 embedded + 20 page two + 2 new on the overlapping tail", 42, episodes.size)
        assertEquals("1063130", episodes.first().id.remoteKey)
        assertEquals("제643화", episodes.first().title)
        assertEquals("5318", episodes.last().id.remoteKey)
        assertEquals("제602화", episodes.last().title)
        assertEquals(42.0, episodes.first().sequenceNumber)
        assertEquals(1.0, episodes.last().sequenceNumber)
        assertEquals("the tail must not duplicate the boundary chapter", 1,
            episodes.count { it.id.remoteKey == "5320" })
        assertEquals("5320", episodes[39].id.remoteKey)
        assertEquals(
            "the first chapter must win the minimum-sequence selection",
            "5318",
            episodes.filter { it.sequenceNumber != null }.minByOrNull { it.sequenceNumber!! }?.id?.remoteKey,
        )

        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[0].url.endsWith("/comics/41"))
        assertTrue(transport.requests[1].url.endsWith("/comics/41/chapters?page=2"))
        assertTrue(transport.requests[2].url.endsWith("/comics/41/chapters?page=3"))
    }

    @Test fun doesNotFollowTheChapterFeedWhenTheSeriesPageHasNoNextPage() = runBlocking {
        val transport = PaginationTransport(fixture("series.html"))

        val episodes = source(transport).episodes(SeriesId(sourceId, "17974"), cursor = null).items

        assertEquals(7, episodes.size)
        assertEquals(1, transport.requests.size)
        assertEquals(7.0, episodes.first().sequenceNumber)
        assertEquals(1.0, episodes.last().sequenceNumber)
    }

    @Test fun earliestChapterHasNoPreviousAndPointsForwardToTheSecondChapter() = runBlocking {
        val transport = PaginationTransport(fixture("series.html"))

        val adjacent = source(transport).adjacent(EpisodeId(SeriesId(sourceId, "17974"), "1062719"))

        assertNull("chapter one has no earlier chapter", adjacent.previous)
        assertEquals("1062718", adjacent.next?.remoteKey)
    }

    @Test fun newestChapterHasNoNextAndPointsBackToTheSecondNewest() = runBlocking {
        val transport = PaginationTransport(fixture("series.html"))

        val adjacent = source(transport).adjacent(EpisodeId(SeriesId(sourceId, "17974"), "1062837"))

        assertEquals("1062722", adjacent.previous?.remoteKey)
        assertNull("the newest chapter has no later chapter", adjacent.next)
    }
}

private class PaginationTransport(vararg bodies: String) : SourceTransport {
    private val bodies = ArrayDeque(bodies.toList())
    val requests = mutableListOf<SourceRequest>()

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        val bytes = bodies.removeFirst().toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = PaginationBytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "application/json; charset=utf-8",
        )
    }
}

private class PaginationBytesStream(private val bytes: ByteArray) : PageByteStream {
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
