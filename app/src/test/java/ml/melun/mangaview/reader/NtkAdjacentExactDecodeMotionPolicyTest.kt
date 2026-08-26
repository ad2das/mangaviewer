package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentExactDecodeMotionPolicyTest {
    @Test
    fun directWifiAdjacentMangaDefersInitialAndRemainingDecodeDuringMotion() {
        assertTrue(
            NtkAdjacentExactDecodeMotionPolicy.shouldDefer(
                directWifiRunwayProfile = true,
                reason = "append_initial_runway_prepare_before_publish",
                adjacentManhwaPages = true,
            ),
        )
        assertTrue(
            NtkAdjacentExactDecodeMotionPolicy.shouldDefer(
                directWifiRunwayProfile = true,
                reason = "append_runway_remaining_publish",
                adjacentManhwaPages = true,
            ),
        )
    }

    @Test
    fun webtoonRemainderAndNonDirectProfilesKeepTheirExistingAdmission() {
        assertFalse(
            NtkAdjacentExactDecodeMotionPolicy.shouldDefer(
                directWifiRunwayProfile = true,
                reason = "append_runway_remaining_publish",
                adjacentManhwaPages = false,
            ),
        )
        assertFalse(
            NtkAdjacentExactDecodeMotionPolicy.shouldDefer(
                directWifiRunwayProfile = false,
                reason = "append_initial_runway_prepare_before_publish",
                adjacentManhwaPages = true,
            ),
        )
    }

    @Test
    fun existingInitialStrictSourceDeferralRemainsForDirectWifi() {
        assertTrue(
            NtkAdjacentExactDecodeMotionPolicy.shouldDefer(
                directWifiRunwayProfile = true,
                reason = "initial_strict_source",
                adjacentManhwaPages = false,
            ),
        )
    }
}
