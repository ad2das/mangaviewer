package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentMetadataControlPolicyTest {
    @Test
    fun directWifiManhwaMayFetchOnlyItsControlMetadataEarly() {
        assertTrue(
            NtkAdjacentMetadataControlPolicy.mayOpenAtFlightAdmission(
                directWifiAdjacentBodyGate = true,
                targetEpisodePath = "/manhwa/2/u-mqz1l7df-bsc8",
            )
        )
    }

    @Test
    fun numericManhwaSharesTheSameBodyWall() {
        assertTrue(
            NtkAdjacentMetadataControlPolicy.mayOpenAtFlightAdmission(
                directWifiAdjacentBodyGate = true,
                targetEpisodePath = "/manhwa/2/12345",
            )
        )
    }

    @Test
    fun webtoonAndNonDirectWifiStayCompletionGated() {
        assertFalse(
            NtkAdjacentMetadataControlPolicy.mayOpenAtFlightAdmission(
                directWifiAdjacentBodyGate = true,
                targetEpisodePath = "/webtoon/work/u-mqz1l7df-bsc8",
            )
        )
        assertFalse(
            NtkAdjacentMetadataControlPolicy.mayOpenAtFlightAdmission(
                directWifiAdjacentBodyGate = false,
                targetEpisodePath = "/manhwa/2/u-mqz1l7df-bsc8",
            )
        )
    }
}
