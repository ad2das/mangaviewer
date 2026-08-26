package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NtkAsyncTelemetryTest {
    @Test
    fun serializationRunsOffCallerAndCloseDrainsAcceptedWork() {
        val caller = Thread.currentThread()
        val serializedOffCaller = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        val telemetry = NtkAsyncTelemetry(capacity = 2, enabled = { true }, sink = { _, message ->
            serializedOffCaller.set(Thread.currentThread() !== caller)
            assertTrue(message.contains("event authority=7,value=ready,elapsedMs=9"))
            completed.countDown()
        })

        assertTrue(telemetry.offer("event", 7L, 9L) { "value=ready" })
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(serializedOffCaller.get())
        telemetry.close()
        assertTrue(telemetry.awaitDrainedForTesting(2, TimeUnit.SECONDS))
        val snapshot = telemetry.snapshot()
        assertEquals(1L, snapshot.offered)
        assertEquals(1L, snapshot.accepted)
        assertEquals(1L, snapshot.completed)
        assertEquals(0L, snapshot.dropped)
    }

    @Test
    fun boundedQueueDropsWithoutRunningSerializationOnCaller() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val thirdSerialized = AtomicBoolean(false)
        val telemetry = NtkAsyncTelemetry(capacity = 1, enabled = { true }, sink = { _, message ->
            if (message.startsWith("first ")) {
                workerEntered.countDown()
                releaseWorker.await(2, TimeUnit.SECONDS)
            }
        })

        assertTrue(telemetry.offer("first", 7L, 0L) { "one" })
        assertTrue(workerEntered.await(2, TimeUnit.SECONDS))
        assertTrue(telemetry.offer("second", 7L, 0L) { "two" })
        assertFalse(telemetry.offer("third", 7L, 0L) {
            thirdSerialized.set(true)
            "three"
        })
        assertFalse(thirdSerialized.get())
        releaseWorker.countDown()
        telemetry.close()
        assertTrue(telemetry.awaitDrainedForTesting(2, TimeUnit.SECONDS))
        val snapshot = telemetry.snapshot()
        assertEquals(3L, snapshot.offered)
        assertEquals(2L, snapshot.accepted)
        assertEquals(2L, snapshot.completed)
        assertEquals(1L, snapshot.dropped)
    }

    @Test
    fun closedLaneRejectsWithoutEvaluatingFields() {
        val evaluated = AtomicBoolean(false)
        val telemetry = NtkAsyncTelemetry(enabled = { true }, sink = { _, _ -> })
        telemetry.close()

        assertFalse(telemetry.offer("closed", 7L, 1L) {
            evaluated.set(true)
            "unused"
        })
        assertFalse(evaluated.get())
        assertEquals(1L, telemetry.snapshot().dropped)
    }

    @Test
    fun rawSchemaSerializationRunsOffCallerAndPreservesMessage() {
        val caller = Thread.currentThread()
        val completed = CountDownLatch(1)
        val serializedOffCaller = AtomicBoolean(false)
        var actual = ""
        val telemetry = NtkAsyncTelemetry(capacity = 2, enabled = { true }, sink = { _, message ->
            serializedOffCaller.set(Thread.currentThread() !== caller)
            actual = message
            completed.countDown()
        })

        assertTrue(telemetry.offerRaw("source_event", 11L) {
            "source_event sessionId=11,page=3"
        })
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        telemetry.close()

        assertTrue(serializedOffCaller.get())
        assertEquals("source_event sessionId=11,page=3", actual)
        assertEquals(0L, telemetry.snapshot().dropped)
    }
}
