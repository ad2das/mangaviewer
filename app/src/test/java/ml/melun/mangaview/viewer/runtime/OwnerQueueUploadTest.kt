package ml.melun.mangaview.viewer.runtime

import java.io.Closeable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerQueueUploadTest {
    @Test fun cancellationWhileAwaitingOwnerCleanupReturnsTheTexture() = runTest {
        val queue = ArrayDeque<Runnable>()
        val released = mutableListOf<Long>()
        lateinit var job: Job
        val pixels = Pixels {
            runCurrent()
            assertFalse("Successful resume must still await native cleanup", job.isCompleted)
            job.cancel()
        }
        job = launch {
            uploadOnOwnerQueue(pixels, { queue.addLast(it); true }, { 11L }, released::add)
        }
        runCurrent()
        queue.removeFirst().run()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, pixels.closes)
        assertEquals(listOf(11L), released)
    }

    @Test fun queuedCancellationKeepsPixelsUntilTheOwnerDiscardsTheTask() = runTest {
        val pixels = Pixels()
        val queue = ArrayDeque<Runnable>()
        var uploads = 0
        val job = launch {
            uploadOnOwnerQueue(pixels, { queue.addLast(it); true }, { uploads++; 1L }, {})
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(0, pixels.closes)
        assertFalse("Upload worker completed before its queued native task", job.isCompleted)
        queue.removeFirst().run()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, pixels.closes)
        assertEquals(0, uploads)
    }

    @Test fun cancellationDuringUploadCannotFreeThePixelsBeingRead() = runTest {
        val pixels = Pixels()
        val queue = ArrayDeque<Runnable>()
        val released = mutableListOf<Long>()
        lateinit var job: Job
        job = launch {
            uploadOnOwnerQueue(pixels, { queue.addLast(it); true }, {
                assertEquals(0, it.closes)
                job.cancel()
                assertEquals(0, it.closes)
                42L
            }, released::add)
        }
        runCurrent()
        queue.removeFirst().run()
        runCurrent()
        assertEquals(1, pixels.closes)
        assertEquals(listOf(42L), released)
    }

    @Test fun cancelledResultDeliveryReturnsTheTextureButNotPixelsTwice() = runTest {
        val pixels = Pixels()
        val queue = ArrayDeque<Runnable>()
        val released = mutableListOf<Long>()
        val job = launch {
            uploadOnOwnerQueue(pixels, { queue.addLast(it); true }, { 7L }, released::add)
        }
        runCurrent()
        queue.removeFirst().run()
        job.cancel()
        runCurrent()
        assertEquals(1, pixels.closes)
        assertEquals(listOf(7L), released)
    }

    @Test fun aClosedQueueReturnsPixelsImmediately() = runTest {
        val pixels = Pixels()
        val result = runCatching {
            uploadOnOwnerQueue(pixels, { false }, { error("Upload cannot run") }, {})
        }
        assertTrue(result.isFailure)
        assertEquals(1, pixels.closes)
    }

    @Test fun successTransfersOnlyTheTextureToTheCaller() = runTest {
        val pixels = Pixels()
        val queue = ArrayDeque<Runnable>()
        val released = mutableListOf<Long>()
        var result = 0L
        launch {
            result = uploadOnOwnerQueue(pixels, { queue.addLast(it); true }, { 9L }, released::add)
        }
        runCurrent()
        queue.removeFirst().run()
        runCurrent()
        assertEquals(9L, result)
        assertEquals(1, pixels.closes)
        assertTrue(released.isEmpty())
    }

    private class Pixels(private val onClose: () -> Unit = {}) : Closeable {
        var closes = 0
        override fun close() { check(++closes == 1); onClose() }
    }
}
