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
import ml.melun.mangaview.runtime.AppDispatchers
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object ReaderWarmupCoordinator {
    private const val VISIBLE_WINDOW_PAGES = 12
    private const val IMMEDIATE_WINDOW_PAGES = 16
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun openKey(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        val entry = createEntry(context, manga, title, viewerWidth, exactEpisode) ?: return null
        schedule(context!!.applicationContext, entry, exactEpisode, true)
        return entry.key
    }

    @JvmStatic
    fun readyKey(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        if (context == null || manga == null) return null
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val width = normalizeWidth(context, viewerWidth)
        val startPage = requestedStartPage(manga, exactEpisode)
        val key = stableKey(manga, launchTitle, startPage, width, exactEpisode)
        val entry = ReaderPreparedStore.get(key) ?: return null
        val snapshot = entry.snapshot()
        return if (!snapshot.images.isNullOrEmpty() || snapshot.bitmaps.isNotEmpty()) key else null
    }

    @JvmStatic
    fun primeVisible(context: Context?, manga: Manga?, title: Title?) {
        val entry = createEntry(context, manga, title, 0, false) ?: return
        schedule(context!!.applicationContext, entry, false, false)
    }

    @JvmStatic
    fun primeImmediate(context: Context?, manga: Manga?, title: Title?) {
        val entry = createEntry(context, manga, title, 0, false) ?: return
        schedule(context!!.applicationContext, entry, false, true)
    }

    @JvmStatic
    fun primeExactVisible(context: Context?, manga: Manga?, title: Title?) {
        val entry = createEntry(context, manga, title, 0, true) ?: return
        schedule(context!!.applicationContext, entry, true, false)
    }

    @JvmStatic
    fun primeExactImmediate(context: Context?, manga: Manga?, title: Title?) {
        val entry = createEntry(context, manga, title, 0, true) ?: return
        schedule(context!!.applicationContext, entry, true, true)
    }

    @JvmStatic
    fun prepareBlocking(
        context: Context,
        manga: Manga,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        val entry = createEntry(context, manga, title, viewerWidth, exactEpisode) ?: return null
        prepareEntry(context.applicationContext, entry, exactEpisode, true)
        return entry.key
    }

    private fun createEntry(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): ReaderPreparedStore.Entry? {
        if (context == null || manga == null) return null
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val width = normalizeWidth(context, viewerWidth)
        val startPage = requestedStartPage(manga, exactEpisode)
        val key = stableKey(manga, launchTitle, startPage, width, exactEpisode)
        return ReaderPreparedStore.createOrGet(key, manga, launchTitle, startPage, width)
    }

    private fun schedule(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        exactEpisode: Boolean,
        immediate: Boolean
    ) {
        val snapshot = entry.snapshot()
        if (snapshot.status == ReaderPreparedStore.Status.WINDOW_READY ||
            snapshot.status == ReaderPreparedStore.Status.FIRST_BITMAP_READY && !immediate
        ) {
            return
        }
        val flightKey = entry.key + if (immediate) ":immediate" else ":visible"
        if (!inFlight.add(flightKey)) return
        val task = Runnable {
            try {
                prepareEntry(appContext, entry, exactEpisode, immediate)
            } finally {
                inFlight.remove(flightKey)
            }
        }
        if (immediate) AppDispatchers.submitUserAction(task) else AppDispatchers.submitImageWarmup(task)
    }

    private fun prepareEntry(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        exactEpisode: Boolean,
        immediate: Boolean
    ) {
        try {
            val manga = entry.manga
            attachTitle(manga, entry.title)
            var urls = MangaRepository.imageUrls(manga, appContext)
            if (manga.isOnline && urls.isNullOrEmpty()) {
                val result = MangaRepository.fetchViewerInitial(manga, MangaRepository.cancellation())
                if (result != Title.LOAD_OK) {
                    entry.fail()
                    return
                }
                urls = MangaRepository.imageUrls(manga, appContext)
            } else if (!manga.isOnline && urls.isNullOrEmpty()) {
                urls = MangaRepository.imageUrls(manga, appContext)
            }
            if (urls.isNullOrEmpty()) {
                entry.fail()
                return
            }
            val startPage = entry.requestedStartPage.coerceIn(0, urls.lastIndex)
            entry.setImages(urls, startPage)
            val width = max(1, entry.requestedWidth)
            val order = decodeOrder(startPage, urls.size, if (immediate) IMMEDIATE_WINDOW_PAGES else VISIBLE_WINDOW_PAGES)
            for ((position, index) in order.withIndex()) {
                val bitmap = decodePage(appContext, manga, urls[index], width)
                entry.putBitmap(index, bitmap, index == startPage, position == order.lastIndex)
            }
        } catch (e: Exception) {
            ml.melun.mangaview.report.CrashReporter.record(e)
            entry.fail()
        }
    }

    private fun decodeOrder(startPage: Int, count: Int, limit: Int): List<Int> {
        if (count <= 0 || limit <= 0) return emptyList()
        val result = ArrayList<Int>(minOf(count, limit))
        fun add(index: Int) {
            if (index >= 0 && index < count && result.size < limit && !result.contains(index)) result.add(index)
        }
        add(startPage)
        var distance = 1
        while (result.size < limit && (startPage + distance < count || startPage - distance >= 0)) {
            add(startPage + distance)
            add(startPage - distance)
            distance++
        }
        return result
    }

    private fun attachTitle(manga: Manga, title: Title?) {
        if (title == null) return
        manga.title = title
        manga.titleId = title.id
        val episodes = Utils.snapshotEpisodes(title)
        if (episodes.isNotEmpty()) manga.setEps(episodes)
    }

    private fun requestedStartPage(manga: Manga, exactEpisode: Boolean): Int {
        if (exactEpisode) return 0
        val page = if (manga.useBookmark() && p != null) p.getViewerBookmark(manga) else 0
        return max(0, page)
    }

    private fun normalizeWidth(context: Context, viewerWidth: Int): Int {
        if (viewerWidth > 0) return viewerWidth
        return max(1, context.resources.displayMetrics.widthPixels)
    }

    private fun stableKey(
        manga: Manga,
        title: Title?,
        startPage: Int,
        width: Int,
        exactEpisode: Boolean
    ): String {
        val source = (title?.sourceSite ?: "").trim().lowercase(Locale.ROOT)
        val path = manga.ntkEpisodePath ?: ""
        val titleId = title?.id ?: manga.titleId
        return "reader:$source:${manga.baseMode}:$titleId:${manga.id}:${path.lowercase(Locale.ROOT)}:$startPage:$width:$exactEpisode"
    }

    private fun decodePage(context: Context, manga: Manga, image: String, width: Int): Bitmap {
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
        } ?: throw java.io.IOException("Bitmap decode failed")
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
        val dir = File(context.cacheDir, "reader_warmup").apply { mkdirs() }
        val file = File(dir, "${System.nanoTime()}_${image.hashCode()}.img")
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
