package ml.melun.mangaview.source.ntk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import ml.melun.mangaview.source.PageFetchPriority
import org.junit.Assert.assertEquals
import org.junit.Test

class NtkReplicaAttemptAdmissionTest {
    @Test
    fun currentForwardAttemptPassesAnOlderAdjacentAttempt() = runTest {
        val admission = NtkReplicaAttemptAdmission(maxAttempts = 1, visibleReserved = 0)
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()
        val first = async {
            admission.withPermit(PageFetchPriority.ADJACENT_FORWARD) {
                starts += "running"
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val adjacent = async {
            admission.withPermit(PageFetchPriority.ADJACENT_FORWARD) { starts += "adjacent" }
        }
        val forward = async {
            admission.withPermit(PageFetchPriority.FORWARD) { starts += "forward" }
        }
        yield()

        releaseFirst.complete(Unit)
        first.await()
        forward.await()
        adjacent.await()

        assertEquals(listOf("running", "forward", "adjacent"), starts)
    }

    @Test
    fun visibleAttemptUsesItsReservedSlotWhileSpeculationWaits() = runTest {
        val admission = NtkReplicaAttemptAdmission(maxAttempts = 2, visibleReserved = 1)
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()
        val first = async {
            admission.withPermit(PageFetchPriority.FORWARD) {
                starts += "running"
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val waiting = async {
            admission.withPermit(PageFetchPriority.FORWARD) { starts += "waiting" }
        }
        val visible = async {
            admission.withPermit(PageFetchPriority.VISIBLE) { starts += "visible" }
        }
        yield()

        visible.await()
        assertEquals(listOf("running", "visible"), starts)
        releaseFirst.complete(Unit)
        first.await()
        waiting.await()
    }
}
