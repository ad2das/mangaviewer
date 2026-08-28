package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkHostGpuEmulatorCurrentWebtoonLanePolicyTest {
    @Test
    fun pixelRunwayStopsAtTheFirstUxTargetWhileTransportKeepsTheFullOpeningProof() {
        assertEquals(
            9,
            NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_VISIBLE_RUNWAY_BODIES,
        )
        assertEquals(
            6,
            NtkHostGpuEmulatorCurrentWebtoonLanePolicy.INITIAL_SCROLL_RUNWAY_BODIES,
        )
    }

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
        healthyBulkExpansion: Boolean = false,
        fragmentedTlsRecoveryQualified: Boolean = false,
        quicRecoveryQualified: Boolean = false,
        wellProvenQuicRecoveryQualified: Boolean = false,
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
        healthyBulkExpansion,
        fragmentedTlsRecoveryQualified,
        quicRecoveryQualified,
        wellProvenQuicRecoveryQualified,
    )

    @Test
    fun capsOnlyPostAnchorHostEmulatorCurrentWebtoonResume() {
        assertEquals(9, cap(progressiveLaneCount = 60))
        assertEquals(8, cap(progressiveLaneCount = 64, healthyBulkExpansion = true))
        assertEquals(8, cap(fragmentedTlsRecoveryQualified = true))
        assertEquals(8, cap(quicRecoveryQualified = true))
        assertEquals(8, cap(wellProvenQuicRecoveryQualified = true))
        assertEquals(8, cap(
            progressiveLaneCount = 18,
            fragmentedTlsRecoveryQualified = true,
        ))
        assertEquals(8, cap(progressiveLaneCount = 18, healthyBulkExpansion = true))
        assertEquals(5, cap(progressiveLaneCount = 5))

        assertEquals(60, cap(emulatorRuntime = false))
        assertEquals(60, cap(directWifiTransport = false))
        assertEquals(60, cap(cellularResilientTransport = true))
        assertEquals(60, cap(webtoon = false))
        assertEquals(60, cap(rollingAdmission = false))
        assertEquals(9, cap(initialPageIndex = 0))
        assertEquals(60, cap(currentForegroundEpisode = false))
        assertEquals(60, cap(adjacentPrefetch = true))
        assertEquals(60, cap(anchorBodyPublished = false))
    }

    @Test
    fun givesTheFiniteOpeningRunwayFourLanesThenUsesTheBoundedWave() {
        assertEquals(4, cap(contiguousForwardBodyCount = 0))
        assertEquals(4, cap(contiguousForwardBodyCount = 8))
        assertEquals(4, cap(
            contiguousForwardBodyCount = 8,
            fragmentedTlsRecoveryQualified = true,
        ))
        assertEquals(4, cap(
            contiguousForwardBodyCount = 8,
            quicRecoveryQualified = true,
        ))
        assertEquals(4, cap(
            contiguousForwardBodyCount = 8,
            wellProvenQuicRecoveryQualified = true,
        ))
        assertEquals(
            4,
            cap(progressiveLaneCount = 8, contiguousForwardBodyCount = 0),
        )
        assertEquals(
            4,
            cap(progressiveLaneCount = 1, contiguousForwardBodyCount = 0),
        )
        assertEquals(9, cap(contiguousForwardBodyCount = 9))
        assertEquals(8, cap(
            contiguousForwardBodyCount = 9,
            healthyBulkExpansion = true,
        ))
        assertEquals(8, cap(
            contiguousForwardBodyCount = 20,
            healthyBulkExpansion = true,
        ))
        assertEquals(8, cap(
            contiguousForwardBodyCount = 9,
            fragmentedTlsRecoveryQualified = true,
        ))

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
    fun keepsTheQuicProofPageAheadOfOffscreenColdCohorts() {
        assertEquals(true, NtkHostGpuEmulatorCurrentWebtoonLanePolicy
            .openingProofIncomplete(8))
        assertEquals(false, NtkHostGpuEmulatorCurrentWebtoonLanePolicy
            .openingProofIncomplete(9))
    }

    @Test
    fun widensOnlyTheIncompleteFinalWellProvenQuicBatch() {
        assertEquals(8, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(8, 17, true))
        assertEquals(12, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(8, 16, true))
        assertEquals(12, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(8, 13, true))
        assertEquals(12, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(8, 12, true))
        assertEquals(9, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(8, 9, true))
        assertEquals(8, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(8, 8, true))
        assertEquals(8, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(8, 12, false))
        assertEquals(6, NtkHostGpuEmulatorCurrentWebtoonLanePolicy.finalWaveCap(6, 10, true))
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
        assertEquals(4, limit)
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
