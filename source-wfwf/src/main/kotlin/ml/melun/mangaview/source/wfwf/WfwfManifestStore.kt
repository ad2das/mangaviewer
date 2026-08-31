package ml.melun.mangaview.source.wfwf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId

internal data class WfwfManifestPayload(
    val manifest: EpisodeManifest,
    val pageUrls: Map<PageId, String>,
) {
    init {
        require(pageUrls.keys == manifest.pages.mapTo(linkedSetOf()) { it.id }) {
            "WFWF manifest and page URLs must describe the same pages"
        }
        require(pageUrls.values.all(String::isNotBlank)) { "WFWF page URL must not be blank" }
    }
}

internal data class WfwfManifestEntry(
    val payload: WfwfManifestPayload,
    val revision: Long,
)

internal sealed interface WfwfPageLookup {
    data object MissingEpisode : WfwfPageLookup
    data class MissingPage(val revision: Long) : WfwfPageLookup
    data class Found(val url: String, val revision: Long) : WfwfPageLookup
}

internal class WfwfManifestStore(
    private val capacity: Int,
    private val fetch: suspend (EpisodeId) -> WfwfManifestPayload,
) {
    init {
        require(capacity > 0) { "WFWF manifest cache capacity must be positive" }
    }

    private val mutex = Mutex()
    private val cached = LinkedHashMap<EpisodeId, WfwfManifestEntry>(capacity, 0.75f, true)
    private val flights = mutableMapOf<EpisodeId, CompletableDeferred<WfwfManifestEntry>>()
    private var nextRevision = 1L

    suspend fun load(episodeId: EpisodeId): WfwfManifestEntry = load(episodeId, staleRevision = null)

    suspend fun refreshIfCurrent(episodeId: EpisodeId, staleRevision: Long): WfwfManifestEntry =
        load(episodeId, staleRevision)

    suspend fun page(pageId: PageId): WfwfPageLookup = mutex.withLock {
        val entry = cached[pageId.episodeId] ?: return@withLock WfwfPageLookup.MissingEpisode
        val url = entry.payload.pageUrls[pageId]
        if (url == null) WfwfPageLookup.MissingPage(entry.revision)
        else WfwfPageLookup.Found(url, entry.revision)
    }

    private suspend fun load(episodeId: EpisodeId, staleRevision: Long?): WfwfManifestEntry {
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val claim = claim(episodeId, staleRevision)) {
                is Claim.Cached -> return claim.entry
                is Claim.Wait -> try {
                    return claim.result.await()
                } catch (_: FlightOwnerCancelledException) {
                    continue
                }
                is Claim.Fetch -> return fetchOwned(episodeId, claim.result)
            }
        }
    }

    private suspend fun claim(episodeId: EpisodeId, staleRevision: Long?): Claim = mutex.withLock {
        val existing = cached[episodeId]
        if (existing != null && (staleRevision == null || existing.revision != staleRevision)) {
            return@withLock Claim.Cached(existing)
        }
        flights[episodeId]?.let { return@withLock Claim.Wait(it) }
        val result = CompletableDeferred<WfwfManifestEntry>()
        flights[episodeId] = result
        Claim.Fetch(result)
    }

    private suspend fun fetchOwned(
        episodeId: EpisodeId,
        result: CompletableDeferred<WfwfManifestEntry>,
    ): WfwfManifestEntry = try {
        val payload = fetch(episodeId)
        require(payload.manifest.id == episodeId) { "WFWF manifest fetch returned another episode" }
        val entry = mutex.withLock { publish(episodeId, payload, result) }
        result.complete(entry)
        entry
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { abandon(episodeId, result) }
        throw cancelled
    } catch (failure: Throwable) {
        abandon(episodeId, result, failure)
        throw failure
    }

    private fun publish(
        episodeId: EpisodeId,
        payload: WfwfManifestPayload,
        result: CompletableDeferred<WfwfManifestEntry>,
    ): WfwfManifestEntry {
        val entry = WfwfManifestEntry(payload, nextRevision())
        cached[episodeId] = entry
        if (flights[episodeId] === result) flights.remove(episodeId)
        while (cached.size > capacity) cached.entries.iterator().run {
            next()
            remove()
        }
        return entry
    }

    private suspend fun abandon(
        episodeId: EpisodeId,
        result: CompletableDeferred<WfwfManifestEntry>,
        failure: Throwable = FlightOwnerCancelledException(),
    ) {
        mutex.withLock {
            if (flights[episodeId] === result) flights.remove(episodeId)
        }
        result.completeExceptionally(failure)
    }

    private fun nextRevision(): Long {
        val revision = nextRevision
        nextRevision = if (revision == Long.MAX_VALUE) 1L else revision + 1L
        return revision
    }

    private sealed interface Claim {
        data class Cached(val entry: WfwfManifestEntry) : Claim
        data class Wait(val result: CompletableDeferred<WfwfManifestEntry>) : Claim
        data class Fetch(val result: CompletableDeferred<WfwfManifestEntry>) : Claim
    }

    private class FlightOwnerCancelledException : Exception()
}
