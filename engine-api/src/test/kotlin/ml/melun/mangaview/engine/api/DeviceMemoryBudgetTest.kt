package ml.melun.mangaview.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceMemoryBudgetTest {
    @Test
    fun approvedModernDeviceBudgetsUseRamFractionsWithCaps() {
        assertEquals(DeviceMemoryBudget(256 * MIB, 512 * MIB, true),
            DeviceMemoryBudget.fromPhysicalRam(8 * GIB))
        assertEquals(DeviceMemoryBudget(384 * MIB, 768 * MIB, true),
            DeviceMemoryBudget.fromPhysicalRam(12 * GIB))
        assertEquals(DeviceMemoryBudget(384 * MIB, 768 * MIB, true),
            DeviceMemoryBudget.fromPhysicalRam(Long.MAX_VALUE))
    }

    @Test
    fun missingOrInvalidRamUsesOnlyTheDocumentedFallback() {
        val fallback = DeviceMemoryBudget(128 * MIB, 256 * MIB, false)
        for (value in listOf(null, 0L, -1L)) {
            assertEquals(fallback, DeviceMemoryBudget.fromPhysicalRam(value))
            assertFalse(DeviceMemoryBudget.fromPhysicalRam(value).physicalRamKnown)
        }
    }

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}
