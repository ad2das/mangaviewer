package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.bumptech.glide.Glide
import ml.melun.mangaview.Utils
import ml.melun.mangaview.glide.ViewerBitmapTrim
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.model.PageItem
import ml.melun.mangaview.repository.MangaRepository
import ml.melun.mangaview.runtime.MainThreadStallMonitor
import java.io.File
import java.io.InterruptedIOException
import java.util.LinkedHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
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
        val image: String?,
        val transitionTitle: String? = null,
        val sourceIndex: Int = 0,
        var pageIndex: Int = -1,
        val localPage: Int = 0,
        val totalPages: Int = 0,
        val side: Int = PAGE_SIDE_FIRST,
        val allowAutoSplit: Boolean = true
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

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
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
    private val pages = ArrayList<PageRef>()
    private val pagesLock = Object()
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val urgentLoading = ConcurrentHashMap.newKeySet<Int>()
    private val bytePrefetching = ConcurrentHashMap.newKeySet<Int>()
    private val failedPages = ConcurrentHashMap.newKeySet<Int>()
    private val decodedWidths = ConcurrentHashMap<Int, Int>()
    private val desiredWidths = ConcurrentHashMap<Int, Int>()
    private val inFlightWidths = ConcurrentHashMap<Int, Int>()
    private val pendingDeliveryWidths = ConcurrentHashMap<Int, Int>()
    private val sourceWidths = ConcurrentHashMap<Int, Int>()
    private val achievableWidths = ConcurrentHashMap<Int, Int>()
    private val deliveryQueue = ConcurrentLinkedQueue<Delivery>()
    private val primedDeliveryBacklog = ConcurrentHashMap<Int, Delivery>()
    private val deliveryDrainPosted = AtomicBoolean(false)
    private val viewportBusy = AtomicBoolean(false)
    private val deliveryResumeAtMs = AtomicLong(0L)
    private val lastUserInteractionMs = AtomicLong(0L)
    private val earlyPreparedBitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val deliveredBitmaps = LinkedHashMap<Int, Bitmap>(32, 0.75f, true)
    private val deliveredTiles = LinkedHashMap<Int, List<ReaderTile>>(16, 0.75f, true)
    private val deliveredOwned = HashSet<Int>()
    private var retainedFirstPage = 0
    private var retainedLastPage = 0
    private var retainedAnchorPage = 0
    private val firstBitmapLogged = AtomicBoolean(false)
    private val initialFanoutStarted = AtomicBoolean(false)
    private val pendingInitialFanoutPage = AtomicInteger(-1)
    private val windowGeneration = AtomicInteger(0)
    private val nextLoading = AtomicBoolean(false)
    private val previousAppendLoading = AtomicBoolean(false)
    private val nextAppendLoading = AtomicBoolean(false)
    private val timelinePrimeLoading = AtomicBoolean(false)
    private val timelinePrimeRequested = AtomicBoolean(false)
    private val repositoryLoading = AtomicBoolean(false)
    private val windowLock = Object()
    private var lastWindowAnchor = -1
    private var lastWindowDirection = 0
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
        network.execute {
            try {
                attachTitle()
                var urls = imageRepository.imageUrls(manga, appContext)
                if (urls.isNullOrEmpty()) {
                    val cancellation = MangaRepository.cancellation().userVisible()
                    if (isNtkSource(manga, title)) cancellation.prioritizeWebViewFallback()
                    val result = imageRepository.fetchViewerInitial(manga, cancellation)
                    if (result != Title.LOAD_OK) {
                        if (result == Title.LOAD_CAPTCHA) {
                            postCaptchaRequired(manga)
                            return@execute
                        }
                        postMessage("이미지를 불러오지 못했습니다")
                        return@execute
                    }
                    urls = imageRepository.imageUrls(manga, appContext)
                }
                if (urls.isNullOrEmpty()) {
                    postMessage("표시할 이미지가 없습니다")
                    return@execute
                }
                val startPage = requestedStartPage().coerceIn(0, urls.lastIndex)
                installImages(urls, startPage, false)
                flushEarlyPreparedBitmaps()
                requestPageForeground(startPage)
                requestInitialFanout(startPage)
            } catch (e: Exception) {
                recordIfUnexpected(e)
                if (!isExpectedCancellation(e)) postMessage("이미지를 불러오지 못했습니다")
            } finally {
                repositoryLoading.set(false)
            }
        }
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

    private fun installImages(
        urls: List<String>,
        requestedStartPage: Int,
        requestInitialWindow: Boolean,
        notifyInitialPage: Boolean = true
    ): Int {
        if (cancelled.get() || urls.isEmpty()) return -1
        if (!pagesInstalled.compareAndSet(false, true)) return -1
        val refs = pageRefsForImages(manga, urls)
        synchronized(pagesLock) {
            pages.clear()
            refs.forEachIndexed { index, page ->
                page.pageIndex = index
            }
            pages.addAll(refs)
        }
        val startPage = displayStartPage(requestedStartPage, requestedStartSide(), refs)
        main.post {
            if (!cancelled.get()) {
                listener.onPagesReady(refs.size)
                if (notifyInitialPage) listener.onInitialPage(startPage)
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
        requestWindow(
            max(0, startPage - ReaderPipelinePolicy.INITIAL_WINDOW_BEFORE),
            minOf(count - 1, startPage + ReaderPipelinePolicy.INITIAL_WINDOW_AFTER),
            startPage,
            busy
        )
    }

    private fun requestInitialFanout(startPage: Int) {
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
        }
        prefetchImageFilesAround(startPage)
    }

    private fun warmNtkInitialPages(startPage: Int) {
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val first = startPage.coerceIn(0, count - 1)
        val nearLast = minOf(count - 1, first + NTK_INITIAL_NEAR_DECODE_AHEAD_PAGES)
        if (first >= nearLast) return
        for (index in (first + 1)..nearLast) {
            requestPage(index, busy = true, anchor = false, generation = PRIME_WARM_GENERATION)
        }
        val farLast = minOf(count - 1, first + NTK_INITIAL_DECODE_AHEAD_PAGES)
        if (nearLast >= farLast) return
        main.postDelayed({
            if (cancelled.get()) return@postDelayed
            for (index in (nearLast + 1)..farLast) {
                requestPage(index, busy = true, anchor = false, generation = PRIME_WARM_GENERATION)
            }
        }, NTK_INITIAL_FAR_WARM_DELAY_MS)
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

    private fun prefetchImageFilesAround(startPage: Int) {
        val refs = synchronized(pagesLock) { pages.toList() }
        if (refs.isEmpty()) return
        val first = startPage
        val last = minOf(refs.lastIndex, startPage + START_SOURCE_PREFETCH_AFTER)
        val ordered = ArrayList<Int>(last - first + 1)
        val anchor = startPage.coerceIn(first, last)
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
            try {
                network.execute { prefetchImageFileQuietly(index, page) }
            } catch (_: RejectedExecutionException) {
                return
            }
        }
    }

    private fun resolvePreparedStartPage(preparedStartPage: Int): Int {
        val requested = requestedStartPage()
        return if (!startAtFirstPage && requested > 0 && preparedStartPage <= 0) requested else preparedStartPage
    }

    private fun requestPageForeground(index: Int) {
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
        if (cancelled.get() || index < 0 || bitmap.isRecycled) return
        decodedWidths[index] = max(decodedWidths[index] ?: 0, bitmap.width)
        loading.remove(index)
        trackDeliveredBitmap(index, bitmap, owned)
        markFirstPreparedBitmapDelivered()
        main.post {
            if (!cancelled.get()) {
                listener.onPageReady(index, bitmap)
                main.post { releaseInitialFanoutIfAnchorReady(index) }
            }
        }
    }

    private fun markFirstPreparedBitmapDelivered() {
        if (firstBitmapLogged.compareAndSet(false, true)) {
            ViewerWarmupManager.logMetric("reader_first_bitmap_prepared", 1L)
            releasePreparedStoreBitmapsSoon()
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
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val requestList: List<Int>
        val generation: Int
        val safeFirst: Int
        val safeLast: Int
        val direction: Int
        synchronized(windowLock) {
            safeFirst = first.coerceIn(0, count - 1)
            safeLast = last.coerceIn(safeFirst, count - 1)
            generation = windowGeneration.incrementAndGet()
            direction = when {
                lastWindowAnchor < 0 -> lastWindowDirection
                anchor > lastWindowAnchor -> 1
                anchor < lastWindowAnchor -> -1
                else -> lastWindowDirection
            }
            lastWindowAnchor = anchor
            if (direction != 0) lastWindowDirection = direction
            requestList = windowOrder(safeFirst, safeLast, anchor, direction)
        }
        if (retainWindow) {
            synchronized(deliveredBitmaps) {
                retainedFirstPage = safeFirst
                retainedLastPage = safeLast
                retainedAnchorPage = anchor.coerceIn(safeFirst, safeLast)
            }
            if (primedDeliveryBacklog.isNotEmpty()) scheduleDeliveryDrain()
        }
        if (busy) {
            val visibleFirst = max(safeFirst, anchor - BUSY_VISIBLE_DECODE_RADIUS)
            val visibleLast = minOf(safeLast, anchor + BUSY_VISIBLE_DECODE_RADIUS)
            val visible = windowOrder(visibleFirst, visibleLast, anchor, direction)
            for (i in visible) requestPage(i, true, i == anchor, generation)
            for (i in requestList) {
                if (!visible.contains(i)) pageRef(i)?.let { prefetchBusyPage(i, it, generation) }
            }
            trimDecodedWidth(anchor, true)
            return
        }
        for (i in requestList) requestPage(i, busy, i == anchor, generation)
        trimDecodedWidth(anchor, busy)
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
        main.removeCallbacks(clearPreparedBitmapsRunnable)
        main.removeCallbacks(deliveryDrainRunnable)
        recycleQueuedDeliveries()
        preparedEntry?.removeListener(preparedListener)
        releaseDeliveredBitmaps()
        network.shutdownNow()
        decode.shutdownNow()
        anchorNetwork.shutdownNow()
        anchorDecode.shutdownNow()
        primeNetwork.shutdownNow()
        primeDecode.shutdownNow()
        cleanup.shutdown()
        control.shutdownNow()
    }

    fun prepareNextEpisode(anchor: Int) {
        if (isNtkSource(manga, title) && !firstBitmapLogged.get()) return
        if (cancelled.get() || nextLoading.getAndSet(true)) return
        network.execute {
            try {
                val anchorManga = pageRef(anchor)?.manga ?: manga
                val currentTitle = title ?: anchorManga.title ?: manga.title
                if (currentTitle == null) return@execute
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    imageRepository.fetchEpisodesForeground(currentTitle, MangaRepository.cancellation())
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
                    val result = imageRepository.fetchViewerInitial(next, MangaRepository.cancellation())
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
        appendAdjacentEpisode(anchor, direction, silentMissing = true)
    }

    fun appendAdjacentEpisode(anchor: Int, direction: Int, silentMissing: Boolean = false): AppendStartResult {
        val loadingFlag = if (direction < 0) previousAppendLoading else nextAppendLoading
        if (cancelled.get()) return AppendStartResult.CANCELLED
        if (isNtkSource(manga, title) && !firstBitmapLogged.get()) return AppendStartResult.CANCELLED
        if (loadingFlag.getAndSet(true)) return AppendStartResult.BUSY
        try {
            network.execute {
            var captchaRequired = false
            var suppressedCaptcha = false
            try {
                val anchorManga = pageRef(anchor)?.manga ?: manga
                val currentTitle = title ?: anchorManga.title ?: manga.title ?: return@execute
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    val result = imageRepository.fetchEpisodesForeground(currentTitle, MangaRepository.cancellation())
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
                val episodes = Utils.snapshotEpisodes(currentTitle)
                if (episodes.isNotEmpty()) {
                    manga.setEps(episodes)
                    anchorManga.setEps(episodes)
                }
                anchorManga.title = currentTitle
                anchorManga.titleId = currentTitle.id
                val target = nextUnloadedAdjacentEpisode(anchorManga, currentTitle, episodes, direction)
                if (target == null) {
                    if (!silentMissing) {
                        postMessage(if (direction < 0) "이전 회차가 없습니다" else "다음 회차가 없습니다")
                    }
                    return@execute
                }
                target.title = currentTitle
                target.titleId = currentTitle.id
                target.mode = anchorManga.mode
                if (episodes.isNotEmpty()) target.setEps(episodes)
                var urls = imageRepository.imageUrls(target, appContext)
                if (!urls.isNullOrEmpty() &&
                    isNtkSource(target, currentTitle) &&
                    shouldRefreshNtkGeneratedAppendUrls(urls)
                ) {
                    target.setImgs(null)
                    val result = imageRepository.fetchViewerInitial(target, MangaRepository.cancellation().userVisible())
                    if (result != Title.LOAD_OK) {
                        if (result == Title.LOAD_CAPTCHA) {
                            if (silentMissing) {
                                suppressedCaptcha = true
                                return@execute
                            }
                            captchaRequired = true
                            postCaptchaRequired(target)
                            return@execute
                        }
                        postMessage(if (result == Title.LOAD_CAPTCHA) "캡차 확인이 필요합니다" else "회차를 불러오지 못했습니다")
                        return@execute
                    }
                    urls = imageRepository.imageUrls(target, appContext)
                }
                if (urls.isNullOrEmpty()) {
                    val result = imageRepository.fetchViewerInitial(target, MangaRepository.cancellation().userVisible())
                    if (result != Title.LOAD_OK) {
                        if (result == Title.LOAD_CAPTCHA) {
                            if (silentMissing) {
                                suppressedCaptcha = true
                                return@execute
                            }
                            captchaRequired = true
                            postCaptchaRequired(target)
                            return@execute
                        }
                        postMessage(if (result == Title.LOAD_CAPTCHA) "캡차 확인이 필요합니다" else "회차를 불러오지 못했습니다")
                        return@execute
                    }
                    urls = imageRepository.imageUrls(target, appContext)
                }
                if (urls.isNullOrEmpty()) {
                    postMessage("표시할 이미지가 없습니다")
                    return@execute
                }
                appendResolvedEpisode(target, urls, direction)
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

    private fun primeForwardTimeline() {
        if (cancelled.get() || !timelinePrimeLoading.compareAndSet(false, true)) return
        network.execute {
            try {
                var current = manga
                val currentTitle = title ?: current.title ?: manga.title ?: return@execute
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    val result = imageRepository.fetchEpisodesForeground(currentTitle, MangaRepository.cancellation())
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
                            val result = imageRepository.fetchViewerInitial(target, MangaRepository.cancellation())
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
    }

    private fun pageRefsForEpisode(target: Manga, urls: List<String>, direction: Int): List<PageRef> {
        val episodeName = target.name ?: title?.name ?: "회차"
        val transitionTitle = if (direction < 0) "이전 회차: $episodeName" else "다음 회차: $episodeName"
        val pageRefs = pageRefsForImages(target, urls)
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
        main.post {
            if (!cancelled.get()) {
                listener.onPagesAppended(total)
                for ((index, title) in appendedCards) {
                    listener.onPageCard(index, title)
                }
            }
        }
        warmPrimedForwardRefs(appendedCards.map { it.first }, total, lightWarm)
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
        if (direction < 0) {
            synchronized(pagesLock) {
                if (containsEpisodeLocked(target)) return
                for (page in pages) page.pageIndex += inserted
                refs.forEachIndexed { index, page -> page.pageIndex = index }
                pages.addAll(0, refs)
                total = pages.size
                shiftPageStateForPrepend(inserted)
            }
            main.post {
                if (!cancelled.get()) {
                    listener.onPagesPrepended(total, inserted)
                    if (cardOffset >= 0) listener.onPageCard(cardOffset, transitionTitle)
                }
            }
            if (warm) warmPrependedEpisode(inserted)
        } else {
            val cardIndex: Int
            synchronized(pagesLock) {
                if (containsEpisodeLocked(target)) return
                cardIndex = pages.size
                refs.forEachIndexed { offset, page -> page.pageIndex = cardIndex + offset }
                pages.addAll(refs)
                total = pages.size
            }
            main.post {
                if (!cancelled.get()) {
                    listener.onPagesAppended(total)
                    if (cardOffset >= 0) listener.onPageCard(cardIndex + cardOffset, transitionTitle)
                }
            }
            if (warm) warmAppendedEpisode(cardIndex, total)
        }
    }

    private fun warmAppendedEpisode(cardIndex: Int, total: Int) {
        if (cancelled.get() || cardIndex < 0 || total <= cardIndex) return
        val generation = windowGeneration.get()
        val busy = viewportBusy.get()
        val decodeAhead = if (busy) BOUNDARY_BUSY_DECODE_AHEAD_PAGES else BOUNDARY_DECODE_AHEAD_PAGES
        val byteAhead = if (busy) BOUNDARY_BUSY_BYTE_AHEAD_PAGES else BOUNDARY_BYTE_AHEAD_PAGES
        requestPage(cardIndex, busy = busy, anchor = false, generation = generation)
        val last = minOf(total - 1, cardIndex + decodeAhead)
        for (index in (cardIndex + 1)..last) {
            requestPage(index, busy = busy, anchor = false, generation = generation)
        }
        val byteLast = minOf(total - 1, cardIndex + byteAhead)
        for (index in (last + 1)..byteLast) {
            val page = pageRef(index) ?: continue
            network.execute { prefetchImageFileQuietly(index, page) }
        }
    }

    private fun warmPrependedEpisode(inserted: Int) {
        if (cancelled.get() || inserted <= 0) return
        val generation = windowGeneration.get()
        val busy = viewportBusy.get()
        val decodeAhead = if (busy) BOUNDARY_BUSY_DECODE_AHEAD_PAGES else BOUNDARY_DECODE_AHEAD_PAGES
        val byteAhead = if (busy) BOUNDARY_BUSY_BYTE_AHEAD_PAGES else BOUNDARY_BYTE_AHEAD_PAGES
        val firstDecoded = max(1, inserted - decodeAhead)
        for (index in (inserted - 1) downTo firstDecoded) {
            requestPage(index, busy = busy, anchor = false, generation = generation)
        }
        val firstByte = max(1, inserted - byteAhead)
        for (index in (firstDecoded - 1) downTo firstByte) {
            val page = pageRef(index) ?: continue
            network.execute { prefetchImageFileQuietly(index, page) }
        }
    }

    private fun hasEpisode(target: Manga): Boolean = synchronized(pagesLock) {
        containsEpisodeLocked(target)
    }

    private fun nextUnloadedAdjacentEpisode(
        source: Manga,
        currentTitle: Title,
        episodes: List<Manga>,
        direction: Int
    ): Manga? {
        var candidate = if (direction < 0) source.prevEp() else source.nextEp()
        var checked = 0
        while (candidate != null && checked < ADJACENT_EXISTING_SKIP_LIMIT) {
            candidate.title = currentTitle
            candidate.titleId = currentTitle.id
            candidate.mode = source.mode
            if (episodes.isNotEmpty()) candidate.setEps(episodes)
            if (!hasEpisode(candidate)) return candidate
            candidate = if (direction < 0) candidate.prevEp() else candidate.nextEp()
            checked++
        }
        return null
    }

    private fun containsEpisodeLocked(target: Manga): Boolean {
        return pages.any { sameEpisode(it.manga, target) }
    }

    private fun appendableNewEpisodeRefsLocked(refs: List<PageRef>): List<PageRef> {
        if (refs.isEmpty()) return emptyList()
        val accepted = ArrayList<PageRef>(refs.size)
        val acceptedMangas = ArrayList<Manga>()
        for (ref in refs) {
            val existing = containsEpisodeLocked(ref.manga)
            val alreadyAccepted = acceptedMangas.any { sameEpisode(it, ref.manga) }
            if (!existing && !alreadyAccepted) acceptedMangas.add(ref.manga)
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
        inFlightWidths.clear()
        loading.clear()
        urgentLoading.clear()
        bytePrefetching.clear()
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

    private fun requestPage(index: Int, busy: Boolean, anchor: Boolean, generation: Int = windowGeneration.get()) {
        if (cancelled.get()) return
        val page = pageRef(index) ?: return
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
        val targetWidth = targetWidth(busy)
        rememberDesiredWidth(index, targetWidth)
        val effectiveTargetWidth = achievableWidth(index, targetWidth)
        val decodedWidth = decodedWidths[index] ?: 0
        if (decodedWidth >= effectiveTargetWidth) return
        val pendingWidth = pendingDeliveryWidths[index] ?: 0
        if (pendingWidth >= effectiveTargetWidth) return
        val activeWidth = inFlightWidths[index] ?: 0
        val ownsLoading = loading.add(index)
        val urgent = !ownsLoading &&
            busy &&
            !hasDeliveredBitmap(index) &&
            urgentLoading.add(index)
        if (!ownsLoading && !urgent) {
            if (targetWidth > activeWidth)
                ViewerWarmupManager.logMetric("busy_to_idle_upgrade_pending", targetWidth.toLong())
            return
        }
        if (ownsLoading) inFlightWidths[index] = targetWidth
        if (ownsLoading) {
            main.post { if (!cancelled.get()) listener.onPageLoading(index) }
        }
        if (urgent) ViewerWarmupManager.logMetric("reader_urgent_visible_decode", index.toLong())
        val foregroundPrime = generation == FOREGROUND_PRIME_WARM_GENERATION
        if (foregroundPrime && ownsLoading && shouldHedgeForegroundPrime(index)) {
            scheduleForegroundPrimeHedge(index)
        }
        val retainWhenBusy = generation == PRIME_WARM_GENERATION || foregroundPrime
        val networkExecutor = when {
            anchor || urgent -> anchorNetwork
            foregroundPrime -> primeNetwork
            else -> network
        }
        val decodeExecutor = when {
            anchor || urgent -> anchorDecode
            foregroundPrime -> primeDecode
            else -> decode
        }
        try {
            networkExecutor.execute {
            try {
                if (shouldSkipStalePage(index, generation, anchor)) {
                    clearPageLoadState(index, ownsLoading, urgent)
                    return@execute
                }
                val originalPage = page
                cachedDecodedResult(originalPage, targetWidth)?.let { cached ->
                    clearPageLoadState(index, ownsLoading, urgent)
                    postDecodeResult(Delivery(index, originalPage, cached, SystemClock.elapsedRealtime(), targetWidth, retainWhenBusy))
                    ViewerWarmupManager.logMetric("reader_decoded_cache_hit", index.toLong())
                    return@execute
                }
                if (!anchor && !urgent) prefetchImageFile(index, originalPage)
                try {
                    decodeExecutor.execute {
                    val gate = if (busy) busyDecodeGate else idleDecodeGate
                    var acquired = false
                    var delivered = false
                    try {
                        gate.acquire()
                        acquired = true
                        if (cancelled.get() || shouldSkipStalePage(index, generation, anchor)) return@execute
                        val startedAt = SystemClock.elapsedRealtime()
                        val foregroundFetch = shouldUseForegroundFetch(index, originalPage, anchor, urgent, busy, generation)
                        val result = decodePageWithLease(index, originalPage, targetWidth, foregroundFetch)
                        if (
                            cancelled.get() ||
                            shouldSkipStalePage(index, generation, anchor) ||
                            pageRef(index) != originalPage
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
                        if (acquired) gate.release()
                        clearPageLoadState(index, ownsLoading, urgent)
                        if (delivered) ViewerWarmupManager.logMetric("reader_delivery_posted", index.toLong())
                    }
                }
                } catch (_: RejectedExecutionException) {
                    clearPageLoadState(index, ownsLoading, urgent)
                }
            } catch (e: Exception) {
                clearPageLoadState(index, ownsLoading, urgent)
                recordIfUnexpected(e)
                postPageError(index, page, e)
            }
            }
        } catch (_: RejectedExecutionException) {
            clearPageLoadState(index, ownsLoading, urgent)
        }
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
        val first = requestedStartPage()
        val hedgeFirst = first + NTK_INITIAL_PRIORITY_PAGES + 1
        val hedgeLast = first + NTK_INITIAL_PRIORITY_PAGES + NTK_PRIMED_EPISODE_PRIORITY_PAGES
        return index in hedgeFirst..hedgeLast
    }

    private fun clearPageLoadState(index: Int, ownsLoading: Boolean, urgent: Boolean) {
        if (ownsLoading) {
            loading.remove(index)
            inFlightWidths.remove(index)
        }
        if (urgent) urgentLoading.remove(index)
    }

    private fun prefetchBusyPage(index: Int, page: PageRef, generation: Int) {
        if (!bytePrefetching.add(index)) return
        try {
            network.execute {
                try {
                    if (!shouldSkipStalePage(index, generation, false)) {
                        prefetchImageFile(index, page)
                    }
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                } finally {
                    bytePrefetching.remove(index)
                }
            }
        } catch (_: RejectedExecutionException) {
            bytePrefetching.remove(index)
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

    private fun logFirstBitmapIfNeeded(startedAt: Long) {
        if (firstBitmapLogged.compareAndSet(false, true)) {
            ViewerWarmupManager.logMetric("reader_first_bitmap_ms", SystemClock.elapsedRealtime() - startedAt)
            releasePreparedStoreBitmapsSoon()
            upgradeNtkInitialPriorityPagesAfterFirstBitmap()
            scheduleNtkForwardTimelinePrimeAfterFirstBitmap()
        }
    }

    private fun upgradeNtkInitialPriorityPagesAfterFirstBitmap() {
        if (!isNtkSource(manga, title)) return
        val first = requestedStartPage()
        val count = synchronized(pagesLock) { pages.size }
        if (count <= 0) return
        val firstPriority = minOf(count - 1, first + NTK_INITIAL_PRIORITY_START_OFFSET)
        val last = minOf(count - 1, first + NTK_INITIAL_PRIORITY_PAGES)
        if (firstPriority > last) return
        for (index in firstPriority..last) {
            requestPage(index, busy = true, anchor = false, generation = FOREGROUND_PRIME_WARM_GENERATION)
        }
    }

    private fun scheduleNtkForwardTimelinePrimeAfterFirstBitmap() {
        if (autoCut || !isNtkSource(manga, title)) return
        if (!timelinePrimeRequested.compareAndSet(false, true)) return
        main.postDelayed({
            if (!cancelled.get()) primeForwardTimeline()
        }, ntkForwardPrimeDelayMs())
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

    private fun leaseImageFile(index: Int, page: PageRef, foreground: Boolean): ReaderImageCache.FileLease {
        val image = page.image ?: throw java.io.IOException("Missing image for page $index")
        return ReaderImageCache.leaseFile(appContext, page.manga, image, foreground)
    }

    private fun prefetchImageFile(index: Int, page: PageRef, foreground: Boolean = false) {
        val image = page.image ?: return
        if (page.manga.isOnline) {
            if (foreground) {
                ReaderImageCache.getOrFetchFileForeground(appContext, page.manga, image)
            } else {
                ReaderImageCache.getOrFetchFile(appContext, page.manga, image)
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
        if (cancelled.get() || isExpectedCancellation(e) || !failedPages.add(index)) return
        main.post {
            if (!cancelled.get() && pageRef(index) == page) {
                listener.onPageError(index, "Image load failed")
            }
        }
    }

    private fun isExpectedCancellation(t: Throwable?): Boolean {
        if (t == null) return false
        if (cancelled.get()) return true
        if (t is InterruptedException || t is InterruptedIOException) return true
        if (t is ExecutionException) return isExpectedCancellation(t.cause)
        val cause = t.cause
        return cause != null && cause !== t && isExpectedCancellation(cause)
    }

    private fun decodePageWithLease(index: Int, page: PageRef, targetWidth: Int, foregroundFetch: Boolean): PageDecodeResult {
        cachedDecodedResult(page, targetWidth)?.let { return it }
        decodeForegroundStream(index, page, targetWidth, foregroundFetch)?.let { return it }
        val leaseStart = if (index == requestedStartPage() && page.manga.isOnline) SystemClock.elapsedRealtime() else 0L
        leaseImageFile(index, page, foregroundFetch || index == requestedStartPage()).use { lease ->
            if (leaseStart > 0L) ViewerWarmupManager.logMetric(
                "reader_first_fetch_wait_ms",
                SystemClock.elapsedRealtime() - leaseStart
            )
            return decodePage(index, page, lease.file, targetWidth)
        }
    }

    private fun decodeForegroundStream(index: Int, page: PageRef, targetWidth: Int, foregroundFetch: Boolean): PageDecodeResult? {
        if (!page.manga.isOnline || (!foregroundFetch && index != requestedStartPage())) return null
        val image = page.image ?: return null
        val metric = SystemClock.elapsedRealtime()
        val raw = try {
            ReaderImageCache.decodeForegroundBitmap(appContext, page.manga, image, targetWidth, autoCut, page.allowAutoSplit)
        } catch (e: Exception) {
            recordIfUnexpected(e)
            null
        } ?: return null
        val rawAt = SystemClock.elapsedRealtime()
        val decodeTargetWidth = decodeTargetWidth(raw.width, raw.height, targetWidth, page.allowAutoSplit)
        val decoded = Decoder(page.manga.seed, page.manga.id).decode(raw, decodeTargetWidth, Glide.get(appContext).bitmapPool)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        val transformedAt = SystemClock.elapsedRealtime()
        val bitmap = ViewerBitmapTrim.trimBlankVerticalEdges(
            applyAutoSplit(decoded, page.side, page.allowAutoSplit),
            true
        )
        postPageBounds(index, bitmap.width, bitmap.height)
        val result = drawableResult(bitmap)
        ViewerWarmupManager.logMetric("reader_first_stream_raw_ms", rawAt - metric)
        ViewerWarmupManager.logMetric("reader_first_stream_transform_ms", transformedAt - rawAt)
        ViewerWarmupManager.logMetric("reader_first_decode_total_ms", SystemClock.elapsedRealtime() - metric)
        return result
    }

    private fun cachedDecodedResult(page: PageRef, targetWidth: Int): PageDecodeResult? {
        val image = page.image ?: return null
        val bitmap = ViewerWarmupManager.getDecodedBitmap(
            PageItem(page.sourceIndex, image, page.manga, page.side),
            autoCut,
            reverse,
            targetWidth
        ) ?: return null
        if (bitmap.isRecycled) return null
        return PageDecodeResult.Full(ViewerBitmapTrim.trimBlankVerticalEdges(bitmap))
    }

    private fun decodePage(index: Int, page: PageRef, file: File, targetWidth: Int): PageDecodeResult {
        val metric = index == requestedStartPage() && page.manga.isOnline
        val startedAt = if (metric) SystemClock.elapsedRealtime() else 0L
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (page.manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } else {
            decodeLocal(page.image ?: "", bounds)
        }
        val boundsAt = if (metric) SystemClock.elapsedRealtime() else 0L
        if (!autoCut && shouldDecodeTiles(page, file, bounds)) {
            val displayBounds = displayBounds(bounds.outWidth, bounds.outHeight, page.side, page.allowAutoSplit)
            postPageBounds(index, displayBounds.width(), displayBounds.height())
            return decodePageTiles(file, bounds, targetWidth)
        }
        val decodeTargetWidth = decodeTargetWidth(bounds.outWidth, bounds.outHeight, targetWidth, page.allowAutoSplit)
        val sample = sampleSize(bounds.outWidth, decodeTargetWidth)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sample
        }
        val raw = if (page.manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } else {
            decodeLocal(page.image ?: "", options)
        }
            ?: throw java.io.IOException("Bitmap decode failed")
        val rawAt = if (metric) SystemClock.elapsedRealtime() else 0L
        if (!page.manga.isOnline) {
            val bitmap = ViewerBitmapTrim.trimBlankVerticalEdges(
                applyAutoSplit(raw, page.side, page.allowAutoSplit),
                true
            )
            postPageBounds(index, bitmap.width, bitmap.height)
            return drawableResult(bitmap)
        }
        val decoded = Decoder(page.manga.seed, page.manga.id).decode(raw, decodeTargetWidth, Glide.get(appContext).bitmapPool)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        val transformedAt = if (metric) SystemClock.elapsedRealtime() else 0L
        val bitmap = ViewerBitmapTrim.trimBlankVerticalEdges(
            applyAutoSplit(decoded, page.side, page.allowAutoSplit),
            true
        )
        postPageBounds(index, bitmap.width, bitmap.height)
        val result = drawableResult(bitmap)
        if (metric) {
            val finishedAt = SystemClock.elapsedRealtime()
            ViewerWarmupManager.logMetric("reader_first_bounds_ms", boundsAt - startedAt)
            ViewerWarmupManager.logMetric("reader_first_raw_decode_ms", rawAt - boundsAt)
            ViewerWarmupManager.logMetric("reader_first_transform_ms", transformedAt - rawAt)
            ViewerWarmupManager.logMetric("reader_first_decode_total_ms", finishedAt - startedAt)
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

    private fun displayConfig(bitmap: Bitmap): Bitmap.Config {
        return if (bitmap.config == Bitmap.Config.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
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
                    inPreferredConfig = Bitmap.Config.RGB_565
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

    private fun postPageBounds(index: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        sourceWidths[index] = width
        main.post {
            if (!cancelled.get()) listener.onPageBoundsReady(index, width, height)
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

    fun pageInfo(index: Int): PageInfo? {
        val page = synchronized(pagesLock) { pages.getOrNull(index) } ?: return null
        val transition = page.transitionTitle != null
        return PageInfo(
            manga = page.manga,
            title = page.transitionTitle ?: displayEpisodeTitle(page.manga),
            localPage = if (transition) 0 else max(1, page.localPage),
            totalPages = page.totalPages,
            sourcePageIndex = sourcePageIndex(page),
            side = page.side,
            layoutReady = transition || sourceWidths.containsKey(index) || decodedWidths.containsKey(index),
            transitionCard = transition
        )
    }

    private fun sourcePageIndex(page: PageRef): Int {
        if (page.transitionTitle != null) return 0
        return page.sourceIndex.coerceAtLeast(0)
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
        if (hasDeliveredAtLeast(delivery, delivery.result.width)) {
            ViewerWarmupManager.logMetric("reader_drop_duplicate_before_queue", delivery.result.width.toLong())
            recycleDecodeResult(delivery.result)
            return
        }
        prepareDecodeResultForDraw(delivery.result)
        pendingDeliveryWidths.merge(delivery.index, delivery.result.width, ::max)
        deliveryQueue.add(delivery)
        scheduleDeliveryDrain()
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
        if (anchor || index == requestedStartPage()) return true
        if (urgent) return true
        if (!urgent && (!busy || generation == PRIME_WARM_GENERATION)) return false
        val image = page.image ?: return false
        return !ReaderImageCache.hasActiveFetch(page.manga, image)
    }

    private fun hasDeliveredAtLeast(delivery: Delivery, width: Int): Boolean {
        val currentIndex = synchronized(pagesLock) {
            val knownIndex = delivery.page.pageIndex
            if (knownIndex in pages.indices && pages[knownIndex] === delivery.page) {
                knownIndex
            } else {
                pages.indexOfFirst { it === delivery.page }
            }
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
            val index = delivery.page.pageIndex
            if (index in retainedFirst..retainedLast) {
                retained.add(delivery)
            } else if (delivery.retainWhenBusy) {
                primedDeliveryBacklog[delivery.index] = delivery
            } else {
                pendingDeliveryWidths.remove(delivery.index)
                recycleDecodeResult(delivery.result)
            }
        }
        retained.sortWith(
            compareBy<Delivery> { abs(it.page.pageIndex - retainedAnchor) }
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
            val index = delivery.page.pageIndex
            if (index in retainedFirst..retainedLast && primedDeliveryBacklog.remove(key, delivery)) {
                deliveryQueue.add(delivery)
            }
        }
    }

    private fun deliverDecodeResultOnMain(delivery: Delivery, busy: Boolean) {
        if (cancelled.get()) {
            pendingDeliveryWidths.remove(delivery.index)
            recycleDecodeResult(delivery.result)
            return
        }
        val retainedFirst: Int
        val retainedLast: Int
        synchronized(deliveredBitmaps) {
            retainedFirst = retainedFirstPage
            retainedLast = retainedLastPage
        }
        val knownIndex = delivery.page.pageIndex
        if (busy && knownIndex !in retainedFirst..retainedLast) {
            pendingDeliveryWidths.remove(delivery.index)
            recycleDecodeResult(delivery.result)
            return
        }
        var droppedLowerWidth = false
        val currentIndex = synchronized(pagesLock) {
            val index = if (knownIndex in pages.indices && pages[knownIndex] === delivery.page) {
                knownIndex
            } else {
                pages.indexOfFirst { it === delivery.page }
            }
            if (index >= 0 && (!busy || index in retainedFirst..retainedLast)) {
                val deliveredWidth = decodedWidths[index] ?: 0
                if (deliveredWidth >= delivery.result.width && hasDeliveredBitmap(index)) {
                    droppedLowerWidth = true
                } else {
                    decodedWidths[index] = max(deliveredWidth, delivery.result.width)
                    val desiredWidth = if (busy) {
                        max(delivery.requestedWidth, targetWidth(false))
                    } else {
                        delivery.requestedWidth
                    }
                    desiredWidths[index] = max(desiredWidths[index] ?: 0, desiredWidth)
                    if (delivery.result is PageDecodeResult.Tiles) {
                        if (delivery.result.decodedWidth < delivery.requestedWidth) {
                            achievableWidths[index] = max(achievableWidths[index] ?: 0, delivery.result.decodedWidth)
                        }
                    }
                    trackDeliveredResult(index, delivery.result)
                }
            }
            index
        }
        if (currentIndex < 0 || (busy && currentIndex !in retainedFirst..retainedLast)) {
            pendingDeliveryWidths.remove(delivery.index)
            recycleDecodeResult(delivery.result)
            return
        }
        if (droppedLowerWidth) {
            ViewerWarmupManager.logMetric("reader_drop_stale_lower_width", delivery.result.width.toLong())
            pendingDeliveryWidths.remove(delivery.index)
            recycleDecodeResult(delivery.result)
            retryPendingWidthIfNeeded(currentIndex)
            return
        }
        pendingDeliveryWidths.remove(delivery.index)
        logFirstBitmapIfNeeded(delivery.startedAt)
        when (val result = delivery.result) {
            is PageDecodeResult.Full -> listener.onPageReady(currentIndex, result.bitmap)
            is PageDecodeResult.Tiles -> listener.onPageTilesReady(currentIndex, result.pageWidth, result.pageHeight, result.tiles)
        }
        main.post { releaseInitialFanoutIfAnchorReady(currentIndex) }
        retryPendingWidthIfNeeded(currentIndex)
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

    private fun postCaptchaRequired(target: Manga) {
        main.post { if (!cancelled.get()) listener.onCaptchaRequired(target) }
    }

    private fun postBoundaryAppendFinished(anchor: Int, direction: Int, silent: Boolean, suppressedCaptcha: Boolean) {
        main.post { if (!cancelled.get()) listener.onBoundaryAppendFinished(anchor, direction, silent, suppressedCaptcha) }
    }

    private companion object {
        private const val PREPARED_BITMAP_RELEASE_DELAY_MS = 12000L
        private const val PRIME_WARM_GENERATION = Int.MIN_VALUE
        private const val FOREGROUND_PRIME_WARM_GENERATION = Int.MIN_VALUE + 1
        private const val PRIME_PIPELINE_PARALLELISM = 3
        private const val NTK_FOREGROUND_PRIME_HEDGE_DELAY_MS = 1400L
        private const val PRIME_FORWARD_EPISODES = 40
        private const val NTK_PRIME_FORWARD_EPISODES = 2
        private const val NTK_GENERATED_FORWARD_PRIME_AFTER_FIRST_BITMAP_DELAY_MS = 0L
        private const val NTK_NATIVE_FORWARD_PRIME_AFTER_FIRST_BITMAP_DELAY_MS = 4500L
        private const val NTK_PRIMED_EPISODE_DECODE_AHEAD_PAGES = 20
        private const val NTK_PRIMED_EPISODE_PRIORITY_PAGES = 16
        private const val NTK_PRIMED_EPISODE_BYTE_AHEAD_PAGES = 28
        private const val NTK_LIGHT_PRIMED_EPISODE_DECODE_AHEAD_PAGES = 10
        private const val NTK_LIGHT_PRIMED_EPISODE_BYTE_AHEAD_PAGES = 12
        private const val NTK_INITIAL_PRIORITY_START_OFFSET = 1
        private const val NTK_INITIAL_PRIORITY_PAGES = 12
        private const val NTK_INITIAL_NEAR_DECODE_AHEAD_PAGES = 20
        private const val NTK_INITIAL_DECODE_AHEAD_PAGES = 64
        private const val NTK_INITIAL_FAR_WARM_DELAY_MS = 450L
        private const val BOUNDARY_DECODE_AHEAD_PAGES = 8
        private const val BOUNDARY_BYTE_AHEAD_PAGES = 32
        private const val BOUNDARY_BUSY_DECODE_AHEAD_PAGES = 10
        private const val BOUNDARY_BUSY_BYTE_AHEAD_PAGES = 28
        private const val BUSY_DELIVERY_SCAN_LIMIT = 64
        private const val BUSY_VISIBLE_DECODE_RADIUS = 5
        private const val BUSY_DELIVERY_DRAIN_LIMIT = 2
        private const val IDLE_DELIVERY_DRAIN_LIMIT = 3
        private const val BUSY_DELIVERY_DRAIN_DELAY_MS = 0L
        private const val IDLE_DELIVERY_RESUME_DELAY_MS = 24L
        private const val IDLE_DELIVERY_FRAME_DELAY_MS = 8L
        private const val INPUT_PRIORITY_QUIET_MS = 24L
        private const val START_SOURCE_PREFETCH_BEFORE = 0
        private const val START_SOURCE_PREFETCH_AFTER = 64
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
