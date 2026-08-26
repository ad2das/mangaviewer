package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOffscreenBoundsInvalidationArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()

    @Test
    fun parkedOffscreenBoundsDoNotInvalidateTheVisibleProducerScene() {
        val method = functionBody("fun setPageBounds(")
        val defer = method.indexOf("shouldDeferOffscreenBoundsOnlyResolveLocked(oldTop)")
        val mutation = method.indexOf("page.width = max(1, pageWidth)")
        val invalidation = method.indexOf("invalidatePreparedRenderSceneStateLocked()")

        assertTrue(defer >= 0)
        assertTrue(invalidation > defer)
        assertTrue(invalidation < mutation)
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
