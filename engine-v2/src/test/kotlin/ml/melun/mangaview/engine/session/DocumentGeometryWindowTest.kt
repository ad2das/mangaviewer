package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentGeometryWindowTest {
    private val series = SeriesId(SourceId("test"), "window")

    private fun episode(number: Int) = EpisodeId(series, number.toString())

    private fun manifest(number: Int, previous: Int?, next: Int?) = EpisodeManifest(
        episode(number), "episode-$number", listOf(PageSpec(PageId.at(episode(number), 0), 0)),
        previous?.let(::episode), next?.let(::episode),
    )

    @Test
    fun keepsOnlyTheReadingWindowAndTheTarget() {
        val geometry = DocumentGeometry(episode(1), EngineViewport(100, 150))
        (1..30).forEach { number ->
            geometry.addManifest(
                manifest(number, (number - 1).takeIf { it >= 1 }, (number + 1).takeIf { it <= 30 }), true,
            )
        }
        assertEquals(30, geometry.manifests.size)
        geometry.retainWindow(episode(20), episode(1))
        // Anchor 20, a deep backward chain (12 links: 8..19), two forward links (21, 22), and the
        // session's target 1.
        val expected = setOf(episode(1)) + (8..22).map(::episode).toSet()
        assertEquals(expected, geometry.manifests.keys.toSet())
        assertEquals(geometry.manifests.keys.toSet(), geometry.navigationKnown.keys.toSet())
        assertTrue(geometry.actualDimensions.keys.all { it.episodeId in geometry.manifests.keys })
    }

    @Test
    fun prunesNothingWhileInsideTheWindow() {
        val geometry = DocumentGeometry(episode(1), EngineViewport(100, 150))
        (1..4).forEach { number ->
            geometry.addManifest(
                manifest(number, (number - 1).takeIf { it >= 1 }, (number + 1).takeIf { it <= 4 }), true,
            )
        }
        geometry.retainWindow(episode(2), episode(1))
        assertEquals(4, geometry.manifests.size)
    }
}
