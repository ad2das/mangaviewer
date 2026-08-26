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

    @Test
    fun partialReadySuffixNeverDropsEarlierPendingSourcesFromRemainingManifest() {
        val existing = (4 until 180).map { sourceIndex ->
            NtkAdjacentRunwayRefreshPolicy.Assignment(
                sourceIndex,
                "https://img.example/manhwa/10073/238730/" +
                    "p${(sourceIndex + 1).toString().padStart(3, '0')}.jpg"
            )
        }
        val latestSuffix = (166..180).map { page ->
            "https://alternate.example/manhwa/10073/238730/" +
                "p${page.toString().padStart(3, '0')}.jpg"
        }

        val reconciled = NtkAdjacentRunwayRefreshPolicy.reconcile(existing, latestSuffix)

        assertEquals((4 until 180).toList(), reconciled.map { it.sourceIndex })
        assertEquals(existing.first().image, reconciled.first().image)
        assertTrue(reconciled.last().image.startsWith("https://alternate.example/"))
    }

    @Test
    fun ambiguousPartialRefreshLeavesCompleteRemainingManifestUntouched() {
        val existing = (4 until 12).map { sourceIndex ->
            NtkAdjacentRunwayRefreshPolicy.Assignment(sourceIndex, "original-$sourceIndex")
        }

        val reconciled = NtkAdjacentRunwayRefreshPolicy.reconcile(
            existing,
            listOf("opaque-a", "opaque-b")
        )

        assertEquals(existing, reconciled)
    }

    @Test
    fun refreshPreservesBothDisplaySidesOfOneSourceSlot() {
        val existing = listOf(
            NtkAdjacentRunwayRefreshPolicy.Assignment(0, "old-p0-side0"),
            NtkAdjacentRunwayRefreshPolicy.Assignment(0, "old-p0-side1"),
            NtkAdjacentRunwayRefreshPolicy.Assignment(1, "old-p1"),
        )

        val reconciled = NtkAdjacentRunwayRefreshPolicy.reconcile(
            existing,
            listOf(
                "https://alternate.example/manhwa/work/p001.jpg",
                "https://alternate.example/manhwa/work/p002.jpg",
            ),
        )

        assertEquals(listOf(0, 0, 1), reconciled.map { it.sourceIndex })
        assertEquals(reconciled[0].image, reconciled[1].image)
        assertTrue(reconciled[0].image.endsWith("p001.jpg"))
        assertTrue(reconciled[2].image.endsWith("p002.jpg"))
    }

    @Test
    fun opaqueRefreshUsesSourceCardinalityAndNeverDisplayRefCardinality() {
        val existing = listOf(
            NtkAdjacentRunwayRefreshPolicy.Assignment(1, "old-p1-side0"),
            NtkAdjacentRunwayRefreshPolicy.Assignment(1, "old-p1-side1"),
            NtkAdjacentRunwayRefreshPolicy.Assignment(2, "old-p2"),
            NtkAdjacentRunwayRefreshPolicy.Assignment(3, "old-p3"),
        )

        assertEquals(
            existing,
            NtkAdjacentRunwayRefreshPolicy.reconcile(
                existing,
                listOf("opaque-a", "opaque-b", "opaque-c", "opaque-d"),
            ),
        )

        val reconciled = NtkAdjacentRunwayRefreshPolicy.reconcile(
            existing,
            listOf("new-p1", "new-p2", "new-p3"),
        )
        assertEquals(listOf(1, 1, 2, 3), reconciled.map { it.sourceIndex })
        assertEquals(listOf("new-p1", "new-p1", "new-p2", "new-p3"), reconciled.map { it.image })
    }

    @Test
    fun staleReadySuffixCannotJumpOverPendingSourceSlots() {
        assertEquals(
            0,
            NtkAdjacentRunwayRefreshPolicy.contiguousPrefixLength(
                maxInstalledSourceIndex = 3,
                orderedCandidateSourceIndexes = listOf(171, 172, 173),
            )
        )
    }

    @Test
    fun contiguousPrefixStopsAtFirstSourceGap() {
        assertEquals(
            3,
            NtkAdjacentRunwayRefreshPolicy.contiguousPrefixLength(
                maxInstalledSourceIndex = 3,
                orderedCandidateSourceIndexes = listOf(4, 5, 6, 8, 9),
            )
        )
    }

    @Test
    fun secondDisplaySideOfSameSourceRemainsContiguous() {
        assertEquals(
            4,
            NtkAdjacentRunwayRefreshPolicy.contiguousPrefixLength(
                maxInstalledSourceIndex = 3,
                orderedCandidateSourceIndexes = listOf(3, 4, 4, 5),
            )
        )
    }
}
