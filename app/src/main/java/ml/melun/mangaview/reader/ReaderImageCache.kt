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
import java.util.concurrent.Future
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
    private const val NTK_GENERATED_ANCHOR_RACE_FAST_FAIL_MS = 1200L
    private const val NTK_VERIFIED_ANCHOR_HANDOFF_DELAY_MS = 300L
    private const val FOREGROUND_STREAM_RACE_ATTEMPTS = 1
    private const val FOREGROUND_STREAM_JOIN_TIMEOUT_MS = 1800L
    private const val FOREGROUND_INITIAL_STREAM_JOIN_TIMEOUT_MS = 900L
    private const val FOREGROUND_INITIAL_STREAM_LATE_JOIN_TIMEOUT_MS = 2400L
    private const val NTK_GENERATED_INITIAL_RECOVERY_HEDGE_MS = 300L
    private const val NTK_GENERATED_INITIAL_RECOVERY_SAME_URL_HEDGE_MS = 350L
    private const val NTK_GENERATED_INITIAL_RECOVERY_PAGES = 4
    private const val FOREGROUND_STREAM_HANDOFF_TTL_MS = 2500L
    private const val FOREGROUND_STREAM_STALE_MS = 9000L
    private const val NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES = 3
    private const val NTK_GENERATED_INITIAL_TRANSIENT_RETRY_DELAY_MS = 180L
    private const val EARLY_NTK_IMAGE_URL_TTL_MS = 6000L
    private const val EARLY_NTK_IMAGE_URL_STARTED_SKEW_MS = 1200L
    private const val NTK_ACK_RECOVERY_LAUNCH_HOLD_MS = 30_000L
    private const val MAX_DIRECT_STREAM_DECODE_BYTES = 16L * 1024L * 1024L
    private const val MAX_DIRECT_STREAM_BITMAP_BYTES = 2L * 1024L * 1024L
    private const val DIRECT_STREAM_TILE_ASPECT_RATIO = 3.0f
    private const val DIRECT_STREAM_TILE_MIN_ESTIMATED_BYTES = 12L * 1024L * 1024L
    private const val FULL_DECODE_VALIDATION_MAX_BYTES = 256L * 1024L
    private const val NTK_GENERATED_BACKGROUND_FETCH_PARALLELISM = 4
    private const val NTK_GENERATED_FOREGROUND_FETCH_PARALLELISM = 6
    private val flights = ConcurrentHashMap<String, FutureTask<File>>()
    private val foregroundStreams = ConcurrentHashMap<String, FutureTask<ByteArray?>>()
    private val foregroundStreamStartedAt = ConcurrentHashMap<String, Long>()
    private val earlyNtkImageUrls = ConcurrentHashMap<String, EarlyNtkImageUrls>()
    private val ntkApiFallbackFlights = ConcurrentHashMap<String, FutureTask<List<String>?>>()
    private val ntkApiFallbackImages = ConcurrentHashMap<String, List<String>>()
    private val ntkGeneratedAckRecoveryFlights = ConcurrentHashMap<String, FutureTask<Boolean>>()
    private val ntkAckRecoveryLaunchHolds = ConcurrentHashMap<String, Long>()
    private val ntkGeneratedEpisodeExtensions = ConcurrentHashMap<String, String>()
    private val activeReads = ConcurrentHashMap<String, AtomicInteger>()
    private val cacheGeneration = AtomicLong(0L)
    private val ntkGeneratedBackgroundFetchGate = Semaphore(NTK_GENERATED_BACKGROUND_FETCH_PARALLELISM)
    private val ntkGeneratedForegroundFetchGate = Semaphore(NTK_GENERATED_FOREGROUND_FETCH_PARALLELISM)
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

    private data class InitialRecoveryResult(
        val label: String,
        val file: File
    )

    @JvmStatic
    fun clearVolatileStateForTest() {
        val generation = cacheGeneration.incrementAndGet()
        flights.values.forEach { it.cancel(true) }
        flights.clear()
        foregroundStreams.values.forEach { it.cancel(true) }
        foregroundStreams.clear()
        foregroundStreamStartedAt.clear()
        earlyNtkImageUrls.clear()
        ntkApiFallbackFlights.values.forEach { it.cancel(true) }
        ntkApiFallbackFlights.clear()
        ntkApiFallbackImages.clear()
        ntkGeneratedAckRecoveryFlights.values.forEach { it.cancel(true) }
        ntkGeneratedAckRecoveryFlights.clear()
        ntkAckRecoveryLaunchHolds.clear()
        ntkGeneratedEpisodeExtensions.clear()
        activeReads.clear()
        Log.d(TAG, "reader_image_cache_volatile_clear_for_test generation=$generation")
    }

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
        return cachedImageFile(appContext, manga, image)
    }

    fun hasActiveFetch(manga: Manga, image: String): Boolean {
        if (!manga.isOnline) return false
        return ntkGeneratedCacheLookupImages(image).any { candidate ->
            val key = key(manga.baseMode, candidate)
            flights.containsKey(key) || foregroundStreams.containsKey(key)
        }
    }

    @JvmStatic
    fun clearNtkGeneratedEpisodeExtensionHintsForTest() {
        ntkGeneratedEpisodeExtensions.clear()
    }

    @JvmStatic
    fun holdNtkAckRecoveryUntilFirstDrawable(path: String?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return
        val until = SystemClock.elapsedRealtime() + NTK_ACK_RECOVERY_LAUNCH_HOLD_MS
        ntkAckRecoveryLaunchHolds[key] = until
        Log.d(TAG, "reader_ntk_ack_recovery_launch_hold path=$key,untilMs=$until")
    }

    @JvmStatic
    fun releaseNtkAckRecoveryAfterFirstDrawable(path: String?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return
        if (ntkAckRecoveryLaunchHolds.remove(key) != null) {
            Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_release path=$key")
        }
    }

    @JvmStatic
    fun isNtkAckRecoveryLaunchHeldForPath(path: String?): Boolean {
        return isNtkAckRecoveryLaunchHeld(path)
    }

    private fun isNtkAckRecoveryLaunchHeld(path: String?): Boolean {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return false
        val until = ntkAckRecoveryLaunchHolds[key] ?: return false
        val now = SystemClock.elapsedRealtime()
        if (until <= now) {
            ntkAckRecoveryLaunchHolds.remove(key, until)
            Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_expired path=$key")
            return false
        }
        return true
    }

    fun rememberEarlyNtkImageUrls(path: String?, urls: List<String>?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || urls.isNullOrEmpty()) return
        val trusted = urls.mapNotNull { it?.trim()?.takeIf { value -> isTrustedNtkImageUrl(value) } }
        if (trusted.isEmpty()) return
        earlyNtkImageUrls[key] = EarlyNtkImageUrls(Collections.unmodifiableList(ArrayList(trusted)), SystemClock.elapsedRealtime())
        trusted
            .asSequence()
            .filter { ntkGeneratedTarget(it)?.page in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES }
            .take(NTK_GENERATED_INITIAL_RECOVERY_PAGES)
            .forEach { rememberNtkGeneratedEpisodeExtension(it) }
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
        val exactActive = flights.containsKey(key) || foregroundStreams.containsKey(key)
        val allowVerifiedGeneratedVariant =
            ntkGeneratedEpisodeExtensionMatches(image) && !exactActive
        if (hasActiveFetch(manga, image) && !allowVerifiedGeneratedVariant) return false
        val finalFile = File(cacheDir(appContext), "$key.img")
        if (flights.containsKey(key)) return false
        val startedAt = SystemClock.elapsedRealtime()
        val generation = cacheGeneration.get()
        val task = FutureTask<ByteArray?> {
            try {
                val bytes = fetchForegroundBytes(
                    appContext,
                    manga,
                    image,
                    cancellation,
                    startedAt,
                    FOREGROUND_STREAM_RACE_ATTEMPTS,
                    anchorHedge,
                    generation
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
                    "ms=${SystemClock.elapsedRealtime() - startedAt},error=${throwableSummary(t)}"
                )
                null
            }
        }
        val existing = foregroundStreams.putIfAbsent(key, task)
        if (existing != null) {
            if (isStaleForegroundStream(key, startedAt) &&
                foregroundStreams.remove(key, existing)
            ) {
                existing.cancel(true)
                foregroundStreamStartedAt.remove(key)
                logCacheEvent("foreground_stream_stale_restart", manga, image, true, "activeStream=true")
                return startForegroundStreamFetch(context, manga, image, cancellation, anchorHedge)
            }
            if (existing.isDone && foregroundStreams.remove(key, existing)) {
                foregroundStreamStartedAt.remove(key)
                return startForegroundStreamFetch(context, manga, image, cancellation, anchorHedge)
            }
            logCacheEvent("foreground_stream_async_join", manga, image, true, "activeStream=true")
            ViewerWarmupManager.logMetric("reader_foreground_stream_async_join", 1L)
            return false
        }
        foregroundStreamStartedAt[key] = startedAt
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
            scheduleInitialGeneratedRecoveryHedge(
                appContext,
                manga,
                image,
                key,
                finalFile,
                task,
                cancellation,
                generation
            )
            true
        } catch (_: Exception) {
            foregroundStreams.remove(key, task)
            foregroundStreamStartedAt.remove(key)
            false
        }
    }

    private fun scheduleInitialGeneratedRecoveryHedge(
        context: Context,
        manga: Manga,
        image: String,
        key: String,
        finalFile: File,
        streamTask: FutureTask<ByteArray?>,
        cancellation: Cancellation?,
        generation: Long
    ) {
        val target = ntkGeneratedTarget(image) ?: return
        if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return
        try {
            foregroundRaceExecutor.execute {
                try {
                    Thread.sleep(NTK_GENERATED_INITIAL_RECOVERY_HEDGE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
                if (streamTask.isDone) return@execute
                try {
                    cancellation?.throwIfCancelled()
                } catch (_: IOException) {
                    return@execute
                }
                if (target.page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
                    !isInitialGeneratedAnchorFileReady(finalFile.parentFile, manga, image)
                ) {
                    return@execute
                }
                if (isUsableImage(finalFile) || flights.containsKey(key)) return@execute
                val recovery = FutureTask {
                    downloadInitialGeneratedRecovery(
                        context,
                        manga,
                        image,
                        finalFile,
                        cancellation,
                        generation,
                        target.page
                    )
                }
                val existing = flights.putIfAbsent(key, recovery)
                if (existing != null) return@execute
                logCacheEvent(
                    "download_initial_recovery_hedge_start",
                    manga,
                    image,
                    true,
                    "page=${target.page},delayMs=$NTK_GENERATED_INITIAL_RECOVERY_HEDGE_MS"
                )
                try {
                    recovery.run()
                    val result = recovery.get()
                    logCacheEvent(
                        "download_initial_recovery_hedge_done",
                        manga,
                        image,
                        true,
                        "page=${target.page},bytes=${result.length()}"
                    )
                } catch (t: Throwable) {
                    rethrowIfFatal(t)
                    logCacheEvent(
                        "download_initial_recovery_hedge_error",
                        manga,
                        image,
                        true,
                        "page=${target.page},error=${throwableSummary(t)}"
                    )
                } finally {
                    flights.remove(key, recovery)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun downloadInitialGeneratedRecovery(
        context: Context,
        manga: Manga,
        image: String,
        finalFile: File,
        cancellation: Cancellation?,
        generation: Long,
        page: Int
    ): File {
        if (page != 1) {
            return withNtkGeneratedFetchPermit(manga, image, true) {
                downloadAtomically(context, manga, image, finalFile, false, cancellation, generation)
            }
        }
        val completion = ExecutorCompletionService<InitialRecoveryResult>(foregroundRaceExecutor)
        val attempts = mutableListOf<Future<InitialRecoveryResult>>()
        val startedAt = SystemClock.elapsedRealtime()
        fun submit(label: String, delayMs: Long, anchorHedge: Boolean) {
            val future = completion.submit(Callable {
                if (delayMs > 0L) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
                cancellation?.throwIfCancelled()
                if (isUsableImage(finalFile)) {
                    return@Callable InitialRecoveryResult(label, finalFile)
                }
                logCacheEvent(
                    "download_initial_recovery_same_url_${label}_start",
                    manga,
                    image,
                    true,
                    "page=$page,delayMs=$delayMs,anchorHedge=$anchorHedge"
                )
                val file = withNtkGeneratedFetchPermit(manga, image, true) {
                    downloadAtomically(
                        context,
                        manga,
                        image,
                        finalFile,
                        false,
                        cancellation,
                        generation,
                        anchorHedge
                    )
                }
                InitialRecoveryResult(label, file)
            })
            attempts += future
        }
        submit("primary", 0L, anchorHedge = false)
        submit("hedge", NTK_GENERATED_INITIAL_RECOVERY_SAME_URL_HEDGE_MS, anchorHedge = true)
        var remaining = attempts.size
        var firstFailure: Throwable? = null
        while (remaining > 0) {
            val completed = try {
                completion.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while waiting for generated recovery").apply {
                    initCause(e)
                }
            }
            remaining--
            try {
                val result = completed.get()
                if (isUsableImage(result.file)) {
                    logCacheEvent(
                        "download_initial_recovery_same_url_${result.label}_hit",
                        manga,
                        image,
                        true,
                        "page=$page,ms=${SystemClock.elapsedRealtime() - startedAt},bytes=${result.file.length()}"
                    )
                    attempts.forEach { if (it !== completed) it.cancel(true) }
                    return result.file
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while waiting for generated recovery result").apply {
                    initCause(e)
                }
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                if (firstFailure == null) firstFailure = cause
                logCacheEvent(
                    "download_initial_recovery_same_url_error",
                    manga,
                    image,
                    true,
                    "page=$page,error=${throwableSummary(cause)}"
                )
            } catch (e: java.util.concurrent.CancellationException) {
                if (firstFailure == null) firstFailure = e
            }
        }
        throw IOException("Generated initial recovery failed", firstFailure)
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
        awaitForegroundStreamVariantFile(appContext, manga, image, true)?.file?.let {
            return null
        }
        val key = key(manga.baseMode, image)
        val startedAt = SystemClock.elapsedRealtime()
        val generation = cacheGeneration.get()
        val task = FutureTask<ByteArray?> {
            fetchForegroundBytes(
                appContext,
                manga,
                image,
                cancellation,
                startedAt,
                FOREGROUND_RACE_ATTEMPTS,
                anchorHedge,
                generation
            )
        }
        val existing = foregroundStreams.putIfAbsent(key, task)
        if (existing != null) {
            if (isStaleForegroundStream(key, startedAt) &&
                foregroundStreams.remove(key, existing)
            ) {
                existing.cancel(true)
                foregroundStreamStartedAt.remove(key)
            } else {
            ViewerWarmupManager.logMetric("reader_foreground_stream_join", 1L)
            logCacheEvent(
                "foreground_stream_join",
                manga,
                image,
                true,
                "activeStream=${!existing.isDone},doneStream=${existing.isDone}"
            )
            val joinTimeoutMs = foregroundStreamJoinTimeoutMs(image)
            val target = ntkGeneratedTarget(image)
            val streamStartedAt = foregroundStreamStartedAt[key]
            val streamAgeMs = streamStartedAt?.let { max(0L, startedAt - it) } ?: 0L
            val remainingJoinTimeoutMs =
                if (target != null &&
                    target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
                    streamStartedAt != null
                ) {
                    max(0L, joinTimeoutMs - streamAgeMs)
                } else {
                    joinTimeoutMs
                }
            val effectiveJoinTimeoutMs = if (remainingJoinTimeoutMs <= 0L &&
                target != null &&
                target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
                !existing.isDone
            ) {
                FOREGROUND_INITIAL_STREAM_LATE_JOIN_TIMEOUT_MS
            } else {
                remainingJoinTimeoutMs
            }
            if (effectiveJoinTimeoutMs <= 0L) {
                logCacheEvent(
                    "foreground_stream_join_skip_initial_recovery",
                    manga,
                    image,
                    true,
                    "page=${target?.page ?: 0},ageMs=$streamAgeMs,timeoutMs=$joinTimeoutMs"
                )
                ViewerWarmupManager.logMetric("reader_foreground_stream_join_skip_initial_recovery", 1L)
                return null
            }
            return try {
                decodeForegroundBytes(
                    existing,
                    startedAt,
                    autoCut,
                    allowSplit,
                    targetWidth,
                    boundedWait = true,
                    waitTimeoutMs = effectiveJoinTimeoutMs
                )
            } finally {
                if (existing.isDone) {
                    foregroundStreams.remove(key, existing)
                    foregroundStreamStartedAt.remove(key)
                }
            }
            }
        }
        foregroundStreamStartedAt[key] = startedAt
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
            foregroundStreamStartedAt.remove(key)
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
                foregroundStreamStartedAt.remove(key)
            }
        }
    }

    private fun isStaleForegroundStream(key: String, nowMs: Long): Boolean {
        val startedAt = foregroundStreamStartedAt[key] ?: return false
        return nowMs - startedAt > FOREGROUND_STREAM_STALE_MS
    }

    private fun fetchForegroundBytes(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        startedAt: Long,
        raceAttempts: Int = FOREGROUND_RACE_ATTEMPTS,
        anchorHedge: Boolean = false,
        generation: Long = cacheGeneration.get()
    ): ByteArray? {
        val useForegroundTransport = shouldUseForegroundTransportForForegroundBytes(image)
        if (!useForegroundTransport) {
            logCacheEvent(
                "foreground_stream_background_transport",
                manga,
                image,
                true,
                "page=${ntkGeneratedTarget(image)?.page ?: 0}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_background_transport", 1L)
        }
        val racedResponse = retryNtkGeneratedInitialFullRace(context, manga, image, cancellation)
        val prioritizeInitialHintedFullFetch = shouldPrioritizeHintedInitialGeneratedFullFetch(image)
        if (prioritizeInitialHintedFullFetch) {
            logCacheEvent(
                "foreground_stream_hinted_initial_priority",
                manga,
                image,
                true,
                "page=${ntkGeneratedTarget(image)?.page ?: 0}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_hinted_initial_priority", 1L)
        }
        val response = racedResponse ?: withNtkGeneratedFetchPermit(
            manga,
            image,
            useForegroundTransport || prioritizeInitialHintedFullFetch
        ) {
            requestWithNtkGeneratedFallback(
                context,
                manga,
                image,
                foreground = useForegroundTransport,
                cancellation = cancellation,
                foregroundRaceAttempts = raceAttempts,
                anchorHedge = anchorHedge && useForegroundTransport,
                skipInitialForegroundGeneratedRetryOnActiveFlight = useForegroundTransport
            )
        }
        response.use {
            val headersAt = SystemClock.elapsedRealtime()
            if (!it.isSuccessful) return null
            if (!validateNtkImageResponseUrl(manga, image, it, true, "foreground_stream")) return null
            val actualImage = it.request.url.toString()
            rememberNtkGeneratedEpisodeExtension(actualImage)
            rememberEarlyNtkGeneratedSuccess(manga, actualImage)
            val body = it.body ?: return null
            val contentLength = body.contentLength()
            if (contentLength > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val bytesAt = SystemClock.elapsedRealtime()
            val partialImage = it.header("x-mangaviewer-partial-image") == "1"
            if (partialImage) {
                ViewerWarmupManager.logMetric("reader_foreground_stream_partial_rejected", bytes.size.toLong())
                logCacheEvent(
                    "foreground_stream_partial_rejected",
                    manga,
                    image,
                    true,
                    "bytes=${bytes.size},contentLength=$contentLength"
                )
                waitCachedFullBytesAfterPartial(context, manga, image)?.let { fullBytes ->
                    ViewerWarmupManager.logMetric("reader_foreground_stream_partial_full_handoff", fullBytes.size.toLong())
                    logCacheEvent(
                        "foreground_stream_partial_full_handoff",
                        manga,
                        image,
                        true,
                        "bytes=${fullBytes.size}"
                    )
                    return fullBytes
                }
                return null
            }
            if (!partialImage) {
                val cacheImage = foregroundResponseCacheImage(image, it)
                cacheForegroundBytes(context, manga, cacheImage, bytes, generation)
                rememberVerifiedForegroundNtkAnchor(manga, cacheImage)
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

    private fun retryNtkGeneratedInitialFullRace(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return null
        if (!shouldTryNtkGeneratedExtensionFallback(image)) return null
        if (!ntkGeneratedEpisodeExtensionHinted(image)) return null
        if (ntkGeneratedEpisodeExtensionMatches(image)) return null
        val candidates = ntkGeneratedExtensionFallbacks(image)
            .filter { it != image }
            .distinct()
            .take(3)
        if (candidates.size <= 1) return null
        val startedAt = SystemClock.elapsedRealtime()
        val completion = ExecutorCompletionService<okhttp3.Response?>(foregroundRaceExecutor)
        val futures = ArrayList<Future<okhttp3.Response?>>(candidates.size)
        for (candidate in candidates) {
            futures += completion.submit(Callable {
                cancellation?.throwIfCancelled()
                logCacheEvent(
                    "generated_initial_full_race_start",
                    manga,
                    candidate,
                    true,
                    "page=${target.page},source=${image.substringAfterLast('/').takeLast(64)}"
                )
                val response = try {
                    requestForForegroundMode(
                        context,
                        manga,
                        candidate,
                        foreground = false,
                        cancellation = cancellation,
                        foregroundRaceAttempts = 1,
                        anchorHedge = false
                    )
                } catch (t: Throwable) {
                    rethrowIfFatal(t)
                    logCacheEvent(
                        "generated_initial_full_race_error",
                        manga,
                        candidate,
                        true,
                        "page=${target.page},error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
                    )
                    null
                }
                if (response != null &&
                    response.isSuccessful &&
                    acceptNtkGeneratedForegroundResponse(manga, candidate, response, true)
                ) {
                    rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
                    logCacheEvent(
                        "generated_initial_full_race_win",
                        manga,
                        candidate,
                        true,
                        "page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},code=${response.code}"
                    )
                    return@Callable response
                }
                logCacheEvent(
                    "generated_initial_full_race_miss",
                    manga,
                    candidate,
                    true,
                    "page=${target.page},code=${response?.code ?: 0},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                response?.close()
                null
            })
        }
        var remaining = futures.size
        val deadlineMs = startedAt + 2600L
        try {
            while (remaining > 0) {
                val waitMs = deadlineMs - SystemClock.elapsedRealtime()
                if (waitMs <= 0L) break
                val future = completion.poll(waitMs, TimeUnit.MILLISECONDS) ?: break
                remaining--
                val response = try {
                    future.get()
                } catch (_: ExecutionException) {
                    null
                }
                if (response != null) {
                    futures.forEach { if (it !== future) it.cancel(true) }
                    return response
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Generated initial full race interrupted")
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
        logCacheEvent(
            "generated_initial_full_race_timeout",
            manga,
            image,
            true,
            "page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},candidates=${candidates.size}"
        )
        return null
    }

    private fun waitCachedFullBytesAfterPartial(
        context: Context,
        manga: Manga,
        image: String
    ): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + 900L
        while (SystemClock.elapsedRealtime() < deadline) {
            val file = cachedImageFile(context.applicationContext, manga, image)
            if (file != null && file.length() in 1..MAX_DIRECT_STREAM_DECODE_BYTES) {
                return try {
                    file.readBytes()
                } catch (_: Exception) {
                    null
                }
            }
            try {
                Thread.sleep(35L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun shouldUseForegroundTransportForForegroundBytes(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return true
        return target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
    }

    private fun shouldPrioritizeHintedInitialGeneratedFullFetch(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun rememberVerifiedForegroundNtkAnchor(manga: Manga, image: String) {
        val target = ntkGeneratedTarget(image) ?: return
        if (target.page != 1) return
        val path = manga.ntkEpisodePath
        val publish = Runnable {
            try {
                if (earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L).isNotEmpty()) return@Runnable
                rememberEarlyNtkImageUrls(path, listOf(image))
                logCacheEvent("foreground_verified_anchor_handoff", manga, image, true, "path=$path")
            } catch (_: Exception) {
            }
        }
        try {
            foregroundRaceExecutor.execute {
                try {
                    Thread.sleep(NTK_VERIFIED_ANCHOR_HANDOFF_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
                publish.run()
            }
        } catch (_: Exception) {
            publish.run()
        }
    }

    private fun decodeForegroundBytes(
        task: FutureTask<ByteArray?>,
        startedAt: Long,
        autoCut: Boolean,
        allowSplit: Boolean,
        targetWidth: Int,
        boundedWait: Boolean,
        waitTimeoutMs: Long = FOREGROUND_STREAM_JOIN_TIMEOUT_MS
    ): Bitmap? {
        val bytes = try {
            if (boundedWait) task.get(waitTimeoutMs, TimeUnit.MILLISECONDS) else task.get()
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

    private fun foregroundStreamJoinTimeoutMs(image: String): Long {
        val target = ntkGeneratedTarget(image) ?: return FOREGROUND_STREAM_JOIN_TIMEOUT_MS
        if (target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            return FOREGROUND_INITIAL_STREAM_JOIN_TIMEOUT_MS
        }
        return FOREGROUND_STREAM_JOIN_TIMEOUT_MS
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

    private fun cacheForegroundBytes(context: Context, manga: Manga, image: String, bytes: ByteArray, generation: Long) {
        if (bytes.isEmpty()) return
        if (generation != cacheGeneration.get()) {
            logCacheEvent("foreground_cache_generation_skip", manga, image, true, "generation=$generation,current=${cacheGeneration.get()}")
            return
        }
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

    private fun validateNtkImageResponseUrl(
        manga: Manga,
        image: String,
        response: okhttp3.Response,
        foreground: Boolean,
        stage: String
    ): Boolean {
        val actual = response.request.url.toString()
        if (actual.isBlank()) return true
        val requestedTarget = ntkGeneratedTarget(image)
        val allowed = if (requestedTarget != null) {
            val actualTarget = ntkGeneratedTarget(actual)
            actualTarget != null &&
                isCompatibleNtkGeneratedPage(requestedTarget, actualTarget) &&
                !isDisallowedNtkImageAssetUrl(actual)
        } else {
            isTrustedNtkImageUrl(actual) && !isDisallowedNtkImageAssetUrl(actual)
        }
        if (!allowed) {
            logCacheEvent(
                "${stage}_reject_untrusted_actual_url",
                manga,
                image,
                foreground,
                "actual=${safeImageName(actual)},requested=${safeImageName(image)}"
            )
            ViewerWarmupManager.logMetric("reader_ntk_reject_untrusted_actual_url", 1L)
        }
        return allowed
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
        cachedImageFile(appContext, manga, image)?.let { cached ->
            cached.setLastModified(System.currentTimeMillis())
            val variant = cached.name != finalFile.name
            logCacheEvent(
                if (variant) "disk_hit_variant" else "disk_hit",
                manga,
                image,
                foreground,
                "bytes=${cached.length()}"
            )
            ViewerWarmupManager.logMetric("reader_image_cache_disk_hit", 1L)
            if (variant) ViewerWarmupManager.logMetric("reader_image_cache_variant_disk_hit", 1L)
            return cached
        }
        val streamAwait = awaitForegroundStreamFile(key, finalFile, manga, image, foreground)
        streamAwait?.file?.let { return it }
        awaitForegroundStreamVariantFile(appContext, manga, image, foreground)?.file?.let { return it }
        val bypassForegroundDownload = streamAwait?.bypassForegroundDownload == true
        val downloadForeground = foreground && !bypassForegroundDownload
        val priorityFullDownload = foreground &&
            bypassForegroundDownload &&
            ntkGeneratedTarget(image) != null
        val generation = cacheGeneration.get()
        val task = FutureTask {
            withNtkGeneratedFetchPermit(manga, image, downloadForeground || priorityFullDownload) {
                downloadAtomically(appContext, manga, image, finalFile, downloadForeground, cancellation, generation)
            }
        }
        var existing = flights.putIfAbsent(key, task)
        if (existing != null && shouldSupersedeInitialGeneratedFlight(image, foreground, priorityFullDownload)) {
            if (flights.remove(key, existing)) {
                existing.cancel(true)
                logCacheEvent(
                    "flight_supersede_initial_generated",
                    manga,
                    image,
                    foreground,
                    "activeFlight=true,foregroundDownload=$downloadForeground"
                )
                ViewerWarmupManager.logMetric("reader_image_cache_flight_supersede_initial_generated", 1L)
                existing = flights.putIfAbsent(key, task)
            }
        }
        if (existing != null) {
            logCacheEvent(
                "flight_join",
                manga,
                image,
                foreground,
                "activeFlight=true,foregroundDownload=$downloadForeground"
            )
            ViewerWarmupManager.logMetric("reader_image_cache_flight_join", 1L)
        } else {
            logCacheEvent(
                "download_start",
                manga,
                image,
                foreground,
                "activeFlight=false,foregroundDownload=$downloadForeground"
            )
            ViewerWarmupManager.logMetric("reader_image_cache_download_start", 1L)
        }
        val running = existing ?: task.also { it.run() }
        return try {
            val result = running.get()
            if (bypassForegroundDownload) {
                logCacheEvent(
                    "download_initial_recovery_done",
                    manga,
                    image,
                    true,
                    "foregroundDownload=false,bytes=${result.length()}"
                )
            }
            result
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
    ): ForegroundStreamAwait? {
        val stream = foregroundStreams[key] ?: return null
        val cacheDirectory = finalFile.parentFile
        if (shouldSkipForegroundStreamWaitForGeneratedFullDownload(key, cacheDirectory, manga, image, foreground, stream)) {
            return ForegroundStreamAwait(bypassForegroundDownload = true)
        }
        logCacheEvent("foreground_stream_wait", manga, image, foreground, "activeStream=true")
        ViewerWarmupManager.logMetric("reader_foreground_stream_wait", 1L)
        val target = ntkGeneratedTarget(image)
        val streamStartedAt = foregroundStreamStartedAt[key]
        val streamAgeMs = streamStartedAt?.let { max(0L, SystemClock.elapsedRealtime() - it) } ?: 0L
        val baseWaitTimeoutMs = foregroundStreamJoinTimeoutMs(image)
        val waitTimeoutMs = if (
            target != null &&
            target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            streamAgeMs >= baseWaitTimeoutMs &&
            !stream.isDone
        ) {
            FOREGROUND_INITIAL_STREAM_LATE_JOIN_TIMEOUT_MS
        } else {
            baseWaitTimeoutMs
        }
        try {
            stream.get(waitTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: java.util.concurrent.TimeoutException) {
            logCacheEvent(
                "foreground_stream_wait_timeout",
                manga,
                image,
                foreground,
                "activeStream=true,timeoutMs=$waitTimeoutMs"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_wait_timeout", 1L)
            if (shouldBypassForegroundStreamForInitialRecovery(cacheDirectory, manga, image, foreground)) {
                logCacheEvent(
                    "foreground_stream_wait_timeout_initial_recovery",
                    manga,
                    image,
                    foreground,
                    "activeStream=true,timeoutMs=$waitTimeoutMs"
                )
                return ForegroundStreamAwait(bypassForegroundDownload = true)
            }
            if (isStaleForegroundStream(key, SystemClock.elapsedRealtime()) &&
                foregroundStreams.remove(key, stream)
            ) {
                stream.cancel(true)
                foregroundStreamStartedAt.remove(key)
                logCacheEvent("foreground_stream_wait_stale_drop", manga, image, foreground, "activeStream=true")
            }
            return null
        } catch (_: ExecutionException) {
            return null
        }
        if (!isUsableImage(finalFile)) return null
        finalFile.setLastModified(System.currentTimeMillis())
        logCacheEvent("foreground_stream_wait_hit", manga, image, foreground, "bytes=${finalFile.length()}")
        ViewerWarmupManager.logMetric("reader_foreground_stream_wait_hit", 1L)
        return ForegroundStreamAwait(file = finalFile)
    }

    private fun awaitForegroundStreamVariantFile(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean
    ): ForegroundStreamAwait? {
        val dir = cacheDir(context)
        for (candidate in ntkGeneratedCacheLookupImages(image).drop(1)) {
            val candidateKey = key(manga.baseMode, candidate)
            val candidateFile = File(dir, "$candidateKey.img")
            val awaited = awaitForegroundStreamFile(candidateKey, candidateFile, manga, candidate, foreground)
            val file = awaited?.file ?: continue
            logCacheEvent(
                "foreground_stream_wait_variant_hit",
                manga,
                candidate,
                foreground,
                "requested=${image.substringAfterLast('/').takeLast(64)},bytes=${file.length()}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_wait_variant_hit", 1L)
            return ForegroundStreamAwait(file = file)
        }
        return null
    }

    private fun shouldSkipForegroundStreamWaitForGeneratedFullDownload(
        key: String,
        cacheDirectory: File?,
        manga: Manga,
        image: String,
        foreground: Boolean,
        stream: FutureTask<ByteArray?>
    ): Boolean {
        if (!foreground || stream.isDone) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            logCacheEvent(
                "foreground_stream_wait_skip_generated_full_download",
                manga,
                image,
                true,
                "page=${target.page}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_wait_skip_generated_full_download", target.page.toLong())
            return true
        }
        val hinted = ntkGeneratedImageWithHintedExtension(image)
        if (hinted != image) {
            logCacheEvent(
                "foreground_stream_wait_skip_hinted_extension",
                manga,
                hinted,
                true,
                "page=${target.page},source=${image.substringAfterLast('/').takeLast(64)}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_wait_skip_hinted_extension", target.page.toLong())
            return true
        }
        val startedAt = foregroundStreamStartedAt[key] ?: return false
        val ageMs = SystemClock.elapsedRealtime() - startedAt
        val waitTimeoutMs = foregroundStreamJoinTimeoutMs(image)
        if (ageMs < waitTimeoutMs) return false
        if (!shouldBypassForegroundStreamForInitialRecovery(cacheDirectory, manga, image, foreground)) return false
        logCacheEvent(
            "foreground_stream_wait_skip_initial_recovery",
            manga,
            image,
            true,
            "page=${target.page},ageMs=$ageMs,timeoutMs=$waitTimeoutMs"
        )
        ViewerWarmupManager.logMetric("reader_foreground_stream_wait_skip_initial_recovery", 1L)
        return true
    }

    private fun shouldSupersedeInitialGeneratedFlight(
        image: String,
        foreground: Boolean,
        priorityFullDownload: Boolean
    ): Boolean {
        if (!foreground || !priorityFullDownload) return false
        val target = ntkGeneratedTarget(image) ?: return false
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
    }

    private fun shouldBypassForegroundStreamForInitialRecovery(
        cacheDirectory: File?,
        manga: Manga,
        image: String,
        foreground: Boolean
    ): Boolean {
        if (!foreground) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        if (target.page == 1) return true
        return isInitialGeneratedAnchorFileReady(cacheDirectory, manga, image)
    }

    private fun isInitialGeneratedAnchorFileReady(
        cacheDirectory: File?,
        manga: Manga,
        image: String
    ): Boolean {
        val dir = cacheDirectory ?: return false
        val anchorImage = ntkGeneratedPageImage(image, 1) ?: return false
        return isUsableImage(File(dir, "${key(manga.baseMode, anchorImage)}.img"))
    }

    private data class ForegroundStreamAwait(
        val file: File? = null,
        val bypassForegroundDownload: Boolean = false
    )

    private fun cacheDir(context: Context): File {
        return File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    }

    private fun key(baseMode: Int, image: String): String {
        val normalized = Utils.viewerImageRequestUrl(image, baseMode)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseMode|$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cachedImageFile(context: Context, manga: Manga, image: String): File? {
        val dir = cacheDir(context)
        for (candidate in ntkGeneratedCacheLookupImages(image)) {
            val file = File(dir, "${key(manga.baseMode, candidate)}.img")
            if (isUsableImage(file)) return file
        }
        return null
    }

    private fun ntkGeneratedCacheLookupImages(image: String): List<String> {
        if (ntkGeneratedTarget(image) == null) return listOf(image)
        val candidates = ArrayList<String>()
        candidates.add(image)
        val hinted = ntkGeneratedImageWithHintedExtension(image)
        if (hinted != image) candidates.add(hinted)
        candidates.addAll(ntkGeneratedExtensionFallbacks(image))
        return candidates.distinct()
    }

    private fun ntkGeneratedPageImage(image: String, page: Int): String? {
        ntkGeneratedTarget(image) ?: return null
        val pageName = page.coerceAtLeast(1).toString().padStart(3, '0')
        return Regex("/p\\d{3}(?=\\.)", RegexOption.IGNORE_CASE)
            .replace(image, "/p$pageName")
    }

    private fun earlyNtkPathKey(path: String?): String {
        return path?.trim().orEmpty()
    }

    private fun isTrustedNtkImageUrl(value: String): Boolean {
        val lower = value.lowercase()
        if (isDisallowedNtkImageAssetUrl(lower)) return false
        if (lower.startsWith("toonflix.app/") || lower.startsWith("//toonflix.app/")) return true
        if (Regex("^fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            Regex("^//fvcdn\\d*\\.com/").containsMatchIn(lower)
        ) return true
        return try {
            val host = Uri.parse(value).host?.lowercase().orEmpty()
            host == "toonflix.app" || host.endsWith(".toonflix.app") ||
                Regex("^fvcdn\\d*\\.com$").matches(host)
        } catch (_: Exception) {
            false
        }
    }

    private fun isDisallowedNtkImageAssetUrl(value: String): Boolean {
        val lower = value.lowercase()
        val path = try {
            Uri.parse(lower).path.orEmpty()
        } catch (_: Exception) {
            lower.substringBefore('?').substringBefore('#')
        }
        return path.contains("/cdn-cgi/") ||
            path.contains("/challenge") ||
            path.contains("/turnstile") ||
            path.contains("/cloudflare") ||
            path.contains("/verification") ||
            path.contains("/captcha") ||
            path.contains("/banner") ||
            path.contains("/advert") ||
            path.contains("/ads/") ||
            path.contains("/ad/") ||
            Regex("(^|[-_/])(ad|ads|banner|advert)([-_/]|$)").containsMatchIn(path)
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
        val requestUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
        if (!isNtkGeneratedImageUrl(requestUrl)) return block()
        val startedAt = SystemClock.elapsedRealtime()
        val gate = if (foreground) ntkGeneratedForegroundFetchGate else ntkGeneratedBackgroundFetchGate
        val target = ntkGeneratedTarget(requestUrl)
        val initialForeground = foreground && target != null && target.page in 1..3
        val acquired = if (initialForeground) {
            try {
                gate.tryAcquire(250L, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while waiting for generated image gate").apply {
                    initCause(e)
                }
            }
        } else {
            gate.acquire()
            true
        }
        val waitedMs = SystemClock.elapsedRealtime() - startedAt
        if (!acquired) {
            logCacheEvent(
                "ntk_generated_fetch_gate_bypass",
                manga,
                image,
                foreground,
                "page=${target?.page ?: 0},ms=$waitedMs,available=${gate.availablePermits()}"
            )
            return block()
        }
        if (waitedMs > 100L) {
            logCacheEvent(
                "ntk_generated_fetch_gate_wait",
                manga,
                image,
                foreground,
                "ms=$waitedMs,available=${gate.availablePermits()}"
            )
        }
        return try {
            block()
        } finally {
            gate.release()
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
        cancellation: Cancellation? = null,
        generation: Long = cacheGeneration.get(),
        anchorHedge: Boolean = false
    ): File {
        val tmp = File(finalFile.parentFile, "${finalFile.name}.part.${System.nanoTime()}")
        val startedAt = SystemClock.elapsedRealtime()
        try {
            cancellation?.throwIfCancelled()
            val requestUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
            requestWithNtkGeneratedFallback(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts = if (foreground && ntkGeneratedTarget(image) != null) {
                    FOREGROUND_RACE_ATTEMPTS
                } else if (foreground) {
                    1
                } else {
                    FOREGROUND_RACE_ATTEMPTS
                },
                anchorHedge = anchorHedge
            ).use { response ->
                val headersAt = SystemClock.elapsedRealtime()
                if (!response.isSuccessful) throw java.io.IOException("Image request failed: ${response.code} url=$requestUrl")
                if (!validateNtkImageResponseUrl(manga, image, response, foreground, "download")) {
                    throw java.io.IOException("Rejected untrusted NTK image response url")
                }
                rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
                val body = response.body ?: throw java.io.IOException("Empty image body")
                val contentLength = body.contentLength()
                if (response.header("x-mangaviewer-partial-image") == "1") {
                    throw java.io.IOException("Partial foreground image response is not cacheable")
                }
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
            if (generation != cacheGeneration.get()) {
                throw java.io.IOException("Image cache generation invalidated")
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
        if (shouldBypassQuicForHintedInitialGeneratedFull(image, foregroundPriority)) {
            requestBuilder.addHeader("X-MangaViewer-No-Quic", "1")
        }
        return requestBuilder.build()
    }

    private fun shouldBypassQuicForHintedInitialGeneratedFull(
        image: String,
        foregroundPriority: Boolean
    ): Boolean {
        if (foregroundPriority) return false
        val target = ntkGeneratedTarget(image) ?: return false
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun requestWithNtkGeneratedFallback(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean = false,
        cancellation: Cancellation? = null,
        foregroundRaceAttempts: Int = FOREGROUND_RACE_ATTEMPTS,
        anchorHedge: Boolean = false,
        skipInitialForegroundGeneratedRetryOnActiveFlight: Boolean = false
    ): okhttp3.Response {
        val activeImage = canonicalNtkGeneratedImageForActiveEpisode(manga, image)
        if (activeImage != null && activeImage != image) {
            logCacheEvent(
                "generated_active_episode_rewrite",
                manga,
                image,
                foreground,
                "replacement=${activeImage.substringAfterLast('/').takeLast(80)}"
            )
            ViewerWarmupManager.logMetric("ntk_generated_active_episode_rewrite", 1L)
            return requestWithNtkGeneratedFallback(
                context,
                manga,
                activeImage,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge,
                skipInitialForegroundGeneratedRetryOnActiveFlight
            )
        }
        val initialTarget = ntkGeneratedTarget(image)
        val foregroundApiFallbackTask: FutureTask<List<String>?>? =
            if (foreground && initialTarget != null)
                startNtkApiFallbackImages(context, manga, initialTarget)
            else null
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
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                val e = asIOException(t)
                hintedFailure = e
                null
            }
            if (hintedResponse != null && hintedResponse.isSuccessful &&
                acceptNtkGeneratedForegroundResponse(manga, hintedImage, hintedResponse, foreground)
            ) {
                rememberNtkGeneratedEpisodeExtension(hintedResponse.request.url.toString())
                return hintedResponse
            }
            val hintedCode = hintedResponse?.code ?: imageFailureCode(hintedFailure)
            hintedResponse?.close()
            if (hintedCode != 404) {
                if (foreground) {
                    retryNtkGeneratedExtensionCandidates(
                        context,
                        manga,
                        hintedImage,
                        foreground,
                        cancellation,
                        foregroundRaceAttempts,
                        anchorHedge
                    )?.let { return it }
                }
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
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            if (!shouldTryNtkGeneratedExtensionFallback(image)) throw asIOException(t)
            initialFailure = asIOException(t)
            null
        }
        if (response == null && isCancellationFailure(initialFailure)) {
            throw initialFailure ?: java.io.InterruptedIOException("Reader image request cancelled")
        }
        if (response != null && response.isSuccessful &&
            acceptNtkGeneratedForegroundResponse(manga, image, response, foreground)
        ) {
            rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
            return response
        }
        if (response != null && (response.isSuccessful || !shouldTryNtkGeneratedExtensionFallback(image))) {
            if (response.isSuccessful) response.close()
            else return response
        }
        if (response == null && !shouldTryNtkGeneratedExtensionFallback(image)) {
            throw IOException("Generated image request failed before fallback: $image")
        }
        if (response == null &&
            imageFailureCode(initialFailure) != 404 &&
            shouldRetryInitialGeneratedSameUrlBeforeExtensionFallback(foreground, image)
        ) {
            val target = ntkGeneratedTarget(image)
            logCacheEvent(
                "generated_initial_same_url_retry_before_extension",
                manga,
                image,
                true,
                "page=${target?.page ?: 0},background=true,reason=no_extension_hint"
            )
            ViewerWarmupManager.logMetric(
                "ntk_generated_initial_same_url_retry_before_extension",
                (target?.page ?: 0).toLong()
            )
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
                if (foreground) {
                    throwIfInitialForegroundRetryHasActiveRecovery(
                        manga,
                        image,
                        foreground,
                        skipInitialForegroundGeneratedRetryOnActiveFlight
                    )
                    retryOriginalNtkGeneratedImage(
                        context,
                        manga,
                        image,
                        foreground,
                        cancellation,
                        1,
                        anchorHedge,
                        initialFailure
                    )?.let { return it }
                    val apiFallbackTask = foregroundApiFallbackTask ?: target?.let { startNtkApiFallbackImages(context, manga, it) }
                    if (apiFallbackTask != null) {
                        requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                            ?.let { return it }
                        retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                            ?.let { return it }
                    }
                    retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
                }
            if (shouldPreferGeneratedApiBeforeOriginalRetry(foreground, initialFailure)) {
                val apiFallbackTask = foregroundApiFallbackTask ?: target?.let { startNtkApiFallbackImages(context, manga, it) }
                if (apiFallbackTask != null) {
                    requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                        ?.let { return it }
                    retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                        ?.let { return it }
                }
            }
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
            if (!foreground) {
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
            }
            retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
            retryNtkGeneratedViaApiFallback(context, manga, image, foreground, foregroundApiFallbackTask, cancellation)
                ?.let { return it }
            return requestForForegroundMode(context, manga, image, foreground = false, cancellation = cancellation)
        }
        if (response == null && foreground && imageFailureCode(initialFailure) != 404) {
            val target = ntkGeneratedTarget(image)
            throwIfInitialForegroundRetryHasActiveRecovery(
                manga,
                image,
                foreground,
                skipInitialForegroundGeneratedRetryOnActiveFlight
            )
            val directFallbackRace = target != null &&
                target.page > NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
            val apiFallbackTask = foregroundApiFallbackTask ?: target?.let { startNtkApiFallbackImages(context, manga, it) }
            if (directFallbackRace && apiFallbackTask != null) {
                requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = true)
                    ?.let { return it }
            }
            retryOriginalNtkGeneratedImage(
                context,
                manga,
                image,
                foreground,
                cancellation,
                1,
                anchorHedge,
                initialFailure
            )?.let { return it }
            if (!directFallbackRace && apiFallbackTask != null) {
                requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                    ?.let { return it }
                retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                    ?.let { return it }
            }
            retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)
                ?.let { return it }
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
            retryNtkGeneratedViaApiFallback(context, manga, image, foreground, foregroundApiFallbackTask, cancellation)?.let { return it }
            return requestForForegroundMode(context, manga, image, foreground, cancellation)
        }
        var apiFallbackTask = foregroundApiFallbackTask ?: if (generated404) target?.let {
            startNtkApiFallbackImages(context, manga, it)
        } else null
        if (foreground && generated404) {
            retryNtkGeneratedExtensionFullRace(context, manga, image, cancellation)?.let { return it }
        }
        if (foreground && generated404 && apiFallbackTask != null) {
            requestForegroundGeneratedRace(context, manga, image, apiFallbackTask)?.let { return it }
        }
        for (candidate in ntkGeneratedExtensionFallbacks(image)) {
            val fallback = try {
                requestForForegroundMode(context, manga, candidate, foreground, cancellation)
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                val e = asIOException(t)
                if (imageFailureCode(e) == 404) {
                    generated404 = true
                    if (apiFallbackTask == null)
                        apiFallbackTask = target?.let { startNtkApiFallbackImages(context, manga, it) }
                }
                continue
            }
            if (fallback.isSuccessful && acceptNtkGeneratedForegroundResponse(manga, candidate, fallback, foreground)) {
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

    private fun retryNtkGeneratedExtensionCandidates(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        cancellation: Cancellation?,
        foregroundRaceAttempts: Int,
        anchorHedge: Boolean
    ): okhttp3.Response? {
        if (!shouldTryNtkGeneratedExtensionFallback(image)) return null
        val target = ntkGeneratedTarget(image) ?: return null
        for (candidate in ntkGeneratedExtensionFallbacks(image)) {
            cancellation?.throwIfCancelled()
            logCacheEvent(
                "generated_extension_fast_retry_start",
                manga,
                candidate,
                foreground,
                "page=${target.page},source=${image.substringAfterLast('/').takeLast(64)}"
            )
            val response = try {
                requestForForegroundMode(
                    context,
                    manga,
                    candidate,
                    foreground,
                    cancellation,
                    foregroundRaceAttempts,
                    anchorHedge
                )
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                logCacheEvent(
                    "generated_extension_fast_retry_error",
                    manga,
                    candidate,
                    foreground,
                    "page=${target.page},error=${t.javaClass.simpleName}"
                )
                continue
            }
            if (response.isSuccessful && acceptNtkGeneratedForegroundResponse(manga, candidate, response, foreground)) {
                rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
                logCacheEvent(
                    "generated_extension_fast_retry_hit",
                    manga,
                    candidate,
                    foreground,
                    "page=${target.page},code=${response.code}"
                )
                return response
            }
            logCacheEvent(
                "generated_extension_fast_retry_miss",
                manga,
                candidate,
                foreground,
                "page=${target.page},code=${response.code}"
            )
            response.close()
        }
        return null
    }

    private fun retryNtkGeneratedExtensionFullRace(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?
    ): okhttp3.Response? {
        if (!shouldTryNtkGeneratedExtensionFallback(image)) return null
        val target = ntkGeneratedTarget(image) ?: return null
        val candidates = ntkGeneratedExtensionFallbacks(image).take(3)
        if (candidates.isEmpty()) return null
        val startedAt = SystemClock.elapsedRealtime()
        val completion = ExecutorCompletionService<okhttp3.Response?>(foregroundRaceExecutor)
        val futures = ArrayList<Future<okhttp3.Response?>>(candidates.size)
        for (candidate in candidates) {
            futures += completion.submit(Callable {
                cancellation?.throwIfCancelled()
                logCacheEvent(
                    "generated_extension_full_race_start",
                    manga,
                    candidate,
                    true,
                    "page=${target.page},source=${image.substringAfterLast('/').takeLast(64)}"
                )
                val response = try {
                    requestForForegroundMode(
                        context,
                        manga,
                        candidate,
                        foreground = false,
                        cancellation = cancellation,
                        foregroundRaceAttempts = 1,
                        anchorHedge = false
                    )
                } catch (t: Throwable) {
                    rethrowIfFatal(t)
                    logCacheEvent(
                        "generated_extension_full_race_error",
                        manga,
                        candidate,
                        true,
                        "page=${target.page},error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
                    )
                    null
                }
                if (response != null &&
                    response.isSuccessful &&
                    acceptNtkGeneratedForegroundResponse(manga, candidate, response, true)
                ) {
                    rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
                    logCacheEvent(
                        "generated_extension_full_race_win",
                        manga,
                        candidate,
                        true,
                        "page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},code=${response.code}"
                    )
                    return@Callable response
                }
                logCacheEvent(
                    "generated_extension_full_race_miss",
                    manga,
                    candidate,
                    true,
                    "page=${target.page},code=${response?.code ?: 0},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                response?.close()
                null
            })
        }
        var remaining = futures.size
        val deadlineMs = startedAt + 2200L
        try {
            while (remaining > 0) {
                val waitMs = deadlineMs - SystemClock.elapsedRealtime()
                if (waitMs <= 0L) break
                val future = completion.poll(waitMs, TimeUnit.MILLISECONDS) ?: break
                remaining--
                val response = try {
                    future.get()
                } catch (e: ExecutionException) {
                    null
                }
                if (response != null) {
                    futures.forEach { if (it !== future) it.cancel(true) }
                    return response
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Generated extension full race interrupted")
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
        logCacheEvent(
            "generated_extension_full_race_timeout",
            manga,
            image,
            true,
            "page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},candidates=${candidates.size}"
        )
        return null
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
        val retry = requestOriginalNtkGeneratedRetry(
            context,
            manga,
            image,
            foreground,
            cancellation,
            foregroundRaceAttempts,
            anchorHedge,
            target.page,
            "generated_original_retry"
        ) ?: return null
        if (retry.isSuccessful && acceptNtkGeneratedForegroundResponse(manga, image, retry, foreground)) {
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

    private fun throwIfInitialForegroundRetryHasActiveRecovery(
        manga: Manga,
        image: String,
        foreground: Boolean,
        enabled: Boolean
    ) {
        if (!enabled || !foreground) return
        val target = ntkGeneratedTarget(image) ?: return
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return
        val cacheKey = key(manga.baseMode, image)
        if (!flights.containsKey(cacheKey)) return
        logCacheEvent(
            "generated_original_retry_skip_active_recovery",
            manga,
            image,
            true,
            "page=${target.page},activeFlight=true"
        )
        ViewerWarmupManager.logMetric("ntk_generated_initial_retry_skip_active_recovery", target.page.toLong())
        throw IOException("Generated foreground retry skipped while recovery flight is active")
    }

    private fun shouldRetryInitialGeneratedSameUrlBeforeExtensionFallback(
        foreground: Boolean,
        image: String
    ): Boolean {
        if (foreground || ntkGeneratedEpisodeExtensionMatches(image)) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        return shouldTryNtkGeneratedExtensionFallback(image)
    }

    private fun requestOriginalNtkGeneratedRetry(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        cancellation: Cancellation?,
        foregroundRaceAttempts: Int,
        anchorHedge: Boolean,
        page: Int,
        stage: String
    ): okhttp3.Response? {
        try {
            return requestForForegroundMode(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge
            )
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            val transientForeground = shouldRetryForegroundGeneratedTransient(foreground, page, t)
            logCacheEvent(
                "${stage}_error",
                manga,
                image,
                foreground,
                "page=$page,error=${t.javaClass.simpleName},transientForeground=$transientForeground," +
                    "summary=${throwableSummary(t)}"
            )
            if (!transientForeground) return null
            ViewerWarmupManager.logMetric("ntk_generated_foreground_transient_retry", page.toLong())
            logCacheEvent(
                "${stage}_transient_again_start",
                manga,
                image,
                foreground,
                "page=$page,delayMs=$NTK_GENERATED_INITIAL_TRANSIENT_RETRY_DELAY_MS"
            )
            return try {
                cancellation?.throwIfCancelled()
                Thread.sleep(NTK_GENERATED_INITIAL_TRANSIENT_RETRY_DELAY_MS)
                cancellation?.throwIfCancelled()
                requestForForegroundMode(
                    context,
                    manga,
                    image,
                    foreground,
                    cancellation,
                    foregroundRaceAttempts,
                    anchorHedge
                )
            } catch (again: Throwable) {
                rethrowIfFatal(again)
                logCacheEvent(
                    "${stage}_transient_again_error",
                    manga,
                    image,
                    foreground,
                    "page=$page,error=${again.javaClass.simpleName},summary=${throwableSummary(again)}"
                )
                null
            }
        }
    }

    private fun acceptNtkGeneratedForegroundResponse(
        manga: Manga,
        image: String,
        response: Response,
        foreground: Boolean
    ): Boolean {
        if (!foreground || !shouldTryNtkGeneratedExtensionFallback(image)) return true
        if (!validateNtkImageResponseUrl(manga, image, response, true, "generated_accept")) return false
        return try {
            val bytes = response.peekBody(512L).bytes()
            val accepted = looksLikeImage(bytes)
            if (!accepted) {
                logCacheEvent(
                    "generated_foreground_reject_body",
                    manga,
                    image,
                    true,
                    "code=${response.code},bytes=${bytes.size},type=${response.body?.contentType()}"
                )
            }
            accepted
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "generated_foreground_reject_error",
                manga,
                image,
                true,
                "code=${response.code},error=${t.javaClass.simpleName}"
            )
            false
        }
    }

    private fun isCancellationFailure(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is java.io.InterruptedIOException) return true
            current = current.cause
        }
        return false
    }

    private fun shouldRetryForegroundGeneratedTransient(
        foreground: Boolean,
        page: Int,
        error: Throwable?
    ): Boolean {
        if (!foreground || page <= 0) return false
        if (isCancellationFailure(error)) return false
        var current = error
        while (current != null) {
            if (current is java.net.SocketException) return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains("connection reset") ||
                message.contains("stream was reset") ||
                message.contains("unexpected end of stream") ||
                message.contains("connection shutdown")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun rethrowIfFatal(error: Throwable) {
        if (error is ThreadDeath || error is VirtualMachineError) throw error
    }

    private fun asIOException(error: Throwable): IOException {
        return if (error is IOException) error else IOException(error.javaClass.simpleName, error)
    }

    private fun throwableSummary(error: Throwable): String {
        val names = ArrayList<String>()
        var current: Throwable? = error
        while (current != null && names.size < 4) {
            val message = current.message.orEmpty()
                .replace(',', ' ')
                .replace('\n', ' ')
                .take(80)
            names.add(
                if (message.isBlank()) current.javaClass.simpleName
                else "${current.javaClass.simpleName}:$message"
            )
            current = current.cause
        }
        return names.joinToString(">")
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

    private fun shouldPreferGeneratedApiBeforeOriginalRetry(foreground: Boolean, error: Throwable?): Boolean {
        if (!foreground || error == null) return false
        val code = imageFailureCode(error)
        if (isCancellationFailure(error) || code == 0 || code == 404) return false
        return true
    }

    private fun isLikelyPastGeneratedTail(manga: Manga, image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        val knownCount = manga.ntkImageCount
        return knownCount > 0 && target.page > knownCount
    }

    private fun isLikelyInvalidGeneratedTail(manga: Manga, image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        val knownCount = manga.ntkImageCount
        return knownCount > 0 && target.page >= max(18, knownCount - 4)
    }

    private fun requestForegroundGeneratedRace(
        context: Context,
        manga: Manga,
        image: String,
        apiFallbackTask: FutureTask<List<String>?>,
        includeDirectRetries: Boolean = true
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "ntk_generated_image_race_start page=${target.page},path=${target.path},direct=$includeDirectRetries")
        val completion = ExecutorCompletionService<Response?>(foregroundRaceExecutor)
        var submitted = 0
        if (includeDirectRetries) {
            completion.submit(Callable {
                requestGeneratedDirectFallbacks(context, manga, image).also { response ->
                    Log.d(
                        TAG,
                        "ntk_generated_image_race_direct page=${target.page},code=${response?.code ?: 0},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
            })
            submitted++
        }
        completion.submit(Callable {
            val images = fetchNtkApiFallbackImages(context, manga, target, apiFallbackTask) ?: return@Callable null
            val replacement = images.getOrNull(target.page - 1) ?: image
            val sameUrl = replacement == image
            val replacementTarget = ntkGeneratedTarget(replacement)
            val sameGeneratedPage = replacementTarget != null &&
                isCompatibleNtkGeneratedPage(target, replacementTarget)
            ViewerWarmupManager.logMetric("ntk_generated_image_api_fallback_race", target.page.toLong())
            Log.d(
                TAG,
                "ntk_generated_image_race_api_ready page=${target.page},sameUrl=$sameUrl,images=${images.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            if (!sameUrl && !sameGeneratedPage) {
                Log.d(
                    TAG,
                    "ntk_generated_image_race_api_skip_mismatch page=${target.page},replacement=${replacement.substringAfterLast('/').takeLast(80)},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return@Callable null
            }
            try {
                requestForForegroundMode(context, manga, replacement, foreground = false).also { response ->
                    Log.d(
                        TAG,
                        "ntk_generated_image_race_api_response page=${target.page},code=${response.code},sameUrl=$sameUrl,ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                null
            }
        })
        submitted++
        repeat(submitted) {
            val response = try {
                completion.poll(if (includeDirectRetries) 2200L else 1700L, TimeUnit.MILLISECONDS)?.get()
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
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            null
        }
        if (first != null && first.isSuccessful) return first
        first?.close()
        for (candidate in ntkGeneratedExtensionFallbacks(image)) {
            val fallback = try {
                request(context, manga, candidate)
            } catch (t: Throwable) {
                rethrowIfFatal(t)
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
        if (isNtkAckRecoveryLaunchHeld(path)) {
            logCacheEvent(
                "generated_webview_ack_recovery_skip",
                manga,
                image,
                true,
                "path=$path,reason=launch_first_drawable_hold"
            )
            return null
        }
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
            task.get(if (foreground) 5L else 26L, TimeUnit.SECONDS)
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
        } catch (t: Throwable) {
            rethrowIfFatal(t)
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
        val path = ntkFallbackKeyPath(manga, target)
        if (foreground) {
            logCacheEvent(
                "generated_native_ack_retry_skip",
                manga,
                image,
                true,
                "page=${target.page},reason=foreground_image"
            )
            return null
        }
        if (isNtkAckRecoveryLaunchHeld(path)) {
            logCacheEvent(
                "generated_native_ack_retry_skip",
                manga,
                image,
                false,
                "page=${target.page},reason=launch_first_drawable_hold"
            )
            return null
        }
        cancellation?.throwIfCancelled()
        val acked = try {
            getHttpClient().performNtkNativeAckBypass(target.baseUrl, path)
        } catch (_: Exception) {
            false
        }
        if (!acked) return null
        ViewerWarmupManager.logMetric("ntk_generated_image_native_ack_retry", target.page.toLong())
        val retry = try {
            requestForForegroundMode(context, manga, image, foreground, cancellation)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
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
        val images = fetchNtkApiFallbackImages(
            context,
            manga,
            target,
            runningTask,
            waitMs = if (foreground) 2_500L else 18_000L
        ) ?: return null
        val replacement = images.getOrNull(target.page - 1) ?: image
        val replacementTarget = ntkGeneratedTarget(replacement)
        if (replacement != image &&
            (replacementTarget == null || !isCompatibleNtkGeneratedPage(target, replacementTarget))
        ) {
            logCacheEvent(
                "generated_api_retry_skip_mismatch",
                manga,
                image,
                foreground,
                "page=${target.page},replacement=${replacement.substringAfterLast('/').takeLast(80)}"
            )
            return null
        }
        ViewerWarmupManager.logMetric("ntk_generated_image_api_fallback_retry", target.page.toLong())
        val retry = try {
            requestForForegroundMode(context, manga, replacement, foreground, cancellation)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
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
        runningTask: FutureTask<List<String>?>? = null,
        waitMs: Long = 18_000L
    ): List<String>? {
        val key = "${target.baseUrl}${ntkFallbackKeyPath(manga, target)}"
        ntkApiFallbackImages[key]?.let {
            ViewerWarmupManager.logMetric("ntk_generated_image_api_fallback_cache_hit", target.page.toLong())
            return it
        }
        val running = runningTask ?: startNtkApiFallbackImages(context, manga, target)
        return try {
            running.get(waitMs, TimeUnit.MILLISECONDS)?.also { images ->
                ntkApiFallbackImages[key] = images
                if (isCompatibleNtkGeneratedFallbackImages(target, images)) {
                    manga.setImgs(ArrayList(images))
                    manga.ntkImageCount = images.size
                } else {
                    logCacheEvent(
                        "generated_api_fallback_skip_count_mismatch",
                        manga,
                        "${target.baseUrl}${target.path}/p${target.page.toString().padStart(3, '0')}",
                        false,
                        "images=${images.size}"
                    )
                }
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
            } catch (t: Throwable) {
                rethrowIfFatal(t)
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

    private fun isCompatibleNtkGeneratedFallbackImages(
        target: NtkGeneratedTarget,
        images: List<String>
    ): Boolean {
        if (images.isEmpty()) return false
        return images.all { image ->
            val imageTarget = ntkGeneratedTarget(image) ?: return@all false
            isCompatibleNtkGeneratedEpisode(target, imageTarget)
        }
    }

    private fun isCompatibleNtkGeneratedPage(
        expected: NtkGeneratedTarget,
        actual: NtkGeneratedTarget
    ): Boolean {
        return isCompatibleNtkGeneratedEpisode(expected, actual) && actual.page == expected.page
    }

    private fun isCompatibleNtkGeneratedEpisode(
        expected: NtkGeneratedTarget,
        actual: NtkGeneratedTarget
    ): Boolean {
        if (actual.path == expected.path) return true
        val expectedParts = expected.path.trim('/').split('/')
        val actualParts = actual.path.trim('/').split('/')
        if (expectedParts.size < 3 || actualParts.size < 3) return false
        return expectedParts[2] == actualParts[2] &&
            expectedParts[0].equals(actualParts[0], ignoreCase = true)
    }

    private fun shouldRaceForegroundImage(image: String): Boolean {
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
        val generatedAnchor = ntkGeneratedTarget(image)?.page == 1
        var failure: Throwable? = null
        repeat(attempts.size) { completedIndex ->
            try {
                var future = if (generatedAnchor && completedIndex == 0) {
                    completion.poll(NTK_GENERATED_ANCHOR_RACE_FAST_FAIL_MS, TimeUnit.MILLISECONDS)
                } else {
                    completion.take()
                }
                if (future == null) {
                    logCacheEvent(
                        "foreground_race_fast_wait",
                        manga,
                        image,
                        true,
                        "completed=$completedIndex,total=${attempts.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    future = completion.take()
                }
                val result = future.get()
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

    private fun canonicalNtkGeneratedImageForActiveEpisode(manga: Manga, image: String): String? {
        val target = ntkGeneratedTarget(image) ?: return null
        val activePath = manga.ntkEpisodePath?.trim().orEmpty()
        if (activePath.isBlank() || activePath == target.path) return null
        val activeParts = activePath.trim('/').split('/')
        val targetParts = target.path.trim('/').split('/')
        if (activeParts.size < 3 || targetParts.size < 3) return null
        if (!activeParts[0].equals(targetParts[0], ignoreCase = true)) return null
        if (activeParts[1] != targetParts[1]) return null
        val activeWork = activeParts[1]
        val activeEpisode = activeParts[2]
        val pageName = Regex("/(p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?)$", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return when {
            image.contains("/blacktoon/episodes/", ignoreCase = true) ->
                "${target.baseUrl}/blacktoon/episodes/$activeWork/$activeEpisode/$pageName"
            image.contains("/wt/episodes/", ignoreCase = true) ->
                "${target.baseUrl}/wt/episodes/$activeWork/$activeEpisode/$pageName"
            else ->
                "${target.baseUrl}/${activeParts[0]}/$activeWork/$activeEpisode/$pageName"
        }
    }

    private data class NtkGeneratedImageRef(
        val episodeKey: String,
        val extension: String
    )

    private fun ntkGeneratedImageRef(image: String): NtkGeneratedImageRef? {
        val numericMatch = Regex("^(?:https?://[^/]+)/(manhwa|webtoon)/(\\d+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (numericMatch != null) {
            val key = "${numericMatch.groupValues[1].lowercase()}/${numericMatch.groupValues[2]}/${numericMatch.groupValues[3]}/p${numericMatch.groupValues[4]}"
            return NtkGeneratedImageRef(key, numericMatch.groupValues[5].lowercase())
        }
        val blacktoonMatch = Regex("^(?:https?://[^/]+)/blacktoon/episodes/(\\d+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (blacktoonMatch != null) {
            val key = "webtoon/${blacktoonMatch.groupValues[1]}/${blacktoonMatch.groupValues[2]}/p${blacktoonMatch.groupValues[3]}"
            return NtkGeneratedImageRef(key, blacktoonMatch.groupValues[4].lowercase())
        }
        val slugMatch = Regex("^(?:https?://[^/]+)/wt/episodes/([^/?#]+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image) ?: return null
        val key = "wt/${slugMatch.groupValues[1]}/${slugMatch.groupValues[2]}/p${slugMatch.groupValues[3]}"
        return NtkGeneratedImageRef(key, slugMatch.groupValues[4].lowercase())
    }

    private fun rememberNtkGeneratedEpisodeExtension(image: String) {
        val ref = ntkGeneratedImageRef(image) ?: return
        val previous = ntkGeneratedEpisodeExtensions.put(ref.episodeKey, ref.extension)
        if (previous != ref.extension) {
            Log.d(TAG, "ntk_generated_episode_extension_hint key=${ref.episodeKey},extension=${ref.extension},previous=${previous.orEmpty()}")
        }
    }

    private fun rememberEarlyNtkGeneratedSuccess(manga: Manga, image: String) {
        val path = manga.ntkEpisodePath
        val target = ntkGeneratedTarget(image) ?: return
        if (path.isNullOrBlank() || target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return
        val existing = earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
        val mergedByPage = LinkedHashMap<Int, String>()
        for (url in existing) {
            val existingTarget = ntkGeneratedTarget(url) ?: continue
            if (existingTarget.page in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) {
                mergedByPage[existingTarget.page] = url
            }
        }
        mergedByPage[target.page] = image
        val merged = mergedByPage.toSortedMap().values.toList()
        if (merged.isEmpty() || merged == existing) return
        rememberEarlyNtkImageUrls(path, merged)
        logCacheEvent(
            "foreground_generated_success_early_merge",
            manga,
            image,
            true,
            "page=${target.page},count=${merged.size}"
        )
    }

    private fun ntkGeneratedEpisodeExtensionMatches(image: String): Boolean {
        val ref = ntkGeneratedImageRef(image) ?: return false
        return ntkGeneratedEpisodeExtensions[ref.episodeKey] == ref.extension
    }

    private fun ntkGeneratedEpisodeExtensionHinted(image: String): Boolean {
        val ref = ntkGeneratedImageRef(image) ?: return false
        return ntkGeneratedEpisodeExtensions.containsKey(ref.episodeKey)
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
