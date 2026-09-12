package ml.melun.mangaview.engine.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.api.*

/** Renderer-independent pixels can be prepared before a window exists and borrowed at opening. */
class EnginePixelWork(
    private val decoder: EngineImageDecoder,
    private val dispatcher: CoroutineDispatcher,
) {
    fun request(page: WorkRequest<StoredPage>, tile: EngineTileSpec, priority: WorkPriority): WorkRequest<EnginePixels> {
        val revision = revision(page, tile)
        val key = WorkKey(page.key.principal, page.key.resource, "graphics.pixels", revision, EnginePixels::class.java)
        val decodeKey = key.copy(operation = "graphics.decode")
        return WorkRequest(key, WorkDomain.CONTROL, priority, authEpoch = page.authEpoch, execute = { parent ->
            parent.useDependency(page) { stored ->
                require(stored.pageId == tile.pageId && stored.contentRevision == tile.contentRevision &&
                    stored.sha256 == tile.sha256 && stored.dimensions == tile.dimensions) {
                    "Tile does not match immutable page bytes"
                }
                parent.dependency(WorkRequest(decodeKey, WorkDomain.DECODE, parent.priority.value,
                    authEpoch = page.authEpoch, execute = { decode(stored, tile) }, dispose = { it.close() }))
            }
        })
    }

    private suspend fun decode(page: StoredPage, tile: EngineTileSpec): EnginePixels {
        var owned: EnginePixels? = null
        try {
            withContext(dispatcher) {
                owned = decoder.decode(page, tile)
                require(owned!!.tile == tile && owned!!.byteCount == tile.byteCount)
            }
            return checkNotNull(owned)
        } catch (failure: Throwable) {
            withContext(NonCancellable + dispatcher) {
                try { owned?.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    companion object {
        internal fun revision(page: WorkRequest<StoredPage>, tile: EngineTileSpec) =
            "${page.key.contentRevision}:${tile.sha256}:${tile.sourceTop}:${tile.sourceBottom}:" +
                "${tile.displayWidth}:${tile.dimensions.widthPx}:${tile.dimensions.heightPx}:" +
                "${tile.cropLeftPx}:${tile.cropRightPx}"
    }
}
