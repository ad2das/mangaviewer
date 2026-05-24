package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.repository.MangaRepository
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
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
        fun onInitialPage(index: Int)
        fun onPageLoading(index: Int)
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

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newFixedThreadPool(ReaderPipelinePolicy.FOREGROUND_NETWORK_PARALLELISM)
    private val decode = Executors.newFixedThreadPool(ReaderPipelinePolicy.IDLE_DECODE_PARALLELISM)
    private val busyDecodeGate = Semaphore(ReaderPipelinePolicy.BUSY_DECODE_PARALLELISM)
    private val idleDecodeGate = Semaphore(ReaderPipelinePolicy.IDLE_DECODE_PARALLELISM)
    private val cancelled = AtomicBoolean(false)
    private val pages = Collections.synchronizedList(ArrayList<PageRef>())
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val decodedWidths = ConcurrentHashMap<Int, Int>()
    private val deliveredBitmaps = LinkedHashMap<Int, Bitmap>(32, 0.75f, true)
    private val deliveredOwned = HashSet<Int>()
    private var retainedFirstPage = 0
    private var retainedLastPage = 0
    private val nextLoading = AtomicBoolean(false)
    private val repositoryLoading = AtomicBoolean(false)
    private val preparedEntry = ReaderPreparedStore.get(preparedKey)
    private val pagesInstalled = AtomicBoolean(false)
    private val preparedListener = object : ReaderPreparedStore.Listener {
        override fun onUrlsReady(images: List<String>, startPage: Int) {
            installImages(images, startPage, true)
        }

        override fun onBitmapReady(index: Int, bitmap: Bitmap) {
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
            installImages(urls, snapshot.startPage, true)
            for (entry in snapshot.bitmaps.entries) deliverPreparedBitmap(entry.key, entry.value)
            return true
        }
        for (entry in snapshot.bitmaps.entries) deliverPreparedBitmap(entry.key, entry.value)
        return false
    }

    private fun installImages(urls: List<String>, requestedStartPage: Int, requestInitialWindow: Boolean) {
        if (cancelled.get() || urls.isEmpty()) return
        if (!pagesInstalled.compareAndSet(false, true)) return
        pages.clear()
        pages.addAll(urls.map { PageRef(manga, it) })
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
        val count = pages.size
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
        val decodedWidth = decodedWidths[index] ?: 0
        if (decodedWidth >= targetWidth || !loading.add(index)) return
        main.post { if (!cancelled.get()) listener.onPageLoading(index) }
        try {
            val page = pageRef(index) ?: return
            val image = page.image ?: return
            val bitmap = if (page.manga.isOnline) {
                decodePageBytes(page.manga, ReaderImageCache.getOrFetchBytes(appContext, page.manga, image), targetWidth)
            } else {
                decodePage(index, ensureImageFile(index), targetWidth)
            }
            if (cancelled.get()) return
            decodedWidths[index] = max(decodedWidths[index] ?: 0, targetWidth)
            trackDeliveredBitmap(index, bitmap, true)
            main.post {
                if (!cancelled.get()) listener.onPageReady(index, bitmap)
            }
        } catch (e: Exception) {
            if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
        } finally {
            loading.remove(index)
        }
    }

    private fun deliverPreparedBitmap(index: Int, bitmap: Bitmap) {
        if (cancelled.get() || index < 0 || bitmap.isRecycled) return
        decodedWidths[index] = max(decodedWidths[index] ?: 0, viewerWidth)
        loading.remove(index)
        trackDeliveredBitmap(index, bitmap, false)
        main.post {
            if (!cancelled.get()) listener.onPageReady(index, bitmap)
        }
    }

    fun requestWindow(first: Int, last: Int, anchor: Int, busy: Boolean) {
        if (cancelled.get()) return
        val count = pages.size
        if (count <= 0) return
        val safeFirst = first.coerceIn(0, count - 1)
        val safeLast = last.coerceIn(safeFirst, count - 1)
        synchronized(deliveredBitmaps) {
            retainedFirstPage = safeFirst
            retainedLastPage = safeLast
        }
        for (i in windowOrder(safeFirst, safeLast, anchor)) requestPage(i, busy, i == anchor)
        trimDecodedWidth(anchor, busy)
    }

    fun clearOutside(first: Int, last: Int) {
        decodedWidths.keys.removeAll { it < first || it > last }
        evictDeliveredBitmaps(first, last)
    }

    fun cancel() {
        cancelled.set(true)
        preparedEntry?.removeListener(preparedListener)
        network.shutdownNow()
        decode.shutdownNow()
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
                anchorManga.title = currentTitle
                anchorManga.titleId = currentTitle.id
                val next = anchorManga.prevEp() ?: return@execute
                next.title = currentTitle
                next.titleId = currentTitle.id
                if (MangaRepository.imageUrls(next, appContext).isNullOrEmpty()) {
                    val result = MangaRepository.fetchViewerInitial(next, MangaRepository.cancellation())
                    if (result != Title.LOAD_OK) return@execute
                }
                val nextUrls = MangaRepository.imageUrls(next, appContext)
                if (nextUrls.isNullOrEmpty()) return@execute
                val start = pages.size
                pages.add(PageRef(next, null, next.name ?: "다음 회차"))
                pages.addAll(nextUrls.map { PageRef(next, it) })
                main.post {
                    if (!cancelled.get()) {
                        listener.onPagesAppended(pages.size)
                        requestWindow(max(start, anchor), minOf(pages.lastIndex, start + ReaderPipelinePolicy.INITIAL_WINDOW_AFTER), start, false)
                    }
                }
            } catch (e: Exception) {
                if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
            } finally {
                nextLoading.set(false)
            }
        }
    }

    private fun requestPage(index: Int, busy: Boolean, anchor: Boolean) {
        val page = pageRef(index) ?: return
        if (page.transitionTitle != null) {
            decodedWidths[index] = Int.MAX_VALUE
            loading.remove(index)
            main.post {
                if (!cancelled.get()) listener.onPageCard(index, page.transitionTitle)
            }
            return
        }
        val targetWidth = targetWidth(busy)
        val decodedWidth = decodedWidths[index] ?: 0
        if (decodedWidth >= targetWidth) return
        if (!loading.add(index)) return
        main.post { if (!cancelled.get()) listener.onPageLoading(index) }
        network.execute {
            try {
                val file = ensureImageFile(index)
                decode.execute {
                    val gate = if (busy) busyDecodeGate else idleDecodeGate
                    var acquired = false
                    try {
                        gate.acquire()
                        acquired = true
                        if (cancelled.get()) return@execute
                        val bitmap = decodePage(index, file, targetWidth)
                        decodedWidths[index] = max(decodedWidths[index] ?: 0, targetWidth)
                        trackDeliveredBitmap(index, bitmap, true)
                        main.post {
                            if (!cancelled.get()) listener.onPageReady(index, bitmap)
                        }
                    } catch (e: Exception) {
                        if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
                    } finally {
                        if (acquired) gate.release()
                        loading.remove(index)
                    }
                }
            } catch (e: Exception) {
                loading.remove(index)
                if (!cancelled.get()) ml.melun.mangaview.report.CrashReporter.record(e)
            }
        }
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

    private fun decodePageBytes(manga: Manga, bytes: ByteArray, targetWidth: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
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

    private fun pageRef(index: Int): PageRef? = synchronized(pages) {
        pages.getOrNull(index)
    }

    fun pageInfo(index: Int): PageInfo? = synchronized(pages) {
        val page = pages.getOrNull(index) ?: return@synchronized null
        val episodeManga = page.manga
        val transition = page.transitionTitle != null
        var total = 0
        var local = 0
        for (i in pages.indices) {
            val candidate = pages[i]
            if (!sameEpisode(candidate.manga, episodeManga) || candidate.image == null) continue
            total++
            if (i <= index) local = total
        }
        PageInfo(
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
            if (release.clearPage) decodedWidths.remove(release.index)
            val action = Runnable {
                if (release.clearPage && !cancelled.get()) listener.onPageCleared(release.index)
                if (release.bitmap != null && !release.bitmap.isRecycled) release.bitmap.recycle()
            }
            if (release.clearPage) {
                main.post(action)
            } else {
                main.postDelayed(action, REPLACED_BITMAP_RECYCLE_DELAY_MS)
            }
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

    private fun windowOrder(first: Int, last: Int, anchor: Int): List<Int> {
        val result = ArrayList<Int>(last - first + 1)
        fun add(index: Int) {
            if (index in first..last && !result.contains(index)) result.add(index)
        }
        val safeAnchor = anchor.coerceIn(first, last)
        add(safeAnchor)
        var distance = 1
        while (result.size < last - first + 1) {
            add(safeAnchor + distance)
            add(safeAnchor - distance)
            distance++
        }
        return result
    }

    private fun trimDecodedWidth(anchor: Int, busy: Boolean) {
        if (!busy) return
        val keepFirst = max(0, anchor - ReaderPipelinePolicy.BUSY_WINDOW_BEFORE)
        val keepLast = anchor + ReaderPipelinePolicy.BUSY_WINDOW_AFTER
        decodedWidths.keys.removeAll { it < keepFirst || it > keepLast }
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
    }
}
