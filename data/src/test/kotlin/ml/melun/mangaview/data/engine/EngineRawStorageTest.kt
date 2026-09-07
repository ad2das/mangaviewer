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
            if (failedAt == EnginePublicationStep.FILE_SYNCED) assertNull(recovered)
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
        assertNull(store.find(id, "v1"))
        val replacement = store.publish(store.prepare(id, "v1", Body(bytes).opened()))
        assertArrayEquals(bytes, replacement.page.file.readBytes())
        replacement.close()
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

    @Test fun modifiedStagingBytesCannotBePublishedWithTheOriginalDigest() = runTest {
        val index = MemoryIndex()
        val store = store(temporary.newFolder(), index)
        val prepared = store.prepare(id, "v1", Body(bytes).opened())
        prepared.page.file.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 0 })
        expect<IllegalStateException> { store.publish(prepared) }
        assertTrue(index.pageRows.isEmpty())
        assertTrue(index.journalRows.isEmpty())
        store.discard(prepared)
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

    private fun TestScope.store(root: File, index: MemoryIndex,
        fileOps: EngineFilePublication = LocalFileOps(),
        checkpoint: suspend (EnginePublicationStep) -> Unit = {}) = EngineRawStorage(
        root, index, StandardTestDispatcher(testScheduler, "storage"), NoPositions,
        fileOps, { 100L }, checkpoint,
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
    override suspend fun page(cacheKey: String, revision: String) = pageRows[cacheKey to revision]
    override suspend fun pages() = pageRows.values.toList()
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
}
