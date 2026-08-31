package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.PageFetchPriority

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
                    source.prepare(target, PreparationIntent.INITIAL_VIEW)
                    val manifest = source.manifest(target)
                    val pageOrder = warmPageOrder(
                        manifest,
                        library.readingPosition(target)?.pageId,
                    )
                    pageOrder.forEach { pageId ->
                        repository.get(
                            pageId,
                            PageFetchPriority.FORWARD,
                        )
                    }
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Viewer entry owns visible retry/error handling.
            } finally {
                if (episodeId == target) {
                    job = null
                    if (!completed) episodeId = null
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        episodeId = null
    }
}

internal fun warmTargetPage(manifest: EpisodeManifest, saved: PageId?): PageId =
    saved?.takeIf { pageId -> manifest.pages.any { it.id == pageId } }
        ?: manifest.pages.first().id

/**
 * Warms only the most likely reading direction. Every entry is awaited before the next begins, so
 * forward preparation can never compete with the page the reader is currently waiting to see.
 */
internal fun warmPageOrder(manifest: EpisodeManifest, saved: PageId?): List<PageId> {
    val target = warmTargetPage(manifest, saved)
    val index = manifest.pages.indexOfFirst { it.id == target }
    check(index >= 0) { "Warm target must belong to the manifest" }
    return manifest.pages.subList(index, minOf(manifest.pages.size, index + WARM_PAGE_COUNT))
        .map { it.id }
}

private const val WARM_PAGE_COUNT = 3
