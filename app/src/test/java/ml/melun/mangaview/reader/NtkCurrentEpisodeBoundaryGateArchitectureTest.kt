package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkCurrentEpisodeBoundaryGateArchitectureTest {
    private val activity = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt",
    ).readText()

    @Test
    fun laterEpisodeBoundaryUsesPathScopedSessionReadinessNotLaunchSealHistory() {
        val start = activity.indexOf("private fun startBoundaryAppend(")
        require(start >= 0)
        val end = activity.indexOf("\n    private fun ", start + 1)
            .takeIf { it >= 0 } ?: activity.length
        val boundary = activity.substring(start, end)

        assertTrue(
            boundary.contains(
                "session?.canPrepareForwardAdjacentNow(currentManga?.ntkEpisodePath) != true",
            ),
        )
        assertFalse(boundary.contains("!strictAllImagesReadyPublished"))

        val immediateStart = activity.indexOf(
            "private fun shouldStartNtkNextBoundaryImmediately(",
        )
        require(immediateStart >= 0)
        val immediateEnd = activity.indexOf("\n    private fun ", immediateStart + 1)
            .takeIf { it >= 0 } ?: activity.length
        val immediate = activity.substring(immediateStart, immediateEnd)
        assertTrue(
            immediate.contains(
                "session?.canPrepareForwardAdjacentNow(currentManga?.ntkEpisodePath) == true",
            ),
        )
        assertFalse(immediate.contains("strictAllImagesReadyPublished &&"))
    }
}
