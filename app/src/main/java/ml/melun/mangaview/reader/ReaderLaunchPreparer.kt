package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.Utils
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.repository.MangaRepository
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max

object ReaderLaunchPreparer {
    const val EXTRA_PREPARED_KEY = "ml.melun.mangaview.reader.PREPARED_KEY"
    private const val MAX_PREPARED_PAGES = 16
    private const val PREPARE_PARALLELISM = 3

    @JvmStatic
    fun prepareFirstFrame(
        context: Context,
        manga: Manga,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        val appContext = context.applicationContext
        attachTitle(manga, title)
        var urls = MangaRepository.imageUrls(manga, appContext)
        if (manga.isOnline && urls.isNullOrEmpty()) {
            val result = MangaRepository.fetchViewerInitial(manga, MangaRepository.cancellation())
            if (result != Title.LOAD_OK) return null
            urls = MangaRepository.imageUrls(manga, appContext)
        } else if (!manga.isOnline && urls.isNullOrEmpty()) {
            urls = MangaRepository.imageUrls(manga, appContext)
        }
        if (urls.isNullOrEmpty()) return null
        val startPage = resolveStartPage(manga, urls.size, exactEpisode)
        val bitmaps = LinkedHashMap<Int, Bitmap>()
        val decodeWidth = minOf(max(1, viewerWidth), ReaderPipelinePolicy.IDLE_DECODE_WIDTH)
        bitmaps[startPage] = decodeFirstFrame(appContext, manga, urls[startPage], decodeWidth)
        prepareAdjacentFrames(appContext, manga, urls, startPage, decodeWidth, bitmaps)
        return ReaderPreparedStore.put(
            ReaderPreparedStore.Entry(
                manga = manga,
                title = title ?: manga.title,
                images = urls,
                startPage = startPage,
                bitmaps = bitmaps
            )
        )
    }

    private fun prepareAdjacentFrames(
        context: Context,
        manga: Manga,
        urls: List<String>,
        startPage: Int,
        decodeWidth: Int,
        bitmaps: LinkedHashMap<Int, Bitmap>
    ) {
        val order = decodeOrder(startPage, urls.size)
            .asSequence()
            .filter { it != startPage }
            .take(MAX_PREPARED_PAGES - bitmaps.size)
            .toList()
        if (order.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(PREPARE_PARALLELISM, order.size))
        try {
            val futures = order.map { index ->
                pool.submit(Callable { index to decodeFirstFrame(context, manga, urls[index], decodeWidth) })
            }
            for (future in futures) {
                val (index, bitmap) = future.get()
                bitmaps[index] = bitmap
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun decodeOrder(startPage: Int, count: Int): List<Int> {
        val result = ArrayList<Int>(count)
        for (i in startPage until count) result.add(i)
        for (i in startPage - 1 downTo 0) result.add(i)
        return result
    }

    private fun attachTitle(manga: Manga, title: Title?) {
        if (title == null) return
        manga.title = title
        manga.titleId = title.id
    }

    private fun resolveStartPage(manga: Manga, count: Int, exactEpisode: Boolean): Int {
        if (count <= 0 || exactEpisode) return 0
        val page = if (manga.useBookmark() && p != null) p.getViewerBookmark(manga) else 0
        return page.coerceIn(0, count - 1)
    }

    private fun decodeFirstFrame(context: Context, manga: Manga, image: String, width: Int): Bitmap {
        val source = if (manga.isOnline) onlineImageFile(context, manga, image) else null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (source != null) {
            BitmapFactory.decodeFile(source.absolutePath, bounds)
        } else {
            decodeLocal(context, image, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize(bounds.outWidth, width)
        }
        val raw = if (source != null) {
            BitmapFactory.decodeFile(source.absolutePath, options)
        } else {
            decodeLocal(context, image, options)
        } ?: throw java.io.IOException("First frame decode failed")
        if (!manga.isOnline) return raw
        val decoded = Decoder(manga.seed, manga.id).decode(raw, width)
        if (decoded !== raw && !raw.isRecycled) raw.recycle()
        return decoded
    }

    private fun decodeLocal(context: Context, image: String, options: BitmapFactory.Options): Bitmap? {
        val uri = Uri.parse(image)
        if (!uri.scheme.isNullOrEmpty()) {
            return context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
        return BitmapFactory.decodeFile(image, options)
    }

    private fun onlineImageFile(context: Context, manga: Manga, image: String): File {
        val file = File(context.cacheDir, "reader_launch_${System.nanoTime()}.img")
        val requestBuilder = Request.Builder().url(Utils.viewerImageRequestUrl(image, manga.baseMode))
        for (entry in Utils.viewerImageRequestHeaders(image, manga.baseMode).entries) {
            requestBuilder.addHeader(entry.key, entry.value)
        }
        getHttpClient().imageClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Image request failed: ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty image body")
            FileOutputStream(file).use { out -> body.byteStream().copyTo(out) }
        }
        return file
    }

    private fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return max(1, sample)
    }
}
