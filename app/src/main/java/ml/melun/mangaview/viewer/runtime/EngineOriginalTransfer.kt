package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.EnginePixels
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUpload

/** The caller retains its CPU borrow until upload/close; no per-original display buffer. */
internal fun prepareOriginalUpload(pixels: EnginePixels, limit: Long,
    upload: suspend (NativeEnginePixels, Long, Long) -> EngineTexture,
): EngineTextureUpload {
    val nativePixels = pixels as? NativeEnginePixels ?: error("Native owner requires native pixels")
    require(pixels.byteCount <= limit) { "An original exceeds the allocation limit" }
    check(!nativePixels.isClosed)
    return PreparedOriginalUpload(nativePixels, upload)
}

private class PreparedOriginalUpload(private val pixels: NativeEnginePixels,
    private val submit: suspend (NativeEnginePixels, Long, Long) -> EngineTexture,
) : EngineTextureUpload {
    private var closed = false
    override suspend fun upload(expectedEpoch: Long): EngineTexture {
        check(!closed)
        return submit(pixels, pixels.handle, expectedEpoch)
    }
    override suspend fun close() { closed = true }
}

internal suspend fun uploadOriginal(pixels: EnginePixels, limit: Long, epoch: Long,
    submit: suspend (NativeEnginePixels, Long, Long) -> EngineTexture,
): EngineTexture {
    val transfer = prepareOriginalUpload(pixels, limit, submit)
    try { return transfer.upload(epoch) }
    finally { withContext(NonCancellable) { transfer.close() } }
}

internal fun readEngineTextureOwnership(native: Long): EngineTextureOwnership {
    val values = OwnedRendererBridge.nativeTextureCounts(native)
    check(values.size == 5 && values.all { it >= 0 })
    return EngineTextureOwnership(values[0], values[1], values[2], values[3], values[4])
}
