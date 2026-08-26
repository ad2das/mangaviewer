package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NtkPauseAdjustedTimestampPolicyTest {
    @Test
    fun prePauseTimestampMovesByTheWholeBackgroundDuration() {
        assertEquals(1_050L, NtkPauseAdjustedTimestampPolicy.shift(50L, 100L, 1_100L))
        assertEquals(1_100L, NtkPauseAdjustedTimestampPolicy.shift(100L, 100L, 1_100L))
    }

    @Test
    fun timestampPublishedDuringPauseMovesToResumeBoundary() {
        assertEquals(1_100L, NtkPauseAdjustedTimestampPolicy.shift(500L, 100L, 1_100L))
    }

    @Test
    fun unsetOrPostResumeTimestampIsUnchanged() {
        assertEquals(0L, NtkPauseAdjustedTimestampPolicy.shift(0L, 100L, 1_100L))
        assertEquals(1_100L, NtkPauseAdjustedTimestampPolicy.shift(1_100L, 100L, 1_100L))
        assertEquals(1_200L, NtkPauseAdjustedTimestampPolicy.shift(1_200L, 100L, 1_100L))
    }

    @Test
    fun invalidPauseIntervalFailsClosed() {
        try {
            NtkPauseAdjustedTimestampPolicy.shift(50L, 200L, 100L)
            fail("Expected reversed pause interval to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
