package ml.melun.mangaview.engine.api

data class EngineDrawQuad(val texture: EngineTexture, val topScreenUnits: Long, val bottomScreenUnits: Long) {
    init { require(bottomScreenUnits > topScreenUnits) }
}

/** Coordinates retain 1/1024-pixel precision until the platform rasterizer. */
data class EngineDrawScene(
    val session: EngineSessionSnapshot,
    val quads: List<EngineDrawQuad>,
    val completeCoverage: Boolean,
)
