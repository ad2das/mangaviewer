package ml.melun.mangaview.viewer.session

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

class DemandAndSceneTest {
    @Test
    fun visibleAndForwardPagesAlwaysOutrankBehindPages() {
        val session = session()
        session.applyUserInput(FixedPx.fromPixels(4_000), 3_000.0F)

        val demands = DemandEngine().snapshot(session.state).demands
        val firstBehind = demands.indexOfFirst { it.demandClass == DemandClass.BEHIND }
        val lastVisibleOrForward = demands.indexOfLast {
            it.demandClass == DemandClass.VISIBLE ||
                it.demandClass == DemandClass.CURRENT_FORWARD_NEAR
        }
        assertTrue(firstBehind == -1 || lastVisibleOrForward < firstBehind)
    }

    @Test
    fun sceneRepresentsEveryMissingAndResidentSourceRange() {
        val session = session()
        val pageId = session.state.timeline.pages.first().id
        session.visualReady(pageId, VisualBand(300, 900, 1_600, VisualKey(9)))
        val scene = SceneBuilder(overscanScreens = 4).build(session.state)
        val pageQuads = scene.quads.filter { it.pageId == pageId }

        assertEquals(listOf(0, 300, 900), pageQuads.map(SceneQuad::sourceTopPx))
        assertEquals(listOf(300, 900, 1_600), pageQuads.map(SceneQuad::sourceBottomPx))
        assertEquals(listOf(null, VisualKey(9), null), pageQuads.map(SceneQuad::visualKey))
    }

    @Test
    fun priorEpisodePrefixIsNeverClassifiedAsAdjacentForward() {
        val session = session()
        val prior = session.state.timeline.episodes.first().manifest
        val nextId = requireNotNull(prior.nextEpisodeId)
        session.appendEpisode(manifest(nextId, next = null))
        session.navigateEpisode(nextId)

        val priorPrefix = DemandEngine().snapshot(session.state).demands
            .first { it.pageId == prior.pages.first().id }

        assertEquals(DemandClass.BEHIND, priorPrefix.demandClass)
    }

    @Test
    fun visibleLongImageRequestsForwardPixelsBeyondTheViewport() {
        val series = SeriesId(SourceId("ntk"), "long")
        val episode = EpisodeId(series, "episode")
        val height = 20_000
        val manifest = EpisodeManifest(
            episode,
            "Long webtoon",
            listOf(PageSpec(PageId.at(episode, 0), 0, PageDimensions(1_080, height))),
        )
        val session = ViewerSession(
            Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(2_000)),
        ).also {
            it.initialManifestResolved(manifest)
            it.savedPositionResolved(null)
        }

        val range = requireNotNull(DemandEngine().snapshot(session.state).demands.single().sourceRange)
        val requestedBottom = range.endQ32 * height / SemanticViewportAnchor.Q32_ONE

        assertTrue(requestedBottom >= 5_999L)
    }

    private fun session(): ViewerSession {
        val series = SeriesId(SourceId("ntk"), "demand")
        val episode = EpisodeId(series, "episode")
        val manifest = EpisodeManifest(
            episode,
            "Episode",
            List(8) { ordinal ->
                PageSpec(PageId.at(episode, ordinal), ordinal, PageDimensions(1_080, 1_600))
            },
            nextEpisodeId = EpisodeId(series, "episode-next"),
        )
        return ViewerSession(
            Viewport(FixedPx.fromPixels(1_080), FixedPx.fromPixels(2_138)),
        ).also {
            it.initialManifestResolved(manifest)
            it.savedPositionResolved(null)
        }
    }

    private fun manifest(id: EpisodeId, next: EpisodeId?) = EpisodeManifest(
        id,
        "Next",
        List(8) { ordinal ->
            PageSpec(PageId.at(id, ordinal), ordinal, PageDimensions(1_080, 1_600))
        },
        nextEpisodeId = next,
    )
}
