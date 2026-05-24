package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.Utils
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.repository.MangaRepository
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
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
        fun onMessage(message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newFixedThreadPool(ReaderPipelinePolicy.FOREGROUND_NETWORK_PARALLELISM)
    private val decode = Executors.newFixedThreadPool(ReaderPipelinePolicy.IDLE_DECODE_PARALLELISM)
    private val busyDecodeGate = Semaphore(ReaderPipelinePolicy.BUSY_DECODE_PARALLELISM)
    private val idleDecodeGate = Semaphore(ReaderPipelinePolicy.IDLE_DECODE_PARALLELISM)
    private val cancelled = AtomicBoolean(false)
    private val images = Collections.synchronizedList(ArrayList<String>())
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val decodedWidths = ConcurrentHashMap<Int, Int>()
    private val cacheDir = File(appContext.cacheDir, "reader_v2/${System.nanoTime()}").apply { mkdirs() }
    private val nextLoading = AtomicBoolean(false)
    private val prepared = ReaderPreparedStore.take(preparedKey)

    fun start() {
        network.execute {
            try {
                attachTitle()
                var urls = prepared?.images ?: MangaRepository.imageUrls(manga, appContext)
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
                images.clear()
                images.addAll(urls)
                val startPage = prepared?.startPage?.coerceIn(0, urls.lastIndex) ?: 0
                val preparedBitmaps = prepared?.bitmaps.orEmpty()
                for (index in preparedBitmaps.keys) decodedWidths[index] = viewerWidth
                main.post {
                    if (!cancelled.get()) {
                        listener.onPagesReady(urls.size)
                        listener.onInitialPage(startPage)
                        for (entry in preparedBitmaps.entries) listener.onPageReady(entry.key, entry.value)
                    }
                }
                requestWindow(
                    max(0, startPage - ReaderPipelinePolicy.INITIAL_WINDOW_BEFORE),
                    minOf(urls.lastIndex, startPage + ReaderPipelinePolicy.INITIAL_WINDOW_AFTER),
                    startPage,
                    false
                )
            } catch (e: Exception) {
                ml.melun.mangaview.report.CrashReporter.record(e)
                postMessage("이미지를 불러오지 못했습니다")
            }
        }
    }

    fun requestWindow(first: Int, last: Int, anchor: Int, busy: Boolean) {
        if (cancelled.get()) return
        val count = images.size
        if (count <= 0) return
        val safeFirst = first.coerceIn(0, count - 1)
        val safeLast = last.coerceIn(safeFirst, count - 1)
        for (i in safeFirst..safeLast) requestPage(i, busy, i == anchor)
        trimDecodedWidth(anchor, busy)
    }

    fun clearOutside(first: Int, last: Int) {
        decodedWidths.keys.removeAll { it < first || it > last }
    }

    fun cancel() {
        cancelled.set(true)
        network.shutdownNow()
        decode.shutdownNow()
        try {
            cacheDir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    fun prepareNextEpisode(anchor: Int) {
        if (cancelled.get() || nextLoading.getAndSet(true)) return
        network.execute {
            try {
                val currentTitle = title ?: manga.title
                if (currentTitle == null) return@execute
                if (currentTitle.eps == null || currentTitle.eps.size <= 1) {
                    MangaRepository.fetchEpisodesForeground(currentTitle, MangaRepository.cancellation())
                }
                attachTitle()
                val next = manga.nextEp() ?: return@execute
                next.title = currentTitle
                next.titleId = currentTitle.id
                if (MangaRepository.imageUrls(next, appContext).isNullOrEmpty()) {
                    val result = MangaRepository.fetchViewerInitial(next, MangaRepository.cancellation())
                    if (result != Title.LOAD_OK) return@execute
                }
                val nextUrls = MangaRepository.imageUrls(next, appContext)
                if (nextUrls.isNullOrEmpty()) return@execute
                val start = images.size
                images.addAll(nextUrls)
                main.post {
                    if (!cancelled.get()) {
                        listener.onPagesAppended(images.size)
                        requestWindow(max(start, anchor), minOf(images.lastIndex, start + ReaderPipelinePolicy.INITIAL_WINDOW_AFTER), start, false)
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
                    gate.acquire()
                    try {
                        if (cancelled.get()) return@execute
                        val bitmap = decodePage(index, file, targetWidth)
                        decodedWidths[index] = max(decodedWidths[index] ?: 0, targetWidth)
                        main.post {
                            if (!cancelled.get()) listener.onPageReady(index, bitmap)
                        }
                    } catch (e: Exception) {
                        ml.melun.mangaview.report.CrashReporter.record(e)
                    } finally {
                        gate.release()
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
        if (!manga.isOnline) return File(images[index])
        val file = File(cacheDir, "page_$index.img")
        if (file.length() > 0L) return file
        val image = images[index]
        val requestBuilder = Request.Builder().url(Utils.viewerImageRequestUrl(image, manga.baseMode))
        val headers = Utils.viewerImageRequestHeaders(image, manga.baseMode)
        for (entry in headers.entries) requestBuilder.addHeader(entry.key, entry.value)
        getHttpClient().imageClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Image request failed: ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty image body")
            FileOutputStream(file).use { out -> body.byteStream().copyTo(out) }
        }
        return file
    }

    private fun decodePage(index: Int, file: File, targetWidth: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } else {
            decodeLocal(images[index], bounds)
        }
        val sample = sampleSize(bounds.outWidth, targetWidth)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sample
        }
        val raw = if (manga.isOnline || file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } else {
            decodeLocal(images[index], options)
        }
            ?: throw java.io.IOException("Bitmap decode failed")
        if (!manga.isOnline) return raw
        val decoded = Decoder(manga.seed, manga.id).decode(raw, targetWidth)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        return decoded
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
        return if (busy) {
            minOf(viewerWidth, ReaderPipelinePolicy.BUSY_DECODE_WIDTH)
        } else {
            minOf(max(1, viewerWidth), ReaderPipelinePolicy.IDLE_DECODE_WIDTH)
        }
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

    private fun postMessage(message: String) {
        main.post { if (!cancelled.get()) listener.onMessage(message) }
    }
}
