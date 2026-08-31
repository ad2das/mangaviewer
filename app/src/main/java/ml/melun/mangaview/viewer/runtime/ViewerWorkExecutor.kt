package ml.melun.mangaview.viewer.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.cache.PageCacheKey
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.viewer.EpisodeOperationToken
import ml.melun.mangaview.viewer.OperationToken
import ml.melun.mangaview.viewer.PixelBand
import ml.melun.mangaview.viewer.PixelRef
import ml.melun.mangaview.viewer.VerifiedPageRef
import ml.melun.mangaview.viewer.ViewerCommand
import ml.melun.mangaview.viewer.ViewerEvent
import ml.melun.mangaview.viewer.ViewerWorkPort
import ml.melun.mangaview.viewer.WorkPriority

internal class ViewerWorkExecutor(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val hardDecodeDispatcher: CoroutineDispatcher,
    private val warmDecodeDispatcher: CoroutineDispatcher,
    private val source: ContentSource,
    private val repository: PageRepository,
    private val tilePool: NativeTilePool,
    private val eventSink: (ViewerEvent) -> Unit,
    private val retirePixel: (PixelRef) -> Unit,
) : ViewerWorkPort {
    private val ownership = ViewerWorkerOwnership()
    private val verifiedPages = VerifiedPageHandoffStore()
    private val stopping = AtomicBoolean(false)

    override fun accept(command: ViewerCommand) {
        if (stopping.get()) return
        when (command) {
            is ViewerCommand.LoadNextEpisode -> loadNextEpisode(command.token)
            is ViewerCommand.FetchPage -> fetch(command.token)
            is ViewerCommand.DecodePage -> decode(command.token, command.encoded, command.band)
            is ViewerCommand.CancelDecode -> cancel(command.token)
            is ViewerCommand.CancelGeneration -> cancelGeneration(command.generation)
            is ViewerCommand.ReleasePixel -> retirePixel(command.pixel)
        }
    }

    fun recycle(pixel: PixelRef) {
        tilePool.recycle(pixel)
    }

    fun compact() {
        val job = ownership.registerMaintenance {
            scope.launch(warmDecodeDispatcher, start = CoroutineStart.LAZY) { tilePool.compact() }
        }
        job?.start()
    }

    fun pauseDecodes() {
        ownership.pauseDecodes()
    }

    fun resumeDecodes() {
        ownership.resumeDecodes()
    }

    fun shutdown(afterWorkersStop: () -> Unit) {
        if (!stopping.compareAndSet(false, true)) return
        val active = ownership.beginShutdown()
        CoroutineScope(NonCancellable + warmDecodeDispatcher).launch {
            active.joinAll()
            ownership.finishShutdown()
            verifiedPages.clear()
            afterWorkersStop()
        }
    }

    internal fun workerSnapshot(): ViewerWorkerSnapshot = ownership.snapshot().copy(
        verifiedHandoffs = verifiedPages.size(),
    )

    private fun fetch(token: OperationToken) {
        launchUnique(token, ioDispatcher) {
            val started = System.nanoTime()
            try {
                val cached = repository.get(token.pageId) {
                    eventSink(ViewerEvent.FetchResponseStarted(token, System.nanoTime()))
                }
                // CachedPage is metadata plus a File handle, not the encoded body. Keeping a
                // bounded descriptor LRU avoids Room and header reads for every decoded band.
                verifiedPages.remember(cached)
                eventSink(ViewerEvent.FetchSucceeded(
                    token = token,
                    encoded = VerifiedPageRef(
                        cacheKey = PageCacheKey.of(token.pageId),
                        byteCount = cached.byteCount,
                        sha256 = cached.sha256,
                        dimensions = cached.dimensions,
                    ),
                    elapsedMillis = elapsedMillis(started),
                    atNanos = System.nanoTime(),
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                android.util.Log.w(
                    "ViewerPipeline",
                    "fetch failed page=${token.pageId.remoteKey} attempt=${token.attempt}: " +
                        (failure.message ?: failure.javaClass.simpleName),
                )
                eventSink(fetchFailure(token, failure))
            }
        }
    }

    private fun loadNextEpisode(token: EpisodeOperationToken) {
        launchUniqueEpisode(token) {
            try {
                source.prepare(token.targetEpisodeId, PreparationIntent.ADJACENT_FORWARD)
                val manifest = source.manifest(token.targetEpisodeId)
                require(manifest.id == token.targetEpisodeId) {
                    "Source returned a different adjacent episode"
                }
                eventSink(ViewerEvent.NextEpisodeSucceeded(
                    token = token,
                    manifest = manifest,
                    atNanos = System.nanoTime(),
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                android.util.Log.w(
                    "ViewerEpisodeAppend",
                    "load failed from=${token.fromEpisodeId.remoteKey} " +
                        "target=${token.targetEpisodeId.remoteKey} attempt=${token.attempt}: " +
                        (failure.message ?: failure.javaClass.simpleName),
                )
                eventSink(ViewerEvent.NextEpisodeFailed(
                    token = token,
                    reason = failure.message?.takeIf(String::isNotBlank)
                        ?: failure.javaClass.simpleName,
                    retryDelayNanos = retryDelayNanos(token.attempt),
                    atNanos = System.nanoTime(),
                ))
            }
        }
    }

    private fun decode(token: OperationToken, encoded: VerifiedPageRef, band: PixelBand) {
        launchUnique(token, decodeDispatcherFor(token.priority)) {
            coroutineContext.ensureActive()
            decodeLocked(token, encoded, band)
        }
    }

    private fun decodeDispatcherFor(priority: WorkPriority): CoroutineDispatcher =
        if (priority == WorkPriority.HARD) hardDecodeDispatcher else warmDecodeDispatcher

    private suspend fun decodeLocked(
        token: OperationToken,
        encoded: VerifiedPageRef,
        band: PixelBand,
    ) {
        val started = System.nanoTime()
        try {
            val cached = verifiedPage(token, encoded, band)
            val pixel = tilePool.decodeBand(cached.file, cached.dimensions, band)
            try {
                coroutineContext.ensureActive()
            } catch (cancelled: CancellationException) {
                tilePool.recycle(pixel)
                throw cancelled
            }
            eventSink(ViewerEvent.DecodeSucceeded(
                token = token,
                pixel = pixel,
                elapsedMillis = elapsedMillis(started),
                atNanos = System.nanoTime(),
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            android.util.Log.w(
                "ViewerDecode",
                "failed page=${token.pageId.remoteKey}: " +
                    (failure.message ?: failure.javaClass.simpleName),
            )
            eventSink(decodeFailure(token, failure))
        }
    }

    private suspend fun verifiedPage(
        token: OperationToken,
        encoded: VerifiedPageRef,
        band: PixelBand,
    ): CachedPage {
        val handedOff = verifiedPages.find(token.pageId, encoded)
        val cached = handedOff ?: repository.get(token.pageId).also(verifiedPages::remember)
        check(cached.byteCount == encoded.byteCount && cached.sha256 == encoded.sha256) {
            "Cached page identity changed before decode"
        }
        check(encoded.dimensions == null || cached.dimensions == encoded.dimensions) {
            "Cached page dimensions changed before decode"
        }
        return cached
    }

    private fun launchUnique(
        token: OperationToken,
        dispatcher: CoroutineDispatcher,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val job = ownership.registerPage(token) { predecessor ->
            scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                predecessor?.join()
                coroutineContext.ensureActive()
                block()
            }
        }
        job?.start()
    }

    private fun launchUniqueEpisode(
        token: EpisodeOperationToken,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val job = ownership.registerEpisode(token) { predecessor ->
            scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                predecessor?.join()
                coroutineContext.ensureActive()
                block()
            }
        }
        job?.start()
    }

    private fun cancel(token: OperationToken) {
        ownership.cancel(token)
    }

    private fun cancelGeneration(generation: Long) {
        ownership.cancelGeneration(generation)
    }

    private fun fetchFailure(token: OperationToken, failure: Throwable): ViewerEvent.FetchFailed =
        ViewerEvent.FetchFailed(
            token = token,
            reason = failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName,
            retryDelayNanos = retryDelayNanos(token.attempt),
            atNanos = System.nanoTime(),
        )

    private fun decodeFailure(token: OperationToken, failure: Throwable): ViewerEvent.DecodeFailed =
        ViewerEvent.DecodeFailed(
            token = token,
            reason = failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName,
            retryDelayNanos = retryDelayNanos(token.attempt),
            atNanos = System.nanoTime(),
        )

    private fun retryDelayNanos(attempt: Int): Long {
        val milliseconds = when (attempt) {
            1 -> 250L
            2 -> 750L
            3 -> 2_000L
            else -> 5_000L
        }
        return milliseconds * NANOS_PER_MILLISECOND
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

}
