package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkProtectedBitmapWindowSnapshotTest {
    private val snapshot = NtkProtectedBitmapWindowSnapshot(
        revision = 7L,
        sourceGeneration = 41,
        structureGeneration = 13,
        pageCount = 40,
        anchor = 24,
        direction = -1,
        first = 15,
        last = 27,
    )

    @Test
    fun onlyThePublishedIndexedGenerationCanAdmitPixels() {
        assertTrue(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot,
                revision = 7L,
                structureGeneration = 13,
                pageCount = 40,
                index = 15,
            )
        )
        assertTrue(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot,
                revision = 7L,
                structureGeneration = 13,
                pageCount = 40,
                index = 27,
            )
        )
        assertFalse(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot,
                revision = 8L,
                structureGeneration = 13,
                pageCount = 40,
                index = 24,
            )
        )
        assertFalse(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot,
                revision = 7L,
                structureGeneration = 14,
                pageCount = 40,
                index = 24,
            )
        )
        assertTrue(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot,
                revision = 7L,
                structureGeneration = 13,
                pageCount = 41,
                index = 24,
            )
        )
        assertFalse(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot,
                revision = 7L,
                structureGeneration = 13,
                pageCount = 39,
                index = 24,
            )
        )
    }

    @Test
    fun malformedOrOutOfRangeWindowsFailClosed() {
        assertFalse(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot.copy(first = 28, last = 27),
                revision = 7L,
                structureGeneration = 13,
                pageCount = 40,
                index = 27,
            )
        )
        assertFalse(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                snapshot.copy(last = 40),
                revision = 7L,
                structureGeneration = 13,
                pageCount = 40,
                index = 39,
            )
        )
        assertFalse(
            NtkProtectedBitmapWindowSnapshotPolicy.admits(
                null,
                revision = 7L,
                structureGeneration = 13,
                pageCount = 40,
                index = 24,
            )
        )
    }
}
