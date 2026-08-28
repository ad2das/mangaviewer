package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkPublishedExactEpisodePixelCompletionArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun firstActualCompletesAlreadyPublishedPixelsWithoutRepublishingStructure() {
        val firstActual = functionBody("private fun wakeRemainingAdjacentAppendAfterExactFirstActual(")
        val completion = functionBody("private fun drainEnteredExactEpisodePixelCompletion(")

        assertTrue(firstActual.contains("requestEnteredExactEpisodePixelCompletion(path)"))
        assertTrue(completion.contains("prepareAdjacentRunwayDrawableBatch("))
        assertTrue(completion.contains("refs = listOf(missingPage)"))
        assertTrue(completion.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertTrue(completion.contains("NTK_ENTERED_EXACT_PIXEL_COMPLETION_IDLE_MS"))
        assertTrue(completion.contains("commitAdjacentRunwayDrawableBatch("))
        assertTrue(completion.contains("scheduleEnteredExactEpisodePixelCompletionAudit(path)"))
        val audit = functionBody("private fun scheduleEnteredExactEpisodePixelCompletionAudit(")
        assertTrue(audit.contains("enteredExactEpisodePixelCompletionAuditPaths.add(path)"))
        assertTrue(audit.contains("latestEnteredExactEpisodePath.get() == path"))
        assertTrue(audit.contains("requestEnteredExactEpisodePixelCompletion(path)"))
        assertFalse(completion.contains("pages.add"))
        assertFalse(completion.contains("listener.onPreparedAdjacentPagesAppended"))
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed function: $signature")
    }
}
