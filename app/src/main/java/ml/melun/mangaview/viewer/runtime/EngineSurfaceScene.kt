package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineDrawScene
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.FrameIdentity
import ml.melun.mangaview.engine.api.SourceAnchor

internal data class EngineTexturePlacement(val texture: EngineTexture, val topPx: Int, val bottomPx: Int) {
    init { require(bottomPx > topPx) }
}

internal data class EngineSurfaceScene(
    val sessionId: Long,
    val generation: Long,
    val inputRevision: Long,
    val geometryRevision: Long,
    val viewport: EngineViewport,
    val anchor: SourceAnchor?,
    val placements: List<EngineTexturePlacement>,
    val coordinateUnitsPerPixel: Int = 1,
    val completeCoverage: Boolean = false,
) {
    init {
        require(sessionId > 0 && generation > 0 && inputRevision >= 0 && geometryRevision >= 0)
        require(placements.size <= 128)
        require(placements.all { it.texture.tile.displayWidth == viewport.widthPx })
        require(coordinateUnitsPerPixel == 1 || coordinateUnitsPerPixel == 1024)
    }

    fun pack(): IntArray = IntArray(placements.size * 7).also { packed ->
        placements.forEachIndexed { index, placement ->
            val texture = placement.texture
            val at = index * 7
            packed[at] = texture.key.toInt()
            packed[at + 1] = (texture.key ushr 32).toInt()
            packed[at + 2] = texture.tile.sourceTop
            packed[at + 3] = texture.tile.sourceBottom
            packed[at + 4] = texture.tile.dimensions.heightPx
            packed[at + 5] = placement.topPx
            packed[at + 6] = placement.bottomPx
        }
    }

    companion object {
        fun from(scene: EngineDrawScene): EngineSurfaceScene = EngineSurfaceScene(
            scene.session.sessionId, scene.session.generation, scene.session.inputRevision,
            scene.session.geometryRevision, scene.session.viewport, scene.session.anchor,
            scene.quads.map { EngineTexturePlacement(it.texture, Math.toIntExact(it.topScreenUnits),
                Math.toIntExact(it.bottomScreenUnits)) }, 1024, scene.completeCoverage,
        )
    }
}

internal data class EngineSurfacePresentation(
    val identity: FrameIdentity,
    val scene: EngineSurfaceScene,
    val submittedAtNanos: Long,
    val renderLatencyNanos: Long,
    val swapSucceeded: Boolean,
    val timestampKind: PresentationTimestampKind,
    val timestampNanos: Long,
    val eglFrameId: Long,
    val rendererId: Long,
)

/** Native texture allocations only; transient upload/readback buffers and measured PSS are separate. */
internal data class EngineTextureOwnership(
    val textures: Long,
    val bytes: Long,
    val retiringTextures: Long,
    val retiringBytes: Long,
    val sceneEntries: Long,
)
