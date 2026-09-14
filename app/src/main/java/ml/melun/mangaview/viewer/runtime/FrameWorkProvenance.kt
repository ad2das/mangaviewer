package ml.melun.mangaview.viewer.runtime

import android.os.Trace
import java.util.concurrent.atomic.AtomicBoolean
import ml.melun.mangaview.engine.api.EngineDrawScene
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.FrameIdentity
import ml.melun.mangaview.engine.api.FrameWorkObserver

/** Stable origin kinds for the trace schema; values are persisted, never renumbered. */
internal object FrameOriginKind {
    const val MISSING = 0
    const val CHOREOGRAPHER = 1
    const val WORK_RESULT = FrameWorkObserver.WORK_RESULT
    const val SNAPSHOT_UPDATE = FrameWorkObserver.SNAPSHOT_UPDATE
    const val RECOVERY = 4
    const val INPUT_DISPATCH = 5
}

/**
 * Sentinel vsync id for input applied at MotionEvent dispatch entry: there is no frame-timeline
 * vsync id and no expected presentation time. It must never be confused with the legacy
 * Choreographer fallback id (-1), which stays [FrameOriginKind.CHOREOGRAPHER].
 */
internal const val NO_VSYNC_ID = Long.MIN_VALUE

/** One resolved provenance answer for exactly one submitted token. */
internal data class FrameOrigin(
    val kind: Int,
    val atNanos: Long,
    val vsyncId: Long,
    val sessionId: Long,
    val inputRevision: Long,
    val movementRevision: Long,
)

/**
 * Immutable per-offer provenance snapshot. The ledger's mutable state can advance (newer inputs,
 * newer work triggers, recovery) while this ticket is in flight; resolution always answers from
 * this snapshot, never from current main-thread state. One-shot: the first successful submission
 * of the token that carries it consumes it.
 */
internal class FrameWorkTicket internal constructor(
    private val ledger: FrameWorkProvenanceLedger,
    internal val sessionId: Long,
    internal val inputRevision: Long,
    internal val movementRevision: Long,
    internal val inputOrigin: FrameOrigin?,
    internal val freshKind: Int,
    internal val freshAtNanos: Long,
) {
    private val consumed = AtomicBoolean(false)

    /** Called on the GL owner immediately after a successful native submit. */
    fun resolve(identity: FrameIdentity, rendererId: Long) {
        if (consumed.compareAndSet(false, true)) ledger.resolve(this, identity, rendererId)
    }
}

/**
 * Trace-only origin ledger for submitted frames.
 *
 * An input origin is armed before the synchronous `content.input` dispatch and bound with the
 * exact session/input/movement revisions the dispatch produced. Every scene offer snapshots its
 * candidates into an immutable [FrameWorkTicket] (`ticketForOffer`): the bound input origin when
 * the offered revisions match it, plus the work trigger that was current at offer time as that
 * ticket's own exclusive fallback. The ticket travels inside the scene sidecar through `latest`
 * replacement, GL submission, and `Pending`, and resolves on the GL owner at submit time. Once an
 * input is represented by a submitted token, later tickets resolve to their own saved fresh
 * trigger, never to a globally newer timestamp. Disabled tracing creates no tickets, stores no
 * state, and formats no names.
 */
internal class FrameWorkProvenanceLedger(
    private val enabled: () -> Boolean = { Trace.isEnabled() },
    private val emit: (String) -> Unit = { name -> Trace.beginSection(name); Trace.endSection() },
) {
    private data class Armed(
        val vsyncId: Long,
        val expectedPresentationNanos: Long,
        val frameTimeNanos: Long,
        val sequence: Long,
        val baseSessionId: Long,
        val baseInputRevision: Long,
        val baseMovementRevision: Long,
    )

    private var armed: Armed? = null
    private var outstanding: FrameOrigin? = null
    private var freshKind = 0
    private var freshAtNanos = 0L
    private val represented = ArrayDeque<Triple<Long, Long, Long>>()

    val isTracking: Boolean get() = enabled()

    /** Armed by the input frame before `content.input`; stores the pre-dispatch revisions. */
    @Synchronized
    fun armInputFrame(vsyncId: Long, expectedPresentationNanos: Long, frameTimeNanos: Long, sequence: Long,
        baseSessionId: Long, baseInputRevision: Long, baseMovementRevision: Long) {
        if (!enabled()) return
        armed = Armed(vsyncId, expectedPresentationNanos, frameTimeNanos, sequence, baseSessionId,
            baseInputRevision, baseMovementRevision)
    }

    /** Bound by the synchronous `onContent` snapshot before the scene offer reaches the renderer. */
    @Synchronized
    fun bindInputFrame(sessionId: Long, inputRevision: Long, movementRevision: Long) {
        val pending = armed ?: return
        if (!enabled()) { armed = null; return }
        if (sessionId != pending.baseSessionId) { armed = null; return }
        if (movementRevision == pending.baseMovementRevision) return
        armed = null
        val kind = if (pending.vsyncId == NO_VSYNC_ID && pending.expectedPresentationNanos == 0L) {
            FrameOriginKind.INPUT_DISPATCH
        } else {
            FrameOriginKind.CHOREOGRAPHER
        }
        val origin = FrameOrigin(kind, pending.frameTimeNanos, pending.vsyncId,
            sessionId, inputRevision, movementRevision)
        outstanding?.let { previous ->
            if (previous.inputRevision != origin.inputRevision || previous.movementRevision != origin.movementRevision) {
                emit(supersededTraceName(previous.sessionId, previous.inputRevision, previous.movementRevision,
                    origin.inputRevision, origin.movementRevision))
            }
        }
        outstanding = origin
    }

    /**
     * Drops the CURRENT unbound arm when the dispatch was not deferred, regardless of any older
     * outstanding origin; a clamped input must never be re-bound by a later image offer. A
     * deferred receipt keeps the arm for the later replay resolution instead.
     */
    @Synchronized
    fun finishInputFrame(deferred: Boolean) {
        if (!deferred) armed = null
    }

    /** Fresh non-input trigger from the engine's scheduling observer. */
    @Synchronized
    fun noteWorkTrigger(kind: Int, atNanos: Long) {
        if (!enabled()) return
        freshKind = kind
        freshAtNanos = atNanos
    }

    /** Recovery/lifecycle clears every previous origin and seeds a fresh one. */
    @Synchronized
    fun noteRecovery(atNanos: Long) {
        armed = null
        outstanding = null
        represented.clear()
        val tracing = enabled()
        freshKind = if (tracing) FrameOriginKind.RECOVERY else 0
        freshAtNanos = if (tracing) atNanos else 0L
    }

    @Synchronized
    fun clear() {
        armed = null
        outstanding = null
        represented.clear()
        freshKind = 0
        freshAtNanos = 0L
    }

    /** Snapshots this offer's immutable provenance; `null` when tracing is off (no allocation). */
    @Synchronized
    fun ticketForOffer(session: EngineSessionSnapshot): FrameWorkTicket? =
        ticketForOffer(session.sessionId, session.inputRevision, session.movementRevision)

    @Synchronized
    fun ticketForOffer(sessionId: Long, inputRevision: Long, movementRevision: Long): FrameWorkTicket? {
        if (!enabled()) return null
        val input = outstanding?.takeIf {
            it.sessionId == sessionId && it.inputRevision == inputRevision &&
                it.movementRevision == movementRevision
        }
        val ticket = FrameWorkTicket(this, sessionId, inputRevision, movementRevision, input, freshKind, freshAtNanos)
        freshKind = 0
        freshAtNanos = 0L
        return ticket
    }

    @Synchronized
    internal fun resolve(ticket: FrameWorkTicket, identity: FrameIdentity, rendererId: Long) {
        val input = ticket.inputOrigin
        val alreadyRepresented = input != null && represented.any {
            it.first == input.sessionId && it.second == input.inputRevision && it.third == input.movementRevision
        }
        val origin = when {
            input != null && !alreadyRepresented -> {
                represented.addLast(Triple(input.sessionId, input.inputRevision, input.movementRevision))
                if (represented.size > REPRESENTED_LIMIT) represented.removeFirst()
                if (outstanding?.let {
                    it.sessionId == input.sessionId && it.inputRevision == input.inputRevision &&
                        it.movementRevision == input.movementRevision
                } == true) outstanding = null
                input
            }
            ticket.freshAtNanos != 0L -> FrameOrigin(ticket.freshKind, ticket.freshAtNanos, 0L,
                ticket.sessionId, ticket.inputRevision, ticket.movementRevision)
            else -> FrameOrigin(FrameOriginKind.MISSING, 0L, 0L, ticket.sessionId, ticket.inputRevision,
                ticket.movementRevision)
        }
        if (enabled()) {
            emit(frameOriginTraceName(identity, rendererId))
            emit(frameOriginAtTraceName(origin))
        }
    }

    private companion object {
        /** Bounded history of inputs already represented by a submitted token. */
        const val REPRESENTED_LIMIT = 16
    }
}

/** Attaches this offer's ticket as an opaque scene sidecar; no copy and no ticket when off. */
internal fun FrameWorkProvenanceLedger.attachTicket(scene: EngineDrawScene): EngineDrawScene =
    ticketForOffer(scene.session)?.let { scene.copy(diagnostics = it) } ?: scene

/** engine_frame_origin:sessionId:rendererId:rendererEpoch:surfaceEpoch:token (hex). */
internal fun frameOriginTraceName(identity: FrameIdentity, rendererId: Long): String =
    "engine_frame_origin:" + identity.sessionId.hex() + ":" + rendererId.hex() + ":" +
        identity.rendererEpoch.hex() + ":" + identity.surfaceEpoch.hex() + ":" + identity.token.hex()

/** engine_frame_origin_at:kind:atNanos:vsyncId:inputRevision:movementRevision (hex). */
internal fun frameOriginAtTraceName(origin: FrameOrigin): String =
    "engine_frame_origin_at:" + origin.kind.hex() + ":" + origin.atNanos.hex() + ":" + origin.vsyncId.hex() +
        ":" + origin.inputRevision.hex() + ":" + origin.movementRevision.hex()

/** engine_frame_origin_superseded:sessionId:oldInputRev:oldMovementRev:newInputRev:newMovementRev (hex). */
internal fun supersededTraceName(sessionId: Long, oldInputRevision: Long, oldMovementRevision: Long,
    newInputRevision: Long, newMovementRevision: Long): String =
    "engine_frame_origin_superseded:" + sessionId.hex() + ":" + oldInputRevision.hex() + ":" +
        oldMovementRevision.hex() + ":" + newInputRevision.hex() + ":" + newMovementRevision.hex()

private fun Long.hex(): String = toULong().toString(16)
private fun Int.hex(): String = toUInt().toString(16)
