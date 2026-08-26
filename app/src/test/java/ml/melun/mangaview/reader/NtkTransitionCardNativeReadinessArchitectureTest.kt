package ml.melun.mangaview.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NtkTransitionCardNativeReadinessArchitectureTest {
    private val surface = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
    ).readText()
    private val pool = File(
        "src/main/java/ml/melun/mangaview/reader/HostExactHardwareTilePool.kt",
    ).readText()

    @Test
    fun visibleStructuralCardWaitsForItsNativePixelTwinBeforeSubmission() {
        val readiness = block("private fun nativeStructuralPixelsReady(", surface)
        val submit = block("private fun submitNativeFrame(", surface)

        assertTrue(readiness.contains("item.cardText == null"))
        assertTrue(readiness.contains("item.nativeCardBitmap"))
        assertTrue(readiness.contains("HostExactHardwareTilePool.nativeHandle(nativeCard) == 0L"))
        assertTrue(submit.contains("nativeStructuralPixelsReady(state)"))
    }

    @Test
    fun requiredStructuralCopyDoesNotWaitForAnOlderCardsCompatibleSlot() {
        val copy = block("fun copyStructuralBitmap(", pool)
        val acquire = block("private fun acquireSlots(", pool)

        assertTrue(copy.contains("waitForCompatibleRetirement = false"))
        assertTrue(acquire.contains("waitForCompatibleRetirement && newAllocationExceedsSettledTarget"))
    }

    private fun block(signature: String, source: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val opening = source.indexOf('{', start)
        require(opening >= 0) { "Missing body: $signature" }
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed body: $signature")
    }
}
