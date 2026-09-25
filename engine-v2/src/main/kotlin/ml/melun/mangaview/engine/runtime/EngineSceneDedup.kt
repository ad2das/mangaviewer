package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.engine.api.EngineDrawScene

/**
 * Before the first attachment, disabled metadata updates have no old pixels to clear.
 * Keep the first buffer for actual content; later disabling must still retire partial scenes.
 */
internal fun shouldSubmitScene(
    enabled: Boolean,
    hasSubmittedScene: Boolean,
    displayed: EngineDrawScene?,
    next: EngineDrawScene,
): Boolean = (enabled || hasSubmittedScene) && !sameSubmittedViewport(displayed, next)

internal fun sameSubmittedViewport(previous: EngineDrawScene?, next: EngineDrawScene): Boolean {
    if (previous == null || !previous.completeCoverage || !next.completeCoverage) return false
    val before = previous.session
    val after = next.session
    return before.sessionId == after.sessionId && before.generation == after.generation &&
        before.inputRevision == after.inputRevision && before.movementRevision == after.movementRevision &&
        before.viewport == after.viewport && before.anchor == after.anchor &&
        before.visibleRegions == after.visibleRegions && previous.quads == next.quads
}
