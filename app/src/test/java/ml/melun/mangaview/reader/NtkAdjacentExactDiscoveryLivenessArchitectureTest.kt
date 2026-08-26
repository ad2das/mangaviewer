package ml.melun.mangaview.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtkAdjacentExactDiscoveryLivenessArchitectureTest {
    @Test
    fun authoritativeBoundaryStartsOrJoinsExactOwnerBeforeWaitingForManifest() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val body = functionBody(
            source,
            "private fun loadAuthoritativeAdjacentUrlsForPrefetch(",
            "private fun loadAppendUrlsForCandidate(",
        )

        val completionGate = body.indexOf("if (!isEpisodeFullyDrawableForAdjacent(source))")
        val ownerLaunch = body.indexOf("startForwardAdjacentExactDiscoveryAtCompletion(")
        val selectedTargetCheck = body.indexOf(
            "selectedExactTargetPath.equals(expectedPath, ignoreCase = true)",
        )
        val bodyRelease = body.indexOf("releaseClaimedForwardAdjacentBodiesAfterPredecessorComplete(")
        val manifestWait = body.indexOf("waitForExactViewerApiAdjacentUrls(")

        assertTrue(completionGate >= 0)
        assertTrue(ownerLaunch > completionGate)
        assertTrue(selectedTargetCheck > ownerLaunch)
        assertTrue(bodyRelease > selectedTargetCheck)
        assertTrue(manifestWait > bodyRelease)
        assertTrue(body.contains("resolvedNext = target"))
        assertTrue(body.contains("selectedExactTargetPath"))
    }

    private fun functionBody(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        check(startIndex >= 0 && endIndex > startIndex) {
            "Unable to isolate $start"
        }
        return source.substring(startIndex, endIndex)
    }
}
