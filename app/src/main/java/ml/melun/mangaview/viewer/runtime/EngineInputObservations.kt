package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.InputReceipt
import ml.melun.mangaview.engine.api.SourceAnchor

internal data class EngineInputObservation(
    val ordinal: Long,
    val sessionId: Long,
    val generation: Long,
    val inputRevision: Long,
    val geometryRevision: Long,
    val anchor: SourceAnchor?,
    val pendingInputCount: Int,
    val receipt: InputReceipt,
)

internal data class EngineInputObservationBatch(
    val observations: List<EngineInputObservation>,
    val latestOrdinal: Long,
    val firstRetainedOrdinal: Long,
    val lostCount: Long,
)

internal data class EngineInputCloseProof(
    val sessionId: Long,
    val generation: Long,
    val inputRevision: Long,
    val receivedInputCount: Long,
    val observationCount: Long,
    val closedAtNanos: Long,
)

/** Bounded value-only observations. Overwritten evidence is explicitly reported to readers. */
internal class EngineInputObservations(private val capacity: Int = 512) {
    init { require(capacity > 0) }
    private val entries = ArrayDeque<EngineInputObservation>()
    private var latest = 0L
    private var closed: EngineInputCloseProof? = null

    @Synchronized fun record(state: EngineSessionSnapshot, receipts: List<InputReceipt>) {
        check(closed == null) { "Input history is already sealed" }
        receipts.forEach { receipt ->
            latest = Math.incrementExact(latest)
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(EngineInputObservation(latest, state.sessionId, state.generation,
                state.inputRevision, state.geometryRevision, state.anchor, state.pendingInputCount, receipt))
        }
    }

    /** Called after content, graphics, persistence and native owners have all closed successfully. */
    @Synchronized fun seal(state: EngineSessionSnapshot, receivedInputCount: Long, atNanos: Long) {
        check(closed == null && state.phase == EngineSessionPhase.CLOSED && state.pendingInputCount == 0)
        check(receivedInputCount >= 0 && state.inputRevision == receivedInputCount && atNanos > 0)
        closed = EngineInputCloseProof(state.sessionId, state.generation, state.inputRevision,
            receivedInputCount, latest, atNanos)
    }

    @Synchronized fun closeProof(): EngineInputCloseProof? = closed

    @Synchronized fun since(afterOrdinal: Long): EngineInputObservationBatch {
        require(afterOrdinal in 0..latest) { "Input observation cursor is outside the journal" }
        val first = entries.firstOrNull()?.ordinal ?: 1L
        return EngineInputObservationBatch(entries.filter { it.ordinal > afterOrdinal }, latest, first,
            (first - 1L - afterOrdinal).coerceAtLeast(0L))
    }
}
