package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.Utils
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.repository.MangaRepository
import ml.melun.mangaview.runtime.AppDispatchers
import ml.melun.mangaview.runtime.BackgroundPrefetchBudget
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object ReaderWarmupCoordinator {
    enum class WarmupProfile {
        URL_ONLY,
        FIRST_BYTE,
        FIRST_BITMAP,
        ADJACENT_BYTES,
        LAUNCH_WINDOW
    }

    private const val DEFAULT_LAUNCH_WINDOW_DECODE_PAGES = 3
    private const val DEFAULT_LAUNCH_WINDOW_BYTE_PAGES = 16
    private const val NTK_LAUNCH_WINDOW_DECODE_PAGES = 3
    private const val NTK_LAUNCH_WINDOW_BYTE_PAGES = 14
    private const val WFWF_LAUNCH_WINDOW_DECODE_PAGES = 3
    private const val WFWF_LAUNCH_WINDOW_BYTE_PAGES = 18
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val entryLocks = Array(4096) { Any() }

    private data class SourcePreloadProfile(
        val visibleProfile: WarmupProfile,
        val exactVisibleProfile: WarmupProfile,
        val tapProfile: WarmupProfile,
        val launchDecodePages: Int,
        val launchBytePages: Int,
        val adjacentBytePages: Int
    )

    @JvmStatic
    fun openKey(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        val profile = launchProfile(title ?: manga?.title)
        val entry = createEntry(context, manga, title, viewerWidth, exactEpisode, profile) ?: return null
        schedule(context!!.applicationContext, entry, exactEpisode, profile)
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
        val entry = ReaderPreparedStore.get(key) ?: run {
            ml.melun.mangaview.glide.ViewerWarmupManager.logMetric("prepared_miss_no_entry", 1L)
            return null
        }
        val snapshot = entry.snapshot()
        val startBitmap = snapshot.bitmaps[snapshot.startPage]
        val hasStartBitmap = startBitmap != null && !startBitmap.isRecycled
        if (hasStartBitmap) ml.melun.mangaview.glide.ViewerWarmupManager.logMetric("prepared_ready_bitmap_hit", 1L)
        else ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "prepared_miss_" + snapshot.status.name.lowercase(Locale.ROOT),
            1L
        )
        return if (hasStartBitmap) key else null
    }

    @JvmStatic
    fun primeVisible(context: Context?, manga: Manga?, title: Title?) {
        val profile = visibleContinueProfile(title ?: manga?.title)
        val entry = createEntry(context, manga, title, 0, false, profile) ?: return
        schedule(context!!.applicationContext, entry, false, profile)
    }

    @JvmStatic
    fun primeImmediate(context: Context?, manga: Manga?, title: Title?) {
        val profile = launchProfile(title ?: manga?.title)
        val entry = createEntry(context, manga, title, 0, false, profile) ?: return
        BackgroundPrefetchBudget.suppressForUserNavigation()
        schedule(context!!.applicationContext, entry, false, profile)
    }

    @JvmStatic
    fun primeExactVisible(context: Context?, manga: Manga?, title: Title?) {
        val profile = exactVisibleProfile(title ?: manga?.title)
        val entry = createEntry(context, manga, title, 0, true, profile) ?: return
        schedule(context!!.applicationContext, entry, true, profile)
    }

    @JvmStatic
    fun primeExactImmediate(context: Context?, manga: Manga?, title: Title?) {
        val profile = launchProfile(title ?: manga?.title)
        val entry = createEntry(context, manga, title, 0, true, profile) ?: return
        BackgroundPrefetchBudget.suppressForUserNavigation()
        schedule(context!!.applicationContext, entry, true, profile)
    }

    @JvmStatic
    fun primeAdjacent(context: Context?, manga: Manga?, title: Title?) {
        val profile = if (p != null && p.getDataSave()) WarmupProfile.FIRST_BYTE else WarmupProfile.LAUNCH_WINDOW
        val entry = createEntry(context, manga, title, 0, true, profile) ?: return
        schedule(context!!.applicationContext, entry, true, profile)
    }

    @JvmStatic
    fun prepareBlocking(
        context: Context,
        manga: Manga,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        val profile = tapProfile(title ?: manga.title)
        val entry = createEntry(context, manga, title, viewerWidth, exactEpisode, profile) ?: return null
        BackgroundPrefetchBudget.suppressForUserNavigation()
        prepareEntry(context.applicationContext, entry, exactEpisode, profile)
        return readyKey(context, manga, title, viewerWidth, exactEpisode)
    }

    private fun createEntry(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean,
        profile: WarmupProfile
    ): ReaderPreparedStore.Entry? {
        if (context == null || manga == null) return null
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val width = normalizeWidth(context, viewerWidth)
        val startPage = requestedStartPage(manga, exactEpisode)
        val key = stableKey(manga, launchTitle, startPage, width, exactEpisode)
        return ReaderPreparedStore.createOrGet(key, manga, launchTitle, startPage, width, shouldPinStart(profile))
    }

    private fun schedule(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        exactEpisode: Boolean,
        profile: WarmupProfile
    ) {
        val snapshot = entry.snapshot()
        val immediate = profile == WarmupProfile.LAUNCH_WINDOW
        if (snapshot.status == ReaderPreparedStore.Status.WINDOW_READY ||
            snapshot.status == ReaderPreparedStore.Status.FIRST_BITMAP_READY && !immediate ||
            snapshot.status == ReaderPreparedStore.Status.URLS_READY && profile == WarmupProfile.URL_ONLY ||
            snapshot.status == ReaderPreparedStore.Status.BYTES_READY && isByteReadyProfile(profile)
        ) {
            return
        }
        val immediateFlag = AtomicBoolean(immediate)
        val existing = inFlight.putIfAbsent(entry.key, immediateFlag)
        if (existing != null) {
            if (immediate && existing.compareAndSet(false, true)) {
                AppDispatchers.submitUserAction {
                    prepareEntry(appContext, entry, exactEpisode, WarmupProfile.LAUNCH_WINDOW)
                }
            }
            return
        }
        val task = Runnable {
            try {
                prepareEntry(appContext, entry, exactEpisode, profile, immediateFlag)
            } finally {
                inFlight.remove(entry.key, immediateFlag)
            }
        }
        if (immediate) AppDispatchers.submitUserAction(task) else AppDispatchers.submitImageWarmup(task)
    }

    private fun prepareEntry(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        exactEpisode: Boolean,
        profile: WarmupProfile,
        launchRequested: AtomicBoolean = AtomicBoolean(profile == WarmupProfile.LAUNCH_WINDOW)
    ) {
        val lock = lockForEntry(entry.key)
        synchronized(lock) {
        var effectiveProfile = profile
        try {
            val manga = entry.manga
            attachTitle(manga, entry.title)
            effectiveProfile = if (launchRequested.get()) WarmupProfile.LAUNCH_WINDOW else profile
            val status = entry.snapshot().status
            if (status == ReaderPreparedStore.Status.WINDOW_READY ||
                status == ReaderPreparedStore.Status.FIRST_BITMAP_READY && effectiveProfile != WarmupProfile.LAUNCH_WINDOW ||
                status == ReaderPreparedStore.Status.URLS_READY &&
                effectiveProfile == WarmupProfile.URL_ONLY ||
                status == ReaderPreparedStore.Status.BYTES_READY && isByteReadyProfile(effectiveProfile)
            ) {
                return
            }
            var urls = MangaRepository.imageUrls(manga, appContext)
            if (manga.isOnline && urls.isNullOrEmpty()) {
                val result = fetchViewerInitialForProfile(manga, effectiveProfile)
                if (result != Title.LOAD_OK) {
                    ml.melun.mangaview.glide.ViewerWarmupManager.logMetric("prepared_warmup_soft_fail", result.toLong())
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
            warmImagesForProfile(appContext, entry, manga, urls, startPage, width, effectiveProfile)
            if (effectiveProfile != WarmupProfile.LAUNCH_WINDOW && launchRequested.get())
                warmImagesForProfile(appContext, entry, manga, urls, startPage, width, WarmupProfile.LAUNCH_WINDOW)
        } catch (e: Exception) {
            if (isSpeculativeByteProfile(effectiveProfile)) {
                ml.melun.mangaview.glide.ViewerWarmupManager.logMetric("prepared_warmup_soft_exception", 1L)
                return
            }
            ml.melun.mangaview.report.CrashReporter.record(e)
            entry.fail()
        }
        }
    }

    private fun fetchViewerInitialForProfile(manga: Manga, profile: WarmupProfile): Int {
        val cancellation = MangaRepository.cancellation()
        if (profile == WarmupProfile.LAUNCH_WINDOW) {
            cancellation.prioritizeWebViewFallback()
            return MangaRepository.fetchViewerInitial(manga, cancellation)
        }
        val client = getHttpClient()
        return client?.runWithFetchMode(CustomHttpClient.FetchMode.DIRECT_ONLY) {
            MangaRepository.fetchViewerInitial(manga, cancellation)
        } ?: MangaRepository.fetchViewerInitial(manga, cancellation)
    }

    private fun isSpeculativeByteProfile(profile: WarmupProfile): Boolean {
        return profile == WarmupProfile.FIRST_BYTE || profile == WarmupProfile.ADJACENT_BYTES
    }

    private fun isByteReadyProfile(profile: WarmupProfile): Boolean {
        return profile == WarmupProfile.URL_ONLY ||
            profile == WarmupProfile.FIRST_BYTE ||
            profile == WarmupProfile.ADJACENT_BYTES
    }

    private fun lockForEntry(key: String): Any {
        return entryLocks[(key.hashCode() and Int.MAX_VALUE) % entryLocks.size]
    }

    private fun warmImagesForProfile(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        manga: Manga,
        urls: List<String>,
        startPage: Int,
        width: Int,
        profile: WarmupProfile
    ) {
        when (profile) {
            WarmupProfile.URL_ONLY -> return
            WarmupProfile.FIRST_BYTE -> {
                fetchImageFile(appContext, manga, urls[startPage])
                entry.markBytesReady()
                return
            }
            WarmupProfile.FIRST_BITMAP -> {
                val bitmap = decodePage(appContext, manga, urls[startPage], width)
                entry.putBitmap(startPage, bitmap, true, false)
                return
            }
            WarmupProfile.ADJACENT_BYTES -> {
                val byteOrder = decodeOrder(startPage, urls.size, sourceProfile(entry.title ?: manga.title).adjacentBytePages)
                for (index in byteOrder) fetchImageFile(appContext, manga, urls[index])
                entry.markBytesReady()
                return
            }
            WarmupProfile.LAUNCH_WINDOW -> {
                val sourceProfile = sourceProfile(entry.title ?: manga.title)
                val decodeOrder = decodeOrder(startPage, urls.size, sourceProfile.launchDecodePages)
                val decoded = HashSet<Int>()
                for ((position, index) in decodeOrder.withIndex()) {
                    val bitmap = decodePage(appContext, manga, urls[index], width)
                    decoded.add(index)
                    entry.putBitmap(index, bitmap, index == startPage, position == decodeOrder.lastIndex)
                }
                val byteOrder = decodeOrder(startPage, urls.size, sourceProfile.launchBytePages)
                for (index in byteOrder) {
                    if (!decoded.contains(index)) fetchImageFile(appContext, manga, urls[index])
                }
            }
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

    private fun visibleContinueProfile(title: Title?): WarmupProfile {
        if (p != null && p.getDataSave()) return WarmupProfile.URL_ONLY
        return sourceProfile(title).visibleProfile
    }

    private fun exactVisibleProfile(title: Title?): WarmupProfile {
        if (p != null && p.getDataSave()) return WarmupProfile.URL_ONLY
        return sourceProfile(title).exactVisibleProfile
    }

    private fun tapProfile(title: Title?): WarmupProfile {
        if (p != null && p.getDataSave()) return WarmupProfile.URL_ONLY
        return sourceProfile(title).tapProfile
    }

    private fun launchProfile(title: Title?): WarmupProfile {
        if (p != null && p.getDataSave()) return tapProfile(title)
        return WarmupProfile.LAUNCH_WINDOW
    }

    private fun shouldPinStart(profile: WarmupProfile): Boolean {
        return profile == WarmupProfile.FIRST_BITMAP || profile == WarmupProfile.LAUNCH_WINDOW
    }

    private fun sourceProfile(title: Title?): SourcePreloadProfile {
        return sourceProfile(title?.sourceSite)
    }

    private fun sourceProfile(sourceSite: String?): SourcePreloadProfile {
        return when ((sourceSite ?: "").trim().lowercase(Locale.ROOT)) {
            "ntk" -> SourcePreloadProfile(
                visibleProfile = WarmupProfile.URL_ONLY,
                exactVisibleProfile = WarmupProfile.FIRST_BYTE,
                tapProfile = WarmupProfile.FIRST_BITMAP,
                launchDecodePages = NTK_LAUNCH_WINDOW_DECODE_PAGES,
                launchBytePages = NTK_LAUNCH_WINDOW_BYTE_PAGES,
                adjacentBytePages = 8
            )
            "wfwf" -> SourcePreloadProfile(
                visibleProfile = WarmupProfile.URL_ONLY,
                exactVisibleProfile = WarmupProfile.FIRST_BYTE,
                tapProfile = WarmupProfile.FIRST_BITMAP,
                launchDecodePages = WFWF_LAUNCH_WINDOW_DECODE_PAGES,
                launchBytePages = WFWF_LAUNCH_WINDOW_BYTE_PAGES,
                adjacentBytePages = 10
            )
            else -> SourcePreloadProfile(
                visibleProfile = WarmupProfile.URL_ONLY,
                exactVisibleProfile = WarmupProfile.FIRST_BYTE,
                tapProfile = WarmupProfile.FIRST_BITMAP,
                launchDecodePages = DEFAULT_LAUNCH_WINDOW_DECODE_PAGES,
                launchBytePages = DEFAULT_LAUNCH_WINDOW_BYTE_PAGES,
                adjacentBytePages = 10
            )
        }
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

    private fun fetchImageFile(context: Context, manga: Manga, image: String): File? {
        return if (manga.isOnline) ReaderImageCache.getOrFetchFile(context, manga, image) else null
    }

    private fun decodePage(context: Context, manga: Manga, image: String, width: Int): Bitmap {
        val source = fetchImageFile(context, manga, image)
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

    private fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return max(1, sample)
    }

    @JvmStatic
    fun decodeLimitForTest(profile: WarmupProfile): Int {
        return when (profile) {
            WarmupProfile.URL_ONLY, WarmupProfile.FIRST_BYTE, WarmupProfile.ADJACENT_BYTES -> 0
            WarmupProfile.FIRST_BITMAP -> 1
            WarmupProfile.LAUNCH_WINDOW -> DEFAULT_LAUNCH_WINDOW_DECODE_PAGES
        }
    }

    @JvmStatic
    fun byteLimitForTest(profile: WarmupProfile): Int {
        return when (profile) {
            WarmupProfile.URL_ONLY -> 0
            WarmupProfile.FIRST_BYTE, WarmupProfile.FIRST_BITMAP -> 1
            WarmupProfile.ADJACENT_BYTES -> 5
            WarmupProfile.LAUNCH_WINDOW -> DEFAULT_LAUNCH_WINDOW_BYTE_PAGES
        }
    }

    @JvmStatic
    fun launchDecodeLimitForTest(sourceSite: String?): Int {
        return sourceProfile(sourceSite).launchDecodePages
    }

    @JvmStatic
    fun launchByteLimitForTest(sourceSite: String?): Int {
        return sourceProfile(sourceSite).launchBytePages
    }

    @JvmStatic
    fun visibleProfileForTest(sourceSite: String?): WarmupProfile {
        return sourceProfile(sourceSite).visibleProfile
    }

    @JvmStatic
    fun tapProfileForTest(sourceSite: String?): WarmupProfile {
        return sourceProfile(sourceSite).tapProfile
    }

    @JvmStatic
    fun launchProfileForTest(dataSave: Boolean): WarmupProfile {
        return if (dataSave) WarmupProfile.URL_ONLY else WarmupProfile.LAUNCH_WINDOW
    }

    @JvmStatic
    fun adjacentByteLimitForTest(sourceSite: String?): Int {
        return sourceProfile(sourceSite).adjacentBytePages
    }
}
