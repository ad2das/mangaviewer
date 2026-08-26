package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPreparedOriginalProofCropTest {
    private val asset = "https://example.invalid/p007.jpg"

    @Test
    fun canonicalAutoSplitHalfRetainsFullOriginalAuthority() {
        val proof = ReaderPreparedStore.PreparedOriginalProof(
            canonicalAsset = asset,
            variant = ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
            originalWidth = 2400,
            originalHeight = 1600,
            inSampleSize = 1,
            sourceCropLeft = 1200,
            sourceCropTop = 0,
            sourceCropWidth = 1200,
            sourceCropHeight = 1600,
        )

        assertTrue(ReaderPreparedStore.isCanonicalOriginalProof(proof, asset, 1200, 1600))
        assertFalse(ReaderPreparedStore.isCanonicalOriginalProof(proof, asset, 2400, 1600))
    }

    @Test
    fun cropOutsideOriginalIsRejected() {
        val proof = ReaderPreparedStore.PreparedOriginalProof(
            canonicalAsset = asset,
            variant = ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
            originalWidth = 2400,
            originalHeight = 1600,
            inSampleSize = 1,
            sourceCropLeft = 1201,
            sourceCropWidth = 1200,
            sourceCropHeight = 1600,
        )

        assertFalse(ReaderPreparedStore.isCanonicalOriginalProof(proof, asset, 1200, 1600))
    }
}
