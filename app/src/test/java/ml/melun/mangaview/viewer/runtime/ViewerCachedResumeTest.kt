package ml.melun.mangaview.viewer.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.AtomicFilePublisher
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.cache.CompleteEpisodeSnapshotStore
import ml.melun.mangaview.data.cache.RawPageCache
import ml.melun.mangaview.data.cache.PageTransferPreview
import ml.melun.mangaview.data.cache.SnapshotFilePinner
import ml.melun.mangaview.data.cache.PinnedSnapshotBody
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ViewerCachedResumeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val episode = EpisodeId(SeriesId(SourceId("source"), "series"), "episode")
    private val manifest = EpisodeManifest(episode, "Saved", listOf("z", "a").mapIndexed { ordinal, key ->
        PageSpec(PageId(episode, key), ordinal)
    })

    @Test fun onlyCompleteNormalUseEvidenceIsPersistedInManifestOrder() = runTest {
        val fixture = fixture()
        val resume = ViewerCachedResume(fixture.store, fixture.online)
        resume.manifestResolved(manifest)
        assertNull(resume.rawVerified(fixture.pages[1].ref()))
        assertNull(resume.rawVerified(fixture.pages[1].ref()))
        val complete = requireNotNull(resume.rawVerified(fixture.pages[0].ref()))
        assertEquals(manifest.pages.map { it.id }, complete.pages.map { it.pageId })
        fixture.store.save(complete)

        assertEquals(manifest, resume.open(episode))
        assertEquals(fixture.pages[0].sha256, resume.find(manifest.pages[0].id)?.fingerprint)
        assertEquals(0, fixture.online.calls)
        resume.close()
    }

    @Test fun missingPinnedBodyNeverFallsBackToFreshSourceData() = runTest {
        val fixture = fixture()
        val resume = ViewerCachedResume(fixture.store, fixture.online)
        resume.manifestResolved(manifest)
        resume.rawVerified(fixture.pages[0].ref())
        fixture.store.save(requireNotNull(resume.rawVerified(fixture.pages[1].ref())))
        requireNotNull(resume.open(episode))
        val id = manifest.pages[0].id
        File(requireNotNull(resume.find(id)).path).delete()

        assertThrows(IllegalStateException::class.java) { runBlocking { resume.find(id) } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { resume.fetch(id, PageFetchPriority.VISIBLE) {} }
        }
        assertEquals(0, fixture.online.calls)
        resume.close()
    }

    private fun fixture(): Fixture {
        val bytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWZkAAAAASUVORK5CYII=",
        )
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val pages = manifest.pages.map { spec ->
            val file = temporary.newFile().also { it.writeBytes(bytes) }
            CachedPage(spec.id, file, bytes.size.toLong(), sha, "image/png", PageDimensions(1, 1))
        }
        val cache = object : RawPageCache {
            override suspend fun find(pageId: PageId): CachedPage? = pages.firstOrNull { it.pageId == pageId }
            override suspend fun write(pageId: PageId, openedPage: OpenedPage,
                onPreview: ((PageTransferPreview) -> Unit)?): CachedPage = error("not a transfer fixture")
            override suspend fun remove(pageId: PageId) = Unit
        }
        val store = CompleteEpisodeSnapshotStore(temporary.newFolder(), cache, Dispatchers.IO,
            AtomicFilePublisher { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) },
            SnapshotFilePinner { from, to ->
                Files.createLink(to.toPath(), from.toPath())
                PinnedSnapshotBody(to, java.io.Closeable {})
            })
        return Fixture(store, pages, CountingRawPort())
    }

    private fun CachedPage.ref() = EncodedPageRef(pageId, file.absolutePath, byteCount, sha256, dimensions)

    private class CountingRawPort : RawPagePort {
        var calls = 0
        override suspend fun find(pageId: PageId): EncodedPageRef? { calls++; return null }
        override suspend fun fetch(pageId: PageId, priority: PageFetchPriority,
            responseStarted: () -> Unit): EncodedPageRef { calls++; error("unexpected source fetch") }
    }

    private data class Fixture(val store: CompleteEpisodeSnapshotStore, val pages: List<CachedPage>,
        val online: CountingRawPort)
}
