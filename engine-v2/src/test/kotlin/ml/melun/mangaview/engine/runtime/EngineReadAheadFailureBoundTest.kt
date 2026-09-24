package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineTileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineReadAheadFailureBoundTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "read-ahead"), "1")

    private fun tile(index: Int): EngineTileSpec {
        val page = PageId.at(episode, index)
        return EngineTileSpec(page, "1", "a".repeat(64), PageDimensions(100, 100), 0, 100, 100)
    }

    @Test
    fun failuresBeyondTheBoundArePrunedToTheCurrentPlan() {
        val failed = linkedSetOf<EngineTileSpec>()
        repeat(300) { failed += tile(it) }
        val wanted = (100 until 110).mapTo(linkedSetOf()) { tile(it) }
        assertTrue(pruneFailedReadAhead(failed, 256) { wanted })
        assertEquals(wanted, failed)
    }

    @Test
    fun failuresInsideTheBoundAreLeftAloneWithoutBuildingThePlanSet() {
        val failed = linkedSetOf(tile(0), tile(1))
        var evaluated = false
        assertFalse(pruneFailedReadAhead(failed, 256) { evaluated = true; emptySet() })
        assertEquals(2, failed.size)
        assertFalse("The wanted set must not be built below the bound", evaluated)
    }
}
