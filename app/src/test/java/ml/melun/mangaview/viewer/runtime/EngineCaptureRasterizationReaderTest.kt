package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.*
import org.junit.Test

class EngineCaptureRasterizationReaderTest {
    @Test fun queryIsLazyAndCachedUntilEitherOwnerEpochChanges() {
        var renderer = 1L
        var surface = 1L
        var calls = 0
        val reader = EngineCaptureRasterizationReader({ renderer }, { surface }) {
            calls++
            intArrayOf(8, 0, 0)
        }
        assertEquals(0, calls)
        val first = reader()
        assertSame(first, reader())
        assertEquals(1, calls)
        surface++
        assertNotSame(first, reader())
        assertEquals(2, calls)
        renderer++
        reader()
        assertEquals(3, calls)
    }

    @Test fun failedQueryDoesNotPoisonTheCache() {
        var calls = 0
        val reader = EngineCaptureRasterizationReader({ 1L }, { 1L }) {
            if (++calls == 1) intArrayOf(8) else intArrayOf(8, 0, 0)
        }
        try { reader(); fail("Invalid capabilities must fail") } catch (_: IllegalStateException) { }
        assertEquals(EngineRasterizationInfo(8, 0, 0), reader())
        assertEquals(2, calls)
    }
}
