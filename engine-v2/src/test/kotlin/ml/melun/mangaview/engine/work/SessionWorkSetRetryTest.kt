package ml.melun.mangaview.engine.work

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.runtime.SessionDemand
import ml.melun.mangaview.engine.runtime.SessionWorkSet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed demand retries exactly when its backoff is due, and a reconcile whose failures are not
 * yet due must return from the clock check without scanning the registry.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionWorkSetRetryTest {

    @Test
    fun failuresWithDifferentBackoffsRetryAtTheirDueTimes() = runTest {
        var now = 0L
        val attempts = mutableMapOf<String, Int>()
        val failures = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val coordinator = WorkCoordinator(this)
        val work = SessionWorkSet(this, coordinator, { key, _ -> failures += key.resource }, clock = { now })
        val demands = listOf(demand("a", attempts, accepted), demand("b", attempts, accepted))

        // "a" fails at t=0, so its backoff is due at 1s.
        work.reconcile(listOf(demands[0]))
        runCurrent()
        assertEquals(listOf("a"), failures)
        assertEquals(1, attempts["a"])

        // "b" fails later, so its own backoff is due at 1.4s.
        now = 400_000_000L
        work.reconcile(demands)
        runCurrent()
        assertEquals(listOf("a", "b"), failures)
        assertEquals(1, attempts["b"])

        // Nothing is due at 0.999s: the reconcile must neither scan nor restart.
        now = 999_999_999L
        val scansBeforeDue = work.retryScans
        work.reconcile(demands)
        runCurrent()
        assertEquals("a reconcile with nothing due must not scan", scansBeforeDue, work.retryScans)
        assertEquals(1, attempts["a"])
        assertEquals(1, attempts["b"])

        // "a" restarts exactly at 1s; "b" still waits for its own backoff.
        now = 1_000_000_000L
        work.reconcile(demands)
        runCurrent()
        assertEquals(listOf("a"), accepted)
        assertEquals(2, attempts["a"])
        assertEquals(1, attempts["b"])
        assertEquals(scansBeforeDue + 1, work.retryScans)

        // "b" restarts at its due time.
        now = 1_400_000_000L
        work.reconcile(demands)
        runCurrent()
        assertEquals(listOf("a", "b"), accepted)
        assertEquals(2, attempts["b"])

        work.close()
        coordinator.close()
    }

    @Test
    fun failedEntryWithActiveJobRetriesOnceItsJobCompletes() = runTest {
        var now = 0L
        val attempts = mutableMapOf<String, Int>()
        val accepted = mutableListOf<String>()
        val coordinator = WorkCoordinator(this)
        val work = SessionWorkSet(this, coordinator, { _, _ -> }, clock = { now })
        val demands = listOf(demand("a", attempts, accepted), demand("b", attempts, accepted))

        work.reconcile(demands)
        runCurrent()
        assertEquals(1, attempts["a"])
        assertEquals(1, attempts["b"])

        // Reproduce the window where finish() has set a failed entry's retry time while its job has
        // not completed yet: the entry is failed and due but still looks active.
        val gate = Job()
        suspendJobAfterRetry(work, demands[0].request.key, gate)

        // B restarts first. A bound computed without the still-active failed entry would go
        // stale-high here and never due-check A again.
        now = 1_000_000_000L
        work.reconcile(demands)
        runCurrent()
        assertEquals(2, attempts["b"])
        assertEquals(1, attempts["a"])

        // A's job completes with its retry time already due.
        gate.complete()
        now = 2_000_000_000L
        work.reconcile(demands)
        runCurrent()
        assertEquals("a failed entry whose job completed later is still retried", 2, attempts["a"])
        assertEquals(listOf("b", "a"), accepted)

        work.close()
        coordinator.close()
    }

    @Test
    fun reconcileWithNothingDueReturnsBeforeScanning() = runTest {
        var now = 0L
        val attempts = mutableMapOf<String, Int>()
        val accepted = mutableListOf<String>()
        val coordinator = WorkCoordinator(this)
        val work = SessionWorkSet(this, coordinator, { _, _ -> }, clock = { now })
        val demands = listOf(demand("a", attempts, accepted))

        work.reconcile(demands)
        runCurrent()
        assertEquals(1, attempts["a"])
        assertEquals(0, work.retryScans)

        // Reconciles while the backoff is pending stay a clock check with no scan.
        now = 500_000_000L
        repeat(20) {
            work.reconcile(demands)
            runCurrent()
        }
        assertEquals("nothing due must not scan", 0, work.retryScans)
        assertEquals(1, attempts["a"])
        assertEquals(emptyList<String>(), accepted)

        // The first reconcile at or past the due time scans and restarts.
        now = 1_000_000_000L
        work.reconcile(demands)
        runCurrent()
        assertEquals(1, work.retryScans)
        assertEquals(2, attempts["a"])
        assertEquals(listOf("a"), accepted)

        work.close()
        coordinator.close()
    }

    /**
     * Reproduces the window where finish() has set a failed entry's retry time but its job has not
     * completed yet: the job is swapped for one this test completes later. Reflection keeps the
     * test compilable against the previous implementation, so it can fail there for the real
     * reason instead of a compile error.
     */
    private fun suspendJobAfterRetry(work: SessionWorkSet, key: WorkKey<*>, job: Job) {
        val entriesField = SessionWorkSet::class.java.getDeclaredField("entries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val entries = entriesField.get(work) as Map<WorkKey<*>, Any>
        val entry = entries[key] ?: error("No entry for $key")
        entry.javaClass.getDeclaredField("job").apply { isAccessible = true }.set(entry, job)
    }

    private fun demand(
        name: String,
        attempts: MutableMap<String, Int>,
        accepted: MutableList<String>,
    ): SessionDemand<String> = SessionDemand(request(name, attempts), accept = { accepted += name })

    private fun request(name: String, attempts: MutableMap<String, Int>): WorkRequest<String> =
        WorkRequest(
            WorkKey("test", name, "read", "1", String::class.java),
            WorkDomain.STORAGE,
            WorkPriority.VISIBLE,
            execute = {
                val attempt = (attempts[name] ?: 0) + 1
                attempts[name] = attempt
                if (attempt == 1) error("transient $name failure")
                "ok-$name"
            },
            dispose = {},
        )
}
