package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class InputReceiptTest {
    private val sample = InputSample(1, 1, 100, -1024)
    private val page = PageId.at(EpisodeId(SeriesId(SourceId("s"), "series"), "episode"), 0)

    @Test
    fun deferredInputHasNoInventedCompletionTime() {
        val receipt = InputReceipt(sample, 110, null, 0, InputOutcome.DEFERRED, 0)
        assertNull(receipt.resolvedAtNanos)
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(resolvedAtNanos = 110) }
    }

    @Test
    fun clampedInputRequiresBoundaryEvidenceAndRealResolutionTime() {
        assertThrows(IllegalArgumentException::class.java) {
            InputReceipt(sample, 110, 111, 0, InputOutcome.CLAMPED, 1)
        }
        val proof = BoundaryProof(DocumentBoundary.START, page, 1)
        val receipt = InputReceipt(sample, 110, 111, 0, InputOutcome.CLAMPED, 1, proof)
        assertEquals(0L, receipt.appliedScreenUnits)
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(outcome = InputOutcome.APPLIED) }
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(resolvedAtNanos = 109) }
    }

    @Test
    fun deferredResolutionRetainsTheOriginalAcceptance() {
        val initial = InputReceipt(sample, 110, null, -256, InputOutcome.DEFERRED, 1)
        val resolved = initial.copy(resolvedAtNanos = 500, appliedScreenUnits = -1024,
            outcome = InputOutcome.APPLIED, geometryRevision = 2)
        assertEquals(initial.acceptedAtNanos, resolved.acceptedAtNanos)
        assertEquals(initial.sample, resolved.sample)
    }
}
