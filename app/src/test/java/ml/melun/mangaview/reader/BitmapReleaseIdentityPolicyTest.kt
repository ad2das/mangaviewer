package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BitmapReleaseIdentityPolicyTest {
    @Test
    fun replacementKeepsEveryIdentitySharedWithIncomingTiles() {
        val shared = EqualValue(7)
        val stale = EqualValue(8)

        val releases = BitmapReleaseIdentityPolicy.uniqueCandidatesExcludingRetained(
            candidates = listOf(shared, stale, shared),
            retained = listOf(shared),
        )

        assertEquals(1, releases.size)
        assertSame(stale, releases.single())
    }

    @Test
    fun equalButDistinctBitmapStandInsDoNotAlias() {
        val stale = EqualValue(7)
        val equalReplacement = EqualValue(7)

        val releases = BitmapReleaseIdentityPolicy.uniqueCandidatesExcludingRetained(
            candidates = listOf(stale),
            retained = listOf(equalReplacement),
        )

        assertEquals(1, releases.size)
        assertSame(stale, releases.single())
    }

    @Test
    fun duplicateStaleIdentityIsReleasedOnlyOnce() {
        val stale = EqualValue(9)

        val releases = BitmapReleaseIdentityPolicy.uniqueCandidatesExcludingRetained(
            candidates = listOf(stale, stale),
            retained = emptyList(),
        )

        assertEquals(1, releases.size)
        assertSame(stale, releases.single())
    }

    @Test
    fun readerSessionFinalRecycleGuardProtectsEveryCurrentIdentityUnderOneLockOrder() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val currentGuard = source.functionBody(
            "private fun isCurrentlyDeliveredBitmapLocked",
            "private fun isBitmapProtectedFromRecycle",
        )
        val terminalRelease = source.functionBody(
            "private fun releaseBitmapToPoolOrRecycle",
            "private fun bitmapBytes",
        )

        assertTrue(currentGuard.contains("deliveredBitmaps.values.any"))
        assertTrue(currentGuard.contains("deliveredTiles.values.any"))
        assertFalse(currentGuard.contains("deliveredOwned"))

        val externalLock = terminalRelease.indexOf("synchronized(externallyOwnedBitmaps)")
        val deliveredLock = terminalRelease.indexOf("synchronized(deliveredBitmaps)")
        val externalGuard = terminalRelease.indexOf("bitmap in externallyOwnedBitmaps")
        val currentIdentityGuard = terminalRelease.indexOf("isCurrentlyDeliveredBitmapLocked(bitmap)")
        val terminalRecycle = terminalRelease.indexOf("bitmap.recycle()")
        assertTrue(externalLock >= 0)
        assertTrue(deliveredLock > externalLock)
        assertTrue(externalGuard > deliveredLock)
        assertTrue(currentIdentityGuard > deliveredLock)
        assertTrue(terminalRecycle > currentIdentityGuard)
    }

    @Test
    fun readerSessionReplacementPathsUseIdentityFilteredCandidates() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val helper = source.functionBody(
            "private fun addReplacementBitmapReleases",
            "private fun tryClaimPendingDelivery",
        )

        assertTrue(
            helper.contains(
                "BitmapReleaseIdentityPolicy.uniqueCandidatesExcludingRetained(candidates, retained)",
            ),
        )
        assertEquals(5, Regex("addReplacementBitmapReleases\\(").findAll(source).count())
    }

    private fun String.functionBody(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, startIndex = start + startMarker.length)
        assertTrue("Missing source marker: $startMarker", start >= 0)
        assertTrue("Missing source marker: $endMarker", end > start)
        return substring(start, end)
    }

    private data class EqualValue(val value: Int)
}
