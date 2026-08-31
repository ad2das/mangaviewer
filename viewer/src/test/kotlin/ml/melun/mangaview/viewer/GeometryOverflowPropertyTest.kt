package ml.melun.mangaview.viewer

import java.util.Random
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryOverflowPropertyTest {
    private val controller = ScrollController()

    @Test
    fun fiveHundredMaximumHeightPagesRemainExactlyAddressable() {
        val width = FixedPx.fromPixels(8_192)
        val dimensions = PageDimensions(1, Int.MAX_VALUE)
        val manifest = ViewerFixtures.manifest(500) { dimensions }

        val ledger = LayoutLedger.create(manifest.pages, width)

        val pageHeight = multiplyDivideFloorExact(width.units, Int.MAX_VALUE, 1)
        assertEquals(Math.multiplyExact(pageHeight, 500L), ledger.totalHeight.units)
        assertEquals(manifest.pages.last().id, ledger.pageAt(FixedPx(ledger.totalHeight.units - 1L)))
        ledger.entries.forEachIndexed { index, entry ->
            assertEquals(pageHeight, entry.height.units)
            assertEquals(Math.multiplyExact(pageHeight, index.toLong()), ledger.topAt(index).units)
        }
    }

    @Test
    fun randomizedDelayedGeometryAndReflowPreserveLogicalAnchor() {
        val random = Random(0x6E6F_6A75_6D70L)
        val manifest = ViewerFixtures.manifest(500)
        var viewport = Viewport(FixedPx.fromPixels(4_096), FixedPx.fromPixels(1))
        var ledger = LayoutLedger.create(manifest.pages, viewport.width)
        var scroll = controller.navigate(
            ledger,
            viewport,
            manifest.pages[250].id,
            FixedPx.ZERO,
            1L,
        )
        val anchorPage = scroll.anchor.pageId
        val anchorOffset = scroll.anchor.offsetInPageUnits
        val widths = intArrayOf(1, 720, 4_096, 8_192)

        manifest.pages.shuffled(random).forEachIndexed { step, page ->
            ledger = ledger.resolve(page.id, randomDimensions(random, step))
            if (step % 37 == 0) {
                viewport = Viewport(
                    FixedPx.fromPixels(widths[(step / 37) % widths.size]),
                    FixedPx.fromPixels(1),
                )
                ledger = ledger.reflow(viewport.width)
            }
            scroll = controller.preserveAnchor(ledger, viewport, scroll)
            assertEquals(anchorPage, scroll.anchor.pageId)
            assertEquals(anchorOffset, scroll.anchor.offsetInPageUnits)
            assertConsistentAnchor(ledger, scroll)
        }
    }

    @Test
    fun outOfOrderReducerCompletionsKeepADeepLogicalAnchorValid() {
        val random = Random(0xC0FFEE)
        val manifest = ViewerFixtures.manifest(500)
        var viewport = Viewport(FixedPx.fromPixels(4_096), FixedPx.fromPixels(1))
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 991L,
                manifest = manifest,
                viewport = viewport,
                atNanos = 1L,
                initialPosition = ReadingPosition(
                    manifest.pages[250].id,
                    FixedPx.fromPixels(6_000).units,
                ),
            ),
        ))
        val pending = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().toMutableList()
        val anchorPage = reduction.state.scroll.anchor.pageId
        var expectedAnchorOffset = reduction.state.scroll.anchor.offsetInPageUnits
        var completed = 0
        var now = 2L

        while (pending.isNotEmpty()) {
            val command = pending.removeAt(random.nextInt(pending.size))
            reduction = requireNotNull(reducer.reduce(
                reduction.state,
                ViewerEvent.FetchSucceeded(
                    command.token,
                    VerifiedPageRef(
                        cacheKey = "page-$completed",
                        byteCount = 1L,
                        sha256 = "sha-$completed",
                        dimensions = randomDimensions(random, completed),
                    ),
                    elapsedMillis = 1L,
                    atNanos = now++,
                ),
            ))
            completed += 1
            pending += reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
            if (completed % 53 == 0) {
                viewport = Viewport(FixedPx.fromPixels(if (completed % 106 == 0) 8_192 else 720), FixedPx.fromPixels(1))
                reduction = requireNotNull(reducer.reduce(
                    reduction.state,
                    ViewerEvent.ViewportChanged(viewport, now++),
                ))
                pending += reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
            }
            val anchorHeight = requireNotNull(reduction.state.layout.heightOf(anchorPage)).units
            expectedAnchorOffset = expectedAnchorOffset.coerceAtMost(anchorHeight - 1L)
            assertEquals(anchorPage, reduction.state.scroll.anchor.pageId)
            assertEquals(expectedAnchorOffset, reduction.state.scroll.anchor.offsetInPageUnits)
            assertConsistentAnchor(reduction.state.layout, reduction.state.scroll)
        }

        assertEquals(500, completed)
        assertTrue(reduction.state.coldFetchSweep.isComplete)
    }

    @Test
    fun appendReplaceAndReflowCannotMutatePrefixOrAnchor() {
        val first = ViewerFixtures.manifest(250, "episode-1") { index ->
            PageDimensions(1 + index % 17, 1_000_000 + index * 101)
        }
        val second = ViewerFixtures.manifest(250, "episode-2") { index ->
            PageDimensions(1 + index % 19, 2_000_000 + index * 103)
        }
        var viewport = Viewport(FixedPx.fromPixels(4_096), FixedPx.fromPixels(1))
        var ledger = LayoutLedger.create(first.pages, viewport.width)
        var scroll = controller.navigate(ledger, viewport, first.pages[173].id, FixedPx.ZERO, 9L)
        val prefixTops = first.pages.associate { it.id to requireNotNull(ledger.topOf(it.id)) }
        val boundary = PageSpec(PageId(second.id, "pending"), 0, PageDimensions(2, 3))

        ledger = ledger.append(listOf(boundary))
        assertEquals(prefixTops, first.pages.associate { it.id to requireNotNull(ledger.topOf(it.id)) })
        ledger = ledger.replaceLast(boundary.id, second.pages.first()).append(second.pages.drop(1))
        scroll = controller.preserveAnchor(ledger, viewport, scroll)
        assertEquals(first.pages[173].id, scroll.anchor.pageId)
        assertConsistentAnchor(ledger, scroll)

        listOf(720, 8_192, 1_080, 4_096).forEach { width ->
            viewport = Viewport(FixedPx.fromPixels(width), FixedPx.fromPixels(1))
            ledger = ledger.reflow(viewport.width)
            scroll = controller.preserveAnchor(ledger, viewport, scroll)
            assertEquals(first.pages[173].id, scroll.anchor.pageId)
            assertEquals(0L, scroll.anchor.offsetInPageUnits)
            assertConsistentAnchor(ledger, scroll)
        }
    }

    @Test
    fun retainedAndRenderWindowsSaturateWithoutWrappingNearLongLimit() {
        val viewport = Viewport(FixedPx(Long.MAX_VALUE / 2L), FixedPx(Long.MAX_VALUE / 2L))
        val manifest = ViewerFixtures.manifest(1) { PageDimensions(1, 2) }
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(777L, manifest, viewport, 1L),
        ))
        val moved = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.UserScroll(FixedPx(Long.MAX_VALUE), Long.MAX_VALUE, 2L),
        )).state

        val window = PixelWindowPolicy().window(moved)
        val frame = FramePlanner(overscanScreenfuls = Int.MAX_VALUE).plan(moved)
        val demands = DemandPlanner().plan(moved)

        assertTrue(window.visibleStartUnits >= 0L)
        assertTrue(window.visibleEndUnits >= window.visibleStartUnits)
        assertEquals(moved.layout.totalHeight.units, window.retainedEndUnits)
        assertEquals(Long.MAX_VALUE, frame.pages.single().top.units + frame.pages.single().height.units + 1L)
        assertTrue(demands.all { it.distanceUnits >= 0L })
    }

    @Test
    fun unrepresentableGeometryFailsInsteadOfWrappingNegative() {
        val manifest = ViewerFixtures.manifest(1) { PageDimensions(1, 2) }
        val ledger = LayoutLedger.create(manifest.pages, FixedPx.fromPixels(1_080))

        assertThrows(ArithmeticException::class.java) {
            LayoutLedger.create(manifest.pages, FixedPx(Long.MAX_VALUE))
        }
        assertThrows(IllegalArgumentException::class.java) { FixedPx.fromPixels(Double.NaN) }
        assertEquals(FixedPx(Long.MAX_VALUE), FixedPx(Long.MAX_VALUE) + FixedPx(1L))
        assertEquals(FixedPx(Long.MIN_VALUE), FixedPx(Long.MIN_VALUE) - FixedPx(1L))
        assertTrue(ledger.indicesIntersecting(FixedPx(100L), FixedPx(99L)).isEmpty())
    }

    private fun randomDimensions(random: Random, index: Int): PageDimensions = when (index % 17) {
        0 -> PageDimensions(1, Int.MAX_VALUE)
        1 -> PageDimensions(Int.MAX_VALUE, 1)
        else -> PageDimensions(1 + random.nextInt(8_191), 1 + random.nextInt(2_000_000))
    }

    private fun assertConsistentAnchor(ledger: LayoutLedger, scroll: ScrollSnapshot) {
        val top = requireNotNull(ledger.topOf(scroll.anchor.pageId)).units
        val position = saturatingSubtract(
            saturatingAdd(top, scroll.anchor.offsetInPageUnits),
            scroll.contentOffset.units,
        )
        assertEquals(scroll.anchor.viewportOffsetUnits, position)
    }
}
