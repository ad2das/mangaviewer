package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.bumptech.glide.Glide
import com.google.gson.Gson
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.Utils
import ml.melun.mangaview.glide.ViewerBitmapTrim
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.model.PageItem
import ml.melun.mangaview.repository.CacheFileStore
import ml.melun.mangaview.repository.EpisodeSnapshotCache
import ml.melun.mangaview.repository.MangaRepository
import ml.melun.mangaview.runtime.MainThreadStallMonitor
import java.io.File
import java.io.InterruptedIOException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

class ReaderSession(
    private val context: Context,
    private val manga: Manga,
    private val title: Title?,
    private val viewerWidth: Int,
    private val viewerHeight: Int,
    private val autoCut: Boolean,
    private val reverse: Boolean,
    preparedKey: String?,
    private val startAtFirstPage: Boolean = false,
    private val listener: Listener,
    private val imageRepository: ReaderImageRepository = LegacyReaderImageRepository
) {
    interface Listener {
        fun onPagesReady(count: Int)
        fun onPagesAppended(count: Int)
        fun onPagesPrepended(count: Int, insertedCount: Int)
        fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int)
        fun onInitialPage(index: Int)
        fun onPageLoading(index: Int)
        fun onPageBoundsReady(index: Int, width: Int, height: Int)
        fun onPageReady(index: Int, bitmap: Bitmap)
        fun onPageTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>)
        fun onPageCard(index: Int, title: String)
        fun onPageError(index: Int, message: String)
        fun onPageCleared(index: Int)
        fun onMessage(message: String)
        fun onCaptchaRequired(manga: Manga)
        fun onBoundaryAppendFinished(anchor: Int, direction: Int, silent: Boolean, suppressedCaptcha: Boolean)
    }

    enum class AppendStartResult {
        STARTED,
        BUSY,
        CANCELLED
    }

    data class PageInfo(
        val manga: Manga,
        val title: String,
        val localPage: Int,
        val totalPages: Int,
        val sourcePageIndex: Int,
        val side: Int,
        val layoutReady: Boolean,
        val transitionCard: Boolean
    )

    private data class PageRef(
        val manga: Manga,
        var image: String?,
        val transitionTitle: String? = null,
        val sourceIndex: Int = 0,
        var pageIndex: Int = -1,
        val localPage: Int = 0,
        var totalPages: Int = 0,
        val side: Int = PAGE_SIDE_FIRST,
        val allowAutoSplit: Boolean = true
    )

    private data class AppendUrlLoad(
        val result: Int,
        val urls: List<String>
    )

    private data class BitmapRelease(
        val index: Int,
        val bitmap: Bitmap?,
        val clearPage: Boolean
    )

    private sealed class PageDecodeResult {
        abstract val width: Int

        data class Full(val bitmap: Bitmap) : PageDecodeResult() {
            override val width: Int = bitmap.width
        }

        data class Tiles(
            val pageWidth: Int,
            val pageHeight: Int,
            val decodedWidth: Int,
            val tiles: List<ReaderTile>
        ) : PageDecodeResult() {
            override val width: Int = decodedWidth
        }
    }

    private data class WindowCommand(
        val first: Int,
        val last: Int,
        val anchor: Int,
        val busy: Boolean
    )

    private data class Delivery(
        val index: Int,
        val page: PageRef,
        val result: PageDecodeResult,
        val startedAt: Long,
        val requestedWidth: Int,
        val retainWhenBusy: Boolean = false
    )

    private data class PreparedDelivery(
        val bitmap: Bitmap,
        val owned: Boolean
    )

    private data class InitialFetchOutcome(
        val result: Int,
        val installedEarly: Boolean
    )

    private class CachedEpisodeSnapshot {
        var episodes: ArrayList<Manga>? = null
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mainImmediate =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Handler.createAsync(Looper.getMainLooper()) else main
    private val network = Executors.newFixedThreadPool(
        ReaderPipelinePolicy.FOREGROUND_NETWORK_PARALLELISM,
        readerThreadFactory("ReaderNetwork", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val decode = Executors.newFixedThreadPool(
        ReaderPipelinePolicy.IDLE_DECODE_PARALLELISM,
        readerThreadFactory("ReaderDecode", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val anchorNetwork = Executors.newSingleThreadExecutor(
        readerThreadFactory("ReaderAnchorNetwork", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val anchorDecode = Executors.newSingleThreadExecutor(
        readerThreadFactory("ReaderAnchorDecode", Process.THREAD_PRIORITY_DEFAULT)
    )
    private val adjacentNetwork = Executors.newFixedThreadPool(
        ADJACENT_PIPELINE_PARALLELISM,
        readerThreadFactory("ReaderAdjacentNetwork", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val urgentNetwork = Executors.newFixedThreadPool(
        URGENT_VISIBLE_PIPELINE_PARALLELISM,
        readerThreadFactory("ReaderUrgentNetwork", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val urgentDecode = Executors.newFixedThreadPool(
        URGENT_VISIBLE_PIPELINE_PARALLELISM,
        readerThreadFactory("ReaderUrgentDecode", Process.THREAD_PRIORITY_DEFAULT)
    )
    private val primeNetwork = Executors.newFixedThreadPool(
        PRIME_PIPELINE_PARALLELISM,
        readerThreadFactory("ReaderPrimeNetwork", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val primeDecode = Executors.newFixedThreadPool(
        PRIME_PIPELINE_PARALLELISM,
        readerThreadFactory("ReaderPrimeDecode", Process.THREAD_PRIORITY_DEFAULT)
    )
    private val cleanup = Executors.newSingleThreadExecutor(
        readerThreadFactory("ReaderCleanup", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val control = Executors.newSingleThreadExecutor(
        readerThreadFactory("ReaderControl", Process.THREAD_PRIORITY_BACKGROUND)
    )
    private val busyDecodeGate = Semaphore(ReaderPipelinePolicy.BUSY_DECODE_PARALLELISM)
    private val idleDecodeGate = Semaphore(ReaderPipelinePolicy.IDLE_DECODE_PARALLELISM)
    private val cancelled = AtomicBoolean(false)
    private val imageCancellation = ReaderImageCache.Cancellation()
    private val pages = ArrayList<PageRef>()
    private val pagesLock = Object()
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val loadingPages = ConcurrentHashMap<Int, PageRef>()
    private val urgentLoading = ConcurrentHashMap.newKeySet<Int>()
    private val urgentLoadingPages = ConcurrentHashMap<Int, PageRef>()
    private val bytePrefetching = ConcurrentHashMap.newKeySet<Int>()
    private val preAnchorFallbackRetries = ConcurrentHashMap.newKeySet<Int>()
    private val visibleGeneratedByteHedges = ConcurrentHashMap.newKeySet<Int>()
    private val visibleGeneratedDecodeHedges = ConcurrentHashMap.newKeySet<Int>()
    private val idleFullWidthUpgradeScheduled = ConcurrentHashMap.newKeySet<Int>()
    private val failedPages = ConcurrentHashMap.newKeySet<Int>()
    private val transientGeneratedRetries = ConcurrentHashMap<Int, Int>()
    private val decodedWidths = ConcurrentHashMap<Int, Int>()
    private val desiredWidths = ConcurrentHashMap<Int, Int>()
    private val inFlightWidths = ConcurrentHashMap<Int, Int>()
    private val pendingDeliveryWidths = ConcurrentHashMap<Int, Int>()
    private val listenerDrawableDeliveries = ConcurrentHashMap.newKeySet<Int>()
    private val sourceWidths = ConcurrentHashMap<Int, Int>()
    private val achievableWidths = ConcurrentHashMap<Int, Int>()
    private val deliveryQueue = ConcurrentLinkedQueue<Delivery>()
    private val primedDeliveryBacklog = ConcurrentHashMap<Int, Delivery>()
    private val initialDeliveryBacklog = ConcurrentHashMap<Int, Delivery>()
    private val initialPreparedBacklog = ConcurrentHashMap<Int, PreparedDelivery>()
    private val initialContinuousPostedWidths = ConcurrentHashMap<Int, Int>()
    private val deliveryDrainPosted = AtomicBoolean(false)
    private val initialDeliveryFallbackPosted = AtomicBoolean(false)
    private val initialDeliveryFlushInProgress = AtomicBoolean(false)
    private val currentViewportAnchor = AtomicInteger(-1)
    private val initialAnchorCoalesceDelayed = AtomicBoolean(false)
    private val initialPagesReadyDelivered = AtomicBoolean(false)
    private val initialFullAppendDeferredUntilFirstBitmap = AtomicBoolean(false)
    private val structurePublishPending = AtomicInteger(0)
    private val viewportBusy = AtomicBoolean(false)
    private val deliveryResumeAtMs = AtomicLong(0L)
    private val lastUserInteractionMs = AtomicLong(0L)
    private val ntkFirstBitmapAtMs = AtomicLong(0L)
    private val earlyPreparedBitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val deliveredBitmaps = LinkedHashMap<Int, Bitmap>(32, 0.75f, true)
    private val deliveredTiles = LinkedHashMap<Int, List<ReaderTile>>(16, 0.75f, true)
    private val deliveredOwned = HashSet<Int>()
    private var retainedFirstPage = 0
    private var retainedLastPage = 0
    private var retainedAnchorPage = 0
    private val firstBitmapLogged = AtomicBoolean(false)
    private val initialFanoutStarted = AtomicBoolean(false)
    private val initialNearAfterAnchorDecodeStarted = AtomicBoolean(false)
    @Volatile
    private var ntkCoordinator: NtkEpisodeCoordinator? = null
    private val pendingInitialFanoutPage = AtomicInteger(-1)
    private val resolvedInitialStartPage = AtomicInteger(-1)
    private val ntkGeneratedFullBytePrefetchCursor = AtomicInteger(-1)
    private val windowGeneration = AtomicInteger(0)
    private val nextLoading = AtomicBoolean(false)
    private val previousAppendLoading = AtomicBoolean(false)
    private val nextAppendLoading = AtomicBoolean(false)
    private val adjacentMissingRefreshes = ConcurrentHashMap.newKeySet<String>()
    private val adjacentMissingTargets = ConcurrentHashMap.newKeySet<String>()
    private val ntkAdjacentAckPreflightPaths = ConcurrentHashMap.newKeySet<String>()
    private val ntkEpisodeMetadataLoading = AtomicBoolean(false)
    private val deferredAdjacentPrepareScheduled = AtomicBoolean(false)
    private val deferredAdjacentPrepareAnchor = AtomicInteger(-1)
    private val deferredAdjacentPrepareDirection = AtomicInteger(0)
    private val ntkAdjacentAfterAckReleaseAtMs = AtomicLong(0L)
    private val timelinePrimeLoading = AtomicBoolean(false)
    private val timelinePrimeRequested = AtomicBoolean(false)
    private val repositoryLoading = AtomicBoolean(false)
    private val repositoryCancellations = ConcurrentHashMap.newKeySet<MangaRepository.Cancellation>()
    private val windowLock = Object()
    private var lastWindowAnchor = -1
    private var lastWindowDirection = 0
    private var lastSourcePrefetchAnchor = -1
    private val controlLock = Object()
    private var pendingWindowCommand: WindowCommand? = null
    private var windowCommandPosted = false
    private val preparedEntry = ReaderPreparedStore.findReadyCompatible(preparedKey) ?: ReaderPreparedStore.get(preparedKey)
    private val preparedStoreKey = preparedEntry?.key ?: preparedKey
    private val clearPreparedBitmapsRunnable = Runnable {
        val key = preparedStoreKey ?: return@Runnable
        if (!cancelled.get() && ReaderPreparedStore.get(key) === preparedEntry) {
            ReaderPreparedStore.clearBitmaps(key)
        }
    }
    private val deliveryDrainRunnable = Runnable { drainDecodeDeliveries() }
    private val pagesInstalled = AtomicBoolean(false)
    private val preparedListener = object : ReaderPreparedStore.Listener {
        override fun onUrlsReady(images: List<String>, startPage: Int) {
            val resolvedStartPage = resolvePreparedStartPage(startPage)
            installImages(images, resolvedStartPage, false)
            flushEarlyPreparedBitmaps()
            releasePreparedStoreBitmapsSoon()
            requestPageForeground(resolvedStartPage)
            requestInitialFanout(resolvedStartPage)
        }

        override fun onBitmapReady(index: Int, bitmap: Bitmap) {
            if (!pagesInstalled.get()) {
                val snapshot = preparedEntry?.snapshot()
                val urls = snapshot?.images
                if (!urls.isNullOrEmpty()) {
                    val resolvedStartPage = resolvePreparedStartPage(snapshot.startPage)
                    installImages(urls, resolvedStartPage, false)
                    ViewerWarmupManager.logMetric("reader_prepared_bitmap_installed_pages", 1L)
                    deliverPreparedSourceBitmap(index, bitmap)
                    flushEarlyPreparedBitmaps()
                    releasePreparedStoreBitmapsSoon()
                    requestPageForeground(resolvedStartPage)
                    requestInitialFanout(resolvedStartPage)
                    return
                }
                if (!bitmap.isRecycled) earlyPreparedBitmaps[index] = bitmap
                return
            }
            deliverPreparedSourceBitmap(index, bitmap)
        }

        override fun onFailed() {
            if (!pagesInstalled.get()) loadFromRepository()
        }
    }

    fun start() {
        val entry = preparedEntry
        if (entry != null) {
            val snapshot = entry.addListener(preparedListener)
            ViewerWarmupManager.logMetric(
                "reader_prepared_start_" + snapshot.status.name.lowercase(java.util.Locale.ROOT),
                snapshot.bitmaps.size.toLong()
            )
            if (installPreparedSnapshot(snapshot)) return
            if (snapshot.status != ReaderPreparedStore.Status.FAILED) {
                loadFromRepository()
                return
            }
        } else if (!preparedStoreKey.isNullOrEmpty()) {
            ViewerWarmupManager.logMetric("reader_prepared_start_missing", 1L)
        }
        loadFromRepository()
    }

    private fun loadFromRepository() {
        if (!repositoryLoading.compareAndSet(false, true)) return
        val queuedAt = SystemClock.elapsedRealtime()
        network.execute {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                logNtkRepositoryStage(manga, "load_start", "queueMs=${startedAt - queuedAt}")
                attachTitle()
                if (isNtkSource(manga, title)) {
                    scheduleNtkAdjacentAckPreflightsAfterFirstBitmap(includeLookahead = false)
                }
                var activeManga = manga
                var urls = imageRepository.imageUrls(activeManga, appContext)
                logNtkRepositoryStage(
                    activeManga,
                    "cached_urls_checked",
                    "count=${urls?.size ?: 0},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                if (urls.isNullOrEmpty()) {
                    val cancellation = repositoryCancellation(userVisible = true)
                    val ntkInitial = isNtkSource(manga, title)
                    if (ntkInitial) cancellation.prioritizeWebViewFallback()
                    val initialFetch = if (ntkInitial) {
                        fetchInitialAllowingEarlyNtkUrls(activeManga, cancellation, startedAt)
                    } else {
                        try {
                            InitialFetchOutcome(imageRepository.fetchViewerInitial(activeManga, cancellation), false)
                        } finally {
                            releaseRepositoryCancellation(cancellation)
                        }
                    }
                    if (initialFetch.installedEarly) {
                        return@execute
                    }
                    val result = initialFetch.result
                    logNtkRepositoryStage(activeManga, "fetch_initial_done", "result=$result,ms=${SystemClock.elapsedRealtime() - startedAt}")
                    if (cancelled.get()) return@execute
                    if (result != Title.LOAD_OK && result != Title.LOAD_CAPTCHA) {
                        resolveInitialNtkUnavailableEpisode(activeManga)?.let { replacement ->
                            activeManga = replacement.first
                            urls = replacement.second
                            Log.d(
                                TAG,
                                "ntk_initial_unavailable_replaced sourcePath=${manga.ntkEpisodePath} " +
                                    "targetPath=${activeManga.ntkEpisodePath} images=${urls.size}"
                            )
                            val startPage = 0
                            startInitialForegroundStreamIfNeeded(activeManga, urls, startPage, startedAt)
                            installImagesForManga(activeManga, urls, startPage, false)
                            flushEarlyPreparedBitmaps()
                            requestPageForeground(startPage)
                            requestInitialContinuousPagesFromEarlyUrls(startPage, urls.size)
                            requestInitialFanout(startPage)
                            return@execute
                        }
                    }
                    if (result != Title.LOAD_OK) {
                        if (result == Title.LOAD_CAPTCHA) {
                            postCaptchaRequired(manga)
                            return@execute
                        }
                        postInitialPageError("이미지를 불러오지 못했습니다")
                        return@execute
                    }
                    urls = imageRepository.imageUrls(activeManga, appContext)
                }
                if (urls.isNullOrEmpty()) {
                    postInitialPageError("표시할 이미지가 없습니다")
                    return@execute
                }
                if (installEarlyGeneratedNtkUrlsIfBoardOnly(activeManga, urls, startedAt)) {
                    return@execute
                }
                val startPage = requestedStartPage().coerceIn(0, urls.lastIndex)
                startInitialForegroundStreamIfNeeded(activeManga, urls, startPage, startedAt)
                logNtkRepositoryStage(activeManga, "urls_ready", "count=${urls.size},ms=${SystemClock.elapsedRealtime() - startedAt}")
                installImagesForManga(activeManga, urls, startPage, false)
                flushEarlyPreparedBitmaps()
                logNtkRepositoryStage(activeManga, "request_foreground", "page=$startPage,ms=${SystemClock.elapsedRealtime() - startedAt}")
                requestPageForeground(startPage)
                requestInitialContinuousPagesFromEarlyUrls(startPage, urls.size)
                requestInitialFanout(startPage)
            } catch (e: Exception) {
                recordIfUnexpected(e)
                if (!isExpectedCancellation(e)) postInitialPageError("이미지를 불러오지 못했습니다")
            } finally {
                repositoryLoading.set(false)
            }
        }
    }

    private fun fetchInitialAllowingEarlyNtkUrls(
        target: Manga,
        cancellation: MangaRepository.Cancellation,
        loadStartedAt: Long
    ): InitialFetchOutcome {
        val task = FutureTask {
            imageRepository.fetchViewerInitial(target, cancellation)
        }
        try {
            network.execute(task)
        } catch (_: RejectedExecutionException) {
            return try {
                InitialFetchOutcome(imageRepository.fetchViewerInitial(target, cancellation), false)
            } finally {
                releaseRepositoryCancellation(cancellation)
            }
        }

        val deadline = loadStartedAt + NTK_EARLY_URL_HANDOFF_WAIT_MS
        var releaseDeferred = false
        try {
            while (!cancelled.get() && !task.isDone && SystemClock.elapsedRealtime() < deadline) {
                val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
                if (earlyUrls.size >= ntkEarlyUrlMinCount() && installEarlyNtkUrls(target, earlyUrls, loadStartedAt)) {
                    releaseDeferred = true
                    finishInitialFetchAfterEarlyInstall(target, task, cancellation, loadStartedAt)
                    return InitialFetchOutcome(Title.LOAD_OK, true)
                }
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
            if (isNtkSource(target, title) && !task.isDone) {
                val lateDeadline = loadStartedAt + NTK_EARLY_URL_LATE_HANDOFF_WAIT_MS
                while (!cancelled.get() && !task.isDone && SystemClock.elapsedRealtime() < lateDeadline) {
                    val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
                    if (earlyUrls.size >= ntkEarlyUrlMinCount() &&
                        installEarlyNtkUrls(target, earlyUrls, loadStartedAt)
                    ) {
                        releaseDeferred = true
                        finishInitialFetchAfterEarlyInstall(target, task, cancellation, loadStartedAt)
                        logNtkRepositoryStage(
                            target,
                            "early_urls_before_fetch_done_late",
                            "count=${earlyUrls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                        )
                        return InitialFetchOutcome(Title.LOAD_OK, true)
                    }
                    try {
                        Thread.sleep(NTK_EARLY_URL_POLL_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
            val result = task.get()
            val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
            if (earlyUrls.size >= ntkEarlyUrlMinCount() && installEarlyNtkUrls(target, earlyUrls, loadStartedAt)) {
                releaseDeferred = true
                finishInitialFetchAfterEarlyInstall(target, task, cancellation, loadStartedAt)
                logNtkRepositoryStage(
                    target,
                    "early_urls_after_fetch_done",
                    "count=${earlyUrls.size},result=$result,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                return InitialFetchOutcome(Title.LOAD_OK, true)
            }
            val shouldKeepPollingForEarlyUrls =
                result != Title.LOAD_OK ||
                    (isNtkSource(target, title) && imageRepository.imageUrls(target, appContext).isNullOrEmpty())
            if (shouldKeepPollingForEarlyUrls) {
                while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                    val delayedEarlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
                    if (delayedEarlyUrls.size >= ntkEarlyUrlMinCount() &&
                        installEarlyNtkUrls(target, delayedEarlyUrls, loadStartedAt)
                    ) {
                        releaseDeferred = true
                        finishInitialFetchAfterEarlyInstall(target, task, cancellation, loadStartedAt)
                        logNtkRepositoryStage(
                            target,
                            "early_urls_after_fetch_done_late",
                            "count=${delayedEarlyUrls.size},result=$result,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                        )
                        return InitialFetchOutcome(Title.LOAD_OK, true)
                    }
                    if (result == Title.LOAD_OK &&
                        !imageRepository.imageUrls(target, appContext).isNullOrEmpty()
                    ) {
                        break
                    }
                    try {
                        Thread.sleep(NTK_EARLY_URL_POLL_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
            return InitialFetchOutcome(result, false)
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            if (!releaseDeferred) releaseRepositoryCancellation(cancellation)
        }
    }

    private fun installEarlyNtkUrls(target: Manga, urls: List<String>, loadStartedAt: Long): Boolean {
        if (cancelled.get() || pagesInstalled.get()) return false
        val initialUrls = if (shouldKeepManhwaGeneratedEarlyToObservedUrls(target, urls)) {
            urls
        } else if (shouldDeferGeneratedEarlyExpansionBeforeFirstBitmap(target, urls)) {
            expandInitialVerifiedGeneratedEarlyUrls(target, urls)
        } else {
            expandVerifiedGeneratedEarlyUrls(target, urls)
        }
        val startPage = requestedStartPage().coerceIn(0, initialUrls.lastIndex)
        logNtkRepositoryStage(
            target,
            "early_urls_ready",
            "count=${initialUrls.size},raw=${urls.size},page=$startPage,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        startInitialForegroundStreamIfNeeded(target, initialUrls, startPage, loadStartedAt)
        installImagesForManga(target, initialUrls, startPage, false)
        scheduleGeneratedEarlyExpansionAfterFirstBitmap(target, initialUrls, loadStartedAt)
        appendExpandedEarlyNtkUrlsUntilFirstBitmap(target, initialUrls.size, loadStartedAt)
        flushEarlyPreparedBitmaps()
        logNtkRepositoryStage(
            target,
            "request_foreground",
            "source=early,page=$startPage,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        requestPageForeground(startPage)
        requestInitialContinuousPagesFromEarlyUrls(startPage, initialUrls.size)
        if (initialUrls.size <= startPage + 1) requestInitialFanout(startPage)
        return true
    }

    private fun scheduleGeneratedEarlyExpansionAfterFirstBitmap(
        target: Manga,
        urls: List<String>,
        loadStartedAt: Long
    ) {
        if (!isNtkSource(target, title) || urls.isEmpty()) return
        if (shouldKeepManhwaGeneratedEarlyToObservedUrls(target, urls)) {
            scheduleObservedManhwaGeneratedExpansionAfterFirstBitmap(target, urls.size, loadStartedAt)
            return
        }
        control.execute {
            val deadline = SystemClock.elapsedRealtime() + NTK_EARLY_GENERATED_EXPAND_AFTER_FIRST_BITMAP_WAIT_MS
            while (!cancelled.get() && !firstBitmapLogged.get() && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            if (cancelled.get() || !firstBitmapLogged.get()) return@execute
            val expanded = expandVerifiedGeneratedEarlyUrls(target, urls)
            if (expanded.size <= urls.size) return@execute
            appendInitialNtkUrlsAfterEarlyInstall(target, expanded, loadStartedAt)
            logNtkRepositoryStage(
                target,
                "early_urls_generated_expand_after_first",
                "count=${expanded.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
        }
    }

    private fun appendExpandedEarlyNtkUrlsUntilFirstBitmap(
        target: Manga,
        initialInstalledCount: Int,
        loadStartedAt: Long
    ) {
        if (!isNtkSource(target, title) || initialInstalledCount <= 0) return
        control.execute {
            var installedCount = initialInstalledCount
            var installedStartImage = synchronized(pagesLock) { pages.getOrNull(currentStartPage())?.image }
            val deadline = SystemClock.elapsedRealtime() + NTK_EARLY_GENERATED_EXPAND_BEFORE_FIRST_BITMAP_WAIT_MS
            while (!cancelled.get() && !firstBitmapLogged.get() && SystemClock.elapsedRealtime() < deadline) {
                val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
                val startPage = currentStartPage()
                val incomingStartImage = earlyUrls.getOrNull(startPage)
                val startImageChanged = isSameInitialGeneratedPageReplacement(installedStartImage, incomingStartImage)
                if (earlyUrls.size > installedCount || startImageChanged) {
                    appendInitialNtkUrlsAfterEarlyInstall(
                        target,
                        earlyUrls,
                        loadStartedAt,
                        allowFirstBitmapDefer = !startImageChanged
                    )
                    installedCount = maxOf(installedCount, earlyUrls.size)
                    installedStartImage = incomingStartImage
                    logNtkRepositoryStage(
                        target,
                        if (startImageChanged) {
                            "early_urls_anchor_replace_before_first"
                        } else {
                            "early_urls_generated_expand_before_first"
                        },
                        "count=$installedCount,page=$startPage,first=${incomingStartImage?.substringAfterLast('/') ?: ""}," +
                            "ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), installedCount)
                    requestPageForeground(currentStartPage())
                }
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
        }
    }

    private fun isSameInitialGeneratedPageReplacement(current: String?, incoming: String?): Boolean {
        if (current.isNullOrBlank() || incoming.isNullOrBlank() || current == incoming) return false
        if (isSameInitialProtectedCdnReplacement(current, incoming)) return true
        if (!isNtkGeneratedImageUrl(current) || !isNtkGeneratedImageUrl(incoming)) return false
        val currentPage = ntkGeneratedPageNumber(current) ?: return false
        val incomingPage = ntkGeneratedPageNumber(incoming) ?: return false
        return currentPage == incomingPage &&
            incomingPage in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES &&
            !isNtkBoardUploadImageUrl(current) &&
            !isNtkBoardUploadImageUrl(incoming)
    }

    private fun isSameInitialProtectedCdnReplacement(current: String, incoming: String): Boolean {
        val path = manga.ntkEpisodePath.orEmpty()
        if (!Regex("^/webtoon/[^0-9/?#][^/?#]*/[^/?#]+$", RegexOption.IGNORE_CASE).matches(path)) {
            return false
        }
        if (isNtkGeneratedImageUrl(current) || isNtkGeneratedImageUrl(incoming)) return false
        if (isNtkBoardUploadImageUrl(current) || isNtkBoardUploadImageUrl(incoming)) return false
        return isNtkProtectedCdnImageUrl(current) && isNtkProtectedCdnImageUrl(incoming)
    }

    private fun shouldDeferGeneratedEarlyExpansionBeforeFirstBitmap(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (urls.none { isNtkGeneratedImageUrl(it) }) return false
        return urls.size < NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD
    }

    private fun shouldKeepManhwaGeneratedEarlyToObservedUrls(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (!isNtkManhwaEpisodePath(target.ntkEpisodePath)) return false
        if (urls.size != 1) return false
        return urls.all { isNtkGeneratedImageUrl(it) }
    }

    private fun expandVerifiedGeneratedEarlyUrls(target: Manga, urls: List<String>): List<String> {
        if (!isNtkSource(target, title) || urls.isEmpty()) return urls
        val seed = urls.firstOrNull { isNtkGeneratedImageUrl(it) } ?: return urls
        val knownCount = target.ntkImageCount
        val desiredCount = ntkGeneratedEarlyExpandCount(target, urls.size, knownCount)
        if (desiredCount <= urls.size) return urls
        val expanded = ArrayList<String>(desiredCount)
        for (page in 1..desiredCount) {
            val generated = ntkGeneratedImageUrlForTarget(seed, target, page) ?: break
            expanded.add(generated)
        }
        if (expanded.size <= urls.size) return urls
        target.setNtkImageCount(expanded.size)
        logNtkRepositoryStage(
            target,
            "early_urls_generated_expand",
            "from=${urls.size},to=${expanded.size},known=$knownCount"
        )
        return expanded
    }

    private fun expandInitialVerifiedGeneratedEarlyUrls(target: Manga, urls: List<String>): List<String> {
        if (!isNtkSource(target, title) || urls.isEmpty()) return urls
        val seed = urls.firstOrNull { isNtkGeneratedImageUrl(it) } ?: return urls
        val knownCount = target.ntkImageCount
        val desiredCount = when {
            knownCount > urls.size && knownCount <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD -> knownCount
            knownCount > urls.size -> minOf(knownCount, NTK_GENERATED_INITIAL_RECOVERY_PAGES)
            else -> maxOf(urls.size, NTK_GENERATED_INITIAL_RECOVERY_PAGES)
        }
        if (desiredCount <= urls.size) return urls
        val expanded = ArrayList<String>(desiredCount)
        for (page in 1..desiredCount) {
            val generated = ntkGeneratedImageUrlForTarget(seed, target, page) ?: break
            expanded.add(generated)
        }
        if (expanded.size <= urls.size) return urls
        target.setNtkImageCount(maxOf(knownCount, expanded.size))
        logNtkRepositoryStage(
            target,
            "early_urls_generated_initial_expand",
            "from=${urls.size},to=${expanded.size},known=$knownCount"
        )
        return expanded
    }

    private fun ntkGeneratedEarlyExpandCount(target: Manga, currentCount: Int, knownCount: Int): Int {
        if (target.baseMode == MTitle.base_comic && !isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            val comicLimit = maxOf(currentCount, NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD)
            return when {
                knownCount > currentCount -> minOf(knownCount, comicLimit)
                else -> comicLimit
            }
        }
        if (isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            return when {
                knownCount > currentCount && currentCount > NTK_GENERATED_INITIAL_RECOVERY_PAGES -> knownCount
                else -> currentCount
            }
        }
        return when {
            knownCount > currentCount -> knownCount
            currentCount >= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD -> currentCount
            else -> currentCount
        }
    }

    private fun isNtkManhwaOrWebtoonEpisodePath(path: String?): Boolean {
        return NTK_EPISODE_PATH.matchEntire(path.orEmpty()) != null
    }

    private fun isNtkManhwaEpisodePath(path: String?): Boolean {
        return path.orEmpty().startsWith("/manhwa/", ignoreCase = true)
    }

    private fun installEarlyGeneratedNtkUrlsIfBoardOnly(target: Manga, urls: List<String>, loadStartedAt: Long): Boolean {
        if (!isNtkSource(target, title) || urls.none { isNtkBoardUploadImageUrl(it) }) return false
        logNtkRepositoryStage(
            target,
            "board_only_install_original",
            "count=${urls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        return installEarlyNtkUrls(target, urls, loadStartedAt)
    }

    private fun requestInitialContinuousPagesFromEarlyUrls(startPage: Int, urlCount: Int) {
        if (!isNtkSource(manga, title)) return
        val effectiveUrlCount = if (!firstBitmapLogged.get()) {
            if (isNtkSyntheticEpisodePath(manga.ntkEpisodePath)) {
                minOf(urlCount, startPage + NTK_SYNTHETIC_INITIAL_VISIBLE_PAGES)
            } else if (isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)) {
                minOf(urlCount, startPage + NTK_GENERATED_INITIAL_RECOVERY_PAGES)
            } else {
                urlCount
            }
        } else {
            urlCount
        }
        if (!firstBitmapLogged.get()) {
            if (effectiveUrlCount <= startPage + 1) {
                logNtkRepositoryStage(
                    manga,
                    "early_urls_initial_continuous_defer_until_anchor",
                    "start=$startPage,count=$urlCount,effective=$effectiveUrlCount"
                )
                return
            }
            logNtkRepositoryStage(
                manga,
                "early_urls_initial_continuous_allow_anchor_fallback",
                "start=$startPage,count=$urlCount,effective=$effectiveUrlCount"
            )
        }
        val count = synchronized(pagesLock) { pages.size }
        if (startPage > 0) {
            val firstPrevious = (startPage - NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES).coerceAtLeast(0)
            for (index in firstPrevious until startPage) {
                requestPage(
                    index,
                    busy = true,
                    anchor = false,
                    generation = FOREGROUND_PRIME_WARM_GENERATION
                )
            }
        }
        val last = minOf(count - 1, effectiveUrlCount - 1, startPage + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1)
        if (last <= startPage) return
        for (index in (startPage + 1)..last) {
            val delayMs = NTK_INITIAL_CONTINUOUS_STAGGER_MS * (index - startPage)
            val busy = index <= startPage + NTK_INITIAL_CONTINUOUS_BUSY_PAGES
            if (delayMs <= 0L) {
                requestPage(index, busy = busy, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
            } else {
                main.postDelayed({
                    if (!cancelled.get()) {
                        requestPage(index, busy = busy, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
                    }
                }, delayMs)
            }
        }
        Log.d(
            TAG,
            "reader_ntk_initial_continuous_request start=$startPage,count=${last - startPage}," +
                "staggerMs=$NTK_INITIAL_CONTINUOUS_STAGGER_MS"
        )
        ViewerWarmupManager.logMetric("reader_ntk_initial_continuous_request", (last - startPage).toLong())
    }

    private fun finishInitialFetchAfterEarlyInstall(
        target: Manga,
        task: FutureTask<Int>,
        cancellation: MangaRepository.Cancellation,
        loadStartedAt: Long
    ) {
        network.execute {
            try {
                if (shouldDeferInitialFullFetchAfterEarlyInstall(target)) {
                    logNtkRepositoryStage(
                        target,
                        "fetch_initial_after_early_keep_inflight",
                        "ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    if (shouldCancelInitialFullFetchDuringAnchor(target)) {
                        cancellation.cancel()
                        task.cancel(true)
                        logNtkRepositoryStage(
                            target,
                            "fetch_initial_after_early_cancel_for_anchor",
                            "ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                        )
                        waitForFirstBitmapBeforeInitialFullFetch(target, loadStartedAt)
                        if (!cancelled.get()) fetchInitialFullAfterEarlyInstall(target, loadStartedAt)
                        return@execute
                    }
                    waitForFirstBitmapBeforeInitialFullFetch(target, loadStartedAt)
                    if (cancelled.get()) {
                        cancellation.cancel()
                        task.cancel(true)
                        return@execute
                    }
                    val result = task.get()
                    val urls = imageRepository.imageUrls(target, appContext)
                    logNtkRepositoryStage(
                        target,
                        "fetch_initial_done_after_early_inflight",
                        "result=$result,count=${urls?.size ?: 0},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    if (!cancelled.get() && result == Title.LOAD_OK && !urls.isNullOrEmpty()) {
                        appendInitialNtkUrlsAfterEarlyInstall(target, urls, loadStartedAt)
                    }
                    return@execute
                }
                appendExpandedEarlyNtkUrlsBeforeInitialFetchDone(
                    target,
                    initialInstalledCount = synchronized(pagesLock) { pages.size },
                    task,
                    loadStartedAt
                )
                val result = task.get()
                val urls = imageRepository.imageUrls(target, appContext)
                logNtkRepositoryStage(
                    target,
                    "fetch_initial_done_after_early",
                    "result=$result,count=${urls?.size ?: 0},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                if (!cancelled.get() && result == Title.LOAD_OK && !urls.isNullOrEmpty()) {
                    appendInitialNtkUrlsAfterEarlyInstall(target, urls, loadStartedAt)
                }
            } catch (e: Exception) {
                recordIfUnexpected(e)
                logNtkRepositoryStage(
                    target,
                    "fetch_initial_done_after_early_error",
                    "error=${e.javaClass.simpleName},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
            } finally {
                releaseRepositoryCancellation(cancellation)
            }
        }
    }

    private fun shouldDeferInitialFullFetchAfterEarlyInstall(target: Manga): Boolean {
        return isNtkSource(target, title) && !firstBitmapLogged.get()
    }

    private fun shouldCancelInitialFullFetchDuringAnchor(target: Manga): Boolean {
        if (!isNtkSource(target, title) || firstBitmapLogged.get()) return false
        if (target.ntkImageCount <= 0) return false
        return synchronized(pagesLock) {
            pages.isNotEmpty() && pages.all { ref ->
                ref.transitionTitle != null || isNtkGeneratedImageUrl(ref.image.orEmpty())
            }
        }
    }

    private fun waitForFirstBitmapBeforeInitialFullFetch(target: Manga, loadStartedAt: Long) {
        val deadline = loadStartedAt + NTK_INITIAL_FULL_FETCH_AFTER_EARLY_DEFER_MS
        while (!cancelled.get() && !firstBitmapLogged.get() && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(NTK_EARLY_URL_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        logNtkRepositoryStage(
            target,
            "fetch_initial_after_early_defer_full_done",
            "firstBitmap=${firstBitmapLogged.get()},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
    }

    private fun fetchInitialFullAfterEarlyInstall(target: Manga, loadStartedAt: Long) {
        val fullCancellation = repositoryCancellation(userVisible = false)
        if (isNtkSource(target, title)) fullCancellation.prioritizeWebViewFallback()
        try {
            val result = imageRepository.fetchViewerInitial(target, fullCancellation)
            val urls = imageRepository.imageUrls(target, appContext)
            logNtkRepositoryStage(
                target,
                "fetch_initial_done_after_early_deferred_full",
                "result=$result,count=${urls?.size ?: 0},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            if (!cancelled.get() && result == Title.LOAD_OK && !urls.isNullOrEmpty()) {
                appendInitialNtkUrlsAfterEarlyInstall(target, urls, loadStartedAt)
            }
        } catch (e: Exception) {
            recordIfUnexpected(e)
            logNtkRepositoryStage(
                target,
                "fetch_initial_done_after_early_deferred_full_error",
                "error=${e.javaClass.simpleName},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
        } finally {
            releaseRepositoryCancellation(fullCancellation)
        }
    }

    private fun appendExpandedEarlyNtkUrlsBeforeInitialFetchDone(
        target: Manga,
        initialInstalledCount: Int,
        task: FutureTask<Int>,
        loadStartedAt: Long
    ) {
        if (!isNtkSource(target, title) || initialInstalledCount <= 0) return
        var installedCount = initialInstalledCount
        val deadline = SystemClock.elapsedRealtime() + NTK_EARLY_URL_EXPANSION_WAIT_MS
        while (!cancelled.get() && !task.isDone && SystemClock.elapsedRealtime() < deadline) {
            val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
            if (earlyUrls.size > installedCount) {
                appendInitialNtkUrlsAfterEarlyInstall(target, earlyUrls, loadStartedAt)
                installedCount = earlyUrls.size
                logNtkRepositoryStage(
                    target,
                    "early_urls_expand_before_fetch_done",
                    "count=$installedCount,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), installedCount)
            }
            try {
                Thread.sleep(NTK_EARLY_URL_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun appendInitialNtkUrlsAfterEarlyInstall(
        target: Manga,
        urls: List<String>,
        loadStartedAt: Long,
        allowFirstBitmapDefer: Boolean = true
    ) {
        if (cancelled.get() || urls.isEmpty()) return
        val verifiedApiUrls = ReaderImageCache.cachedNtkApiFallbackImages(target.ntkEpisodePath)
        val knownGeneratedCount = target.ntkImageCount
        val shouldReplaceWithShortVerifiedApi =
            verifiedApiUrls.isNotEmpty() &&
                verifiedApiUrls.size < urls.size &&
                (
                    knownGeneratedCount <= 0 ||
                        verifiedApiUrls.size >= knownGeneratedCount ||
                        urls.none { isNtkGeneratedImageUrl(it) }
                )
        val observedManhwaGeneratedUrls = if (shouldKeepManhwaGeneratedAppendToObservedUrls(target, urls)) {
            observedInitialManhwaGeneratedUrls(target, urls)
        } else {
            emptyList()
        }
        if (observedManhwaGeneratedUrls.isNotEmpty()) {
            val installedGeneratedCount = installedGeneratedPageCountForCurrentEpisode()
            if (firstBitmapLogged.get() && installedGeneratedCount > observedManhwaGeneratedUrls.size) {
                Log.d(
                    TAG,
                    "reader_ntk_generated_full_skip_observed_shrink path=${target.ntkEpisodePath}," +
                        "installed=$installedGeneratedCount,observed=${observedManhwaGeneratedUrls.size}," +
                        "incoming=${urls.size}"
                )
                return
            }
        }
        var sourceUrls = if (observedManhwaGeneratedUrls.isNotEmpty()) {
            Log.d(
                TAG,
                "reader_ntk_generated_full_keep_observed_manhwa path=${target.ntkEpisodePath}," +
                    "from=${urls.size},to=${observedManhwaGeneratedUrls.size}"
            )
            observedManhwaGeneratedUrls
        } else if (shouldReplaceWithShortVerifiedApi) {
            Log.d(
                TAG,
                "reader_ntk_generated_full_replace_with_verified_api path=${target.ntkEpisodePath}," +
                    "from=${urls.size},to=${verifiedApiUrls.size}"
            )
            verifiedApiUrls
        } else {
            if (verifiedApiUrls.isNotEmpty() && verifiedApiUrls.size < urls.size) {
                Log.d(
                    TAG,
                    "reader_ntk_generated_full_keep_known_count path=${target.ntkEpisodePath}," +
                        "generated=${urls.size},verified=${verifiedApiUrls.size},known=$knownGeneratedCount"
                )
            }
            urls
        }
        sourceUrls = filterKnownMissingGeneratedInitialUrls(target, sourceUrls, loadStartedAt)
        if (sourceUrls.isEmpty()) return
        var fullRefs = pageRefsForImages(target, sourceUrls)
        if (fullRefs.isEmpty()) return
        if (isNtkSource(target, title) &&
            isGeneratedOnlyNtkRefs(fullRefs) &&
            target.ntkImageCount > 0 &&
            fullRefs.size > target.ntkImageCount
        ) {
            Log.d(
                TAG,
                "reader_ntk_generated_full_cap_to_verified_count path=${target.ntkEpisodePath}," +
                    "from=${fullRefs.size},to=${target.ntkImageCount}"
            )
            fullRefs = fullRefs.take(target.ntkImageCount)
        }
        var generatedOnlyRefs = isGeneratedOnlyNtkRefs(fullRefs)
        if (allowFirstBitmapDefer && shouldDeferInitialFullAppendUntilFirstBitmap(fullRefs)) {
            deferInitialFullAppendUntilFirstBitmap(target, sourceUrls, loadStartedAt)
            return
        }
        val startIndex: Int
        val total: Int
        var previousTotal = 0
        var refreshedExisting = false
        synchronized(pagesLock) {
            if (pages.isEmpty()) return
            previousTotal = pages.size
            fullRefs = replaceNtkBoardUploadsWithGeneratedFullRefs(target, fullRefs)
            generatedOnlyRefs = isGeneratedOnlyNtkRefs(fullRefs)
            val replaceGeneratedSeedWithFullBoard =
                pages.size < fullRefs.size &&
                    pages.all { it.transitionTitle != null || isNtkGeneratedImageUrl(it.image.orEmpty()) } &&
                    fullRefs.any { isNtkBoardUploadImageUrl(it.image) }
            if (pages.size >= fullRefs.size || replaceGeneratedSeedWithFullBoard) {
                if (!replaceGeneratedSeedWithFullBoard && !shouldRefreshInitialNtkInstalledRefs(fullRefs)) return
                beginStructurePublish()
                refreshedExisting = true
                startIndex = 0
                fullRefs.forEachIndexed { index, page ->
                    page.pageIndex = index
                    page.totalPages = fullRefs.size
                }
                pages.clear()
                pages.addAll(fullRefs)
                loading.clear()
                loadingPages.clear()
                urgentLoading.clear()
                urgentLoadingPages.clear()
                inFlightWidths.clear()
                bytePrefetching.clear()
                visibleGeneratedByteHedges.clear()
                visibleGeneratedDecodeHedges.clear()
                listenerDrawableDeliveries.clear()
                total = pages.size
            } else {
                beginStructurePublish()
                startIndex = pages.size
                for (index in pages.indices) {
                    pages[index].totalPages = fullRefs.size
                }
                val appendable = fullRefs.drop(startIndex)
                appendable.forEachIndexed { offset, page -> page.pageIndex = startIndex + offset }
                pages.addAll(appendable)
                total = pages.size
            }
        }
        logNtkRepositoryStage(
            target,
            if (refreshedExisting) "early_urls_refresh_full" else "early_urls_append_full",
            "from=$startIndex,total=$total,previous=$previousTotal,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        val gateGeneratedAppendNotify =
            generatedOnlyRefs &&
                !refreshedExisting &&
                firstBitmapLogged.get() &&
                shouldGateGeneratedAppendNotifyUntilNearReady(startIndex, total)
        val generatedWarmStartedBeforePublish = generatedOnlyRefs && firstBitmapLogged.get()
        if (generatedWarmStartedBeforePublish) {
            warmNtkGeneratedInitialPagesLimited(currentStartPage(), loadStartedAt)
        }
        val posted = postInitialFullAppendPublish(
            target = target,
            total = total,
            generatedOnlyRefs = generatedOnlyRefs,
            loadStartedAt = loadStartedAt
        ) {
            try {
                if (!cancelled.get() && !gateGeneratedAppendNotify) {
                    if (refreshedExisting && previousTotal > total) {
                        listener.onPagesRemoved(total, previousTotal - total, total)
                    } else {
                        listener.onPagesAppended(total)
                    }
                }
            } finally {
                finishStructurePublish()
            }
            if (generatedOnlyRefs) {
                if (!generatedWarmStartedBeforePublish) {
                    logNtkRepositoryStage(
                        target,
                        "early_urls_append_full_skip_generated_warm",
                        "total=$total,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    warmNtkGeneratedInitialPagesLimited(currentStartPage(), loadStartedAt)
                }
                if (gateGeneratedAppendNotify) {
                    notifyGeneratedAppendWhenNearReady(target, total, loadStartedAt)
                }
            } else {
                warmNtkInitialPages(currentStartPage())
            }
        }
        if (!posted) finishStructurePublish()
    }

    private fun shouldGateGeneratedAppendNotifyUntilNearReady(startIndex: Int, total: Int): Boolean {
        if (!isNtkSource(manga, title)) return false
        val start = currentStartPage()
        val firstNearDrawable = firstGeneratedAppendDrawableIndex(start, total) ?: return false
        return !hasListenerDrawableDelivery(firstNearDrawable)
    }

    private fun notifyGeneratedAppendWhenNearReady(target: Manga, total: Int, loadStartedAt: Long) {
        val start = currentStartPage()
        val firstNearDrawable = firstGeneratedAppendDrawableIndex(start, total) ?: return
        val notify = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                val ready = hasListenerDrawableDelivery(firstNearDrawable)
                if (!ready) {
                    main.postDelayed(this, NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
                    return
                }
                logNtkRepositoryStage(
                    target,
                    "early_urls_append_full_notify_near_ready",
                    "firstNear=$firstNearDrawable,total=$total,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                listener.onPagesAppended(total)
            }
        }
        notify.run()
    }

    private fun firstGeneratedAppendDrawableIndex(start: Int, total: Int): Int? = synchronized(pagesLock) {
        if (start + 1 >= total) return@synchronized null
        val last = minOf(total - 1, pages.lastIndex)
        for (index in (start + 1)..last) {
            val page = pages.getOrNull(index) ?: continue
            if (page.transitionTitle == null) return@synchronized index
        }
        null
    }

    private fun filterKnownMissingGeneratedInitialUrls(
        target: Manga,
        urls: List<String>,
        loadStartedAt: Long
    ): List<String> {
        if (!isNtkSource(target, title) || urls.none { isNtkGeneratedImageUrl(it) }) return urls
        var removed = 0
        val filtered = urls.filter { image ->
            val remove = isNtkGeneratedImageUrl(image) && ReaderImageCache.isKnownNtkGeneratedNotFound(target, image)
            if (remove) removed++
            !remove
        }
        if (removed > 0) {
            logNtkRepositoryStage(
                target,
                "early_urls_filter_generated_not_found",
                "from=${urls.size},to=${filtered.size},removed=$removed,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
        }
        return filtered
    }

    private fun shouldKeepManhwaGeneratedAppendToObservedUrls(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (!isNtkManhwaEpisodePath(target.ntkEpisodePath)) return false
        if (urls.size <= 1 || urls.any { !isNtkGeneratedImageUrl(it) }) return false
        val observed = observedInitialManhwaGeneratedUrls(target, urls)
        return observed.isNotEmpty() && observed.size < urls.size
    }

    private fun observedInitialManhwaGeneratedUrls(target: Manga, fallback: List<String>): List<String> {
        val observed = ReaderImageCache
            .earlyNtkGeneratedSuccessImageUrls(target.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L)
            .filter { isNtkGeneratedImageUrl(it) }
        if (observed.isNotEmpty()) return observed
        val verifiedInitial = ReaderImageCache
            .earlyNtkImageUrls(target.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L)
            .filter { isNtkGeneratedImageUrl(it) }
        if (verifiedInitial.size > 1) {
            val count = minOf(verifiedInitial.size, fallback.size, NTK_GENERATED_INITIAL_RECOVERY_PAGES)
            if (count > 1) return fallback.take(count)
        }
        return fallback.take(1)
    }

    private fun installedGeneratedPageCountForCurrentEpisode(): Int = synchronized(pagesLock) {
        pages.count { page ->
            page.transitionTitle == null && isNtkGeneratedImageUrl(page.image.orEmpty())
        }
    }

    private fun postInitialFullAppendPublish(
        target: Manga,
        total: Int,
        generatedOnlyRefs: Boolean,
        loadStartedAt: Long,
        publish: () -> Unit
    ): Boolean {
        if (!isNtkSource(target, title)) {
            return main.post(publish)
        }
        if (firstBitmapLogged.get() || ntkFirstBitmapAtMs.get() > 0L) {
            logNtkRepositoryStage(
                target,
                "early_urls_append_full_publish_front_after_bitmap_delivery",
                "total=$total,generated=$generatedOnlyRefs,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            return main.postAtFrontOfQueue(publish)
        }
        control.execute {
            val deadline = SystemClock.elapsedRealtime() + NTK_INITIAL_FULL_APPEND_PUBLISH_AFTER_FIRST_BITMAP_WAIT_MS
            while (!cancelled.get() &&
                !firstBitmapLogged.get() &&
                ntkFirstBitmapAtMs.get() <= 0L &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    main.post(publish)
                    return@execute
                }
            }
            val firstBitmapReady = firstBitmapLogged.get() || ntkFirstBitmapAtMs.get() > 0L
            logNtkRepositoryStage(
                target,
                "early_urls_append_full_publish_${if (firstBitmapReady) "after_first_bitmap" else "deadline"}",
                "firstBitmap=$firstBitmapReady,total=$total,generated=$generatedOnlyRefs,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            val posted = if (firstBitmapReady) {
                main.postAtFrontOfQueue(publish)
            } else {
                main.post(publish)
            }
            if (!posted) finishStructurePublish()
        }
        return true
    }

    private fun shouldDeferInitialFullAppendUntilFirstBitmap(fullRefs: List<PageRef>): Boolean {
        if (firstBitmapLogged.get() || ntkFirstBitmapAtMs.get() > 0L) return false
        if (!isNtkSource(manga, title)) return false
        if (!isGeneratedOnlyNtkRefs(fullRefs)) return false
        val installedCount = synchronized(pagesLock) { pages.size }
        return installedCount > 0 && fullRefs.size > installedCount
    }

    private fun deferInitialFullAppendUntilFirstBitmap(target: Manga, urls: List<String>, loadStartedAt: Long) {
        if (!initialFullAppendDeferredUntilFirstBitmap.compareAndSet(false, true)) return
        control.execute {
            val deadline = SystemClock.elapsedRealtime() + NTK_INITIAL_FULL_APPEND_AFTER_FIRST_BITMAP_WAIT_MS
            while (!cancelled.get() &&
                !firstBitmapLogged.get() &&
                ntkFirstBitmapAtMs.get() <= 0L &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    initialFullAppendDeferredUntilFirstBitmap.set(false)
                    return@execute
                }
            }
            initialFullAppendDeferredUntilFirstBitmap.set(false)
            if (!cancelled.get()) {
                logNtkRepositoryStage(
                    target,
                    "early_urls_append_full_after_first_bitmap",
                    "firstBitmap=${firstBitmapLogged.get()},count=${urls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                appendInitialNtkUrlsAfterEarlyInstall(target, urls, loadStartedAt, allowFirstBitmapDefer = false)
            }
        }
    }

    private fun isGeneratedOnlyNtkRefs(refs: List<PageRef>): Boolean {
        if (refs.isEmpty()) return false
        return refs.all { ref ->
            ref.transitionTitle != null || isNtkGeneratedImageUrl(ref.image.orEmpty())
        }
    }

    private fun replaceNtkBoardUploadsWithGeneratedFullRefs(target: Manga, fullRefs: List<PageRef>): List<PageRef> {
        if (!isNtkSource(target, title) || fullRefs.isEmpty()) return fullRefs
        if (fullRefs.none { isNtkBoardUploadImageUrl(it.image) }) return fullRefs
        val seed = currentNtkGeneratedSeed()
        if (seed != null) {
            return replaceNtkBoardUploadsWithGeneratedSeed(target, fullRefs, seed)
        }
        logNtkRepositoryStage(
            target,
            "board_uploads_keep_original",
            "count=${fullRefs.count { isNtkBoardUploadImageUrl(it.image) }}"
        )
        return fullRefs
    }

    private fun replaceNtkBoardUploadsWithGeneratedSeed(
        target: Manga,
        fullRefs: List<PageRef>,
        seed: String
    ): List<PageRef> {
        val boardCount = fullRefs.count { isNtkBoardUploadImageUrl(it.image) }
        val reportedCount = target.ntkImageCount
        val sourceRefs = if (boardCount > 0 && fullRefs.size > NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD) {
            fullRefs.take(NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD)
        } else {
            fullRefs
        }
        val replaced = sourceRefs.map { ref ->
            if (ref.transitionTitle != null || !isNtkBoardUploadImageUrl(ref.image)) {
                ref
            } else {
                val generated = ntkGeneratedImageUrlForTarget(seed, target, ref.sourceIndex + 1)
                if (generated == null) ref else ref.copy(image = generated)
            }
        }
        if (replaced == fullRefs) return fullRefs
        Log.d(
            TAG,
            "ntk_generated_full_replace_board_uploads path=${target.ntkEpisodePath}," +
                "count=$boardCount,from=${fullRefs.size},to=${replaced.size}," +
                "reportedCount=$reportedCount,seed=${seed.substringAfter("://")}"
        )
        return replaced
    }

    private fun currentNtkGeneratedSeed(): String? {
        val pageSeed = pages.firstOrNull {
            it.transitionTitle == null && isNtkGeneratedImageUrl(it.image.orEmpty())
        }?.image
        if (pageSeed != null) return pageSeed
        return ReaderImageCache
            .earlyNtkImageUrls(manga.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L)
            .firstOrNull { isNtkGeneratedImageUrl(it) }
    }

    private fun shouldRefreshInitialNtkInstalledRefs(fullRefs: List<PageRef>): Boolean {
        if (!isNtkSource(manga, title)) return false
        if (fullRefs.isEmpty()) return false
        if (pages.size != fullRefs.size) return true
        for (index in fullRefs.indices) {
            val current = pages.getOrNull(index) ?: return true
            val incoming = fullRefs[index]
            if (current.transitionTitle != incoming.transitionTitle) return true
            if (current.image != incoming.image) return true
            if (current.manga.ntkEpisodePath != incoming.manga.ntkEpisodePath) return true
        }
        return false
    }

    private fun startInitialForegroundStreamIfNeeded(
        target: Manga,
        urls: List<String>,
        startPage: Int,
        loadStartedAt: Long
    ) {
        if (!isNtkSource(target, title)) return
        val image = urls.getOrNull(startPage) ?: return
        val started = try {
            val permit = ntkCoordinator?.imagePermit(
                startPage,
                target,
                image,
                NtkImageLane.FIRST_IMAGE,
                "startInitialForegroundStreamIfNeeded"
            )
            if (!ntkCoordinatorAllowsStream(startPage, target, image, permit, "startInitialForegroundStreamIfNeeded")) {
                false
            } else {
                ReaderImageCache.startForegroundStreamFetch(
                    appContext,
                    target,
                    image,
                    imageCancellation,
                    anchorHedge = false,
                    permit = permit,
                    pageIndex = startPage
                )
            }
        } catch (e: Exception) {
            recordIfUnexpected(e)
            false
        }
        Log.d(
            TAG,
            "reader_anchor_stream_after_urls page=$startPage,started=$started,count=${urls.size}," +
                "ms=${SystemClock.elapsedRealtime() - loadStartedAt},path=${target.ntkEpisodePath}"
        )
        ViewerWarmupManager.logMetric("reader_anchor_stream_after_urls", if (started) 1L else 0L)
    }

    private fun startAdjacentForegroundStreams(
        target: Manga,
        urls: List<String>,
        direction: Int
    ) {
        if (!isNtkSource(target, title) || urls.isEmpty()) return
        val indexes = if (direction < 0) {
            ((urls.size - NTK_ADJACENT_FOREGROUND_STREAM_PAGES).coerceAtLeast(0) until urls.size).toList().asReversed()
        } else {
            (0 until minOf(urls.size, NTK_ADJACENT_FOREGROUND_STREAM_PAGES)).toList()
        }
        var startedCount = 0
        for (index in indexes) {
            val image = urls.getOrNull(index) ?: continue
            val started = try {
                val permit = ntkCoordinator?.imagePermit(
                    index,
                    target,
                    image,
                    NtkImageLane.NEAR_WARMUP,
                    "startAdjacentForegroundStreams"
                )
                if (!ntkCoordinatorAllowsStream(index, target, image, permit, "startAdjacentForegroundStreams")) {
                    false
                } else {
                    ReaderImageCache.startForegroundStreamFetch(
                        appContext,
                        target,
                        image,
                        imageCancellation,
                        anchorHedge = false,
                        permit = permit,
                        pageIndex = index
                    )
                }
            } catch (e: Exception) {
                recordIfUnexpected(e)
                false
            }
            if (started) startedCount++
        }
        Log.d(
            TAG,
            "append_adjacent_foreground_streams targetId=${target.id} path=${target.ntkEpisodePath} " +
                "direction=$direction,started=$startedCount,count=${indexes.size}"
        )
    }

    private fun logNtkRepositoryStage(target: Manga, stage: String, detail: String) {
        if (!isNtkSource(target, title)) return
        Log.d(TAG, "reader_repository_stage stage=$stage,path=${target.ntkEpisodePath},$detail")
    }

    private fun installPreparedSnapshot(snapshot: ReaderPreparedStore.Snapshot): Boolean {
        val urls = snapshot.images
        if (!urls.isNullOrEmpty()) {
            val resolvedStartPage = resolvePreparedStartPage(snapshot.startPage)
            val startPage = installImages(urls, resolvedStartPage, false, notifyInitialPage = false)
            for (entry in snapshot.bitmaps.entries) deliverPreparedSourceBitmap(entry.key, entry.value)
            flushEarlyPreparedBitmaps()
            main.post {
                if (!cancelled.get() && startPage >= 0) listener.onInitialPage(startPage)
            }
            releasePreparedStoreBitmapsSoon()
            requestPageForeground(resolvedStartPage)
            requestInitialFanout(resolvedStartPage)
            return true
        }
        for (entry in snapshot.bitmaps.entries) {
            if (!entry.value.isRecycled) earlyPreparedBitmaps[entry.key] = entry.value
        }
        return false
    }

    private fun releasePreparedStoreBitmapsSoon() {
        if (preparedStoreKey == null) return
        main.removeCallbacks(clearPreparedBitmapsRunnable)
        if (!firstBitmapLogged.get()) return
        main.postDelayed(clearPreparedBitmapsRunnable, PREPARED_BITMAP_RELEASE_DELAY_MS)
    }

    private fun resolveInitialNtkUnavailableEpisode(source: Manga): Pair<Manga, List<String>>? {
        if (!isNtkSource(source, title)) return null
        if (source.ntkViewerParseReason != "unavailable") return null
        val currentTitle = title ?: source.title ?: manga.title ?: return null
        if (syncNtkTitlePathFromEpisode(currentTitle, source)) {
            currentTitle.removeEps()
        }
        restoreNtkEpisodeSnapshotIfNeeded(currentTitle, source)
        if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
            val result = withRepositoryCancellation(userVisible = true) {
                imageRepository.fetchEpisodesForeground(currentTitle, it)
            }
            if (cancelled.get() || result != Title.LOAD_OK) return null
        }
        attachTitle()
        val episodes = Utils.snapshotEpisodes(currentTitle)
        if (episodes.isNotEmpty()) {
            manga.setEps(episodes)
            source.setEps(episodes)
            persistNtkEpisodeSnapshot(currentTitle, episodes)
        }
        source.title = currentTitle
        source.titleId = currentTitle.id
        for (direction in listOf(ReaderSurfaceView.DIRECTION_NEXT, ReaderSurfaceView.DIRECTION_PREVIOUS)) {
            var checked = 0
            for (candidate in adjacentEpisodeCandidates(source, episodes, direction)) {
                if (checked >= ADJACENT_EXISTING_SKIP_LIMIT) break
                candidate.title = currentTitle
                candidate.titleId = currentTitle.id
                candidate.mode = source.mode
                if (episodes.isNotEmpty()) candidate.setEps(episodes)
                val appendLoad = loadAppendUrlsForCandidate(candidate, currentTitle, direction)
                if (cancelled.get()) return null
                if (appendLoad.result == Title.LOAD_OK && appendLoad.urls.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "ntk_initial_unavailable_candidate direction=$direction sourcePath=${source.ntkEpisodePath} " +
                            "targetPath=${candidate.ntkEpisodePath} images=${appendLoad.urls.size}"
                    )
                    return candidate to appendLoad.urls
                }
                Log.d(
                    TAG,
                    "ntk_initial_unavailable_candidate_skip direction=$direction sourcePath=${source.ntkEpisodePath} " +
                        "targetPath=${candidate.ntkEpisodePath} result=${appendLoad.result} " +
                        "reason=${candidate.ntkViewerParseReason} images=${appendLoad.urls.size}"
                )
                candidate.setImgs(null)
                checked++
            }
        }
        return null
    }

    private fun installImages(
        urls: List<String>,
        requestedStartPage: Int,
        requestInitialWindow: Boolean,
        notifyInitialPage: Boolean = true
    ): Int = installImagesForManga(manga, urls, requestedStartPage, requestInitialWindow, notifyInitialPage)

    private fun installImagesForManga(
        target: Manga,
        urls: List<String>,
        requestedStartPage: Int,
        requestInitialWindow: Boolean,
        notifyInitialPage: Boolean = true
    ): Int {
        if (cancelled.get() || urls.isEmpty()) return -1
        if (!pagesInstalled.compareAndSet(false, true)) return -1
        val refs = pageRefsForImages(target, urls)
        synchronized(pagesLock) {
            pages.clear()
            refs.forEachIndexed { index, page ->
                page.pageIndex = index
            }
            pages.addAll(refs)
        }
        val startPage = displayStartPage(requestedStartPage, requestedStartSide(), refs)
        resolvedInitialStartPage.set(startPage)
        if (isNtkSource(target, title)) {
            ntkCoordinator = NtkEpisodeCoordinator(target.ntkEpisodePath, true, startPage)
        }
        if (!isNtkSource(manga, title)) {
            main.post {
                if (!cancelled.get()) {
                    deliverInitialPagesReadyIfNeeded(refs.size, startPage, notifyInitialPage)
                }
            }
        }
        if (!autoCut && !isNtkSource(manga, title) && timelinePrimeRequested.compareAndSet(false, true)) {
            primeForwardTimeline()
        }
        if (requestInitialWindow) requestInitialWindow(startPage, false)
        return startPage
    }

    private fun requestInitialWindow(startPage: Int, busy: Boolean) {
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val preFirstNtkLast = if (
            isNtkSource(manga, title) &&
            !firstBitmapLogged.get() &&
            isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)
        ) {
            startPage + NTK_GENERATED_INITIAL_RECOVERY_PAGES - 1
        } else {
            startPage + ReaderPipelinePolicy.INITIAL_WINDOW_AFTER
        }
        requestWindow(
            max(0, startPage - ReaderPipelinePolicy.INITIAL_WINDOW_BEFORE),
            minOf(count - 1, preFirstNtkLast),
            startPage,
            busy
        )
    }

    private fun requestInitialFanout(startPage: Int) {
        if (isNtkSource(manga, title) && !firstBitmapLogged.get()) {
            ViewerWarmupManager.logMetric("reader_ntk_initial_fanout_eager", startPage.toLong())
            warmNtkInitialPages(startPage)
            return
        }
        if (shouldDeferInitialFanoutUntilAnchor()) {
            pendingInitialFanoutPage.set(startPage)
            prefetchImageFilesAround(startPage)
            return
        }
        startInitialFanout(startPage)
    }

    private fun startInitialFanout(startPage: Int) {
        if (!initialFanoutStarted.compareAndSet(false, true)) return
        if (isNtkSource(manga, title)) {
            warmNtkInitialPages(startPage)
        } else {
            requestInitialWindow(startPage, false)
            prefetchImageFilesAround(startPage)
        }
    }

    private fun warmNtkInitialPages(startPage: Int) {
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val anchor = startPage.coerceIn(0, count - 1)
        if (!hasActiveOrDeliveredPage(anchor)) {
            requestPage(anchor, busy = true, anchor = true, generation = FOREGROUND_PRIME_WARM_GENERATION)
        }
        if (!firstBitmapLogged.get()) return
        val bootPriorityPages = if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_INITIAL_BOOT_PRIORITY_PAGES
        } else {
            NTK_INITIAL_BOOT_PRIORITY_PAGES
        }
        val bootUrgentPages = if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_INITIAL_BOOT_URGENT_PAGES
        } else {
            NTK_INITIAL_BOOT_URGENT_PAGES
        }
        val bootBackgroundPages = if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_INITIAL_BOOT_BACKGROUND_PAGES
        } else {
            NTK_INITIAL_BOOT_BACKGROUND_PAGES
        }
        for (offset in 1..bootPriorityPages) {
            val ahead = anchor + offset
            if (ahead < count) {
                val generation = if (offset <= NTK_FOREGROUND_STREAM_AHEAD_PAGES) {
                    FOREGROUND_PRIME_WARM_GENERATION
                } else {
                    PRIME_WARM_GENERATION
                }
                requestPage(
                    ahead,
                    busy = offset <= bootUrgentPages,
                    anchor = false,
                    generation = generation
                )
            }
        }
        val backgroundLast = minOf(count - 1, anchor + bootBackgroundPages)
        if (ntkCurrentBackgroundWarmDelayMs() <= 0L
            && anchor + bootPriorityPages + 1 <= backgroundLast
        ) {
            for (index in (anchor + bootPriorityPages + 1)..backgroundLast) {
                requestPage(index, busy = false, anchor = false, generation = PRIME_WARM_GENERATION)
            }
        }
        prefetchNtkInitialNextBytes(anchor, count)
    }

    private fun warmNtkGeneratedInitialPagesLimited(startPage: Int, loadStartedAt: Long) {
        if (!isNtkSource(manga, title)) return
        if (!firstBitmapLogged.get()) return
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val anchor = startPage.coerceIn(0, count - 1)
        val last = minOf(count - 1, anchor + ntkGeneratedInitialLimitedWarmPages())
        if (last <= anchor) return
        logNtkRepositoryStage(
            manga,
            "early_urls_append_full_generated_limited_warm",
            "from=${anchor + 1},to=$last,total=$count,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        val foregroundPages = ntkGeneratedInitialLimitedForegroundPages()
        val busyPages = ntkGeneratedInitialLimitedBusyPages()
        for (index in (anchor + 1)..last) {
            val offset = index - anchor
            val generation = if (offset <= foregroundPages) {
                FOREGROUND_PRIME_WARM_GENERATION
            } else {
                PRIME_WARM_GENERATION
            }
            requestPage(
                index,
                busy = offset <= busyPages,
                anchor = false,
                generation = generation
            )
        }
    }

    private fun ntkGeneratedInitialLimitedForegroundPages(): Int {
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES
        } else {
            NTK_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES
        }
    }

    private fun ntkGeneratedInitialLimitedBusyPages(): Int {
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_GENERATED_INITIAL_LIMITED_BUSY_PAGES
        } else {
            NTK_GENERATED_INITIAL_LIMITED_BUSY_PAGES
        }
    }

    private fun ntkGeneratedInitialLimitedWarmPages(): Int {
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_GENERATED_INITIAL_LIMITED_WARM_PAGES
        } else {
            NTK_GENERATED_INITIAL_LIMITED_WARM_PAGES
        }
    }

    private fun ntkInitialBytePrefetchAheadPages(): Int {
        val refs = synchronized(pagesLock) { pages.toList() }
        if (isGeneratedOnlyNtkRefs(refs)) {
            return NTK_WEBTOON_INITIAL_BYTE_PREFETCH_AHEAD_PAGES
        }
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_INITIAL_BYTE_PREFETCH_AHEAD_PAGES
        } else {
            NTK_INITIAL_BYTE_PREFETCH_AHEAD_PAGES
        }
    }

    private fun prefetchNtkInitialNextBytes(anchor: Int, count: Int) {
        if (!isNtkSource(manga, title)) return
        if (!firstBitmapLogged.get()) return
        if (MainApplication.p?.getDataSave() == true) return
        val last = minOf(count - 1, anchor + ntkInitialBytePrefetchAheadPages())
        if (last <= anchor) return
        val generation = FOREGROUND_PRIME_WARM_GENERATION
        for (index in (anchor + 1)..last) {
            val page = pageRef(index) ?: continue
            if (page.transitionTitle != null) continue
            prefetchBusyPage(index, page, generation)
        }
        Log.d(TAG, "reader_ntk_initial_next_byte_prefetch anchor=$anchor count=${last - anchor}")
        ViewerWarmupManager.logMetric("reader_ntk_initial_next_byte_prefetch", (last - anchor).toLong())
    }

    private fun hasActiveOrDeliveredPage(index: Int): Boolean {
        if (loading.contains(index) || urgentLoading.contains(index)) return true
        if ((pendingDeliveryWidths[index] ?: 0) > 0) return true
        return hasDeliveredBitmap(index)
    }

    private fun releaseInitialFanoutIfAnchorReady(index: Int) {
        val pending = pendingInitialFanoutPage.get()
        if (pending < 0 || index != pending) return
        if (!pendingInitialFanoutPage.compareAndSet(pending, -1)) return
        startInitialFanout(index)
    }

    private fun shouldDeferInitialFanoutUntilAnchor(): Boolean {
        return !isNtkSource(manga, title)
    }

    private fun prefetchImageFilesAround(startPage: Int, after: Int = START_SOURCE_PREFETCH_AFTER) {
        val refs = synchronized(pagesLock) { pages.toList() }
        if (refs.isEmpty()) return
        val first = startPage.coerceIn(0, refs.lastIndex)
        val effectiveAfter = if (ntkCurrentBackgroundWarmDelayMs() > 0L) {
            minOf(after, NTK_INITIAL_PRIORITY_PAGES)
        } else {
            after
        }
        val last = minOf(refs.lastIndex, first + effectiveAfter)
        if (last < first) return
        val ordered = ArrayList<Int>(last - first + 1)
        val anchor = startPage.coerceIn(first, last)
        lastSourcePrefetchAnchor = anchor
        val generation = windowGeneration.get()
        ordered.add(anchor)
        for (offset in 1..max(last - anchor, anchor - first)) {
            val ahead = anchor + offset
            if (ahead <= last) ordered.add(ahead)
            val behind = anchor - offset
            if (behind >= first) ordered.add(behind)
        }
        for (index in ordered) {
            if (index == anchor) continue
            val page = refs.getOrNull(index) ?: continue
            if (page.transitionTitle != null) continue
            prefetchBusyPage(index, page, generation)
        }
    }

    private fun resolvePreparedStartPage(preparedStartPage: Int): Int {
        val requested = requestedStartPage()
        return if (!startAtFirstPage && requested > 0 && preparedStartPage <= 0) requested else preparedStartPage
    }

    private fun requestPageForeground(index: Int) {
        if (isNtkSource(manga, title) && autoCut && index > 0) {
            requestPage(index - 1, busy = false, anchor = false, generation = windowGeneration.get())
        }
        requestPage(index, busy = false, anchor = true, generation = windowGeneration.get())
    }

    private fun deliverPreparedSourceBitmap(sourceIndex: Int, bitmap: Bitmap) {
        if (!autoCut) {
            deliverPreparedBitmap(sourceIndex, bitmap, false)
            return
        }
        val refs = synchronized(pagesLock) {
            pages.withIndex()
                .filter { it.value.sourceIndex == sourceIndex && it.value.transitionTitle == null }
                .map { it.index to it.value }
        }
        if (refs.isEmpty()) {
            if (!bitmap.isRecycled) earlyPreparedBitmaps[sourceIndex] = bitmap
            return
        }
        for ((pageIndex, page) in refs) {
            val prepared = preparedBitmapForPage(bitmap, page) ?: continue
            deliverPreparedBitmap(pageIndex, prepared.first, prepared.second)
        }
    }

    private data class PendingTile(
        val sourceTop: Int,
        val sourceBottom: Int,
        val bitmap: Bitmap
    )

    private fun preparedBitmapForPage(bitmap: Bitmap, page: PageRef): Pair<Bitmap, Boolean>? {
        if (bitmap.isRecycled) return null
        if (!shouldSplitPreparedBitmapForPage(autoCut, page.allowAutoSplit, bitmap.width, bitmap.height)) {
            return if (page.side == PAGE_SIDE_FIRST) bitmap to false else null
        }
        val cropWidth = max(1, bitmap.width / 2)
        val cropX = if (page.side == PAGE_SIDE_FIRST) {
            if (reverse) 0 else bitmap.width - cropWidth
        } else {
            if (reverse) bitmap.width - cropWidth else 0
        }
        val displayBitmap = Bitmap.createBitmap(cropWidth, bitmap.height, displayConfig(bitmap))
        Canvas(displayBitmap).drawBitmap(
            bitmap,
            Rect(cropX, 0, cropX + cropWidth, bitmap.height),
            Rect(0, 0, cropWidth, bitmap.height),
            null
        )
        return displayBitmap to true
    }

    private fun deliverPreparedBitmap(index: Int, bitmap: Bitmap, owned: Boolean) {
        deliverPreparedBitmap(index, bitmap, owned, true)
    }

    private fun deliverPreparedBitmap(index: Int, bitmap: Bitmap, owned: Boolean, allowInitialHold: Boolean) {
        if (cancelled.get() || index < 0 || bitmap.isRecycled) return
        if (allowInitialHold && shouldHoldInitialNtkIndex(index)) {
            loading.remove(index)
            initialPreparedBacklog.put(index, PreparedDelivery(bitmap, owned))?.let { previous ->
                if (previous.owned && !previous.bitmap.isRecycled) previous.bitmap.recycle()
            }
            Log.d(TAG, "reader_initial_hold_prepared page=$index,start=${currentStartPage()},width=${bitmap.width}")
            scheduleInitialDeliveryFallback()
            return
        }
        decodedWidths[index] = max(decodedWidths[index] ?: 0, bitmap.width)
        loading.remove(index)
        trackDeliveredBitmap(index, bitmap, owned)
        markFirstPreparedBitmapDelivered()
        main.post {
            if (!cancelled.get()) {
                deliverInitialPagesReadyForCurrentPagesIfNeeded()
                listener.onPageReady(index, bitmap)
                ntkCoordinator?.markAnchorBitmapDecoded(index)
                ntkCoordinator?.markFirstDrawableCommitted(index)
                main.post { releaseInitialFanoutIfAnchorReady(index) }
            }
        }
    }

    private fun markFirstPreparedBitmapDelivered() {
        if (firstBitmapLogged.compareAndSet(false, true)) {
            ntkFirstBitmapAtMs.set(SystemClock.uptimeMillis())
            ViewerWarmupManager.logMetric("reader_first_bitmap_prepared", 1L)
            releasePreparedStoreBitmapsSoon()
            scheduleNtkEpisodeMetadataAfterFirstBitmap()
        }
    }

    private fun flushEarlyPreparedBitmaps() {
        val pending = earlyPreparedBitmaps.entries.toList()
        earlyPreparedBitmaps.clear()
        for (entry in pending) deliverPreparedSourceBitmap(entry.key, entry.value)
    }

    fun requestWindow(first: Int, last: Int, anchor: Int, busy: Boolean) {
        requestWindow(first, last, anchor, busy, true)
    }

    fun noteUserInteraction() {
        lastUserInteractionMs.set(SystemClock.uptimeMillis())
    }

    fun requestWindowAsync(first: Int, last: Int, anchor: Int, busy: Boolean) {
        synchronized(controlLock) {
            pendingWindowCommand = WindowCommand(first, last, anchor, busy)
            if (windowCommandPosted) return
            windowCommandPosted = true
        }
        try {
            control.execute {
                while (!cancelled.get()) {
                    val command = synchronized(controlLock) {
                        val next = pendingWindowCommand
                        pendingWindowCommand = null
                        if (next == null) {
                            windowCommandPosted = false
                            return@synchronized null
                        }
                        next
                    } ?: return@execute
                    requestWindow(command.first, command.last, command.anchor, command.busy, true)
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(controlLock) {
                windowCommandPosted = false
            }
        }
    }

    private fun requestWindow(first: Int, last: Int, anchor: Int, busy: Boolean, retainWindow: Boolean) {
        if (cancelled.get()) return
        val wasBusy = viewportBusy.getAndSet(busy)
        if (busy) {
            deliveryResumeAtMs.set(Long.MAX_VALUE)
        } else if (wasBusy) {
            deliveryResumeAtMs.set(SystemClock.uptimeMillis() + IDLE_DELIVERY_RESUME_DELAY_MS)
        }
        if (isStructurePublishPending()) return
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val windowAnchor = anchor.coerceIn(0, count - 1)
        currentViewportAnchor.set(windowAnchor)
        val windowFirstInput: Int
        val windowLastInput: Int
        val ntkWebtoon = isNtkWebtoonSource(manga, title)
        val ntkInitialAnchorWindow = isNtkSource(manga, title) &&
            firstBitmapLogged.get() &&
            windowAnchor == currentStartPage() &&
            first <= windowAnchor
        if (isNtkSource(manga, title) && !firstBitmapLogged.get()) {
            windowFirstInput = windowAnchor
            windowLastInput = windowAnchor
        } else if (ntkInitialAnchorWindow) {
            val initialAhead = if (ntkWebtoon) NTK_WEBTOON_INITIAL_DECODE_AHEAD_PAGES else NTK_INITIAL_DECODE_AHEAD_PAGES
            windowFirstInput = first
            windowLastInput = minOf(count - 1, max(last, windowAnchor + initialAhead))
        } else {
            windowFirstInput = first
            windowLastInput = if (ntkWebtoon && firstBitmapLogged.get()) {
                val ntkWindowAfter = if (busy) {
                    NTK_WEBTOON_BUSY_WINDOW_AFTER
                } else {
                    NTK_WEBTOON_WINDOW_AFTER
                }
                minOf(count - 1, max(last, windowAnchor + ntkWindowAfter))
            } else if (busy && isNtkSource(manga, title) && firstBitmapLogged.get()) {
                minOf(count - 1, last + NTK_BUSY_VISIBLE_EDGE_EXTRA_PAGES)
            } else if (isNtkSource(manga, title) &&
                firstBitmapLogged.get() &&
                isNtkGeneratedPageRef(windowAnchor)
            ) {
                minOf(count - 1, last + NTK_BUSY_VISIBLE_EDGE_EXTRA_PAGES)
            } else {
                last
            }
        }
        val requestList: List<Int>
        val generation: Int
        val safeFirst: Int
        val safeLast: Int
        val direction: Int
        synchronized(windowLock) {
            safeFirst = windowFirstInput.coerceIn(0, count - 1)
            safeLast = windowLastInput.coerceIn(safeFirst, count - 1)
            generation = windowGeneration.incrementAndGet()
            direction = when {
                lastWindowAnchor < 0 -> lastWindowDirection
                windowAnchor > lastWindowAnchor -> 1
                windowAnchor < lastWindowAnchor -> -1
                else -> lastWindowDirection
            }
            lastWindowAnchor = windowAnchor
            if (direction != 0) lastWindowDirection = direction
            requestList = windowOrder(safeFirst, safeLast, windowAnchor, direction)
        }
        if (retainWindow) {
            synchronized(deliveredBitmaps) {
                retainedFirstPage = safeFirst
                retainedLastPage = safeLast
                retainedAnchorPage = windowAnchor.coerceIn(safeFirst, safeLast)
            }
            if (primedDeliveryBacklog.isNotEmpty()) scheduleDeliveryDrain()
        }
        if (busy) {
            val generatedWindow = isNtkGeneratedPageRef(windowAnchor)
            val generatedBusyPrefetchLast = if (generatedWindow) {
                minOf(safeLast, windowAnchor + NTK_GENERATED_BUSY_SOURCE_PREFETCH_AFTER)
            } else {
                safeLast
            }
            val busyVisibleRadius = if (ntkWebtoon) {
                NTK_WEBTOON_BUSY_VISIBLE_DECODE_RADIUS
            } else if (generatedWindow) {
                NTK_GENERATED_BUSY_VISIBLE_DECODE_RADIUS
            } else {
                BUSY_VISIBLE_DECODE_RADIUS
            }
            val visibleFirst = if (direction < 0) {
                safeFirst
            } else {
                max(safeFirst, windowAnchor - busyVisibleRadius)
            }
            val visibleLast = if (direction >= 0) {
                val decodeAhead = if (isNtkSource(manga, title) && !firstBitmapLogged.get()) {
                    0
                } else if (ntkInitialAnchorWindow && ntkWebtoon) {
                    0
                } else if (ntkInitialAnchorWindow) {
                    NTK_INITIAL_PRIORITY_PAGES
                } else if (ntkWebtoon) {
                    NTK_WEBTOON_BUSY_DIRECTIONAL_DECODE_AHEAD
                } else if (generatedWindow) {
                    NTK_GENERATED_BUSY_DIRECTIONAL_DECODE_AHEAD
                } else {
                    BUSY_DIRECTIONAL_DECODE_AHEAD
                }
                minOf(safeLast, windowAnchor + decodeAhead)
            } else {
                minOf(safeLast, windowAnchor + busyVisibleRadius)
            }
            val visible = if (generatedWindow && direction > 0) {
                forwardBiasedWindowOrder(visibleFirst, visibleLast, windowAnchor)
            } else {
                windowOrder(visibleFirst, visibleLast, windowAnchor, direction)
            }
            for (i in visible) requestPage(i, true, i == windowAnchor, generation)
            if (!ntkInitialAnchorWindow) {
                for (i in requestList) {
                    if (generatedWindow && i > generatedBusyPrefetchLast) continue
                    if (!visible.contains(i)) pageRef(i)?.let { prefetchBusyPage(i, it, generation) }
                }
            }
            trimDecodedWidth(windowAnchor, true)
            if (!ntkInitialAnchorWindow) {
                maybePrefetchNtkSourceAround(windowAnchor, true)
            }
            return
        }
        if (ntkWebtoon) {
            val visibleRadius = if (ntkInitialAnchorWindow) 0 else NTK_WEBTOON_IDLE_VISIBLE_DECODE_RADIUS
            val visibleFirst = max(safeFirst, windowAnchor - visibleRadius)
            val visibleLast = minOf(safeLast, windowAnchor + visibleRadius)
            val decodeFirst = visibleFirst
            val decodeAhead = if (ntkInitialAnchorWindow) 0 else NTK_WEBTOON_IDLE_DECODE_AHEAD
            val decodeLast = minOf(safeLast, windowAnchor + decodeAhead)
            val prefetchLast = if (isNtkGeneratedPageRef(windowAnchor)) {
                if (ntkInitialAnchorWindow) windowAnchor else minOf(safeLast, windowAnchor + NTK_GENERATED_BUSY_SOURCE_PREFETCH_AFTER)
            } else {
                safeLast
            }
            val visible = windowOrder(visibleFirst, visibleLast, windowAnchor, direction)
            for (i in visible) requestPage(i, busy = true, anchor = i == windowAnchor, generation)
            for (i in requestList) {
                if (visible.contains(i)) continue
                val page = pageRef(i) ?: continue
                if (i in decodeFirst..decodeLast) {
                    requestPage(i, busy = false, anchor = i == windowAnchor, generation)
                } else if (i <= prefetchLast) {
                    prefetchBusyPage(i, page, generation)
                }
            }
            trimDecodedWidth(windowAnchor, busy)
            maybePrefetchNtkSourceAround(windowAnchor, busy)
            return
        }
        for (i in requestList) requestPage(i, busy, i == windowAnchor, generation)
        trimDecodedWidth(windowAnchor, busy)
        maybePrefetchNtkSourceAround(windowAnchor, busy)
    }

    private fun beginStructurePublish() {
        structurePublishPending.incrementAndGet()
    }

    private fun finishStructurePublish() {
        val remaining = structurePublishPending.updateAndGet { pending ->
            if (pending > 0) pending - 1 else 0
        }
        if (remaining == 0 && deliveryQueue.isNotEmpty()) scheduleDeliveryDrain()
    }

    private fun isStructurePublishPending(): Boolean {
        return structurePublishPending.get() > 0
    }

    private fun maybePrefetchNtkSourceAround(anchor: Int, busy: Boolean) {
        if (!isNtkSource(manga, title)) return
        if (!firstBitmapLogged.get()) return
        val previous = lastSourcePrefetchAnchor
        val step = if (busy) NTK_BUSY_SOURCE_PREFETCH_STEP else NTK_IDLE_SOURCE_PREFETCH_STEP
        if (previous >= 0 && abs(anchor - previous) < step) return
        val after = if (busy && isNtkGeneratedPageRef(anchor)) {
            NTK_GENERATED_BUSY_SOURCE_PREFETCH_AFTER
        } else if (busy) {
            NTK_BUSY_SOURCE_PREFETCH_AFTER
        } else {
            NTK_IDLE_SOURCE_PREFETCH_AFTER
        }
        prefetchImageFilesAround(anchor, after)
    }

    private fun isNtkGeneratedPageRef(index: Int): Boolean {
        val image = pageRef(index)?.image ?: return false
        return isNtkGeneratedImageUrl(image)
    }

    fun clearOutside(first: Int, last: Int) {
        decodedWidths.keys.removeAll { it < first || it > last }
        desiredWidths.keys.removeAll { it < first || it > last }
        sourceWidths.keys.removeAll { it < first || it > last }
        achievableWidths.keys.removeAll { it < first || it > last }
        evictDeliveredBitmaps(first, last)
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        viewportBusy.set(false)
        deliveryResumeAtMs.set(0L)
        lastUserInteractionMs.set(0L)
        urgentLoading.clear()
        visibleGeneratedByteHedges.clear()
        visibleGeneratedDecodeHedges.clear()
        for (cancellation in repositoryCancellations.toList()) {
            cancellation.cancel()
        }
        imageCancellation.cancel()
        ReaderImageCache.cancelNtkEpisodeVolatile(manga)
        MainApplication.getHttpClient().cancelNtkWebViewFallbacks()
        repositoryCancellations.clear()
        main.removeCallbacks(clearPreparedBitmapsRunnable)
        main.removeCallbacks(deliveryDrainRunnable)
        recycleQueuedDeliveries()
        preparedEntry?.removeListener(preparedListener)
        releaseDeliveredBitmaps()
        network.shutdownNow()
        decode.shutdownNow()
        anchorNetwork.shutdownNow()
        anchorDecode.shutdownNow()
        adjacentNetwork.shutdownNow()
        urgentNetwork.shutdownNow()
        urgentDecode.shutdownNow()
        primeNetwork.shutdownNow()
        primeDecode.shutdownNow()
        cleanup.shutdown()
        control.shutdownNow()
    }

    private fun repositoryCancellation(userVisible: Boolean = false): MangaRepository.Cancellation {
        val cancellation = MangaRepository.cancellation()
        if (userVisible) cancellation.userVisible()
        repositoryCancellations.add(cancellation)
        if (cancelled.get()) cancellation.cancel()
        return cancellation
    }

    private fun releaseRepositoryCancellation(cancellation: MangaRepository.Cancellation) {
        repositoryCancellations.remove(cancellation)
        if (cancelled.get()) cancellation.cancel()
    }

    private inline fun <T> withRepositoryCancellation(
        userVisible: Boolean = false,
        block: (MangaRepository.Cancellation) -> T
    ): T {
        val cancellation = repositoryCancellation(userVisible)
        try {
            return block(cancellation)
        } finally {
            releaseRepositoryCancellation(cancellation)
        }
    }

    fun prepareNextEpisode(anchor: Int) {
        if (isNtkSource(manga, title) && !firstBitmapLogged.get()) return
        if (shouldDelayNtkAdjacentWorkForCurrentAck()) {
            Log.d(TAG, "prepare_next_deferred_ack_preflight anchor=$anchor path=${manga.ntkEpisodePath}")
            main.postDelayed({ prepareNextEpisode(anchor) }, NTK_ACK_PREFLIGHT_ADJACENT_RECHECK_MS)
            return
        }
        if (cancelled.get() || nextLoading.getAndSet(true)) return
        network.execute {
            try {
                val anchorManga = pageRef(anchor)?.manga ?: manga
                val currentTitle = title ?: anchorManga.title ?: manga.title
                if (currentTitle == null) return@execute
                if (syncNtkTitlePathFromEpisode(currentTitle, anchorManga)) {
                    currentTitle.removeEps()
                }
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    withRepositoryCancellation { imageRepository.fetchEpisodesForeground(currentTitle, it) }
                    if (cancelled.get()) return@execute
                }
                attachTitle()
                val episodes = Utils.snapshotEpisodes(currentTitle)
                if (episodes.isNotEmpty()) {
                    manga.setEps(episodes)
                    anchorManga.setEps(episodes)
                }
                anchorManga.title = currentTitle
                anchorManga.titleId = currentTitle.id
                val next = anchorManga.nextEp() ?: return@execute
                next.title = currentTitle
                next.titleId = currentTitle.id
                if (episodes.isNotEmpty()) next.setEps(episodes)
                if (imageRepository.imageUrls(next, appContext).isNullOrEmpty()) {
                    val result = withRepositoryCancellation {
                        imageRepository.fetchViewerInitial(next, it)
                    }
                    if (cancelled.get()) return@execute
                    if (result != Title.LOAD_OK) return@execute
                }
                val nextUrls = imageRepository.imageUrls(next, appContext)
                if (nextUrls.isNullOrEmpty()) return@execute
            } catch (e: Exception) {
                recordIfUnexpected(e)
            } finally {
                nextLoading.set(false)
            }
        }
    }

    fun prepareAdjacentEpisode(anchor: Int, direction: Int) {
        val quietMs = if (isNtkSource(manga, title)) 0L else ntkBackgroundPrepareQuietRemainingMs()
        if (quietMs > 0L) {
            scheduleDeferredAdjacentPrepare(anchor, direction, quietMs)
            return
        }
        appendAdjacentEpisode(anchor, direction, silentMissing = true)
    }

    private fun scheduleDeferredAdjacentPrepare(anchor: Int, direction: Int, delayMs: Long) {
        deferredAdjacentPrepareAnchor.set(anchor)
        deferredAdjacentPrepareDirection.set(direction)
        if (!deferredAdjacentPrepareScheduled.compareAndSet(false, true)) return
        main.postDelayed({ flushDeferredAdjacentPrepare() }, delayMs)
    }

    private fun flushDeferredAdjacentPrepare() {
        if (cancelled.get()) {
            deferredAdjacentPrepareScheduled.set(false)
            return
        }
        val quietMs = ntkBackgroundPrepareQuietRemainingMs()
        if (quietMs > 0L) {
            main.postDelayed({ flushDeferredAdjacentPrepare() }, quietMs)
            return
        }
        val anchor = deferredAdjacentPrepareAnchor.getAndSet(-1)
        val direction = deferredAdjacentPrepareDirection.getAndSet(0)
        deferredAdjacentPrepareScheduled.set(false)
        if (anchor >= 0 && direction != 0) {
            if (!isNtkSilentAdjacentStillNearBoundary(anchor, direction)) return
            appendAdjacentEpisode(anchor, direction, silentMissing = true)
        }
    }

    fun appendAdjacentEpisode(anchor: Int, direction: Int, silentMissing: Boolean = false): AppendStartResult {
        val loadingFlag = if (direction < 0) previousAppendLoading else nextAppendLoading
        if (cancelled.get()) return AppendStartResult.CANCELLED
        if (isNtkSource(manga, title) && !firstBitmapLogged.get()) return AppendStartResult.CANCELLED
        if (silentMissing && !isNtkSilentAdjacentStillNearBoundary(anchor, direction)) {
            Log.d(
                TAG,
                "append_adjacent_silent_stale_boundary direction=$direction anchor=$anchor " +
                    "path=${manga.ntkEpisodePath}"
            )
            return AppendStartResult.CANCELLED
        }
        if (loadingFlag.getAndSet(true)) return AppendStartResult.BUSY
        try {
            val appendExecutor = if (isNtkSource(manga, title)) adjacentNetwork else network
            appendExecutor.execute {
            var captchaRequired = false
            var suppressedCaptcha = false
            try {
                val anchorManga = pageRef(anchor)?.manga ?: manga
                val currentTitle = title ?: anchorManga.title ?: manga.title ?: return@execute
                val adjacentMissingKey = "${Manga.episodeIdentityKey(anchorManga)}:$direction"
                if (adjacentMissingTargets.contains(adjacentMissingKey)) return@execute
                Log.d(
                    TAG,
                    "append_adjacent_start direction=$direction anchor=$anchor sourceId=${anchorManga.id} " +
                        "sourceTitleId=${anchorManga.titleId} titleId=${currentTitle.id} " +
                        "sourcePath=${anchorManga.ntkEpisodePath} sourceName=${anchorManga.name}"
                )
                if (syncNtkTitlePathFromEpisode(currentTitle, anchorManga)) {
                    currentTitle.removeEps()
                }
                restoreNtkEpisodeSnapshotIfNeeded(currentTitle, anchorManga)
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    val result = withRepositoryCancellation {
                        imageRepository.fetchEpisodesForeground(currentTitle, it)
                    }
                    if (cancelled.get()) return@execute
                    if (result != Title.LOAD_OK) {
                        if (result == Title.LOAD_CAPTCHA) {
                            if (silentMissing) {
                                suppressedCaptcha = true
                                return@execute
                            }
                            captchaRequired = true
                            postCaptchaRequired(anchorManga)
                            return@execute
                        }
                        postMessage(if (result == Title.LOAD_CAPTCHA) "캡차 확인이 필요합니다" else "회차를 불러오지 못했습니다")
                        return@execute
                    }
                }
                attachTitle()
                var episodes = Utils.snapshotEpisodes(currentTitle)
                if (episodes.isNotEmpty()) {
                    manga.setEps(episodes)
                    anchorManga.setEps(episodes)
                    persistNtkEpisodeSnapshot(currentTitle, episodes)
                }
                anchorManga.title = currentTitle
                anchorManga.titleId = currentTitle.id
                var target = nextUnloadedAdjacentEpisode(anchorManga, currentTitle, episodes, direction)
                if (target == null && anchorManga.isOnline) {
                    val refreshKey = adjacentMissingKey
                    if (adjacentMissingRefreshes.add(refreshKey)) {
                        val result = withRepositoryCancellation {
                            imageRepository.fetchEpisodesForeground(currentTitle, it)
                        }
                        if (cancelled.get()) return@execute
                        Log.d(TAG, "append_adjacent_refresh direction=$direction result=$result beforeEpisodes=${episodes.size}")
                        if (result == Title.LOAD_OK) {
                            attachTitle()
                            episodes = Utils.snapshotEpisodes(currentTitle)
                            if (episodes.isNotEmpty()) {
                                manga.setEps(episodes)
                                anchorManga.setEps(episodes)
                                persistNtkEpisodeSnapshot(currentTitle, episodes)
                            }
                            anchorManga.title = currentTitle
                            anchorManga.titleId = currentTitle.id
                            target = nextUnloadedAdjacentEpisode(anchorManga, currentTitle, episodes, direction)
                        } else {
                            if (result == Title.LOAD_CAPTCHA) {
                                if (silentMissing) {
                                    suppressedCaptcha = true
                                    return@execute
                                }
                                captchaRequired = true
                                postCaptchaRequired(anchorManga)
                                return@execute
                            }
                            if (!silentMissing) {
                                postMessage("Failed to load episode list")
                            }
                            return@execute
                        }
                    }
                }
                if (target == null) {
                    adjacentMissingTargets.add(adjacentMissingKey)
                    Log.d(
                        TAG,
                        "append_adjacent_target_missing direction=$direction episodes=${episodes.size} " +
                            "sourceKey=${Manga.episodeIdentityKey(anchorManga)}"
                    )
                    if (!silentMissing) {
                        postMessage(if (direction < 0) "이전 회차가 없습니다" else "다음 회차가 없습니다")
                    }
                    return@execute
                }
                var resolvedTarget: Manga? = null
                var resolvedUrls: List<String> = emptyList()
                var checkedCandidates = 0
                for (candidate in adjacentEpisodeCandidates(anchorManga, episodes, direction)) {
                    if (checkedCandidates >= ADJACENT_EXISTING_SKIP_LIMIT) break
                    candidate.title = currentTitle
                    candidate.titleId = currentTitle.id
                    candidate.mode = anchorManga.mode
                    if (episodes.isNotEmpty()) candidate.setEps(episodes)
                    inheritNtkAppendGeneratedHints(candidate, anchorManga, currentTitle)
                    seedNtkAppendGeneratedUrlsFromNeighbor(candidate, anchorManga, currentTitle)
                    val loaded = if (direction < 0) hasEpisodeFast(candidate) else hasEpisode(candidate)
                    if (loaded) {
                        checkedCandidates++
                        continue
                    }
                    Log.d(
                        TAG,
                        "append_adjacent_target direction=$direction targetId=${candidate.id} " +
                            "targetTitleId=${candidate.titleId} targetPath=${candidate.ntkEpisodePath} targetName=${candidate.name}"
                    )
                    val appendLoad = loadAppendUrlsForCandidate(candidate, currentTitle, direction)
                    if (cancelled.get()) return@execute
                    if (appendLoad.result == Title.LOAD_CAPTCHA) {
                        if (silentMissing) {
                            suppressedCaptcha = true
                            return@execute
                        }
                        captchaRequired = true
                        postCaptchaRequired(candidate)
                        return@execute
                    }
                    if (appendLoad.result == Title.LOAD_OK && appendLoad.urls.isNotEmpty()) {
                        resolvedTarget = candidate
                        resolvedUrls = appendLoad.urls
                        break
                    }
                    Log.d(
                        TAG,
                        "append_adjacent_candidate_skip direction=$direction targetId=${candidate.id} " +
                            "path=${candidate.ntkEpisodePath} result=${appendLoad.result} " +
                            "reason=${candidate.ntkViewerParseReason} images=${appendLoad.urls.size}"
                    )
                    candidate.setImgs(null)
                    checkedCandidates++
                }
                if (resolvedTarget == null || resolvedUrls.isEmpty()) {
                    if (!silentMissing) postMessage("표시할 이미지가 없습니다")
                    return@execute
                }
                startAdjacentForegroundStreams(resolvedTarget, resolvedUrls, direction)
                appendResolvedEpisode(resolvedTarget, resolvedUrls, direction)
                scheduleNtkForwardLookahead(resolvedTarget, currentTitle, episodes, direction)
            } catch (e: Exception) {
                recordIfUnexpected(e)
            } finally {
                loadingFlag.set(false)
                if (!captchaRequired) postBoundaryAppendFinished(anchor, direction, silentMissing, suppressedCaptcha)
            }
            }
        } catch (_: RejectedExecutionException) {
            loadingFlag.set(false)
            return AppendStartResult.CANCELLED
        }
        return AppendStartResult.STARTED
    }

    private fun loadAppendUrlsForCandidate(target: Manga, currentTitle: Title, direction: Int): AppendUrlLoad {
        var urls = imageRepository.imageUrls(target, appContext)
        val earlyInstalled = if (isNtkSource(target, currentTitle)) {
            installEarlyGeneratedAppendUrlsIfAvailable(target) > 0
        } else {
            false
        }
        if (earlyInstalled) {
            urls = imageRepository.imageUrls(target, appContext)
        }
        val seededGeneratedUrls = !urls.isNullOrEmpty() && urls.any { isNtkGeneratedImageUrl(it) }
        val seededTrustedEarlyUrls =
            earlyInstalled ||
                (!urls.isNullOrEmpty() &&
                    isNtkSource(target, currentTitle) &&
                    isNtkSyntheticEpisodePath(target.ntkEpisodePath) &&
                    urls.none { isNtkGeneratedImageUrl(it) })
        val preferVerifiedApiAppend =
            shouldPreferVerifiedApiAppend(target, currentTitle) && !seededGeneratedUrls && !seededTrustedEarlyUrls
        if (!preferVerifiedApiAppend && !urls.isNullOrEmpty() &&
            isNtkSource(target, currentTitle) &&
            shouldRefreshNtkGeneratedAppendUrls(urls)
        ) {
            target.setImgs(null)
            val result = fetchGeneratedNtkAppendUrls(target, currentTitle, direction)
            Log.d(TAG, "append_adjacent_fetch direction=$direction targetId=${target.id} result=$result")
            if (result != Title.LOAD_OK) return AppendUrlLoad(result, emptyList())
            urls = imageRepository.imageUrls(target, appContext)
        }
        if (urls.isNullOrEmpty() || preferVerifiedApiAppend) {
            if (preferVerifiedApiAppend) target.setImgs(null)
            val result = fetchGeneratedNtkAppendUrls(target, currentTitle, direction)
            Log.d(TAG, "append_adjacent_fetch direction=$direction targetId=${target.id} result=$result")
            if (result != Title.LOAD_OK &&
                installEarlyGeneratedAppendUrlsIfAvailable(target) <= 0
            ) {
                return AppendUrlLoad(result, emptyList())
            }
            urls = imageRepository.imageUrls(target, appContext)
        }
        return AppendUrlLoad(Title.LOAD_OK, urls ?: emptyList())
    }

    private fun loadLookaheadAppendUrls(target: Manga, currentTitle: Title, direction: Int): AppendUrlLoad {
        var urls = imageRepository.imageUrls(target, appContext)
        val earlyInstalled = if (isNtkSource(target, currentTitle)) {
            installEarlyGeneratedAppendUrlsIfAvailable(target) > 0
        } else {
            false
        }
        if (earlyInstalled) {
            urls = imageRepository.imageUrls(target, appContext)
        }
        val seededGeneratedUrls = !urls.isNullOrEmpty() && urls.any { isNtkGeneratedImageUrl(it) }
        val seededTrustedEarlyUrls =
            earlyInstalled ||
                (!urls.isNullOrEmpty() &&
                    isNtkSource(target, currentTitle) &&
                    isNtkSyntheticEpisodePath(target.ntkEpisodePath) &&
                    urls.none { isNtkGeneratedImageUrl(it) })
        val preferVerifiedApiAppend =
            shouldPreferVerifiedApiAppend(target, currentTitle) && !seededGeneratedUrls && !seededTrustedEarlyUrls
        if (!preferVerifiedApiAppend && !urls.isNullOrEmpty() &&
            isNtkSource(target, currentTitle) &&
            shouldRefreshNtkGeneratedAppendUrls(urls)
        ) {
            target.setImgs(null)
            val result = fetchGeneratedNtkAppendUrls(target, currentTitle, direction)
            if (result != Title.LOAD_OK) return AppendUrlLoad(result, emptyList())
            urls = imageRepository.imageUrls(target, appContext)
        }
        if (urls.isNullOrEmpty() || preferVerifiedApiAppend) {
            if (preferVerifiedApiAppend) target.setImgs(null)
            val result = fetchGeneratedNtkAppendUrls(target, currentTitle, direction)
            if (result != Title.LOAD_OK &&
                installEarlyGeneratedAppendUrlsIfAvailable(target) <= 0
            ) {
                return AppendUrlLoad(result, emptyList())
            }
            urls = imageRepository.imageUrls(target, appContext)
        }
        return AppendUrlLoad(Title.LOAD_OK, urls ?: emptyList())
    }

    private fun appendNtkForwardLookahead(
        source: Manga,
        currentTitle: Title,
        episodes: List<Manga>,
        direction: Int
    ) {
        if (direction == 0 || cancelled.get() || !isNtkSource(source, currentTitle)) return
        val target = nextUnloadedAdjacentEpisode(source, currentTitle, episodes, direction)
        if (target == null) {
            Log.d(
                TAG,
                "append_adjacent_lookahead_missing direction=$direction sourceId=${source.id} " +
                    "sourcePath=${source.ntkEpisodePath} episodes=${episodes.size}"
            )
            return
        }
        target.title = currentTitle
        target.titleId = currentTitle.id
        target.mode = source.mode
        if (episodes.isNotEmpty()) target.setEps(episodes)
        inheritNtkAppendGeneratedHints(target, source, currentTitle)
        seedNtkAppendGeneratedUrlsFromNeighbor(target, source, currentTitle)
        val syntheticNtkPath = isNtkSyntheticEpisodePath(target.ntkEpisodePath)
        if (shouldPreferVerifiedApiAppend(target, currentTitle) && !syntheticNtkPath) {
            Log.d(
                TAG,
                "append_adjacent_lookahead_skip_verified_api direction=$direction sourceId=${source.id} targetId=${target.id} " +
                    "targetPath=${target.ntkEpisodePath}"
            )
            return
        }
        Log.d(
            TAG,
            "append_adjacent_lookahead_start direction=$direction sourceId=${source.id} targetId=${target.id} " +
                "targetPath=${target.ntkEpisodePath} targetName=${target.name}"
        )
        val appendUrls = loadLookaheadAppendUrls(target, currentTitle, direction)
        if (cancelled.get()) return
        Log.d(
            TAG,
            "append_adjacent_lookahead_fetch direction=$direction targetId=${target.id} result=${appendUrls.result} " +
                "images=${appendUrls.urls.size}"
        )
        if (appendUrls.result != Title.LOAD_OK || appendUrls.urls.isEmpty()) return
        Log.d(
            TAG,
            "append_adjacent_lookahead_prepared direction=$direction targetId=${target.id} " +
                "targetPath=${target.ntkEpisodePath} images=${appendUrls.urls.size}"
        )
    }

    private fun scheduleNtkForwardLookahead(
        source: Manga,
        currentTitle: Title,
        episodes: List<Manga>,
        direction: Int
    ) {
        if (direction == 0 || cancelled.get() || !isNtkSource(source, currentTitle)) return
        try {
            adjacentNetwork.execute {
                try {
                    appendNtkForwardLookahead(source, currentTitle, episodes, direction)
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Session is closing; the visible append already completed.
        }
    }

    private fun scheduleNtkAdjacentAckPreflightsAfterFirstBitmap(includeLookahead: Boolean = true) {
        if (!isNtkSource(manga, title)) return
        if (!firstBitmapLogged.get()) {
            Log.d(
                TAG,
                "ntk_adjacent_ack_preflight_skip_before_first_bitmap path=${manga.ntkEpisodePath}"
            )
            return
        }
        val currentPath = manga.ntkEpisodePath?.trim().orEmpty()
        if (currentPath.isNotEmpty() &&
            !MainApplication.getHttpClient().hasRecentStrictNtkAdAckProof(currentPath)
        ) {
            Log.d(
                TAG,
                "ntk_adjacent_ack_preflight_skip_before_current_ack path=$currentPath"
            )
            return
        }
        try {
            primeNetwork.execute {
                try {
                    val currentTitle = title ?: manga.title ?: return@execute
                    if (syncNtkTitlePathFromEpisode(currentTitle, manga)) {
                        currentTitle.removeEps()
                    }
                    restoreNtkEpisodeSnapshotIfNeeded(currentTitle, manga)
                    if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                        val result = withRepositoryCancellation {
                            imageRepository.fetchEpisodesForeground(currentTitle, it)
                        }
                        if (cancelled.get() || result != Title.LOAD_OK) return@execute
                    }
                    attachTitle()
                    val episodes = Utils.snapshotEpisodes(currentTitle)
                    if (episodes.isNotEmpty()) {
                        manga.setEps(episodes)
                        persistNtkEpisodeSnapshot(currentTitle, episodes)
                    }
                    manga.title = currentTitle
                    manga.titleId = currentTitle.id
                    if (includeLookahead) {
                        val syntheticCurrentPath = isNtkSyntheticEpisodePath(manga.ntkEpisodePath)
                        if (!syntheticCurrentPath) {
                            scheduleNtkForwardLookahead(
                                manga,
                                currentTitle,
                                episodes,
                                ReaderSurfaceView.DIRECTION_NEXT
                            )
                            scheduleNtkForwardLookahead(
                                manga,
                                currentTitle,
                                episodes,
                                ReaderSurfaceView.DIRECTION_PREVIOUS
                            )
                        }
                    }
                    val next = manga.nextEp()
                    val previous = manga.prevEp()
                    val candidates = arrayOf(next to 0L, previous to 1200L)
                    for ((candidate, delayMs) in candidates) {
                        if (cancelled.get()) return@execute
                        if (candidate == null) continue
                        candidate.title = currentTitle
                        candidate.titleId = currentTitle.id
                        candidate.mode = manga.mode
                        if (episodes.isNotEmpty()) candidate.setEps(episodes)
                        val path = candidate.ntkEpisodePath?.trim().orEmpty()
                        if (!isNtkSyntheticEpisodePath(path)) continue
                        startNtkAdjacentAckPreflight(path, delayMs)
                    }
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Session is closing; adjacent ACK preflight is only a background accelerator.
        }
    }

    private fun startNtkAdjacentAckPreflight(path: String, delayMs: Long = 0L) {
        if (!ntkAdjacentAckPreflightPaths.add(path)) return
        val sourcePath = manga.ntkEpisodePath
        val thread = Thread({
            try {
                Process.setThreadPriority(
                    if (delayMs <= 0L) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND
                )
                if (delayMs > 0L) SystemClock.sleep(delayMs)
                if (cancelled.get()) return@Thread
                Log.d(
                    TAG,
                    "ntk_adjacent_ack_preflight_start sourcePath=$sourcePath targetPath=$path delayMs=$delayMs"
                )
                val ok = MainApplication.getHttpClient().performNtkWebViewAckPreflight(path)
                Log.d(
                    TAG,
                    "ntk_adjacent_ack_preflight_done sourcePath=$sourcePath targetPath=$path success=$ok"
                )
            } catch (e: Exception) {
                recordIfUnexpected(e)
            }
        }, "ntk-adjacent-ack-preflight")
        thread.isDaemon = true
        thread.start()
    }

    private fun seedNtkAppendGeneratedUrlsFromNeighbor(target: Manga, source: Manga, currentTitle: Title): Int {
        if (!isNtkSource(target, currentTitle) || !isNtkSource(source, currentTitle)) return 0
        if (!imageRepository.imageUrls(target, appContext).isNullOrEmpty()) return 0
        val extension = generatedExtensionForAppendNeighbor(source)
        if (extension.isEmpty()) return 0
        val path = target.ntkEpisodePath?.trim().orEmpty()
        val match = NTK_VIEWER_EPISODE_PATH.matchEntire(path) ?: return 0
        val segment = match.groupValues[1].lowercase(Locale.ROOT)
        val pathWorkId = match.groupValues[2].trim()
        val pathEpisodeToken = match.groupValues[3].trim()
        val inheritedWorkId = target.ntkImageWorkId.trim()
        val imageWorkId = if (inheritedWorkId.isNotEmpty()) inheritedWorkId else pathWorkId
        val recordedImageEpisodeId = target.ntkImageEpisodeId.trim()
        val imageEpisodeId = if (pathEpisodeToken.matches(Regex("\\d+"))) {
            canonicalWebtoonAppendImageEpisodeId(
                segment,
                pathWorkId,
                pathEpisodeToken,
                imageWorkId,
                recordedImageEpisodeId
            )
        } else {
            recordedImageEpisodeId
        }
        val count = target.ntkImageCount
        if (!imageWorkId.matches(Regex("\\d{1,12}")) ||
            !imageEpisodeId.matches(Regex("\\d{1,12}")) ||
            count <= 0
        ) {
            Log.d(
                TAG,
                "append_adjacent_seed_generated_skip_invalid path=$path " +
                    "workId=$imageWorkId imageEpisodeId=$imageEpisodeId count=$count " +
                    "sourcePath=${source.ntkEpisodePath}"
            )
            return 0
        }
        val urls = ArrayList<String>(count)
        for (page in 1..count) {
            val pageName = "p%03d.%s".format(Locale.ROOT, page, extension)
            val url = if (segment == "webtoon") {
                "https://moamoabon.com/blacktoon/episodes/$imageWorkId/$imageEpisodeId/$pageName"
            } else {
                "https://moamoabon.com/$segment/$imageWorkId/$imageEpisodeId/$pageName"
            }
            urls.add(url)
        }
        target.setImgs(urls)
        ReaderImageCache.rememberEarlyNtkImageUrls(path, urls.take(NTK_APPEND_EARLY_PUBLISH_PAGES))
        Log.d(
            TAG,
            "append_adjacent_seed_generated_urls targetId=${target.id} path=$path " +
                "workId=$imageWorkId imageEpisodeId=$imageEpisodeId count=${urls.size} " +
                "extension=$extension sourcePath=${source.ntkEpisodePath}"
        )
        return urls.size
    }

    private fun canonicalWebtoonAppendImageEpisodeId(
        segment: String,
        pathWorkId: String,
        pathEpisodeToken: String,
        imageWorkId: String,
        recordedImageEpisodeId: String
    ): String {
        if (segment != "webtoon") return recordedImageEpisodeId
        val pathWork = pathWorkId.toLongOrNull() ?: return recordedImageEpisodeId
        if (pathWork < NTK_CANONICAL_WEBTOON_APPEND_MIN_WORK_ID) return recordedImageEpisodeId
        if (imageWorkId == pathWorkId) return recordedImageEpisodeId
        if (!pathEpisodeToken.matches(Regex("\\d{1,12}"))) return recordedImageEpisodeId
        if (recordedImageEpisodeId == pathEpisodeToken) return recordedImageEpisodeId
        Log.d(
            TAG,
            "append_adjacent_seed_generated_canonical_episode_fallback pathWorkId=$pathWorkId " +
                "imageWorkId=$imageWorkId recordedImageEpisodeId=$recordedImageEpisodeId " +
                "pathEpisodeId=$pathEpisodeToken"
        )
        return pathEpisodeToken
    }

    private fun generatedExtensionForAppendNeighbor(source: Manga): String {
        val earlyUrls = ReaderImageCache.earlyNtkImageUrls(source.ntkEpisodePath, 0L)
        val early = earlyUrls.firstOrNull { isNtkGeneratedImageUrl(it) }
        val extension = generatedImageExtension(early)
        if (extension.isNotEmpty()) return extension
        val sourceUrls = imageRepository.imageUrls(source, appContext)
        return generatedImageExtension(sourceUrls.firstOrNull { isNtkGeneratedImageUrl(it) })
    }

    private fun generatedImageExtension(image: String?): String {
        if (image.isNullOrBlank()) return ""
        val match = NTK_GENERATED_IMAGE_EXTENSION.find(image) ?: return ""
        return match.groupValues[1].lowercase(Locale.ROOT)
    }

    private fun inheritNtkAppendGeneratedHints(target: Manga, source: Manga, currentTitle: Title) {
        if (!isNtkSource(target, currentTitle) || !isNtkSource(source, currentTitle)) return
        if (target.titleId != source.titleId && target.titleId != currentTitle.id) return
        var inherited = false
        if (target.ntkImageWorkId.isNullOrBlank()) {
            val sourceWorkId = source.ntkImageWorkId
            if (!sourceWorkId.isNullOrBlank()) {
                target.ntkImageWorkId = sourceWorkId
                inherited = true
            }
        }
        if (target.ntkImageCount <= 0 && source.ntkImageCount > 0) {
            target.setNtkImageCount(source.ntkImageCount)
            inherited = true
        }
        if (inherited) {
            Log.d(
                TAG,
                "append_adjacent_inherit_generated_hints targetId=${target.id} " +
                    "targetPath=${target.ntkEpisodePath} sourceId=${source.id} " +
                    "sourcePath=${source.ntkEpisodePath} imageWorkId=${target.ntkImageWorkId} " +
                    "imageEpisodeId=${target.ntkImageEpisodeId} imageCount=${target.ntkImageCount}"
            )
        }
    }

    private fun fetchGeneratedNtkAppendUrls(target: Manga, currentTitle: Title, direction: Int): Int {
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "append_adjacent_verified_fetch_start direction=$direction targetId=${target.id} " +
                "titleId=${currentTitle.id} path=${target.ntkEpisodePath}"
        )
        return try {
            val preferApiFirst = shouldPreferVerifiedApiAppend(target, currentTitle)
            val syntheticNtkPath = isNtkSyntheticEpisodePath(target.ntkEpisodePath)
            Log.d(
                TAG,
                "append_adjacent_verified_fetch_mode targetId=${target.id} path=${target.ntkEpisodePath} " +
                    "preferApiFirst=$preferApiFirst synthetic=$syntheticNtkPath " +
                    "isNtk=${isNtkSource(target, currentTitle)}"
            )
            val initialResult = if (isNtkSource(target, currentTitle) && !preferApiFirst && !syntheticNtkPath) {
                fetchGeneratedNtkAppendUrlsWithEarlyHandoff(target)
            } else if (syntheticNtkPath) {
                fetchGeneratedNtkAppendUrlsWithEarlyHandoff(target, "api-strict")
            } else {
                withRepositoryCancellation(userVisible = true) { cancellation ->
                    if (preferApiFirst || syntheticNtkPath) {
                        imageRepository.fetchViewerInitialWithMode(target, cancellation, "api-strict")
                    } else {
                        imageRepository.fetchViewerInitial(target, cancellation)
                    }
                }
            }
            val initialImages = imageRepository.imageUrls(target, appContext).size
            val earlyGeneratedImages = installEarlyGeneratedAppendUrlsIfAvailable(target)
            if (earlyGeneratedImages > 0 ||
                (initialResult == Title.LOAD_OK && initialImages > 0) ||
                (initialResult == Title.LOAD_CAPTCHA && !syntheticNtkPath) ||
                cancelled.get()
            ) {
                if (earlyGeneratedImages > 0) Title.LOAD_OK else initialResult
            } else {
                if (target.ntkViewerParseReason == "unavailable") {
                    initialResult
                    } else {
                        restoreNtkEpisodeSnapshotIfNeeded(currentTitle, target)
                        target.setImgs(null)
                    val retryMode = if (isNtkSource(target, currentTitle) && !syntheticNtkPath) {
                        "generated"
                    } else {
                        "api-strict"
                    }
                    Log.d(
                        TAG,
                        "append_adjacent_verified_fetch_retry direction=$direction targetId=${target.id} " +
                            "initialResult=$initialResult initialImages=$initialImages retryMode=$retryMode " +
                            "path=${target.ntkEpisodePath}"
                    )
                    val retryResult = withRepositoryCancellation(userVisible = true) { retryCancellation ->
                        if (retryMode == "generated" && !syntheticNtkPath) {
                            imageRepository.fetchViewerInitial(target, retryCancellation)
                        } else {
                            imageRepository.fetchViewerInitialWithMode(target, retryCancellation, retryMode)
                        }
                    }
                    if (retryResult != Title.LOAD_OK &&
                        (installEarlyGeneratedAppendUrlsIfAvailable(target) > 0 ||
                            (retryMode == "api-strict" &&
                                waitForEarlyAppendUrlsIfAvailable(target, NTK_APPEND_EARLY_API_STRICT_LATE_WAIT_MS) > 0))
                    ) {
                        Title.LOAD_OK
                    } else {
                        retryResult
                    }
                }
            }.also {
                Log.d(
                    TAG,
                    "append_adjacent_verified_fetch direction=$direction targetId=${target.id} " +
                        "result=$it ms=${SystemClock.elapsedRealtime() - startedAt} " +
                        "reason=${target.ntkViewerParseReason} images=${imageRepository.imageUrls(target, appContext).size}"
                )
            }
        } catch (e: Exception) {
            recordIfUnexpected(e)
            Title.LOAD_ERROR
        }
    }

    private fun fetchGeneratedNtkAppendUrlsWithEarlyHandoff(target: Manga, mode: String? = null): Int {
        Log.d(
            TAG,
            "append_adjacent_early_handoff_start path=${target.ntkEpisodePath} mode=$mode " +
                "images=${imageRepository.imageUrls(target, appContext).size}"
        )
        val task = FutureTask {
            val appendCancellation = MangaRepository.cancellation()
            appendCancellation.userVisible()
            if (mode != null) {
                imageRepository.fetchViewerInitialWithMode(target, appendCancellation, mode)
            } else {
                imageRepository.fetchViewerInitial(target, appendCancellation)
            }
        }
        return try {
            val fetchThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                task.run()
            }, "ntk-append-fetch")
            fetchThread.isDaemon = true
            fetchThread.start()
            val deadline = SystemClock.elapsedRealtime() + NTK_APPEND_EARLY_GENERATED_WAIT_MS
            while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                if (installEarlyGeneratedAppendUrlsIfAvailable(target) > 0) {
                    Log.d(
                        TAG,
                        "append_adjacent_early_generated_handoff path=${target.ntkEpisodePath} " +
                            "images=${imageRepository.imageUrls(target, appContext).size}"
                    )
                    return Title.LOAD_OK
                }
                SystemClock.sleep(NTK_APPEND_EARLY_GENERATED_POLL_MS)
            }
            val result = try {
                task.get()
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is Exception) throw cause
                throw e
            }
            Log.d(
                TAG,
                "append_adjacent_api_strict_probe path=${target.ntkEpisodePath} mode=$mode " +
                    "result=$result images=${imageRepository.imageUrls(target, appContext).size} " +
                    "reason=${target.ntkViewerParseReason} cancelled=${cancelled.get()}"
            )
            if (installEarlyGeneratedAppendUrlsIfAvailable(target) > 0 ||
                (mode == "api-strict" &&
                    waitForEarlyAppendUrlsIfAvailable(target, NTK_APPEND_EARLY_API_STRICT_LATE_WAIT_MS) > 0)
            ) {
                Title.LOAD_OK
            } else if (
                mode == "api-strict" &&
                result != Title.LOAD_OK &&
                waitForStrictNtkAckProof(target, NTK_APPEND_API_STRICT_ACK_RETRY_WAIT_MS)
            ) {
                val retryResult = withRepositoryCancellation(userVisible = true) { retryCancellation ->
                    imageRepository.fetchViewerInitialWithMode(target, retryCancellation, mode)
                }
                if (installEarlyGeneratedAppendUrlsIfAvailable(target) > 0 ||
                    waitForEarlyAppendUrlsIfAvailable(target, NTK_APPEND_EARLY_API_STRICT_LATE_WAIT_MS) > 0
                ) {
                    Title.LOAD_OK
                } else {
                    retryResult
                }
            } else {
                result
            }
        } catch (e: Exception) {
            recordIfUnexpected(e)
            Log.d(
                TAG,
                "append_adjacent_early_handoff_error path=${target.ntkEpisodePath} mode=$mode " +
                    "cancelled=${cancelled.get()} error=${e.javaClass.simpleName}:${e.message}"
            )
            if (mode == "api-strict") {
                if (!cancelled.get() && waitForStrictNtkAckProof(target, NTK_APPEND_API_STRICT_ACK_RETRY_WAIT_MS)) {
                    val retryResult = try {
                        withRepositoryCancellation(userVisible = true) { retryCancellation ->
                            imageRepository.fetchViewerInitialWithMode(target, retryCancellation, mode)
                        }
                    } catch (retryError: Exception) {
                        recordIfUnexpected(retryError)
                        Log.d(
                            TAG,
                            "append_adjacent_early_handoff_retry_error path=${target.ntkEpisodePath} " +
                                "error=${retryError.javaClass.simpleName}:${retryError.message}"
                        )
                        Title.LOAD_ERROR
                    }
                    if (installEarlyGeneratedAppendUrlsIfAvailable(target) > 0 ||
                        waitForEarlyAppendUrlsIfAvailable(target, NTK_APPEND_EARLY_API_STRICT_LATE_WAIT_MS) > 0
                    ) {
                        Title.LOAD_OK
                    } else {
                        retryResult
                    }
                } else if (waitForEarlyAppendUrlsIfAvailable(
                        target,
                        NTK_APPEND_EARLY_API_STRICT_LATE_WAIT_MS,
                        ignoreSessionCancelled = true
                    ) > 0) {
                    Title.LOAD_OK
                } else {
                    Title.LOAD_ERROR
                }
            } else {
                Title.LOAD_ERROR
            }
        }
    }

    private fun shouldPreferVerifiedApiAppend(target: Manga, currentTitle: Title): Boolean {
        if (!isNtkSource(target, currentTitle)) return false
        if (target.baseMode != ml.melun.mangaview.mangaview.MTitle.base_webtoon) return false
        return !Manga.shouldUseGeneratedAppendBeforeApi(
            target.baseMode,
            target.ntkEpisodePath,
            target.ntkImageCount
        )
    }

    private fun installEarlyGeneratedAppendUrlsIfAvailable(target: Manga): Int {
        if (!isNtkSource(target, title)) return 0
        var earlyUrls = ReaderImageCache.earlyNtkAppendImageUrls(
            target.ntkEpisodePath,
            SystemClock.elapsedRealtime() - 30000L
        )
        if (earlyUrls.isEmpty() && isNtkSyntheticEpisodePath(target.ntkEpisodePath)) {
            earlyUrls = ReaderImageCache.earlyNtkImageUrls(
                target.ntkEpisodePath,
                SystemClock.elapsedRealtime() - 30000L
            )
        }
        if (earlyUrls.isEmpty()) return 0
        target.setImgs(ArrayList(earlyUrls))
        Log.d(
            TAG,
            "append_adjacent_early_generated_installed targetId=${target.id} " +
                "path=${target.ntkEpisodePath} images=${earlyUrls.size}"
        )
        return earlyUrls.size
    }

    private fun waitForEarlyAppendUrlsIfAvailable(
        target: Manga,
        timeoutMs: Long,
        ignoreSessionCancelled: Boolean = false
    ): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while ((ignoreSessionCancelled || !cancelled.get()) && SystemClock.elapsedRealtime() < deadline) {
            val installed = installEarlyGeneratedAppendUrlsIfAvailable(target)
            if (installed > 0) {
                Log.d(
                    TAG,
                    "append_adjacent_early_api_handoff path=${target.ntkEpisodePath} images=$installed"
                )
                return installed
            }
            SystemClock.sleep(NTK_APPEND_EARLY_GENERATED_POLL_MS)
        }
        return installEarlyGeneratedAppendUrlsIfAvailable(target)
    }

    private fun waitForStrictNtkAckProof(target: Manga, timeoutMs: Long): Boolean {
        val path = target.ntkEpisodePath ?: return false
        val client = MainApplication.getHttpClient()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
            if (client.hasRecentStrictNtkAdAckProof(path)) {
                Log.d(
                    TAG,
                    "append_adjacent_api_strict_ack_ready path=$path ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return true
            }
            SystemClock.sleep(NTK_APPEND_EARLY_GENERATED_POLL_MS)
        }
        val ready = client.hasRecentStrictNtkAdAckProof(path)
        Log.d(
            TAG,
            "append_adjacent_api_strict_ack_wait_done path=$path ready=$ready " +
                "cancelled=${cancelled.get()} ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return ready
    }

    private fun primeForwardTimeline() {
        if (cancelled.get() || !timelinePrimeLoading.compareAndSet(false, true)) return
        try {
            control.execute {
            try {
                var current = manga
                val currentTitle = title ?: current.title ?: manga.title ?: return@execute
                if (syncNtkTitlePathFromEpisode(currentTitle, current)) {
                    currentTitle.removeEps()
                }
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    val result = withRepositoryCancellation {
                        imageRepository.fetchEpisodesForeground(currentTitle, it)
                    }
                    if (cancelled.get()) return@execute
                    if (result != Title.LOAD_OK) return@execute
                }
                attachTitle()
                val episodes = Utils.snapshotEpisodes(currentTitle)
                if (episodes.isNotEmpty()) {
                    manga.setEps(episodes)
                    current.setEps(episodes)
                }
                current.title = currentTitle
                current.titleId = currentTitle.id
                val ntk = isNtkSource(manga, title)
                val primedRefs = ArrayList<PageRef>()
                val cardOffsets = ArrayList<Int>()
                var checked = 0
                val maxPrimeEpisodes = if (ntk) NTK_PRIME_FORWARD_EPISODES else PRIME_FORWARD_EPISODES
                while (!cancelled.get() && checked < maxPrimeEpisodes) {
                    val target = current.nextEp() ?: break
                    target.title = currentTitle
                    target.titleId = currentTitle.id
                    target.mode = current.mode
                    if (episodes.isNotEmpty()) target.setEps(episodes)
                    if (!hasEpisode(target)) {
                        if (imageRepository.imageUrls(target, appContext).isNullOrEmpty()) {
                            val result = withRepositoryCancellation {
                                imageRepository.fetchViewerInitial(target, it)
                            }
                            if (cancelled.get()) return@execute
                            if (result != Title.LOAD_OK) break
                        }
                        val urls = imageRepository.imageUrls(target, appContext)
                        if (urls.isNullOrEmpty()) break
                        val episodeRefs = pageRefsForEpisode(target, urls, ReaderSurfaceView.DIRECTION_NEXT)
                        if (ntk) {
                            appendPrimedForwardRefs(episodeRefs, listOf(0), lightWarm = checked > 0)
                        } else {
                            cardOffsets.add(primedRefs.size)
                            primedRefs.addAll(episodeRefs)
                        }
                    }
                    checked++
                    current = target
                }
                if (!ntk) appendPrimedForwardRefs(primedRefs, cardOffsets)
            } catch (e: Exception) {
                recordIfUnexpected(e)
            } finally {
                timelinePrimeLoading.set(false)
            }
            }
        } catch (_: RejectedExecutionException) {
            timelinePrimeLoading.set(false)
        }
    }

    private fun pageRefsForEpisode(target: Manga, urls: List<String>, direction: Int): List<PageRef> {
        val episodeName = target.name ?: title?.name ?: "회차"
        val transitionTitle = if (direction < 0) "이전 회차: $episodeName" else "다음 회차: $episodeName"
        var pageRefs = pageRefsForImages(target, urls)
        if (isNtkSource(target, title) && pageRefs.any { isNtkBoardUploadImageUrl(it.image) }) {
            logNtkRepositoryStage(
                target,
                "board_uploads_keep_original_timeline",
                "count=${pageRefs.count { isNtkBoardUploadImageUrl(it.image) }}"
            )
        }
        val totalPages = pageRefs.size
        return ArrayList<PageRef>(pageRefs.size + 1).apply {
            if (direction < 0) {
                addAll(pageRefs)
                add(PageRef(target, null, transitionTitle, localPage = 0, totalPages = totalPages))
            } else {
                add(PageRef(target, null, transitionTitle, localPage = 0, totalPages = totalPages))
                addAll(pageRefs)
            }
        }
    }

    private fun pageRefsForImages(target: Manga, urls: List<String>): List<PageRef> {
        if (!autoCut) {
            val totalPages = urls.size
            return urls.mapIndexed { index, url ->
                PageRef(
                    manga = target,
                    image = url,
                    sourceIndex = index,
                    pageIndex = index,
                    localPage = index + 1,
                    totalPages = totalPages
                )
            }
        }
        val splitPages = urls.map { url -> shouldAutoSplitImage(target, url) }
        val totalPages: Int = splitPages.fold(0) { total, split -> total + if (split) 2 else 1 }
        val refs = ArrayList<PageRef>(totalPages)
        var localPage = 1
        for (index in urls.indices) {
            val url = urls[index]
            val split = splitPages[index]
            refs.add(PageRef(target, url, sourceIndex = index, localPage = localPage++, totalPages = totalPages, side = PAGE_SIDE_FIRST, allowAutoSplit = split))
            if (splitPages[index]) {
                refs.add(PageRef(target, url, sourceIndex = index, localPage = localPage++, totalPages = totalPages, side = PAGE_SIDE_SECOND, allowAutoSplit = split))
            }
        }
        return refs
    }

    private fun displayStartPage(sourcePage: Int, sourceSide: Int, refs: List<PageRef>): Int {
        if (refs.isEmpty()) return 0
        if (!autoCut) return sourcePage.coerceIn(0, refs.lastIndex)
        val side = sourceSide.coerceIn(PAGE_SIDE_FIRST, PAGE_SIDE_SECOND)
        val exact = refs.indexOfFirst { it.sourceIndex == sourcePage && it.side == side }
        if (exact >= 0) return exact
        val first = refs.indexOfFirst { it.sourceIndex == sourcePage }
        return if (first >= 0) first else sourcePage.coerceIn(0, refs.lastIndex)
    }

    private fun appendPrimedForwardRefs(refs: List<PageRef>, cardOffsets: List<Int>, lightWarm: Boolean = false) {
        if (cancelled.get() || refs.isEmpty()) return
        val startIndex: Int
        val total: Int
        val appendedCards = ArrayList<Pair<Int, String>>()
        synchronized(pagesLock) {
            val appendable = appendableNewEpisodeRefsLocked(refs)
            if (appendable.isEmpty()) return
            beginStructurePublish()
            startIndex = pages.size
            appendable.forEachIndexed { offset, page -> page.pageIndex = startIndex + offset }
            pages.addAll(appendable)
            total = pages.size
            for (offset in cardOffsets) {
                val card = refs.getOrNull(offset) ?: continue
                val appendedOffset = appendable.indexOf(card)
                if (appendedOffset >= 0 && card.transitionTitle != null) {
                    appendedCards.add(Pair(startIndex + appendedOffset, card.transitionTitle))
                }
            }
        }
        val posted = main.post {
            var shouldWarm = false
            try {
                if (!cancelled.get()) {
                    listener.onPagesAppended(total)
                    for ((index, title) in appendedCards) {
                        listener.onPageCard(index, title)
                    }
                    shouldWarm = true
                }
            } finally {
                finishStructurePublish()
            }
            if (shouldWarm) warmPrimedForwardRefs(appendedCards.map { it.first }, total, lightWarm)
        }
        if (!posted) finishStructurePublish()
    }

    private fun warmPrimedForwardRefs(cardIndexes: List<Int>, total: Int, lightWarm: Boolean = false) {
        if (cancelled.get() || cardIndexes.isEmpty()) return
        val generation = if (isNtkSource(manga, title)) PRIME_WARM_GENERATION else windowGeneration.get()
        val ntk = isNtkSource(manga, title)
        val busy = if (ntk) true else viewportBusy.get()
        val decodeAhead = if (ntk) {
            if (lightWarm) NTK_LIGHT_PRIMED_EPISODE_DECODE_AHEAD_PAGES else NTK_PRIMED_EPISODE_DECODE_AHEAD_PAGES
        } else {
            BOUNDARY_DECODE_AHEAD_PAGES
        }
        val byteAhead = if (ntk) {
            if (lightWarm) NTK_LIGHT_PRIMED_EPISODE_BYTE_AHEAD_PAGES else NTK_PRIMED_EPISODE_BYTE_AHEAD_PAGES
        } else {
            BOUNDARY_BYTE_AHEAD_PAGES
        }
        for (cardIndex in cardIndexes) {
            requestPage(cardIndex, busy = busy, anchor = false, generation = generation)
            val decodeLast = minOf(total - 1, cardIndex + decodeAhead)
            for (index in (cardIndex + 1)..decodeLast) {
                val priority = ntk && index <= cardIndex + NTK_PRIMED_EPISODE_PRIORITY_PAGES
                val requestGeneration = if (priority) FOREGROUND_PRIME_WARM_GENERATION else generation
                requestPage(index, busy = busy, anchor = false, generation = requestGeneration)
            }
            val byteLast = minOf(total - 1, cardIndex + byteAhead)
            for (index in (decodeLast + 1)..byteLast) {
                val page = pageRef(index) ?: continue
                try {
                    network.execute { prefetchImageFileQuietly(index, page) }
                } catch (_: RejectedExecutionException) {
                    return
                }
            }
        }
    }

    private fun appendResolvedEpisode(target: Manga, urls: List<String>, direction: Int, warm: Boolean = true) {
        val refs = pageRefsForEpisode(target, urls, direction)
        val cardOffset = refs.indexOfFirst { it.transitionTitle != null }
        val transitionTitle = refs.getOrNull(cardOffset)?.transitionTitle ?: ""
        val inserted = refs.size
        val total: Int
        if (inserted <= 0) {
            Log.d(TAG, "append_adjacent_resolved_empty direction=$direction targetId=${target.id} path=${target.ntkEpisodePath} urls=${urls.size}")
            return
        }
        if (direction < 0) {
            synchronized(pagesLock) {
                if (containsEpisodeForAppendLocked(target)) {
                    Log.d(TAG, "append_adjacent_resolved_duplicate direction=$direction targetId=${target.id} path=${target.ntkEpisodePath} pages=${pages.size}")
                    return
                }
                beginStructurePublish()
                for (page in pages) page.pageIndex += inserted
                refs.forEachIndexed { index, page -> page.pageIndex = index }
                pages.addAll(0, refs)
                total = pages.size
                shiftPageStateForPrepend(inserted)
            }
            val posted = main.post {
                var shouldWarm = false
                var finished = false
                try {
                    finishStructurePublish()
                    finished = true
                    if (!cancelled.get()) {
                        if (warm) {
                            warmPrependedEpisode(inserted)
                            warmPrependedEpisodeStart(inserted)
                        }
                        listener.onPagesPrepended(total, inserted)
                        if (cardOffset >= 0) listener.onPageCard(cardOffset, transitionTitle)
                        redeliverReadyPrependedStart(inserted)
                    }
                } finally {
                    if (!finished) finishStructurePublish()
                }
            }
            if (!posted) finishStructurePublish()
        } else {
            val cardIndex: Int
            synchronized(pagesLock) {
                if (containsEpisodeForAppendLocked(target)) {
                    Log.d(TAG, "append_adjacent_resolved_duplicate direction=$direction targetId=${target.id} path=${target.ntkEpisodePath} pages=${pages.size}")
                    return
                }
                beginStructurePublish()
                cardIndex = pages.size
                refs.forEachIndexed { offset, page -> page.pageIndex = cardIndex + offset }
                pages.addAll(refs)
                total = pages.size
            }
            Log.d(TAG, "append_adjacent_resolved_inserted direction=$direction targetId=${target.id} path=${target.ntkEpisodePath} inserted=$inserted total=$total")
            val gateNtkGeneratedNotify =
                shouldGateAdjacentAppendNotifyUntilNearReady(cardIndex, total)
            val posted = main.post {
                try {
                    finishStructurePublish()
                    if (cancelled.get()) return@post
                    if (gateNtkGeneratedNotify) {
                        warmAppendedEpisode(cardIndex, total)
                        notifyAdjacentAppendWhenNearReady(target, cardIndex, total, cardOffset, transitionTitle)
                    } else {
                        listener.onPagesAppended(total)
                        if (cardOffset >= 0) listener.onPageCard(cardIndex + cardOffset, transitionTitle)
                        if (warm) warmAppendedEpisode(cardIndex, total)
                    }
                } finally {
                    if (isStructurePublishPending()) finishStructurePublish()
                }
            }
            if (!posted) finishStructurePublish()
        }
    }

    private fun redeliverReadyPrependedStart(inserted: Int) {
        if (cancelled.get() || inserted <= 0) return
        val limit = minOf(inserted, NTK_PREPENDED_EPISODE_START_DECODE_PAGES)
        var redelivered = 0
        for (index in 0 until limit) {
            val page = pageRef(index) ?: continue
            if (page.transitionTitle != null) continue
            val bitmap: Bitmap?
            val tiles: List<ReaderTile>?
            synchronized(deliveredBitmaps) {
                bitmap = deliveredBitmaps[index]?.takeIf { !it.isRecycled }
                tiles = if (bitmap == null) {
                    deliveredTiles[index]?.takeIf { list -> list.any { !it.bitmap.isRecycled } }
                } else {
                    null
                }
            }
            if (bitmap != null) {
                listener.onPageReady(index, bitmap)
                listenerDrawableDeliveries.add(index)
                redelivered++
            } else if (!tiles.isNullOrEmpty()) {
                val first = tiles.first()
                listener.onPageTilesReady(index, first.sourceWidth, first.sourceHeight, tiles)
                listenerDrawableDeliveries.add(index)
                redelivered++
            } else {
                requestPage(
                    index,
                    busy = true,
                    anchor = index == 0,
                    generation = FOREGROUND_PRIME_WARM_GENERATION
                )
            }
        }
        if (redelivered > 0) {
            Log.d(TAG, "append_adjacent_prepend_redeliver_ready inserted=$inserted,count=$redelivered")
        }
    }

    private fun shouldGateAdjacentAppendNotifyUntilNearReady(cardIndex: Int, total: Int): Boolean {
        if (!isNtkSource(manga, title) || !firstBitmapLogged.get()) return false
        val firstNearDrawable = firstGeneratedAppendDrawableIndex(cardIndex, total) ?: return false
        return !hasListenerDrawableDelivery(firstNearDrawable)
    }

    private fun notifyAdjacentAppendWhenNearReady(
        target: Manga,
        cardIndex: Int,
        total: Int,
        cardOffset: Int,
        transitionTitle: String
    ) {
        val firstNearDrawable = firstGeneratedAppendDrawableIndex(cardIndex, total) ?: run {
            listener.onPagesAppended(total)
            if (cardOffset >= 0) listener.onPageCard(cardIndex + cardOffset, transitionTitle)
            return
        }
        val notify = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                if (!hasListenerDrawableDelivery(firstNearDrawable)) {
                    main.postDelayed(this, NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
                    return
                }
                Log.d(
                    TAG,
                    "append_adjacent_notify_near_ready targetId=${target.id} path=${target.ntkEpisodePath} " +
                        "firstNear=$firstNearDrawable total=$total"
                )
                listener.onPagesAppended(total)
                if (cardOffset >= 0) listener.onPageCard(cardIndex + cardOffset, transitionTitle)
            }
        }
        notify.run()
    }

    private fun warmAppendedEpisode(cardIndex: Int, total: Int) {
        if (cancelled.get() || cardIndex < 0 || total <= cardIndex) return
        val ntk = isNtkSource(manga, title)
        val generation = if (ntk) FOREGROUND_PRIME_WARM_GENERATION else windowGeneration.get()
        val busy = ntk || viewportBusy.get()
        val decodeAhead = if (ntk) {
            NTK_PRIMED_EPISODE_DECODE_AHEAD_PAGES
        } else if (busy) {
            BOUNDARY_BUSY_DECODE_AHEAD_PAGES
        } else {
            BOUNDARY_DECODE_AHEAD_PAGES
        }
        val byteAhead = if (ntk) {
            NTK_PRIMED_EPISODE_BYTE_AHEAD_PAGES
        } else if (busy) {
            BOUNDARY_BUSY_BYTE_AHEAD_PAGES
        } else {
            BOUNDARY_BYTE_AHEAD_PAGES
        }
        requestPage(cardIndex, busy = busy, anchor = false, generation = generation)
        val last = minOf(total - 1, cardIndex + decodeAhead)
        for (index in (cardIndex + 1)..last) {
            val priorityGeneration = if (ntk && index <= cardIndex + NTK_PRIMED_EPISODE_PRIORITY_PAGES) {
                FOREGROUND_PRIME_WARM_GENERATION
            } else {
                generation
            }
            requestPage(index, busy = busy, anchor = false, generation = priorityGeneration)
        }
        val byteLast = minOf(total - 1, cardIndex + byteAhead)
        for (index in (last + 1)..byteLast) {
            val page = pageRef(index) ?: continue
            network.execute { prefetchImageFileQuietly(index, page) }
        }
    }

    private fun warmPrependedEpisode(inserted: Int) {
        if (cancelled.get() || inserted <= 0) return
        val ntk = isNtkSource(manga, title)
        val generation = if (ntk) FOREGROUND_PRIME_WARM_GENERATION else windowGeneration.get()
        val busy = ntk || viewportBusy.get()
        val decodeAhead = if (ntk) {
            NTK_PREPENDED_EPISODE_DECODE_AHEAD_PAGES
        } else if (busy) {
            BOUNDARY_BUSY_DECODE_AHEAD_PAGES
        } else {
            BOUNDARY_DECODE_AHEAD_PAGES
        }
        val byteAhead = if (ntk) {
            NTK_PREPENDED_EPISODE_BYTE_AHEAD_PAGES
        } else if (busy) {
            BOUNDARY_BUSY_BYTE_AHEAD_PAGES
        } else {
            BOUNDARY_BYTE_AHEAD_PAGES
        }
        val cardIndex = inserted - 1
        requestPage(cardIndex, busy = busy, anchor = false, generation = generation)
        val firstImageIndex = if (pageRef(cardIndex)?.transitionTitle != null) cardIndex - 1 else cardIndex
        if (firstImageIndex >= 1) {
            requestPage(
                firstImageIndex,
                busy = busy,
                anchor = ntk,
                generation = if (ntk) FOREGROUND_PRIME_WARM_GENERATION else generation
            )
        }
        val firstDecoded = max(1, inserted - decodeAhead)
        if (firstImageIndex - 1 >= firstDecoded) {
            for (index in (firstImageIndex - 1) downTo firstDecoded) {
                requestPage(index, busy = busy, anchor = false, generation = generation)
            }
        }
        val firstByte = max(1, inserted - byteAhead)
        for (index in (firstDecoded - 1) downTo firstByte) {
            val page = pageRef(index) ?: continue
            network.execute { prefetchImageFileQuietly(index, page) }
        }
    }

    private fun warmPrependedEpisodeStart(inserted: Int) {
        if (cancelled.get() || inserted <= 0) return
        val ntk = isNtkSource(manga, title)
        if (!ntk) return
        val decodeLast = minOf(inserted - 1, NTK_PREPENDED_EPISODE_START_DECODE_PAGES - 1)
        for (index in 0..decodeLast) {
            val page = pageRef(index) ?: continue
            if (page.transitionTitle != null) continue
            requestPage(
                index,
                busy = false,
                anchor = index == 0,
                generation = FOREGROUND_PRIME_WARM_GENERATION
            )
        }
        val byteLast = minOf(inserted - 1, NTK_PREPENDED_EPISODE_START_BYTE_PAGES - 1)
        for (index in (decodeLast + 1)..byteLast) {
            val page = pageRef(index) ?: continue
            if (page.transitionTitle != null) continue
            network.execute { prefetchImageFileQuietly(index, page) }
        }
    }

    private fun hasEpisode(target: Manga): Boolean = synchronized(pagesLock) {
        containsEpisodeForAppendLocked(target)
    }

    private fun hasEpisodeFast(target: Manga): Boolean = synchronized(pagesLock) {
        containsEpisodeFastLocked(target)
    }

    private fun nextUnloadedAdjacentEpisode(
        source: Manga,
        currentTitle: Title,
        episodes: List<Manga>,
        direction: Int
    ): Manga? {
        var checked = 0
        for (candidate in adjacentEpisodeCandidates(source, episodes, direction)) {
            if (checked >= ADJACENT_EXISTING_SKIP_LIMIT) break
            candidate.title = currentTitle
            candidate.titleId = currentTitle.id
            candidate.mode = source.mode
            if (episodes.isNotEmpty()) candidate.setEps(episodes)
            val loaded = if (direction < 0) hasEpisodeFast(candidate) else hasEpisode(candidate)
            if (!loaded) return candidate
            checked++
        }
        return null
    }

    private fun adjacentEpisodeCandidates(source: Manga, episodes: List<Manga>, direction: Int): List<Manga> {
        val candidates = ArrayList<Manga>()
        fun addCandidate(candidate: Manga?) {
            if (candidate == null) return
            if (!isValidAdjacentEpisodeCandidate(source, candidate)) return
            if (looseSameEpisodeForAppend(source, candidate)) return
            if (candidates.any { looseSameEpisodeForAppend(it, candidate) }) return
            candidates.add(candidate)
        }
        addCandidate(if (direction < 0) source.prevEp() else source.nextEp())
        val sourceIndex = looseEpisodeIndexForAppend(episodes, source)
        if (sourceIndex >= 0) {
            var index = if (direction < 0) sourceIndex + 1 else sourceIndex - 1
            while (index >= 0 && index < episodes.size && candidates.size < ADJACENT_EXISTING_SKIP_LIMIT) {
                addCandidate(episodes[index])
                index += if (direction < 0) 1 else -1
            }
        }
        return candidates
    }

    private fun isValidAdjacentEpisodeCandidate(source: Manga, candidate: Manga): Boolean {
        val ntk = isNtkSource(source, source.title) || isNtkSource(candidate, candidate.title)
        if (!ntk) return true
        val path = candidate.ntkEpisodePath?.trim().orEmpty()
        val candidatePath = if (path.isNotEmpty()) path else candidate.url?.trim().orEmpty()
        val valid = candidatePath.isNotEmpty() && isNtkViewerEpisodePath(candidatePath)
        if (!valid) {
            Log.d(
                TAG,
                "append_adjacent_skip_non_episode path=$candidatePath id=${candidate.id} name=${candidate.name}"
            )
        }
        return valid
    }

    private fun isNtkViewerEpisodePath(path: String): Boolean {
        val parts = path
            .substringBefore('#')
            .substringBefore('?')
            .trim()
            .trimEnd('/')
            .split('/')
            .filter { it.isNotEmpty() }
        if (parts.size < 3) return false
        return (parts[0] == "manhwa" || parts[0] == "webtoon") &&
            parts[1].isNotBlank() &&
            parts[2].isNotBlank()
    }

    private fun isNtkSyntheticEpisodePath(path: String?): Boolean {
        val match = NTK_VIEWER_EPISODE_PATH.matchEntire(path?.trim().orEmpty()) ?: return false
        return !match.groupValues[3].matches(Regex("\\d+"))
    }

    private fun looseEpisodeIndexForAppend(episodes: List<Manga>, source: Manga): Int {
        for (index in episodes.indices) {
            if (looseSameEpisodeForAppend(source, episodes[index])) return index
        }
        return -1
    }

    private fun looseSameEpisodeForAppend(first: Manga?, second: Manga?): Boolean {
        if (Manga.sameEpisodeIdentity(first, second)) return true
        if (first == null || second == null || first.baseMode != second.baseMode) return false
        if (!isNtkSource(first, first.title) && !isNtkSource(second, second.title)) return false
        val firstPath = first.ntkEpisodePath?.trim().orEmpty()
        val secondPath = second.ntkEpisodePath?.trim().orEmpty()
        if (firstPath.isNotEmpty() && secondPath.isNotEmpty()) return firstPath == secondPath
        val firstNumber = Manga.visibleEpisodeNumberKey(first.name)
        val secondNumber = Manga.visibleEpisodeNumberKey(second.name)
        return firstNumber.isNotEmpty()
            && secondNumber.isNotEmpty()
            && firstNumber == secondNumber
            && first.id > 0
            && first.id == second.id
    }

    private fun containsEpisodeLocked(target: Manga): Boolean {
        return pages.any { sameEpisode(it.manga, target) }
    }

    private fun containsEpisodeFastLocked(target: Manga): Boolean {
        return pages.any { Manga.sameEpisodeIdentity(it.manga, target) }
    }

    private fun containsEpisodeForAppendLocked(target: Manga): Boolean {
        val key = episodeAppendKey(target)
        if (key.isNotEmpty())
            return pages.any { episodeAppendKey(it.manga) == key }
        return containsEpisodeLocked(target)
    }

    private fun appendableNewEpisodeRefsLocked(refs: List<PageRef>): List<PageRef> {
        if (refs.isEmpty()) return emptyList()
        val accepted = ArrayList<PageRef>(refs.size)
        val acceptedMangas = ArrayList<Manga>()
        val existingKeys = HashSet<String>()
        for (page in pages) {
            val key = episodeAppendKey(page.manga)
            if (key.isNotEmpty()) existingKeys.add(key)
        }
        val acceptedKeys = HashSet<String>()
        for (ref in refs) {
            val key = episodeAppendKey(ref.manga)
            val existing = if (key.isNotEmpty()) existingKeys.contains(key) else containsEpisodeLocked(ref.manga)
            val alreadyAccepted = if (key.isNotEmpty()) acceptedKeys.contains(key) else acceptedMangas.any { sameEpisode(it, ref.manga) }
            if (!existing && !alreadyAccepted) {
                if (key.isNotEmpty()) acceptedKeys.add(key) else acceptedMangas.add(ref.manga)
            }
            if (!existing || alreadyAccepted) accepted.add(ref)
        }
        return accepted
    }

    private fun shiftPageStateForPrepend(delta: Int) {
        if (delta <= 0) return
        shiftConcurrentMap(decodedWidths, delta)
        shiftConcurrentMap(desiredWidths, delta)
        shiftConcurrentMap(pendingDeliveryWidths, delta)
        shiftConcurrentMap(sourceWidths, delta)
        shiftConcurrentMap(achievableWidths, delta)
        shiftConcurrentMap(earlyPreparedBitmaps, delta)
        shiftConcurrentSet(failedPages, delta)
        shiftConcurrentSet(listenerDrawableDeliveries, delta)
        inFlightWidths.clear()
        loading.clear()
        loadingPages.clear()
        urgentLoading.clear()
        urgentLoadingPages.clear()
        bytePrefetching.clear()
        preAnchorFallbackRetries.clear()
        visibleGeneratedByteHedges.clear()
        visibleGeneratedDecodeHedges.clear()
        idleFullWidthUpgradeScheduled.clear()
        synchronized(deliveredBitmaps) {
            val oldEntries = deliveredBitmaps.entries.toList()
            deliveredBitmaps.clear()
            for (entry in oldEntries) deliveredBitmaps[entry.key + delta] = entry.value
            val oldTileEntries = deliveredTiles.entries.toList()
            deliveredTiles.clear()
            for (entry in oldTileEntries) deliveredTiles[entry.key + delta] = entry.value
            val oldOwned = deliveredOwned.toList()
            deliveredOwned.clear()
            for (index in oldOwned) deliveredOwned.add(index + delta)
            retainedFirstPage += delta
            retainedLastPage += delta
        }
        synchronized(windowLock) {
            if (lastWindowAnchor >= 0) lastWindowAnchor += delta
            windowGeneration.incrementAndGet()
        }
    }

    private fun <T> shiftConcurrentMap(map: ConcurrentHashMap<Int, T>, delta: Int) {
        if (map.isEmpty()) return
        val entries = map.entries.toList()
        map.clear()
        for (entry in entries) map[entry.key + delta] = entry.value
    }

    private fun shiftConcurrentSet(set: MutableSet<Int>, delta: Int) {
        if (set.isEmpty()) return
        val entries = set.toList()
        set.clear()
        for (index in entries) set.add(index + delta)
    }

    private fun requestPage(index: Int, busy: Boolean, anchor: Boolean, generation: Int = windowGeneration.get()) {
        if (cancelled.get()) return
        val visibleIntent = busy || anchor
        if (isStructurePublishPending()) {
            if (visibleIntent && isNtkSource(manga, title)) {
                Log.d(TAG, "reader_visible_request_skip_structure page=$index,busy=$busy,anchor=$anchor,generation=$generation")
            }
            return
        }
        val page = pageRef(index) ?: return
        if (shouldDeferNtkPreAnchorPageRequest(index, page, anchor, generation)) return
        failedPages.remove(index)
        if (page.transitionTitle != null) {
            decodedWidths[index] = Int.MAX_VALUE
            desiredWidths.remove(index)
            inFlightWidths.remove(index)
            loading.remove(index)
            main.post {
                if (!cancelled.get()) listener.onPageCard(index, page.transitionTitle)
            }
            return
        }
        val targetWidth = targetWidth(busy || isNtkWebtoonSource(page.manga, title))
        rememberDesiredWidth(index, targetWidth)
        val effectiveTargetWidth = achievableWidth(index, targetWidth)
        clearStaleLoadStateForIndex(index, page)
        val decodedWidth = decodedWidths[index] ?: 0
        if (decodedWidth >= effectiveTargetWidth) {
            if (hasDeliveredBitmap(index)) return
            decodedWidths.remove(index)
        }
        val preAnchorFallbackVisible = busy &&
            generation == FOREGROUND_PRIME_WARM_GENERATION &&
            !firstBitmapLogged.get() &&
            isNtkSource(page.manga, title) &&
            ntkCoordinator?.allowsPreAnchorFallback(index, page.image, "requestPage.visiblePriority") == true
        val ntkInitialNearPrimeVisible = busy &&
            generation == FOREGROUND_PRIME_WARM_GENERATION &&
            firstBitmapLogged.get() &&
            isNtkSource(page.manga, title) &&
            index <= currentStartPage() + NTK_FOREGROUND_STREAM_AHEAD_PAGES
        val actualWindowVisible = anchor || (busy && generation >= 0) ||
            preAnchorFallbackVisible || ntkInitialNearPrimeVisible
        val visiblePriority = actualWindowVisible && !hasDeliveredBitmap(index)
        val pendingWidth = pendingDeliveryWidths[index] ?: 0
        if (pendingWidth >= effectiveTargetWidth) {
            if (visiblePriority && isNtkSource(page.manga, title)) {
                Log.d(
                    TAG,
                    "reader_visible_request_skip_pending page=$index,pending=$pendingWidth," +
                        "target=$effectiveTargetWidth,busy=$busy,anchor=$anchor,generation=$generation"
                )
                promoteVisiblePendingDelivery(index)
            }
            return
        }
        val activeWidth = inFlightWidths[index] ?: 0
        if (activeWidth >= effectiveTargetWidth && loading.contains(index)) {
            if (!visiblePriority || urgentLoading.contains(index)) {
                if (visiblePriority && isNtkSource(page.manga, title)) {
                    Log.d(
                        TAG,
                        "reader_visible_request_skip_active page=$index,active=$activeWidth," +
                            "target=$effectiveTargetWidth,urgent=${urgentLoading.contains(index)},generation=$generation"
                    )
                    hedgeVisibleGeneratedByteFetch(index, page, "active_skip")
                }
                return
            }
        }
        val ownsLoading = loading.add(index)
        val urgent = !ownsLoading &&
            visiblePriority &&
            urgentLoading.add(index)
        if (!ownsLoading && !urgent) {
            if (targetWidth > activeWidth)
                ViewerWarmupManager.logMetric("busy_to_idle_upgrade_pending", targetWidth.toLong())
            return
        }
        if (ownsLoading) {
            loadingPages[index] = page
            inFlightWidths[index] = targetWidth
        }
        if (urgent) {
            urgentLoadingPages[index] = page
        }
        if (ownsLoading && shouldPostPageLoadingState(index, page, busy, anchor, generation)) {
            main.post { if (!cancelled.get()) listener.onPageLoading(index) }
        }
        if (visiblePriority) {
            ViewerWarmupManager.logMetric("reader_urgent_visible_decode", index.toLong())
            if (isNtkSource(page.manga, title)) {
                Log.d(
                    TAG,
                    "reader_visible_request_start page=$index,owns=$ownsLoading,urgent=$urgent," +
                        "busy=$busy,anchor=$anchor,generation=$generation,image=${page.image?.substringAfterLast('/')}"
                )
                hedgeVisibleGeneratedByteFetch(
                    index,
                    page,
                    if (anchor) "anchor" else "visible",
                    delayMs = NTK_VISIBLE_GENERATED_BYTE_HEDGE_DELAY_MS
                )
            }
        }
        val foregroundPrime = generation == FOREGROUND_PRIME_WARM_GENERATION
        val primeWarm = generation == PRIME_WARM_GENERATION
        if (foregroundPrime && ownsLoading && shouldHedgeForegroundPrime(index)) {
            scheduleForegroundPrimeHedge(index)
        }
        val retainWhenBusy = generation == PRIME_WARM_GENERATION ||
            foregroundPrime ||
            (visiblePriority && isNtkSource(page.manga, title) && isNtkGeneratedImageUrl(page.image.orEmpty()))
        val networkExecutor = when {
            anchor -> anchorNetwork
            visiblePriority -> urgentNetwork
            foregroundPrime || primeWarm -> primeNetwork
            else -> network
        }
        val decodeExecutor = when {
            anchor -> anchorDecode
            visiblePriority -> urgentDecode
            foregroundPrime || primeWarm -> primeDecode
            else -> decode
        }
        try {
            networkExecutor.execute {
            try {
                if (shouldSkipStalePage(index, generation, anchor)) {
                    clearPageLoadState(index, page, ownsLoading, urgent)
                    return@execute
                }
                val originalPage = page
                val allowPreviewCache = shouldUsePreviewDecodedCache(index, targetWidth)
                cachedDecodedResult(originalPage, targetWidth, allowPreviewCache)?.let { cached ->
                    logNtkPagePerf(index, "cache_hit", "target=$targetWidth,width=${cached.width}")
                    clearPageLoadState(index, page, ownsLoading, urgent)
                    postDecodeResult(Delivery(index, originalPage, cached, SystemClock.elapsedRealtime(), targetWidth, retainWhenBusy))
                    ViewerWarmupManager.logMetric("reader_decoded_cache_hit", index.toLong())
                    return@execute
                }
                if (!anchor && !urgent && !foregroundPrime) prefetchImageFile(index, originalPage)
                try {
                    decodeExecutor.execute {
                    val gate = if (visiblePriority) null else if (busy) busyDecodeGate else idleDecodeGate
                    var acquired = false
                    var delivered = false
                    try {
                        if (gate != null) {
                            gate.acquire()
                            acquired = true
                        }
                        if (cancelled.get() || shouldSkipStalePage(index, generation, anchor)) return@execute
                        val startedAt = SystemClock.elapsedRealtime()
                        val foregroundFetch = shouldUseForegroundFetch(
                            index,
                            originalPage,
                            anchor,
                            urgent || visiblePriority,
                            busy,
                            generation
                        )
                        val foregroundPermit = if (foregroundFetch) {
                            ntkCoordinator?.imagePermit(
                                index,
                                originalPage.manga,
                                originalPage.image,
                                if (index == currentStartPage()) NtkImageLane.FIRST_IMAGE else NtkImageLane.FOLLOWING_VISIBLE,
                                "requestPage.decode"
                            )
                        } else {
                            null
                        }
                        if (foregroundFetch && isNtkSource(originalPage.manga, title) &&
                            !ntkCoordinatorAllowsStream(index, originalPage, foregroundPermit, "requestPage.decode")
                        ) {
                            clearPageLoadState(index, page, ownsLoading, urgent)
                            return@execute
                        }
                        val result = decodePageWithLease(
                            index,
                            originalPage,
                            targetWidth,
                            foregroundFetch,
                            allowPreviewCache,
                            visiblePriority || anchor,
                            foregroundPermit
                        )
                        if (
                            cancelled.get() ||
                            shouldSkipStalePage(index, generation, anchor) ||
                            currentPageIndexForDelivery(originalPage, index) < 0
                        ) {
                            recycleDecodeResult(result)
                            return@execute
                        }
                        delivered = true
                        postDecodeResult(Delivery(index, originalPage, result, startedAt, targetWidth, retainWhenBusy))
                    } catch (e: Exception) {
                        recordIfUnexpected(e)
                        postPageError(index, originalPage, e)
                    } finally {
                        if (acquired) gate?.release()
                        clearPageLoadState(index, page, ownsLoading, urgent)
                        if (delivered) ViewerWarmupManager.logMetric("reader_delivery_posted", index.toLong())
                    }
                }
                } catch (_: RejectedExecutionException) {
                    clearPageLoadState(index, page, ownsLoading, urgent)
                }
            } catch (e: Exception) {
                clearPageLoadState(index, page, ownsLoading, urgent)
                recordIfUnexpected(e)
                postPageError(index, page, e)
            }
            }
        } catch (_: RejectedExecutionException) {
            clearPageLoadState(index, page, ownsLoading, urgent)
        }
    }

    private fun shouldDeferNtkPreAnchorPageRequest(
        index: Int,
        page: PageRef,
        anchor: Boolean,
        generation: Int
    ): Boolean {
        if (firstBitmapLogged.get()) return false
        if (!isNtkSource(page.manga, title)) return false
        val start = currentStartPage()
        if (index == start) return false
        if (isNtkGeneratedImageUrl(page.image.orEmpty()) &&
            !ReaderImageCache.hasNtkAnchorAssetForEpisode(page.manga)
        ) {
            if (shouldAllowVerifiedNearGeneratedBeforeAnchorAsset(index, page, start, anchor, generation)) {
                Log.d(
                    TAG,
                    "reader_ntk_pre_anchor_request_allowed_before_anchor_asset page=$index,start=$start," +
                        "anchor=$anchor,generation=$generation,image=${page.image?.substringAfterLast('/')}"
                )
                ViewerWarmupManager.logMetric(
                    "reader_ntk_pre_anchor_request_allowed_before_anchor_asset",
                    index.toLong()
                )
                return false
            }
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_request_deferred_until_anchor_asset page=$index,start=$start," +
                    "anchor=$anchor,generation=$generation,image=${page.image?.substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_request_deferred_until_anchor_asset", index.toLong())
            schedulePreAnchorFallbackRetry(index, page, generation)
            return true
        }
        if (ntkCoordinator?.allowsPreAnchorFallback(index, page.image, "shouldDeferNtkPreAnchorPageRequest") == true) {
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_request_allowed page=$index,start=$start," +
                    "anchor=$anchor,generation=$generation,image=${page.image?.substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_request_allowed", index.toLong())
            return false
        }
        if (index > start + NTK_PRE_ANCHOR_FALLBACK_MAX_AHEAD) {
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_request_skip_far page=$index,start=$start," +
                    "maxAhead=$NTK_PRE_ANCHOR_FALLBACK_MAX_AHEAD,image=${page.image?.substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_request_skip_far", index.toLong())
            return true
        }
        schedulePreAnchorFallbackRetry(index, page, generation)
        Log.d(
            TAG,
            "reader_ntk_pre_anchor_request_deferred page=$index,start=$start," +
                "anchor=$anchor,generation=$generation"
        )
        ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_request_deferred", index.toLong())
        return true
    }

    private fun shouldAllowVerifiedNearGeneratedBeforeAnchorAsset(
        index: Int,
        page: PageRef,
        start: Int,
        anchor: Boolean,
        generation: Int
    ): Boolean {
        if (anchor) return false
        val minNearVisible = (start - NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES).coerceAtLeast(0)
        if (index < minNearVisible || index > start + NTK_PRE_ANCHOR_VERIFIED_GENERATED_AHEAD) return false
        if (index == start) return false
        val image = page.image.orEmpty()
        if (!isNtkGeneratedImageUrl(image)) return false
        if (ntkCoordinator?.allowsPreAnchorFallback(index, page.image, "verifiedNearGeneratedBeforeAnchorAsset") != true) {
            return false
        }
        val earlyUrls = ReaderImageCache.earlyNtkImageUrls(
            page.manga.ntkEpisodePath,
            SystemClock.elapsedRealtime() - 30000L
        )
        val normalizedImage = Utils.viewerImageRequestUrl(image, page.manga.baseMode)
        val exactMatch = earlyUrls.any {
            it == image || Utils.viewerImageRequestUrl(it, page.manga.baseMode) == normalizedImage
        }
        return exactMatch || earlyUrls.isNotEmpty()
    }

    private fun schedulePreAnchorFallbackRetry(index: Int, page: PageRef, generation: Int) {
        if (generation != FOREGROUND_PRIME_WARM_GENERATION) return
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return
        if (!preAnchorFallbackRetries.add(index)) return
        val delayMs = ntkCoordinator
            ?.preAnchorFallbackRetryDelayMs(index, page.image)
            ?.let { remaining ->
                if (remaining > 0L) {
                    remaining.coerceAtMost(NTK_PRE_ANCHOR_FALLBACK_RETRY_MAX_MS)
                } else {
                    NTK_PRE_ANCHOR_FALLBACK_RETRY_MS
                }
            }
            ?: NTK_PRE_ANCHOR_FALLBACK_RETRY_MS
        main.postDelayed({
            preAnchorFallbackRetries.remove(index)
            if (cancelled.get() || firstBitmapLogged.get()) return@postDelayed
            if (pageRef(index) !== page) return@postDelayed
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_request_retry page=$index,start=${currentStartPage()}," +
                    "generation=$generation,image=${page.image?.substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_request_retry", index.toLong())
            requestPage(index, busy = true, anchor = false, generation = generation)
        }, delayMs)
    }

    private fun ntkCoordinatorAllowsStream(
        index: Int,
        page: PageRef,
        permit: NtkImagePermit?,
        source: String
    ): Boolean {
        return ntkCoordinatorAllowsStream(index, page.manga, page.image, permit, source)
    }

    private fun ntkCoordinatorAllowsStream(
        index: Int,
        target: Manga,
        image: String?,
        permit: NtkImagePermit?,
        source: String
    ): Boolean {
        if (!isNtkSource(target, title)) return true
        return ntkCoordinator?.assertForegroundStreamPermit(index, permit, image, source) ?: true
    }

    private fun scheduleForegroundPrimeHedge(index: Int) {
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            if (!loading.contains(index) || hasDeliveredBitmap(index)) return@postDelayed
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
        }, NTK_FOREGROUND_PRIME_HEDGE_DELAY_MS)
    }

    private fun shouldHedgeForegroundPrime(index: Int): Boolean {
        if (!isNtkSource(manga, title)) return false
        val first = currentStartPage()
        val hedgeFirst = first + NTK_INITIAL_PRIORITY_PAGES + 1
        val hedgeLast = minOf(
            first + NTK_INITIAL_DECODE_AHEAD_PAGES,
            first + NTK_INITIAL_PRIORITY_PAGES + NTK_PRIMED_EPISODE_PRIORITY_PAGES
        )
        return index in hedgeFirst..hedgeLast
    }

    private fun clearStaleLoadStateForIndex(index: Int, page: PageRef) {
        loadingPages[index]?.let { loadingPage ->
            if (loadingPage !== page) {
                loadingPages.remove(index, loadingPage)
                loading.remove(index)
                inFlightWidths.remove(index)
            }
        }
        urgentLoadingPages[index]?.let { urgentPage ->
            if (urgentPage !== page) {
                urgentLoadingPages.remove(index, urgentPage)
                urgentLoading.remove(index)
            }
        }
    }

    private fun clearPageLoadState(index: Int, page: PageRef, ownsLoading: Boolean, urgent: Boolean) {
        if (ownsLoading && loadingPages[index] === page) {
            loadingPages.remove(index, page)
            loading.remove(index)
            inFlightWidths.remove(index)
        }
        if (urgent && urgentLoadingPages[index] === page) {
            urgentLoadingPages.remove(index, page)
            urgentLoading.remove(index)
        }
    }

    private fun prefetchBusyPage(index: Int, page: PageRef, generation: Int) {
        if (
            hasDeliveredBitmap(index) ||
            loading.contains(index) ||
            urgentLoading.contains(index) ||
            (pendingDeliveryWidths[index] ?: 0) > 0
        ) {
            return
        }
        if (!bytePrefetching.add(index)) return
        try {
            network.execute {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    if (!shouldSkipStalePage(index, generation, false)) {
                        prefetchImageFile(
                            index,
                            page,
                            foreground = false,
                            visiblePriority = false
                        )
                        logNtkPagePerf(index, "byte_prefetch_done", "ms=${SystemClock.elapsedRealtime() - startedAt}")
                    }
                } catch (e: Exception) {
                    if (isExpectedCancellation(e)) {
                        logNtkPagePerf(index, "byte_prefetch_cancelled", "ms=${SystemClock.elapsedRealtime() - startedAt}")
                    } else {
                        logNtkPagePerf(index, "byte_prefetch_error", "ms=${SystemClock.elapsedRealtime() - startedAt},error=${e.javaClass.simpleName}")
                        recordIfUnexpected(e)
                    }
                } finally {
                    bytePrefetching.remove(index)
                }
            }
        } catch (_: RejectedExecutionException) {
            bytePrefetching.remove(index)
        }
    }

    private fun hedgeVisibleGeneratedByteFetch(index: Int, page: PageRef, reason: String, delayMs: Long = 0L) {
        if (!firstBitmapLogged.get()) return
        if (!isNtkSource(page.manga, title)) return
        if (!isNtkGeneratedPageRef(index)) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        if (!visibleGeneratedByteHedges.add(index)) return
        if (delayMs > 0L) {
            main.postDelayed({
                if (cancelled.get() ||
                    pageRef(index) != page ||
                    hasDeliveredBitmap(index) ||
                    (pendingDeliveryWidths[index] ?: 0) > 0
                ) {
                    visibleGeneratedByteHedges.remove(index)
                    return@postDelayed
                }
                runVisibleGeneratedByteHedge(index, page, reason, delayMs)
            }, delayMs)
            return
        }
        runVisibleGeneratedByteHedge(index, page, reason, delayMs)
    }

    private fun runVisibleGeneratedByteHedge(index: Int, page: PageRef, reason: String, delayMs: Long) {
        try {
            urgentNetwork.execute {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    if (!cancelled.get() && pageRef(index) == page && !hasDeliveredBitmap(index)) {
                        prefetchImageFile(index, page, foreground = true, visiblePriority = true)
                        logNtkPagePerf(
                            index,
                            "visible_generated_byte_hedge_done",
                            "reason=$reason,delayMs=$delayMs,ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        scheduleVisibleGeneratedCachedDecode(index, page, reason, startedAt)
                    }
                } catch (e: Exception) {
                    if (isExpectedCancellation(e)) {
                        logNtkPagePerf(
                            index,
                            "visible_generated_byte_hedge_cancelled",
                            "reason=$reason,delayMs=$delayMs,ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                    } else {
                        visibleGeneratedByteHedges.remove(index)
                        logNtkPagePerf(
                            index,
                            "visible_generated_byte_hedge_error",
                            "reason=$reason,delayMs=$delayMs,ms=${SystemClock.elapsedRealtime() - startedAt},error=${e.javaClass.simpleName}"
                        )
                        recordIfUnexpected(e)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            visibleGeneratedByteHedges.remove(index)
        }
    }

    private fun scheduleVisibleGeneratedCachedDecode(
        index: Int,
        page: PageRef,
        reason: String,
        byteStartedAt: Long
    ) {
        if (cancelled.get() || pageRef(index) != page) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        val image = page.image ?: return
        val cached = ReaderImageCache.cachedFile(appContext, page.manga, image) ?: run {
            logNtkPagePerf(index, "visible_generated_cached_decode_skip", "reason=$reason,cached=false")
            return
        }
        if (!visibleGeneratedDecodeHedges.add(index)) return
        try {
            urgentDecode.execute {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    if (
                        cancelled.get() ||
                        pageRef(index) != page ||
                        hasDeliveredBitmap(index) ||
                        (pendingDeliveryWidths[index] ?: 0) > 0
                    ) {
                        return@execute
                    }
                    val targetWidth = targetWidth(true)
                    val result = cachedDecodedResult(
                        page,
                        targetWidth,
                        shouldUsePreviewDecodedCache(index, targetWidth)
                    ) ?: decodePage(index, page, cached, targetWidth)
                    if (
                        cancelled.get() ||
                        pageRef(index) != page ||
                        currentPageIndexForDelivery(page, index) < 0
                    ) {
                        recycleDecodeResult(result)
                        return@execute
                    }
                    logNtkPagePerf(
                        index,
                        "visible_generated_cached_decode_ready",
                        "reason=$reason,byteMs=${startedAt - byteStartedAt},decodeMs=${SystemClock.elapsedRealtime() - startedAt},width=${result.width}"
                    )
                    postDecodeResult(
                        Delivery(
                            index,
                            page,
                            result,
                            startedAt,
                            targetWidth,
                            retainWhenBusy = true
                        )
                    )
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                    postPageError(index, page, e)
                } finally {
                    visibleGeneratedDecodeHedges.remove(index)
                }
            }
        } catch (_: RejectedExecutionException) {
            visibleGeneratedDecodeHedges.remove(index)
        }
    }

    private fun shouldSkipStalePage(index: Int, generation: Int, anchor: Boolean): Boolean {
        if (cancelled.get()) return true
        if (generation == PRIME_WARM_GENERATION) return false
        if (generation == FOREGROUND_PRIME_WARM_GENERATION) return false
        if (generation == windowGeneration.get()) return false
        return !isRetainedPage(index)
    }

    private fun rememberDesiredWidth(index: Int, targetWidth: Int) {
        while (true) {
            val current = desiredWidths[index]
            if (current != null && current >= targetWidth) return
            if (current == null) {
                if (desiredWidths.putIfAbsent(index, targetWidth) == null) return
            } else if (desiredWidths.replace(index, current, targetWidth)) {
                return
            }
        }
    }

    private fun retryPendingWidthIfNeeded(index: Int) {
        val wanted = achievableWidth(index, desiredWidths[index] ?: return)
        val have = decodedWidths[index] ?: 0
        if (wanted <= have) return
        if (cancelled.get() || !isRetainedPage(index)) {
            ViewerWarmupManager.logMetric("busy_to_idle_upgrade_miss", wanted.toLong())
            return
        }
        val effectiveWanted = achievableWidth(index, wanted)
        if ((pendingDeliveryWidths[index] ?: 0) >= effectiveWanted) return
        ViewerWarmupManager.logMetric("busy_to_idle_upgrade_retry", wanted.toLong())
        requestPage(index, false, false, windowGeneration.get())
    }

    private fun isRetainedPage(index: Int): Boolean = synchronized(deliveredBitmaps) {
        index in retainedFirstPage..retainedLastPage
    }

    private fun hasDeliveredBitmap(index: Int): Boolean = synchronized(deliveredBitmaps) {
        deliveredBitmaps[index]?.let { !it.isRecycled } == true ||
            deliveredTiles[index]?.any { !it.bitmap.isRecycled } == true
    }

    private fun hasDeliveredOrPendingDrawable(index: Int): Boolean {
        if (hasDeliveredBitmap(index)) return true
        if ((pendingDeliveryWidths[index] ?: 0) > 0) return true
        if (initialDeliveryBacklog.containsKey(index)) return true
        if (initialPreparedBacklog.containsKey(index)) return true
        return false
    }

    private fun hasListenerDrawableDelivery(index: Int): Boolean {
        return listenerDrawableDeliveries.contains(index)
    }

    private fun logFirstBitmapIfNeeded(startedAt: Long) {
        if (firstBitmapLogged.compareAndSet(false, true)) {
            ntkCoordinator?.markAnchorBitmapDecoded(currentStartPage())
            ntkFirstBitmapAtMs.set(SystemClock.uptimeMillis())
            ViewerWarmupManager.logMetric("reader_first_bitmap_ms", SystemClock.elapsedRealtime() - startedAt)
            releasePreparedStoreBitmapsSoon()
            appendLatestEarlyNtkUrlsAfterFirstBitmap(startedAt)
            upgradeNtkInitialPriorityPagesAfterFirstBitmap()
            prefetchNtkInitialNextBytesAfterFirstBitmap()
            scheduleNtkSecondaryInitialWarmAfterFirstBitmap()
            scheduleNtkSourcePrefetchAfterFirstBitmap()
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterFirstBitmap()
            scheduleNtkEpisodeMetadataAfterFirstBitmap()
            scheduleNtkForwardTimelinePrimeAfterFirstBitmap()
        }
    }

    private fun scheduleObservedManhwaGeneratedExpansionAfterFirstBitmap(
        target: Manga,
        initialInstalledCount: Int,
        loadStartedAt: Long
    ) {
        if (!isNtkSource(target, title) || !isNtkManhwaEpisodePath(target.ntkEpisodePath)) return
        control.execute {
            val firstBitmapDeadline =
                SystemClock.elapsedRealtime() + NTK_EARLY_GENERATED_EXPAND_AFTER_FIRST_BITMAP_WAIT_MS
            while (!cancelled.get() && !firstBitmapLogged.get() && SystemClock.elapsedRealtime() < firstBitmapDeadline) {
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            if (cancelled.get() || !firstBitmapLogged.get()) return@execute
            var installedCount = initialInstalledCount
            val appendDeadline = SystemClock.elapsedRealtime() + NTK_OBSERVED_MANHWA_APPEND_AFTER_FIRST_BITMAP_WAIT_MS
            while (!cancelled.get() && SystemClock.elapsedRealtime() < appendDeadline) {
                val earlyUrls = ReaderImageCache
                    .earlyNtkImageUrls(target.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L)
                    .filter { isNtkGeneratedImageUrl(it) }
                val observed = observedInitialManhwaGeneratedUrls(target, earlyUrls)
                if (observed.size > installedCount) {
                    appendInitialNtkUrlsAfterEarlyInstall(
                        target,
                        observed,
                        loadStartedAt,
                        allowFirstBitmapDefer = false
                    )
                    installedCount = observed.size
                    logNtkRepositoryStage(
                        target,
                        "early_urls_observed_manhwa_append_after_first",
                        "count=$installedCount,raw=${earlyUrls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), installedCount)
                    if (installedCount >= NTK_GENERATED_INITIAL_RECOVERY_PAGES) return@execute
                }
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
        }
    }

    private fun appendLatestEarlyNtkUrlsAfterFirstBitmap(loadStartedAt: Long) {
        if (!isNtkSource(manga, title)) return
        val latest = ReaderImageCache.earlyNtkImageUrls(manga.ntkEpisodePath, 0L)
        if (latest.isEmpty()) return
        val installedCount = synchronized(pagesLock) { pages.size }
        if (latest.size <= installedCount) return
        appendInitialNtkUrlsAfterEarlyInstall(manga, latest, loadStartedAt, allowFirstBitmapDefer = false)
        logNtkRepositoryStage(
            manga,
            "early_urls_latest_append_after_first_bitmap",
            "from=$installedCount,to=${latest.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), latest.size)
    }

    private fun scheduleNtkSecondaryInitialWarmAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        val first = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val ntkWebtoon = isNtkWebtoonSource(manga, title)
        val nearDecodeAhead = if (ntkWebtoon) NTK_WEBTOON_INITIAL_NEAR_DECODE_AHEAD_PAGES else NTK_INITIAL_NEAR_DECODE_AHEAD_PAGES
        val fullDecodeAhead = if (ntkWebtoon) NTK_WEBTOON_INITIAL_DECODE_AHEAD_PAGES else NTK_INITIAL_DECODE_AHEAD_PAGES
        val priorityLast = minOf(count - 1, first + NTK_INITIAL_PRIORITY_PAGES)
        val nearLast = minOf(count - 1, first + nearDecodeAhead)
        if (priorityLast < nearLast) {
            main.postDelayed({
                if (cancelled.get()) return@postDelayed
                for (index in (priorityLast + 1)..nearLast) {
                    requestPage(index, busy = true, anchor = false, generation = PRIME_WARM_GENERATION)
                }
            }, NTK_INITIAL_SECONDARY_WARM_DELAY_MS)
        }
        val farLast = minOf(count - 1, first + fullDecodeAhead)
        if (nearLast >= farLast) return
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            scheduleNtkFarInitialWarmBatch(nearLast + 1, farLast)
        }, NTK_INITIAL_FAR_WARM_DELAY_MS)
    }

    private fun scheduleNtkSecondaryInitialWarmAfterFirstBitmap(delayMs: Long) {
        main.postDelayed({
            if (!cancelled.get()) scheduleNtkSecondaryInitialWarmAfterFirstBitmap()
        }, delayMs)
    }

    private fun scheduleNtkFarInitialWarmBatch(nextIndex: Int, farLast: Int) {
        if (cancelled.get() || nextIndex > farLast) return
        val delayMs = ntkCurrentBackgroundWarmDelayMs()
        val ackSafeLast = currentStartPage() + NTK_INITIAL_ACK_INFLIGHT_WARM_PAGES
        if (delayMs > 0L && nextIndex > ackSafeLast) {
            main.postDelayed({
                if (!cancelled.get()) scheduleNtkFarInitialWarmBatch(nextIndex, farLast)
            }, delayMs)
            return
        }
        val batchLast = minOf(
            farLast,
            nextIndex + NTK_INITIAL_FAR_WARM_BATCH_PAGES - 1,
            if (delayMs > 0L) ackSafeLast else farLast
        )
        for (index in nextIndex..batchLast) {
            requestPage(index, busy = true, anchor = false, generation = PRIME_WARM_GENERATION)
        }
        if (batchLast < farLast) {
            main.postDelayed({
                if (!cancelled.get()) scheduleNtkFarInitialWarmBatch(batchLast + 1, farLast)
            }, NTK_INITIAL_FAR_WARM_BATCH_DELAY_MS)
        }
    }

    private fun scheduleNtkSourcePrefetchAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            val delayMs = ntkBackgroundPrepareQuietRemainingMs()
            if (delayMs > 0L) {
                scheduleNtkSourcePrefetchAfterFirstBitmap(delayMs)
                return@postDelayed
            }
            prefetchImageFilesAround(currentStartPage(), ntkInitialSourcePrefetchAfterPages())
        }, NTK_INITIAL_SOURCE_PREFETCH_AFTER_FIRST_BITMAP_DELAY_MS)
    }

    private fun scheduleNtkSourcePrefetchAfterFirstBitmap(delayMs: Long) {
        main.postDelayed({
            if (!cancelled.get()) scheduleNtkSourcePrefetchAfterFirstBitmap()
        }, delayMs)
    }

    private fun ntkInitialSourcePrefetchAfterPages(): Int {
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_INITIAL_SOURCE_PREFETCH_AFTER
        } else {
            START_SOURCE_PREFETCH_AFTER
        }
    }

    private fun scheduleNtkGeneratedFullEpisodeBytePrefetchAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(
            NTK_GENERATED_FULL_BYTE_PREFETCH_AFTER_FIRST_BITMAP_DELAY_MS
        )
    }

    private fun scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(delayMs: Long) {
        main.postDelayed({
            runNtkGeneratedFullEpisodeBytePrefetch()
        }, delayMs)
    }

    private fun runNtkGeneratedFullEpisodeBytePrefetch() {
        if (cancelled.get()) return
        val delayMs = ntkBackgroundPrepareQuietRemainingMs()
        if (delayMs > 0L) {
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(delayMs)
            return
        }
        val refs = synchronized(pagesLock) { pages.toList() }
        if (!isGeneratedOnlyNtkRefs(refs)) return
        val anchor = currentStartPage().coerceIn(0, refs.lastIndex)
        if (hasUndeliveredNtkVisibleWindow(anchor, refs.lastIndex)) {
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_DELAY_MS)
            return
        }
        val defaultFirst = minOf(refs.lastIndex, anchor + 1)
        if (defaultFirst > refs.lastIndex) return
        val first = ntkGeneratedFullBytePrefetchCursor.updateAndGet { current ->
            if (current < defaultFirst) defaultFirst else current
        }
        if (first > refs.lastIndex) return
        var scheduled = 0
        var nextIndex = first
        while (nextIndex <= refs.lastIndex && scheduled < NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_PAGES) {
            val index = nextIndex
            nextIndex++
            val page = refs.getOrNull(index) ?: continue
            if (page.transitionTitle != null || hasDeliveredBitmap(index)) continue
            prefetchBusyPage(index, page, PRIME_WARM_GENERATION)
            scheduled++
        }
        ntkGeneratedFullBytePrefetchCursor.set(nextIndex)
        Log.d(
            TAG,
            "reader_ntk_generated_full_byte_prefetch anchor=$anchor,from=$first," +
                "to=${refs.lastIndex},scheduled=$scheduled,next=$nextIndex"
        )
        ViewerWarmupManager.logMetric("reader_ntk_generated_full_byte_prefetch", scheduled.toLong())
        if (nextIndex <= refs.lastIndex) {
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_DELAY_MS)
        }
    }

    private fun hasUndeliveredNtkVisibleWindow(anchor: Int, lastIndex: Int): Boolean {
        val first = max(0, anchor - NTK_WEBTOON_IDLE_VISIBLE_DECODE_RADIUS)
        val last = minOf(lastIndex, anchor + NTK_WEBTOON_IDLE_VISIBLE_DECODE_RADIUS)
        for (index in first..last) {
            val page = pageRef(index) ?: continue
            if (page.transitionTitle != null) continue
            if (!hasDeliveredBitmap(index)) return true
        }
        return false
    }

    private fun scheduleNtkEpisodeMetadataAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        main.postDelayed({
            if (isNtkCurrentAckPreflightInFlight()) {
                scheduleNtkEpisodeMetadataAfterFirstBitmap()
                return@postDelayed
            }
            fetchNtkEpisodeMetadataInBackground()
        }, NTK_EPISODE_METADATA_AFTER_FIRST_BITMAP_DELAY_MS)
    }

    private fun fetchNtkEpisodeMetadataInBackground() {
        if (cancelled.get()) return
        if (!ntkEpisodeMetadataLoading.compareAndSet(false, true)) return
        try {
            primeNetwork.execute {
                try {
                    val currentTitle = title ?: manga.title ?: return@execute
                    if (!isNtkSource(manga, currentTitle)) return@execute
                    if (syncNtkTitlePathFromEpisode(currentTitle, manga)) {
                        currentTitle.removeEps()
                    }
                    restoreNtkEpisodeSnapshotIfNeeded(currentTitle, manga)
                    if ((currentTitle.eps?.size ?: 0) > 1) return@execute
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = withRepositoryCancellation {
                        imageRepository.fetchEpisodesForeground(currentTitle, it)
                    }
                    if (cancelled.get()) return@execute
                    if (result == Title.LOAD_OK) {
                        attachTitle()
                        val episodes = Utils.snapshotEpisodes(currentTitle)
                        if (episodes.isNotEmpty()) {
                            manga.setEps(episodes)
                            persistNtkEpisodeSnapshot(currentTitle, episodes)
                        }
                    }
                    Log.d(
                        TAG,
                        "ntk_episode_metadata_prefetch result=$result ms=${SystemClock.elapsedRealtime() - startedAt} " +
                            "episodes=${currentTitle.eps?.size ?: 0}"
                    )
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                } finally {
                    ntkEpisodeMetadataLoading.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            ntkEpisodeMetadataLoading.set(false)
        }
    }

    private fun restoreNtkEpisodeSnapshotIfNeeded(currentTitle: Title, anchorManga: Manga): Boolean {
        if (!isNtkSource(anchorManga, currentTitle)) return false
        if ((currentTitle.eps?.size ?: 0) > 1) return true
        val episodes = readCachedNtkEpisodeSnapshot(currentTitle) ?: return false
        attachNtkEpisodeSnapshot(currentTitle, anchorManga, episodes)
        Log.d(
            TAG,
            "ntk_episode_snapshot_cache_hit titleId=${currentTitle.id} episodes=${episodes.size}"
        )
        return true
    }

    private fun readCachedNtkEpisodeSnapshot(currentTitle: Title): ArrayList<Manga>? {
        val primaryKey = EpisodeSnapshotCache.key(currentTitle, true)
        val legacyKey = EpisodeSnapshotCache.legacyKey(currentTitle)
        return readCachedNtkEpisodeSnapshot(primaryKey)
            ?: if (legacyKey == primaryKey) null else readCachedNtkEpisodeSnapshot(legacyKey)
    }

    private fun readCachedNtkEpisodeSnapshot(cacheKey: String): ArrayList<Manga>? {
        if (cacheKey.isEmpty()) return null
        val memoryJson = CacheFileStore.readMemory(cacheKey)
        parseCachedNtkEpisodeSnapshot(memoryJson)?.let { return it }
        val diskJson = CacheFileStore.read(appContext, cacheKey)
        return parseCachedNtkEpisodeSnapshot(diskJson)
    }

    private fun parseCachedNtkEpisodeSnapshot(json: String?): ArrayList<Manga>? {
        if (json.isNullOrEmpty()) return null
        return try {
            val snapshot = GSON.fromJson(json, CachedEpisodeSnapshot::class.java)
            val ordered = Title.orderedEpisodeSnapshot(snapshot?.episodes) ?: return null
            if (ordered.size > 1) ordered else null
        } catch (e: Exception) {
            recordIfUnexpected(e)
            null
        }
    }

    private fun attachNtkEpisodeSnapshot(currentTitle: Title, anchorManga: Manga, episodes: ArrayList<Manga>) {
        for (episode in episodes) {
            episode.title = currentTitle
            episode.titleId = currentTitle.id
        }
        currentTitle.setEps(episodes)
        manga.setEps(episodes)
        anchorManga.setEps(episodes)
        anchorManga.title = currentTitle
        anchorManga.titleId = currentTitle.id
    }

    private fun persistNtkEpisodeSnapshot(currentTitle: Title, episodes: List<Manga>) {
        if (!isNtkSource(manga, currentTitle) || episodes.size <= 1) return
        try {
            val snapshot = CachedEpisodeSnapshot()
            snapshot.episodes = ntkEpisodeSnapshotForCache(episodes)
            if (snapshot.episodes.isNullOrEmpty()) return
            CacheFileStore.write(appContext, EpisodeSnapshotCache.key(currentTitle, true), GSON.toJson(snapshot))
        } catch (e: Exception) {
            recordIfUnexpected(e)
        }
    }

    private fun ntkEpisodeSnapshotForCache(episodes: List<Manga>): ArrayList<Manga> {
        val ordered = Title.orderedEpisodeSnapshot(episodes) ?: return ArrayList()
        val copy = ArrayList<Manga>(ordered.size)
        for (episode in ordered) {
            val item = Manga(episode.id, episode.name, episode.date, episode.baseMode)
            item.addThumb(episode.thumb)
            item.mode = episode.mode
            item.titleId = episode.titleId
            item.setNtkEpisodePath(episode.ntkEpisodePath)
            item.ntkImageEpisodeId = episode.ntkImageEpisodeId
            item.ntkImageWorkId = episode.ntkImageWorkId
            item.ntkViewerPayloadHint = episode.ntkViewerPayloadHint
            item.setNtkImageCount(episode.ntkImageCount)
            item.offlinePath = episode.offlinePath
            copy.add(item)
        }
        return copy
    }

    private fun upgradeNtkInitialPriorityPagesAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        val first = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val firstPriority = minOf(count - 1, first + NTK_INITIAL_PRIORITY_START_OFFSET)
        val priorityPages = if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_INITIAL_BOOT_PRIORITY_PAGES
        } else {
            NTK_INITIAL_PRIORITY_PAGES
        }
        val last = minOf(count - 1, first + priorityPages)
        if (firstPriority > last) return
        for (index in firstPriority..last) {
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
        }
    }

    private fun prefetchNtkInitialNextBytesAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        prefetchNtkInitialNextBytes(currentStartPage(), count)
    }

    private fun scheduleNtkForwardTimelinePrimeAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        if (NTK_PRIME_FORWARD_EPISODES <= 0) return
        if (!timelinePrimeRequested.compareAndSet(false, true)) return
        scheduleNtkForwardTimelinePrimeAfterDelay(ntkForwardPrimeDelayMs())
    }

    private fun scheduleNtkForwardTimelinePrimeAfterDelay(delayMs: Long) {
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            val quietMs = ntkBackgroundPrepareQuietRemainingMs()
            if (quietMs > 0L) {
                scheduleNtkForwardTimelinePrimeAfterDelay(quietMs)
                return@postDelayed
            }
            primeForwardTimeline()
        }, delayMs)
    }

    private fun ntkBackgroundPrepareQuietRemainingMs(): Long {
        if (!isNtkSource(manga, title)) return 0L
        val firstBitmapAt = ntkFirstBitmapAtMs.get()
        if (firstBitmapAt > 0L) {
            val quietFor = SystemClock.uptimeMillis() - firstBitmapAt
            val remaining = NTK_BACKGROUND_PREPARE_AFTER_FIRST_BITMAP_QUIET_MS - quietFor
            if (remaining > 0L) return remaining
        }
        if (viewportBusy.get()) return NTK_BACKGROUND_PREPARE_QUIET_MS
        val lastInteraction = lastUserInteractionMs.get()
        if (lastInteraction <= 0L) return 0L
        val quietFor = SystemClock.uptimeMillis() - lastInteraction
        return (NTK_BACKGROUND_PREPARE_QUIET_MS - quietFor).coerceAtLeast(0L)
    }

    private fun isNtkCurrentAckPreflightInFlight(): Boolean {
        if (!isNtkSource(manga, title)) return false
        val path = manga.ntkEpisodePath
        if (path.isNullOrEmpty()) return false
        return try {
            MainApplication.getHttpClient().isNtkWebViewAckPreflightInFlight(path)
        } catch (_: Exception) {
            false
        }
    }

    private fun shouldDelayNtkAdjacentWorkForCurrentAck(): Boolean {
        return ntkCurrentAdjacentDelayMs() > 0L
    }

    private fun isNtkSilentAdjacentStillNearBoundary(anchor: Int, direction: Int): Boolean {
        if (!isNtkSource(manga, title)) return true
        val count = synchronized(pagesLock) {
            if (!isNtkGeneratedManhwaReadyForSilentAdjacentLocked()) return false
            pages.size
        }
        if (count <= 0) return false
        val boundedAnchor = anchor.coerceIn(0, count - 1)
        return if (direction > 0) {
            if (count <= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
            boundedAnchor >= count - NTK_SILENT_ADJACENT_BOUNDARY_PAGES
        } else {
            boundedAnchor < NTK_SILENT_ADJACENT_BOUNDARY_PAGES
        }
    }

    private fun isNtkGeneratedManhwaReadyForSilentAdjacentLocked(): Boolean {
        if (!isNtkManhwaEpisodePath(manga.ntkEpisodePath)) return true
        val currentKey = Manga.episodeIdentityKey(manga)
        val currentRefs = pages.filter { Manga.episodeIdentityKey(it.manga) == currentKey }
        if (currentRefs.none { isNtkGeneratedImageUrl(it.image.orEmpty()) }) return true
        val currentCount = currentRefs.count { it.transitionTitle == null }
        if (currentCount >= NTK_GENERATED_INITIAL_RECOVERY_PAGES) return true
        val knownCount = manga.ntkImageCount
        return knownCount in 1..currentCount
    }

    private fun ntkCurrentBackgroundWarmDelayMs(): Long {
        return ntkCurrentAdjacentDelayMs()
    }

    private fun ntkCurrentAdjacentDelayMs(): Long {
        if (!isNtkSource(manga, title)) return 0L
        val path = manga.ntkEpisodePath
        if (path.isNullOrEmpty()) return 0L
        return try {
            val client = MainApplication.getHttpClient()
            val now = SystemClock.uptimeMillis()
            val releaseAt = ntkAdjacentAfterAckReleaseAtMs.get()
            if (releaseAt > now) return (releaseAt - now).coerceAtLeast(NTK_ACK_PREFLIGHT_ADJACENT_RECHECK_MS)
            if (client.hasUsableNtkAdAckCookieForPath(path)) return 0L
            if (client.isNtkWebViewAckPreflightInFlight(path)) {
                ntkAdjacentAfterAckReleaseAtMs.updateAndGet { previous ->
                    maxOf(previous, now + NTK_ADJACENT_AFTER_ACK_QUIET_MS)
                }
                return NTK_ACK_PREFLIGHT_ADJACENT_RECHECK_MS
            }
            val firstBitmapAt = ntkFirstBitmapAtMs.get()
            if (firstBitmapAt <= 0L) return NTK_ACK_PREFLIGHT_ADJACENT_RECHECK_MS
            val waitedMs = SystemClock.uptimeMillis() - firstBitmapAt
            if (waitedMs < NTK_CURRENT_ACK_ADJACENT_MAX_WAIT_MS) {
                minOf(
                    NTK_BACKGROUND_PREPARE_QUIET_MS.coerceAtLeast(NTK_ACK_PREFLIGHT_ADJACENT_RECHECK_MS),
                    NTK_CURRENT_ACK_ADJACENT_MAX_WAIT_MS - waitedMs
                )
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun ntkForwardPrimeDelayMs(): Long {
        return if (isNtkGeneratedFastParse()) {
            NTK_GENERATED_FORWARD_PRIME_AFTER_FIRST_BITMAP_DELAY_MS
        } else {
            NTK_NATIVE_FORWARD_PRIME_AFTER_FIRST_BITMAP_DELAY_MS
        }
    }

    private fun isNtkGeneratedFastParse(): Boolean {
        val reason = manga.getNtkViewerParseReason().lowercase()
        return reason.startsWith("generated") && !reason.startsWith("generated-unreachable")
    }

    private fun leaseImageFile(
        index: Int,
        page: PageRef,
        foreground: Boolean,
        visiblePriority: Boolean
    ): ReaderImageCache.FileLease {
        val image = page.image ?: throw java.io.IOException("Missing image for page $index")
        return ReaderImageCache.leaseFile(
            appContext,
            page.manga,
            image,
            foreground,
            imageCancellation,
            visiblePriority = visiblePriority
        )
    }

    private fun prefetchImageFile(
        index: Int,
        page: PageRef,
        foreground: Boolean = false,
        visiblePriority: Boolean = false
    ) {
        val image = page.image ?: return
        if (page.manga.isOnline) {
            if (foreground) {
                ReaderImageCache.getOrFetchFileForeground(
                    appContext,
                    page.manga,
                    image,
                    imageCancellation,
                    visiblePriority = visiblePriority
                )
            } else {
                ReaderImageCache.getOrFetchFile(appContext, page.manga, image, imageCancellation)
            }
        } else {
            File(image)
        }
    }

    private fun prefetchImageFileQuietly(index: Int, page: PageRef) {
        try {
            if (!cancelled.get()) prefetchImageFile(index, page)
        } catch (e: Exception) {
            recordIfUnexpected(e)
        }
    }

    private fun recordIfUnexpected(e: Exception) {
        if (!cancelled.get() && !isExpectedCancellation(e)) {
            ml.melun.mangaview.report.CrashReporter.record(e)
        }
    }

    private fun postPageError(index: Int, page: PageRef, e: Exception) {
        if (trimNtkGeneratedTail(index, page, e)) return
        if (refreshNtkGeneratedPageImage(index, page, e)) return
        if (removeInvalidNtkGeneratedPage(index, page, e)) return
        if (retryTransientNtkGeneratedPageError(index, page, e)) return
        if (pageRef(index) != page) return
        if (hasDeliveredOrPendingDrawable(index)) {
            Log.d(
                TAG,
                "page_error_skip_delivered index=$index,pageIndex=${page.pageIndex},source=${page.sourceIndex}," +
                    "path=${page.manga.ntkEpisodePath},image=${page.image},error=${e.javaClass.simpleName}:${e.message}"
            )
            ViewerWarmupManager.logMetric("reader_page_error_skip_delivered", index.toLong())
            return
        }
        if (cancelled.get() || isExpectedCancellation(e) || !failedPages.add(index)) return
        Log.d(
            TAG,
            "page_error index=$index,pageIndex=${page.pageIndex},source=${page.sourceIndex},path=${page.manga.ntkEpisodePath},image=${page.image},error=${e.message}"
        )
        main.post {
            if (!cancelled.get() && pageRef(index) == page) {
                listener.onPageError(index, "Image load failed")
            }
        }
    }

    private fun trimNtkGeneratedTail(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val knownCount = page.manga.ntkImageCount
        val pastGeneratedTail = (e.message ?: "").startsWith("Generated image past tail:")
        if (knownCount <= 0) return false
        val firstTailSourceIndex = when {
            knownCount > 0 && page.sourceIndex >= knownCount -> knownCount
            pastGeneratedTail && page.sourceIndex >= knownCount -> page.sourceIndex
            else -> return false
        }
        if (firstTailSourceIndex < 0) return false
        val ranges = ArrayList<Pair<Int, Int>>()
        val total: Int
        var displayTotalPages = 0
        synchronized(pagesLock) {
            val removeIndexes = pages.withIndex()
                .filter { item ->
                    item.value.transitionTitle == null &&
                        item.value.sourceIndex >= firstTailSourceIndex &&
                        Manga.sameEpisodeIdentity(item.value.manga, page.manga)
                }
                .map { it.index }
            if (removeIndexes.isEmpty()) return false
            beginStructurePublish()
            var start = removeIndexes.first()
            var previous = start
            for (i in 1 until removeIndexes.size) {
                val current = removeIndexes[i]
                if (current == previous + 1) {
                    previous = current
                } else {
                    ranges.add(start to previous)
                    start = current
                    previous = current
                }
            }
            ranges.add(start to previous)
            for ((rangeStart, rangeEnd) in ranges.asReversed()) {
                for (i in rangeEnd downTo rangeStart) pages.removeAt(i)
            }
            pages.forEachIndexed { pageIndex, ref -> ref.pageIndex = pageIndex }
            displayTotalPages = pages.count { ref ->
                ref.transitionTitle == null && Manga.sameEpisodeIdentity(ref.manga, page.manga)
            }
            if (displayTotalPages > 0) {
                page.manga.setNtkImageCount(displayTotalPages)
                pages.forEach { ref ->
                    if (ref.transitionTitle == null && Manga.sameEpisodeIdentity(ref.manga, page.manga)) {
                        ref.totalPages = displayTotalPages
                    }
                }
            }
            removePageStateRange(rangeStart = ranges.first().first, removedCount = removeIndexes.size)
            total = pages.size
        }
        for ((start, end) in ranges.asReversed()) {
            val count = end - start + 1
            main.post {
                if (!cancelled.get()) listener.onPagesRemoved(start, count, total)
            }
            Log.d(
                TAG,
                "trim_generated_tail start=$start,count=$count,known=$knownCount,tailSource=$firstTailSourceIndex," +
                    "errorPage=$index,total=$total,displayTotal=$displayTotalPages"
            )
        }
        if (!main.post {
                finishStructurePublish()
                requestRetainedWindowAfterStructureChange()
            }) {
            finishStructurePublish()
            requestRetainedWindowAfterStructureChange()
        }
        return true
    }

    private fun refreshNtkGeneratedPageImage(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val originalImage = page.image ?: return false
        if (!isNtkGeneratedImageUrl(originalImage)) return false
        if (!isImageNotFoundError(e)) return false
        val replacement = imageRepository.imageUrls(page.manga, appContext).getOrNull(page.sourceIndex)
            ?.takeIf { it.isNotBlank() && it != originalImage }
            ?: return false
        synchronized(pagesLock) {
            if (index !in pages.indices || pages[index] != page) return false
            for (ref in pages) {
                if (
                    ref.transitionTitle == null &&
                    ref.sourceIndex == page.sourceIndex &&
                    ref.image == originalImage &&
                    Manga.sameEpisodeIdentity(ref.manga, page.manga)
                ) {
                    ref.image = replacement
                }
            }
        }
        failedPages.remove(index)
        decodedWidths.remove(index)
        desiredWidths.remove(index)
        pendingDeliveryWidths.remove(index)
        sourceWidths.remove(index)
        achievableWidths.remove(index)
        inFlightWidths.remove(index)
        Log.d(
            TAG,
            "refresh_generated_page_image index=$index,source=${page.sourceIndex},path=${page.manga.ntkEpisodePath}," +
                "from=$originalImage,to=$replacement"
        )
        requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
        return true
    }

    private fun removeInvalidNtkGeneratedPage(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val image = page.image ?: return false
        if (!isNtkGeneratedImageUrl(image)) return false
        if (!isImageNotFoundError(e)) return false
        if (!firstBitmapLogged.get() && index == currentStartPage()) {
            val replacement = ntkGeneratedImageUrlForTarget(
                image,
                page.manga,
                ntkGeneratedPageNumber(image) ?: (page.sourceIndex + 1)
            )
            if (!replacement.isNullOrBlank() && replacement != image) {
                synchronized(pagesLock) {
                    if (index !in pages.indices || pages[index] != page) return false
                    for (ref in pages) {
                        if (
                            ref.transitionTitle == null &&
                            ref.sourceIndex == page.sourceIndex &&
                            ref.image == image &&
                            Manga.sameEpisodeIdentity(ref.manga, page.manga)
                        ) {
                            ref.image = replacement
                        }
                    }
                }
                failedPages.remove(index)
                decodedWidths.remove(index)
                desiredWidths.remove(index)
                pendingDeliveryWidths.remove(index)
                sourceWidths.remove(index)
                achievableWidths.remove(index)
                inFlightWidths.remove(index)
                Log.d(
                    TAG,
                    "remove_invalid_generated_page_retarget_anchor index=$index,source=${page.sourceIndex}," +
                        "path=${page.manga.ntkEpisodePath},from=$image,to=$replacement,error=${e.message}"
                )
                requestPage(index, busy = true, anchor = true, generation = FOREGROUND_PRIME_WARM_GENERATION)
                return true
            }
            Log.d(
                TAG,
                "remove_invalid_generated_page_defer_anchor index=$index,source=${page.sourceIndex}," +
                    "path=${page.manga.ntkEpisodePath},image=$image,error=${e.message}"
            )
            return true
        }
        val removeIndex: Int
        val total: Int
        val displayTotalPages: Int
        synchronized(pagesLock) {
            if (index !in pages.indices || pages[index] != page) return false
            beginStructurePublish()
            removeIndex = index
            pages.removeAt(removeIndex)
            pages.forEachIndexed { pageIndex, ref -> ref.pageIndex = pageIndex }
            displayTotalPages = pages.count { ref ->
                ref.transitionTitle == null && Manga.sameEpisodeIdentity(ref.manga, page.manga)
            }
            if (displayTotalPages > 0) {
                pages.forEach { ref ->
                    if (ref.transitionTitle == null && Manga.sameEpisodeIdentity(ref.manga, page.manga)) {
                        ref.totalPages = displayTotalPages
                    }
                }
            }
            removePageStateRange(removeIndex, 1)
            total = pages.size
        }
        val posted = main.post {
            try {
                if (!cancelled.get()) listener.onPagesRemoved(removeIndex, 1, total)
            } finally {
                finishStructurePublish()
                requestRetainedWindowAfterStructureChange()
            }
        }
        if (!posted) {
            finishStructurePublish()
            requestRetainedWindowAfterStructureChange()
        }
        Log.d(
            TAG,
            "remove_invalid_generated_page index=$removeIndex,source=${page.sourceIndex},displayTotal=$displayTotalPages," +
                "path=${page.manga.ntkEpisodePath},image=$image,error=${e.message}"
        )
        return true
    }

    private fun requestRetainedWindowAfterStructureChange() {
        if (cancelled.get()) return
        val snapshot = synchronized(deliveredBitmaps) {
            if (retainedLastPage < retainedFirstPage) {
                null
            } else {
                val first = retainedFirstPage.coerceAtLeast(0)
                val last = retainedLastPage.coerceAtLeast(first)
                val anchor = retainedAnchorPage.coerceIn(first, last)
                Triple(first, last, anchor)
            }
        } ?: run {
            if (isNtkSource(manga, title) && !firstBitmapLogged.get()) {
                val anchor = currentStartPage()
                Log.d(
                    TAG,
                    "reader_structure_anchor_re_request page=$anchor,busy=${viewportBusy.get()}"
                )
                requestPageForeground(anchor)
                requestInitialContinuousPagesFromEarlyUrls(anchor, synchronized(pagesLock) { pages.size })
            }
            return
        }
        Log.d(
            TAG,
            "reader_structure_window_re_request first=${snapshot.first},last=${snapshot.second},anchor=${snapshot.third}," +
                "busy=${viewportBusy.get()}"
        )
        requestWindowAsync(snapshot.first, snapshot.second, snapshot.third, viewportBusy.get())
        val count = synchronized(pagesLock) { pages.size }
        val edgeLast = minOf(count - 1, snapshot.second + NTK_BUSY_VISIBLE_EDGE_EXTRA_PAGES)
        if (edgeLast > snapshot.second) {
            for (index in (snapshot.second + 1)..edgeLast) {
                requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
            }
        }
    }

    private fun isImageNotFoundError(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("Image request failed: 404") || message.contains(" code=404")) return true
            if (message.contains("Image request failed: 410") || message.contains(" code=410")) return true
            if (message.contains("Image request failed: 520") || message.contains(" code=520")) return true
            if (message.contains("Generated image not found", ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }

    private fun isTransientNtkGeneratedImageError(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                current is java.net.SocketException ||
                current is java.net.SocketTimeoutException ||
                message.contains("Partial foreground image response is not cacheable", ignoreCase = true) ||
                message.contains("Connection reset", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true)
            ) return true
            current = current.cause
        }
        return false
    }

    private fun isNtkGeneratedImageUrl(image: String): Boolean {
        return NTK_GENERATED_IMAGE_URL.matchEntire(image) != null
    }

    private fun isNtkBoardUploadImageUrl(image: String?): Boolean {
        return image?.lowercase()?.contains("/board_uploads/") == true
    }

    private fun isNtkProtectedCdnImageUrl(image: String?): Boolean {
        val lower = image?.lowercase() ?: return false
        return lower.contains("://toonflix.app/") ||
            lower.contains("://i.toonflix.app/") ||
            Regex("://flysky\\d*m\\.com/").containsMatchIn(lower) ||
            lower.contains("://moamoabon.com/") ||
            Regex("://fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            lower.startsWith("toonflix.app/") ||
            lower.startsWith("i.toonflix.app/") ||
            Regex("^flysky\\d*m\\.com/").containsMatchIn(lower) ||
            lower.startsWith("moamoabon.com/") ||
            lower.startsWith("//moamoabon.com/") ||
            Regex("^fvcdn\\d*\\.com/").containsMatchIn(lower)
    }

    private fun ntkGeneratedSiblingImageUrl(seed: String, page: Int): String? {
        if (page <= 0) return null
        val match = NTK_GENERATED_IMAGE_URL.matchEntire(seed) ?: return null
        return "${match.groupValues[1]}p${page.toString().padStart(3, '0')}.${match.groupValues[2]}${match.groupValues[3]}"
    }

    private fun ntkGeneratedSeedPage(seed: String): Int {
        return Regex("/p(\\d{3,})\\.").find(seed)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
    }

    private fun ntkGeneratedPageNumber(image: String): Int? {
        return NTK_GENERATED_PAGE_URL.find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun ntkGeneratedImageUrlForTarget(seed: String, target: Manga, page: Int): String? {
        if (page <= 0) return null
        val seedMatch = NTK_GENERATED_IMAGE_URL.matchEntire(seed) ?: return null
        val targetMatch = NTK_EPISODE_PATH.matchEntire(target.ntkEpisodePath.orEmpty()) ?: return ntkGeneratedSiblingImageUrl(seed, page)
        val seedTargetPath = ntkGeneratedPathFromPrefix(seedMatch.groupValues[1])
        if ((seedTargetPath != null && seedTargetPath.equals(target.ntkEpisodePath.orEmpty(), ignoreCase = true)) ||
            ntkGeneratedPrefixEpisodeMatchesTarget(seedMatch.groupValues[1], targetMatch)
        ) {
            val pathEpisodeId = targetMatch.groupValues[3]
            val imageEpisodeId = target.ntkImageEpisodeId.orEmpty().trim()
            if (!imageEpisodeId.matches(Regex("\\d+")) || imageEpisodeId == pathEpisodeId) {
                return ntkGeneratedSiblingImageUrl(seed, page)
            }
        }
        val segment = targetMatch.groupValues[1]
        val pathEpisodeId = targetMatch.groupValues[3]
        val imageEpisodeId = target.ntkImageEpisodeId.orEmpty().trim()
        val episodeId = imageEpisodeId.takeIf { it.matches(Regex("\\d+")) }
            ?: pathEpisodeId.takeIf { it.matches(Regex("\\d+")) }
            ?: pathEpisodeId
        val seedPrefix = seedMatch.groupValues[1]
        val targetPrefix = when {
            seedPrefix.contains("/blacktoon/episodes/", ignoreCase = true) -> {
                val seedWorkId = Regex("/blacktoon/episodes/([^/]+)/").find(seedPrefix)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return ntkGeneratedSiblingImageUrl(seed, page)
                ntkGeneratedPrefixOrigin(seedPrefix) + "/blacktoon/episodes/$seedWorkId/$episodeId/"
            }
            seedPrefix.contains("/wt/episodes/", ignoreCase = true) -> {
                val seedWorkId = Regex("/wt/episodes/([^/]+)/").find(seedPrefix)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return ntkGeneratedSiblingImageUrl(seed, page)
                ntkGeneratedPrefixOrigin(seedPrefix) + "/wt/episodes/$seedWorkId/$episodeId/"
            }
            else -> {
                val seedWorkId = Regex("/(?:manhwa|webtoon)/([^/]+)/").find(seedPrefix)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: targetMatch.groupValues[2]
                ntkGeneratedPrefixOrigin(seedPrefix) + "/$segment/$seedWorkId/$episodeId/"
            }
        }
        val actualPage = ntkGeneratedSeedPage(seed) + page - 1
        return "${targetPrefix}p${actualPage.toString().padStart(3, '0')}.${seedMatch.groupValues[2]}${seedMatch.groupValues[3]}"
    }

    private fun ntkGeneratedPrefixOrigin(prefix: String): String {
        val match = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(prefix)
        return match?.groupValues?.getOrNull(1) ?: "https://i.toonflix.app"
    }

    private fun ntkGeneratedPathFromPrefix(prefix: String): String? {
        Regex("^https?://[^/]+/(manhwa|webtoon)/(\\d+)/([^/?#]+)/$", RegexOption.IGNORE_CASE)
            .find(prefix)
            ?.let { return "/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}" }
        Regex("^https?://[^/]+/blacktoon/episodes/(\\d+)/([^/?#]+)/$", RegexOption.IGNORE_CASE)
            .find(prefix)
            ?.let { return "/webtoon/${it.groupValues[1]}/${it.groupValues[2]}" }
        Regex("^https?://[^/]+/wt/episodes/([^/?#]+)/([^/?#]+)/$", RegexOption.IGNORE_CASE)
            .find(prefix)
            ?.let { return "/webtoon/${it.groupValues[1]}/${it.groupValues[2]}" }
        return null
    }

    private fun ntkGeneratedPrefixEpisodeMatchesTarget(
        prefix: String,
        targetMatch: MatchResult
    ): Boolean {
        val targetSegment = targetMatch.groupValues[1]
        val targetEpisode = targetMatch.groupValues[3]
        val seedEpisode = when {
            prefix.contains("/blacktoon/episodes/", ignoreCase = true) ->
                Regex("^https?://[^/]+/blacktoon/episodes/[^/]+/([^/?#]+)/$", RegexOption.IGNORE_CASE)
                    .find(prefix)
                    ?.groupValues
                    ?.getOrNull(1)
            prefix.contains("/wt/episodes/", ignoreCase = true) ->
                Regex("^https?://[^/]+/wt/episodes/[^/]+/([^/?#]+)/$", RegexOption.IGNORE_CASE)
                    .find(prefix)
                    ?.groupValues
                    ?.getOrNull(1)
            else ->
                Regex("^https?://[^/]+/(manhwa|webtoon)/[^/]+/([^/?#]+)/$", RegexOption.IGNORE_CASE)
                    .find(prefix)
                    ?.takeIf { it.groupValues[1].equals(targetSegment, ignoreCase = true) }
                    ?.groupValues
                    ?.getOrNull(2)
        }
        return !seedEpisode.isNullOrBlank() && seedEpisode == targetEpisode
    }

    private fun removePageStateRange(rangeStart: Int, removedCount: Int) {
        if (removedCount <= 0) return
        shiftConcurrentMapAfterRemoval(decodedWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(desiredWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(pendingDeliveryWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(sourceWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(achievableWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(transientGeneratedRetries, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(initialContinuousPostedWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(earlyPreparedBitmaps, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(inFlightWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(loadingPages, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(urgentLoadingPages, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(loading, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(urgentLoading, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(bytePrefetching, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(preAnchorFallbackRetries, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(idleFullWidthUpgradeScheduled, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(failedPages, rangeStart, removedCount)
        shiftDeliveryQueueAfterRemoval(rangeStart, removedCount)
        shiftDeliveryMapAfterRemoval(primedDeliveryBacklog, rangeStart, removedCount)
        shiftDeliveryMapAfterRemoval(initialDeliveryBacklog, rangeStart, removedCount)
        shiftPreparedMapAfterRemoval(initialPreparedBacklog, rangeStart, removedCount)
        synchronized(deliveredBitmaps) {
            shiftLinkedMapAfterRemoval(deliveredBitmaps, rangeStart, removedCount)
            shiftLinkedMapAfterRemoval(deliveredTiles, rangeStart, removedCount)
            val oldOwned = deliveredOwned.toList()
            deliveredOwned.clear()
            for (index in oldOwned) shiftedIndexAfterRemoval(index, rangeStart, removedCount)?.let { deliveredOwned.add(it) }
            retainedFirstPage = shiftedIndexAfterRemoval(retainedFirstPage, rangeStart, removedCount) ?: rangeStart
            retainedLastPage = shiftedIndexAfterRemoval(retainedLastPage, rangeStart, removedCount) ?: (rangeStart - 1)
            retainedAnchorPage = shiftedIndexAfterRemoval(retainedAnchorPage, rangeStart, removedCount) ?: retainedFirstPage
        }
        synchronized(windowLock) {
            lastWindowAnchor = shiftedIndexAfterRemoval(lastWindowAnchor, rangeStart, removedCount) ?: -1
            windowGeneration.incrementAndGet()
        }
    }

    private fun shiftedIndexAfterRemoval(index: Int, rangeStart: Int, removedCount: Int): Int? {
        if (index < rangeStart) return index
        if (index < rangeStart + removedCount) return null
        return index - removedCount
    }

    private fun <T> shiftConcurrentMapAfterRemoval(map: ConcurrentHashMap<Int, T>, rangeStart: Int, removedCount: Int) {
        if (map.isEmpty()) return
        val entries = map.entries.toList()
        map.clear()
        for (entry in entries) shiftedIndexAfterRemoval(entry.key, rangeStart, removedCount)?.let { map[it] = entry.value }
    }

    private fun shiftConcurrentSetAfterRemoval(set: MutableSet<Int>, rangeStart: Int, removedCount: Int) {
        if (set.isEmpty()) return
        val entries = set.toList()
        set.clear()
        for (index in entries) shiftedIndexAfterRemoval(index, rangeStart, removedCount)?.let { set.add(it) }
    }

    private fun shiftDeliveryQueueAfterRemoval(rangeStart: Int, removedCount: Int) {
        if (deliveryQueue.isEmpty()) return
        val entries = ArrayList<Delivery>()
        while (true) {
            entries.add(deliveryQueue.poll() ?: break)
        }
        for (delivery in entries) {
            val current = currentPageIndex(delivery.page, delivery.index)
            if (current < 0) {
                recycleDecodeResult(delivery.result)
            } else {
                deliveryQueue.add(delivery.copy(index = current))
            }
        }
    }

    private fun shiftDeliveryMapAfterRemoval(
        map: ConcurrentHashMap<Int, Delivery>,
        rangeStart: Int,
        removedCount: Int
    ) {
        if (map.isEmpty()) return
        val entries = map.entries.toList()
        map.clear()
        for (entry in entries) {
            val current = currentPageIndex(entry.value.page, entry.value.index)
            if (current < 0) {
                recycleDecodeResult(entry.value.result)
            } else {
                map[current] = entry.value.copy(index = current)
            }
        }
    }

    private fun shiftPreparedMapAfterRemoval(
        map: ConcurrentHashMap<Int, PreparedDelivery>,
        rangeStart: Int,
        removedCount: Int
    ) {
        if (map.isEmpty()) return
        val entries = map.entries.toList()
        map.clear()
        for (entry in entries) {
            val shifted = shiftedIndexAfterRemoval(entry.key, rangeStart, removedCount)
            if (shifted == null) {
                if (entry.value.owned && !entry.value.bitmap.isRecycled) entry.value.bitmap.recycle()
            } else {
                map[shifted] = entry.value
            }
        }
    }

    private fun <T> shiftLinkedMapAfterRemoval(map: LinkedHashMap<Int, T>, rangeStart: Int, removedCount: Int) {
        if (map.isEmpty()) return
        val entries = map.entries.toList()
        map.clear()
        for (entry in entries) shiftedIndexAfterRemoval(entry.key, rangeStart, removedCount)?.let { map[it] = entry.value }
    }

    private fun isExpectedCancellation(t: Throwable?): Boolean {
        if (t == null) return false
        if (cancelled.get()) return true
        if (t is java.util.concurrent.CancellationException) return true
        if (t is InterruptedException || t is InterruptedIOException) return true
        if (t.javaClass.name.endsWith("StreamResetException") && (t.message ?: "").contains("CANCEL")) return true
        if ((t.message ?: "").startsWith("Generated image past tail:")) return true
        if (t is ExecutionException) return isExpectedCancellation(t.cause)
        val cause = t.cause
        return cause != null && cause !== t && isExpectedCancellation(cause)
    }

    private fun decodePageWithLease(
        index: Int,
        page: PageRef,
        targetWidth: Int,
        foregroundFetch: Boolean,
        allowPreviewCache: Boolean,
        visiblePriority: Boolean,
        foregroundPermit: NtkImagePermit? = null
    ): PageDecodeResult {
        cachedDecodedResult(page, targetWidth, allowPreviewCache)?.let {
            logNtkPagePerf(index, "cache_hit_decode", "target=$targetWidth,width=${it.width}")
            return it
        }
        decodeForegroundStream(index, page, targetWidth, foregroundFetch, visiblePriority, foregroundPermit)?.let {
            logNtkPagePerf(index, "foreground_stream_hit", "target=$targetWidth,width=${it.width}")
            return it
        }
        val startPage = currentStartPage()
        val trace = shouldTraceNtkPage(index, page)
        val leaseStart = if (page.manga.isOnline && (index == startPage || trace)) SystemClock.elapsedRealtime() else 0L
        leaseImageFile(index, page, foregroundFetch || index == startPage, visiblePriority || index == startPage).use { lease ->
            if (leaseStart > 0L) {
                val leaseMs = SystemClock.elapsedRealtime() - leaseStart
                if (index == startPage) ViewerWarmupManager.logMetric("reader_first_fetch_wait_ms", leaseMs)
                val image = page.image
                val cached = image != null && ReaderImageCache.cachedFile(appContext, page.manga, image) != null
                logNtkPagePerf(
                    index,
                    "file_lease",
                    "ms=$leaseMs,foreground=$foregroundFetch,visiblePriority=$visiblePriority,cached=$cached"
                )
            }
            return decodePage(index, page, lease.file, targetWidth)
        }
    }

    private fun shouldTraceNtkPage(index: Int, page: PageRef): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val start = currentStartPage()
        return index in max(0, start - 1)..minOf(start + NTK_TRACE_AHEAD_PAGES, start + 12)
    }

    private fun logNtkPagePerf(index: Int, stage: String, detail: String) {
        if (!isNtkSource(manga, title)) return
        val start = currentStartPage()
        if (index !in max(0, start - 1)..minOf(start + NTK_TRACE_AHEAD_PAGES, start + 12)) return
        Log.d(TAG, "reader_ntk_page_perf page=$index,rel=${index - start},stage=$stage,$detail")
    }

    private fun decodeForegroundStream(
        index: Int,
        page: PageRef,
        targetWidth: Int,
        foregroundFetch: Boolean,
        visiblePriority: Boolean,
        permit: NtkImagePermit?
    ): PageDecodeResult? {
        if (!page.manga.isOnline || (!foregroundFetch && index != currentStartPage())) return null
        val image = page.image ?: return null
        if (!ntkCoordinatorAllowsStream(index, page, permit, "decodeForegroundStream")) return null
        val metric = SystemClock.elapsedRealtime()
        val raw = try {
            ReaderImageCache.decodeForegroundBitmap(
                appContext,
                page.manga,
                image,
                targetWidth,
                autoCut,
                page.allowAutoSplit,
                imageCancellation,
                anchorHedge = false,
                visiblePriority = visiblePriority || index == currentStartPage(),
                permit = permit,
                pageIndex = index
            )
        } catch (e: Exception) {
            recordIfUnexpected(e)
            null
        } ?: run {
            if (isNtkSource(page.manga, title) && isNtkGeneratedImageUrl(image)) {
                ReaderImageCache.cachedFile(appContext, page.manga, image)?.let { cached ->
                    logNtkPagePerf(
                        index,
                        "foreground_stream_cached_file_fallback",
                        "bytes=${cached.length()},target=$targetWidth"
                    )
                    return decodePage(index, page, cached, targetWidth)
                }
            }
            return null
        }
        val rawAt = SystemClock.elapsedRealtime()
        val decodeTargetWidth = decodeTargetWidth(raw.width, raw.height, targetWidth, page.allowAutoSplit)
        val decoded = if (raw.width <= decodeTargetWidth) {
            raw
        } else {
            Decoder(page.manga.seed, page.manga.id).decode(raw, decodeTargetWidth, Glide.get(appContext).bitmapPool)
        }
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        deliverAutoSplitSiblingFromDecoded(index, page, decoded)
        val transformedAt = SystemClock.elapsedRealtime()
        val bitmap = ViewerBitmapTrim.trimBlankVerticalEdges(
            applyAutoSplit(decoded, page.side, page.allowAutoSplit),
            true
        )
        postPageBounds(index, page, bitmap.width, bitmap.height)
        val result = drawableResult(bitmap)
        ViewerWarmupManager.logMetric("reader_first_stream_raw_ms", rawAt - metric)
        ViewerWarmupManager.logMetric("reader_first_stream_transform_ms", transformedAt - rawAt)
        ViewerWarmupManager.logMetric("reader_first_decode_total_ms", SystemClock.elapsedRealtime() - metric)
        logNtkPagePerf(index, "foreground_stream_decode", "rawMs=${rawAt - metric},transformMs=${transformedAt - rawAt},totalMs=${SystemClock.elapsedRealtime() - metric},width=${result.width}")
        return result
    }

    private fun shouldUsePreviewDecodedCache(index: Int, targetWidth: Int): Boolean {
        return targetWidth < targetWidth(false) && !hasDeliveredBitmap(index)
    }

    private fun cachedDecodedResult(
        page: PageRef,
        targetWidth: Int,
        allowPreviewCache: Boolean
    ): PageDecodeResult? {
        val image = page.image ?: return null
        val pageItem = PageItem(page.sourceIndex, image, page.manga, page.side)
        val full = ViewerWarmupManager.getDecodedBitmap(
            pageItem,
            autoCut,
            reverse,
            targetWidth,
            false
        )
        val preview = if (allowPreviewCache) {
            ViewerWarmupManager.getDecodedBitmap(
                pageItem,
                autoCut,
                reverse,
                targetWidth,
                true
            )
        } else {
            null
        }
        val bitmap = full ?: preview ?: return null
        if (bitmap.isRecycled) return null
        return PageDecodeResult.Full(ViewerBitmapTrim.trimBlankVerticalEdges(bitmap))
    }

    private fun decodePage(index: Int, page: PageRef, file: File, targetWidth: Int): PageDecodeResult {
        val metric = index == currentStartPage() && page.manga.isOnline
        val trace = shouldTraceNtkPage(index, page)
        val startedAt = if (metric || trace) SystemClock.elapsedRealtime() else 0L
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (page.manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } else {
            decodeLocal(page.image ?: "", bounds)
        }
        val boundsAt = if (metric || trace) SystemClock.elapsedRealtime() else 0L
        if (!autoCut && shouldDecodeTiles(page, file, bounds)) {
            val displayBounds = displayBounds(bounds.outWidth, bounds.outHeight, page.side, page.allowAutoSplit)
            postPageBounds(index, page, displayBounds.width(), displayBounds.height())
            val result = decodePageTiles(file, bounds, targetWidth)
            if (trace) {
                logNtkPagePerf(index, "decode_tiles", "boundsMs=${boundsAt - startedAt},totalMs=${SystemClock.elapsedRealtime() - startedAt},target=$targetWidth,width=${result.width}")
            }
            return result
        }
        val decodeTargetWidth = decodeTargetWidth(bounds.outWidth, bounds.outHeight, targetWidth, page.allowAutoSplit)
        val sample = sampleSize(bounds.outWidth, decodeTargetWidth)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val raw = if (page.manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } else {
            decodeLocal(page.image ?: "", options)
        }
            ?: throw java.io.IOException("Bitmap decode failed")
        val rawAt = if (metric || trace) SystemClock.elapsedRealtime() else 0L
        if (!page.manga.isOnline) {
            val bitmap = ViewerBitmapTrim.trimBlankVerticalEdges(
                applyAutoSplit(raw, page.side, page.allowAutoSplit),
                true
            )
            postPageBounds(index, page, bitmap.width, bitmap.height)
            val result = drawableResult(bitmap)
            if (trace) {
                logNtkPagePerf(index, "decode_local", "boundsMs=${boundsAt - startedAt},rawMs=${rawAt - boundsAt},totalMs=${SystemClock.elapsedRealtime() - startedAt},target=$targetWidth,width=${result.width}")
            }
            return result
        }
        val decoded = Decoder(page.manga.seed, page.manga.id).decode(raw, decodeTargetWidth, Glide.get(appContext).bitmapPool)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        deliverAutoSplitSiblingFromDecoded(index, page, decoded)
        val transformedAt = if (metric || trace) SystemClock.elapsedRealtime() else 0L
        val bitmap = ViewerBitmapTrim.trimBlankVerticalEdges(
            applyAutoSplit(decoded, page.side, page.allowAutoSplit),
            true
        )
        postPageBounds(index, page, bitmap.width, bitmap.height)
        val result = drawableResult(bitmap)
        if (metric) {
            val finishedAt = SystemClock.elapsedRealtime()
            ViewerWarmupManager.logMetric("reader_first_bounds_ms", boundsAt - startedAt)
            ViewerWarmupManager.logMetric("reader_first_raw_decode_ms", rawAt - boundsAt)
            ViewerWarmupManager.logMetric("reader_first_transform_ms", transformedAt - rawAt)
            ViewerWarmupManager.logMetric("reader_first_decode_total_ms", finishedAt - startedAt)
        }
        if (trace) {
            val finishedAt = SystemClock.elapsedRealtime()
            logNtkPagePerf(index, "decode_file", "boundsMs=${boundsAt - startedAt},rawMs=${rawAt - boundsAt},transformMs=${transformedAt - rawAt},totalMs=${finishedAt - startedAt},target=$targetWidth,width=${result.width}")
        }
        return result
    }

    private fun drawableResult(bitmap: Bitmap): PageDecodeResult {
        if (!shouldSplitDecodedBitmapForDraw(bitmap)) return PageDecodeResult.Full(bitmap)
        val tiles = ArrayList<ReaderTile>()
        val width = bitmap.width
        val height = bitmap.height
        try {
            var top = 0
            while (top < height) {
                val bottom = minOf(height, top + DECODED_DRAW_TILE_HEIGHT)
                val tileBitmap = Bitmap.createBitmap(bitmap, 0, top, width, bottom - top)
                tiles.add(ReaderTile(top, bottom, width, height, tileBitmap))
                top = bottom
            }
        } catch (_: Exception) {
            for (tile in tiles) {
                if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
            }
            return PageDecodeResult.Full(bitmap)
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        return PageDecodeResult.Tiles(width, height, width, tiles)
    }

    private fun shouldSplitDecodedBitmapForDraw(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        if (bitmap.height < DECODED_DRAW_TILE_MIN_HEIGHT) return false
        return bitmapBytes(bitmap) >= DECODED_DRAW_TILE_MIN_BYTES
    }

    private fun decodeTargetWidth(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, allowSplit: Boolean): Int {
        val safeTarget = max(1, targetWidth)
        if (!autoCut || !allowSplit || !shouldAutoSplit(max(1, sourceWidth), max(1, sourceHeight))) return safeTarget
        return safeTarget * 2
    }

    private fun displayBounds(width: Int, height: Int, side: Int, allowSplit: Boolean): Rect {
        val safeWidth = max(1, width)
        val safeHeight = max(1, height)
        if (!autoCut || !allowSplit) return Rect(0, 0, safeWidth, safeHeight)
        if (shouldAutoSplit(safeWidth, safeHeight)) {
            val cropWidth = max(1, safeWidth / 2)
            return Rect(0, 0, cropWidth, safeHeight)
        }
        return if (side == PAGE_SIDE_FIRST) {
            Rect(0, 0, safeWidth, safeHeight)
        } else {
            Rect(0, 0, safeWidth, 1)
        }
    }

    private fun applyAutoSplit(bitmap: Bitmap, side: Int, allowSplit: Boolean): Bitmap {
        if (!autoCut || !allowSplit) return bitmap
        val decodedWidth = bitmap.width
        val decodedHeight = bitmap.height
        if (!shouldAutoSplit(decodedWidth, decodedHeight)) {
            if (side == PAGE_SIDE_FIRST) return bitmap
            val empty = Bitmap.createBitmap(max(1, decodedWidth), 1, displayConfig(bitmap))
            empty.eraseColor(Color.TRANSPARENT)
            if (!bitmap.isRecycled) bitmap.recycle()
            return empty
        }
        val cropWidth = max(1, decodedWidth / 2)
        val cropX = if (side == PAGE_SIDE_FIRST) {
            if (reverse) 0 else decodedWidth - cropWidth
        } else {
            if (reverse) decodedWidth - cropWidth else 0
        }
        val displayBitmap = Bitmap.createBitmap(cropWidth, decodedHeight, displayConfig(bitmap))
        Canvas(displayBitmap).drawBitmap(
            bitmap,
            Rect(cropX, 0, cropX + cropWidth, decodedHeight),
            Rect(0, 0, cropWidth, decodedHeight),
            null
        )
        if (!bitmap.isRecycled) bitmap.recycle()
        return displayBitmap
    }

    private fun deliverAutoSplitSiblingFromDecoded(index: Int, page: PageRef, decoded: Bitmap) {
        if (!autoCut || !page.allowAutoSplit || decoded.isRecycled) return
        val sibling = autoSplitSiblingPage(index, page) ?: return
        if (hasDeliveredBitmap(sibling.first)) return
        if ((pendingDeliveryWidths[sibling.first] ?: 0) > 0) return
        val siblingBitmap = copyAutoSplitBitmap(decoded, sibling.second.side, sibling.second.allowAutoSplit)
            ?: return
        val trimmed = ViewerBitmapTrim.trimBlankVerticalEdges(siblingBitmap, true)
        postPageBounds(sibling.first, sibling.second, trimmed.width, trimmed.height)
        deliverPreparedBitmap(sibling.first, trimmed, true)
        ViewerWarmupManager.logMetric("reader_autosplit_sibling_delivered", sibling.first.toLong())
    }

    private fun autoSplitSiblingPage(index: Int, page: PageRef): Pair<Int, PageRef>? {
        val image = page.image ?: return null
        val siblingSide = if (page.side == PAGE_SIDE_FIRST) PAGE_SIDE_SECOND else PAGE_SIDE_FIRST
        return synchronized(pagesLock) {
            pages.withIndex().firstOrNull { entry ->
                entry.index != index &&
                    entry.value.image == image &&
                    entry.value.sourceIndex == page.sourceIndex &&
                    entry.value.side == siblingSide
            }?.let { it.index to it.value }
        }
    }

    private fun copyAutoSplitBitmap(bitmap: Bitmap, side: Int, allowSplit: Boolean): Bitmap? {
        if (!autoCut || !allowSplit || bitmap.isRecycled) return null
        val decodedWidth = bitmap.width
        val decodedHeight = bitmap.height
        if (!shouldAutoSplit(decodedWidth, decodedHeight)) {
            if (side != PAGE_SIDE_SECOND) return bitmap.copy(displayConfig(bitmap), false)
            return Bitmap.createBitmap(max(1, decodedWidth), 1, displayConfig(bitmap)).apply {
                eraseColor(Color.TRANSPARENT)
            }
        }
        val cropWidth = max(1, decodedWidth / 2)
        val cropX = if (side == PAGE_SIDE_FIRST) {
            if (reverse) 0 else decodedWidth - cropWidth
        } else {
            if (reverse) decodedWidth - cropWidth else 0
        }
        return Bitmap.createBitmap(cropWidth, decodedHeight, displayConfig(bitmap)).also { displayBitmap ->
            Canvas(displayBitmap).drawBitmap(
                bitmap,
                Rect(cropX, 0, cropX + cropWidth, decodedHeight),
                Rect(0, 0, cropWidth, decodedHeight),
                null
            )
        }
    }

    private fun displayConfig(bitmap: Bitmap): Bitmap.Config {
        return Bitmap.Config.ARGB_8888
    }

    private fun shouldAutoSplit(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return width / height.toFloat() >= SPREAD_ASPECT_RATIO
    }

    private fun shouldAutoSplitImage(target: Manga, image: String): Boolean {
        if (!autoCut) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (target.isOnline) {
                val file = ReaderImageCache.cachedFile(appContext, target, image) ?: return false
                BitmapFactory.decodeFile(file.absolutePath, bounds)
            } else {
                decodeLocal(image, bounds)
            }
            shouldAutoSplit(bounds.outWidth, bounds.outHeight)
        } catch (e: Exception) {
            recordIfUnexpected(e)
            false
        }
    }

    private fun shouldDecodeTiles(page: PageRef, file: File, bounds: BitmapFactory.Options): Boolean {
        if (page.manga.seed != 0) return false
        if (!file.isFile) return false
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false
        val estimatedBytes = width.toLong() * height.toLong() * 2L
        return height / width.toFloat() >= TILE_PAGE_ASPECT_RATIO ||
            estimatedBytes >= TILE_PAGE_MIN_ESTIMATED_BYTES
    }

    private fun decodePageTiles(file: File, bounds: BitmapFactory.Options, targetWidth: Int): PageDecodeResult.Tiles {
        val sourceWidth = max(1, bounds.outWidth)
        val sourceHeight = max(1, bounds.outHeight)
        val sample = max(sampleSize(sourceWidth, targetWidth), sampleSizeForByteBudget(sourceWidth, sourceHeight, TILE_PAGE_MAX_BYTES))
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            ?: throw java.io.IOException("Bitmap region decode failed")
        val pendingTiles = ArrayList<PendingTile>()
        val rect = Rect()
        var removedSourceHeight = 0
        var success = false
        try {
            var top = 0
            while (top < sourceHeight) {
                val bottom = minOf(sourceHeight, top + TILE_SOURCE_HEIGHT)
                rect.set(0, top, sourceWidth, bottom)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                }
                val bitmap = decoder.decodeRegion(rect, options)
                    ?: throw java.io.IOException("Bitmap tile decode failed")
                pendingTiles.add(PendingTile(top - removedSourceHeight, bottom - removedSourceHeight, bitmap))
                top = bottom
            }
            success = true
        } finally {
            if (!success) {
                for (tile in pendingTiles) {
                    if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
                }
            }
            decoder.recycle()
        }
        val decodedWidth = max(1, (sourceWidth + sample - 1) / sample)
        val pageHeight = max(1, sourceHeight - removedSourceHeight)
        val tiles = pendingTiles.map { tile ->
            val clampedTop = tile.sourceTop.coerceIn(0, pageHeight - 1)
            val clampedBottom = tile.sourceBottom.coerceIn(clampedTop + 1, pageHeight)
            ReaderTile(
                clampedTop,
                clampedBottom,
                sourceWidth,
                pageHeight,
                tile.bitmap
            )
        }
        return PageDecodeResult.Tiles(sourceWidth, pageHeight, decodedWidth, tiles)
    }

    private fun postPageBounds(index: Int, page: PageRef, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val currentIndex = currentPageIndex(page, index)
        if (currentIndex < 0) return
        sourceWidths[currentIndex] = width
        main.post {
            val latestIndex = currentPageIndex(page, currentIndex)
            if (!cancelled.get() && latestIndex >= 0) {
                listener.onPageBoundsReady(latestIndex, width, height)
            }
        }
    }

    private fun achievableWidth(index: Int, requestedWidth: Int): Int {
        val sourceWidth = sourceWidths[index] ?: return requestedWidth
        val decodeCeiling = achievableWidths[index] ?: Int.MAX_VALUE
        return minOf(requestedWidth, max(1, sourceWidth), max(1, decodeCeiling))
    }

    private fun pageRef(index: Int): PageRef? = synchronized(pagesLock) {
        pages.getOrNull(index)
    }

    private fun currentPageIndex(page: PageRef, fallback: Int): Int = synchronized(pagesLock) {
        pageIndexLocked(page, fallback)
    }

    private fun currentPageIndexForDelivery(page: PageRef, fallback: Int): Int = synchronized(pagesLock) {
        val strict = pageIndexLocked(page, fallback)
        if (strict >= 0) return@synchronized strict
        if (fallback in pages.indices && equivalentPageRefForDelivery(page, pages[fallback])) {
            return@synchronized fallback
        }
        pages.indexOfFirst { equivalentPageRefForDelivery(page, it) }
    }

    private fun pageIndexLocked(page: PageRef, fallback: Int): Int {
        val known = page.pageIndex
        if (known in pages.indices && pages[known] === page) return known
        if (fallback in pages.indices && pages[fallback] === page) return fallback
        return pages.indexOfFirst { it === page }
    }

    private fun pageIndexForDeliveryLocked(delivery: Delivery, fallback: Int): Int {
        val strict = pageIndexLocked(delivery.page, fallback)
        if (strict >= 0) return strict
        if (fallback in pages.indices && equivalentPageRefForDelivery(delivery.page, pages[fallback])) {
            return fallback
        }
        return pages.indexOfFirst { equivalentPageRefForDelivery(delivery.page, it) }
    }

    private fun equivalentPageRefForDelivery(a: PageRef, b: PageRef): Boolean {
        if (a.transitionTitle != null || b.transitionTitle != null) return false
        if (a.sourceIndex != b.sourceIndex || a.side != b.side) return false
        val firstImage = a.image
        val secondImage = b.image
        if (!firstImage.isNullOrBlank() && firstImage == secondImage) return true
        val first = a.manga
        val second = b.manga
        if (first === second) return true
        return first.id == second.id &&
            first.baseMode == second.baseMode &&
            first.titleId == second.titleId &&
            (first.ntkEpisodePath ?: "") == (second.ntkEpisodePath ?: "")
    }

    private fun deliveryAtCurrentIndex(delivery: Delivery): Delivery? {
        val currentIndex = synchronized(pagesLock) {
            pageIndexForDeliveryLocked(delivery, delivery.index)
        }
        if (currentIndex < 0) return null
        if (currentIndex != delivery.index) {
            Log.d(
                TAG,
                "reader_delivery_index_recovered page=${delivery.index},current=$currentIndex," +
                    "source=${delivery.page.sourceIndex},side=${delivery.page.side}"
            )
        }
        return if (currentIndex == delivery.index) delivery else delivery.copy(index = currentIndex)
    }

    fun pageInfo(index: Int): PageInfo? {
        val page = synchronized(pagesLock) { pages.getOrNull(index) } ?: return null
        val transition = page.transitionTitle != null
        return PageInfo(
            manga = page.manga,
            title = page.transitionTitle ?: displayEpisodeTitle(page.manga),
            localPage = if (transition) 0 else max(1, page.localPage),
            totalPages = displayTotalPages(page),
            sourcePageIndex = sourcePageIndex(page),
            side = page.side,
            layoutReady = transition || sourceWidths.containsKey(index) || decodedWidths.containsKey(index),
            transitionCard = transition
        )
    }

    fun containsEpisodeForTest(target: Manga): Boolean = synchronized(pagesLock) {
        containsEpisodeForAppendLocked(target) || containsEpisodeFastLocked(target)
    }

    private fun sourcePageIndex(page: PageRef): Int {
        if (page.transitionTitle != null) return 0
        return page.sourceIndex.coerceAtLeast(0)
    }

    private fun displayTotalPages(page: PageRef): Int {
        if (page.transitionTitle != null) return page.totalPages
        val knownCount = page.manga.ntkImageCount
        if (knownCount > 0) return knownCount
        if (isNtkSource(page.manga, title) && page.totalPages >= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD) return 0
        return page.totalPages
    }

    private fun displayEpisodeTitle(pageManga: Manga): String {
        return pageManga.name?.takeIf { it.isNotBlank() }
            ?: title?.name?.takeIf { it.isNotBlank() }
            ?: manga.name?.takeIf { it.isNotBlank() }
            ?: "회차"
    }

    private fun sameEpisode(a: Manga, b: Manga): Boolean {
        if (a === b) return true
        if (a.id == b.id &&
            a.baseMode == b.baseMode &&
            a.titleId == b.titleId &&
            (a.ntkEpisodePath ?: "") == (b.ntkEpisodePath ?: "")
        ) return true
        val first = imageRepository.imageUrls(a, appContext)
        val second = imageRepository.imageUrls(b, appContext)
        return !first.isNullOrEmpty() && first == second
    }

    private fun episodeAppendKey(target: Manga?): String {
        if (target == null) return ""
        val path = target.ntkEpisodePath?.trim().orEmpty()
        if (path.isNotEmpty()) return "ntk:${target.baseMode}:$path"
        val titleId = target.titleId
        if (target.id > 0 && titleId > 0) return "id:${target.baseMode}:$titleId:${target.id}"
        if (target.id > 0) return "id:${target.baseMode}:${target.id}"
        return ""
    }

    private fun trackDeliveredBitmap(index: Int, bitmap: Bitmap, owned: Boolean) {
        val cleared = ArrayList<BitmapRelease>()
        synchronized(deliveredBitmaps) {
            val previous = deliveredBitmaps.put(index, bitmap)
            if (previous != null && previous !== bitmap && deliveredOwned.remove(index)) {
                cleared.add(BitmapRelease(index, previous, false))
            }
            deliveredTiles.remove(index)?.forEach { tile ->
                cleared.add(BitmapRelease(index, tile.bitmap, false))
            }
            if (owned) deliveredOwned.add(index) else deliveredOwned.remove(index)
            trimDeliveredBudgetLocked(cleared)
        }
        postBitmapReleases(cleared)
    }

    private fun trackDeliveredResult(index: Int, result: PageDecodeResult) {
        when (result) {
            is PageDecodeResult.Full -> trackDeliveredBitmap(index, result.bitmap, true)
            is PageDecodeResult.Tiles -> trackDeliveredTiles(index, result.tiles)
        }
    }

    private fun trackDeliveredTiles(index: Int, tiles: List<ReaderTile>) {
        val cleared = ArrayList<BitmapRelease>()
        synchronized(deliveredBitmaps) {
            val previous = deliveredBitmaps.remove(index)
            if (previous != null && deliveredOwned.remove(index)) {
                cleared.add(BitmapRelease(index, previous, false))
            }
            deliveredTiles.put(index, tiles)?.forEach { tile ->
                cleared.add(BitmapRelease(index, tile.bitmap, false))
            }
            deliveredOwned.add(index)
            trimDeliveredBudgetLocked(cleared)
        }
        postBitmapReleases(cleared)
    }

    private fun postDecodeResult(delivery: Delivery) {
        val currentDelivery = deliveryAtCurrentIndex(delivery)
        if (currentDelivery == null) {
            recycleDecodeResult(delivery.result)
            return
        }
        if (hasDeliveredAtLeast(currentDelivery, currentDelivery.result.width)) {
            ViewerWarmupManager.logMetric("reader_drop_duplicate_before_queue", delivery.result.width.toLong())
            recycleDecodeResult(delivery.result)
            return
        }
        if (isStructurePublishPending() && !shouldDeliverInitialGeneratedDuringStructurePublish(currentDelivery)) {
            deliveryQueue.add(currentDelivery)
            scheduleDeliveryDrain()
            return
        }
        logNtkPagePerf(currentDelivery.index, "decode_ready", "ms=${SystemClock.elapsedRealtime() - currentDelivery.startedAt},width=${currentDelivery.result.width},retain=${currentDelivery.retainWhenBusy}")
        val deliverInitialAnchorNow = shouldDeliverInitialAnchorImmediately(currentDelivery)
        val holdInitialNtkDelivery = shouldHoldInitialNtkDelivery(currentDelivery)
        val primeNearAfterInitialAnchorDelivery = deliverInitialAnchorNow &&
            shouldPrimeNtkNearPagesAfterAnchorDecode(currentDelivery)
        if (!deliverInitialAnchorNow || holdInitialNtkDelivery) {
            prepareDecodeResultForDraw(currentDelivery.result)
        }
        pendingDeliveryWidths.merge(currentDelivery.index, currentDelivery.result.width, ::max)
        if (holdInitialNtkDelivery) {
            if (!storeInitialHeldDelivery(currentDelivery)) return
            Log.d(
                TAG,
                "reader_initial_hold page=${currentDelivery.index},start=${currentStartPage()},width=${currentDelivery.result.width}"
            )
            if (maybePromoteInitialGeneratedStartToHeld(currentDelivery)) {
                val flushPromoted = Runnable {
                    if (!cancelled.get()) {
                        flushInitialHeldDeliveries("promote")
                    }
                }
                if (!mainImmediate.postAtFrontOfQueue(flushPromoted)) {
                    mainImmediate.post(flushPromoted)
                }
                scheduleInitialDeliveryFallback()
                return
            }
            flushInitialViewportIfReady()
            scheduleInitialDeliveryFallback()
            return
        }
        if (deliverInitialAnchorNow) {
            ViewerWarmupManager.logMetric("reader_anchor_delivery_direct", currentDelivery.index.toLong())
            val queuedAt = SystemClock.elapsedRealtime()
            if (primeNearAfterInitialAnchorDelivery) {
                primeNtkNearPagesAfterAnchorDecode(currentDelivery.index)
            }
            val deliverAnchor = Runnable {
                val runStartedAt = SystemClock.elapsedRealtime()
                if (cancelled.get()) {
                    pendingDeliveryWidths.remove(currentDelivery.index)
                    recycleDecodeResult(currentDelivery.result)
                    return@Runnable
                }
                val queueMs = SystemClock.elapsedRealtime() - queuedAt
                if (queueMs > 120L) {
                    Log.d(TAG, "reader_anchor_delivery_queue_delay page=${currentDelivery.index},ms=$queueMs,pagesReady=${initialPagesReadyDelivered.get()}")
                }
                deliverInitialPagesReadyIfNeeded(
                    synchronized(pagesLock) { pages.size },
                    currentStartPage(),
                    true
                )
                deliverDecodeResultOnMain(currentDelivery, false)
                val runMs = SystemClock.elapsedRealtime() - runStartedAt
                if (runMs > 32L) {
                    Log.d(TAG, "reader_anchor_delivery_run_ms page=${currentDelivery.index},ms=$runMs")
                }
            }
            val anchorCoalesceDelayMs = initialAnchorCoalesceDelayMs(currentDelivery)
            if (anchorCoalesceDelayMs > 0L) {
                Log.d(
                    TAG,
                    "reader_initial_anchor_coalesce_delay page=${currentDelivery.index}," +
                        "ms=$anchorCoalesceDelayMs"
                )
            }
            val posted = if (anchorCoalesceDelayMs > 0L) {
                mainImmediate.postDelayed(deliverAnchor, anchorCoalesceDelayMs)
            } else {
                mainImmediate.postAtFrontOfQueue(deliverAnchor)
            }
            if (!posted) {
                pendingDeliveryWidths.remove(currentDelivery.index)
                recycleDecodeResult(currentDelivery.result)
            }
            return
        }
        if (shouldDeliverInitialContinuousImmediately(currentDelivery)) {
            if (!markInitialContinuousDeliveryPosted(currentDelivery)) {
                ViewerWarmupManager.logMetric("reader_initial_continuous_delivery_coalesced", currentDelivery.index.toLong())
                recycleDecodeResult(currentDelivery.result)
                return
            }
            ViewerWarmupManager.logMetric("reader_initial_continuous_delivery_direct", currentDelivery.index.toLong())
            Log.d(
                TAG,
                "reader_initial_continuous_delivery_direct page=${currentDelivery.index}," +
                    "start=${currentStartPage()},width=${currentDelivery.result.width}"
            )
            val queuedAt = SystemClock.elapsedRealtime()
            val deliverInitialContinuous = Runnable {
                val queueMs = SystemClock.elapsedRealtime() - queuedAt
                if (queueMs > 120L) {
                    Log.d(
                        TAG,
                        "reader_initial_continuous_delivery_queue_delay page=${currentDelivery.index}," +
                            "ms=$queueMs,pagesReady=${initialPagesReadyDelivered.get()}"
                    )
                }
                if (cancelled.get()) {
                    pendingDeliveryWidths.remove(currentDelivery.index)
                    initialContinuousPostedWidths.remove(currentDelivery.index)
                    recycleDecodeResult(currentDelivery.result)
                    return@Runnable
                }
                initialContinuousPostedWidths.remove(currentDelivery.index)
                deliverDecodeResultOnMain(currentDelivery, false)
            }
            if (!mainImmediate.postAtFrontOfQueue(deliverInitialContinuous)) {
                pendingDeliveryWidths.remove(currentDelivery.index)
                initialContinuousPostedWidths.remove(currentDelivery.index)
                recycleDecodeResult(currentDelivery.result)
            }
            return
        }
        if (shouldDeliverRetainedImmediately(currentDelivery)) {
            ViewerWarmupManager.logMetric("reader_retained_delivery_direct", currentDelivery.index.toLong())
            val deliverRetained = Runnable {
                if (cancelled.get()) {
                    pendingDeliveryWidths.remove(currentDelivery.index)
                    recycleDecodeResult(currentDelivery.result)
                    return@Runnable
                }
                deliverDecodeResultOnMain(currentDelivery, viewportBusy.get())
            }
            if (!mainImmediate.postAtFrontOfQueue(deliverRetained)) {
                pendingDeliveryWidths.remove(currentDelivery.index)
                recycleDecodeResult(currentDelivery.result)
            }
            return
        }
        deliveryQueue.add(currentDelivery)
        scheduleDeliveryDrain()
    }

    private fun shouldDeliverInitialAnchorImmediately(delivery: Delivery): Boolean {
        if (firstBitmapLogged.get()) return false
        return delivery.index == currentStartPage()
    }

    private fun initialAnchorCoalesceDelayMs(delivery: Delivery): Long {
        if (firstBitmapLogged.get()) return 0L
        if (!isNtkSource(manga, title)) return 0L
        if (isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) return 0L
        val start = currentStartPage()
        if (delivery.index != start) return 0L
        val firstNext = start + 1
        val secondNext = start + 2
        val firstNextReady = initialDeliveryBacklog.containsKey(firstNext) ||
            (pendingDeliveryWidths[firstNext] ?: 0) > 0
        val secondNextReady = initialDeliveryBacklog.containsKey(secondNext) ||
            (pendingDeliveryWidths[secondNext] ?: 0) > 0
        val firstNextLikelySoon = firstNextReady ||
            loading.contains(firstNext) ||
            urgentLoading.contains(firstNext)
        val secondNextLikelySoon = secondNextReady ||
            loading.contains(secondNext) ||
            urgentLoading.contains(secondNext)
        if (resultDrawHeightPx(delivery.result) < max(1, viewerHeight).toFloat() && firstNextLikelySoon) {
            return NTK_INITIAL_SHORT_ANCHOR_VIEWPORT_COALESCE_MS
        }
        if (!firstNextLikelySoon || !secondNextLikelySoon) return 0L
        if (!initialAnchorCoalesceDelayed.compareAndSet(false, true)) return 0L
        val decodeMs = SystemClock.elapsedRealtime() - delivery.startedAt
        return if (!secondNextReady && decodeMs < NTK_INITIAL_ANCHOR_FAST_COALESCE_MAX_DECODE_MS) {
            NTK_INITIAL_ANCHOR_FAST_COALESCE_MS
        } else {
            NTK_INITIAL_ANCHOR_COALESCE_MS
        }
    }

    private fun shouldDeliverInitialGeneratedDuringStructurePublish(delivery: Delivery): Boolean {
        if (!isNtkSource(delivery.page.manga, title)) return false
        if (!firstBitmapLogged.get() && delivery.index == currentStartPage()) return true
        if (!isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) return false
        val start = currentStartPage()
        if (delivery.index !in start until start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        val currentIndex = currentPageIndex(delivery.page, delivery.index)
        return currentIndex == delivery.index
    }

    private fun shouldDeliverInitialContinuousImmediately(delivery: Delivery): Boolean {
        if (!firstBitmapLogged.get()) return false
        if (!isNtkSource(manga, title)) return false
        if (!delivery.retainWhenBusy) return false
        val start = currentStartPage()
        if (delivery.index <= start) return false
        if (delivery.index > start + NTK_INITIAL_DIRECT_DELIVERY_PAGES) return false
        val firstBitmapAt = ntkFirstBitmapAtMs.get()
        if (firstBitmapAt > 0L &&
            SystemClock.uptimeMillis() - firstBitmapAt > NTK_INITIAL_CONTINUOUS_DIRECT_WINDOW_MS
        ) return false
        return true
    }

    private fun markInitialContinuousDeliveryPosted(delivery: Delivery): Boolean {
        while (true) {
            val previous = initialContinuousPostedWidths[delivery.index]
            if (previous == null) {
                if (initialContinuousPostedWidths.putIfAbsent(delivery.index, delivery.result.width) == null) return true
                continue
            }
            return previous < delivery.result.width &&
                initialContinuousPostedWidths.replace(delivery.index, previous, delivery.result.width)
        }
    }

    private fun shouldPrimeNtkNearPagesAfterAnchorDecode(delivery: Delivery): Boolean {
        if (firstBitmapLogged.get()) return false
        if (delivery.index != currentStartPage()) return false
        if (!isNtkSource(delivery.page.manga, title)) return false
        return initialNearAfterAnchorDecodeStarted.compareAndSet(false, true)
    }

    private fun primeNtkNearPagesAfterAnchorDecode(anchor: Int) {
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val last = minOf(count - 1, anchor + NTK_INITIAL_ANCHOR_DECODE_PRIME_PAGES)
        if (last <= anchor) return
        for (index in (anchor + 1)..last) {
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
        }
        Log.d(TAG, "reader_ntk_anchor_decode_prime anchor=$anchor count=${last - anchor}")
        ViewerWarmupManager.logMetric("reader_ntk_anchor_decode_prime", (last - anchor).toLong())
    }

    private fun shouldDeliverRetainedImmediately(delivery: Delivery): Boolean {
        if (!firstBitmapLogged.get()) return false
        if (!isNtkSource(manga, title)) return false
        val retainedFirst: Int
        val retainedLast: Int
        synchronized(deliveredBitmaps) {
            retainedFirst = retainedFirstPage
            retainedLast = retainedLastPage
        }
        return delivery.index in retainedFirst..retainedLast
    }

    private fun shouldHoldInitialNtkDelivery(delivery: Delivery): Boolean {
        return shouldHoldInitialNtkIndex(delivery.index)
    }

    private fun shouldHoldInitialNtkIndex(index: Int): Boolean {
        if (firstBitmapLogged.get()) return false
        if (ntkFirstBitmapAtMs.get() > 0L) return false
        if (!isNtkSource(manga, title)) return false
        val start = currentStartPage()
        if (index == start) return false
        val firstHeld = if (start <= 0) {
            start + 1
        } else {
            max(0, start - NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES)
        }
        return index in firstHeld..minOf(start + NTK_INITIAL_PRIORITY_PAGES, start + 12)
    }

    private fun storeInitialHeldDelivery(delivery: Delivery): Boolean {
        while (true) {
            val previous = initialDeliveryBacklog[delivery.index]
            if (previous == null) {
                if (initialDeliveryBacklog.putIfAbsent(delivery.index, delivery) == null) return true
                continue
            }
            if (previous.result.width >= delivery.result.width) {
                recycleDecodeResult(delivery.result)
                return false
            }
            if (initialDeliveryBacklog.replace(delivery.index, previous, delivery)) {
                recycleDecodeResult(previous.result)
                return true
            }
        }
    }

    private fun maybePromoteInitialGeneratedStartToHeld(delivery: Delivery): Boolean {
        if (firstBitmapLogged.get()) return false
        if (!isNtkSource(manga, title)) return false
        if (!isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) return false
        val start = currentStartPage()
        if (hasActiveOrDeliveredPage(start)) {
            Log.d(
                TAG,
                "reader_initial_generated_start_promote_defer oldStart=$start,newStart=${delivery.index}," +
                    "reason=start_active_or_delivered"
            )
            return false
        }
        if (delivery.index <= start) return false
        if (delivery.index > start + NTK_INITIAL_GENERATED_PROMOTE_MAX_AHEAD) return false
        var promotionIndex = delivery.index
        var promotionDelivery = delivery
        if (delivery.index > start + 1 &&
            (!initialHeldViewportReadyFrom(delivery.index) ||
                !initialPromotedScrollCushionReady(delivery.index))
        ) {
            val alternateIndex = findPromotableInitialGeneratedHeldStart(start)
            if (alternateIndex == null) {
                Log.d(
                    TAG,
                    "reader_initial_generated_start_promote_defer oldStart=$start,newStart=${delivery.index}," +
                        "reason=viewport_cushion,height=${resultDrawHeightPx(delivery.result).toInt()},viewport=$viewerHeight"
                )
                return false
            }
            val alternateDelivery = initialDeliveryBacklog[alternateIndex]?.let { deliveryAtCurrentIndex(it) }
                ?: return false
            promotionIndex = alternateIndex
            promotionDelivery = alternateDelivery
        }
        if (initialDeliveryBacklog.containsKey(start) || hasDeliveredBitmap(start)) return false
        val page = pageRef(promotionIndex) ?: return false
        if (page !== promotionDelivery.page) return false
        if (!promoteResolvedInitialStart(start, promotionIndex)) return false
        clearInitialStaleLoadingBeforePromotedStart(start, promotionIndex)
        Log.d(
            TAG,
            "reader_initial_generated_start_promote oldStart=$start,newStart=$promotionIndex," +
                "image=${promotionDelivery.page.image?.substringAfterLast('/')}"
        )
        ViewerWarmupManager.logMetric("reader_initial_generated_start_promote", promotionIndex.toLong())
        return true
    }

    private fun findPromotableInitialGeneratedHeldStart(oldStart: Int): Int? {
        val last = minOf(oldStart + NTK_INITIAL_GENERATED_PROMOTE_MAX_AHEAD, synchronized(pagesLock) { pages.size - 1 })
        for (index in (oldStart + 1)..last) {
            val delivery = initialDeliveryBacklog[index]?.let { deliveryAtCurrentIndex(it) } ?: continue
            if (!isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) continue
            if (index > oldStart + 1 && !initialPreviousPageReadyForPromotedStart(index)) continue
            if (index == oldStart + 1 ||
                (initialHeldViewportReadyFrom(index) && initialPromotedScrollCushionReady(index))
            ) return index
        }
        return null
    }

    private fun initialPreviousPageReadyForPromotedStart(index: Int): Boolean {
        val previousIndex = index - 1
        if (previousIndex < 0) return true
        if (hasDeliveredBitmap(previousIndex)) return true
        val previousDelivery = initialDeliveryBacklog[previousIndex]?.let { deliveryAtCurrentIndex(it) }
            ?: return false
        if (previousDelivery.index != previousIndex) return false
        return resultDrawHeightPx(previousDelivery.result) > 0f
    }

    private fun clearInitialStaleLoadingBeforePromotedStart(oldStart: Int, newStart: Int) {
        if (newStart <= oldStart) return
        for (index in oldStart until newStart) {
            loading.remove(index)
            loadingPages.remove(index)
            urgentLoading.remove(index)
            urgentLoadingPages.remove(index)
            inFlightWidths.remove(index)
            pendingDeliveryWidths.remove(index)
            if (!hasDeliveredBitmap(index)) {
                main.post {
                    if (!cancelled.get() && !hasDeliveredBitmap(index)) {
                        listener.onPageCleared(index)
                    }
                }
            }
        }
        Log.d(TAG, "reader_initial_promote_clear_stale_loading oldStart=$oldStart,newStart=$newStart")
    }

    private fun promoteResolvedInitialStart(oldStart: Int, newStart: Int): Boolean {
        while (true) {
            val resolved = resolvedInitialStartPage.get()
            val current = if (resolved >= 0) resolved else requestedStartPage()
            if (current != oldStart) return false
            val expected = if (resolved >= 0) resolved else -1
            if (resolvedInitialStartPage.compareAndSet(expected, newStart)) return true
        }
    }

    private fun scheduleInitialDeliveryFallback() {
        if (!initialDeliveryFallbackPosted.compareAndSet(false, true)) return
        mainImmediate.postDelayed({
            initialDeliveryFallbackPosted.set(false)
            if (cancelled.get() || firstBitmapLogged.get()) return@postDelayed
            val start = currentStartPage()
            if (!initialDeliveryBacklog.containsKey(start) && !hasDeliveredBitmap(start)) {
                scheduleInitialDeliveryFallback()
                return@postDelayed
            }
            flushInitialHeldDeliveries("fallback")
        }, NTK_INITIAL_DELIVERY_HOLD_FALLBACK_MS)
    }

    private fun promoteVisiblePendingDelivery(index: Int) {
        if (!isNtkSource(manga, title)) return
        if (hasDeliveredBitmap(index)) return
        val queuedAt = SystemClock.elapsedRealtime()
        val deliverVisible = Runnable {
            if (cancelled.get() || hasDeliveredBitmap(index)) return@Runnable
            val prepared = initialPreparedBacklog.remove(index)
            if (prepared != null) {
                Log.d(
                    TAG,
                    "reader_visible_pending_promote_prepared page=$index," +
                        "ms=${SystemClock.elapsedRealtime() - queuedAt}"
                )
                deliverPreparedBitmap(index, prepared.bitmap, prepared.owned, false)
                return@Runnable
            }
            val held = initialDeliveryBacklog.remove(index)?.let { deliveryAtCurrentIndex(it) }
            if (held != null) {
                Log.d(
                    TAG,
                    "reader_visible_pending_promote_decoded page=${held.index}," +
                        "ms=${SystemClock.elapsedRealtime() - queuedAt},width=${held.result.width}"
                )
                deliverDecodeResultOnMain(held, false)
                return@Runnable
            }
            scheduleDeliveryDrain()
        }
        if (!mainImmediate.postAtFrontOfQueue(deliverVisible)) mainImmediate.post(deliverVisible)
    }

    private fun flushInitialViewportIfReady() {
        if (!isNtkSource(manga, title) || firstBitmapLogged.get()) return
        val start = currentStartPage()
        if (!initialDeliveryBacklog.containsKey(start)) return
        val count = synchronized(pagesLock) { pages.size }
        if (!initialHeldViewportReady(start, count)) return
        mainImmediate.post {
            if (!cancelled.get() && !firstBitmapLogged.get()) {
                flushInitialHeldDeliveries("viewport")
            }
        }
    }

    private fun flushInitialHeldDeliveries(reason: String) {
        if (initialDeliveryBacklog.isEmpty() && initialPreparedBacklog.isEmpty()) return
        if ((reason == "anchor" || reason == "fallback") &&
            isNtkSource(manga, title) &&
            !firstBitmapLogged.get() &&
            !initialStableViewportReadyForFirstFlush()
        ) {
            Log.d(
                TAG,
                "reader_initial_hold_flush_defer reason=$reason,start=${currentStartPage()}," +
                    "backlog=${initialDeliveryBacklog.size},prepared=${initialPreparedBacklog.size}"
            )
            scheduleInitialDeliveryFallback()
            return
        }
        val prepared = initialPreparedBacklog.entries
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val delivery = entry.value
                if (initialPreparedBacklog.remove(entry.key, delivery)) entry.key to delivery else null
            }
        val held = initialDeliveryBacklog.entries
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val delivery = entry.value
                if (initialDeliveryBacklog.remove(entry.key, delivery)) {
                    deliveryAtCurrentIndex(delivery)
                } else {
                    null
                }
            }
        if (held.isEmpty() && prepared.isEmpty()) return
        for ((index, delivery) in prepared) {
            deliverPreparedBitmap(index, delivery.bitmap, delivery.owned, false)
        }
        val deliverHeldNow = (reason == "anchor" || reason == "viewport" || reason == "fallback" || reason == "promote") &&
            Looper.myLooper() == Looper.getMainLooper()
        val immediateHeld = if (deliverHeldNow) initialViewportHeldDeliveries(held) else emptySet()
        if (deliverHeldNow) {
            initialDeliveryFlushInProgress.set(true)
            try {
                for (delivery in held) {
                    if (delivery.index in immediateHeld) {
                        deliverDecodeResultOnMain(delivery, viewportBusy.get())
                    } else {
                        deliveryQueue.add(delivery)
                    }
                }
            } finally {
                initialDeliveryFlushInProgress.set(false)
            }
        } else {
            for (delivery in held) deliveryQueue.add(delivery)
        }
        Log.d(TAG, "reader_initial_hold_flush reason=$reason,prepared=${prepared.size},decoded=${held.size}")
        if (!deliverHeldNow || held.any { it.index !in immediateHeld }) scheduleDeliveryDrain()
    }

    private fun initialStableViewportReadyForFirstFlush(): Boolean {
        val start = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (!initialHeldViewportReady(start, count)) return false
        return initialPromotedScrollCushionReady(start)
    }

    private fun initialHeldViewportReady(start: Int, count: Int): Boolean {
        val held = initialDeliveryBacklog.entries
            .mapNotNull { entry -> deliveryAtCurrentIndex(entry.value) }
            .sortedBy { it.index }
        if (held.isEmpty()) return false
        val byIndex = held.associateBy { it.index }
        var coveredHeight = 0f
        var index = start
        val requiredHeight = max(1, viewerHeight).toFloat()
        while (index < count) {
            val delivery = byIndex[index] ?: return false
            coveredHeight += resultDrawHeightPx(delivery.result)
            if (coveredHeight >= requiredHeight) return true
            if (index >= start + NTK_INITIAL_PRIORITY_PAGES) return true
            index++
        }
        return coveredHeight >= requiredHeight
    }

    private fun initialHeldViewportReadyFrom(startIndex: Int): Boolean {
        val count = synchronized(pagesLock) { pages.size }
        val requiredHeight = max(1, viewerHeight).toFloat()
        var coveredHeight = 0f
        var index = startIndex
        val last = minOf(count - 1, startIndex + NTK_INITIAL_PRIORITY_PAGES)
        while (index <= last) {
            val delivery = initialDeliveryBacklog[index]?.let { deliveryAtCurrentIndex(it) } ?: return false
            if (delivery.index != index) return false
            coveredHeight += resultDrawHeightPx(delivery.result)
            if (coveredHeight >= requiredHeight) return true
            index++
        }
        return coveredHeight >= requiredHeight
    }

    private fun initialPromotedScrollCushionReady(startIndex: Int): Boolean {
        val count = synchronized(pagesLock) { pages.size }
        val last = minOf(count - 1, startIndex + 3)
        for (index in startIndex..last) {
            val delivery = initialDeliveryBacklog[index]?.let { deliveryAtCurrentIndex(it) } ?: return false
            if (delivery.index != index) return false
        }
        return true
    }

    private fun initialViewportHeldDeliveries(held: List<Delivery>): Set<Int> {
        if (held.isEmpty()) return emptySet()
        if (!isNtkSource(manga, title)) return held.mapTo(HashSet()) { it.index }
        val start = currentStartPage()
        val byIndex = held.associateBy { it.index }
        val count = synchronized(pagesLock) { pages.size }
        val requiredHeight = max(1, viewerHeight).toFloat()
        var coveredHeight = 0f
        var promotedPrevious = start
        if (start > 0) {
            val firstPrevious = max(0, start - NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES)
            for (index in firstPrevious until start) {
                if (byIndex.containsKey(index)) {
                    promotedPrevious = index
                    break
                }
            }
        }
        var firstImmediate = promotedPrevious
        var lastImmediate = start
        var index = promotedPrevious
        val anchorAlreadyDelivered = !byIndex.containsKey(start) && hasDeliveredBitmap(start)
        if (anchorAlreadyDelivered) {
            firstImmediate = if (promotedPrevious < start) promotedPrevious else start + 1
            lastImmediate = start
            index = if (promotedPrevious < start) promotedPrevious else start + 1
        }
        while (index < count) {
            val delivery = byIndex[index] ?: break
            coveredHeight += resultDrawHeightPx(delivery.result)
            lastImmediate = index
            if (index < start + NTK_INITIAL_DIRECT_DELIVERY_PAGES) {
                index++
                continue
            }
            if (!anchorAlreadyDelivered && index >= start + 1 && coveredHeight >= requiredHeight) break
            if (index >= start + NTK_INITIAL_PRIORITY_PAGES) break
            index++
        }
        return held.asSequence()
            .map { it.index }
            .filter { it in firstImmediate..lastImmediate }
            .toHashSet()
    }

    private fun resultDrawHeightPx(result: PageDecodeResult): Float {
        val width = max(1, viewerWidth).toFloat()
        return when (result) {
            is PageDecodeResult.Full -> {
                val bitmapWidth = max(1, result.bitmap.width)
                val bitmapHeight = max(1, result.bitmap.height)
                max(1f, width * bitmapHeight / bitmapWidth.toFloat())
            }
            is PageDecodeResult.Tiles -> {
                val pageWidth = max(1, result.pageWidth)
                val pageHeight = max(1, result.pageHeight)
                max(1f, width * pageHeight / pageWidth.toFloat())
            }
        }
    }

    private fun shouldUseForegroundFetch(
        index: Int,
        page: PageRef,
        anchor: Boolean,
        urgent: Boolean,
        busy: Boolean,
        generation: Int
    ): Boolean {
        if (!page.manga.isOnline) return false
        if (anchor || index == currentStartPage()) return true
        val current = currentStartPage()
        val ntk = isNtkSource(page.manga, title)
        if (ntk && !firstBitmapLogged.get()) {
            return urgent &&
                generation == FOREGROUND_PRIME_WARM_GENERATION &&
                ntkCoordinator?.allowsPreAnchorFallback(index, page.image, "shouldUseForegroundFetch") == true
        }
        if (urgent) {
            if (ntk && firstBitmapLogged.get() && busy && generation >= 0) {
                val viewportAnchor = currentViewportAnchor.get().takeIf { it >= 0 } ?: current
                val radius = if (isNtkWebtoonSource(page.manga, title)) {
                    NTK_WEBTOON_ACTIVE_SCROLL_FOREGROUND_RADIUS
                } else {
                    NTK_ACTIVE_SCROLL_FOREGROUND_RADIUS
                }
                return index in max(0, viewportAnchor - radius)..(viewportAnchor + radius)
            }
            return true
        }
        val foregroundAhead = if (ntk && isNtkWebtoonSource(page.manga, title)) {
            NTK_WEBTOON_FOREGROUND_STREAM_AHEAD_PAGES
        } else {
            NTK_FOREGROUND_STREAM_AHEAD_PAGES
        }
        if (generation == FOREGROUND_PRIME_WARM_GENERATION && ntk) {
            val primeAhead = if (isNtkGeneratedFastParse()) {
                maxOf(foregroundAhead, NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
            } else {
                foregroundAhead
            }
            return index <= current + primeAhead
        }
        if (ntk && index > current + foregroundAhead) {
            return false
        }
        if (!urgent && (!busy || generation == PRIME_WARM_GENERATION)) return false
        val image = page.image
        val requestImage = image ?: return false
        return !ReaderImageCache.hasActiveFetch(page.manga, requestImage)
    }

    private fun shouldPostPageLoadingState(
        index: Int,
        page: PageRef,
        busy: Boolean,
        anchor: Boolean,
        generation: Int
    ): Boolean {
        if (!isNtkSource(page.manga, title)) return true
        if (!firstBitmapLogged.get()) return true
        if (!busy || generation < 0) return true
        if (anchor) return true
        return index == currentViewportAnchor.get()
    }

    private fun hasDeliveredAtLeast(delivery: Delivery, width: Int): Boolean {
        val currentIndex = synchronized(pagesLock) {
            pageIndexLocked(delivery.page, delivery.index)
        }
        if (currentIndex < 0) return false
        val deliveredWidth = decodedWidths[currentIndex] ?: 0
        return deliveredWidth >= width && hasDeliveredBitmap(currentIndex)
    }

    private fun prepareDecodeResultForDraw(result: PageDecodeResult) {
        when (result) {
            is PageDecodeResult.Full -> prepareBitmapForDraw(result.bitmap)
            is PageDecodeResult.Tiles -> result.tiles.forEach { prepareBitmapForDraw(it.bitmap) }
        }
    }

    private fun prepareBitmapForDraw(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        try {
            bitmap.prepareToDraw()
        } catch (_: Throwable) {
            // Best-effort GPU upload hint; correctness does not depend on it.
        }
    }

    private fun scheduleDeliveryDrain() {
        if (deliveryDrainPosted.compareAndSet(false, true)) {
            val delayMs = deliveryDrainDelayMs()
            if (delayMs > 0L) main.postDelayed(deliveryDrainRunnable, delayMs) else main.post(deliveryDrainRunnable)
        }
    }

    private fun drainDecodeDeliveries() {
        MainThreadStallMonitor.trace("reader_drain_decode_deliveries") {
            deliveryDrainPosted.set(false)
            if (cancelled.get()) {
                recycleQueuedDeliveries()
                return@trace
            }
            if (isStructurePublishPending()) {
                if (deliveryQueue.isNotEmpty() && deliveryDrainPosted.compareAndSet(false, true)) {
                    main.postDelayed(deliveryDrainRunnable, STRUCTURE_PUBLISH_DRAIN_DELAY_MS)
                }
                return@trace
            }
            enqueueRetainedPrimedDeliveries()
            val busy = viewportBusy.get()
            if (busy) {
                deliverBusyDecodeResults()
                if (deliveryQueue.isNotEmpty() && deliveryDrainPosted.compareAndSet(false, true)) {
                    main.postDelayed(deliveryDrainRunnable, BUSY_DELIVERY_DRAIN_DELAY_MS)
                }
                return@trace
            }
            val delayMs = deliveryDrainDelayMs()
            if (delayMs > 0L) {
                if (deliveryQueue.isNotEmpty() && deliveryDrainPosted.compareAndSet(false, true)) {
                    main.postDelayed(deliveryDrainRunnable, delayMs)
                }
                return@trace
            }
            val maxDeliveries = IDLE_DELIVERY_DRAIN_LIMIT
            var deliveredCount = 0
            while (deliveredCount < maxDeliveries) {
                val delivery = deliveryQueue.poll() ?: break
                deliverDecodeResultOnMain(delivery, busy)
                deliveredCount++
            }
            if (deliveryQueue.isNotEmpty() && deliveryDrainPosted.compareAndSet(false, true)) {
                if (viewportBusy.get()) {
                    main.postDelayed(deliveryDrainRunnable, BUSY_DELIVERY_DRAIN_DELAY_MS)
                } else {
                    main.postDelayed(deliveryDrainRunnable, IDLE_DELIVERY_FRAME_DELAY_MS)
                }
            }
        }
    }

    private fun deliveryDrainDelayMs(): Long {
        if (viewportBusy.get()) return BUSY_DELIVERY_DRAIN_DELAY_MS
        val resumeAt = deliveryResumeAtMs.get()
        val now = SystemClock.uptimeMillis()
        val idleDelay = if (resumeAt == Long.MAX_VALUE) IDLE_DELIVERY_RESUME_DELAY_MS else max(0L, resumeAt - now)
        val inputDelay = max(0L, lastUserInteractionMs.get() + INPUT_PRIORITY_QUIET_MS - now)
        return max(idleDelay, inputDelay)
    }

    private fun deliverBusyDecodeResults() {
        enqueueRetainedPrimedDeliveries()
        val retainedFirst: Int
        val retainedLast: Int
        val retainedAnchor: Int
        synchronized(deliveredBitmaps) {
            retainedFirst = retainedFirstPage
            retainedLast = retainedLastPage
            retainedAnchor = retainedAnchorPage
        }
        val retained = ArrayList<Delivery>(BUSY_DELIVERY_SCAN_LIMIT)
        var checked = 0
        while (checked < BUSY_DELIVERY_SCAN_LIMIT) {
            val delivery = deliveryQueue.poll() ?: break
            checked++
            val currentDelivery = deliveryAtCurrentIndex(delivery)
            if (currentDelivery == null) {
                recycleDecodeResult(delivery.result)
                continue
            }
            val index = currentDelivery.index
            if (index in retainedFirst..retainedLast) {
                retained.add(currentDelivery)
            } else if (currentDelivery.retainWhenBusy) {
                primedDeliveryBacklog[index] = currentDelivery
            } else {
                pendingDeliveryWidths.remove(index)
                recycleDecodeResult(currentDelivery.result)
            }
        }
        retained.sortWith(
            compareBy<Delivery> { abs(it.index - retainedAnchor) }
                .thenBy { it.startedAt }
        )
        var deliveredCount = 0
        for (delivery in retained) {
            if (deliveredCount < BUSY_DELIVERY_DRAIN_LIMIT) {
                deliverDecodeResultOnMain(delivery, true)
                deliveredCount++
            } else {
                deliveryQueue.add(delivery)
            }
        }
    }

    private fun enqueueRetainedPrimedDeliveries() {
        if (primedDeliveryBacklog.isEmpty()) return
        val retainedFirst: Int
        val retainedLast: Int
        synchronized(deliveredBitmaps) {
            retainedFirst = retainedFirstPage
            retainedLast = retainedLastPage
        }
        for ((key, delivery) in primedDeliveryBacklog.entries) {
            val currentDelivery = deliveryAtCurrentIndex(delivery)
            if (currentDelivery == null) {
                if (primedDeliveryBacklog.remove(key, delivery)) recycleDecodeResult(delivery.result)
                continue
            }
            val index = currentDelivery.index
            if (index in retainedFirst..retainedLast && primedDeliveryBacklog.remove(key, delivery)) {
                deliveryQueue.add(currentDelivery)
            } else if (index != key && primedDeliveryBacklog.remove(key, delivery)) {
                primedDeliveryBacklog[index] = currentDelivery
            }
        }
    }

    private fun deliverDecodeResultOnMain(delivery: Delivery, busy: Boolean) {
        val currentDelivery = deliveryAtCurrentIndex(delivery)
        if (currentDelivery == null) {
            recycleDecodeResult(delivery.result)
            return
        }
        if (cancelled.get()) {
            pendingDeliveryWidths.remove(currentDelivery.index)
            recycleDecodeResult(currentDelivery.result)
            return
        }
        if (isStructurePublishPending() && !shouldDeliverInitialGeneratedDuringStructurePublish(currentDelivery)) {
            deliveryQueue.add(currentDelivery)
            scheduleDeliveryDrain()
            return
        }
        val retainedFirst: Int
        val retainedLast: Int
        synchronized(deliveredBitmaps) {
            retainedFirst = retainedFirstPage
            retainedLast = retainedLastPage
        }
        val knownIndex = currentDelivery.index
        val allowBusyGeneratedDelivery = shouldDeliverBusyGeneratedOutsideRetained(currentDelivery, knownIndex)
        if (busy && knownIndex !in retainedFirst..retainedLast && !allowBusyGeneratedDelivery) {
            pendingDeliveryWidths.remove(currentDelivery.index)
            recycleDecodeResult(currentDelivery.result)
            return
        }
        var droppedLowerWidth = false
        val currentIndex = synchronized(pagesLock) {
            val index = pageIndexForDeliveryLocked(currentDelivery, knownIndex)
            if (index >= 0 &&
                (!busy || index in retainedFirst..retainedLast || shouldDeliverBusyGeneratedOutsideRetained(currentDelivery, index))
            ) {
                val deliveredWidth = decodedWidths[index] ?: 0
                if (deliveredWidth >= currentDelivery.result.width && hasDeliveredBitmap(index)) {
                    droppedLowerWidth = true
                } else {
                    decodedWidths[index] = max(deliveredWidth, currentDelivery.result.width)
                    val fullWidth = achievableWidth(index, targetWidth(false))
                    val shouldUpgradeRetainedLowRes =
                        index in retainedFirst..retainedLast &&
                        currentDelivery.result.width < fullWidth
                    if (shouldUpgradeRetainedLowRes) {
                        scheduleIdleFullWidthUpgrade(index, fullWidth)
                    }
                    desiredWidths[index] = max(desiredWidths[index] ?: 0, currentDelivery.requestedWidth)
                    if (currentDelivery.result is PageDecodeResult.Tiles) {
                        if (currentDelivery.result.decodedWidth < currentDelivery.requestedWidth) {
                            achievableWidths[index] = max(achievableWidths[index] ?: 0, currentDelivery.result.decodedWidth)
                        }
                    }
                    trackDeliveredResult(index, currentDelivery.result)
                }
            }
            index
        }
        if (currentIndex < 0 ||
            (busy && currentIndex !in retainedFirst..retainedLast &&
                !shouldDeliverBusyGeneratedOutsideRetained(currentDelivery, currentIndex))
        ) {
            pendingDeliveryWidths.remove(currentDelivery.index)
            recycleDecodeResult(currentDelivery.result)
            return
        }
        if (droppedLowerWidth) {
            ViewerWarmupManager.logMetric("reader_drop_stale_lower_width", currentDelivery.result.width.toLong())
            pendingDeliveryWidths.remove(currentDelivery.index)
            recycleDecodeResult(currentDelivery.result)
            retryPendingWidthIfNeeded(currentIndex)
            return
        }
        pendingDeliveryWidths.remove(currentDelivery.index)
        failedPages.remove(currentDelivery.index)
        failedPages.remove(currentIndex)
        transientGeneratedRetries.remove(currentDelivery.index)
        transientGeneratedRetries.remove(currentIndex)
        initialContinuousPostedWidths.remove(currentDelivery.index)
        initialContinuousPostedWidths.remove(currentIndex)
        deliverInitialPagesReadyForCurrentPagesIfNeeded()
        logFirstBitmapIfNeeded(currentDelivery.startedAt)
        when (val result = currentDelivery.result) {
            is PageDecodeResult.Full -> listener.onPageReady(currentIndex, result.bitmap)
            is PageDecodeResult.Tiles -> listener.onPageTilesReady(currentIndex, result.pageWidth, result.pageHeight, result.tiles)
        }
        listenerDrawableDeliveries.add(currentIndex)
        ntkCoordinator?.markFirstDrawableCommitted(currentIndex)
        if (currentIndex == currentStartPage() && !initialDeliveryFlushInProgress.get()) {
            flushInitialHeldDeliveries("anchor")
        }
        main.post { releaseInitialFanoutIfAnchorReady(currentIndex) }
        retryPendingWidthIfNeeded(currentIndex)
    }

    private fun shouldDeliverBusyGeneratedOutsideRetained(delivery: Delivery, index: Int): Boolean {
        if (!delivery.retainWhenBusy) return false
        if (!isNtkSource(delivery.page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) return false
        val start = currentStartPage()
        return index in start..(start + NTK_INITIAL_DIRECT_DELIVERY_PAGES)
    }

    private fun scheduleIdleFullWidthUpgrade(index: Int, width: Int) {
        if (!idleFullWidthUpgradeScheduled.add(index)) return
        main.postDelayed({
            idleFullWidthUpgradeScheduled.remove(index)
            if (cancelled.get()) return@postDelayed
            val quietMs = readerQuietRemainingMs(LOW_RES_UPGRADE_QUIET_MS)
            if (quietMs > 0L) {
                scheduleIdleFullWidthUpgrade(index, width)
                return@postDelayed
            }
            if (!isRetainedPage(index)) return@postDelayed
            ViewerWarmupManager.logMetric("busy_to_idle_upgrade_retry", width.toLong())
            rememberDesiredWidth(index, width)
            requestPage(index, busy = false, anchor = false, generation = windowGeneration.get())
        }, LOW_RES_UPGRADE_QUIET_MS)
    }

    private fun readerQuietRemainingMs(requiredQuietMs: Long): Long {
        if (viewportBusy.get()) return requiredQuietMs
        val lastInteraction = lastUserInteractionMs.get()
        if (lastInteraction <= 0L) return 0L
        val quietFor = SystemClock.uptimeMillis() - lastInteraction
        return (requiredQuietMs - quietFor).coerceAtLeast(0L)
    }

    private fun recycleQueuedDeliveries() {
        while (true) {
            val delivery = deliveryQueue.poll() ?: break
            pendingDeliveryWidths.remove(delivery.index)
            recycleDecodeResult(delivery.result)
        }
        for ((_, delivery) in primedDeliveryBacklog) {
            pendingDeliveryWidths.remove(delivery.index)
            recycleDecodeResult(delivery.result)
        }
        primedDeliveryBacklog.clear()
        for ((_, delivery) in initialDeliveryBacklog) {
            pendingDeliveryWidths.remove(delivery.index)
            recycleDecodeResult(delivery.result)
        }
        initialDeliveryBacklog.clear()
        for ((_, delivery) in initialPreparedBacklog) {
            if (delivery.owned && !delivery.bitmap.isRecycled) recycleBitmapAsync(delivery.bitmap)
        }
        initialPreparedBacklog.clear()
    }

    private fun recycleDecodeResult(result: PageDecodeResult) {
        when (result) {
            is PageDecodeResult.Full -> recycleBitmapAsync(result.bitmap)
            is PageDecodeResult.Tiles -> result.tiles.forEach { recycleBitmapAsync(it.bitmap) }
        }
    }

    private fun releaseDeliveredBitmaps() {
        val toRecycle = ArrayList<Bitmap>()
        synchronized(deliveredBitmaps) {
            for ((index, bitmap) in deliveredBitmaps) {
                if (deliveredOwned.contains(index) && !bitmap.isRecycled) {
                    toRecycle.add(bitmap)
                }
            }
            deliveredBitmaps.clear()
            for (tiles in deliveredTiles.values) {
                for (tile in tiles) {
                    if (!tile.bitmap.isRecycled) toRecycle.add(tile.bitmap)
                }
            }
            deliveredTiles.clear()
            deliveredOwned.clear()
            retainedFirstPage = 0
            retainedLastPage = 0
        }
        for (bitmap in toRecycle) {
            if (cancelled.get()) recycleBitmapAsync(bitmap) else recycleBitmapAfterDelay(bitmap)
        }
    }

    private fun evictDeliveredBitmaps(first: Int, last: Int) {
        val cleared = ArrayList<BitmapRelease>()
        synchronized(deliveredBitmaps) {
            val iterator = deliveredBitmaps.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key < first || entry.key > last) {
                    val owned = deliveredOwned.remove(entry.key)
                    cleared.add(BitmapRelease(entry.key, if (owned) entry.value else null, true))
                    iterator.remove()
                }
            }
            val tileIterator = deliveredTiles.entries.iterator()
            while (tileIterator.hasNext()) {
                val entry = tileIterator.next()
                if (entry.key < first || entry.key > last) {
                    val owned = deliveredOwned.remove(entry.key)
                    if (owned) {
                        for (tile in entry.value) cleared.add(BitmapRelease(entry.key, tile.bitmap, true))
                    } else {
                        cleared.add(BitmapRelease(entry.key, null, true))
                    }
                    tileIterator.remove()
                }
            }
            trimDeliveredBudgetLocked(cleared)
        }
        postBitmapReleases(cleared)
    }

    private fun trimDeliveredBudgetLocked(cleared: MutableList<BitmapRelease>) {
        while (deliveredBitmapBytesLocked() > ACTIVE_BITMAP_BYTES) {
            val iterator = deliveredBitmaps.entries.iterator()
            var trimmed = false
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key in retainedFirstPage..retainedLastPage) continue
                val owned = deliveredOwned.remove(entry.key)
                cleared.add(BitmapRelease(entry.key, if (owned) entry.value else null, true))
                iterator.remove()
                trimmed = true
                break
            }
            if (!trimmed) {
                val tileIterator = deliveredTiles.entries.iterator()
                while (tileIterator.hasNext()) {
                    val entry = tileIterator.next()
                    if (entry.key in retainedFirstPage..retainedLastPage) continue
                    val owned = deliveredOwned.remove(entry.key)
                    if (owned) {
                        for (tile in entry.value) cleared.add(BitmapRelease(entry.key, tile.bitmap, true))
                    } else {
                        cleared.add(BitmapRelease(entry.key, null, true))
                    }
                    tileIterator.remove()
                    trimmed = true
                    break
                }
            }
            if (!trimmed) return
        }
    }

    private fun deliveredBitmapBytesLocked(): Long {
        var total = 0L
        for (bitmap in deliveredBitmaps.values) {
            if (!bitmap.isRecycled) total += bitmapBytes(bitmap).toLong()
        }
        for (tiles in deliveredTiles.values) {
            for (tile in tiles) {
                if (!tile.bitmap.isRecycled) total += bitmapBytes(tile.bitmap).toLong()
            }
        }
        return total
    }

    private fun postBitmapReleases(releases: List<BitmapRelease>) {
        if (releases.isEmpty()) return
        val clearedPages = LinkedHashSet<Int>()
        for (release in releases) {
            if (release.clearPage) {
                decodedWidths.remove(release.index)
                desiredWidths.remove(release.index)
                sourceWidths.remove(release.index)
                achievableWidths.remove(release.index)
                clearedPages.add(release.index)
            }
            release.bitmap?.let { recycleBitmapAfterDelay(it) }
        }
        if (clearedPages.isNotEmpty()) {
            main.post {
                if (!cancelled.get()) {
                    for (index in clearedPages) listener.onPageCleared(index)
                }
            }
        }
    }

    private fun recycleBitmapAfterDelay(bitmap: Bitmap) {
        main.postDelayed({
            if (cancelled.get()) {
                recycleBitmapAsync(bitmap)
            } else if (viewportBusy.get() || deliveryDrainDelayMs() > 0L) {
                recycleBitmapAfterDelay(bitmap)
            } else {
                recycleBitmapAsync(bitmap)
            }
        }, REPLACED_BITMAP_RECYCLE_DELAY_MS)
    }

    private fun recycleBitmapAsync(bitmap: Bitmap) {
        try {
            cleanup.execute {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (_: RuntimeException) {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun bitmapBytes(bitmap: Bitmap): Int {
        return try {
            bitmap.allocationByteCount
        } catch (_: Exception) {
            bitmap.byteCount
        }
    }

    private fun decodeLocal(image: String, options: BitmapFactory.Options): Bitmap? {
        val uri = Uri.parse(image)
        if (!uri.scheme.isNullOrEmpty()) {
            return appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
        return BitmapFactory.decodeFile(image, options)
    }

    private fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return max(1, sample)
    }

    private fun sampleSizeForByteBudget(sourceWidth: Int, sourceHeight: Int, maxBytes: Long): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxBytes <= 0L) return 1
        var sample = 1
        while (estimatedRgb565Bytes(sourceWidth, sourceHeight, sample) > maxBytes) sample *= 2
        return sample
    }

    private fun estimatedRgb565Bytes(width: Int, height: Int, sample: Int): Long {
        val decodedWidth = max(1, (width + sample - 1) / sample)
        val decodedHeight = max(1, (height + sample - 1) / sample)
        return decodedWidth.toLong() * decodedHeight.toLong() * 2L
    }

    private fun targetWidth(busy: Boolean): Int {
        val width = max(1, viewerWidth)
        return if (busy) minOf(width, ReaderPipelinePolicy.BUSY_DECODE_WIDTH) else width
    }

    private fun windowOrder(first: Int, last: Int, anchor: Int, direction: Int): List<Int> {
        val result = ArrayList<Int>(last - first + 1)
        fun add(index: Int) {
            if (index in first..last && !result.contains(index)) result.add(index)
        }
        val safeAnchor = anchor.coerceIn(first, last)
        add(safeAnchor)
        var distance = 1
        while (result.size < last - first + 1) {
            if (direction >= 0) {
                add(safeAnchor + distance)
                add(safeAnchor - distance)
            } else {
                add(safeAnchor - distance)
                add(safeAnchor + distance)
            }
            distance++
        }
        return result
    }

    private fun forwardBiasedWindowOrder(first: Int, last: Int, anchor: Int): List<Int> {
        val result = ArrayList<Int>(last - first + 1)
        fun add(index: Int) {
            if (index in first..last && !result.contains(index)) result.add(index)
        }
        val safeAnchor = anchor.coerceIn(first, last)
        add(safeAnchor)
        for (index in (safeAnchor + 1)..last) add(index)
        for (index in (safeAnchor - 1) downTo first) add(index)
        return result
    }

    private fun trimDecodedWidth(anchor: Int, busy: Boolean) {
        if (!busy) return
        val keepFirst = max(0, anchor - ReaderPipelinePolicy.BUSY_WINDOW_BEFORE)
        val keepLast = anchor + ReaderPipelinePolicy.BUSY_WINDOW_AFTER
        decodedWidths.keys.removeAll { (it < keepFirst || it > keepLast) && !hasDeliveredBitmap(it) }
        desiredWidths.keys.removeAll { it < keepFirst || it > keepLast }
    }

    private fun attachTitle() {
        if (title != null) {
            manga.title = title
            manga.titleId = title.id
        }
    }

    private fun isWfwfSource(manga: Manga?, title: Title?): Boolean {
        val source = (title?.sourceSite ?: manga?.title?.sourceSite ?: "")
            .trim()
            .lowercase(java.util.Locale.ROOT)
        return source == "wfwf" ||
            (source.isBlank() && !ml.melun.mangaview.MainApplication.getHttpClient().isNtk)
    }

    private fun isNtkSource(manga: Manga?, title: Title?): Boolean {
        val source = (title?.sourceSite ?: manga?.title?.sourceSite ?: "")
            .trim()
            .lowercase(java.util.Locale.ROOT)
        return source == "ntk" ||
            (source.isBlank() && ml.melun.mangaview.MainApplication.getHttpClient().isNtk)
    }

    private fun isNtkWebtoonSource(manga: Manga?, title: Title?): Boolean {
        if (!isNtkSource(manga, title)) return false
        return title?.baseMode == MTitle.base_webtoon ||
            manga?.baseMode == MTitle.base_webtoon ||
            manga?.title?.baseMode == MTitle.base_webtoon
    }

    private fun syncNtkTitlePathFromEpisode(title: Title?, episode: Manga?): Boolean {
        if (title == null || episode == null) return false
        if (!isNtkSource(episode, title)) return false
        val path = episode.ntkEpisodePath?.trim().orEmpty()
        if (path.isEmpty()) return false
        return title.applyNtkTitlePathFromEpisodePath(path)
    }

    private fun shouldRefreshNtkGeneratedAppendUrls(urls: List<String>): Boolean {
        if (urls.size <= NTK_GENERATED_APPEND_REFRESH_MIN_COUNT) return false
        val first = urls.firstOrNull()?.lowercase(java.util.Locale.ROOT) ?: return false
        val last = urls.lastOrNull()?.lowercase(java.util.Locale.ROOT) ?: return false
        if (!first.contains("://i.toonflix.app/")) return false
        if (!first.contains("/manhwa/") && !first.contains("/webtoon/")) return false
        val firstMatch = NTK_GENERATED_PAGE_URL.find(first) ?: return false
        val lastMatch = NTK_GENERATED_PAGE_URL.find(last) ?: return false
        return firstMatch.groupValues.getOrNull(1) == "001" &&
            lastMatch.groupValues.getOrNull(1)?.toIntOrNull()?.let { it > NTK_GENERATED_APPEND_REFRESH_MIN_COUNT } == true
    }

    private fun requestedStartPage(): Int {
        if (startAtFirstPage) return 0
        val page = if (manga.useBookmark() && ml.melun.mangaview.MainApplication.p != null) {
            ml.melun.mangaview.MainApplication.p.getViewerBookmark(manga)
        } else {
            0
        }
        return max(0, page)
    }

    private fun currentStartPage(): Int {
        val resolved = resolvedInitialStartPage.get()
        return if (resolved >= 0) resolved else requestedStartPage()
    }

    private fun requestedStartSide(): Int {
        if (!autoCut || ml.melun.mangaview.MainApplication.p == null) return PAGE_SIDE_FIRST
        return if (ml.melun.mangaview.MainApplication.p.getViewerBookmarkSide(manga) == PAGE_SIDE_SECOND) {
            PAGE_SIDE_SECOND
        } else {
            PAGE_SIDE_FIRST
        }
    }

    private fun postMessage(message: String) {
        main.post { if (!cancelled.get()) listener.onMessage(message) }
    }

    private fun postInitialPageError(message: String) {
        if (!pagesInstalled.compareAndSet(false, true)) {
            postMessage(message)
            return
        }
        resolvedInitialStartPage.set(0)
        main.post {
            if (!cancelled.get()) {
                deliverInitialPagesReadyIfNeeded(1, 0, true)
                listener.onPageError(0, message)
            }
        }
    }

    private fun deliverInitialPagesReadyForCurrentPagesIfNeeded() {
        if (initialPagesReadyDelivered.get() || cancelled.get()) return
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        deliverInitialPagesReadyIfNeeded(count, currentStartPage(), true)
    }

    private fun deliverInitialPagesReadyIfNeeded(count: Int, startPage: Int, notifyInitialPage: Boolean) {
        if (count <= 0 || cancelled.get()) return
        if (!initialPagesReadyDelivered.compareAndSet(false, true)) return
        val startedAt = SystemClock.elapsedRealtime()
        listener.onPagesReady(count)
        val pagesReadyMs = SystemClock.elapsedRealtime() - startedAt
        if (notifyInitialPage && startPage >= 0) listener.onInitialPage(startPage)
        val totalMs = SystemClock.elapsedRealtime() - startedAt
        if (totalMs > 32L) {
            Log.d(
                TAG,
                "reader_initial_pages_ready_deliver_ms count=$count,start=$startPage," +
                    "pagesReadyMs=$pagesReadyMs,totalMs=$totalMs,notifyInitialPage=$notifyInitialPage"
            )
        }
    }

    private fun retryTransientNtkGeneratedPageError(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val image = page.image ?: return false
        if (!isNtkGeneratedImageUrl(image)) return false
        if (!isTransientNtkGeneratedImageError(e)) return false
        if (pageRef(index) != page || cancelled.get()) return true
        val attempt = transientGeneratedRetries.merge(index, 1) { previous, _ -> previous + 1 } ?: 1
        if (attempt > NTK_GENERATED_TRANSIENT_RETRY_ATTEMPTS) {
            transientGeneratedRetries.remove(index)
            return false
        }
        failedPages.remove(index)
        decodedWidths.remove(index)
        desiredWidths.remove(index)
        pendingDeliveryWidths.remove(index)
        sourceWidths.remove(index)
        achievableWidths.remove(index)
        inFlightWidths.remove(index)
        loading.remove(index)
        urgentLoading.remove(index)
        loadingPages.remove(index)
        urgentLoadingPages.remove(index)
        val generatedForegroundTransient = e.message.orEmpty().let { message ->
            message.contains("Partial foreground image response is not cacheable", ignoreCase = true) ||
                message.contains("Connection reset", ignoreCase = true) ||
                message.contains("stream was reset", ignoreCase = true) ||
                message.contains("unexpected end of stream", ignoreCase = true) ||
                message.contains("foreground image race failed", ignoreCase = true)
        }
        val delayMs = if (generatedForegroundTransient) {
            if (ReaderImageCache.hasActiveInitialNtkGeneratedFetch(page.manga, image)) {
                NTK_GENERATED_ACTIVE_FETCH_RETRY_DELAY_MS * attempt
            } else {
                NTK_GENERATED_PARTIAL_RETRY_DELAY_MS * attempt
            }
        } else {
            NTK_GENERATED_TRANSIENT_RETRY_DELAY_MS * attempt
        }
        Log.d(
            TAG,
            "retry_transient_generated_page_error index=$index,source=${page.sourceIndex},attempt=$attempt," +
                "delayMs=$delayMs,activeInitialFetch=${ReaderImageCache.hasActiveInitialNtkGeneratedFetch(page.manga, image)}," +
                "path=${page.manga.ntkEpisodePath},image=$image,error=${e.javaClass.simpleName}:${e.message}"
        )
        main.postDelayed({
            if (!cancelled.get() && pageRef(index) == page) {
                requestPage(index, busy = true, anchor = false, generation = PRIME_WARM_GENERATION)
            }
        }, delayMs)
        return true
    }

    private fun postCaptchaRequired(target: Manga) {
        main.post { if (!cancelled.get()) listener.onCaptchaRequired(target) }
    }

    private fun postBoundaryAppendFinished(anchor: Int, direction: Int, silent: Boolean, suppressedCaptcha: Boolean) {
        main.post { if (!cancelled.get()) listener.onBoundaryAppendFinished(anchor, direction, silent, suppressedCaptcha) }
    }

    private companion object {
        private const val TAG = "ViewerPerf"
        private val GSON = Gson()
        private const val PREPARED_BITMAP_RELEASE_DELAY_MS = 12000L
        private const val PRIME_WARM_GENERATION = Int.MIN_VALUE
        private const val FOREGROUND_PRIME_WARM_GENERATION = Int.MIN_VALUE + 1
        private const val URGENT_VISIBLE_PIPELINE_PARALLELISM = 8
        private const val PRIME_PIPELINE_PARALLELISM = 8
        private const val ADJACENT_PIPELINE_PARALLELISM = 3
        private const val NTK_FOREGROUND_PRIME_HEDGE_DELAY_MS = 1400L
        private const val NTK_VISIBLE_GENERATED_BYTE_HEDGE_DELAY_MS = 0L
        private const val NTK_PRE_ANCHOR_FALLBACK_RETRY_MS = 60L
        private const val NTK_PRE_ANCHOR_FALLBACK_RETRY_MAX_MS = 600L
        private const val NTK_PRE_ANCHOR_FALLBACK_MAX_AHEAD = 8
        private const val NTK_PRE_ANCHOR_VERIFIED_GENERATED_AHEAD = 18
        private const val NTK_EARLY_URL_HANDOFF_WAIT_MS = 4200L
        private const val NTK_EARLY_URL_LATE_HANDOFF_WAIT_MS = 30000L
        private const val NTK_EARLY_URL_POLL_MS = 16L
        private const val NTK_EARLY_URL_EXPANSION_WAIT_MS = 1200L
        private const val NTK_EARLY_GENERATED_EXPAND_AFTER_FIRST_BITMAP_WAIT_MS = 5000L
        private const val NTK_EARLY_GENERATED_EXPAND_BEFORE_FIRST_BITMAP_WAIT_MS = 10000L
        private const val NTK_BOARD_ONLY_GENERATED_GRACE_MS = 1400L
        private const val NTK_CANONICAL_WEBTOON_APPEND_MIN_WORK_ID = 800000L
        private fun ntkEarlyUrlMinCount(): Int = 1
        private const val STRUCTURE_PUBLISH_DRAIN_DELAY_MS = 16L
        private const val PRIME_FORWARD_EPISODES = 8
        private const val NTK_PRIME_FORWARD_EPISODES = 0
        private const val NTK_GENERATED_FORWARD_PRIME_AFTER_FIRST_BITMAP_DELAY_MS = 700L
        private const val NTK_NATIVE_FORWARD_PRIME_AFTER_FIRST_BITMAP_DELAY_MS = 1200L
        private const val NTK_PRIMED_EPISODE_DECODE_AHEAD_PAGES = 24
        private const val NTK_PRIMED_EPISODE_PRIORITY_PAGES = 12
        private const val NTK_PRIMED_EPISODE_BYTE_AHEAD_PAGES = 48
        private const val NTK_LIGHT_PRIMED_EPISODE_DECODE_AHEAD_PAGES = 14
        private const val NTK_LIGHT_PRIMED_EPISODE_BYTE_AHEAD_PAGES = 28
        private const val NTK_PREPENDED_EPISODE_DECODE_AHEAD_PAGES = 8
        private const val NTK_PREPENDED_EPISODE_BYTE_AHEAD_PAGES = 16
        private const val NTK_PREPENDED_EPISODE_START_DECODE_PAGES = 4
        private const val NTK_PREPENDED_EPISODE_START_BYTE_PAGES = 10
        private const val NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD = 64
        private const val NTK_INITIAL_PRIORITY_START_OFFSET = 1
        private const val NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES = 18
        private const val NTK_INITIAL_CONTINUOUS_BUSY_PAGES = 16
        private const val NTK_SYNTHETIC_INITIAL_VISIBLE_PAGES = 2
        private const val NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES = 2
        private const val NTK_INITIAL_DIRECT_DELIVERY_PAGES = 10
        private const val NTK_INITIAL_ANCHOR_COALESCE_MS = 80L
        private const val NTK_INITIAL_ANCHOR_FAST_COALESCE_MS = 520L
        private const val NTK_INITIAL_SHORT_ANCHOR_VIEWPORT_COALESCE_MS = 1600L
        private const val NTK_INITIAL_ANCHOR_FAST_COALESCE_MAX_DECODE_MS = 700L
        private const val NTK_INITIAL_CONTINUOUS_DIRECT_WINDOW_MS = 5200L
        private const val NTK_INITIAL_CONTINUOUS_STAGGER_MS = 24L
        private const val NTK_APPEND_EARLY_GENERATED_WAIT_MS = 2600L
        private const val NTK_APPEND_EARLY_API_STRICT_LATE_WAIT_MS = 5200L
        private const val NTK_APPEND_API_STRICT_ACK_RETRY_WAIT_MS = 9000L
        private const val NTK_APPEND_EARLY_GENERATED_POLL_MS = 40L
        private const val NTK_APPEND_EARLY_PUBLISH_PAGES = 12
        private val NTK_VIEWER_EPISODE_PATH = Regex("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$")
        private val NTK_GENERATED_IMAGE_EXTENSION = Regex("(?i)\\.([a-z0-9]+)(?:[?#].*)?$")
        private const val NTK_INITIAL_BOOT_PRIORITY_PAGES = 16
        private const val NTK_INITIAL_BOOT_URGENT_PAGES = 16
        private const val NTK_INITIAL_BOOT_BACKGROUND_PAGES = 18
        private const val NTK_WEBTOON_INITIAL_BOOT_PRIORITY_PAGES = 12
        private const val NTK_WEBTOON_INITIAL_BOOT_URGENT_PAGES = 10
        private const val NTK_WEBTOON_INITIAL_BOOT_BACKGROUND_PAGES = 14
        private const val NTK_INITIAL_BYTE_PREFETCH_AHEAD_PAGES = 24
        private const val NTK_WEBTOON_INITIAL_BYTE_PREFETCH_AHEAD_PAGES = 16
        private const val NTK_GENERATED_INITIAL_LIMITED_WARM_PAGES = 18
        private const val NTK_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES = 18
        private const val NTK_GENERATED_INITIAL_LIMITED_BUSY_PAGES = 8
        private const val NTK_WEBTOON_GENERATED_INITIAL_LIMITED_WARM_PAGES = 14
        private const val NTK_WEBTOON_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES = 12
        private const val NTK_WEBTOON_GENERATED_INITIAL_LIMITED_BUSY_PAGES = 8
        private const val NTK_INITIAL_ANCHOR_DECODE_PRIME_PAGES = 2
        private const val NTK_INITIAL_PRIORITY_PAGES = 9
        private const val NTK_INITIAL_GENERATED_PROMOTE_MAX_AHEAD = 4
        private const val NTK_FOREGROUND_STREAM_AHEAD_PAGES = 6
        private const val NTK_INITIAL_NEAR_DECODE_AHEAD_PAGES = 8
        private const val NTK_INITIAL_DECODE_AHEAD_PAGES = 18
        private const val NTK_WEBTOON_INITIAL_NEAR_DECODE_AHEAD_PAGES = 8
        private const val NTK_WEBTOON_INITIAL_DECODE_AHEAD_PAGES = 12
        private const val NTK_WEBTOON_FOREGROUND_STREAM_AHEAD_PAGES = 12
        private const val NTK_WEBTOON_ACTIVE_SCROLL_FOREGROUND_RADIUS = 2
        private const val NTK_WEBTOON_WINDOW_AFTER = 8
        private const val NTK_WEBTOON_BUSY_WINDOW_AFTER = 6
        private const val NTK_WEBTOON_BUSY_DIRECTIONAL_DECODE_AHEAD = 2
        private const val NTK_WEBTOON_BUSY_VISIBLE_DECODE_RADIUS = 1
        private const val NTK_WEBTOON_IDLE_VISIBLE_DECODE_RADIUS = 2
        private const val NTK_WEBTOON_IDLE_DECODE_AHEAD = 2
        private const val NTK_INITIAL_SECONDARY_WARM_DELAY_MS = 120L
        private const val NTK_INITIAL_FAR_WARM_DELAY_MS = 160L
        private const val NTK_INITIAL_FAR_WARM_BATCH_PAGES = 6
        private const val NTK_INITIAL_FAR_WARM_BATCH_DELAY_MS = 80L
        private const val NTK_INITIAL_ACK_INFLIGHT_WARM_PAGES = 18
        private const val NTK_INITIAL_SOURCE_PREFETCH_AFTER_FIRST_BITMAP_DELAY_MS = 250L
        private const val NTK_INITIAL_FULL_FETCH_AFTER_EARLY_DEFER_MS = 4200L
        private const val NTK_INITIAL_FULL_APPEND_AFTER_FIRST_BITMAP_WAIT_MS = 3500L
        private const val NTK_INITIAL_FULL_APPEND_PUBLISH_AFTER_FIRST_BITMAP_WAIT_MS = 1800L
        private const val NTK_GENERATED_FULL_BYTE_PREFETCH_AFTER_FIRST_BITMAP_DELAY_MS = 420L
        private const val NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_PAGES = 12
        private const val NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_DELAY_MS = 60L
        private const val NTK_EPISODE_METADATA_AFTER_FIRST_BITMAP_DELAY_MS = 300L
        private const val NTK_INITIAL_DELIVERY_HOLD_FALLBACK_MS = 2600L
        private const val NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS = 32L
        private const val NTK_GENERATED_TRANSIENT_RETRY_ATTEMPTS = 3
        private const val NTK_GENERATED_TRANSIENT_RETRY_DELAY_MS = 650L
        private const val NTK_GENERATED_PARTIAL_RETRY_DELAY_MS = 120L
        private const val NTK_GENERATED_ACTIVE_FETCH_RETRY_DELAY_MS = 900L
        private const val NTK_GENERATED_INITIAL_RECOVERY_PAGES = 4
        private const val NTK_OBSERVED_MANHWA_APPEND_AFTER_FIRST_BITMAP_WAIT_MS = 4500L
        private const val NTK_TRACE_AHEAD_PAGES = 8
        private const val NTK_BACKGROUND_PREPARE_QUIET_MS = 120L
        private const val NTK_BACKGROUND_PREPARE_AFTER_FIRST_BITMAP_QUIET_MS = 3500L
        private const val NTK_ACK_PREFLIGHT_ADJACENT_RECHECK_MS = 250L
        private const val NTK_ADJACENT_AFTER_ACK_QUIET_MS = 1000L
        private const val NTK_CURRENT_ACK_ADJACENT_MAX_WAIT_MS = 2000L
        private const val NTK_SILENT_ADJACENT_BOUNDARY_PAGES = 3
        private const val NTK_ADJACENT_FOREGROUND_STREAM_PAGES = 4
        private const val BOUNDARY_DECODE_AHEAD_PAGES = 8
        private const val BOUNDARY_BYTE_AHEAD_PAGES = 32
        private const val BOUNDARY_BUSY_DECODE_AHEAD_PAGES = 8
        private const val BOUNDARY_BUSY_BYTE_AHEAD_PAGES = 24
        private const val BUSY_DELIVERY_SCAN_LIMIT = 64
        private const val NTK_GENERATED_BUSY_DIRECTIONAL_DECODE_AHEAD = 3
        private const val NTK_GENERATED_BUSY_VISIBLE_DECODE_RADIUS = 2
        private const val NTK_ACTIVE_SCROLL_FOREGROUND_RADIUS = 1
        private const val BUSY_DIRECTIONAL_DECODE_AHEAD = 8
        private const val BUSY_VISIBLE_DECODE_RADIUS = 5
        private const val BUSY_DELIVERY_DRAIN_LIMIT = 12
        private const val IDLE_DELIVERY_DRAIN_LIMIT = 12
        private const val BUSY_DELIVERY_DRAIN_DELAY_MS = 0L
        private const val IDLE_DELIVERY_RESUME_DELAY_MS = 24L
        private const val IDLE_DELIVERY_FRAME_DELAY_MS = 16L
        private const val INPUT_PRIORITY_QUIET_MS = 24L
        private const val LOW_RES_UPGRADE_QUIET_MS = 900L
        private const val START_SOURCE_PREFETCH_BEFORE = 0
        private const val START_SOURCE_PREFETCH_AFTER = 96
        private const val NTK_WEBTOON_INITIAL_SOURCE_PREFETCH_AFTER = 12
        private const val NTK_BUSY_SOURCE_PREFETCH_STEP = 32
        private const val NTK_IDLE_SOURCE_PREFETCH_STEP = 48
        private const val NTK_BUSY_SOURCE_PREFETCH_AFTER = 96
        private const val NTK_GENERATED_BUSY_SOURCE_PREFETCH_AFTER = 6
        private const val NTK_BUSY_VISIBLE_EDGE_EXTRA_PAGES = 2
        private const val NTK_IDLE_SOURCE_PREFETCH_AFTER = 64
        private const val ADJACENT_EXISTING_SKIP_LIMIT = 8
        private const val NTK_GENERATED_APPEND_REFRESH_MIN_COUNT = 24
        private val ACTIVE_BITMAP_BYTES: Long = minOf(
            320L * 1024L * 1024L,
            maxOf(128L * 1024L * 1024L, Runtime.getRuntime().maxMemory() * 3L / 5L)
        )
        private const val TILE_PAGE_MAX_BYTES = 24L * 1024L * 1024L
        private const val REPLACED_BITMAP_RECYCLE_DELAY_MS = 750L
        private const val TILE_PAGE_ASPECT_RATIO = 3.0f
        private const val TILE_PAGE_MIN_ESTIMATED_BYTES = 12L * 1024L * 1024L
        private const val TILE_SOURCE_HEIGHT = 2048
        private const val DECODED_DRAW_TILE_HEIGHT = 768
        private const val DECODED_DRAW_TILE_MIN_HEIGHT = 1400
        private const val DECODED_DRAW_TILE_MIN_BYTES = 1536L * 1024L
        private const val SPREAD_ASPECT_RATIO = 0.90f
        private const val PAGE_SIDE_FIRST = 0
        private const val PAGE_SIDE_SECOND = 1
        private val NTK_GENERATED_PAGE_URL = Regex("/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")
        private val NTK_EPISODE_PATH = Regex("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)$", RegexOption.IGNORE_CASE)
        private val NTK_GENERATED_IMAGE_URL = Regex(
            "^(https?://[^/]+/(?:blacktoon/episodes/\\d+/[^/?#]+|(?:manhwa|webtoon)/\\d+/[^/?#]+|wt/episodes/[^/?#]+/[^/?#]+)/)p\\d{3}\\.(jpg|jpeg|png|webp)([?#].*)?$",
            RegexOption.IGNORE_CASE
        )

        @JvmStatic
        fun shouldSplitPreparedBitmapForTest(autoCut: Boolean, allowSplit: Boolean, width: Int, height: Int): Boolean {
            return shouldSplitPreparedBitmapForPage(autoCut, allowSplit, width, height)
        }

        private fun shouldSplitPreparedBitmapForPage(
            autoCut: Boolean,
            allowSplit: Boolean,
            width: Int,
            height: Int
        ): Boolean {
            if (!autoCut || !allowSplit || width <= 0 || height <= 0) return false
            return width / height.toFloat() >= SPREAD_ASPECT_RATIO
        }

        private fun readerThreadFactory(name: String, priority: Int): ThreadFactory {
            val counter = AtomicInteger(1)
            return ThreadFactory { runnable ->
                Thread {
                    Process.setThreadPriority(priority)
                    runnable.run()
                }.apply {
                    this.name = "$name-${counter.getAndIncrement()}"
                    isDaemon = true
                }
            }
        }
    }
}
