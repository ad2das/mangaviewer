package ml.melun.mangaview.content

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceHandoffTest {
    @Test fun disposalCanRaceWithoutReleasingTwice() {
        val releases = AtomicInteger()
        val handoff = ResourceHandoff(42) { releases.incrementAndGet() }
        val start = CountDownLatch(1)
        val contenders = List(8) { Thread { start.await(); handoff.close() } }
        contenders.forEach(Thread::start)
        start.countDown()
        contenders.forEach {
            it.join(5000)
            assertTrue("Resource cleanup thread did not terminate", !it.isAlive)
        }
        handoff.close()
        assertEquals(1, releases.get())
    }

    @Test fun aReceiverOwnsTheResourceAfterTakingIt() {
        val releases = AtomicInteger()
        val handoff = ResourceHandoff(42) { releases.incrementAndGet() }
        assertEquals(42, handoff.take())
        handoff.close()
        assertEquals(0, releases.get())
    }

    @Test fun channelCancellationDisposesBufferedResults() = runTest {
        val releases = AtomicInteger()
        val channel = Channel<PipelineCommand>(1, onUndeliveredElement = PipelineCommand::releaseUndelivered)
        channel.sendCompletion(completion(releases))
        channel.cancel()
        assertEquals(1, releases.get())
    }

    @Test fun cancellingABlockedSenderDisposesItsResultExactlyOnce() = runTest {
        val releases = AtomicInteger()
        val channel = Channel<PipelineCommand>(1, onUndeliveredElement = PipelineCommand::releaseUndelivered)
        channel.send(PipelineCommand.SetForeground(true))
        val sender = launch { channel.sendCompletion(completion(releases)) }
        runCurrent()
        assertTrue(sender.isActive)
        sender.cancelAndJoin()
        channel.cancel()
        assertEquals(1, releases.get())
    }

    private fun completion(releases: AtomicInteger): PipelineCommand {
        val page = PageId.at(EpisodeId(SeriesId(SourceId("test"), "work"), "episode"), 0)
        val texture = TextureRef(page, 1, 1, 0, 1, 1, 4)
        return PipelineCommand.UploadFinished(
            1, page, 1, Result.success(ResourceHandoff(texture) { releases.incrementAndGet() }),
        )
    }
}
