package ml.melun.mangaview.source.newxtoon

import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class NewxtoonEpisodeOrderTest {
    private fun fixture(name: String): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("newxtoon/$name"),
    ) { "Missing fixture $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun firstChapterHasTheSmallestSequenceEvenThoughTheListIsNewestFirst() = runBlocking {
        val source = NewxtoonContentSource(
            NewxtoonConfig(userAgent = "MangaViewer test"),
            QueueTransport(fixture("series.html")),
        )
        val episodes = source.episodes(SeriesId(SourceId("newxtoon"), "17974"), cursor = null).items

        assertEquals("list order must stay newest-first", "1062837", episodes.first().id.remoteKey)
        assertEquals("list order must stay newest-first", "1062719", episodes.last().id.remoteKey)
        assertEquals("first list entry must keep its provider title", "제7화", episodes.first().title)
        assertEquals("chapter 1 must keep its provider title", "제1화", episodes.last().title)
        assertEquals(7.0, episodes.first().sequenceNumber)
        assertEquals(1.0, episodes.last().sequenceNumber)
        assertEquals(
            "the first chapter must win the minimum-sequence selection",
            "1062719",
            episodes.filter { it.sequenceNumber != null }.minByOrNull { it.sequenceNumber!! }?.id?.remoteKey,
        )
    }
}

private class QueueTransport(vararg bodies: String) : SourceTransport {
    private val bodies = ArrayDeque(bodies.toList())

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val bytes = bodies.removeFirst().toByteArray()
        return SourceResponse(
            statusCode = 200,
            finalUrl = request.url,
            headers = emptyMap(),
            body = BytesStream(bytes),
            contentLength = bytes.size.toLong(),
            contentType = "text/html; charset=utf-8",
        )
    }
}

private class BytesStream(private val bytes: ByteArray) : PageByteStream {
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
