package ml.melun.mangaview.activity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderForegroundCommitLifecycleTest {
    @Test
    fun strictActualSemanticsRequireAnOwnedVisibleForegroundCommit() {
        val source = File(
            "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
        ).readText()

        assertTrue(source.contains("strictTelemetryForegroundCommitArmed = false"))
        assertTrue(source.contains("if (!strictTelemetryForegroundCommitArmed)"))
        assertTrue(source.contains("renderView.invalidateCommittedPresentationProof()"))
        assertTrue(source.contains("if (!strictTelemetryOwned || strictTelemetryClosed"))
        assertTrue(source.contains("if (!renderView.isShown ||"))
        assertTrue(source.contains("renderView.windowVisibility != View.VISIBLE"))
    }
}
