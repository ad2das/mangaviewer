package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.ContentSource

internal class LibraryEpisodeWarmer(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val sources: SourceRegistry,
    private val repository: PageRepository,
    private val library: UserLibraryRepository,
) {
    private var job: Job? = null
    private var episodeId: EpisodeId? = null

    fun warm(target: EpisodeId) {
        if (episodeId == target) return
        cancel()
        episodeId = target
        job = scope.launch {
            var completed = false
            try {
                withContext(dispatcher) {
                    val source = sources.require(target.seriesId.sourceId)
                    coroutineScope {
                        // Position I/O is independent of provider authorization. Starting it here
                        // keeps an existing reading anchor from waiting behind the ACK/manifest
                        // path before the predicted opening viewport can be selected.
                        val savedPosition = async { library.readingPosition(target) }
                        source.prepare(target, PreparationIntent.INITIAL_VIEW)
                        val manifest = source.manifest(target)
                        val pageOrder = warmPageOrder(manifest, savedPosition.await())
                        val knownForward = if (
                            manifest.pages.size <= MAX_FULL_FORWARD_EPISODE_PAGES
                        ) {
                            source.knownForward(target, FORWARD_EPISODE_LIMIT)
                        } else emptyList()
                        val knownForwardWarm = knownForward.takeIf { it.isNotEmpty() }
                            ?.let { ordered -> async { warmForwardEpisodes(source, ordered) } }
                        warmOpeningAndAdjacent(
                            source,
                            manifest,
                            pageOrder,
                            knownForward,
                            knownForwardWarm,
                        )
                    }
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Viewer entry owns visible retry/error handling.
                android.util.Log.w("LibraryWarm", "warm failed target=${target.remoteKey}", failure)
            } finally {
                if (episodeId == target) {
                    job = null
                    if (!completed) episodeId = null
                }
            }
        }
    }

    private suspend fun warmOpeningAndAdjacent(
        source: ContentSource,
        manifest: EpisodeManifest,
        pageOrder: List<PageId>,
        knownForward: List<EpisodeId>,
        knownForwardWarm: kotlinx.coroutines.Deferred<Unit>?,
    ) = coroutineScope {
        // Every opening request must validate its signed image prefix before the browser changes
        // episode credentials. A single landing-page fence leaves its forward siblings vulnerable
        // to that cutover and one failed sibling cancels the whole structured warmup.
        val routeReady = pageOrder.map { CompletableDeferred<Unit>() }
        val pages = async { warmPages(pageOrder, routeReady) }
        val adjacent = async {
            val next = manifest.nextEpisodeId
            if (knownForwardWarm != null && knownForward.firstOrNull() == next) {
                knownForwardWarm.await()
            } else {
                knownForwardWarm?.cancel()
                routeReady.awaitAll()
                if (next != null && warmCoversForwardTail(manifest, pageOrder)) {
                    warmForwardEpisodes(source, listOf(next))
                }
            }
        }
        pages.await()
        adjacent.await()
    }

    private suspend fun warmPages(
        pageOrder: List<PageId>,
        routeReady: List<CompletableDeferred<Unit>>? = null,
        fixedPriority: PageFetchPriority? = null,
    ) {
        require(routeReady == null || routeReady.size == pageOrder.size)
        coroutineScope {
            pageOrder.mapIndexed { index, pageId ->
                async {
                    val ready = routeReady?.get(index)
                    try {
                        repository.get(
                            pageId,
                            fixedPriority ?: warmPagePriority(index),
                            speculative = true,
                            onNetworkResponseStarted = ready?.let { signal ->
                                { signal.complete(Unit) }
                            },
                        )
                        ready?.complete(Unit)
                    } catch (failure: Throwable) {
                        ready?.completeExceptionally(failure)
                        throw failure
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun warmForwardEpisodes(
        source: ContentSource,
        ordered: List<EpisodeId>,
    ) {
        android.util.Log.d(
            "LibraryWarm",
            "forward ordered=${ordered.joinToString { it.remoteKey }}",
        )
        // One provider browser owns one authorization document at a time. Walk the forward path
        // in order so each manifest starts the following preparation without rejected sibling
        // challenges competing with the opening viewport.
        for (episodeId in ordered) {
            source.prepare(episodeId, PreparationIntent.ADJACENT_FORWARD)
            if (!warmPreparedForwardEpisode(source, episodeId)) return
        }
    }

    private suspend fun warmPreparedForwardEpisode(
        source: ContentSource,
        episodeId: EpisodeId,
    ): Boolean = try {
        val manifest = source.manifest(episodeId)
        val fullyOwned = manifest.pages.size <= MAX_FULL_FORWARD_EPISODE_PAGES
        val pages = if (fullyOwned) manifest.pages.map { it.id }
            else warmPageOrder(manifest, saved = null)
        warmPages(pages, fixedPriority = PageFetchPriority.ADJACENT_FORWARD)
        fullyOwned
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    fun cancel() {
        job?.cancel()
        job = null
        episodeId = null
    }
}

internal fun warmTargetPage(manifest: EpisodeManifest, saved: ReadingPosition?): PageId =
    saved?.pageId?.takeIf { pageId -> manifest.pages.any { it.id == pageId } }
        ?: manifest.pages.first().id

/**
 * Predicts a bounded opening viewport group: the saved anchor (or the first page) and contiguous
 * forward pages. The actual viewer replaces this prediction with exact viewport geometry as soon
 * as it opens; no backward page is admitted merely because an anchor exists.
 */
internal fun warmPageOrder(manifest: EpisodeManifest, saved: ReadingPosition?): List<PageId> {
    val target = warmTargetPage(manifest, saved)
    val index = manifest.pages.indexOfFirst { it.id == target }
    check(index >= 0) { "Warm target must belong to the manifest" }
    return manifest.pages.subList(index, minOf(manifest.pages.size, index + WARM_PAGE_COUNT))
        .map { it.id }
}

internal fun warmCoversForwardTail(manifest: EpisodeManifest, warmedPages: List<PageId>): Boolean =
    warmedPages.isNotEmpty() && warmedPages.last() == manifest.pages.last().id

/**
 * Detail-screen prediction has one exact landing page. Its forward neighbors may start at the
 * same time, but remain forward work until real viewer geometry proves that they are visible.
 * This prevents predicted pages from competing with the landing page at transport-highest
 * priority; PageRepository promotes either flight in place when the viewer actually reaches it.
 */
internal fun warmPagePriority(index: Int): PageFetchPriority {
    require(index >= 0) { "Warm page index must not be negative" }
    return if (index == 0) PageFetchPriority.FOCUS else PageFetchPriority.FORWARD
}

private const val WARM_PAGE_COUNT = 6
private const val FORWARD_EPISODE_LIMIT = 6
private const val MAX_FULL_FORWARD_EPISODE_PAGES = 12
