package ml.melun.mangaview.viewer.runtime

import java.util.concurrent.atomic.AtomicBoolean
import ml.melun.mangaview.content.CpuTileLease
import ml.melun.mangaview.content.DecodeRequest
import ml.melun.mangaview.content.ImageDecodePort
import ml.melun.mangaview.core.PageId

internal object NativeCpuDecodeBridge {
    init {
        System.loadLibrary("viewer_native")
    }

    external fun nativeDecode(
        encodedPath: String,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceTop: Int,
        sourceBottom: Int,
        displayWidth: Int,
    ): Long

    external fun nativeByteCount(handle: Long): Long
    external fun nativeRelease(handle: Long)
}

internal class NativeCpuDecodePort : ImageDecodePort {
    override suspend fun decode(request: DecodeRequest): CpuTileLease {
        require(request.page.id == request.encoded.pageId) { "Encoded image belongs to another page" }
        val rows = request.sourceRange
        val handle = NativeCpuDecodeBridge.nativeDecode(
            request.encoded.path,
            request.dimensions.widthPx,
            request.dimensions.heightPx,
            rows.top,
            rows.bottomExclusive,
            request.displayWidthPx,
        )
        check(handle != 0L) { "Native CPU decode failed for ${request.page.id}" }
        return NativeCpuTileLease(
            request.page.id,
            rows.top,
            rows.bottomExclusive,
            request.dimensions.heightPx,
            handle,
            NativeCpuDecodeBridge.nativeByteCount(handle),
            request.displayWidthPx,
        )
    }

}

internal class NativeCpuTileLease(
    override val pageId: PageId,
    override val sourceTopPx: Int,
    override val sourceBottomPx: Int,
    override val sourceHeightPx: Int,
    internal val nativeHandle: Long,
    override val byteCount: Long,
    internal val displayWidthPx: Int,
) : CpuTileLease {
    private val closed = AtomicBoolean(false)

    init {
        require(nativeHandle != 0L)
        require(byteCount > 0L)
        require(displayWidthPx > 0 && byteCount % (displayWidthPx * 4L) == 0L)
    }

    internal val displayHeightPx: Int
        get() = Math.toIntExact(byteCount / (displayWidthPx * 4L))

    override fun close() {
        check(closed.compareAndSet(false, true)) { "CPU tile closed more than once" }
        NativeCpuDecodeBridge.nativeRelease(nativeHandle)
    }
}
