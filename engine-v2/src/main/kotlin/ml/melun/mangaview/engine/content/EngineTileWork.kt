package ml.melun.mangaview.engine.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
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

/** Short-lived file/pixel borrows feed a separately owned GPU result. */
class EngineTileWork(
    decoder: EngineImageDecoder,
    decodingDispatcher: CoroutineDispatcher,
    private val uploader: EngineTextureUploader,
) {
    private val pixels = EnginePixelWork(decoder, decodingDispatcher)
    fun request(page: WorkRequest<StoredPage>, tile: EngineTileSpec, priority: WorkPriority): WorkRequest<EngineTexture> {
        val epoch = uploader.rendererEpoch
        val revision = EnginePixelWork.revision(page, tile)
        val gpuResource = "${uploader.rendererId}:${page.key.resource}"
        val gpuRevision = "$epoch:$revision"
        val uploadKey = WorkKey(page.key.principal, gpuResource, "graphics.upload", gpuRevision, EngineTexture::class.java)
        val resultKey = uploadKey.copy(operation = "graphics.texture")
        return WorkRequest(resultKey, WorkDomain.CONTROL, priority, authEpoch = page.authEpoch, execute = { parent ->
            parent.useDependency(page) { stored ->
                validate(stored, tile)
                parent.useDependency(pixels.request(page, tile, parent.priority.value)) { pixels ->
                    val transfer = uploader.prepareTexture(pixels)
                    try {
                        parent.dependency(WorkRequest(uploadKey, WorkDomain.UPLOAD, parent.priority.value,
                            authEpoch = page.authEpoch, execute = { transfer.upload(epoch) },
                            dispose = { uploader.release(it) }))
                    } finally { withContext(NonCancellable) { transfer.close() } }
                }
            }
        })
    }

    private fun validate(page: StoredPage, tile: EngineTileSpec) {
        require(page.pageId == tile.pageId && page.contentRevision == tile.contentRevision &&
            page.sha256 == tile.sha256 && page.dimensions == tile.dimensions) { "Tile does not match immutable page bytes" }
    }
}
