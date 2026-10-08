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
import ml.melun.mangaview.engine.api.WorkPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineFlingHorizonDemandTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "horizon"), "1")
    private val nextEpisode = episode.copy(remoteKey = "2")

    private fun plan(id: EpisodeId, pages: Int): EpisodeAccessPlan {
        val specs = (0 until pages).map { PageSpec(PageId.at(id, it), it) }
        val manifest = EpisodeManifest(id, id.remoteKey, specs,
            nextEpisodeId = if (id == episode) nextEpisode else null)
        return EpisodeAccessPlan(manifest, "revision", "0".repeat(64), URI("https://test.example/read"), 0,
            specs.map { PageAccessPlan(it.id, it.ordinal.toString(), listOf(URI("https://test.example/page.png"))) })
    }

    private fun state(index: Int, required: Set<PageId> = emptySet()): EngineSessionSnapshot = EngineSessionSnapshot(
        sessionId = 1, generation = 1, phase = EngineSessionPhase.ACTIVE,
        viewport = EngineViewport(100, 100), anchor = SourceAnchor(PageId.at(episode, index), 0L),
        geometryRevision = 1, inputRevision = 1, pendingInputCount = 0,
        visibleRegions = emptyList(), requiredDimensions = required,
        requiredEpisodes = setOf(episode), completeViewport = true,
    )

    private fun demanded(
        lead: Int,
        prepared: Set<PageId> = emptySet(),
        failed: Set<PageId> = emptySet(),
        missing: Set<PageId> = emptySet(),
        initialPresented: Boolean = true,
        interactionActive: Boolean = true,
        index: Int = 0,
        plans: Map<EpisodeId, EpisodeAccessPlan> = mapOf(episode to plan(episode, 40)),
        required: Set<PageId> = emptySet(),
    ): LinkedHashMap<PageId, WorkPriority> = pagePriorities(
        state(index, required), plans, episode, prepared, failed, missing, initialPresented,
        interactionActive, lead, EarlyOriginalTransfers(),
    )

    /** Demand entries the interaction horizon itself owns (the other sources keep their priorities). */
    private fun horizonPages(wanted: Map<PageId, WorkPriority>): List<PageId> =
        wanted.entries.filter { it.value == WorkPriority.NEXT_IMAGE }.map { it.key }

    @Test
    fun leadFormulaCoversTheFlingAndClamps() {
        assertEquals(12, interactionLead(8.0, 1.0))
        assertEquals(6, interactionLead(0.0, 1.0))
        assertEquals(6, interactionLead(2.0, 0.1))
        assertEquals(20, interactionLead(20.0, 3.0))
        assertEquals(6, interactionLead(Double.NaN, 1.0))
        assertEquals(6, interactionLead(8.0, Double.NaN))
    }

    @Test
    fun flingAtEightPagesPerSecondAndASecondOfLatencyDemandsTheCappedHorizonNearestFirst() {
        val lead = interactionLead(8.0, 1.0)
        assertEquals(12, lead)
        val wanted = demanded(lead)
        assertEquals((1..10).map { PageId.at(episode, it) }, horizonPages(wanted))
        assertEquals(WorkPriority.NEXT_IMAGE, wanted[PageId.at(episode, 10)])
    }

    @Test
    fun deeperLeadsNeverExceedTheBudget() {
        val horizon = horizonPages(demanded(MAX_INTERACTION_LEAD))
        assertEquals((1..MAX_INTERACTION_HORIZON_FETCHES).map { PageId.at(episode, it) }, horizon)
    }

    @Test
    fun preparedPagesDoNotConsumeTheBudget() {
        val prepared = setOf(PageId.at(episode, 1), PageId.at(episode, 2))
        val outstanding = horizonPages(demanded(12, prepared = prepared)).filter { it !in prepared }
        assertEquals((3..12).map { PageId.at(episode, it) }, outstanding)
    }

    @Test
    fun failedPagesAreSkippedWithoutConsumingTheBudget() {
        val horizon = horizonPages(demanded(8, failed = setOf(PageId.at(episode, 3))))
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 8).map { PageId.at(episode, it) }, horizon)
    }

    @Test
    fun boundaryLeadContinuesIntoTheKnownNextEpisode() {
        val plans = mapOf(episode to plan(episode, 20), nextEpisode to plan(nextEpisode, 10))
        val horizon = horizonPages(demanded(12, index = 12, plans = plans))
        val expected = (13..19).map { PageId.at(episode, it) } + (0..2).map { PageId.at(nextEpisode, it) }
        assertEquals(expected, horizon)
    }

    @Test
    fun restHorizonStaysShallow() {
        val result = linkedMapOf<PageId, WorkPriority>()
        addNearbyOriginals(state(0), plan(episode, 40).manifest, 0, mapOf(episode to plan(episode, 40)),
            emptySet(), emptySet(), initialPresented = true, lead = PAGES_AHEAD_AT_REST, result = result)
        assertEquals(listOf(1, 2).map { PageId.at(episode, it) }, result.keys.toList())
    }

    @Test
    fun openingHorizonKeepsTheFirstTwoVisible() {
        val wanted = demanded(8, initialPresented = false)
        assertEquals(WorkPriority.VISIBLE, wanted[PageId.at(episode, 1)])
        assertEquals(WorkPriority.VISIBLE, wanted[PageId.at(episode, 2)])
        assertEquals(WorkPriority.NEXT_IMAGE, wanted[PageId.at(episode, 3)])
        assertEquals(WorkPriority.NEXT_IMAGE, wanted[PageId.at(episode, 8)])
    }

    @Test
    fun blockedWindowSkipsDefinitivelyMissingAndFailedPages() {
        val blocked = PageId.at(episode, 5)
        val wanted = demanded(
            lead = PAGES_AHEAD_WHILE_INTERACTING,
            failed = setOf(PageId.at(episode, 7), PageId.at(episode, 39)),
            missing = setOf(PageId.at(episode, 3)),
            index = 5,
            required = setOf(blocked),
        )
        assertEquals(WorkPriority.FOCUS, wanted[blocked])
        assertNull("a definitively missing page in the blocked window must not be re-demanded",
            wanted[PageId.at(episode, 3)])
        assertNull("a failed page in the blocked window must not be re-demanded",
            wanted[PageId.at(episode, 7)])
        // The window continues past a skipped page: only the skipped ids are gone.
        assertEquals(WorkPriority.NEXT_IMAGE, wanted[PageId.at(episode, 4)])
        assertEquals(WorkPriority.NEXT_IMAGE, wanted[PageId.at(episode, 6)])
        assertEquals(WorkPriority.NEXT_IMAGE, wanted[PageId.at(episode, 8)])
    }

    @Test
    fun requiredPagesStayDemandedEvenWhenFailedOrMissing() {
        val blocked = PageId.at(episode, 5)
        val transient = PageId.at(episode, 1)
        val missing = PageId.at(episode, 3)
        val wanted = demanded(
            lead = PAGES_AHEAD_WHILE_INTERACTING,
            failed = setOf(transient, PageId.at(episode, 39)),
            missing = setOf(missing),
            index = 5,
            required = setOf(blocked, transient, missing),
        )
        assertEquals("a transiently failed (503) required page must stay demanded",
            WorkPriority.FOCUS, wanted[transient])
        assertEquals("a definitively missing required page must stay demanded until its placeholder is published",
            WorkPriority.FOCUS, wanted[missing])
        assertEquals(WorkPriority.FOCUS, wanted[blocked])
    }
}
