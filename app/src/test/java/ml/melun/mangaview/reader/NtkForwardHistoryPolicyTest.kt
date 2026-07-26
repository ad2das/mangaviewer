package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkForwardHistoryPolicyTest {
    @Test
    fun keepsBoundaryUntilThirdRealImageOfNextEpisode() {
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(86, 0, true))
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(86, 1, true))
        assertEquals(86, NtkForwardHistoryPolicy.removablePrefix(86, 2, true))
    }

    @Test
    fun neverTrimsCurrentFirstEpisodeOrReverseReading() {
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(0, 20, true))
        assertEquals(0, NtkForwardHistoryPolicy.removablePrefix(86, 20, false))
    }
}
