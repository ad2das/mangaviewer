package ml.melun.mangaview.engine.api

import java.io.Closeable
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId

/** Source rows identify original content; raster rows identify the full-width decoded image crop. */
data class EngineTileSpec(
    val pageId: PageId,
    val contentRevision: String,
    val sha256: String,
    val dimensions: PageDimensions,
    val sourceTop: Int,
    val sourceBottom: Int,
    val displayWidth: Int,
) {
    init {
        require(contentRevision.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceTop >= 0 && sourceBottom > sourceTop && sourceBottom <= dimensions.heightPx)
        require(displayWidth > 0)
    }

    val rasterHeight: Int get() = Math.toIntExact((dimensions.heightPx.toLong() * displayWidth +
        dimensions.widthPx - 1L) / dimensions.widthPx)
    val rasterTop: Int get() = (sourceTop.toLong() * rasterHeight / dimensions.heightPx).toInt()
    val rasterBottom: Int get() = ((sourceBottom.toLong() * rasterHeight + dimensions.heightPx - 1L) /
        dimensions.heightPx).toInt()
    val decodedHeight: Int get() = rasterBottom - rasterTop
    val byteCount: Long get() = Math.multiplyExact(displayWidth.toLong() * decodedHeight, 4L)
}

/** Immutable native pixels, owned by the coordinator. Uploaders borrow them and never close them. */
interface EnginePixels : Closeable {
    val tile: EngineTileSpec
    val byteCount: Long
}

fun interface EngineImageDecoder {
    /** The caller holds a StoredPage work dependency until decoding and its cleanup finish. */
    suspend fun decode(page: StoredPage, tile: EngineTileSpec): EnginePixels
}

data class EngineTexture(
    val tile: EngineTileSpec,
    val rendererId: Long,
    val rendererEpoch: Long,
    val key: Long,
    val byteCount: Long,
) {
    init { require(rendererId > 0 && rendererEpoch > 0 && key > 0 && byteCount == tile.byteCount) }
}

interface EngineTextureUploader {
    val rendererId: Long
    val rendererEpoch: Long
    /** Cancellation returns only after the owner stops reading pixels and disposes any lost texture. */
    suspend fun upload(pixels: EnginePixels, expectedEpoch: Long): EngineTexture
    /** Returns after this texture is absent from the owner's allocations, including scene retention. */
    suspend fun release(texture: EngineTexture)
}
