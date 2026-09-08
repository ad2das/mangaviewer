package ml.melun.mangaview.engine.session

import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineSessionSnapshot

/** Owner-thread acknowledgement state, independent of input acceptance and geometry availability. */
internal class SessionViewportReadiness {
    private var enabled = false
    var held = false
        private set
    var revision = 0L
        private set

    fun engage() { enabled = true; held = true }
    fun invalidate() { if (enabled) held = true }
    fun moved(consumed: BigRational) {
        if (!consumed.isZero()) {
            revision++
            if (enabled) held = true
        }
    }

    fun release(presented: EngineSessionSnapshot, current: EngineSessionSnapshot): Boolean {
        if (!enabled || !held || current.phase == EngineSessionPhase.CLOSED) return false
        if (presented.sessionId != current.sessionId || presented.generation != current.generation ||
            presented.geometryRevision != current.geometryRevision || presented.anchor != current.anchor ||
            presented.movementRevision != revision || presented.viewport != current.viewport ||
            !presented.completeViewport) return false
        held = false
        return true
    }
}
