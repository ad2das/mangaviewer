package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStripPresentationProofTest {
    @Test
    fun cleanMergedCoverageDerivesExactEpisodeEndWithoutLateActorFlag() {
        assertTrue(ntkExactEpisodeEndFromPresentation(
            explicitEnd = false,
            contentHeightPx = 1000L,
            intervals = listOf(
                NtkPresentedContentInterval(400L, 1000L),
                NtkPresentedContentInterval(0L, 401L)
            )
        ))
    }

    @Test
    fun gapBeforeEpisodeEndCannotDeriveExactEnd() {
        assertFalse(ntkExactEpisodeEndFromPresentation(
            explicitEnd = false,
            contentHeightPx = 1000L,
            intervals = listOf(
                NtkPresentedContentInterval(0L, 499L),
                NtkPresentedContentInterval(500L, 1000L)
            )
        ))
    }

    @Test
    fun typedCompositionPreservesPipelineResourceAndSurfacePresentationAuthorities() {
        val digest = NtkStripDigests.sha256Tokens("proof-composition")
        val pipeline = proof(digest).copy(
            metadataPages = 1,
            sourceOriginalProofPages = 1,
            peakCpuChargedBytes = 64L,
            peakCpuDecodedBytes = 32L,
            cpuTransientHardCapBytes = 128L,
            exactEpisodeEnd = true,
            detachedRetireCount = 2,
            resourceCycleAdmissionCount = 7,
            resourceCycleReleaseCount = 3,
            resourceCycleLedgerDigest = digest,
            resourceCycleLedgerValid = true
        )
        val surface = proof(digest).copy(
            drawableProofPages = 1,
            presentedContentIntervals = listOf(NtkPresentedContentInterval(0L, 100L)),
            presentedPages = setOf(0),
            traversalCommittedPages = 1,
            exactEpisodeEnd = true
        )

        val composed = pipeline.composeWithSurfacePresentation(surface)
        assertNotNull(composed)
        assertEquals(7, composed!!.resourceCycleAdmissionCount)
        assertEquals(3, composed.resourceCycleReleaseCount)
        assertEquals(2, composed.detachedRetireCount)
        assertEquals(digest, composed.resourceCycleLedgerDigest)
        assertTrue(composed.resourceCycleLedgerValid)
        assertEquals(surface.presentedContentIntervals, composed.presentedContentIntervals)
        assertEquals(surface.presentedPages, composed.presentedPages)
        assertTrue(composed.exactEpisodeEnd)

        assertNull(pipeline.composeWithSurfacePresentation(
            surface.copy(contentHeightPx = 101L)
        ))
    }

    private fun proof(digest: String) = NtkEpisodeProofSnapshot(
        manifestRevision = 1L,
        manifestDigest = digest,
        geometryDigest = digest,
        geometryTileCount = 1,
        contentHeightPx = 100L,
        manifestPages = 1,
        metadataPages = 0,
        sourceOriginalProofPages = 0,
        drawableProofPages = 0,
        everDecodedTiles = emptySet(),
        everPublishedTiles = emptySet(),
        presentedContentIntervals = emptyList(),
        presentedPages = emptySet(),
        traversalCommittedPages = 0,
        traversalMissingPages = setOf(0),
        viewportDefectFrames = 0L,
        runwayDefectFrames = 0L,
        preSubmitViewportGap = 0L,
        currentAccounting = NtkResidencyAccounting(),
        peakCpuChargedBytes = 0L,
        peakCpuDecodedBytes = 0L,
        cpuTransientHardCapBytes = Long.MAX_VALUE,
        gpuSceneCapacityProof = null,
        exactEpisodeEnd = false
    )
}
