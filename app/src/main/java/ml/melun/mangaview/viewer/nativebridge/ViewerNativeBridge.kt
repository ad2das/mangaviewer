package ml.melun.mangaview.viewer.nativebridge

import android.hardware.HardwareBuffer

internal object ViewerNativeBridge {
    init {
        System.loadLibrary("viewer_native")
    }

    fun load() = Unit

    external fun nativeAllocateTile(width: Int, height: Int): Long
    external fun nativeTileAllocationBytes(handle: Long): Long
    external fun nativeReleaseTile(handle: Long)
    external fun nativePublishTile(handle: Long, contentVersion: Long): Boolean

    external fun nativeDecodeFileBand(
        encodedPath: String,
        handle: Long,
        contentVersion: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceTop: Int,
        sourceBottom: Int,
        displayWidth: Int,
    ): Boolean

    external fun nativeReadPublishedTilePixelArgb(
        handle: Long,
        contentVersion: Long,
        x: Int,
        y: Int,
    ): Long

    external fun nativePublishedTileHardwareBuffer(
        handle: Long,
        contentVersion: Long,
    ): HardwareBuffer?

}
