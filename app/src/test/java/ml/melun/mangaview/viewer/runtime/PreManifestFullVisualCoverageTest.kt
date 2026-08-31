package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.LoadingFramePlanner
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertTrue
import org.junit.Test

class PreManifestFullVisualCoverageTest {
    @Test
    fun everyViewportShapeRemainsCoveredAcrossColdEntryScrolling() {
        val dimensions = listOf(
            1_080 to 2_138,
            1_440 to 3_120,
            2_560 to 1_440,
            800 to 1_281,
        )
        dimensions.forEach { (width, height) ->
            val viewport = Viewport(FixedPx.fromPixels(width), FixedPx.fromPixels(height))
            val planner = LoadingFramePlanner(screenfulCount = 8)
            val geometry = planner.geometry(viewport)
            val offsets = listOf(
                FixedPx.ZERO,
                FixedPx(viewport.height.units / 17L),
                FixedPx(viewport.height.units / 3L),
                FixedPx(viewport.height.units - 1L),
                FixedPx(viewport.height.units * 2L + 541L),
            )
            offsets.forEach { offset ->
                val packed = requireNotNull(packer.pack(planner.plan(geometry, offset)))
                assertTrue(
                    "viewport=$viewport offset=$offset ranges=" +
                        packed.localVisualRanges.contentToString(),
                    packed.hasCompleteVisualCoverage(),
                )
            }
        }
    }

    private val packer = NativeFramePacker()
}
