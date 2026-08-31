package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import ml.melun.mangaview.viewer.nativebridge.ViewerNativeBridge

/** Immutable-after-publication GPU-sampled tiles, imported once and reused by HWUI. */
internal class HardwareTileStore : NativePixelBindings {
    private data class Entry(
        val version: Long,
        val buffer: HardwareBuffer,
        val bitmap: Bitmap,
    )

    private val lock = Any()
    private val entries = mutableMapOf<Long, Entry>()

    override fun allocate(width: Int, height: Int): Long =
        JniNativePixelBindings.allocate(width, height)

    override fun allocationBytes(handle: Long): Long =
        JniNativePixelBindings.allocationBytes(handle)

    override fun decodeBand(
        encodedPath: String,
        handle: Long,
        contentVersion: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceTop: Int,
        sourceBottom: Int,
        displayWidth: Int,
    ): Boolean = JniNativePixelBindings.decodeBand(
        encodedPath,
        handle,
        contentVersion,
        sourceWidth,
        sourceHeight,
        sourceTop,
        sourceBottom,
        displayWidth,
    )

    override fun publish(handle: Long, contentVersion: Long): Boolean {
        if (!JniNativePixelBindings.publish(handle, contentVersion)) return false
        val buffer = ViewerNativeBridge.nativePublishedTileHardwareBuffer(handle, contentVersion)
            ?: return false
        val bitmap = Bitmap.wrapHardwareBuffer(buffer, SRGB)
        if (bitmap == null) {
            buffer.close()
            return false
        }
        val old = synchronized(lock) {
            entries.put(handle, Entry(contentVersion, buffer, bitmap))
        }
        old?.close()
        return true
    }

    override fun release(handle: Long) {
        synchronized(lock) { entries.remove(handle) }?.close()
        JniNativePixelBindings.release(handle)
    }

    fun bitmap(handle: Long, contentVersion: Long): Bitmap? = synchronized(lock) {
        entries[handle]?.takeIf { it.version == contentVersion }?.bitmap
    }

    private fun Entry.close() {
        bitmap.recycle()
        buffer.close()
    }

    private val SRGB = ColorSpace.get(ColorSpace.Named.SRGB)
}
