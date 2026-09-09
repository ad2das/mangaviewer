package ml.melun.mangaview.viewer.runtime

import android.os.Trace
import java.util.concurrent.atomic.AtomicBoolean
import ml.melun.mangaview.engine.api.EngineImageDecoder
import ml.melun.mangaview.engine.api.EnginePixels
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.StoredPage

/** NDK adapter; dispatcher delivery and file/pixel lifetimes belong to EngineTileWork. */
internal class NativeEngineImageDecoder : EngineImageDecoder {
    override suspend fun decode(page: StoredPage, tile: EngineTileSpec): EnginePixels {
        require(page.pageId == tile.pageId && page.contentRevision == tile.contentRevision &&
            page.sha256 == tile.sha256 && page.dimensions == tile.dimensions)
        val handle = traceEngineWork("engine_decode") {
            val tracing = Trace.isEnabled()
            if (tracing) Trace.beginSection("decode_tile:${tile.sha256.take(12)}:" +
                "${tile.dimensions.widthPx}x${tile.dimensions.heightPx}:" +
                "${tile.sourceTop}:${tile.sourceBottom}:${tile.displayWidth}")
            try {
                NativeCpuDecodeBridge.nativeDecode(page.file.absolutePath, tile.dimensions.widthPx,
                    tile.dimensions.heightPx, tile.sourceTop, tile.sourceBottom, tile.displayWidth)
            } finally { if (tracing) Trace.endSection() }
        }
        check(handle != 0L) { "Native original-image decode failed" }
        try {
            val bytes = NativeCpuDecodeBridge.nativeByteCount(handle)
            check(bytes == tile.byteCount) { "Native raster crop does not match its allocation contract" }
            return NativeEnginePixels(tile, handle, bytes)
        } catch (failure: Throwable) {
            NativeCpuDecodeBridge.nativeRelease(handle)
            throw failure
        }
    }
}

internal class NativeEnginePixels(
    override val tile: EngineTileSpec,
    internal val handle: Long,
    override val byteCount: Long,
) : EnginePixels {
    private val closed = AtomicBoolean(false)
    internal val isClosed: Boolean get() = closed.get()

    override fun close() {
        check(closed.compareAndSet(false, true)) { "Native pixels were released twice" }
        NativeCpuDecodeBridge.nativeRelease(handle)
    }
}
