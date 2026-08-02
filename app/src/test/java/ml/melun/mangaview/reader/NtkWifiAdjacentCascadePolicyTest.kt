package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkWifiAdjacentCascadePolicyTest {
    @Test
    fun wifiAlwaysPreparesTheFirstAdjacentEpisode() {
        assertTrue(
            NtkWifiAdjacentCascadePolicy.shouldStart(
                wifiTransportActive = true,
                initialEpisode = true,
                activeSourcePageIndex = 0,
            )
        )
    }

    @Test
    fun wifiDefersAThirdEpisodeUntilTheNewEpisodeIsActuallyRead() {
        assertFalse(
            NtkWifiAdjacentCascadePolicy.shouldStart(
                wifiTransportActive = true,
                initialEpisode = false,
                activeSourcePageIndex =
                    NtkWifiAdjacentCascadePolicy.MIN_ACTIVE_SOURCE_PAGE_INDEX - 1,
            )
        )
        assertTrue(
            NtkWifiAdjacentCascadePolicy.shouldStart(
                wifiTransportActive = true,
                initialEpisode = false,
                activeSourcePageIndex =
                    NtkWifiAdjacentCascadePolicy.MIN_ACTIVE_SOURCE_PAGE_INDEX,
            )
        )
    }

    @Test
    fun directWifiStartsTheFollowingEpisodeAsSoonAsCurrentIsComplete() {
        assertTrue(
            NtkWifiAdjacentCascadePolicy.shouldStart(
                wifiTransportActive = true,
                initialEpisode = false,
                directWifiCurrentEpisodeComplete = true,
                activeSourcePageIndex =
                    NtkWifiAdjacentCascadePolicy.MIN_ACTIVE_SOURCE_PAGE_INDEX - 1,
            )
        )
    }

    @Test
    fun mobileBehaviorIsUnchanged() {
        assertTrue(
            NtkWifiAdjacentCascadePolicy.shouldStart(
                wifiTransportActive = false,
                initialEpisode = false,
                activeSourcePageIndex = 0,
            )
        )
    }
}
