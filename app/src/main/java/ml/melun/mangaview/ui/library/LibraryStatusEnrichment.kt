package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.source.SeriesStatus

import kotlinx.coroutines.CoroutineScope

/** Owns the paced status queue and its batched UI updates for one library lifetime. */
internal class LibraryStatusEnrichment(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val sourceRegistry: SourceRegistry,
    private val update: (((LibraryState) -> LibraryState) -> Unit),
) {
    private var statusWorker: Job? = null
    private val statusQueue = ArrayDeque<SeriesId>()
    private val statusCache = mutableMapOf<SeriesId, SeriesStatus?>()
    private val statusUnsupported = mutableSetOf<SourceId>()
    private val pendingStatusUpdates = mutableMapOf<SeriesId, SeriesStatus>()
    private var statusFlushJob: Job? = null
    /**
     * Providers that omit the series status from catalog markup (newxtoon) are filled in from
     * series detail pages: one request at a time, paced, cached, and skipped when the provider
     * does not expose details at all (ntk/wfwf).
     */
    fun enrich(series: List<SourceSeries>) {
        val cached = series.mapNotNull { item ->
            statusCache[item.id]?.takeIf { item.status == null }?.let { status -> item.id to status }
        }
        if (cached.isNotEmpty()) {
            update { state ->
                cached.fold(state) { patched, (id, status) -> patched.withSeriesStatus(id, status) }
            }
        }
        val added = series.asSequence()
            .filter { it.status == null && it.id.sourceId !in statusUnsupported }
            .map { it.id }
            .filter { !statusCache.containsKey(it) && it !in statusQueue }
            .toList()
        if (added.isEmpty()) return
        statusQueue.addAll(added)
        if (statusWorker?.isActive == true) return
        statusWorker = scope.launch {
            while (true) {
                val id = statusQueue.removeFirstOrNull()
                if (id == null) {
                    flushStatusUpdates()
                    break
                }
                val source = runCatching { sourceRegistry.require(id.sourceId) }.getOrNull() ?: continue
                val details = runCatching {
                    withContext(ioDispatcher) { source.seriesDetails(id) }
                }.getOrNull()
                if (details == null) {
                    statusUnsupported += id.sourceId
                    statusQueue.removeAll { it.sourceId == id.sourceId }
                    continue
                }
                statusCache[id] = details.status
                details.status?.let { status -> queueStatusUpdate(id, status) }
                delay(STATUS_ENRICH_DELAY_MILLIS)
            }
        }
    }

    private fun queueStatusUpdate(id: SeriesId, status: SeriesStatus) {
        pendingStatusUpdates[id] = status
        if (statusFlushJob?.isActive == true) return
        statusFlushJob = scope.launch {
            delay(STATUS_FLUSH_INTERVAL_MILLIS)
            flushStatusUpdates()
        }
    }

    private fun flushStatusUpdates() {
        if (pendingStatusUpdates.isEmpty()) return
        val batch = pendingStatusUpdates.toMap()
        pendingStatusUpdates.clear()
        update { state ->
            batch.entries.fold(state) { patched, (id, status) -> patched.withSeriesStatus(id, status) }
        }
    }

    fun cancel() {
        statusWorker?.cancel()
        statusWorker = null
        statusQueue.clear()
        flushStatusUpdates()
        statusFlushJob?.cancel()
        statusFlushJob = null
    }

}

private const val STATUS_ENRICH_DELAY_MILLIS = 250L
private const val STATUS_FLUSH_INTERVAL_MILLIS = 500L
