package ml.melun.mangaview.data.cache

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.*
import ml.melun.mangaview.source.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpisodeCatalogSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = SeriesId(SourceId("ntk"), "/webtoon/123")
    private fun snapshot(series: SeriesId = id) = EpisodeCatalogSnapshot(series, listOf(
        SourceEpisode(EpisodeId(series, "special"), "외전-1화", 1_700_000_000_000L, 30, 1.5),
        SourceEpisode(EpisodeId(series, "first"), "1화"),
    ), SourceSeriesDetails(SeriesStatus.ONGOING, "소개", "작가"), 500L)
    private fun store(root: File) = EpisodeCatalogSnapshotStore(root, Dispatchers.IO,
        AtomicFilePublisher { stage, destination -> java.nio.file.Files.move(stage.toPath(), destination.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING) })

    @Test fun recreationPreservesProviderOrderIdsAndEveryMetadataField() = runTest {
        val root = temporary.newFolder()
        store(root).save(snapshot())
        assertEquals(snapshot(), store(root).load(id))
        assertTrue(root.listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.bin")) })
    }

    @Test fun providerIdentityAndOpaqueKeysCannotCollideOrEscapeTheDirectory() = runTest {
        val root = temporary.newFolder()
        val other = SeriesId(SourceId("goodtoon"), id.remoteKey)
        val traversal = SeriesId(SourceId("ntk"), "../../outside")
        val cache = store(root)
        listOf(id, other, traversal).forEach { cache.save(snapshot(it)) }
        listOf(id, other, traversal).forEach { assertEquals(snapshot(it), cache.load(it)) }
        assertEquals(3, root.listFiles()!!.size)
    }

    @Test fun failedAtomicReplacementLeavesThePreviouslyCompleteSnapshotReadable() = runTest {
        val root = temporary.newFolder()
        val original = snapshot()
        store(root).save(original)
        val failing = EpisodeCatalogSnapshotStore(root, Dispatchers.IO, AtomicFilePublisher { _, _ -> error("disk error") })
        try { failing.save(original.copy(episodes = emptyList())); fail("Expected publication failure") }
        catch (_: IllegalStateException) { }
        assertEquals(original, store(root).load(id))
        assertFalse(root.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun malformedAndOversizedFilesAreCacheMisses() = runTest {
        val root = temporary.newFolder()
        val cache = store(root)
        cache.save(snapshot())
        root.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.load(id))
        root.listFiles()!!.single().writeBytes(ByteArray(4 * 1024 * 1024 + 1))
        assertNull(cache.load(id))
    }

    @Test fun cacheEvictsOldFilesAndRejectsForeignOrDuplicateEpisodes() = runTest {
        val root = temporary.newFolder()
        val cache = store(root)
        repeat(35) { cache.save(snapshot(SeriesId(SourceId("ntk"), "series-$it"))) }
        assertEquals(32, root.listFiles()!!.size)
        assertNotNull(cache.load(SeriesId(SourceId("ntk"), "series-34")))
        try { cache.save(snapshot().copy(episodes = snapshot().episodes + snapshot().episodes)); fail("duplicate IDs") }
        catch (_: IllegalArgumentException) { }
        try { cache.save(snapshot().copy(seriesId = SeriesId(SourceId("wfwf"), "other"))); fail("foreign IDs") }
        catch (_: IllegalArgumentException) { }
    }
}
