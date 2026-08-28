package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkInitialRunwayPrivatePreparationArchitectureTest {
    @Test
    fun followerNativePreparationIsTwoWideButPublicHandoffRemainsCanonical() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val requestStart = source.indexOf("private fun requestStrictExactSourcePage(")
        val requestEnd = source.indexOf("private fun releaseStrictExactDecodeClaim(", requestStart)
        assertTrue(requestStart >= 0)
        assertTrue(requestEnd > requestStart)
        val request = source.substring(requestStart, requestEnd)

        val followerGate = "if (initialHostExactScrollRunwayFollowerDecode &&"
        val preparationGate = request.indexOf(followerGate)
        val anchorPixels = request.indexOf(
            "strictExactInitialAnchorPixelsInstalled.await(",
            preparationGate,
        )
        val preparationWindow = request.indexOf(
            "awaitStrictInitialDecodePreparationWindow(page.sourceIndex)",
            preparationGate,
        )
        val decodePermit = request.indexOf(
            "initialHostExactRunwayNativeDecodePermits.acquire()",
            preparationWindow,
        )
        val decode = request.indexOf("val result = if (")
        val orderedHandoff = request.indexOf(
            "if (initialHostExactScrollRunwayFollowerDecode) {",
            decode,
        )
        val ownershipAck = request.indexOf(
            "strictExactInitialAnchorPixelsInstalled.await(",
            orderedHandoff,
        )
        val orderedTurn = request.indexOf(
            "awaitStrictInitialDecodeRunwayTurn(page.sourceIndex)",
            orderedHandoff,
        )
        val nativeGate = request.indexOf(
            "initialHostExactViewportDecodeGate.acquire()",
            orderedTurn,
        )
        val offscreenDeferral = request.indexOf("val deferHostOffscreenUntilQuiet =")
        val publish = request.indexOf("handOffStrictExactAuthoritativeTiles(", decode)
        val advance = request.indexOf(
            "strictExactInitialDecodeRunwayNextSource.compareAndSet(",
            publish,
        )

        assertTrue(preparationGate in 0 until decode)
        assertTrue(anchorPixels in preparationGate until decode)
        assertTrue(preparationWindow in anchorPixels until decode)
        assertTrue(decodePermit in preparationWindow until decode)
        assertTrue(offscreenDeferral in decodePermit until decode)
        assertTrue(
            request.substring(offscreenDeferral, decode)
                .contains("!initialHostExactScrollRunwayFollowerDecode"),
        )
        assertTrue(orderedHandoff > decode)
        assertTrue(ownershipAck in orderedHandoff until publish)
        assertTrue(orderedTurn in ownershipAck until publish)
        assertTrue(nativeGate in orderedTurn until publish)
        assertTrue(publish > decode)
        assertTrue(advance > publish)
    }
}
