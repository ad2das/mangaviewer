package ml.melun.mangaview.viewer.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnacknowledgedFramesTest {
    @Test
    fun framesBeyondTheBoundAreEvictedOldestFirst() {
        val frames = UnacknowledgedFrames<String>(4)
        val evicted = (0 until 20).flatMap { frames.add(it.toLong(), "frame-$it") }
        assertEquals(4, frames.size)
        assertEquals((0 until 16).map { "frame-$it" }, evicted)
        assertEquals(listOf("frame-16", "frame-17", "frame-18", "frame-19"), frames.values())
        assertEquals("frame-19", frames.get(19L))
        assertNull(frames.get(0L))
    }

    @Test
    fun framesInsideTheBoundAreKeptAndRemovedExplicitly() {
        val frames = UnacknowledgedFrames<String>(4)
        assertTrue(frames.isEmpty())
        assertEquals(emptyList<String>(), frames.add(1L, "a"))
        assertEquals(emptyList<String>(), frames.add(2L, "b"))
        assertEquals(2, frames.size)
        assertEquals("a", frames.remove(1L))
        assertNull(frames.remove(1L))
        assertFalse(frames.isEmpty())
        assertEquals(listOf("b"), frames.values())
    }
}
