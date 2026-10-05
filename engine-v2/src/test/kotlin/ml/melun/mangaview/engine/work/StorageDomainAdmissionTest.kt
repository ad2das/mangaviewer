package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * STORAGE_READ and STORAGE_PUBLISH are separate lanes from the single STORAGE writer: reads must be
 * admitted while a writer (or a publish) holds its permit, each lane is bounded independently, and
 * none of the three domains consumes another's permit.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageDomainAdmissionTest {
    @Test fun readsRunWhileTheSingleStorageWriterHoldsItsOnlyPermit() = runTest {
        val coordinator = WorkCoordinator(this)
        val writerStarted = CompletableDeferred<Unit>()
        val writerRelease = CompletableDeferred<Unit>()
        val writer = coordinator.submit(request("writer", WorkDomain.STORAGE) {
            writerStarted.complete(Unit)
            writerRelease.await()
            "saved"
        })
        writerStarted.await()

        val started = List(4) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        val reads = started.mapIndexed { index, signal ->
            coordinator.submit(request("read$index", WorkDomain.STORAGE_READ) {
                signal.complete(Unit)
                release.await()
                "page"
            })
        }
        started.forEach { it.await() }

        val fifthStarted = CompletableDeferred<Unit>()
        val fifth = coordinator.submit(request("read4", WorkDomain.STORAGE_READ) {
            fifthStarted.complete(Unit)
            "page"
        })
        runCurrent()
        assertFalse("a fifth read must wait for a free read permit", fifthStarted.isCompleted)

        release.complete(Unit)
        reads.forEach { assertEquals("page", it.await()) }
        assertEquals("page", fifth.await())

        writerRelease.complete(Unit)
        assertEquals("saved", writer.await())
        listOf(writer, fifth).forEach { it.close(); it.awaitReleased() }
        reads.forEach { it.close(); it.awaitReleased() }
        coordinator.close()
    }

    @Test fun publishLaneAdmitsFourConcurrentPublishersAndQueuesTheFifth() = runTest {
        val coordinator = WorkCoordinator(this)
        val started = List(4) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        val publishers = started.mapIndexed { index, signal ->
            coordinator.submit(request("publish$index", WorkDomain.STORAGE_PUBLISH) {
                signal.complete(Unit)
                release.await()
                "published"
            })
        }
        started.forEach { it.await() }

        val fifthStarted = CompletableDeferred<Unit>()
        val fifth = coordinator.submit(request("publish4", WorkDomain.STORAGE_PUBLISH) {
            fifthStarted.complete(Unit)
            "published"
        })
        runCurrent()
        assertFalse("a fifth publish must wait for a free publish permit", fifthStarted.isCompleted)

        release.complete(Unit)
        publishers.forEach { assertEquals("published", it.await()) }
        assertEquals("published", fifth.await())
        publishers.forEach { it.close(); it.awaitReleased() }
        fifth.close()
        fifth.awaitReleased()
        coordinator.close()
    }

    @Test fun readAndWriteLanesNeverConsumeEachOthersPermits() = runTest {
        val coordinator = WorkCoordinator(this)
        val readStarted = CompletableDeferred<Unit>()
        val readRelease = CompletableDeferred<Unit>()
        val held = coordinator.submit(request("read", WorkDomain.STORAGE_READ) {
            readStarted.complete(Unit)
            readRelease.await()
            "page"
        })
        readStarted.await()

        // A STORAGE writer and a STORAGE_PUBLISH publisher both run with the read lane saturated.
        val writerLease = coordinator.acquire(request("writer", WorkDomain.STORAGE) { "saved" })
        assertEquals("saved", writerLease.value)
        writerLease.close()
        writerLease.awaitReleased()
        val publisherLease = coordinator.acquire(request("publisher", WorkDomain.STORAGE_PUBLISH) { "published" })
        assertEquals("published", publisherLease.value)
        publisherLease.close()
        publisherLease.awaitReleased()

        // And a held STORAGE writer does not starve the read lane.
        val writerStarted = CompletableDeferred<Unit>()
        val writerRelease = CompletableDeferred<Unit>()
        val writer = coordinator.submit(request("writer2", WorkDomain.STORAGE) {
            writerStarted.complete(Unit)
            writerRelease.await()
            "saved"
        })
        writerStarted.await()
        val readLease = coordinator.acquire(request("read2", WorkDomain.STORAGE_READ) { "page" })
        assertEquals("page", readLease.value)
        readLease.close()
        readLease.awaitReleased()

        readRelease.complete(Unit)
        writerRelease.complete(Unit)
        assertEquals("page", held.await())
        assertEquals("saved", writer.await())
        held.close(); held.awaitReleased()
        writer.close(); writer.awaitReleased()
        coordinator.close()
    }

    @Test fun nonPositiveStorageLaneLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WorkLimits(storageRead = 0) }
        assertThrows(IllegalArgumentException::class.java) { WorkLimits(storageRead = -1) }
        assertThrows(IllegalArgumentException::class.java) { WorkLimits(storagePublish = 0) }
        assertThrows(IllegalArgumentException::class.java) { WorkLimits(storagePublish = -1) }
    }

    private fun request(
        resource: String,
        domain: WorkDomain,
        execute: suspend (WorkContext) -> String,
    ) = WorkRequest(
        WorkKey("storage-admission", resource, "test", "revision", String::class.java),
        domain,
        WorkPriority.VISIBLE,
        execute = execute,
    )
}
