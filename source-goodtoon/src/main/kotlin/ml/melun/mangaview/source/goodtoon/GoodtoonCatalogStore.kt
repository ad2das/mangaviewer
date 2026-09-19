package ml.melun.mangaview.source.goodtoon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SourceEpisode

internal class GoodtoonCatalogStore(
    private val fetchProgressively: (suspend (SeriesId, suspend (List<SourceEpisode>) -> Unit) -> List<SourceEpisode>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (SeriesId) -> List<SourceEpisode>,
) {
    private val mutex = Mutex()
    private val cached = LinkedHashMap<SeriesId, CachedCatalog>(16, 0.75f, true)
    private var flights: Map<SeriesId, CompletableDeferred<List<SourceEpisode>>> = emptyMap()

    suspend fun load(
        seriesId: SeriesId, refresh: Boolean,
        onPartial: suspend (List<SourceEpisode>) -> Unit = {},
    ): List<SourceEpisode> {
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val claim = claim(seriesId, refresh)) {
                is Claim.Cached -> return claim.value
                is Claim.Wait -> try {
                    return claim.result.await()
                } catch (_: FlightOwnerCancelledException) {
                    continue
                }
                is Claim.Fetch -> return fetchOwned(seriesId, claim.result, onPartial)
            }
        }
    }

    private suspend fun claim(seriesId: SeriesId, refresh: Boolean): Claim = mutex.withLock {
        if (!refresh) {
            // An unbounded, expiry-less map grew one episode list per series forever and served
            // arbitrarily stale catalogs; entries retire on TTL and the map stays bounded.
            cached[seriesId]?.takeIf { clock() - it.savedAt in 0..CATALOG_TTL_MILLIS }
                ?.let { return@withLock Claim.Cached(it.episodes) }
        }
        flights[seriesId]?.let { return@withLock Claim.Wait(it) }
        val result = CompletableDeferred<List<SourceEpisode>>()
        flights = flights + (seriesId to result)
        Claim.Fetch(result)
    }

    private suspend fun fetchOwned(
        seriesId: SeriesId,
        result: CompletableDeferred<List<SourceEpisode>>,
        onPartial: suspend (List<SourceEpisode>) -> Unit,
    ): List<SourceEpisode> = try {
        val loaded = fetchProgressively?.invoke(seriesId, onPartial) ?: fetch(seriesId)
        mutex.withLock {
            cached[seriesId] = CachedCatalog(loaded, clock())
            while (cached.size > MAX_CACHED_CATALOGS) cached.remove(cached.keys.first())
            removeFlight(seriesId, result)
        }
        result.complete(loaded)
        loaded
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            mutex.withLock { removeFlight(seriesId, result) }
            result.completeExceptionally(FlightOwnerCancelledException())
        }
        throw cancelled
    } catch (failure: Throwable) {
        mutex.withLock { removeFlight(seriesId, result) }
        result.completeExceptionally(failure)
        throw failure
    }

    private fun removeFlight(
        seriesId: SeriesId,
        expected: CompletableDeferred<List<SourceEpisode>>,
    ) {
        if (flights[seriesId] === expected) flights = flights - seriesId
    }

    private sealed interface Claim {
        data class Cached(val value: List<SourceEpisode>) : Claim
        data class Wait(val result: CompletableDeferred<List<SourceEpisode>>) : Claim
        data class Fetch(val result: CompletableDeferred<List<SourceEpisode>>) : Claim
    }

    private data class CachedCatalog(val episodes: List<SourceEpisode>, val savedAt: Long)

    private class FlightOwnerCancelledException : Exception()

    private companion object {
        const val CATALOG_TTL_MILLIS = 10 * 60_000L
        const val MAX_CACHED_CATALOGS = 32
    }
}
