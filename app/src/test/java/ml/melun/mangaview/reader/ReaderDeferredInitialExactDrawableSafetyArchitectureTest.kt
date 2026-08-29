package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDeferredInitialExactDrawableSafetyArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()

    @Test
    fun sealedGeometryPreservesIntentWithoutNavigatingAheadOfInstalledPixels() {
        assertFalse(source.contains("NtkDeferredInitialExactNavigationPolicy"))
        assertFalse(source.contains("canNavigateDeferredInitialExactGeometryLocked"))

        val identities = source.substring(
            source.indexOf("fun setCommittedPageIdentities("),
            source.indexOf("fun setPageBounds(", source.indexOf("fun setCommittedPageIdentities(")),
        )
        assertTrue(identities.contains("hasLiveBlockedForwardIntentLocked()"))
        assertTrue(identities.contains("scheduleBlockedForwardWindowRequestLocked()"))
        assertTrue(identities.contains("scheduleBlockedForwardIntentResumeLocked()"))
        assertFalse(identities.contains("setScrollOffsetLocked(retainedTarget)"))
        assertFalse(identities.contains("clearBlockedForwardIntentLocked()"))
    }

    @Test
    fun everyForwardGestureAndRepairUsesTheDrawablePrefixGuard() {
        val cap = source.substring(
            source.indexOf("private fun capForwardInputScrollLocked("),
            source.indexOf("private fun physicalEpisodeTailHoldLimitLocked(",
                source.indexOf("private fun capForwardInputScrollLocked(")),
        )
        assertTrue(cap.contains("forwardScrollLimitLocked(scheduleBlocked = false)"))
        assertTrue(cap.contains("rememberBlockedForwardIntentLocked"))

        val repair = source.substring(
            source.indexOf("private fun repairUnsafeDrawableViewportLocked("),
            source.indexOf("private fun scheduleBlockedForwardWindowRequestLocked(",
                source.indexOf("private fun repairUnsafeDrawableViewportLocked(")),
        )
        assertTrue(repair.contains("drawableViewportCleanAtScrollLocked(scrollOffset)"))
    }
}
