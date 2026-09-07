package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.content.CpuTileLease
import ml.melun.mangaview.content.TextureRef
import ml.melun.mangaview.content.TextureUploadPort

internal class OwnedTextureUploadPort(
    private val renderer: OwnedSurfaceRenderer,
) : TextureUploadPort {
    override suspend fun upload(rendererEpoch: Long, pixels: CpuTileLease): TextureRef {
        var rendererOwnsPixels = false
        var acquiredKey = 0L
        return try {
            val native = pixels as? NativeCpuTileLease
                ?: error("Owned renderer requires a native CPU tile")
            check(rendererEpoch == renderer.rendererEpoch) { "Renderer epoch changed before upload" }
            rendererOwnsPixels = true
            val key = renderer.upload(native)
            acquiredKey = key
            check(key > 0L) { "GPU tile upload failed" }
            check(rendererEpoch == renderer.rendererEpoch) { "Renderer epoch changed during upload" }
            val byteCount = native.byteCount
            check(byteCount > 0L) { "GPU texture accounting failed" }
            TextureRef(
                native.pageId,
                rendererEpoch,
                key,
                native.sourceTopPx,
                native.sourceBottomPx,
                native.sourceHeightPx,
                byteCount,
            ).also { acquiredKey = 0L }
        } finally {
            if (acquiredKey > 0L) renderer.release(acquiredKey)
            if (!rendererOwnsPixels) pixels.close()
        }
    }

    override fun release(texture: TextureRef) {
        renderer.release(texture.key)
    }
}
