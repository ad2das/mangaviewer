package ml.melun.mangaview.data.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import ml.melun.mangaview.engine.api.PreparedPage
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.StoredPageLease
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Group-commit behavior of [EnginePublicationBatcher]: batching under the single storage mutex,
 * per-page crash recovery, per-page error isolation, cancellation of queued/in-batch callers,
 * the defensive commitAll immutability check, concurrent staging syncs, the atomic eviction that
 * closes the trim-vs-find race, and the same-key duplicate deferred to the next batch.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnginePublicationBatchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun publishesPendingUnderTheMutexCommitAsOneBatch() = runTest {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        val ops = CountingFileOps()
        val store = newStore(root, index, ops)
        publishEvictable(store)
        val first = prepare(store, id(1), "v1")
        val second = prepare(store, id(2), "v1")
        val (trim, gate) = parkTrim(store, index)
        index.resetRecording()

        val a = async { store.publish(first) }
        val b = async { store.publish(second) }
        runCurrent()
        val syncsBeforeRelease = ops.pageDirectorySyncs
        gate.complete(Unit)
        val leaseA = a.await()
        val leaseB = b.await()
        trim.join()

        assertEquals(listOf(2), index.stageAllSizes)
        assertEquals(listOf(2), index.commitAllSizes)
        // One directory sync when trim unlinks its evicted file, one for the whole publish batch.
        assertEquals(syncsBeforeRelease + 2, ops.pageDirectorySyncs)
        assertTrue(leaseA.page.file.isFile)
        assertTrue(leaseB.page.file.isFile)
        leaseA.close()
        leaseB.close()
        checkNotNull(store.find(id(1), "v1")).close()
        checkNotNull(store.find(id(2), "v1")).close()
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
    }

    /**
     * Two publishes of one (key, revision) can be enqueued before either is in the index. The batch
     * stages only the first; the deferred second resolves in the next batch against the committed
     * row. With identical bodies it dedupes to the committed file and no rename ever collides.
     */
    @Test fun duplicatePublishInOneBatchDedupesToTheCommittedLease() = runTest {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        val store = newStore(root, index, CountingFileOps())
        publishEvictable(store)
        val first = prepare(store, id(1), "v1")
        val second = prepare(store, id(1), "v1")
        val (trim, gate) = parkTrim(store, index)
        index.resetRecording()

        val a = async { store.publish(first) }
        val b = async { store.publish(second) }
        runCurrent()
        gate.complete(Unit)
        val leaseA = a.await()
        val leaseB = b.await()
        trim.join()

        assertEquals("only the first of the pair stages", listOf(1), index.stageAllSizes)
        assertEquals(listOf(1), index.commitAllSizes)
        assertEquals("the deferred second delegates to the committed file", leaseA.page.file, leaseB.page.file)
        assertArrayEqualsPayload(leaseB.page.file)
        leaseA.close()
        leaseB.close()
        checkNotNull(store.find(id(1), "v1")).close()
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(index.journalRows.isEmpty())
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    /**
     * Same forced batch, different bodies: the deferred second must fail alone with
     * ImmutableRevisionConflictException against the committed row; the first stays durable and the
     * batch is not failed.
     */
    @Test fun duplicatePublishInOneBatchConflictsOnlyTheSecond() = runTest {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        val store = newStore(root, index, CountingFileOps())
        publishEvictable(store)
        val first = prepare(store, id(1), "v1")
        val conflicting = prepare(
            store, id(1), "v1",
            payload.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
        )
        val (trim, gate) = parkTrim(store, index)
        index.resetRecording()

        val a = async { store.publish(first) }
        val b = async { runCatching { store.publish(conflicting) } }
        runCurrent()
        gate.complete(Unit)
        val leaseA = a.await()
        val resultB = b.await()
        trim.join()

        assertTrue(
            "only the second fails, with a revision conflict",
            resultB.exceptionOrNull() is ImmutableRevisionConflictException,
        )
        assertEquals(listOf(1), index.stageAllSizes)
        assertEquals(listOf(1), index.commitAllSizes)
        assertArrayEqualsPayload(leaseA.page.file)
        leaseA.close()
        store.discard(conflicting)
        checkNotNull(store.find(id(1), "v1")).close()
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(index.journalRows.isEmpty())
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun publisherReturnsAfterAtMostItsOwnBatchPlusTheOneInProgress() = runTest {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        val store = newStore(root, index, CountingFileOps())
        publishEvictable(store)
        val first = prepare(store, id(1), "v1")
        val second = prepare(store, id(2), "v1")
        val third = prepare(store, id(3), "v1")
        val (trim, gate) = parkTrim(store, index)
        index.stageAllGate = CompletableDeferred()
        index.resetRecording()

        val a = async { store.publish(first) }
        runCurrent()
        gate.complete(Unit)
        runCurrent() // a takes the mutex, batches [a], and parks inside the gated stageAll
        val b = async { store.publish(second) }
        val c = async { store.publish(third) }
        runCurrent()
        index.stageAllGate!!.complete(Unit)

        val leaseA = a.await()
        val leaseB = b.await()
        val leaseC = c.await()
        trim.join()

        // a: one batch (its own). b: the in-progress batch plus its own (which absorbs c). c: same.
        assertEquals(2, index.batchCount)
        assertEquals(listOf(1, 2), index.stageAllSizes)
        assertEquals(listOf(1, 2), index.commitAllSizes)
        listOf(leaseA, leaseB, leaseC).forEach { it.close() }
    }

    @Test fun crashAtEveryBatchStepRecoversEveryPage() = runTest {
        for (size in 1..3) {
            for (failedAt in EnginePublicationStep.entries) {
                val root = temporary.newFolder()
                val index = RecordingIndex()
                var armed = false
                val store = newStore(root, index) { if (armed && it == failedAt) throw IOException("crash") }
                publishEvictable(store)
                val ids = List(size) { id(it) }
                ids.forEach { store.publish(store.prepare(it, "old", BatchStream(payload).opened())).close() }
                val prepared = ids.map { store.prepare(it, "new", BatchStream(payload).opened()) }
                val (trim, gate) = parkTrim(store, index, targetBytes = size * payload.size.toLong())
                armed = true
                val publishers = prepared.map { page -> async { runCatching { store.publish(page).also { it.close() } } } }
                runCurrent()
                gate.complete(Unit)
                val results = publishers.awaitAll()
                trim.join()

                assertTrue("case size=$size step=$failedAt must fail", results.all { it.isFailure })
                val restarted = newStore(root, index, CountingFileOps())
                restarted.recover()
                restarted.recover()
                for (entity in ids) {
                    val previous = checkNotNull(restarted.find(entity, "old"))
                    assertArrayEqualsPayload(previous.page.file)
                    previous.close()
                    val recovered = restarted.find(entity, "new")
                    if (failedAt == EnginePublicationStep.FILE_SYNCED) {
                        assertNull("size=$size step=$failedAt must leave the new revision absent", recovered)
                    } else {
                        assertNotNull("size=$size step=$failedAt must recover the new revision", recovered)
                        assertArrayEqualsPayload(recovered!!.page.file)
                        recovered.close()
                    }
                }
                assertEquals(0, restarted.ownership().pendingPublications)
                assertTrue(File(root, "staging").listFiles()!!.isEmpty())
                assertTrue(index.journalRows.isEmpty())
            }
        }
    }

    @Test fun oneBrokenPageDoesNotFailTheBatch() = runTest {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        val store = newStore(root, index, CountingFileOps())
        publishEvictable(store)
        val pinned = store.publish(store.prepare(id(1), "v1", BatchStream(payload).opened()))
        val clean = prepare(store, id(2), "v1")
        val tampered = prepare(store, id(3), "v1")
        tampered.page.file.appendBytes(byteArrayOf(0))
        val republishPinned = prepare(store, id(1), "v1")
        pinned.page.file.writeBytes(payload.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })

        val (trim, gate) = parkTrim(store, index)
        index.resetRecording()
        val publishers = listOf(republishPinned, clean, tampered)
            .map { page -> async { page to runCatching { store.publish(page) } } }
        runCurrent()
        gate.complete(Unit)
        val results = publishers.awaitAll()
        trim.join()

        assertTrue(results[0].second.exceptionOrNull() is EnginePageInUseException)
        val cleanLease = results[1].second.getOrThrow()
        assertTrue(results[2].second.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(1), index.stageAllSizes)
        assertEquals(listOf(1), index.commitAllSizes)
        assertArrayEqualsPayload(cleanLease.page.file)
        cleanLease.close()

        // The pinned page publishes after its lease is released; the failed staging is discarded.
        pinned.close()
        val repaired = store.publish(republishPinned)
        assertArrayEqualsPayload(repaired.page.file)
        repaired.close()
        store.discard(tampered)
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
        assertEquals(0, store.ownership().preparedPages)
        assertEquals(0, store.ownership().fileLeases)
    }

    @Test fun queuedPublisherCancelledWhileWaitingStillPublishesOnce() = runTest {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        val store = newStore(root, index, CountingFileOps())
        publishEvictable(store)
        val prepared = prepare(store, id(1), "v1")
        val (trim, gate) = parkTrim(store, index)

        val caller = async { store.publish(prepared) }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        trim.join()
        caller.join()

        assertTrue(caller.isCancelled)
        checkNotNull(store.find(id(1), "v1")).close()
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
        assertTrue(index.journalRows.isEmpty())
    }

    @Test fun cancellationAtCommittedCompletesTheBatchAndClosesOnlyThatLease() = runTest {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        lateinit var leader: Deferred<StoredPageLease>
        var armed = false
        val store = newStore(root, index) { if (armed && it == EnginePublicationStep.COMMITTED) leader.cancel() }
        publishEvictable(store)
        val first = prepare(store, id(1), "v1")
        val second = prepare(store, id(2), "v1")
        val (trim, gate) = parkTrim(store, index)

        armed = true
        leader = async { store.publish(first) }
        val follower = async { store.publish(second) }
        runCurrent()
        gate.complete(Unit)
        val leaderFailure = runCatching { leader.await() }
        val followerLease = follower.await()
        trim.join()

        assertTrue(leader.isCancelled)
        assertTrue(leaderFailure.isFailure)
        followerLease.close()
        assertEquals(0, store.ownership().fileLeases)
        assertEquals(0, store.ownership().preparedPages)
        checkNotNull(store.find(id(1), "v1")).close()
        checkNotNull(store.find(id(2), "v1")).close()
        assertTrue(index.journalRows.isEmpty())
    }

    @Test fun commitAllImmutabilityCheckFailsTheWholeBatchAndRecoveryResolvesIt() = runTest {
        val root = temporary.newFolder()
        val delegate = MemoryIndex()
        val index = RecordingIndex(delegate).also { it.immutableCheck = true }
        val store = newStore(root, index, CountingFileOps())
        publishEvictable(store)
        val first = prepare(store, id(1), "v1")
        val second = prepare(store, id(2), "v1")
        var injected = false
        val files = EnginePageFiles(root, LocalFileOps())
        delegate.afterStage = {
            if (!injected) {
                injected = true
                delegate.pageRows[PageCacheKey.of(id(1)) to "v1"] =
                    first.page.entity(files.destination(first.page), 1L).copy(byteCount = first.page.byteCount + 1)
            }
        }
        val (trim, gate) = parkTrim(store, index)
        val publishers = listOf(first, second).map { page -> async { runCatching { store.publish(page) } } }
        runCurrent()
        gate.complete(Unit)
        val results = publishers.awaitAll()
        trim.join()

        assertTrue(results.all { it.isFailure })
        assertTrue(results.all { it.exceptionOrNull() is IllegalStateException })
        assertEquals(2, index.journalRows.size)

        val restarted = newStore(root, index, CountingFileOps())
        restarted.recover()
        assertNull("the conflicting row for id1 must win; its staged publication is abandoned", restarted.find(id(1), "v1"))
        checkNotNull(restarted.find(id(2), "v1")).close()
        assertTrue(index.journalRows.isEmpty())
        assertTrue(File(root, "staging").listFiles()!!.isEmpty())
    }

    @Test fun batchStagingSyncsRunConcurrently() = runBlocking {
        val root = temporary.newFolder()
        val index = RecordingIndex()
        val barrier = CyclicBarrier(2)
        val overlapped = AtomicInteger()
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val ops = object : EngineFilePublication {
            private val inner = LocalFileOps()
            override fun syncFile(file: File) {
                if (armed.get()) {
                    barrier.await(5, TimeUnit.SECONDS)
                    overlapped.incrementAndGet()
                }
                inner.syncFile(file)
            }
            override fun rename(staging: File, destination: File) = inner.rename(staging, destination)
            override fun syncDirectory(directory: File) = inner.syncDirectory(directory)
        }
        val store = EngineRawStorage(root, index, Dispatchers.IO, BatchPositions, ops, { 100L }, {})
        store.publish(store.prepare(evictable, "trim", BatchStream(payload).opened())).close()
        val first = store.prepare(id(1), "v1", BatchStream(payload).opened())
        val second = store.prepare(id(2), "v1", BatchStream(payload).opened())
        armed.set(true)

        val gate = CompletableDeferred<Unit>()
        index.removeEntered = CompletableDeferred()
        index.removeGate = gate
        val trim = launch { store.trimTo(0) }
        index.removeEntered.await()

        val a = async { store.publish(first) }
        val b = async { store.publish(second) }
        kotlinx.coroutines.delay(200)
        gate.complete(Unit)
        val leaseA = a.await()
        val leaseB = b.await()
        trim.join()

        assertEquals("both staging files must fsync on distinct threads", 2, overlapped.get())
        assertArrayEqualsPayload(leaseA.page.file)
        assertArrayEqualsPayload(leaseB.page.file)
        leaseA.close()
        leaseB.close()
    }

    /**
     * Deterministic replay of the trim-vs-find interleaving the old two-step allowed:
     * [trim: isPinned=false] then [find: acquire + valid] then [trim: delete]. The atomic eviction
     * folds the pin check into the unlink under the lease lock, so a pin that lands first must make
     * the eviction refuse, and the only state a post-unlink reader can observe is a missing file.
     */
    @Test fun trimStyleEvictionCannotEvictAPinnedFile() = runTest {
        val root = temporary.newFolder()
        val file = File(root, "probe.page").also { it.writeBytes(payload) }
        val page = storedPage(file)
        val ownership = EngineStorageOwnership(Any())

        // The reader pinned first: the eviction step must refuse the unlink.
        val lease = ownership.acquire(page)
        assertNull("a pinned file must survive the eviction step", ownership.removeUnpinned(file) { file.delete(); true })
        assertTrue(file.isFile)

        // The eviction landed first: a reader that follows can never validate the removed file.
        lease.close()
        assertNotNull(ownership.removeUnpinned(file) { file.delete(); true })
        assertFalse(file.exists())
        val files = EnginePageFiles(root, LocalFileOps())
        val late = ownership.acquire(page)
        assertFalse("a lease acquired after the unlink can never validate", files.valid(late.page))
        late.close()
    }

    private val payload = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=",
    )

    private fun id(index: Int) = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "20001"), "7"), index)

    private val evictable = id(0)

    private suspend fun TestScope.newStore(
        root: File,
        index: EnginePublicationIndex,
        fileOps: EngineFilePublication = LocalFileOps(),
        checkpoint: suspend (EnginePublicationStep) -> Unit = {},
    ) = EngineRawStorage(
        root, index, StandardTestDispatcher(testScheduler, "storage"), BatchPositions, fileOps, { 100L }, checkpoint,
    )

    private suspend fun publishEvictable(store: EngineRawStorage) {
        store.publish(store.prepare(evictable, "trim", BatchStream(payload).opened())).close()
    }

    private suspend fun prepare(
        store: EngineRawStorage,
        pageId: PageId,
        revision: String,
        body: ByteArray = payload,
    ) = store.prepare(pageId, revision, BatchStream(body).opened())

    /**
     * Publishes the evictable page first, then runs trimTo so its loop reaches the gated
     * index.remove with the storage mutex held: publishers started afterwards enqueue and block on
     * that mutex, so all of them land in the first batch the released mutex produces.
     */
    private suspend fun TestScope.parkTrim(
        store: EngineRawStorage,
        index: RecordingIndex,
        targetBytes: Long = 0L,
    ): Pair<Job, CompletableDeferred<Unit>> {
        val gate = CompletableDeferred<Unit>()
        index.removeEntered = CompletableDeferred()
        index.removeGate = gate
        val trim = launch { store.trimTo(targetBytes) }
        runCurrent()
        index.removeEntered.await()
        return trim to gate
    }

    private fun assertArrayEqualsPayload(file: File) {
        assertTrue("published body must match the payload", file.readBytes().contentEquals(payload))
    }

    private fun storedPage(file: File) = ml.melun.mangaview.engine.api.StoredPage(
        pageId = id(9),
        contentRevision = "v1",
        file = file,
        byteCount = payload.size.toLong(),
        sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) },
        dimensions = ml.melun.mangaview.core.PageDimensions(1, 1),
        mediaType = "image/png",
    )
}

/** Recording wrapper: batch shapes, gates the batch-wide steps, and lets a test park trim under the mutex. */
internal class RecordingIndex(
    val delegate: MemoryIndex = MemoryIndex(),
) : EnginePublicationIndex by delegate {
    val stageAllSizes = mutableListOf<Int>()
    val commitAllSizes = mutableListOf<Int>()
    var batchCount = 0
        private set
    var stageAllGate: CompletableDeferred<Unit>? = null
    var removeGate: CompletableDeferred<Unit>? = null
    var removeEntered = CompletableDeferred<Unit>()

    /** Mirrors RoomEnginePublicationIndex's in-transaction immutability check when enabled. */
    var immutableCheck = false

    /** Discards setup publishes so assertions see only the batch under test. */
    fun resetRecording() {
        stageAllSizes.clear()
        commitAllSizes.clear()
        batchCount = 0
    }

    val journalRows get() = delegate.journalRows

    override suspend fun stageAll(journals: List<EnginePublicationEntity>) {
        stageAllGate?.await()
        stageAllSizes += journals.size
        batchCount += 1
        delegate.stageAll(journals)
    }

    override suspend fun commitAll(entries: List<Pair<String, EnginePageEntity>>) {
        if (immutableCheck) {
            for ((_, page) in entries) {
                val previous = delegate.page(page.cacheKey, page.contentRevision)
                check(previous == null || previous.copy(lastAccessEpochMillis = page.lastAccessEpochMillis,
                    createdAtEpochMillis = page.createdAtEpochMillis) == page) { "Immutable publication changed" }
            }
        }
        commitAllSizes += entries.size
        delegate.commitAll(entries)
    }

    override suspend fun remove(page: EnginePageEntity) {
        removeEntered.complete(Unit)
        removeGate?.await()
        delegate.remove(page)
    }
}

private class CountingFileOps : EngineFilePublication {
    private val inner = LocalFileOps()
    var pageDirectorySyncs = 0
        private set

    override fun syncFile(file: File) = inner.syncFile(file)
    override fun rename(staging: File, destination: File) = inner.rename(staging, destination)
    override fun syncDirectory(directory: File) {
        if (directory.name == "pages") pageDirectorySyncs += 1
        inner.syncDirectory(directory)
    }
}

private object BatchPositions : EnginePositionPort {
    override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) = Unit
    override suspend fun load(episodeId: EpisodeId): SourceAnchor? = null
}

private class BatchStream(private val payload: ByteArray) : PageByteStream {
    private var cursor = 0
    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (cursor == payload.size) return -1
        val count = minOf(byteCount, payload.size - cursor)
        payload.copyInto(destination, offset, cursor, cursor + count)
        cursor += count
        return count
    }
    override fun close() = Unit
    fun opened() = OpenedPage(this, payload.size.toLong(), "image/png", null, null)
}
