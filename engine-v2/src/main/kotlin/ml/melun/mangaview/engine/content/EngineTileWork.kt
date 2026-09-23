package ml.melun.mangaview.engine.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.EngineImageDecoder
import ml.melun.mangaview.engine.api.EngineTexture
import ml.melun.mangaview.engine.api.EngineTextureUploader
import ml.melun.mangaview.engine.api.EngineTileSpec
import ml.melun.mangaview.engine.api.StoredPage
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

    /**
     * The native upload writes through the GL owner, which serialises the transfer itself, and the
     * bandwidth pacer and the owner queue assume one transfer at a time. That was previously the
     * job of an upload-domain work record; a permit here preserves exactly the same concurrency
     * while keeping the upload on the tile's own continuation.
     */
    private val uploadPermit = Semaphore(1)

    fun request(page: WorkRequest<StoredPage>, tile: EngineTileSpec, priority: WorkPriority): WorkRequest<EngineTexture> {
        val epoch = uploader.rendererEpoch
        val revision = EnginePixelWork.revision(page, tile)
        val gpuResource = "${uploader.rendererId}:${page.key.resource}"
        val gpuRevision = "$epoch:$revision"
        val uploadKey = WorkKey(page.key.principal, gpuResource, "graphics.upload", gpuRevision, EngineTexture::class.java)
        val resultKey = uploadKey.copy(operation = "graphics.texture")
        // A tile's residency is what the reader feels, so the tile owns the texture and releases it
        // when its own result retires. The upload used to be a separate record whose only remaining
        // effect was a second admission and a second completion on the same worker lane: measured on
        // the GPU AVD, a steady read-ahead tile spent 0.05ms reaching that record and 0.78ms after
        // the native upload finished, on a chain that is otherwise eight dispatches at ~0.2ms each.
        return WorkRequest(resultKey, WorkDomain.CONTROL, priority, authEpoch = page.authEpoch,
            dispose = { uploader.release(it) },
            execute = { parent ->
                val startedAtNanos = System.nanoTime()
                EngineStageProbe.record(tile as Any, EngineStageProbe.WORK_ENTER, startedAtNanos)
                parent.useDependency(page) { stored ->
                    EngineStageProbe.record(tile as Any, EngineStageProbe.PAGE_READY, System.nanoTime())
                    validate(stored, tile)
                    parent.useDependency(pixels.request(page, tile, parent.priority.value),
                        disposeAbandoned = { uploader.release(it) }) { pixels ->
                        EngineStageProbe.record(tile as Any, EngineStageProbe.PIXELS_READY, System.nanoTime())
                        EnginePageWork.observer?.invoke("decode-done elapsedMs=${elapsed(startedAtNanos)}")
                        val transfer = uploader.prepareTexture(pixels)
                        try {
                            // Residency is what the reader actually sees, so speculation is held here
                            // rather than at the decode: a read-ahead tile waits for the visible decodes
                            // it follows before it may take the upload slot and become resident. Its
                            // decode still runs freely, which is what the horizon's latency depends on.
                            val uploaded = parent.withDomainPermit(WorkDomain.UPLOAD) {
                                uploadPermit.withPermit { transfer.upload(epoch) }
                            }
                            EnginePageWork.observer?.invoke("upload-done elapsedMs=${elapsed(startedAtNanos)}")
                            uploaded
                        } finally { withContext(NonCancellable) { transfer.close() } }
                    }
                }
            })
    }

    private fun elapsed(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    private fun validate(page: StoredPage, tile: EngineTileSpec) {
        require(page.pageId == tile.pageId && page.contentRevision == tile.contentRevision &&
            page.sha256 == tile.sha256 && page.dimensions == tile.dimensions) { "Tile does not match immutable page bytes" }
    }
}
