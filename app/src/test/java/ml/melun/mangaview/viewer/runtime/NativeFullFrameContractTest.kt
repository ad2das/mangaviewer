package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.FramePlan
import ml.melun.mangaview.viewer.PagePlacement
import ml.melun.mangaview.viewer.LoadingFramePlanner
import ml.melun.mangaview.viewer.PixelRef
import ml.melun.mangaview.viewer.PixelTileRef
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeFullFrameContractTest {
    @Test
    fun loadingScrollSubmitsGeometryButNeverClaimsFirstImageContent() {
        val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(1_920))
        val planner = LoadingFramePlanner(screenfulCount = 8)
        val initial = planner.plan(planner.geometry(viewport), FixedPx.fromPixels(200))
        val contract = NativeFullFrameContract(
            structureEpoch = 1L,
            plan = initial,
            renderWidth = 1_080,
            renderHeight = 1_920,
            bandHeight = 5_760,
            coordinateOrigin = FixedPx.ZERO,
            localTileRanges = intArrayOf(),
            localVisualRanges = intArrayOf(0, 5_760),
        )

        val scroll = contract.scrollSubmission(initial.copy(
            scrollOffset = FixedPx.fromPixels(500),
        ))

        assertEquals(500, scroll?.viewportTop)
        assertEquals(false, scroll?.hasContent)
        assertEquals(true, scroll?.fullVisualCoverage)
        assertEquals(false, scroll?.fullActualCoverage)
        assertEquals(true, scroll?.changesViewport)
    }

    @Test
    fun exactPlanWithOnlyScrollChangedProducesScalarViewportTop() {
        val initial = plan(scrollPixels = 400)
        val contract = contract(initial)

        val scroll = contract.scrollSubmission(initial.copy(
            scrollOffset = FixedPx.fromPixels(1_000),
        ))

        assertEquals(500, scroll?.viewportTop)
        assertEquals(37L, scroll?.structureEpoch)
        assertEquals(true, scroll?.hasContent)
        assertEquals(false, scroll?.fullActualCoverage)
        assertEquals(true, scroll?.changesViewport)
    }

    @Test
    fun subpixelLogicalScrollDoesNotRequestAVisualNoOpTransaction() {
        val initial = plan(scrollPixels = 400)
        val contract = contract(initial)
        val subpixel = initial.copy(
            scrollOffset = initial.scrollOffset + FixedPx(1L),
        )

        val scroll = contract.scrollSubmission(subpixel)

        assertEquals(200, scroll?.viewportTop)
        assertEquals(false, scroll?.changesViewport)
    }

    @Test
    fun successiveScalarPlansKeepTheNewestAbsoluteViewportTop() {
        val initial = plan(scrollPixels = 400)
        var contract = contract(initial)
        val firstPlan = initial.copy(scrollOffset = FixedPx.fromPixels(1_000))

        assertEquals(500, contract.scrollSubmission(firstPlan)?.viewportTop)
        contract = contract.advancedTo(firstPlan)
        val newestPlan = initial.copy(scrollOffset = FixedPx.fromPixels(2_400))

        assertEquals(1_200, contract.scrollSubmission(newestPlan)?.viewportTop)
        assertEquals(false, contract.scrollSubmission(newestPlan)?.hasContent)
    }

    @Test
    fun unchangedScrollOrAnyStructuralDifferenceRequiresFullSubmit() {
        val initial = plan(scrollPixels = 400)
        val contract = contract(initial)
        val moved = initial.copy(scrollOffset = FixedPx.fromPixels(600))

        assertNull(contract.scrollSubmission(initial))
        assertNull(contract.scrollSubmission(moved.copy(generation = 2L)))
        assertNull(contract.scrollSubmission(moved.copy(
            viewport = Viewport(FixedPx.fromPixels(1_439), FixedPx.fromPixels(2_560)),
        )))
        assertNull(contract.scrollSubmission(moved.copy(
            contentHeight = FixedPx.fromPixels(16_001),
        )))
        assertNull(contract.scrollSubmission(moved.copy(
            pages = moved.pages.map { it.copy(top = FixedPx.fromPixels(1)) },
        )))
    }

    @Test
    fun pixelPublicationIdentityChangeRequiresFullSubmit() {
        val initial = plan(scrollPixels = 400)
        val contract = contract(initial)
        val oldPixel = requireNotNull(initial.pages.single().pixel)
        val changedTile = oldPixel.tiles.single().copy(contentVersion = 8L)
        val changedPixel = oldPixel.copy(tiles = listOf(changedTile))
        val changed = initial.copy(
            scrollOffset = FixedPx.fromPixels(600),
            pages = initial.pages.map { it.copy(pixel = changedPixel) },
        )

        assertNull(contract.scrollSubmission(changed))
    }

    @Test
    fun outOfRangeScalarScrollRequiresAFullSubmissionInsteadOfBeingClamped() {
        val initial = plan(scrollPixels = 400)
        val contract = contract(initial)

        assertNull(contract.scrollSubmission(initial.copy(
            scrollOffset = FixedPx.fromPixels(13_441),
        )))
    }

    @Test
    fun scalarScrollIsRelativeToTheInstalledLocalCoordinateOrigin() {
        val initial = plan(scrollPixels = 400).copy(
            scrollOffset = FixedPx(3_000_000_000L * 1_024L),
            contentHeight = FixedPx(3_000_010_000L * 1_024L),
        )
        val origin = FixedPx((3_000_000_000L - 2_560L) * 1_024L)
        val contract = NativeFullFrameContract(
            structureEpoch = 41L,
            plan = initial,
            renderWidth = 720,
            renderHeight = 1_280,
            bandHeight = 3_840,
            coordinateOrigin = origin,
            localTileRanges = intArrayOf(1_280, 2_304),
        )

        val moved = initial.copy(
            scrollOffset = initial.scrollOffset + FixedPx.fromPixels(500),
        )

        assertEquals(1_530, contract.scrollSubmission(moved)?.viewportTop)
        assertNull(contract.scrollSubmission(initial.copy(
            scrollOffset = origin - FixedPx.fromPixels(1),
        )))
    }

    private fun contract(initial: FramePlan) = NativeFullFrameContract(
        structureEpoch = 37L,
        plan = initial,
        renderWidth = 720,
        renderHeight = 1_280,
        bandHeight = 8_000,
        coordinateOrigin = FixedPx.ZERO,
        localTileRanges = intArrayOf(0, 1_200),
    )

    private fun plan(scrollPixels: Int): FramePlan {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val dimensions = PageDimensions(1_440, 16_000)
        val tile = PixelTileRef(
            handle = 19L,
            sourceTopPx = 0,
            sourceBottomPx = 2_048,
            displayHeightPx = 1_024,
            contentVersion = 7L,
            allocationBytes = 2_949_120L,
            displayWidthPx = 720,
        )
        return FramePlan(
            generation = 1L,
            viewport = Viewport(FixedPx.fromPixels(1_440), FixedPx.fromPixels(2_560)),
            scrollOffset = FixedPx.fromPixels(scrollPixels),
            contentHeight = FixedPx.fromPixels(16_000),
            pages = listOf(PagePlacement(
                ordinal = 0,
                pageId = PageId.at(episode, 0),
                top = FixedPx.ZERO,
                height = FixedPx.fromPixels(16_000),
                pixel = PixelRef(19L, dimensions, tile.allocationBytes, listOf(tile)),
            )),
        )
    }
}
