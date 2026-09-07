package ml.melun.mangaview.source.wfwf

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WfwfTransportRaceTest {
    @Test
    fun internalDeadlineIsAnIoFailureRatherThanAUserCancellation() = runTest {
        try {
            executeWfwfHedged(
                timeoutMillis = 100L,
                hedgeDelayMillis = 0L,
                alternateDelayMillis = 0L,
                primaryRequest = { awaitCancellation() },
                recoveryRequest = { awaitCancellation() },
                alternateRequest = { awaitCancellation() },
            )
            fail("The route race should time out")
        } catch (failure: Throwable) {
            assertTrue("Expected IOException but was ${failure::class.java.name}", failure is IOException)
        }
    }
}
