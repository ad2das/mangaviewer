package ml.melun.mangaview.viewer.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerFlingDriverTest {
    @Test fun releaseAdvancesInTheFirstAvailableFrameWithoutDuplicatingElapsedDistance() {
        val scheduler = SchedulerHarness()
        val emissions = mutableListOf<Emission>()
        val observations = mutableListOf<Pair<Long, Long>>()
        val driver = ViewerFlingDriver(scheduler, { displacement, velocity, frameTime, expected, vsync ->
            emissions += Emission(displacement, velocity, frameTime, expected, vsync)
            true
        }, { sequence, frameTime -> observations += sequence to frameTime }, {})
        val released = 1_008_000_000L
        val first = 1_016_666_667L
        val second = 1_033_333_334L

        assertTrue(driver.startFromRelease(6_000.0, released, first, 41, 71, first + 8_000_000))
        assertEquals(1, emissions.size)
        assertEquals(first, emissions.single().frameTime)
        assertEquals(71, emissions.single().vsync)
        assertEquals(first + 8_000_000, emissions.single().expectedPresentation)
        assertEquals(listOf(41L to first), observations)
        assertEquals(1, scheduler.postCount)
        scheduler.deliver(second)

        val expected = ViewerFlingPhysics.advance(6_000.0, (second - released) / 1_000_000_000.0)
        assertEquals(expected.displacementPixels, emissions.sumOf { it.displacement }, 1e-9)
        assertEquals(listOf(41L to first, 41L to second), observations)
        assertTrue(scheduler.scheduled)
    }

    @Test fun releaseAfterTheVsyncKeepsItsOriginUntilAFutureFrame() {
        val scheduler = SchedulerHarness()
        val emissions = mutableListOf<Double>()
        val driver = ViewerFlingDriver(scheduler, { displacement, _, _, _, _ ->
            emissions += displacement; true
        }, { _, _ -> }, {})
        val released = 1_020_000_000L
        assertTrue(driver.startFromRelease(6_000.0, released, 1_016_666_667L, 1, 71, 1_025_000_000L))
        assertTrue(emissions.isEmpty())
        scheduler.deliver(1_019_000_000L)
        assertTrue(emissions.isEmpty())
        scheduler.deliver(1_033_333_334L)
        val expected = ViewerFlingPhysics.advance(6_000.0, .013333334)
        assertEquals(expected.displacementPixels, emissions.single(), 1e-9)
        assertTrue(scheduler.scheduled)
    }

    @Test fun boundaryAtReleaseCancelsThePendingFlingWithoutReportingMotion() {
        val scheduler = SchedulerHarness()
        var finished = 0
        val driver = ViewerFlingDriver(scheduler, { _, _, _, _, _ -> false },
            { _, _ -> throw AssertionError("Boundary hold is not movement") }, { finished++ })
        assertFalse(driver.startFromRelease(6_000.0, 1_000_000_000L, 1_016_666_667L, 1, 71, 1_025_000_000L))
        assertEquals(1, finished)
        assertFalse(scheduler.scheduled)
        assertFalse(scheduler.deliverIfScheduled(1_033_333_334L))
    }

    @Test fun nextFrameIsArmedInsideTheCallbackBeforeEmissionWork() {
        val scheduler = SchedulerHarness()
        val emissions = mutableListOf<Emission>()
        val observations = mutableListOf<Pair<Long, Long>>()
        val emissionEntered = CountDownLatch(1)
        val releaseEmission = CountDownLatch(1)
        val driver = ViewerFlingDriver(scheduler, { displacement, velocity, frameTime, expected, vsync ->
            emissions += Emission(displacement, velocity, frameTime, expected, vsync)
            emissionEntered.countDown()
            check(releaseEmission.await(5, TimeUnit.SECONDS))
            true
        }, { sequence, frameTime -> observations += sequence to frameTime }, {})
        val previous = 1_000_000_000L
        val current = previous + 16_666_667L

        assertTrue(driver.start(6_000.0, previous, 41))
        val callbackFailure = AtomicReference<Throwable?>()
        val callback = thread(name = "fling-callback-test") {
            try { scheduler.deliver(current, 71, current + 8_000_000) }
            catch (failure: Throwable) { callbackFailure.set(failure) }
        }
        assertTrue("Emission did not begin", emissionEntered.await(5, TimeUnit.SECONDS))
        try {
            assertTrue("Successor frame was not armed while synchronous emission was blocked", scheduler.scheduled)
            assertEquals(listOf("post", "deliver", "post"), scheduler.events)
        } finally {
            releaseEmission.countDown()
        }
        callback.join(5_000)
        assertFalse("Fling callback did not finish", callback.isAlive)
        callbackFailure.get()?.let { throw AssertionError("Fling callback failed", it) }

        val expected = ViewerFlingPhysics.advance(6_000.0, 16_666_667.0 / 1_000_000_000.0)
        assertEquals(1, emissions.size)
        assertEquals(expected.displacementPixels, emissions.single().displacement, 0.0)
        assertEquals(6_000.0, emissions.single().velocity, 0.0)
        assertEquals(current, emissions.single().frameTime)
        assertEquals(71, emissions.single().vsync)
        assertEquals(current + 8_000_000, emissions.single().expectedPresentation)
        assertEquals(listOf(41L to current), observations)
        assertTrue(scheduler.scheduled)
        assertEquals(2, scheduler.postCount)
    }

    @Test fun rejectedEmissionCancelsTheForwardArmWithoutAnotherSample() {
        val scheduler = SchedulerHarness()
        var emissions = 0
        var finished = 0
        val driver = ViewerFlingDriver(scheduler, { _, _, _, _, _ -> emissions++; false },
            { _, _ -> throw AssertionError("Rejected emission must not be observed") }, { finished++ })

        assertTrue(driver.start(6_000.0, 1_000_000_000L, 1))
        scheduler.deliver(1_016_666_667L)

        assertEquals(1, emissions)
        assertEquals(1, finished)
        assertFalse(scheduler.scheduled)
        assertEquals(1, scheduler.cancelCount)
        assertFalse(scheduler.deliverIfScheduled(1_033_333_334L))
        assertEquals(1, emissions)
    }

    @Test fun terminalPhysicsStepCancelsTheForwardArmAndEmitsExactlyOnce() {
        val scheduler = SchedulerHarness()
        val emissions = mutableListOf<Emission>()
        var observations = 0
        var finished = 0
        val driver = ViewerFlingDriver(scheduler, { displacement, velocity, frameTime, expected, vsync ->
            emissions += Emission(displacement, velocity, frameTime, expected, vsync)
            true
        }, { _, _ -> observations++ }, { finished++ })

        assertTrue(driver.start(25.0, 1_000_000_000L, 1))
        scheduler.deliver(2_000_000_000L)

        val expected = ViewerFlingPhysics.advance(25.0, 1.0)
        assertTrue(expected.velocityPixelsPerSecond < 24.0)
        assertEquals(1, emissions.size)
        assertEquals(expected.displacementPixels, emissions.single().displacement, 0.0)
        assertEquals(25.0, emissions.single().velocity, 0.0)
        assertEquals(1, observations)
        assertEquals(1, finished)
        assertFalse(scheduler.scheduled)
        assertEquals(1, scheduler.cancelCount)
        assertFalse(scheduler.deliverIfScheduled(3_000_000_000L))
        assertEquals(1, emissions.size)
    }

    private data class Emission(
        val displacement: Double,
        val velocity: Double,
        val frameTime: Long,
        val expectedPresentation: Long,
        val vsync: Long,
    )

    private class SchedulerHarness : ViewerFrameSchedulerFactory, ViewerFrameScheduler {
        val events = mutableListOf<String>()
        var scheduled = false
            private set
        var postCount = 0
            private set
        var cancelCount = 0
            private set
        private lateinit var callback: (Long, Long, Long) -> Unit

        override fun create(callback: (Long, Long, Long) -> Unit): ViewerFrameScheduler = this.also {
            this.callback = callback
        }

        override fun post() {
            if (scheduled) return
            scheduled = true
            postCount++
            events += "post"
        }

        override fun cancel() {
            if (!scheduled) return
            scheduled = false
            cancelCount++
            events += "cancel"
        }

        fun deliver(frameTime: Long, vsync: Long = -1, expectedPresentation: Long = frameTime) {
            check(deliverIfScheduled(frameTime, vsync, expectedPresentation))
        }

        fun deliverIfScheduled(
            frameTime: Long,
            vsync: Long = -1,
            expectedPresentation: Long = frameTime,
        ): Boolean {
            if (!scheduled) return false
            scheduled = false
            events += "deliver"
            callback(frameTime, vsync, expectedPresentation)
            return true
        }
    }
}
