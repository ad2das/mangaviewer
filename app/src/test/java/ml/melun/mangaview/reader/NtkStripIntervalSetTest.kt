package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class NtkStripIntervalSetTest {
    @Test
    fun mergesAdjacentTilesAndExposesContinuousFrontier() {
        val coverage = NtkStripIntervalSet()
        coverage.add(512, 1024)
        coverage.add(0, 512)
        coverage.add(1536, 2048)

        assertEquals(1024L, coverage.continuousEndFrom(0))
        assertEquals(1024L, coverage.firstGap(0, 2048))
        coverage.add(1024, 1536)
        assertEquals(2048L, coverage.continuousEndFrom(0))
        assertNull(coverage.firstGap(0, 2048))
    }

    @Test
    fun randomizedAddRemoveMatchesReferencePixels() {
        val random = Random(0x51A1C0DE)
        repeat(100) {
            val actual = NtkStripIntervalSet()
            val reference = BooleanArray(4096)
            repeat(1000) {
                val first = random.nextInt(reference.size)
                val second = random.nextInt(reference.size)
                val start = minOf(first, second)
                val end = maxOf(first, second) + 1
                if (random.nextBoolean()) {
                    actual.add(start.toLong(), end.toLong())
                    for (pixel in start until end) reference[pixel] = true
                } else {
                    actual.remove(start.toLong(), end.toLong())
                    for (pixel in start until end) reference[pixel] = false
                }
                repeat(4) {
                    val probe = random.nextInt(reference.size)
                    var expectedEnd = probe
                    while (expectedEnd < reference.size && reference[expectedEnd]) expectedEnd++
                    assertEquals(expectedEnd.toLong(), actual.continuousEndFrom(probe.toLong()))
                    var expectedStart = probe
                    while (expectedStart > 0 && reference[expectedStart - 1]) expectedStart--
                    assertEquals(expectedStart.toLong(), actual.continuousStartFrom(probe.toLong()))
                }
            }
            val expectedCovered = reference.count { it }.toLong()
            assertEquals(expectedCovered, actual.coveredLength(0, reference.size.toLong()))
            val intervals = actual.snapshot()
            intervals.zipWithNext().forEach { (left, right) -> assertTrue(left.endPx < right.startPx) }
        }
    }

    @Test
    fun invalidRangesNeverBecomeCoverage() {
        val coverage = NtkStripIntervalSet()
        coverage.add(-1, 2)
        coverage.add(3, 3)
        assertTrue(coverage.isEmpty())
        assertFalse(coverage.contains(0, 1))
    }
}
