package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOffscreenPixelInvalidationArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()

    @Test
    fun pagePixelMutationAdvancesVisibleGenerationOnlyWhenAVisibleSceneOwnsThePage() {
        val method = functionBody("private fun invalidateRetainedPageNodeStateLocked(")

        assertTrue(method.contains("val cachedBandChanged = invalidateCachedNativeBandForPageLocked(index)"))
        assertTrue(method.contains("activeNativeBandProof?.items?.any { it.index == index }"))
        assertTrue(method.contains("val visibleSceneChanged = cachedBandChanged || activeBandChanged || preparedSceneChanged"))
        assertTrue(method.contains("if (visibleSceneChanged) advanceVisualGenerationLocked()"))
        assertTrue(method.contains("if (visibleSceneChanged) discardPreparedRenderSceneLocked()"))
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
