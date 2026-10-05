package ml.melun.mangaview.data.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.data.db.EnginePageEntity
import ml.melun.mangaview.data.db.EnginePublicationEntity
import ml.melun.mangaview.engine.api.EnginePositionPort
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineRawStorageTest {
    @Test fun exactHeaderGeometryArrivesBeforeTheRemainingBodyAndDoesNotPublishBytes() = runTest {
        val root = temporary.newFolder()
        val index = MemoryIndex()
        val store = store(root, index)
        val tail = CompletableDeferred<Unit>()
        var cursor = 0
        var closes = 0
        val stream = object : PageByteStream {
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
                if (cursor > 0) tail.await()
                if (cursor == bytes.size) return -1
                val count = minOf(byteCount, if (cursor == 0) 33 else bytes.size - cursor)
                bytes.copyInto(destination, offset, cursor, cursor + count)
                cursor += count
                return count
            }
            override fun close() { closes++ }
        }
        val geometries = mutableListOf<ml.melun.mangaview.core.PageDimensions>()
        val transfer = async { store.prepareWithGeometry(id, "v1",
            OpenedPage(stream, bytes.size.toLong(), "image/png", null, null)) { geometries += it } }
        runCurrent()
        assertEquals(listOf(ml.melun.mangaview.core.PageDimensions(1, 1)), geometries)
        assertEquals(33, cursor)
        assertFalse(transfer.isCompleted)
        assertTrue(index.pageRows.isEmpty())
        assertNull(store.find(id, "v1"))
        tail.complete(Unit)
        val prepared = transfer.await()
        assertArrayEquals(bytes, prepared.page.file.readBytes())
        assertEquals(1, geometries.size)
        assertEquals(1, closes)
        store.publish(prepared).close()
        assertEquals(0, store.ownership().fileLeases)
    }

    @get:Rule val temporary = TemporaryFolder()
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "10001"), "1"), 0)
    private val bytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=",
    )

    @Test fun preparedBytesAreInvisibleAndEveryLeaseProtectsEviction() = runTest {
        val root = temporary.newFolder()
        val index = MemoryIndex()
        val store = store(root, index)
        val stream = Body(bytes)
        val prepared = store.prepare(id, "v1", stream.opened())
        assertEquals(1, stream.closes)
        assertNull(store.find(id, "v1"))
        val first = store.publish(prepared)
        val second = checkNotNull(store.find(id, "v1"))
        assertArrayEquals(bytes, second.page.file.readBytes())
        assertEquals(bytes.size.toLong(), store.trimTo(0))
        first.close()
        first.close()
        assertEquals(1, store.ownership().fileLeases)
        assertEquals(bytes.size.toLong(), store.trimTo(0))
        second.close()
        assertEquals(0L, store.trimTo(0))
        assertNull(store.find(id, "v1"))
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(File(root, "pages").listFiles()!!.isEmpty())
    }

    @Test fun crashAtEveryPublicationBoundaryPreservesPreviousRevision() = runTest {
        for (failedAt in EnginePublicationStep.entries) {
            val root = temporary.newFolder()
            val index = MemoryIndex()
            var armed = false
            val store = store(root, index) { if (armed && it == failedAt) throw IOException("crash") }
            val old = store.publish(store.prepare(id, "old", Body(bytes).opened()))
            old.close()
            armed = true
            val prepared = store.prepare(id, "new", Body(bytes).opened())
            expect<IOException> { store.publish(prepared) }
            // A fresh storage owner has no process-local handles; only files and index survive.
            val restarted = store(root, index)
            restarted.recover()
            restarted.recover()
            val previous = checkNotNull(restarted.find(id, "old"))
            assertArrayEquals(bytes, previous.page.file.readBytes())
            previous.close()
            val recovered = restarted.find(id, "new")
            if (failedAt == EnginePublicationStep.STAGED) assertNull(recovered)
            else {
                assertNotNull(recovered)
                assertArrayEquals(bytes, recovered!!.page.file.readBytes())
                recovered.close()
            }
            assertEquals(0, restarted.ownership().pendingPublications)
            assertTrue(File(root, "staging").listFiles()!!.isEmpty())
        }
    }

    @Test fun sameLengthCorruptionIsDetectedAfterAnEarlierSuccessfulRead() = runTest {
        val store = store(temporary.newFolder(), MemoryIndex())
        val lease = store.publish(store.prepare(id, "v1", Body(bytes).opened()))
        val file = lease.page.file
        lease.close()
        checkNotNull(store.find(id, "v1")).close()
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        file.writeBytes(corrupt)
        // The verified-fast-path compares the recorded size and mtime; make the changed content
        // visible to it without relying on filesystem timestamp resolution.
        file.setLastModified(file.lastModified() + 2_000L)
        assertNull(store.find(id, "v1"))
        val replacement = store.publish(store.prepare(id, "v1", Body(bytes).opened()))
        assertArrayEquals(bytes, replacement.page.file.readBytes())
        replacement.close()
    }

    @Test fun powerLossDamagedDestinationIsRejectedAndRepublishedByAFreshOwner() = runTest {
        for (damage in listOf("truncate", "zero", "delete")) {
            val root = temporary.newFolder()
            val index = MemoryIndex()
            val publisher = store(root, index)
            publisher.publish(publisher.prepare(id, "v1", Body(bytes).opened())).close()
            val committed = File(root, index.pageRows.getValue(PageCacheKey.of(id) to "v1").relativePath)
            when (damage) {
                "truncate" -> committed.writeBytes(bytes.copyOf(bytes.size / 2))
                "zero" -> committed.writeBytes(ByteArray(bytes.size))
                "delete" -> check(committed.delete()) { "cannot remove the committed body" }
            }
            // A fresh owner starts with an empty verification cache, exactly like the first process
            // after a power loss: its first find re-digests and rejects the damaged body.
            val restarted = store(root, index)
            assertNull("damaged $damage body must never be leased", restarted.find(id, "v1"))
            val republished = restarted.publish(restarted.prepare(id, "v1", Body(bytes).opened()))
            assertArrayEquals(bytes, republished.page.file.readBytes())
            republished.close()
            checkNotNull(restarted.find(id, "v1")).use {
                assertArrayEquals("republished $damage body must serve the original bytes", bytes, it.page.file.readBytes())
            }
        }
    }

    @Test fun corruptLeasedFileCannotBeOverwrittenUntilRelease() = runTest {
        val store = store(temporary.newFolder(), MemoryIndex())
        val lease = store.publish(store.prepare(id, "v1", Body(bytes).opened()))
        lease.page.file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 0 })
        val prepared = store.prepare(id, "v1", Body(bytes).opened())
        expect<EnginePageInUseException> { store.publish(prepared) }
        lease.close()
        val repaired = store.publish(prepared)
        assertArrayEquals(bytes, repaired.page.file.readBytes())
        repaired.close()
    }

    @Test fun revisionConflictLeavesCommittedBodyUnchanged() = runTest {
        val store = store(temporary.newFolder(), MemoryIndex())
        val lease = store.publish(store.prepare(id, "v1", Body(bytes).opened()))
        val conflicting = store.prepare(id, "v1", Body(bytes + byteArrayOf(42)).opened())
        expect<ImmutableRevisionConflictException> { store.publish(conflicting) }
        store.discard(conflicting)
        assertArrayEquals(bytes, lease.page.file.readBytes())
        assertEquals(0, store.ownership().preparedPages)
        lease.close()
    }

    @Test fun resizedStagingBytesCannotBePublishedWithTheOriginalDigest() = runTest {
        val index = MemoryIndex()
        val store = store(temporary.newFolder(), index)
        val prepared = store.prepare(id, "v1", Body(bytes).opened())
        // publication trusts the transfer's digest for the bytes it just wrote, so the cheap
        // remaining invariant is the size the prepared body records.
        prepared.page.file.appendBytes(byteArrayOf(0))
        expect<IllegalStateException> { store.publish(prepared) }
        assertTrue(index.pageRows.isEmpty())
        assertTrue(index.journalRows.isEmpty())
        store.discard(prepared)
    }

    @Test fun publicationRenamesWithoutAnyFileOrDirectorySync() = runTest {
        val events = mutableListOf<String>()
        val ops = object : EngineFilePublication {
            override fun syncFile(file: File) { events += "fsync:${file.parentFile!!.name}/${file.name}" }
            override fun rename(staging: File, destination: File) {
                events += "rename"
                Files.move(staging.toPath(), destination.toPath())
            }
            override fun syncDirectory(directory: File) { events += "dirsync:${directory.name}" }
        }
        val store = store(temporary.newFolder(), MemoryIndex(), ops)
        val prepared = store.prepare(id, "v1", Body(bytes).opened())
        events.clear()
        val lease = store.publish(prepared)
        lease.close()
        // Initialization syncs the storage root once during prepare(); the publish path itself
        // must touch no sync op: process-crash protection is the journal/rename/commit order.
        assertEquals(listOf("rename"), events)
        checkNotNull(store.find(id, "v1")).close()
    }

    @Test fun repeatedLookupsVerifyTheFileOnceAndReuseThePooledBuffer() = runTest {
        val root = temporary.newFolder()
        val index = MemoryIndex()
        val publisher = store(root, index)
        publisher.publish(publisher.prepare(id, "v1", Body(bytes).opened())).close()

        // The publishing owner seeded the stamp with the digest transfer() already took: its own
        // lookups must not re-read the body.
        assertEquals(0, publisher.pageFileStats().digestPasses)
        repeat(4) { checkNotNull(publisher.find(id, "v1")).close() }
        assertEquals(0, publisher.pageFileStats().digestPasses)

        // A fresh owner on the same root has an empty verification cache, as after a process
        // restart: its first lookup re-verifies once and the rest reuse the stamp and pooled buffer.
        val restarted = store(root, index)
        assertEquals(0, restarted.pageFileStats().digestPasses)
        repeat(4) { checkNotNull(restarted.find(id, "v1")).close() }
        assertEquals(1, restarted.pageFileStats().digestPasses)
        assertEquals(1, restarted.pageFileStats().bufferAllocations)
    }

    @Test fun findServesTheMirrorWithoutReadingTheIndex() = runTest {
        val index = MemoryIndex()
        val store = store(temporary.newFolder(), index)
        store.publish(store.prepare(id, "v1", Body(bytes).opened())).close()
        val unpublished = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "10001"), "2"), 7)
        index.pageReads = 0
        index.pageListReads = 0
        repeat(4) { checkNotNull(store.find(id, "v1")).close() }
        assertNull(store.find(unpublished, "v1"))
        assertEquals("find must serve the mirror without Room reads", 0, index.pageReads)
        assertEquals(0, index.pageListReads)
    }

    @Test fun pendingTouchesFlushInBatchesAndDriveTheTrimOrder() = runTest {
        val root = temporary.newFolder()
        val index = MemoryIndex()
        var now = 1_000L
        val store = store(root, index, nowMillis = { now })
        val first = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "10001"), "3"), 0)
        val second = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "10001"), "3"), 1)
        val third = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "10001"), "3"), 2)
        val fourth = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "10001"), "3"), 3)
        for (page in listOf(first, second, third)) {
            store.publish(store.prepare(page, "v1", Body(bytes).opened())).close()
            now += 1_000L
        }

        // A touch recorded by a lookup is flushed by the next batch commit, not by the find.
        now = 31_000L
        checkNotNull(store.find(first, "v1")).close()
        now = 32_000L
        store.publish(store.prepare(fourth, "v1", Body(bytes).opened())).close()
        assertEquals("the commit transaction flushes the pending touch", 31_000L,
            index.pageRows[PageCacheKey.of(first) to "v1"]!!.lastAccessEpochMillis)
        assertEquals(listOf(1), index.touchAllSizes)

        // A touch recorded after that commit is flushed at the start of trim, and the fresh value
        // (not the publication timestamp) decides which pages survive.
        now = 33_000L
        checkNotNull(store.find(second, "v1")).close()
        assertEquals(bytes.size.toLong() * 2, store.trimTo(bytes.size.toLong() * 2))
        assertEquals("trim flushes the second touch", 33_000L,
            index.pageRows[PageCacheKey.of(second) to "v1"]!!.lastAccessEpochMillis)
        assertEquals(listOf(1, 1), index.touchAllSizes)
        checkNotNull(store.find(second, "v1")).close()
        checkNotNull(store.find(fourth, "v1")).close()
        assertNull(store.find(first, "v1"))
        assertNull(store.find(third, "v1"))
    }

    @Test fun decodeFailureInvalidationEvictsTheUnpinnedPublication() = runTest {
        val index = MemoryIndex()
        val store = store(temporary.newFolder(), index)
        val lease = store.publish(store.prepare(id, "v1", Body(bytes).opened()))
        val page = lease.page
        lease.close()
        checkNotNull(store.find(id, "v1")).close()
        store.invalidate(page)
        assertNull(store.find(id, "v1"))
        assertTrue(index.pageRows.isEmpty())
        assertFalse(page.file.exists())
    }

    @Test fun cancellationDuringBodyReadClosesStreamAndRemovesStaging() = runTest {
        val root = temporary.newFolder()
        val store = store(root, MemoryIndex())
        val entered = CompletableDeferred<Unit>()
        val stream = Body(bytes, atEnd = { entered.complete(Unit); awaitCancellation() })
        val task = async { store.prepare(id, "v1", stream.opened()) }
        entered.await()
        task.cancel()
        task.join()
        assertEquals(1, stream.closes)
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun cancellationAtIoReturnDoesNotLeakPreparedOwnership() = runTest {
        val root = temporary.newFolder()
        val store = store(root, MemoryIndex())
        val stream = Body(bytes, atEnd = { currentCoroutineContext()[Job]!!.cancel() })
        val task = async { store.prepare(id, "v1", stream.opened()) }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, stream.closes)
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun cancelledPublicationDeliveryReleasesLeaseButKeepsDurablePage() = runTest {
        val index = MemoryIndex()
        lateinit var caller: Job
        val store = store(temporary.newFolder(), index) {
            if (it == EnginePublicationStep.COMMITTED) caller.cancel()
        }
        val prepared = store.prepare(id, "v1", Body(bytes).opened())
        caller = async { store.publish(prepared) }
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
        checkNotNull(store.find(id, "v1")).close()
    }

    @Test fun uncertainJournalWriteRemainsRecoverableAfterDiscard() = runTest {
        val index = MemoryIndex()
        val store = store(temporary.newFolder(), index)
        val prepared = store.prepare(id, "v1", Body(bytes).opened())
        index.afterStage = { throw IOException("write committed before failure") }
        expect<IOException> { store.publish(prepared) }
        store.discard(prepared)
        assertEquals(1, store.ownership().pendingPublications)
        store.recover()
        checkNotNull(store.find(id, "v1")).close()
        assertEquals(0, store.ownership().preparedPages)
    }

    @Test fun recoveryDoesNotDeleteLivePreparedBytes() = runTest {
        val store = store(temporary.newFolder(), MemoryIndex())
        val prepared = store.prepare(id, "v1", Body(bytes).opened())
        store.recover()
        assertTrue(prepared.page.file.isFile)
        val lease = store.publish(prepared)
        lease.close()
    }

    @Test fun foreignHandlesAndEscapingJournalPathsAreRejectedWithoutDeletion() = runTest {
        val root = temporary.newFolder()
        val index = MemoryIndex()
        val first = store(root, index)
        val other = store(temporary.newFolder(), MemoryIndex())
        val prepared = first.prepare(id, "v1", Body(bytes).opened())
        expect<IllegalArgumentException> { other.publish(prepared) }
        assertTrue(prepared.page.file.isFile)
        val outside = File(root.parentFile, "preserve.txt").also { it.writeText("keep") }
        val files = EnginePageFiles(root, LocalFileOps())
        val journal = prepared.page.entity(files.destination(prepared.page), 0)
            .journal(prepared.page.file.name.removeSuffix(".part"), prepared.page.file.name)
        index.journalRows[journal.publicationId] = journal.copy(stagingRelativePath = "../preserve.txt")
        expect<IllegalArgumentException> { first.recover() }
        assertEquals("keep", outside.readText())
        index.journalRows.clear()
        first.discard(prepared)
    }

    @Test fun streamCloseFailureDoesNotPublishOrRetainPreparedBody() = runTest {
        val root = temporary.newFolder()
        val store = store(root, MemoryIndex())
        val stream = Body(bytes, closeFailure = IOException("close"))
        expect<IOException> { store.prepare(id, "v1", stream.opened()) }
        assertEquals(1, stream.closes)
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun cancellationDuringStreamCloseDiscardsThePreparedBodyThatCannotBeReturned() = runTest {
        val root = temporary.newFolder()
        val store = store(root, MemoryIndex())
        lateinit var caller: Job
        val stream = Body(bytes, onClose = { caller.cancel() })
        val operation = async {
            caller = requireNotNull(currentCoroutineContext()[Job])
            store.prepare(id, "v1", stream.opened())
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(1, stream.closes)
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun cleanupFailureKeepsOwnershipUntilRecoveryRetriesDirectorySync() = runTest {
        val root = temporary.newFolder()
        var failSync = false
        val ops = object : EngineFilePublication by LocalFileOps() {
            override fun syncDirectory(directory: File) {
                if (failSync && directory.name == "staging") {
                    failSync = false
                    throw IOException("directory sync failed")
                }
                check(directory.isDirectory)
            }
        }
        val store = store(root, MemoryIndex(), ops)
        val stream = Body(bytes, atEnd = { failSync = true }, closeFailure = IOException("close"))
        expect<IOException> { store.prepare(id, "v1", stream.opened()) }
        assertEquals(1, store.ownership().preparedPages)
        store.recover()
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun orphanRecoveryBatchesDirectorySyncsAndPreservesIndexedAndUnknownFiles() = runTest {
        val root = temporary.newFolder()
        val index = MemoryIndex()
        val synced = mutableListOf<File>()
        val ops = object : EngineFilePublication by LocalFileOps() {
            override fun syncDirectory(directory: File) { synced += directory.canonicalFile }
        }
        val original = store(root, index, ops)
        val committed = original.publish(original.prepare(id, "v1", Body(bytes).opened()))
        val committedFile = committed.page.file
        committed.close()
        val pageDirectory = File(root, "pages")
        val stagingDirectory = File(root, "staging")
        repeat(128) { ordinal ->
            File(pageDirectory, "${ordinal.toString(16).padStart(64, '0')}-${"a".repeat(64)}-${"b".repeat(64)}.page").writeBytes(bytes)
            File(stagingDirectory, "${java.util.UUID.randomUUID()}.part").writeBytes(bytes)
        }
        val unknown = File(pageDirectory, "unrecognized.keep").apply { writeText("preserve") }
        synced.clear()
        val restarted = store(root, index, ops)
        restarted.recover()
        assertEquals(1, synced.count { it == pageDirectory.canonicalFile })
        assertEquals(1, synced.count { it == stagingDirectory.canonicalFile })
        assertEquals(setOf(committedFile.name, unknown.name), pageDirectory.list()!!.toSet())
        assertTrue(stagingDirectory.list()!!.isEmpty())
        val pinned = checkNotNull(restarted.find(id, "v1"))
        assertArrayEquals(bytes, pinned.page.file.readBytes())
        pinned.close()
        assertEquals(0, restarted.ownership().fileLeases)
    }

    private fun TestScope.store(root: File, index: MemoryIndex,
        fileOps: EngineFilePublication = LocalFileOps(),
        nowMillis: () -> Long = { 100L },
        checkpoint: suspend (EnginePublicationStep) -> Unit = {}) = EngineRawStorage(
        root, index, StandardTestDispatcher(testScheduler, "storage"), NoPositions,
        fileOps, nowMillis, checkpoint,
    )

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.name}") }
        catch (failure: Throwable) { if (failure !is T) throw failure }
    }

    private class Body(private val bytes: ByteArray, private val atEnd: suspend () -> Unit = {},
        private val closeFailure: Throwable? = null, private val onClose: () -> Unit = {}) : PageByteStream {
        var closes = 0
        private var offset = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (this.offset == bytes.size) { atEnd(); return -1 }
            val count = minOf(byteCount, bytes.size - this.offset)
            bytes.copyInto(destination, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
        override fun close() { closes++; onClose(); closeFailure?.let { throw it } }
        fun opened() = OpenedPage(this, bytes.size.toLong(), "image/png", null, null)
    }

    private object NoPositions : EnginePositionPort {
        override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) = Unit
        override suspend fun load(episodeId: EpisodeId): SourceAnchor? = null
    }
}

internal class LocalFileOps : EngineFilePublication {
    override fun syncFile(file: File) = java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
    override fun rename(staging: File, destination: File) { Files.move(staging.toPath(), destination.toPath()) }
    override fun syncDirectory(directory: File) { check(directory.isDirectory) }
}

internal class MemoryIndex : EnginePublicationIndex {
    val pageRows = linkedMapOf<Pair<String, String>, EnginePageEntity>()
    val journalRows = linkedMapOf<String, EnginePublicationEntity>()
    var afterStage: suspend () -> Unit = {}
    var pageReads = 0
    var pageListReads = 0
    val touchAllSizes = mutableListOf<Int>()

    override suspend fun page(cacheKey: String, revision: String): EnginePageEntity? {
        pageReads += 1
        return pageRows[cacheKey to revision]
    }

    override suspend fun pages(): List<EnginePageEntity> {
        pageListReads += 1
        return pageRows.values.toList()
    }

    override suspend fun journals() = journalRows.values.toList()
    override suspend fun stage(journal: EnginePublicationEntity) {
        journalRows[journal.publicationId] = journal
        afterStage()
    }
    override suspend fun commit(journalId: String, page: EnginePageEntity) {
        pageRows[page.cacheKey to page.contentRevision] = page
        journalRows.remove(journalId)
    }
    override suspend fun forgetJournal(journalId: String) { journalRows.remove(journalId) }
    override suspend fun remove(page: EnginePageEntity) { pageRows.remove(page.cacheKey to page.contentRevision) }
    override suspend fun touch(page: EnginePageEntity, timeMillis: Long) {
        pageRows[page.cacheKey to page.contentRevision] = page.copy(lastAccessEpochMillis = timeMillis)
    }
    override suspend fun touchAll(pages: List<EnginePageEntity>) {
        if (pages.isEmpty()) return
        touchAllSizes += pages.size
        pages.forEach { touch(it, it.lastAccessEpochMillis) }
    }
}
