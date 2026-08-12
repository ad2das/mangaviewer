package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectCadenceWatchdogPolicyTest {
    @Test
    fun hostGpuWaitsTwoPeriodsBeforeReplacingAChoreographerCallback() {
        assertEquals(35L, NtkDirectCadenceWatchdogPolicy.delayMs(16.6667f, true))
    }

    @Test
    fun physicalDeviceKeepsItsEstablishedOnePeriodGrace() {
        assertEquals(18L, NtkDirectCadenceWatchdogPolicy.delayMs(16.6667f, false))
    }

    @Test
    fun displayTimingNormalizesInvalidRatesAndPreservesHighRefreshBudgets() {
        assertEquals(60f, NtkDisplayTimingPolicy.normalizedRefreshRate(null), 0f)
        assertEquals(60f, NtkDisplayTimingPolicy.normalizedRefreshRate(Float.NaN), 0f)
        assertEquals(60f, NtkDisplayTimingPolicy.normalizedRefreshRate(0f), 0f)
        assertEquals(120f, NtkDisplayTimingPolicy.normalizedRefreshRate(120f), 0f)
        assertEquals(8.333333f, NtkDisplayTimingPolicy.frameBudgetMs(120f), 0.0001f)
    }

    @Test
    fun inputHotPathUsesLifecycleCachedDisplayTiming() {
        val source = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt",
        ).readText()
        val budgetStart = source.indexOf("private fun frameBudgetMs(): Float")
        val watchdogStart = source.indexOf(
            "private fun directCadenceWatchdogDelayMs(): Long",
            startIndex = budgetStart,
        )
        assertTrue(budgetStart >= 0)
        assertTrue(watchdogStart > budgetStart)
        val budgetBlock = source.substring(budgetStart, watchdogStart)
        assertTrue(budgetBlock.contains("cachedDisplayRefreshRate"))
        assertFalse(budgetBlock.contains("display?.refreshRate"))
        assertEquals(1, Regex("""display\?\.refreshRate""").findAll(source).count())
    }
}
