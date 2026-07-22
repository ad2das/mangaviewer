package ml.melun.mangaview.reader

import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Manga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkInlineStageAdmissionTest {
    private val path = "/manhwa/33727/1692251"

    private fun episode(): Manga = Manga(1692251, "fixed31", "", MTitle.base_comic).apply {
        ntkEpisodePath = path
    }

    @Test
    fun coldClickDerivesStrictIdentityWithoutPreparedStoreKey() {
        val strictKey = NtkInlineReaderController.strictPreparedKey(path)
        assertTrue(strictKey.isNotEmpty())
        assertEquals(
            strictKey,
            NtkInlineReaderController.resolvePreparedKey(path, null)
        )
        assertEquals(
            strictKey,
            NtkInlineReaderController.resolvePreparedKey(path, "")
        )
        assertEquals(
            "explicit-key",
            NtkInlineReaderController.resolvePreparedKey(path, "explicit-key")
        )
    }

    @Test
    fun discoveringEmptyManifestIsNotAnIdentityMismatch() {
        assertEquals(
            NtkInlineReaderController.StageResult.PREPARED_NOT_READY,
            NtkInlineReaderController.preparedImagesStageRejection(episode(), path, null)
        )
        assertEquals(
            NtkInlineReaderController.StageResult.PREPARED_NOT_READY,
            NtkInlineReaderController.preparedImagesStageRejection(episode(), path, emptyList())
        )
    }

    @Test
    fun presentManifestStillEnforcesExactEpisodeIdentity() {
        assertNull(
            NtkInlineReaderController.preparedImagesStageRejection(
                episode(),
                path,
                listOf("https://img.example/manhwa/33727/1692251/p001.webp")
            )
        )
        assertEquals(
            NtkInlineReaderController.StageResult.PREPARED_IDENTITY_MISMATCH,
            NtkInlineReaderController.preparedImagesStageRejection(
                episode(),
                path,
                listOf("https://img.example/manhwa/33727/999999/p001.webp")
            )
        )
    }

    @Test
    fun onlyNormalStagingWaitStatesAreTransient() {
        assertTrue(NtkInlineReaderController.isTransientStageRejection(
            NtkInlineReaderController.StageResult.PREPARED_NOT_READY))
        assertTrue(NtkInlineReaderController.isTransientStageRejection(
            NtkInlineReaderController.StageResult.HOST_NOT_ATTACHED))
        assertTrue(NtkInlineReaderController.isTransientStageRejection(
            NtkInlineReaderController.StageResult.HOST_NOT_LAID_OUT))
        assertFalse(NtkInlineReaderController.isTransientStageRejection(
            NtkInlineReaderController.StageResult.PREPARED_IDENTITY_MISMATCH))
        assertFalse(NtkInlineReaderController.isTransientStageRejection(
            NtkInlineReaderController.StageResult.SESSION_START_FAILED))
    }
}
