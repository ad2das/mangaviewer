package ml.melun.mangaview.reader

import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPagePipelineRegistryTest {
    @After
    fun clear() = ReaderPagePipelineRegistry.clearForTest()

    @Test
    fun warmupControllerAndSessionShareOneEpisodePipeline() {
        val warmup = ReaderPagePipelineRegistry.createOrGet("episode-key", 31)
        val controller = ReaderPagePipelineRegistry.get("episode-key")
        val session = ReaderPagePipelineRegistry.createOrGet("episode-key", 31)
        assertSame(warmup, controller)
        assertSame(warmup, session)
    }

    @Test
    fun manifestReplacementRetiresOldPipelineAsOneUnit() {
        val old = ReaderPagePipelineRegistry.createOrGet("episode-key", 31)
        val replacement = ReaderPagePipelineRegistry.createOrGet("episode-key", 32)
        assertNotSame(old, replacement)
        assertTrue(old.invariantSnapshot().retired)
    }
}
