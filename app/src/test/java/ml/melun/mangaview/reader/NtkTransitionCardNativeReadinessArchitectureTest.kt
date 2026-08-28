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
    fun visibleStructuralCardWaitsForItsNativeTwinWithoutBlockingFarOffscreenPixels() {
        val frameItems = block("private fun nativeItemsReadyForCurrentFrame(", surface)
        val readiness = block("private fun nativeStructuralPixelsReady(", surface)
        val submit = block("private fun submitNativeFrame(", surface)

        assertTrue(frameItems.contains("state.nativeBandItems.ifEmpty { state.items }"))
        assertTrue(frameItems.contains("nativeItemPixelsReadyForSubmission(item, directTiles)"))
        assertTrue(frameItems.contains("top >= state.height.toFloat() + guard"))
        assertTrue(frameItems.contains("top + item.pageHeight <= -guard"))
        assertTrue(frameItems.contains("return nativeItems.filterNot"))
        assertTrue(readiness.contains("nativeItems.all"))
        assertTrue(submit.contains("val nativeItems = nativeItemsReadyForCurrentFrame(state)"))
        assertTrue(submit.contains("nativeStructuralPixelsReady(state, nativeItems)"))
        assertTrue(submit.indexOf("nativeItemsReadyForCurrentFrame(state)") <
            submit.indexOf("val cleanPixels"))
    }

    @Test
    fun requiredStructuralCopyDoesNotWaitForAnOlderCardsCompatibleSlot() {
        val copy = block("fun copyStructuralBitmap(", pool)
        val acquire = block("private fun acquireSlots(", pool)

        assertTrue(copy.contains("waitForCompatibleRetirement = false"))
        assertTrue(acquire.contains("waitForCompatibleRetirement && newAllocationExceedsSettledTarget"))
    }

    @Test
    fun adjacentTransitionCardNativeTwinIsPreparedBeforeAtomicStructurePublication() {
        val session = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val append = block("private fun appendResolvedEpisodeInitialRunway(", session)
        val prepare = block("fun preparePageCard(", surface)
        val install = block("fun setPageCard(", surface)

        assertTrue(append.contains("listener::onPageCardPreparationRequested"))
        assertTrue(
            append.indexOf("listener::onPageCardPreparationRequested") <
                append.indexOf("shouldDeferDirectWifiAdjacentStructurePublication(target)"),
        )
        assertTrue(prepare.contains("TRANSITION_CARD_NATIVE_EXECUTOR.execute"))
        assertTrue(prepare.contains("preparedNativeTransitionCards.put("))
        assertTrue(install.contains("preparedNativeTransitionCards.remove(title)"))
        assertTrue(install.contains("page.nativeCardBitmap = prepared?.nativeToken"))
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
