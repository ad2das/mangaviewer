package ml.melun.mangaview.data.cache

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.OpenedPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompleteEpisodeSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = EpisodeId(SeriesId(SourceId("provider"), "series"), "episode")
    private val publisher = AtomicFilePublisher { source, destination ->
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    private val pinner = SnapshotFilePinner { source, destination ->
        Files.createLink(destination.toPath(), source.toPath())
        PinnedSnapshotBody(destination, java.io.Closeable {})
    }

    @Test fun exactManifestOrderAndMetadataSurviveStoreRecreation() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        val reopened = store(fixture.root, fixture.cache)

        requireNotNull(reopened.open(id)).use { lease ->
            assertEquals(fixture.snapshot, lease.snapshot)
            assertEquals(listOf("z-last", "a-first"), lease.snapshot.manifest.pages.map { it.id.remoteKey })
            fixture.pages.forEach { expected ->
                assertArrayEquals(expected.file.readBytes(), lease.page(expected.pageId).file.readBytes())
            }
        }
        assertTrue(fixture.root.listFiles().orEmpty().none { it.name.startsWith(".lease-") })
    }

    @Test fun missingBodyRejectsTheWholeSnapshotAndReleasesPartialLinks() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        fixture.cache.remove(fixture.pages.last().pageId)

        assertNull(fixture.store.open(id))
        assertTrue(fixture.root.listFiles().orEmpty().none { it.name.startsWith(".lease-") })
    }

    @Test fun changedCacheShaCannotBeMixedWithPreviouslySavedPositionalPages() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        fixture.cache.write(fixture.pages.first().pageId, opened(bytes(3)))

        assertNull(fixture.store.open(id))
    }

    @Test fun sameLengthBodyCorruptionIsDetectedEvenWhenIndexMetadataIsUnchanged() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        val file = fixture.pages.first().file
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })

        assertNull(fixture.store.open(id))
    }

    @Test fun imageHeaderMustMatchThePersistedDimensions() = runTest {
        val fixture = fixture()
        val wrongDimensions = PageDimensions(99, 88)
        val cached = fixture.pages.associate { it.pageId to it.copy(dimensions = wrongDimensions) }
        val metadataOnlyCache = object : RawPageCache by fixture.cache {
            override suspend fun find(pageId: PageId): CachedPage? = cached[pageId]
        }
        val store = store(fixture.root, metadataOnlyCache)
        val manifest = fixture.snapshot.manifest.copy(pages = fixture.snapshot.manifest.pages.map {
            it.copy(dimensions = wrongDimensions)
        })
        store.save(CompleteEpisodeSnapshot(manifest, fixture.snapshot.pages.map { it.copy(dimensions = wrongDimensions) }))

        assertNull(store.open(id))
    }

    @Test fun leasedBodiesSurviveOrdinaryCacheEvictionAndAtomicReplacement() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        val lease = requireNotNull(fixture.store.open(id))
        val original = lease.page(fixture.pages.first().pageId).file.readBytes()
        fixture.cache.remove(fixture.pages.first().pageId)
        fixture.cache.write(fixture.pages.first().pageId, opened(bytes(9)))

        assertArrayEquals(original, lease.page(fixture.pages.first().pageId).file.readBytes())
        // Opening a second store must not delete a currently live lease as crash residue.
        assertNull(store(fixture.root, fixture.cache).open(id))
        assertArrayEquals(original, lease.page(fixture.pages.first().pageId).file.readBytes())
        lease.close()
        lease.close()
        assertTrue(fixture.root.listFiles().orEmpty().none { it.name.startsWith(".lease-") })
    }

    @Test fun missingPinnedBodyIsAnErrorRatherThanAnInvitationToRefetch() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        requireNotNull(fixture.store.open(id)).use { lease ->
            assertTrue(lease.page(fixture.pages.first().pageId).file.delete())
            assertThrows(IllegalStateException::class.java) { lease.page(fixture.pages.first().pageId) }
        }
    }

    @Test fun snapshotIntegrityAndPageOwnershipAreValidatedBeforeOpeningBodies() = runTest {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            CompleteEpisodeSnapshot(fixture.snapshot.manifest, fixture.snapshot.pages.reversed())
        }
        fixture.store.save(fixture.snapshot)
        val metadata = fixture.root.listFiles().orEmpty().single { it.extension == "snapshot" }
        metadata.writeBytes(metadata.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertNull(fixture.store.open(id))
    }

    @Test fun linkFailureFallsBackWithoutLeavingAnyTemporaryBodies() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        val failed = CompleteEpisodeSnapshotStore(fixture.root, fixture.cache, Dispatchers.IO,
            publisher, SnapshotFilePinner { _, _ -> throw java.io.IOException("pin unavailable") })

        assertNull(failed.open(id))
        assertTrue(fixture.root.listFiles().orEmpty().none { it.name.startsWith(".lease-") })
    }

    @Test fun cancellationDuringValidationReleasesAlreadyLinkedBodies() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        val blocked = CompletableDeferred<Unit>()
        val cache = object : RawPageCache by fixture.cache {
            override suspend fun find(pageId: PageId): CachedPage? {
                if (pageId == fixture.pages.last().pageId) {
                    blocked.complete(Unit)
                    awaitCancellation()
                }
                return fixture.cache.find(pageId)
            }
        }
        val pending = async { store(fixture.root, cache).open(id) }
        blocked.await()
        pending.cancelAndJoin()

        assertTrue(fixture.root.listFiles().orEmpty().none { it.name.startsWith(".lease-") })
        assertTrue(fixture.pages.all { it.file.isFile })
    }

    @Test fun cleanupFailureIsVisibleAndDoesNotMarkTheLeaseSuccessfullyClosed() = runTest {
        val fixture = fixture()
        fixture.store.save(fixture.snapshot)
        val lease = requireNotNull(fixture.store.open(id))
        val unexpected = File(lease.page(fixture.pages.first().pageId).file.parentFile, "unexpected")
        assertTrue(unexpected.mkdir())
        assertThrows(SnapshotLeaseCleanupException::class.java) { lease.close() }
        assertTrue(unexpected.delete())
        lease.close()
        assertTrue(fixture.root.listFiles().orEmpty().none { it.name.startsWith(".lease-") })
    }

    @Test fun unavailableOptionalSnapshotStorageFallsBackToFreshResolution() = runTest {
        val fixture = fixture()
        assertNull(store(temporary.newFile(), fixture.cache).open(id))
    }

    private suspend fun fixture(): Fixture {
        val cache = RawPageStore(temporary.newFolder(), InMemoryRawPageDao(), Dispatchers.IO, publisher)
        val pages = listOf("z-last", "a-first").mapIndexed { index, key ->
            cache.write(PageId(id, key), opened(bytes(index)))
        }
        val manifest = EpisodeManifest(id, "Saved title", pages.mapIndexed { index, page ->
            PageSpec(page.pageId, index, page.dimensions, page.byteCount, page.sha256)
        }, EpisodeId(id.seriesId, "previous"), EpisodeId(id.seriesId, "next"), "provider-revision")
        val snapshot = CompleteEpisodeSnapshot(manifest, pages.map {
            SnapshotPageBinding(it.pageId, it.byteCount, it.sha256, it.dimensions)
        })
        val root = temporary.newFolder()
        return Fixture(cache, root, store(root, cache), snapshot, pages)
    }

    private fun store(root: File, cache: RawPageCache) =
        CompleteEpisodeSnapshotStore(root, cache, Dispatchers.IO, publisher, pinner)

    private fun bytes(marker: Int) = ImageHeaderProbeTest.png(20, 30) + byteArrayOf(marker.toByte())

    private fun opened(bytes: ByteArray) = OpenedPage(ByteArrayPageStream(bytes), bytes.size.toLong(),
        "image/png", null, null)

    private data class Fixture(val cache: RawPageStore, val root: File, val store: CompleteEpisodeSnapshotStore,
        val snapshot: CompleteEpisodeSnapshot, val pages: List<CachedPage>)
}
