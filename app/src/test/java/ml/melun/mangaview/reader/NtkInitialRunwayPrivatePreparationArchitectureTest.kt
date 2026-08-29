package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkInitialRunwayPrivatePreparationArchitectureTest {
    @Test
    fun followerPreparationIsTwoWideButNativeDecodeAndHandoffRemainCanonical() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
        ).readText()
        val requestStart = source.indexOf("private fun requestStrictExactSourcePage(")
        val requestEnd = source.indexOf("private fun releaseStrictExactDecodeClaim(", requestStart)
        assertTrue(requestStart >= 0)
        assertTrue(requestEnd > requestStart)
        val request = source.substring(requestStart, requestEnd)

        assertTrue(request.contains("isNtkManhwaOrWebtoonEpisodePath"))
        assertTrue(
            request.contains(
                "val nextInitialRunwaySource = strictExactInitialDecodeRunwayNextSource.get()"
            )
        )
        assertTrue(request.contains("page.sourceIndex >= nextInitialRunwaySource"))

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
        val orderedTurn = request.indexOf(
            "awaitStrictInitialDecodeRunwayTurn(page.sourceIndex)",
            decodePermit,
        )
        val nativeGate = request.indexOf(
            "initialHostExactViewportDecodeGate.acquire()",
            orderedTurn,
        )
        val decode = request.indexOf("val result = if (", nativeGate)
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
        assertTrue(
            request.substring(offscreenDeferral, decode).contains(
                "else if (!initialHostExactScrollRunwayFollowerDecode &&"
            ),
        )
        assertTrue(
            request.substring(offscreenDeferral, decode)
                .contains("NtkStrictActiveScrollDecodePolicy.shouldShareVisibleDecodeGate("),
        )
        assertTrue(orderedTurn in decodePermit until decode)
        assertTrue(nativeGate in orderedTurn until decode)
        assertTrue(publish > decode)
        assertTrue(advance > publish)
    }
}
