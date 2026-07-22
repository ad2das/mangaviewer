package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictSourceOperationTelemetryPolicyTest {
    @Test
    fun successfulStripOperationsAreSampledWithoutHidingFailures() {
        assertTrue(NtkStrictSourceOperationTelemetryPolicy.shouldLogStart(0))
        assertFalse(NtkStrictSourceOperationTelemetryPolicy.shouldLogStart(1))
        assertFalse(NtkStrictSourceOperationTelemetryPolicy.shouldLogEnd(53, true, false))
        assertTrue(NtkStrictSourceOperationTelemetryPolicy.shouldLogEnd(53, false, false))
        assertTrue(NtkStrictSourceOperationTelemetryPolicy.shouldLogEnd(53, true, true))
        assertTrue(NtkStrictSourceOperationTelemetryPolicy.shouldLogEnd(0, true, false))
        assertTrue(NtkStrictSourceOperationTelemetryPolicy.shouldLogSuccessfulAdoption(0))
        assertFalse(NtkStrictSourceOperationTelemetryPolicy.shouldLogSuccessfulAdoption(53))
    }
}
