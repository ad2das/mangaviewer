package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictActiveScrollDecodePolicyTest {
    @Test
    fun directWifiCurrentManhwaOffscreenDecodeSharesTheScrollGate() {
        assertTrue(
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                hostSurfaceRuntime = false,
                physicalScrollEverStarted = false,
                directWifi = true,
                currentForegroundEpisode = true,
                activeInput = true,
                anchor = false,
                manhwa = true,
            )
        )
    }

    @Test
    fun visibleAnchorAndWebtoonKeepTheirExistingDecodePath() {
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                hostSurfaceRuntime = false,
                physicalScrollEverStarted = false,
                directWifi = true,
                currentForegroundEpisode = true,
                activeInput = true,
                anchor = true,
                manhwa = true,
            )
        )
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                hostSurfaceRuntime = false,
                physicalScrollEverStarted = false,
                directWifi = true,
                currentForegroundEpisode = true,
                activeInput = true,
                anchor = false,
                manhwa = false,
            )
        )
    }

    @Test
    fun mobileSniAdjacentAndIdleDecodesRemainUnchanged() {
        val cases = listOf(
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                hostSurfaceRuntime = false,
                physicalScrollEverStarted = false,
                directWifi = false,
                currentForegroundEpisode = true,
                activeInput = true,
                anchor = false,
                manhwa = true,
            ),
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                hostSurfaceRuntime = false,
                physicalScrollEverStarted = false,
                directWifi = true,
                currentForegroundEpisode = false,
                activeInput = true,
                anchor = false,
                manhwa = true,
            ),
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                hostSurfaceRuntime = false,
                physicalScrollEverStarted = false,
                directWifi = true,
                currentForegroundEpisode = true,
                activeInput = false,
                anchor = false,
                manhwa = true,
            ),
        )
        cases.forEach(::assertFalse)
    }

    @Test
    fun interactiveHostCapsEveryCurrentEpisodeContentTypeAcrossGestureGaps() {
        listOf(false, true).forEach { manhwa ->
            assertTrue(
                NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                    hostSurfaceRuntime = true,
                    physicalScrollEverStarted = true,
                    directWifi = false,
                    currentForegroundEpisode = true,
                    activeInput = false,
                    anchor = false,
                    manhwa = manhwa,
                )
            )
        }
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                hostSurfaceRuntime = true,
                physicalScrollEverStarted = false,
                directWifi = false,
                currentForegroundEpisode = true,
                activeInput = false,
                anchor = false,
                manhwa = false,
            )
        )
    }
}
