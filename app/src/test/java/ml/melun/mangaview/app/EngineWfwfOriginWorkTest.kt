package ml.melun.mangaview.app

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.work.WorkCoordinator
import org.junit.Assert.*
import org.junit.Test

class EngineWfwfOriginWorkTest {
    private val old = URI("https://wfwf492.com")
    private val live = URI("https://wfwf493.com")

    @Test fun resetRecoversAndNextEpisodeUsesPublishedOriginWithinOneBodyPermit() = runTest {
        var resolutions = 0
        val origins = EngineWfwfOriginWork(old) { resolutions++; live.toString() }
        val coordinator = WorkCoordinator(this, WorkLimits(network = 1, bodies = 1, backgroundNetwork = 1))
        val calls = mutableListOf<URI>()
        fun episode(id: String) = origins.request { origin -> document(id, origin) {
            calls += origin
            if (origin == old) throw IOException("Connection reset")
            "$id from $origin"
        } }
        val first = coordinator.acquire(episode("3"))
        assertEquals("3 from $live", first.value)
        first.close(); first.awaitReleased()
        val second = coordinator.acquire(episode("4"))
        assertEquals("4 from $live", second.value)
        second.close(); second.awaitReleased()
        assertEquals(listOf(old, live, live), calls)
        assertEquals(1, resolutions)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun unresolvedAddressPreservesOriginalFailureWithoutReplay() = runTest {
        val failure = IOException("Connection reset")
        var attempts = 0
        val origins = EngineWfwfOriginWork(old) { null }
        val coordinator = WorkCoordinator(this)
        val sub = coordinator.submit(origins.request { origin -> document("3", origin) {
            attempts++; throw failure
        } })
        try { sub.await(); fail("Expected connection failure") } catch (actual: IOException) {
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
        sub.close(); sub.awaitReleased()
        assertEquals(1, attempts)
        coordinator.close()
    }

    @Test fun closingTheViewerCancelsDiscoveryAndReleasesItsBodyWork() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val origins = EngineWfwfOriginWork(old) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val coordinator = WorkCoordinator(this)
        val sub = coordinator.submit(origins.request { origin -> document("3", origin) {
            throw IOException("Connection reset")
        } })
        entered.await()
        sub.close(); sub.awaitReleased()
        assertTrue(cancelled.isCompleted)
        val ownership = coordinator.snapshot()
        assertEquals(0, ownership.active)
        assertEquals(0, ownership.subscribers)
        assertEquals(0, ownership.retainedResults)
        coordinator.close()
    }

    private fun document(id: String, origin: URI, execute: suspend () -> String) = WorkRequest(
        WorkKey("wfwf:public", id, "document", origin.toString(), String::class.java),
        WorkDomain.BODY, WorkPriority.FOCUS, execute = { execute() },
    )
}
