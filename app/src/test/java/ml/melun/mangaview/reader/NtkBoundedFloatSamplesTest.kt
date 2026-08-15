package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NtkBoundedFloatSamplesTest {
    @Test
    fun aLongGestureRetainsOnlyTheLatestFixedWindow() {
        val samples = NtkBoundedFloatSamples(4)

        repeat(10_000) { samples.add(it.toFloat()) }

        assertEquals(4, samples.size)
        assertEquals(listOf(9_996f, 9_997f, 9_998f, 9_999f), samples.snapshot())
    }

    @Test
    fun clearStartsAFreshWindowWithoutRetainingOldEvidence() {
        val samples = NtkBoundedFloatSamples(3)
        samples.add(1f)
        samples.add(2f)
        samples.clear()
        samples.add(7f)

        assertEquals(1, samples.size)
        assertEquals(listOf(7f), samples.snapshot())
    }
}
