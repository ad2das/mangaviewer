package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStrictPerformanceContractTest {
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
}
