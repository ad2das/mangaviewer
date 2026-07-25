package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkRequiredEpisodePathProtectionTest {
    @Test
    fun requiredEpisodeProtectionIsReferenceCountedAndBalanced() {
        val path = "/manhwa/10078/97592"
        val registry = RequiredNtkEpisodePathRegistry()

        assertEquals(0, registry.refCount(path))
        assertEquals(1, registry.retain(path))
        assertEquals(2, registry.retain(path))
        assertTrue(registry.contains(path))
        assertEquals(setOf(path), registry.snapshot())

        assertEquals(1, registry.release(path))
        assertEquals(1, registry.refCount(path))
        assertEquals(0, registry.release(path))
        assertEquals(0, registry.refCount(path))
        assertFalse(registry.contains(path))
        assertEquals(null, registry.release(path))
    }

    @Test
    fun clearRemovesAllProtection() {
        val registry = RequiredNtkEpisodePathRegistry()
        registry.retain("/manhwa/10078/97592")
        registry.retain("/webtoon/1/2")
        registry.clear()
        assertTrue(registry.snapshot().isEmpty())
    }
}
