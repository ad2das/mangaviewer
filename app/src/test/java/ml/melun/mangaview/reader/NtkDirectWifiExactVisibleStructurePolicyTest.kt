package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiExactVisibleStructurePolicyTest {
    @Test
    fun enforcementDoesNotDependOnLateExpandedRunwayPublication() {
        assertTrue(
            NtkDirectWifiExactVisibleStructurePolicy.shouldEnforce(
                emulatorRuntime = true,
                realPixelsOnly = true,
            ),
        )
        assertFalse(
            NtkDirectWifiExactVisibleStructurePolicy.shouldEnforce(
                emulatorRuntime = false,
                realPixelsOnly = true,
            ),
        )
        assertFalse(
            NtkDirectWifiExactVisibleStructurePolicy.shouldEnforce(
                emulatorRuntime = true,
                realPixelsOnly = false,
            ),
        )
    }

    @Test
    fun emptySingleAndConsecutiveDisplayPagesAreValid() {
        assertTrue(NtkDirectWifiExactVisibleStructurePolicy.isContiguous(intArrayOf()))
        assertTrue(NtkDirectWifiExactVisibleStructurePolicy.isContiguous(intArrayOf(13)))
        assertTrue(NtkDirectWifiExactVisibleStructurePolicy.isContiguous(intArrayOf(9, 10, 11)))
        assertTrue(NtkDirectWifiExactVisibleStructurePolicy.isContiguous(intArrayOf(11, 12, 13)))
    }

    @Test
    fun aMissingStructuralDisplayPageIsRejected() {
        assertFalse(NtkDirectWifiExactVisibleStructurePolicy.isContiguous(intArrayOf(11, 13)))
        assertFalse(NtkDirectWifiExactVisibleStructurePolicy.isContiguous(intArrayOf(55, 56, 103)))
        assertFalse(NtkDirectWifiExactVisibleStructurePolicy.isContiguous(intArrayOf(13, 12)))
    }
}
