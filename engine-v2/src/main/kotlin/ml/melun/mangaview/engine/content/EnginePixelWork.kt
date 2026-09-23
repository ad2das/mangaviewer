package ml.melun.mangaview.engine.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.engine.runtime.EngineStageProbe
import ml.melun.mangaview.engine.api.*

/**
 * One decode lane: the scheduling and thread priority a decode of a given priority runs under.
 * A lane may run its block inline — the caller's worker already owns the tile record whose decode
 * this is, so a dispatcher hop would only add a handoff — or dispatch it to its own pool.
 */
interface DecodeLane {
    suspend fun <R> run(block: suspend () -> R): R
}

/** A lane that dispatches its block to [dispatcher]. */
class DispatcherDecodeLane(private val dispatcher: CoroutineDispatcher) : DecodeLane {
    override suspend fun <R> run(block: suspend () -> R): R = withContext(dispatcher) { block() }
}

/** Renderer-independent pixels can be prepared before a window exists and borrowed at opening. */
class EnginePixelWork(
    private val decoder: EngineImageDecoder,
    private val lanesFor: (WorkPriority) -> DecodeLane,
) {
    /** Single-lane construction: every priority decodes on the same dispatcher. */
    constructor(decoder: EngineImageDecoder, dispatcher: CoroutineDispatcher)
        : this(decoder, { DispatcherDecodeLane(dispatcher) })

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

    /**
     * Runs one tile's decode on the caller's own record, without registering a record of its own.
     * The caller has already claimed the operation and holds the decode domain's permit around this
     * call; validation and cleanup of a raster that cannot be returned stay identical to [request].
     */
    suspend fun decodeInline(stored: StoredPage, tile: EngineTileSpec, priority: WorkPriority): EnginePixels {
        require(stored.pageId == tile.pageId && stored.contentRevision == tile.contentRevision &&
            stored.sha256 == tile.sha256 && stored.dimensions == tile.dimensions) {
            "Tile does not match immutable page bytes"
        }
        return decode(stored, tile, priority)
    }

    private suspend fun decode(page: StoredPage, tile: EngineTileSpec, priority: WorkPriority): EnginePixels {
        var owned: EnginePixels? = null
        // The lane is chosen when the decode actually runs, so a page first registered by the opening
        // prediction still follows whatever priority its demand carries now.
        val lane = lanesFor(priority)
        try {
            return lane.run {
                EngineStageProbe.record(tile as Any, EngineStageProbe.DECODE_ENTER, System.nanoTime())
                val decoded = decoder.decode(page, tile)
                owned = decoded
                EngineStageProbe.record(tile as Any, EngineStageProbe.DECODE_DONE, System.nanoTime())
                require(decoded.tile == tile && decoded.byteCount == tile.byteCount)
                decoded
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { lane.run { owned?.close() } } catch (cleanup: Throwable) {
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
