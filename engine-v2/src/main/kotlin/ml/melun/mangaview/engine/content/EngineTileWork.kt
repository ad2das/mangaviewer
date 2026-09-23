package ml.melun.mangaview.engine.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.EngineImageDecoder
import ml.melun.mangaview.engine.api.EnginePixels
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUploader
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkContext
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.runtime.EngineStageProbe

/** Short-lived file/pixel borrows feed a separately owned GPU result. */
class EngineTileWork(
    decoder: EngineImageDecoder,
    decodingDispatchers: (WorkPriority) -> CoroutineDispatcher,
    private val uploader: EngineTextureUploader,
) {
    /** Single-lane construction: every priority decodes on the same dispatcher. */
    constructor(decoder: EngineImageDecoder, decodingDispatcher: CoroutineDispatcher, uploader: EngineTextureUploader)
        : this(decoder, { decodingDispatcher }, uploader)

    private val pixels = EnginePixelWork(decoder, decodingDispatchers)

    fun request(page: WorkRequest<StoredPage>, tile: EngineTileSpec, priority: WorkPriority): WorkRequest<EngineTexture> {
        val epoch = uploader.rendererEpoch
        val revision = EnginePixelWork.revision(page, tile)
        val gpuResource = "${uploader.rendererId}:${page.key.resource}"
        val gpuRevision = "$epoch:$revision"
        val uploadKey = WorkKey(page.key.principal, gpuResource, "graphics.upload", gpuRevision, EngineTexture::class.java)
        val resultKey = uploadKey.copy(operation = "graphics.texture")
        // A tile's residency is what the reader feels, so the tile owns the texture and releases it
        // when its own result retires. Both the upload and the decode used to be separate records
        // whose only remaining effect was an extra admission, dispatch and resume per tile, on a
        // chain whose non-decode half is mostly those handoffs: measured on the GPU AVD, a steady
        // read-ahead tile spent 0.05ms reaching the upload record and 0.78ms after the native upload
        // finished, and removing the decode record was worth ~1.5ms on wfwf's read-ahead median.
        return WorkRequest(resultKey, WorkDomain.CONTROL, priority, authEpoch = page.authEpoch,
            dispose = { uploader.release(it) },
            execute = { parent ->
                val startedAtNanos = System.nanoTime()
                EngineStageProbe.record(tile as Any, EngineStageProbe.WORK_ENTER, startedAtNanos)
                parent.useDependency(page) { stored ->
                    EngineStageProbe.record(tile as Any, EngineStageProbe.PAGE_READY, System.nanoTime())
                    validate(stored, tile)
                    // A prepared prediction registers this tile's pixels before the viewer wants it,
                    // and the tile borrows that record: that is the point of the pre-decode, and it
                    // keeps the opening band's first decode off the demand path. When nothing has
                    // claimed the pixels the tile runs the same decode inline instead of registering
                    // a child record whose only subscriber it would be.
                    val borrowed = parent.useRegisteredDependency(pixels.request(page, tile, parent.priority.value),
                        disposeAbandoned = { uploader.release(it) }) { prepared ->
                        upload(parent, prepared, tile, epoch, startedAtNanos)
                    }
                    if (borrowed != null) return@useDependency borrowed
                    val owned = parent.withDomainPermit(WorkDomain.DECODE) {
                        pixels.decodeInline(stored, tile, parent.priority.value)
                    }
                    var uploaded: EngineTexture? = null
                    try {
                        uploaded = upload(parent, owned, tile, epoch, startedAtNanos)
                        // The upload owns a GL result now: a record cancelled while it ran must free
                        // that result here, exactly as the borrow path frees a result whose block
                        // outlived its subscription.
                        currentCoroutineContext().ensureActive()
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            val created = uploaded
                            if (created != null) try { uploader.release(created) } catch (cleanup: Throwable) {
                                if (cleanup !== error) error.addSuppressed(cleanup)
                            }
                        }
                        throw error
                    } finally {
                        // The inline path owns its raster: the upload has copied it, and the record's
                        // own result owns the texture from here.
                        withContext(NonCancellable) { owned.close() }
                    }
                    checkNotNull(uploaded)
                }
            })
    }

    /**
     * Prepares and uploads one decoded raster. The upload domain's permit is what serialises
     * transfers: it carries the boundary rule that a speculative tile may not become resident while a
     * visible decode is in flight, and it already caps uploads at one, so the tile keeps no private
     * permit of its own.
     */
    private suspend fun upload(
        parent: WorkContext,
        source: EnginePixels,
        tile: EngineTileSpec,
        epoch: Long,
        startedAtNanos: Long,
    ): EngineTexture {
        EngineStageProbe.record(tile as Any, EngineStageProbe.PIXELS_READY, System.nanoTime())
        EnginePageWork.observer?.invoke("decode-done elapsedMs=${elapsed(startedAtNanos)}")
        val transfer = uploader.prepareTexture(source)
        try {
            val uploaded = parent.withDomainPermit(WorkDomain.UPLOAD) { transfer.upload(epoch) }
            EnginePageWork.observer?.invoke("upload-done elapsedMs=${elapsed(startedAtNanos)}")
            return uploaded
        } finally {
            withContext(NonCancellable) { transfer.close() }
        }
    }

    private fun elapsed(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    private fun validate(page: StoredPage, tile: EngineTileSpec) {
        require(page.pageId == tile.pageId && page.contentRevision == tile.contentRevision &&
            page.sha256 == tile.sha256 && page.dimensions == tile.dimensions) { "Tile does not match immutable page bytes" }
    }
}
