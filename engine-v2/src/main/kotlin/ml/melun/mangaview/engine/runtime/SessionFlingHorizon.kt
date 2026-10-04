package ml.melun.mangaview.engine.runtime

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.WorkPriority

/**
 * The runtime's fling-horizon measurements: how fast the anchor moves and how long a network page
 * fetch takes demand-to-accepted. Owns both estimators, the last sampled anchor and the applied
 * horizon depth, keeping the session runtime class inside the architecture size gate. Owner-thread
 * confined like its runtime.
 *
 * The applied lead is hysteretic: a deeper computed lead applies at once, while a shallower one
 * must hold for [LEAD_HOLD_NANOS] and then only drops to the deepest lead computed inside that
 * window. Per-frame velocity jitter therefore cannot flap the demand cache, and a stale estimate
 * decays through the same hold instead of collapsing in one frame.
 */
internal class SessionFlingHorizon(private val clock: () -> Long) {
    val readingVelocity = ReadingVelocity()
    val fetchLatency = FetchLatencyEstimate()
    private var lastSample: VelocitySample? = null
    private var appliedLead = 0
    private var loweredAtNanos: Long? = null
    private val loweredLeads = ArrayDeque<LeadCandidate>()

    /** The clamped interaction horizon depth; the fixed shallow depth applies at rest. */
    fun lead(interactionActive: Boolean): Int {
        if (!interactionActive) {
            resetLeadHysteresis()
            return PAGES_AHEAD_AT_REST
        }
        val now = clock()
        val computed = interactionLead(readingVelocity.pagesPerSecondAt(now), fetchLatency.seconds)
        return stabilizedLead(computed, now)
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

    /**
     * Applies [computed] with hysteresis: an increase is immediate, while a decrease only lands
     * once the lead stayed lower for the hold window, and then drops to the deepest value seen
     * inside that window instead of straight to the floor.
     */
    private fun stabilizedLead(computed: Int, now: Long): Int {
        if (computed >= appliedLead) {
            appliedLead = computed
            loweredAtNanos = null
            loweredLeads.clear()
            return appliedLead
        }
        if (loweredAtNanos == null) {
            loweredAtNanos = now
            loweredLeads.clear()
        }
        loweredLeads.addLast(LeadCandidate(now, computed))
        val heldSince = loweredAtNanos ?: now
        if (now - heldSince < LEAD_HOLD_NANOS) return appliedLead
        while (loweredLeads.size > 1 && loweredLeads.first().atNanos < now - LEAD_HOLD_NANOS) {
            loweredLeads.removeFirst()
        }
        appliedLead = loweredLeads.maxOf { it.lead }
        loweredAtNanos = now
        loweredLeads.clear()
        return appliedLead
    }

    private fun resetLeadHysteresis() {
        appliedLead = 0
        loweredAtNanos = null
        loweredLeads.clear()
    }

    /** Clears every pending measurement; used when the session changes documents or pauses. */
    fun reset() {
        readingVelocity.reset()
        lastSample = null
        fetchLatency.clear()
        resetLeadHysteresis()
    }

    private companion object {
        /** How long a shallower computed lead must hold before it replaces the applied depth. */
        const val LEAD_HOLD_NANOS = 1_000_000_000L
    }
}

/** Last anchor position whose movement was sampled into [ReadingVelocity]. */
private data class VelocitySample(val pageId: PageId, val sourceYQ32: Long, val movementRevision: Long)

/** One computed lead observed while the applied horizon depth sits higher. */
private data class LeadCandidate(val atNanos: Long, val lead: Int)
