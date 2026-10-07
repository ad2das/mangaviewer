package ml.melun.mangaview.engine.runtime

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.content.PageMissingException
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

// Page acceptance and failure handling for [EngineSessionRuntime], kept beside it so the runtime
// class stays inside the architecture size gate. Owner-thread confined like the runtime itself.

internal fun EngineSessionRuntime.acceptPageGeometry(generation: Long, id: PageId, plan: EpisodeAccessPlan, metadata: WorkMetadata) {
    if (!isCurrent(generation) || plans[id.episodeId] !== plan) return
    val geometry = metadata as? WorkMetadata.PageGeometry ?: return
    require(geometry.pageId == id && geometry.contentRevision == plan.contentRevision)
    if (!restoreUnavailable(generation, id, geometry.dimensions)) return
    if (matchesVerifiedGeometry(pages[id], geometry) && id in prepared && id !in failedReadAheadPages) return
    // Only a body transfer publishes geometry; a storage lookup never does, so a fact that reaches
    // a fresh (not yet prepared) demand marks it as a network fetch for the latency estimate.
    horizon.fetchLatency.network(id)
    demandVersion++
    earlyTransfers.observed(id)
    val update = try {
        session.dispatch(SessionEvent.DimensionsResolved(generation, id, geometry.dimensions))
    } catch (stale: UnknownPageDimensionsException) {
        // The document was pruned while this read-ahead demand was in flight. The result is
        // inert: forget any earlier acceptance so a later delivery of the document re-derives
        // this page's geometry instead of trusting a stale prepared marker.
        forgetAcceptedPage(id, prepared, pages) { pages = it }
        return
    }
    process(update)
}


internal fun EngineSessionRuntime.acceptPage(generation: Long, expected: PageId, plan: EpisodeAccessPlan, page: StoredPage) {
    require(page.pageId == expected && page.contentRevision == plan.contentRevision)
    if (!restoreUnavailable(generation, expected, page.dimensions)) return
    val identity = PageContentIdentity(expected, page.contentRevision, page.sha256, page.dimensions, page.byteCount)
    // Reacquiring the same verified original changes subscription ownership, not visible content.
    if (pages[expected] == identity && expected in prepared && expected !in failedReadAheadPages) return
    horizon.fetchLatency.complete(expected) { observationClock() }
    prepared += expected
    failedReadAheadPages -= expected
    missingPages -= expected
    pageFailureCounts -= expected
    val update = try {
        session.dispatch(SessionEvent.DimensionsResolved(generation, expected, page.dimensions))
    } catch (stale: UnknownPageDimensionsException) {
        // See acceptPageGeometry: the document was pruned while this demand was in flight.
        forgetAcceptedPage(expected, prepared, pages) { pages = it }
        return
    }
    if (pages[expected] != identity) pages = withEntry(pages, expected, identity)
    launch.onPageAccepted(expected, page, identity, generation, update.snapshot)
    process(update)
}


/**
 * A page whose provider candidates keep failing while the geometry still needs it is declared
 * unavailable for this session once its attempts reach the bound: the runtime publishes
 * placeholder geometry so the reader can continue past it and stops demanding the page. The
 * counter survives the reader bouncing away, so a boundary stall cannot reset the decision; it
 * clears only when the original is actually accepted.
 */
internal fun EngineSessionRuntime.handlePageFailure(id: PageId, cause: Throwable) {
    markPageFailure(id, failedReadAheadPages) { process(SessionUpdate(session.snapshot)) }
    horizon.fetchLatency.abandon(id)
    val failures = (pageFailureCounts[id] ?: 0) + 1
    pageFailureCounts[id] = failures
    // A definitive miss is knowledge about the provider, not about this demand: remember it even
    // while the page is only read-ahead, so the demand that later makes it required publishes the
    // placeholder immediately instead of retrying the 404.
    if (cause is PageMissingException) missingPages += id
    // Idle retries back off and stop after a bound; reading on reconciles again anyway.
    armPageRetryWake(failures)
    if (id in unavailablePages) return
    val state = session.snapshot
    // A definitive miss (every candidate answered 404/410) means the provider has no original:
    // declare the placeholder at the first round trip instead of waiting out the failure bound.
    // Anything else keeps the ordinary transient-failure semantics.
    if (cause !is PageMissingException && failures < PAGE_UNAVAILABLE_FAILURES) return
    if (id !in state.requiredDimensions) return
    declareUnavailable(id, state)
}

/**
 * Publishes placeholder geometry for a page the provider cannot serve and stops demanding it until
 * an original is accepted. False when the document was pruned before the dispatch.
 */
internal fun EngineSessionRuntime.declareUnavailable(id: PageId, state: EngineSessionSnapshot): Boolean {
    if (id in unavailablePages) return false
    val update = try {
        session.dispatch(
            SessionEvent.DimensionsResolved(state.generation, id, unavailableDimensions(id, state)),
        )
    } catch (stale: UnknownPageDimensionsException) {
        return false
    }
    unavailablePages += id
    process(update)
    return true
}

/**
 * Pages whose definitive miss arrived while they were only read-ahead: the geometry requires them
 * now, so publish the already-known placeholder before the demand rebuild asks the provider again.
 * Called from the reconcile path. [declareUnavailable] marks the runtime dirty instead of
 * reconciling, so the enclosing process loop discards the stale demand list it is building and
 * re-runs against the updated snapshot: no demand ever reaches the work set carrying a page the
 * placeholder already covers.
 */
internal fun EngineSessionRuntime.promoteDefinitiveMisses(state: EngineSessionSnapshot) {
    if (missingPages.isEmpty()) return
    state.requiredDimensions.forEach { id ->
        if (id in missingPages) declareUnavailable(id, state)
    }
}

/**
 * The original of a page declared unavailable arrived after all: its dimensions replace the
 * placeholder's so the page renders. False when the document was pruned meanwhile.
 */
private fun EngineSessionRuntime.restoreUnavailable(generation: Long, id: PageId, dimensions: PageDimensions): Boolean {
    if (id !in unavailablePages) return true
    val update = try {
        session.dispatch(SessionEvent.DimensionsResolved(generation, id, dimensions, replacesPlaceholder = true))
    } catch (stale: UnknownPageDimensionsException) {
        return false
    }
    unavailablePages -= id
    missingPages -= id
    demandVersion++
    process(update)
    return true
}

/**
 * Placeholder geometry for an unavailable page. A neighbor's dimensions keep the layout's width
 * scale consistent with the rest of the document; without one, a viewport-sized block.
 */
private fun EngineSessionRuntime.unavailableDimensions(id: PageId, state: EngineSessionSnapshot): PageDimensions {
    val manifest = plans[id.episodeId]?.manifest
    val index = manifest?.pages?.indexOfFirst { it.id == id } ?: -1
    if (manifest != null && index >= 0) {
        for (offset in listOf(1, -1)) {
            val neighbor = manifest.pages.getOrNull(index + offset)?.id ?: continue
            pages[neighbor]?.dimensions?.let { return it }
        }
    }
    return PageDimensions(state.viewport.widthPx, state.viewport.heightPx)
}
