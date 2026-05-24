package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class ReaderSession(
    private val context: Context,
    private val manga: Manga,
    private val title: Title?,
    private val viewerWidth: Int,
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
        fun onPageCard(index: Int, title: String)
        fun onPageCleared(index: Int)
        fun onMessage(message: String)
    }

    data class PageInfo(
        val manga: Manga,
        val title: String,
        val localPage: Int,
        val totalPages: Int,
        val transitionCard: Boolean
    )

    private data class PageRef(
        val manga: Manga,
        val image: String?,
        val transitionTitle: String? = null
    )

    private data class BitmapRelease(
        val index: Int,
        val bitmap: Bitmap?,
        val clearPage: Boolean
    )

    private data class WindowCommand(
        val first: Int,
        val last: Int,
        val anchor: Int,
        val busy: Boolean
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
    private val earlyPreparedBitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val deliveredBitmaps = LinkedHashMap<Int, Bitmap>(32, 0.75f, true)
    private val deliveredOwned = HashSet<Int>()
    private var retainedFirstPage = 0
    private var retainedLastPage = 0
    private val firstBitmapLogged = AtomicBoolean(false)
    private val windowGeneration = AtomicInteger(0)
    private val nextLoading = AtomicBoolean(false)
    private val adjacentAppendLoading = AtomicBoolean(false)
    private val repositoryLoading = AtomicBoolean(false)
    private val windowLock = Object()
    private var lastWindowAnchor = -1
    private var lastWindowDirection = 0
    private val controlLock = Object()
    private var pendingWindowCommand: WindowCommand? = null
    private var windowCommandPosted = false
    private val preparedEntry = ReaderPreparedStore.get(preparedKey)
    private val pagesInstalled = AtomicBoolean(false)
    private val preparedListener = object : ReaderPreparedStore.Listener {
        override fun onUrlsReady(images: List<String>, startPage: Int) {
            installImages(images, startPage, false)
            flushEarlyPreparedBitmaps()
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
                ml.melun.mangaview.report.CrashReporter.record(e)
                postMessage("이미지를 불러오지 못했습니다")
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
            requestInitialWindow(snapshot.startPage, false)
            return true
        }
        for (entry in snapshot.bitmaps.entries) {
            if (!entry.value.isRecycled) earlyPreparedBitmaps[entry.key] = entry.value
        }
        return false
    }

    private fun installImages(urls: List<String>, requestedStartPage: Int, requestInitialWindow: Boolean) {
        if (cancelled.get() || urls.isEmpty()) return
        if (!pagesInstalled.compareAndSet(false, true)) return
        synchronized(pagesLock) {
            pages.clear()
            pages.addAll(urls.map { PageRef(manga, it) })
        }
        val startPage = requestedStartPage.coerceIn(0, urls.lastIndex)
        main.post {
            if (!cancelled.get()) {
                listener.onPagesReady(urls.size)
                listener.onInitialPage(startPage)
            }
        }
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
            val image = page.image ?: return
            val bitmap = if (page.manga.isOnline) {
                decodePageBytes(index, page.manga, ReaderImageCache.getOrFetchBytes(appContext, page.manga, image), targetWidth)
            } else {
                decodePage(index, ensureImageFile(index), targetWidth)
            }
            if (cancelled.get()) return
            decodedWidths[index] = max(decodedWidths[index] ?: 0, bitmap.width)
            trackDeliveredBitmap(index, bitmap, true)
            delivered = true
            logFirstBitmapIfNeeded(startedAt)
            main.post {
                if (!cancelled.get()) listener.onPageReady(index, bitmap)
            }
        } catch (e: Exception) {
            if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
        } finally {
            loading.remove(index)
            inFlightWidths.remove(index)
            if (delivered) retryPendingWidthIfNeeded(index)
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
        cancelled.set(true)
        preparedEntry?.removeListener(preparedListener)
        network.shutdownNow()
        decode.shutdownNow()
        anchorNetwork.shutdownNow()
        anchorDecode.shutdownNow()
        cleanup.shutdownNow()
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
                if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
            } finally {
                nextLoading.set(false)
            }
        }
    }

    fun appendAdjacentEpisode(anchor: Int, direction: Int) {
        if (cancelled.get() || adjacentAppendLoading.getAndSet(true)) return
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
                    postMessage(if (direction < 0) "이전 회차가 없습니다" else "다음 회차가 없습니다")
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
                if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
            } finally {
                adjacentAppendLoading.set(false)
            }
        }
    }

    private fun appendResolvedEpisode(target: Manga, urls: List<String>, direction: Int) {
        val episodeName = target.name ?: title?.name ?: "회차"
        val transitionTitle = if (direction < 0) "이전 회차: $episodeName" else "다음 회차: $episodeName"
        val refs = ArrayList<PageRef>(urls.size + 1)
        refs.add(PageRef(target, null, transitionTitle))
        refs.addAll(urls.map { PageRef(target, it) })
        val inserted = refs.size
        val total: Int
        if (direction < 0) {
            synchronized(pagesLock) {
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
        } else {
            val cardIndex: Int
            synchronized(pagesLock) {
                cardIndex = pages.size
                pages.addAll(refs)
                total = pages.size
            }
            main.post {
                if (!cancelled.get()) {
                    listener.onPagesAppended(total)
                    listener.onPageCard(cardIndex, transitionTitle)
                }
            }
        }
    }

    private fun hasEpisode(target: Manga): Boolean = synchronized(pagesLock) {
        pages.any { sameEpisode(it.manga, target) }
    }

    private fun shiftPageStateForPrepend(delta: Int) {
        if (delta <= 0) return
        shiftConcurrentMap(decodedWidths, delta)
        shiftConcurrentMap(desiredWidths, delta)
        shiftConcurrentMap(inFlightWidths, delta)
        shiftConcurrentMap(sourceWidths, delta)
        shiftConcurrentMap(earlyPreparedBitmaps, delta)
        shiftConcurrentSet(loading, delta)
        synchronized(deliveredBitmaps) {
            val oldEntries = deliveredBitmaps.entries.toList()
            deliveredBitmaps.clear()
            for (entry in oldEntries) deliveredBitmaps[entry.key + delta] = entry.value
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
                val file = ensureImageFile(index)
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
                        val bitmap = decodePage(index, file, targetWidth)
                        decodedWidths[index] = max(decodedWidths[index] ?: 0, bitmap.width)
                        trackDeliveredBitmap(index, bitmap, true)
                        delivered = true
                        logFirstBitmapIfNeeded(startedAt)
                        main.post {
                            if (!cancelled.get()) listener.onPageReady(index, bitmap)
                        }
                    } catch (e: Exception) {
                        if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
                    } finally {
                        if (acquired) gate.release()
                        loading.remove(index)
                        inFlightWidths.remove(index)
                        if (delivered) retryPendingWidthIfNeeded(index)
                    }
                }
                } catch (_: RejectedExecutionException) {
                    loading.remove(index)
                    inFlightWidths.remove(index)
                }
            } catch (e: Exception) {
                loading.remove(index)
                inFlightWidths.remove(index)
                if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
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
        deliveredBitmaps[index]?.let { !it.isRecycled } == true
    }

    private fun logFirstBitmapIfNeeded(startedAt: Long) {
        if (firstBitmapLogged.compareAndSet(false, true))
            ViewerWarmupManager.logMetric("reader_first_bitmap_ms", SystemClock.elapsedRealtime() - startedAt)
    }

    private fun ensureImageFile(index: Int): File {
        val page = pageRef(index) ?: throw IndexOutOfBoundsException("Missing page $index")
        val image = page.image ?: throw java.io.IOException("Missing image for page $index")
        if (!page.manga.isOnline) return File(image)
        return ReaderImageCache.getOrFetchFile(appContext, page.manga, image)
    }

    private fun decodePage(index: Int, file: File, targetWidth: Int): Bitmap {
        val page = pageRef(index) ?: throw IndexOutOfBoundsException("Missing page $index")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (page.manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } else {
            decodeLocal(page.image ?: "", bounds)
        }
        postPageBounds(index, bounds.outWidth, bounds.outHeight)
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
        if (!page.manga.isOnline) return raw
        val decoded = Decoder(page.manga.seed, page.manga.id).decode(raw, targetWidth)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        return decoded
    }

    private fun decodePageBytes(index: Int, manga: Manga, bytes: ByteArray, targetWidth: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        postPageBounds(index, bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize(bounds.outWidth, targetWidth)
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw java.io.IOException("Bitmap decode failed")
        val decoded = Decoder(manga.seed, manga.id).decode(raw, targetWidth)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        return decoded
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
        val snapshot = synchronized(pagesLock) {
            if (index !in pages.indices) return null
            pages.toList()
        }
        val page = snapshot.getOrNull(index) ?: return null
        val episodeManga = page.manga
        val transition = page.transitionTitle != null
        var total = 0
        var local = 0
        for (i in snapshot.indices) {
            val candidate = snapshot[i]
            if (!sameEpisode(candidate.manga, episodeManga) || candidate.image == null) continue
            total++
            if (i <= index) local = total
        }
        return PageInfo(
            manga = episodeManga,
            title = page.transitionTitle ?: episodeManga.name ?: title?.name ?: "",
            localPage = if (transition) 0 else max(1, local),
            totalPages = total,
            transitionCard = transition
        )
    }

    private fun sameEpisode(a: Manga, b: Manga): Boolean {
        return a.id == b.id &&
            a.baseMode == b.baseMode &&
            a.titleId == b.titleId &&
            (a.ntkEpisodePath ?: "") == (b.ntkEpisodePath ?: "")
    }

    private fun trackDeliveredBitmap(index: Int, bitmap: Bitmap, owned: Boolean) {
        val cleared = ArrayList<BitmapRelease>()
        synchronized(deliveredBitmaps) {
            val previous = deliveredBitmaps.put(index, bitmap)
            if (previous != null && previous !== bitmap && deliveredOwned.remove(index)) {
                cleared.add(BitmapRelease(index, previous, false))
            }
            if (owned) deliveredOwned.add(index) else deliveredOwned.remove(index)
            trimDeliveredBudgetLocked(cleared)
        }
        postBitmapReleases(cleared)
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
            if (!trimmed) return
        }
    }

    private fun deliveredBitmapBytesLocked(): Long {
        var total = 0L
        for (bitmap in deliveredBitmaps.values) {
            if (!bitmap.isRecycled) total += bitmapBytes(bitmap).toLong()
        }
        return total
    }

    private fun postBitmapReleases(releases: List<BitmapRelease>) {
        if (releases.isEmpty()) return
        for (release in releases) {
            if (release.clearPage) {
                decodedWidths.remove(release.index)
                desiredWidths.remove(release.index)
                sourceWidths.remove(release.index)
            }
            if (release.clearPage) {
                main.post {
                    if (!cancelled.get()) listener.onPageCleared(release.index)
                    release.bitmap?.let { bitmap ->
                        main.postDelayed({
                            recycleBitmapAsync(bitmap)
                        }, REPLACED_BITMAP_RECYCLE_DELAY_MS)
                    }
                }
            } else {
                release.bitmap?.let { bitmap ->
                    main.postDelayed({
                        recycleBitmapAsync(bitmap)
                    }, REPLACED_BITMAP_RECYCLE_DELAY_MS)
                }
            }
        }
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

    private fun targetWidth(busy: Boolean): Int {
        val width = max(1, viewerWidth)
        return if (busy) {
            minOf(width, ReaderPipelinePolicy.BUSY_DECODE_WIDTH)
        } else {
            max(width, ReaderPipelinePolicy.IDLE_DECODE_WIDTH)
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

    private fun postMessage(message: String) {
        main.post { if (!cancelled.get()) listener.onMessage(message) }
    }

    private companion object {
        private const val PREPARED_FALLBACK_MS = 1500L
        private const val ACTIVE_BITMAP_BYTES = 64L * 1024L * 1024L
        private const val REPLACED_BITMAP_RECYCLE_DELAY_MS = 750L

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
