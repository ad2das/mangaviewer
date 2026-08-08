package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkHostGpuEmulatorCurrentWebtoonLanePolicyTest {
    private fun cap(
        progressiveLaneCount: Int = 60,
        emulatorRuntime: Boolean = true,
        directWifiTransport: Boolean = true,
        cellularResilientTransport: Boolean = false,
        webtoon: Boolean = true,
        rollingAdmission: Boolean = true,
        initialPageIndex: Int = 30,
        currentForegroundEpisode: Boolean = true,
        adjacentPrefetch: Boolean = false,
        anchorBodyPublished: Boolean = true,
    ): Int = NtkHostGpuEmulatorCurrentWebtoonLanePolicy.cap(
        progressiveLaneCount,
        emulatorRuntime,
        directWifiTransport,
        cellularResilientTransport,
        webtoon,
        rollingAdmission,
        initialPageIndex,
        currentForegroundEpisode,
        adjacentPrefetch,
        anchorBodyPublished,
    )

    @Test
    fun capsOnlyPostAnchorHostEmulatorCurrentWebtoonResume() {
        assertEquals(24, cap(progressiveLaneCount = 60))
        assertEquals(24, cap(progressiveLaneCount = 64))
        assertEquals(18, cap(progressiveLaneCount = 18))

        assertEquals(60, cap(emulatorRuntime = false))
        assertEquals(60, cap(directWifiTransport = false))
        assertEquals(60, cap(cellularResilientTransport = true))
        assertEquals(60, cap(webtoon = false))
        assertEquals(60, cap(rollingAdmission = false))
        assertEquals(60, cap(initialPageIndex = 0))
        assertEquals(60, cap(currentForegroundEpisode = false))
        assertEquals(60, cap(adjacentPrefetch = true))
        assertEquals(60, cap(anchorBodyPublished = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLaneCount() {
        cap(progressiveLaneCount = -1)
    }
}
