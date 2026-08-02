package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkAdjacentFirstActualFrameArchitectureTest {
    private val sessionSource = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt"
    ).readText()
    private val activitySource = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
    ).readText()

    @Test
    fun committedAdjacentPixelsOpenTheirOwnStrictFallbackExactlyOnce() {
        val claim = blockStartingAt("private data class AdjacentStrictSourceClaim(", sessionSource)
        assertTrue(claim.contains("firstActualFramePresented: AtomicBoolean"))

        val signal = blockStartingAt(
            "fun onExactNtkAdjacentActualFramePresented(",
            sessionSource,
        )
        assertTrue(signal.contains("NtkStripDigests.normalizeEpisodePath(episodePath)"))
        assertTrue(signal.contains("adjacentStrictSourceClaims[normalizedPath]"))
        assertTrue(signal.contains("firstActualFramePresented.compareAndSet(false, true)"))
        assertTrue(signal.contains("transport.onFirstActualFramePresented(claim.episode)"))

        val completedDraw = blockStartingAt(
            "private fun handleStrictRollingCompletedDraw(",
            activitySource,
        )
        val commitValidation = completedDraw.indexOf("if (!commitValid)")
        val distinctPhysicalEpisodes = completedDraw.indexOf(
            ".distinct()",
            startIndex = commitValidation,
        )
        val adjacentSignal = completedDraw.indexOf(
            ".forEach(activeSession::onExactNtkAdjacentActualFramePresented)"
        )
        val launchSignal = completedDraw.indexOf(
            "activeSession.onExactNtkPhysicalDrawPresented("
        )
        assertTrue(commitValidation >= 0)
        assertTrue(distinctPhysicalEpisodes > commitValidation)
        assertTrue(adjacentSignal > distinctPhysicalEpisodes)
        assertTrue(launchSignal > adjacentSignal)
    }

    private fun blockStartingAt(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Missing closing brace: $signature")
    }
}
