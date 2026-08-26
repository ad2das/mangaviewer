package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAuthoritativeOriginalGenerationPolicyTest {
    @Test
    fun onlyNewerSessionMayReplaceTheSameCanonicalRetainedOriginal() {
        assertTrue(
            NtkAuthoritativeOriginalGenerationPolicy.mayReplaceRetainedOriginal(
                existingUsableOriginal = true,
                sameTileIdentity = false,
                sameCanonicalProof = true,
                existingGeneration = 4,
                incomingGeneration = 5,
            ),
        )
        assertFalse(
            NtkAuthoritativeOriginalGenerationPolicy.mayReplaceRetainedOriginal(
                true, false, true, existingGeneration = 5, incomingGeneration = 5,
            ),
        )
        assertFalse(
            NtkAuthoritativeOriginalGenerationPolicy.mayReplaceRetainedOriginal(
                true, false, true, existingGeneration = 5, incomingGeneration = 4,
            ),
        )
        assertFalse(
            NtkAuthoritativeOriginalGenerationPolicy.mayReplaceRetainedOriginal(
                true, false, false, existingGeneration = 4, incomingGeneration = 5,
            ),
        )
    }

    @Test
    fun physicalRestoreFloorIsPublishedBeforeWindowCoalescing() {
        val activity = File(
            "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
        ).readText()
        val callback = activity.substringAfter("override fun onWindowChanged(")
            .substringBefore("override fun onPhysicalScrollGestureStarted(")
        val visibleFloor = callback.indexOf(
            "activeSession?.recordStrictExactPhysicalVisibleFloor(physicalFirstPage)",
        )
        val reverseFloor = callback.indexOf("activeSession?.recordStrictExactPhysicalReverseFloor(it)")
        assertTrue(visibleFloor >= 0)
        assertTrue(reverseFloor > visibleFloor)

        val session = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val floor = session.substringAfter("fun recordStrictExactPhysicalVisibleFloor(")
            .substringBefore("fun recordStrictExactPhysicalReverseFloor(")
        assertTrue(floor.contains("val visibleFloor = first.sourceIndex.coerceAtLeast(0)"))
        assertTrue(floor.contains("requestRetainedWindowAfterStructureChange()"))
    }
}
