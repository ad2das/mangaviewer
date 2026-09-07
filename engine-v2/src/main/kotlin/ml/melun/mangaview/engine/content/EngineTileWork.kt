package ml.melun.mangaview.engine.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.EngineImageDecoder
import ml.melun.mangaview.engine.api.EnginePixels
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
    private val decoder: EngineImageDecoder,
    private val decodingDispatcher: CoroutineDispatcher,
    private val uploader: EngineTextureUploader,
) {
    fun request(page: WorkRequest<StoredPage>, tile: EngineTileSpec, priority: WorkPriority): WorkRequest<EngineTexture> {
        val epoch = uploader.rendererEpoch
        val revision = "${page.key.contentRevision}:${tile.sha256}:${tile.sourceTop}:${tile.sourceBottom}:" +
            "${tile.displayWidth}:${tile.dimensions.widthPx}:${tile.dimensions.heightPx}"
        val decodeKey = WorkKey(page.key.principal, page.key.resource, "graphics.decode", revision, EnginePixels::class.java)
        val gpuResource = "${uploader.rendererId}:${page.key.resource}"
        val gpuRevision = "$epoch:$revision"
        val uploadKey = WorkKey(page.key.principal, gpuResource, "graphics.upload", gpuRevision, EngineTexture::class.java)
        val resultKey = uploadKey.copy(operation = "graphics.texture")
        return WorkRequest(resultKey, WorkDomain.CONTROL, priority, authEpoch = page.authEpoch, execute = { parent ->
            parent.useDependency(page) { stored ->
                validate(stored, tile)
                parent.useDependency(WorkRequest(decodeKey, WorkDomain.DECODE, parent.priority.value,
                    authEpoch = page.authEpoch, execute = { decode(stored, tile) }, dispose = { it.close() })) { pixels ->
                    parent.dependency(WorkRequest(uploadKey, WorkDomain.UPLOAD, parent.priority.value,
                        authEpoch = page.authEpoch, execute = { uploader.upload(pixels, epoch) },
                        dispose = { uploader.release(it) }))
                }
            }
        })
    }

    private suspend fun decode(page: StoredPage, tile: EngineTileSpec): EnginePixels {
        var owned: EnginePixels? = null
        try {
            withContext(decodingDispatcher) {
                owned = decoder.decode(page, tile)
                require(owned!!.tile == tile && owned!!.byteCount == tile.byteCount)
            }
            return checkNotNull(owned)
        } catch (failure: Throwable) {
            withContext(NonCancellable + decodingDispatcher) {
                try { owned?.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    private fun validate(page: StoredPage, tile: EngineTileSpec) {
        require(page.pageId == tile.pageId && page.contentRevision == tile.contentRevision &&
            page.sha256 == tile.sha256 && page.dimensions == tile.dimensions) { "Tile does not match immutable page bytes" }
    }
}
