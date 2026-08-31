package ml.melun.mangaview.source.wfwf

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

internal class WfwfCatalogStore(
    private val fetch: suspend (SeriesId) -> List<SourceEpisode>,
) {
    private val mutex = Mutex()
    private var cached: Map<SeriesId, List<SourceEpisode>> = emptyMap()
    private var flights: Map<SeriesId, CompletableDeferred<List<SourceEpisode>>> = emptyMap()

    suspend fun load(seriesId: SeriesId, refresh: Boolean): List<SourceEpisode> {
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val claim = claim(seriesId, refresh)) {
                is Claim.Cached -> return claim.value
                is Claim.Wait -> try {
                    return claim.result.await()
                } catch (_: FlightOwnerCancelledException) {
                    continue
                }
                is Claim.Fetch -> return fetchOwned(seriesId, claim.result)
            }
        }
    }

    private suspend fun claim(seriesId: SeriesId, refresh: Boolean): Claim = mutex.withLock {
        if (!refresh) cached[seriesId]?.let { return@withLock Claim.Cached(it) }
        flights[seriesId]?.let { return@withLock Claim.Wait(it) }
        val result = CompletableDeferred<List<SourceEpisode>>()
        flights = flights + (seriesId to result)
        Claim.Fetch(result)
    }

    private suspend fun fetchOwned(
        seriesId: SeriesId,
        result: CompletableDeferred<List<SourceEpisode>>,
    ): List<SourceEpisode> = try {
        val loaded = fetch(seriesId)
        mutex.withLock {
            cached = cached + (seriesId to loaded)
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

    private class FlightOwnerCancelledException : Exception()
}
