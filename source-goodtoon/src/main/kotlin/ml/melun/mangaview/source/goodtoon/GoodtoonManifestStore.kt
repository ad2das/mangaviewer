package ml.melun.mangaview.source.goodtoon

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

internal data class GoodtoonManifestPayload(
    val manifest: EpisodeManifest,
    val pageUrls: Map<PageId, String>,
) {
    init {
        require(pageUrls.keys == manifest.pages.mapTo(linkedSetOf()) { it.id }) {
            "GoodToon manifest and page URLs must describe the same pages"
        }
        require(pageUrls.values.all(String::isNotBlank)) { "GoodToon page URL must not be blank" }
    }
}

internal data class GoodtoonManifestEntry(
    val payload: GoodtoonManifestPayload,
    val revision: Long,
)

internal sealed interface GoodtoonPageLookup {
    data object MissingEpisode : GoodtoonPageLookup
    data class MissingPage(val revision: Long) : GoodtoonPageLookup
    data class Found(val url: String, val revision: Long) : GoodtoonPageLookup
}

internal class GoodtoonManifestStore(
    private val capacity: Int,
    private val fetch: suspend (EpisodeId) -> GoodtoonManifestPayload,
) {
    init {
        require(capacity > 0) { "GoodToon manifest cache capacity must be positive" }
    }

    private val mutex = Mutex()
    private val cached = LinkedHashMap<EpisodeId, GoodtoonManifestEntry>(capacity, 0.75f, true)
    private val flights = mutableMapOf<EpisodeId, CompletableDeferred<GoodtoonManifestEntry>>()
    private var nextRevision = 1L

    suspend fun load(episodeId: EpisodeId): GoodtoonManifestEntry = load(episodeId, staleRevision = null)

    suspend fun refreshIfCurrent(episodeId: EpisodeId, staleRevision: Long): GoodtoonManifestEntry =
        load(episodeId, staleRevision)

    suspend fun page(pageId: PageId): GoodtoonPageLookup = mutex.withLock {
        val entry = cached[pageId.episodeId] ?: return@withLock GoodtoonPageLookup.MissingEpisode
        val url = entry.payload.pageUrls[pageId]
        if (url == null) GoodtoonPageLookup.MissingPage(entry.revision)
        else GoodtoonPageLookup.Found(url, entry.revision)
    }

    private suspend fun load(episodeId: EpisodeId, staleRevision: Long?): GoodtoonManifestEntry {
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
        val result = CompletableDeferred<GoodtoonManifestEntry>()
        flights[episodeId] = result
        Claim.Fetch(result)
    }

    private suspend fun fetchOwned(
        episodeId: EpisodeId,
        result: CompletableDeferred<GoodtoonManifestEntry>,
    ): GoodtoonManifestEntry = try {
        val payload = fetch(episodeId)
        require(payload.manifest.id == episodeId) { "GoodToon manifest fetch returned another episode" }
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
        payload: GoodtoonManifestPayload,
        result: CompletableDeferred<GoodtoonManifestEntry>,
    ): GoodtoonManifestEntry {
        val entry = GoodtoonManifestEntry(payload, nextRevision())
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
        result: CompletableDeferred<GoodtoonManifestEntry>,
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
        data class Cached(val entry: GoodtoonManifestEntry) : Claim
        data class Wait(val result: CompletableDeferred<GoodtoonManifestEntry>) : Claim
        data class Fetch(val result: CompletableDeferred<GoodtoonManifestEntry>) : Claim
    }

    private class FlightOwnerCancelledException : Exception()
}
