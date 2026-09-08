package ml.melun.mangaview.account

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import ml.melun.mangaview.data.db.BookmarkEntity
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSyncSessionTest {
    private val bookmark = CloudLibraryCodec.bookmark(BookmarkEntity("wfwf", "comic:12", "3", "p0001", 80, 10))

    @Test fun unresolvedLegacyEpisodesRetryWithoutBlockingANewLocalChange() = runTest {
        val local = MemoryLocal(listOf(bookmark))
        val cloud = WaitingRemote().apply { unresolvedRecords = 1; response.complete(listOf(bookmark)) }
        val job = backgroundScope.launch { session(local, MemoryCheckpoint(null), cloud).run() }
        runCurrent(); advanceTimeBy(1201); runCurrent()
        assertEquals(1, cloud.uploads.size)
        advanceTimeBy(61_201); runCurrent()
        assertEquals(2, cloud.uploads.size)
        local.changes.emit(Unit); runCurrent(); advanceTimeBy(1201); runCurrent()
        assertEquals(3, cloud.uploads.size)
        job.cancelAndJoin()
    }

    @Test fun localDeletionIsDurableWhileCloudRequestIsStillWaiting() = runTest {
        val local = MemoryLocal(listOf(bookmark))
        val checkpoint = MemoryCheckpoint(AccountCheckpoint("owner", listOf(bookmark)))
        val cloud = WaitingRemote()
        val job = backgroundScope.launch { session(local, checkpoint, cloud).run() }
        runCurrent(); advanceTimeBy(1201); runCurrent()
        assertEquals(1, cloud.uploads.size)
        local.records = emptyList(); local.changes.emit(Unit); runCurrent()
        assertTrue(checkpoint.value!!.records.single().deleted)
        cloud.response.complete(listOf(bookmark)); runCurrent()
        assertTrue(local.records.isEmpty())
        assertTrue(checkpoint.value!!.records.single().deleted)
        job.cancelAndJoin()
        assertTrue(cloud.unsubscribed)
    }

    @Test fun oldAccountLibraryNeverUploadsToAnotherAccount() = runTest {
        val local = MemoryLocal(listOf(bookmark))
        val checkpoint = MemoryCheckpoint(AccountCheckpoint("someone-else", listOf(bookmark)))
        val cloud = WaitingRemote()
        session(local, checkpoint, cloud).run()
        assertTrue(cloud.uploads.isEmpty())
        assertEquals("someone-else", checkpoint.value!!.owner)
        assertEquals(listOf(bookmark), local.records)
    }

    @Test fun cancelledAccountCannotApplyAnInFlightDownload() = runTest {
        val local = MemoryLocal(emptyList())
        val checkpoint = MemoryCheckpoint(null)
        val cloud = WaitingRemote()
        val job = backgroundScope.launch { session(local, checkpoint, cloud).run() }
        runCurrent(); advanceTimeBy(1201); runCurrent()
        job.cancelAndJoin()
        cloud.response.complete(listOf(bookmark)); runCurrent()
        assertTrue(local.records.isEmpty())
        assertTrue(cloud.unsubscribed)
    }

    private fun session(local: MemoryLocal, checkpoint: MemoryCheckpoint, remote: WaitingRemote) =
        AccountSyncSession("owner", local, checkpoint, remote, Channel(Channel.CONFLATED), { true }, { _, _ -> }, { 20 })

    private class MemoryCheckpoint(var value: AccountCheckpoint?) : AccountCheckpointPort {
        override fun read() = value
        override fun write(value: AccountCheckpoint) { this.value = value }
    }

    private class MemoryLocal(var records: List<CloudLibraryRecord>) : CloudLocalPort {
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override suspend fun snapshot() = records
        override suspend fun restore(remote: List<CloudLibraryRecord>, beforeDownload: List<CloudLibraryRecord>, now: Long): List<CloudLibraryRecord> {
            val merged = CloudLibraryRecords.mergeAfterDownload(remote, beforeDownload, records, now)
            records = merged.filterNot { it.deleted }
            return merged
        }
    }

    private class WaitingRemote : CloudRemotePort {
        override var unresolvedRecords = 0
        val uploads = mutableListOf<List<CloudLibraryRecord>>()
        val response = CompletableDeferred<List<CloudLibraryRecord>>()
        var unsubscribed = false
        override fun observe(changed: () -> Unit): () -> Unit = { unsubscribed = true }
        override suspend fun exchange(local: List<CloudLibraryRecord>): List<CloudLibraryRecord> {
            uploads += local
            return response.await()
        }
    }
}
