package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtkStrictTerminalDecodePolicyTest {
    @Test
    fun `current direct wifi webtoon may use seven after every body is resident`() {
        assertEquals(
            7,
            NtkStrictTerminalDecodePolicy.expandedParallelism(
                rollingPixelResidency = false,
                terminalBacklog = 7,
                directWifi = true,
                currentForegroundEpisode = true,
                webtoon = true,
            ),
        )
    }

    @Test
    fun `mobile sni adjacent and manhwa keep the proven five worker tail`() {
        val inputs = listOf(
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, false),
        )
        inputs.forEach { (directWifi, currentForegroundEpisode, webtoon) ->
            assertEquals(
                5,
                NtkStrictTerminalDecodePolicy.expandedParallelism(
                    rollingPixelResidency = false,
                    terminalBacklog = 20,
                    directWifi = directWifi,
                    currentForegroundEpisode = currentForegroundEpisode,
                    webtoon = webtoon,
                ),
            )
        }
    }

    @Test
    fun `six page wifi tail and ordinary eleven page tail never expand`() {
        assertNull(
            NtkStrictTerminalDecodePolicy.expandedParallelism(
                rollingPixelResidency = false,
                terminalBacklog = 6,
                directWifi = true,
                currentForegroundEpisode = true,
                webtoon = true,
            ),
        )
        assertNull(
            NtkStrictTerminalDecodePolicy.expandedParallelism(
                rollingPixelResidency = false,
                terminalBacklog = 11,
                directWifi = false,
                currentForegroundEpisode = true,
                webtoon = true,
            ),
        )
    }

    @Test
    fun `rolling residency never expands`() {
        assertNull(
            NtkStrictTerminalDecodePolicy.expandedParallelism(
                rollingPixelResidency = true,
                terminalBacklog = 50,
                directWifi = true,
                currentForegroundEpisode = true,
                webtoon = true,
            ),
        )
    }
}
