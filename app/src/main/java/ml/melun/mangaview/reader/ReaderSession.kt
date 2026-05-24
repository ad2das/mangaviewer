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
import ml.melun.mangaview.Utils
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
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
import kotlin.math.max

class ReaderSession(
    private val context: Context,
    private val manga: Manga,
    private val title: Title?,
    private val viewerWidth: Int,
    private val autoCut: Boolean,
    private val reverse: Boolean,
    preparedKey: String?,
    private val listener: Listener
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
        fun onPageCleared(index: Int)
        fun onMessage(message: String)
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
        var pageIndex: Int = -1,
        val localPage: Int = 0,
        val totalPages: Int = 0,
        val side: Int = PAGE_SIDE_FIRST
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
            val tiles: List<ReaderTile>
        ) : PageDecodeResult() {
            override val width: Int = pageWidth
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
        val requestedWidth: Int
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
    private val decodedWidths = ConcurrentHashMap<Int, Int>()
    private val desiredWidths = ConcurrentHashMap<Int, Int>()
    private val inFlightWidths = ConcurrentHashMap<Int, Int>()
    private val sourceWidths = ConcurrentHashMap<Int, Int>()
    private val deliveryQueue = ConcurrentLinkedQueue<Delivery>()
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
    private val firstBitmapLogged = AtomicBoolean(false)
    private val windowGeneration = AtomicInteger(0)
    private val nextLoading = AtomicBoolean(false)
    private val previousAppendLoading = AtomicBoolean(false)
    private val nextAppendLoading = AtomicBoolean(false)
    private val timelinePrimeLoading = AtomicBoolean(false)
    private val repositoryLoading = AtomicBoolean(false)
    private val windowLock = Object()
    private var lastWindowAnchor = -1
    private var lastWindowDirection = 0
    private val controlLock = Object()
    private var pendingWindowCommand: WindowCommand? = null
    private var windowCommandPosted = false
    private val preparedEntry = if (autoCut) null else ReaderPreparedStore.get(preparedKey)
    private val preparedStoreKey = if (autoCut) null else preparedKey
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
            installImages(images, startPage, false)
            flushEarlyPreparedBitmaps()
            releasePreparedStoreBitmapsSoon()
            requestInitialWindow(startPage, false)
        }

        override fun onBitmapReady(index: Int, bitmap: Bitmap) {
            if (!pagesInstalled.get()) {
                if (!bitmap.isRecycled) earlyPreparedBitmaps[index] = bitmap
                return
            }
            deliverPreparedBitmap(index, bitmap)
        }

        override fun onFailed() {
            if (!pagesInstalled.get()) loadFromRepository()
        }
    }

    fun start() {
        val entry = preparedEntry
        if (entry != null) {
            val snapshot = entry.addListener(preparedListener)
            if (installPreparedSnapshot(snapshot)) return
            if (snapshot.status != ReaderPreparedStore.Status.FAILED) {
                main.postDelayed({
                    if (!cancelled.get() && !pagesInstalled.get()) loadFromRepository()
                }, PREPARED_FALLBACK_MS)
                return
            }
        }
        loadFromRepository()
    }

    private fun loadFromRepository() {
        if (!repositoryLoading.compareAndSet(false, true)) return
        network.execute {
            try {
                attachTitle()
                var urls = MangaRepository.imageUrls(manga, appContext)
                if (urls.isNullOrEmpty()) {
                    val result = MangaRepository.fetchViewerInitial(manga, MangaRepository.cancellation())
                    if (result != Title.LOAD_OK) {
                        postMessage("이미지를 불러오지 못했습니다")
                        return@execute
                    }
                    urls = MangaRepository.imageUrls(manga, appContext)
                }
                if (urls.isNullOrEmpty()) {
                    postMessage("표시할 이미지가 없습니다")
                    return@execute
                }
                val startPage = requestedStartPage().coerceIn(0, urls.lastIndex)
                installImages(urls, startPage, false)
                flushEarlyPreparedBitmaps()
                requestPageForeground(startPage)
                requestInitialWindow(startPage, false)
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
            installImages(urls, snapshot.startPage, false)
            for (entry in snapshot.bitmaps.entries) deliverPreparedBitmap(entry.key, entry.value)
            flushEarlyPreparedBitmaps()
            releasePreparedStoreBitmapsSoon()
            requestInitialWindow(snapshot.startPage, false)
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
        main.postDelayed(clearPreparedBitmapsRunnable, PREPARED_BITMAP_RELEASE_DELAY_MS)
    }

    private fun installImages(urls: List<String>, requestedStartPage: Int, requestInitialWindow: Boolean) {
        if (cancelled.get() || urls.isEmpty()) return
        if (!pagesInstalled.compareAndSet(false, true)) return
        val refs = pageRefsForImages(manga, urls)
        synchronized(pagesLock) {
            pages.clear()
            refs.forEachIndexed { index, page ->
                page.pageIndex = index
            }
            pages.addAll(refs)
        }
        val startPage = displayStartPage(requestedStartPage, requestedStartSide(), refs.size)
        main.post {
            if (!cancelled.get()) {
                listener.onPagesReady(refs.size)
                listener.onInitialPage(startPage)
            }
        }
        primeForwardTimeline()
        if (requestInitialWindow) requestInitialWindow(startPage, false)
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

    private fun requestPageForeground(index: Int) {
        val targetWidth = targetWidth(false)
        rememberDesiredWidth(index, targetWidth)
        val decodedWidth = decodedWidths[index] ?: 0
        if (decodedWidth >= achievableWidth(index, targetWidth)) return
        val activeWidth = inFlightWidths[index] ?: 0
        if (!loading.add(index)) {
            if (targetWidth > activeWidth)
                ViewerWarmupManager.logMetric("busy_to_idle_upgrade_pending", targetWidth.toLong())
            return
        }
        inFlightWidths[index] = targetWidth
        val startedAt = SystemClock.elapsedRealtime()
        var delivered = false
        main.post { if (!cancelled.get()) listener.onPageLoading(index) }
        try {
            val page = pageRef(index) ?: return
            prefetchImageFile(index, page)
            val result = decodePageWithLease(index, page, targetWidth)
            if (cancelled.get() || pageRef(index) != page) {
                recycleDecodeResult(result)
                return
            }
            delivered = true
            postDecodeResult(Delivery(index, page, result, startedAt, targetWidth))
        } catch (e: Exception) {
            recordIfUnexpected(e)
        } finally {
            loading.remove(index)
            inFlightWidths.remove(index)
            if (delivered) ViewerWarmupManager.logMetric("reader_delivery_posted", index.toLong())
        }
    }

    private fun deliverPreparedBitmap(index: Int, bitmap: Bitmap) {
        if (cancelled.get() || index < 0 || bitmap.isRecycled) return
        decodedWidths[index] = max(decodedWidths[index] ?: 0, bitmap.width)
        loading.remove(index)
        trackDeliveredBitmap(index, bitmap, false)
        main.post {
            if (!cancelled.get()) listener.onPageReady(index, bitmap)
        }
    }

    private fun flushEarlyPreparedBitmaps() {
        val pending = earlyPreparedBitmaps.entries.toList()
        earlyPreparedBitmaps.clear()
        for (entry in pending) deliverPreparedBitmap(entry.key, entry.value)
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
        synchronized(windowLock) {
            val safeFirst = first.coerceIn(0, count - 1)
            val safeLast = last.coerceIn(safeFirst, count - 1)
            generation = windowGeneration.incrementAndGet()
            val direction = when {
                lastWindowAnchor < 0 -> lastWindowDirection
                anchor > lastWindowAnchor -> 1
                anchor < lastWindowAnchor -> -1
                else -> lastWindowDirection
            }
            lastWindowAnchor = anchor
            if (direction != 0) lastWindowDirection = direction
            if (retainWindow) {
                synchronized(deliveredBitmaps) {
                    retainedFirstPage = safeFirst
                    retainedLastPage = safeLast
                }
            }
            requestList = windowOrder(safeFirst, safeLast, anchor, direction)
        }
        if (busy) return
        for (i in requestList) requestPage(i, busy, i == anchor, generation)
        trimDecodedWidth(anchor, busy)
    }

    fun clearOutside(first: Int, last: Int) {
        decodedWidths.keys.removeAll { it < first || it > last }
        desiredWidths.keys.removeAll { it < first || it > last }
        sourceWidths.keys.removeAll { it < first || it > last }
        evictDeliveredBitmaps(first, last)
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        main.removeCallbacks(clearPreparedBitmapsRunnable)
        main.removeCallbacks(deliveryDrainRunnable)
        recycleQueuedDeliveries()
        preparedEntry?.removeListener(preparedListener)
        releaseDeliveredBitmaps()
        network.shutdownNow()
        decode.shutdownNow()
        anchorNetwork.shutdownNow()
        anchorDecode.shutdownNow()
        cleanup.shutdown()
        control.shutdownNow()
    }

    fun prepareNextEpisode(anchor: Int) {
        if (cancelled.get() || nextLoading.getAndSet(true)) return
        network.execute {
            try {
                val anchorManga = pageRef(anchor)?.manga ?: manga
                val currentTitle = title ?: anchorManga.title ?: manga.title
                if (currentTitle == null) return@execute
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    MangaRepository.fetchEpisodesForeground(currentTitle, MangaRepository.cancellation())
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
                if (MangaRepository.imageUrls(next, appContext).isNullOrEmpty()) {
                    val result = MangaRepository.fetchViewerInitial(next, MangaRepository.cancellation())
                    if (result != Title.LOAD_OK) return@execute
                }
                val nextUrls = MangaRepository.imageUrls(next, appContext)
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

    fun appendAdjacentEpisode(anchor: Int, direction: Int, silentMissing: Boolean = false) {
        val loadingFlag = if (direction < 0) previousAppendLoading else nextAppendLoading
        if (cancelled.get() || loadingFlag.getAndSet(true)) return
        network.execute {
            try {
                val anchorManga = pageRef(anchor)?.manga ?: manga
                val currentTitle = title ?: anchorManga.title ?: manga.title
                if (currentTitle == null) return@execute
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    val result = MangaRepository.fetchEpisodesForeground(currentTitle, MangaRepository.cancellation())
                    if (result != Title.LOAD_OK) {
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
                val target = if (direction < 0) anchorManga.prevEp() else anchorManga.nextEp()
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
                if (hasEpisode(target)) return@execute
                if (MangaRepository.imageUrls(target, appContext).isNullOrEmpty()) {
                    val result = MangaRepository.fetchViewerInitial(target, MangaRepository.cancellation())
                    if (result != Title.LOAD_OK) {
                        postMessage(if (result == Title.LOAD_CAPTCHA) "캡차 확인이 필요합니다" else "회차를 불러오지 못했습니다")
                        return@execute
                    }
                }
                val urls = MangaRepository.imageUrls(target, appContext)
                if (urls.isNullOrEmpty()) {
                    postMessage("표시할 이미지가 없습니다")
                    return@execute
                }
                appendResolvedEpisode(target, urls, direction)
            } catch (e: Exception) {
                recordIfUnexpected(e)
            } finally {
                loadingFlag.set(false)
            }
        }
    }

    private fun primeForwardTimeline() {
        if (cancelled.get() || !timelinePrimeLoading.compareAndSet(false, true)) return
        network.execute {
            try {
                var current = manga
                val currentTitle = title ?: current.title ?: manga.title ?: return@execute
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    val result = MangaRepository.fetchEpisodesForeground(currentTitle, MangaRepository.cancellation())
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
                val primedRefs = ArrayList<PageRef>()
                val cardOffsets = ArrayList<Int>()
                var checked = 0
                while (!cancelled.get() && checked < PRIME_FORWARD_EPISODES) {
                    val target = current.nextEp() ?: break
                    target.title = currentTitle
                    target.titleId = currentTitle.id
                    target.mode = current.mode
                    if (episodes.isNotEmpty()) target.setEps(episodes)
                    if (!hasEpisode(target)) {
                        if (MangaRepository.imageUrls(target, appContext).isNullOrEmpty()) {
                            val result = MangaRepository.fetchViewerInitial(target, MangaRepository.cancellation())
                            if (result != Title.LOAD_OK) break
                        }
                        val urls = MangaRepository.imageUrls(target, appContext)
                        if (urls.isNullOrEmpty()) break
                        cardOffsets.add(primedRefs.size)
                        primedRefs.addAll(pageRefsForEpisode(target, urls, ReaderSurfaceView.DIRECTION_NEXT))
                    }
                    checked++
                    current = target
                }
                appendPrimedForwardRefs(primedRefs, cardOffsets)
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
            add(PageRef(target, null, transitionTitle, localPage = 0, totalPages = totalPages))
            addAll(pageRefs)
        }
    }

    private fun pageRefsForImages(target: Manga, urls: List<String>): List<PageRef> {
        if (!autoCut) {
            val totalPages = urls.size
            return urls.mapIndexed { index, url ->
                PageRef(
                    manga = target,
                    image = url,
                    pageIndex = index,
                    localPage = index + 1,
                    totalPages = totalPages
                )
            }
        }
        val totalPages = urls.size * 2
        val refs = ArrayList<PageRef>(totalPages)
        for (index in urls.indices) {
            val url = urls[index]
            refs.add(PageRef(target, url, localPage = index * 2 + 1, totalPages = totalPages, side = PAGE_SIDE_FIRST))
            refs.add(PageRef(target, url, localPage = index * 2 + 2, totalPages = totalPages, side = PAGE_SIDE_SECOND))
        }
        return refs
    }

    private fun displayStartPage(sourcePage: Int, sourceSide: Int, totalPages: Int): Int {
        val mapped = if (autoCut) sourcePage * 2 + sourceSide.coerceIn(PAGE_SIDE_FIRST, PAGE_SIDE_SECOND) else sourcePage
        return mapped.coerceIn(0, max(0, totalPages - 1))
    }

    private fun appendPrimedForwardRefs(refs: List<PageRef>, cardOffsets: List<Int>) {
        if (cancelled.get() || refs.isEmpty()) return
        val startIndex: Int
        val total: Int
        synchronized(pagesLock) {
            startIndex = pages.size
            refs.forEachIndexed { offset, page -> page.pageIndex = startIndex + offset }
            pages.addAll(refs)
            total = pages.size
        }
        main.post {
            if (!cancelled.get()) {
                listener.onPagesAppended(total)
                for (offset in cardOffsets) {
                    refs.getOrNull(offset)?.transitionTitle?.let { title ->
                        listener.onPageCard(startIndex + offset, title)
                    }
                }
            }
        }
    }

    private fun appendResolvedEpisode(target: Manga, urls: List<String>, direction: Int, warm: Boolean = true) {
        val refs = pageRefsForEpisode(target, urls, direction)
        val transitionTitle = refs.first().transitionTitle ?: ""
        val inserted = refs.size
        val total: Int
        if (direction < 0) {
            synchronized(pagesLock) {
                for (page in pages) page.pageIndex += inserted
                refs.forEachIndexed { index, page -> page.pageIndex = index }
                pages.addAll(0, refs)
                total = pages.size
                shiftPageStateForPrepend(inserted)
            }
            main.post {
                if (!cancelled.get()) {
                    listener.onPagesPrepended(total, inserted)
                    listener.onPageCard(0, transitionTitle)
                }
            }
            if (warm) warmPrependedEpisode(inserted)
        } else {
            val cardIndex: Int
            synchronized(pagesLock) {
                cardIndex = pages.size
                refs.forEachIndexed { offset, page -> page.pageIndex = cardIndex + offset }
                pages.addAll(refs)
                total = pages.size
            }
            main.post {
                if (!cancelled.get()) {
                    listener.onPagesAppended(total)
                    listener.onPageCard(cardIndex, transitionTitle)
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
        requestPage(0, busy = busy, anchor = false, generation = generation)
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
        pages.any { sameEpisode(it.manga, target) }
    }

    private fun shiftPageStateForPrepend(delta: Int) {
        if (delta <= 0) return
        shiftConcurrentMap(decodedWidths, delta)
        shiftConcurrentMap(desiredWidths, delta)
        shiftConcurrentMap(sourceWidths, delta)
        shiftConcurrentMap(earlyPreparedBitmaps, delta)
        inFlightWidths.clear()
        loading.clear()
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
        val activeWidth = inFlightWidths[index] ?: 0
        if (!loading.add(index)) {
            if (targetWidth > activeWidth)
                ViewerWarmupManager.logMetric("busy_to_idle_upgrade_pending", targetWidth.toLong())
            return
        }
        inFlightWidths[index] = targetWidth
        if (!busy || anchor) {
            main.post { if (!cancelled.get()) listener.onPageLoading(index) }
        }
        val networkExecutor = if (anchor) anchorNetwork else network
        val decodeExecutor = if (anchor) anchorDecode else decode
        try {
            networkExecutor.execute {
            try {
                if (shouldSkipStalePage(index, generation, anchor)) {
                    loading.remove(index)
                    inFlightWidths.remove(index)
                    return@execute
                }
                val originalPage = page
                prefetchImageFile(index, originalPage)
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
                        val result = decodePageWithLease(index, originalPage, targetWidth)
                        if (
                            cancelled.get() ||
                            shouldSkipStalePage(index, generation, anchor) ||
                            pageRef(index) != originalPage
                        ) {
                            recycleDecodeResult(result)
                            return@execute
                        }
                        delivered = true
                        postDecodeResult(Delivery(index, originalPage, result, startedAt, targetWidth))
                    } catch (e: Exception) {
                        recordIfUnexpected(e)
                    } finally {
                        if (acquired) gate.release()
                        loading.remove(index)
                        inFlightWidths.remove(index)
                        if (delivered) ViewerWarmupManager.logMetric("reader_delivery_posted", index.toLong())
                    }
                }
                } catch (_: RejectedExecutionException) {
                    loading.remove(index)
                    inFlightWidths.remove(index)
                }
            } catch (e: Exception) {
                loading.remove(index)
                inFlightWidths.remove(index)
                recordIfUnexpected(e)
            }
            }
        } catch (_: RejectedExecutionException) {
            loading.remove(index)
            inFlightWidths.remove(index)
        }
    }

    private fun prefetchBusyPage(index: Int, page: PageRef, generation: Int) {
        if (!loading.add(index)) return
        inFlightWidths[index] = targetWidth(true)
        try {
            network.execute {
                try {
                    if (!shouldSkipStalePage(index, generation, false)) {
                        prefetchImageFile(index, page)
                    }
                } catch (e: Exception) {
                    recordIfUnexpected(e)
                } finally {
                    loading.remove(index)
                    inFlightWidths.remove(index)
                }
            }
        } catch (_: RejectedExecutionException) {
            loading.remove(index)
            inFlightWidths.remove(index)
        }
    }

    private fun shouldSkipStalePage(index: Int, generation: Int, anchor: Boolean): Boolean {
        if (cancelled.get()) return true
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
        if (firstBitmapLogged.compareAndSet(false, true))
            ViewerWarmupManager.logMetric("reader_first_bitmap_ms", SystemClock.elapsedRealtime() - startedAt)
    }

    private fun leaseImageFile(index: Int, page: PageRef): ReaderImageCache.FileLease {
        val image = page.image ?: throw java.io.IOException("Missing image for page $index")
        return ReaderImageCache.leaseFile(appContext, page.manga, image)
    }

    private fun prefetchImageFile(index: Int, page: PageRef) {
        val image = page.image ?: return
        if (page.manga.isOnline) {
            ReaderImageCache.getOrFetchFile(appContext, page.manga, image)
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

    private fun isExpectedCancellation(t: Throwable?): Boolean {
        if (t == null) return false
        if (cancelled.get()) return true
        if (t is InterruptedException || t is InterruptedIOException) return true
        if (t is ExecutionException) return isExpectedCancellation(t.cause)
        val cause = t.cause
        return cause != null && cause !== t && isExpectedCancellation(cause)
    }

    private fun decodePageWithLease(index: Int, page: PageRef, targetWidth: Int): PageDecodeResult {
        leaseImageFile(index, page).use { lease ->
            return decodePage(index, page, lease.file, targetWidth)
        }
    }

    private fun decodePage(index: Int, page: PageRef, file: File, targetWidth: Int): PageDecodeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (page.manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } else {
            decodeLocal(page.image ?: "", bounds)
        }
        val displayBounds = displayBounds(bounds.outWidth, bounds.outHeight, page.side)
        postPageBounds(index, displayBounds.width(), displayBounds.height())
        if (!autoCut && shouldDecodeTiles(page, file, bounds)) {
            return decodePageTiles(file, bounds, targetWidth)
        }
        val sample = sampleSize(bounds.outWidth, targetWidth)
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
        if (!page.manga.isOnline) return PageDecodeResult.Full(applyAutoSplit(raw, page.side))
        val decoded = Decoder(page.manga.seed, page.manga.id).decode(raw, targetWidth)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        return PageDecodeResult.Full(applyAutoSplit(decoded, page.side))
    }

    private fun displayBounds(width: Int, height: Int, side: Int): Rect {
        val safeWidth = max(1, width)
        val safeHeight = max(1, height)
        if (!autoCut) return Rect(0, 0, safeWidth, safeHeight)
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

    private fun applyAutoSplit(bitmap: Bitmap, side: Int): Bitmap {
        if (!autoCut) return bitmap
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
        val tiles = ArrayList<ReaderTile>()
        val rect = Rect()
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
                tiles.add(ReaderTile(top, bottom, sourceWidth, sourceHeight, bitmap))
                top = bottom
            }
            success = true
        } finally {
            if (!success) {
                for (tile in tiles) {
                    if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
                }
            }
            decoder.recycle()
        }
        return PageDecodeResult.Tiles(sourceWidth, sourceHeight, tiles)
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
        return minOf(requestedWidth, max(1, sourceWidth))
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
        return if (autoCut) {
            max(0, (page.localPage - 1) / 2)
        } else {
            max(0, page.localPage - 1)
        }
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
        val first = MangaRepository.imageUrls(a, appContext)
        val second = MangaRepository.imageUrls(b, appContext)
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
        deliveryQueue.add(delivery)
        scheduleDeliveryDrain()
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
            val busy = viewportBusy.get()
            if (busy) {
                discardBusyStaleDeliveries()
                var deliveredCount = 0
                while (deliveredCount < BUSY_DELIVERY_DRAIN_LIMIT) {
                    val delivery = deliveryQueue.poll() ?: break
                    deliverDecodeResultOnMain(delivery, true)
                    deliveredCount++
                }
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

    private fun discardBusyStaleDeliveries() {
        val retainedFirst: Int
        val retainedLast: Int
        synchronized(deliveredBitmaps) {
            retainedFirst = retainedFirstPage
            retainedLast = retainedLastPage
        }
        val retained = ArrayList<Delivery>(BUSY_DELIVERY_RETAIN_LIMIT)
        var checked = 0
        while (checked < BUSY_DELIVERY_DISCARD_LIMIT) {
            val delivery = deliveryQueue.poll() ?: break
            checked++
            val index = delivery.page.pageIndex
            if (index in retainedFirst..retainedLast && retained.size < BUSY_DELIVERY_RETAIN_LIMIT) {
                retained.add(delivery)
            } else {
                recycleDecodeResult(delivery.result)
            }
        }
        retained.forEach { deliveryQueue.add(it) }
    }

    private fun deliverDecodeResultOnMain(delivery: Delivery, busy: Boolean) {
        if (cancelled.get()) {
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
            recycleDecodeResult(delivery.result)
            return
        }
        val currentIndex = synchronized(pagesLock) {
            val index = if (knownIndex in pages.indices && pages[knownIndex] === delivery.page) {
                knownIndex
            } else {
                pages.indexOfFirst { it === delivery.page }
            }
            if (index >= 0 && (!busy || index in retainedFirst..retainedLast)) {
                decodedWidths[index] = max(decodedWidths[index] ?: 0, delivery.result.width)
                desiredWidths[index] = max(desiredWidths[index] ?: 0, delivery.requestedWidth)
                trackDeliveredResult(index, delivery.result)
            }
            index
        }
        if (currentIndex < 0 || (busy && currentIndex !in retainedFirst..retainedLast)) {
            recycleDecodeResult(delivery.result)
            return
        }
        logFirstBitmapIfNeeded(delivery.startedAt)
        when (val result = delivery.result) {
            is PageDecodeResult.Full -> listener.onPageReady(currentIndex, result.bitmap)
            is PageDecodeResult.Tiles -> listener.onPageTilesReady(currentIndex, result.pageWidth, result.pageHeight, result.tiles)
        }
        retryPendingWidthIfNeeded(currentIndex)
    }

    private fun recycleQueuedDeliveries() {
        while (true) {
            val delivery = deliveryQueue.poll() ?: return
            recycleDecodeResult(delivery.result)
        }
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
        for (bitmap in toRecycle) recycleBitmapAfterDelay(bitmap)
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
            if (viewportBusy.get() || deliveryDrainDelayMs() > 0L) {
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
        return if (busy) {
            minOf(width, ReaderPipelinePolicy.BUSY_DECODE_WIDTH)
        } else {
            width
        }
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

    private fun requestedStartPage(): Int {
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

    private companion object {
        private const val PREPARED_FALLBACK_MS = 1500L
        private const val PREPARED_BITMAP_RELEASE_DELAY_MS = 3000L
        private const val PRIME_FORWARD_EPISODES = 40
        private const val BOUNDARY_DECODE_AHEAD_PAGES = 4
        private const val BOUNDARY_BYTE_AHEAD_PAGES = 16
        private const val BOUNDARY_BUSY_DECODE_AHEAD_PAGES = 4
        private const val BOUNDARY_BUSY_BYTE_AHEAD_PAGES = 12
        private const val BUSY_DELIVERY_DISCARD_LIMIT = 16
        private const val BUSY_DELIVERY_RETAIN_LIMIT = 4
        private const val BUSY_DELIVERY_DRAIN_LIMIT = 1
        private const val IDLE_DELIVERY_DRAIN_LIMIT = 1
        private const val BUSY_DELIVERY_DRAIN_DELAY_MS = 24L
        private const val IDLE_DELIVERY_RESUME_DELAY_MS = 96L
        private const val IDLE_DELIVERY_FRAME_DELAY_MS = 24L
        private const val INPUT_PRIORITY_QUIET_MS = 96L
        private const val ACTIVE_BITMAP_BYTES = 64L * 1024L * 1024L
        private const val TILE_PAGE_MAX_BYTES = 24L * 1024L * 1024L
        private const val REPLACED_BITMAP_RECYCLE_DELAY_MS = 750L
        private const val TILE_PAGE_ASPECT_RATIO = 3.0f
        private const val TILE_PAGE_MIN_ESTIMATED_BYTES = 12L * 1024L * 1024L
        private const val TILE_SOURCE_HEIGHT = 2048
        private const val SPREAD_ASPECT_RATIO = 0.90f
        private const val PAGE_SIDE_FIRST = 0
        private const val PAGE_SIDE_SECOND = 1

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
