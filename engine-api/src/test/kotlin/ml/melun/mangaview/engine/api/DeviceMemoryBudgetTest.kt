package ml.melun.mangaview.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun textureBudgetsReserveUploadHeadroomForSpeculativeTilesOnly() {
        val budget = DeviceMemoryBudget.fromPhysicalRam(3 * GIB)
        assertEquals(96 * MIB, budget.glResidentBytes)
        // 1080 x 2400: one target tile at the larger display side is 2400 * 2048 * 4 = 19,660,800;
        // doubled beats one eighth of the 96 MiB allocation.
        val portrait = budget.textureBudgets(1080, 2400)
        assertEquals(39_321_600L, portrait.headroomBytes)
        assertEquals(96 * MIB - 39_321_600L, portrait.plannerBytes)
        // 1440 x 3200: 2 * 26,214,400 exceeds the half-allocation cap, so the cap binds.
        val landscape = budget.textureBudgets(1440, 3200)
        assertEquals(50_331_648L, landscape.headroomBytes)
        assertEquals(96 * MIB - 50_331_648L, landscape.plannerBytes)
        // A tiny display cannot shrink the headroom below one eighth of the allocation.
        val small = budget.textureBudgets(100, 100)
        assertEquals(12 * MIB, small.headroomBytes)
        assertEquals(96 * MIB - 12 * MIB, small.plannerBytes)
        // Rotation and two-pane layouts read the same larger side.
        assertEquals(portrait, budget.textureBudgets(2400, 1080))
        assertEquals(landscape, budget.textureBudgets(3200, 1440))
        for (derived in listOf(portrait, landscape, small)) {
            assertEquals(96 * MIB, derived.allocationBytes)
            assertEquals(derived.allocationBytes - derived.headroomBytes, derived.plannerBytes)
            assertTrue(derived.plannerBytes < derived.allocationBytes)
        }
    }

    @Test
    fun lowRamDisplayKeepsHalfTheAllocationEvenWhenTheTileWouldTakeMore() {
        val budget = DeviceMemoryBudget(64 * MIB, 128 * MIB, true)
        val derived = budget.textureBudgets(1440, 3200)
        assertEquals(64 * MIB / 2, derived.headroomBytes)
        assertEquals(64 * MIB - 64 * MIB / 2, derived.plannerBytes)
    }

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}
