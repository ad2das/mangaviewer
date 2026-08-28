package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictActiveScrollDecodePolicyTest {
    @Test
    fun hostOffscreenPixelsWaitThroughTheFirstHumanInteractionWindow() {
        val firstFrameAt = 10_000L
        assertTrue(
            NtkStrictActiveScrollDecodePolicy.shouldHoldHostOffscreenBeforeFirstInteraction(
                hostSurfaceRuntime = true,
                currentForegroundEpisode = true,
                anchor = false,
                initialScrollRunway = false,
                physicalScrollEverStarted = false,
                firstPhysicalFrameAtMs = firstFrameAt,
                nowMs = firstFrameAt + 1_499L,
            )
        )
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldHoldHostOffscreenBeforeFirstInteraction(
                hostSurfaceRuntime = true,
                currentForegroundEpisode = true,
                anchor = false,
                initialScrollRunway = false,
                physicalScrollEverStarted = false,
                firstPhysicalFrameAtMs = firstFrameAt,
                nowMs = firstFrameAt + 1_500L,
            )
        )
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldHoldHostOffscreenBeforeFirstInteraction(
                hostSurfaceRuntime = true,
                currentForegroundEpisode = true,
                anchor = false,
                initialScrollRunway = false,
                physicalScrollEverStarted = true,
                firstPhysicalFrameAtMs = firstFrameAt,
                nowMs = firstFrameAt + 1L,
            )
        )
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldHoldHostOffscreenBeforeFirstInteraction(
                hostSurfaceRuntime = true,
                currentForegroundEpisode = true,
                anchor = false,
                initialScrollRunway = true,
                physicalScrollEverStarted = false,
                firstPhysicalFrameAtMs = 0L,
                nowMs = 0L,
            )
        )
    }

    @Test
    fun missingPhysicalCallbackUsesFiniteAnchorPixelGrace() {
        val anchorPixelsAt = 20_000L
        assertTrue(
            NtkStrictActiveScrollDecodePolicy.shouldHoldHostOffscreenBeforeFirstInteraction(
                hostSurfaceRuntime = true,
                currentForegroundEpisode = true,
                anchor = false,
                initialScrollRunway = false,
                physicalScrollEverStarted = false,
                firstPhysicalFrameAtMs = 0L,
                firstAnchorPixelsAtMs = anchorPixelsAt,
                nowMs = anchorPixelsAt + 1_499L,
            )
        )
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldHoldHostOffscreenBeforeFirstInteraction(
                hostSurfaceRuntime = true,
                currentForegroundEpisode = true,
                anchor = false,
                initialScrollRunway = false,
                physicalScrollEverStarted = false,
                firstPhysicalFrameAtMs = 0L,
                firstAnchorPixelsAtMs = anchorPixelsAt,
                nowMs = anchorPixelsAt + 1_500L,
            )
        )
    }

    @Test
    fun initialViewportCompletionNeverWaitsOnItsOwnPhysicalFrame() {
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldHoldHostOffscreenBeforeFirstInteraction(
                hostSurfaceRuntime = true,
                currentForegroundEpisode = true,
                anchor = false,
                initialScrollRunway = false,
                initialViewportRequired = true,
                physicalScrollEverStarted = false,
                firstPhysicalFrameAtMs = 0L,
                firstAnchorPixelsAtMs = 0L,
                nowMs = 0L,
            )
        )
    }

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
    fun interactiveHostParksEveryCurrentEpisodeContentTypeUntilQuiet() {
        listOf(false, true).forEach { manhwa ->
            assertTrue(
                NtkStrictActiveScrollDecodePolicy.shouldDeferHostOffscreenUntilQuiet(
                    hostSurfaceRuntime = true,
                    physicalScrollEverStarted = true,
                    currentForegroundEpisode = true,
                    anchor = false,
                )
            )
            assertFalse(
                NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                    hostSurfaceRuntime = true,
                    physicalScrollEverStarted = true,
                    directWifi = true,
                    currentForegroundEpisode = true,
                    activeInput = true,
                    anchor = false,
                    manhwa = manhwa,
                )
            )
        }
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldDeferHostOffscreenUntilQuiet(
                hostSurfaceRuntime = true,
                physicalScrollEverStarted = false,
                currentForegroundEpisode = true,
                anchor = false,
            )
        )
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldDeferHostOffscreenUntilQuiet(
                hostSurfaceRuntime = true,
                physicalScrollEverStarted = true,
                currentForegroundEpisode = true,
                anchor = true,
            )
        )
    }
}
