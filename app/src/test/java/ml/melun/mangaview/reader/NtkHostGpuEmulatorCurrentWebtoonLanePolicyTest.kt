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
        contiguousForwardBodyCount: Int =
            NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES,
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
        contiguousForwardBodyCount,
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

    @Test
    fun reservesBalancedNineLaneWaveUntilTheVisibleForwardRunwayIsResident() {
        assertEquals(9, cap(contiguousForwardBodyCount = 0))
        assertEquals(9, cap(contiguousForwardBodyCount = 5))
        assertEquals(
            8,
            cap(progressiveLaneCount = 8, contiguousForwardBodyCount = 0),
        )
        assertEquals(24, cap(contiguousForwardBodyCount = 6))
        assertEquals(24, cap(contiguousForwardBodyCount = 20))

        // The runway stage is fenced by the same exact profile as the one-body-per-pool cap.
        assertEquals(60, cap(
            emulatorRuntime = false,
            contiguousForwardBodyCount = 0,
        ))
        assertEquals(60, cap(
            adjacentPrefetch = true,
            contiguousForwardBodyCount = 0,
        ))
    }

    @Test
    fun openingWaveCannotSkipPastItsContiguousViewportPages() {
        assertEquals(true, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.allowsOpeningWavePage(
            pageIndex = 38,
            pageCount = 76,
            initialPageIndex = 38,
            initialWaveTarget = 3,
            openingWaveIncomplete = true,
            eligible = true,
        ))
        assertEquals(true, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.allowsOpeningWavePage(
            pageIndex = 40,
            pageCount = 76,
            initialPageIndex = 38,
            initialWaveTarget = 3,
            openingWaveIncomplete = true,
            eligible = true,
        ))
        assertEquals(false, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.allowsOpeningWavePage(
            pageIndex = 41,
            pageCount = 76,
            initialPageIndex = 38,
            initialWaveTarget = 3,
            openingWaveIncomplete = true,
            eligible = true,
        ))
        assertEquals(true, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.allowsOpeningWavePage(
            pageIndex = 41,
            pageCount = 76,
            initialPageIndex = 38,
            initialWaveTarget = 3,
            openingWaveIncomplete = false,
            eligible = true,
        ))
        assertEquals(true, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.allowsOpeningWavePage(
            pageIndex = 41,
            pageCount = 76,
            initialPageIndex = 38,
            initialWaveTarget = 3,
            openingWaveIncomplete = true,
            eligible = false,
        ))
    }

    @Test
    fun permitsOnlyOnePairedAnchorPoolStreamInsideTheFiniteOpeningWave() {
        val limit = NtkHostGpuEmulatorCurrentWebtoonLanePolicy
            .openingWaveAnchorPoolOperationLimit(
                ordinaryLimit = 1,
                eligible = true,
                openingWaveIncomplete = true,
                pageInOpeningWave = true,
            )
        assertEquals(2, limit)
        assertEquals(1, NtkHostGpuEmulatorCurrentWebtoonLanePolicy
            .openingWaveAnchorPoolOperationLimit(1, false, true, true))
        assertEquals(1, NtkHostGpuEmulatorCurrentWebtoonLanePolicy
            .openingWaveAnchorPoolOperationLimit(1, true, false, true))
        assertEquals(1, NtkHostGpuEmulatorCurrentWebtoonLanePolicy
            .openingWaveAnchorPoolOperationLimit(1, true, true, false))
        assertEquals(4, NtkHostGpuEmulatorCurrentWebtoonLanePolicy
            .openingWaveAnchorPoolOperationLimit(4, true, true, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLaneCount() {
        cap(progressiveLaneCount = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeForwardBodyCount() {
        cap(contiguousForwardBodyCount = -1)
    }
}
