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
        (1..10).forEach { number ->
            geometry.addManifest(
                manifest(number, (number - 1).takeIf { it >= 1 }, (number + 1).takeIf { it <= 10 }), true,
            )
        }
        assertEquals(10, geometry.manifests.size)
        geometry.retainWindow(episode(6), episode(1))
        // Anchor 6, two navigation links each way (4, 5, 7, 8), and the session's target 1.
        assertEquals(
            setOf(episode(1), episode(4), episode(5), episode(6), episode(7), episode(8)),
            geometry.manifests.keys.toSet(),
        )
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
