package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.model.PageItem
import ml.melun.mangaview.repository.CacheFileStore
import ml.melun.mangaview.repository.EpisodeSnapshotCache
import ml.melun.mangaview.repository.MangaRepository
import ml.melun.mangaview.runtime.AppDispatchers
import ml.melun.mangaview.runtime.MainThreadStallMonitor
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    enum class InitialPrerenderResult {
        NOT_RENDERED,
        RENDERED_ONLY,
        RENDERED_AND_COMMIT
    }

    interface Listener {
        fun onPagesReady(count: Int)
        fun onPagesAppended(count: Int)
        fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int = 0)
        fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int)
        fun onInitialPage(index: Int)
        fun onPageLoading(index: Int)
        fun onPageBoundsReady(index: Int, width: Int, height: Int)
        fun onPageReady(index: Int, bitmap: Bitmap)
        fun onPageTilesReady(index: Int, pageWidth: Int, pageHeight: Int, tiles: List<ReaderTile>)
        fun onInitialPageDecoded(index: Int, bitmap: Bitmap): InitialPrerenderResult =
            InitialPrerenderResult.NOT_RENDERED

        fun onInitialPageTilesDecoded(
            index: Int,
            pageWidth: Int,
            pageHeight: Int,
            tiles: List<ReaderTile>
        ): InitialPrerenderResult = InitialPrerenderResult.NOT_RENDERED

        fun onInitialContinuousPageDecoded(index: Int, bitmap: Bitmap): InitialPrerenderResult =
            InitialPrerenderResult.NOT_RENDERED

        fun onInitialContinuousPageTilesDecoded(
            index: Int,
            pageWidth: Int,
            pageHeight: Int,
            tiles: List<ReaderTile>
        ): InitialPrerenderResult = InitialPrerenderResult.NOT_RENDERED
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

    private data class AppendGeneratedCandidate(
        val imageEpisodeId: String,
        val extension: String
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
    private val initialAnchorDecode = Executors.newSingleThreadExecutor(
        readerThreadFactory("ReaderInitialAnchorDecode", Process.THREAD_PRIORITY_DISPLAY)
    )
    private val anchorPoll = Executors.newSingleThreadExecutor(
        readerThreadFactory("ReaderAnchorPoll", Process.THREAD_PRIORITY_DEFAULT)
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
    private val sourcePrefetchNetwork = Executors.newFixedThreadPool(
        NTK_FULL_EPISODE_SOURCE_PREFETCH_PARALLELISM,
        readerThreadFactory("ReaderSourcePrefetch", Process.THREAD_PRIORITY_BACKGROUND)
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
    private val initialRecovery = Executors.newSingleThreadExecutor(
        readerThreadFactory("ReaderInitialRecovery", Process.THREAD_PRIORITY_DEFAULT)
    )
    private val busyDecodeGate = Semaphore(ReaderPipelinePolicy.BUSY_DECODE_PARALLELISM)
    private val idleDecodeGate = Semaphore(ReaderPipelinePolicy.IDLE_DECODE_PARALLELISM)
    private val cancelled = AtomicBoolean(false)
    private val imageCancellation = ReaderImageCache.Cancellation()
    private val pages = ArrayList<PageRef>()
    private val pagesLock = Object()
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val loadingPages = ConcurrentHashMap<Int, PageRef>()
    private val loadingStartedAtMs = ConcurrentHashMap<Int, Long>()
    private val urgentLoading = ConcurrentHashMap.newKeySet<Int>()
    private val urgentLoadingPages = ConcurrentHashMap<Int, PageRef>()
    private val bytePrefetching = ConcurrentHashMap.newKeySet<Int>()
    private val bytePrefetchCompletedAtMs = ConcurrentHashMap<Int, Long>()
    private val fullEpisodeSourcePrefetching = ConcurrentHashMap.newKeySet<Int>()
    private val preAnchorFallbackRetries = ConcurrentHashMap.newKeySet<Int>()
    private val initialAdjacentDecodeRetries = ConcurrentHashMap.newKeySet<Int>()
    private val postInitialContinuousDeferredRequests = ConcurrentHashMap.newKeySet<Int>()
    private val visibleGeneratedByteHedges = ConcurrentHashMap.newKeySet<Int>()
    private val visibleGeneratedDecodeHedges = ConcurrentHashMap.newKeySet<Int>()
    private val initialGeneratedAssetDecodeListeners = ConcurrentHashMap.newKeySet<Int>()
    private val initialGeneratedCachedDecodeInFlight = ConcurrentHashMap.newKeySet<Int>()
    private val initialGeneratedDirectDecodeInFlight = ConcurrentHashMap.newKeySet<Int>()
    private val idleFullWidthUpgradeScheduled = ConcurrentHashMap.newKeySet<Int>()
    private val failedPages = ConcurrentHashMap.newKeySet<Int>()
    private val ntkImageCaptchaLastPostedAt = AtomicLong(0L)
    private val transientGeneratedRetries = ConcurrentHashMap<Int, Int>()
    private val decodedWidths = ConcurrentHashMap<Int, Int>()
    private val desiredWidths = ConcurrentHashMap<Int, Int>()
    private val inFlightWidths = ConcurrentHashMap<Int, Int>()
    private val pendingDeliveryWidths = ConcurrentHashMap<Int, Int>()
    private val listenerDrawableDeliveries = ConcurrentHashMap.newKeySet<Int>()
    private val preRenderedInitialDeliveries = ConcurrentHashMap.newKeySet<Int>()
    private val preRenderedInitialContinuousDeliveries = ConcurrentHashMap.newKeySet<Int>()
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
    private val earlyNtkMainAppendScheduled = AtomicBoolean(false)
    private val earlyNtkEventFullAppendScheduled = AtomicBoolean(false)
    private val earlyNtkImageUrlRefreshScheduled = AtomicBoolean(false)
    private val earlyNtkImageUrlRefreshLatest = AtomicReference<List<String>>(emptyList())
    private val structurePublishPending = AtomicInteger(0)
    private val viewportBusy = AtomicBoolean(false)
    private val ntkAnchorCachedDecodeRetryPosted = AtomicBoolean(false)
    private val ntkAnchorCachedDecodeRetryUntilMs = AtomicLong(0L)
    private val initialAnchorCachedDecodeStarted = AtomicBoolean(false)
    private val initialAnchorAssetDecodeListenerKeys = ConcurrentHashMap.newKeySet<String>()
    private val deliveryResumeAtMs = AtomicLong(0L)
    private val lastUserInteractionMs = AtomicLong(0L)
    private val ntkFirstBitmapAtMs = AtomicLong(0L)
    private val repositoryLoadStartedAtMs = AtomicLong(0L)
    private val earlyPreparedBitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val deliveredBitmaps = LinkedHashMap<Int, Bitmap>(32, 0.75f, true)
    private val deliveredTiles = LinkedHashMap<Int, List<ReaderTile>>(16, 0.75f, true)
    private val deliveredOwned = HashSet<Int>()
    private val deliveredDrawableProofWidths = ConcurrentHashMap<Int, Int>()
    private var retainedFirstPage = 0
    private var retainedLastPage = 0
    private var retainedAnchorPage = 0
    private val firstBitmapLogged = AtomicBoolean(false)
    private val firstDrawableDelivered = AtomicBoolean(false)
    private val firstBitmapFollowupsStarted = AtomicBoolean(false)
    private val immediateGeneratedFullWarmStarted = AtomicBoolean(false)
    private val generatedFullEpisodeBulkRetryScheduled = AtomicBoolean(false)
    private val generatedFullEpisodeDecodeRetryScheduled = AtomicBoolean(false)
    private val initialContinuousAfterFirstBitmapStarted = AtomicBoolean(false)
    private val initialRapidGeneratedWindowStarted = AtomicBoolean(false)
    private val initialFanoutStarted = AtomicBoolean(false)
    private val ntkFullEpisodeWarmRetries = AtomicInteger(0)
    private val ntkAppendFullWarmBatches = ConcurrentHashMap.newKeySet<Int>()
    private val initialNearAfterAnchorDecodeStarted = AtomicBoolean(false)
    @Volatile
    private var ntkCoordinator: NtkEpisodeCoordinator? = null
    private val pendingInitialFanoutPage = AtomicInteger(-1)
    private val resolvedInitialStartPage = AtomicInteger(-1)
    private val ntkGeneratedFullBytePrefetchCursor = AtomicInteger(-1)
    private val windowGeneration = AtomicInteger(0)
    private var earlyNtkImageUrlsUnregister: (() -> Unit)? = null
    private val nextLoading = AtomicBoolean(false)
    private val previousAppendLoading = AtomicBoolean(false)
    private val nextAppendLoading = AtomicBoolean(false)
    private val adjacentMissingRefreshes = ConcurrentHashMap.newKeySet<String>()
    private val adjacentMissingTargets = ConcurrentHashMap.newKeySet<String>()
    private val ntkAdjacentAckPreflightPaths = ConcurrentHashMap.newKeySet<String>()
    private val ntkGeneratedBatchUnavailablePaths = ConcurrentHashMap.newKeySet<String>()
    private val ntkInitialUnavailableReplacementStarted = AtomicBoolean(false)
    private val deferredGeneratedTailTrimKeys = ConcurrentHashMap.newKeySet<String>()
    private val ntkEpisodeMetadataLoading = AtomicBoolean(false)
    private val deferredAdjacentPrepareScheduled = AtomicBoolean(false)
    private val deferredAdjacentPrepareAnchor = AtomicInteger(-1)
    private val deferredAdjacentPrepareDirection = AtomicInteger(0)
    private val deferredAdjacentPrepareSilent = AtomicBoolean(true)
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
        registerEarlyNtkImageUrlRefreshListener()
        val installedPreloadedEarlyUrls = installPreloadedEarlyNtkUrlsFromSessionStart()
        if (!installedPreloadedEarlyUrls) {
            schedulePreloadedEarlyNtkUrlsImmediateInstall()
        }
        scheduleImmediateNtkGeneratedInitialUrls()
        if (pagesInstalled.get()) return
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

    private fun schedulePreloadedEarlyNtkUrlsImmediateInstall() {
        if (!isNtkSource(manga, title) || cancelled.get() || pagesInstalled.get()) return
        try {
            initialRecovery.execute {
                if (!cancelled.get()) installPreloadedEarlyNtkUrlsFromSessionStart()
            }
        } catch (_: RejectedExecutionException) {
            if (!cancelled.get()) installPreloadedEarlyNtkUrlsFromSessionStart()
        }
    }

    private fun installPreloadedEarlyNtkUrlsFromSessionStart(): Boolean {
        if (cancelled.get() || pagesInstalled.get() || !isNtkSource(manga, title)) return false
        val now = SystemClock.elapsedRealtime()
        val minCreatedAt = max(0L, now - NTK_PRELOADED_EARLY_URL_ACCEPT_MS)
        val earlyUrls = ReaderImageCache.earlyNtkImageUrls(manga.ntkEpisodePath, minCreatedAt)
        if (earlyUrls.size < ntkEarlyUrlMinCount(manga)) return false
        val installed = installEarlyNtkUrls(manga, earlyUrls, now)
        logNtkRepositoryStage(
            manga,
            "early_urls_session_start_preloaded",
            "installed=$installed,count=${earlyUrls.size},ms=${SystemClock.elapsedRealtime() - now}"
        )
        return installed
    }

    private fun scheduleImmediateNtkGeneratedInitialUrls() {
        if (!isNtkSource(manga, title) || cancelled.get()) return
        try {
            initialRecovery.execute {
                if (!cancelled.get()) {
                    primeImmediateNtkGeneratedInitialUrls()
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!cancelled.get()) primeImmediateNtkGeneratedInitialUrls()
        }
    }

    private fun registerEarlyNtkImageUrlRefreshListener() {
        if (!isNtkSource(manga, title) || earlyNtkImageUrlsUnregister != null) return
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isEmpty()) return
        earlyNtkImageUrlsUnregister = ReaderImageCache.addEarlyNtkImageUrlsListener { changedPath, urls ->
            if (cancelled.get() || changedPath != path || urls.isEmpty()) return@addEarlyNtkImageUrlsListener
            earlyNtkImageUrlRefreshLatest.set(urls)
            if (!earlyNtkImageUrlRefreshScheduled.compareAndSet(false, true)) return@addEarlyNtkImageUrlsListener
            mainImmediate.postAtFrontOfQueue {
                drainEarlyNtkImageUrlRefreshEvents()
            }
        }
    }

    private fun drainEarlyNtkImageUrlRefreshEvents() {
        try {
            while (!cancelled.get()) {
                val urls = earlyNtkImageUrlRefreshLatest.getAndSet(emptyList())
                if (urls.isEmpty()) return
                handleEarlyNtkImageUrlsChanged(urls)
            }
        } finally {
            earlyNtkImageUrlRefreshScheduled.set(false)
            if (
                !cancelled.get() &&
                earlyNtkImageUrlRefreshLatest.get().isNotEmpty() &&
                earlyNtkImageUrlRefreshScheduled.compareAndSet(false, true)
            ) {
                mainImmediate.postAtFrontOfQueue { drainEarlyNtkImageUrlRefreshEvents() }
            }
        }
    }

    private fun handleEarlyNtkImageUrlsChanged(urls: List<String>) {
        if (cancelled.get() || urls.isEmpty()) return
        val start = currentStartPage()
        val target = pageRef(start)?.manga ?: manga
        val installedCount = synchronized(pagesLock) { pages.size }
        if (installedCount == 0 && !pagesInstalled.get() && isNtkSource(target, title)) {
            val loadStartedAt = repositoryLoadStartedAtMs.get().takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
            logNtkRepositoryStage(
                target,
                "early_urls_event_initial_install",
                "count=${urls.size},page=$start,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            if (installEarlyNtkUrls(target, urls, loadStartedAt)) {
                return
            }
        }
        if (installedCount > 0 &&
            urls.size > installedCount &&
            earlyNtkEventFullAppendScheduled.compareAndSet(false, true)
        ) {
            val loadStartedAt = repositoryLoadStartedAtMs.get().takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
            logNtkRepositoryStage(
                target,
                "early_urls_event_expand_detected",
                "from=$installedCount,to=${urls.size},page=$start,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            if (installedCount < NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES &&
                urls.size >= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES
            ) {
                try {
                    val runwayUrls = urls.take(NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
                    logNtkRepositoryStage(
                        target,
                        "early_urls_event_runway_append",
                        "from=$installedCount,to=$NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES," +
                            "full=${urls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    appendInitialNtkUrlsAfterEarlyInstall(
                        target,
                        runwayUrls,
                        loadStartedAt,
                        allowFirstBitmapDefer = false
                    )
                    requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), runwayUrls.size)
                    appendInitialNtkUrlsAfterEarlyInstall(
                        target,
                        urls,
                        loadStartedAt,
                        allowFirstBitmapDefer = false
                    )
                    requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), urls.size)
                    scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(0L)
                } finally {
                    earlyNtkEventFullAppendScheduled.set(false)
                }
                return
            }
            try {
                control.execute {
                    try {
                        appendInitialNtkUrlsAfterEarlyInstall(
                            target,
                            urls,
                            loadStartedAt,
                            allowFirstBitmapDefer = false
                        )
                        mainImmediate.postAtFrontOfQueue {
                            requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), urls.size)
                        }
                    } finally {
                        earlyNtkEventFullAppendScheduled.set(false)
                    }
                }
            } catch (_: RejectedExecutionException) {
                earlyNtkEventFullAppendScheduled.set(false)
            }
        }
        if (!shouldRefreshInstalledNtkGeneratedImagesFromEarlyUrls(target, urls)) return
        val loadStartedAt = repositoryLoadStartedAtMs.get().takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        if (!refreshInstalledNtkGeneratedImagesFromEarlyUrls(target, urls, "early_urls_event_same_count")) return
        logNtkRepositoryStage(
            target,
            "early_urls_event_same_count_replace",
            "count=${urls.size},page=$start,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        requestPageForeground(start)
        requestInitialContinuousPagesFromEarlyUrls(start, urls.size)
        scheduleNtkAnchorCachedDecodeRetry(start, "early_urls_event_same_count", loadStartedAt)
        scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(0L)
    }

    private fun primeImmediateNtkGeneratedInitialUrls(): Boolean {
        if (!isNtkSource(manga, title) || cancelled.get()) return false
        if (pagesInstalled.get()) return false
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        val match = NTK_VIEWER_EPISODE_PATH.matchEntire(path) ?: return false
        val segment = match.groupValues[1].lowercase(Locale.ROOT)
        if (segment != "webtoon" && segment != "manhwa") return false
        val pathWorkId = match.groupValues[2].trim()
        val pathEpisodeToken = match.groupValues[3].trim()
        if (segment == "webtoon" && !pathEpisodeToken.matches(Regex("\\d{1,12}"))) {
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_skip path=$path " +
                    "reason=slug_webtoon_non_numeric_episode"
            )
            ViewerWarmupManager.logMetric("reader_ntk_immediate_generated_initial_skip", 1L)
            return false
        }
        val imageWorkId = manga.ntkImageWorkId.trim().ifEmpty { pathWorkId }
        val recordedImageEpisodeId = manga.ntkImageEpisodeId.trim()
        val trustedKnownGeneratedCount = hasTrustedKnownGeneratedInitialCount(manga)
        val largeUntrustedWebtoonCount =
            segment == "webtoon" &&
                manga.ntkImageCount > NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
                !trustedKnownGeneratedCount
        if (largeUntrustedWebtoonCount && !pathEpisodeToken.matches(Regex("\\d{1,12}"))) {
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_skip path=$path " +
                    "reason=untrusted_large_webtoon_count count=${manga.ntkImageCount}"
            )
            return false
        } else if (largeUntrustedWebtoonCount) {
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_limited path=$path " +
                    "reason=untrusted_large_webtoon_count_initial_only count=${manga.ntkImageCount}"
            )
        }
        val cachedIdentity = try {
            CustomHttpClient.cachedNtkImageIdentity(path)
        } catch (_: Throwable) {
            null
        }
        val recentGeneratedSuccess = ReaderImageCache
            .earlyNtkGeneratedSuccessImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
        if (segment == "webtoon" &&
            pathWorkId.matches(Regex("\\d{1,12}")) &&
            pathEpisodeToken.matches(Regex("\\d{1,12}")) &&
            manga.ntkImageCount > 0 &&
            imageWorkId == pathWorkId &&
            (cachedIdentity == null ||
                cachedIdentity.workId != imageWorkId ||
                cachedIdentity.episodeId != pathEpisodeToken) &&
            recentGeneratedSuccess.isEmpty()
        ) {
            if (
                cachedIdentity != null &&
                cachedIdentity.workId.matches(Regex("\\d{1,12}")) &&
                cachedIdentity.episodeId == pathEpisodeToken &&
                cachedIdentity.count > 0
            ) {
                val count = minOf(cachedIdentity.count, 128)
                val urls = ArrayList<String>(count)
                for (page in 1..count) {
                    urls.add(
                        "https://fifa.worldcup73.xyz/black/episodes/${cachedIdentity.workId}/" +
                            "${cachedIdentity.episodeId}/p%03d.jpg".format(Locale.ROOT, page)
                    )
                }
                ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
                installImmediateGeneratedEarlyUrls(urls, "cached_identity_prime")
                var started = 0
                val immediate = minOf(4, urls.size)
                for (index in 0 until immediate) {
                    if (
                        ReaderImageCache.startForegroundStreamFetch(
                            appContext,
                            manga,
                            urls[index],
                            null,
                            false,
                            null,
                            index,
                            true
                        )
                    ) {
                        started++
                    }
                }
                Log.d(
                    TAG,
                    "ntk_immediate_cached_identity_initial_prime path=$path " +
                        "workId=${cachedIdentity.workId} episodeId=${cachedIdentity.episodeId} " +
                        "count=${urls.size} startedCount=$started"
                )
                return true
            }
            manga.startNtkVerifiedInitialImageProbe(MainApplication.getHttpClient())
            manga.startNtkEarlyViewerApiPrefetch(MainApplication.getHttpClient())
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_defer_authority path=$path " +
                    "workId=$imageWorkId pathWorkId=$pathWorkId pathEpisodeId=$pathEpisodeToken " +
                    "identityWorkId=${cachedIdentity?.workId.orEmpty()} " +
                    "identityEpisodeId=${cachedIdentity?.episodeId.orEmpty()} count=${manga.ntkImageCount}"
            )
            return true
        }
        if (
            segment == "webtoon" &&
            shouldPreferNtkApiForCanonicalWebtoonPath(pathWorkId, pathEpisodeToken) &&
            recentGeneratedSuccess.isEmpty()
        ) {
            manga.startNtkVerifiedInitialImageProbe(MainApplication.getHttpClient())
            manga.startNtkEarlyViewerApiPrefetch(MainApplication.getHttpClient())
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_defer_canonical_api_first path=$path " +
                    "workId=$imageWorkId pathWorkId=$pathWorkId " +
                    "pathEpisodeId=$pathEpisodeToken count=${manga.ntkImageCount}"
            )
            return true
        }
        if (segment == "webtoon" &&
            !pathWorkId.matches(Regex("\\d{1,12}")) &&
            pathEpisodeToken.matches(Regex("\\d{1,12}"))
        ) {
            val slugCount = if (manga.ntkImageCount > 0) {
                manga.ntkImageCount
            } else {
                NTK_WEBTOON_GENERATED_INITIAL_RECOVERY_PAGES
            }
            val urls = ArrayList<String>(slugCount)
            for (page in 1..slugCount) {
                val pageName = "p%03d.jpg".format(Locale.ROOT, page)
                urls.add("https://fifa.worldcup73.xyz/wt/episodes/$pathWorkId/$pathEpisodeToken/$pageName")
            }
            ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
            installImmediateGeneratedEarlyUrls(urls, "slug_prime")
            val first = urls.firstOrNull() ?: return false
            val started = ReaderImageCache.startForegroundStreamFetch(
                appContext,
                manga,
                first,
                null,
                false,
                null,
                0,
                true
            )
            Log.d(
                TAG,
                "ntk_immediate_slug_generated_initial_prime path=$path " +
                    "count=${urls.size} started=$started jpgHedgeScheduled=false " +
                    "first=${first.substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric(
                "reader_ntk_immediate_slug_generated_initial_prime",
                if (started) 1L else 0L
            )
            return true
        }
        if (
            segment == "webtoon" &&
            !pathEpisodeToken.matches(Regex("\\d{1,12}")) &&
            recentGeneratedSuccess.isEmpty()
        ) {
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_skip path=$path " +
                    "reason=slug_requires_verified_payload count=${manga.ntkImageCount}"
            )
            return false
        }
        val canPrimePathEpisodeWithImageWork =
            segment == "webtoon" &&
                imageWorkId.matches(Regex("\\d{1,12}")) &&
                pathEpisodeToken.matches(Regex("\\d{1,12}"))
        if (
            segment == "webtoon" &&
            pathEpisodeToken.matches(Regex("\\d{1,12}")) &&
            (largeUntrustedWebtoonCount || recordedImageEpisodeId.matches(Regex("\\d{1,12}"))) &&
            !canPrimePathEpisodeWithImageWork &&
            recentGeneratedSuccess.isEmpty()
        ) {
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_defer_unverified_numeric_webtoon path=$path " +
                    "workId=$imageWorkId pathWorkId=$pathWorkId pathEpisodeId=$pathEpisodeToken " +
                    "recordedEpisodeId=$recordedImageEpisodeId count=${manga.ntkImageCount}"
            )
            return true
        }
        val imageEpisodeId = if (
            segment == "webtoon" &&
            pathEpisodeToken.matches(Regex("\\d{1,12}")) &&
            pathEpisodeToken != recordedImageEpisodeId
        ) {
            pathEpisodeToken
        } else {
            recordedImageEpisodeId.ifEmpty {
                pathEpisodeToken.takeIf { it.matches(Regex("\\d{1,12}")) }.orEmpty()
            }
        }
        if (!imageWorkId.matches(Regex("\\d{1,12}")) ||
            !imageEpisodeId.matches(Regex("\\d{1,12}"))
        ) {
            return false
        }
        val useWtEpisodeUrls =
            segment == "webtoon" &&
                !pathWorkId.matches(Regex("\\d{1,12}")) &&
                imageWorkId == pathWorkId &&
                recordedImageEpisodeId.matches(Regex("\\d{1,12}")) &&
                recordedImageEpisodeId != pathEpisodeToken &&
                manga.ntkImageCount in 1..64
        val initialLimit = if (trustedKnownGeneratedCount) {
            manga.ntkImageCount
        } else if (
            segment == "webtoon" &&
            manga.ntkImageCount in 1..NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD
        ) {
            manga.ntkImageCount
        } else if (segment == "webtoon") {
            NTK_WEBTOON_GENERATED_INITIAL_RECOVERY_PAGES
        } else {
            NTK_GENERATED_INITIAL_RECOVERY_PAGES
        }
        val count = if (manga.ntkImageCount > 0) {
            minOf(initialLimit, manga.ntkImageCount)
        } else {
            initialLimit
        }
        val urls = ArrayList<String>(count)
        val initialExtension = ntkGeneratedInitialExtension(segment, pathEpisodeToken)
        if (
            segment == "webtoon" &&
            initialExtension.equals("jpg", ignoreCase = true) &&
            manga.ntkImageCount > 0 &&
            imageWorkId.matches(Regex("\\d{1,12}")) &&
            imageEpisodeId.matches(Regex("\\d{1,12}")) &&
            !(imageWorkId == pathWorkId &&
                imageEpisodeId == pathEpisodeToken &&
                recordedImageEpisodeId == pathEpisodeToken) &&
            recentGeneratedSuccess.isEmpty()
        ) {
            manga.startNtkVerifiedInitialImageProbe(MainApplication.getHttpClient())
            manga.startNtkEarlyViewerApiPrefetch(MainApplication.getHttpClient())
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_defer_verify path=$path " +
                    "workId=$imageWorkId pathWorkId=$pathWorkId " +
                    "imageEpisodeId=$imageEpisodeId count=${manga.ntkImageCount}"
            )
            return true
        }
        for (page in 1..count) {
            val pageName = "p%03d.%s".format(Locale.ROOT, page, initialExtension)
            val url = if (segment == "webtoon") {
                if (useWtEpisodeUrls) {
                    "https://fifa.worldcup73.xyz/wt/episodes/$pathWorkId/$pathEpisodeToken/$pageName"
                } else {
                    "http://fifa.worldcup73.xyz/black/episodes/$imageWorkId/$imageEpisodeId/$pageName"
                }
            } else {
                "http://apihost93.com/$segment/$imageWorkId/$imageEpisodeId/$pageName"
            }
            urls.add(url)
        }
        ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
        installImmediateGeneratedEarlyUrls(urls, "prime")
        val first = urls.firstOrNull() ?: return false
        val immediateCount = minOf(ntkImmediateGeneratedInitialForegroundPages(segment), urls.size)
        val visibleImmediateCount = if (
            !firstBitmapLogged.get() &&
            (segment == "webtoon" || segment == "manhwa")
        ) {
            minOf(NTK_IMMEDIATE_GENERATED_INITIAL_BOOT_STREAM_PAGES, urls.size)
        } else if (segment == "webtoon" || segment == "manhwa") {
            minOf(maxOf(immediateCount, NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES), urls.size)
        } else {
            immediateCount
        }
        var startedCount = 0
        urls.take(visibleImmediateCount).forEachIndexed { index, image ->
            val started = ReaderImageCache.startForegroundStreamFetch(
                appContext,
                manga,
                image,
                null,
                false,
                null,
                index,
                true
            )
            if (started) startedCount++
        }
        var hedgeStartedCount = 0
        var wtHedgeStartedCount = 0
        if (
            segment == "webtoon" &&
            visibleImmediateCount > 0 &&
            firstBitmapLogged.get() &&
            pathWorkId.matches(Regex("\\d{1,12}")) &&
            pathEpisodeToken.matches(Regex("\\d{1,12}"))
        ) {
            for (index in 0 until visibleImmediateCount) {
                val pageName = "p%03d.%s".format(Locale.ROOT, index + 1, initialExtension)
                val image = "https://fifa.worldcup73.xyz/wt/episodes/$pathWorkId/$pathEpisodeToken/$pageName"
                val started = ReaderImageCache.startForegroundStreamFetch(
                    appContext,
                    manga,
                    image,
                    null,
                    false,
                    null,
                    index,
                    true
                )
                if (started) wtHedgeStartedCount++
            }
        }
        var jpgHedgeStarted = false
        var jpgHedgeScheduled = false
        if (segment == "webtoon" && initialExtension.equals("jpg", ignoreCase = true)) {
            val jpgHedge = replaceNtkGeneratedImageExtension(first, "jpeg")
            if (jpgHedge.isNotEmpty() && jpgHedge != first) {
                jpgHedgeScheduled = scheduleDelayedInitialJpgHedge(
                    first,
                    jpgHedge,
                    SystemClock.elapsedRealtime()
                )
            }
        }
        if (urls.size > visibleImmediateCount && initialContinuousDrawableDelivered(currentStartPage())) {
            urls.drop(visibleImmediateCount).forEachIndexed { tailIndex, image ->
                val pageIndex = visibleImmediateCount + tailIndex
                main.postDelayed({
                    if (cancelled.get()) return@postDelayed
                    if (!initialContinuousDrawableDelivered(currentStartPage())) return@postDelayed
                    val started = ReaderImageCache.startForegroundStreamFetch(
                        appContext,
                        manga,
                        image,
                        null,
                        false,
                        null,
                        pageIndex,
                        false
                    )
                    Log.d(
                        TAG,
                        "ntk_immediate_generated_initial_tail_prime path=$path " +
                            "page=$pageIndex started=$started first=${image.substringAfterLast('/')}"
                    )
                }, NTK_IMMEDIATE_GENERATED_INITIAL_TAIL_STAGGER_MS * (tailIndex + 1))
            }
        }
        Log.d(
            TAG,
            "ntk_immediate_generated_initial_prime path=$path segment=$segment " +
                "workId=$imageWorkId imageEpisodeId=$imageEpisodeId count=${urls.size} " +
                "immediateCount=$immediateCount visibleImmediateCount=$visibleImmediateCount " +
                "started=${startedCount > 0} startedCount=$startedCount " +
                "hedgeStartedCount=$hedgeStartedCount wtHedgeStartedCount=$wtHedgeStartedCount " +
                "jpgHedgeStarted=$jpgHedgeStarted jpgHedgeScheduled=$jpgHedgeScheduled " +
                "first=${first.substringAfterLast('/')}"
        )
        ViewerWarmupManager.logMetric("reader_ntk_immediate_generated_initial_prime", startedCount.toLong())
        return true
    }

    private fun hasTrustedKnownGeneratedInitialCount(target: Manga): Boolean {
        val knownCount = target.ntkImageCount
        if (knownCount <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD) return false
        val path = target.ntkEpisodePath?.trim().orEmpty()
        val match = NTK_VIEWER_EPISODE_PATH.matchEntire(path) ?: return false
        val segment = match.groupValues[1].lowercase(Locale.ROOT)
        val pathWorkId = match.groupValues[2].trim()
        val pathEpisodeToken = match.groupValues[3].trim()
        val imageWorkId = target.ntkImageWorkId.trim().ifEmpty { pathWorkId }
        val recordedImageEpisodeId = target.ntkImageEpisodeId.trim()
        if (segment != "webtoon" && segment != "manhwa") return false
        if (!pathWorkId.matches(Regex("\\d{1,12}"))) return false
        if (!pathEpisodeToken.matches(Regex("\\d{1,12}"))) return false
        if (!imageWorkId.matches(Regex("\\d{1,12}"))) return false
        val imageEpisodeId = recordedImageEpisodeId.ifEmpty { pathEpisodeToken }
        if (!imageEpisodeId.matches(Regex("\\d{1,12}"))) return false
        if (imageWorkId != pathWorkId) return false
        return imageEpisodeId == pathEpisodeToken || recordedImageEpisodeId.isBlank()
    }

    private fun installImmediateGeneratedEarlyUrls(urls: List<String>, reason: String): Boolean {
        if (cancelled.get() || urls.isEmpty() || pagesInstalled.get()) return false
        if (!isNtkSource(manga, title)) return false
        val loadStartedAt = repositoryLoadStartedAtMs.get().takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        val installed = installEarlyNtkUrls(manga, urls, loadStartedAt)
        logNtkRepositoryStage(
            manga,
            "immediate_generated_early_install",
            "reason=$reason,installed=$installed,count=${urls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        return installed
    }

    private fun ntkImmediateGeneratedInitialForegroundPages(segment: String): Int {
        return if (segment.equals("webtoon", ignoreCase = true)) {
            NTK_WEBTOON_IMMEDIATE_GENERATED_INITIAL_FOREGROUND_PAGES
        } else {
            NTK_IMMEDIATE_GENERATED_INITIAL_FOREGROUND_PAGES
        }
    }

    private fun startVerifiedNtkGeneratedInitialProbe(
        path: String,
        segment: String,
        imageWorkId: String,
        pathEpisodeToken: String,
        recordedImageEpisodeId: String,
        loadStartedAt: Long
    ) {
        if (!imageWorkId.matches(Regex("\\d{1,12}"))) return
        val candidateEpisodeIds = LinkedHashSet<String>()
        val hasNumericPathEpisode = pathEpisodeToken.matches(Regex("\\d{1,12}"))
        if (hasNumericPathEpisode) {
            candidateEpisodeIds.add(pathEpisodeToken)
            if (recordedImageEpisodeId.matches(Regex("\\d{1,12}"))) {
                candidateEpisodeIds.add(recordedImageEpisodeId)
            }
        } else if (recordedImageEpisodeId.matches(Regex("\\d{1,12}"))) {
            candidateEpisodeIds.add(recordedImageEpisodeId)
        }
        if (candidateEpisodeIds.isEmpty()) return
        val count = when {
            manga.ntkImageCount > 0 -> manga.ntkImageCount
            else -> NTK_WEBTOON_GENERATED_INITIAL_RECOVERY_PAGES
        }
        val started = AtomicBoolean(false)
        startSpeculativeVerifiedInitialCandidateStreams(
            path = path,
            segment = segment,
            imageWorkId = imageWorkId,
            candidateEpisodeIds = candidateEpisodeIds,
            initialPageCount = count
        )
        Log.d(
            TAG,
            "ntk_verified_generated_initial_probe_skip path=$path workId=$imageWorkId " +
                "pathEpisodeId=$pathEpisodeToken recordedEpisodeId=$recordedImageEpisodeId"
        )
        ViewerWarmupManager.logMetric("reader_ntk_verified_generated_initial_probe_skip", 1L)
        return
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val probes = ArrayList<Pair<String, String>>()
                for (episodeId in candidateEpisodeIds) {
                    for (extension in listOf("jpg", "jpeg", "png", "webp")) {
                        probes.add(episodeId to extension)
                    }
                }
                val latch = CountDownLatch(probes.size)
                val winner = AtomicReference<Pair<String, String>?>(null)
                probes.forEach { probe ->
                    Thread({
                        try {
                            if (cancelled.get() || winner.get() != null) return@Thread
                            val first = ntkGeneratedInitialUrl(segment, imageWorkId, probe.first, 1, probe.second)
                            if (isReachableNtkGeneratedProbe(first)) {
                                winner.compareAndSet(null, probe)
                            }
                        } catch (_: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }, "ntk-generated-probe").apply {
                        isDaemon = true
                        start()
                    }
                }
                latch.await(NTK_GENERATED_INITIAL_PROBE_TOTAL_WAIT_MS, TimeUnit.MILLISECONDS)
                val hit = winner.get()
                if (hit != null && !cancelled.get()) {
                    val urls = (1..count).map { page ->
                        ntkGeneratedInitialUrl(segment, imageWorkId, hit.first, page, hit.second)
                    }
                    ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
                    urls.take(ntkImmediateGeneratedInitialForegroundPages(segment))
                        .forEachIndexed { index, image ->
                            ReaderImageCache.startForegroundStreamFetch(
                                appContext,
                                manga,
                                image,
                                null,
                                false,
                                null,
                                index,
                                true
                            )
                        }
                    Log.d(
                        TAG,
                        "ntk_verified_generated_initial_publish path=$path workId=$imageWorkId " +
                            "episodeId=${hit.first} extension=${hit.second} count=${urls.size} " +
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    started.set(true)
                    return@Thread
                }
                if (ntkGeneratedBatchUnavailablePaths.add(path)) {
                    Log.d(
                        TAG,
                        "ntk_verified_generated_initial_unavailable path=$path workId=$imageWorkId " +
                            "pathEpisodeId=$pathEpisodeToken recordedEpisodeId=$recordedImageEpisodeId " +
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    replaceUnavailableGeneratedInitialWithAdjacent(manga, loadStartedAt)
                }
            } catch (e: Exception) {
                if (!cancelled.get()) recordIfUnexpected(e)
            } finally {
                if (!started.get()) {
                    ViewerWarmupManager.logMetric("reader_ntk_verified_generated_initial_miss", 1L)
                }
            }
        }, "ntk-verified-generated-initial").apply {
            isDaemon = true
            start()
        }
    }

    private fun startSpeculativeVerifiedInitialCandidateStreams(
        path: String,
        segment: String,
        imageWorkId: String,
        candidateEpisodeIds: Set<String>,
        initialPageCount: Int
    ) {
        if (segment != "webtoon") return
        val firstCandidate = candidateEpisodeIds.firstOrNull { it.matches(Regex("\\d{1,12}")) } ?: return
        val streamLimit = ntkImmediateGeneratedInitialForegroundPages(segment)
        val streamCount = initialPageCount.coerceAtLeast(1).coerceAtMost(streamLimit)
        val urls = (1..streamCount).map { page ->
            ntkGeneratedInitialUrl(segment, imageWorkId, firstCandidate, page, "jpg")
        }
        try {
            ReaderImageCache.rememberEarlyNtkImageUrls(path, urls)
            var startedCount = 0
            val streamUrls = urls.take(streamLimit).distinct()
            streamUrls.forEach { image ->
                val page = generatedImagePageNumber(image)
                val pageIndex = if (page > 0) page - 1 else -1
                val started = ReaderImageCache.startForegroundStreamFetch(
                    appContext,
                    manga,
                    image,
                    null,
                    false,
                    null,
                    pageIndex,
                    true
                )
                if (started) startedCount++
            }
            Log.d(
                TAG,
                "ntk_verified_generated_initial_candidate_stream path=$path " +
                    "workId=$imageWorkId episodeId=$firstCandidate started=$startedCount " +
                    "count=${urls.size} first=${urls.firstOrNull().orEmpty().substringAfterLast('/')}"
            )
        } catch (e: Exception) {
            Log.d(TAG, "ntk_verified_generated_initial_candidate_stream_error path=$path,$e")
        }
    }

    private fun generatedImagePageNumber(image: String): Int {
        val name = image.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val match = Regex("(?i)^p(\\d{1,4})\\.(?:jpg|jpeg|png|webp)$").find(name) ?: return -1
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun immediateGeneratedInitialPrimaryExtension(
        segment: String,
        pathWorkId: String,
        generatedWorkId: String,
        pathEpisodeToken: String,
        recordedImageEpisodeId: String
    ): String {
        if (segment != "webtoon") return ntkGeneratedInitialExtension(segment, pathEpisodeToken)
        return ntkGeneratedInitialExtension(segment, pathEpisodeToken)
    }

    private fun immediateGeneratedInitialUrls(
        segment: String,
        workId: String,
        episodeId: String,
        count: Int,
        extension: String
    ): ArrayList<String> {
        val urls = ArrayList<String>(count)
        for (page in 1..count) {
            urls.add(ntkGeneratedInitialUrl(segment, workId, episodeId, page, extension))
        }
        return urls
    }

    private fun startImmediateInitialExtensionHedges(
        segment: String,
        workId: String,
        episodeId: String,
        count: Int,
        primaryExtension: String,
        immediateCount: Int
    ): Int {
        if (segment != "webtoon" || count <= 0 || immediateCount <= 0) return 0
        val hedgeExtensions = listOf("jpg", "jpeg").filter { it != primaryExtension }
        if (hedgeExtensions.isEmpty()) return 0
        val hedgePages = minOf(
            count,
            immediateCount,
            NTK_IMMEDIATE_GENERATED_INITIAL_EXTENSION_HEDGE_PAGES
        )
        for (page in 1..hedgePages) {
            for (extension in hedgeExtensions) {
                val image = ntkGeneratedInitialUrl(segment, workId, episodeId, page, extension)
                val primaryImage = ntkGeneratedInitialUrl(segment, workId, episodeId, page, primaryExtension)
                AppDispatchers.runIoDelayed({
                    if (cancelled.get()) return@runIoDelayed
                    if (ReaderImageCache.cachedExactFile(appContext, manga, primaryImage) != null) {
                        Log.d(
                            TAG,
                            "ntk_immediate_generated_initial_extension_hedge_skip path=${manga.ntkEpisodePath} " +
                                "page=$page reason=primary_cached hedge=${image.substringAfterLast('/')}"
                        )
                        return@runIoDelayed
                    }
                    if (!ReaderImageCache.isKnownNtkGeneratedNotFound(manga, primaryImage)) {
                        Log.d(
                            TAG,
                            "ntk_immediate_generated_initial_extension_hedge_skip path=${manga.ntkEpisodePath} " +
                                "page=$page reason=primary_not_failed primary=$primaryExtension " +
                                "hedge=${image.substringAfterLast('/')}"
                        )
                        return@runIoDelayed
                    }
                    val started = ReaderImageCache.startForegroundStreamFetch(
                        appContext,
                        manga,
                        image,
                        null,
                        false,
                        null,
                        page - 1,
                        true
                    )
                    Log.d(
                        TAG,
                        "ntk_immediate_generated_initial_extension_hedge_start path=${manga.ntkEpisodePath} " +
                            "page=$page started=$started primary=$primaryExtension hedge=${image.substringAfterLast('/')}"
                    )
                }, NTK_IMMEDIATE_GENERATED_INITIAL_EXTENSION_HEDGE_DELAY_MS)
            }
        }
        Log.d(
            TAG,
            "ntk_immediate_generated_initial_extension_hedge path=${manga.ntkEpisodePath} " +
                "primary=$primaryExtension pages=$hedgePages scheduled=${hedgePages * hedgeExtensions.size}"
        )
        return 0
    }

    private fun ntkGeneratedInitialUrl(
        segment: String,
        workId: String,
        episodeId: String,
        page: Int,
        extension: String
    ): String {
        val pageName = "p%03d.%s".format(Locale.ROOT, page, extension)
        return if (segment == "webtoon") {
            "http://fifa.worldcup73.xyz/black/episodes/$workId/$episodeId/$pageName"
        } else {
            "http://apihost93.com/$segment/$workId/$episodeId/$pageName"
        }
    }

    private fun isReachableNtkGeneratedProbe(url: String): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NTK_GENERATED_INITIAL_PROBE_TIMEOUT_MS
            readTimeout = NTK_GENERATED_INITIAL_PROBE_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
            )
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        }
        return try {
            val code = connection.responseCode
            code in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private fun scheduleDelayedInitialJpgHedge(
        first: String,
        jpgHedge: String,
        scheduledAtMs: Long
    ): Boolean {
        if (first.isBlank() || jpgHedge.isBlank()) return false
        AppDispatchers.runIoDelayed({
            maybeStartDelayedInitialJpgHedge(first, jpgHedge, scheduledAtMs)
        }, NTK_INITIAL_JPG_HEDGE_DELAY_MS)
        Log.d(
            TAG,
            "ntk_immediate_generated_initial_jpg_hedge_scheduled path=${manga.ntkEpisodePath} " +
                "delayMs=$NTK_INITIAL_JPG_HEDGE_DELAY_MS first=${first.substringAfterLast('/')} " +
                "hedge=${jpgHedge.substringAfterLast('/')}"
        )
        return true
    }

    private fun maybeStartDelayedInitialJpgHedge(
        first: String,
        jpgHedge: String,
        scheduledAtMs: Long
    ) {
        if (cancelled.get()) return
        val elapsedMs = SystemClock.elapsedRealtime() - scheduledAtMs
        if (ReaderImageCache.cachedExactFile(appContext, manga, first) != null ||
            hasRecentInitialGeneratedSuccess(first)
        ) {
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_jpg_hedge_skip path=${manga.ntkEpisodePath} " +
                    "reason=primary_ready elapsedMs=$elapsedMs first=${first.substringAfterLast('/')}"
            )
            return
        }
        if (!ReaderImageCache.isKnownNtkGeneratedNotFound(manga, first)) {
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_jpg_hedge_skip path=${manga.ntkEpisodePath} " +
                    "reason=primary_not_failed elapsedMs=$elapsedMs first=${first.substringAfterLast('/')}"
            )
            return
        }
        if (ReaderImageCache.hasActiveFetch(manga, first) &&
            elapsedMs < NTK_INITIAL_JPG_HEDGE_MAX_WAIT_MS
        ) {
            AppDispatchers.runIoDelayed({
                maybeStartDelayedInitialJpgHedge(first, jpgHedge, scheduledAtMs)
            }, NTK_INITIAL_JPG_HEDGE_RECHECK_MS)
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_jpg_hedge_wait path=${manga.ntkEpisodePath} " +
                    "elapsedMs=$elapsedMs recheckMs=$NTK_INITIAL_JPG_HEDGE_RECHECK_MS " +
                    "first=${first.substringAfterLast('/')}"
            )
            return
        }
        val started = ReaderImageCache.startForegroundStreamFetch(
            appContext,
            manga,
            jpgHedge,
            null,
            false,
            null,
            0,
            true
        )
        Log.d(
            TAG,
            "ntk_immediate_generated_initial_jpg_hedge_start path=${manga.ntkEpisodePath} " +
                "started=$started elapsedMs=$elapsedMs first=${first.substringAfterLast('/')} " +
                "hedge=${jpgHedge.substringAfterLast('/')}"
        )
    }

    private fun hasRecentInitialGeneratedSuccess(first: String): Boolean {
        return ReaderImageCache
            .earlyNtkGeneratedSuccessImageUrls(manga.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L)
            .any { it == first }
    }

    private fun scheduleNtkInitialTailGeneratedStreamAfterDelay(
        target: Manga,
        image: String,
        pageIndex: Int,
        delayMs: Long
    ) {
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            val quietMs = ntkBackgroundWarmQuietRemainingMs()
            if (!firstBitmapLogged.get() || quietMs > 0L) {
                scheduleNtkInitialTailGeneratedStreamAfterDelay(
                    target,
                    image,
                    pageIndex,
                    if (quietMs > 0L) quietMs else NTK_BACKGROUND_WARM_RECHECK_MS
                )
                return@postDelayed
            }
            val started = ReaderImageCache.startForegroundStreamFetch(
                appContext,
                target,
                image,
                null,
                false,
                null,
                pageIndex,
                false
            )
            Log.d(
                TAG,
                "ntk_immediate_generated_initial_tail_after_first_bitmap path=${target.ntkEpisodePath} " +
                    "page=$pageIndex started=$started first=${image.substringAfterLast('/')}"
            )
        }, delayMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
    }

    private fun ntkGeneratedInitialExtension(segment: String, pathEpisodeToken: String): String {
        if (segment == "webtoon" && pathEpisodeToken.matches(Regex("\\d{1,12}"))) return "jpg"
        return "jpg"
    }

    private fun replaceNtkGeneratedImageExtension(url: String, extension: String): String {
        if (url.isEmpty() || extension.isEmpty()) return ""
        val query = url.indexOf('?').takeIf { it >= 0 } ?: url.length
        val fragment = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val cut = minOf(query, fragment)
        val main = url.substring(0, cut)
        val suffix = url.substring(cut)
        val dot = main.lastIndexOf('.')
        val slash = main.lastIndexOf('/')
        if (dot <= slash) return url
        return main.substring(0, dot + 1) + extension + suffix
    }

    private fun loadFromRepository() {
        if (!repositoryLoading.compareAndSet(false, true)) return
        val queuedAt = SystemClock.elapsedRealtime()
        network.execute {
            val startedAt = SystemClock.elapsedRealtime()
            repositoryLoadStartedAtMs.set(startedAt)
            try {
                if (pagesInstalled.get()) {
                    logNtkRepositoryStage(manga, "load_skip_pages_installed", "queueMs=${startedAt - queuedAt}")
                    return@execute
                }
                logNtkRepositoryStage(manga, "load_start", "queueMs=${startedAt - queuedAt}")
                attachTitle()
                if (pagesInstalled.get()) {
                    logNtkRepositoryStage(
                        manga,
                        "load_skip_pages_installed_after_attach",
                        "ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return@execute
                }
                if (isNtkSource(manga, title)) {
                    scheduleNtkAdjacentAckPreflightsAfterFirstBitmap(includeLookahead = false)
                }
                var activeManga = manga
                if (installPreloadedEarlyNtkUrlsIfReady(activeManga, startedAt)) {
                    return@execute
                }
                var urls = imageRepository.imageUrls(activeManga, appContext)
                logNtkRepositoryStage(
                    activeManga,
                    "cached_urls_checked",
                    "count=${urls?.size ?: 0},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                if (urls.isNullOrEmpty()) {
                    val cancellation = repositoryCancellation(userVisible = true)
                    val ntkInitial = isNtkSource(manga, title)
                    if (ntkInitial && !isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)) {
                        cancellation.prioritizeWebViewFallback()
                    }
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
                        if (waitInstallEarlyNtkUrlsBeforeUnavailable(activeManga, startedAt)) {
                            return@execute
                        }
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

    private fun waitInstallEarlyNtkUrlsBeforeUnavailable(target: Manga, loadStartedAt: Long): Boolean {
        if (!isNtkSource(target, title) || firstBitmapLogged.get()) return false
        val deadline = SystemClock.elapsedRealtime() + NTK_UNAVAILABLE_EARLY_URL_GRACE_MS
        while (!cancelled.get() && !firstBitmapLogged.get() && SystemClock.elapsedRealtime() < deadline) {
            val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
            if (earlyUrls.size >= ntkEarlyUrlMinCount(target) && installEarlyNtkUrls(target, earlyUrls, loadStartedAt)) {
                logNtkRepositoryStage(
                    target,
                    "early_urls_before_unavailable",
                    "count=${earlyUrls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                return true
            }
            try {
                Thread.sleep(NTK_UNAVAILABLE_EARLY_URL_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun ntkEarlyUrlMinCount(target: Manga): Int {
        return 1
    }

    private fun installPreloadedEarlyNtkUrlsIfReady(target: Manga, loadStartedAt: Long): Boolean {
        if (!isNtkSource(target, title)) return false
        val minCreatedAt = max(0L, loadStartedAt - NTK_PRELOADED_EARLY_URL_ACCEPT_MS)
        val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, minCreatedAt)
        if (earlyUrls.size < ntkEarlyUrlMinCount(target)) return false
        if (!installEarlyNtkUrls(target, earlyUrls, loadStartedAt)) return false
        logNtkRepositoryStage(
            target,
            "early_urls_preloaded",
            "count=${earlyUrls.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        return true
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
                if (earlyUrls.size >= ntkEarlyUrlMinCount(target) && installEarlyNtkUrls(target, earlyUrls, loadStartedAt)) {
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
                    if (earlyUrls.size >= ntkEarlyUrlMinCount(target) &&
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
            if (earlyUrls.size >= ntkEarlyUrlMinCount(target) && installEarlyNtkUrls(target, earlyUrls, loadStartedAt)) {
                releaseDeferred = true
                finishInitialFetchAfterEarlyInstall(target, task, cancellation, loadStartedAt)
                logNtkRepositoryStage(
                    target,
                    "early_urls_after_fetch_done",
                    "count=${earlyUrls.size},result=$result,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                return InitialFetchOutcome(Title.LOAD_OK, true)
            }
            if (result != Title.LOAD_OK && target.ntkViewerParseReason == "unavailable") {
                logNtkRepositoryStage(
                    target,
                    "early_urls_after_fetch_done_skip_unavailable",
                    "result=$result,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                return InitialFetchOutcome(result, false)
            }
            val shouldKeepPollingForEarlyUrls =
                result != Title.LOAD_OK ||
                    (isNtkSource(target, title) && imageRepository.imageUrls(target, appContext).isNullOrEmpty())
            if (shouldKeepPollingForEarlyUrls) {
                while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                    val delayedEarlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
                    if (delayedEarlyUrls.size >= ntkEarlyUrlMinCount(target) &&
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
        val latest = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, loadStartedAt)
        val sourceUrls = if (latest.size > urls.size) latest else urls
        val installPartialOnly = shouldInstallPartialEarlyNtkUrlsWithoutExpansion(target, sourceUrls)
        val partialRunwayUrls = if (installPartialOnly) {
            initialContinuousRunwayUrlsFromPartial(target, sourceUrls)
        } else {
            null
        }
        val initialUrls = if (partialRunwayUrls != null) {
            partialRunwayUrls
        } else if (installPartialOnly) {
            sourceUrls
        } else if (shouldLimitLargeUntrustedGeneratedEarlyUrls(target, sourceUrls)) {
            limitedLargeUntrustedGeneratedUrls(target, sourceUrls)
        } else if (shouldKeepManhwaGeneratedEarlyToObservedUrls(target, sourceUrls)) {
            sourceUrls
        } else if (shouldDeferGeneratedEarlyExpansionBeforeFirstBitmap(target, sourceUrls)) {
            expandInitialVerifiedGeneratedEarlyUrls(target, sourceUrls)
        } else {
            expandVerifiedGeneratedEarlyUrls(target, sourceUrls)
        }
        val startPage = requestedStartPage().coerceIn(0, initialUrls.lastIndex)
        logNtkRepositoryStage(
            target,
            "early_urls_ready",
            "count=${initialUrls.size},raw=${urls.size},source=${sourceUrls.size}," +
                "partialOnly=$installPartialOnly,partialRunway=${partialRunwayUrls?.size ?: 0}," +
                "page=$startPage,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        val startImage = initialUrls.getOrNull(startPage).orEmpty()
        val naverOriginalFirst = isNaverWebtoonPageImageUrl(startImage)
        val naverStartCached = naverOriginalFirst &&
            ReaderImageCache.cachedFile(appContext, target, startImage) != null
        if (!naverOriginalFirst) {
            registerNtkAnchorAssetCachedDecode(
                startPage,
                target,
                startImage,
                "early_urls_ready_preinstall",
                loadStartedAt
            )
            startNaverOriginalForegroundStreamBeforeInstall(target, initialUrls, startPage, loadStartedAt)
            startEarlyNtkAnchorForegroundStreamBeforeInstall(target, initialUrls, startPage, loadStartedAt)
        }
        installImagesForManga(target, initialUrls, startPage, false)
        logNtkRepositoryStage(
            target,
            "request_foreground",
            "source=early,page=$startPage,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        val naverImmediateDecode = naverOriginalFirst &&
            scheduleNaverOriginalImmediateDecode(startPage, "early_urls_ready", loadStartedAt)
        if (!naverImmediateDecode) {
            scheduleNtkAnchorCachedDecodeRetry(startPage, "early_urls_ready", loadStartedAt)
        }
        if (naverOriginalFirst) {
            if (!naverImmediateDecode && !naverStartCached) requestPageForeground(startPage)
            logNtkRepositoryStage(
                target,
                "early_naver_first_foreground_only",
                "page=$startPage,count=${initialUrls.size},cached=$naverStartCached,immediate=$naverImmediateDecode,adjacent=deferred,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
        } else {
            requestInitialContinuousPagesFromEarlyUrls(startPage, initialUrls.size)
            requestPageForeground(startPage)
        }
        flushEarlyPreparedBitmaps()
        scheduleGeneratedEarlyExpansionAfterFirstBitmap(target, initialUrls, loadStartedAt)
        appendExpandedEarlyNtkUrlsUntilFirstBitmap(target, initialUrls.size, loadStartedAt)
        scheduleExpandedEarlyNtkUrlsMainAppend(target, initialUrls.size, loadStartedAt)
        if (!naverOriginalFirst && initialUrls.size <= startPage + 1) requestInitialFanout(startPage)
        if (!naverOriginalFirst) startInitialForegroundStreamIfNeeded(target, initialUrls, startPage, loadStartedAt)
        return true
    }

    private fun shouldInstallPartialEarlyNtkUrlsWithoutExpansion(target: Manga, urls: List<String>): Boolean {
        if (urls.size >= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        if (target.ntkImageCount < NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        return urls.all { isNtkGeneratedImageUrl(it) || ReaderImageCache.isTrustedInitialNtkApiImageForEarlyStream(it) }
    }

    private fun initialContinuousRunwayUrlsFromPartial(target: Manga, urls: List<String>): List<String>? {
        if (urls.isEmpty() || urls.size >= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return null
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return null
        val knownCount = target.ntkImageCount
        if (knownCount < NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return null
        val seed = urls.firstOrNull { isNtkGeneratedImageUrl(it) } ?: return null
        val runway = ArrayList<String>(NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
        for (page in 1..NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) {
            val generated = ntkGeneratedImageUrlForTarget(seed, target, page) ?: return null
            runway.add(generated)
        }
        logNtkRepositoryStage(
            target,
            "early_urls_partial_initial_runway",
            "from=${urls.size},to=${runway.size},known=$knownCount,first=${seed.substringAfterLast('/')}"
        )
        return runway
    }

    private fun scheduleExpandedEarlyNtkUrlsMainAppend(
        target: Manga,
        initialInstalledCount: Int,
        loadStartedAt: Long
    ) {
        if (!isNtkSource(target, title) || initialInstalledCount <= 0) return
        if (!earlyNtkMainAppendScheduled.compareAndSet(false, true)) return
        val deadline = loadStartedAt + NTK_EARLY_GENERATED_EXPAND_BEFORE_FIRST_BITMAP_WAIT_MS
        val poll = object : Runnable {
            override fun run() {
                if (cancelled.get()) {
                    earlyNtkMainAppendScheduled.set(false)
                    return
                }
                val latest = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, 0L)
                val installedCount = synchronized(pagesLock) { pages.size }
                val shouldRefreshSameCount =
                    latest.size <= installedCount &&
                        shouldRefreshInstalledNtkGeneratedImagesFromEarlyUrls(target, latest)
                if (latest.size > installedCount || shouldRefreshSameCount) {
                    logNtkRepositoryStage(
                        target,
                        if (shouldRefreshSameCount) {
                            "early_urls_main_same_count_replace_detected"
                        } else {
                            "early_urls_main_expand_detected"
                        },
                        "from=$installedCount,to=${latest.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    if (shouldRefreshSameCount) {
                        try {
                            refreshInstalledNtkGeneratedImagesFromEarlyUrls(
                                target,
                                latest,
                                "early_urls_main_same_count_before_first"
                            )
                            val start = currentStartPage()
                            requestPageForeground(start)
                            requestInitialContinuousPagesFromEarlyUrls(start, latest.size)
                            scheduleNtkAnchorCachedDecodeRetry(
                                start,
                                "early_urls_main_same_count_before_first",
                                loadStartedAt
                            )
                            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(0L)
                        } finally {
                            earlyNtkMainAppendScheduled.set(false)
                            if (
                                !cancelled.get() &&
                                !firstBitmapLogged.get() &&
                                SystemClock.elapsedRealtime() < deadline
                            ) {
                                main.postDelayed(
                                    {
                                        scheduleExpandedEarlyNtkUrlsMainAppend(
                                            target,
                                            latest.size.coerceAtLeast(1),
                                            loadStartedAt
                                        )
                                    },
                                    NTK_EARLY_URL_POLL_MS
                                )
                            }
                        }
                        return
                    }
                    control.execute {
                        try {
                            appendInitialNtkUrlsAfterEarlyInstall(
                                target,
                                latest,
                                loadStartedAt,
                                allowFirstBitmapDefer = !isTrustedDirectWebtoonGeneratedEpisode(target)
                            )
                            requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), latest.size)
                        } finally {
                            earlyNtkMainAppendScheduled.set(false)
                            if (
                                !cancelled.get() &&
                                !firstBitmapLogged.get() &&
                                SystemClock.elapsedRealtime() < deadline
                            ) {
                                main.postDelayed(
                                    {
                                        scheduleExpandedEarlyNtkUrlsMainAppend(
                                            target,
                                            latest.size.coerceAtLeast(1),
                                            loadStartedAt
                                        )
                                    },
                                    NTK_EARLY_URL_POLL_MS
                                )
                            }
                        }
                    }
                    return
                }
                if (SystemClock.elapsedRealtime() < deadline) {
                    main.postDelayed(this, NTK_EARLY_URL_POLL_MS)
                } else {
                    logNtkRepositoryStage(
                        target,
                        "early_urls_main_expand_stop",
                        "installed=$installedCount,latest=${latest.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    earlyNtkMainAppendScheduled.set(false)
                }
            }
        }
        main.postDelayed(poll, NTK_EARLY_URL_POLL_MS)
    }

    private fun startNaverOriginalForegroundStreamBeforeInstall(
        target: Manga,
        urls: List<String>,
        startPage: Int,
        loadStartedAt: Long
    ) {
        val image = urls.getOrNull(startPage) ?: return
        if (!isNaverWebtoonPageImageUrl(image)) return
        val started = try {
            ReaderImageCache.startForegroundStreamFetch(
                appContext,
                target,
                image,
                imageCancellation,
                anchorHedge = false,
                permit = null,
                pageIndex = startPage,
                visiblePriority = true
            )
        } catch (e: Exception) {
            recordIfUnexpected(e)
            false
        }
        logNtkRepositoryStage(
            target,
            "early_naver_foreground_stream_before_install",
            "started=$started,page=$startPage,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
    }

    private fun startEarlyNtkAnchorForegroundStreamBeforeInstall(
        target: Manga,
        urls: List<String>,
        startPage: Int,
        loadStartedAt: Long
    ) {
        val image = urls.getOrNull(startPage) ?: return
        if (!ReaderImageCache.isTrustedInitialNtkApiImageForEarlyStream(image)) return
        val started = try {
            ReaderImageCache.startForegroundStreamFetch(
                appContext,
                target,
                image,
                imageCancellation,
                anchorHedge = false,
                permit = null,
                pageIndex = startPage,
                visiblePriority = true
            )
        } catch (e: Exception) {
            recordIfUnexpected(e)
            false
        }
        logNtkRepositoryStage(
            target,
            "early_ntk_anchor_foreground_stream_before_install",
            "started=$started,page=$startPage,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
    }

    private fun isNaverWebtoonPageImageUrl(image: String): Boolean {
        return try {
            val uri = Uri.parse(image)
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val path = uri.path.orEmpty().lowercase(Locale.ROOT)
            host == "image-comic.pstatic.net" &&
                path.matches(Regex("^/webtoon/\\d{5,}/\\d+/[^/?#]+\\.(jpg|jpeg|png|webp)$")) &&
                !path.contains("thumbnail") &&
                !path.contains("/ad/") &&
                !path.contains("/ads/")
        } catch (_: Throwable) {
            false
        }
    }

    private fun scheduleNaverOriginalImmediateDecode(index: Int, reason: String, startedAt: Long): Boolean {
        if (cancelled.get() || firstBitmapLogged.get()) return false
        val page = pageRef(index) ?: return false
        val image = page.image ?: return false
        if (!isNaverWebtoonPageImageUrl(image)) return false
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return true
        if (!urgentLoading.add(index)) return false
        urgentLoadingPages[index] = page
        val targetWidth = targetWidth(true)
        urgentDecode.execute {
            val decodeStartedAt = SystemClock.elapsedRealtime()
            var delivered = false
            try {
                if (
                    cancelled.get() ||
                    firstBitmapLogged.get() ||
                    pageRef(index) != page ||
                    hasDeliveredBitmap(index) ||
                    (pendingDeliveryWidths[index] ?: 0) > 0
                ) {
                    return@execute
                }
                val result = decodeNaverOriginalImmediateResult(index, page, image, targetWidth, decodeStartedAt)
                    ?: run {
                        logNtkPagePerf(index, "early_naver_immediate_decode_skip", "reason=$reason,cached=false")
                        return@execute
                    }
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
                    "early_naver_immediate_decode_ready",
                    "reason=$reason,waitMs=${decodeStartedAt - startedAt},decodeMs=${SystemClock.elapsedRealtime() - decodeStartedAt},width=${result.width}"
                )
                delivered = true
                postDecodeResult(Delivery(index, page, result, decodeStartedAt, targetWidth, retainWhenBusy = true))
                try {
                    network.execute {
                        startNaverOriginalAdjacentForegroundStreamsFromPages(index, "anchor_decoded", decodeStartedAt)
                    }
                } catch (_: RejectedExecutionException) {
                    startNaverOriginalAdjacentForegroundStreamsFromPages(index, "anchor_decoded_inline", decodeStartedAt)
                }
                scheduleNaverOriginalAdjacentDecodeAfterAnchor(index, "early_naver_immediate", decodeStartedAt)
            } catch (e: Exception) {
                recordIfUnexpected(e)
                postPageError(index, page, e)
            } finally {
                urgentLoadingPages.remove(index, page)
                urgentLoading.remove(index)
                if (!delivered) ViewerWarmupManager.logMetric("reader_naver_immediate_decode_not_delivered", index.toLong())
            }
        }
        return true
    }

    private fun decodeNaverOriginalImmediateResult(
        index: Int,
        page: PageRef,
        image: String,
        targetWidth: Int,
        decodeStartedAt: Long
    ): PageDecodeResult? {
        val cached = waitNaverOriginalCachedFile(page.manga, image, decodeStartedAt) ?: return null
        logNtkPagePerf(index, "early_naver_cached_file_decode", "target=$targetWidth,bytes=${cached.length()}")
        return decodePage(index, page, cached, targetWidth)
    }

    private fun startNaverOriginalAdjacentForegroundStreamsFromPages(
        startPage: Int,
        reason: String,
        startedAt: Long
    ) {
        if (cancelled.get()) return
        val target = pageRef(startPage)?.manga ?: manga
        var startedCount = 0
        val last = minOf(startPage + 2, synchronized(pagesLock) { pages.size } - 1)
        if (last <= startPage) return
        for (index in (startPage + 1)..last) {
            val page = pageRef(index) ?: continue
            val image = page.image ?: continue
            if (!isNaverWebtoonPageImageUrl(image)) continue
            val started = try {
                ReaderImageCache.startForegroundStreamFetch(
                    appContext,
                    page.manga,
                    image,
                    imageCancellation,
                    anchorHedge = false,
                    permit = null,
                    pageIndex = index,
                    visiblePriority = false
                )
            } catch (e: Exception) {
                recordIfUnexpected(e)
                false
            }
            if (started) startedCount++
        }
        logNtkRepositoryStage(
            target,
            "early_naver_adjacent_streams_after_anchor_file",
            "start=$startPage,started=$startedCount,reason=$reason,ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
    }

    private fun scheduleNaverOriginalAdjacentDecodeAfterAnchor(
        startPage: Int,
        reason: String,
        startedAt: Long
    ) {
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (!firstDrawableDelivered.get() && elapsed < NTK_NAVER_ORIGINAL_ADJACENT_DECODE_MAX_WAIT_MS) {
                scheduleNaverOriginalAdjacentDecodeAfterAnchor(startPage, reason, startedAt)
                return@postDelayed
            }
            logNtkRepositoryStage(
                pageRef(startPage)?.manga ?: manga,
                "early_naver_adjacent_decode_after_anchor",
                "start=$startPage,reason=$reason,firstDrawable=${firstDrawableDelivered.get()},ms=$elapsed"
            )
            requestInitialContinuousPagesFromEarlyUrls(startPage, synchronized(pagesLock) { pages.size })
        }, NTK_NAVER_ORIGINAL_ADJACENT_DECODE_RECHECK_MS)
    }

    private fun waitNaverOriginalCachedFile(manga: Manga, image: String, startedAt: Long): File? {
        val deadline = startedAt + NTK_NAVER_ORIGINAL_FIRST_FILE_WAIT_MS
        while (!cancelled.get() && !firstBitmapLogged.get() && SystemClock.elapsedRealtime() <= deadline) {
            ReaderImageCache.cachedFile(appContext, manga, image)?.let { return it }
            try {
                Thread.sleep(NTK_EARLY_URL_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return ReaderImageCache.cachedFile(appContext, manga, image)
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
            while (!cancelled.get() && !firstDrawableDelivered.get() && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            if (cancelled.get() || !firstDrawableDelivered.get()) return@execute
            val publishDuringTouch = shouldPublishGeneratedExpansionDuringTouch(target, urls)
            if (!publishDuringTouch && shouldQuietNtkGeneratedExpansionAfterFirstBitmap(target, urls)) {
                waitForInitialFullFetchQuietAfterFirstBitmap(target)
                if (cancelled.get()) return@execute
            }
            val expanded = expandVerifiedGeneratedEarlyUrls(target, urls)
            if (expanded.size <= urls.size) return@execute
            appendInitialNtkUrlsAfterEarlyInstall(
                target,
                expanded,
                loadStartedAt,
                allowFirstBitmapDefer = !publishDuringTouch
            )
            logNtkRepositoryStage(
                target,
                "early_urls_generated_expand_after_first",
                "count=${expanded.size},publishDuringTouch=$publishDuringTouch,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
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
            var installedImages = currentNtkInstalledImages(target)
            var installedStartImage = installedImages.getOrNull(currentStartPage())
            val deadline = SystemClock.elapsedRealtime() + NTK_EARLY_GENERATED_EXPAND_BEFORE_FIRST_BITMAP_WAIT_MS
            while (!cancelled.get() && !firstBitmapLogged.get() && SystemClock.elapsedRealtime() < deadline) {
                val earlyUrls = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, 0L)
                val startPage = currentStartPage()
                val incomingStartImage = earlyUrls.getOrNull(startPage)
                val startImageChanged = isSameInitialGeneratedPageReplacement(installedStartImage, incomingStartImage)
                val verifiedReplacementChanged = hasInitialVerifiedReplacement(installedImages, earlyUrls)
                val anchorAlreadyReadable = (startImageChanged || verifiedReplacementChanged) &&
                    (hasDeliveredOrPendingDrawable(startPage) ||
                        ReaderImageCache.hasNtkAnchorAssetForEpisode(target))
                if (anchorAlreadyReadable && earlyUrls.size <= installedCount) {
                    installedImages = mergeInitialVerifiedReplacements(installedImages, earlyUrls)
                    installedStartImage = incomingStartImage
                    logNtkRepositoryStage(
                        target,
                        "early_urls_anchor_replace_defer_before_first",
                        "count=$installedCount,incoming=${earlyUrls.size}," +
                            "page=$startPage,first=${incomingStartImage?.substringAfterLast('/') ?: ""}," +
                            "ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    try {
                        Thread.sleep(NTK_EARLY_URL_POLL_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                    continue
                }
                if (earlyUrls.size > installedCount || startImageChanged || verifiedReplacementChanged) {
                    val targetPath = target.ntkEpisodePath?.trim().orEmpty()
                    val unavailableGeneratedWebtoonBatch =
                        targetPath.startsWith("/webtoon/", ignoreCase = true) &&
                            earlyUrls.isNotEmpty() &&
                            earlyUrls.all { isNtkGeneratedImageUrl(it) } &&
                            ntkGeneratedBatchUnavailablePaths.contains(targetPath)
                    if (unavailableGeneratedWebtoonBatch) {
                        logNtkRepositoryStage(
                            target,
                            "early_urls_generated_expand_suppressed_after_not_found",
                            "incoming=${earlyUrls.size},installed=$installedCount," +
                                "page=$startPage,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                        )
                        removeInitialUnavailableGeneratedPages(target)
                        return@execute
                    }
                    val replacementUrls = if (
                        (startImageChanged || verifiedReplacementChanged) &&
                        earlyUrls.size < installedImages.size
                    ) {
                        mergeInitialVerifiedReplacements(installedImages, earlyUrls)
                    } else {
                        earlyUrls
                    }
                    appendInitialNtkUrlsAfterEarlyInstall(
                        target,
                        replacementUrls,
                        loadStartedAt,
                        allowFirstBitmapDefer = !startImageChanged && !verifiedReplacementChanged
                    )
                    installedImages = currentNtkInstalledImages(target)
                    installedCount = installedImages.size
                    installedStartImage = installedImages.getOrNull(startPage)
                    logNtkRepositoryStage(
                        target,
                        if (startImageChanged || verifiedReplacementChanged) {
                            "early_urls_anchor_replace_before_first"
                        } else {
                            "early_urls_generated_expand_before_first"
                        },
                        "count=$installedCount,incoming=${earlyUrls.size},merged=${replacementUrls.size}," +
                            "page=$startPage,first=${incomingStartImage?.substringAfterLast('/') ?: ""}," +
                            "ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), installedCount)
                    val currentAnchor = currentStartPage()
                    requestPageForeground(currentAnchor)
                    scheduleNtkAnchorCachedDecode(currentAnchor, "early_urls_anchor_replace_before_first", loadStartedAt)
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

    private fun scheduleNtkAnchorCachedDecode(index: Int, reason: String, startedAt: Long) {
        tryScheduleNtkAnchorCachedDecode(index, reason, startedAt)
    }

    private fun registerNtkAnchorAssetCachedDecode(
        index: Int,
        target: Manga,
        image: String,
        reason: String,
        startedAt: Long
    ): Boolean {
        if (cancelled.get() || firstBitmapLogged.get()) return false
        if (image.isBlank() || !isNtkGeneratedImageUrl(image)) return false
        if (!isNtkSource(target, title) || !isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        val listenerKey = ntkAnchorAssetDecodeListenerKey(index, target, image)
        if (!initialAnchorAssetDecodeListenerKeys.add(listenerKey)) return true
        val accepted = ReaderImageCache.runWhenNtkAnchorAssetReady(
            appContext,
            target,
            image,
            Runnable {
                runNtkAnchorAssetReadyCachedDecode(index, target, image, reason, startedAt, listenerKey, 0)
            }
        )
        if (!accepted) {
            initialAnchorAssetDecodeListenerKeys.remove(listenerKey)
            return false
        }
        logNtkRepositoryStage(
            target,
            "anchor_asset_decode_listener",
            "reason=$reason,page=$index,image=${image.substringAfterLast('/').takeLast(64)}"
        )
        return true
    }

    private fun runNtkAnchorAssetReadyCachedDecode(
        index: Int,
        target: Manga,
        image: String,
        reason: String,
        startedAt: Long,
        listenerKey: String,
        attempt: Int
    ) {
        if (cancelled.get() || firstBitmapLogged.get()) {
            initialAnchorAssetDecodeListenerKeys.remove(listenerKey)
            return
        }
        val page = pageRef(index)
        if (
            page == null ||
            page.transitionTitle != null ||
            !Manga.sameEpisodeIdentity(page.manga, target) ||
            !sameNtkAnchorAssetImage(page.image.orEmpty(), image, index)
        ) {
            if (attempt < NTK_ANCHOR_ASSET_DECODE_PAGE_BIND_RETRIES) {
                main.postDelayed({
                    runNtkAnchorAssetReadyCachedDecode(
                        index,
                        target,
                        image,
                        reason,
                        startedAt,
                        listenerKey,
                        attempt + 1
                    )
                }, NTK_EARLY_URL_POLL_MS)
            } else {
                initialAnchorAssetDecodeListenerKeys.remove(listenerKey)
            }
            return
        }
        initialAnchorAssetDecodeListenerKeys.remove(listenerKey)
        scheduleNtkAnchorAssetReadyDecode(
            index,
            page,
            "${reason}_asset_ready",
            startedAt
        )
        requestInitialContinuousPagesFromEarlyUrls(index, synchronized(pagesLock) { pages.size })
    }

    private fun scheduleNtkAnchorAssetReadyDecode(
        index: Int,
        page: PageRef,
        reason: String,
        byteStartedAt: Long
    ) {
        if (cancelled.get() || firstBitmapLogged.get() || pageRef(index) != page) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        val image = page.image ?: return
        val cached = ReaderImageCache.cachedFile(appContext, page.manga, image) ?: run {
            scheduleVisibleGeneratedCachedDecode(index, page, reason, byteStartedAt, forceRequeue = true)
            return
        }
        try {
            urgentDecode.execute {
                val decodeStartedAt = SystemClock.elapsedRealtime()
                var posted = false
                try {
                    if (
                        cancelled.get() ||
                        firstBitmapLogged.get() ||
                        pageRef(index) != page ||
                        hasDeliveredBitmap(index) ||
                        (pendingDeliveryWidths[index] ?: 0) > 0
                    ) {
                        return@execute
                    }
                    val targetWidth = targetWidth(page, true)
                    val result = decodePage(index, page, cached, targetWidth)
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
                        "ntk_anchor_asset_ready_decode_ready",
                        "reason=$reason,byteMs=${decodeStartedAt - byteStartedAt},decodeMs=${SystemClock.elapsedRealtime() - decodeStartedAt},width=${result.width}"
                    )
                    posted = true
                    postDecodeResult(Delivery(index, page, result, decodeStartedAt, targetWidth, retainWhenBusy = true))
                    cancelInitialGeneratedForegroundWorkAfterDrawableQueued(
                        page,
                        image,
                        "anchor_asset_ready_decode_posted"
                    )
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                    postPageError(index, page, e)
                } finally {
                    if (!posted && !firstBitmapLogged.get() && !hasDeliveredBitmap(index)) {
                        logNtkPagePerf(index, "ntk_anchor_asset_ready_decode_aborted", "reason=$reason")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun sameNtkAnchorAssetImage(actual: String, expected: String, fallbackIndex: Int): Boolean {
        if (actual == expected) return true
        if (!isNtkGeneratedImageUrl(actual) || !isNtkGeneratedImageUrl(expected)) return false
        val actualPage = ntkImagePageNumber(actual) ?: ntkGeneratedPageNumber(actual) ?: return false
        val expectedPage = ntkImagePageNumber(expected) ?: ntkGeneratedPageNumber(expected) ?: (fallbackIndex + 1)
        return actualPage == expectedPage
    }

    private fun ntkAnchorAssetDecodeListenerKey(index: Int, target: Manga, image: String): String {
        val page = ntkImagePageNumber(image) ?: ntkGeneratedPageNumber(image) ?: (index + 1)
        return "${target.baseMode}|${target.ntkEpisodePath.orEmpty()}|$page|${image.substringAfterLast('/')}"
    }

    private fun removeInitialAnchorAssetDecodeListenersForEpisode(target: Manga) {
        val prefix = "${target.baseMode}|${target.ntkEpisodePath.orEmpty()}|"
        initialAnchorAssetDecodeListenerKeys.removeAll { it.startsWith(prefix) }
    }

    private fun scheduleNtkAnchorCachedDecodeRetry(index: Int, reason: String, startedAt: Long) {
        if (cancelled.get() || firstBitmapLogged.get()) return
        refreshInstalledNtkGeneratedImagesFromLatestEarlyUrls(
            pageRef(index)?.manga ?: manga,
            "anchor_cached_decode_retry_$reason",
            startedAt
        )
        val now = SystemClock.elapsedRealtime()
        ntkAnchorCachedDecodeRetryUntilMs.updateAndGet { current ->
            max(current, now + NTK_ANCHOR_CACHED_DECODE_RETRY_MS)
        }
        if (tryScheduleNtkAnchorCachedDecode(index, reason, startedAt)) return
        val page = pageRef(index)
        val image = page?.image
        if (page != null && !image.isNullOrBlank()) {
            registerNtkAnchorAssetCachedDecode(index, page.manga, image, reason, startedAt)
        }
        if (!ntkAnchorCachedDecodeRetryPosted.compareAndSet(false, true)) return
        try {
            anchorPoll.execute {
                try {
                    while (
                        !cancelled.get() &&
                        !firstBitmapLogged.get() &&
                        SystemClock.elapsedRealtime() <= ntkAnchorCachedDecodeRetryUntilMs.get()
                    ) {
                        if (tryScheduleNtkAnchorCachedDecode(index, reason, startedAt)) return@execute
                        try {
                            Thread.sleep(NTK_EARLY_URL_POLL_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@execute
                        }
                    }
                } finally {
                    ntkAnchorCachedDecodeRetryPosted.set(false)
                    if (
                        !cancelled.get() &&
                        !firstBitmapLogged.get() &&
                        SystemClock.elapsedRealtime() <= ntkAnchorCachedDecodeRetryUntilMs.get()
                    ) {
                        scheduleNtkAnchorCachedDecodeRetry(index, reason, startedAt)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            ntkAnchorCachedDecodeRetryPosted.set(false)
        }
    }

    private fun tryScheduleNtkAnchorCachedDecode(index: Int, reason: String, startedAt: Long): Boolean {
        if (cancelled.get() || firstBitmapLogged.get()) return false
        val page = pageRef(index) ?: return false
        if (!isNtkSource(page.manga, title) || page.transitionTitle != null) return false
        val image = page.image ?: return false
        val cached = ReaderImageCache.cachedFile(appContext, page.manga, image) ?: run {
            logNtkPagePerf(index, "ntk_anchor_cached_decode_skip", "reason=$reason,cached=false")
            return false
        }
        if (!initialAnchorCachedDecodeStarted.compareAndSet(false, true)) {
            logNtkPagePerf(index, "ntk_anchor_cached_decode_skip", "reason=$reason,already_started=true")
            return true
        }
        try {
            initialAnchorDecode.execute {
                val decodeStartedAt = SystemClock.elapsedRealtime()
                var posted = false
                try {
                    if (
                        cancelled.get() ||
                        firstBitmapLogged.get() ||
                        pageRef(index) != page ||
                        hasDeliveredBitmap(index)
                    ) {
                        return@execute
                    }
                    val targetWidth = targetWidth(page, true)
                    val result = cachedDecodedResult(
                        index,
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
                        "ntk_anchor_cached_decode_ready",
                        "reason=$reason,waitMs=${decodeStartedAt - startedAt},decodeMs=${SystemClock.elapsedRealtime() - decodeStartedAt},width=${result.width}"
                    )
                    posted = true
                    postDecodeResult(Delivery(index, page, result, decodeStartedAt, targetWidth, retainWhenBusy = true))
                    cancelInitialGeneratedForegroundWorkAfterDrawableQueued(
                        page,
                        image,
                        "anchor_cached_decode_posted"
                    )
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                    postPageError(index, page, e)
                } finally {
                    if (!posted && !firstBitmapLogged.get() && !hasDeliveredBitmap(index)) {
                        initialAnchorCachedDecodeStarted.set(false)
                        logNtkPagePerf(index, "ntk_anchor_cached_decode_aborted", "reason=$reason")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            initialAnchorCachedDecodeStarted.set(false)
            return false
        }
        return true
    }

    private fun currentNtkInstalledImages(target: Manga): List<String> = synchronized(pagesLock) {
        val bySource = TreeMap<Int, String>()
        for (ref in pages) {
            if (ref.transitionTitle != null || !Manga.sameEpisodeIdentity(ref.manga, target)) continue
            val image = ref.image ?: continue
            if (!bySource.containsKey(ref.sourceIndex)) bySource[ref.sourceIndex] = image
        }
        bySource.values.toList()
    }

    private fun hasInitialVerifiedReplacement(current: List<String>, incoming: List<String>): Boolean {
        if (current.isEmpty() || incoming.isEmpty()) return false
        for (candidate in incoming) {
            val page = ntkImagePageNumber(candidate) ?: continue
            val index = page - 1
            if (index !in current.indices) continue
            val existing = current[index]
            if (existing == candidate) continue
            if (!isSameInitialGeneratedPageReplacement(existing, candidate)) continue
            return true
        }
        return false
    }

    private fun mergeInitialVerifiedReplacements(current: List<String>, incoming: List<String>): List<String> {
        if (current.isEmpty() || incoming.isEmpty()) return incoming
        val merged = ArrayList(current)
        for (candidate in incoming) {
            val page = ntkImagePageNumber(candidate) ?: continue
            val index = page - 1
            if (index !in merged.indices) continue
            val existing = merged[index]
            if (existing == candidate) continue
            if (isSameInitialGeneratedPageReplacement(existing, candidate)) {
                merged[index] = candidate
            }
        }
        return merged
    }

    private fun isSameInitialGeneratedPageReplacement(current: String?, incoming: String?): Boolean {
        if (current.isNullOrBlank() || incoming.isNullOrBlank() || current == incoming) return false
        if (isSameInitialProtectedCdnReplacement(current, incoming)) return true
        if (!isNtkGeneratedImageUrl(current)) return false
        val currentPage = ntkImagePageNumber(current) ?: return false
        val incomingPage = ntkImagePageNumber(incoming) ?: return false
        return currentPage == incomingPage &&
            incomingPage in 1..NTK_GENERATED_INITIAL_RECOVERY_WINDOW_PAGES &&
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

    private fun shouldLimitLargeUntrustedGeneratedEarlyUrls(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        if (urls.none { isNtkGeneratedImageUrl(it) }) return false
        if (isTrustedDirectWebtoonGeneratedEpisode(target)) return false
        val trustedApiCount = ReaderImageCache.trustedNtkImageApiCount(target.ntkEpisodePath, 0L)
        val knownCount = target.ntkImageCount
        if (knownCount > NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
            trustedApiCount < minOf(knownCount, urls.size)
        ) {
            return true
        }
        val looksLikeDefaultGeneratedGuess =
            urls.size >= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
                knownCount <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
                trustedApiCount <= 0
        if (looksLikeDefaultGeneratedGuess) return true
        if (urls.size <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
            knownCount <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD
        ) return false
        return trustedApiCount < minOf(urls.size, NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
    }

    private fun limitedLargeUntrustedGeneratedUrls(
        target: Manga,
        urls: List<String>,
        minGeneratedLimit: Int = 0
    ): List<String> {
        if (urls.isEmpty()) return urls
        val installedForEpisode = synchronized(pagesLock) {
            pages.count { ref ->
                ref.transitionTitle == null && looseSameEpisodeForAppend(ref.manga, target)
            }
        }
        val runway = ntkInitialGeneratedRecoveryPagesForTarget(target)
            .coerceAtLeast(NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
        val firstMissingPage = ReaderImageCache.knownNtkGeneratedFirstNotFoundPage(target.ntkEpisodePath)
        val missingCap = if (firstMissingPage > 1) firstMissingPage - 1 else Int.MAX_VALUE
        val anchor = currentViewportAnchor.get().takeIf { it >= 0 } ?: currentStartPage()
        val desired = maxOf(runway, anchor + runway, minGeneratedLimit)
        val limit = minOf(urls.size, desired, missingCap)
        if (limit >= urls.size) return urls
        logNtkRepositoryStage(
            target,
            "early_urls_large_untrusted_generated_limit",
            "from=${urls.size},to=$limit,installed=$installedForEpisode,anchor=$anchor," +
                "min=$minGeneratedLimit,missingPage=$firstMissingPage"
        )
        return urls.take(limit)
    }

    private fun shouldKeepManhwaGeneratedEarlyToObservedUrls(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (!isNtkManhwaEpisodePath(target.ntkEpisodePath)) return false
        val knownCount = target.ntkImageCount
        if (knownCount >= urls.size) return false
        return urls.all { isNtkGeneratedImageUrl(it) }
    }

    private fun shouldQuietNtkGeneratedExpansionAfterFirstBitmap(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (urls.none { isNtkGeneratedImageUrl(it) }) return false
        return isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath) ||
            target.baseMode == MTitle.base_webtoon
    }

    private fun shouldPublishGeneratedExpansionDuringTouch(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        if (urls.none { isNtkGeneratedImageUrl(it) }) return false
        return urls.size >= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES
    }

    private fun isInitialGeneratedFullAppendReadyForImmediatePublish(target: Manga, total: Int): Boolean {
        if (!isNtkSource(target, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        return total >= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES
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
            isNtkWebtoonEpisodePath(target.ntkEpisodePath) && knownCount > urls.size -> knownCount
            knownCount > urls.size && knownCount <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD -> knownCount
            knownCount > urls.size -> minOf(knownCount, ntkInitialGeneratedRecoveryPagesForTarget(target))
            else -> maxOf(urls.size, ntkInitialGeneratedRecoveryPagesForTarget(target))
        }
        if (desiredCount <= urls.size) return urls
        val expanded = ArrayList<String>(desiredCount)
        for (page in 1..desiredCount) {
            val generated = ntkGeneratedImageUrlForTarget(seed, target, page) ?: break
            expanded.add(generated)
        }
        if (expanded.size <= urls.size) return urls
        if (knownCount > 0 || expanded.size >= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD) {
            target.setNtkImageCount(maxOf(knownCount, expanded.size))
        }
        logNtkRepositoryStage(
            target,
            "early_urls_generated_initial_expand",
            "from=${urls.size},to=${expanded.size},known=$knownCount"
        )
        return expanded
    }

    private fun ntkInitialGeneratedRecoveryPagesForTarget(target: Manga): Int {
        if (isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            val knownCount = target.ntkImageCount
            val recoveryPages = if (
                isNtkWebtoonEpisodePath(target.ntkEpisodePath) ||
                target.baseMode == MTitle.base_webtoon
            ) {
                NTK_WEBTOON_GENERATED_INITIAL_RECOVERY_PAGES
            } else {
                NTK_GENERATED_INITIAL_RECOVERY_PAGES
            }
            return if (knownCount > 0) {
                minOf(knownCount, recoveryPages)
            } else {
                recoveryPages
            }
        }
        return NTK_GENERATED_INITIAL_RECOVERY_PAGES
    }

    private fun ntkGeneratedEarlyExpandCount(target: Manga, currentCount: Int, knownCount: Int): Int {
        if (target.baseMode == MTitle.base_comic && !isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            if (knownCount > 0) return maxOf(currentCount, knownCount)
            val comicLimit = maxOf(currentCount, NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD)
            return when {
                knownCount > currentCount -> minOf(knownCount, comicLimit)
                else -> comicLimit
            }
        }
        if (isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            return if (knownCount > currentCount) knownCount else currentCount
        }
        return when {
            knownCount > currentCount -> knownCount
            currentCount >= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD -> currentCount
            else -> currentCount
        }
    }

    private fun isNtkManhwaOrWebtoonEpisodePath(path: String?): Boolean {
        return NTK_VIEWER_EPISODE_PATH.matchEntire(path?.trim().orEmpty()) != null
    }

    private fun isNtkManhwaEpisodePath(path: String?): Boolean {
        return path.orEmpty().startsWith("/manhwa/", ignoreCase = true)
    }

    private fun isNtkWebtoonEpisodePath(path: String?): Boolean {
        return path.orEmpty().startsWith("/webtoon/", ignoreCase = true)
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
        val firstImage = pageRef(startPage)?.image.orEmpty()
        val initialGeneratedRunway =
            isNtkGeneratedImageUrl(firstImage) &&
                isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)
        val trustedApiSlugWebtoonFirst = isNtkWebtoonEpisodePath(manga.ntkEpisodePath) &&
            ReaderImageCache.isTrustedInitialNtkApiImageForEarlyStream(firstImage) &&
            !hasVerifiedGeneratedInitialRun(startPage)
        val effectiveUrlCount = if (!firstBitmapLogged.get()) {
            if (isNaverWebtoonPageImageUrl(firstImage)) {
                minOf(urlCount, startPage + 3)
            } else if (isNtkWebtoonEpisodePath(manga.ntkEpisodePath)) {
                if (initialGeneratedRunway) {
                    minOf(urlCount, startPage + NTK_GENERATED_INITIAL_CONTINUOUS_RUNWAY_PAGES)
                } else if (trustedApiSlugWebtoonFirst) {
                    minOf(urlCount, startPage + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
                } else if (hasVerifiedGeneratedInitialRun(startPage)) {
                    minOf(urlCount, startPage + ntkInitialGeneratedRecoveryPagesForTarget(manga))
                } else {
                    minOf(urlCount, startPage + NTK_WEBTOON_INITIAL_VISIBLE_RECOVERY_PAGES)
                }
            } else if (isNtkSyntheticEpisodePath(manga.ntkEpisodePath)) {
                minOf(urlCount, startPage + NTK_SYNTHETIC_INITIAL_VISIBLE_PAGES)
            } else if (isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)) {
                if (initialGeneratedRunway) {
                    minOf(urlCount, startPage + NTK_GENERATED_INITIAL_CONTINUOUS_RUNWAY_PAGES)
                } else {
                    minOf(urlCount, startPage + ntkInitialGeneratedRecoveryPagesForTarget(manga))
                }
            } else {
                urlCount
            }
        } else {
            urlCount
        }
        if (!firstBitmapLogged.get()) {
            val anchorAssetReady = ReaderImageCache.hasNtkAnchorAssetForEpisode(manga)
            val allowPreFirstInitialContinuous =
                (initialGeneratedRunway && anchorAssetReady) ||
                    trustedApiSlugWebtoonFirst ||
                    hasVerifiedGeneratedInitialRun(startPage) ||
                    isFreshExactEarlyNtkImageUrl(manga, firstImage)
            if (!allowPreFirstInitialContinuous) {
                logNtkRepositoryStage(
                    manga,
                    "early_urls_initial_continuous_defer_until_first_bitmap",
                    "start=$startPage,count=$urlCount,effective=$effectiveUrlCount,anchorAsset=$anchorAssetReady"
                )
                scheduleNtkAnchorCachedDecodeRetry(
                    startPage,
                    "initial_continuous_deferred_until_first_bitmap",
                    SystemClock.elapsedRealtime()
                )
                return
            }
            logNtkRepositoryStage(
                manga,
                "early_urls_initial_continuous_pre_first",
                "start=$startPage,count=$urlCount,effective=$effectiveUrlCount," +
                    "trustedApiSlug=$trustedApiSlugWebtoonFirst"
            )
        }
        val count = synchronized(pagesLock) { pages.size }
        if (!firstBitmapLogged.get() && ntkFirstBitmapAtMs.get() <= 0L && !initialGeneratedRunway) {
            requestPageForeground(startPage)
            logNtkRepositoryStage(
                manga,
                "early_urls_initial_continuous_anchor_only_until_first_bitmap",
                "start=$startPage,count=$urlCount,effective=$effectiveUrlCount"
            )
            return
        } else if (!firstBitmapLogged.get() && ntkFirstBitmapAtMs.get() <= 0L) {
            requestPageForeground(startPage)
            logNtkRepositoryStage(
                manga,
                "early_urls_initial_continuous_generated_runway_before_first_bitmap",
                "start=$startPage,count=$urlCount,effective=$effectiveUrlCount"
            )
        }
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
        val continuousWindowPages = if (initialGeneratedRunway) {
            NTK_GENERATED_INITIAL_CONTINUOUS_RUNWAY_PAGES
        } else {
            NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES
        }
        val last = minOf(count - 1, effectiveUrlCount - 1, startPage + continuousWindowPages - 1)
        if (last <= startPage) return
        for (index in (startPage + 1)..last) {
            val page = pageRef(index)
            val verifiedInitialGenerated = page != null &&
                shouldAllowVerifiedNearGeneratedBeforeAnchorAsset(
                    index,
                    page,
                    startPage,
                    anchor = false,
                    generation = FOREGROUND_PRIME_WARM_GENERATION
                )
            val directInitialGeneratedAhead = when {
                page != null && isNtkWebtoonEpisodePath(page.manga.ntkEpisodePath) ->
                    NTK_GENERATED_INITIAL_CONTINUOUS_RUNWAY_PAGES
                page != null && isNtkManhwaEpisodePath(page.manga.ntkEpisodePath) ->
                    NTK_GENERATED_INITIAL_CONTINUOUS_RUNWAY_PAGES
                else -> NTK_DEFAULT_GENERATED_INITIAL_DIRECT_AHEAD_PAGES
            }
            val directInitialGenerated = page != null &&
                index <= startPage + directInitialGeneratedAhead &&
                isNtkGeneratedImageUrl(page.image.orEmpty())
            val directInitialNaverOriginal = page != null &&
                index <= startPage + 2 &&
                isNaverWebtoonPageImageUrl(page.image.orEmpty())
            val delayMs = if (verifiedInitialGenerated || directInitialGenerated || directInitialNaverOriginal) {
                0L
            } else {
                NTK_INITIAL_CONTINUOUS_STAGGER_MS * (index - startPage)
            }
            val busy = directInitialNaverOriginal ||
                directInitialGenerated ||
                index <= startPage + NTK_INITIAL_CONTINUOUS_BUSY_PAGES
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
                "runway=$continuousWindowPages," +
                "staggerMs=$NTK_INITIAL_CONTINUOUS_STAGGER_MS"
        )
        ViewerWarmupManager.logMetric("reader_ntk_initial_continuous_request", (last - startPage).toLong())
    }

    private fun hasVerifiedGeneratedInitialRun(startPage: Int): Boolean {
        if (!isNtkSource(manga, title)) return false
        if (!isNtkWebtoonEpisodePath(manga.ntkEpisodePath)) return false
        val count = synchronized(pagesLock) { pages.size }
        if (count <= startPage + 1) return false
        val first = pageRef(startPage) ?: return false
        val next = pageRef(startPage + 1) ?: return false
        if (!isNtkGeneratedImageUrl(first.image.orEmpty())) return false
        if (!isNtkGeneratedImageUrl(next.image.orEmpty())) return false
        if (isFreshExactEarlyNtkImageUrl(manga, first.image)) return true
        return ReaderImageCache.hintedNtkGeneratedImageUrl(next.image.orEmpty()) != null ||
            hasFreshEarlyNtkImageUrlFor(manga, next.image, requireExact = false)
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
                        appendInitialNtkUrlsAfterEarlyInstall(
                            target,
                            urls,
                            loadStartedAt,
                            allowFirstBitmapDefer = !shouldPublishGeneratedExpansionDuringTouch(target, urls)
                        )
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
                    appendInitialNtkUrlsAfterEarlyInstall(
                        target,
                        urls,
                        loadStartedAt,
                        allowFirstBitmapDefer = !shouldPublishGeneratedExpansionDuringTouch(target, urls)
                    )
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
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            waitForInitialFullFetchQuietAfterFirstBitmap(target)
        }
        val fullCancellation = repositoryCancellation(userVisible = false)
        if (isNtkSource(target, title) && !isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            fullCancellation.prioritizeWebViewFallback()
        }
        try {
            val result = imageRepository.fetchViewerInitial(target, fullCancellation)
            val urls = imageRepository.imageUrls(target, appContext)
            logNtkRepositoryStage(
                target,
                "fetch_initial_done_after_early_deferred_full",
                "result=$result,count=${urls?.size ?: 0},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            if (!cancelled.get() && result == Title.LOAD_OK && !urls.isNullOrEmpty()) {
                appendInitialNtkUrlsAfterEarlyInstall(
                    target,
                    urls,
                    loadStartedAt,
                    allowFirstBitmapDefer = !shouldPublishGeneratedExpansionDuringTouch(target, urls)
                )
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
        allowFirstBitmapDefer: Boolean = true,
        minGeneratedLimit: Int = 0
    ) {
        if (cancelled.get() || urls.isEmpty()) return
        val verifiedApiUrls = ReaderImageCache.cachedNtkApiFallbackImages(target.ntkEpisodePath)
        val knownGeneratedCount = target.ntkImageCount
        val shouldPreferSameLengthVerifiedApi =
            shouldPreferSameLengthVerifiedApiGeneratedUrls(target, urls, verifiedApiUrls)
        val shouldReplaceWithShortVerifiedApi =
            verifiedApiUrls.isNotEmpty() &&
                verifiedApiUrls.size < urls.size &&
                (
                    isNtkSyntheticEpisodePath(target.ntkEpisodePath) ||
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
        } else if (shouldReplaceWithShortVerifiedApi || shouldPreferSameLengthVerifiedApi) {
            Log.d(
                TAG,
                "reader_ntk_generated_full_replace_with_verified_api path=${target.ntkEpisodePath}," +
                    "from=${urls.size},to=${verifiedApiUrls.size}," +
                    "reason=${if (shouldPreferSameLengthVerifiedApi) "same_length_verified" else "short_verified"}"
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
        if (shouldLimitLargeUntrustedGeneratedEarlyUrls(target, sourceUrls)) {
            sourceUrls = limitedLargeUntrustedGeneratedUrls(target, sourceUrls, minGeneratedLimit)
        }
        sourceUrls = filterKnownMissingGeneratedInitialUrls(target, sourceUrls, loadStartedAt)
        if (sourceUrls.isEmpty()) return
        if (shouldKeepCurrentNaverOriginalRefs(target, sourceUrls)) {
            logNtkRepositoryStage(
                target,
                "early_urls_generated_refresh_skip_naver_original",
                "incoming=${sourceUrls.size},installed=${synchronized(pagesLock) { pages.size }},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            return
        }
        if (firstBitmapLogged.get() &&
            isNtkSource(target, title) &&
            sourceUrls.all { isNtkGeneratedImageUrl(it) }
        ) {
            val installedForEpisode = installedDrawablePageCountForEpisode(target)
            if (installedForEpisode > sourceUrls.size &&
                !shouldAllowNtkGeneratedVerifiedShrink(target, sourceUrls)
            ) {
                Log.d(
                    TAG,
                    "reader_ntk_generated_full_skip_late_shrink path=${target.ntkEpisodePath}," +
                        "installed=$installedForEpisode,incoming=${sourceUrls.size}"
                )
                return
            }
        }
        var fullRefs = pageRefsForImages(target, sourceUrls)
        if (fullRefs.isEmpty()) return
        if (isNtkSource(target, title) &&
            isGeneratedOnlyNtkRefs(fullRefs) &&
            target.ntkImageCount > 0 &&
            fullRefs.size > target.ntkImageCount
        ) {
            val trustedApiCount = ReaderImageCache.trustedNtkImageApiCount(
                target.ntkEpisodePath,
                loadStartedAt - 1_200L
            )
            val recentEarlyFullCount = ReaderImageCache.earlyNtkImageUrls(
                target.ntkEpisodePath,
                loadStartedAt - 1_200L
            ).size
            val trustedDiscoveredBeyondDefault =
                trustedApiCount >= fullRefs.size ||
                    (
                        fullRefs.size > NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
                            recentEarlyFullCount >= fullRefs.size
                    )
            if (trustedDiscoveredBeyondDefault) {
                val previousCount = target.ntkImageCount
                target.setNtkImageCount(fullRefs.size)
                Log.d(
                    TAG,
                    "reader_ntk_generated_full_trusted_count_expand path=${target.ntkEpisodePath}," +
                        "from=$previousCount,to=${fullRefs.size},trusted=$trustedApiCount," +
                        "early=$recentEarlyFullCount"
                )
            } else {
                Log.d(
                    TAG,
                    "reader_ntk_generated_full_cap_to_verified_count path=${target.ntkEpisodePath}," +
                        "from=${fullRefs.size},to=${target.ntkImageCount},trusted=$trustedApiCount," +
                        "early=$recentEarlyFullCount"
                )
                fullRefs = fullRefs.take(target.ntkImageCount)
            }
        }
        var generatedOnlyRefs = isGeneratedOnlyNtkRefs(fullRefs)
        if (shouldSkipLateInitialEpisodeShrink(target, fullRefs, sourceUrls, loadStartedAt)) return
        if (allowFirstBitmapDefer && shouldDeferInitialFullAppendUntilFirstBitmap(fullRefs)) {
            deferInitialFullAppendUntilFirstBitmap(target, sourceUrls, loadStartedAt)
            return
        }
        if (allowFirstBitmapDefer && shouldDeferGeneratedFullAppendUntilReaderQuiet(generatedOnlyRefs)) {
            deferGeneratedFullAppendUntilReaderQuiet(target, sourceUrls, loadStartedAt)
            return
        }
        val startIndex: Int
        val total: Int
        var previousTotal = 0
        var refreshedExisting = false
        var refreshedInPlace = false
        synchronized(pagesLock) {
            if (pages.isEmpty()) return
            previousTotal = pages.size
            fullRefs = replaceNtkBoardUploadsWithGeneratedFullRefs(target, fullRefs)
            generatedOnlyRefs = isGeneratedOnlyNtkRefs(fullRefs)
            if (
            generatedOnlyRefs &&
            firstBitmapLogged.get() &&
            fullRefs.size < pages.size
        ) {
            if (!shouldAllowNtkGeneratedVerifiedShrink(target, sourceUrls)) {
                    logNtkRepositoryStage(
                        target,
                        "early_urls_refresh_full_skip_active_generated_shrink",
                        "from=${pages.size},to=${fullRefs.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    return
                }
            }
            if (
                generatedOnlyRefs &&
                fullRefs.size < pages.size &&
                !shouldAllowNtkGeneratedVerifiedShrink(target, sourceUrls)
            ) {
                logNtkRepositoryStage(
                    target,
                    "early_urls_refresh_full_skip_unverified_generated_shrink",
                    "from=${pages.size},to=${fullRefs.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                return
            }
            val replaceGeneratedSeedWithFullBoard =
                pages.size < fullRefs.size &&
                    pages.all { it.transitionTitle != null || isNtkGeneratedImageUrl(it.image.orEmpty()) } &&
                    fullRefs.any { isNtkBoardUploadImageUrl(it.image) }
            val replaceGeneratedSeedWithVerifiedUrls =
                shouldRefreshGeneratedSeedWithVerifiedUrls(target, fullRefs)
            if (pages.size >= fullRefs.size ||
                replaceGeneratedSeedWithFullBoard ||
                replaceGeneratedSeedWithVerifiedUrls
            ) {
                if (!replaceGeneratedSeedWithFullBoard && !shouldRefreshInitialNtkInstalledRefs(fullRefs)) return
                beginStructurePublish()
                refreshedExisting = true
                startIndex = 0
                if (replaceGeneratedSeedWithVerifiedUrls && pages.size == fullRefs.size) {
                    deliveredDrawableProofWidths.clear()
                    refreshedInPlace = true
                    pages.forEachIndexed { index, page ->
                        val replacement = fullRefs[index]
                        page.image = replacement.image
                        page.totalPages = fullRefs.size
                        page.pageIndex = index
                    }
                } else {
                    fullRefs.forEachIndexed { index, page ->
                        page.pageIndex = index
                        page.totalPages = fullRefs.size
                    }
                    pages.clear()
                    pages.addAll(fullRefs)
                    deliveredDrawableProofWidths.clear()
                }
                loading.clear()
                loadingPages.clear()
                loadingStartedAtMs.clear()
                urgentLoading.clear()
                urgentLoadingPages.clear()
                inFlightWidths.clear()
                failedPages.clear()
                decodedWidths.clear()
                desiredWidths.clear()
                pendingDeliveryWidths.clear()
                sourceWidths.clear()
                achievableWidths.clear()
                bytePrefetching.clear()
                fullEpisodeSourcePrefetching.clear()
                visibleGeneratedByteHedges.clear()
                visibleGeneratedDecodeHedges.clear()
                initialAnchorAssetDecodeListenerKeys.clear()
                initialGeneratedAssetDecodeListeners.clear()
                initialGeneratedCachedDecodeInFlight.clear()
                initialGeneratedDirectDecodeInFlight.clear()
                listenerDrawableDeliveries.clear()
                preRenderedInitialContinuousDeliveries.clear()
                total = pages.size
            } else {
                beginStructurePublish()
                startIndex = pages.size
                clearPageStateFromIndex(startIndex)
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
        val generatedWarmStartedBeforePublish =
            generatedOnlyRefs && firstBitmapLogged.get() && ntkGeneratedAppendPublishDelayMs() <= 0L
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
                if (!cancelled.get() && !gateGeneratedAppendNotify && !refreshedInPlace) {
                    val currentTotal = synchronized(pagesLock) { pages.size }
                    if (!refreshedExisting && total < currentTotal) {
                        logNtkRepositoryStage(
                            target,
                            "early_urls_append_full_skip_stale_publish",
                            "total=$total,current=$currentTotal,previous=$previousTotal,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                        )
                    } else if (refreshedExisting && previousTotal > total) {
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
                requestGeneratedPublishWarmPagesAfterInitialContinuous("generated_full_publish")
                if (refreshedInPlace) {
                    val start = currentStartPage()
                    requestPage(start, busy = true, anchor = true, generation = FOREGROUND_PRIME_WARM_GENERATION)
                    requestInitialContinuousPagesFromEarlyUrls(start, total)
                }
                if (gateGeneratedAppendNotify) {
                    notifyGeneratedAppendWhenNearReady(target, startIndex, total, loadStartedAt)
                }
            } else {
                warmNtkInitialPages(currentStartPage())
                val refs = synchronized(pagesLock) { pages.toList() }
                requestInitialRapidScrollGeneratedWindow(refs, "initial_full_publish")
            }
        }
        if (!posted) finishStructurePublish()
    }

    private fun requestGeneratedPublishWarmPagesAfterInitialContinuous(reason: String) {
        if (shouldDeferGeneratedFullSurfaceWorkForInitialContinuous()) {
            main.postDelayed({
                if (!cancelled.get()) requestGeneratedPublishWarmPagesAfterInitialContinuous(reason)
            }, NTK_FIRST_BITMAP_FOLLOWUP_RECHECK_MS)
            return
        }
        requestGeneratedPublishWarmPages(reason)
    }

    private fun shouldDeferGeneratedFullSurfaceWorkForInitialContinuous(): Boolean {
        if (!isNtkSource(manga, title)) return false
        val path = manga.ntkEpisodePath.orEmpty()
        val initialContinuousPath = path.startsWith("/webtoon/", ignoreCase = true) ||
            path.startsWith("/manhwa/", ignoreCase = true) ||
            manga.baseMode == MTitle.base_webtoon
        if (!initialContinuousPath) return false
        val start = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0 || start !in 0 until count) return false
        val lastRequired = minOf(count - 1, start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1)
        for (index in start..lastRequired) {
            if (!hasListenerDrawableDelivery(index)) return true
        }
        return false
    }

    private fun shouldGateGeneratedAppendNotifyUntilNearReady(startIndex: Int, total: Int): Boolean {
        if (!isNtkSource(manga, title)) return false
        val firstNearDrawable = firstGeneratedAppendDrawableIndex(startIndex, total) ?: return false
        return !hasListenerDrawableDelivery(firstNearDrawable)
    }

    private fun notifyGeneratedAppendWhenNearReady(target: Manga, startIndex: Int, total: Int, loadStartedAt: Long) {
        val firstNearDrawable = firstGeneratedAppendDrawableIndex(startIndex, total) ?: return
        val requiredLast = generatedAppendNotifyRequiredLast(startIndex, total, firstNearDrawable)
        val notify = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                val ready = hasGeneratedAppendDrawableDeliveryRange(startIndex, requiredLast)
                if (!ready) {
                    main.postDelayed(this, NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
                    return
                }
                logNtkRepositoryStage(
                    target,
                    "early_urls_append_full_notify_near_ready",
                    "firstNear=$firstNearDrawable,requiredLast=$requiredLast,total=$total,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                val publishDelayMs = ntkGeneratedAppendPublishDelayMs()
                if (publishDelayMs > 0L) {
                    logNtkRepositoryStage(
                        target,
                        "early_urls_append_full_notify_defer_reader_busy",
                        "delayMs=$publishDelayMs,total=$total,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                    )
                    main.postDelayed(this, publishDelayMs)
                    return
                }
                listener.onPagesAppended(total)
            }
        }
        notify.run()
    }

    private fun generatedAppendNotifyRequiredLast(startIndex: Int, total: Int, firstNearDrawable: Int): Int {
        if (total <= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) {
            return minOf(total - 1, pagesLastIndex())
        }
        return firstNearDrawable
    }

    private fun hasGeneratedAppendDrawableDeliveryRange(startIndex: Int, requiredLast: Int): Boolean {
        if (requiredLast < startIndex) return false
        for (index in startIndex..requiredLast) {
            if (!hasListenerDrawableDelivery(index)) return false
        }
        return true
    }

    private fun pagesLastIndex(): Int = synchronized(pagesLock) { pages.lastIndex }

    private fun ntkGeneratedAppendPublishDelayMs(): Long {
        if (!isNtkSource(manga, title) || !firstBitmapLogged.get()) return 0L
        val quietMs = maxOf(
            ntkBackgroundWarmQuietRemainingMs(),
            readerQuietRemainingMs(NTK_APPEND_INITIAL_PUBLISH_TOUCH_QUIET_MS)
        )
        return if (quietMs > 0L) {
            quietMs.coerceAtLeast(NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
        } else {
            0L
        }
    }

    private fun firstGeneratedAppendDrawableIndex(startIndex: Int, total: Int): Int? = synchronized(pagesLock) {
        if (startIndex >= total) return@synchronized null
        val last = minOf(total - 1, pages.lastIndex)
        for (index in startIndex..last) {
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
        val targetPath = target.ntkEpisodePath?.trim().orEmpty()
        val allGenerated = urls.all { isNtkGeneratedImageUrl(it) }
        if (
            targetPath.startsWith("/webtoon/", ignoreCase = true) &&
            allGenerated &&
            ntkGeneratedBatchUnavailablePaths.contains(targetPath)
        ) {
            return emptyList()
        }
        var removed = 0
        val filtered = urls.filter { image ->
            val remove = isNtkGeneratedImageUrl(image) && ReaderImageCache.isKnownNtkGeneratedNotFound(target, image)
            if (remove) removed++
            !remove
        }
        if (removed > 0) {
            if (
                targetPath.startsWith("/webtoon/", ignoreCase = true) &&
                allGenerated
            ) {
                if (filtered.isEmpty()) {
                    if (ntkGeneratedBatchUnavailablePaths.add(targetPath)) {
                        logNtkRepositoryStage(
                            target,
                            "early_urls_drop_generated_batch_after_not_found",
                            "from=${urls.size},removed=$removed,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                        )
                        removeInitialUnavailableGeneratedPages(target)
                        replaceUnavailableGeneratedInitialWithAdjacent(target, loadStartedAt)
                    }
                    return emptyList()
                }
                logNtkRepositoryStage(
                    target,
                    "early_urls_filter_generated_not_found_partial",
                    "from=${urls.size},to=${filtered.size},removed=$removed,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                return filtered
            }
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
        val knownCount = target.ntkImageCount
        if (knownCount >= urls.size) return false
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
            val count = minOf(verifiedInitial.size, fallback.size, ntkInitialGeneratedRecoveryPagesForTarget(target))
            if (count > 1) return fallback.take(count)
        }
        return fallback.take(1)
    }

    private fun installedGeneratedPageCountForCurrentEpisode(): Int = synchronized(pagesLock) {
        pages.count { page ->
            page.transitionTitle == null && isNtkGeneratedImageUrl(page.image.orEmpty())
        }
    }

    private fun installedDrawablePageCountForEpisode(target: Manga): Int = synchronized(pagesLock) {
        pages.count { page ->
            page.transitionTitle == null && looseSameEpisodeForAppend(page.manga, target)
        }
    }

    private fun shouldSkipLateInitialEpisodeShrink(
        target: Manga,
        fullRefs: List<PageRef>,
        sourceUrls: List<String>,
        loadStartedAt: Long
    ): Boolean {
        if (!firstBitmapLogged.get() || !isNtkSource(target, title) || fullRefs.isEmpty()) return false
        val installedForEpisode = installedDrawablePageCountForEpisode(target)
        val incomingForEpisode = fullRefs.count { ref ->
            ref.transitionTitle == null && looseSameEpisodeForAppend(ref.manga, target)
        }
        if (installedForEpisode <= incomingForEpisode) return false
        val knownCount = target.ntkImageCount
        val knownComplete = knownCount > 0 && incomingForEpisode >= knownCount
        if (knownComplete) return false
        logNtkRepositoryStage(
            target,
            "early_urls_refresh_full_skip_late_episode_shrink",
            "installed=$installedForEpisode,incoming=$incomingForEpisode,known=$knownCount,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        return true
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
        if (shouldPublishInitialContinuousRunwayImmediately(target, total, generatedOnlyRefs)) {
            logNtkRepositoryStage(
                target,
                "early_urls_append_full_publish_initial_runway",
                "total=$total,generated=$generatedOnlyRefs,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            return mainImmediate.postAtFrontOfQueue(publish)
        }
        val trustedDirectWebtoon = isTrustedDirectWebtoonGeneratedEpisode(target)
        if (trustedDirectWebtoon) {
            logNtkRepositoryStage(
                target,
                "early_urls_append_full_publish_trusted_direct_webtoon",
                "total=$total,generated=$generatedOnlyRefs,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            return main.postAtFrontOfQueue(publish)
        }
        if (firstBitmapLogged.get() || ntkFirstBitmapAtMs.get() > 0L) {
            if (generatedOnlyRefs) {
                logNtkRepositoryStage(
                    target,
                    "early_urls_append_full_publish_generated_immediate",
                    "total=$total,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                return main.postAtFrontOfQueue(publish)
            }
            logNtkRepositoryStage(
                target,
                "early_urls_append_full_publish_front_after_bitmap_delivery",
                "total=$total,generated=$generatedOnlyRefs,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            return if (generatedOnlyRefs) {
                main.post(publish)
            } else {
                main.postAtFrontOfQueue(publish)
            }
        }
        try {
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
        } catch (_: RejectedExecutionException) {
            if (!main.post(publish)) finishStructurePublish()
        }
        return true
    }

    private fun shouldPublishInitialContinuousRunwayImmediately(
        target: Manga,
        total: Int,
        generatedOnlyRefs: Boolean
    ): Boolean {
        if (!generatedOnlyRefs) return false
        if (total > NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        return synchronized(pagesLock) { pages.size <= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES }
    }

    private fun shouldDeferInitialFullAppendUntilFirstBitmap(fullRefs: List<PageRef>): Boolean {
        if (firstBitmapLogged.get() || ntkFirstBitmapAtMs.get() > 0L) return false
        if (!isNtkSource(manga, title)) return false
        if (!isGeneratedOnlyNtkRefs(fullRefs)) return false
        val installedCount = synchronized(pagesLock) { pages.size }
        if (
            isNtkWebtoonEpisodePath(manga.ntkEpisodePath) &&
            installedCount in 1..NTK_WEBTOON_INITIAL_VISIBLE_RECOVERY_PAGES &&
            fullRefs.size > installedCount
        ) {
            logNtkRepositoryStage(
                manga,
                "early_urls_append_full_no_defer_webtoon_initial",
                "installed=$installedCount,total=${fullRefs.size}"
            )
            return false
        }
        return installedCount > 0 && fullRefs.size > installedCount
    }

    private fun shouldDeferGeneratedFullAppendUntilReaderQuiet(generatedOnlyRefs: Boolean): Boolean {
        if (!generatedOnlyRefs) return false
        if (!isNtkSource(manga, title)) return false
        if (isTrustedDirectWebtoonGeneratedEpisode(manga)) return false
        if (!firstBitmapLogged.get() && ntkFirstBitmapAtMs.get() <= 0L) return false
        return generatedFullAppendQuietRemainingMs() > 0L
    }

    private fun isTrustedDirectWebtoonGeneratedEpisode(target: Manga): Boolean {
        if (!isNtkWebtoonEpisodePath(target.ntkEpisodePath)) return false
        val latest = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, 0L)
        if (latest.isEmpty()) return false
        val generatedWtUrls = latest.all { isNtkGeneratedImageUrl(it) } &&
            latest.any { it.contains("/wt/episodes/", ignoreCase = true) }
        if (generatedWtUrls) return true
        val trustedApiCount = ReaderImageCache.trustedNtkImageApiCount(target.ntkEpisodePath, 0L)
        if (trustedApiCount < minOf(latest.size, NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)) return false
        return latest.all {
            isNtkGeneratedImageUrl(it) ||
                ReaderImageCache.isTrustedInitialNtkApiImageForEarlyStream(it)
        }
    }

    private fun generatedFullAppendQuietRemainingMs(): Long {
        if (!isNtkSource(manga, title)) return 0L
        val touchQuiet = readerQuietRemainingMs(NTK_APPEND_INITIAL_PUBLISH_TOUCH_QUIET_MS)
        return maxOf(touchQuiet, ntkBackgroundWarmQuietRemainingMs())
    }

    private fun deferGeneratedFullAppendUntilReaderQuiet(target: Manga, urls: List<String>, loadStartedAt: Long) {
        val delayMs = generatedFullAppendQuietRemainingMs().coerceAtLeast(NTK_GENERATED_FULL_APPEND_RECHECK_MS)
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            val quietMs = generatedFullAppendQuietRemainingMs()
            if (quietMs > 0L) {
                deferGeneratedFullAppendUntilReaderQuiet(target, urls, loadStartedAt)
                return@postDelayed
            }
            appendInitialNtkUrlsAfterEarlyInstall(
                target,
                urls,
                loadStartedAt,
                allowFirstBitmapDefer = false
            )
        }, delayMs)
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

    private fun shouldKeepCurrentNaverOriginalRefs(target: Manga, sourceUrls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (sourceUrls.isEmpty() || !sourceUrls.all { isNtkGeneratedImageUrl(it) }) return false
        return synchronized(pagesLock) {
            pages.any { current ->
                current.transitionTitle == null &&
                    Manga.sameEpisodeIdentity(current.manga, target) &&
                    isNaverWebtoonPageImageUrl(current.image.orEmpty())
            }
        }
    }

    private fun isGeneratedOnlyNtkRefs(refs: List<PageRef>): Boolean {
        if (refs.isEmpty()) return false
        return refs.all { ref ->
            ref.transitionTitle != null || isNtkGeneratedImageUrl(ref.image.orEmpty())
        }
    }

    private fun isCurrentManhwaGeneratedOnlyRefs(): Boolean {
        if (!isNtkManhwaEpisodePath(manga.ntkEpisodePath)) return false
        val refs = synchronized(pagesLock) { pages.toList() }
        return isGeneratedOnlyNtkRefs(refs)
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

    private fun shouldRefreshGeneratedSeedWithVerifiedUrls(target: Manga, fullRefs: List<PageRef>): Boolean {
        if (!isNtkSource(target, title) || fullRefs.isEmpty()) return false
        if (fullRefs.all { it.transitionTitle != null || isNtkGeneratedImageUrl(it.image.orEmpty()) }) return false
        return pages.any { current ->
            current.transitionTitle == null &&
                Manga.sameEpisodeIdentity(current.manga, target) &&
                isNtkGeneratedImageUrl(current.image.orEmpty())
        }
    }

    private fun shouldPreferSameLengthVerifiedApiGeneratedUrls(
        target: Manga,
        urls: List<String>,
        verifiedApiUrls: List<String>
    ): Boolean {
        if (!isNtkSource(target, title)) return false
        if (urls.isEmpty() || verifiedApiUrls.isEmpty()) return false
        if (verifiedApiUrls.size < urls.size) return false
        if (!urls.all { isNtkGeneratedImageUrl(it) }) return false
        if (!verifiedApiUrls.all { isNtkGeneratedImageUrl(it) }) return false
        if (urls.firstOrNull() == verifiedApiUrls.firstOrNull()) return false
        val known = target.ntkImageCount
        return known <= 0 || verifiedApiUrls.size >= minOf(urls.size, known)
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
        scheduleNtkAnchorCachedDecodeRetry(startPage, "anchor_stream_after_urls", loadStartedAt)
        ViewerWarmupManager.logMetric("reader_anchor_stream_after_urls", if (started) 1L else 0L)
    }

    private fun startAdjacentForegroundStreams(
        target: Manga,
        urls: List<String>,
        direction: Int
    ) {
        if (!isNtkSource(target, title) || urls.isEmpty()) return
        val quietMs = ntkBackgroundPrepareQuietRemainingMs()
        if (quietMs > 0L) {
            Log.d(
                TAG,
                "append_adjacent_foreground_streams_defer targetId=${target.id} " +
                    "path=${target.ntkEpisodePath} direction=$direction quietMs=$quietMs"
            )
            main.postDelayed({
                if (cancelled.get()) return@postDelayed
                try {
                    control.execute { startAdjacentForegroundStreams(target, urls, direction) }
                } catch (_: RejectedExecutionException) {
                }
            }, quietMs.coerceAtLeast(NTK_ADJACENT_FOREGROUND_STREAM_RECHECK_MS))
            return
        }
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
        val generatedUnavailable = ntkGeneratedBatchUnavailablePaths.contains(source.ntkEpisodePath?.trim().orEmpty())
        if (source.ntkViewerParseReason != "unavailable" && !generatedUnavailable) return null
        val currentTitle = title ?: source.title ?: manga.title ?: return null
        if (syncNtkTitlePathFromEpisode(currentTitle, source)) {
            currentTitle.removeEps()
        }
        restoreNtkEpisodeSnapshotIfNeeded(currentTitle, source)
        if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
            Log.d(
                TAG,
                "ntk_initial_unavailable_fetch_episodes_start sourcePath=${source.ntkEpisodePath} " +
                    "titlePath=${currentTitle.path.orEmpty()} eps=${currentTitle.eps?.size ?: 0}"
            )
            val result = withRepositoryCancellation(userVisible = true) {
                imageRepository.fetchEpisodesForeground(currentTitle, it)
            }
            Log.d(
                TAG,
                "ntk_initial_unavailable_fetch_episodes_done sourcePath=${source.ntkEpisodePath} " +
                    "result=$result eps=${currentTitle.eps?.size ?: 0}"
            )
            if (cancelled.get() || result != Title.LOAD_OK) return null
        }
        attachTitle()
        val episodes = Utils.snapshotEpisodes(currentTitle)
        Log.d(
            TAG,
            "ntk_initial_unavailable_episodes sourcePath=${source.ntkEpisodePath} " +
                "count=${episodes.size} first=${episodes.firstOrNull()?.ntkEpisodePath.orEmpty()} " +
                "last=${episodes.lastOrNull()?.ntkEpisodePath.orEmpty()}"
        )
        if (episodes.isNotEmpty()) {
            manga.setEps(episodes)
            source.setEps(episodes)
            persistNtkEpisodeSnapshot(currentTitle, episodes)
        }
        source.title = currentTitle
        source.titleId = currentTitle.id
        for (direction in listOf(ReaderSurfaceView.DIRECTION_NEXT, ReaderSurfaceView.DIRECTION_PREVIOUS)) {
            var checked = 0
            val candidates = adjacentEpisodeCandidates(source, episodes, direction)
            Log.d(
                TAG,
                "ntk_initial_unavailable_candidates direction=$direction sourcePath=${source.ntkEpisodePath} " +
                    "count=${candidates.size} first=${candidates.firstOrNull()?.ntkEpisodePath.orEmpty()}"
            )
            for (candidate in candidates) {
                if (checked >= ADJACENT_EXISTING_SKIP_LIMIT) break
                candidate.title = currentTitle
                candidate.titleId = currentTitle.id
                candidate.mode = source.mode
                if (episodes.isNotEmpty()) candidate.setEps(episodes)
                Log.d(
                    TAG,
                    "ntk_initial_unavailable_candidate_probe direction=$direction sourcePath=${source.ntkEpisodePath} " +
                        "targetPath=${candidate.ntkEpisodePath} checked=$checked"
                )
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
            deliveredDrawableProofWidths.clear()
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
            startPage
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
        val quietMs = ntkBackgroundWarmQuietRemainingMs()
        if (quietMs > 0L) {
            Log.d(TAG, "reader_ntk_initial_warm_defer_quiet page=$anchor,delayMs=$quietMs")
            main.postDelayed({
                if (!cancelled.get()) warmNtkInitialPages(startPage)
            }, quietMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
            return
        }
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
        scheduleNtkFullEpisodeWarmRetry(startPage)
    }

    fun requestAllPagesForTest() {
        requestAllUndeliveredNtkPages("test")
    }

    fun pageReadinessSnapshotForTest(): ReaderSurfaceView.PageReadinessSnapshot {
        val refs = synchronized(pagesLock) { pages.toList() }
        var ready = 0
        var loadingCount = 0
        var errorCount = 0
        var cardCount = 0
        var unresolved = 0
        val loadingIndexes = ArrayList<Int>()
        val unresolvedIndexes = ArrayList<Int>()
        refs.forEachIndexed { index, page ->
            val card = page.transitionTitle != null
            val cached = hasPageSourceReady(index, page)
            val delivered =
                hasDeliveredBitmap(index) ||
                    (pendingDeliveryWidths[index] ?: 0) > 0 ||
                    (deliveredDrawableProofWidths[index] ?: 0) > 0
            val failed = failedPages.contains(index)
            val loadingActive =
                loading.contains(index) ||
                    loadingPages.containsKey(index) ||
                    urgentLoading.contains(index) ||
                    urgentLoadingPages.containsKey(index) ||
                    inFlightWidths.containsKey(index)
            when {
                card -> {
                    cardCount++
                    ready++
                }
                delivered || cached -> ready++
                failed -> {
                    errorCount++
                    unresolved++
                    unresolvedIndexes.add(index)
                }
                loadingActive -> {
                    loadingCount++
                    unresolved++
                    loadingIndexes.add(index)
                    unresolvedIndexes.add(index)
                }
                else -> {
                    unresolved++
                    unresolvedIndexes.add(index)
                }
            }
        }
        return ReaderSurfaceView.PageReadinessSnapshot(
            pageCount = refs.size,
            drawablePages = ready,
            loadingPages = loadingCount,
            errorPages = errorCount,
            cardPages = cardCount,
            unresolvedPages = unresolved,
            loadingIndexes = loadingIndexes.joinToString("|"),
            unresolvedIndexes = unresolvedIndexes.joinToString("|")
        )
    }

    private fun scheduleNtkFullEpisodeWarmRetry(startPage: Int) {
        if (!isNtkSource(manga, title)) return
        val attempt = ntkFullEpisodeWarmRetries.incrementAndGet()
        if (attempt > NTK_FULL_EPISODE_WARM_RETRY_MAX) return
        val delayMs = NTK_FULL_EPISODE_WARM_RETRY_MS * attempt
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            requestAllUndeliveredNtkPages("retry_$attempt")
            warmNtkInitialPages(startPage)
        }, delayMs)
    }

    private fun requestAllUndeliveredNtkPages(reason: String) {
        requestAllUndeliveredNtkPages(reason, 0)
    }

    private fun requestAllUndeliveredNtkPages(reason: String, startOffset: Int) {
        if (!isNtkSource(manga, title)) return
        val bulkDeferMs = generatedFullEpisodeBulkDeferMs(reason)
        if (bulkDeferMs > 0L) {
            Log.d(
                TAG,
                "reader_ntk_full_episode_bulk_defer reason=$reason,delayMs=$bulkDeferMs,offset=$startOffset"
            )
            scheduleGeneratedFullEpisodeBulkRetry(reason, startOffset, bulkDeferMs)
            return
        }
        val rawIndexes = synchronized(pagesLock) { pages.indices.toList() }
        var requested = 0
        var staleCleared = 0
        val requestedIndexes = ArrayList<Int>()
        val staleIndexes = ArrayList<Int>()
        val now = SystemClock.elapsedRealtime()
        val strictCoverageWarm =
                reason == "test" ||
                reason.startsWith("test_") ||
                reason.startsWith("append_full") ||
                reason.startsWith("generated_full_decode")
        val anchor = currentStartPage().coerceAtLeast(0)
        val indexes = if (reason.startsWith("generated_full_decode") && rawIndexes.isNotEmpty()) {
            val split = anchor.coerceIn(rawIndexes.first(), rawIndexes.last())
            rawIndexes.filter { it >= split } + rawIndexes.filter { it < split }
        } else {
            rawIndexes
        }
        val safeStartOffset = startOffset.coerceIn(0, indexes.size)
        val generatedFullSourceOnlyWarm = strictCoverageWarm &&
            indexes.size > NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES &&
            synchronized(pagesLock) { pages.all { it.transitionTitle != null || isNtkGeneratedImageUrl(it.image.orEmpty()) } }
        val avoidLargeUntrustedGeneratedFullSurfaceWarm =
            shouldAvoidLargeUntrustedGeneratedFullSurfaceWarm(reason, indexes)
        val forceFullSurfaceWarm = strictCoverageWarm &&
            indexes.size <= NTK_FULL_SURFACE_WARM_MAX_PAGES &&
            !avoidLargeUntrustedGeneratedFullSurfaceWarm
        if (strictCoverageWarm) {
            deliveryResumeAtMs.set(0L)
            flushInitialHeldDeliveries("test")
        }
        val holdGeneratedTailForInitialContinuous =
            !reason.startsWith("test") && shouldHoldNtkGeneratedTailForInitialContinuous()
        val staleLoadingMs = if (strictCoverageWarm) {
            NTK_FULL_EPISODE_TEST_STALE_LOADING_MS
        } else {
            NTK_FULL_EPISODE_STALE_LOADING_MS
        }
        var activeWarmRequests = indexes.count { index ->
            !hasDeliveredBitmap(index) &&
                (
                    loading.contains(index) ||
                        loadingPages.containsKey(index) ||
                        urgentLoading.contains(index) ||
                        urgentLoadingPages.containsKey(index) ||
                        inFlightWidths.containsKey(index) ||
                        pendingDeliveryWidths.containsKey(index)
                )
        }
        var examined = 0
        var nextOffset = -1
        for (position in safeStartOffset until indexes.size) {
            if (
                strictCoverageWarm &&
                examined >= NTK_FULL_EPISODE_WARM_MAIN_BATCH_PAGES
            ) {
                nextOffset = position
                break
            }
            examined++
            val index = indexes[position]
            if (hasDeliveredBitmap(index)) continue
            if (holdGeneratedTailForInitialContinuous &&
                index > anchor + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
            ) {
                continue
            }
            val page = pageRef(index)
            if (strictCoverageWarm && page != null && trimBlankNtkGeneratedTail(index, page)) {
                continue
            }
            val visibleWarmRange =
                max(0, anchor - NTK_FULL_EPISODE_TEST_VISIBLE_RADIUS)..(anchor + NTK_FULL_EPISODE_TEST_VISIBLE_RADIUS)
            val initialRunwayLast = anchor + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
            if (
                avoidLargeUntrustedGeneratedFullSurfaceWarm &&
                index > initialRunwayLast &&
                index !in visibleWarmRange
            ) {
                continue
            }
            val hasActiveLoadState =
                loading.contains(index) ||
                    loadingPages.containsKey(index) ||
                    urgentLoading.contains(index) ||
                    urgentLoadingPages.containsKey(index) ||
                    inFlightWidths.containsKey(index) ||
                    pendingDeliveryWidths.containsKey(index)
            val loadingStartedAt = loadingStartedAtMs[index]
            val loadingAge = loadingStartedAt?.let { now - it } ?: 0L
            if (loadingAge >= staleLoadingMs || failedPages.contains(index)) {
                loading.remove(index)
                loadingPages.remove(index)
                loadingStartedAtMs.remove(index)
                urgentLoading.remove(index)
                urgentLoadingPages.remove(index)
                inFlightWidths.remove(index)
                pendingDeliveryWidths.remove(index)
                failedPages.remove(index)
                staleCleared++
                if (staleIndexes.size < 32) staleIndexes.add(index)
            }
            if (!strictCoverageWarm && !forceFullSurfaceWarm &&
                index >= NTK_PRE_ANCHOR_VERIFIED_GENERATED_AHEAD
            ) {
                continue
            }
            if (
                generatedFullSourceOnlyWarm &&
                !forceFullSurfaceWarm &&
                index > anchor + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES &&
                index !in max(0, anchor - NTK_FULL_EPISODE_TEST_VISIBLE_RADIUS)..(anchor + NTK_FULL_EPISODE_TEST_VISIBLE_RADIUS)
            ) {
                continue
            }
            if (
                strictCoverageWarm &&
                !forceFullSurfaceWarm &&
                !hasActiveLoadState &&
                !hasDeliveredBitmap(index) &&
                activeWarmRequests >= NTK_FULL_EPISODE_TEST_ACTIVE_REQUEST_LIMIT &&
                index !in visibleWarmRange
            ) {
                continue
            }
            if (page != null && !hasPageSourceReady(index, page)) {
                forcePrefetchNtkPageSource(index, page, reason)
            }
            requestPage(
                index,
                busy = strictCoverageWarm,
                anchor = false,
                generation = if (strictCoverageWarm) FOREGROUND_PRIME_WARM_GENERATION else PRIME_WARM_GENERATION
            )
            if (strictCoverageWarm && !hasActiveLoadState && !hasDeliveredBitmap(index)) {
                activeWarmRequests++
            }
            requested++
            if (requestedIndexes.size < 32) requestedIndexes.add(index)
        }
        Log.d(
            TAG,
            "reader_ntk_full_episode_warm reason=$reason,requested=$requested," +
                "staleCleared=$staleCleared,total=${indexes.size}," +
                "offset=$safeStartOffset,examined=$examined,nextOffset=$nextOffset," +
                "requestedIndexes=${requestedIndexes.joinToString("|")}," +
                "staleIndexes=${staleIndexes.joinToString("|")}"
        )
        if (nextOffset >= 0) {
            main.postDelayed({
                if (!cancelled.get()) requestAllUndeliveredNtkPages(reason, nextOffset)
            }, NTK_FULL_EPISODE_WARM_MAIN_BATCH_DELAY_MS)
        }
    }

    private fun scheduleGeneratedFullEpisodeBulkRetry(reason: String, startOffset: Int, delayMs: Long) {
        if (!reason.startsWith("generated_full_decode")) {
            main.postDelayed({
                if (!cancelled.get()) requestAllUndeliveredNtkPages(reason, startOffset)
            }, delayMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
            return
        }
        if (!generatedFullEpisodeBulkRetryScheduled.compareAndSet(false, true)) return
        main.postDelayed({
            generatedFullEpisodeBulkRetryScheduled.set(false)
            if (!cancelled.get()) requestAllUndeliveredNtkPages(reason, startOffset)
        }, delayMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
    }

    private fun shouldAvoidLargeUntrustedGeneratedFullSurfaceWarm(reason: String, indexes: List<Int>): Boolean {
        if (!reason.startsWith("generated_full_decode")) return false
        if (!isNtkWebtoonEpisodePath(manga.ntkEpisodePath)) return false
        if (manga.ntkImageCount <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
            indexes.size <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD
        ) return false
        if (isTrustedDirectWebtoonGeneratedEpisode(manga)) return false
        if (ReaderImageCache.knownNtkGeneratedFirstNotFoundPage(manga.ntkEpisodePath) > 1) return true
        return synchronized(pagesLock) {
            pages.isNotEmpty() &&
                pages.all { it.transitionTitle != null || isNtkGeneratedImageUrl(it.image.orEmpty()) }
        }
    }

    private fun generatedFullEpisodeBulkDeferMs(reason: String): Long {
        if (!reason.startsWith("generated_full_decode")) return 0L
        if (!isImmediateNtkGeneratedUx()) return 0L
        val quietMs = ntkBackgroundWarmQuietRemainingMs()
        if (shouldHoldNtkGeneratedTailForInitialContinuous()) {
            return maxOf(quietMs, NTK_GENERATED_FULL_BULK_HOLD_RECHECK_MS)
        }
        return quietMs
    }

    private fun requestGeneratedPublishWarmPages(reason: String) {
        if (!isNtkSource(manga, title)) return
        val refs = synchronized(pagesLock) { pages.toList() }
        if (refs.isEmpty()) return
        if (!isGeneratedOnlyNtkRefs(refs) || refs.size <= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) {
            requestAllUndeliveredNtkPages(reason)
            return
        }
        if (refs.size <= NTK_GENERATED_PUBLISH_FULL_WARM_PAGE_LIMIT &&
            !shouldAvoidLargeUntrustedGeneratedFullSurfaceWarm("generated_full_decode_publish", refs.indices.toList())
        ) {
            requestAllUndeliveredNtkPages("generated_full_decode_publish")
            return
        }
        val anchor = currentStartPage().coerceIn(0, refs.lastIndex)
        val first = max(0, anchor - NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES)
        val last = minOf(refs.lastIndex, anchor + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1)
        var requested = 0
        val requestedIndexes = ArrayList<Int>()
        for (index in first..last) {
            if (hasDeliveredBitmap(index)) continue
            val page = refs.getOrNull(index) ?: continue
            if (page.transitionTitle != null) continue
            if (!hasPageSourceReady(index, page)) {
                forcePrefetchNtkPageSource(index, page, reason)
            }
            requestPage(index, busy = index != anchor, anchor = index == anchor, generation = PRIME_WARM_GENERATION)
            requested++
            if (requestedIndexes.size < 32) requestedIndexes.add(index)
        }
        Log.d(
            TAG,
            "reader_ntk_generated_publish_window_warm reason=$reason,requested=$requested," +
                "range=$first-$last,total=${refs.size},requestedIndexes=${requestedIndexes.joinToString("|")}"
        )
    }

    private fun forcePrefetchNtkPageSource(index: Int, page: PageRef, reason: String) {
        if (!isNtkSource(page.manga, title)) return
        if (page.transitionTitle != null || page.image.isNullOrEmpty()) return
        if (shouldDeferInitialInteractiveGeneratedBackground(index, page, PRIME_WARM_GENERATION)) return
        if (hasPageSourceReady(index, page)) return
        if (!fullEpisodeSourcePrefetching.add(index)) return
        try {
            sourcePrefetchNetwork.execute {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    if (!cancelled.get() && pageRef(index) == page && !hasPageSourceReady(index, page)) {
                        prefetchImageFile(
                            index,
                            page,
                            foreground = false,
                            visiblePriority = false
                        )
                        logNtkPagePerf(
                            index,
                            "full_episode_source_prefetch_done",
                            "reason=$reason,ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        decodeInitialContinuousCachedAfterSource(index, page, reason, startedAt)
                        decodeFullSurfaceCachedAfterSource(index, page, reason, startedAt)
                    }
                } catch (e: Exception) {
                    if (isExpectedCancellation(e)) {
                        logNtkPagePerf(
                            index,
                            "full_episode_source_prefetch_cancelled",
                            "reason=$reason,ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                    } else {
                        logNtkPagePerf(
                            index,
                            "full_episode_source_prefetch_error",
                            "reason=$reason,ms=${SystemClock.elapsedRealtime() - startedAt}," +
                                "source=${page.sourceIndex},path=${page.manga.ntkEpisodePath}," +
                                "image=${page.image},error=${e.javaClass.simpleName}:${e.message}"
                        )
                        recordIfUnexpected(e)
                        if (!cancelled.get() && pageRef(index) == page) {
                            postPageError(index, page, e)
                        }
                    }
                } finally {
                    fullEpisodeSourcePrefetching.remove(index)
                }
            }
        } catch (_: RejectedExecutionException) {
            fullEpisodeSourcePrefetching.remove(index)
        }
    }

    private fun warmNtkGeneratedInitialPagesLimited(startPage: Int, loadStartedAt: Long) {
        if (!isNtkSource(manga, title)) return
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val anchor = startPage.coerceIn(0, count - 1)
        if (!firstBitmapLogged.get()) {
            if (!isNtkManhwaEpisodePath(manga.ntkEpisodePath)) return
            if (!hasDeliveredOrPendingDrawable(anchor)) return
        } else {
            val quietMs = ntkBackgroundWarmQuietRemainingMs()
            if (quietMs > 0L) {
                logNtkRepositoryStage(
                    manga,
                    "early_urls_append_full_generated_limited_warm_defer_quiet",
                    "delayMs=$quietMs,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
                )
                main.postDelayed({
                    if (!cancelled.get()) warmNtkGeneratedInitialPagesLimited(startPage, loadStartedAt)
                }, quietMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
                return
            }
        }
        val warmAhead = if (shouldDeferGeneratedFullSurfaceWorkForInitialContinuous()) {
            NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        } else {
            ntkGeneratedInitialLimitedWarmPages()
        }
        val last = minOf(count - 1, anchor + warmAhead)
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

    private fun waitForInitialFullFetchQuietAfterFirstBitmap(target: Manga) {
        if (!isNtkSource(target, title) || !firstBitmapLogged.get()) return
        val firstBitmapAt = ntkFirstBitmapAtMs.get()
        if (firstBitmapAt <= 0L) return
        while (!cancelled.get()) {
            val now = SystemClock.uptimeMillis()
            val firstQuietMs = (
                firstBitmapAt + NTK_INITIAL_FULL_FETCH_AFTER_FIRST_BITMAP_QUIET_MS - now
            ).coerceAtLeast(0L)
            val interactionQuietMs = readerQuietRemainingMs(NTK_INITIAL_FULL_FETCH_TOUCH_QUIET_MS)
            val waitMs = max(firstQuietMs, interactionQuietMs)
            if (waitMs <= 0L) return
            try {
                Thread.sleep(waitMs.coerceAtMost(NTK_INITIAL_FULL_FETCH_QUIET_POLL_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun ntkGeneratedInitialLimitedForegroundPages(): Int {
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES
        } else if (isCurrentManhwaGeneratedOnlyRefs()) {
            NTK_MANHWA_GENERATED_INITIAL_VISIBLE_AHEAD_PAGES
        } else {
            NTK_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES
        }
    }

    private fun ntkGeneratedInitialLimitedBusyPages(): Int {
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_GENERATED_INITIAL_LIMITED_BUSY_PAGES
        } else if (isCurrentManhwaGeneratedOnlyRefs()) {
            NTK_MANHWA_GENERATED_INITIAL_VISIBLE_AHEAD_PAGES
        } else {
            NTK_GENERATED_INITIAL_LIMITED_BUSY_PAGES
        }
    }

    private fun ntkGeneratedInitialLimitedWarmPages(): Int {
        return if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_GENERATED_INITIAL_LIMITED_WARM_PAGES
        } else if (isCurrentManhwaGeneratedOnlyRefs()) {
            NTK_MANHWA_GENERATED_INITIAL_VISIBLE_AHEAD_PAGES
        } else {
            NTK_GENERATED_INITIAL_LIMITED_WARM_PAGES
        }
    }

    private fun ntkInitialBytePrefetchAheadPages(): Int {
        val refs = synchronized(pagesLock) { pages.toList() }
        if (isGeneratedOnlyNtkRefs(refs)) {
            return if (isNtkManhwaEpisodePath(manga.ntkEpisodePath)) {
                NTK_MANHWA_GENERATED_INITIAL_VISIBLE_AHEAD_PAGES
            } else {
                NTK_WEBTOON_INITIAL_BYTE_PREFETCH_AHEAD_PAGES
            }
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
        var deliveryIndex = index
        if (!firstBitmapLogged.get()) {
            deliveryIndex = collapseInitialMissingNtkGeneratedPrefixForPrepared(index)
        }
        if (cancelled.get() || deliveryIndex < 0 || bitmap.isRecycled) return
        if (allowInitialHold && shouldHoldInitialNtkIndex(deliveryIndex)) {
            loading.remove(deliveryIndex)
            initialPreparedBacklog.put(deliveryIndex, PreparedDelivery(bitmap, owned))?.let { previous ->
                if (previous.owned && !previous.bitmap.isRecycled) previous.bitmap.recycle()
            }
            Log.d(TAG, "reader_initial_hold_prepared page=$deliveryIndex,start=${currentStartPage()},width=${bitmap.width}")
            scheduleInitialDeliveryFallback()
            return
        }
        decodedWidths[deliveryIndex] = max(decodedWidths[deliveryIndex] ?: 0, bitmap.width)
        loading.remove(deliveryIndex)
        val preRenderedInitialAnchor =
            if (shouldPreRenderPreparedInitialAnchor(deliveryIndex, bitmap)) {
                try {
                    listener.onInitialPageDecoded(deliveryIndex, bitmap)
                } catch (e: Throwable) {
                    Log.d(
                        TAG,
                        "reader_prepared_initial_anchor_prerender_error page=$deliveryIndex," +
                            "error=${e.javaClass.simpleName}"
                    )
                    InitialPrerenderResult.NOT_RENDERED
                }
            } else {
                InitialPrerenderResult.NOT_RENDERED
            }
        if (preRenderedInitialAnchor != InitialPrerenderResult.NOT_RENDERED) {
            preRenderedInitialDeliveries.add(deliveryIndex)
            firstDrawableDelivered.compareAndSet(false, true)
            ntkCoordinator?.markFirstDrawableCommitted(deliveryIndex)
            Log.d(
                TAG,
                "reader_prepared_initial_anchor_prerender page=$deliveryIndex," +
                    "result=$preRenderedInitialAnchor,width=${bitmap.width}"
            )
        }
        trackDeliveredBitmap(deliveryIndex, bitmap, owned)
        markFirstPreparedBitmapDelivered()
        main.post {
            if (!cancelled.get()) {
                deliverInitialPagesReadyForCurrentPagesIfNeeded()
                listener.onPageReady(deliveryIndex, bitmap)
                ntkCoordinator?.markAnchorBitmapDecoded(deliveryIndex)
                ntkCoordinator?.markFirstDrawableCommitted(deliveryIndex)
                main.post { releaseInitialFanoutIfAnchorReady(deliveryIndex) }
            }
        }
    }

    private fun shouldPreRenderPreparedInitialAnchor(index: Int, bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        if (!isNtkSource(manga, title)) return false
        if (firstDrawableDelivered.get()) return false
        return index == currentStartPage()
    }

    private fun collapseInitialMissingNtkGeneratedPrefixForPrepared(index: Int): Int {
        if (index <= 0 || firstBitmapLogged.get()) return index
        val start = currentStartPage()
        if (index <= start) return index
        var removed = 0
        var removeStart = start
        var total = 0
        synchronized(pagesLock) {
            if (index !in pages.indices || start !in pages.indices) return index
            val target = pages[index]
            if (!isNtkSource(target.manga, title) ||
                !isNtkManhwaOrWebtoonEpisodePath(target.manga.ntkEpisodePath) ||
                !isNtkGeneratedImageUrl(target.image.orEmpty())
            ) {
                return index
            }
            var canCollapse = true
            for (candidateIndex in start until index) {
                val candidate = pages.getOrNull(candidateIndex) ?: return index
                if (candidate.transitionTitle != null ||
                    !Manga.sameEpisodeIdentity(candidate.manga, target.manga) ||
                    !isNtkGeneratedImageUrl(candidate.image.orEmpty()) ||
                    !ReaderImageCache.isKnownNtkGeneratedNotFound(candidate.manga, candidate.image.orEmpty())
                ) {
                    canCollapse = false
                    break
                }
            }
            if (!canCollapse) {
                removeStart = start + 1
                if (index <= removeStart) return index
                for (candidateIndex in removeStart until index) {
                    val candidate = pages.getOrNull(candidateIndex) ?: return index
                    if (candidate.transitionTitle != null ||
                        !Manga.sameEpisodeIdentity(candidate.manga, target.manga) ||
                        !isNtkGeneratedImageUrl(candidate.image.orEmpty()) ||
                        !ReaderImageCache.isKnownNtkGeneratedNotFound(candidate.manga, candidate.image.orEmpty())
                    ) {
                        return index
                    }
                }
            }
            removed = index - removeStart
            beginStructurePublish()
            repeat(removed) { pages.removeAt(removeStart) }
            pages.forEachIndexed { pageIndex, ref -> ref.pageIndex = pageIndex }
            removePageStateRange(removeStart, removed)
            total = pages.size
        }
        val posted = main.post {
            try {
                if (!cancelled.get()) listener.onPagesRemoved(removeStart, removed, total)
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
            "collapse_initial_missing_generated_prefix start=$removeStart,removed=$removed," +
                "oldIndex=$index,newIndex=${index - removed},path=${manga.ntkEpisodePath}"
        )
        return index - removed
    }

    private fun scheduleInitialMissingNtkGeneratedGapCollapse(reason: String, attempts: Int) {
        if (attempts <= 0 || cancelled.get()) return
        main.postDelayed({
            if (cancelled.get() || initialContinuousDrawableDelivered(currentStartPage())) return@postDelayed
            val collapsed = collapseInitialMissingNtkGeneratedGapToCachedPage(reason)
            if (collapsed) {
                requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), synchronized(pagesLock) { pages.size })
                return@postDelayed
            }
            scheduleInitialMissingNtkGeneratedGapCollapse(reason, attempts - 1)
        }, 80L)
    }

    private fun collapseInitialMissingNtkGeneratedGapToCachedPage(reason: String): Boolean {
        if (!isNtkSource(manga, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)) return false
        val start = currentStartPage()
        if (!initialContinuousDrawableDelivered(start)) return false
        val snapshot = synchronized(pagesLock) { pages.toList() }
        if (start !in snapshot.indices) return false
        val anchor = snapshot[start]
        if (anchor.transitionTitle != null ||
            !isNtkGeneratedImageUrl(anchor.image.orEmpty())
        ) {
            return false
        }
        val lastCandidate = minOf(snapshot.lastIndex, start + NTK_INITIAL_RAPID_SCROLL_DECODE_AHEAD_PAGES)
        for (candidateIndex in (start + 2)..lastCandidate) {
            val candidate = snapshot.getOrNull(candidateIndex) ?: continue
            if (candidate.transitionTitle != null ||
                !Manga.sameEpisodeIdentity(candidate.manga, anchor.manga) ||
                !isNtkGeneratedImageUrl(candidate.image.orEmpty())
            ) {
                continue
            }
            var gapMissing = true
            for (gapIndex in (start + 1) until candidateIndex) {
                val gap = snapshot.getOrNull(gapIndex)
                if (gap == null ||
                    gap.transitionTitle != null ||
                    !Manga.sameEpisodeIdentity(gap.manga, anchor.manga) ||
                    !isNtkGeneratedImageUrl(gap.image.orEmpty()) ||
                    !ReaderImageCache.isKnownNtkGeneratedNotFound(gap.manga, gap.image.orEmpty())
                ) {
                    gapMissing = false
                    break
                }
            }
            if (!gapMissing) continue
            if (!hasPageSourceReady(candidateIndex, candidate) &&
                !hasEarlyNtkGeneratedSuccessForPage(candidate)
            ) {
                continue
            }
            val removeStart = start + 1
            val removed = candidateIndex - removeStart
            var total = 0
            synchronized(pagesLock) {
                if (candidateIndex !in pages.indices || pages[candidateIndex] !== candidate) return false
                for (gapIndex in removeStart until candidateIndex) {
                    if (pages.getOrNull(gapIndex) !== snapshot[gapIndex]) return false
                }
                beginStructurePublish()
                repeat(removed) { pages.removeAt(removeStart) }
                pages.forEachIndexed { pageIndex, ref -> ref.pageIndex = pageIndex }
                removePageStateRange(removeStart, removed)
                total = pages.size
            }
            val posted = main.post {
                try {
                    if (!cancelled.get()) listener.onPagesRemoved(removeStart, removed, total)
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
                "collapse_initial_missing_generated_gap reason=$reason,start=$removeStart," +
                    "removed=$removed,cachedIndex=$candidateIndex,newIndex=${candidateIndex - removed}," +
                    "path=${manga.ntkEpisodePath}"
            )
            return true
        }
        return false
    }

    private fun hasEarlyNtkGeneratedSuccessForPage(page: PageRef): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val targetPage = ntkImagePageNumber(page.image.orEmpty()) ?: (page.sourceIndex + 1)
        if (targetPage <= 0) return false
        val earlyUrls = ReaderImageCache.earlyNtkGeneratedSuccessImageUrls(
            page.manga.ntkEpisodePath,
            SystemClock.elapsedRealtime() - 30000L
        )
        return earlyUrls.any { candidate ->
            ntkImagePageNumber(candidate) == targetPage &&
                ntkImageCandidateMatchesEpisode(candidate, page.manga)
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
        val now = SystemClock.uptimeMillis()
        if (busy) {
            lastUserInteractionMs.set(now)
            deliveryResumeAtMs.set(Long.MAX_VALUE)
        } else if (wasBusy) {
            deliveryResumeAtMs.set(now + IDLE_DELIVERY_RESUME_DELAY_MS)
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
            val interactiveInitial = ntkWebtoon &&
                isImmediateNtkGeneratedUx() &&
                (busy || ntkInitialInteractiveSettleRemainingMs() > 0L || viewportBusy.get())
            val initialAhead = if (interactiveInitial) {
                NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
            } else if (ntkWebtoon) {
                NTK_WEBTOON_INITIAL_DECODE_AHEAD_PAGES
            } else {
                NTK_INITIAL_DECODE_AHEAD_PAGES
            }
            windowFirstInput = first
            windowLastInput = minOf(count - 1, max(last, windowAnchor + initialAhead))
        } else {
            val restrictInitialGeneratedWindow = shouldRestrictInitialGeneratedWindowForRealUx(busy)
            windowFirstInput = first
            windowLastInput = if (ntkWebtoon && firstBitmapLogged.get()) {
                val ntkWindowAfter = if (busy) {
                    if (restrictInitialGeneratedWindow) {
                        NTK_INITIAL_INTERACTIVE_VIEWPORT_AHEAD_PAGES
                    } else {
                        NTK_WEBTOON_BUSY_WINDOW_AFTER
                    }
                } else {
                    if (restrictInitialGeneratedWindow) {
                        NTK_INITIAL_INTERACTIVE_VIEWPORT_AHEAD_PAGES
                    } else {
                        NTK_WEBTOON_WINDOW_AFTER
                    }
                }
                if (restrictInitialGeneratedWindow) {
                    minOf(count - 1, windowAnchor + ntkWindowAfter)
                } else {
                    minOf(count - 1, max(last, windowAnchor + ntkWindowAfter))
                }
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
                    if (shouldRestrictInitialGeneratedWindowForRealUx(busy)) {
                        NTK_INITIAL_INTERACTIVE_VIEWPORT_AHEAD_PAGES
                    } else {
                        NTK_WEBTOON_BUSY_DIRECTIONAL_DECODE_AHEAD
                    }
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
            if (!ntkInitialAnchorWindow && !shouldRestrictInitialGeneratedWindowForRealUx(busy)) {
                for (i in requestList) {
                    if (generatedWindow && i > generatedBusyPrefetchLast) continue
                    if (!visible.contains(i)) pageRef(i)?.let { prefetchBusyPage(i, it, generation) }
                }
            }
            trimDecodedWidth(windowAnchor, true)
            if (!ntkInitialAnchorWindow && !shouldRestrictInitialGeneratedWindowForRealUx(busy)) {
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
                } else if (i <= prefetchLast && !shouldRestrictInitialGeneratedWindowForRealUx(busy)) {
                    prefetchBusyPage(i, page, generation)
                }
            }
            trimDecodedWidth(windowAnchor, busy)
            if (!shouldRestrictInitialGeneratedWindowForRealUx(busy)) {
                maybePrefetchNtkSourceAround(windowAnchor, busy)
            }
            return
        }
        for (i in requestList) requestPage(i, busy, i == windowAnchor, generation)
        trimDecodedWidth(windowAnchor, busy)
        maybePrefetchNtkSourceAround(windowAnchor, busy)
    }

    private fun shouldRestrictInitialGeneratedWindowForRealUx(busy: Boolean): Boolean {
        if (!busy) return false
        if (!isImmediateNtkGeneratedUx()) return false
        if (!isNtkWebtoonSource(manga, title)) return false
        return shouldHoldNtkGeneratedTailForInitialContinuous()
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
        val generatedAnchor = busy && isNtkGeneratedPageRef(anchor)
        val step = if (generatedAnchor) {
            NTK_GENERATED_BUSY_SOURCE_PREFETCH_STEP
        } else if (busy) {
            NTK_BUSY_SOURCE_PREFETCH_STEP
        } else {
            NTK_IDLE_SOURCE_PREFETCH_STEP
        }
        if (previous >= 0 && abs(anchor - previous) < step) return
        val after = if (generatedAnchor) {
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
        earlyNtkImageUrlsUnregister?.invoke()
        earlyNtkImageUrlsUnregister = null
        viewportBusy.set(false)
        deliveryResumeAtMs.set(0L)
        lastUserInteractionMs.set(0L)
        urgentLoading.clear()
        visibleGeneratedByteHedges.clear()
        visibleGeneratedDecodeHedges.clear()
        initialAnchorAssetDecodeListenerKeys.clear()
        initialGeneratedAssetDecodeListeners.clear()
        initialGeneratedCachedDecodeInFlight.clear()
        initialGeneratedDirectDecodeInFlight.clear()
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
        initialAnchorDecode.shutdownNow()
        anchorPoll.shutdownNow()
        adjacentNetwork.shutdownNow()
        urgentNetwork.shutdownNow()
        urgentDecode.shutdownNow()
        primeNetwork.shutdownNow()
        sourcePrefetchNetwork.shutdownNow()
        primeDecode.shutdownNow()
        initialRecovery.shutdownNow()
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
        val quietMs = ntkAdjacentAppendStartDelayMs()
        if (quietMs > 0L) {
            scheduleDeferredAdjacentPrepare(anchor, direction, quietMs, silentMissing = true)
            return
        }
        appendAdjacentEpisode(anchor, direction, silentMissing = true)
    }

    private fun scheduleDeferredAdjacentPrepare(
        anchor: Int,
        direction: Int,
        delayMs: Long,
        silentMissing: Boolean
    ) {
        deferredAdjacentPrepareAnchor.set(anchor)
        deferredAdjacentPrepareDirection.set(direction)
        deferredAdjacentPrepareSilent.set(silentMissing)
        if (!deferredAdjacentPrepareScheduled.compareAndSet(false, true)) return
        main.postDelayed({ flushDeferredAdjacentPrepare() }, delayMs)
    }

    private fun flushDeferredAdjacentPrepare() {
        if (cancelled.get()) {
            deferredAdjacentPrepareScheduled.set(false)
            return
        }
        val quietMs = ntkAdjacentAppendStartDelayMs()
        if (quietMs > 0L) {
            main.postDelayed({ flushDeferredAdjacentPrepare() }, quietMs)
            return
        }
        val anchor = deferredAdjacentPrepareAnchor.getAndSet(-1)
        val direction = deferredAdjacentPrepareDirection.getAndSet(0)
        val silentMissing = deferredAdjacentPrepareSilent.getAndSet(true)
        deferredAdjacentPrepareScheduled.set(false)
        if (anchor >= 0 && direction != 0) {
            if (!isNtkSilentAdjacentStillNearBoundary(anchor, direction)) {
                Log.d(
                    TAG,
                    "append_adjacent_deferred_boundary_not_near direction=$direction anchor=$anchor " +
                        "path=${manga.ntkEpisodePath}"
                )
                return
            }
            appendAdjacentEpisode(anchor, direction, silentMissing = silentMissing, skipStartDelay = true)
        }
    }

    private fun ntkAdjacentCurrentInstallDelayMs(
        anchor: Int,
        direction: Int,
        sourceOverride: Manga? = null
    ): Long {
        if (!isNtkSource(manga, title)) return 0L
        if (direction == 0) return 0L
        if (isStructurePublishPending()) return NTK_ADJACENT_CURRENT_INSTALL_RECHECK_MS
        val source = sourceOverride ?: pageRef(anchor)?.manga ?: manga
        if (!isNtkManhwaOrWebtoonEpisodePath(source.ntkEpisodePath)) return 0L
        val sourcePath = source.ntkEpisodePath?.trim().orEmpty()
        val naverOriginalSource =
            sourcePath.contains("naver-", ignoreCase = true) ||
                sourcePath.contains("nv-", ignoreCase = true)
        val naverOriginalRepositoryCount = if (naverOriginalSource) {
            imageRepository.imageUrls(source, appContext).size
        } else {
            0
        }
        return synchronized(pagesLock) {
            if (pages.isEmpty()) return@synchronized NTK_ADJACENT_CURRENT_INSTALL_RECHECK_MS
            val matchingCount = pages.count { page ->
                page.transitionTitle == null && Manga.sameEpisodeIdentity(page.manga, source)
            }
            if (matchingCount <= 0) return@synchronized NTK_ADJACENT_CURRENT_INSTALL_RECHECK_MS
            val knownCount = source.ntkImageCount
            if (direction > 0 && knownCount > matchingCount) {
                val generatedCurrent = pages.any { page ->
                    page.transitionTitle == null &&
                        Manga.sameEpisodeIdentity(page.manga, source) &&
                        isNtkGeneratedImageUrl(page.image.orEmpty())
                }
                val firstMissingGeneratedPage =
                    ReaderImageCache.knownNtkGeneratedFirstNotFoundPage(source.ntkEpisodePath)
                val generatedTailConfirmed =
                    generatedCurrent &&
                        firstMissingGeneratedPage > 1 &&
                        firstMissingGeneratedPage <= matchingCount + 1
                if (generatedCurrent && !generatedTailConfirmed) {
                    Log.d(
                        TAG,
                        "append_adjacent_wait_generated_current_expand direction=$direction " +
                            "anchor=$anchor matching=$matchingCount known=$knownCount " +
                            "missingPage=$firstMissingGeneratedPage path=${source.ntkEpisodePath}"
                    )
                    return@synchronized NTK_ADJACENT_CURRENT_INSTALL_RECHECK_MS
                }
                if (anchor >= matchingCount - 1) {
                    updateNtkDisplayCountForEpisode(source, matchingCount)
                    Log.d(
                        TAG,
                        "append_adjacent_current_install_boundary_count_corrected direction=$direction " +
                            "anchor=$anchor from=$knownCount to=$matchingCount path=${source.ntkEpisodePath}"
                    )
                    return@synchronized 0L
                }
                if (knownCount - matchingCount <= 1 && anchor >= matchingCount - 2) {
                    updateNtkDisplayCountForEpisode(source, matchingCount)
                    Log.d(
                        TAG,
                        "append_adjacent_current_install_tail_count_corrected direction=$direction " +
                            "anchor=$anchor from=$knownCount to=$matchingCount path=${source.ntkEpisodePath}"
                    )
                    return@synchronized 0L
                }
                if (naverOriginalRepositoryCount > 0 &&
                    matchingCount >= naverOriginalRepositoryCount
                ) {
                    updateNtkDisplayCountForEpisode(source, matchingCount)
                    Log.d(
                        TAG,
                        "append_adjacent_current_install_naver_count_corrected direction=$direction " +
                            "anchor=$anchor from=$knownCount to=$matchingCount " +
                            "repository=$naverOriginalRepositoryCount path=${source.ntkEpisodePath}"
                    )
                    return@synchronized 0L
                }
                if (naverOriginalSource && anchor >= matchingCount - 2) {
                    updateNtkDisplayCountForEpisode(source, matchingCount)
                    Log.d(
                        TAG,
                        "append_adjacent_current_install_naver_tail_corrected direction=$direction " +
                            "anchor=$anchor from=$knownCount to=$matchingCount path=${source.ntkEpisodePath}"
                    )
                    return@synchronized 0L
                }
                Log.d(
                    TAG,
                    "append_adjacent_wait_current_install direction=$direction anchor=$anchor " +
                        "matching=$matchingCount known=$knownCount path=${source.ntkEpisodePath}"
                )
                return@synchronized NTK_ADJACENT_CURRENT_INSTALL_RECHECK_MS
            }
            0L
        }
    }

    private fun scheduleAdjacentCurrentInstallRetry(
        anchor: Int,
        direction: Int,
        delayMs: Long,
        silentMissing: Boolean
    ) {
        val source = pageRef(anchor)?.manga ?: manga
        maybeExpandCurrentNtkInstallForAdjacent(source, anchor)
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            maybeExpandCurrentNtkInstallForAdjacent(pageRef(anchor)?.manga ?: source, anchor)
            appendAdjacentEpisode(anchor, direction, silentMissing = silentMissing, skipStartDelay = true)
        }, delayMs.coerceAtLeast(NTK_ADJACENT_CURRENT_INSTALL_RECHECK_MS))
    }

    private fun updateNtkDisplayCountForEpisode(source: Manga, displayTotalPages: Int) {
        if (displayTotalPages <= 0) return
        source.setNtkImageCount(displayTotalPages)
        if (source !== manga && Manga.sameEpisodeIdentity(source, manga)) {
            manga.setNtkImageCount(displayTotalPages)
        }
    }

    private fun ntkCurrentGeneratedExpansionLimit(
        source: Manga,
        installed: Int,
        anchor: Int,
        available: Int
    ): Int {
        if (available <= 0) return 0
        val runway = ntkInitialGeneratedRecoveryPagesForTarget(source)
            .coerceAtLeast(NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
        val anchorLimit = if (anchor >= 0) anchor + runway + 1 else 0
        val installedLimit = installed + runway
        return minOf(available, maxOf(runway, anchorLimit, installedLimit))
    }

    private fun generatedCurrentEpisodeUrlsFromInstalledSeed(source: Manga, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val seed = synchronized(pagesLock) {
            pages.firstOrNull { page ->
                page.transitionTitle == null &&
                    Manga.sameEpisodeIdentity(page.manga, source) &&
                    isNtkGeneratedImageUrl(page.image.orEmpty())
            }?.image
        } ?: return emptyList()
        val generated = ArrayList<String>(count)
        for (page in 1..count) {
            val image = ntkGeneratedImageUrlForTarget(seed, source, page) ?: break
            generated.add(image)
        }
        return generated
    }

    private fun currentGeneratedUrlsForAdjacentExpansion(
        source: Manga,
        knownCount: Int,
        installed: Int
    ): List<String> {
        val path = source.ntkEpisodePath
        val cached = ReaderImageCache.earlyNtkAppendImageUrls(path, 0L)
        if (cached.size >= knownCount && cached.size > installed) return cached
        val derived = generatedCurrentEpisodeUrlsFromInstalledSeed(source, knownCount)
        if (derived.size >= knownCount && derived.size > installed) {
            logNtkRepositoryStage(
                source,
                "append_adjacent_derive_current_generated_urls",
                "installed=$installed,known=$knownCount,cached=${cached.size},derived=${derived.size}"
            )
            return derived
        }
        return cached
    }

    private fun maybeExpandCurrentNtkInstallForAdjacent(source: Manga, anchor: Int = -1) {
        if (!isNtkSource(source, title)) return
        val path = source.ntkEpisodePath ?: return
        val knownCount = source.ntkImageCount
        if (knownCount <= 0) return
        val installed = installedDrawablePageCountForEpisode(source)
        if (installed >= knownCount) return
        val latest = currentGeneratedUrlsForAdjacentExpansion(source, knownCount, installed)
        if (latest.size < knownCount || latest.size <= installed) return
        logNtkRepositoryStage(
            source,
            "append_adjacent_expand_current_before_retry",
            "from=$installed,to=${latest.size},known=$knownCount,path=$path"
        )
        val minGeneratedLimit = ntkCurrentGeneratedExpansionLimit(source, installed, anchor, latest.size)
        appendInitialNtkUrlsAfterEarlyInstall(
            source,
            latest,
            SystemClock.elapsedRealtime(),
            allowFirstBitmapDefer = false,
            minGeneratedLimit = minGeneratedLimit
        )
        requestAllUndeliveredNtkPages("current_full_before_next_retry")
    }

    fun appendAdjacentEpisode(
        anchor: Int,
        direction: Int,
        silentMissing: Boolean = false,
        skipStartDelay: Boolean = false
    ): AppendStartResult {
        if (cancelled.get()) return AppendStartResult.CANCELLED
        if (isNtkSource(manga, title) && !firstBitmapLogged.get()) return AppendStartResult.CANCELLED
        if (isNtkSource(manga, title) && !skipStartDelay) {
            val quietMs = ntkAdjacentAppendStartDelayMs()
            if (quietMs > 0L) {
                Log.d(
                    TAG,
                    "append_adjacent_start_defer_reader_busy direction=$direction anchor=$anchor " +
                        "quietMs=$quietMs path=${manga.ntkEpisodePath}"
                )
                scheduleDeferredAdjacentPrepare(anchor, direction, quietMs, silentMissing)
                return AppendStartResult.STARTED
            }
        }
        val currentInstallDelayMs = ntkAdjacentCurrentInstallDelayMs(anchor, direction)
        if (currentInstallDelayMs > 0L) {
            Log.d(
                TAG,
                "append_adjacent_start_defer_current_install direction=$direction anchor=$anchor " +
                    "delayMs=$currentInstallDelayMs path=${manga.ntkEpisodePath}"
            )
            scheduleAdjacentCurrentInstallRetry(anchor, direction, currentInstallDelayMs, silentMissing)
            return AppendStartResult.STARTED
        }
        val loadingFlag = if (direction < 0) previousAppendLoading else nextAppendLoading
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
                val installDelayMs = ntkAdjacentCurrentInstallDelayMs(anchor, direction, anchorManga)
                if (installDelayMs > 0L) {
                    Log.d(
                        TAG,
                        "append_adjacent_fetch_defer_current_install direction=$direction anchor=$anchor " +
                            "delayMs=$installDelayMs sourcePath=${anchorManga.ntkEpisodePath}"
                    )
                    main.post {
                        if (!cancelled.get()) {
                            scheduleAdjacentCurrentInstallRetry(anchor, direction, installDelayMs, silentMissing)
                        }
                    }
                    return@execute
                }
                if (isNtkAdjacentAppendBlockedByCurrentTail(anchorManga, anchor, direction)) {
                    return@execute
                }
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
                val publishUrls = completeKnownGeneratedAppendUrls(resolvedTarget, resolvedUrls)
                val warmUrls = ntkBoundaryAppendWarmUrls(resolvedTarget, publishUrls, silentMissing)
                startAdjacentForegroundStreams(resolvedTarget, warmUrls, direction)
                appendResolvedEpisode(resolvedTarget, publishUrls, direction)
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

    private fun ntkBoundaryAppendWarmUrls(
        target: Manga,
        urls: List<String>,
        silentMissing: Boolean
    ): List<String> {
        if (!silentMissing || !isNtkSource(target, title)) return urls
        if (urls.size <= NTK_APPEND_EARLY_PUBLISH_PAGES) return urls
        if (!urls.any { isNtkGeneratedImageUrl(it) }) return urls
        val limited = urls.take(NTK_APPEND_EARLY_PUBLISH_PAGES)
        Log.d(
            TAG,
            "append_adjacent_boundary_warm_limited path=${target.ntkEpisodePath} " +
                "warm=${limited.size} original=${urls.size}"
        )
        return limited
    }

    private fun completeKnownGeneratedAppendUrls(target: Manga, urls: List<String>): List<String> {
        if (!isNtkSource(target, title) || urls.isEmpty()) return urls
        if (urls.none { isNtkGeneratedImageUrl(it) }) return urls
        val knownCount = target.ntkImageCount
        if (knownCount <= urls.size || knownCount <= 0) return urls
        val seed = urls.firstOrNull { isNtkGeneratedImageUrl(it) } ?: return urls
        val expanded = ArrayList<String>(knownCount)
        for (page in 1..knownCount) {
            val generated = ntkGeneratedImageUrlForTarget(seed, target, page) ?: break
            if (ReaderImageCache.isKnownNtkGeneratedNotFound(target, generated)) break
            expanded.add(generated)
        }
        if (expanded.size <= urls.size) return urls
        target.setImgs(ArrayList(expanded))
        Log.d(
            TAG,
            "append_adjacent_generated_expand_to_known path=${target.ntkEpisodePath} " +
                "from=${urls.size} to=${expanded.size} known=$knownCount"
        )
        return expanded
    }

    private fun ntkAdjacentAppendStartDelayMs(): Long {
        if (!isNtkSource(manga, title) || !firstBitmapLogged.get()) return 0L
        val quietMs = maxOf(
            ntkBackgroundPrepareQuietRemainingMs(),
            readerQuietRemainingMs(NTK_APPEND_INITIAL_PUBLISH_TOUCH_QUIET_MS)
        )
        return if (quietMs > 0L) {
            quietMs.coerceAtLeast(NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
        } else {
            0L
        }
    }

    private fun loadAppendUrlsForCandidate(target: Manga, currentTitle: Title, direction: Int): AppendUrlLoad {
        var urls = imageRepository.imageUrls(target, appContext)
        if (urls.isNullOrEmpty() && isNtkSource(target, currentTitle)) {
            val naverOriginalCount = installNtkNaverOriginalAppendUrlsIfAvailable(target)
            if (naverOriginalCount > 0) {
                urls = imageRepository.imageUrls(target, appContext)
            }
        }
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
        return AppendUrlLoad(Title.LOAD_OK, completeKnownGeneratedAppendUrls(target, urls ?: emptyList()))
    }

    private fun loadLookaheadAppendUrls(target: Manga, currentTitle: Title, direction: Int): AppendUrlLoad {
        var urls = imageRepository.imageUrls(target, appContext)
        if (urls.isNullOrEmpty() && isNtkSource(target, currentTitle)) {
            val naverOriginalCount = installNtkNaverOriginalAppendUrlsIfAvailable(target)
            if (naverOriginalCount > 0) {
                urls = imageRepository.imageUrls(target, appContext)
            }
        }
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
        return AppendUrlLoad(Title.LOAD_OK, completeKnownGeneratedAppendUrls(target, urls ?: emptyList()))
    }

    private fun installNtkNaverOriginalAppendUrlsIfAvailable(target: Manga): Int {
        val path = target.ntkEpisodePath?.trim().orEmpty()
        if (!path.contains("/naver-", ignoreCase = true) &&
            !path.contains("/nv-", ignoreCase = true)
        ) {
            return 0
        }
        val urls = target.fetchNaverWebtoonOriginalImageUrlsForNtkPath(
            MainApplication.getHttpClient(),
            0
        )
        if (urls.isNullOrEmpty()) {
            Log.d(TAG, "append_adjacent_naver_original_miss path=$path")
            return 0
        }
        target.setImgs(ArrayList(urls))
        if (target.ntkImageCount <= 0 || target.ntkImageCount != urls.size) {
            target.setNtkImageCount(urls.size)
        }
        Log.d(
            TAG,
            "append_adjacent_naver_original_urls path=$path count=${urls.size} " +
                "first=${urls.firstOrNull()?.substringAfterLast('/').orEmpty()}"
        )
        return urls.size
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
        if (isNtkWebtoonSource(manga, title)) {
            Log.d(TAG, "ntk_adjacent_ack_preflight_skip_webtoon_visible_path path=${manga.ntkEpisodePath}")
            return
        }
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
                val client = MainApplication.getHttpClient()
                if (!client.hasCloudflareClearance() && !client.hasRecentStrictNtkAdAckProof(path)) {
                    Log.d(
                        TAG,
                        "ntk_adjacent_ack_preflight_skip_no_clearance sourcePath=$sourcePath targetPath=$path"
                    )
                    return@Thread
                }
                val ok = client.performNtkWebViewAckPreflight(path) {
                    cancelled.get()
                }
                if (cancelled.get()) {
                    Log.d(
                        TAG,
                        "ntk_adjacent_ack_preflight_cancelled sourcePath=$sourcePath targetPath=$path"
                    )
                    return@Thread
                }
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
        if (isNtkSyntheticEpisodePath(target.ntkEpisodePath) &&
            (target.ntkImageWorkId.isNullOrBlank() ||
                !target.ntkImageEpisodeId.matches(Regex("\\d{1,12}")) ||
                target.ntkImageCount <= 0)
        ) {
            Log.d(
                TAG,
                "append_adjacent_seed_generated_skip_synthetic path=${target.ntkEpisodePath} " +
                    "sourcePath=${source.ntkEpisodePath}"
            )
            return 0
        }
        val path = target.ntkEpisodePath?.trim().orEmpty()
        val match = NTK_VIEWER_EPISODE_PATH.matchEntire(path) ?: return 0
        val segment = match.groupValues[1].lowercase(Locale.ROOT)
        val inferredExtension = generatedExtensionForAppendNeighbor(source)
        val extensionCandidates = generatedExtensionCandidatesForAppend(segment, inferredExtension)
        if (inferredExtension.isEmpty() && extensionCandidates.isNotEmpty()) {
            Log.d(
                TAG,
                "append_adjacent_seed_generated_default_extensions path=$path " +
                    "sourcePath=${source.ntkEpisodePath} extensions=${extensionCandidates.joinToString("|")}"
            )
        }
        if (extensionCandidates.isEmpty()) {
            return 0
        }
        val pathWorkId = match.groupValues[2].trim()
        val pathEpisodeToken = match.groupValues[3].trim()
        val neighborGeneratedWorkId = generatedWorkIdForAppendNeighbor(source)
        val neighborDeclaredWorkId = source.ntkImageWorkId.trim()
        val inheritedWorkId = target.ntkImageWorkId.trim()
        val imageWorkId = when {
            shouldUseNeighborAppendWorkId(segment, pathWorkId, inheritedWorkId, neighborGeneratedWorkId) ->
                neighborGeneratedWorkId
            inheritedWorkId.matches(Regex("\\d{1,12}")) -> inheritedWorkId
            shouldUseNeighborAppendWorkId(segment, pathWorkId, inheritedWorkId, neighborDeclaredWorkId) ->
                neighborDeclaredWorkId
            neighborGeneratedWorkId.isNotEmpty() -> neighborGeneratedWorkId
            neighborDeclaredWorkId.isNotEmpty() -> neighborDeclaredWorkId
            inheritedWorkId.isNotEmpty() -> inheritedWorkId
            else -> pathWorkId
        }
        val recordedImageEpisodeId = target.ntkImageEpisodeId.trim()
        val candidateImageEpisodeId = if (pathEpisodeToken.matches(Regex("\\d+"))) {
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
        val existingUrls = imageRepository.imageUrls(target, appContext)
        if (!existingUrls.isNullOrEmpty() && (count <= 0 || existingUrls.size >= count)) {
            return 0
        }
        if (!imageWorkId.matches(Regex("\\d{1,12}")) ||
            !candidateImageEpisodeId.matches(Regex("\\d{1,12}")) ||
            count <= 0
        ) {
            Log.d(
                TAG,
                "append_adjacent_seed_generated_skip_invalid path=$path " +
                    "workId=$imageWorkId imageEpisodeId=$candidateImageEpisodeId count=$count " +
                    "existing=${existingUrls?.size ?: 0} neighborWorkId=$neighborGeneratedWorkId " +
                    "neighborDeclaredWorkId=$neighborDeclaredWorkId inheritedWorkId=$inheritedWorkId " +
                    "sourcePath=${source.ntkEpisodePath}"
            )
            return 0
        }
        if (
            segment == "webtoon" &&
            shouldPreferNtkApiForCanonicalWebtoonPath(pathWorkId, pathEpisodeToken)
        ) {
            val extension = generatedExtensionForAppendNeighbor(source).ifBlank { "jpg" }
            val urls = ArrayList<String>(count)
            for (page in 1..count) {
                val pageName = "p%03d.%s".format(Locale.ROOT, page, extension)
                urls.add("http://fifa.worldcup73.xyz/black/episodes/$imageWorkId/$candidateImageEpisodeId/$pageName")
            }
            target.setImgs(urls)
            ReaderImageCache.rememberEarlyNtkImageUrls(path, urls.take(NTK_APPEND_EARLY_PUBLISH_PAGES))
            Log.d(
                TAG,
                "append_adjacent_seed_generated_canonical_fast_urls targetId=${target.id} path=$path " +
                    "workId=$imageWorkId imageEpisodeId=$candidateImageEpisodeId count=${urls.size} " +
                    "extension=$extension existing=${existingUrls?.size ?: 0} sourcePath=${source.ntkEpisodePath}"
            )
            return urls.size
        }
        val verifiedCandidate = verifiedAppendGeneratedImageCandidate(
            segment,
            imageWorkId,
            candidateImageEpisodeId,
            recordedImageEpisodeId,
            pathEpisodeToken,
            extensionCandidates,
            path
        ) ?: speculativeAppendGeneratedImageCandidate(
            segment,
            imageWorkId,
            candidateImageEpisodeId,
            recordedImageEpisodeId,
            pathEpisodeToken,
            extensionCandidates,
            path
        ) ?: return 0
        val imageEpisodeId = verifiedCandidate.imageEpisodeId
        val extension = verifiedCandidate.extension
        val urls = ArrayList<String>(count)
        for (page in 1..count) {
            val pageName = "p%03d.%s".format(Locale.ROOT, page, extension)
            val url = if (segment == "webtoon") {
                "http://fifa.worldcup73.xyz/black/episodes/$imageWorkId/$imageEpisodeId/$pageName"
            } else {
                "http://apihost93.com/$segment/$imageWorkId/$imageEpisodeId/$pageName"
            }
            urls.add(url)
        }
        target.setImgs(urls)
        ReaderImageCache.rememberEarlyNtkImageUrls(path, urls.take(NTK_APPEND_EARLY_PUBLISH_PAGES))
        Log.d(
            TAG,
                "append_adjacent_seed_generated_urls targetId=${target.id} path=$path " +
                "workId=$imageWorkId imageEpisodeId=$imageEpisodeId count=${urls.size} " +
                "extension=$extension existing=${existingUrls?.size ?: 0} " +
                "neighborWorkId=$neighborGeneratedWorkId neighborDeclaredWorkId=$neighborDeclaredWorkId " +
                "inheritedWorkId=$inheritedWorkId sourcePath=${source.ntkEpisodePath}"
        )
        return urls.size
    }

    private fun speculativeAppendGeneratedImageCandidate(
        segment: String,
        imageWorkId: String,
        candidateImageEpisodeId: String,
        recordedImageEpisodeId: String,
        pathEpisodeToken: String,
        extensions: List<String>,
        path: String
    ): AppendGeneratedCandidate? {
        if (segment != "manhwa" && segment != "webtoon") return null
        if (!imageWorkId.matches(Regex("\\d{1,12}"))) return null
        val imageEpisodeId = when {
            candidateImageEpisodeId.matches(Regex("\\d{1,12}")) -> candidateImageEpisodeId
            recordedImageEpisodeId.matches(Regex("\\d{1,12}")) -> recordedImageEpisodeId
            pathEpisodeToken.matches(Regex("\\d{1,12}")) -> pathEpisodeToken
            else -> return null
        }
        val extension = extensions
            .map { it.trim().lowercase(Locale.ROOT) }
            .firstOrNull { it.matches(Regex("jpg|jpeg|png|webp")) }
            ?: return null
        Log.d(
            TAG,
            "append_adjacent_seed_generated_speculative_urls path=$path " +
                "workId=$imageWorkId imageEpisodeId=$imageEpisodeId extension=$extension " +
                "candidate=$candidateImageEpisodeId recorded=$recordedImageEpisodeId " +
                "pathEpisodeId=$pathEpisodeToken"
        )
        return AppendGeneratedCandidate(imageEpisodeId, extension)
    }

    private fun shouldUseNeighborAppendWorkId(
        segment: String,
        pathWorkId: String,
        targetWorkId: String,
        neighborWorkId: String
    ): Boolean {
        val neighbor = neighborWorkId.trim()
        if (!neighbor.matches(Regex("\\d{1,12}"))) return false
        val target = targetWorkId.trim()
        if (target == neighbor) return false
        if (target.isEmpty()) return true
        if (!target.matches(Regex("\\d{1,12}"))) return true
        if (segment != "webtoon" && segment != "manhwa") return false
        return pathWorkId.matches(Regex("\\d{1,12}")) && target == pathWorkId
    }

    private fun generatedExtensionCandidatesForAppend(
        segment: String,
        inferredExtension: String
    ): List<String> {
        val out = LinkedHashSet<String>()
        val normalized = inferredExtension.trim().lowercase(Locale.ROOT)
        if (normalized.matches(Regex("jpg|jpeg|png|webp"))) out.add(normalized)
        if (segment == "webtoon" || segment == "manhwa") {
            out.add("jpeg")
            out.add("jpg")
        }
        return out.toList()
    }

    private fun verifiedAppendGeneratedImageCandidate(
        segment: String,
        imageWorkId: String,
        candidateImageEpisodeId: String,
        recordedImageEpisodeId: String,
        pathEpisodeToken: String,
        extensions: List<String>,
        path: String
    ): AppendGeneratedCandidate? {
        val candidates = LinkedHashSet<String>()
        if (candidateImageEpisodeId.matches(Regex("\\d{1,12}"))) candidates.add(candidateImageEpisodeId)
        if (recordedImageEpisodeId.matches(Regex("\\d{1,12}"))) candidates.add(recordedImageEpisodeId)
        if (pathEpisodeToken.matches(Regex("\\d{1,12}"))) candidates.add(pathEpisodeToken)
        val extensionCandidates = extensions
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.matches(Regex("jpg|jpeg|png|webp")) }
            .distinct()
        if (candidates.isEmpty() || extensionCandidates.isEmpty()) return null
        val hits = ConcurrentHashMap.newKeySet<String>()
        val latch = CountDownLatch(candidates.size * extensionCandidates.size)
        val startedAt = SystemClock.elapsedRealtime()
        for (episodeId in candidates) {
            for (extension in extensionCandidates) {
                Thread({
                    try {
                        if (cancelled.get()) return@Thread
                        val first = ntkGeneratedInitialUrl(segment, imageWorkId, episodeId, 1, extension)
                        if (isReachableNtkGeneratedProbe(first)) hits.add("$episodeId|$extension")
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }, "ntk-append-generated-probe").apply {
                    isDaemon = true
                    start()
                }
            }
        }
        latch.await(NTK_GENERATED_INITIAL_PROBE_TOTAL_WAIT_MS, TimeUnit.MILLISECONDS)
        var hitEpisodeId: String? = null
        var hitExtension: String? = null
        for (episodeId in candidates) {
            for (extension in extensionCandidates) {
                if (hits.contains("$episodeId|$extension")) {
                    hitEpisodeId = episodeId
                    hitExtension = extension
                    break
                }
            }
            if (hitEpisodeId != null) break
        }
        val hit = hitEpisodeId
        if (hit == null) {
            Log.d(
                TAG,
                "append_adjacent_seed_generated_probe_miss path=$path workId=$imageWorkId " +
                    "candidate=$candidateImageEpisodeId recorded=$recordedImageEpisodeId " +
                    "pathEpisodeId=$pathEpisodeToken extensions=${extensionCandidates.joinToString("|")} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return null
        }
        if (hit != candidateImageEpisodeId) {
            Log.d(
                TAG,
                "append_adjacent_seed_generated_probe_reselect path=$path workId=$imageWorkId " +
                    "candidate=$candidateImageEpisodeId selected=$hit recorded=$recordedImageEpisodeId " +
                    "pathEpisodeId=$pathEpisodeToken extension=$hitExtension " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
        }
        return AppendGeneratedCandidate(hit, hitExtension ?: extensionCandidates.first())
    }

    private fun generatedWorkIdForAppendNeighbor(source: Manga): String {
        val earlyUrls = ReaderImageCache.earlyNtkImageUrls(source.ntkEpisodePath, 0L)
        val early = earlyUrls.firstOrNull { isNtkGeneratedImageUrl(it) }
        val earlyWorkId = generatedImageWorkId(early)
        if (earlyWorkId.isNotEmpty()) return earlyWorkId
        val sourceUrls = imageRepository.imageUrls(source, appContext)
        return generatedImageWorkId(sourceUrls.firstOrNull { isNtkGeneratedImageUrl(it) })
    }

    private fun canonicalWebtoonAppendImageEpisodeId(
        segment: String,
        pathWorkId: String,
        pathEpisodeToken: String,
        imageWorkId: String,
        recordedImageEpisodeId: String
    ): String {
        if (segment != "webtoon") return recordedImageEpisodeId
        if (
            pathEpisodeToken.matches(Regex("\\d{1,12}")) &&
            recordedImageEpisodeId.matches(Regex("\\d{1,12}")) &&
            recordedImageEpisodeId != pathEpisodeToken
        ) {
            Log.d(
                TAG,
                "append_adjacent_seed_generated_path_episode_override pathWorkId=$pathWorkId " +
                    "imageWorkId=$imageWorkId recordedImageEpisodeId=$recordedImageEpisodeId " +
                    "pathEpisodeId=$pathEpisodeToken"
            )
            return pathEpisodeToken
        }
        if (recordedImageEpisodeId.matches(Regex("\\d{1,12}"))) return recordedImageEpisodeId
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

    private fun shouldPreferNtkApiForCanonicalWebtoonPath(
        pathWorkId: String,
        pathEpisodeToken: String
    ): Boolean {
        if (!pathWorkId.matches(Regex("\\d{1,12}"))) return false
        if (!pathEpisodeToken.matches(Regex("\\d{1,12}"))) return false
        return pathWorkId.toLongOrNull()?.let {
            it >= NTK_CANONICAL_WEBTOON_API_FIRST_MIN_WORK_ID
        } ?: true
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

    private fun generatedImageWorkId(image: String?): String {
        if (image.isNullOrBlank()) return ""
        Regex("/black/episodes/(\\d+)/", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("/blacktoon/episodes/(\\d+)/", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("/wt/episodes/(\\d+)/", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("/(?:manhwa|webtoon)/(\\d+)/", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        return ""
    }

    private fun inheritNtkAppendGeneratedHints(target: Manga, source: Manga, currentTitle: Title) {
        if (!isNtkSource(target, currentTitle) || !isNtkSource(source, currentTitle)) return
        if (target.titleId != source.titleId && target.titleId != currentTitle.id) return
        var inherited = false
        val path = target.ntkEpisodePath?.trim().orEmpty()
        val match = NTK_VIEWER_EPISODE_PATH.matchEntire(path)
        val segment = match?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT).orEmpty()
        val pathWorkId = match?.groupValues?.getOrNull(2)?.trim().orEmpty()
        val targetWorkId = target.ntkImageWorkId.trim()
        val sourceGeneratedWorkId = generatedWorkIdForAppendNeighbor(source)
        val sourceDeclaredWorkId = source.ntkImageWorkId.trim()
        val sourceWorkId = if (sourceGeneratedWorkId.matches(Regex("\\d{1,12}"))) {
            sourceGeneratedWorkId
        } else {
            sourceDeclaredWorkId
        }
        if (shouldUseNeighborAppendWorkId(segment, pathWorkId, targetWorkId, sourceWorkId)) {
            target.ntkImageWorkId = sourceWorkId
            inherited = true
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
                    "previousWorkId=$targetWorkId sourceGeneratedWorkId=$sourceGeneratedWorkId " +
                    "sourceDeclaredWorkId=$sourceDeclaredWorkId pathWorkId=$pathWorkId " +
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
            val initialResult = if (isNtkSource(target, currentTitle) && preferApiFirst) {
                fetchGeneratedNtkAppendUrlsWithEarlyHandoff(target, "api-strict")
            } else if (isNtkSource(target, currentTitle) && !syntheticNtkPath) {
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
            val earlyWaitMs = if (mode == "api-strict") {
                NTK_APPEND_EARLY_API_STRICT_HANDOFF_WAIT_MS
            } else {
                NTK_APPEND_EARLY_GENERATED_WAIT_MS
            }
            val deadline = SystemClock.elapsedRealtime() + earlyWaitMs
            while (!cancelled.get() && !task.isDone && SystemClock.elapsedRealtime() < deadline) {
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
                (performNtkAppendAckPreflight(target) ||
                    waitForStrictNtkAckProof(target, NTK_APPEND_API_STRICT_ACK_RETRY_WAIT_MS))
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
                if (!cancelled.get() &&
                    (performNtkAppendAckPreflight(target) ||
                        waitForStrictNtkAckProof(target, NTK_APPEND_API_STRICT_ACK_RETRY_WAIT_MS))
                ) {
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

    private fun performNtkAppendAckPreflight(target: Manga): Boolean {
        val path = target.ntkEpisodePath?.trim().orEmpty()
        if (path.isEmpty() || cancelled.get()) return false
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val client = MainApplication.getHttpClient()
            if (!client.hasCloudflareClearance() && !client.hasRecentStrictNtkAdAckProof(path)) {
                Log.d(
                    TAG,
                    "append_adjacent_api_strict_ack_preflight_skip_no_clearance path=$path " +
                        "ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return false
            }
            val ok = client.performNtkWebViewAckPreflight(path) {
                cancelled.get()
            }
            Log.d(
                TAG,
                "append_adjacent_api_strict_ack_preflight path=$path ok=$ok " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            ok
        } catch (e: Exception) {
            recordIfUnexpected(e)
            Log.d(
                TAG,
                "append_adjacent_api_strict_ack_preflight_error path=$path " +
                    "error=${e.javaClass.simpleName}:${e.message} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            false
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
        val repositoryUrls = imageRepository.imageUrls(target, appContext)
        if (repositoryUrls.isNotEmpty()) {
            val completeUrls = completeKnownGeneratedAppendUrls(target, repositoryUrls)
            Log.d(
                TAG,
                "append_adjacent_repository_urls_installed targetId=${target.id} " +
                    "path=${target.ntkEpisodePath} images=${completeUrls.size}"
            )
            return completeUrls.size
        }
        var earlyUrls = ReaderImageCache.earlyNtkAppendImageUrls(
            target.ntkEpisodePath,
            SystemClock.elapsedRealtime() - 30000L
        )
        if (earlyUrls.isEmpty()) {
            earlyUrls = ReaderImageCache.earlyNtkImageUrls(
                target.ntkEpisodePath,
                SystemClock.elapsedRealtime() - 30000L
            )
        }
        if (earlyUrls.isEmpty()) return 0
        val completeUrls = completeKnownGeneratedAppendUrls(target, earlyUrls)
        target.setImgs(ArrayList(completeUrls))
        Log.d(
            TAG,
            "append_adjacent_early_generated_installed targetId=${target.id} " +
                "path=${target.ntkEpisodePath} images=${completeUrls.size} early=${earlyUrls.size}"
        )
        return completeUrls.size
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
        val ntkGeneratedAppend = isNtkSource(target, title) &&
            pageRefs.isNotEmpty() &&
            pageRefs.all { isNtkGeneratedImageUrl(it.image.orEmpty()) }
        if (ntkGeneratedAppend) {
            Log.d(
                TAG,
                "reader_repository_stage stage=generated_append_skip_transition_card," +
                    "path=${target.ntkEpisodePath},count=${pageRefs.size},direction=$direction"
            )
            return pageRefs
        }
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
        if (!autoCut || shouldSkipInitialNtkGeneratedAutoSplitRefs(target, urls)) {
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

    private fun shouldSkipInitialNtkGeneratedAutoSplitRefs(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title) || urls.isEmpty()) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        return urls.all { isNtkGeneratedImageUrl(it) }
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
            val holdUntilReady = prependedHoldUntilReadyCount(target, refs)
            val gateNtkPrependNotify =
                shouldGateAdjacentPrependNotifyUntilNearReady(target, inserted, holdUntilReady)
            val posted = main.post {
                try {
                    finishStructurePublish()
                    if (cancelled.get()) return@post
                    if (warm) {
                        warmPrependedEpisode(inserted)
                        warmPrependedEpisodeStart(inserted)
                    }
                    if (gateNtkPrependNotify) {
                        notifyAdjacentPrependWhenNearReady(target, inserted, total, cardOffset, transitionTitle, holdUntilReady)
                    } else {
                        listener.onPagesPrepended(total, inserted, holdUntilReady)
                        if (cardOffset >= 0) listener.onPageCard(cardOffset, transitionTitle)
                        redeliverReadyPrependedStart(inserted)
                    }
                } finally {
                    if (isStructurePublishPending()) finishStructurePublish()
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
                clearPageStateFromIndex(cardIndex)
                refs.forEachIndexed { offset, page -> page.pageIndex = cardIndex + offset }
                pages.addAll(refs)
                total = pages.size
            }
            Log.d(TAG, "append_adjacent_resolved_inserted direction=$direction targetId=${target.id} path=${target.ntkEpisodePath} inserted=$inserted total=$total")
            val gateNtkGeneratedNotify =
                shouldGateAdjacentAppendNotifyUntilNearReady(target, cardIndex, total)
            val posted = main.post {
                try {
                    finishStructurePublish()
                    if (cancelled.get()) return@post
                    if (gateNtkGeneratedNotify) {
                        if (warm && shouldWarmAppendedEpisode(cardIndex)) warmAppendedEpisode(cardIndex, total)
                        warmFullNtkAppendEpisode(cardIndex, total, "append_full_before_publish")
                        Log.d(
                            TAG,
                            "append_adjacent_publish_defer_until_near_ready targetId=${target.id} " +
                                "path=${target.ntkEpisodePath} cardIndex=$cardIndex ready=" +
                                "${generatedAppendNearReadyCount(cardIndex, total)} " +
                                "required=${requiredGeneratedAppendReadyPages(target)} total=$total"
                        )
                        notifyAdjacentAppendWhenNearReady(
                            target,
                            cardIndex,
                            total,
                            cardOffset,
                            transitionTitle
                        )
                    } else {
                        listener.onPagesAppended(total)
                        if (cardOffset >= 0) listener.onPageCard(cardIndex + cardOffset, transitionTitle)
                        if (warm && shouldWarmAppendedEpisode(cardIndex)) warmAppendedEpisode(cardIndex, total)
                        warmFullNtkAppendEpisode(cardIndex, total, "append_full_after_notify")
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

    private fun prependedHoldUntilReadyCount(target: Manga, refs: List<PageRef>): Int {
        if (!isNtkSource(target, title) || !firstBitmapLogged.get()) return 0
        if (!isNtkManhwaEpisodePath(target.ntkEpisodePath)) return 0
        val drawableCount = refs.count { it.transitionTitle == null }
        if (drawableCount <= 0) return 0
        val knownCount = when {
            target.ntkImageCount > 1 -> target.ntkImageCount
            drawableCount > 1 -> drawableCount
            else -> 0
        }
        if (knownCount <= 1) return 1
        return minOf(NTK_OBSERVED_MANHWA_APPEND_READY_PAGES, knownCount, drawableCount)
    }

    private fun shouldGateAdjacentPrependNotifyUntilNearReady(
        target: Manga,
        inserted: Int,
        requiredReady: Int
    ): Boolean {
        if (!isNtkSource(manga, title) || !firstBitmapLogged.get()) return false
        if (!isNtkManhwaEpisodePath(target.ntkEpisodePath)) return false
        if (inserted <= 0 || requiredReady <= 0) return false
        return generatedPrependNearReadyCount(inserted, requiredReady) < requiredReady
    }

    private fun notifyAdjacentPrependWhenNearReady(
        target: Manga,
        inserted: Int,
        total: Int,
        cardOffset: Int,
        transitionTitle: String,
        requiredReady: Int
    ) {
        val notify = object : Runnable {
            override fun run() {
                if (cancelled.get()) return
                val ready = generatedPrependNearReadyCount(inserted, requiredReady)
                if (ready < requiredReady) {
                    main.postDelayed(this, NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
                    return
                }
                Log.d(
                    TAG,
                    "append_adjacent_prepend_notify_near_ready targetId=${target.id} path=${target.ntkEpisodePath} " +
                        "ready=$ready required=$requiredReady inserted=$inserted total=$total"
                )
                val publishDelayMs = ntkAdjacentPrependPublishDelayMs()
                if (publishDelayMs > 0L) {
                    Log.d(
                        TAG,
                        "append_adjacent_prepend_notify_defer_active_view path=${target.ntkEpisodePath} " +
                            "delayMs=$publishDelayMs viewportAnchor=${currentViewportAnchor.get()}"
                    )
                    main.postDelayed(this, publishDelayMs)
                    return
                }
                listener.onPagesPrepended(total, inserted, requiredReady)
                if (cardOffset >= 0) listener.onPageCard(cardOffset, transitionTitle)
                redeliverReadyPrependedStart(inserted)
            }
        }
        notify.run()
    }

    private fun ntkAdjacentPrependPublishDelayMs(): Long {
        if (!isNtkSource(manga, title) || !firstBitmapLogged.get()) return 0L
        val backgroundQuietMs = ntkBackgroundPrepareQuietRemainingMs()
        val touchQuietMs = readerQuietRemainingMs(NTK_APPEND_INITIAL_PUBLISH_TOUCH_QUIET_MS)
        val quietMs = maxOf(backgroundQuietMs, touchQuietMs)
        if (quietMs > 0L) return quietMs.coerceAtLeast(NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
        val viewportAnchor = currentViewportAnchor.get()
        if (viewportAnchor > 0) return NTK_PREPEND_NOTIFY_BOUNDARY_RECHECK_MS
        return 0L
    }

    private fun generatedPrependNearReadyCount(inserted: Int, requiredReady: Int): Int = synchronized(pagesLock) {
        if (inserted <= 0 || pages.isEmpty()) return@synchronized 0
        val last = minOf(inserted - 1, pages.lastIndex)
        var ready = 0
        for (index in 0..last) {
            val page = pages.getOrNull(index) ?: continue
            if (page.transitionTitle != null) continue
            if (!hasListenerDrawableDelivery(index)) break
            ready++
            if (ready >= requiredReady) break
        }
        ready
    }

    private fun shouldGateAdjacentAppendNotifyUntilNearReady(target: Manga, cardIndex: Int, total: Int): Boolean {
        if (!isNtkSource(manga, title) || !firstBitmapLogged.get()) return false
        val firstNearDrawable = firstGeneratedAppendDrawableIndex(cardIndex, total) ?: return false
        return !hasGeneratedAppendNearReady(target, cardIndex, total) ||
            !hasListenerDrawableDelivery(firstNearDrawable)
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
                if (!hasGeneratedAppendNearReady(target, cardIndex, total)) {
                    main.postDelayed(this, NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS)
                    return
                }
                val ready = generatedAppendNearReadyCount(cardIndex, total)
                val required = requiredGeneratedAppendReadyPages(target)
                Log.d(
                    TAG,
                    "append_adjacent_notify_near_ready targetId=${target.id} path=${target.ntkEpisodePath} " +
                        "firstNear=$firstNearDrawable ready=$ready required=$required total=$total"
                )
                listener.onPagesAppended(total)
                if (cardOffset >= 0) listener.onPageCard(cardIndex + cardOffset, transitionTitle)
            }
        }
        notify.run()
    }

    private fun hasGeneratedAppendNearReady(target: Manga, start: Int, total: Int): Boolean {
        return generatedAppendNearReadyCount(start, total) >= requiredGeneratedAppendReadyPages(target)
    }

    private fun requiredGeneratedAppendReadyPages(target: Manga): Int {
        if (isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) {
            val known = if (target.ntkImageCount > 0) target.ntkImageCount else NTK_APPEND_EARLY_PUBLISH_PAGES
            return minOf(NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES, known)
        }
        return 1
    }

    private fun generatedAppendNearReadyCount(start: Int, total: Int): Int = synchronized(pagesLock) {
        val first = firstGeneratedAppendDrawableIndex(start, total) ?: return@synchronized 0
        val last = minOf(total - 1, pages.lastIndex)
        var ready = 0
        for (index in first..last) {
            val page = pages.getOrNull(index) ?: continue
            if (page.transitionTitle != null) continue
            if (!hasListenerDrawableDelivery(index)) break
            ready++
            if (ready >= requiredGeneratedAppendReadyPages(page.manga)) break
        }
        ready
    }

    private fun shouldWarmAppendedEpisode(cardIndex: Int): Boolean {
        if (!isNtkSource(manga, title)) return true
        val anchor = currentViewportAnchor.get()
        if (anchor < 0) return false
        val near = anchor >= cardIndex - NTK_APPEND_WARM_BOUNDARY_PAGES
        if (!near) {
            Log.d(
                TAG,
                "append_adjacent_warm_deferred_offscreen cardIndex=$cardIndex,anchor=$anchor,path=${manga.ntkEpisodePath}"
            )
        }
        return near
    }

    private fun warmFullNtkAppendEpisode(cardIndex: Int, total: Int, reason: String) {
        if (cancelled.get() || !isNtkSource(manga, title) || cardIndex < 0 || total <= cardIndex) return
        if (!ntkAppendFullWarmBatches.add(cardIndex)) {
            Log.d(
                TAG,
                "append_full_warm_skip_active cardIndex=$cardIndex,total=$total,reason=$reason"
            )
            return
        }
        requestAllUndeliveredNtkPages(reason, cardIndex)
        scheduleNtkAppendFullWarmRetry(cardIndex, 1)
    }

    private fun scheduleNtkAppendFullWarmRetry(cardIndex: Int, attempt: Int) {
        if (attempt > NTK_APPEND_FULL_WARM_RETRY_MAX) {
            ntkAppendFullWarmBatches.remove(cardIndex)
            return
        }
        val delayMs = NTK_APPEND_FULL_WARM_RETRY_MS * attempt
        main.postDelayed({
            if (cancelled.get() || !isNtkSource(manga, title)) {
                ntkAppendFullWarmBatches.remove(cardIndex)
                return@postDelayed
            }
            if (isNtkAppendTailReady(cardIndex)) {
                ntkAppendFullWarmBatches.remove(cardIndex)
                return@postDelayed
            }
            if (isNtkAppendTailActivelyLoading(cardIndex)) {
                Log.d(
                    TAG,
                    "append_full_retry_defer_active cardIndex=$cardIndex,attempt=$attempt"
                )
                scheduleNtkAppendFullWarmRetry(cardIndex, attempt + 1)
                return@postDelayed
            }
            requestAllUndeliveredNtkPages("append_full_retry_$attempt", cardIndex)
            if (!isNtkAppendTailReady(cardIndex)) {
                scheduleNtkAppendFullWarmRetry(cardIndex, attempt + 1)
            } else {
                ntkAppendFullWarmBatches.remove(cardIndex)
            }
        }, delayMs)
    }

    private fun isNtkAppendTailReady(cardIndex: Int): Boolean {
        val refs = synchronized(pagesLock) { pages.toList() }
        if (cardIndex < 0 || cardIndex >= refs.size) return true
        for (index in cardIndex until refs.size) {
            val page = refs[index]
            if (page.transitionTitle != null) continue
            if (!isNtkSource(page.manga, title)) continue
            if (!hasDeliveredBitmap(index)) return false
        }
        return true
    }

    private fun isNtkAppendTailActivelyLoading(cardIndex: Int): Boolean {
        val refs = synchronized(pagesLock) { pages.toList() }
        if (cardIndex < 0 || cardIndex >= refs.size) return false
        for (index in cardIndex until refs.size) {
            val page = refs[index]
            if (page.transitionTitle != null) continue
            if (!isNtkSource(page.manga, title)) continue
            if (loading.contains(index) ||
                loadingPages.containsKey(index) ||
                urgentLoading.contains(index) ||
                urgentLoadingPages.containsKey(index) ||
                inFlightWidths.containsKey(index) ||
                pendingDeliveryWidths.containsKey(index) ||
                primedDeliveryBacklog.containsKey(index)
            ) {
                return true
            }
        }
        return false
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
        return (match.groupValues[1].equals("webtoon", ignoreCase = true) &&
            !match.groupValues[2].matches(Regex("\\d+"))) ||
            !match.groupValues[3].matches(Regex("\\d+"))
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
        shiftConcurrentMap(deliveredDrawableProofWidths, delta)
        shiftConcurrentSet(failedPages, delta)
        shiftConcurrentSet(listenerDrawableDeliveries, delta)
        inFlightWidths.clear()
        loading.clear()
        loadingPages.clear()
        loadingStartedAtMs.clear()
        urgentLoading.clear()
        urgentLoadingPages.clear()
        bytePrefetching.clear()
        fullEpisodeSourcePrefetching.clear()
        preAnchorFallbackRetries.clear()
        initialAdjacentDecodeRetries.clear()
        visibleGeneratedByteHedges.clear()
        visibleGeneratedDecodeHedges.clear()
        initialAnchorAssetDecodeListenerKeys.clear()
        initialGeneratedAssetDecodeListeners.clear()
        initialGeneratedCachedDecodeInFlight.clear()
        initialGeneratedDirectDecodeInFlight.clear()
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

    private fun applyHintedNtkGeneratedImage(index: Int, page: PageRef, reason: String): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val originalImage = page.image ?: return false
        if (!isNtkGeneratedImageUrl(originalImage)) return false
        val replacement = ReaderImageCache.hintedNtkGeneratedImageUrl(originalImage) ?: return false
        if (shouldKeepCurrentGeneratedImageOverHint(page, originalImage, replacement)) return false
        var changed = false
        synchronized(pagesLock) {
            if (index !in pages.indices || pages[index] !== page) return false
            for (ref in pages) {
                if (
                    ref.transitionTitle == null &&
                    ref.sourceIndex == page.sourceIndex &&
                    ref.image == originalImage &&
                    Manga.sameEpisodeIdentity(ref.manga, page.manga)
                ) {
                    ref.image = replacement
                    changed = true
                }
            }
        }
        if (!changed) return false
        failedPages.remove(index)
        decodedWidths.remove(index)
        desiredWidths.remove(index)
        pendingDeliveryWidths.remove(index)
        sourceWidths.remove(index)
        achievableWidths.remove(index)
        inFlightWidths.remove(index)
        if (loadingPages[index] === page) {
            loadingPages.remove(index, page)
            loading.remove(index)
        }
        if (urgentLoadingPages[index] === page) {
            urgentLoadingPages.remove(index, page)
            urgentLoading.remove(index)
        }
        Log.d(
            TAG,
            "reader_ntk_generated_hint_apply index=$index,source=${page.sourceIndex}," +
                "reason=$reason,from=${originalImage.substringAfterLast('/')},to=${replacement.substringAfterLast('/')}"
        )
        ViewerWarmupManager.logMetric("reader_ntk_generated_hint_apply", index.toLong())
        return true
    }

    private fun shouldKeepCurrentGeneratedImageOverHint(
        page: PageRef,
        originalImage: String,
        replacement: String
    ): Boolean {
        val originalExt = ntkImageExtension(originalImage)
        val replacementExt = ntkImageExtension(replacement)
        if (originalExt.isEmpty() || replacementExt.isEmpty()) return false
        if (
            page.manga.ntkEpisodePath.orEmpty().startsWith("/webtoon/", ignoreCase = true) &&
            replacementExt == "jpg" &&
            originalExt != "jpg" &&
            page.sourceIndex in 0 until NTK_GENERATED_INITIAL_RECOVERY_PAGES
        ) {
            val hasVerifiedOriginal = ReaderImageCache
                .earlyNtkGeneratedSuccessImageUrls(
                    page.manga.ntkEpisodePath,
                    SystemClock.elapsedRealtime() - 30000L
                )
                .any { it == originalImage }
            if (!hasVerifiedOriginal) return false
        }
        if (originalExt == "jpg" || replacementExt != "jpg") return false
        if (ReaderImageCache.isKnownNtkGeneratedNotFound(page.manga, originalImage)) return false
        Log.d(
            TAG,
            "reader_ntk_generated_hint_preserve_current source=${page.sourceIndex}," +
                "from=${originalImage.substringAfterLast('/')},to=${replacement.substringAfterLast('/')}"
        )
        return true
    }

    private fun ntkImageExtension(image: String): String {
        return Regex("(?i)\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    }

    private fun requestPage(index: Int, busy: Boolean, anchor: Boolean, generation: Int = windowGeneration.get()) {
        if (cancelled.get()) return
        val visibleIntent = busy || anchor
        var page = pageRef(index) ?: return
        if (
            isStructurePublishPending() &&
            !shouldAllowInitialRequestDuringStructurePublish(index, page, visibleIntent)
        ) {
            if (visibleIntent && isNtkSource(manga, title)) {
                Log.d(TAG, "reader_visible_request_skip_structure page=$index,busy=$busy,anchor=$anchor,generation=$generation")
            }
            return
        }
        if (applyHintedNtkGeneratedImage(index, page, "request_page")) {
            page = pageRef(index) ?: return
        }
        if (skipKnownMissingNtkGeneratedPage(index, page, "request_page")) return
        if (isNtkSource(page.manga, title) &&
            shouldHoldNtkGeneratedTailForInitialContinuous() &&
            index > currentStartPage() + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1 &&
            generation != windowGeneration.get()
        ) {
            logNtkPagePerf(index, "generated_tail_hold_initial_continuous", "generation=$generation")
            return
        }
        if (shouldDeferPostInitialContinuousNtkRequest(index, page, busy, anchor, generation)) return
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
        val targetWidth = targetWidth(page, busy)
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
        val ntkInitialContinuousPrimeVisible = busy &&
            generation == FOREGROUND_PRIME_WARM_GENERATION &&
            isNtkSource(page.manga, title) &&
            index in currentStartPage() until
                (currentStartPage() + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)
        val ntkInitialRapidGeneratedVisible = isInitialRapidGeneratedUrgent(index, page, busy, generation)
        val actualWindowVisible = anchor || (busy && generation >= 0) ||
            preAnchorFallbackVisible || ntkInitialNearPrimeVisible ||
            ntkInitialContinuousPrimeVisible || ntkInitialRapidGeneratedVisible
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
                return
            }
            return
        }
        val activeWidth = inFlightWidths[index] ?: 0
        if (activeWidth >= effectiveTargetWidth && loading.contains(index)) {
            if (visiblePriority && isNtkSource(page.manga, title)) {
                val activeStartedAt = loadingStartedAtMs[index] ?: 0L
                Log.d(
                    TAG,
                    "reader_visible_request_skip_active page=$index,active=$activeWidth," +
                        "target=$effectiveTargetWidth,urgent=${urgentLoading.contains(index)},generation=$generation"
                )
                val initialAnchorDirect = shouldUseInitialGeneratedAnchorDirectDecode(index, page)
                if (
                    (initialAnchorDirect || shouldUseInitialGeneratedCachedDecodeGuard(index, page)) &&
                    !hasDeliveredOrPendingDrawable(index) &&
                    scheduleInitialGeneratedDirectDecode(
                        index,
                        page,
                        effectiveTargetWidth,
                        "visible_active_skip",
                        if (activeStartedAt > 0L) activeStartedAt else SystemClock.elapsedRealtime(),
                        allowAnchor = initialAnchorDirect
                    )
                ) {
                    logNtkPagePerf(index, "visible_active_skip_direct_decode", "target=$effectiveTargetWidth")
                    return
                }
                promoteVisibleGeneratedCachedDecode(index, page, "active_skip")
                hedgeVisibleGeneratedByteFetch(index, page, "active_skip")
                val activeAgeMs = if (activeStartedAt > 0L) {
                    SystemClock.elapsedRealtime() - activeStartedAt
                } else {
                    Long.MAX_VALUE
                }
                if (!urgentLoading.contains(index) &&
                    loadingPages[index] === page &&
                    shouldRestartVisibleActiveRequest(index, page, anchor, activeAgeMs)
                ) {
                    loading.remove(index)
                    loadingPages.remove(index, page)
                    loadingStartedAtMs.remove(index)
                    inFlightWidths.remove(index)
                    Log.d(
                        TAG,
                        "reader_visible_request_promote_active page=$index,target=$effectiveTargetWidth," +
                            "ageMs=$activeAgeMs,image=${page.image?.substringAfterLast('/')}"
                    )
                } else {
                    return
                }
            }
            return
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
            loadingStartedAtMs[index] = SystemClock.elapsedRealtime()
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
                    delayMs = if (
                        (!firstBitmapLogged.get() && index == currentStartPage()) ||
                        shouldUseInitialGeneratedCachedDecodeGuard(index, page)
                    ) {
                        0L
                    } else {
                        NTK_VISIBLE_GENERATED_BYTE_HEDGE_DELAY_MS
                    }
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
            (visiblePriority && isNtkSource(page.manga, title))
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
                cachedDecodedResult(index, originalPage, targetWidth, allowPreviewCache)?.let { cached ->
                    logNtkPagePerf(index, "cache_hit", "target=$targetWidth,width=${cached.width}")
                    clearPageLoadState(index, page, ownsLoading, urgent)
                    postDecodeResult(Delivery(index, originalPage, cached, SystemClock.elapsedRealtime(), targetWidth, retainWhenBusy))
                    ViewerWarmupManager.logMetric("reader_decoded_cache_hit", index.toLong())
                    return@execute
                }
                if (!anchor && !urgent && !foregroundPrime && !visiblePriority) {
                    prefetchImageFile(index, originalPage)
                }
                val adjacentYieldMs = initialAdjacentGeneratedDecodeYieldMs(index, originalPage, anchor)
                if (adjacentYieldMs > 0L) {
                    Log.d(
                        TAG,
                        "reader_ntk_initial_adjacent_decode_yield page=$index,start=${currentStartPage()}," +
                            "delayMs=$adjacentYieldMs,image=${originalPage.image?.substringAfterLast('/')}"
                    )
                    scheduleInitialAdjacentGeneratedDecodeRetry(index, originalPage, generation, adjacentYieldMs)
                    clearPageLoadState(index, page, ownsLoading, urgent)
                    return@execute
                }
                if (visiblePriority &&
                    shouldUseInitialGeneratedCachedDecodeGuard(index, originalPage) &&
                    !hasDeliveredOrPendingDrawable(index)
                ) {
                    val byteStartedAt = SystemClock.elapsedRealtime()
                    val directStarted = scheduleInitialGeneratedDirectDecode(
                        index,
                        originalPage,
                        targetWidth,
                        "initial_continuous_visible_wait",
                        byteStartedAt
                    )
                    if (!directStarted) {
                        scheduleVisibleGeneratedCachedDecode(
                            index,
                            originalPage,
                            "initial_continuous_visible_wait",
                            byteStartedAt,
                            forceRequeue = true
                        )
                        hedgeVisibleGeneratedByteFetch(
                            index,
                            originalPage,
                            "initial_continuous_visible_preview",
                            0L
                        )
                    }
                    logNtkPagePerf(
                        index,
                        "initial_continuous_preview_only",
                        "target=$targetWidth,directStarted=$directStarted"
                    )
                    clearPageLoadState(index, page, ownsLoading, urgent)
                    return@execute
                }
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
                        if (shouldUseNtkInitialGeneratedDisplayDecode(index, originalPage) &&
                            hasDeliveredOrPendingDrawable(index)
                        ) {
                            if (hasDeliveredBitmap(index)) {
                                logNtkPagePerf(index, "decode_skip_pending_initial_generated", "target=$targetWidth")
                                return@execute
                            }
                            if (!visiblePriority) {
                                logNtkPagePerf(index, "decode_skip_pending_initial_generated", "target=$targetWidth")
                                return@execute
                            }
                            promoteVisiblePendingDelivery(index)
                            if (hasDeliveredBitmap(index)) {
                                logNtkPagePerf(index, "decode_skip_promoted_initial_generated", "target=$targetWidth")
                                return@execute
                            }
                            logNtkPagePerf(index, "decode_continue_pending_visible_generated", "target=$targetWidth")
                        }
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
        if (shouldHoldInitialGeneratedAnchorExclusive(index, page, start)) {
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_request_deferred_anchor_exclusive page=$index,start=$start," +
                    "anchor=$anchor,generation=$generation,image=${page.image?.substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_request_deferred_anchor_exclusive", index.toLong())
            schedulePreAnchorFallbackRetry(index, page, generation)
            return true
        }
        if (isNtkGeneratedImageUrl(page.image.orEmpty()) &&
            !ReaderImageCache.hasNtkAnchorAssetForEpisode(page.manga)
        ) {
            if (shouldUseInitialGeneratedStreamRunwayGuard(index, page)) {
                Log.d(
                    TAG,
                    "reader_ntk_pre_anchor_request_allowed_generated_runway page=$index,start=$start," +
                        "anchor=$anchor,generation=$generation,image=${page.image?.substringAfterLast('/')}"
                )
                ViewerWarmupManager.logMetric(
                    "reader_ntk_pre_anchor_request_allowed_generated_runway",
                    index.toLong()
                )
                return false
            }
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

    private fun shouldHoldInitialGeneratedAnchorExclusive(index: Int, page: PageRef, start: Int): Boolean {
        if (firstBitmapLogged.get()) return false
        if (index == start) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        if (shouldUseInitialGeneratedStreamRunwayGuard(index, page)) return false
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
        if (!ReaderImageCache.hasNtkAnchorAssetForEpisode(page.manga)) return false
        val minNearVisible = (start - NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES).coerceAtLeast(0)
        val initialContinuousLast = start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        val maxAhead = if (
            index <= initialContinuousLast &&
            isNtkWebtoonEpisodePath(page.manga.ntkEpisodePath)
        ) {
            NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        } else {
            NTK_PRE_ANCHOR_VERIFIED_GENERATED_AHEAD
        }
        if (index < minNearVisible || index > start + maxAhead) return false
        if (index == start) return false
        val image = page.image.orEmpty()
        if (!isNtkGeneratedImageUrl(image)) return false
        if (isFreshExactEarlyNtkImageUrl(page.manga, image)) return true
        if (hasFreshEarlyNtkImageUrlFor(page.manga, image, requireExact = false)) return true
        if (ntkCoordinator?.allowsPreAnchorFallback(index, page.image, "verifiedNearGeneratedBeforeAnchorAsset") != true) return false
        return hasFreshEarlyNtkImageUrlFor(page.manga, image, requireExact = false)
    }

    private fun isFreshExactEarlyNtkImageUrl(target: Manga, image: String?): Boolean {
        return hasFreshEarlyNtkImageUrlFor(target, image, requireExact = true)
    }

    private fun hasFreshEarlyNtkImageUrlFor(target: Manga, image: String?, requireExact: Boolean): Boolean {
        val requestImage = image.orEmpty()
        if (requestImage.isBlank()) return false
        val earlyUrls = ReaderImageCache.earlyNtkImageUrls(
            target.ntkEpisodePath,
            SystemClock.elapsedRealtime() - 30000L
        )
        if (earlyUrls.isEmpty()) return false
        val normalizedImage = Utils.viewerImageRequestUrl(requestImage, target.baseMode)
        val exactMatch = earlyUrls.any {
            it == requestImage || Utils.viewerImageRequestUrl(it, target.baseMode) == normalizedImage
        }
        if (exactMatch) return true
        val requestPage = ntkImagePageNumber(requestImage) ?: ntkGeneratedPageNumber(requestImage)
        if (requestPage != null && isNtkGeneratedImageUrl(requestImage)) {
            val pageMatch = earlyUrls.any { early ->
                (ntkImagePageNumber(early) ?: ntkGeneratedPageNumber(early)) == requestPage
            }
            if (pageMatch) return true
        }
        return !requireExact
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
            val latest = pageRef(index) ?: return@postDelayed
            if (!sameNtkSourceSlot(page, latest)) return@postDelayed
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_request_retry page=$index,start=${currentStartPage()}," +
                    "generation=$generation,image=${latest.image?.substringAfterLast('/')}"
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
        if (!firstBitmapLogged.get() &&
            index in (currentStartPage() + 1)..(currentStartPage() + NTK_PRE_ANCHOR_VERIFIED_GENERATED_AHEAD) &&
            isNtkGeneratedImageUrl(image.orEmpty()) &&
            hasFreshEarlyNtkImageUrlFor(target, image, requireExact = false)
        ) {
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_stream_allowed_by_verified_run page=$index,start=${currentStartPage()}," +
                    "source=$source,image=${image.orEmpty().substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_stream_allowed_by_verified_run", index.toLong())
            return true
        }
        if (!firstBitmapLogged.get() &&
            isFreshExactEarlyNtkImageUrl(target, image) &&
            index in (currentStartPage() + 1)..(currentStartPage() + NTK_PRE_ANCHOR_VERIFIED_GENERATED_AHEAD)
        ) {
            Log.d(
                TAG,
                "reader_ntk_pre_anchor_stream_allowed_by_early_url page=$index,start=${currentStartPage()}," +
                    "source=$source,image=${image.orEmpty().substringAfterLast('/')}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_pre_anchor_stream_allowed_by_early_url", index.toLong())
            return true
        }
        return ntkCoordinator?.assertForegroundStreamPermit(index, permit, image, source) ?: true
    }

    private fun initialAdjacentGeneratedDecodeYieldMs(index: Int, page: PageRef, anchor: Boolean): Long {
        if (anchor) return 0L
        if (!isNtkSource(page.manga, title)) return 0L
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return 0L
        val start = currentStartPage()
        if (index !in (start + 1)..(start + NTK_INITIAL_DIRECT_DELIVERY_PAGES)) return 0L
        if (
            index < start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES &&
            (isFreshExactEarlyNtkImageUrl(page.manga, page.image) ||
                hasFreshEarlyNtkImageUrlFor(page.manga, page.image, requireExact = false) ||
                ReaderImageCache.hasActiveInitialNtkGeneratedFetch(page.manga, page.image))
        ) {
            return 0L
        }
        page.image?.let { image ->
            if (ReaderImageCache.cachedFile(appContext, page.manga, image) != null) return 0L
        }
        val firstBitmapAt = ntkFirstBitmapAtMs.get()
        if (firstBitmapLogged.get() && firstBitmapAt > 0L) {
            if (isNtkManhwaEpisodePath(page.manga.ntkEpisodePath)) return 0L
            if (isNtkWebtoonEpisodePath(page.manga.ntkEpisodePath)) return 0L
            val quietMs = readerQuietRemainingMs(NTK_INITIAL_ADJACENT_DECODE_TOUCH_QUIET_MS)
            val initialQuietMs = (
                NTK_INITIAL_ADJACENT_DECODE_AFTER_FIRST_QUIET_MS -
                    (SystemClock.uptimeMillis() - firstBitmapAt)
                ).coerceAtLeast(0L)
            return max(quietMs, initialQuietMs)
        }
        if (hasDeliveredBitmap(start) || (pendingDeliveryWidths[start] ?: 0) > 0) return 0L
        return if (loading.contains(start) || urgentLoading.contains(start)) {
            NTK_PRE_ANCHOR_FALLBACK_RETRY_MS
        } else {
            0L
        }
    }

    private fun scheduleInitialAdjacentGeneratedDecodeRetry(
        index: Int,
        page: PageRef,
        generation: Int,
        delayMs: Long
    ) {
        if (!initialAdjacentDecodeRetries.add(index)) return
        main.postDelayed({
            initialAdjacentDecodeRetries.remove(index)
            if (cancelled.get()) return@postDelayed
            val latest = pageRef(index) ?: return@postDelayed
            if (!sameNtkSourceSlot(page, latest)) return@postDelayed
            requestPage(index, busy = true, anchor = false, generation = generation)
        }, delayMs.coerceIn(NTK_PRE_ANCHOR_FALLBACK_RETRY_MS, NTK_INITIAL_ADJACENT_DECODE_RETRY_MAX_MS))
    }

    private fun sameNtkSourceSlot(expected: PageRef, actual: PageRef): Boolean {
        if (expected.transitionTitle != actual.transitionTitle) return false
        if (expected.sourceIndex != actual.sourceIndex) return false
        return Manga.sameEpisodeIdentity(expected.manga, actual.manga)
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
                loadingStartedAtMs.remove(index)
            }
        }
        urgentLoadingPages[index]?.let { urgentPage ->
            if (urgentPage !== page) {
                urgentLoadingPages.remove(index, urgentPage)
                urgentLoading.remove(index)
            }
        }
    }

    private fun shouldRestartVisibleActiveRequest(
        index: Int,
        page: PageRef,
        anchor: Boolean,
        activeAgeMs: Long
    ): Boolean {
        if (!isNtkSource(page.manga, title)) return true
        if (anchor || index == currentStartPage()) return activeAgeMs >= NTK_VISIBLE_ANCHOR_RESTART_MIN_MS
        if (shouldUseInitialGeneratedCachedDecodeGuard(index, page)) {
            return activeAgeMs >= NTK_VISIBLE_INITIAL_CONTINUOUS_RESTART_MIN_MS
        }
        return activeAgeMs >= NTK_VISIBLE_ACTIVE_RESTART_MIN_MS
    }

    private fun clearPageLoadState(index: Int, page: PageRef, ownsLoading: Boolean, urgent: Boolean) {
        if (ownsLoading && loadingPages[index] === page) {
            loadingPages.remove(index, page)
            loading.remove(index)
            inFlightWidths.remove(index)
            loadingStartedAtMs.remove(index)
        }
        if (urgent && urgentLoadingPages[index] === page) {
            urgentLoadingPages.remove(index, page)
            urgentLoading.remove(index)
        }
    }

    private fun prefetchBusyPage(index: Int, page: PageRef, generation: Int) {
        if (skipKnownMissingNtkGeneratedPage(index, page, "byte_prefetch")) return
        if (
            hasDeliveredBitmap(index) ||
            loading.contains(index) ||
            urgentLoading.contains(index) ||
            (pendingDeliveryWidths[index] ?: 0) > 0
        ) {
            return
        }
        if (shouldDeferInitialInteractiveGeneratedBackground(index, page, generation)) return
        if (isNtkSource(page.manga, title) && isNtkGeneratedImageUrl(page.image.orEmpty())) {
            if (hasPageSourceReady(index, page)) {
                decodeInitialContinuousCachedAfterSource(index, page, "byte_prefetch_cached", SystemClock.elapsedRealtime())
                logNtkPagePerf(index, "byte_prefetch_skip_source_ready", "generation=$generation")
                return
            }
            val lastDoneAt = bytePrefetchCompletedAtMs[index] ?: 0L
            if (lastDoneAt > 0L &&
                SystemClock.elapsedRealtime() - lastDoneAt < NTK_BYTE_PREFETCH_REPEAT_SUPPRESS_MS
            ) {
                logNtkPagePerf(
                    index,
                    "byte_prefetch_skip_recent",
                    "generation=$generation,ageMs=${SystemClock.elapsedRealtime() - lastDoneAt}"
                )
                return
            }
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
                        bytePrefetchCompletedAtMs[index] = SystemClock.elapsedRealtime()
                        logNtkPagePerf(index, "byte_prefetch_done", "ms=${SystemClock.elapsedRealtime() - startedAt}")
                        decodeInitialContinuousCachedAfterSource(index, page, "byte_prefetch", startedAt)
                        decodeFullSurfaceCachedAfterSource(index, page, "byte_prefetch", startedAt)
                    }
                } catch (e: Exception) {
                    if (isExpectedCancellation(e)) {
                        logNtkPagePerf(index, "byte_prefetch_cancelled", "ms=${SystemClock.elapsedRealtime() - startedAt}")
                    } else {
                        logNtkPagePerf(index, "byte_prefetch_error", "ms=${SystemClock.elapsedRealtime() - startedAt},error=${e.javaClass.simpleName}")
                        if (isNtkGeneratedImageUrl(page.image.orEmpty()) && isImageNotFoundError(e)) {
                            postPageError(index, page, e)
                        }
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

    private fun decodeInitialContinuousCachedAfterSource(
        index: Int,
        page: PageRef,
        reason: String,
        sourceStartedAt: Long
    ) {
        if (!isNtkSource(page.manga, title)) return
        if (pageRef(index) != page) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        val start = currentStartPage()
        if (index !in (start + 1)..(start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1)) return
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return
        Log.d(
            TAG,
            "reader_initial_continuous_cached_decode_after_source page=$index," +
                "start=$start,reason=$reason"
        )
        scheduleVisibleGeneratedCachedDecode(index, page, "initial_continuous_$reason", sourceStartedAt)
    }

    private fun decodeFullSurfaceCachedAfterSource(
        index: Int,
        page: PageRef,
        reason: String,
        sourceStartedAt: Long
    ) {
        if (!isNtkSource(page.manga, title)) return
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return
        if (pageRef(index) != page) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        val count = synchronized(pagesLock) { pages.size }
        if (count <= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES || count > NTK_FULL_SURFACE_WARM_MAX_PAGES) return
        if (reason == "byte_prefetch" && ntkBackgroundWarmQuietRemainingMs() > 0L) {
            logNtkPagePerf(index, "full_surface_cached_decode_defer_scroll_quiet", "reason=$reason")
            return
        }
        if (shouldDeferGeneratedFullSurfaceWorkForInitialContinuous()) {
            val start = currentStartPage()
            if (index !in start until start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) {
                logNtkPagePerf(index, "full_surface_cached_decode_defer_initial_continuous", "reason=$reason")
                return
            }
        }
        Log.d(
            TAG,
            "reader_full_surface_cached_decode_after_source page=$index,total=$count,reason=$reason"
        )
        scheduleVisibleGeneratedCachedDecode(index, page, "full_surface_$reason", sourceStartedAt)
    }

    private fun promoteVisibleGeneratedCachedDecode(index: Int, page: PageRef, reason: String) {
        if (!isNtkSource(page.manga, title)) return
        if (!isNtkGeneratedPageRef(index)) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        scheduleVisibleGeneratedCachedDecode(
            index,
            page,
            "visible_$reason",
            SystemClock.elapsedRealtime(),
            forceRequeue = true
        )
    }

    private fun hedgeVisibleGeneratedByteFetch(index: Int, page: PageRef, reason: String, delayMs: Long = 0L) {
        val initialAnchor = !firstBitmapLogged.get() && index == currentStartPage()
        val initialContinuousVisible =
            shouldUseInitialGeneratedCachedDecodeGuard(index, page) ||
                shouldUseInitialGeneratedStreamRunwayGuard(index, page)
        if (!firstBitmapLogged.get() &&
            !initialAnchor &&
            !initialContinuousVisible &&
            isNtkGeneratedImageUrl(page.image.orEmpty())
        ) {
            logNtkPagePerf(index, "visible_generated_byte_hedge_defer_anchor_exclusive", "reason=$reason")
            return
        }
        if (!firstBitmapLogged.get() && !initialAnchor && !initialContinuousVisible) return
        if (!isNtkSource(page.manga, title)) return
        if (!isNtkGeneratedPageRef(index)) return
        if (skipKnownMissingNtkGeneratedPage(index, page, "visible_byte_hedge_$reason")) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        if (!initialAnchor && !initialContinuousVisible && (loading.contains(index) || urgentLoading.contains(index))) return
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
                        if (shouldUseInitialGeneratedCachedDecodeGuard(index, page) ||
                            shouldUseInitialGeneratedStreamRunwayGuard(index, page)
                        ) {
                            val image = page.image
                            if (image.isNullOrBlank()) {
                                visibleGeneratedByteHedges.remove(index)
                                return@execute
                            }
                            val permit = ntkCoordinator?.imagePermit(
                                index,
                                page.manga,
                                image,
                                NtkImageLane.FOLLOWING_VISIBLE,
                                "visibleGeneratedByteHedge"
                            )
                            val started = ReaderImageCache.startForegroundStreamFetch(
                                appContext,
                                page.manga,
                                image,
                                imageCancellation,
                                false,
                                permit,
                                index,
                                true
                            )
                            logNtkPagePerf(
                                index,
                                "visible_generated_preview_stream_start",
                                "reason=$reason,started=$started,delayMs=$delayMs,ms=${SystemClock.elapsedRealtime() - startedAt}"
                            )
                            if (!started) {
                                val previewStarted = ReaderImageCache.startVisibleInitialGeneratedPreviewFetch(
                                    appContext,
                                    page.manga,
                                    image,
                                    imageCancellation,
                                    index
                                )
                                logNtkPagePerf(
                                    index,
                                    "visible_generated_preview_range_start",
                                    "reason=$reason,started=$previewStarted,delayMs=$delayMs,ms=${SystemClock.elapsedRealtime() - startedAt}"
                                )
                                visibleGeneratedByteHedges.remove(index)
                                scheduleVisibleGeneratedCachedDecode(
                                    index,
                                    page,
                                    if (previewStarted) "${reason}_range_preview" else reason,
                                    startedAt
                                )
                            }
                            return@execute
                        }
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
                        if (isNtkGeneratedImageUrl(page.image.orEmpty()) && isImageNotFoundError(e)) {
                            postPageError(index, page, e)
                        }
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
        byteStartedAt: Long,
        forceRequeue: Boolean = false
    ) {
        if (cancelled.get() || pageRef(index) != page) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        val image = page.image ?: return
        val cached = ReaderImageCache.cachedFile(appContext, page.manga, image) ?: run {
            registerInitialGeneratedAssetCachedDecode(index, page, reason, byteStartedAt)
            logNtkPagePerf(index, "visible_generated_cached_decode_skip", "reason=$reason,cached=false")
            return
        }
        val initialAnchor = !firstBitmapLogged.get() && index == currentStartPage()
        val initialContinuousGenerated = shouldUseInitialGeneratedCachedDecodeGuard(index, page)
        if (initialContinuousGenerated &&
            !firstBitmapLogged.get() &&
            !hasDeliveredOrPendingDrawable(currentStartPage())
        ) {
            logNtkPagePerf(index, "visible_generated_cached_decode_pre_anchor_allowed", "reason=$reason")
        }
        if (initialContinuousGenerated && initialGeneratedCachedDecodeInFlight.contains(index)) {
            logNtkPagePerf(index, "visible_generated_cached_decode_skip", "reason=$reason,initial_in_flight=true")
            return
        }
        if (initialContinuousGenerated && initialGeneratedDirectDecodeInFlight.contains(index)) {
            logNtkPagePerf(
                index,
                "visible_generated_cached_decode_direct_in_flight_allowed",
                "reason=$reason"
            )
        }
        if (forceRequeue && !initialContinuousGenerated) visibleGeneratedDecodeHedges.remove(index)
        if (!visibleGeneratedDecodeHedges.add(index)) return
        if (initialContinuousGenerated && !initialGeneratedCachedDecodeInFlight.add(index)) {
            visibleGeneratedDecodeHedges.remove(index)
            logNtkPagePerf(index, "visible_generated_cached_decode_skip", "reason=$reason,initial_in_flight=true")
            return
        }
        val decodeExecutor = if (initialAnchor) initialAnchorDecode else urgentDecode
        if (initialAnchor && !initialAnchorCachedDecodeStarted.compareAndSet(false, true)) {
            logNtkPagePerf(index, "visible_generated_cached_decode_skip", "reason=$reason,anchor_already_started=true")
            if (initialContinuousGenerated) initialGeneratedCachedDecodeInFlight.remove(index)
            return
        }
        try {
            decodeExecutor.execute {
                val startedAt = SystemClock.elapsedRealtime()
                var posted = false
                try {
                    if (
                        cancelled.get() ||
                        pageRef(index) != page ||
                        hasDeliveredBitmap(index) ||
                        (pendingDeliveryWidths[index] ?: 0) > 0
                    ) {
                        return@execute
                    }
                    val targetWidth = targetWidth(page, true)
                    val result = if (initialAnchor) {
                        cachedDecodedResult(
                            index,
                            page,
                            targetWidth,
                            shouldUsePreviewDecodedCache(index, targetWidth)
                        ) ?: decodePage(index, page, cached, targetWidth)
                    } else {
                        cachedDecodedResult(
                            index,
                            page,
                            targetWidth,
                            shouldUsePreviewDecodedCache(index, targetWidth)
                        )
                    } ?: decodePage(index, page, cached, targetWidth)
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
                    posted = true
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
                    cancelInitialGeneratedForegroundWorkAfterDrawableQueued(
                        page,
                        image,
                        "visible_cached_decode_posted"
                    )
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                    postPageError(index, page, e)
                } finally {
                    if (initialAnchor && !posted && !firstBitmapLogged.get() && !hasDeliveredBitmap(index)) {
                        initialAnchorCachedDecodeStarted.set(false)
                        logNtkPagePerf(index, "visible_generated_cached_decode_aborted", "reason=$reason")
                    }
                    visibleGeneratedDecodeHedges.remove(index)
                    if (initialContinuousGenerated) initialGeneratedCachedDecodeInFlight.remove(index)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (initialAnchor) initialAnchorCachedDecodeStarted.set(false)
            visibleGeneratedDecodeHedges.remove(index)
            if (initialContinuousGenerated) initialGeneratedCachedDecodeInFlight.remove(index)
        }
    }

    private fun scheduleInitialGeneratedDirectDecode(
        index: Int,
        page: PageRef,
        targetWidth: Int,
        reason: String,
        byteStartedAt: Long,
        allowAnchor: Boolean = false
    ): Boolean {
        val initialAnchor = shouldUseInitialGeneratedAnchorDirectDecode(index, page)
        if (!shouldUseInitialGeneratedCachedDecodeGuard(index, page) && !(allowAnchor && initialAnchor)) return false
        if (cancelled.get() || pageRef(index) != page) return false
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return false
        val image = page.image ?: return false
        if (!initialGeneratedDirectDecodeInFlight.add(index)) {
            logNtkPagePerf(index, "initial_generated_direct_decode_skip", "reason=$reason,direct_in_flight=true")
            return false
        }
        val permit = ntkCoordinator?.imagePermit(
            index,
            page.manga,
            image,
            if (initialAnchor) NtkImageLane.FIRST_IMAGE else NtkImageLane.FOLLOWING_VISIBLE,
            "initialGeneratedDirectDecode"
        )
        if (!ntkCoordinatorAllowsStream(index, page, permit, "initialGeneratedDirectDecode")) {
            initialGeneratedDirectDecodeInFlight.remove(index)
            return false
        }
        val streamStarted = ReaderImageCache.startForegroundStreamFetch(
            appContext,
            page.manga,
            image,
            imageCancellation,
            false,
            permit,
            index,
            true
        )
        logNtkPagePerf(
            index,
            "initial_generated_direct_decode_stream_start",
            "reason=$reason,started=$streamStarted"
        )
        return try {
            urgentDecode.execute {
                val startedAt = SystemClock.elapsedRealtime()
                var posted = false
                try {
                    if (
                        cancelled.get() ||
                        pageRef(index) != page ||
                        hasDeliveredBitmap(index) ||
                        (pendingDeliveryWidths[index] ?: 0) >= targetWidth
                    ) {
                        return@execute
                    }
                    val result = decodeForegroundStream(
                        index,
                        page,
                        targetWidth,
                        foregroundFetch = true,
                        visiblePriority = true,
                        permit = permit,
                        bypassInitialCachedDecodeGuard = true
                    ) ?: return@execute
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
                        "initial_generated_direct_decode_ready",
                        "reason=$reason,byteMs=${startedAt - byteStartedAt},decodeMs=${SystemClock.elapsedRealtime() - startedAt},width=${result.width}"
                    )
                    posted = true
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
                    cancelInitialGeneratedForegroundWorkAfterDrawableQueued(
                        page,
                        image,
                        "initial_generated_direct_decode_posted"
                    )
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                    postPageError(index, page, e)
                } finally {
                    initialGeneratedDirectDecodeInFlight.remove(index)
                    if (
                        !posted &&
                        !cancelled.get() &&
                        pageRef(index) == page &&
                        !hasDeliveredBitmap(index) &&
                        (pendingDeliveryWidths[index] ?: 0) <= 0
                    ) {
                        scheduleVisibleGeneratedCachedDecode(
                            index,
                            page,
                            "${reason}_direct_fallback",
                            byteStartedAt,
                            forceRequeue = true
                        )
                        hedgeVisibleGeneratedByteFetch(
                            index,
                            page,
                            "${reason}_direct_fallback",
                            0L
                        )
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            initialGeneratedDirectDecodeInFlight.remove(index)
            false
        }
    }

    private fun shouldSkipStalePage(index: Int, generation: Int, anchor: Boolean): Boolean {
        if (cancelled.get()) return true
        if (anchor) return false
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
            val firstBitmapElapsedAt = SystemClock.elapsedRealtime()
            ntkFirstBitmapAtMs.set(SystemClock.uptimeMillis())
            ViewerWarmupManager.logMetric("reader_first_bitmap_ms", SystemClock.elapsedRealtime() - startedAt)
            releasePreparedStoreBitmapsSoon()
            requestInitialContinuousGeneratedAfterFirstBitmap("first_bitmap")
            main.post { runFirstBitmapFollowupsWhenReady(startedAt, firstBitmapElapsedAt) }
        }
    }

    private fun registerInitialGeneratedAssetCachedDecode(
        index: Int,
        page: PageRef,
        reason: String,
        byteStartedAt: Long
    ) {
        if (reason.contains("asset_ready")) return
        if (!isNtkSource(page.manga, title)) return
        if (pageRef(index) != page) return
        if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) return
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return
        val start = currentStartPage()
        if (index !in start until start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return
        if (!initialGeneratedAssetDecodeListeners.add(index)) return
        val accepted = ReaderImageCache.runWhenNtkInitialGeneratedAssetReady(
            appContext,
            page.manga,
            page.image.orEmpty(),
            Runnable {
                initialGeneratedAssetDecodeListeners.remove(index)
                if (
                    cancelled.get() ||
                    pageRef(index) != page ||
                    hasDeliveredBitmap(index) ||
                    (pendingDeliveryWidths[index] ?: 0) > 0
                ) {
                    return@Runnable
                }
                scheduleVisibleGeneratedCachedDecode(
                    index,
                    page,
                    "${reason}_asset_ready",
                    byteStartedAt,
                    forceRequeue = true
                )
            }
        )
        if (!accepted) {
            initialGeneratedAssetDecodeListeners.remove(index)
            return
        }
        logNtkPagePerf(index, "initial_generated_asset_decode_listener", "reason=$reason")
    }

    private fun runFirstBitmapFollowupsWhenReady(startedAt: Long, firstBitmapElapsedAt: Long) {
        if (cancelled.get() || firstBitmapFollowupsStarted.get()) return
        if (shouldDeferFirstBitmapFollowups(firstBitmapElapsedAt)) {
            Log.d(
                TAG,
                "reader_first_bitmap_followups_defer start=${currentStartPage()}," +
                    "delivered=${initialViewportDeliveredForFollowupsCount()}"
            )
            main.postDelayed(
                { runFirstBitmapFollowupsWhenReady(startedAt, firstBitmapElapsedAt) },
                NTK_FIRST_BITMAP_FOLLOWUP_RECHECK_MS
            )
            return
        }
        requestInitialRapidScrollGeneratedWindowOnce("first_bitmap_followups")
        val quietDelayMs = ntkFirstBitmapFollowupQuietDelayMs()
        if (quietDelayMs > 0L) {
            main.postDelayed(
                { runFirstBitmapFollowupsWhenReady(startedAt, firstBitmapElapsedAt) },
                quietDelayMs.coerceAtMost(NTK_FIRST_BITMAP_FOLLOWUP_QUIET_POLL_MS)
            )
            return
        }
        if (!firstBitmapFollowupsStarted.compareAndSet(false, true)) return
        appendLatestEarlyNtkUrlsAfterFirstBitmap(startedAt)
        upgradeNtkInitialPriorityPagesAfterFirstBitmap()
        prefetchNtkInitialNextBytesAfterFirstBitmap()
        requestImmediateGeneratedFullWarmOnce("first_bitmap_initial_ready")
        requestGeneratedFullEpisodeDecodeAfterFirstBitmap()
        scheduleNtkSecondaryInitialWarmAfterFirstBitmap()
        scheduleNtkSourcePrefetchAfterFirstBitmap()
        scheduleNtkGeneratedFullEpisodeBytePrefetchAfterFirstBitmap()
        scheduleNtkEpisodeMetadataAfterFirstBitmap()
        scheduleNtkForwardTimelinePrimeAfterFirstBitmap()
    }

    private fun requestImmediateGeneratedFullWarmOnce(reason: String) {
        if (!canRunImmediateGeneratedFullWarm()) return
        val quietMs = ntkBackgroundWarmQuietRemainingMs()
        if (quietMs > 0L) {
            main.postDelayed({
                if (!cancelled.get()) requestImmediateGeneratedFullWarmOnce(reason)
            }, quietMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
            Log.d(
                TAG,
                "reader_ntk_immediate_generated_full_warm_defer reason=$reason," +
                    "delayMs=$quietMs,start=${currentStartPage()}"
            )
            return
        }
        if (!immediateGeneratedFullWarmStarted.compareAndSet(false, true)) return
        Log.d(
            TAG,
            "reader_ntk_immediate_generated_full_warm_start reason=$reason,start=${currentStartPage()}"
        )
        requestGeneratedFullEpisodeDecodeAfterFirstBitmap(forceImmediate = false)
        scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(
            NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_DELAY_MS
        )
    }

    private fun canRunImmediateGeneratedFullWarm(): Boolean {
        if (!isImmediateNtkGeneratedUx()) return false
        if (shouldHoldNtkGeneratedTailForInitialContinuous()) return false
        val refs = synchronized(pagesLock) { pages.toList() }
        return refs.isNotEmpty() &&
            refs.size <= NTK_FULL_SURFACE_WARM_MAX_PAGES &&
            isGeneratedOnlyNtkRefs(refs)
    }

    private fun ntkFirstBitmapFollowupQuietDelayMs(): Long {
        if (!isNtkSource(manga, title)) return 0L
        if (!firstBitmapLogged.get()) return 0L
        val path = manga.ntkEpisodePath
        val initialContinuousPath =
            isNtkManhwaOrWebtoonEpisodePath(path) || manga.baseMode == MTitle.base_webtoon
        if (!initialContinuousPath) return 0L
        return maxOf(
            ntkBackgroundPrepareQuietRemainingMs(),
            readerQuietRemainingMs(NTK_APPEND_INITIAL_PUBLISH_TOUCH_QUIET_MS)
        )
    }

    private fun isImmediateNtkGeneratedUx(): Boolean {
        if (!isNtkSource(manga, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)) return false
        return true
    }

    private fun requestInitialRapidScrollGeneratedWindow(refs: List<PageRef>, reason: String) {
        if (!isImmediateNtkGeneratedUx()) return
        if (refs.isEmpty()) return
        val anchor = currentStartPage().coerceIn(0, refs.lastIndex)
        val aheadPages = if (shouldHoldNtkGeneratedTailForInitialContinuous()) {
            NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        } else {
            ntkInitialRapidScrollAheadPages(reason)
        }
        val last = minOf(refs.lastIndex, anchor + aheadPages)
        if (last <= anchor) return
        var requested = 0
        val requestedIndexes = ArrayList<Int>()
        for (index in (anchor + 1)..last) {
            val page = refs.getOrNull(index) ?: continue
            if (page.transitionTitle != null || hasDeliveredBitmap(index)) continue
            if (!hasPageSourceReady(index, page)) {
                forcePrefetchNtkPageSource(index, page, reason)
            }
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
            requested++
            if (requestedIndexes.size < 32) requestedIndexes.add(index)
        }
        Log.d(
            TAG,
            "reader_ntk_initial_rapid_scroll_window reason=$reason,anchor=$anchor," +
                "requested=$requested,range=${anchor + 1}-$last," +
                "requestedIndexes=${requestedIndexes.joinToString("|")}"
        )
        ViewerWarmupManager.logMetric("reader_ntk_initial_rapid_scroll_window", requested.toLong())
    }

    private fun requestInitialRapidScrollGeneratedWindowOnce(reason: String) {
        if (!initialRapidGeneratedWindowStarted.compareAndSet(false, true)) return
        val refs = synchronized(pagesLock) { pages.toList() }
        requestInitialRapidScrollGeneratedWindow(refs, reason)
    }

    private fun ntkInitialRapidScrollAheadPages(reason: String): Int {
        if (!isImmediateNtkGeneratedUx()) return NTK_INITIAL_RAPID_SCROLL_DECODE_AHEAD_PAGES
        return when (reason) {
            "first_bitmap_followups" -> NTK_INITIAL_INTERACTIVE_VIEWPORT_AHEAD_PAGES
            "initial_full_decode_near",
            "generated_full_decode_initial_near",
            "generated_full_byte_prefetch_runway" -> NTK_INITIAL_RAPID_SCROLL_DECODE_AHEAD_PAGES
            else -> NTK_INITIAL_RAPID_SCROLL_DECODE_AHEAD_PAGES
        }
    }

    private fun requestInitialContinuousGeneratedAfterFirstBitmap(reason: String) {
        if (!isImmediateNtkGeneratedUx()) return
        if (!initialContinuousAfterFirstBitmapStarted.compareAndSet(false, true)) return
        val start = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0 || start !in 0 until count) return
        val last = minOf(count - 1, start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1)
        if (last <= start) return
        var requested = 0
        for (index in (start + 1)..last) {
            val page = pageRef(index) ?: continue
            if (page.transitionTitle != null) continue
            if (!isNtkGeneratedImageUrl(page.image.orEmpty())) continue
            if (hasDeliveredBitmap(index) || (pendingDeliveryWidths[index] ?: 0) > 0) continue
            val now = SystemClock.elapsedRealtime()
            scheduleVisibleGeneratedCachedDecode(
                index,
                page,
                "initial_continuous_$reason",
                now,
                forceRequeue = true
            )
            hedgeVisibleGeneratedByteFetch(index, page, "initial_continuous_$reason", 0L)
            requested++
        }
        Log.d(
            TAG,
            "reader_initial_continuous_after_first_bitmap reason=$reason,start=$start,requested=$requested"
        )
        if (collapseInitialMissingNtkGeneratedGapToCachedPage("initial_continuous_${reason}_immediate")) {
            requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), synchronized(pagesLock) { pages.size })
        } else {
            scheduleInitialMissingNtkGeneratedGapCollapse("initial_continuous_$reason", attempts = 18)
        }
    }

    private fun requestGeneratedFullEpisodeDecodeAfterFirstBitmap(forceImmediate: Boolean = false) {
        if (!isNtkSource(manga, title)) return
        val quietMs = ntkBackgroundWarmQuietRemainingMs()
        if (quietMs > 0L) {
            scheduleGeneratedFullEpisodeDecodeRetry(quietMs)
            return
        }
        val refs = synchronized(pagesLock) { pages.toList() }
        if (refs.isEmpty()) return
        if (!isGeneratedOnlyNtkRefs(refs)) {
            requestInitialRapidScrollGeneratedWindow(refs, "initial_full_decode_near")
            return
        }
        requestInitialRapidScrollGeneratedWindow(refs, "generated_full_decode_initial_near")
        if (refs.size > NTK_FULL_SURFACE_WARM_MAX_PAGES) {
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(0L)
            return
        }
        if (hasUndeliveredNtkImmediateRunway(refs, currentStartPage().coerceIn(0, refs.lastIndex))) {
            if (!forceImmediate) {
                scheduleGeneratedFullEpisodeDecodeRetry(NTK_BACKGROUND_WARM_RECHECK_MS)
                return
            }
        }
        requestAllUndeliveredNtkPages("generated_full_decode_initial")
    }

    private fun scheduleGeneratedFullEpisodeDecodeRetry(delayMs: Long) {
        if (!generatedFullEpisodeDecodeRetryScheduled.compareAndSet(false, true)) return
        main.postDelayed({
            generatedFullEpisodeDecodeRetryScheduled.set(false)
            if (!cancelled.get()) requestGeneratedFullEpisodeDecodeAfterFirstBitmap()
        }, delayMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
    }

    private fun shouldDeferFirstBitmapFollowups(firstBitmapElapsedAt: Long): Boolean {
        if (!isNtkSource(manga, title)) return false
        val path = manga.ntkEpisodePath ?: return false
        val initialContinuousPath = path.startsWith("/webtoon/", ignoreCase = true) ||
            path.startsWith("/manhwa/", ignoreCase = true) ||
            manga.baseMode == MTitle.base_webtoon
        if (!initialContinuousPath) return false
        if (SystemClock.elapsedRealtime() - firstBitmapElapsedAt >= NTK_FIRST_BITMAP_FOLLOWUP_MAX_DEFER_MS) {
            return false
        }
        val start = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0 || start !in 0 until count) return true
        val requiredAhead = if (
            path.startsWith("/webtoon/", ignoreCase = true) ||
            manga.baseMode == MTitle.base_webtoon
        ) {
            NTK_FIRST_BITMAP_WEBTOON_FOLLOWUP_READY_AHEAD
        } else {
            NTK_FIRST_BITMAP_MANHWA_FOLLOWUP_READY_AHEAD
        }
        val lastRequired = minOf(count - 1, start + requiredAhead)
        for (index in start..lastRequired) {
            if (!hasListenerDrawableDelivery(index)) return true
        }
        return false
    }

    private fun shouldDeferPostInitialContinuousNtkRequest(
        index: Int,
        page: PageRef,
        busy: Boolean,
        anchor: Boolean,
        generation: Int
    ): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        val start = currentStartPage()
        if (index < start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        if (initialContinuousDrawableDelivered(start)) return false
        val viewportAnchor = currentViewportAnchor.get().takeIf { it >= 0 } ?: start
        val actualViewportRequest =
            anchor ||
                index == viewportAnchor ||
                (generation == windowGeneration.get() && busy)
        if (actualViewportRequest || isInitialContinuousNarrowViewportRequest(index, viewportAnchor)) {
            Log.d(
                TAG,
                "reader_ntk_post_initial_continuous_bypass_viewport page=$index,start=$start," +
                    "viewportAnchor=$viewportAnchor,busy=$busy,anchor=$anchor,generation=$generation," +
                    "actualViewport=$actualViewportRequest"
            )
            return false
        }
        if (shouldBypassInitialContinuousGateForGeneratedUx(index, page, busy, anchor, generation)) {
            Log.d(
                TAG,
                "reader_ntk_post_initial_continuous_bypass page=$index,start=$start," +
                    "busy=$busy,anchor=$anchor,generation=$generation"
            )
            return false
        }
        if (!postInitialContinuousDeferredRequests.add(index)) return true
        Log.d(
            TAG,
            "reader_ntk_post_initial_continuous_defer page=$index,start=$start," +
                "busy=$busy,anchor=$anchor,generation=$generation"
        )
        main.postDelayed({
            postInitialContinuousDeferredRequests.remove(index)
            if (cancelled.get()) return@postDelayed
            val latest = pageRef(index) ?: return@postDelayed
            if (!sameNtkSourceSlot(page, latest)) return@postDelayed
            requestPage(index, busy = busy, anchor = anchor, generation = generation)
        }, NTK_POST_INITIAL_CONTINUOUS_RETRY_MS)
        return true
    }

    private fun shouldBypassInitialContinuousGateForGeneratedUx(
        index: Int,
        page: PageRef,
        busy: Boolean,
        anchor: Boolean,
        generation: Int
    ): Boolean {
        if (!firstBitmapLogged.get() && ntkFirstBitmapAtMs.get() <= 0L) return false
        if (!isImmediateNtkGeneratedUx()) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        val start = currentStartPage()
        val lastAllowed = minOf(pagesLastIndex(), start + NTK_INITIAL_RAPID_SCROLL_DECODE_AHEAD_PAGES)
        if (index !in (start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)..lastAllowed) return false
        if (!initialContinuousDrawableDelivered(start)) return false
        val actualViewportRequest = generation >= 0 && (anchor || busy || index == currentViewportAnchor.get())
        val initialRapidPrimeRequest = generation == FOREGROUND_PRIME_WARM_GENERATION && busy
        return actualViewportRequest || initialRapidPrimeRequest
    }

    private fun isInitialRapidGeneratedUrgent(
        index: Int,
        page: PageRef,
        busy: Boolean,
        generation: Int
    ): Boolean {
        if (!busy || generation != FOREGROUND_PRIME_WARM_GENERATION) return false
        if (!firstBitmapLogged.get() && ntkFirstBitmapAtMs.get() <= 0L) return false
        if (!isImmediateNtkGeneratedUx()) return false
        if (!isNtkSource(page.manga, title)) return false
        val start = currentStartPage()
        val viewportAnchor = currentViewportAnchor.get().takeIf { it >= 0 } ?: start
        return index <= start + NTK_INITIAL_RAPID_SCROLL_URGENT_PAGES ||
            index in max(0, viewportAnchor - 2)..(viewportAnchor + 2)
    }

    private fun initialContinuousDrawableDelivered(start: Int): Boolean {
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return false
        val last = minOf(count - 1, start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1)
        if (last < start) return false
        for (pageIndex in start..last) {
            if (!hasListenerDrawableDelivery(pageIndex)) return false
        }
        return true
    }

    private fun shouldHoldNtkGeneratedTailForInitialContinuous(): Boolean {
        if (!isImmediateNtkGeneratedUx()) return false
        val start = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        return !initialContinuousDrawableDelivered(start)
    }

    private fun initialViewportDeliveredForFollowupsCount(): Int {
        val start = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0 || start !in 0 until count) return 0
        val path = manga.ntkEpisodePath.orEmpty()
        val requiredAhead = if (
            path.startsWith("/webtoon/", ignoreCase = true) ||
            manga.baseMode == MTitle.base_webtoon
        ) {
            NTK_FIRST_BITMAP_WEBTOON_FOLLOWUP_READY_AHEAD
        } else {
            NTK_FIRST_BITMAP_MANHWA_FOLLOWUP_READY_AHEAD
        }
        val lastRequired = minOf(count - 1, start + requiredAhead)
        var delivered = 0
        for (index in start..lastRequired) {
            if (hasListenerDrawableDelivery(index)) delivered++
        }
        return delivered
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
            while (!cancelled.get() && !firstDrawableDelivered.get() && SystemClock.elapsedRealtime() < firstBitmapDeadline) {
                try {
                    Thread.sleep(NTK_EARLY_URL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
            if (cancelled.get() || !firstDrawableDelivered.get()) return@execute
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
                    if (installedCount >= ntkInitialGeneratedRecoveryPagesForTarget(target)) return@execute
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
        if (latest.size < installedCount && shouldAllowNtkGeneratedVerifiedShrink(manga, latest)) {
            appendInitialNtkUrlsAfterEarlyInstall(manga, latest, loadStartedAt, allowFirstBitmapDefer = true)
            logNtkRepositoryStage(
                manga,
                "early_urls_latest_shrink_after_first_bitmap",
                "from=$installedCount,to=${latest.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), latest.size)
            return
        }
        if (latest.size <= installedCount) {
            if (refreshInstalledNtkGeneratedImagesFromEarlyUrls(manga, latest, "early_urls_latest_same_size")) {
                requestAllUndeliveredNtkPages("generated_full_decode_refresh")
                requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), latest.size)
            }
            return
        }
        val trustedDirectWebtoon = isTrustedDirectWebtoonGeneratedEpisode(manga)
        val generatedFullExpansion = latest.all { isNtkGeneratedImageUrl(it) }
        val allowAppendDefer = !trustedDirectWebtoon
        appendInitialNtkUrlsAfterEarlyInstall(
            manga,
            latest,
            loadStartedAt,
            allowFirstBitmapDefer = allowAppendDefer
        )
        if (generatedFullExpansion && allowAppendDefer && generatedFullAppendQuietRemainingMs() > 0L) {
            logNtkRepositoryStage(
                manga,
                if (trustedDirectWebtoon) {
                    "early_urls_latest_append_after_first_bitmap_trusted_direct"
                } else {
                    "early_urls_latest_append_after_first_bitmap_deferred"
                },
                "from=$installedCount,to=${latest.size},trustedDirectWebtoon=$trustedDirectWebtoon,ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
            )
            if (!trustedDirectWebtoon) return
        }
        logNtkRepositoryStage(
            manga,
            "early_urls_latest_append_after_first_bitmap",
            "from=$installedCount,to=${latest.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        requestInitialContinuousPagesFromEarlyUrls(currentStartPage(), latest.size)
    }

    private fun refreshInstalledNtkGeneratedImagesFromLatestEarlyUrls(
        target: Manga,
        reason: String,
        loadStartedAt: Long
    ): Boolean {
        if (cancelled.get() || !isNtkSource(target, title)) return false
        val latest = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, 0L)
        if (!shouldRefreshInstalledNtkGeneratedImagesFromEarlyUrls(target, latest)) return false
        if (!refreshInstalledNtkGeneratedImagesFromEarlyUrls(target, latest, reason)) return false
        val start = currentStartPage()
        logNtkRepositoryStage(
            target,
            "early_urls_latest_same_count_replace",
            "reason=$reason,count=${latest.size},ms=${SystemClock.elapsedRealtime() - loadStartedAt}"
        )
        requestPageForeground(start)
        requestInitialContinuousPagesFromEarlyUrls(start, latest.size)
        scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(0L)
        return true
    }

    private fun shouldRefreshInstalledNtkGeneratedImagesFromEarlyUrls(
        target: Manga,
        urls: List<String>
    ): Boolean {
        if (urls.isEmpty() || !isNtkSource(target, title)) return false
        if (urls.any { !isNtkGeneratedImageUrl(it) }) return false
        val current = currentNtkInstalledImages(target)
        if (current.isEmpty()) return false
        return hasInitialVerifiedReplacement(current, urls)
    }

    private fun refreshInstalledNtkGeneratedImagesFromEarlyUrls(
        target: Manga,
        urls: List<String>,
        reason: String
    ): Boolean {
        if (urls.isEmpty() || !isNtkSource(target, title)) return false
        if (urls.any { !isNtkGeneratedImageUrl(it) }) return false
        val changedIndexes = ArrayList<Int>()
        val changedImages = ArrayList<String>()
        val initialVisibleChangedImages = ArrayList<String>()
        val protectedInitialVisibleIndexes = ArrayList<Int>()
        val initialVisibleFirst = currentStartPage()
        val initialVisibleLast = initialVisibleFirst + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        synchronized(pagesLock) {
            for (index in pages.indices) {
                val ref = pages[index]
                if (ref.transitionTitle != null || !Manga.sameEpisodeIdentity(ref.manga, target)) continue
                val replacement = urls.getOrNull(ref.sourceIndex) ?: continue
                val current = ref.image
                if (current == replacement) continue
                if (!isNtkGeneratedImageUrl(current.orEmpty())) continue
                val currentImage = current.orEmpty()
                if (
                    index in initialVisibleFirst..initialVisibleLast &&
                    hasInitialVisibleGeneratedWork(index)
                ) {
                    protectedInitialVisibleIndexes.add(index)
                    continue
                }
                changedImages.add(currentImage)
                if (!firstBitmapLogged.get() && index in initialVisibleFirst..initialVisibleLast) {
                    initialVisibleChangedImages.add(currentImage)
                }
                ref.image = replacement
                changedIndexes.add(index)
            }
        }
        if (changedIndexes.isEmpty()) return false
        if (initialVisibleChangedImages.isNotEmpty()) {
            ReaderImageCache.cancelNtkGeneratedForegroundWorkForImages(
                target,
                initialVisibleChangedImages,
                "${reason}_initial_visible"
            )
        }
        if (changedIndexes.contains(currentStartPage())) {
            initialAnchorCachedDecodeStarted.set(false)
            removeInitialAnchorAssetDecodeListenersForEpisode(target)
        }
        for (index in changedIndexes) {
            failedPages.remove(index)
            loading.remove(index)
            urgentLoading.remove(index)
            loadingPages.remove(index)
            urgentLoadingPages.remove(index)
            loadingStartedAtMs.remove(index)
            inFlightWidths.remove(index)
            pendingDeliveryWidths.remove(index)
            visibleGeneratedByteHedges.remove(index)
            visibleGeneratedDecodeHedges.remove(index)
            initialGeneratedAssetDecodeListeners.remove(index)
        }
        Log.d(
            TAG,
            "refresh_installed_ntk_generated_images reason=$reason,path=${target.ntkEpisodePath}," +
                "changed=${changedIndexes.size},indexes=${changedIndexes.take(32).joinToString("|")}," +
                "protectedInitial=${protectedInitialVisibleIndexes.take(8).joinToString("|")}"
        )
        if (initialVisibleChangedImages.isEmpty()) {
            scheduleCancelNtkGeneratedForegroundWorkForImages(target, changedImages, reason)
        } else {
            val initialVisibleSet = HashSet(initialVisibleChangedImages)
            scheduleCancelNtkGeneratedForegroundWorkForImages(
                target,
                changedImages.filterNot { initialVisibleSet.contains(it) },
                reason
            )
        }
        return true
    }

    private fun hasInitialVisibleGeneratedWork(index: Int): Boolean {
        if (hasDeliveredBitmap(index)) return true
        if ((pendingDeliveryWidths[index] ?: 0) > 0) return true
        if (initialDeliveryBacklog.containsKey(index)) return true
        if (initialPreparedBacklog.containsKey(index)) return true
        if (loading.contains(index) || urgentLoading.contains(index)) return true
        if (loadingPages.containsKey(index) || urgentLoadingPages.containsKey(index)) return true
        if (inFlightWidths.containsKey(index)) return true
        return false
    }

    private fun isInitialContinuousNarrowViewportRequest(index: Int, viewportAnchor: Int): Boolean {
        if (viewportAnchor < 0) return false
        val lastAllowed = minOf(
            pagesLastIndex(),
            viewportAnchor + NTK_INITIAL_INTERACTIVE_VIEWPORT_AHEAD_PAGES
        )
        return index in viewportAnchor..lastAllowed
    }

    private fun scheduleCancelNtkGeneratedForegroundWorkForImages(
        target: Manga,
        images: List<String>,
        reason: String
    ) {
        if (images.isEmpty()) return
        val snapshot = ArrayList(images)
        try {
            cleanup.execute {
                ReaderImageCache.cancelNtkGeneratedForegroundWorkForImages(target, snapshot, reason)
            }
        } catch (_: RejectedExecutionException) {
            // Session teardown: stale generated foreground work will be cancelled with the session.
        }
    }

    private fun shouldAllowNtkGeneratedVerifiedShrink(target: Manga, urls: List<String>): Boolean {
        if (!isNtkSource(target, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(target.ntkEpisodePath)) return false
        if (urls.size <= 1 || urls.any { !isNtkGeneratedImageUrl(it) }) return false
        val knownCount = target.ntkImageCount
        if (knownCount > urls.size && !hasKnownMissingGeneratedTailAfter(target, urls.size)) return false
        val latest = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L)
        if (latest.size != urls.size || latest.any { !isNtkGeneratedImageUrl(it) }) return false
        return latest.toSet() == urls.toSet()
    }

    private fun hasKnownMissingGeneratedTailAfter(target: Manga, verifiedCount: Int): Boolean {
        if (verifiedCount <= 0) return false
        return synchronized(pagesLock) {
            val last = minOf(pages.lastIndex, verifiedCount + NTK_GENERATED_INITIAL_RECOVERY_PAGES)
            if (verifiedCount > last) return@synchronized false
            for (index in verifiedCount..last) {
                val page = pages.getOrNull(index) ?: continue
                if (!Manga.sameEpisodeIdentity(page.manga, target)) continue
                val image = page.image.orEmpty()
                if (isNtkGeneratedImageUrl(image) &&
                    ReaderImageCache.isKnownNtkGeneratedNotFound(target, image)
                ) {
                    return@synchronized true
                }
            }
            false
        }
    }

    private fun scheduleNtkSecondaryInitialWarmAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        val quietMs = ntkBackgroundWarmQuietRemainingMs()
        val immediateGeneratedUx = isImmediateNtkGeneratedUx()
        if (quietMs > 0L && !immediateGeneratedUx) {
            Log.d(TAG, "reader_ntk_secondary_initial_warm_defer_quiet delayMs=$quietMs")
            scheduleNtkSecondaryInitialWarmAfterFirstBitmap(quietMs)
            return
        }
        if (quietMs > 0L) {
            Log.d(TAG, "reader_ntk_secondary_initial_warm_skip_quiet_for_immediate_ux delayMs=$quietMs")
        }
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
                    requestPage(
                        index,
                        busy = true,
                        anchor = false,
                        generation = if (immediateGeneratedUx) FOREGROUND_PRIME_WARM_GENERATION else PRIME_WARM_GENERATION
                    )
                }
            }, if (immediateGeneratedUx) 0L else NTK_INITIAL_SECONDARY_WARM_DELAY_MS)
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
            val delayMs = ntkBackgroundWarmQuietRemainingMs()
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
            if (isImmediateNtkGeneratedUx() && !firstDrawableDelivered.get()) {
                scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(
                    NTK_GENERATED_FULL_BYTE_PREFETCH_AFTER_FIRST_BITMAP_DELAY_MS
                )
                return@postDelayed
            }
            runNtkGeneratedFullEpisodeBytePrefetch()
        }, delayMs)
    }

    private fun runNtkGeneratedFullEpisodeBytePrefetch() {
        if (cancelled.get()) return
        if (shouldHoldNtkGeneratedTailForInitialContinuous()) {
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_DELAY_MS)
            return
        }
        val delayMs = ntkBackgroundWarmQuietRemainingMs()
        if (delayMs > 0L) {
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(delayMs)
            return
        }
        val refs = synchronized(pagesLock) { pages.toList() }
        if (!isGeneratedOnlyNtkRefs(refs)) return
        val anchor = currentStartPage().coerceIn(0, refs.lastIndex)
        if (hasUndeliveredNtkImmediateRunway(refs, anchor)) {
            requestInitialRapidScrollGeneratedWindow(refs, "generated_full_byte_prefetch_runway")
            scheduleNtkGeneratedFullEpisodeBytePrefetchAfterDelay(NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_DELAY_MS)
            return
        }
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
            network.execute {
                prefetchImageFileQuietly(index, page)
            }
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

    private fun hasUndeliveredNtkImmediateRunway(refs: List<PageRef>, anchor: Int): Boolean {
        if (!isImmediateNtkGeneratedUx()) return false
        if (!isGeneratedOnlyNtkRefs(refs)) return false
        if (refs.isEmpty()) return false
        val safeAnchor = anchor.coerceIn(0, refs.lastIndex)
        val last = minOf(refs.lastIndex, safeAnchor + NTK_GENERATED_IMMEDIATE_RUNWAY_PAGES - 1)
        for (index in safeAnchor..last) {
            val page = refs.getOrNull(index) ?: continue
            if (page.transitionTitle != null) continue
            if (!isNtkGeneratedImageUrl(page.image.orEmpty())) continue
            if (!hasDeliveredBitmap(index)) return true
        }
        return false
    }

    private fun scheduleNtkEpisodeMetadataAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        main.postDelayed({
            val quietMs = ntkBackgroundWarmQuietRemainingMs()
            if (quietMs > 0L) {
                scheduleNtkEpisodeMetadataAfterDelay(quietMs)
                return@postDelayed
            }
            if (isNtkCurrentAckPreflightInFlight()) {
                scheduleNtkEpisodeMetadataAfterFirstBitmap()
                return@postDelayed
            }
            fetchNtkEpisodeMetadataInBackground()
        }, NTK_EPISODE_METADATA_AFTER_FIRST_BITMAP_DELAY_MS)
    }

    private fun scheduleNtkEpisodeMetadataAfterDelay(delayMs: Long) {
        main.postDelayed({
            if (!cancelled.get()) scheduleNtkEpisodeMetadataAfterFirstBitmap()
        }, delayMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
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
        val quietMs = ntkBackgroundWarmQuietRemainingMs()
        if (quietMs > 0L) {
            Log.d(TAG, "reader_ntk_initial_priority_upgrade_defer_quiet delayMs=$quietMs")
            scheduleNtkInitialPriorityUpgradeAfterDelay(quietMs)
            return
        }
        val first = currentStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val firstPriority = minOf(count - 1, first + NTK_INITIAL_PRIORITY_START_OFFSET)
        val priorityPages = if (isNtkWebtoonSource(manga, title)) {
            NTK_WEBTOON_INITIAL_BOOT_PRIORITY_PAGES
        } else if (isCurrentManhwaGeneratedOnlyRefs()) {
            NTK_MANHWA_GENERATED_INITIAL_VISIBLE_AHEAD_PAGES
        } else {
            NTK_INITIAL_PRIORITY_PAGES
        }
        val last = minOf(count - 1, first + priorityPages)
        if (firstPriority > last) return
        for (index in firstPriority..last) {
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
        }
    }

    private fun scheduleNtkInitialPriorityUpgradeAfterDelay(delayMs: Long) {
        main.postDelayed({
            if (!cancelled.get()) upgradeNtkInitialPriorityPagesAfterFirstBitmap()
        }, delayMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
    }

    private fun prefetchNtkInitialNextBytesAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        val quietMs = ntkBackgroundWarmQuietRemainingMs()
        if (quietMs > 0L) {
            Log.d(TAG, "reader_ntk_initial_next_byte_prefetch_defer_quiet delayMs=$quietMs")
            scheduleNtkInitialNextBytesAfterDelay(quietMs)
            return
        }
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        prefetchNtkInitialNextBytes(currentStartPage(), count)
    }

    private fun scheduleNtkInitialNextBytesAfterDelay(delayMs: Long) {
        main.postDelayed({
            if (!cancelled.get()) prefetchNtkInitialNextBytesAfterFirstBitmap()
        }, delayMs.coerceAtLeast(NTK_BACKGROUND_WARM_RECHECK_MS))
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
            val quietMs = ntkBackgroundWarmQuietRemainingMs()
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

    private fun ntkBackgroundWarmQuietRemainingMs(): Long {
        if (!isNtkSource(manga, title)) return 0L
        return maxOf(
            ntkBackgroundPrepareQuietRemainingMs(),
            ntkInitialInteractiveSettleRemainingMs(),
            readerQuietRemainingMs(NTK_BACKGROUND_WARM_TOUCH_QUIET_MS)
        )
    }

    private fun ntkInitialInteractiveSettleRemainingMs(): Long {
        if (!isNtkSource(manga, title)) return 0L
        if (!firstBitmapLogged.get()) return 0L
        val path = manga.ntkEpisodePath
        val initialContinuousPath =
            isNtkManhwaOrWebtoonEpisodePath(path) || manga.baseMode == MTitle.base_webtoon
        if (!initialContinuousPath) return 0L
        val firstBitmapAt = ntkFirstBitmapAtMs.get()
        if (firstBitmapAt <= 0L) return 0L
        val quietFor = SystemClock.uptimeMillis() - firstBitmapAt
        return (NTK_INITIAL_INTERACTIVE_SETTLE_AFTER_FIRST_BITMAP_MS - quietFor).coerceAtLeast(0L)
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
        return synchronized(pagesLock) {
            if (!isNtkGeneratedManhwaReadyForSilentAdjacentLocked()) return false
            val count = pages.size
            if (count <= 0) return false
            val boundedAnchor = anchor.coerceIn(0, count - 1)
            val viewportAnchor = currentViewportAnchor.get()
                .takeIf { it >= 0 }
                ?.coerceIn(0, count - 1)
            val effectiveAnchor = if (direction > 0) {
                maxOf(boundedAnchor, viewportAnchor ?: boundedAnchor)
            } else {
                minOf(boundedAnchor, viewportAnchor ?: boundedAnchor)
            }
            if (direction > 0) {
                if (count <= NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
                val near = effectiveAnchor >= count - NTK_SILENT_ADJACENT_BOUNDARY_PAGES
                if (!near) {
                    Log.d(
                        TAG,
                        "append_adjacent_silent_not_near_tail anchor=$anchor effective=$effectiveAnchor " +
                            "viewport=${viewportAnchor ?: -1} count=$count path=${manga.ntkEpisodePath}"
                    )
                }
                near
            } else {
                val near = effectiveAnchor < NTK_SILENT_ADJACENT_BOUNDARY_PAGES
                if (!near) {
                    Log.d(
                        TAG,
                        "append_adjacent_silent_not_near_head anchor=$anchor effective=$effectiveAnchor " +
                            "viewport=${viewportAnchor ?: -1} count=$count path=${manga.ntkEpisodePath}"
                    )
                }
                near
            }
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

    private fun isNtkAdjacentAppendBlockedByCurrentTail(
        source: Manga,
        anchor: Int,
        direction: Int
    ): Boolean {
        if (!isNtkSource(source, title)) return false
        return synchronized(pagesLock) {
            val matchingRefs = pages.withIndex()
                .filter { (_, page) ->
                    page.transitionTitle == null && Manga.sameEpisodeIdentity(page.manga, source)
                }
            val matchingIndexes = matchingRefs.map { it.index }
            if (matchingIndexes.isEmpty()) return@synchronized false
            val first = matchingIndexes.minOrNull() ?: return@synchronized false
            val last = matchingIndexes.maxOrNull() ?: return@synchronized false
            val knownCount = source.ntkImageCount
            val trustedKnownCount = knownCount in 1..NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD ||
                ReaderImageCache.trustedNtkImageApiCount(source.ntkEpisodePath, 0L) >= knownCount
            val count = matchingIndexes.size
            val hasInsertedNextEpisode = direction > 0 &&
                ((last + 1)..pages.lastIndex).any { index ->
                    val page = pages[index]
                    page.transitionTitle == null && !Manga.sameEpisodeIdentity(page.manga, source)
                }
            val hasInsertedPreviousEpisode = direction < 0 &&
                (0 until first).any { index ->
                    val page = pages[index]
                    page.transitionTitle == null && !Manga.sameEpisodeIdentity(page.manga, source)
                }
            val missingKnownCurrentPages =
                trustedKnownCount &&
                    knownCount > count &&
                    count <= NTK_UNKNOWN_GENERATED_DISPLAY_THRESHOLD &&
                    direction > 0
            val blocked = when {
                hasInsertedNextEpisode -> true
                hasInsertedPreviousEpisode -> true
                missingKnownCurrentPages -> true
                direction > 0 && anchor < last - NTK_APPEND_CURRENT_TAIL_BOUNDARY_PAGES -> true
                direction < 0 && anchor > first + NTK_APPEND_CURRENT_TAIL_BOUNDARY_PAGES -> true
                else -> false
            }
            if (blocked) {
                Log.d(
                    TAG,
                    "append_adjacent_block_current_tail direction=$direction anchor=$anchor " +
                        "first=$first last=$last count=$count known=$knownCount " +
                        "path=${source.ntkEpisodePath}"
                )
            }
            if (missingKnownCurrentPages) {
                main.post {
                    if (cancelled.get()) return@post
                    val latest = currentGeneratedUrlsForAdjacentExpansion(source, knownCount, count)
                    if (latest.size >= knownCount && latest.size > count) {
                        val minGeneratedLimit = ntkCurrentGeneratedExpansionLimit(
                            source,
                            count,
                            anchor,
                            latest.size
                        )
                        appendInitialNtkUrlsAfterEarlyInstall(
                            source,
                            latest,
                            SystemClock.elapsedRealtime(),
                            allowFirstBitmapDefer = false,
                            minGeneratedLimit = minGeneratedLimit
                        )
                        logNtkRepositoryStage(
                            source,
                            "append_block_expand_current_from_early",
                            "from=$count,to=${latest.size},known=$knownCount,min=$minGeneratedLimit"
                        )
                    }
                    requestAllUndeliveredNtkPages("current_full_before_next")
                }
            }
            blocked
        }
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

    private fun hasPageSourceReady(index: Int, page: PageRef): Boolean {
        val image = page.image ?: return false
        if (!page.manga.isOnline) return true
        return try {
            ReaderImageCache.cachedFile(appContext, page.manga, image) != null
        } catch (e: Exception) {
            if (!isExpectedCancellation(e)) {
                logNtkPagePerf(index, "source_ready_probe_error", "error=${e.javaClass.simpleName}")
            }
            false
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
        if (isNtkGeneratedImageUrl(page.image.orEmpty()) && isImageNotFoundError(e)) {
            ReaderImageCache.markNtkGeneratedNotFound(
                page.manga,
                page.image,
                "reader_page_error"
            )
        }
        if (postNtkImageCloudflareCaptcha(index, page, e)) return
        if (refreshNtkGeneratedPageImage(index, page, e)) return
        if (trimNtkGeneratedTail(index, page, e)) return
        if (trimEarlyInvalidNtkGeneratedTail(index, page, e)) return
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

    private fun shouldDeferInitialInteractiveGeneratedBackground(
        index: Int,
        page: PageRef,
        generation: Int
    ): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isImmediateNtkGeneratedUx()) return false
        val start = currentStartPage()
        val initialLast = start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        if (shouldHoldNtkGeneratedTailForInitialContinuous() && index > initialLast) {
            val viewportAnchor = currentViewportAnchor.get().takeIf { it >= 0 } ?: start
            if (isInitialContinuousNarrowViewportRequest(index, viewportAnchor)) return false
            logNtkPagePerf(index, "generated_background_defer_initial_continuous", "generation=$generation")
            return true
        }
        if (generation == PRIME_WARM_GENERATION && ntkBackgroundWarmQuietRemainingMs() > 0L) {
            val viewportAnchor = currentViewportAnchor.get().takeIf { it >= 0 } ?: start
            val lastAllowed = minOf(
                pagesLastIndex(),
                viewportAnchor + NTK_INITIAL_INTERACTIVE_VIEWPORT_AHEAD_PAGES
            )
            if (index > lastAllowed) {
                logNtkPagePerf(index, "generated_background_defer_interactive", "generation=$generation")
                return true
            }
        }
        return false
    }

    private fun deferGeneratedTailTrimIfBusy(
        index: Int,
        page: PageRef,
        e: Exception,
        reason: String
    ): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val image = page.image.orEmpty()
        if (image.isNotEmpty() && ReaderImageCache.isKnownNtkGeneratedNotFound(page.manga, image)) {
            return false
        }
        val quietMs = readerQuietRemainingMs(NTK_GENERATED_TAIL_TRIM_QUIET_MS)
        if (quietMs <= 0L) return false
        val path = page.manga.ntkEpisodePath?.trim().orEmpty()
        val key = "$path|${page.sourceIndex}|$reason"
        if (!deferredGeneratedTailTrimKeys.add(key)) return true
        Log.d(
            TAG,
            "trim_generated_tail_defer path=$path,index=$index,source=${page.sourceIndex}," +
                "reason=$reason,quietMs=$quietMs,error=${e.message}"
        )
        main.postDelayed({
            try {
                control.execute {
                    deferredGeneratedTailTrimKeys.remove(key)
                    if (cancelled.get()) return@execute
                    val match = synchronized(pagesLock) {
                        val exact = if (index in pages.indices && pages[index] === page) index else -1
                        val found = if (exact >= 0) {
                            exact
                        } else {
                            pages.indexOfFirst {
                                it.transitionTitle == null &&
                                    it.sourceIndex == page.sourceIndex &&
                                    Manga.sameEpisodeIdentity(it.manga, page.manga)
                            }
                        }
                        if (found >= 0) found to pages[found] else null
                    } ?: return@execute
                    when (reason) {
                        "blank" -> trimBlankNtkGeneratedTail(match.first, match.second)
                        "early_invalid" -> trimEarlyInvalidNtkGeneratedTail(match.first, match.second, e)
                        else -> trimNtkGeneratedTail(match.first, match.second, e)
                    }
                }
            } catch (_: RejectedExecutionException) {
                deferredGeneratedTailTrimKeys.remove(key)
            }
        }, quietMs.coerceAtLeast(NTK_GENERATED_TAIL_TRIM_RECHECK_MS))
        return true
    }

    private fun skipKnownMissingNtkGeneratedPage(index: Int, page: PageRef, source: String): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val image = page.image?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (!isNtkGeneratedImageUrl(image)) return false
        if (!ReaderImageCache.isKnownNtkGeneratedNotFound(page.manga, image)) return false
        clearStaleLoadStateForIndex(index, page)
        loading.remove(index)
        loadingPages.remove(index, page)
        urgentLoading.remove(index)
        urgentLoadingPages.remove(index, page)
        inFlightWidths.remove(index)
        pendingDeliveryWidths.remove(index)
        failedPages.remove(index)
        Log.d(
            TAG,
            "reader_known_generated_not_found_skip page=$index,source=${page.sourceIndex}," +
                "reason=$source,path=${page.manga.ntkEpisodePath},image=${image.substringAfterLast('/')}"
        )
        trimNtkGeneratedTail(
            index,
            page,
            java.io.IOException("Generated image not found: page=${page.sourceIndex + 1} code=404")
        )
        return true
    }

    private fun trimNtkGeneratedTail(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (shouldProtectKnownNtkGeneratedPage(page, e)) {
            Log.d(
                TAG,
                "trim_generated_tail_skip_known_page known=${page.manga.ntkImageCount}," +
                    "index=$index,source=${page.sourceIndex},path=${page.manga.ntkEpisodePath},error=${e.message}"
            )
            return false
        }
        val knownCount = page.manga.ntkImageCount
        val pastGeneratedTail = (e.message ?: "").startsWith("Generated image past tail:")
        val generatedNotFoundTail =
            isNtkGeneratedImageUrl(page.image.orEmpty()) &&
                isImageNotFoundError(e) &&
                page.sourceIndex >= NTK_GENERATED_CONFIRMED_TAIL_MISSING_SOURCE_MIN
        if (knownCount <= 0 && !generatedNotFoundTail && !pastGeneratedTail) return false
        if (!pastGeneratedTail && isNtkGeneratedImageUrl(page.image ?: "")) {
            val displayTotalPages = synchronized(pagesLock) {
                pages.count { ref ->
                    ref.transitionTitle == null && Manga.sameEpisodeIdentity(ref.manga, page.manga)
                }
            }
            if (!generatedNotFoundTail && displayTotalPages > knownCount) {
                Log.d(
                    TAG,
                    "trim_generated_tail_skip_partial_known_count known=$knownCount,displayTotal=$displayTotalPages," +
                        "errorPage=$index,source=${page.sourceIndex},path=${page.manga.ntkEpisodePath},error=${e.message}"
                )
                return false
            }
        }
        val firstTailSourceIndex = when {
            knownCount > 0 && page.sourceIndex >= knownCount -> knownCount
            generatedNotFoundTail -> page.sourceIndex
            pastGeneratedTail && page.sourceIndex >= knownCount -> page.sourceIndex
            else -> return false
        }
        if (firstTailSourceIndex < 0) return false
        if (deferGeneratedTailTrimIfBusy(index, page, e, "tail")) return true
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

    private fun trimBlankNtkGeneratedTail(index: Int, page: PageRef): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        if (!page.image.isNullOrBlank()) return false
        if (isKnownNtkGeneratedPage(page)) {
            Log.d(
                TAG,
                "trim_blank_generated_tail_skip_known_page known=${page.manga.ntkImageCount}," +
                    "index=$index,source=${page.sourceIndex},path=${page.manga.ntkEpisodePath}"
            )
            return false
        }
        if (deferGeneratedTailTrimIfBusy(
                index,
                page,
                IllegalStateException("Blank generated tail"),
                "blank"
            )
        ) {
            return true
        }
        val total: Int
        val displayTotalPages: Int
        synchronized(pagesLock) {
            if (index !in pages.indices || pages[index] !== page || index != pages.lastIndex) return false
            if (index <= 0) return false
            beginStructurePublish()
            pages.removeAt(index)
            removePageStateRange(index, 1)
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
            total = pages.size
        }
        main.post {
            if (!cancelled.get()) listener.onPagesRemoved(index, 1, total)
            finishStructurePublish()
            requestRetainedWindowAfterStructureChange()
        }
        Log.d(
            TAG,
            "trim_blank_generated_tail start=$index,count=1,path=${page.manga.ntkEpisodePath}," +
                "total=$total,displayTotal=$displayTotalPages"
        )
        return true
    }

    private fun trimEarlyInvalidNtkGeneratedTail(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isImageNotFoundError(e)) return false
        if (isKnownNtkGeneratedPage(page) && !initialContinuousDrawableDelivered(currentStartPage())) {
            Log.d(
                TAG,
                "trim_early_invalid_generated_tail_skip_initial_continuous known=${page.manga.ntkImageCount}," +
                    "index=$index,source=${page.sourceIndex},path=${page.manga.ntkEpisodePath},error=${e.message}"
            )
            return true
        }
        if (shouldProtectKnownNtkGeneratedPage(page, e)) {
            Log.d(
                TAG,
                "trim_early_invalid_generated_tail_skip_known_page known=${page.manga.ntkImageCount}," +
                    "index=$index,source=${page.sourceIndex},path=${page.manga.ntkEpisodePath},error=${e.message}"
            )
            return false
        }
        val firstTailSourceIndex = page.sourceIndex
        if (firstTailSourceIndex !in NTK_GENERATED_EARLY_TAIL_MISSING_SOURCE_MIN..NTK_GENERATED_EARLY_TAIL_MISSING_SOURCE_MAX) {
            return false
        }
        if (deferGeneratedTailTrimIfBusy(index, page, e, "early_invalid")) return true
        val ranges = ArrayList<Pair<Int, Int>>()
        val total: Int
        var displayTotalPages = 0
        var removedCount = 0
        synchronized(pagesLock) {
            val removeIndexes = pages.withIndex()
                .filter { item ->
                    item.value.transitionTitle == null &&
                        item.value.sourceIndex >= firstTailSourceIndex &&
                        Manga.sameEpisodeIdentity(item.value.manga, page.manga)
                }
                .map { it.index }
            if (removeIndexes.size < NTK_GENERATED_EARLY_TAIL_MISSING_MIN_REMOVE) return false
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
                val count = rangeEnd - rangeStart + 1
                repeat(count) { pages.removeAt(rangeStart) }
                removePageStateRange(rangeStart, count)
                removedCount += count
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
            total = pages.size
        }
        for ((start, end) in ranges.asReversed()) {
            val count = end - start + 1
            main.post {
                if (!cancelled.get()) listener.onPagesRemoved(start, count, total)
            }
        }
        if (!main.post {
                finishStructurePublish()
                requestRetainedWindowAfterStructureChange()
            }) {
            finishStructurePublish()
            requestRetainedWindowAfterStructureChange()
        }
        Log.d(
            TAG,
            "trim_early_invalid_generated_tail index=$index,source=$firstTailSourceIndex,removed=$removedCount," +
                "displayTotal=$displayTotalPages,path=${page.manga.ntkEpisodePath},error=${e.message}"
        )
        return true
    }

    private fun refreshNtkGeneratedPageImage(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val originalImage = page.image ?: return false
        if (!isNtkGeneratedImageUrl(originalImage)) return false
        if (!isImageNotFoundError(e)) return false
        val repositoryReplacement = imageRepository.imageUrls(page.manga, appContext).getOrNull(page.sourceIndex)
            ?.takeIf { it.isNotBlank() && it != originalImage }
            ?.takeIf { ntkImageCandidateMatchesEpisode(it, page.manga) }
        val replacement = repositoryReplacement
            ?: ntkReplacementImageUrlForPage(
                page.manga,
                originalImage,
                ntkImagePageNumber(originalImage) ?: (page.sourceIndex + 1),
                page.sourceIndex
            )
            ?: return false
        if (replacement == originalImage) {
            if (!hasPageSourceReady(index, page)) return false
            failedPages.remove(index)
            decodedWidths.remove(index)
            desiredWidths.remove(index)
            pendingDeliveryWidths.remove(index)
            sourceWidths.remove(index)
            achievableWidths.remove(index)
            inFlightWidths.remove(index)
            Log.d(
                TAG,
                "refresh_generated_page_image_cached index=$index,source=${page.sourceIndex}," +
                    "path=${page.manga.ntkEpisodePath},image=$originalImage"
            )
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
            return true
        }
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
        val currentIndex = currentIndexForPageRef(index, page)
        if (currentIndex < 0) return false
        if (!firstBitmapLogged.get() && isKnownNtkGeneratedPage(page)) {
            Log.d(
                TAG,
                "remove_invalid_generated_page_skip_initial_known index=$currentIndex," +
                    "source=${page.sourceIndex},known=${page.manga.ntkImageCount}," +
                    "path=${page.manga.ntkEpisodePath},image=$image,error=${e.message}"
            )
            return true
        }
        if (shouldProtectKnownNtkGeneratedPage(page, e) ||
            (isKnownNtkGeneratedPage(page) &&
                (hasDeliveredBitmap(currentIndex) ||
                    hasListenerDrawableDelivery(currentIndex) ||
                    (pendingDeliveryWidths[currentIndex] ?: 0) > 0))
        ) {
            Log.d(
                TAG,
                "remove_invalid_generated_page_skip_known_page known=${page.manga.ntkImageCount}," +
                    "index=$currentIndex,source=${page.sourceIndex},path=${page.manga.ntkEpisodePath},image=$image,error=${e.message}"
            )
            return false
        }
        if (!firstBitmapLogged.get() && currentIndex == currentStartPage()) {
            val replacement = ntkReplacementImageUrlForPage(
                page.manga,
                image,
                ntkImagePageNumber(image) ?: (page.sourceIndex + 1),
                page.sourceIndex
            )
            if (!replacement.isNullOrBlank() && replacement != image) {
                synchronized(pagesLock) {
                    if (currentIndex !in pages.indices || pages[currentIndex] !== page) return false
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
                    "remove_invalid_generated_page_retarget_anchor index=$currentIndex,source=${page.sourceIndex}," +
                        "path=${page.manga.ntkEpisodePath},from=$image,to=$replacement,error=${e.message}"
                )
                requestPage(currentIndex, busy = true, anchor = true, generation = FOREGROUND_PRIME_WARM_GENERATION)
                return true
            }
            Log.d(
                TAG,
                "remove_invalid_generated_page_drop_anchor index=$currentIndex,source=${page.sourceIndex}," +
                    "path=${page.manga.ntkEpisodePath},image=$image,error=${e.message}"
            )
        }
        val removeIndex: Int
        val total: Int
        val displayTotalPages: Int
        synchronized(pagesLock) {
            val latestIndex = if (currentIndex in pages.indices && pages[currentIndex] === page) {
                currentIndex
            } else {
                pages.indexOfFirst { it === page }
            }
            if (latestIndex < 0) return false
            beginStructurePublish()
            removeIndex = latestIndex
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

    private fun isKnownNtkGeneratedPage(page: PageRef): Boolean {
        val knownCount = page.manga.ntkImageCount
        return knownCount > 0 &&
            page.sourceIndex in 0 until knownCount &&
            isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)
    }

    private fun shouldProtectKnownNtkGeneratedPage(page: PageRef, e: Exception): Boolean {
        if (!isKnownNtkGeneratedPage(page)) return false
        if (!firstBitmapLogged.get() && isImageNotFoundError(e)) return true
        if (isNtkGeneratedImageUrl(page.image.orEmpty()) && isImageNotFoundError(e)) {
            return false
        }
        return true
    }

    private fun currentIndexForPageRef(index: Int, page: PageRef): Int {
        synchronized(pagesLock) {
            if (index in pages.indices && pages[index] === page) return index
            return pages.indexOfFirst { it === page }
        }
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

    private fun isNtkImageCloudflareHardBlockError(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("Image request failed: 403") ||
                message.contains(" code=403") ||
                message.contains("cloudflare-html-403", ignoreCase = true)
            ) {
                return true
            }
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
            Regex("://apihost\\d*\\.com/").containsMatchIn(lower) ||
            lower.contains("://moamoabon.com/") ||
            Regex("://fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            lower.startsWith("toonflix.app/") ||
            lower.startsWith("i.toonflix.app/") ||
            Regex("^flysky\\d*m\\.com/").containsMatchIn(lower) ||
            Regex("^apihost\\d*\\.com/").containsMatchIn(lower) ||
            lower.startsWith("moamoabon.com/") ||
            lower.startsWith("//moamoabon.com/") ||
            Regex("^fvcdn\\d*\\.com/").containsMatchIn(lower)
    }

    private fun ntkGeneratedSiblingImageUrl(seed: String, page: Int): String? {
        if (page <= 0) return null
        val match = NTK_GENERATED_IMAGE_URL.matchEntire(seed) ?: return null
        val candidate = "${match.groupValues[1]}p${page.toString().padStart(3, '0')}.${match.groupValues[2]}${match.groupValues[3]}"
        return ReaderImageCache.hintedNtkGeneratedImageUrl(candidate) ?: candidate
    }

    private fun ntkGeneratedSeedPage(seed: String): Int {
        return Regex("/p(\\d{3,})\\.").find(seed)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
    }

    private fun ntkImagePageNumber(image: String): Int? {
        return Regex("/p(\\d{3,})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun ntkGeneratedPageNumber(image: String): Int? {
        return NTK_GENERATED_PAGE_URL.find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun ntkVerifiedImageUrlForPage(target: Manga, page: Int, sourceIndex: Int? = null): String? {
        if (page <= 0) return null
        val cachedImages = ReaderImageCache.cachedNtkApiFallbackImages(target.ntkEpisodePath)
        val earlyImages = ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L)
        val currentImages = target.getImgs(null)
        if (sourceIndex != null && sourceIndex >= 0) {
            sequenceOf(cachedImages, earlyImages, currentImages?.toList().orEmpty())
                .mapNotNull { list ->
                    list.getOrNull(sourceIndex)
                        ?.takeIf { it.isNotBlank() }
                        ?.takeIf { !isNtkGeneratedImageUrl(it) }
                }
                .firstOrNull()
                ?.let { return it }
        }
        val candidates = ArrayList<String>()
        candidates.addAll(cachedImages)
        candidates.addAll(earlyImages)
        if (!currentImages.isNullOrEmpty()) candidates.addAll(currentImages)
        return candidates.firstOrNull { candidate ->
            !isNtkGeneratedImageUrl(candidate) && ntkImagePageNumber(candidate) == page
        }
    }

    private fun postNtkImageCloudflareCaptcha(index: Int, page: PageRef, e: Exception): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        val image = page.image ?: return false
        if (!isNtkGeneratedImageUrl(image)) return false
        if (!isNtkImageCloudflareHardBlockError(e)) return false
        val client = MainApplication.getHttpClient()
        if (!client.hasRecentCloudflareChallenge()) return false
        val now = SystemClock.elapsedRealtime()
        val previous = ntkImageCaptchaLastPostedAt.get()
        if (now - previous < 15_000L) return true
        if (!ntkImageCaptchaLastPostedAt.compareAndSet(previous, now)) return true
        Log.d(
            TAG,
            "reader_ntk_image_cloudflare_captcha index=$index,source=${page.sourceIndex}," +
                "path=${page.manga.ntkEpisodePath},image=${image.substringAfterLast('/').takeLast(80)}," +
                "challenge=${client.getLastCloudflareChallengeUrl()},error=${e.message}"
        )
        postCaptchaRequired(page.manga)
        return true
    }

    private fun ntkReplacementImageUrlForPage(
        target: Manga,
        currentImage: String,
        page: Int,
        sourceIndex: Int? = null
    ): String? {
        if (page <= 0) return null
        val exactReplacement = ntkCachedOrEarlyReplacementImageUrlForPage(
            target,
            currentImage,
            page,
            sourceIndex
        )
        if (!exactReplacement.isNullOrBlank()) return exactReplacement
        val verified = ntkVerifiedImageUrlForPage(target, page, sourceIndex)
        if (!verified.isNullOrBlank() && verified != currentImage) return verified
        return ntkGeneratedImageUrlForTarget(currentImage, target, page)
    }

    private fun ntkCachedOrEarlyReplacementImageUrlForPage(
        target: Manga,
        currentImage: String,
        page: Int,
        sourceIndex: Int?
    ): String? {
        if (page <= 0) return null
        val lists = arrayOf(
            ReaderImageCache.cachedNtkApiFallbackImages(target.ntkEpisodePath),
            ReaderImageCache.earlyNtkImageUrls(target.ntkEpisodePath, SystemClock.elapsedRealtime() - 30000L),
            target.getImgs(null)?.toList().orEmpty()
        )
        if (sourceIndex != null && sourceIndex >= 0) {
            for (list in lists) {
                val candidate = list.getOrNull(sourceIndex) ?: continue
                if (isUsableNtkReplacementImage(candidate, currentImage, target, page)) return candidate
            }
        }
        for (list in lists) {
            for (candidate in list) {
                if (isUsableNtkReplacementImage(candidate, currentImage, target, page)) return candidate
            }
        }
        return null
    }

    private fun replaceUnavailableGeneratedInitialWithAdjacent(source: Manga, startedAt: Long) {
        if (firstBitmapLogged.get()) return
        if (!isNtkSource(source, title)) return
        if (!ntkInitialUnavailableReplacementStarted.compareAndSet(false, true)) return
        logNtkRepositoryStage(
            source,
            "ntk_initial_unavailable_replacement_start",
            "ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
        try {
            initialRecovery.execute {
                try {
                    val replacement = resolveInitialNtkUnavailableEpisode(source)
                    if (cancelled.get() || replacement == null) {
                        logNtkRepositoryStage(
                            source,
                            "ntk_initial_unavailable_replacement_missing",
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        ntkInitialUnavailableReplacementStarted.set(false)
                        return@execute
                    }
                    val target = replacement.first
                    val urls = replacement.second
                    val removed = removeInitialUnavailableGeneratedPages(source)
                    Log.d(
                        TAG,
                        "ntk_initial_unavailable_generated_replaced sourcePath=${source.ntkEpisodePath} " +
                            "targetPath=${target.ntkEpisodePath} images=${urls.size} removed=$removed"
                    )
                    appendResolvedEpisode(target, urls, ReaderSurfaceView.DIRECTION_NEXT, warm = true)
                    val firstImage = firstDrawableIndexForEpisode(target)
                    if (firstImage >= 0) {
                        requestPageForeground(firstImage)
                        requestInitialContinuousPagesFromEarlyUrls(firstImage, synchronized(pagesLock) { pages.size })
                    }
                } catch (e: Exception) {
                    ntkInitialUnavailableReplacementStarted.set(false)
                    recordIfUnexpected(e)
                }
            }
        } catch (_: RejectedExecutionException) {
            ntkInitialUnavailableReplacementStarted.set(false)
        }
    }

    private fun removeInitialUnavailableGeneratedPages(source: Manga): Int {
        val start: Int
        val removed: Int
        val total: Int
        synchronized(pagesLock) {
            val indexes = pages.withIndex()
                .filter {
                    it.value.transitionTitle == null &&
                        Manga.sameEpisodeIdentity(it.value.manga, source) &&
                        isNtkGeneratedImageUrl(it.value.image.orEmpty())
                }
                .map { it.index }
            if (indexes.isEmpty()) return 0
            beginStructurePublish()
            start = indexes.first()
            removed = indexes.size
            for (index in indexes.asReversed()) {
                pages.removeAt(index)
            }
            pages.forEachIndexed { pageIndex, ref -> ref.pageIndex = pageIndex }
            clearPageStateFromIndex(start)
            total = pages.size
        }
        val posted = main.post {
            try {
                if (!cancelled.get()) listener.onPagesRemoved(start, removed, total)
            } finally {
                finishStructurePublish()
            }
        }
        if (!posted) finishStructurePublish()
        return removed
    }

    private fun firstDrawableIndexForEpisode(target: Manga): Int = synchronized(pagesLock) {
        pages.indexOfFirst {
            it.transitionTitle == null && Manga.sameEpisodeIdentity(it.manga, target)
        }
    }

    private fun isUsableNtkReplacementImage(
        candidate: String?,
        currentImage: String,
        target: Manga,
        page: Int
    ): Boolean {
        if (candidate.isNullOrBlank() || candidate == currentImage) return false
        if (ntkImagePageNumber(candidate) != page) return false
        if (!ntkImageCandidateMatchesEpisode(candidate, target)) return false
        if (isNtkGeneratedImageUrl(candidate) &&
            ReaderImageCache.isKnownNtkGeneratedNotFound(target, candidate)
        ) {
            return false
        }
        return true
    }

    private fun ntkGeneratedImageUrlForTarget(seed: String, target: Manga, page: Int): String? {
        if (page <= 0) return null
        val seedMatch = NTK_GENERATED_IMAGE_URL.matchEntire(seed) ?: return null
        val targetMatch = NTK_EPISODE_PATH.matchEntire(target.ntkEpisodePath.orEmpty()) ?: return ntkGeneratedSiblingImageUrl(seed, page)
        val seedTargetPath = ntkGeneratedPathFromPrefix(seedMatch.groupValues[1])
        if (shouldPreserveApiGeneratedSeedPrefix(seedMatch.groupValues[1], target, targetMatch)) {
            return ntkGeneratedSiblingImageUrl(seed, page)
        }
        if ((seedTargetPath != null && seedTargetPath.equals(target.ntkEpisodePath.orEmpty(), ignoreCase = true)) ||
            ntkGeneratedPrefixEpisodeMatchesTarget(seedMatch.groupValues[1], targetMatch)
        ) {
            return ntkGeneratedSiblingImageUrl(seed, page)
        }
        val segment = targetMatch.groupValues[1]
        val pathEpisodeId = targetMatch.groupValues[3]
        val imageEpisodeId = target.ntkImageEpisodeId.orEmpty().trim()
        val episodeId = pathEpisodeId.takeIf { it.matches(Regex("\\d+")) }
            ?: imageEpisodeId.takeIf { it.matches(Regex("\\d+")) }
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
        val candidate = "${targetPrefix}p${actualPage.toString().padStart(3, '0')}.${seedMatch.groupValues[2]}${seedMatch.groupValues[3]}"
        return ReaderImageCache.hintedNtkGeneratedImageUrl(candidate) ?: candidate
    }

    private fun shouldPreserveApiGeneratedSeedPrefix(
        prefix: String,
        target: Manga,
        targetMatch: MatchResult
    ): Boolean {
        if (!isProtectedApiGeneratedPrefix(prefix)) return false
        val targetEpisode = targetMatch.groupValues[3]
        if (!targetEpisode.matches(Regex("\\d+"))) return true
        val imageWorkId = target.ntkImageWorkId.trim()
        val seedWorkId = apiGeneratedPrefixWorkId(prefix)
        return imageWorkId.matches(Regex("\\d{1,12}")) && seedWorkId == imageWorkId
    }

    private fun isProtectedApiGeneratedPrefix(prefix: String): Boolean {
        return prefix.contains("/black/episodes/", ignoreCase = true) ||
            prefix.contains("/blacktoon/episodes/", ignoreCase = true) ||
            prefix.contains("/wt/episodes/", ignoreCase = true)
    }

    private fun apiGeneratedPrefixWorkId(prefix: String): String? {
        return Regex("^https?://[^/]+/(?:black(?:toon)?/episodes|wt/episodes)/([^/?#]+)/", RegexOption.IGNORE_CASE)
            .find(prefix)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun ntkGeneratedPrefixOrigin(prefix: String): String {
        val match = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(prefix)
        return match?.groupValues?.getOrNull(1) ?: "https://i.toonflix.app"
    }

    private fun ntkGeneratedPathFromPrefix(prefix: String): String? {
        Regex("^https?://[^/]+/(manhwa|webtoon)/(\\d+)/([^/?#]+)/$", RegexOption.IGNORE_CASE)
            .find(prefix)
            ?.let { return "/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}" }
        Regex("^https?://[^/]+/black/episodes/(\\d+)/([^/?#]+)/$", RegexOption.IGNORE_CASE)
            .find(prefix)
            ?.let { return "/webtoon/${it.groupValues[1]}/${it.groupValues[2]}" }
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
            prefix.contains("/black/episodes/", ignoreCase = true) ->
                Regex("^https?://[^/]+/black/episodes/[^/]+/([^/?#]+)/$", RegexOption.IGNORE_CASE)
                    .find(prefix)
                    ?.groupValues
                    ?.getOrNull(1)
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

    private fun ntkImageCandidateMatchesEpisode(image: String, target: Manga): Boolean {
        if (!isNtkGeneratedImageUrl(image)) return true
        val targetMatch = NTK_EPISODE_PATH.matchEntire(target.ntkEpisodePath.orEmpty()) ?: return true
        val seedPrefix = NTK_GENERATED_IMAGE_URL.matchEntire(image)?.groupValues?.getOrNull(1) ?: return true
        if (shouldPreserveApiGeneratedSeedPrefix(seedPrefix, target, targetMatch)) return true
        val seedTargetPath = ntkGeneratedPathFromPrefix(seedPrefix)
        if (seedTargetPath != null)
            return seedTargetPath.equals(target.ntkEpisodePath.orEmpty(), ignoreCase = true)
        return ntkGeneratedPrefixEpisodeMatchesTarget(seedPrefix, targetMatch)
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
        shiftConcurrentMapAfterRemoval(deliveredDrawableProofWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(inFlightWidths, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(loadingPages, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(urgentLoadingPages, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(loading, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(urgentLoading, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(bytePrefetching, rangeStart, removedCount)
        shiftConcurrentMapAfterRemoval(bytePrefetchCompletedAtMs, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(fullEpisodeSourcePrefetching, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(preAnchorFallbackRetries, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(initialAdjacentDecodeRetries, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(initialGeneratedAssetDecodeListeners, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(initialGeneratedCachedDecodeInFlight, rangeStart, removedCount)
        shiftConcurrentSetAfterRemoval(initialGeneratedDirectDecodeInFlight, rangeStart, removedCount)
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

    private fun clearPageStateFromIndex(startIndex: Int) {
        clearConcurrentMapFromIndex(decodedWidths, startIndex)
        clearConcurrentMapFromIndex(desiredWidths, startIndex)
        clearConcurrentMapFromIndex(pendingDeliveryWidths, startIndex)
        clearConcurrentMapFromIndex(sourceWidths, startIndex)
        clearConcurrentMapFromIndex(achievableWidths, startIndex)
        clearConcurrentMapFromIndex(transientGeneratedRetries, startIndex)
        clearConcurrentMapFromIndex(initialContinuousPostedWidths, startIndex)
        clearConcurrentMapFromIndex(earlyPreparedBitmaps, startIndex)
        clearConcurrentMapFromIndex(deliveredDrawableProofWidths, startIndex)
        clearConcurrentMapFromIndex(inFlightWidths, startIndex)
        clearConcurrentMapFromIndex(loadingPages, startIndex)
        clearConcurrentMapFromIndex(urgentLoadingPages, startIndex)
        clearConcurrentSetFromIndex(loading, startIndex)
        clearConcurrentSetFromIndex(urgentLoading, startIndex)
        clearConcurrentSetFromIndex(bytePrefetching, startIndex)
        clearConcurrentMapFromIndex(bytePrefetchCompletedAtMs, startIndex)
        clearConcurrentSetFromIndex(fullEpisodeSourcePrefetching, startIndex)
        clearConcurrentSetFromIndex(preAnchorFallbackRetries, startIndex)
        clearConcurrentSetFromIndex(initialAdjacentDecodeRetries, startIndex)
        clearConcurrentSetFromIndex(initialGeneratedAssetDecodeListeners, startIndex)
        clearConcurrentSetFromIndex(initialGeneratedCachedDecodeInFlight, startIndex)
        clearConcurrentSetFromIndex(initialGeneratedDirectDecodeInFlight, startIndex)
        clearConcurrentSetFromIndex(idleFullWidthUpgradeScheduled, startIndex)
        clearConcurrentSetFromIndex(failedPages, startIndex)
        clearConcurrentSetFromIndex(listenerDrawableDeliveries, startIndex)
        clearDeliveryQueueFromIndex(startIndex)
        clearDeliveryMapFromIndex(primedDeliveryBacklog, startIndex)
        clearDeliveryMapFromIndex(initialDeliveryBacklog, startIndex)
        clearPreparedMapFromIndex(initialPreparedBacklog, startIndex)
        synchronized(deliveredBitmaps) {
            clearLinkedMapFromIndex(deliveredBitmaps, startIndex)
            clearLinkedMapFromIndex(deliveredTiles, startIndex)
            deliveredOwned.removeIf { it >= startIndex }
            if (retainedFirstPage >= startIndex) {
                retainedFirstPage = 0
                retainedLastPage = -1
                retainedAnchorPage = 0
            } else if (retainedLastPage >= startIndex) {
                retainedLastPage = startIndex - 1
                retainedAnchorPage = retainedAnchorPage.coerceIn(retainedFirstPage, retainedLastPage)
            }
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

    private fun <T> clearConcurrentMapFromIndex(map: ConcurrentHashMap<Int, T>, startIndex: Int) {
        if (map.isEmpty()) return
        for (key in map.keys.toList()) {
            if (key >= startIndex) map.remove(key)
        }
    }

    private fun clearConcurrentSetFromIndex(set: MutableSet<Int>, startIndex: Int) {
        if (set.isEmpty()) return
        set.removeIf { it >= startIndex }
    }

    private fun <T> clearLinkedMapFromIndex(map: MutableMap<Int, T>, startIndex: Int) {
        if (map.isEmpty()) return
        val iterator = map.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() >= startIndex) iterator.remove()
        }
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

    private fun clearDeliveryQueueFromIndex(startIndex: Int) {
        if (deliveryQueue.isEmpty()) return
        val entries = ArrayList<Delivery>()
        while (true) {
            entries.add(deliveryQueue.poll() ?: break)
        }
        for (delivery in entries) {
            if (delivery.index < startIndex) deliveryQueue.add(delivery)
        }
    }

    private fun clearDeliveryMapFromIndex(map: ConcurrentHashMap<Int, Delivery>, startIndex: Int) {
        clearConcurrentMapFromIndex(map, startIndex)
    }

    private fun clearPreparedMapFromIndex(map: ConcurrentHashMap<Int, PreparedDelivery>, startIndex: Int) {
        clearConcurrentMapFromIndex(map, startIndex)
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
        val initialGeneratedAnchor = shouldUseInitialGeneratedAnchorDirectDecode(index, page)
        if (!initialGeneratedAnchor) {
            cachedDecodedResult(index, page, targetWidth, allowPreviewCache)?.let {
                logNtkPagePerf(index, "cache_hit_decode", "target=$targetWidth,width=${it.width}")
                return it
            }
        }
        if (index == currentStartPage() && isNtkSource(page.manga, title)) {
            val image = page.image
            if (!image.isNullOrBlank()) {
                if (initialGeneratedAnchor) {
                    ReaderImageCache.cachedFile(appContext, page.manga, image)?.let { cached ->
                        logNtkPagePerf(index, "anchor_cached_file_decode", "target=$targetWidth,bytes=${cached.length()}")
                        return decodePage(index, page, cached, targetWidth)
                    }
                }
                decodeForegroundStream(
                    index,
                    page,
                    targetWidth,
                    foregroundFetch = true,
                    visiblePriority = true,
                    permit = foregroundPermit,
                    bypassInitialCachedDecodeGuard = true
                )?.let {
                    logNtkPagePerf(index, "anchor_foreground_stream_hit", "target=$targetWidth,width=${it.width}")
                    return it
                }
                ReaderImageCache.cachedFile(appContext, page.manga, image)?.let { cached ->
                    logNtkPagePerf(index, "anchor_cached_file_decode", "target=$targetWidth,bytes=${cached.length()}")
                    return decodePage(index, page, cached, targetWidth)
                }
            }
        }
        if (initialGeneratedAnchor) {
            cachedDecodedResult(index, page, targetWidth, allowPreviewCache)?.let {
                logNtkPagePerf(index, "cache_hit_decode_after_anchor_stream", "target=$targetWidth,width=${it.width}")
                return it
            }
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
        permit: NtkImagePermit?,
        bypassInitialCachedDecodeGuard: Boolean = false
    ): PageDecodeResult? {
        if (!page.manga.isOnline || (!foregroundFetch && index != currentStartPage())) return null
        val image = page.image ?: return null
        if (isNtkSource(page.manga, title) &&
            isNtkGeneratedImageUrl(image) &&
            (hasDeliveredBitmap(index) ||
                listenerDrawableDeliveries.contains(index) ||
                (pendingDeliveryWidths[index] ?: 0) > 0 ||
                (decodedWidths[index] ?: 0) > 0)
        ) {
            logNtkPagePerf(index, "foreground_stream_skip_ready", "target=$targetWidth")
            return null
        }
        if (!bypassInitialCachedDecodeGuard && shouldPreferInitialGeneratedCachedDecode(index, page)) {
            logNtkPagePerf(index, "foreground_stream_skip_cached_decode_in_flight", "target=$targetWidth")
            return null
        }
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
                if (hasDeliveredBitmap(index) ||
                    listenerDrawableDeliveries.contains(index) ||
                    (pendingDeliveryWidths[index] ?: 0) > 0 ||
                    (decodedWidths[index] ?: 0) > 0
                ) {
                    logNtkPagePerf(
                        index,
                        "foreground_stream_cached_file_fallback_skip_ready",
                        "target=$targetWidth"
                    )
                    return null
                }
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
        if (shouldAbortNtkForegroundStreamAfterRaw(index, page, targetWidth, bypassInitialCachedDecodeGuard)) {
            if (!raw.isRecycled) raw.recycle()
            logNtkPagePerf(index, "foreground_stream_abort_after_raw_ready", "target=$targetWidth")
            return null
        }
        val decodeTargetWidth = decodeTargetWidth(raw.width, raw.height, targetWidth, page.allowAutoSplit)
        val decoded = if (raw.width <= decodeTargetWidth) {
            raw
        } else {
            Decoder(page.manga.seed, page.manga.id).decode(raw, decodeTargetWidth, Glide.get(appContext).bitmapPool)
        }
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        deliverAutoSplitSiblingFromDecoded(index, page, decoded)
        val transformedAt = SystemClock.elapsedRealtime()
        val trimmed = ViewerBitmapTrim.trimBlankVerticalEdges(
            applyAutoSplit(decoded, page.side, page.allowAutoSplit),
            true
        )
        val bitmap = scaleInitialNtkGeneratedForDraw(index, page, trimmed, targetWidth)
        val splitGenerated = shouldSplitNtkGeneratedBitmapForDraw(index, page, bitmap)
        if (!splitGenerated) bitmap.prepareToDraw()
        postPageBounds(index, page, bitmap.width, bitmap.height)
        val result = drawableResult(
            bitmap,
            splitForDraw = shouldSplitDecodedPageForDraw(index, page, splitGenerated),
            forceSplitForDraw = splitGenerated,
            tileHeight = if (splitGenerated) NTK_GENERATED_DRAW_TILE_HEIGHT else DECODED_DRAW_TILE_HEIGHT
        )
        ViewerWarmupManager.logMetric("reader_first_stream_raw_ms", rawAt - metric)
        ViewerWarmupManager.logMetric("reader_first_stream_transform_ms", transformedAt - rawAt)
        ViewerWarmupManager.logMetric("reader_first_decode_total_ms", SystemClock.elapsedRealtime() - metric)
        logNtkPagePerf(index, "foreground_stream_decode", "rawMs=${rawAt - metric},transformMs=${transformedAt - rawAt},totalMs=${SystemClock.elapsedRealtime() - metric},width=${result.width}")
        return result
    }

    private fun shouldAbortNtkForegroundStreamAfterRaw(
        index: Int,
        page: PageRef,
        targetWidth: Int,
        bypassInitialCachedDecodeGuard: Boolean = false
    ): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        val start = currentStartPage()
        if (hasDeliveredBitmap(index) || listenerDrawableDeliveries.contains(index)) return true
        val pendingWidth = pendingDeliveryWidths[index] ?: 0
        if (pendingWidth >= targetWidth) return true
        val decodedWidth = decodedWidths[index] ?: 0
        if (decodedWidth >= targetWidth) return true
        if (index == start && initialAnchorCachedDecodeStarted.get() && !bypassInitialCachedDecodeGuard) {
            return true
        }
        if (index !in (start + 1) until (start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)) return false
        return !bypassInitialCachedDecodeGuard && initialGeneratedCachedDecodeInFlight.contains(index)
    }

    private fun cancelInitialGeneratedForegroundWorkAfterDrawableQueued(
        page: PageRef,
        image: String,
        reason: String
    ) {
        if (!isNtkSource(page.manga, title)) return
        if (!isNtkGeneratedImageUrl(image)) return
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return
        ReaderImageCache.cancelNtkGeneratedForegroundWorkForImages(
            page.manga,
            listOf(image),
            reason
        )
    }

    private fun shouldUseInitialGeneratedCachedDecodeGuard(index: Int, page: PageRef): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        val start = currentStartPage()
        if (index <= start || index >= start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        if (initialContinuousDrawableDelivered(start)) return false
        return true
    }

    private fun shouldUseInitialGeneratedStreamRunwayGuard(index: Int, page: PageRef): Boolean {
        if (firstBitmapLogged.get()) return false
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        val start = currentStartPage()
        if (index <= start || index >= start + NTK_GENERATED_INITIAL_CONTINUOUS_RUNWAY_PAGES) return false
        if (initialContinuousDrawableDelivered(start)) return false
        return true
    }

    private fun shouldUseInitialGeneratedAnchorDirectDecode(index: Int, page: PageRef): Boolean {
        if (firstBitmapLogged.get()) return false
        if (index != currentStartPage()) return false
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        return true
    }

    private fun shouldPreferInitialGeneratedCachedDecode(index: Int, page: PageRef): Boolean {
        if (!shouldUseInitialGeneratedCachedDecodeGuard(index, page)) return false
        return initialGeneratedCachedDecodeInFlight.contains(index)
    }

    private fun shouldUsePreviewDecodedCache(index: Int, targetWidth: Int): Boolean {
        return targetWidth < targetWidth(false) && !hasDeliveredBitmap(index)
    }

    private fun cachedDecodedResult(
        index: Int,
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
        val trimmed = ViewerBitmapTrim.trimBlankVerticalEdges(bitmap)
        val splitGenerated = shouldSplitNtkGeneratedBitmapForDraw(index, page, trimmed)
        return drawableResult(
            trimmed,
            splitForDraw = shouldSplitDecodedPageForDraw(index, page, splitGenerated),
            forceSplitForDraw = splitGenerated,
            tileHeight = if (splitGenerated) NTK_GENERATED_DRAW_TILE_HEIGHT else DECODED_DRAW_TILE_HEIGHT
        )
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
        if (!autoCut && shouldDecodeTiles(index, page, file, bounds)) {
            val displayBounds = displayBounds(bounds.outWidth, bounds.outHeight, page.side, page.allowAutoSplit)
            postPageBounds(index, page, displayBounds.width(), displayBounds.height())
            val result = decodePageTiles(file, bounds, targetWidth)
            if (trace) {
                logNtkPagePerf(index, "decode_tiles", "boundsMs=${boundsAt - startedAt},totalMs=${SystemClock.elapsedRealtime() - startedAt},target=$targetWidth,width=${result.width}")
            }
            return result
        }
        val decodeTargetWidth = decodeTargetWidth(bounds.outWidth, bounds.outHeight, targetWidth, page.allowAutoSplit)
        val initialGeneratedPreview = shouldUseInitialGeneratedPreviewDecode(index, page)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = if (initialGeneratedPreview) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inSampleSize = if (initialGeneratedPreview) {
                initialGeneratedPreviewSampleSize(bounds.outWidth, decodeTargetWidth)
            } else {
                sampleSize(bounds.outWidth, decodeTargetWidth)
            }
            if (initialGeneratedPreview) {
                applyInitialGeneratedDecodeScale(bounds.outWidth, decodeTargetWidth)
            }
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
            bitmap.prepareToDraw()
            postPageBounds(index, page, bitmap.width, bitmap.height)
            val result = drawableResult(bitmap)
            if (trace) {
                logNtkPagePerf(index, "decode_local", "boundsMs=${boundsAt - startedAt},rawMs=${rawAt - boundsAt},totalMs=${SystemClock.elapsedRealtime() - startedAt},target=$targetWidth,width=${result.width}")
            }
            return result
        }
        val decoded = if (
            (isNtkSource(page.manga, title) && isNtkGeneratedImageUrl(page.image.orEmpty())) ||
            raw.width <= decodeTargetWidth
        ) {
            raw
        } else {
            Decoder(page.manga.seed, page.manga.id).decode(raw, decodeTargetWidth, Glide.get(appContext).bitmapPool)
        }
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        deliverAutoSplitSiblingFromDecoded(index, page, decoded)
        val transformedAt = if (metric || trace) SystemClock.elapsedRealtime() else 0L
        val trimmed = ViewerBitmapTrim.trimBlankVerticalEdges(
            applyAutoSplit(decoded, page.side, page.allowAutoSplit),
            true
        )
        val bitmap = scaleInitialNtkGeneratedForDraw(index, page, trimmed, targetWidth)
        val splitGenerated = shouldSplitNtkGeneratedBitmapForDraw(index, page, bitmap)
        if (!splitGenerated) bitmap.prepareToDraw()
        postPageBounds(index, page, bitmap.width, bitmap.height)
        val result = drawableResult(
            bitmap,
            splitForDraw = shouldSplitDecodedPageForDraw(index, page, splitGenerated),
            forceSplitForDraw = splitGenerated,
            tileHeight = if (splitGenerated) NTK_GENERATED_DRAW_TILE_HEIGHT else DECODED_DRAW_TILE_HEIGHT
        )
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

    private fun BitmapFactory.Options.applyInitialGeneratedDecodeScale(sourceWidth: Int, targetWidth: Int) {
        if (sourceWidth <= 0 || targetWidth <= 0 || sourceWidth == targetWidth) return
        if (sourceWidth < targetWidth) {
            val delta = targetWidth - sourceWidth
            if (delta > max(8, targetWidth / 100)) return
        }
        inScaled = true
        inDensity = sourceWidth
        inTargetDensity = targetWidth
    }

    private fun initialGeneratedPreviewSampleSize(sourceWidth: Int, targetWidth: Int): Int {
        return sampleSize(sourceWidth, targetWidth)
    }

    private fun shouldUseNtkInitialGeneratedDisplayDecode(index: Int, page: PageRef): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        val start = currentStartPage()
        if (index < start || index > start + NTK_INITIAL_DIRECT_DELIVERY_PAGES) return false
        val path = page.manga.ntkEpisodePath.orEmpty()
        return path.startsWith("/webtoon/", ignoreCase = true) ||
            path.startsWith("/manhwa/", ignoreCase = true)
    }

    private fun drawableResult(
        bitmap: Bitmap,
        splitForDraw: Boolean = true,
        forceSplitForDraw: Boolean = false,
        tileHeight: Int = DECODED_DRAW_TILE_HEIGHT
    ): PageDecodeResult {
        if (!splitForDraw || (!forceSplitForDraw && !shouldSplitDecodedBitmapForDraw(bitmap))) {
            return PageDecodeResult.Full(bitmap)
        }
        val tiles = ArrayList<ReaderTile>()
        val width = bitmap.width
        val height = bitmap.height
        val safeTileHeight = max(1, tileHeight)
        try {
            var top = 0
            while (top < height) {
                val bottom = minOf(height, top + safeTileHeight)
                val tileBitmap = Bitmap.createBitmap(bitmap, 0, top, width, bottom - top)
                prepareBitmapForDraw(tileBitmap)
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

    private fun shouldSplitDecodedPageForDraw(index: Int, page: PageRef, splitGenerated: Boolean): Boolean {
        if (isNaverWebtoonPageImageUrl(page.image.orEmpty())) {
            return index != currentStartPage()
        }
        return !isNtkGeneratedImageUrl(page.image.orEmpty()) || splitGenerated
    }

    private fun shouldSplitDecodedBitmapForDraw(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        if (bitmap.height < DECODED_DRAW_TILE_MIN_HEIGHT) return false
        return bitmapBytes(bitmap) >= DECODED_DRAW_TILE_MIN_BYTES
    }

    private fun shouldSplitNtkGeneratedBitmapForDraw(index: Int, page: PageRef, bitmap: Bitmap): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        if (shouldUseInitialGeneratedPreviewDecode(index, page) && index == currentStartPage()) return false
        if (!firstBitmapLogged.get() && index == currentStartPage()) return false
        if (
            !hasDeliveredBitmap(index) &&
            index < currentStartPage() + NTK_GENERATED_INITIAL_UNTILED_DRAW_PAGES
        ) {
            return false
        }
        return shouldSplitDecodedBitmapForDraw(bitmap) || shouldSplitGeneratedBitmapForInteractiveDraw(bitmap)
    }

    private fun shouldSplitGeneratedBitmapForInteractiveDraw(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (bitmapBytes(bitmap) < NTK_GENERATED_DRAW_TILE_MIN_BYTES) return false
        val drawHeight = viewerWidth.toLong() * bitmap.height.toLong() / max(1, bitmap.width)
        val minDrawHeight = max(1, viewerHeight) * NTK_GENERATED_DRAW_TILE_MIN_VIEWPORT_PERMILLE / 1000
        return drawHeight >= minDrawHeight
    }

    private fun scaleInitialNtkGeneratedForDraw(
        index: Int,
        page: PageRef,
        bitmap: Bitmap,
        targetWidth: Int
    ): Bitmap {
        if (!isNtkSource(page.manga, title)) return bitmap
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return bitmap
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return bitmap
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        val safeTargetWidth = max(1, targetWidth)
        if (bitmap.width == safeTargetWidth) return bitmap
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val scaledHeight = max(1, (sourceHeight.toLong() * safeTargetWidth / sourceWidth).toInt())
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, safeTargetWidth, scaledHeight, false)
            if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            logNtkPagePerf(
                index,
                "generated_scale_for_draw",
                "from=${sourceWidth}x${sourceHeight},to=${scaled.width}x${scaled.height},target=$safeTargetWidth"
            )
            scaled
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun shouldUseInitialGeneratedPreviewDecode(index: Int, page: PageRef): Boolean {
        if (!isNtkSource(page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(page.image.orEmpty())) return false
        if (!isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) return false
        val start = currentStartPage()
        if (index !in start until (start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES)) return false
        if (initialContinuousDrawableDelivered(start)) return false
        return true
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

    private fun shouldDecodeTiles(index: Int, page: PageRef, file: File, bounds: BitmapFactory.Options): Boolean {
        if (page.manga.seed != 0) return false
        if (!file.isFile) return false
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false
        if (
            isNtkSource(page.manga, title) &&
            isNtkGeneratedImageUrl(page.image.orEmpty()) &&
            index == currentStartPage() &&
            !firstBitmapLogged.get()
        ) {
            return false
        }
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
        val sourceCeiling = pageRef(index)?.let { page ->
            val canBypassGeneratedPreviewWidthCap =
                firstBitmapLogged.get() &&
                    sourceWidth <= NTK_GENERATED_DIRECT_DRAW_MIN_WIDTH &&
                    sourceWidth < requestedWidth
            if (isNtkSource(page.manga, title) &&
                isNtkGeneratedImageUrl(page.image.orEmpty()) &&
                isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath) &&
                canBypassGeneratedPreviewWidthCap
            ) {
                requestedWidth
            } else {
                max(1, sourceWidth)
            }
        } ?: max(1, sourceWidth)
        return minOf(requestedWidth, sourceCeiling, max(1, decodeCeiling))
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
        deliveredDrawableProofWidths[index] = max(1, bitmap.width)
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

    private fun trackDeliveredResult(index: Int, result: PageDecodeResult, owned: Boolean = true) {
        when (result) {
            is PageDecodeResult.Full -> trackDeliveredBitmap(index, result.bitmap, owned)
            is PageDecodeResult.Tiles -> trackDeliveredTiles(index, result.tiles, owned)
        }
    }

    private fun trackDeliveredTiles(index: Int, tiles: List<ReaderTile>, owned: Boolean = true) {
        deliveredDrawableProofWidths[index] = max(
            1,
            tiles.firstOrNull()?.sourceWidth ?: tiles.firstOrNull()?.bitmap?.width ?: 1
        )
        val cleared = ArrayList<BitmapRelease>()
        synchronized(deliveredBitmaps) {
            val previous = deliveredBitmaps.remove(index)
            if (previous != null && deliveredOwned.remove(index)) {
                cleared.add(BitmapRelease(index, previous, false))
            }
            deliveredTiles.put(index, tiles)?.forEach { tile ->
                cleared.add(BitmapRelease(index, tile.bitmap, false))
            }
            if (owned) deliveredOwned.add(index) else deliveredOwned.remove(index)
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
        if (hasDeliveredAtLeast(currentDelivery, currentDelivery.result.width) &&
            !shouldForceInitialAnchorDrawableDelivery(currentDelivery)
        ) {
            ViewerWarmupManager.logMetric("reader_drop_duplicate_before_queue", delivery.result.width.toLong())
            recycleDecodeResult(delivery.result)
            return
        }
        val pendingWidth = pendingDeliveryWidths[currentDelivery.index] ?: 0
        if (pendingWidth >= currentDelivery.result.width) {
            if (
                shouldDeliverInitialContinuousImmediately(currentDelivery) &&
                !hasDeliveredBitmap(currentDelivery.index) &&
                currentDelivery.result.width > pendingWidth
            ) {
                takeQueuedVisibleDelivery(currentDelivery.index)?.let { stale ->
                    recycleDecodeResult(stale.result)
                    logNtkPagePerf(
                        currentDelivery.index,
                        "replace_pending_initial_continuous_delivery",
                        "pending=$pendingWidth,width=${currentDelivery.result.width}"
                    )
                }
                pendingDeliveryWidths.remove(currentDelivery.index, pendingWidth)
                initialContinuousPostedWidths.remove(currentDelivery.index)
            } else {
                ViewerWarmupManager.logMetric("reader_drop_duplicate_pending_delivery", delivery.result.width.toLong())
                logNtkPagePerf(
                    currentDelivery.index,
                    "drop_duplicate_pending_delivery",
                    "pending=$pendingWidth,width=${currentDelivery.result.width}"
                )
                recycleDecodeResult(delivery.result)
                return
            }
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
            val preRenderedInitialAnchor = preRenderInitialAnchorDrawable(currentDelivery)
            if (preRenderedInitialAnchor != InitialPrerenderResult.NOT_RENDERED) {
                preRenderedInitialDeliveries.add(currentDelivery.index)
                logFirstBitmapIfNeeded(currentDelivery.startedAt)
                firstDrawableDelivered.compareAndSet(false, true)
                ntkCoordinator?.markFirstDrawableCommitted(currentDelivery.index)
            }
            val primeNearBeforeAnchorDelivery = primeNearAfterInitialAnchorDelivery &&
                shouldPrimeNtkNearPagesBeforeAnchorDelivery(currentDelivery)
            if (primeNearBeforeAnchorDelivery) {
                primeNtkNearPagesAfterAnchorDecode(currentDelivery.index)
            }
            val queuedAt = SystemClock.elapsedRealtime()
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
                deliverDecodeResultOnMain(currentDelivery, false, pagesReadyBeforeDrawable = false)
                deliverInitialPagesReadyIfNeeded(
                    synchronized(pagesLock) { pages.size },
                    currentStartPage(),
                    true
                )
                if (primeNearAfterInitialAnchorDelivery && !primeNearBeforeAnchorDelivery) {
                    val primeNear = Runnable {
                        if (!cancelled.get()) {
                            primeNtkNearPagesAfterAnchorDecode(currentDelivery.index)
                        }
                    }
                    if (isNtkWebtoonSource(currentDelivery.page.manga, title)) {
                        main.postDelayed(primeNear, NTK_WEBTOON_ANCHOR_PRIME_AFTER_DRAW_DELAY_MS)
                    } else {
                        main.post(primeNear)
                    }
                }
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
            val preRenderedInitialContinuous = preRenderInitialContinuousDrawable(currentDelivery)
            if (preRenderedInitialContinuous != InitialPrerenderResult.NOT_RENDERED) {
                listenerDrawableDeliveries.add(currentDelivery.index)
                preRenderedInitialContinuousDeliveries.add(currentDelivery.index)
                Log.d(
                    TAG,
                    "reader_initial_continuous_prerender page=${currentDelivery.index}," +
                        "start=${currentStartPage()},result=$preRenderedInitialContinuous"
                )
            }
            ViewerWarmupManager.logMetric("reader_initial_continuous_delivery_direct", currentDelivery.index.toLong())
            Log.d(
                TAG,
                "reader_initial_continuous_delivery_direct page=${currentDelivery.index}," +
                    "start=${currentStartPage()},width=${currentDelivery.result.width}"
            )
            val deliverInitialContinuous = Runnable {
                if (cancelled.get()) {
                    pendingDeliveryWidths.remove(currentDelivery.index)
                    initialContinuousPostedWidths.remove(currentDelivery.index)
                    recycleDecodeResult(currentDelivery.result)
                    return@Runnable
                }
                try {
                    deliverDecodeResultOnMain(currentDelivery, false)
                } finally {
                    initialContinuousPostedWidths.remove(currentDelivery.index)
                }
            }
            val delayMs = initialContinuousDeliveryDelayMs(currentDelivery)
            val posted = if (delayMs > 0L) {
                main.postDelayed(deliverInitialContinuous, delayMs)
            } else {
                mainImmediate.postAtFrontOfQueue(deliverInitialContinuous)
            }
            if (!posted) {
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

    private fun preRenderInitialContinuousDrawable(delivery: Delivery): InitialPrerenderResult {
        if (cancelled.get()) return InitialPrerenderResult.NOT_RENDERED
        if (!isInitialContinuousNtkImage(delivery.page)) return InitialPrerenderResult.NOT_RENDERED
        return try {
            when (val result = delivery.result) {
                is PageDecodeResult.Full ->
                    listener.onInitialContinuousPageDecoded(delivery.index, result.bitmap)
                is PageDecodeResult.Tiles ->
                    listener.onInitialContinuousPageTilesDecoded(
                        delivery.index,
                        result.pageWidth,
                        result.pageHeight,
                        result.tiles
                    )
            }
        } catch (e: Throwable) {
            Log.d(
                TAG,
                "reader_initial_continuous_prerender_error page=${delivery.index},error=${e.javaClass.simpleName}"
            )
            InitialPrerenderResult.NOT_RENDERED
        }
    }

    private fun shouldDeliverInitialAnchorImmediately(delivery: Delivery): Boolean {
        if (firstDrawableDelivered.get()) return false
        return delivery.index == currentStartPage()
    }

    private fun shouldForceInitialAnchorDrawableDelivery(delivery: Delivery): Boolean {
        if (!isNtkSource(delivery.page.manga, title)) return false
        if (firstDrawableDelivered.get()) return false
        return delivery.index == currentStartPage()
    }

    private fun preRenderInitialAnchorDrawable(delivery: Delivery): InitialPrerenderResult {
        if (cancelled.get()) return InitialPrerenderResult.NOT_RENDERED
        if (!isNtkSource(delivery.page.manga, title)) return InitialPrerenderResult.NOT_RENDERED
        if (delivery.index != currentStartPage()) return InitialPrerenderResult.NOT_RENDERED
        return try {
            when (val result = delivery.result) {
                is PageDecodeResult.Full ->
                    listener.onInitialPageDecoded(delivery.index, result.bitmap)
                is PageDecodeResult.Tiles ->
                    listener.onInitialPageTilesDecoded(
                        delivery.index,
                        result.pageWidth,
                        result.pageHeight,
                        result.tiles
                    )
            }
        } catch (e: Throwable) {
            Log.d(
                TAG,
                "reader_initial_anchor_prerender_error page=${delivery.index},error=${e.javaClass.simpleName}"
            )
            InitialPrerenderResult.NOT_RENDERED
        }
    }

    private fun initialAnchorCoalesceDelayMs(delivery: Delivery): Long {
        if (firstBitmapLogged.get()) return 0L
        if (!isNtkSource(manga, title)) return 0L
        if (isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)) return 0L
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
        if (!isInitialContinuousNtkImage(delivery.page)) return false
        val start = currentStartPage()
        if (delivery.index !in start until start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        val currentIndex = currentPageIndex(delivery.page, delivery.index)
        return currentIndex == delivery.index
    }

    private fun shouldAllowInitialRequestDuringStructurePublish(
        index: Int,
        page: PageRef,
        visibleIntent: Boolean
    ): Boolean {
        if (!visibleIntent) return false
        if (!isNtkSource(page.manga, title)) return false
        val start = currentStartPage()
        if (!firstBitmapLogged.get() && index == start) return true
        if (!isInitialContinuousNtkImage(page)) return false
        if (index !in start until start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        return currentPageIndex(page, index) == index
    }

    private fun isInitialContinuousNtkImage(page: PageRef): Boolean {
        val image = page.image.orEmpty()
        return isNtkGeneratedImageUrl(image) ||
            ReaderImageCache.isTrustedInitialNtkApiImageForEarlyStream(image) ||
            hasFreshEarlyNtkImageUrlFor(page.manga, image, requireExact = true)
    }

    private fun shouldDeliverInitialContinuousImmediately(delivery: Delivery): Boolean {
        if (shouldDeliverPreFirstInitialContinuousNow(delivery)) return true
        if (!firstBitmapLogged.get()) return false
        if (!isNtkSource(manga, title)) return false
        if (!delivery.retainWhenBusy) return false
        val start = currentStartPage()
        if (delivery.index <= start) return false
        if (delivery.index >= start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        if (!initialContinuousDrawableDelivered(start)) return true
        val firstBitmapAt = ntkFirstBitmapAtMs.get()
        if (firstBitmapAt > 0L &&
            SystemClock.uptimeMillis() - firstBitmapAt > NTK_INITIAL_CONTINUOUS_DIRECT_WINDOW_MS
        ) return false
        return true
    }

    private fun shouldDeliverPreFirstInitialContinuousNow(delivery: Delivery): Boolean {
        if (firstBitmapLogged.get() || ntkFirstBitmapAtMs.get() > 0L) return false
        if (!isNtkSource(manga, title)) return false
        if (!delivery.retainWhenBusy) return false
        val start = currentStartPage()
        if (delivery.index <= start || delivery.index >= start + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES) return false
        val image = delivery.page.image.orEmpty()
        if (!isInitialContinuousNtkImage(delivery.page)) return false
        if (isFreshExactEarlyNtkImageUrl(delivery.page.manga, image)) return true
        return hasFreshEarlyNtkImageUrlFor(delivery.page.manga, image, requireExact = false)
    }

    private fun initialContinuousDeliveryDelayMs(delivery: Delivery): Long {
        val delta = (delivery.index - currentStartPage()).coerceAtLeast(1)
        return (delta - 1).toLong() * NTK_INITIAL_CONTINUOUS_DELIVERY_STAGGER_MS
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

    private fun shouldPrimeNtkNearPagesBeforeAnchorDelivery(delivery: Delivery): Boolean {
        if (!isNtkSource(delivery.page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) return false
        if (isNtkManhwaOrWebtoonEpisodePath(delivery.page.manga.ntkEpisodePath) &&
            !initialContinuousDrawableDelivered(currentStartPage())
        ) return false
        return isNtkManhwaOrWebtoonEpisodePath(delivery.page.manga.ntkEpisodePath)
    }

    private fun primeNtkNearPagesAfterAnchorDecode(anchor: Int) {
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val holdInitialContinuousTail = shouldHoldNtkGeneratedTailForInitialContinuous()
        val deferWideInitialPrime = isImmediateNtkGeneratedUx() &&
            (viewportBusy.get() || ntkInitialInteractiveSettleRemainingMs() > 0L ||
                ntkBackgroundWarmQuietRemainingMs() > 0L)
        val ahead = if (holdInitialContinuousTail || deferWideInitialPrime) {
            NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        } else if (isNtkWebtoonSource(manga, title)) {
            NTK_INITIAL_ANCHOR_DECODE_WEBTOON_PRIME_PAGES
        } else {
            NTK_INITIAL_ANCHOR_DECODE_PRIME_PAGES
        }
        val last = minOf(count - 1, anchor + ahead)
        if (last <= anchor) return
        for (index in (anchor + 1)..last) {
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
        }
        Log.d(
            TAG,
            "reader_ntk_anchor_decode_prime anchor=$anchor count=${last - anchor}," +
                "holdInitialContinuousTail=$holdInitialContinuousTail," +
                "deferWideInitialPrime=$deferWideInitialPrime"
        )
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
        if (shouldDeliverPreFirstInitialContinuousNow(delivery)) return false
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
            val queued = takeQueuedVisibleDelivery(index)
            if (queued != null) {
                Log.d(
                    TAG,
                    "reader_visible_pending_promote_queued page=${queued.index}," +
                        "ms=${SystemClock.elapsedRealtime() - queuedAt},width=${queued.result.width}"
                )
                deliverDecodeResultOnMain(queued, false)
                return@Runnable
            }
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

    private fun takeQueuedVisibleDelivery(index: Int): Delivery? {
        primedDeliveryBacklog.remove(index)?.let { primed ->
            return deliveryAtCurrentIndex(primed)
        }
        val iterator = deliveryQueue.iterator()
        while (iterator.hasNext()) {
            val delivery = iterator.next()
            val currentDelivery = deliveryAtCurrentIndex(delivery) ?: continue
            if (currentDelivery.index != index) continue
            if (deliveryQueue.remove(delivery)) return currentDelivery
        }
        return null
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
        val deliverHeldNow = (reason == "anchor" || reason == "viewport" || reason == "fallback" ||
            reason == "promote" || reason == "test") &&
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
        if (initialAnchorGeneratedCoversViewport(start)) return true
        return initialPromotedScrollCushionReady(start)
    }

    private fun initialAnchorGeneratedCoversViewport(start: Int): Boolean {
        if (!isNtkManhwaOrWebtoonEpisodePath(manga.ntkEpisodePath)) return false
        val delivery = initialDeliveryBacklog[start]?.let { deliveryAtCurrentIndex(it) } ?: return false
        if (delivery.index != start) return false
        if (!isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) return false
        return resultDrawHeightPx(delivery.result) >= max(1, viewerHeight).toFloat()
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
        if (ntk &&
            shouldHoldNtkGeneratedTailForInitialContinuous() &&
            index > current + ntkInitialGeneratedRunwayPagesForHold() - 1
        ) {
            return false
        }
        if (ntk && isInitialRapidGeneratedUrgent(index, page, busy, generation)) {
            return true
        }
        val foregroundAhead = if (ntk && isNtkWebtoonSource(page.manga, title)) {
            NTK_WEBTOON_FOREGROUND_STREAM_AHEAD_PAGES
        } else {
            NTK_FOREGROUND_STREAM_AHEAD_PAGES
        }
        val generatedPrimeLast = if (ntk && generation == FOREGROUND_PRIME_WARM_GENERATION) {
            ntkGeneratedPrimeForegroundLastIndex(current, foregroundAhead)
        } else {
            current + foregroundAhead
        }
        if (ntk && !firstBitmapLogged.get()) {
            if (
                urgent &&
                generation == FOREGROUND_PRIME_WARM_GENERATION &&
                index <= generatedPrimeLast &&
                isVerifiedEarlyNtkGeneratedPage(page)
            ) {
                return true
            }
            return urgent &&
                generation == FOREGROUND_PRIME_WARM_GENERATION &&
                ntkCoordinator?.allowsPreAnchorFallback(index, page.image, "shouldUseForegroundFetch") == true
        }
        if (urgent) {
            if (
                ntk &&
                firstBitmapLogged.get() &&
                busy &&
                generation == FOREGROUND_PRIME_WARM_GENERATION &&
                index <= generatedPrimeLast &&
                isNtkGeneratedImageUrl(page.image.orEmpty())
            ) {
                return true
            }
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
        if (generation == FOREGROUND_PRIME_WARM_GENERATION && ntk) {
            return index <= generatedPrimeLast
        }
        if (ntk && index > current + foregroundAhead) {
            return false
        }
        if (!urgent && (!busy || generation == PRIME_WARM_GENERATION)) return false
        val image = page.image
        val requestImage = image ?: return false
        return !ReaderImageCache.hasActiveFetch(page.manga, requestImage)
    }

    private fun isVerifiedEarlyNtkGeneratedPage(page: PageRef): Boolean {
        val image = page.image.orEmpty()
        if (!isNtkGeneratedImageUrl(image)) return false
        return isFreshExactEarlyNtkImageUrl(page.manga, image) ||
            hasFreshEarlyNtkImageUrlFor(page.manga, image, requireExact = false)
    }

    private fun ntkInitialGeneratedRunwayPagesForHold(): Int {
        return if (isImmediateNtkGeneratedUx()) {
            NTK_GENERATED_IMMEDIATE_RUNWAY_PAGES
        } else {
            NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES
        }
    }

    private fun ntkGeneratedPrimeForegroundLastIndex(current: Int, foregroundAhead: Int): Int {
        if (!isNtkGeneratedFastParse()) return current + foregroundAhead
        val refs = synchronized(pagesLock) { pages.toList() }
        if (refs.isEmpty() || !isGeneratedOnlyNtkRefs(refs)) return current + foregroundAhead
        if (refs.size <= NTK_FULL_SURFACE_WARM_MAX_PAGES) return refs.lastIndex
        val initialLast = current + NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES - 1
        return maxOf(current + foregroundAhead, initialLast)
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

    private fun deliverDecodeResultOnMain(
        delivery: Delivery,
        busy: Boolean,
        pagesReadyBeforeDrawable: Boolean = true
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val posted = mainImmediate.postAtFrontOfQueue {
                deliverDecodeResultOnMain(delivery, busy, pagesReadyBeforeDrawable)
            }
            if (!posted) {
                pendingDeliveryWidths.remove(delivery.index)
                recycleDecodeResult(delivery.result)
            }
            return
        }
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
            if (shouldKeepBusyGeneratedPrimed(currentDelivery)) {
                storePrimedDelivery(currentDelivery)
                Log.d(
                    TAG,
                    "reader_busy_generated_delivery_primed page=${currentDelivery.index}," +
                        "width=${currentDelivery.result.width}"
                )
                return
            }
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
                }
            }
            index
        }
        if (currentIndex < 0 ||
            (busy && currentIndex !in retainedFirst..retainedLast &&
                !shouldDeliverBusyGeneratedOutsideRetained(currentDelivery, currentIndex))
        ) {
            if (currentIndex >= 0 && shouldKeepBusyGeneratedPrimed(currentDelivery)) {
                storePrimedDelivery(currentDelivery.copy(index = currentIndex))
                Log.d(
                    TAG,
                    "reader_busy_generated_delivery_primed page=$currentIndex," +
                        "width=${currentDelivery.result.width}"
                )
                return
            }
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
        val deliveredWidth = decodedWidths[currentIndex] ?: 0
        decodedWidths[currentIndex] = max(deliveredWidth, currentDelivery.result.width)
        val fullWidth = achievableWidth(currentIndex, targetWidth(false))
        val shouldUpgradeRetainedLowRes =
            currentIndex in retainedFirst..retainedLast &&
                currentDelivery.result.width < fullWidth
        if (shouldUpgradeRetainedLowRes) {
            scheduleIdleFullWidthUpgrade(currentIndex, fullWidth)
        }
        desiredWidths[currentIndex] = max(desiredWidths[currentIndex] ?: 0, currentDelivery.requestedWidth)
        if (currentDelivery.result.width < currentDelivery.requestedWidth) {
            achievableWidths[currentIndex] = max(
                achievableWidths[currentIndex] ?: 0,
                currentDelivery.result.width
            )
        }
        val preRenderedInitialDelivery = preRenderedInitialDeliveries.remove(currentIndex) &&
            currentIndex == currentStartPage()
        val preRenderedInitialContinuousDelivery =
            preRenderedInitialContinuousDeliveries.remove(currentIndex)
        trackDeliveredResult(
            currentIndex,
            currentDelivery.result,
            owned = !preRenderedInitialDelivery && !preRenderedInitialContinuousDelivery
        )
        pendingDeliveryWidths.remove(currentDelivery.index)
        failedPages.remove(currentDelivery.index)
        failedPages.remove(currentIndex)
        transientGeneratedRetries.remove(currentDelivery.index)
        transientGeneratedRetries.remove(currentIndex)
        initialContinuousPostedWidths.remove(currentDelivery.index)
        initialContinuousPostedWidths.remove(currentIndex)
        if (pagesReadyBeforeDrawable) deliverInitialPagesReadyForCurrentPagesIfNeeded()
        logFirstBitmapIfNeeded(currentDelivery.startedAt)
        when (val result = currentDelivery.result) {
            is PageDecodeResult.Full -> listener.onPageReady(currentIndex, result.bitmap)
            is PageDecodeResult.Tiles -> listener.onPageTilesReady(currentIndex, result.pageWidth, result.pageHeight, result.tiles)
        }
        if (!pagesReadyBeforeDrawable) deliverInitialPagesReadyForCurrentPagesIfNeeded()
        listenerDrawableDeliveries.add(currentIndex)
        if (currentIndex == currentStartPage()) {
            firstDrawableDelivered.compareAndSet(false, true)
        }
        ntkCoordinator?.markFirstDrawableCommitted(currentIndex)
        if (currentIndex == currentStartPage() && !initialDeliveryFlushInProgress.get()) {
            if (isNtkSource(currentDelivery.page.manga, title)) {
                main.post {
                    if (!cancelled.get()) flushInitialHeldDeliveries("anchor")
                }
            } else {
                flushInitialHeldDeliveries("anchor")
            }
        }
        main.post { releaseInitialFanoutIfAnchorReady(currentIndex) }
        retryPendingWidthIfNeeded(currentIndex)
    }

    private fun shouldDeliverBusyGeneratedOutsideRetained(delivery: Delivery, index: Int): Boolean {
        if (!delivery.retainWhenBusy) return false
        if (!isNtkSource(delivery.page.manga, title)) return false
        if (!isNtkGeneratedImageUrl(delivery.page.image.orEmpty())) return false
        if (index < 0) return false
        val anchor = currentViewportAnchor.get().takeIf { it >= 0 } ?: currentStartPage()
        val radius = NTK_WEBTOON_BUSY_VISIBLE_DECODE_RADIUS
        return index in max(0, anchor - radius)..(anchor + radius)
    }

    private fun shouldKeepBusyGeneratedPrimed(delivery: Delivery): Boolean {
        return delivery.retainWhenBusy &&
            isNtkSource(delivery.page.manga, title) &&
            isNtkGeneratedImageUrl(delivery.page.image.orEmpty())
    }

    private fun storePrimedDelivery(delivery: Delivery) {
        val previous = primedDeliveryBacklog.put(delivery.index, delivery)
        if (previous != null && previous !== delivery) recycleDecodeResult(previous.result)
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
            is PageDecodeResult.Full -> {
                if (!isNonOwnedDeliveredBitmap(result.bitmap)) recycleBitmapAsync(result.bitmap)
            }
            is PageDecodeResult.Tiles -> result.tiles.forEach { tile ->
                if (!isNonOwnedDeliveredBitmap(tile.bitmap)) recycleBitmapAsync(tile.bitmap)
            }
        }
    }

    private fun isNonOwnedDeliveredBitmap(bitmap: Bitmap): Boolean = synchronized(deliveredBitmaps) {
        deliveredBitmaps.entries.any { (index, delivered) ->
            delivered === bitmap && !deliveredOwned.contains(index)
        } || deliveredTiles.entries.any { (index, tiles) ->
            !deliveredOwned.contains(index) && tiles.any { it.bitmap === bitmap }
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
            deliveredDrawableProofWidths.clear()
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

    private fun targetWidth(page: PageRef, busy: Boolean): Int {
        val width = max(1, viewerWidth)
        if (isNtkWebtoonSource(page.manga, title) || isNtkManhwaOrWebtoonEpisodePath(page.manga.ntkEpisodePath)) {
            return width
        }
        return targetWidth(busy)
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
        private const val URGENT_VISIBLE_PIPELINE_PARALLELISM = 6
        private const val PRIME_PIPELINE_PARALLELISM = 4
        private const val ADJACENT_PIPELINE_PARALLELISM = 3
        private const val NTK_GENERATED_EARLY_TAIL_MISSING_SOURCE_MIN = 3
        private const val NTK_GENERATED_EARLY_TAIL_MISSING_SOURCE_MAX = 12
        private const val NTK_GENERATED_EARLY_TAIL_MISSING_MIN_REMOVE = 2
        private const val NTK_GENERATED_CONFIRMED_TAIL_MISSING_SOURCE_MIN = 12
        private const val NTK_GENERATED_TAIL_TRIM_QUIET_MS = 1200L
        private const val NTK_GENERATED_TAIL_TRIM_RECHECK_MS = 240L
        private const val NTK_FULL_SURFACE_WARM_MAX_PAGES = 128
        private const val NTK_FOREGROUND_PRIME_HEDGE_DELAY_MS = 1400L
        private const val NTK_VISIBLE_GENERATED_BYTE_HEDGE_DELAY_MS = 0L
        private const val NTK_VISIBLE_ANCHOR_RESTART_MIN_MS = 900L
        private const val NTK_VISIBLE_INITIAL_CONTINUOUS_RESTART_MIN_MS = 1800L
        private const val NTK_VISIBLE_ACTIVE_RESTART_MIN_MS = 1400L
        private const val NTK_WEBTOON_ANCHOR_PRIME_AFTER_DRAW_DELAY_MS = 0L
        private const val NTK_PRE_ANCHOR_FALLBACK_RETRY_MS = 60L
        private const val NTK_PRE_ANCHOR_FALLBACK_RETRY_MAX_MS = 600L
        private const val NTK_PRE_ANCHOR_FALLBACK_MAX_AHEAD = 8
        private const val NTK_PRE_ANCHOR_VERIFIED_GENERATED_AHEAD = 0
        private const val NTK_EARLY_URL_HANDOFF_WAIT_MS = 4200L
        private const val NTK_EARLY_URL_LATE_HANDOFF_WAIT_MS = 30000L
        private const val NTK_EARLY_URL_POLL_MS = 16L
        private const val NTK_EARLY_URL_EXPANSION_WAIT_MS = 1200L
        private const val NTK_PRELOADED_EARLY_URL_ACCEPT_MS = 5000L
        private const val NTK_IMMEDIATE_GENERATED_INITIAL_FOREGROUND_PAGES = 18
        private const val NTK_WEBTOON_IMMEDIATE_GENERATED_INITIAL_FOREGROUND_PAGES = 4
        private const val NTK_IMMEDIATE_GENERATED_INITIAL_BOOT_STREAM_PAGES = 8
        private const val NTK_IMMEDIATE_GENERATED_INITIAL_FOLLOWER_STAGGER_MS = 90L
        private const val NTK_IMMEDIATE_GENERATED_INITIAL_EXTENSION_HEDGE_PAGES = 4
        private const val NTK_IMMEDIATE_GENERATED_INITIAL_EXTENSION_HEDGE_DELAY_MS = 650L
        private const val NTK_IMMEDIATE_GENERATED_INITIAL_TAIL_STAGGER_MS = 40L
        private const val NTK_GENERATED_INITIAL_PROBE_TIMEOUT_MS = 750
        private const val NTK_GENERATED_INITIAL_PROBE_TOTAL_WAIT_MS = 950L
        private const val NTK_ANCHOR_CACHED_DECODE_RETRY_MS = 5000L
        private const val NTK_ANCHOR_ASSET_DECODE_PAGE_BIND_RETRIES = 3
        private const val NTK_NAVER_ORIGINAL_FIRST_FILE_WAIT_MS = 2600L
        private const val NTK_NAVER_ORIGINAL_ADJACENT_DECODE_RECHECK_MS = 96L
        private const val NTK_NAVER_ORIGINAL_ADJACENT_DECODE_MAX_WAIT_MS = 1600L
        private const val NTK_EARLY_GENERATED_EXPAND_AFTER_FIRST_BITMAP_WAIT_MS = 5000L
        private const val NTK_EARLY_GENERATED_EXPAND_BEFORE_FIRST_BITMAP_WAIT_MS = 18000L
        private const val NTK_BOARD_ONLY_GENERATED_GRACE_MS = 1400L
        private const val NTK_CANONICAL_WEBTOON_API_FIRST_MIN_WORK_ID = 800000L
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
        private const val NTK_INITIAL_CONTINUOUS_REQUIRED_PAGES = 8
        private const val NTK_INITIAL_CONTINUOUS_BUSY_PAGES = 8
        private const val NTK_INITIAL_INTERACTIVE_VIEWPORT_AHEAD_PAGES = 2
        private const val NTK_SYNTHETIC_INITIAL_VISIBLE_PAGES = 2
        private const val NTK_WEBTOON_INITIAL_VISIBLE_RECOVERY_PAGES = 4
        private const val NTK_INITIAL_CONTINUOUS_PREVIOUS_PAGES = 2
        private const val NTK_INITIAL_DIRECT_DELIVERY_PAGES = 18
        private const val NTK_INITIAL_ANCHOR_COALESCE_MS = 80L
        private const val NTK_INITIAL_ANCHOR_FAST_COALESCE_MS = 520L
        private const val NTK_INITIAL_SHORT_ANCHOR_VIEWPORT_COALESCE_MS = 1600L
        private const val NTK_INITIAL_ANCHOR_FAST_COALESCE_MAX_DECODE_MS = 700L
        private const val NTK_INITIAL_CONTINUOUS_DIRECT_WINDOW_MS = 5200L
        private const val NTK_INITIAL_CONTINUOUS_STAGGER_MS = 0L
        private const val NTK_INITIAL_CONTINUOUS_DELIVERY_STAGGER_MS = 0L
        private const val NTK_INITIAL_CONTINUOUS_PRE_ANCHOR_DECODE_RETRY_MS = 48L
        private const val NTK_POST_INITIAL_CONTINUOUS_RETRY_MS = 180L
        private const val NTK_GENERATED_INITIAL_CONTINUOUS_RUNWAY_PAGES = 18
        private const val NTK_FIRST_BITMAP_FOLLOWUP_RECHECK_MS = 80L
        private const val NTK_FIRST_BITMAP_FOLLOWUP_QUIET_POLL_MS = 250L
        private const val NTK_FIRST_BITMAP_FOLLOWUP_MAX_DEFER_MS = 1800L
        private const val NTK_FIRST_BITMAP_WEBTOON_FOLLOWUP_READY_AHEAD = 3
        private const val NTK_FIRST_BITMAP_MANHWA_FOLLOWUP_READY_AHEAD = 3
        private const val NTK_APPEND_EARLY_GENERATED_WAIT_MS = 2600L
        private const val NTK_APPEND_EARLY_API_STRICT_HANDOFF_WAIT_MS = 13500L
        private const val NTK_APPEND_EARLY_API_STRICT_LATE_WAIT_MS = 5200L
        private const val NTK_APPEND_API_STRICT_ACK_RETRY_WAIT_MS = 9000L
        private const val NTK_APPEND_EARLY_GENERATED_POLL_MS = 40L
        private const val NTK_APPEND_EARLY_PUBLISH_PAGES = 12
        private const val NTK_APPEND_INITIAL_PUBLISH_TOUCH_QUIET_MS = 4200L
        private const val NTK_APPEND_WARM_BOUNDARY_PAGES = 4
        private const val NTK_APPEND_FULL_WARM_RETRY_MAX = 5
        private const val NTK_APPEND_FULL_WARM_RETRY_MS = 1200L
        private const val NTK_APPEND_CURRENT_TAIL_BOUNDARY_PAGES = 3
        private const val NTK_ADJACENT_CURRENT_INSTALL_RECHECK_MS = 250L
        private const val NTK_PREPEND_NOTIFY_BOUNDARY_RECHECK_MS = 250L
        private val NTK_VIEWER_EPISODE_PATH = Regex("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$")
        private val NTK_GENERATED_IMAGE_EXTENSION = Regex("(?i)\\.([a-z0-9]+)(?:[?#].*)?$")
        private const val NTK_INITIAL_BOOT_PRIORITY_PAGES = 8
        private const val NTK_INITIAL_BOOT_URGENT_PAGES = 4
        private const val NTK_INITIAL_BOOT_BACKGROUND_PAGES = 96
        private const val NTK_WEBTOON_INITIAL_BOOT_PRIORITY_PAGES = 4
        private const val NTK_WEBTOON_INITIAL_BOOT_URGENT_PAGES = 4
        private const val NTK_WEBTOON_INITIAL_BOOT_BACKGROUND_PAGES = 160
        private const val NTK_INITIAL_BYTE_PREFETCH_AHEAD_PAGES = 96
        private const val NTK_WEBTOON_INITIAL_BYTE_PREFETCH_AHEAD_PAGES = 48
        private const val NTK_FULL_EPISODE_WARM_RETRY_MAX = 8
        private const val NTK_FULL_EPISODE_WARM_RETRY_MS = 1800L
        private const val NTK_FULL_EPISODE_STALE_LOADING_MS = 10_000L
        private const val NTK_FULL_EPISODE_TEST_STALE_LOADING_MS = 2_500L
        private const val NTK_FULL_EPISODE_TEST_ACTIVE_REQUEST_LIMIT = 4
        private const val NTK_FULL_EPISODE_TEST_VISIBLE_RADIUS = 2
        private const val NTK_FULL_EPISODE_SOURCE_PREFETCH_PARALLELISM = 16
        private const val NTK_FULL_EPISODE_WARM_MAIN_BATCH_PAGES = 18
        private const val NTK_FULL_EPISODE_WARM_MAIN_BATCH_DELAY_MS = 250L
        private const val NTK_BYTE_PREFETCH_REPEAT_SUPPRESS_MS = 12_000L
        private const val NTK_GENERATED_DIRECT_DRAW_MIN_WIDTH = 300
        private const val NTK_GENERATED_PUBLISH_FULL_WARM_PAGE_LIMIT = 128
        private const val NTK_GENERATED_INITIAL_LIMITED_WARM_PAGES = 3
        private const val NTK_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES = 2
        private const val NTK_GENERATED_INITIAL_LIMITED_BUSY_PAGES = 1
        private const val NTK_WEBTOON_GENERATED_INITIAL_LIMITED_WARM_PAGES = 18
        private const val NTK_WEBTOON_GENERATED_INITIAL_LIMITED_FOREGROUND_PAGES = 18
        private const val NTK_WEBTOON_GENERATED_INITIAL_LIMITED_BUSY_PAGES = 18
        private const val NTK_MANHWA_GENERATED_INITIAL_VISIBLE_AHEAD_PAGES = 2
        private const val NTK_DEFAULT_GENERATED_INITIAL_DIRECT_AHEAD_PAGES = 3
        private const val NTK_MANHWA_GENERATED_INITIAL_DIRECT_AHEAD_PAGES = 3
        private const val NTK_INITIAL_ANCHOR_DECODE_PRIME_PAGES = 2
        private const val NTK_INITIAL_ANCHOR_DECODE_WEBTOON_PRIME_PAGES = 18
        private const val NTK_INITIAL_PRIORITY_PAGES = 4
        private const val NTK_INITIAL_GENERATED_PROMOTE_MAX_AHEAD = 4
        private const val NTK_FOREGROUND_STREAM_AHEAD_PAGES = 6
        private const val NTK_INITIAL_NEAR_DECODE_AHEAD_PAGES = 8
        private const val NTK_INITIAL_DECODE_AHEAD_PAGES = 18
        private const val NTK_WEBTOON_INITIAL_NEAR_DECODE_AHEAD_PAGES = 12
        private const val NTK_WEBTOON_INITIAL_DECODE_AHEAD_PAGES = 18
        private const val NTK_INITIAL_RAPID_SCROLL_DECODE_AHEAD_PAGES = 18
        private const val NTK_INITIAL_RAPID_SCROLL_URGENT_PAGES = 10
        private const val NTK_GENERATED_IMMEDIATE_RUNWAY_PAGES = 18
        private const val NTK_WEBTOON_FOREGROUND_STREAM_AHEAD_PAGES = 8
        private const val NTK_WEBTOON_ACTIVE_SCROLL_FOREGROUND_RADIUS = 4
        private const val NTK_WEBTOON_WINDOW_AFTER = 6
        private const val NTK_WEBTOON_BUSY_WINDOW_AFTER = 8
        private const val NTK_WEBTOON_BUSY_DIRECTIONAL_DECODE_AHEAD = 5
        private const val NTK_WEBTOON_BUSY_VISIBLE_DECODE_RADIUS = 3
        private const val NTK_WEBTOON_IDLE_VISIBLE_DECODE_RADIUS = 2
        private const val NTK_WEBTOON_IDLE_DECODE_AHEAD = 2
        private const val NTK_INITIAL_SECONDARY_WARM_DELAY_MS = 120L
        private const val NTK_INITIAL_FAR_WARM_DELAY_MS = 40L
        private const val NTK_INITIAL_FAR_WARM_BATCH_PAGES = 10
        private const val NTK_INITIAL_FAR_WARM_BATCH_DELAY_MS = 40L
        private const val NTK_INITIAL_ACK_INFLIGHT_WARM_PAGES = 18
        private const val NTK_INITIAL_SOURCE_PREFETCH_AFTER_FIRST_BITMAP_DELAY_MS = 250L
        private const val NTK_INITIAL_FULL_FETCH_AFTER_EARLY_DEFER_MS = 4200L
        private const val NTK_INITIAL_FULL_FETCH_AFTER_FIRST_BITMAP_QUIET_MS = 9000L
        private const val NTK_INITIAL_FULL_FETCH_TOUCH_QUIET_MS = 1200L
        private const val NTK_INITIAL_FULL_FETCH_QUIET_POLL_MS = 250L
        private const val NTK_INITIAL_FULL_APPEND_AFTER_FIRST_BITMAP_WAIT_MS = 3500L
        private const val NTK_INITIAL_FULL_APPEND_PUBLISH_AFTER_FIRST_BITMAP_WAIT_MS = 1800L
        private const val NTK_UNAVAILABLE_EARLY_URL_GRACE_MS = 1800L
        private const val NTK_UNAVAILABLE_EARLY_URL_POLL_MS = 40L
        private const val NTK_INITIAL_ADJACENT_DECODE_AFTER_FIRST_QUIET_MS = 4200L
        private const val NTK_INITIAL_ADJACENT_DECODE_TOUCH_QUIET_MS = 900L
        private const val NTK_INITIAL_ADJACENT_DECODE_RETRY_MAX_MS = 4500L
        private const val NTK_GENERATED_FULL_BYTE_PREFETCH_AFTER_FIRST_BITMAP_DELAY_MS = 1200L
        private const val NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_PAGES = 8
        private const val NTK_GENERATED_FULL_BYTE_PREFETCH_BATCH_DELAY_MS = 160L
        private const val NTK_GENERATED_FULL_APPEND_RECHECK_MS = 180L
        private const val NTK_ADJACENT_FOREGROUND_STREAM_RECHECK_MS = 220L
        private const val NTK_EPISODE_METADATA_AFTER_FIRST_BITMAP_DELAY_MS = 300L
        private const val NTK_INITIAL_DELIVERY_HOLD_FALLBACK_MS = 2600L
        private const val NTK_GENERATED_APPEND_NOTIFY_NEAR_READY_POLL_MS = 32L
        private const val NTK_GENERATED_TRANSIENT_RETRY_ATTEMPTS = 3
        private const val NTK_GENERATED_TRANSIENT_RETRY_DELAY_MS = 650L
        private const val NTK_GENERATED_PARTIAL_RETRY_DELAY_MS = 120L
        private const val NTK_GENERATED_ACTIVE_FETCH_RETRY_DELAY_MS = 900L
        private const val NTK_GENERATED_INITIAL_RECOVERY_PAGES = 18
        private const val NTK_WEBTOON_GENERATED_INITIAL_RECOVERY_PAGES = 18
        private const val NTK_GENERATED_INITIAL_RECOVERY_WINDOW_PAGES = 12
        private const val NTK_INITIAL_JPG_HEDGE_DELAY_MS = 1200L
        private const val NTK_INITIAL_JPG_HEDGE_RECHECK_MS = 80L
        private const val NTK_INITIAL_JPG_HEDGE_MAX_WAIT_MS = 0L
        private const val NTK_OBSERVED_MANHWA_APPEND_AFTER_FIRST_BITMAP_WAIT_MS = 4500L
        private const val NTK_OBSERVED_MANHWA_APPEND_READY_PAGES = 12
        private const val NTK_TRACE_AHEAD_PAGES = 8
        private const val NTK_BACKGROUND_PREPARE_QUIET_MS = 120L
        private const val NTK_BACKGROUND_PREPARE_AFTER_FIRST_BITMAP_QUIET_MS = 3500L
        private const val NTK_INITIAL_INTERACTIVE_SETTLE_AFTER_FIRST_BITMAP_MS = 9000L
        private const val NTK_BACKGROUND_WARM_TOUCH_QUIET_MS = 4200L
        private const val NTK_BACKGROUND_WARM_RECHECK_MS = 120L
        private const val NTK_GENERATED_FULL_BULK_HOLD_RECHECK_MS = 1000L
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
        private const val NTK_GENERATED_BUSY_SOURCE_PREFETCH_STEP = 6
        private const val NTK_GENERATED_BUSY_SOURCE_PREFETCH_AFTER = 32
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
        private const val NTK_GENERATED_DRAW_TILE_HEIGHT = 512
        private const val NTK_GENERATED_DRAW_TILE_MIN_BYTES = 768L * 1024L
        private const val NTK_GENERATED_DRAW_TILE_MIN_VIEWPORT_PERMILLE = 450
        private const val NTK_GENERATED_INITIAL_UNTILED_DRAW_PAGES = 0
        private const val SPREAD_ASPECT_RATIO = 0.90f
        private const val PAGE_SIDE_FIRST = 0
        private const val PAGE_SIDE_SECOND = 1
        private val NTK_GENERATED_PAGE_URL = Regex("/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$")
        private val NTK_EPISODE_PATH = Regex("^/(manhwa|webtoon)/(\\d+)/([^/?#]+)$", RegexOption.IGNORE_CASE)
        private val NTK_GENERATED_IMAGE_URL = Regex(
            "^(https?://[^/]+/(?:black(?:toon)?/episodes/\\d+/[^/?#]+|(?:manhwa|webtoon)/\\d+/[^/?#]+|wt/episodes/[^/?#]+/[^/?#]+)/)p\\d{3}\\.(jpg|jpeg|png|webp)([?#].*)?$",
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
