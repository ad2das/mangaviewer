package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class EnginePlanWindowTest {
    private val series = SeriesId(SourceId("test"), "window")

    private fun episode(number: Int) = EpisodeId(series, number.toString())

    @Test
    fun dropsTheOldestUnprotectedPlansWhenOverTheWindow() {
        val plans = linkedMapOf<EpisodeId, Int>()
        (1..12).forEach { plans[episode(it)] = it }
        val dropped = planKeysToDrop(plans, setOf(episode(10), episode(11), episode(12)), 8)
        assertEquals(listOf(episode(1), episode(2), episode(3), episode(4)), dropped)
    }

    @Test
    fun keepsEveryPlanInsideTheWindowAndNeverDropsProtectedEpisodes() {
        val plans = linkedMapOf<EpisodeId, Int>()
        (1..8).forEach { plans[episode(it)] = it }
        assertEquals(emptyList<EpisodeId>(), planKeysToDrop(plans, emptySet(), 8))
        assertEquals(emptyList<EpisodeId>(), planKeysToDrop(plans, plans.keys.toSet(), 4))
    }
}
