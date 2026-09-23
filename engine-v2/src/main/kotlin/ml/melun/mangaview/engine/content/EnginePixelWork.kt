package ml.melun.mangaview.engine.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.runtime.EngineStageProbe
import ml.melun.mangaview.engine.api.*

/** Renderer-independent pixels can be prepared before a window exists and borrowed at opening. */
class EnginePixelWork(
    private val decoder: EngineImageDecoder,
    private val dispatcherFor: (WorkPriority) -> CoroutineDispatcher,
) {
    /** Single-lane construction: every priority decodes on the same dispatcher. */
    constructor(decoder: EngineImageDecoder, dispatcher: CoroutineDispatcher) : this(decoder, { dispatcher })

    fun request(page: WorkRequest<StoredPage>, tile: EngineTileSpec, priority: WorkPriority): WorkRequest<EnginePixels> {
        val revision = revision(page, tile)
        val key = WorkKey(page.key.principal, page.key.resource, "graphics.pixels", revision, EnginePixels::class.java)
        // The decode runs inline on this CONTROL record, but it still borrows its own domain's permit
        // for the decode block. That keeps the record's single completion while preserving the
        // admission contract a dedicated DECODE record used to provide: a background read-ahead decode
        // queues behind a blocked visible decode instead of overtaking it.
        return WorkRequest(key, WorkDomain.CONTROL, priority, authEpoch = page.authEpoch,
            dispose = { it.close() },
            execute = { parent ->
            parent.useDependency(page) { stored ->
                require(stored.pageId == tile.pageId && stored.contentRevision == tile.contentRevision &&
                    stored.sha256 == tile.sha256 && stored.dimensions == tile.dimensions) {
                    "Tile does not match immutable page bytes"
                }
                parent.withDomainPermit(WorkDomain.DECODE) { decode(stored, tile, parent.priority.value) }
            }
        })
    }

    private suspend fun decode(page: StoredPage, tile: EngineTileSpec, priority: WorkPriority): EnginePixels {
        var owned: EnginePixels? = null
        // The lane is chosen when the decode actually runs, so a page first registered by the opening
        // prediction still follows whatever priority its demand carries now.
        val lane = dispatcherFor(priority)
        try {
            withContext(lane) {
                EngineStageProbe.record(tile as Any, EngineStageProbe.DECODE_ENTER, System.nanoTime())
                owned = decoder.decode(page, tile)
                EngineStageProbe.record(tile as Any, EngineStageProbe.DECODE_DONE, System.nanoTime())
                require(owned!!.tile == tile && owned!!.byteCount == tile.byteCount)
            }
            return checkNotNull(owned)
        } catch (failure: Throwable) {
            withContext(NonCancellable + lane) {
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
