package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStrictPerformanceContractTest {

    @Test
    fun rollingResidencyNeverReexpandsTheDirectionalWindowToTheWholeLaunchEpisode() {
        assertTrue(ReaderStrictBitmapResidencyPolicy.protectsFullLaunchSpan(true, false))
        assertFalse(ReaderStrictBitmapResidencyPolicy.protectsFullLaunchSpan(true, true))
        assertFalse(ReaderStrictBitmapResidencyPolicy.protectsFullLaunchSpan(false, false))
    }
    @Test
    fun directWifiRollingKeepsThePhysicalOpeningRunway() {
        assertEquals(
            6,
            ReaderStrictBitmapResidencyPolicy.initialForwardRunwayPages(
                directWifiRolling = true,
                ordinaryPages = 12,
            ),
        )
        assertEquals(
            12,
            ReaderStrictBitmapResidencyPolicy.initialForwardRunwayPages(
                directWifiRolling = false,
                ordinaryPages = 12,
            ),
        )
        assertEquals(
            11,
            ReaderStrictBitmapResidencyPolicy.forwardRetainAheadPages(
                directWifiRolling = true,
                ordinaryPages = 12,
            ),
        )
        assertEquals(
            12,
            ReaderStrictBitmapResidencyPolicy.forwardRetainAheadPages(
                directWifiRolling = false,
                ordinaryPages = 12,
            ),
        )
        assertEquals(
            8,
            ReaderStrictBitmapResidencyPolicy.nextIdleForwardWarmPage(
                directWifiRolling = true,
                busy = false,
                direction = 1,
                visibleLast = 7,
                retainedLast = 11,
                pageCount = 12,
            ),
        )
        assertEquals(
            null,
            ReaderStrictBitmapResidencyPolicy.nextIdleForwardWarmPage(
                directWifiRolling = true,
                busy = true,
                direction = 1,
                visibleLast = 7,
                retainedLast = 11,
                pageCount = 12,
            ),
        )
        assertEquals(
            10,
            ReaderStrictBitmapResidencyPolicy.nextIdleForwardWarmPage(
                directWifiRolling = true,
                busy = false,
                direction = 1,
                visibleLast = 7,
                retainedLast = 11,
                pageCount = 12,
                residentPages = setOf(8, 9),
            ),
        )
        assertEquals(
            null,
            ReaderStrictBitmapResidencyPolicy.nextIdleForwardWarmPage(
                directWifiRolling = true,
                busy = false,
                direction = -1,
                visibleLast = 7,
                retainedLast = 11,
                pageCount = 12,
            ),
        )
        assertEquals(
            null,
            ReaderStrictBitmapResidencyPolicy.nextIdleForwardWarmPage(
                directWifiRolling = true,
                busy = false,
                direction = 1,
                visibleLast = 11,
                retainedLast = 11,
                pageCount = 12,
            ),
        )
    }

    @Test
    fun productionAcceptanceValuesCannotBeRelaxed() {
        assertEquals(1.5f, ReaderStrictPerformanceContract.ACTIVATION_AHEAD_VIEWPORTS)
        assertEquals(2.0f, ReaderStrictPerformanceContract.PRODUCTION_AHEAD_VIEWPORTS)
        assertEquals(2048, ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX)
        assertEquals(12, ReaderStrictPerformanceContract.POST_ACTIVATION_BYTE_RUNWAY_PAGES)
        assertEquals(6, ReaderStrictPerformanceContract.ROLLING_PROOF_METADATA_PAGES)
    }

    @Test
    fun ordinaryExactPagesShareOneDecodeWhileOversizedPagesRemainRegionBounded() {
        assertTrue(ReaderExactDecodeStoragePolicy.useSharedFullPageBitmap(true, 690, 1_600))
        assertFalse(ReaderExactDecodeStoragePolicy.useSharedFullPageBitmap(false, 690, 1_600))
        assertFalse(ReaderExactDecodeStoragePolicy.useSharedFullPageBitmap(true, 1_080, 8_000))
    }

    @Test
    fun oversizedHostScenesOrDirectWifiEpisodesUseRollingPixels() {
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/manhwa/3360/18755",
                168,
                1_432,
                2_048,
            )
        )
        assertFalse(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/manhwa/34770/1791629",
                34,
                1_432,
                2_048,
            )
        )
        assertFalse(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/webtoon/4642/821859",
                200,
                690,
                1_600,
            )
        )
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/webtoon/12868/1346337",
                8,
                1_440,
                28_800,
                directWifiCurrentEpisode = true,
            )
        )
        assertFalse(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/webtoon/12868/1346337",
                8,
                1_440,
                28_800,
                directWifiCurrentEpisode = false,
            )
        )
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/webtoon/12868/1346337",
                9,
                1_440,
                28_800,
                directWifiCurrentEpisode = true,
            )
        )
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/webtoon/ordinary/episode",
                8,
                690,
                1_600,
                directWifiCurrentEpisode = true,
            )
        )
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/webtoon/840540/nv-840540-37",
                55,
                690,
                2_600,
                directWifiCurrentEpisode = true,
            )
        )
        assertFalse(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/webtoon/840540/nv-840540-37",
                55,
                690,
                2_600,
                directWifiCurrentEpisode = false,
            )
        )
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/manhwa/3360/18755",
                159,
                1_432,
                2_048,
            )
        )
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/manhwa/10073/238729",
                176,
                580,
                838,
            )
        )
        assertFalse(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/manhwa/10073/ordinary",
                80,
                580,
                838,
            )
        )
        assertTrue(
            ReaderExactDecodeStoragePolicy.useBoundedRollingResidency(
                "/manhwa/one-piece/1181",
                12,
                1_644,
                2_400,
                directWifiCurrentEpisode = true,
            )
        )
    }

    @Test
    fun appendedExactEpisodesCannotGrowDecodedPixelsWithoutBound() {
        val mib = 1024L * 1024L
        assertEquals(
            64L * mib,
            ReaderStrictBitmapResidencyPolicy.totalBitmapBudgetBytes(
                requiredLaunchBytes = 164L * mib,
                maxHeapBytes = 1024L * mib,
            ),
        )
        assertEquals(
            64L * mib,
            ReaderStrictBitmapResidencyPolicy.totalBitmapBudgetBytes(
                requiredLaunchBytes = 164L * mib,
                maxHeapBytes = 512L * mib,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.protectsLaunchPixel(
                strictColdSession = true,
                rollingPixelResidency = false,
                belongsToLaunchEpisode = true,
                successorPhysicallyPresented = false,
            ),
        )
        assertFalse(
            ReaderStrictBitmapResidencyPolicy.protectsLaunchPixel(
                strictColdSession = true,
                rollingPixelResidency = false,
                belongsToLaunchEpisode = false,
                successorPhysicallyPresented = false,
            ),
        )
        assertFalse(
            ReaderStrictBitmapResidencyPolicy.protectsLaunchPixel(
                strictColdSession = true,
                rollingPixelResidency = false,
                belongsToLaunchEpisode = true,
                successorPhysicallyPresented = true,
            ),
        )
        assertFalse(
            ReaderStrictBitmapResidencyPolicy.protectsLaunchPixel(
                strictColdSession = true,
                rollingPixelResidency = true,
                belongsToLaunchEpisode = true,
                successorPhysicallyPresented = false,
            ),
        )
        assertFalse(
            ReaderStrictBitmapResidencyPolicy.protectsLaunchPixel(
                strictColdSession = true,
                rollingPixelResidency = true,
                belongsToLaunchEpisode = true,
                successorPhysicallyPresented = true,
            ),
        )
        assertFalse(
            ReaderStrictBitmapResidencyPolicy.shouldHardEvictOutsideRetainedWindow(
                strictColdSession = true,
                rollingPixelResidency = false,
                directWifiRolling = false,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldHardEvictOutsideRetainedWindow(
                strictColdSession = true,
                rollingPixelResidency = true,
                directWifiRolling = false,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldHardEvictOutsideRetainedWindow(
                strictColdSession = true,
                rollingPixelResidency = false,
                directWifiRolling = true,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldHardEvictOutsideRetainedWindow(
                strictColdSession = false,
                rollingPixelResidency = false,
                directWifiRolling = false,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldTrimRetainedUnderBudgetPressure(
                directWifiRolling = false,
                immediateGeneratedUx = true,
                strictColdSession = true,
                rollingPixelResidency = false,
                protectsImmediateSurface = true,
                viewportBusy = false,
                initialSettleActive = false,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldTrimRetainedUnderBudgetPressure(
                directWifiRolling = false,
                immediateGeneratedUx = true,
                strictColdSession = true,
                rollingPixelResidency = false,
                protectsImmediateSurface = false,
                viewportBusy = true,
                initialSettleActive = false,
            ),
        )
        assertFalse(
            ReaderStrictBitmapResidencyPolicy.shouldTrimRetainedUnderBudgetPressure(
                directWifiRolling = false,
                immediateGeneratedUx = true,
                strictColdSession = true,
                rollingPixelResidency = false,
                protectsImmediateSurface = false,
                viewportBusy = false,
                initialSettleActive = true,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldTrimRetainedUnderBudgetPressure(
                directWifiRolling = false,
                immediateGeneratedUx = true,
                strictColdSession = true,
                rollingPixelResidency = false,
                protectsImmediateSurface = false,
                viewportBusy = false,
                initialSettleActive = false,
            ),
        )
        assertFalse(
            ReaderStrictBitmapResidencyPolicy.shouldTrimRetainedUnderBudgetPressure(
                directWifiRolling = false,
                immediateGeneratedUx = true,
                strictColdSession = false,
                rollingPixelResidency = false,
                protectsImmediateSurface = true,
                viewportBusy = false,
                initialSettleActive = false,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldTrimRetainedUnderBudgetPressure(
                directWifiRolling = true,
                immediateGeneratedUx = true,
                strictColdSession = true,
                rollingPixelResidency = true,
                protectsImmediateSurface = false,
                viewportBusy = true,
                initialSettleActive = false,
            ),
        )
        assertTrue(
            ReaderStrictBitmapResidencyPolicy.shouldTrimRetainedUnderBudgetPressure(
                directWifiRolling = false,
                immediateGeneratedUx = true,
                strictColdSession = false,
                rollingPixelResidency = false,
                protectsImmediateSurface = false,
                viewportBusy = true,
                initialSettleActive = false,
            ),
        )
    }
}
