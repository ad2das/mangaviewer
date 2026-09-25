package ml.melun.mangaview.engine.session

import ml.melun.mangaview.engine.api.EngineSessionPhase

internal fun isReadyForInput(
    phase: EngineSessionPhase,
    positionResolved: Boolean,
    anchor: AnchorState?,
): Boolean = phase == EngineSessionPhase.ACTIVE && positionResolved && anchor != null

internal fun readinessBlocker(
    positionResolved: Boolean,
    geometry: DocumentGeometry,
): GeometryBlocker? {
    if (!positionResolved) return null
    if (!geometry.manifests.containsKey(geometry.targetEpisodeId)) {
        return GeometryBlocker.Episode(geometry.targetEpisodeId)
    }
    return geometry.requirementsForAnchor().let { requirements ->
        requirements.dimensions.firstOrNull()?.let(GeometryBlocker::Dimension)
            ?: requirements.episodes.firstOrNull()?.let(GeometryBlocker::Episode)
    }
}
