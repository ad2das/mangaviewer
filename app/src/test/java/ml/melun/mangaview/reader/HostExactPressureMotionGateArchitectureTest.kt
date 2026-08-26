package ml.melun.mangaview.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HostExactPressureMotionGateArchitectureTest {
    private val source = File(
        "src/main/java/ml/melun/mangaview/reader/ReaderSession.kt",
    ).readText()

    @Test
    fun pressureRetirementChecksMotionBeforeAndAfterTakingPageTableLock() {
        val start = source.indexOf(
            "private fun scheduleHostExactPoolPressureTrim(minimumRetirementBytes: Long)",
        )
        val end = source.indexOf(
            "private fun isHostExactPoolPressureTrimBlockedByPhysicalMotion()",
            start,
        )
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)

        val firstGate = body.indexOf(
            "if (isHostExactPoolPressureTrimBlockedByPhysicalMotion())",
        )
        val pageLock = body.indexOf("synchronized(pagesLock)")
        val secondGate = body.indexOf(
            "if (isHostExactPoolPressureTrimBlockedByPhysicalMotion())",
            firstGate + 1,
        )
        val mutation = body.indexOf("evictDeliveredBitmaps(")
        assertTrue(firstGate >= 0)
        assertTrue(pageLock > firstGate)
        assertTrue(secondGate > pageLock)
        assertTrue(mutation > secondGate)
        assertTrue(body.contains("deferredForPhysicalMotion = true"))
        assertTrue(body.contains("NTK_HOST_EXACT_PRESSURE_MOTION_RECHECK_MS"))
    }

    @Test
    fun motionGateIncludesPointerViewportAndDisplayGrace() {
        val start = source.indexOf(
            "private fun isHostExactPoolPressureTrimBlockedByPhysicalMotion()",
        )
        val end = source.indexOf("private fun bitmapReleaseLocked(", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)

        assertTrue(body.contains("physicalTouchActive.get()"))
        assertTrue(body.contains("viewportBusy.get()"))
        assertTrue(body.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
    }
}
