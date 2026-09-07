package ml.melun.mangaview.viewer.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import org.junit.Assert.*
import org.junit.Test

class EngineCaptureEvidenceTest {
    private val page = PageId.at(EpisodeId(SeriesId(SourceId("test"), "series"), "episode"), 0)
    private fun state(pending: Int = 0) = EngineSessionSnapshot(7, 2, EngineSessionPhase.ACTIVE,
        EngineViewport(1, 1), SourceAnchor(page, 0), 3, 4, pending,
        emptyList(), emptySet(), emptySet(), false)
    private fun receipt(sequence: Long, deferred: Boolean = false) = InputReceipt(
        InputSample(sequence, 1, 10, 1024), 11, if (deferred) null else 12,
        if (deferred) 0 else 1024, if (deferred) InputOutcome.DEFERRED else InputOutcome.APPLIED, 3)

    @Test fun deferredAndResolvedReceiptsRetainOrderAndSessionIdentity() {
        val journal = EngineInputObservations(2)
        journal.record(state(1), listOf(receipt(1, true)))
        journal.record(state(), listOf(receipt(1)))
        val batch = journal.since(0)
        assertEquals(listOf(1L, 2L), batch.observations.map { it.ordinal })
        assertEquals(listOf(InputOutcome.DEFERRED, InputOutcome.APPLIED), batch.observations.map { it.receipt.outcome })
        assertEquals(listOf(1, 0), batch.observations.map { it.pendingInputCount })
        assertTrue(batch.observations.all { it.sessionId == 7L && it.generation == 2L && it.anchor?.pageId == page })
        assertEquals(0L, batch.lostCount)
    }

    @Test fun overwrittenEvidenceIsExplicitAndReadersCannotClearTheJournal() {
        val journal = EngineInputObservations(2)
        assertEquals(0L, journal.since(0).latestOrdinal)
        journal.record(state(), listOf(receipt(1), receipt(2), receipt(3)))
        val batch = journal.since(0)
        assertEquals(1L, batch.lostCount)
        assertEquals(2L, batch.firstRetainedOrdinal)
        assertEquals(3L, batch.latestOrdinal)
        assertEquals(0L, journal.since(1).lostCount)
        assertTrue(journal.since(3).observations.isEmpty())
        (batch.observations as MutableList).clear()
        assertEquals(2, journal.since(1).observations.size)
        assertThrows(IllegalArgumentException::class.java) { journal.since(4) }
        assertThrows(IllegalArgumentException::class.java) { journal.since(-1) }
    }

    private fun wire(): ByteArray = ByteBuffer.allocate(132).order(ByteOrder.LITTLE_ENDIAN).apply {
        longArrayOf(EngineReadbackPacket.MAGIC, 1, 1, 7, 1, 1, 4, 4, 1, 0, 1, 10, 20, 15, 4, 0)
            .forEach { putLong(it) }
        put(byteArrayOf(1, 2, 3, -1))
    }.array()

    @Test fun closeProofUsesIndependentSessionAndInputCountsEvenAfterOverwrite() {
        val journal = EngineInputObservations(2)
        journal.record(state(), listOf(receipt(1), receipt(2), receipt(3)))
        assertNull(journal.closeProof())
        val closed = state().copy(phase = EngineSessionPhase.CLOSED, inputRevision = 3)
        assertThrows(IllegalStateException::class.java) { journal.seal(closed, 2, 100) }
        journal.seal(closed, 3, 100)
        assertEquals(3L, journal.closeProof()?.receivedInputCount)
        assertEquals(3L, journal.closeProof()?.observationCount)
        assertEquals(2, journal.since(0).observations.size)
        assertThrows(IllegalStateException::class.java) { journal.record(closed, listOf(receipt(4))) }
    }

    @Test fun nativeBytesSurviveInputAndExportBufferMutations() {
        val original = wire()
        val parsed = EngineReadbackPacket.parse(original)
        original.fill(0)
        val exported = parsed.nativePacketBytes()
        assertArrayEquals(wire(), exported)
        exported.fill(0)
        assertArrayEquals(wire(), parsed.nativePacketBytes())
    }

    @Test fun copiedPacketAndChangedPixelsCannotMasqueradeAsOriginalNativeBytes() {
        val parsed = EngineReadbackPacket.parse(wire())
        assertThrows(IllegalStateException::class.java) { parsed.copy(token = 5).nativePacketBytes() }
        parsed.rgbaBytes[0] = 99
        assertThrows(IllegalStateException::class.java) { parsed.nativePacketBytes() }
    }
}
