package ml.melun.mangaview.viewer

import java.util.Random
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutLedgerPropertyTest {
    private val controller = ScrollController()

    @Test
    fun delayedDimensionsPreserveTheExactLogicalAnchorForOneToFiveHundredPages() {
        val random = Random(0x5EEDL)
        for (pageCount in listOf(1, 2, 3, 10, 50, 100, 500)) {
            val manifest = ViewerFixtures.manifest(pageCount)
            var ledger = LayoutLedger.create(manifest.pages, FixedPx.fromPixels(1_080))
            val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(100))
            val anchorIndex = if (pageCount == 1) 0 else pageCount / 3
            var scroll = controller.navigate(
                ledger,
                viewport,
                manifest.pages[anchorIndex].id,
                FixedPx.fromPixels(32),
                1L,
            )
            val expectedAnchor = scroll.anchor
            val shuffled = manifest.pages.shuffled(random)
            for (page in shuffled) {
                val dimensions = randomDimensions(random)
                ledger = ledger.resolve(page.id, dimensions)
                scroll = controller.preserveAnchor(ledger, viewport, scroll)
                assertEquals(expectedAnchor, scroll.anchor)
                assertAnchorIsStationary(ledger, scroll)
            }
        }
    }

    @Test
    fun appendNeverChangesExistingPrefixGeometryOrScroll() {
        val first = ViewerFixtures.manifest(80, "episode-1")
        val second = ViewerFixtures.manifest(120, "episode-2")
        val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(1_920))
        val original = LayoutLedger.create(first.pages, viewport.width)
        val scroll = controller.navigate(
            original,
            viewport,
            first.pages[55].id,
            FixedPx.fromPixels(211),
            9L,
        )
        val oldTops = first.pages.associate { it.id to original.topOf(it.id) }

        val appended = original.append(second.pages)

        assertEquals(oldTops, first.pages.associate { it.id to appended.topOf(it.id) })
        assertEquals(scroll, controller.preserveAnchor(appended, viewport, scroll))
        assertTrue(appended.totalHeight > original.totalHeight)
    }

    @Test
    fun repeatedViewportReflowDoesNotAccumulateSubpixelDrift() {
        val manifest = ViewerFixtures.manifest(200) { index ->
            PageDimensions(700 + index % 9, 900 + index * 7)
        }
        var ledger = LayoutLedger.create(manifest.pages, FixedPx.fromPixels(1_080))
        var viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(1_000))
        var scroll = controller.navigate(
            ledger,
            viewport,
            manifest.pages[91].id,
            FixedPx.fromPixels(123.25),
            1L,
        )
        val anchor = scroll.anchor
        val widths = listOf(720, 1_440, 1_080, 900, 1_080)
        for (width in widths) {
            viewport = Viewport(FixedPx.fromPixels(width), FixedPx.fromPixels(1_000))
            ledger = ledger.reflow(viewport.width)
            scroll = controller.preserveAnchor(ledger, viewport, scroll)
            assertEquals(anchor, scroll.anchor)
            assertAnchorIsStationary(ledger, scroll)
        }
    }

    @Test
    fun resolvingOneOfFiveHundredPagesRetainsUnchangedEntryObjects() {
        val manifest = ViewerFixtures.manifest(500)
        val original = LayoutLedger.create(manifest.pages, FixedPx.fromPixels(1_080))

        val resolved = original.resolve(manifest.pages[250].id, PageDimensions(1_001, 9_999))

        assertSame(original.entries[0], resolved.entries[0])
        assertSame(original.entries[249], resolved.entries[249])
        assertSame(original.entries[251], resolved.entries[251])
        assertSame(original.entries[499], resolved.entries[499])
    }

    @Test
    fun navigationBeyondTheLastScrollablePixelSnapshotsTheActualClampedAnchor() {
        val manifest = ViewerFixtures.manifest(3) { PageDimensions(1_000, 1_000) }
        val viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(1_500))
        val ledger = LayoutLedger.create(manifest.pages, viewport.width)

        val scroll = controller.navigate(
            ledger,
            viewport,
            manifest.pages.last().id,
            FixedPx.fromPixels(999),
            7L,
        )

        assertEquals(FixedPx.fromPixels(1_500), scroll.contentOffset)
        assertEquals(manifest.pages[1].id, scroll.anchor.pageId)
        assertEquals(FixedPx.fromPixels(500).units, scroll.anchor.offsetInPageUnits)
        assertAnchorIsStationary(ledger, scroll)
    }

    @Test
    fun resolvingAShortAnchorPageCannotLeaveAPhantomOffset() {
        val manifest = ViewerFixtures.manifest(3)
        val viewport = Viewport(FixedPx.fromPixels(1_000), FixedPx.fromPixels(500))
        var ledger = LayoutLedger.create(manifest.pages, viewport.width)
        var scroll = controller.navigate(
            ledger,
            viewport,
            manifest.pages[1].id,
            FixedPx.fromPixels(1_499),
            7L,
        )

        ledger = ledger.resolve(manifest.pages[1].id, PageDimensions(2_000, 100))
        scroll = controller.preserveAnchor(ledger, viewport, scroll)

        val height = requireNotNull(ledger.heightOf(manifest.pages[1].id))
        assertEquals(manifest.pages[1].id, scroll.anchor.pageId)
        assertEquals(height.units - 1L, scroll.anchor.offsetInPageUnits)
        assertEquals(
            manifest.pages[1].id,
            ledger.pageAt(requireNotNull(ledger.topOf(manifest.pages[1].id)) + FixedPx(height.units - 1L)),
        )
        assertAnchorIsStationary(ledger, scroll)

        val recaptured = controller.scrollBy(ledger, viewport, scroll, FixedPx.ZERO)
        assertEquals(scroll.anchor, recaptured.anchor)
        assertEquals(scroll.contentOffset, recaptured.contentOffset)
    }

    @Test
    fun randomExtremeResolutionReflowAndAppendAlwaysKeepAnchorInsideItsPage() {
        val random = Random(0xA11C_E55L)
        val widths = intArrayOf(1, 360, 1_080, 4_096, 8_192)
        repeat(40) { iteration ->
            val manifest = ViewerFixtures.manifest(1 + random.nextInt(100), "episode-$iteration")
            var viewport = Viewport(
                width = FixedPx.fromPixels(widths[random.nextInt(widths.size)]),
                height = FixedPx(1L),
            )
            var ledger = LayoutLedger.create(manifest.pages, viewport.width)
            val anchorId = manifest.pages[random.nextInt(manifest.pages.size)].id
            val estimatedHeight = requireNotNull(ledger.heightOf(anchorId))
            var scroll = controller.navigate(
                ledger,
                viewport,
                anchorId,
                FixedPx(random.nextLong().ushr(1) % estimatedHeight.units),
                1L,
            )

            manifest.pages.shuffled(random).forEachIndexed { index, page ->
                ledger = ledger.resolve(page.id, extremeDimensions(random, index))
                if (index % 7 == 0) {
                    viewport = viewport.copy(
                        width = FixedPx.fromPixels(widths[random.nextInt(widths.size)]),
                    )
                    ledger = ledger.reflow(viewport.width)
                }
                scroll = controller.preserveAnchor(ledger, viewport, scroll)
                assertEquals(anchorId, scroll.anchor.pageId)
                assertValidAnchor(ledger, scroll)
            }

            val appended = ViewerFixtures.manifest(1 + random.nextInt(20), "append-$iteration")
            val beforeAppend = scroll
            ledger = ledger.append(appended.pages)
            scroll = controller.preserveAnchor(ledger, viewport, scroll)
            assertEquals(beforeAppend, scroll)
            assertValidAnchor(ledger, scroll)
        }
    }

    private fun randomDimensions(random: Random): PageDimensions = PageDimensions(
        widthPx = 320 + random.nextInt(2_500),
        heightPx = 320 + random.nextInt(18_000),
    )

    private fun extremeDimensions(random: Random, index: Int): PageDimensions = when (index % 8) {
        0 -> PageDimensions(Int.MAX_VALUE, 1)
        1 -> PageDimensions(10_000, 1)
        2 -> PageDimensions(8_192, 1_024)
        3 -> PageDimensions(1, 8_192)
        4 -> PageDimensions(1, 100_000)
        else -> PageDimensions(
            widthPx = 1 + random.nextInt(8_192),
            heightPx = 1 + random.nextInt(100_000),
        )
    }

    private fun assertValidAnchor(ledger: LayoutLedger, scroll: ScrollSnapshot) {
        val height = requireNotNull(ledger.heightOf(scroll.anchor.pageId))
        assertTrue(scroll.anchor.offsetInPageUnits in 0 until height.units)
        val anchorPoint = requireNotNull(ledger.topOf(scroll.anchor.pageId)) +
            FixedPx(scroll.anchor.offsetInPageUnits)
        assertEquals(scroll.anchor.pageId, ledger.pageAt(anchorPoint))
        assertAnchorIsStationary(ledger, scroll)
    }

    private fun assertAnchorIsStationary(ledger: LayoutLedger, scroll: ScrollSnapshot) {
        val top = requireNotNull(ledger.topOf(scroll.anchor.pageId))
        val screenPosition = top.units + scroll.anchor.offsetInPageUnits - scroll.contentOffset.units
        assertEquals(scroll.anchor.viewportOffsetUnits, screenPosition)
    }
}
