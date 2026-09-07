package ml.melun.mangaview.viewer.session

import kotlin.random.Random
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSessionTest {
    @Test
    fun homeFreezesTheFinalInputPositionAndDoesNotResumeOldVelocity() {
        val fixture = Fixture()
        val session = fixture.openSession(fixture.episode(0, next = true, known = true))
        session.applyUserInput(FixedPx.fromPixels(800), 5_000F)
        val finalPosition = session.state.scroll.contentOffset
        session.enterBackground()
        assertEquals(finalPosition, session.state.scroll.contentOffset)
        assertEquals(0F, session.state.scroll.velocityPixelsPerSecond, 0F)
        session.enterForeground()
        assertEquals(finalPosition, session.state.scroll.contentOffset)
        assertEquals(0F, session.state.scroll.velocityPixelsPerSecond, 0F)
    }

    @Test
    fun nonScrollEventsNeverMoveCanonicalPosition() {
        val fixture = Fixture()
        val session = fixture.openSession(fixture.episode(0, next = true, known = false))
        session.applyUserInput(FixedPx.fromPixels(2_900), 5_000.0F)
        val before = session.state.scroll

        session.surfaceAttached()
        session.enterBackground()
        session.enterForeground()
        session.visualReady(
            fixture.page(0, 2),
            VisualBand(0, 800, 1_600, VisualKey(1)),
        )

        assertEquals(before.contentOffset, session.state.scroll.contentOffset)
        assertEquals(0F, session.state.scroll.velocityPixelsPerSecond, 0F)
    }

    @Test
    fun textureEvictionRemovesOnlyItsVisualAndNeverMovesScroll() {
        val fixture = Fixture()
        val session = fixture.openSession(fixture.episode(0, next = true, known = true))
        val page = fixture.page(0, 0)
        session.applyUserInput(FixedPx.fromPixels(300), 0.0F)
        session.visualReady(page, VisualBand(0, 800, 1_600, VisualKey(1)))
        session.visualReady(page, VisualBand(800, 1_600, 1_600, VisualKey(2)))
        val before = session.state.scroll

        session.visualEvicted(page, VisualKey(1))

        assertEquals(before, session.state.scroll)
        assertEquals(listOf(VisualKey(2)), session.state.visuals.getValue(page).map { it.key })
    }

    @Test
    fun rendererInvalidationRemovesAllVisualsWithoutMovingScroll() {
        val fixture = Fixture()
        val session = fixture.openSession(fixture.episode(0, next = true, known = true))
        val page = fixture.page(0, 0)
        session.applyUserInput(FixedPx.fromPixels(300), 0.0F)
        session.visualReady(page, VisualBand(0, 1_600, 1_600, VisualKey(7)))
        val before = session.state.scroll

        session.visualsInvalidated()

        assertTrue(session.state.visuals.isEmpty())
        assertEquals(before, session.state.scroll)
    }

    @Test
    fun geometryResolutionPreservesTheSameSemanticPixel() {
        val fixture = Fixture()
        val manifest = fixture.episode(0, next = true, known = false)
        val session = fixture.openSession(manifest)
        session.applyUserInput(FixedPx.fromPixels(3_450), 0.0F)
        val beforeLayout = requireNotNull(session.state.layout)
        val beforeAnchor = ScrollModel.anchor(
            session.state.scroll,
            session.state.viewport,
            beforeLayout,
        ) as SemanticViewportAnchor

        session.resolvePageDimensions(beforeAnchor.pageId, PageDimensions(900, 3_700))

        val afterAnchor = ScrollModel.anchor(
            session.state.scroll,
            session.state.viewport,
            requireNotNull(session.state.layout),
        ) as SemanticViewportAnchor
        assertEquals(beforeAnchor.pageId, afterAnchor.pageId)
        assertEquals(beforeAnchor.viewportOffset, afterAnchor.viewportOffset)
        val afterLayout = requireNotNull(session.state.layout)
        val pageTop = requireNotNull(afterLayout.topOf(afterAnchor.pageId))
        val actualInside = session.state.scroll.contentOffset.units +
            afterAnchor.viewportOffset.units - pageTop.units
        assertEquals(beforeAnchor.offsetInPage.units, actualInside)
    }

    @Test
    fun appendKeepsEveryExistingPageTopAndCurrentAnchor() {
        val fixture = Fixture()
        val first = fixture.episode(0, next = true, known = true)
        val second = fixture.episode(1, next = false, known = true)
        val session = fixture.openSession(first)
        session.applyUserInput(FixedPx.fromPixels(3_200), 0.0F)
        val oldLayout = requireNotNull(session.state.layout)
        val oldTops = first.pages.associate { it.id to oldLayout.topOf(it.id) }
        val beforeOffset = session.state.scroll.contentOffset

        session.appendEpisode(second)

        val newLayout = requireNotNull(session.state.layout)
        oldTops.forEach { (pageId, top) -> assertEquals(top, newLayout.topOf(pageId)) }
        assertEquals(beforeOffset, session.state.scroll.contentOffset)
    }

    @Test
    fun appendReplacesTheRunwayInPlaceWithoutMovingTheViewport() {
        val fixture = Fixture()
        val first = fixture.episode(0, next = true, known = true)
        val session = fixture.openSession(first)
        session.applyUserInput(session.state.maximumScroll, 0.0F)
        val oldLayout = requireNotNull(session.state.layout)
        val oldOffset = session.state.scroll.contentOffset

        session.appendEpisode(fixture.episode(1, next = true, known = true))

        val newLayout = requireNotNull(session.state.layout)
        assertEquals(oldOffset, session.state.scroll.contentOffset)
        assertEquals(oldLayout.totalHeight, newLayout.topOf(fixture.page(1, 0)))
    }

    @Test
    fun openingInputIsAppliedExactlyOnceToSavedAnchor() {
        val fixture = Fixture()
        val manifest = fixture.episode(0, next = true, known = true)
        val session = ViewerSession(fixture.viewport)
        val delta = FixedPx.fromPixels(611)
        session.applyUserInput(delta, 1_200.0F)
        session.initialManifestResolved(manifest)
        val saved = ReadingPosition(fixture.page(0, 2), FixedPx.fromPixels(300).units)
        session.savedPositionResolved(saved)
        val resolved = session.state.scroll.contentOffset

        session.surfaceAttached()
        session.enterBackground()
        session.enterForeground()

        assertEquals(resolved, session.state.scroll.contentOffset)
        val layout = requireNotNull(session.state.layout)
        val expectedBase = requireNotNull(layout.topOf(saved.pageId)) +
            FixedPx(saved.offsetInPageUnits)
        assertEquals(expectedBase + delta, resolved)
    }

    @Test
    fun randomizedWorkerLikeEventsCannotMoveScroll() {
        val random = Random(0x51A7)
        repeat(100) { run ->
            val fixture = Fixture("series-$run")
            val session = fixture.openSession(fixture.episode(0, next = true, known = false))
            session.applyUserInput(FixedPx.fromPixels(random.nextInt(0, 4_000)), 0.0F)
            repeat(100) { event ->
                val before = session.state.scroll
                val page = fixture.page(0, random.nextInt(0, 6))
                when (random.nextInt(4)) {
                    0 -> session.visualReady(
                        page,
                        VisualBand(0, 400, 1_600, VisualKey(event.toLong() + 1L)),
                    )
                    1 -> session.surfaceAttached()
                    2 -> session.enterForeground()
                    else -> session.enterBackground()
                }
                assertEquals(before, session.state.scroll)
            }
        }
    }
}

private class Fixture(seriesKey: String = "series") {
    val viewport = Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(2_138))
    private val seriesId = SeriesId(SourceId("ntk"), seriesKey)

    fun page(episode: Int, ordinal: Int): PageId = PageId.at(
        EpisodeId(seriesId, "episode-$episode"),
        ordinal,
    )

    fun episode(episode: Int, next: Boolean, known: Boolean): EpisodeManifest {
        val id = EpisodeId(seriesId, "episode-$episode")
        return EpisodeManifest(
            id = id,
            title = "Episode $episode",
            pages = List(6) { ordinal ->
                PageSpec(
                    PageId.at(id, ordinal),
                    ordinal,
                    if (known) PageDimensions(1_080, 1_600 + ordinal * 173) else null,
                )
            },
            previousEpisodeId = if (episode > 0) EpisodeId(seriesId, "episode-${episode - 1}") else null,
            nextEpisodeId = if (next) EpisodeId(seriesId, "episode-${episode + 1}") else null,
        )
    }

    fun openSession(manifest: EpisodeManifest): ViewerSession = ViewerSession(viewport).also {
        it.initialManifestResolved(manifest)
        it.savedPositionResolved(null)
    }
}
