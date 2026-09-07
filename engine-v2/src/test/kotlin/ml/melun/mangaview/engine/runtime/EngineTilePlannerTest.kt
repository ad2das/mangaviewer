package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.VisiblePageRegion
import org.junit.Assert.*
import org.junit.Test

class EngineTilePlannerTest {
    private val pageId = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)
    private val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL

    @Test fun negativeHalfPixelPlacementPreservesTheSourceAnchor() {
        val plan = EngineTilePlanner(80_000, 202).plan(snapshot(100, 1000, 100, 250 * q + q / 2, 350 * q + q / 2))
        val placement = plan.placements.single()
        assertEquals(200, placement.tile.sourceTop)
        assertEquals(400, placement.tile.sourceBottom)
        assertEquals(-51_712L, placement.topScreenUnits)
        assertEquals(153_088L, placement.bottomScreenUnits)
        assertEquals(80_000L, plan.plannedTextureBytes)
        assertEquals(1, plan.demands.size)
    }

    @Test fun fullRasterCropIsProjectedBackToTheExactSourceAspectRatio() {
        val plan = EngineTilePlanner(10_000_000, 2000).plan(snapshot(101, 1000, 150, 0, 1000 * q))
        val placement = plan.placements.single()
        assertEquals(1486, placement.tile.rasterHeight)
        assertEquals(0L, placement.topScreenUnits)
        assertEquals(1_520_792L, placement.bottomScreenUnits)
    }

    @Test fun allOriginalRowsAreCoveredAndRasterPaddingDoesNotCreateGaps() {
        val plan = EngineTilePlanner(10_000_000, 302).plan(snapshot(101, 1000, 150, 0, 1000 * q))
        assertEquals(5, plan.placements.size)
        assertEquals(0, plan.placements.first().tile.sourceTop)
        assertEquals(1000, plan.placements.last().tile.sourceBottom)
        plan.placements.zipWithNext().forEach { (first, next) ->
            assertEquals(first.tile.sourceBottom, next.tile.sourceTop)
            assertTrue(first.bottomScreenUnits >= next.topScreenUnits)
        }
        assertEquals(1000, plan.placements.sumOf { it.tile.sourceBottom - it.tile.sourceTop })
    }

    @Test fun absentVerifiedBytesCannotProduceACompleteScene() {
        val plan = EngineTilePlanner(10_000_000).plan(snapshot(100, 1000, 100, 0, 100 * q).copy(pages = emptyMap()))
        assertFalse(plan.completeGeometry)
        assertTrue(plan.demands.isEmpty())
        assertTrue(plan.placements.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun insufficientVisibleBudgetFailsInsteadOfReducingImageResolution() {
        EngineTilePlanner(79_999, 202).plan(snapshot(100, 1000, 100, 250 * q, 350 * q))
    }

    @Test fun approachingPageEndPreparesNextVerifiedPageWithoutPlacingIt() {
        val state = neighboringSnapshot(false)
        val plan = EngineTilePlanner(1_000_000, 202).plan(state)
        val next = plan.demands.single { it.tile.pageId != pageId }
        assertEquals(0, next.tile.sourceTop)
        assertEquals(ml.melun.mangaview.engine.api.WorkPriority.NEXT_IMAGE, next.priority)
        assertTrue(plan.placements.all { it.tile.pageId == pageId })
    }

    @Test fun approachingPageStartPreparesPreviousPagesLastBand() {
        val plan = EngineTilePlanner(1_000_000, 202).plan(neighboringSnapshot(true))
        val previous = plan.demands.single { it.tile.pageId != pageId }
        assertEquals(800, previous.tile.sourceTop)
        assertEquals(1000, previous.tile.sourceBottom)
        assertTrue(plan.placements.all { it.tile.pageId == pageId })
    }

    @Test fun pageEdgeSpeculationRequiresVerifiedBytesAndSpareBudget() {
        val state = neighboringSnapshot(false)
        val tight = EngineTilePlanner(80_000, 202).plan(state)
        assertEquals(1, tight.demands.size)
        assertEquals(80_000L, tight.plannedTextureBytes)
        val absent = EngineTilePlanner(1_000_000, 202).plan(state.copy(pages = state.pages.filterKeys { it == pageId }))
        assertTrue(absent.demands.all { it.tile.pageId == pageId })
        val middle = neighboringSnapshot(false).let { full ->
            val center = snapshot(100, 1000, 100, 400 * q, 500 * q)
            center.copy(plans = full.plans, pages = full.pages)
        }
        assertTrue(EngineTilePlanner(1_000_000, 202).plan(middle).demands.all { it.tile.pageId == pageId })
    }

    private fun neighboringSnapshot(previous: Boolean): EngineRuntimeSnapshot {
        val state = if (previous) snapshot(100, 1000, 100, 0, 100 * q)
            else snapshot(100, 1000, 100, 800 * q, 900 * q)
        val otherId = PageId.at(pageId.episodeId, 1)
        val ids = if (previous) listOf(otherId, pageId) else listOf(pageId, otherId)
        val specs = ids.mapIndexed { i, id -> ml.melun.mangaview.core.PageSpec(id, i, PageDimensions(100, 1000)) }
        val manifest = ml.melun.mangaview.core.EpisodeManifest(pageId.episodeId, "episode", specs)
        val plan = ml.melun.mangaview.engine.api.EpisodeAccessPlan(manifest, "1", "0".repeat(64),
            java.net.URI("https://test.example/read"), 0, ids.map {
                ml.melun.mangaview.engine.api.PageAccessPlan(it, it.remoteKey, listOf(java.net.URI("https://test.example/page")))
            })
        return state.copy(plans = mapOf(pageId.episodeId to plan),
            pages = state.pages + (otherId to state.pages.getValue(pageId).copy(pageId = otherId)))
    }

    private fun snapshot(sourceWidth: Int, sourceHeight: Int, width: Int, top: Long, bottom: Long): EngineRuntimeSnapshot {
        val dimensions = PageDimensions(sourceWidth, sourceHeight)
        val page = PageContentIdentity(pageId, "1", "1".repeat(64), dimensions, 1)
        val session = EngineSessionSnapshot(1, 1, EngineSessionPhase.ACTIVE, EngineViewport(width, 2000),
            SourceAnchor(pageId, top), 1, 1, 0,
            listOf(VisiblePageRegion(pageId, dimensions, top, bottom, 0, 2000 * 1024L)),
            emptySet(), emptySet(), true)
        return EngineRuntimeSnapshot(session, emptyMap(), mapOf(pageId to page))
    }
}
