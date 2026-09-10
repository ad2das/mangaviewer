package ml.melun.mangaview.engine.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkMetadataTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)
    private val geometry = WorkMetadata.PageGeometry(id, "revision", PageDimensions(100, 200))
    private fun request(execute: suspend (WorkContext) -> String) = WorkRequest(
        WorkKey("test", "page", "original", "revision", String::class.java),
        WorkDomain.NETWORK, WorkPriority.FOCUS, execute = execute)

    @Test fun lateSubscriberSharesGeometryBeforeBodyCompletionWithoutAnotherExecution() = runTest {
        val coordinator = WorkCoordinator(this)
        val body = CompletableDeferred<Unit>()
        var executions = 0
        val first = coordinator.submit(request {
            executions++
            it.publishMetadata(geometry)
            body.await()
            "complete original"
        })
        runCurrent()
        val second = coordinator.submit(request { error("The same key must share its original executor") })
        assertEquals(geometry, first.metadata.first())
        assertEquals(geometry, second.metadata.first())
        assertEquals(1, executions)
        assertEquals(0, coordinator.snapshot().retainedResults)
        first.close()
        first.awaitReleased()
        body.complete(Unit)
        assertEquals("complete original", second.await())
        second.close()
        second.awaitReleased()
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun cancelledExecutionCannotPublishIntoAReplacementWithTheSameKey() = runTest {
        val coordinator = WorkCoordinator(this)
        lateinit var old: WorkContext
        val first = coordinator.submit(request { old = it; awaitCancellation() })
        runCurrent()
        first.close()
        first.awaitReleased()
        val body = CompletableDeferred<Unit>()
        val replacement = geometry.copy(dimensions = PageDimensions(200, 300))
        val second = coordinator.submit(request {
            it.publishMetadata(replacement)
            body.await()
            "new original"
        })
        runCurrent()
        try { old.publishMetadata(geometry); fail("A retired attempt cannot publish") }
        catch (_: IllegalStateException) { }
        assertEquals(replacement, second.metadata.first())
        body.complete(Unit)
        assertEquals("new original", second.await())
        second.close()
        second.awaitReleased()
        coordinator.close()
    }

    @Test fun conflictingHeaderCannotReplaceAnAlreadyPublishedImmutableGeometry() = runTest {
        val coordinator = WorkCoordinator(this)
        val first = coordinator.submit(request {
            it.publishMetadata(geometry)
            it.publishMetadata(geometry)
            try {
                it.publishMetadata(geometry.copy(dimensions = PageDimensions(200, 300)))
                fail("An original revision must have one exact geometry")
            } catch (_: IllegalStateException) { }
            "original"
        })
        assertEquals("original", first.await())
        assertEquals(geometry, first.metadata.first())
        first.close()
        first.awaitReleased()
        coordinator.close()
    }
}
