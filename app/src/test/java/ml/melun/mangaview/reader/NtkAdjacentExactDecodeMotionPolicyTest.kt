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

    @Test
    fun privateExactHeadMayDecodeWithoutPublishingStructureDuringMotion() {
        assertFalse(
            NtkAdjacentExactDecodeMotionPolicy.shouldDefer(
                directWifiRunwayProfile = true,
                reason = NtkAdjacentExactDecodeMotionPolicy.PRIVATE_EXACT_HEAD_STAGING_REASON,
                adjacentManhwaPages = true,
            ),
        )
    }

    @Test
    fun physicalRequestRedrivesARegisteredOwnerWithNoWorkerOrWakeEdge() {
        assertTrue(
            NtkAdjacentExactRehydrateLivenessPolicy.shouldRedriveIdleOwner(
                exactPhysicalIntent = true,
                parked = false,
                scheduledOrRunning = false,
            ),
        )
        assertFalse(
            NtkAdjacentExactRehydrateLivenessPolicy.shouldRedriveIdleOwner(
                exactPhysicalIntent = true,
                parked = true,
                scheduledOrRunning = false,
            ),
        )
        assertFalse(
            NtkAdjacentExactRehydrateLivenessPolicy.shouldRedriveIdleOwner(
                exactPhysicalIntent = true,
                parked = false,
                scheduledOrRunning = true,
            ),
        )
        assertFalse(
            NtkAdjacentExactRehydrateLivenessPolicy.shouldRedriveIdleOwner(
                exactPhysicalIntent = false,
                parked = false,
                scheduledOrRunning = false,
            ),
        )
    }

    @Test
    fun publishedInitialRunwayKeepsOwnershipWhenItsOwnFirstPageWinsTheRace() {
        assertTrue(
            NtkPublishedInitialRunwayCompletionPolicy.stillOwnsPixelCompletion(
                firstActualFramePresented = false,
                latestEnteredEpisodeMatches = false,
            ),
        )
        assertTrue(
            NtkPublishedInitialRunwayCompletionPolicy.stillOwnsPixelCompletion(
                firstActualFramePresented = true,
                latestEnteredEpisodeMatches = true,
            ),
        )
        assertFalse(
            NtkPublishedInitialRunwayCompletionPolicy.stillOwnsPixelCompletion(
                firstActualFramePresented = true,
                latestEnteredEpisodeMatches = false,
            ),
        )
    }
}
