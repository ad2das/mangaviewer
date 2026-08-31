package ml.melun.mangaview.source.ntk

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec

internal data class NtkPreparedEpisode(
    val pages: List<PageSpec>,
    val requests: Map<PageId, NtkPageRequest>,
    val title: String? = null,
    val previousEpisodeId: EpisodeId? = null,
    val nextEpisodeId: EpisodeId? = null,
    val previousKnown: Boolean = false,
    val nextKnown: Boolean = false,
)

/**
 * Small source-owned LRU with a per-episode single-flight. External manifest and ACK work never
 * holds [mutex], so a slow adjacent episode cannot block current-episode page lookup.
 */
internal class NtkPreparedEpisodeStore(
    private val maximumEpisodes: Int = 6,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<EpisodeId, NtkPreparedEpisode>(maximumEpisodes, 0.75f, true)
    private val flights = mutableMapOf<EpisodeId, CompletableDeferred<NtkPreparedEpisode>>()

    init {
        require(maximumEpisodes > 0) { "Prepared episode capacity must be positive" }
    }

    suspend fun contains(episodeId: EpisodeId): Boolean = mutex.withLock {
        entries.containsKey(episodeId)
    }

    suspend fun resolve(
        episodeId: EpisodeId,
        load: suspend () -> NtkPreparedEpisode,
    ): NtkPreparedEpisode {
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val claim = claim(episodeId)) {
                is Claim.Cached -> return claim.episode
                is Claim.Wait -> try {
                    return claim.result.await()
                } catch (_: FlightOwnerCancelledException) {
                    continue
                }
                is Claim.Load -> return loadOwned(episodeId, claim.result, load)
            }
        }
    }

    suspend fun request(pageId: PageId): NtkPageRequest? = mutex.withLock {
        entries[pageId.episodeId]?.requests?.get(pageId)
    }

    private suspend fun claim(episodeId: EpisodeId): Claim = mutex.withLock {
        entries[episodeId]?.let { return@withLock Claim.Cached(it) }
        flights[episodeId]?.let { return@withLock Claim.Wait(it) }
        val result = CompletableDeferred<NtkPreparedEpisode>()
        flights[episodeId] = result
        Claim.Load(result)
    }

    private suspend fun loadOwned(
        episodeId: EpisodeId,
        result: CompletableDeferred<NtkPreparedEpisode>,
        load: suspend () -> NtkPreparedEpisode,
    ): NtkPreparedEpisode = try {
        val prepared = load().also(::validate)
        mutex.withLock {
            entries[episodeId] = prepared
            trimEntries()
            removeFlight(episodeId, result)
        }
        result.complete(prepared)
        prepared
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            mutex.withLock { removeFlight(episodeId, result) }
            result.completeExceptionally(FlightOwnerCancelledException())
        }
        throw cancelled
    } catch (failure: Throwable) {
        mutex.withLock { removeFlight(episodeId, result) }
        result.completeExceptionally(failure)
        throw failure
    }

    private fun validate(prepared: NtkPreparedEpisode) {
        require(prepared.pages.isNotEmpty()) { "Prepared episode must contain pages" }
        require(prepared.requests.keys == prepared.pages.mapTo(mutableSetOf()) { it.id }) {
            "Prepared page requests must match page specs"
        }
    }

    private fun trimEntries() {
        while (entries.size > maximumEpisodes) entries.remove(entries.entries.first().key)
    }

    private fun removeFlight(
        episodeId: EpisodeId,
        expected: CompletableDeferred<NtkPreparedEpisode>,
    ) {
        if (flights[episodeId] === expected) flights.remove(episodeId)
    }

    private sealed interface Claim {
        data class Cached(val episode: NtkPreparedEpisode) : Claim
        data class Wait(val result: CompletableDeferred<NtkPreparedEpisode>) : Claim
        data class Load(val result: CompletableDeferred<NtkPreparedEpisode>) : Claim
    }

    private class FlightOwnerCancelledException : Exception()
}
