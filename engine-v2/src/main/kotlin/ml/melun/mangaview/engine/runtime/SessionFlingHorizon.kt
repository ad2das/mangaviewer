package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.WorkPriority

/**
 * The runtime's fling-horizon measurements: how fast the anchor moves and how long a network page
 * fetch takes demand-to-accepted. Owns both estimators and the last sampled anchor, keeping the
 * session runtime class inside the architecture size gate. Owner-thread confined like its runtime.
 */
internal class SessionFlingHorizon(private val clock: () -> Long) {
    val readingVelocity = ReadingVelocity()
    val fetchLatency = FetchLatencyEstimate()
    private var lastSample: VelocitySample? = null

    /** The clamped interaction horizon depth; the fixed shallow depth applies at rest. */
    fun lead(interactionActive: Boolean): Int = if (interactionActive) {
        interactionLead(readingVelocity.pagesPerSecond, fetchLatency.seconds)
    } else {
        PAGES_AHEAD_AT_REST
    }

    /**
     * Samples the anchor's reading position. Only actual movements count: a metadata-only update
     * that leaves the anchor in place adds no sample, while a new movement revision counts even
     * when it returns to the previous position (whose velocity is 0).
     */
    fun sample(state: EngineSessionSnapshot, plans: Map<EpisodeId, EpisodeAccessPlan>) {
        val anchor = state.anchor ?: return
        val previous = lastSample
        if (previous != null && previous.pageId == anchor.pageId &&
            previous.sourceYQ32 == anchor.sourceYQ32 && previous.movementRevision == state.movementRevision
        ) {
            return
        }
        val manifest = plans[anchor.pageId.episodeId]?.manifest ?: return
        val ordinal = manifest.pages.indexOfFirst { it.id == anchor.pageId }
        if (ordinal < 0) return
        val heightPx = state.anchorDimensions?.heightPx ?: 0
        val extent = heightPx.toLong() * SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val fraction = if (extent > 0L) anchor.sourceYQ32.toDouble() / extent.toDouble() else 0.0
        readingVelocity.onSample(anchor.pageId.episodeId, ordinal, fraction, clock())
        lastSample = VelocitySample(anchor.pageId, anchor.sourceYQ32, state.movementRevision)
    }

    /**
     * Registers the speculative fetch demands that feed the latency EWMA. Every not-yet-prepared
     * NEXT_IMAGE/INTERACTIVE page keeps the instant it was first demanded; demands for pages the
     * runtime no longer wants are dropped so a later re-demand starts a fresh interval. The clock
     * is read only when a demand actually appears.
     */
    fun track(wantedPages: Map<PageId, WorkPriority>, prepared: Set<PageId>) {
        fetchLatency.retain(wantedPages.keys)
        val missing = mutableListOf<PageId>()
        wantedPages.forEach { (id, priority) ->
            if (id !in prepared && (priority == WorkPriority.NEXT_IMAGE || priority == WorkPriority.INTERACTIVE) &&
                !fetchLatency.tracks(id)
            ) {
                missing += id
            }
        }
        if (missing.isEmpty()) return
        val now = clock()
        missing.forEach { fetchLatency.observe(it, now) }
    }

    /** Clears every pending measurement; used when the session changes documents or pauses. */
    fun reset() {
        readingVelocity.reset()
        lastSample = null
        fetchLatency.clear()
    }
}

/** Last anchor position whose movement was sampled into [ReadingVelocity]. */
private data class VelocitySample(val pageId: PageId, val sourceYQ32: Long, val movementRevision: Long)
