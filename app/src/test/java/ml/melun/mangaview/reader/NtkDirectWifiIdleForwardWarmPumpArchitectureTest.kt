package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiIdleForwardWarmPumpArchitectureTest {
    @Test
    fun successfulExactHandoffContinuesOnlyWhileReaderRemainsIdle() {
        val source = File("src/main/java/ml/melun/mangaview/reader/ReaderSession.kt").readText()
        val start = source.indexOf("private fun scheduleDirectWifiIdleForwardWarmPump()")
        val end = source.indexOf("private fun requestPage(", start)
        val pump = source.substring(start, end)

        assertTrue(source.contains("markDecodedDrawableReady(index, page, result.width)\n" +
            "        scheduleDirectWifiIdleForwardWarmPump()"))
        assertTrue(pump.contains("viewportBusy.get()"))
        assertTrue(pump.contains("NtkReaderTransferPacer.isPhysicalMotionActive()"))
        assertTrue(pump.contains("isHostExactOffscreenDecodeInputProtected()"))
        assertTrue(pump.contains("nextIdleForwardWarmPage("))
        assertTrue(pump.contains("strictExactDecodeInFlight.contains(next)"))
        assertTrue(pump.contains("strictExactSplitSourceDecodeInFlight.contains("))
        assertTrue(pump.contains("strictExactBodyDescriptors[page.sourceIndex] == null"))
    }
}
