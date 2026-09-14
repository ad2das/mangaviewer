package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** JVM contract tests for the queued main-looper engine refresh port. */
class HandlerRefreshSchedulerTest {
    @Test fun duplicateRequestsQueueExactlyOneMessage() {
        val harness = Harness()

        harness.post()
        harness.post()
        harness.post()

        assertEquals("repeated requests must coalesce into one queued message", 1, harness.queue.size)
        assertEquals("posting must not deliver inline", 0, harness.drains)
        harness.queue.runAll()
        assertEquals(1, harness.drains)
        assertEquals(0, harness.queue.size)
    }

    @Test fun deliveryIsQueuedNotRunInline() {
        val harness = Harness()

        harness.post()

        assertEquals(1, harness.queue.size)
        assertEquals(0, harness.drains)
    }

    @Test fun aRequestInsideTheCurrentCallbackIsDeliveredAfterItEnds() {
        val harness = Harness()
        harness.callbackActive = true

        harness.post()
        assertEquals("delivery inside the requesting callback is forbidden", 0, harness.drains)

        harness.callbackActive = false
        harness.queue.runAll()
        assertEquals(1, harness.drains)
        assertEquals("the drain must observe that the requesting callback already returned",
            0, harness.drainsWhileCallbackActive)
    }

    @Test fun aRequestDuringDrainQueuesExactlyOneFollowupMessage() {
        var repost = true
        lateinit var harness: Harness
        harness = Harness { if (repost) { repost = false; harness.post() } }

        harness.post()
        harness.queue.runNext()

        assertEquals(1, harness.drains)
        assertEquals("the reentrant request must queue a followup message, never nest",
            1, harness.queue.size)
        harness.queue.runNext()
        assertEquals(2, harness.drains)
        assertEquals(0, harness.queue.size)
    }

    @Test fun cancelRemovesTheQueuedMessageAndAStaleDeliveryNoOpsBeforeANewPost() {
        val harness = Harness()
        harness.post()
        val queued = harness.queue.lastPosted

        harness.cancel()
        assertEquals(0, harness.queue.size)
        harness.cancel()
        assertEquals("cancel is idempotent", 0, harness.queue.size)

        // A cancel -> new post -> run of the previous reference is not expressible on the owner
        // thread: one shared Runnable instance and one queue, so the stale run is only reachable
        // before any new post.
        queued!!.run()
        assertEquals("a removed message run before a new post must no-op", 0, harness.drains)

        harness.post()
        assertEquals("cancel leaves the scheduler usable", 1, harness.queue.size)
        harness.queue.runAll()
        assertEquals(1, harness.drains)
    }

    @Test fun aRejectedPostFailsFastAndLeavesTheSchedulerRetryable() {
        val harness = Harness()
        harness.queue.accept = false

        assertThrows(IllegalStateException::class.java) { harness.post() }

        assertEquals("the rejection must not leave a queued message", 0, harness.queue.size)
        assertEquals(0, harness.drains)

        harness.queue.accept = true
        harness.post()
        assertEquals("state must be normal so a later post can retry", 1, harness.queue.size)
        harness.queue.runAll()
        assertEquals(1, harness.drains)
    }

    private class Harness(private val onDrain: (() -> Unit)? = null) {
        val queue = FakeRefreshMessageQueue()
        var drains = 0
            private set
        var drainsWhileCallbackActive = 0
            private set
        var callbackActive = false
        val scheduler = HandlerRefreshScheduler(queue) {
            drains++
            if (callbackActive) drainsWhileCallbackActive++
            onDrain?.invoke()
        }

        fun post() = scheduler.post()
        fun cancel() = scheduler.cancel()
    }

    private class FakeRefreshMessageQueue : RefreshMessageQueue {
        private val messages = ArrayDeque<Runnable>()
        var accept = true
        var lastPosted: Runnable? = null
            private set
        val size: Int get() = messages.size

        override fun post(message: Runnable): Boolean {
            if (!accept) return false
            messages.addLast(message)
            lastPosted = message
            return true
        }

        override fun remove(message: Runnable) {
            messages.remove(message)
        }

        fun runNext() {
            messages.removeFirstOrNull()?.run()
        }

        fun runAll() {
            while (messages.isNotEmpty()) runNext()
        }
    }
}
