package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.EnginePixels
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUpload
import ml.melun.mangaview.engine.api.WorkPriority

/** The caller retains its CPU borrow until upload/close; no per-original display buffer. */
internal fun prepareOriginalUpload(pixels: EnginePixels, limit: Long,
    reserve: suspend (Long, StateFlow<WorkPriority>) -> UploadCapacityReservations.Reservation,
    upload: suspend (NativeEnginePixels, Long, Long) -> EngineTexture,
): EngineTextureUpload {
    val nativePixels = pixels as? NativeEnginePixels ?: error("Native owner requires native pixels")
    require(pixels.byteCount <= limit) { "An original exceeds the allocation limit" }
    check(!nativePixels.isClosed)
    return PreparedOriginalUpload(nativePixels, reserve, upload)
}

private class PreparedOriginalUpload(
    private val pixels: NativeEnginePixels,
    private val reserve: suspend (Long, StateFlow<WorkPriority>) -> UploadCapacityReservations.Reservation,
    private val submit: suspend (NativeEnginePixels, Long, Long) -> EngineTexture,
) : EngineTextureUpload {
    private var closed = false
    private var reservation: UploadCapacityReservations.Reservation? = null

    /** Capacity is waited for here, before the caller serializes on the shared upload permit. */
    override suspend fun awaitCapacity(priority: StateFlow<WorkPriority>) {
        check(!closed)
        if (reservation == null) reservation = reserve(pixels.byteCount, priority)
    }

    override suspend fun upload(expectedEpoch: Long): EngineTexture {
        check(!closed)
        val texture = submit(pixels, pixels.handle, expectedEpoch)
        // The native upload already synced `used`; this settle double-counts for one pass, conservatively.
        reservation?.commit()
        reservation = null
        return texture
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        reservation?.release()
        reservation = null
    }
}

internal suspend fun uploadOriginal(pixels: EnginePixels, limit: Long, epoch: Long,
    reserve: suspend (Long, StateFlow<WorkPriority>) -> UploadCapacityReservations.Reservation,
    submit: suspend (NativeEnginePixels, Long, Long) -> EngineTexture,
): EngineTexture {
    val transfer = prepareOriginalUpload(pixels, limit, reserve, submit)
    try {
        // A direct upload has a fixed priority; no promotion can ever reach this transfer.
        transfer.awaitCapacity(MutableStateFlow(WorkPriority.VISIBLE))
        return transfer.upload(epoch)
    } finally {
        withContext(NonCancellable) { transfer.close() }
    }
}

internal fun readEngineTextureOwnership(native: Long): EngineTextureOwnership {
    val values = OwnedRendererBridge.nativeTextureCounts(native)
    check(values.size == 5 && values.all { it >= 0 })
    return EngineTextureOwnership(values[0], values[1], values[2], values[3], values[4])
}
