package ml.melun.mangaview.viewer.session

import kotlin.random.Random
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSessionInvariantPropertyTest {
    @Test
    fun oneToFiveHundredPagesPreserveTheVisibleSemanticPixel() {
        val random = Random(0x5E5510)
        repeat(40) { run ->
            val pageCount = when (run) {
                0 -> 1
                1 -> 500
                else -> random.nextInt(1, 501)
            }
            val fixture = PropertyFixture(run, pageCount, random)
            val session = fixture.session()
            val safeMaximum = (session.state.realContentHeight - fixture.viewport.height)
                .coerceIn(FixedPx.ZERO, session.state.maximumScroll)
            val target = if (safeMaximum.units == 0L) 0L else {
                random.nextLong(0L, safeMaximum.units + 1L)
            }
            session.applyUserInput(FixedPx(target), 0.0F)

            fixture.unresolved.shuffled(random).forEach { page ->
                val before = semanticAnchor(session)
                session.resolvePageDimensions(page.id, fixture.actualDimensions(page.ordinal))
                assertSemanticPixelPreserved(before, session)
            }
        }
    }

    @Test
    fun workerAndLifecycleEventsCannotMutateScrollAcrossAllAspectRatios() {
        val random = Random(0xA11CE)
        repeat(100) { run ->
            val fixture = PropertyFixture(run, random.nextInt(1, 501), random)
            val session = fixture.session()
            session.applyUserInput(
                FixedPx(random.nextLong(0L, session.state.maximumScroll.units + 1L)),
                random.nextDouble(-12_000.0, 12_000.0).toFloat(),
            )
            repeat(50) { event ->
                val before = session.state.scroll
                val page = fixture.pages[random.nextInt(fixture.pages.size)]
                when (random.nextInt(5)) {
                    0 -> session.visualReady(
                        page.id,
                        VisualBand(0, 1, 1, VisualKey(event.toLong() + 1L)),
                    )
                    1 -> session.visualEvicted(page.id, VisualKey(event.toLong() + 1L))
                    2 -> session.surfaceAttached()
                    3 -> session.enterBackground()
                    else -> session.enterForeground()
                }
                assertEquals(before.contentOffset, session.state.scroll.contentOffset)
            }
        }
    }

    private fun semanticAnchor(session: ViewerSession): SemanticViewportAnchor? =
        (ScrollModel.anchor(
            session.state.scroll,
            session.state.viewport,
            requireNotNull(session.state.layout),
        ) as? SemanticViewportAnchor)

    private fun assertSemanticPixelPreserved(
        before: SemanticViewportAnchor?,
        session: ViewerSession,
    ) {
        before ?: return
        assertEquals(before.pageId, semanticAnchor(session)?.pageId)
        val layout = requireNotNull(session.state.layout)
        val top = requireNotNull(layout.topOf(before.pageId))
        val inside = before.offsetInPage.units
        val screen = top.units + inside - session.state.scroll.contentOffset.units
        assertEquals(before.viewportOffset.units, screen)
    }
}

private class PropertyFixture(
    run: Int,
    count: Int,
    private val random: Random,
) {
    val viewport = Viewport(
        FixedPx.fromPixels(listOf(720, 1_080, 1_440)[run % 3]),
        FixedPx.fromPixels(listOf(1_280, 2_138, 3_200)[run % 3]),
    )
    private val episode = EpisodeId(SeriesId(SourceId("property"), "series-$run"), "episode")
    val pages = List(count) { ordinal ->
        PageSpec(
            PageId.at(episode, ordinal),
            ordinal,
            if (ordinal % 3 == 0) actualDimensions(ordinal) else null,
        )
    }
    val unresolved = pages.filter { it.dimensions == null }

    fun session(): ViewerSession = ViewerSession(viewport).also {
        it.initialManifestResolved(EpisodeManifest(episode, "Property", pages))
        it.savedPositionResolved(null)
    }

    fun actualDimensions(ordinal: Int): PageDimensions {
        val width = listOf(480, 720, 1_080, 1_440, 2_560)[ordinal % 5]
        val multiplier = listOf(1, 2, 4, 10, 30)[ordinal % 5]
        return PageDimensions(width, width * multiplier + random.nextInt(0, width))
    }
}
