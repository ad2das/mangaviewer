package ml.melun.mangaview.reader

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageCacheForegroundByteFlightTest {
    private fun flight(
        ready: () -> File? = { null },
        external: () -> Boolean = { false }
    ) = ReaderImageCache.ForegroundByteFlight(
        ReaderPagePipeline.PageKey(1L, 3),
        ReaderPagePipeline.Demand.POST_ACTIVATION_BYTES,
        ready,
        { _: File -> true },
        external,
        { it.run() },
        {}
    )

    @Test
    fun sevenSubscribersCreateOneProducerAndSixNonBlockingJoiners() {
        val flight = flight()
        val producers = AtomicInteger()
        repeat(7) { index ->
            val snapshot = flight.subscribeOrSnapshot((index + 1).toLong()) {}
            if (snapshot is ReaderImageCache.ByteFlightSnapshot.Subscribed && snapshot.startProducer) {
                producers.incrementAndGet()
            }
        }
        assertEquals(1, producers.get())
    }

    @Test
    fun existingForegroundStreamUsesNoSourceWorkerThenElectsOneOnFailure() {
        val external = AtomicBoolean(true)
        val retryEvents = AtomicInteger()
        val flight = flight(external = { external.get() })
        val snapshot = flight.subscribeOrSnapshot(1L) {
            if (it === ReaderImageCache.ByteFlightResult.RetryProducer) retryEvents.incrementAndGet()
        }
        assertTrue(snapshot is ReaderImageCache.ByteFlightSnapshot.Subscribed)
        assertFalse((snapshot as ReaderImageCache.ByteFlightSnapshot.Subscribed).startProducer)

        external.set(false)
        flight.externalStreamFinished(null)
        assertEquals(1, retryEvents.get())
        assertTrue(flight.claimProducer())
        assertFalse(flight.claimProducer())
    }

    @Test
    fun completedFileIsDeliveredExactlyOnceToEachSubscriber() {
        val result = File("synthetic-test-result.img")
        val callbacks = AtomicInteger()
        val flight = flight()
        repeat(3) { index ->
            flight.subscribeOrSnapshot((index + 1).toLong()) {
                if (it is ReaderImageCache.ByteFlightResult.Ready) callbacks.incrementAndGet()
            }
        }
        flight.runProducer { result }
        assertEquals(3, callbacks.get())
    }

    @Test
    fun publishedMetadataBodyCompletesExistingByteFlightWithoutAnotherProducer() {
        val result = File.createTempFile("ntk-metadata-body", ".img").apply {
            writeBytes(ByteArray(64) { 1 })
        }
        try {
            val callbacks = AtomicInteger()
            val flight = flight()
            val snapshot = flight.subscribeOrSnapshot(1L) {
                if (it is ReaderImageCache.ByteFlightResult.Ready && it.file == result) {
                    callbacks.incrementAndGet()
                }
            }
            assertTrue(snapshot is ReaderImageCache.ByteFlightSnapshot.Subscribed)
            assertTrue(flight.completeFromPublishedFile(result))
            assertFalse(flight.completeFromPublishedFile(result))
            assertEquals(1, callbacks.get())
            assertTrue(
                flight.subscribeOrSnapshot(2L) {} is
                    ReaderImageCache.ByteFlightSnapshot.Ready
            )
        } finally {
            result.delete()
        }
    }

    @Test
    fun demandPromotionNeverDowngradesVisibleWork() {
        val flight = flight()
        flight.promote(ReaderPagePipeline.Demand.VISIBLE)
        flight.promote(ReaderPagePipeline.Demand.FAR_TAIL)
        assertEquals(ReaderPagePipeline.Demand.VISIBLE, flight.demandForTest())
    }
}
