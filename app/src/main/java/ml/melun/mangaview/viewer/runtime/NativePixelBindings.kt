package ml.melun.mangaview.viewer.runtime

import ml.melun.mangaview.viewer.nativebridge.ViewerNativeBridge

internal interface NativePixelBindings {
    fun allocate(width: Int, height: Int): Long

    fun allocationBytes(handle: Long): Long

    fun release(handle: Long)

    fun decodeBand(
        encodedPath: String,
        handle: Long,
        contentVersion: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceTop: Int,
        sourceBottom: Int,
        displayWidth: Int,
    ): Boolean

    fun publish(handle: Long, contentVersion: Long): Boolean
}

internal object JniNativePixelBindings : NativePixelBindings {
    override fun allocate(width: Int, height: Int): Long =
        ViewerNativeBridge.nativeAllocateTile(width, height)

    override fun allocationBytes(handle: Long): Long =
        ViewerNativeBridge.nativeTileAllocationBytes(handle)

    override fun release(handle: Long) {
        ViewerNativeBridge.nativeReleaseTile(handle)
    }

    override fun decodeBand(
        encodedPath: String,
        handle: Long,
        contentVersion: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceTop: Int,
        sourceBottom: Int,
        displayWidth: Int,
    ): Boolean = ViewerNativeBridge.nativeDecodeFileBand(
        encodedPath,
        handle,
        contentVersion,
        sourceWidth,
        sourceHeight,
        sourceTop,
        sourceBottom,
        displayWidth,
    )

    override fun publish(handle: Long, contentVersion: Long): Boolean =
        ViewerNativeBridge.nativePublishTile(handle, contentVersion)
}
