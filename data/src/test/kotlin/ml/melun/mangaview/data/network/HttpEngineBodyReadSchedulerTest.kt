package ml.melun.mangaview.data.network

import ml.melun.mangaview.source.PageFetchPriority
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpEngineBodyReadSchedulerTest {
    @Test
    fun visibleReadTakesTheFirstFreedSlotAheadOfForwardWork() {
        val scheduler = HttpEngineBodyReadScheduler(2)
        val starts = mutableListOf<String>()
        val first = scheduler.schedule("first", PageFetchPriority.FORWARD) { starts += "first" }
        scheduler.schedule("second", PageFetchPriority.FORWARD) { starts += "second" }
        scheduler.schedule("third", PageFetchPriority.FORWARD) { starts += "third" }
        scheduler.schedule("visible", PageFetchPriority.VISIBLE) { starts += "visible" }

        assertEquals(listOf("first", "second"), starts)
        first.finish()
        assertEquals(listOf("first", "second", "visible"), starts)
    }

    @Test
    fun promotionReordersAQueuedExistingBodyWithoutDuplicatingIt() {
        val scheduler = HttpEngineBodyReadScheduler(1)
        val starts = mutableListOf<String>()
        val promotedOwner = Any()
        val running = scheduler.schedule("running", PageFetchPriority.FORWARD) { starts += "running" }
        scheduler.schedule(promotedOwner, PageFetchPriority.FORWARD) { starts += "promoted" }
        scheduler.schedule("older", PageFetchPriority.FORWARD) { starts += "older" }

        scheduler.promote(promotedOwner, PageFetchPriority.FOCUS)
        running.finish()

        assertEquals(listOf("running", "promoted"), starts)
    }

    @Test
    fun nearForwardReadFinishesBeforeAnOlderDistantForwardRead() {
        val scheduler = HttpEngineBodyReadScheduler(1)
        val starts = mutableListOf<String>()
        val running = scheduler.schedule("running", PageFetchPriority.FOCUS) { starts += "running" }
        scheduler.schedule("distant", PageFetchPriority.DISTANT_FORWARD) { starts += "distant" }
        scheduler.schedule("near", PageFetchPriority.IMMINENT_FORWARD) { starts += "near" }

        running.finish()

        assertEquals(listOf("running", "near"), starts)
    }

    @Test
    fun cancellingAQueuedReadDoesNotConsumeCapacity() {
        val scheduler = HttpEngineBodyReadScheduler(1)
        val starts = mutableListOf<String>()
        val running = scheduler.schedule("running", PageFetchPriority.FOCUS) { starts += "running" }
        val cancelled = scheduler.schedule("cancelled", PageFetchPriority.VISIBLE) {
            starts += "cancelled"
        }
        scheduler.schedule("next", PageFetchPriority.FORWARD) { starts += "next" }

        cancelled.cancel()
        running.finish()

        assertEquals(listOf("running", "next"), starts)
    }
}
