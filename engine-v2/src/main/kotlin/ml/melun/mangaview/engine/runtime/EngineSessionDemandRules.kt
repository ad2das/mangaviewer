package ml.melun.mangaview.engine.runtime

import java.util.Collections
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineSessionPort
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.InputReceipt
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SessionUpdate
import ml.melun.mangaview.engine.api.SessionWorkOwnership
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.api.WorkMetadata
import ml.melun.mangaview.engine.session.UnknownPageDimensionsException
import ml.melun.mangaview.source.AdjacentEpisodes

// The next document's first pages are a fixed horizon; near the document end they outrank the
// remaining bulk so the boundary is already readable when the reader crosses it.
internal const val NEXT_EPISODE_HEAD_PAGES = 4
internal const val BOUNDARY_APPROACH_PAGES = 6
/** Guaranteed next-episode pages once the boundary is within [BOUNDARY_APPROACH_PAGES]. */
internal const val BOUNDARY_HEAD_PAGES = 10
// A transient neighbor failure retries on its own instead of parking the boundary for the session.
internal const val EPISODE_RETRY_DELAY_NANOS = 3_000_000_000L

// A required page that fails this many distinct attempts is declared unavailable for the session:
// the runtime publishes placeholder geometry so one dead provider page cannot block the reader.
// The work set's own retries cover the ordinary transient failure well before this bound.
internal const val PAGE_UNAVAILABLE_FAILURES = 3

/** Keys to drop so the plan map stays inside its window; never drops a protected episode. */
internal fun <V> planKeysToDrop(
    plans: Map<EpisodeId, V>,
    protectedEpisodes: Set<EpisodeId>,
    maximum: Int,
): List<EpisodeId> {
    val overflow = plans.size - maximum
    if (overflow <= 0) return emptyList()
    return plans.keys.filter { it !in protectedEpisodes }.take(overflow)
}

// Depth of the *page* horizon behind the leading required page. While a gesture owns the frame the
// bulk read-ahead is deferred, so without this the horizon is only two pages deep and a read-ahead
// tile is usually demanded before its page has been published. Deepening the page horizon (never
// the tile horizon) lets the publish finish ahead of the demand without queueing extra decode work
// on the lanes the visible tile shares. At rest the bulk already streams the whole tail, so a
// shallow explicit horizon is enough there.
internal const val PAGES_AHEAD_WHILE_INTERACTING = 6
internal const val PAGES_AHEAD_AT_REST = 2

// The interaction horizon follows the reader: lead = ceil(v * L * 1.5), bounded below by the fixed
// minimum and above by MAX_INTERACTION_LEAD. v is the forward reading velocity in pages/second and
// L the runtime EWMA of demand->accepted fetch latency in seconds (see ReadingVelocity and
// FetchLatencyEstimate); a still or reversing reader keeps the fixed six-page horizon.
internal const val MAX_INTERACTION_LEAD = 20
// The horizon never holds more than this many not-yet-prepared pages: leaving two permits of the
// background network budget (WorkLimits.backgroundNetwork = 12) keeps the visible path headroom.
internal const val MAX_INTERACTION_HORIZON_FETCHES = 10
private const val LEAD_SAFETY_FACTOR = 1.5

internal fun interactionLead(pagesPerSecond: Double, latencySeconds: Double): Int {
    if (!pagesPerSecond.isFinite() || !latencySeconds.isFinite() || pagesPerSecond <= 0.0 ||
        latencySeconds <= 0.0
    ) {
        return PAGES_AHEAD_WHILE_INTERACTING
    }
    val raw = pagesPerSecond * latencySeconds * LEAD_SAFETY_FACTOR
    if (!raw.isFinite()) return MAX_INTERACTION_LEAD
    return ceil(raw).toInt().coerceIn(PAGES_AHEAD_WHILE_INTERACTING, MAX_INTERACTION_LEAD)
}

// The replay head is a single page, so a fast catch-up walk otherwise stalls once per page while
// each download parses its own geometry. Batching a short window behind the blocker starts those
// downloads together and turns the walk into one fetch round instead of N.
internal const val BLOCKED_DIMENSION_WINDOW = 4
internal const val BLOCKED_DIMENSION_BACKWARD_WINDOW = 2

// Read-ahead planning lives at file level: pure demand ordering over the caller's maps, so the
// session runtime stays under the size gate without giving up the prepared/failed context.
internal fun addReadAhead(state: EngineSessionSnapshot, plans: Map<EpisodeId, EpisodeAccessPlan>,
    targetEpisode: EpisodeId, prepared: Set<PageId>, failedReadAheadPages: Set<PageId>,
    initialPresented: Boolean, interactionActive: Boolean, lead: Int, result: LinkedHashMap<PageId, WorkPriority>,
) {
    val anchor = readAheadAnchor(state, targetEpisode) ?: return
    val manifest = plans[anchor.episodeId]?.manifest ?: return
    val index = manifest.pages.indexOfFirst { it.id == anchor }
    if (index < 0) return
    addNearbyOriginals(state, manifest, index, plans, prepared, failedReadAheadPages, initialPresented,
        lead, result)
    // Give every original needed by the opening viewport the first network window.
    // Bulk transfer starts as soon as those bytes arrive, independently of rendering.
    // While a drag or fling owns the frame the bulk waits: it only re-materialises whole cached
    // episode tails behind the tiles the reader is scrolling onto, and its hundreds of lookups
    // saturate the storage lane and the decode threads the visible tile's own path has to share.
    if (!interactionActive && (initialPresented || (state.completeViewport && state.visibleRegions.all { it.pageId in prepared })))
        addRemainingOriginals(manifest, index, prepared, failedReadAheadPages, result)
    addNextOriginals(state, manifest, index, plans, prepared, failedReadAheadPages, initialPresented,
        interactionActive, result)
}

internal fun addNearbyOriginals(state: EngineSessionSnapshot, manifest: EpisodeManifest, index: Int,
    plans: Map<EpisodeId, EpisodeAccessPlan>, prepared: Set<PageId>, failedReadAheadPages: Set<PageId>,
    initialPresented: Boolean, lead: Int, result: LinkedHashMap<PageId, WorkPriority>,
) {
    // Keep a small prepared neighborhood available to the tile planner. Originals
    // elsewhere stay in disk storage; never retain an entire episode's textures.
    for (offset in listOf(1, 2, -1)) {
        val id = manifest.pages.getOrNull(index + offset)?.id ?: continue
        if (id in prepared) result.putIfAbsent(id, WorkPriority.NEXT_IMAGE)
    }
    // Start the nearby pages alongside the focus original under background permits.
    // Waiting for the first body or a complete scene serializes a multi-page viewport.
    val leadingIndex = state.requiredDimensions.fold(index) { leading, id ->
        maxOf(leading, manifest.pages.indexOfFirst { it.id == id })
    }
    // While a gesture owns the frame the bulk read-ahead is deferred, so the page horizon is the
    // only forward demand. Its depth follows the reader's forward velocity and the measured fetch
    // latency (the caller computes the clamped lead); deep only when the reader is actually
    // outrunning the fetches, and never deeper than the outstanding-fetch budget. Nearest pages
    // first: the loop walks outward and the map keeps insertion order, so a deeper page added
    // later never takes the sequence of a nearer one.
    var outstanding = 0
    for (offset in 1..lead) {
        val ordinal = leadingIndex + offset
        val id = manifest.pages.getOrNull(ordinal)?.id ?: manifest.nextEpisodeId?.let { next ->
            // The same lead continues across a known document boundary.
            plans[next]?.manifest?.pages?.getOrNull(ordinal - manifest.pages.size)?.id
        } ?: continue
        if (id in failedReadAheadPages) continue
        if (id !in prepared) {
            if (outstanding >= MAX_INTERACTION_HORIZON_FETCHES) continue
            outstanding++
        }
        val priority = if (!initialPresented && ordinal <= index + 2) WorkPriority.VISIBLE else WorkPriority.NEXT_IMAGE
        result.putIfAbsent(id, priority)
    }
}

internal fun addRemainingOriginals(manifest: EpisodeManifest, index: Int, prepared: Set<PageId>,
    failedReadAheadPages: Set<PageId>, result: LinkedHashMap<PageId, WorkPriority>,
) {
    // Stream originals to disk while the app reserves two BODY slots for visible work.
    val remainingSlots = (12 - result.count { (id, priority) -> priority == WorkPriority.NEXT_IMAGE && id !in prepared }).coerceAtLeast(0)
    pendingOriginalPages(manifest, index, remainingSlots, prepared, failedReadAheadPages, result)
        .forEach { result.putIfAbsent(it, WorkPriority.NEXT_IMAGE) }
}

internal fun addNextOriginals(state: EngineSessionSnapshot, manifest: EpisodeManifest, index: Int,
    plans: Map<EpisodeId, EpisodeAccessPlan>, prepared: Set<PageId>, failedReadAheadPages: Set<PageId>,
    initialPresented: Boolean, interactionActive: Boolean, result: LinkedHashMap<PageId, WorkPriority>,
) {
    val next = manifest.nextEpisodeId?.let { plans[it]?.manifest } ?: return
    // The opening horizon is guaranteed: by the time the reader reaches the boundary the first
    // pages wait on disk, independently of how busy the current document's tail still is. Near the
    // boundary they stream ahead of the remaining bulk, still inside the background permits.
    val nearEnd = manifest.pages.size - index <= BOUNDARY_APPROACH_PAGES
    val headPriority = if (nearEnd) WorkPriority.NEXT_IMAGE else WorkPriority.NEXT_EPISODE
    // The guaranteed head deepens once the boundary is in sight: a reader arriving there should
    // find more than the opening pages already on disk. The extra pages keep the same background
    // priority, so they still yield to every visible and interactive request.
    val head = if (nearEnd) BOUNDARY_HEAD_PAGES else NEXT_EPISODE_HEAD_PAGES
    next.pages.take(head).filter { it.id !in failedReadAheadPages }.forEach {
        result.putIfAbsent(it.id, headPriority)
    }
    // While a gesture owns the frame the bulk stays parked: these twelve slots are page transfers
    // whose lookups and publishes otherwise ride the same background lanes the reader's own
    // read-ahead has to share (measured on wfwf's fling median). The four-page head above keeps
    // the boundary warm, and the slots re-fill on the first update after the gesture ends.
    if (interactionActive) return
    // Start before GPU preparation, but leave every queued current body its background slot.
    // Filling all next slots prematurely can strand the current episode's sliding-window tail.
    val openingReady = state.completeViewport && state.visibleRegions.all { it.pageId in prepared }
    if (!initialPresented && !openingReady && manifest.pages.any { it.id !in prepared }) return
    val occupied = result.count { (id, priority) -> id !in prepared &&
        (priority == WorkPriority.NEXT_IMAGE || priority == WorkPriority.NEXT_EPISODE) }
    val available = (12 - occupied).coerceAtLeast(0)
    next.pages.asSequence().filter { it.id !in prepared && it.id !in failedReadAheadPages && it.id !in result }
        .take(available).forEach { result[it.id] = WorkPriority.NEXT_EPISODE }
}

internal fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))
internal fun <K, V> withEntry(source: Map<K, V>, key: K, value: V): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source).apply { put(key, value) })
internal fun <K, V> withoutEntry(source: Map<K, V>, key: K): Map<K, V> =
    if (!source.containsKey(key)) source
    else Collections.unmodifiableMap(LinkedHashMap(source).apply { remove(key) })

// The final original of the reading document gets one spare interactive request so a fast
// reader cannot outrun a displayable episode end. It is not visible work and never displaces
// the twelve background transfer permits. Short documents are covered by the ordinary horizon,
// and the reservation starts only after the opening viewport is presented.
internal fun reserveDocumentEndOriginal(state: EngineSessionSnapshot, plans: Map<EpisodeId, EpisodeAccessPlan>,
    target: EpisodeId, prepared: Set<PageId>, failed: Set<PageId>, presented: Boolean,
    result: LinkedHashMap<PageId, WorkPriority>,
) {
    if (!presented) return
    val anchor = readAheadAnchor(state, target) ?: return
    val manifest = plans[anchor.episodeId]?.manifest ?: return
    val index = manifest.pages.indexOfFirst { it.id == anchor }
    if (index < 0 || manifest.pages.size - index <= 8) return
    val tail = manifest.pages.last().id
    if (tail !in prepared && tail !in failed) result.putIfAbsent(tail, WorkPriority.INTERACTIVE)
}

// Stream forward pages in reading order for the bulk background window. Reverse input fills
// earlier pages only once the forward phase is complete.
internal fun pendingOriginalPages(manifest: EpisodeManifest, index: Int, slots: Int,
    prepared: Set<PageId>, failed: Set<PageId>, existing: Map<PageId, WorkPriority>,
): List<PageId> {
    fun pending(indices: IntProgression) = indices.asSequence().map { manifest.pages[it].id }
        .filter { it !in prepared && it !in failed && it !in existing }
    val forwardPending = (index + 1 until manifest.pages.size).any {
        manifest.pages[it].id !in prepared && manifest.pages[it].id !in failed
    }
    if (!forwardPending) return pending(index - 1 downTo 0).take(slots).toList()
    return pending(index + 1 until manifest.pages.size).take(slots).toList()
}

internal fun matchesVerifiedGeometry(known: PageContentIdentity?, geometry: WorkMetadata.PageGeometry): Boolean {
    if (known == null) return false
    require(known.dimensions == geometry.dimensions) { "Conflicting dimensions for ${geometry.pageId}" }
    return known.contentRevision == geometry.contentRevision
}

// The unresolved legacy page is a known request, even before its dimensions
// can convert the saved offset into an exact source anchor.
internal fun readAheadAnchor(state: EngineSessionSnapshot, target: EpisodeId): PageId? =
    state.anchor?.pageId ?: state.requiredDimensions.firstOrNull { it.episodeId == target }

// Use the spare control slot for one further document as soon as the adjacent plan is held
// and the opening screen has been presented. The gate is monotonic: presenting the first
// screen only ever turns it on, so a 10-20 s document body started here is never cancelled
// by a pause or a reverse -- the demand stays present while the anchor stays in the current
// episode (see the demand-key contract in EngineSessionRuntime.cachedDemands). This never
// starts that document's bodies or recursively walks its navigation links.
internal fun nextDocumentToPrepare(manifest: EpisodeManifest, plans: Map<EpisodeId, EpisodeAccessPlan>,
    initialPresented: Boolean, failed: Set<EpisodeId>,
): EpisodeId? {
    val next = manifest.nextEpisodeId?.takeUnless { it in failed } ?: return null
    val nextPlan = plans[next] ?: return next
    if (!initialPresented) return null
    return nextPlan.manifest.nextEpisodeId?.takeUnless { it in plans || it in failed }
}

internal fun retainPreparedMetadata(state: EngineSessionSnapshot, wantedPages: Set<PageId>,
    plans: Map<EpisodeId, EpisodeAccessPlan>, pages: Map<PageId, PageContentIdentity>,
): Map<PageId, PageContentIdentity> {
    val episodes = wantedPages.mapTo(mutableSetOf()) { it.episodeId }
    state.anchor?.pageId?.episodeId?.let { current ->
        episodes += current
        plans[current]?.manifest?.let { manifest ->
            manifest.previousEpisodeId?.let { previous ->
                episodes += previous
                plans[previous]?.manifest?.previousEpisodeId?.let(episodes::add)
            }
            manifest.nextEpisodeId?.let(episodes::add)
        }
    }
    // Identity metadata keeps budgeted resident pixels valid during a two-document reverse.
    // It owns no file or texture lease; explicit navigation still clears the generation.
    return if (pages.keys.any { it.episodeId !in episodes })
        Collections.unmodifiableMap(pages.filterKeys { it.episodeId in episodes }) else pages
}

internal class CachedPlan(val request: WorkRequest<EpisodeAccessPlan>, val plan: EpisodeAccessPlan)

internal fun cachedPlanPins(retained: Map<EpisodeId, CachedPlan>): List<SessionDemand<*>> =
    retained.values.map { held ->
        // Complete snapshots pin every original until navigation or close, including background
        // suspension. The ready dependency performs no network, decoding or ongoing storage work.
        SessionDemand(held.request) { plan -> check(plan === held.plan) { "Cached plan ownership changed" } }
    }

/** A transient neighbor failure retries on its own instead of parking the boundary. */
internal fun releaseRecoveredEpisodeFailures(
    failed: MutableSet<EpisodeId>,
    retryAt: MutableMap<EpisodeId, Long>,
    clock: () -> Long,
) {
    if (failed.isEmpty()) return
    val now = clock()
    val recovered = failed.filter { now >= (retryAt[it] ?: Long.MAX_VALUE) }
    if (recovered.isEmpty()) return
    recovered.forEach { failed -= it; retryAt -= it }
}

internal fun pagePriorities(
    state: EngineSessionSnapshot,
    plans: Map<EpisodeId, EpisodeAccessPlan>,
    targetEpisode: EpisodeId,
    prepared: Set<PageId>,
    failedReadAheadPages: Set<PageId>,
    missingPages: Set<PageId>,
    initialPresented: Boolean,
    interactionActive: Boolean,
    lead: Int,
    earlyTransfers: EarlyOriginalTransfers,
): LinkedHashMap<PageId, WorkPriority> {
    val result = linkedMapOf<PageId, WorkPriority>()
    state.requiredDimensions.forEach { result[it] = WorkPriority.FOCUS }
    // A blocked replay names only the head of its queue. While a gesture owns the frame, start
    // the pages behind the blocker as well so a fast catch-up walk resolves their geometry in
    // one fetch round instead of stalling on each page's own download in turn (both directions:
    // reverse bursts included). The opening is exempt: its FOCUS/VISIBLE pages must keep the
    // lanes while they publish, so no speculative page rides beside them.
    if (interactionActive) {
        state.requiredDimensions.forEach { blocked ->
            val manifest = plans[blocked.episodeId]?.manifest ?: return@forEach
            val index = manifest.pages.indexOfFirst { it.id == blocked }
            if (index < 0) return@forEach
            // The speculative window skips pages whose fetch already failed or whose provider
            // definitively has no original: re-demanding them only re-arms wakes and floods the
            // connection pool while the walk waits on the blocker anyway. Required (FOCUS) and
            // visible pages are added unfiltered above and below, so nothing needed is lost.
            for (offset in 1..BLOCKED_DIMENSION_WINDOW) {
                val id = manifest.pages.getOrNull(index + offset)?.id ?: break
                if (id in failedReadAheadPages || id in missingPages) continue
                result.putIfAbsent(id, WorkPriority.NEXT_IMAGE)
            }
            for (offset in 1..BLOCKED_DIMENSION_BACKWARD_WINDOW) {
                val id = manifest.pages.getOrNull(index - offset)?.id ?: break
                if (id in failedReadAheadPages || id in missingPages) continue
                result.putIfAbsent(id, WorkPriority.NEXT_IMAGE)
            }
        }
    }
    state.visibleRegions.forEach { region ->
        result.putIfAbsent(
            region.pageId,
            if (region.pageId == state.anchor?.pageId) WorkPriority.FOCUS else WorkPriority.VISIBLE,
        )
    }
    reserveDocumentEndOriginal(state, plans, targetEpisode, prepared, failedReadAheadPages, initialPresented, result)
    earlyTransfers.retain(result, prepared, failedReadAheadPages)
    addReadAhead(state, plans, targetEpisode, prepared, failedReadAheadPages, initialPresented,
        interactionActive, lead, result)
    return result
}

internal fun adjacentPrefetch(
    state: EngineSessionSnapshot,
    positionResolved: Boolean,
    plans: Map<EpisodeId, EpisodeAccessPlan>,
    targetEpisode: EpisodeId,
    initialPresented: Boolean,
    failed: Set<EpisodeId>,
): EpisodeId? {
    if (!positionResolved) return null
    val episode = state.anchor?.pageId?.episodeId ?: targetEpisode
    val plan = plans[episode] ?: return null
    return nextDocumentToPrepare(plan.manifest, plans, initialPresented, failed)
}
