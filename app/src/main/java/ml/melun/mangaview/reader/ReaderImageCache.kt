package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.Utils
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object ReaderImageCache {
    private const val TAG = "ViewerPerf"
    private const val DIR_NAME = "reader_image_cache_v1"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val TARGET_CACHE_BYTES = 384L * 1024L * 1024L
    private const val TRIM_DEBOUNCE_MS = 30_000L
    private const val FOREGROUND_RACE_DELAY_MS = 120L
    private const val FOREGROUND_RACE_ATTEMPTS = 2
    private const val FOREGROUND_STREAM_RACE_ATTEMPTS = 2
    private const val FOREGROUND_STREAM_JOIN_TIMEOUT_MS = 900L
    private const val FOREGROUND_STREAM_HANDOFF_TTL_MS = 2500L
    private const val EARLY_NTK_IMAGE_URL_TTL_MS = 6000L
    private const val EARLY_NTK_IMAGE_URL_STARTED_SKEW_MS = 1200L
    private const val MAX_DIRECT_STREAM_DECODE_BYTES = 16L * 1024L * 1024L
    private const val MAX_DIRECT_STREAM_BITMAP_BYTES = 2L * 1024L * 1024L
    private const val DIRECT_STREAM_TILE_ASPECT_RATIO = 3.0f
    private const val DIRECT_STREAM_TILE_MIN_ESTIMATED_BYTES = 12L * 1024L * 1024L
    private const val FULL_DECODE_VALIDATION_MAX_BYTES = 256L * 1024L
    private const val NTK_GENERATED_FETCH_PARALLELISM = 4
    private val flights = ConcurrentHashMap<String, FutureTask<File>>()
    private val foregroundStreams = ConcurrentHashMap<String, FutureTask<ByteArray?>>()
    private val earlyNtkImageUrls = ConcurrentHashMap<String, EarlyNtkImageUrls>()
    private val ntkApiFallbackFlights = ConcurrentHashMap<String, FutureTask<List<String>?>>()
    private val ntkApiFallbackImages = ConcurrentHashMap<String, List<String>>()
    private val ntkGeneratedAckRecoveryFlights = ConcurrentHashMap<String, FutureTask<Boolean>>()
    private val ntkGeneratedEpisodeExtensions = ConcurrentHashMap<String, String>()
    private val activeReads = ConcurrentHashMap<String, AtomicInteger>()
    private val ntkGeneratedFetchGate = Semaphore(NTK_GENERATED_FETCH_PARALLELISM)
    private val trimLock = Any()
    private val trimScheduled = AtomicBoolean(false)
    private val trimDirty = AtomicBoolean(false)
    private val lastTrimStartedAt = AtomicLong(0L)
    private val trimExecutor = Executors.newSingleThreadScheduledExecutor(ThreadFactory { runnable ->
        Thread(runnable, "ReaderImageCacheTrim").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    })
    private val foregroundRaceExecutor = Executors.newCachedThreadPool(ThreadFactory { runnable ->
        Thread(runnable, "ReaderImageForegroundRace").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    })
    private val ntkApiFallbackExecutor = Executors.newFixedThreadPool(2, ThreadFactory { runnable ->
        Thread(runnable, "ReaderNtkApiFallback").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    })

    private data class EarlyNtkImageUrls(
        val urls: List<String>,
        val createdAtMs: Long
    )

    class FileLease internal constructor(
        val file: File,
        private val key: String?
    ) : AutoCloseable {
        override fun close() {
            if (key != null) releaseActiveRead(key)
        }
    }

    class Cancellation {
        private val cancelled = AtomicBoolean(false)
        private val calls = ConcurrentHashMap.newKeySet<Call>()

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            for (call in calls) call.cancel()
        }

        fun throwIfCancelled() {
            if (cancelled.get()) throw java.io.InterruptedIOException("Reader image request cancelled")
        }

        fun track(call: Call) {
            if (cancelled.get()) {
                call.cancel()
                throw java.io.InterruptedIOException("Reader image request cancelled")
            }
            calls.add(call)
            if (cancelled.get()) call.cancel()
        }

        fun untrack(call: Call) {
            calls.remove(call)
        }
    }

    fun leaseFile(context: Context, manga: Manga, image: String, foreground: Boolean = true): FileLease {
        return leaseFile(context, manga, image, foreground, null)
    }

    fun leaseFile(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean = true,
        cancellation: Cancellation?
    ): FileLease {
        if (!manga.isOnline) return FileLease(File(image), null)
        cancellation?.throwIfCancelled()
        val key = key(manga.baseMode, image)
        retainActiveRead(key)
        return try {
            val file = getOrFetch(context, manga, image, foreground = foreground, cancellation = cancellation)
            file.setLastModified(System.currentTimeMillis())
            FileLease(file, key)
        } catch (t: Throwable) {
            releaseActiveRead(key)
            throw t
        }
    }

    private fun retainActiveRead(key: String) {
        activeReads.compute(key) { _, count ->
            (count ?: AtomicInteger(0)).also { it.incrementAndGet() }
        }
    }

    private fun releaseActiveRead(key: String) {
        activeReads.computeIfPresent(key) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    fun getOrFetchFile(context: Context, manga: Manga, image: String): File {
        return getOrFetchFile(context, manga, image, null)
    }

    fun getOrFetchFile(context: Context, manga: Manga, image: String, cancellation: Cancellation?): File {
        if (!manga.isOnline) return File(image)
        cancellation?.throwIfCancelled()
        return getOrFetch(context, manga, image, cancellation = cancellation)
    }

    fun getOrFetchFileForeground(context: Context, manga: Manga, image: String): File {
        return getOrFetchFileForeground(context, manga, image, null)
    }

    fun getOrFetchFileForeground(context: Context, manga: Manga, image: String, cancellation: Cancellation?): File {
        if (!manga.isOnline) return File(image)
        cancellation?.throwIfCancelled()
        return getOrFetch(context, manga, image, foreground = true, cancellation = cancellation)
    }

    fun cachedFile(context: Context, manga: Manga, image: String): File? {
        if (!manga.isOnline) return File(image)
        val appContext = context.applicationContext
        val file = File(cacheDir(appContext), "${key(manga.baseMode, image)}.img")
        return if (isUsableImage(file)) file else null
    }

    fun hasActiveFetch(manga: Manga, image: String): Boolean {
        if (!manga.isOnline) return false
        val key = key(manga.baseMode, image)
        return flights.containsKey(key) || foregroundStreams.containsKey(key)
    }

    @JvmStatic
    fun clearNtkGeneratedEpisodeExtensionHintsForTest() {
        ntkGeneratedEpisodeExtensions.clear()
    }

    fun rememberEarlyNtkImageUrls(path: String?, urls: List<String>?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || urls.isNullOrEmpty()) return
        val trusted = urls.mapNotNull { it?.trim()?.takeIf { value -> isTrustedNtkImageUrl(value) } }
        if (trusted.isEmpty()) return
        earlyNtkImageUrls[key] = EarlyNtkImageUrls(Collections.unmodifiableList(ArrayList(trusted)), SystemClock.elapsedRealtime())
        Log.d(TAG, "reader_early_ntk_urls_remember path=$key,count=${trusted.size},first=${safeImageName(trusted.firstOrNull())}")
    }

    fun earlyNtkImageUrls(path: String?, minCreatedAtMs: Long): List<String> {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return emptyList()
        val entry = earlyNtkImageUrls[key] ?: return emptyList()
        val ageMs = SystemClock.elapsedRealtime() - entry.createdAtMs
        if (entry.createdAtMs + EARLY_NTK_IMAGE_URL_STARTED_SKEW_MS < minCreatedAtMs ||
            ageMs > EARLY_NTK_IMAGE_URL_TTL_MS
        ) {
            earlyNtkImageUrls.remove(key, entry)
            return emptyList()
        }
        return entry.urls
    }

    fun startForegroundStreamFetch(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation? = null,
        anchorHedge: Boolean = false
    ): Boolean {
        if (!manga.isOnline) return false
        cancellation?.throwIfCancelled()
        val appContext = context.applicationContext
        if (cachedFile(appContext, manga, image) != null) return false
        val key = key(manga.baseMode, image)
        if (flights.containsKey(key)) return false
        val startedAt = SystemClock.elapsedRealtime()
        val task = FutureTask<ByteArray?> {
            try {
                val bytes = fetchForegroundBytes(
                    appContext,
                    manga,
                    image,
                    cancellation,
                    startedAt,
                    FOREGROUND_STREAM_RACE_ATTEMPTS,
                    anchorHedge
                )
                logCacheEvent(
                    "foreground_stream_async_done",
                    manga,
                    image,
                    true,
                    "ms=${SystemClock.elapsedRealtime() - startedAt},bytes=${bytes?.size ?: 0}"
                )
                ViewerWarmupManager.logMetric("reader_foreground_stream_async_done_ms", SystemClock.elapsedRealtime() - startedAt)
                bytes
            } catch (t: Throwable) {
                logCacheEvent(
                    "foreground_stream_async_error",
                    manga,
                    image,
                    true,
                    "ms=${SystemClock.elapsedRealtime() - startedAt},error=${t.javaClass.simpleName}"
                )
                null
            }
        }
        val existing = foregroundStreams.putIfAbsent(key, task)
        if (existing != null) {
            if (existing.isDone && foregroundStreams.remove(key, existing)) {
                return startForegroundStreamFetch(context, manga, image, cancellation, anchorHedge)
            }
            logCacheEvent("foreground_stream_async_join", manga, image, true, "activeStream=true")
            ViewerWarmupManager.logMetric("reader_foreground_stream_async_join", 1L)
            return false
        }
        logCacheEvent("foreground_stream_async_start", manga, image, true, "activeStream=false")
        ViewerWarmupManager.logMetric("reader_foreground_stream_async_start", 1L)
        return try {
            foregroundRaceExecutor.execute {
                try {
                    task.run()
                } finally {
                    scheduleForegroundStreamHandoffExpiry(key, task)
                }
            }
            true
        } catch (_: Exception) {
            foregroundStreams.remove(key, task)
            false
        }
    }

    fun decodeForegroundBitmap(
        context: Context,
        manga: Manga,
        image: String,
        targetWidth: Int,
        autoCut: Boolean,
        allowSplit: Boolean,
        cancellation: Cancellation? = null,
        anchorHedge: Boolean = false
    ): Bitmap? {
        if (!manga.isOnline) return null
        cancellation?.throwIfCancelled()
        val appContext = context.applicationContext
        if (cachedFile(appContext, manga, image) != null) return null
        val key = key(manga.baseMode, image)
        val startedAt = SystemClock.elapsedRealtime()
        val task = FutureTask<ByteArray?> {
            fetchForegroundBytes(appContext, manga, image, cancellation, startedAt, FOREGROUND_RACE_ATTEMPTS, anchorHedge)
        }
        val existing = foregroundStreams.putIfAbsent(key, task)
        if (existing != null) {
            ViewerWarmupManager.logMetric("reader_foreground_stream_join", 1L)
            logCacheEvent(
                "foreground_stream_join",
                manga,
                image,
                true,
                "activeStream=${!existing.isDone},doneStream=${existing.isDone}"
            )
            return try {
                decodeForegroundBytes(existing, startedAt, autoCut, allowSplit, targetWidth, boundedWait = true)
            } finally {
                foregroundStreams.remove(key, existing)
            }
        }
        try {
            task.run()
            return decodeForegroundBytes(task, startedAt, autoCut, allowSplit, targetWidth, boundedWait = false)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: ExecutionException) {
            return null
        } finally {
            foregroundStreams.remove(key, task)
        }
    }

    private fun scheduleForegroundStreamHandoffExpiry(key: String, task: FutureTask<ByteArray?>) {
        foregroundRaceExecutor.execute {
            try {
                Thread.sleep(FOREGROUND_STREAM_HANDOFF_TTL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                foregroundStreams.remove(key, task)
            }
        }
    }

    private fun fetchForegroundBytes(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        startedAt: Long,
        raceAttempts: Int = FOREGROUND_RACE_ATTEMPTS,
        anchorHedge: Boolean = false
    ): ByteArray? {
        withNtkGeneratedFetchPermit(manga, image, true) {
            requestWithNtkGeneratedFallback(
                context,
                manga,
                image,
                foreground = true,
                cancellation = cancellation,
                foregroundRaceAttempts = raceAttempts,
                anchorHedge = anchorHedge
            )
        }.use { response ->
            val headersAt = SystemClock.elapsedRealtime()
            if (!response.isSuccessful) return null
            rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
            val body = response.body ?: return null
            val contentLength = body.contentLength()
            if (contentLength > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val bytesAt = SystemClock.elapsedRealtime()
            val partialImage = response.header("x-mangaviewer-partial-image") == "1"
            if (!partialImage) {
                val cacheImage = foregroundResponseCacheImage(image, response)
                cacheForegroundBytes(context, manga, cacheImage, bytes)
                if (cacheImage != image) {
                    logCacheEvent(
                        "foreground_cached_actual_url",
                        manga,
                        cacheImage,
                        true,
                        "requested=${image.substringAfterLast('/').takeLast(64)}"
                    )
                }
            }
            ViewerWarmupManager.logMetric("reader_foreground_stream_headers_ms", headersAt - startedAt)
            ViewerWarmupManager.logMetric("reader_foreground_stream_body_ms", bytesAt - headersAt)
            ViewerWarmupManager.logMetric("reader_foreground_stream_bytes", if (contentLength >= 0L) contentLength else bytes.size.toLong())
            if (partialImage) ViewerWarmupManager.logMetric("reader_foreground_stream_partial_bytes", bytes.size.toLong())
            return bytes
        }
    }

    private fun decodeForegroundBytes(
        task: FutureTask<ByteArray?>,
        startedAt: Long,
        autoCut: Boolean,
        allowSplit: Boolean,
        targetWidth: Int,
        boundedWait: Boolean
    ): Bitmap? {
        val bytes = try {
            if (boundedWait) task.get(FOREGROUND_STREAM_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) else task.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: java.util.concurrent.TimeoutException) {
            ViewerWarmupManager.logMetric("reader_foreground_stream_join_timeout", 1L)
            return null
        } catch (_: ExecutionException) {
            return null
        } ?: return null
        val bytesAt = SystemClock.elapsedRealtime()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (shouldPreferFileDecodeAfterForegroundCache(bounds, bytes.size, autoCut)) {
            ViewerWarmupManager.logMetric("reader_foreground_stream_file_decode_preferred", bytes.size.toLong())
            return null
        }
        val decodeTargetWidth = foregroundDecodeTargetWidth(bounds.outWidth, bounds.outHeight, targetWidth, autoCut, allowSplit)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = foregroundSampleSize(bounds.outWidth, decodeTargetWidth)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val decodedAt = SystemClock.elapsedRealtime()
        ViewerWarmupManager.logMetric("reader_foreground_stream_decode_ms", decodedAt - bytesAt)
        ViewerWarmupManager.logMetric("reader_foreground_stream_total_ms", decodedAt - startedAt)
        return bitmap
    }

    private fun shouldPreferFileDecodeAfterForegroundCache(
        bounds: BitmapFactory.Options,
        byteCount: Int,
        autoCut: Boolean
    ): Boolean {
        if (autoCut || byteCount <= MAX_DIRECT_STREAM_BITMAP_BYTES) return false
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false
        val estimatedBytes = width.toLong() * height.toLong() * 2L
        return height / width.toFloat() >= DIRECT_STREAM_TILE_ASPECT_RATIO ||
            estimatedBytes >= DIRECT_STREAM_TILE_MIN_ESTIMATED_BYTES
    }

    private fun cacheForegroundBytes(context: Context, manga: Manga, image: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val finalFile = File(cacheDir(context), "${key(manga.baseMode, image)}.img")
        if (isUsableImage(finalFile)) return
        val tmp = File(finalFile.parentFile, "${finalFile.name}.fg.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!isUsableImage(tmp)) {
                tmp.delete()
                return
            }
            replace(tmp, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            logCacheEvent("foreground_cached", manga, image, true, "bytes=${finalFile.length()}")
            ViewerWarmupManager.logMetric("reader_foreground_stream_cached", 1L)
            scheduleTrim(context)
        } catch (e: Exception) {
            tmp.delete()
            logCacheEvent("foreground_cache_error", manga, image, true, "error=${e.javaClass.simpleName}")
        }
    }

    private fun foregroundResponseCacheImage(image: String, response: okhttp3.Response): String {
        val actual = response.request.url.toString()
        if (actual.isBlank() || actual == image) return image
        return actual
    }

    private data class ForegroundRaceResult(
        val attempt: ForegroundRaceAttempt,
        val call: Call,
        val response: Response
    )

    private data class ForegroundRaceAttempt(
        val transport: String,
        val client: okhttp3.OkHttpClient,
        val request: Request
    )

    private fun foregroundDecodeTargetWidth(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        autoCut: Boolean,
        allowSplit: Boolean
    ): Int {
        val safeTarget = max(1, targetWidth)
        if (!autoCut || !allowSplit || sourceWidth <= 0 || sourceHeight <= 0) return safeTarget
        return if (sourceWidth / sourceHeight.toFloat() >= 0.90f) safeTarget * 2 else safeTarget
    }

    private fun foregroundSampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return max(1, sample)
    }

    private fun getOrFetch(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean = false,
        cancellation: Cancellation? = null
    ): File {
        val appContext = context.applicationContext
        cancellation?.throwIfCancelled()
        val key = key(manga.baseMode, image)
        val finalFile = File(cacheDir(appContext), "$key.img")
        if (isUsableImage(finalFile)) {
            finalFile.setLastModified(System.currentTimeMillis())
            logCacheEvent("disk_hit", manga, image, foreground, "bytes=${finalFile.length()}")
            ViewerWarmupManager.logMetric("reader_image_cache_disk_hit", 1L)
            return finalFile
        }
        awaitForegroundStreamFile(key, finalFile, manga, image, foreground)?.let { return it }
        val task = FutureTask {
            withNtkGeneratedFetchPermit(manga, image, foreground) {
                downloadAtomically(appContext, manga, image, finalFile, foreground, cancellation)
            }
        }
        val existing = flights.putIfAbsent(key, task)
        if (existing != null) {
            logCacheEvent("flight_join", manga, image, foreground, "activeFlight=true")
            ViewerWarmupManager.logMetric("reader_image_cache_flight_join", 1L)
        } else {
            logCacheEvent("download_start", manga, image, foreground, "activeFlight=false")
            ViewerWarmupManager.logMetric("reader_image_cache_download_start", 1L)
        }
        val running = existing ?: task.also { it.run() }
        return try {
            running.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Interrupted while waiting for image cache").apply {
                initCause(e)
            }
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is Exception) throw cause
            if (cause is Error) throw cause
            throw java.io.IOException("Image cache fetch failed", cause)
        } finally {
            flights.remove(key, running)
        }
    }

    private fun awaitForegroundStreamFile(
        key: String,
        finalFile: File,
        manga: Manga,
        image: String,
        foreground: Boolean
    ): File? {
        val stream = foregroundStreams[key] ?: return null
        logCacheEvent("foreground_stream_wait", manga, image, foreground, "activeStream=true")
        ViewerWarmupManager.logMetric("reader_foreground_stream_wait", 1L)
        try {
            stream.get(FOREGROUND_STREAM_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: java.util.concurrent.TimeoutException) {
            logCacheEvent("foreground_stream_wait_timeout", manga, image, foreground, "activeStream=true")
            ViewerWarmupManager.logMetric("reader_foreground_stream_wait_timeout", 1L)
            return null
        } catch (_: ExecutionException) {
            return null
        }
        if (!isUsableImage(finalFile)) return null
        finalFile.setLastModified(System.currentTimeMillis())
        logCacheEvent("foreground_stream_wait_hit", manga, image, foreground, "bytes=${finalFile.length()}")
        ViewerWarmupManager.logMetric("reader_foreground_stream_wait_hit", 1L)
        return finalFile
    }

    private fun cacheDir(context: Context): File {
        return File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    }

    private fun key(baseMode: Int, image: String): String {
        val normalized = Utils.viewerImageRequestUrl(image, baseMode)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseMode|$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun earlyNtkPathKey(path: String?): String {
        return path?.trim().orEmpty()
    }

    private fun isTrustedNtkImageUrl(value: String): Boolean {
        val lower = value.lowercase()
        if (lower.startsWith("toonflix.app/") || lower.startsWith("//toonflix.app/")) return true
        return try {
            val host = Uri.parse(value).host?.lowercase().orEmpty()
            host == "toonflix.app" || host.endsWith(".toonflix.app")
        } catch (_: Exception) {
            false
        }
    }

    private fun isNtkGeneratedImageUrl(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("://i.toonflix.app/") &&
            (
                Regex("/(manhwa|webtoon)/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/blacktoon/episodes/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/wt/episodes/[^/?#]+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower)
                )
    }

    private fun <T> withNtkGeneratedFetchPermit(
        manga: Manga,
        image: String,
        foreground: Boolean,
        block: () -> T
    ): T {
        if (!isNtkGeneratedImageUrl(Utils.viewerImageRequestUrl(image, manga.baseMode))) return block()
        val startedAt = SystemClock.elapsedRealtime()
        ntkGeneratedFetchGate.acquire()
        val waitedMs = SystemClock.elapsedRealtime() - startedAt
        if (waitedMs > 100L) {
            logCacheEvent(
                "ntk_generated_fetch_gate_wait",
                manga,
                image,
                foreground,
                "ms=$waitedMs,available=${ntkGeneratedFetchGate.availablePermits()}"
            )
        }
        return try {
            block()
        } finally {
            ntkGeneratedFetchGate.release()
        }
    }

    private fun safeImageName(image: String?): String {
        if (image.isNullOrBlank()) return ""
        val clean = image.substringBefore('?')
        return clean.substringAfterLast('/').takeLast(64)
    }

    private fun logCacheEvent(stage: String, manga: Manga, image: String, foreground: Boolean, detail: String) {
        if (!foreground && !stage.contains("error", ignoreCase = true)) return
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return
        val imageName = image.substringAfterLast('/').takeLast(48)
        Log.d(TAG, "reader_image_cache_event stage=$stage,foreground=$foreground,path=$path,image=$imageName,$detail")
    }

    private fun downloadAtomically(
        context: Context,
        manga: Manga,
        image: String,
        finalFile: File,
        foreground: Boolean = false,
        cancellation: Cancellation? = null
    ): File {
        val tmp = File(finalFile.parentFile, "${finalFile.name}.part.${System.nanoTime()}")
        val startedAt = SystemClock.elapsedRealtime()
        try {
            cancellation?.throwIfCancelled()
            val requestUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
            requestWithNtkGeneratedFallback(context, manga, image, foreground, cancellation).use { response ->
                val headersAt = SystemClock.elapsedRealtime()
                if (!response.isSuccessful) throw java.io.IOException("Image request failed: ${response.code} url=$requestUrl")
                rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
                val body = response.body ?: throw java.io.IOException("Empty image body")
                val contentLength = body.contentLength()
                FileOutputStream(tmp).use { out -> body.byteStream().copyTo(out) }
                if (foreground) {
                    val copiedAt = SystemClock.elapsedRealtime()
                    ViewerWarmupManager.logMetric("reader_foreground_image_headers_ms", headersAt - startedAt)
                    ViewerWarmupManager.logMetric("reader_foreground_image_copy_ms", copiedAt - headersAt)
                    ViewerWarmupManager.logMetric("reader_foreground_image_bytes", contentLength)
                }
                logCacheEvent(
                    "download_body",
                    manga,
                    image,
                    foreground,
                    "headersMs=${headersAt - startedAt},bytes=$contentLength"
                )
            }
            if (!isUsableImage(tmp) &&
                !replaceWithImageExtractedFromHtml(context, manga, image, tmp) &&
                !replaceInvalidNtkGeneratedImageWithFallback(context, manga, image, tmp, foreground, cancellation)
            ) {
                if (isLikelyInvalidGeneratedTail(manga, image)) {
                    throw java.io.IOException("Generated image past tail: $image")
                }
                throw java.io.IOException("Invalid image cache file")
            }
            if (foreground) {
                ViewerWarmupManager.logMetric("reader_foreground_image_validate_ms", SystemClock.elapsedRealtime() - startedAt)
            }
            replace(tmp, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            logCacheEvent(
                "download_done",
                manga,
                image,
                foreground,
                "ms=${SystemClock.elapsedRealtime() - startedAt},bytes=${finalFile.length()}"
            )
            ViewerWarmupManager.logMetric("reader_image_cache_download_done", 1L)
            scheduleTrim(context)
            return finalFile
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun request(context: Context, manga: Manga, image: String, cancellation: Cancellation? = null): okhttp3.Response {
        cancellation?.throwIfCancelled()
        val call = getHttpClient().imageClient.newCall(requestFor(manga, image))
        cancellation?.track(call)
        return try {
            call.execute()
        } finally {
            cancellation?.untrack(call)
        }
    }

    private fun requestFor(
        manga: Manga,
        image: String,
        foregroundPriority: Boolean = false,
        anchorHedge: Boolean = false
    ): Request {
        val requestBuilder = Request.Builder().url(Utils.viewerImageRequestUrl(image, manga.baseMode))
        for (entry in Utils.viewerImageRequestHeaders(image, manga.baseMode).entries) {
            requestBuilder.addHeader(entry.key, entry.value)
        }
        if (foregroundPriority) {
            requestBuilder.addHeader("X-MangaViewer-Foreground", "1")
        }
        if (anchorHedge) {
            requestBuilder.addHeader("X-MangaViewer-Anchor-Hedge", "1")
        }
        return requestBuilder.build()
    }

    private fun requestWithNtkGeneratedFallback(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean = false,
        cancellation: Cancellation? = null,
        foregroundRaceAttempts: Int = FOREGROUND_RACE_ATTEMPTS,
        anchorHedge: Boolean = false
    ): okhttp3.Response {
        val foregroundApiFallbackTask: FutureTask<List<String>?>? = null
        val hintedImage = ntkGeneratedImageWithHintedExtension(image)
        if (hintedImage != image) {
            logCacheEvent(
                "generated_extension_hint_request",
                manga,
                hintedImage,
                foreground,
                "source=${image.substringAfterLast('/').takeLast(64)}"
            )
            var hintedFailure: IOException? = null
            val hintedResponse = try {
                requestForForegroundMode(
                    context,
                    manga,
                    hintedImage,
                    foreground,
                    cancellation,
                    foregroundRaceAttempts,
                    anchorHedge
                )
            } catch (e: IOException) {
                hintedFailure = e
                null
            }
            if (hintedResponse != null && hintedResponse.isSuccessful) {
                rememberNtkGeneratedEpisodeExtension(hintedResponse.request.url.toString())
                return hintedResponse
            }
            val hintedCode = hintedResponse?.code ?: imageFailureCode(hintedFailure)
            hintedResponse?.close()
            if (hintedCode != 404) {
                retryOriginalNtkGeneratedImage(
                    context,
                    manga,
                    hintedImage,
                    foreground,
                    cancellation,
                    foregroundRaceAttempts,
                    anchorHedge,
                    hintedFailure
                )?.let { return it }
                retryNtkGeneratedAfterNativeAck(context, manga, hintedImage, foreground, cancellation)?.let { return it }
                retryNtkGeneratedViaApiFallback(context, manga, hintedImage, foreground, cancellation = cancellation)?.let { return it }
                return requestForForegroundMode(context, manga, hintedImage, foreground, cancellation)
            }
        }
        var initialFailure: IOException? = null
        val response = try {
            requestForForegroundMode(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge
            )
        } catch (e: IOException) {
            if (!shouldTryNtkGeneratedExtensionFallback(image)) throw e
            initialFailure = e
            null
        }
        if (response == null && isCancellationFailure(initialFailure)) {
            throw initialFailure ?: java.io.InterruptedIOException("Reader image request cancelled")
        }
        if (response != null && (response.isSuccessful || !shouldTryNtkGeneratedExtensionFallback(image))) {
            if (response.isSuccessful) rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
            return response
        }
        if (response == null && !shouldTryNtkGeneratedExtensionFallback(image)) {
            throw IOException("Generated image request failed before fallback: $image")
        }
        if (response == null && imageFailureCode(initialFailure) != 404 && ntkGeneratedEpisodeExtensionMatches(image)) {
            val target = ntkGeneratedTarget(image)
            logCacheEvent(
                "generated_extension_fallback_skip",
                manga,
                image,
                foreground,
                "page=${target?.page ?: 0},code=0,reason=hint_matches_current_initial_failure"
            )
            retryOriginalNtkGeneratedImage(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge,
                initialFailure
            )?.let { return it }
            retryOriginalNtkGeneratedImage(
                context,
                manga,
                image,
                foreground = false,
                cancellation = cancellation,
                foregroundRaceAttempts = 1,
                anchorHedge = false,
                initialFailure = initialFailure
            )?.let { return it }
            retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
            return requestForForegroundMode(context, manga, image, foreground = false, cancellation = cancellation)
        }
        if (response == null && foreground && imageFailureCode(initialFailure) != 404) {
            val target = ntkGeneratedTarget(image)
            val apiFallbackTask = target?.let { startNtkApiFallbackImages(context, manga, it) }
            if (apiFallbackTask != null) {
                requestForegroundGeneratedRace(context, manga, image, apiFallbackTask)?.let { return it }
            }
            val ackRecoveryTask = startNtkGeneratedAckRecovery(context, manga, image)
            retryOriginalNtkGeneratedImage(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge,
                initialFailure
            )?.let { return it }
            retryOriginalNtkGeneratedImage(
                context,
                manga,
                image,
                foreground = false,
                cancellation = cancellation,
                foregroundRaceAttempts = 1,
                anchorHedge = false,
                initialFailure = initialFailure
            )?.let { return it }
            retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                ?.let { return it }
            retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)
                ?.let { return it }
            retryNtkGeneratedAfterWebViewAck(
                context,
                manga,
                image,
                foreground,
                ackRecoveryTask,
                cancellation
            )?.let { return it }
            return requestForForegroundMode(context, manga, image, foreground = false, cancellation = cancellation)
        }
        val failedCode = response?.code ?: imageFailureCode(initialFailure)
        var generated404 = failedCode == 404
        if (failedCode == 404 && isLikelyPastGeneratedTail(manga, image)) {
            throw IOException("Generated image past tail: $image")
        }
        response?.close()
        val target = ntkGeneratedTarget(image)
        if (!generated404 && ntkGeneratedEpisodeExtensionMatches(image)) {
            logCacheEvent(
                "generated_extension_fallback_skip",
                manga,
                image,
                foreground,
                "page=${target?.page ?: 0},code=$failedCode,reason=hint_matches_current"
            )
            retryOriginalNtkGeneratedImage(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge,
                initialFailure
            )?.let { return it }
            retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
            retryNtkGeneratedViaApiFallback(context, manga, image, foreground, cancellation = cancellation)?.let { return it }
            return requestForForegroundMode(context, manga, image, foreground, cancellation)
        }
        var apiFallbackTask = foregroundApiFallbackTask ?: if (generated404) target?.let {
            startNtkApiFallbackImages(context, manga, it)
        } else null
        if (foreground && generated404 && apiFallbackTask != null) {
            requestForegroundGeneratedRace(context, manga, image, apiFallbackTask)?.let { return it }
        }
        for (candidate in ntkGeneratedExtensionFallbacks(image)) {
            val fallback = try {
                requestForForegroundMode(context, manga, candidate, foreground, cancellation)
            } catch (e: IOException) {
                if (imageFailureCode(e) == 404) {
                    generated404 = true
                    if (apiFallbackTask == null)
                        apiFallbackTask = target?.let { startNtkApiFallbackImages(context, manga, it) }
                }
                continue
            }
            if (fallback.isSuccessful) {
                rememberNtkGeneratedEpisodeExtension(fallback.request.url.toString())
                return fallback
            }
            if (fallback.code == 404 && isLikelyPastGeneratedTail(manga, candidate)) {
                fallback.close()
                throw IOException("Generated image past tail: $candidate")
            }
            if (fallback.code == 404) {
                generated404 = true
                if (apiFallbackTask == null)
                    apiFallbackTask = target?.let { startNtkApiFallbackImages(context, manga, it) }
            }
            fallback.close()
        }
        if (generated404) {
            retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)?.let { return it }
            retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
            return requestForForegroundMode(context, manga, image, foreground, cancellation)
        }
        retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
        retryNtkGeneratedViaApiFallback(context, manga, image, foreground, cancellation = cancellation)?.let { return it }
        return requestForForegroundMode(context, manga, image, foreground, cancellation)
    }

    private fun retryOriginalNtkGeneratedImage(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        cancellation: Cancellation?,
        foregroundRaceAttempts: Int,
        anchorHedge: Boolean,
        initialFailure: IOException?
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        ViewerWarmupManager.logMetric("ntk_generated_image_original_retry", target.page.toLong())
        logCacheEvent(
            "generated_original_retry_start",
            manga,
            image,
            foreground,
            "page=${target.page},initial=${initialFailure?.javaClass?.simpleName ?: "IOException"}"
        )
        val retry = try {
            requestForForegroundMode(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge
            )
        } catch (e: IOException) {
            logCacheEvent(
                "generated_original_retry_error",
                manga,
                image,
                foreground,
                "page=${target.page},error=${e.javaClass.simpleName}"
            )
            return null
        }
        if (retry.isSuccessful) {
            logCacheEvent(
                "generated_original_retry_hit",
                manga,
                image,
                foreground,
                "page=${target.page},code=${retry.code}"
            )
            return retry
        }
        logCacheEvent(
            "generated_original_retry_miss",
            manga,
            image,
            foreground,
            "page=${target.page},code=${retry.code}"
        )
        retry.close()
        return null
    }

    private fun isCancellationFailure(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is java.io.InterruptedIOException) return true
            current = current.cause
        }
        return false
    }

    private fun imageFailureCode(error: Throwable?): Int {
        var current = error
        while (current != null) {
            Regex("Image request failed: (\\d+)").find(current.message.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return it }
            current = current.cause
        }
        return 0
    }

    private fun isLikelyPastGeneratedTail(manga: Manga, image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        val knownCount = manga.ntkImageCount
        if (knownCount > 0) return target.page > knownCount
        return target.page >= 18
    }

    private fun isLikelyInvalidGeneratedTail(manga: Manga, image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        val knownCount = manga.ntkImageCount
        if (knownCount > 0) return target.page >= max(18, knownCount - 4)
        return target.page >= 18
    }

    private fun requestForegroundGeneratedRace(
        context: Context,
        manga: Manga,
        image: String,
        apiFallbackTask: FutureTask<List<String>?>
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "ntk_generated_image_race_start page=${target.page},path=${target.path}")
        val completion = ExecutorCompletionService<Response?>(foregroundRaceExecutor)
        var submitted = 0
        completion.submit(Callable {
            requestGeneratedDirectFallbacks(context, manga, image).also { response ->
                Log.d(
                    TAG,
                    "ntk_generated_image_race_direct page=${target.page},code=${response?.code ?: 0},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
            }
        })
        submitted++
        completion.submit(Callable {
            val images = fetchNtkApiFallbackImages(context, manga, target, apiFallbackTask) ?: return@Callable null
            val replacement = images.getOrNull(target.page - 1) ?: image
            val sameUrl = replacement == image
            ViewerWarmupManager.logMetric("ntk_generated_image_api_fallback_race", target.page.toLong())
            Log.d(
                TAG,
                "ntk_generated_image_race_api_ready page=${target.page},sameUrl=$sameUrl,images=${images.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            try {
                requestForForegroundMode(context, manga, replacement, foreground = false).also { response ->
                    Log.d(
                        TAG,
                        "ntk_generated_image_race_api_response page=${target.page},code=${response.code},sameUrl=$sameUrl,ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
            } catch (_: IOException) {
                null
            }
        })
        submitted++
        repeat(submitted) {
            val response = try {
                completion.poll(2200L, TimeUnit.MILLISECONDS)?.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                null
            } ?: return@repeat
            if (response.isSuccessful) {
                Log.d(TAG, "ntk_generated_image_race_win page=${target.page},code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}")
                return response
            }
            response.close()
        }
        Log.d(TAG, "ntk_generated_image_race_miss page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt}")
        return null
    }

    private fun requestGeneratedDirectFallbacks(context: Context, manga: Manga, image: String): Response? {
        val first = try {
            request(context, manga, image)
        } catch (_: IOException) {
            null
        }
        if (first != null && first.isSuccessful) return first
        first?.close()
        for (candidate in ntkGeneratedExtensionFallbacks(image)) {
            val fallback = try {
                request(context, manga, candidate)
            } catch (_: IOException) {
                continue
            }
            if (fallback.isSuccessful) return fallback
            fallback.close()
        }
        return null
    }

    private fun startNtkGeneratedAckRecovery(
        context: Context,
        manga: Manga,
        image: String
    ): FutureTask<Boolean>? {
        val target = ntkGeneratedTarget(image) ?: return null
        val path = ntkFallbackKeyPath(manga, target)
        val key = "${target.baseUrl}$path"
        val task = FutureTask {
            val startedAt = SystemClock.elapsedRealtime()
            logCacheEvent(
                "generated_webview_ack_recovery_start",
                manga,
                image,
                true,
                "path=$path"
            )
            val ok = try {
                getHttpClient().performNtkWebViewAckPreflight(path)
            } catch (_: Exception) {
                false
            }
            logCacheEvent(
                "generated_webview_ack_recovery_done",
                manga,
                image,
                true,
                "path=$path,success=$ok,ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            ok
        }
        val existing = ntkGeneratedAckRecoveryFlights.putIfAbsent(key, task)
        if (existing != null) return existing
        try {
            ntkApiFallbackExecutor.execute(task)
        } catch (_: Exception) {
            task.run()
        }
        return task
    }

    private fun retryNtkGeneratedAfterWebViewAck(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        runningTask: FutureTask<Boolean>?,
        cancellation: Cancellation? = null
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        val task = runningTask ?: startNtkGeneratedAckRecovery(context, manga, image) ?: return null
        val ok = try {
            task.get(26, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            ntkGeneratedAckRecoveryFlights.remove("${target.baseUrl}${ntkFallbackKeyPath(manga, target)}", task)
        }
        if (!ok) return null
        cancellation?.throwIfCancelled()
        ViewerWarmupManager.logMetric("ntk_generated_image_webview_ack_retry", target.page.toLong())
        val retry = try {
            requestForForegroundMode(context, manga, image, foreground, cancellation)
        } catch (_: IOException) {
            null
        }
        if (retry != null && retry.isSuccessful) return retry
        retry?.close()
        return null
    }

    private fun retryNtkGeneratedAfterNativeAck(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        cancellation: Cancellation? = null
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        cancellation?.throwIfCancelled()
        val acked = try {
            getHttpClient().performNtkNativeAckBypass(target.baseUrl, ntkFallbackKeyPath(manga, target))
        } catch (_: Exception) {
            false
        }
        if (!acked) return null
        ViewerWarmupManager.logMetric("ntk_generated_image_native_ack_retry", target.page.toLong())
        val retry = try {
            requestForForegroundMode(context, manga, image, foreground, cancellation)
        } catch (_: IOException) {
            null
        }
        if (retry != null && retry.isSuccessful) return retry
        retry?.close()
        return null
    }

    private fun retryNtkGeneratedViaApiFallback(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        runningTask: FutureTask<List<String>?>? = null,
        cancellation: Cancellation? = null
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        cancellation?.throwIfCancelled()
        val images = fetchNtkApiFallbackImages(context, manga, target, runningTask) ?: return null
        val replacement = images.getOrNull(target.page - 1) ?: image
        ViewerWarmupManager.logMetric("ntk_generated_image_api_fallback_retry", target.page.toLong())
        val retry = try {
            requestForForegroundMode(context, manga, replacement, foreground, cancellation)
        } catch (_: IOException) {
            null
        }
        if (retry != null && retry.isSuccessful) return retry
        retry?.close()
        return null
    }

    private fun fetchNtkApiFallbackImages(
        context: Context,
        manga: Manga,
        target: NtkGeneratedTarget,
        runningTask: FutureTask<List<String>?>? = null
    ): List<String>? {
        val key = "${target.baseUrl}${ntkFallbackKeyPath(manga, target)}"
        ntkApiFallbackImages[key]?.let {
            ViewerWarmupManager.logMetric("ntk_generated_image_api_fallback_cache_hit", target.page.toLong())
            return it
        }
        val running = runningTask ?: startNtkApiFallbackImages(context, manga, target)
        return try {
            running.get(18, TimeUnit.SECONDS)?.also { images ->
                ntkApiFallbackImages[key] = images
                manga.setImgs(ArrayList(images))
                manga.ntkImageCount = images.size
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            ntkApiFallbackFlights.remove(key, running)
        }
    }

    private fun startNtkApiFallbackImages(
        context: Context,
        manga: Manga,
        target: NtkGeneratedTarget
    ): FutureTask<List<String>?> {
        val key = "${target.baseUrl}${ntkFallbackKeyPath(manga, target)}"
        val task = FutureTask {
            try {
                val fallbackManga = ntkFallbackFetchCopy(manga)
                val result = Manga.fetchWithTemporaryNtkViewerFetchMode(fallbackManga, getHttpClient(), "api-strict")
                if (result != Title.LOAD_OK) {
                    null
                } else {
                    fallbackManga.getImgs(context.applicationContext).toList().takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                null
            }
        }
        val existing = ntkApiFallbackFlights.putIfAbsent(key, task)
        if (existing != null) return existing
        try {
            ntkApiFallbackExecutor.execute(task)
        } catch (_: Exception) {
            task.run()
        }
        return task
    }

    private fun ntkFallbackKeyPath(manga: Manga, target: NtkGeneratedTarget): String {
        return manga.ntkEpisodePath?.takeIf { it.isNotBlank() } ?: target.path
    }

    private fun ntkFallbackFetchCopy(source: Manga): Manga {
        return Manga(source.id, source.name, source.date, source.baseMode).also { copy ->
            copy.mode = source.mode
            copy.title = source.title
            copy.titleId = source.titleId
            copy.ntkEpisodePath = source.ntkEpisodePath
            copy.ntkImageEpisodeId = source.ntkImageEpisodeId
            copy.ntkImageCount = source.ntkImageCount
            copy.seed = source.seed
            source.eps?.let { copy.eps = ArrayList(it) }
        }
    }

    private fun requestForForegroundMode(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        cancellation: Cancellation? = null,
        foregroundRaceAttempts: Int = FOREGROUND_RACE_ATTEMPTS,
        anchorHedge: Boolean = false
    ): okhttp3.Response {
        if (!foreground) return request(context, manga, image, cancellation)
        return if (shouldRaceForegroundImage(image)) {
            requestForegroundRace(
                manga,
                image,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge
            )
        } else {
            request(context, manga, image, cancellation)
        }
    }

    private fun shouldRaceForegroundImage(image: String): Boolean {
        val ntkTarget = ntkGeneratedTarget(image)
        if (ntkTarget != null) return false
        return true
    }

    private fun requestForegroundRace(
        manga: Manga,
        image: String,
        cancellation: Cancellation? = null,
        raceAttempts: Int = FOREGROUND_RACE_ATTEMPTS,
        anchorHedge: Boolean = false
    ): okhttp3.Response {
        val foregroundRequest = requestFor(
            manga,
            image,
            foregroundPriority = true,
            anchorHedge = anchorHedge
        )
        val attempts = foregroundRaceAttempts(foregroundRequest, raceAttempts)
        val calls = Collections.synchronizedList(ArrayList<Call>())
        val completion = ExecutorCompletionService<ForegroundRaceResult>(foregroundRaceExecutor)
        val completed = AtomicBoolean(false)
        val startedAt = SystemClock.elapsedRealtime()
        fun submit(attempt: ForegroundRaceAttempt, delayMs: Long) {
            completion.submit(Callable {
                if (delayMs > 0L) Thread.sleep(delayMs)
                cancellation?.throwIfCancelled()
                if (completed.get()) throw IOException("Foreground image race already completed")
                val call = attempt.client.newCall(attempt.request)
                cancellation?.track(call)
                calls.add(call)
                try {
                    ForegroundRaceResult(attempt, call, call.execute())
                } catch (t: Throwable) {
                    logCacheEvent(
                        "foreground_race_call_error",
                        manga,
                        image,
                        true,
                        "transport=${attempt.transport},error=${t.javaClass.simpleName},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    throw t
                } finally {
                    cancellation?.untrack(call)
                }
            })
        }
        attempts.forEachIndexed { index, attempt ->
            val delayMs = FOREGROUND_RACE_DELAY_MS * index
            submit(attempt, delayMs)
        }
        var failure: Throwable? = null
        repeat(attempts.size) { completedIndex ->
            try {
                val result = completion.take().get()
                val response = result.response
                if (response.isSuccessful) {
                    if (completedIndex > 0) ViewerWarmupManager.logMetric("reader_foreground_image_race_won", completedIndex.toLong())
                    completed.set(true)
                    for (call in calls) {
                        if (call !== result.call) call.cancel()
                    }
                    logCacheEvent(
                        "foreground_race_win",
                        manga,
                        image,
                        true,
                        "transport=${result.attempt.transport},completed=$completedIndex,total=${attempts.size},code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return response
                }
                failure = IOException("Image request failed: ${response.code}")
                logCacheEvent(
                    "foreground_race_miss",
                    manga,
                    image,
                    true,
                    "transport=${result.attempt.transport},completed=$completedIndex,total=${attempts.size},code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                response.close()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                for (call in calls) call.cancel()
                throw java.io.InterruptedIOException("Interrupted while racing image request").apply {
                    initCause(e)
                }
            } catch (e: ExecutionException) {
                failure = e.cause ?: e
            }
        }
        for (call in calls) call.cancel()
        throw IOException("Foreground image race failed", failure)
    }

    private fun foregroundRaceAttempts(foregroundRequest: Request, raceAttempts: Int): List<ForegroundRaceAttempt> {
        val httpClient = getHttpClient()
        val target = ntkGeneratedTarget(foregroundRequest.url.toString())
        val attempts = raceAttempts.coerceIn(1, 2)
        return List(attempts) { index ->
            ForegroundRaceAttempt("image-${index + 1}", httpClient.imageClient, foregroundRequest)
        }
    }

    private fun shouldTryNtkGeneratedExtensionFallback(image: String): Boolean {
        val lower = image.lowercase()
        return (lower.contains("://toonflix.app/")
                || lower.contains("://i.toonflix.app/")
                || Regex("://fvcdn\\d*\\.com/").containsMatchIn(lower))
            && (
                Regex("/(manhwa|webtoon)/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/blacktoon/episodes/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/wt/episodes/[^/?#]+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower)
                )
    }

    private data class NtkGeneratedTarget(
        val baseUrl: String,
        val path: String,
        val page: Int
    )

    private fun ntkGeneratedTarget(image: String): NtkGeneratedTarget? {
        val numericMatch = Regex("^(https?://[^/]+)/(manhwa|webtoon)/(\\d+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (numericMatch != null) {
            val segment = numericMatch.groupValues[2]
            val workId = numericMatch.groupValues[3]
            val episodeId = numericMatch.groupValues[4]
            val page = numericMatch.groupValues[5].toIntOrNull() ?: return null
            return NtkGeneratedTarget(numericMatch.groupValues[1], "/$segment/$workId/$episodeId", page)
        }
        val blacktoonMatch = Regex("^(https?://[^/]+)/blacktoon/episodes/(\\d+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (blacktoonMatch != null) {
            val workId = blacktoonMatch.groupValues[2]
            val episodeId = blacktoonMatch.groupValues[3]
            val page = blacktoonMatch.groupValues[4].toIntOrNull() ?: return null
            return NtkGeneratedTarget(blacktoonMatch.groupValues[1], "/webtoon/$workId/$episodeId", page)
        }
        val slugMatch = Regex("^(https?://[^/]+)/wt/episodes/([^/?#]+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image) ?: return null
        val slug = slugMatch.groupValues[2]
        val episodeId = slugMatch.groupValues[3]
        val page = slugMatch.groupValues[4].toIntOrNull() ?: return null
        return NtkGeneratedTarget(slugMatch.groupValues[1], "/webtoon/$slug/$episodeId", page)
    }

    private data class NtkGeneratedImageRef(
        val episodeKey: String,
        val extension: String
    )

    private fun ntkGeneratedImageRef(image: String): NtkGeneratedImageRef? {
        val numericMatch = Regex("^(?:https?://[^/]+)/(manhwa|webtoon)/(\\d+)/([^/?#]+)/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (numericMatch != null) {
            val key = "${numericMatch.groupValues[1].lowercase()}/${numericMatch.groupValues[2]}/${numericMatch.groupValues[3]}"
            return NtkGeneratedImageRef(key, numericMatch.groupValues[4].lowercase())
        }
        val blacktoonMatch = Regex("^(?:https?://[^/]+)/blacktoon/episodes/(\\d+)/([^/?#]+)/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (blacktoonMatch != null) {
            val key = "webtoon/${blacktoonMatch.groupValues[1]}/${blacktoonMatch.groupValues[2]}"
            return NtkGeneratedImageRef(key, blacktoonMatch.groupValues[3].lowercase())
        }
        val slugMatch = Regex("^(?:https?://[^/]+)/wt/episodes/([^/?#]+)/([^/?#]+)/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image) ?: return null
        val key = "wt/${slugMatch.groupValues[1]}/${slugMatch.groupValues[2]}"
        return NtkGeneratedImageRef(key, slugMatch.groupValues[3].lowercase())
    }

    private fun rememberNtkGeneratedEpisodeExtension(image: String) {
        val ref = ntkGeneratedImageRef(image) ?: return
        val previous = ntkGeneratedEpisodeExtensions.put(ref.episodeKey, ref.extension)
        if (previous != ref.extension) {
            Log.d(TAG, "ntk_generated_episode_extension_hint key=${ref.episodeKey},extension=${ref.extension},previous=${previous.orEmpty()}")
        }
    }

    private fun ntkGeneratedEpisodeExtensionMatches(image: String): Boolean {
        val ref = ntkGeneratedImageRef(image) ?: return false
        return ntkGeneratedEpisodeExtensions[ref.episodeKey] == ref.extension
    }

    private fun ntkGeneratedImageWithHintedExtension(image: String): String {
        val ref = ntkGeneratedImageRef(image) ?: return image
        val hinted = ntkGeneratedEpisodeExtensions[ref.episodeKey] ?: return image
        if (hinted == ref.extension) return image
        val match = Regex("(?i)\\.(jpg|jpeg|png|webp)([?#].*)?$").find(image) ?: return image
        val suffix = match.groupValues.getOrNull(2).orEmpty()
        return image.substring(0, match.range.first) + ".$hinted$suffix"
    }

    private fun ntkGeneratedExtensionFallbacks(image: String): List<String> {
        val match = Regex("(?i)\\.(jpg|jpeg|png|webp)([?#].*)?$").find(image) ?: return emptyList()
        val current = match.groupValues[1].lowercase()
        val suffix = match.groupValues.getOrNull(2).orEmpty()
        val prefix = image.substring(0, match.range.first)
        val hinted = ntkGeneratedImageRef(image)
            ?.episodeKey
            ?.let { ntkGeneratedEpisodeExtensions[it] }
            ?.takeIf { it != current }
        val ordered = if (hinted != null) {
            listOf(hinted, "jpg", "jpeg", "png", "webp")
        } else {
            listOf("jpg", "jpeg", "png", "webp")
        }
        return ordered
            .distinct()
            .filter { it != current }
            .map { "$prefix.$it$suffix" }
    }

    private fun replaceWithImageExtractedFromHtml(
        context: Context,
        manga: Manga,
        image: String,
        htmlFile: File
    ): Boolean {
        if (!looksLikeHtml(htmlFile)) return false
        val html = try {
            htmlFile.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val sourceUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
        for (candidate in extractImageCandidates(html, sourceUrl, manga.baseMode)) {
            val candidateFile = File(htmlFile.parentFile, "${htmlFile.name}.htmlimg.${System.nanoTime()}")
            try {
                request(context, manga, candidate).use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    FileOutputStream(candidateFile).use { out -> body.byteStream().copyTo(out) }
                }
                if (isUsableImage(candidateFile)) {
                    if (htmlFile.exists()) htmlFile.delete()
                    if (!candidateFile.renameTo(htmlFile)) {
                        candidateFile.copyTo(htmlFile, overwrite = true)
                        candidateFile.delete()
                    }
                    return true
                }
            } catch (_: Exception) {
                // Try the next extracted candidate.
            } finally {
                if (candidateFile.exists()) candidateFile.delete()
            }
        }
        return false
    }

    private fun replaceInvalidNtkGeneratedImageWithFallback(
        context: Context,
        manga: Manga,
        image: String,
        targetFile: File,
        foreground: Boolean,
        cancellation: Cancellation?
    ): Boolean {
        if (!shouldTryNtkGeneratedExtensionFallback(image)) return false
        val candidates = ntkGeneratedExtensionFallbacks(image)
        if (candidates.isEmpty()) return false
        for (candidate in candidates) {
            cancellation?.throwIfCancelled()
            val candidateFile = File(targetFile.parentFile, "${targetFile.name}.fallback.${System.nanoTime()}")
            try {
                requestForForegroundMode(context, manga, candidate, foreground, cancellation).use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    FileOutputStream(candidateFile).use { out -> body.byteStream().copyTo(out) }
                }
                if (isUsableImage(candidateFile)) {
                    if (targetFile.exists()) targetFile.delete()
                    replace(candidateFile, targetFile)
                    logCacheEvent(
                        "invalid_generated_fallback",
                        manga,
                        candidate,
                        foreground,
                        "source=${image.substringAfterLast('/').takeLast(48)}"
                    )
                    ViewerWarmupManager.logMetric("ntk_generated_invalid_extension_fallback", 1L)
                    return true
                }
            } catch (_: Exception) {
                // Try the next extension candidate.
            } finally {
                if (candidateFile.exists()) candidateFile.delete()
            }
        }
        return false
    }

    internal fun extractImageCandidates(html: String, sourceUrl: String, baseMode: Int): List<String> {
        if (html.isBlank()) return emptyList()
        val candidates = LinkedHashSet<String>()
        val document = Jsoup.parse(html)
        for (img in document.select("img")) {
            for (attr in HTML_IMAGE_ATTRS) {
                addHtmlImageCandidate(candidates, img.attr(attr), sourceUrl, baseMode)
            }
        }
        for (match in HTML_IMAGE_URL_PATTERN.findAll(html)) {
            addHtmlImageCandidate(candidates, match.value, sourceUrl, baseMode)
        }
        return candidates.toList()
    }

    @JvmStatic
    fun extractImageCandidatesForTest(html: String, sourceUrl: String, baseMode: Int): List<String> {
        return extractImageCandidates(html, sourceUrl, baseMode)
    }

    private fun addHtmlImageCandidate(
        candidates: MutableSet<String>,
        rawCandidate: String?,
        sourceUrl: String,
        baseMode: Int
    ) {
        val candidate = normalizeHtmlImageCandidate(rawCandidate, baseMode) ?: return
        if (candidate == sourceUrl) return
        if (!isAllowedHtmlImageCandidate(candidate, sourceUrl)) return
        candidates.add(candidate)
    }

    private fun normalizeHtmlImageCandidate(candidate: String?, baseMode: Int): String? {
        val raw = candidate?.trim()?.replace("&amp;", "&") ?: return null
        if (raw.isEmpty()
            || raw.startsWith("data:", ignoreCase = true)
            || raw.startsWith("javascript:", ignoreCase = true)
            || raw.startsWith("about:", ignoreCase = true)
        ) return null
        return Utils.viewerImageRequestUrl(raw, baseMode)
    }

    private fun isAllowedHtmlImageCandidate(candidate: String, sourceUrl: String): Boolean {
        val lower = candidate.lowercase()
        if (!lower.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)([?#].*)?$"))) return false
        if (lower.contains("sprite")
            || lower.contains("logo")
            || lower.contains("favicon")
            || lower.contains("banner")
            || lower.contains("advert")
            || lower.contains("sponsor")
            || lower.contains("popup")
            || lower.contains("/ad/")
            || lower.contains("/ads/")
            || lower.contains("/thumb")
            || lower.contains("/data/member/")
        ) return false
        if (isNtkProtectedImageUrl(sourceUrl) && !isNtkProtectedImageUrl(candidate)) return false

        val sourceName = fileName(sourceUrl)
        val candidateName = fileName(candidate)
        if (sourceName.isNotEmpty() && sourceName == candidateName) return true

        return lower.contains("/webtoon_uploads/")
            || lower.contains("/manhwa_uploads/")
            || lower.contains("/comic_uploads/")
            || lower.contains("/blacktoon/episodes/")
            || lower.contains("/webtoon/")
            || lower.contains("/manhwa/")
            || lower.contains("/comic/")
    }

    private fun isNtkProtectedImageUrl(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty()) return false
        if (host.contains("naver") || host.contains("pstatic")) return false
        return host == "toonflix.app" ||
            host.endsWith(".toonflix.app") ||
            host.startsWith("img.") ||
            Regex("^(www\\.)?pl\\d+\\.com$").matches(host)
    }

    private fun fileName(url: String): String {
        return try {
            Uri.parse(url).lastPathSegment?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun replace(tmp: File, finalFile: File) {
        if (finalFile.exists()) finalFile.delete()
        if (!tmp.renameTo(finalFile)) {
            tmp.copyTo(finalFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun isUsableImage(file: File): Boolean {
        if (!file.isFile || file.length() < 32L) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            if (file.length() > FULL_DECODE_VALIDATION_MAX_BYTES) return true
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sampledValidationDecodeSize(bounds.outWidth, bounds.outHeight)
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
            bitmap.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sampledValidationDecodeSize(width: Int, height: Int): Int {
        val maxDim = max(width, height)
        var sample = 1
        while (maxDim / sample > 256) sample = sample shl 1
        return sample
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        val b2 = bytes[2].toInt() and 0xff
        val b3 = bytes[3].toInt() and 0xff
        if (b0 == 0xff && b1 == 0xd8) return true
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47) return true
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true
        return false
    }

    private fun looksLikeHtml(file: File): Boolean {
        if (!file.isFile || file.length() < 16L) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(64)
                val read = input.read(header)
                if (read <= 0) return false
                val text = String(header, 0, read, Charsets.UTF_8).trimStart().lowercase()
                text.startsWith("<!doctype html")
                    || text.startsWith("<html")
                    || text.startsWith("<head")
                    || text.startsWith("<body")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleTrim(context: Context) {
        val now = System.currentTimeMillis()
        val appContext = context.applicationContext
        if (!trimScheduled.compareAndSet(false, true)) {
            trimDirty.set(true)
            return
        }
        val delayMs = (TRIM_DEBOUNCE_MS - (now - lastTrimStartedAt.get())).coerceAtLeast(0L)
        trimExecutor.schedule({
            try {
                trimCache(appContext)
            } finally {
                trimScheduled.set(false)
                if (trimDirty.getAndSet(false)) scheduleTrim(appContext)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun trimCache(context: Context) = synchronized(trimLock) {
        val startedAt = System.currentTimeMillis()
        lastTrimStartedAt.set(startedAt)
        val dir = cacheDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= TARGET_CACHE_BYTES) break
            val key = file.name.removeSuffix(".img")
            if ((activeReads[key]?.get() ?: 0) > 0) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
        ViewerWarmupManager.logMetric("reader_cache_trim_ms", System.currentTimeMillis() - startedAt)
    }

    private val HTML_IMAGE_ATTRS = arrayOf("data-original", "data-src", "data-lazy-src", "data-url", "src")
    private val HTML_IMAGE_URL_PATTERN = Regex(
        "https?://[^\\\"'<>\\s)]+\\.(?:jpg|jpeg|png|webp|gif)(?:[?#][^\\\"'<>\\s)]*)?",
        RegexOption.IGNORE_CASE
    )
}
