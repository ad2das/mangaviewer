package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictReverseDecodeRetentionTest {
    @Test
    fun rollingSourceDemandUsesBudgetedLruInsteadOfHardViewportEviction() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val start = source.indexOf("private fun requestStrictExactColdWindow(")
        val end = source.indexOf("private fun requestActiveGeneratedScrollRunway(", start)
        val method = source.substring(start, end)

        assertTrue(method.contains("trimDeliveredBitmapsToBudget()"))
        assertTrue(method.contains("trimPendingProtectedNumericBitmaps(demandFirst, demandLast)"))
        assertFalse(method.contains("evictDeliveredBitmaps(demandFirst, demandLast)"))
        assertTrue(source.contains("LinkedHashMap<Int, Bitmap>(32, 0.75f, true)"))
        assertTrue(source.contains("trimDeliveredBudgetLocked(cleared)"))
    }

    @Test
    fun boundedNumericWindowIsCheckedBeforeEitherColdDecodeRoute() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val strictStart = source.indexOf("private fun requestStrictExactSourcePage(")
        val strictEnd = source.indexOf("private fun requestPage(", strictStart)
        val strictWorker = source.substring(strictStart, strictEnd)
        val visibleStart = source.indexOf("val activeGeneratedProofGate =")
        val visibleEnd = source.indexOf("private fun releaseActiveGeneratedProofDecodeGate", visibleStart)
        val visibleWorker = source.substring(visibleStart, visibleEnd)
        val requestStart = source.indexOf("val networkExecutor = when")
        val requestEnd = source.indexOf("private fun shouldHedgeForegroundPrime", requestStart)
        val requestWorker = source.substring(requestStart, requestEnd)

        val visibleGate = visibleWorker.indexOf(
            "shouldSkipDecodeOutsideProtectedNumericWindow("
        )
        val visibleDecode = visibleWorker.indexOf("decodePage(index, page, cached")
        assertTrue(visibleGate >= 0 && visibleDecode > visibleGate)

        val requestGate = requestWorker.indexOf(
            "shouldSkipDecodeOutsideProtectedNumericWindow("
        )
        val requestDecode = requestWorker.indexOf("decodePageWithLease(")
        assertTrue(requestGate >= 0 && requestDecode > requestGate)

        val strictRequestGate = strictWorker.indexOf(
            "\"strict_exact_request\""
        )
        val strictWorkerGate = strictWorker.indexOf(
            "\"strict_exact_worker\""
        )
        val strictDecode = strictWorker.indexOf("val result = opened.predecodedOriginal")
        assertTrue(strictRequestGate >= 0)
        assertTrue(strictWorkerGate > strictRequestGate && strictDecode > strictWorkerGate)

        assertTrue(source.contains("decode_skip_outside_bounded_window"))
        assertTrue(source.contains("decode_drop_outside_bounded_window"))
    }
}
