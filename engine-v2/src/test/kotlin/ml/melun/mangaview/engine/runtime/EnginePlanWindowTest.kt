package ml.melun.mangaview.engine.runtime

import java.net.URI
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.PageAccessPlan
import ml.melun.mangaview.engine.api.SourceAnchor
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

    @Test
    fun theWindowKeepsThePrefetchedNextNextDocumentEvenWhenThePlanSetOverflows() {
        fun plan(number: Int): EpisodeAccessPlan {
            val id = episode(number)
            val spec = PageSpec(PageId.at(id, 0), 0)
            val manifest = EpisodeManifest(id, id.remoteKey, listOf(spec),
                previousEpisodeId = episode(number - 1).takeIf { number > 1 },
                nextEpisodeId = episode(number + 1).takeIf { number < 12 })
            return EpisodeAccessPlan(manifest, "revision", "0".repeat(64), URI("https://test.example/read"), 0,
                listOf(PageAccessPlan(spec.id, "0", listOf(URI("https://test.example/page.png")))))
        }
        val plans = linkedMapOf<EpisodeId, EpisodeAccessPlan>()
        (1..9).forEach { plans[episode(it)] = plan(it) }
        // The required window still covers every older document, so the prefetched grandchild is
        // the only unprotected plan: the overflow would drop it right after its fetch.
        val state = EngineSessionSnapshot(
            sessionId = 1, generation = 1, phase = EngineSessionPhase.ACTIVE,
            viewport = EngineViewport(100, 100), anchor = SourceAnchor(PageId.at(episode(7), 0), 0L),
            geometryRevision = 1, inputRevision = 1, pendingInputCount = 0,
            visibleRegions = emptyList(), requiredDimensions = emptySet(),
            requiredEpisodes = (1..8).mapTo(mutableSetOf()) { episode(it) }, completeViewport = true,
        )
        val retained = linkedMapOf<EpisodeId, CachedPlan>()
        assertEquals("the prefetched next-next plan must stay inside the window", plans,
            applyPlanWindow(plans, retained, state, episode(7), emptyMap()))
    }
}
