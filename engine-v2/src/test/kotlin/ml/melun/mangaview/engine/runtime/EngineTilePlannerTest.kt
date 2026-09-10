package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.VisiblePageRegion
import org.junit.Assert.*
import org.junit.Test

class EngineTilePlannerTest {
    private val pageId = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)
    private val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL

    @Test fun retainedPixelsRequireCurrentContentAndDisplayWidth() {
        val planner = EngineTilePlanner(400_000, 102)
        val initial = snapshot(100, 1000, 100, 250 * q, 350 * q)
        val recent = planner.plan(initial).placements.first().tile
        val far = snapshot(100, 1000, 100, 850 * q, 950 * q)
        val plan = planner.plan(far)
        assertFalse(plan.demands.any { it.tile == recent })
        val kept = planner.retainReady(plan, far, listOf(recent, recent))
        assertEquals(1, kept.demands.count { it.tile == recent })
        assertEquals(plan.placements, kept.placements)
        val page = far.pages.getValue(pageId)
        val invalid = listOf(
            far.copy(pages = emptyMap()),
            far.copy(pages = mapOf(pageId to page.copy(contentRevision = "new"))),
            far.copy(pages = mapOf(pageId to page.copy(sha256 = "2".repeat(64)))),
            far.copy(pages = mapOf(pageId to page.copy(dimensions = PageDimensions(100, 1001)))),
            far.copy(session = far.session.copy(viewport = EngineViewport(200, 100))),
        )
        invalid.forEach { changed ->
            assertEquals(plan, planner.retainReady(plan, changed, listOf(recent)))
        }
    }

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
        assertEquals(1000, placement.tile.rasterHeight)
        assertEquals(0L, placement.topScreenUnits)
        assertEquals(1_520_792L, placement.bottomScreenUnits)
    }

    @Test fun consecutiveFullPagesShareTheNextRegionsExactTopBoundary() {
        val dimensions = PageDimensions(622, 900)
        val previousId = PageId.at(pageId.episodeId, 6)
        val nextId = PageId.at(pageId.episodeId, 7)
        val previous = PageContentIdentity(previousId, "1", "1".repeat(64), dimensions, 1)
        val next = PageContentIdentity(nextId, "1", "2".repeat(64), dimensions, 1)
        val previousTop = 417_587L
        val independentlyMappedPreviousBottom = previousTop + 900L * 1080L * 1024L / 622L
        val seam = 2_017_793L
        val outwardRoundedPreviousBottom = 2_017_794L
        assertEquals(2_017_792L, independentlyMappedPreviousBottom)

        val session = EngineSessionSnapshot(1, 1, EngineSessionPhase.ACTIVE, EngineViewport(1080, 4000),
            SourceAnchor(previousId, 0), 1, 1, 0,
            listOf(
                VisiblePageRegion(previousId, dimensions, 0, 900L * q,
                    previousTop, outwardRoundedPreviousBottom),
                VisiblePageRegion(nextId, dimensions, 0, 900L * q, seam, 3_618_000L),
            ), emptySet(), emptySet(), true)
        val placements = EngineTilePlanner(20_000_000, 2000).plan(
            EngineRuntimeSnapshot(session, emptyMap(), mapOf(previousId to previous, nextId to next)),
        ).placements

        assertEquals(listOf(
            EngineTileSpec(previousId, "1", "1".repeat(64), dimensions, 0, 900, 1080),
            EngineTileSpec(nextId, "1", "2".repeat(64), dimensions, 0, 900, 1080),
        ), placements.map { it.tile })
        assertTrue(placements.all { it.tile.rasterHeight == 900 && it.tile.decodedHeight == 900 })
        assertEquals(seam, placements[0].bottomScreenUnits)
        assertEquals(seam, placements[1].topScreenUnits)
        assertNotEquals(outwardRoundedPreviousBottom, placements[0].bottomScreenUnits)
        assertTrue(placements.all { it.bottomScreenUnits > it.topScreenUnits })
    }

    @Test fun allOriginalRowsAreCoveredAndRasterPaddingDoesNotCreateGaps() {
        val plan = EngineTilePlanner(10_000_000, 302).plan(snapshot(101, 1000, 150, 0, 1000 * q))
        assertEquals(5, plan.placements.size)
        assertEquals(0, plan.placements.first().tile.sourceTop)
        assertEquals(1000, plan.placements.last().tile.sourceBottom)
        assertEquals(listOf(
            0L to 304_977L,
            303_953L to 608_930L,
            607_907L to 912_884L,
            911_861L to 1_216_838L,
            1_215_814L to 1_520_792L,
        ), plan.placements.map { it.topScreenUnits to it.bottomScreenUnits })
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

    @Test fun nextEpisodeFirstBandIsPreparedBeforeItBecomesVisible() {
        val state = neighboringSnapshot(false)
        val nextId = pageId.episodeId.copy(remoteKey = "next")
        val nextPage = PageId.at(nextId, 0)
        fun plan(id: ml.melun.mangaview.core.EpisodeId, page: PageId,
            next: ml.melun.mangaview.core.EpisodeId?) = ml.melun.mangaview.engine.api.EpisodeAccessPlan(
            ml.melun.mangaview.core.EpisodeManifest(id, "episode",
                listOf(ml.melun.mangaview.core.PageSpec(page, 0, PageDimensions(100, 1000))), nextEpisodeId = next),
            "1", "0".repeat(64), java.net.URI("https://test.example/read"), 0,
            listOf(ml.melun.mangaview.engine.api.PageAccessPlan(page, page.remoteKey,
                listOf(java.net.URI("https://test.example/page")))))
        val prepared = state.copy(plans = mapOf(pageId.episodeId to plan(pageId.episodeId, pageId, nextId),
            nextId to plan(nextId, nextPage, null)), pages = mapOf(pageId to state.pages.getValue(pageId),
                nextPage to state.pages.getValue(pageId).copy(pageId = nextPage)))
        val output = EngineTilePlanner(1_000_000, 202).plan(prepared)
        assertEquals(0, output.demands.single { it.tile.pageId == nextPage }.tile.sourceTop)
        assertTrue(output.placements.all { it.tile.pageId == pageId })
        assertTrue(EngineTilePlanner(80_000, 202).plan(prepared).demands.all { it.tile.pageId == pageId })
    }

    @Test fun twoViewportDistanceCrossesPageBoundaryButNeverPlacesPreparedNeighbors() {
        val base = neighboringSnapshot(false)
        val state = base.copy(session = base.session.copy(viewport = EngineViewport(100, 200)))
        val plan = EngineTilePlanner(1_000_000, 202, preparationViewports = 2).plan(state)
        assertEquals(listOf(0, 200), plan.demands.filter { it.tile.pageId != pageId }.map { it.tile.sourceTop })
        assertTrue(plan.placements.all { it.tile.pageId == pageId })
        val extended = EngineTilePlanner(1_000_000, 202, preparationViewports = 4).plan(state)
        assertEquals(listOf(0, 200, 400, 600),
            extended.demands.filter { it.tile.pageId != pageId }.map { it.tile.sourceTop })
        assertEquals(plan.placements, extended.placements)
        val burst = EngineTilePlanner(1_000_000, 202, preparationViewports = 12).plan(state)
        assertEquals(listOf(0, 200, 400, 600, 800),
            burst.demands.filter { it.tile.pageId != pageId }.map { it.tile.sourceTop })
        assertEquals(plan.placements, burst.placements)
        assertTrue(burst.plannedTextureBytes <= 1_000_000)
        val tight = EngineTilePlanner(80_000, 202, preparationViewports = 4).plan(state)
        assertEquals(1, tight.demands.size)
        assertEquals(80_000L, tight.plannedTextureBytes)
    }

    @Test fun verifiedLeadingPageCanDecodeWhileCurrentGeometryIsMissingWithoutFalseCoverage() {
        val base = neighboringSnapshot(false)
        val next = base.pages.keys.single { it != pageId }
        val state = base.copy(session = base.session.copy(visibleRegions = emptyList(),
            completeViewport = false, requiredDimensions = setOf(pageId)), pages = base.pages.filterKeys { it == next })
        val plan = EngineTilePlanner(1_000_000, 202, preparationViewports = 2).plan(state)
        assertFalse(plan.completeGeometry)
        assertTrue(plan.placements.isEmpty())
        assertEquals(next, plan.demands.single().tile.pageId)
        assertEquals(0, plan.demands.single().tile.sourceTop)
        assertEquals(ml.melun.mangaview.engine.api.WorkPriority.NEXT_IMAGE, plan.demands.single().priority)
        assertTrue(EngineTilePlanner(1_000_000, 202, preparationViewports = 2)
            .plan(state.copy(pages = emptyMap())).demands.isEmpty())
    }

    @Test fun readyOriginalBeyondAnUnreceivedPagePreparesInBothDirections() {
        for (reverse in listOf(false, true)) {
            val state = snapshotWithPreparationGap(reverse)
            val ready = PageId.at(pageId.episodeId, 2)
            val control = EngineTilePlanner(1_000_000, 202).plan(state)
            val plan = EngineTilePlanner(1_000_000, 202, preparationViewports = 4).plan(state)
            assertTrue("Verified pixels beyond a missing original must prepare", plan.demands.any {
                it.tile.pageId == ready && it.priority == ml.melun.mangaview.engine.api.WorkPriority.NEXT_IMAGE
            })
            assertEquals(control.placements, plan.placements)
            assertEquals(control.completeGeometry, plan.completeGeometry)
            assertTrue(plan.demands.none { it.tile.pageId == PageId.at(pageId.episodeId, 1) })
            val tight = EngineTilePlanner(80_000, 202, preparationViewports = 4).plan(state)
            assertEquals(80_000L, tight.plannedTextureBytes)
            assertTrue(tight.demands.all { it.tile.pageId == pageId })
        }
    }

    @Test fun missingGeometryCanPrepareAReceivedOriginalBeyondThreeMissingPages() {
        val base = snapshotWithPreparationGap(false, missingCount = 5)
        val state = base.copy(session = base.session.copy(visibleRegions = emptyList(),
            completeViewport = false, requiredDimensions = setOf(pageId)),
            pages = base.pages.filterKeys { it != pageId })
        val plan = EngineTilePlanner(1_000_000, 202, preparationViewports = 4).plan(state)
        assertFalse(plan.completeGeometry)
        assertTrue(plan.placements.isEmpty())
        assertEquals(PageId.at(pageId.episodeId, 6), plan.demands.single().tile.pageId)
        assertEquals(0, plan.demands.single().tile.sourceTop)
    }

    @Test fun nextEpisodeReceivedPagePreparesEvenWhileItsFirstOriginalIsMissing() {
        val base = neighboringSnapshot(false)
        val current = base.plans.getValue(pageId.episodeId)
        val nextId = pageId.episodeId.copy(remoteKey = "next-with-gap")
        val nextIds = (0..1).map { PageId.at(nextId, it) }
        fun access(manifest: ml.melun.mangaview.core.EpisodeManifest) =
            ml.melun.mangaview.engine.api.EpisodeAccessPlan(manifest, "1", "0".repeat(64),
                current.finalDocumentUrl, 0, manifest.pages.map {
                    ml.melun.mangaview.engine.api.PageAccessPlan(it.id, it.id.remoteKey, current.pages.first().candidates)
                })
        val opening = access(current.manifest.copy(pages = current.manifest.pages.take(1), nextEpisodeId = nextId))
        val next = access(ml.melun.mangaview.core.EpisodeManifest(nextId, "next", nextIds.mapIndexed { index, id ->
            ml.melun.mangaview.core.PageSpec(id, index, PageDimensions(100, 1000))
        }))
        val state = base.copy(plans = mapOf(pageId.episodeId to opening, nextId to next),
            pages = mapOf(pageId to base.pages.getValue(pageId),
                nextIds[1] to base.pages.getValue(pageId).copy(pageId = nextIds[1])))
        val planner = EngineTilePlanner(1_000_000, 202, preparationViewports = 4)
        val plan = planner.plan(state)
        assertTrue(plan.demands.any { it.tile.pageId == nextIds[1] })
        assertTrue(plan.demands.none { it.tile.pageId == nextIds[0] })
        assertTrue(plan.placements.all { it.tile.pageId == pageId })
        assertTrue(planner.plan(state.copy(plans = mapOf(pageId.episodeId to opening)))
            .demands.all { it.tile.pageId == pageId })
    }

    private fun snapshotWithPreparationGap(reverse: Boolean, missingCount: Int = 1): EngineRuntimeSnapshot {
        val base = neighboringSnapshot(reverse)
        val ids = (listOf(pageId) + (1..missingCount + 1).map { PageId.at(pageId.episodeId, it) })
            .let { if (reverse) it.reversed() else it }
        val old = base.plans.getValue(pageId.episodeId)
        val plan = ml.melun.mangaview.engine.api.EpisodeAccessPlan(old.manifest.copy(pages = ids.mapIndexed { index, id ->
            ml.melun.mangaview.core.PageSpec(id, index, PageDimensions(100, 1000))
        }), old.contentRevision, old.documentSha256, old.finalDocumentUrl, old.authEpoch,
            ids.map { id -> ml.melun.mangaview.engine.api.PageAccessPlan(id, id.remoteKey, old.pages.first().candidates) })
        val readyId = PageId.at(pageId.episodeId, missingCount + 1)
        return base.copy(plans = mapOf(pageId.episodeId to plan),
            pages = mapOf(pageId to base.pages.getValue(pageId),
                readyId to base.pages.getValue(pageId).copy(pageId = readyId)))
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
