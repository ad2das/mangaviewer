package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.*
import org.junit.Test

class EngineFrameCadenceTest {
    @Test fun slowSubmissionDoesNotRequireAnotherDisplayInterval() {
        assertTrue(EngineFrameCadence.due(100_000_000, 140_000_000, 60F))
        assertFalse(EngineFrameCadence.due(100_000_000, 108_000_000, 60F))
    }

    @Test fun fractionalRefreshIntervalNeverPermitsEarlySubmission() {
        assertFalse(EngineFrameCadence.due(100_000_000, 116_666_666, 60F))
        assertTrue(EngineFrameCadence.due(100_000_000, 116_666_667, 60F))
        assertFalse(EngineFrameCadence.due(100_000_000, 108_333_333, 120F))
        assertTrue(EngineFrameCadence.due(100_000_000, 108_333_334, 120F))
    }

    @Test fun firstSceneCanRenderWithoutAnArtificialVsyncDelay() {
        assertTrue(EngineFrameCadence.due(0, 1, 60F))
    }

    @Test fun reversedClockAndInvalidRefreshRatesFail() {
        assertThrows(IllegalArgumentException::class.java) { EngineFrameCadence.due(100, 99, 60F) }
        for (rate in listOf(0F, -1F, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { EngineFrameCadence.due(100, 101, rate) }
        }
    }
}
