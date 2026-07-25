package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentRunwayRefreshPolicyTest {
    @Test
    fun partialSuffixKeepsItsOriginalSourceSlots() {
        val assignments = NtkAdjacentRunwayRefreshPolicy.assignments(
            existingSourceIndexes = (3 until 30).toList(),
            latestImages = (4..30).map { page ->
                "https://img.example/manhwa/11660/121684/p${page.toString().padStart(3, '0')}.jpg"
            }
        )

        assertEquals((3 until 30).toList(), assignments.map { it.sourceIndex })
        assertEquals("p004.jpg", assignments.first().image.substringAfterLast('/'))
        assertEquals("p030.jpg", assignments.last().image.substringAfterLast('/'))
    }

    @Test
    fun shrinkingSuffixNeverGetsReindexedFromZero() {
        val assignments = NtkAdjacentRunwayRefreshPolicy.assignments(
            existingSourceIndexes = (24 until 30).toList(),
            latestImages = (28..30).map { page ->
                "https://img.example/manhwa/11660/121684/p${page.toString().padStart(3, '0')}.jpeg"
            }
        )

        assertEquals(listOf(27, 28, 29), assignments.map { it.sourceIndex })
    }

    @Test
    fun ambiguousPartialSnapshotIsRejectedInsteadOfShiftingSlots() {
        val assignments = NtkAdjacentRunwayRefreshPolicy.assignments(
            existingSourceIndexes = (3 until 30).toList(),
            latestImages = listOf("opaque-a", "opaque-b", "opaque-c")
        )

        assertTrue(assignments.isEmpty())
    }

    @Test
    fun completeOpaqueSnapshotMayUseExistingOrder() {
        val assignments = NtkAdjacentRunwayRefreshPolicy.assignments(
            existingSourceIndexes = listOf(3, 4, 5),
            latestImages = listOf("opaque-d", "opaque-e", "opaque-f")
        )

        assertEquals(listOf(3, 4, 5), assignments.map { it.sourceIndex })
    }
}
