package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictActiveScrollDecodePolicyTest {
    @Test
    fun directWifiCurrentManhwaOffscreenDecodeSharesTheScrollGate() {
        assertTrue(
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
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
                directWifi = true,
                currentForegroundEpisode = true,
                activeInput = true,
                anchor = true,
                manhwa = true,
            )
        )
        assertFalse(
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
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
                directWifi = false,
                currentForegroundEpisode = true,
                activeInput = true,
                anchor = false,
                manhwa = true,
            ),
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                directWifi = true,
                currentForegroundEpisode = false,
                activeInput = true,
                anchor = false,
                manhwa = true,
            ),
            NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate(
                directWifi = true,
                currentForegroundEpisode = true,
                activeInput = false,
                anchor = false,
                manhwa = true,
            ),
        )
        cases.forEach(::assertFalse)
    }
}
