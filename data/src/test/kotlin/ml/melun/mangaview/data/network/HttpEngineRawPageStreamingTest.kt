package ml.melun.mangaview.data.network

import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.ImageHeaderProbeTest
import ml.melun.mangaview.data.cache.InMemoryRawPageDao
import ml.melun.mangaview.data.cache.AtomicFilePublisher
import ml.melun.mangaview.data.cache.RawPageStore
import ml.melun.mangaview.source.OpenedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpEngineRawPageStreamingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun directBodyStreamPublishesOneVerifiedRawPageWithoutAnIntermediateBodyFile() = runTest {
        val bytes = ImageHeaderProbeTest.png(1_080, 9_000)
        val producer = PullProducer(bytes)
        var finished = 0
        lateinit var stream: HttpEngineBodyPageStream
        stream = HttpEngineBodyPageStream(
            expectedLength = bytes.size.toLong(),
            requestRead = { buffer -> producer.read(buffer, stream) },
            cancelExchange = { cause -> stream.fail(cause) },
            finished = { finished += 1 },
        )
        val root = temporaryFolder.newFolder("direct-http-engine-cache")
        val store = RawPageStore(
            root,
            InMemoryRawPageDao(),
            Dispatchers.IO,
            AtomicFilePublisher { staging, destination ->
                check(staging.renameTo(destination)) { "Test publish failed" }
            },
        )

        val opened = OpenedPage(stream, bytes.size.toLong(), "image/png", null, null)
        val cached = try {
            store.write(pageId(), opened)
        } finally {
            opened.close()
        }

        assertEquals(bytes.size.toLong(), cached.byteCount)
        assertEquals(1, finished)
        assertEquals(listOf(cached.file.name), root.listFiles().orEmpty().map { it.name })
        assertTrue(cached.file.readBytes().contentEquals(bytes))
    }

    private fun pageId(): PageId {
        val series = SeriesId(SourceId("ntk"), "series")
        return PageId(EpisodeId(series, "episode"), "page")
    }

    private class PullProducer(private val bytes: ByteArray) {
        private var offset = 0

        fun read(buffer: ByteBuffer, stream: HttpEngineBodyPageStream) {
            if (offset == bytes.size) {
                stream.completeSuccess()
                return
            }
            val count = minOf(buffer.remaining(), bytes.size - offset)
            buffer.put(bytes, offset, count)
            offset += count
            stream.onReadCompleted(buffer)
        }
    }
}
