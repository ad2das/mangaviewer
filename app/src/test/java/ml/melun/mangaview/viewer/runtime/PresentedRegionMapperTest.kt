package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.activity.PresentedRegionRecorder
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.SceneQuad
import ml.melun.mangaview.viewer.session.SceneSnapshot
import ml.melun.mangaview.viewer.session.VisualKey
import org.junit.Assert.*
import org.junit.Test

class PresentedRegionMapperTest {
    private val page = PageId.at(EpisodeId(SeriesId(SourceId("fixture"), "rows"), "episode"), 0)

    @Test fun clipsToDisplayedSourceRowsAndKeepsBufferIdentity() {
        val region = PresentedRegionMapper.from(9, presentation(offset = 50)).single()
        assertEquals(2_500, region.sourceTopRow)
        assertEquals(3_500, region.sourceBottomRowExclusive)
        assertEquals(7L, region.bufferFrameId)
        assertEquals(9L, region.rendererIdentity)
        assertTrue(region.imageIdentityVerified)
    }

    @Test fun fallbackAndMissingUploadIdentityNeverBecomeVerifiedDisplay() {
        val input = presentation(50).copy(timestampKind = PresentationTimestampKind.COMPOSITION_LATCH,
            verifiedTextureIdentities = emptyMap())
        val region = PresentedRegionMapper.from(1, input).single()
        assertEquals(PresentationTimestampKind.COMPOSITION_LATCH, region.timestampKind)
        assertFalse(region.imageIdentityVerified)
    }

    @Test fun offscreenAndLoadingQuadsEarnNoRows() {
        val input = presentation(250)
        assertTrue(PresentedRegionMapper.from(1, input).isEmpty())
        val loading = presentation(0).let { it.copy(scene = it.scene.copy(
            quads = it.scene.quads.map { quad -> quad.copy(visualKey = null) })) }
        assertTrue(PresentedRegionMapper.from(1, loading).isEmpty())
    }

    @Test fun missingEglTimestampRetainsGeometryWithoutInventingDisplayTime() {
        val input = presentation(0).copy(presentedAtNanos = 0,
            timestampKind = PresentationTimestampKind.UNAVAILABLE)
        val region = PresentedRegionMapper.from(1, input).single()
        assertEquals(0L, region.presentedNanos)
        assertEquals(PresentationTimestampKind.UNAVAILABLE, region.timestampKind)
        assertEquals(input.submittedAtNanos, region.submittedAtNanos)
    }

    @Test fun recorderReportsOverflowAndIndependentCursorBatches() {
        val recorder = PresentedRegionRecorder(2)
        val region = PresentedRegionMapper.from(1, presentation(0)).single()
        recorder.record(listOf(region))
        val first = recorder.since(0)
        assertFalse(first.dropped)
        recorder.record(listOf(region.copy(token = 2), region.copy(token = 3)))
        assertTrue(recorder.since(0).dropped)
        assertEquals(listOf(2L, 3L), recorder.since(first.nextSequence).regions.map { it.token })
        assertTrue(recorder.since(3).regions.isEmpty())
    }

    @Test fun uploadProvenanceRejectsAnotherPageAndAnotherSourceRange() {
        val quad = presentation(0).scene.quads.single()
        val identity = UploadedTextureIdentity(page, 2_000, 4_000, 6_000)
        assertTrue(identity.matches(quad))
        assertFalse(identity.copy(top = 1_999).matches(quad))
        assertFalse(identity.copy(pageId = PageId.at(page.episodeId, 1)).matches(quad))
    }

    @Test fun sharedTextureKeyCannotProveAnotherPagesIdentity() {
        val input = presentation(0)
        val correct = input.scene.quads.single()
        val wrong = correct.copy(pageId = PageId.at(page.episodeId, 1))
        val regions = PresentedRegionMapper.from(1,
            input.copy(scene = input.scene.copy(quads = listOf(correct, wrong))))
        assertEquals(2, regions.size)
        assertTrue(regions[0].imageIdentityVerified)
        assertFalse(regions[1].imageIdentityVerified)
    }

    private fun presentation(offset: Int): OwnedPresentation {
        val scene = SceneSnapshot(1, 1, 1, 1, 1, 0, FixedPx.ZERO, FixedPx.fromPixels(offset),
            FixedPx.fromPixels(600), listOf(SceneQuad(page, FixedPx.ZERO, FixedPx.fromPixels(200),
                2_000, 4_000, 6_000, VisualKey(3))))
        val metadata = OwnedFrameMetadata(ReadingPosition(page, 0), 0, FixedPx.fromPixels(100).units,
            true, true, page)
        return OwnedPresentation(1, 1_000, 500, 100, -1, 0, scene, metadata,
            PresentationTimestampKind.DISPLAY_PRESENT, 7,
            mapOf(3L to UploadedTextureIdentity(page, 2_000, 4_000, 6_000)))
    }
}
