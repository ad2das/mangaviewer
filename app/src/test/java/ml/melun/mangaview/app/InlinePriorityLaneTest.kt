package ml.melun.mangaview.app

import android.os.Process
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The inline decode wrap is thread-scoped: both the entry and the exit write must land on the
 * thread that runs the decode, with no suspension in between, and the exit write must restore the
 * pool's DEFAULT baseline rather than a captured "previous" value.
 */
class InlinePriorityLaneTest {
    private class PriorityRecorder {
        val calls = mutableListOf<Pair<Thread, Int>>()
        val setter = ThreadPriority { priority -> calls += Thread.currentThread() to priority }
    }

    @Test
    fun backgroundAndDefaultAreWrittenOnTheDecodingThread() = runBlocking {
        val recorder = PriorityRecorder()
        val lane = InlinePriorityLane(recorder.setter)
        var blockThread: Thread? = null

        lane.run { blockThread = Thread.currentThread() }

        assertEquals(2, recorder.calls.size)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, recorder.calls[0].second)
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, recorder.calls[1].second)
        assertSame("Entry and exit writes must share the executing thread", recorder.calls[0].first, recorder.calls[1].first)
        assertSame("The decode region must run between the writes", recorder.calls[0].first, blockThread)
    }

    @Test
    fun defaultIsRestoredWhenTheDecodeRegionThrows() = runBlocking {
        val recorder = PriorityRecorder()
        val lane = InlinePriorityLane(recorder.setter)

        try {
            lane.run { throw IOException("native decode failed") }
        } catch (_: IOException) {
        }

        assertEquals(
            listOf(Process.THREAD_PRIORITY_BACKGROUND, Process.THREAD_PRIORITY_DEFAULT),
            recorder.calls.map { it.second },
        )
        assertSame("Restore must run on the failing decode's thread", recorder.calls[0].first, recorder.calls[1].first)
    }
}
