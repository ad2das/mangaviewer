package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.FramePlan
import ml.melun.mangaview.viewer.PagePlacement
import ml.melun.mangaview.viewer.LoadingPlacement
import ml.melun.mangaview.viewer.LoadingFramePlanner
import ml.melun.mangaview.viewer.PixelRef
import ml.melun.mangaview.viewer.PixelTileRef
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeFramePackerTest {
    @Test
    fun completeContentRequiresGapFreeViewportCoverage() {
        assertEquals(true, intArrayOf(0, 500, 500, 1_000).covers(0, 1_000))
        assertEquals(false, intArrayOf(0, 499, 500, 1_000).covers(0, 1_000))
        assertEquals(false, intArrayOf(0, 2_000).covers(500, 2_000))
    }

    @Test
    fun readableContentRequiresRealPixelsAtTheViewportCenter() {
        assertEquals(false, intArrayOf(0, 499).containsPoint(500L))
        assertEquals(true, intArrayOf(0, 501).containsPoint(500L))
        assertThrows(IllegalArgumentException::class.java) {
            intArrayOf(0, 501, 700).containsPoint(500L)
        }
    }

    @Test
    fun residentAndLoadingIntervalsArePackedInSourceOrderWithoutClaimingFullContent() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val dimensions = PageDimensions(1_000, 2_000)
        val tile = PixelTileRef(71L, 0, 1_000, 1_000, 72L)
        val plan = FramePlan(
            generation = 1L,
            viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(2_000)),
            scrollOffset = FixedPx.ZERO,
            contentHeight = FixedPx.fromPixels(2_000),
            pages = listOf(PagePlacement(
                0,
                PageId.at(episode, 0),
                FixedPx.ZERO,
                FixedPx.fromPixels(2_000),
                PixelRef(tile.handle, dimensions, 4_000_000L, listOf(tile)),
            )),
            loading = listOf(LoadingPlacement(
                0,
                FixedPx.fromPixels(1_000),
                FixedPx.fromPixels(1_000),
                1_000,
                2_000,
                2_000,
            )),
        )

        val packed = requireNotNull(NativeFramePacker().pack(plan))

        assertEquals(2, packed.count)
        assertEquals(1, packed.handles.size)
        assertEquals(1_000, packed.tileData[3])
        assertEquals(1_000, packed.tileData[12 + 2])
        assertEquals(3, packed.tileData[12 + 7])
        assertEquals(false, packed.hasCompleteVisibleContent())
        assertEquals(true, packed.hasCompleteVisualCoverage())
    }

    @Test
    fun loadingGeometryProducesVisibleNativeTilesWithoutClaimingImageContent() {
        val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(1_920))
        val planner = LoadingFramePlanner(screenfulCount = 8)
        val frame = planner.plan(planner.geometry(viewport), FixedPx.fromPixels(300))

        val packed = requireNotNull(NativeFramePacker().pack(frame))

        assertEquals(true, packed.count > 0)
        assertEquals(3, packed.tileData[7])
        assertEquals(0, packed.handles.size)
        assertEquals(false, packed.hasCompleteVisibleContent())
        assertEquals(true, packed.hasCompleteVisualCoverage())
    }

    @Test
    fun emptyVisiblePixelsProduceAClearFrame() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val plan = FramePlan(
            generation = 1L,
            viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(1_920)),
            scrollOffset = FixedPx.ZERO,
            contentHeight = FixedPx.fromPixels(2_000),
            pages = listOf(PagePlacement(
                ordinal = 0,
                pageId = PageId.at(episode, 0),
                top = FixedPx.ZERO,
                height = FixedPx.fromPixels(2_000),
                pixel = null,
            )),
        )

        val packed = requireNotNull(NativeFramePacker(overscanScreenfuls = 0).pack(plan))

        assertEquals(0, packed.count)
        assertEquals(0, packed.handles.size)
        assertEquals(false, packed.hasCompleteVisibleContent())
        assertEquals(false, packed.hasCompleteVisualCoverage())
    }

    @Test
    fun packsEveryResidentBandIntersectingTheInstalledLocalBand() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val dimensions = PageDimensions(1_000, 5_000)
        val pixel = PixelRef(
            handle = 17L,
            dimensions = dimensions,
            allocationBytes = 16_000L,
            tiles = listOf(
                PixelTileRef(17L, 0, 2_500, 2_000, 91L),
                PixelTileRef(18L, 2_500, 5_000, 2_000, 92L),
            ),
        )
        val plan = FramePlan(
            generation = 3L,
            viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(2_000)),
            scrollOffset = FixedPx.fromPixels(500),
            contentHeight = FixedPx.fromPixels(5_400),
            pages = listOf(PagePlacement(
                ordinal = 12,
                pageId = PageId.at(episode, 12),
                top = FixedPx.fromPixels(400),
                height = FixedPx.fromPixels(5_000),
                pixel = pixel,
            )),
        )

        val packed = requireNotNull(NativeFramePacker(overscanScreenfuls = 1).pack(plan))

        assertEquals(2, packed.count)
        assertEquals(1_000, packed.width)
        assertEquals(2_000, packed.height)
        assertEquals(12, packed.tileData[0])
        assertEquals(91, packed.tileData[10])
        assertEquals(400, packed.geometryData[0])
        assertEquals(2_900, packed.geometryData[1])
        assertEquals(4_500, packed.bandHeight)
        assertEquals(500, packed.viewportTop)
        assertEquals(true, packed.hasCompleteVisibleContent())

        val lower = requireNotNull(NativeFramePacker(overscanScreenfuls = 1).pack(
            plan.copy(scrollOffset = FixedPx.fromPixels(3_000)),
        ))
        assertEquals(2, lower.count)
        assertEquals(0, lower.tileData[2])
        assertEquals(91, lower.tileData[10])
        assertEquals(2_500, lower.tileData[14])
        assertEquals(92, lower.tileData[22])
    }

    @Test
    fun residentOverscanTileDoesNotClaimContentOutsideTheActualViewport() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val dimensions = PageDimensions(1_000, 4_000)
        val tile = PixelTileRef(23L, 0, 1_000, 1_000, 41L)
        val plan = FramePlan(
            generation = 1L,
            viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(1_000)),
            scrollOffset = FixedPx.fromPixels(1_500),
            contentHeight = FixedPx.fromPixels(4_000),
            pages = listOf(PagePlacement(
                ordinal = 0,
                pageId = PageId.at(episode, 0),
                top = FixedPx.ZERO,
                height = FixedPx.fromPixels(4_000),
                pixel = PixelRef(23L, dimensions, 4_000L, listOf(tile)),
            )),
        )

        val packed = requireNotNull(NativeFramePacker().pack(plan))

        assertEquals(1, packed.count)
        assertEquals(false, packed.hasCompleteVisibleContent())
        assertEquals(false, packed.hasCompleteVisibleContent(viewportTop = 499))
        assertEquals(false, packed.hasCompleteVisibleContent(viewportTop = 500))
    }

    @Test
    fun documentLargerThanIntPixelsPacksInViewportLocalCoordinates() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val pageTopPixels = 3_000_000_000L
        val pageHeightPixels = 10_000L
        val viewportHeightPixels = 1_920L
        val dimensions = PageDimensions(1_080, 10_000)
        val tile = PixelTileRef(29L, 0, 2_048, 2_048, 51L)
        val plan = FramePlan(
            generation = 5L,
            viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx(viewportHeightPixels * 1_024L)),
            scrollOffset = FixedPx(pageTopPixels * 1_024L),
            contentHeight = FixedPx((pageTopPixels + pageHeightPixels) * 1_024L),
            pages = listOf(PagePlacement(
                ordinal = 499,
                pageId = PageId.at(episode, 499),
                top = FixedPx(pageTopPixels * 1_024L),
                height = FixedPx(pageHeightPixels * 1_024L),
                pixel = PixelRef(29L, dimensions, 8_847_360L, listOf(tile)),
            )),
        )

        val packed = requireNotNull(NativeFramePacker().pack(plan))

        assertEquals(FixedPx((pageTopPixels - viewportHeightPixels) * 1_024L), packed.coordinateOrigin)
        assertEquals(1_920, packed.viewportTop)
        assertEquals(5_760, packed.bandHeight)
        assertEquals(1_920, packed.geometryData[0])
        assertEquals(3_968, packed.geometryData[1])
        assertEquals(true, packed.hasCompleteVisibleContent())
    }

    @Test
    fun packRejectsOutOfRangeScrollInsteadOfSilentlyClampingIt() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val plan = FramePlan(
            generation = 1L,
            viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(1_000)),
            scrollOffset = FixedPx.fromPixels(1_001),
            contentHeight = FixedPx.fromPixels(2_000),
            pages = listOf(PagePlacement(
                ordinal = 0,
                pageId = PageId.at(episode, 0),
                top = FixedPx.ZERO,
                height = FixedPx.fromPixels(2_000),
                pixel = null,
            )),
        )

        assertEquals(null, NativeFramePacker().pack(plan))
    }

    @Test
    fun packsTheCompleteNativePublicationIdentityWithoutTruncation() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val dimensions = PageDimensions(1_237, 9_001)
        val version = (7L shl 32) or 0x89abL
        val handle = (5L shl 32) or 0xcdefL
        val tile = PixelTileRef(handle, 1_111, 3_333, 1_439, version, 9_437_184L)
        val plan = FramePlan(
            generation = 1L,
            viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(1_920)),
            scrollOffset = FixedPx.fromPixels(1_000),
            contentHeight = FixedPx.fromPixels(9_001),
            pages = listOf(PagePlacement(
                ordinal = 4,
                pageId = PageId.at(episode, 4),
                top = FixedPx.ZERO,
                height = FixedPx.fromPixels(9_001),
                pixel = PixelRef(handle, dimensions, tile.allocationBytes, listOf(tile)),
            )),
        )

        val packed = requireNotNull(NativeFramePacker(overscanScreenfuls = 1).pack(plan))

        assertEquals(1_111, packed.tileData[2])
        assertEquals(3_333, packed.tileData[3])
        assertEquals(1_237, packed.tileData[4])
        assertEquals(9_001, packed.tileData[5])
        assertEquals(handle, packed.halvesAt(8))
        assertEquals(version, packed.halvesAt(10))
    }

    @Test
    fun installedSceneIgnoresResidentChangesOutsideItsImmutableBand() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(1_000))
        val dimensions = PageDimensions(1_000, 1_000)
        fun pixel(handle: Long) = PixelRef(
            handle,
            dimensions,
            4_000_000L,
            listOf(PixelTileRef(handle, 0, 1_000, 1_000, handle + 100L)),
        )
        val initial = FramePlan(
            generation = 9L,
            viewport = viewport,
            scrollOffset = FixedPx.ZERO,
            contentHeight = FixedPx.fromPixels(3_000),
            pages = listOf(
                PagePlacement(0, PageId.at(episode, 0), FixedPx.ZERO, FixedPx.fromPixels(1_000), pixel(1)),
                PagePlacement(1, PageId.at(episode, 1), FixedPx.fromPixels(1_000), FixedPx.fromPixels(1_000), pixel(2)),
                PagePlacement(2, PageId.at(episode, 2), FixedPx.fromPixels(2_000), FixedPx.fromPixels(1_000), null),
            ),
        )
        val packer = NativeFramePacker(overscanScreenfuls = 1)
        val installed = requireNotNull(packer.pack(initial)).frozenForRender()
        val contract = NativeFullFrameContract(
            structureEpoch = 1L,
            plan = initial,
            renderWidth = installed.width,
            renderHeight = installed.height,
            bandHeight = installed.bandHeight,
            coordinateOrigin = installed.coordinateOrigin,
            localTileRanges = installed.localTileRanges,
            localVisualRanges = installed.localVisualRanges,
            sceneSignature = installed.sceneSignature,
        )

        val changedOutsideBand = requireNotNull(
            packer.packInstalled(initial.copy(pages = initial.pages.mapIndexed { index, page ->
                if (index == 2) page.copy(pixel = pixel(99)) else page
            }), contract),
        )

        assertEquals(installed.sceneSignature, changedOutsideBand.sceneSignature)
        assertEquals(installed.tileData.toList(), changedOutsideBand.tileData.take(installed.count * 12))
    }

    @Test
    fun frozenSceneIsNotMutatedByThePackersNextFrame() {
        val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")
        val dimensions = PageDimensions(1_000, 1_000)
        fun plan(handle: Long) = FramePlan(
            generation = 1L,
            viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(1_000)),
            scrollOffset = FixedPx.ZERO,
            contentHeight = FixedPx.fromPixels(1_000),
            pages = listOf(PagePlacement(
                0,
                PageId.at(episode, 0),
                FixedPx.ZERO,
                FixedPx.fromPixels(1_000),
                PixelRef(
                    handle,
                    dimensions,
                    4_000_000L,
                    listOf(PixelTileRef(handle, 0, 1_000, 1_000, handle + 10L)),
                ),
            )),
        )
        val packer = NativeFramePacker()
        val frozen = requireNotNull(packer.pack(plan(7))).frozenForRender()

        requireNotNull(packer.pack(plan(88)))

        assertEquals(7L, frozen.halvesAt(8))
        assertEquals(17L, frozen.halvesAt(10))
    }

    private fun PackedNativeFrame.halvesAt(offset: Int): Long =
        tileData[offset].toLong().and(0xffff_ffffL) or
            (tileData[offset + 1].toLong().and(0xffff_ffffL) shl 32)
}
