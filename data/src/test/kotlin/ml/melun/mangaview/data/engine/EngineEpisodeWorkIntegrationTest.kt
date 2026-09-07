package ml.melun.mangaview.data.engine

import java.net.URI
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.content.EngineEpisodeWork
import ml.melun.mangaview.engine.content.PageHttpException
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.SourceResponse
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.wfwf.WfwfAccessPlanner
import ml.melun.mangaview.source.wfwf.WfwfKind
import ml.melun.mangaview.source.wfwf.WfwfSeriesKey
import org.junit.Assert.*
import org.junit.Test

class EngineEpisodeWorkIntegrationTest {
    private val episode = EpisodeId(SeriesId(SourceId("wfwf"), WfwfSeriesKey(WfwfKind.COMIC, 7).encode()), "12")
    private val origin = URI("https://wfwf.test")
    private val finalUrl = "https://wfwf.test/cv?toon=7&num=12"
    private val html = """<div class="viewer-wrap"><img data-original="/original.png"></div>""".toByteArray()

    @Test fun independentNavigationPlansShareOneImmutableDocument() = runTest {
        val coordinator = WorkCoordinator(this)
        var calls = 0
        val body = Body(html)
        val factory = EngineEpisodeWork("test", WfwfAccessPlanner("agent"), SourceTransport {
            calls++
            assertEquals(finalUrl, it.url)
            response(body)
        }, StandardTestDispatcher(testScheduler))
        val unknown = coordinator.submit(factory.request(episode, origin, 0, WorkPriority.VISIBLE))
        val next = episode.copy(remoteKey = "13")
        val known = coordinator.submit(factory.request(episode, origin, 0, WorkPriority.VISIBLE,
            AdjacentEpisodes(null, next)))
        val first = unknown.await()
        val second = known.await()
        assertEquals(1, calls)
        assertEquals(1, body.closes)
        assertFalse(first.navigationKnown)
        assertTrue(second.navigationKnown)
        assertEquals(next, second.manifest.nextEpisodeId)
        assertEquals(first.documentSha256, second.documentSha256)
        assertEquals(URI("https://wfwf.test/original.png"), first.pages.single().candidates.single())
        unknown.awaitReleased()
        known.awaitReleased()
        assertEquals(0, coordinator.snapshot().retainedResults)
        coordinator.close()
    }

    @Test fun oversizedAdvertisedBodyClosesBeforeReadingOrParsing() = runTest {
        val coordinator = WorkCoordinator(this)
        val body = Body(html)
        val factory = EngineEpisodeWork("test", WfwfAccessPlanner("agent"), SourceTransport {
            response(body, length = Long.MAX_VALUE)
        }, StandardTestDispatcher(testScheduler))
        expect<IllegalArgumentException> { coordinator.acquire(factory.request(episode, origin, 0, WorkPriority.FOCUS)) }
        assertEquals(0, body.reads)
        assertEquals(1, body.closes)
        coordinator.close()
    }

    @Test fun truncatedDocumentCannotBecomeAPartialManifest() = runTest {
        val coordinator = WorkCoordinator(this)
        val body = Body(html)
        val factory = EngineEpisodeWork("test", WfwfAccessPlanner("agent"), SourceTransport {
            response(body, length = html.size.toLong() + 1)
        }, StandardTestDispatcher(testScheduler))
        expect<IllegalArgumentException> { coordinator.acquire(factory.request(episode, origin, 0, WorkPriority.FOCUS)) }
        assertEquals(1, body.closes)
        assertEquals(0, coordinator.snapshot().retainedResults)
        coordinator.close()
    }

    @Test fun httpFailureClosesWithoutParsingAnErrorPage() = runTest {
        val coordinator = WorkCoordinator(this)
        val body = Body(html)
        val factory = EngineEpisodeWork("test", WfwfAccessPlanner("agent"), SourceTransport {
            response(body, status = 403)
        }, StandardTestDispatcher(testScheduler))
        expect<PageHttpException> { coordinator.acquire(factory.request(episode, origin, 0, WorkPriority.FOCUS)) }
        assertEquals(0, body.reads)
        assertEquals(1, body.closes)
        coordinator.close()
    }

    private fun response(body: Body, status: Int = 200, length: Long = html.size.toLong()) =
        SourceResponse(status, finalUrl, emptyMap(), body, length, "text/html")

    private class Body(private val bytes: ByteArray) : PageByteStream {
        var closes = 0
        var reads = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            reads++
            if (reads > 1) return -1
            bytes.copyInto(destination, offset)
            return bytes.size
        }
        override fun close() { closes++ }
    }

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.name}") }
        catch (failure: Throwable) { if (failure !is T) throw failure }
    }
}
