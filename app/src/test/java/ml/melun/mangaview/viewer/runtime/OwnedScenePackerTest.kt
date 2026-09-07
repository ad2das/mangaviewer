package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.session.SceneQuad
import ml.melun.mangaview.viewer.session.SceneSnapshot
import ml.melun.mangaview.viewer.session.VisualKey
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OwnedScenePackerTest {
    @Test
    fun adjacentWindowsCannotReuseDifferentDrawLists() {
        val first = OwnedScenePacker.pack(scene(0L, 1L))
        val second = OwnedScenePacker.pack(scene(1L, 2L))
        val identity = OwnedSceneIdentity()
        val installed = identity.prepare(first).also(identity::acknowledge)
        val replacement = identity.prepare(second)
        assertNotEquals(installed.id, replacement.id)
        assertNotNull(replacement.replacement)
    }

    @Test
    fun scrollOnlyChangesReuseTheInstalledDrawList() {
        val first = OwnedScenePacker.pack(scene(0L, 1L))
        val identity = OwnedSceneIdentity()
        val installed = identity.prepare(first).also(identity::acknowledge)
        val moved = identity.prepare(first.copy(viewportTopPx = 200))
        assertEquals(installed.id, moved.id)
        assertNull(moved.replacement)
    }

    @Test
    fun equalArrayHashCodesDoNotMeanEqualDrawLists() {
        val identity = OwnedSceneIdentity()
        val first = PackedOwnedScene(100, 0, 1, intArrayOf(1, 32, 0, 0, 0, 0, 0))
        val second = first.copy(entries = intArrayOf(2, 1, 0, 0, 0, 0, 0))
        assertEquals(first.entries.contentHashCode(), second.entries.contentHashCode())
        val installed = identity.prepare(first).also(identity::acknowledge)
        val replacement = identity.prepare(second)
        assertNotEquals(installed.id, replacement.id)
        assertNotNull(replacement.replacement)
    }

    @Test
    fun failedSubmissionMustResendTheDrawList() {
        val identity = OwnedSceneIdentity()
        val packed = OwnedScenePacker.pack(scene(0L, 1L))
        val failed = identity.prepare(packed)
        val retry = identity.prepare(packed)
        assertNotNull(retry.replacement)
        assertNotEquals(failed.id, retry.id)
    }

    @Test
    fun surfaceReattachmentInstallsEvenAnUnchangedDrawList() {
        val identity = OwnedSceneIdentity()
        val packed = OwnedScenePacker.pack(scene(0L, 1L))
        val installed = identity.prepare(packed).also(identity::acknowledge)
        identity.invalidate()
        val restored = identity.prepare(packed)
        assertNotEquals(installed.id, restored.id)
        assertNotNull(restored.replacement)
    }

    @Test
    fun emptyDrawListIsAnExplicitReplacementBeforeItCanBeReused() {
        val identity = OwnedSceneIdentity()
        identity.prepare(OwnedScenePacker.pack(scene(0L, 1L))).also(identity::acknowledge)
        val empty = OwnedScenePacker.pack(scene(0L, 1L).copy(quads = emptyList()))
        val removal = identity.prepare(empty)
        assertNotNull(removal.replacement)
        assertEquals(0, removal.replacement!!.size)
        identity.acknowledge(removal)
        assertNull(identity.prepare(empty).replacement)
    }

    private fun scene(window: Long, texture: Long): SceneSnapshot {
        val episode = EpisodeId(SeriesId(SourceId("fixture"), "work"), "episode")
        val quad = SceneQuad(
            PageId.at(episode, 0), FixedPx.ZERO, FixedPx(1000L),
            0, 100, 100, VisualKey(texture),
        )
        return SceneSnapshot(
            1L, 1L, 1L, 1L, 1L, window, FixedPx.ZERO, FixedPx.ZERO,
            FixedPx(1000L), listOf(quad),
        )
    }
}
