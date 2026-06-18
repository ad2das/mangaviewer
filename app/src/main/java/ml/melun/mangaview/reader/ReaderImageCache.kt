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
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

object ReaderImageCache {
    private const val TAG = "ViewerPerf"
    private const val DIR_NAME = "reader_image_cache_v1"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val TARGET_CACHE_BYTES = 384L * 1024L * 1024L
    private const val TRIM_DEBOUNCE_MS = 30_000L
    private const val FOREGROUND_RACE_DELAY_MS = 120L
    private const val FOREGROUND_RACE_ATTEMPTS = 2
    private const val NTK_GENERATED_ANCHOR_RACE_FAST_FAIL_MS = 420L
    private const val NTK_VERIFIED_ANCHOR_HANDOFF_DELAY_MS = 0L
    private const val FOREGROUND_STREAM_RACE_ATTEMPTS = 1
    private const val FOREGROUND_STREAM_JOIN_TIMEOUT_MS = 1800L
    private const val FOREGROUND_INITIAL_STREAM_JOIN_TIMEOUT_MS = 180L
    private const val NTK_GENERATED_INITIAL_RECOVERY_HEDGE_MS = 300L
    private const val NTK_GENERATED_INITIAL_RECOVERY_SAME_URL_HEDGE_MS = 350L
    private const val NTK_GENERATED_INITIAL_RECOVERY_PAGES = 4
    private const val FOREGROUND_STREAM_HANDOFF_TTL_MS = 2500L
    private const val FOREGROUND_STREAM_STALE_MS = 9000L
    private const val NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES = 4
    private const val NTK_GENERATED_INITIAL_TRANSIENT_RETRY_DELAY_MS = 180L
    private const val NTK_GENERATED_INITIAL_DIRECT_HEDGE_WEBTOON_DELAY_MS = 140L
    private const val NTK_GENERATED_INITIAL_DIRECT_HEDGE_MANHWA_DELAY_MS = 900L
    private const val EARLY_NTK_IMAGE_URL_TTL_MS = 6000L
    private const val EARLY_NTK_APPEND_IMAGE_URL_TTL_MS = 30000L
    private const val EARLY_NTK_IMAGE_URL_STARTED_SKEW_MS = 1200L
    private const val NTK_ACK_RECOVERY_LAUNCH_HOLD_MS = 30_000L
    private const val NTK_ACK_RECOVERY_AFTER_FIRST_DRAWABLE_QUIET_MS = 4_500L
    private const val NTK_GENERATED_ACK_GATE_VISIBLE_WAIT_MS = 0L
    private const val NTK_GENERATED_ACK_GATE_FOREGROUND_WAIT_MS = 0L
    private const val NTK_GENERATED_ACK_GATE_BACKGROUND_WAIT_MS = 3600L
    private const val MAX_DIRECT_STREAM_DECODE_BYTES = 16L * 1024L * 1024L
    private const val MAX_DIRECT_STREAM_BITMAP_BYTES = 2L * 1024L * 1024L
    private const val DIRECT_STREAM_TILE_ASPECT_RATIO = 3.0f
    private const val DIRECT_STREAM_TILE_MIN_ESTIMATED_BYTES = 12L * 1024L * 1024L
    private const val FULL_DECODE_VALIDATION_MAX_BYTES = 256L * 1024L
    private const val NTK_GENERATED_RANGE_CHUNK_BYTES = 64 * 1024
    private const val NTK_GENERATED_RANGE_INITIAL_FIRST_CHUNK_BYTES = 768 * 1024
    private const val NTK_GENERATED_RANGE_ADJACENT_FIRST_CHUNK_BYTES = 128 * 1024
    private const val NTK_GENERATED_RANGE_INITIAL_CHUNK_BYTES = 256 * 1024
    private const val NTK_GENERATED_RANGE_ADJACENT_CHUNK_BYTES = 256 * 1024
    private const val NTK_GENERATED_RANGE_MAX_BYTES = 24L * 1024L * 1024L
    private const val NTK_GENERATED_BACKGROUND_FETCH_PARALLELISM = 1
    private const val NTK_GENERATED_FOREGROUND_FETCH_PARALLELISM = 6
    private val flights = ConcurrentHashMap<String, FutureTask<File>>()
    private val foregroundStreams = ConcurrentHashMap<String, FutureTask<ByteArray?>>()
    private val initialGeneratedRangeFlights = ConcurrentHashMap<String, FutureTask<GeneratedRangeSnapshot?>>()
    private val foregroundStreamStartedAt = ConcurrentHashMap<String, Long>()
    private val earlyNtkImageUrls = ConcurrentHashMap<String, EarlyNtkImageUrls>()
    private val ntkApiFallbackFlights = ConcurrentHashMap<String, FutureTask<List<String>?>>()
    private val ntkApiFallbackImages = ConcurrentHashMap<String, List<String>>()
    private val ntkGeneratedAckRecoveryFlights = ConcurrentHashMap<String, FutureTask<Boolean>>()
    private val ntkAckRecoveryLaunchHolds = ConcurrentHashMap<String, Long>()
    private val ntkGeneratedEpisodeExtensions = ConcurrentHashMap<String, String>()
    private val ntkGeneratedNotFoundPages = ConcurrentHashMap.newKeySet<String>()
    private val ntkGeneratedReplacementClaims = ConcurrentHashMap.newKeySet<String>()
    private val ntkAnchorAssetFiles = ConcurrentHashMap<String, File>()
    private val activeReads = ConcurrentHashMap<String, AtomicInteger>()
    private val cacheGeneration = AtomicLong(0L)
    private val ntkGeneratedBackgroundFetchGate = Semaphore(NTK_GENERATED_BACKGROUND_FETCH_PARALLELISM)
    private val ntkGeneratedForegroundFetchGate = PriorityFetchGate(NTK_GENERATED_FOREGROUND_FETCH_PARALLELISM)
    private val ntkGeneratedInitialVisibleSerialGate = Semaphore(1)
    private val activeVisibleInitialGeneratedFetches = AtomicInteger(0)
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

    private class PriorityFetchGate(private val maxPermits: Int) {
        private val lock = ReentrantLock()
        private val condition = lock.newCondition()
        private var permits = maxPermits
        private var visibleWaiters = 0

        fun acquire(visiblePriority: Boolean) {
            lock.lockInterruptibly()
            try {
                if (visiblePriority) visibleWaiters++
                try {
                    while (permits <= 0 || (!visiblePriority && visibleWaiters > 0)) {
                        condition.await()
                    }
                    permits--
                } finally {
                    if (visiblePriority) {
                        visibleWaiters--
                        condition.signalAll()
                    }
                }
            } finally {
                lock.unlock()
            }
        }

        fun release() {
            lock.lock()
            try {
                permits = (permits + 1).coerceAtMost(maxPermits)
                condition.signalAll()
            } finally {
                lock.unlock()
            }
        }

        fun availablePermits(): Int {
            lock.lock()
            return try {
                permits
            } finally {
                lock.unlock()
            }
        }

        fun visibleWaiterCount(): Int {
            lock.lock()
            return try {
                visibleWaiters
            } finally {
                lock.unlock()
            }
        }
    }

    private data class InitialRecoveryResult(
        val label: String,
        val file: File
    )

    private data class GeneratedRangeSnapshot(
        val request: Request,
        val protocol: Protocol,
        val code: Int,
        val message: String,
        val headers: Headers,
        val contentType: MediaType?,
        val bytes: ByteArray
    )

    @JvmStatic
    fun clearVolatileStateForTest() {
        val generation = cacheGeneration.incrementAndGet()
        flights.values.forEach { it.cancel(true) }
        flights.clear()
        foregroundStreams.values.forEach { it.cancel(true) }
        foregroundStreams.clear()
        initialGeneratedRangeFlights.values.forEach { it.cancel(true) }
        initialGeneratedRangeFlights.clear()
        foregroundStreamStartedAt.clear()
        earlyNtkImageUrls.clear()
        ntkApiFallbackFlights.values.forEach { it.cancel(true) }
        ntkApiFallbackFlights.clear()
        ntkApiFallbackImages.clear()
        ntkGeneratedAckRecoveryFlights.values.forEach { it.cancel(true) }
        ntkGeneratedAckRecoveryFlights.clear()
        ntkAckRecoveryLaunchHolds.clear()
        ntkGeneratedEpisodeExtensions.clear()
        ntkGeneratedNotFoundPages.clear()
        ntkGeneratedReplacementClaims.clear()
        ntkAnchorAssetFiles.clear()
        activeReads.clear()
        Log.d(TAG, "reader_image_cache_volatile_clear_for_test generation=$generation")
    }

    fun cancelNtkEpisodeVolatile(manga: Manga?) {
        val path = earlyNtkPathKey(manga?.ntkEpisodePath)
        if (manga == null || path.isEmpty()) return
        val streamPrefix = "ntk-generated-stream|${manga.baseMode}|$path|"
        val rangePrefix = "ntk-generated-range|${manga.baseMode}|$path|"
        val fallbackSuffix = path
        val episodeKeys = ntkGeneratedEpisodeHintKeys(path)
        val cancelledStreams = cancelFutureTasks(foregroundStreams) { it.startsWith(streamPrefix) }
        foregroundStreamStartedAt.keys.removeAll { it.startsWith(streamPrefix) }
        val cancelledRanges = cancelFutureTasks(initialGeneratedRangeFlights) { it.startsWith(rangePrefix) }
        val cancelledApi = cancelFutureTasks(ntkApiFallbackFlights) { it.endsWith(fallbackSuffix) }
        val cancelledAck = cancelFutureTasks(ntkGeneratedAckRecoveryFlights) { it.endsWith(fallbackSuffix) }
        earlyNtkImageUrls.remove(path)
        ntkApiFallbackImages.keys.removeAll { it.endsWith(fallbackSuffix) }
        ntkAckRecoveryLaunchHolds.remove(path)
        episodeKeys.forEach { ntkGeneratedEpisodeExtensions.remove(it) }
        ntkGeneratedNotFoundPages.removeAll { it.startsWith("$path|") }
        ntkGeneratedReplacementClaims.removeAll { it.contains("|$path|") }
        ntkAnchorAssetFiles.keys.removeAll { it.contains("|$path|") }
        if (cancelledStreams > 0 || cancelledRanges > 0 || cancelledApi > 0 || cancelledAck > 0) {
            Log.d(
                TAG,
                "reader_image_cache_ntk_episode_volatile_cancel path=$path," +
                    "streams=$cancelledStreams,ranges=$cancelledRanges,api=$cancelledApi,ack=$cancelledAck"
            )
        }
    }

    private fun <T> cancelFutureTasks(
        tasks: ConcurrentHashMap<String, FutureTask<T>>,
        shouldCancel: (String) -> Boolean
    ): Int {
        var count = 0
        for ((key, task) in tasks.entries) {
            if (shouldCancel(key) && tasks.remove(key, task)) {
                task.cancel(true)
                count++
            }
        }
        return count
    }

    private fun ntkGeneratedEpisodeHintKeys(path: String): List<String> {
        val parts = path.trim('/').split('/')
        if (parts.size < 3) return emptyList()
        val kind = parts[0].lowercase()
        val work = parts[1]
        val episode = parts[2]
        return if (kind == "webtoon") {
            listOf("webtoon/$work/$episode", "wt/$work/$episode")
        } else {
            listOf("$kind/$work/$episode")
        }
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
        return leaseFile(context, manga, image, foreground, null, visiblePriority = foreground)
    }

    fun leaseFile(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean = true,
        cancellation: Cancellation?,
        visiblePriority: Boolean = foreground
    ): FileLease {
        if (!manga.isOnline) return FileLease(File(image), null)
        cancellation?.throwIfCancelled()
        val key = key(manga.baseMode, image)
        retainActiveRead(key)
        return try {
            val file = getOrFetch(
                context,
                manga,
                image,
                foreground = foreground,
                cancellation = cancellation,
                visiblePriority = visiblePriority
            )
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

    fun getOrFetchFileForeground(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        visiblePriority: Boolean = true
    ): File {
        if (!manga.isOnline) return File(image)
        cancellation?.throwIfCancelled()
        return getOrFetch(
            context,
            manga,
            image,
            foreground = true,
            cancellation = cancellation,
            visiblePriority = visiblePriority
        )
    }

    fun cachedFile(context: Context, manga: Manga, image: String): File? {
        if (!manga.isOnline) return File(image)
        val appContext = context.applicationContext
        cachedAnchorAssetFile(appContext, manga, image, foreground = true)?.let { return it }
        return cachedImageFile(appContext, manga, image)
    }

    fun hasActiveFetch(manga: Manga, image: String): Boolean {
        if (!manga.isOnline) return false
        val logicalStreamKey = foregroundStreamKey(manga.baseMode, image)
        return ntkGeneratedCacheLookupImages(image).any { candidate ->
            val key = key(manga.baseMode, candidate)
            flights.containsKey(key)
        } || foregroundStreams.containsKey(logicalStreamKey)
    }

    @JvmStatic
    fun clearNtkGeneratedEpisodeExtensionHintsForTest() {
        ntkGeneratedEpisodeExtensions.clear()
        ntkGeneratedNotFoundPages.clear()
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
        val until = SystemClock.elapsedRealtime() + NTK_ACK_RECOVERY_AFTER_FIRST_DRAWABLE_QUIET_MS
        ntkAckRecoveryLaunchHolds[key] = until
        Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_after_first_drawable path=$key,untilMs=$until")
    }

    @JvmStatic
    fun clearNtkAckRecoveryLaunchHold(path: String?, reason: String) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return
        if (ntkAckRecoveryLaunchHolds.remove(key) != null) {
            Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_clear path=$key,reason=$reason")
        }
    }

    @JvmStatic
    fun extendNtkAckRecoveryQuiet(path: String?, quietMs: Long, reason: String) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || quietMs <= 0L) return
        val until = SystemClock.elapsedRealtime() + quietMs
        val previous = ntkAckRecoveryLaunchHolds[key]
        if (previous != null && previous >= until) return
        ntkAckRecoveryLaunchHolds[key] = until
        Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_extend path=$key,reason=$reason,untilMs=$until")
    }

    @JvmStatic
    fun releaseNtkAckRecoveryAfterAckProofFailure(path: String?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return
        val until = ntkAckRecoveryLaunchHolds[key]
        val now = SystemClock.elapsedRealtime()
        if (until != null && until > now) {
            Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_retain path=$key,reason=ack_proof_required,untilMs=$until")
            return
        }
        if (ntkAckRecoveryLaunchHolds.remove(key) != null) {
            Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_release_expired path=$key,reason=ack_proof_required")
        }
    }

    @JvmStatic
    fun isNtkAckRecoveryLaunchHeldForPath(path: String?): Boolean {
        return isNtkAckRecoveryLaunchHeld(path)
    }

    @JvmStatic
    fun ntkAckRecoveryLaunchHoldRemainingMs(path: String?): Long {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return 0L
        val until = ntkAckRecoveryLaunchHolds[key] ?: return 0L
        val now = SystemClock.elapsedRealtime()
        if (until <= now) {
            ntkAckRecoveryLaunchHolds.remove(key, until)
            Log.d(TAG, "reader_ntk_ack_recovery_launch_hold_expired path=$key")
            return 0L
        }
        return until - now
    }

    private fun isNtkAckRecoveryLaunchHeld(path: String?): Boolean {
        return ntkAckRecoveryLaunchHoldRemainingMs(path) > 0L
    }

    fun rememberEarlyNtkImageUrls(path: String?, urls: List<String>?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || urls.isNullOrEmpty()) return
        val trusted = urls.mapNotNull { it?.trim()?.takeIf { value -> isTrustedNtkImageUrl(value) } }
        if (trusted.isEmpty()) return
        val existing = earlyNtkImageUrls[key]
        if (existing != null && existing.urls.size > trusted.size) {
            if (shouldReplaceWithVerifiedGeneratedSubset(existing.urls, trusted)) {
                earlyNtkImageUrls[key] = EarlyNtkImageUrls(
                    Collections.unmodifiableList(ArrayList(trusted)),
                    SystemClock.elapsedRealtime()
                )
                prepareEarlyNtkImageTransport(key, trusted)
                rememberInitialGeneratedExtensions(trusted)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_replace_verified_subset path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size},first=${safeImageName(trusted.firstOrNull())}"
                )
                return
            }
            val merged = mergeInitialGeneratedPageUrls(existing.urls, trusted)
            if (merged != null && merged != existing.urls) {
                earlyNtkImageUrls[key] = EarlyNtkImageUrls(
                    Collections.unmodifiableList(ArrayList(merged)),
                    SystemClock.elapsedRealtime()
                )
                prepareEarlyNtkImageTransport(key, merged)
                rememberInitialGeneratedExtensions(merged)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_merge_smaller path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size},merged=${merged.size}," +
                        "first=${safeImageName(merged.firstOrNull())}"
                )
                return
            }
            Log.d(
                TAG,
                "reader_early_ntk_urls_remember_skip_smaller path=$key," +
                    "existing=${existing.urls.size},incoming=${trusted.size},first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (existing != null) {
            val preserved = preserveExistingInitialGeneratedVariants(trusted, existing.urls)
            if (preserved != null && preserved != trusted) {
                earlyNtkImageUrls[key] = EarlyNtkImageUrls(
                    Collections.unmodifiableList(ArrayList(preserved)),
                    SystemClock.elapsedRealtime()
                )
                prepareEarlyNtkImageTransport(key, preserved)
                rememberInitialGeneratedExtensions(preserved)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_preserve_verified path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size},first=${safeImageName(preserved.firstOrNull())}"
                )
                return
            }
        }
        earlyNtkImageUrls[key] = EarlyNtkImageUrls(Collections.unmodifiableList(ArrayList(trusted)), SystemClock.elapsedRealtime())
        prepareEarlyNtkImageTransport(key, trusted)
        rememberInitialGeneratedExtensions(trusted)
        Log.d(TAG, "reader_early_ntk_urls_remember path=$key,count=${trusted.size},first=${safeImageName(trusted.firstOrNull())}")
    }

    private fun shouldReplaceWithVerifiedGeneratedSubset(existing: List<String>, incoming: List<String>): Boolean {
        if (existing.isEmpty() || incoming.isEmpty()) return false
        val incomingTargets = incoming.map { ntkGeneratedTarget(it) ?: return false }
        val existingTargets = existing.map { ntkGeneratedTarget(it) ?: return false }
        val path = incomingTargets.first().path
        if (incomingTargets.any { it.path != path } || existingTargets.any { it.path != path }) return false
        if (incomingTargets.none { it.page == 1 }) return false
        val incomingPages = incomingTargets.map { it.page }.toSet()
        val existingPages = existingTargets.map { it.page }.toSet()
        if (!existingPages.containsAll(incomingPages)) return false
        if (incomingPages.size == existingPages.size) return false
        return incomingPages.size > 1
    }

    private fun prepareEarlyNtkImageTransport(key: String, urls: List<String>) {
        try {
            getHttpClient().prepareNtkImageTransportForViewerUrls(urls, key)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            Log.d(TAG, "reader_early_ntk_transport_prepare_error path=$key,error=${t.javaClass.simpleName}")
        }
    }

    private fun rememberInitialGeneratedExtensions(urls: List<String>) {
        urls
            .asSequence()
            .filter { ntkGeneratedTarget(it)?.page in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES }
            .take(NTK_GENERATED_INITIAL_RECOVERY_PAGES)
            .forEach { rememberNtkGeneratedEpisodeExtension(it) }
    }

    private fun mergeInitialGeneratedPageUrls(existing: List<String>, incoming: List<String>): List<String>? {
        val replacements = LinkedHashMap<Int, String>()
        incoming.forEach { image ->
            val target = ntkGeneratedTarget(image) ?: return@forEach
            if (target.page in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) {
                replacements[target.page] = image
            }
        }
        if (replacements.isEmpty()) return null
        val merged = ArrayList(existing)
        val seenPages = HashSet<Int>()
        var changed = false
        for (index in merged.indices) {
            val target = ntkGeneratedTarget(merged[index]) ?: continue
            val replacement = replacements[target.page] ?: continue
            if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) continue
            seenPages.add(target.page)
            if (merged[index] != replacement) {
                merged[index] = replacement
                changed = true
            }
        }
        replacements.forEach { (page, image) ->
            if (!seenPages.contains(page)) {
                val insertIndex = (page - 1).coerceIn(0, merged.size)
                merged.add(insertIndex, image)
                changed = true
            }
        }
        return if (changed) merged else null
    }

    private fun preserveExistingInitialGeneratedVariants(
        incoming: List<String>,
        existing: List<String>
    ): List<String>? {
        val preferredByPage = LinkedHashMap<Int, String>()
        existing.forEach { image ->
            val target = ntkGeneratedTarget(image) ?: return@forEach
            if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return@forEach
            val ref = ntkGeneratedImageRef(image) ?: return@forEach
            if (ref.extension != "jpg") {
                preferredByPage[target.page] = image
            }
        }
        if (preferredByPage.isEmpty()) return null
        val merged = ArrayList(incoming)
        val seenPages = HashSet<Int>()
        var changed = false
        for (index in merged.indices) {
            val target = ntkGeneratedTarget(merged[index]) ?: continue
            if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) continue
            seenPages.add(target.page)
            val preferred = preferredByPage[target.page] ?: continue
            val ref = ntkGeneratedImageRef(merged[index])
            if (ref?.extension == "jpg" && merged[index] != preferred) {
                merged[index] = preferred
                changed = true
            }
        }
        preferredByPage.forEach { (page, image) ->
            if (!seenPages.contains(page)) {
                val insertIndex = (page - 1).coerceIn(0, merged.size)
                merged.add(insertIndex, image)
                changed = true
            }
        }
        return if (changed) merged else null
    }

    fun earlyNtkImageUrls(path: String?, minCreatedAtMs: Long): List<String> {
        return earlyNtkImageUrls(path, minCreatedAtMs, EARLY_NTK_IMAGE_URL_TTL_MS)
    }

    fun earlyNtkAppendImageUrls(path: String?, minCreatedAtMs: Long): List<String> {
        return earlyNtkImageUrls(path, minCreatedAtMs, EARLY_NTK_APPEND_IMAGE_URL_TTL_MS)
    }

    private fun earlyNtkImageUrls(path: String?, minCreatedAtMs: Long, maxAgeMs: Long): List<String> {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return emptyList()
        val entry = earlyNtkImageUrls[key] ?: return emptyList()
        val ageMs = SystemClock.elapsedRealtime() - entry.createdAtMs
        if (entry.createdAtMs + EARLY_NTK_IMAGE_URL_STARTED_SKEW_MS < minCreatedAtMs ||
            ageMs > maxAgeMs
        ) {
            earlyNtkImageUrls.remove(key, entry)
            return emptyList()
        }
        return entry.urls
    }

    fun cachedNtkApiFallbackImages(path: String?): List<String> {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return emptyList()
        val images = ntkApiFallbackImages.entries
            .firstOrNull { it.key.endsWith(key) }
            ?.value
            ?: return emptyList()
        if (images.isEmpty()) return emptyList()
        return images.takeIf { list ->
            list.all { image ->
                val target = ntkGeneratedTarget(image) ?: return@all false
                target.path == key
            }
        } ?: emptyList()
    }

    fun hasActiveInitialNtkGeneratedFetch(manga: Manga?, image: String?): Boolean {
        if (manga == null || image.isNullOrBlank()) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        val streamKey = foregroundStreamKey(manga.baseMode, image)
        val stream = foregroundStreams[streamKey]
        if (stream != null && !stream.isDone) {
            val startedAt = foregroundStreamStartedAt[streamKey] ?: return true
            if (SystemClock.elapsedRealtime() - startedAt <= FOREGROUND_STREAM_STALE_MS) {
                return true
            }
        }
        val cacheKey = key(manga.baseMode, image)
        val flight = flights[cacheKey]
        return flight != null && !flight.isDone
    }

    private fun isVerifiedEarlyNtkGeneratedUrl(
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget?
    ): Boolean {
        if (target == null || target.page <= 1) return false
        val path = manga.ntkEpisodePath?.takeIf { it.isNotBlank() } ?: return false
        if (target.path != path) return false
        return earlyNtkImageUrls(path, 0L)
            .any { sameNtkGeneratedPage(it, target) }
    }

    private fun canStreamVerifiedEarlyGeneratedWithoutPermit(
        context: Context,
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget?
    ): Boolean {
        val page = target?.page ?: return false
        if (page <= 1) return false
        if (!isVerifiedEarlyNtkGeneratedUrl(manga, image, target) &&
            !isActiveNearGeneratedPageWithVerifiedAnchor(manga, target)
        ) {
            return false
        }
        ntkGeneratedPageImage(image, 1)?.let { anchor ->
            if (cachedImageFile(context, manga, anchor) != null) return true
        }
        return false
    }

    private fun isActiveNearGeneratedPageWithVerifiedAnchor(
        manga: Manga,
        target: NtkGeneratedTarget
    ): Boolean {
        if (target.page !in 2..4) return false
        val path = manga.ntkEpisodePath?.takeIf { it.isNotBlank() } ?: return false
        if (target.path != path) return false
        return earlyNtkImageUrls(path, 0L).any { candidate ->
            val candidateTarget = ntkGeneratedTarget(candidate)
            candidateTarget?.path == target.path && candidateTarget.page == 1
        }
    }

    @JvmOverloads
    fun startForegroundStreamFetch(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation? = null,
        anchorHedge: Boolean = false,
        permit: NtkImagePermit? = null,
        pageIndex: Int = -1
    ): Boolean {
        if (!manga.isOnline) return false
        val appContext = context.applicationContext
        val generatedTarget = ntkGeneratedTarget(image)
        val permitlessVerifiedEarlyGenerated = permit == null &&
            canStreamVerifiedEarlyGeneratedWithoutPermit(appContext, manga, image, generatedTarget)
        if (generatedTarget != null && generatedTarget.page > 1 && permit == null && !permitlessVerifiedEarlyGenerated) {
            logCacheEvent(
                "foreground_stream_missing_permit",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${generatedTarget.page}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_missing_permit", pageIndex.toLong())
            return false
        }
        cancellation?.throwIfCancelled()
        if (cachedFile(appContext, manga, image) != null) return false
        val key = key(manga.baseMode, image)
        val streamKey = foregroundStreamKey(manga.baseMode, image)
        if (shouldDropMismatchedHintedInitialGenerated(image)) {
            logCacheEvent(
                "foreground_stream_skip_hinted_mismatch",
                manga,
                image,
                true,
                "page=${generatedTarget?.page ?: 0},hinted=${ntkGeneratedImageWithHintedExtension(image).substringAfterLast('/').takeLast(64)}"
            )
            ViewerWarmupManager.logMetric(
                "reader_foreground_stream_skip_hinted_mismatch",
                (generatedTarget?.page ?: 0).toLong()
            )
            return false
        }
        val verifiedInitialGeneratedVariant = isVerifiedInitialGeneratedVariant(manga, image, generatedTarget)
        val allowVerifiedGeneratedVariant = verifiedInitialGeneratedVariant
        if (hasActiveFetch(manga, image) && !allowVerifiedGeneratedVariant) return false
        val finalFile = File(cacheDir(appContext), "$key.img")
        if (flights.containsKey(key) && !allowVerifiedGeneratedVariant) return false
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
                    generation,
                    visiblePriority = true
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
        val existing = foregroundStreams.putIfAbsent(streamKey, task)
        if (existing != null) {
            if (isStaleForegroundStream(streamKey, startedAt) &&
                foregroundStreams.remove(streamKey, existing)
            ) {
                existing.cancel(true)
                foregroundStreamStartedAt.remove(streamKey)
                logCacheEvent("foreground_stream_stale_restart", manga, image, true, "activeStream=true")
                return startForegroundStreamFetch(context, manga, image, cancellation, anchorHedge, permit, pageIndex)
            }
            if (existing.isDone && foregroundStreams.remove(streamKey, existing)) {
                foregroundStreamStartedAt.remove(streamKey)
                return startForegroundStreamFetch(context, manga, image, cancellation, anchorHedge, permit, pageIndex)
            }
            logCacheEvent("foreground_stream_async_join", manga, image, true, "activeStream=true")
            ViewerWarmupManager.logMetric("reader_foreground_stream_async_join", 1L)
            return false
        }
        foregroundStreamStartedAt[streamKey] = startedAt
        logCacheEvent(
            "foreground_stream_async_start",
            manga,
            image,
            true,
            "activeStream=false,page=$pageIndex,lane=${permit?.lane},phase=${permit?.phaseAtGrant},permit=${permit?.permitId}"
        )
        ViewerWarmupManager.logMetric("reader_foreground_stream_async_start", 1L)
        return try {
            foregroundRaceExecutor.execute {
                try {
                    task.run()
                } finally {
                    scheduleForegroundStreamHandoffExpiry(streamKey, task)
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
            foregroundStreams.remove(streamKey, task)
            foregroundStreamStartedAt.remove(streamKey)
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
        if (target.page == 1) {
            logCacheEvent(
                "download_initial_recovery_skip_anchor_range_owner",
                manga,
                image,
                true,
                "page=${target.page}"
            )
            ViewerWarmupManager.logMetric("reader_download_initial_recovery_skip_anchor_range_owner", 1L)
            return
        }
        if (shouldDropMismatchedHintedInitialGenerated(image)) {
            logCacheEvent(
                "download_initial_recovery_skip_hinted_mismatch",
                manga,
                image,
                true,
                "page=${target.page}"
            )
            ViewerWarmupManager.logMetric("reader_download_initial_recovery_skip_hinted_mismatch", target.page.toLong())
            return
        }
        try {
            foregroundRaceExecutor.execute {
                val recoveryDelayMs = NTK_GENERATED_INITIAL_RECOVERY_HEDGE_MS
                if (recoveryDelayMs > 0L) {
                    try {
                        Thread.sleep(recoveryDelayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
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
                if (shouldDropMismatchedHintedInitialGenerated(image)) {
                    logCacheEvent(
                        "download_initial_recovery_late_skip_hinted_mismatch",
                        manga,
                        image,
                        true,
                        "page=${target.page}"
                    )
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
                    "page=${target.page},delayMs=$recoveryDelayMs"
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
                        true,
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
        if (!hasActiveInitialNtkGeneratedFetch(manga, image)) {
            submit("hedge", NTK_GENERATED_INITIAL_RECOVERY_SAME_URL_HEDGE_MS, anchorHedge = true)
        }
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
                    publishNtkAnchorAssetFile(context, manga, image, "download_initial_recovery")
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

    @JvmOverloads
    fun decodeForegroundBitmap(
        context: Context,
        manga: Manga,
        image: String,
        targetWidth: Int,
        autoCut: Boolean,
        allowSplit: Boolean,
        cancellation: Cancellation? = null,
        anchorHedge: Boolean = false,
        visiblePriority: Boolean = true,
        permit: NtkImagePermit? = null,
        pageIndex: Int = -1
    ): Bitmap? {
        if (!manga.isOnline) return null
        val generatedTarget = ntkGeneratedTarget(image)
        if (generatedTarget != null && generatedTarget.page > 1 && permit == null) {
            logCacheEvent(
                "foreground_decode_missing_permit",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${generatedTarget.page}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_decode_missing_permit", pageIndex.toLong())
            return null
        }
        cancellation?.throwIfCancelled()
        val appContext = context.applicationContext
        if (cachedFile(appContext, manga, image) != null) return null
        awaitForegroundStreamVariantFile(appContext, manga, image, true)?.file?.let {
            return null
        }
        val key = foregroundStreamKey(manga.baseMode, image)
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
                generation,
                visiblePriority = visiblePriority
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
                val effectiveJoinTimeoutMs = remainingJoinTimeoutMs
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
                    decodeForegroundBytesOrAnchor(
                        appContext,
                        manga,
                        image,
                        existing,
                        startedAt,
                        autoCut,
                        allowSplit,
                        targetWidth,
                        effectiveJoinTimeoutMs
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
        generation: Long = cacheGeneration.get(),
        visiblePriority: Boolean = false
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
        val shouldGateInitialVisible = visiblePriority && prioritizeInitialHintedFullFetch
        val response = if (shouldGateInitialVisible) {
            withNtkGeneratedFetchPermit(manga, image, true, true) {
                retryNtkGeneratedInitialFullRace(context, manga, image, cancellation)
                    ?: requestWithNtkGeneratedFallback(
                        context,
                        manga,
                        image,
                        foreground = useForegroundTransport,
                        cancellation = cancellation,
                        foregroundRaceAttempts = raceAttempts,
                        anchorHedge = anchorHedge && useForegroundTransport,
                        skipInitialForegroundGeneratedRetryOnActiveFlight =
                            useForegroundTransport
                    )
            }
        } else {
            val racedResponse = retryNtkGeneratedInitialFullRace(context, manga, image, cancellation)
            racedResponse ?: withNtkGeneratedFetchPermit(
                manga,
                image,
                useForegroundTransport || prioritizeInitialHintedFullFetch,
                visiblePriority && (useForegroundTransport || prioritizeInitialHintedFullFetch)
            ) {
                requestWithNtkGeneratedFallback(
                    context,
                    manga,
                    image,
                    foreground = useForegroundTransport,
                    cancellation = cancellation,
                    foregroundRaceAttempts = raceAttempts,
                    anchorHedge = anchorHedge && useForegroundTransport,
                    skipInitialForegroundGeneratedRetryOnActiveFlight =
                        useForegroundTransport
                )
            }
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
                waitCachedFullBytesAfterPartial(context, manga, image, actualImage)?.let { fullBytes ->
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
                    cacheForegroundBytes(context, manga, image, bytes, generation)
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
        image: String,
        actualImage: String
    ): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + 1400L
        val candidates = ntkGeneratedCacheLookupImages(image)
            .plus(actualImage)
            .plus(ntkGeneratedCacheLookupImages(actualImage))
            .distinct()
        while (SystemClock.elapsedRealtime() < deadline) {
            for (candidate in candidates) {
                val file = File(cacheDir(context.applicationContext), "${key(manga.baseMode, candidate)}.img")
                if (file.length() in 1..MAX_DIRECT_STREAM_DECODE_BYTES && isUsableImage(file)) {
                    return try {
                        file.readBytes()
                    } catch (_: Exception) {
                        null
                    }
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
        return true
    }

    private fun shouldPrioritizeHintedInitialGeneratedFullFetch(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page == 1 && isSupportedNtkGeneratedImageExtension(image)) return true
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun isSupportedNtkGeneratedImageExtension(image: String): Boolean {
        return Regex("(?i)\\.(jpg|jpeg|png|webp)([?#].*)?$").containsMatchIn(image)
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

    private fun decodeForegroundBytesOrAnchor(
        context: Context,
        manga: Manga,
        image: String,
        task: FutureTask<ByteArray?>,
        startedAt: Long,
        autoCut: Boolean,
        allowSplit: Boolean,
        targetWidth: Int,
        waitTimeoutMs: Long
    ): Bitmap? {
        val waitStartedAt = SystemClock.elapsedRealtime()
        while (true) {
            cachedAnchorAssetFile(context, manga, image, foreground = true)?.let { cached ->
                logCacheEvent(
                    "foreground_stream_join_anchor_asset_hit",
                    manga,
                    image,
                    true,
                    "bytes=${cached.length()},waitMs=${SystemClock.elapsedRealtime() - waitStartedAt}"
                )
                ViewerWarmupManager.logMetric("reader_foreground_stream_join_anchor_asset_hit", 1L)
                return decodeForegroundFile(cached, startedAt, autoCut, allowSplit, targetWidth)
            }
            if (task.isDone) {
                return decodeForegroundBytes(
                    task,
                    startedAt,
                    autoCut,
                    allowSplit,
                    targetWidth,
                    boundedWait = false
                )
            }
            val elapsed = SystemClock.elapsedRealtime() - waitStartedAt
            val remaining = waitTimeoutMs - elapsed
            if (remaining <= 0L) {
                ViewerWarmupManager.logMetric("reader_foreground_stream_join_timeout", 1L)
                return null
            }
            try {
                task.get(minOf(32L, remaining), TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: ExecutionException) {
                return null
            }
        }
    }

    private fun decodeForegroundFile(
        file: File,
        startedAt: Long,
        autoCut: Boolean,
        allowSplit: Boolean,
        targetWidth: Int
    ): Bitmap? {
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val bytesAt = SystemClock.elapsedRealtime()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (shouldPreferFileDecodeAfterForegroundCache(bounds, bytes.size, autoCut)) return null
        val decodeTargetWidth = foregroundDecodeTargetWidth(bounds.outWidth, bounds.outHeight, targetWidth, autoCut, allowSplit)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = foregroundSampleSize(bounds.outWidth, decodeTargetWidth)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val decodedAt = SystemClock.elapsedRealtime()
        ViewerWarmupManager.logMetric("reader_foreground_stream_file_decode_ms", decodedAt - bytesAt)
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
        if (!writeForegroundBytesToCache(context, manga, image, bytes, generation, "foreground_cached")) return
        publishNtkAnchorAssetFile(context, manga, image, "foreground_cached")
        if (ntkGeneratedTarget(image) != null) {
            ntkGeneratedCacheLookupImages(image)
                .plus(ntkGeneratedSamePageExtensionAliases(image))
                .asSequence()
                .filter { it != image }
                .distinct()
                .forEach { alias ->
                    writeForegroundBytesToCache(
                        context,
                        manga,
                        alias,
                        bytes,
                        generation,
                        "foreground_cached_alias"
                    )
                    publishNtkAnchorAssetFile(context, manga, alias, "foreground_cached_alias")
                }
        }
    }

    private fun publishNtkAnchorAssetFile(
        context: Context,
        manga: Manga,
        image: String,
        source: String
    ) {
        val assetKey = ntkAnchorAssetKey(manga, image) ?: return
        val file = File(cacheDir(context), "${key(manga.baseMode, image)}.img")
        if (!isUsableImage(file)) return
        val previous = ntkAnchorAssetFiles.put(assetKey, file)
        if (previous?.absolutePath == file.absolutePath) return
        logCacheEvent(
            "ntk_anchor_asset_stream_satisfied",
            manga,
            image,
            true,
            "asset=$assetKey,source=$source,bytes=${file.length()}"
        )
        ViewerWarmupManager.logMetric("ntk_anchor_asset_stream_satisfied", 1L)
    }

    private fun cachedAnchorAssetFile(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean
    ): File? {
        val assetKey = ntkAnchorAssetKey(manga, image) ?: return null
        val file = ntkAnchorAssetFiles[assetKey] ?: return null
        if (!isUsableImage(file)) {
            ntkAnchorAssetFiles.remove(assetKey, file)
            return null
        }
        logCacheEvent(
            "ntk_anchor_asset_cached_hit",
            manga,
            image,
            foreground,
            "asset=$assetKey,sourceFile=${file.name},bytes=${file.length()}"
        )
        return file
    }

    fun hasNtkAnchorAssetForEpisode(manga: Manga): Boolean {
        val path = earlyNtkPathKey(manga.ntkEpisodePath)
        if (path.isEmpty()) return false
        val assetKey = "${manga.baseMode}|$path|1"
        val file = ntkAnchorAssetFiles[assetKey] ?: return false
        if (isUsableImage(file)) return true
        ntkAnchorAssetFiles.remove(assetKey, file)
        return false
    }

    private fun ntkAnchorAssetKey(manga: Manga, image: String): String? {
        val target = ntkGeneratedTarget(image) ?: return null
        if (target.page != 1) return null
        val path = earlyNtkPathKey(manga.ntkEpisodePath)
        if (path.isEmpty()) return null
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return null
        return "${manga.baseMode}|$path|1"
    }

    private fun ntkGeneratedSamePageExtensionAliases(image: String): List<String> {
        ntkGeneratedTarget(image) ?: return emptyList()
        val match = Regex("(?i)\\.(jpg|jpeg|png|webp)(?=([?#].*)?$)").find(image) ?: return emptyList()
        val suffix = match.groups[2]?.value.orEmpty()
        val prefix = image.substring(0, match.range.first)
        return listOf("jpg", "jpeg", "png", "webp").map { "$prefix.$it$suffix" }
    }

    private fun writeForegroundBytesToCache(
        context: Context,
        manga: Manga,
        image: String,
        bytes: ByteArray,
        generation: Long,
        stage: String
    ): Boolean {
        if (bytes.isEmpty()) return false
        if (generation != cacheGeneration.get()) {
            logCacheEvent("foreground_cache_generation_skip", manga, image, true, "generation=$generation,current=${cacheGeneration.get()}")
            return false
        }
        val finalFile = File(cacheDir(context), "${key(manga.baseMode, image)}.img")
        if (isUsableImage(finalFile)) return true
        val tmp = File(finalFile.parentFile, "${finalFile.name}.fg.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!isUsableImage(tmp)) {
                tmp.delete()
                return false
            }
            replace(tmp, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            logCacheEvent(stage, manga, image, true, "bytes=${finalFile.length()}")
            ViewerWarmupManager.logMetric("reader_foreground_stream_cached", 1L)
            scheduleTrim(context)
            return true
        } catch (e: Exception) {
            tmp.delete()
            logCacheEvent("foreground_cache_error", manga, image, true, "error=${e.javaClass.simpleName}")
            return false
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
            val approvedReplacement = response.header("x-mangaviewer-generated-replacement") == "1"
            actualTarget != null &&
                (
                    isCompatibleNtkGeneratedPage(requestedTarget, actualTarget) ||
                        isCurrentNtkPathGeneratedPage(manga, requestedTarget, actualTarget) ||
                        (
                            approvedReplacement &&
                                isCompatibleNtkGeneratedEpisode(requestedTarget, actualTarget) &&
                                actualTarget.page > requestedTarget.page
                            )
                    ) &&
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
        val call: Call?,
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
        cancellation: Cancellation? = null,
        visiblePriority: Boolean = foreground
    ): File {
        val appContext = context.applicationContext
        cancellation?.throwIfCancelled()
        val key = key(manga.baseMode, image)
        val streamKey = foregroundStreamKey(manga.baseMode, image)
        val finalFile = File(cacheDir(appContext), "$key.img")
        cachedAnchorAssetFile(appContext, manga, image, foreground)?.let { cached ->
            cached.setLastModified(System.currentTimeMillis())
            logCacheEvent(
                "anchor_asset_disk_hit",
                manga,
                image,
                foreground,
                "bytes=${cached.length()}"
            )
            ViewerWarmupManager.logMetric("reader_anchor_asset_disk_hit", 1L)
            return cached
        }
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
        val streamAwait = awaitForegroundStreamFile(appContext, streamKey, finalFile, manga, image, foreground)
        streamAwait?.file?.let { return it }
        cachedAnchorAssetFile(appContext, manga, image, foreground)?.let { cached ->
            cached.setLastModified(System.currentTimeMillis())
            logCacheEvent(
                "anchor_asset_hit_after_foreground_stream_wait",
                manga,
                image,
                foreground,
                "bytes=${cached.length()}"
            )
            ViewerWarmupManager.logMetric("reader_anchor_asset_hit_after_foreground_stream_wait", 1L)
            return cached
        }
        awaitForegroundStreamVariantFile(appContext, manga, image, foreground)?.file?.let { return it }
        cachedImageFile(appContext, manga, image)?.let { cached ->
            cached.setLastModified(System.currentTimeMillis())
            logCacheEvent(
                "disk_hit_after_foreground_stream_wait",
                manga,
                image,
                foreground,
                "bytes=${cached.length()}"
            )
            ViewerWarmupManager.logMetric("reader_image_cache_disk_hit_after_foreground_stream_wait", 1L)
            return cached
        }
        val bypassForegroundDownload = streamAwait?.bypassForegroundDownload == true
        val downloadForeground = foreground && !bypassForegroundDownload
        val priorityFullDownload = foreground &&
            bypassForegroundDownload &&
            ntkGeneratedTarget(image) != null
        val requestForegroundDownload = downloadForeground || priorityFullDownload
        val generation = cacheGeneration.get()
        val task = FutureTask {
            withNtkGeneratedFetchPermit(
                manga,
                image,
                requestForegroundDownload,
                visiblePriority && requestForegroundDownload
            ) {
                downloadAtomically(appContext, manga, image, finalFile, requestForegroundDownload, cancellation, generation)
            }
        }
        var existing = flights.putIfAbsent(key, task)
        if (existing != null && shouldSupersedeGeneratedVisibleFlight(
                image,
                foreground,
                priorityFullDownload,
                hasFreshInitialForegroundStream(streamKey, image),
                existing
            )
        ) {
            if (flights.remove(key, existing)) {
                existing.cancel(true)
                logCacheEvent(
                    "flight_supersede_generated_visible",
                    manga,
                    image,
                    foreground,
                    "activeFlight=true,foregroundDownload=$downloadForeground"
                )
                ViewerWarmupManager.logMetric("reader_image_cache_flight_supersede_generated_visible", 1L)
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
                "activeFlight=false,foregroundDownload=$downloadForeground,priorityFullDownload=$priorityFullDownload"
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
        context: Context,
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
        val waitTimeoutMs = baseWaitTimeoutMs
        val waitStartedAt = SystemClock.elapsedRealtime()
        var timedOut = false
        try {
            while (true) {
                cachedAnchorAssetFile(context, manga, image, foreground)?.let { cached ->
                    cached.setLastModified(System.currentTimeMillis())
                    logCacheEvent(
                        "anchor_asset_hit_during_foreground_stream_wait",
                        manga,
                        image,
                        foreground,
                        "bytes=${cached.length()},waitMs=${SystemClock.elapsedRealtime() - waitStartedAt}"
                    )
                    ViewerWarmupManager.logMetric("reader_anchor_asset_hit_during_foreground_stream_wait", 1L)
                    return ForegroundStreamAwait(file = cached)
                }
                if (isUsableImage(finalFile)) {
                    finalFile.setLastModified(System.currentTimeMillis())
                    logCacheEvent(
                        "foreground_stream_wait_disk_hit_during_wait",
                        manga,
                        image,
                        foreground,
                        "bytes=${finalFile.length()},waitMs=${SystemClock.elapsedRealtime() - waitStartedAt}"
                    )
                    ViewerWarmupManager.logMetric("reader_foreground_stream_wait_disk_hit_during_wait", 1L)
                    return ForegroundStreamAwait(file = finalFile)
                }
                if (stream.isDone) break
                val elapsed = SystemClock.elapsedRealtime() - waitStartedAt
                val remaining = waitTimeoutMs - elapsed
                if (remaining <= 0L) {
                    timedOut = true
                    break
                }
                try {
                    stream.get(minOf(32L, remaining), TimeUnit.MILLISECONDS)
                    break
                } catch (_: java.util.concurrent.TimeoutException) {
                    continue
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: ExecutionException) {
            return null
        }
        if (timedOut) {
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
            val awaited = awaitForegroundStreamFile(context, candidateKey, candidateFile, manga, candidate, foreground)
            if (awaited?.bypassForegroundDownload == true) {
                logCacheEvent(
                    "foreground_stream_wait_variant_bypass",
                    manga,
                    candidate,
                    foreground,
                    "requested=${image.substringAfterLast('/').takeLast(64)}"
                )
                ViewerWarmupManager.logMetric("reader_foreground_stream_wait_variant_bypass", 1L)
                return awaited
            }
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

    private fun shouldSupersedeGeneratedVisibleFlight(
        image: String,
        foreground: Boolean,
        priorityFullDownload: Boolean,
        freshInitialForegroundStream: Boolean,
        existing: FutureTask<File>
    ): Boolean {
        if (!foreground || !priorityFullDownload) return false
        if (existing.isDone || existing.isCancelled) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page == 1) return false
        if (freshInitialForegroundStream &&
            target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
        ) {
            return false
        }
        return target.page >= 1
    }

    private fun hasFreshInitialForegroundStream(streamKey: String, image: String): Boolean {
        val stream = foregroundStreams[streamKey] ?: return false
        if (stream.isDone || stream.isCancelled) return false
        val target = ntkGeneratedTarget(image)
        if (target == null || target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return true
        val startedAt = foregroundStreamStartedAt[streamKey] ?: return true
        return SystemClock.elapsedRealtime() - startedAt < foregroundStreamJoinTimeoutMs(image)
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
        return false
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

    private fun isInitialGeneratedTransientCacheReady(
        context: Context,
        manga: Manga,
        image: String
    ): Boolean {
        val dir = cacheDir(context.applicationContext)
        for (page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            val pageImage = ntkGeneratedPageImage(image, page) ?: return false
            if (isUsableImage(File(dir, "${key(manga.baseMode, pageImage)}.img"))) {
                continue
            }
            if (!hasNtkGeneratedNotFound(manga, pageImage)) {
                return false
            }
        }
        return true
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

    private fun foregroundStreamKey(baseMode: Int, image: String): String {
        val target = ntkGeneratedTarget(image) ?: return key(baseMode, image)
        return "ntk-generated-stream|$baseMode|${target.path}|${target.page}"
    }

    private fun initialGeneratedRangeFlightKey(baseMode: Int, image: String, page: Int): String? {
        val target = ntkGeneratedTarget(image) ?: return null
        if (target.page != page) return null
        return "ntk-generated-range|$baseMode|${target.path}|$page"
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
        if (Regex("^flysky\\d*m\\.com/").containsMatchIn(lower) ||
            Regex("^//flysky\\d*m\\.com/").containsMatchIn(lower) ||
            Regex("^fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            Regex("^//fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            Regex("^aws-cdn\\d*\\.site/").containsMatchIn(lower) ||
            Regex("^//aws-cdn\\d*\\.site/").containsMatchIn(lower)
        ) return true
        return try {
            val host = Uri.parse(value).host?.lowercase().orEmpty()
            host == "toonflix.app" || host.endsWith(".toonflix.app") ||
                Regex("^flysky\\d*m\\.com$").matches(host) ||
                Regex("^fvcdn\\d*\\.com$").matches(host) ||
                Regex("^aws-cdn\\d*\\.site$").matches(host)
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
        return (lower.contains("://toonflix.app/") ||
            lower.contains("://i.toonflix.app/") ||
            Regex("://flysky\\d*m\\.com/").containsMatchIn(lower) ||
            Regex("://fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            Regex("://aws-cdn\\d*\\.site/").containsMatchIn(lower)) &&
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
        visiblePriority: Boolean = foreground,
        block: () -> T
    ): T {
        val requestUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
        if (!isNtkGeneratedImageUrl(requestUrl)) return block()
        val startedAt = SystemClock.elapsedRealtime()
        val target = ntkGeneratedTarget(requestUrl)
        maybeYieldForNtkAckBeforeGeneratedFetch(manga, requestUrl, foreground, visiblePriority, target)
        val initialForeground = foreground && target != null && target.page in 1..3
        if (initialForeground) {
            var acquired = false
            if (visiblePriority) activeVisibleInitialGeneratedFetches.incrementAndGet()
            return try {
                ntkGeneratedForegroundFetchGate.acquire(visiblePriority)
                acquired = true
                block()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while waiting for initial generated image gate").apply {
                    initCause(e)
                }
            } finally {
                if (acquired) ntkGeneratedForegroundFetchGate.release()
                if (visiblePriority) activeVisibleInitialGeneratedFetches.decrementAndGet()
            }
        }
        var acquired = false
        try {
            if (foreground) {
                ntkGeneratedForegroundFetchGate.acquire(visiblePriority)
            } else {
                waitForVisibleInitialGeneratedFetches(manga, image)
                ntkGeneratedBackgroundFetchGate.acquire()
            }
            acquired = true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Interrupted while waiting for generated image gate").apply {
                initCause(e)
            }
        }
        val waitedMs = SystemClock.elapsedRealtime() - startedAt
        if (waitedMs > 100L) {
            val available = if (foreground) {
                ntkGeneratedForegroundFetchGate.availablePermits()
            } else {
                ntkGeneratedBackgroundFetchGate.availablePermits()
            }
            val visibleWaiters = if (foreground) {
                ntkGeneratedForegroundFetchGate.visibleWaiterCount()
            } else {
                0
            }
            logCacheEvent(
                "ntk_generated_fetch_gate_wait",
                manga,
                image,
                foreground,
                "ms=$waitedMs,available=$available,visiblePriority=$visiblePriority,visibleWaiters=$visibleWaiters"
            )
        }
        return try {
            block()
        } finally {
            if (acquired) {
                if (foreground) {
                    ntkGeneratedForegroundFetchGate.release()
                } else {
                    ntkGeneratedBackgroundFetchGate.release()
                }
            }
        }
    }

    private fun maybeYieldForNtkAckBeforeGeneratedFetch(
        manga: Manga,
        image: String,
        foreground: Boolean,
        visiblePriority: Boolean,
        target: NtkGeneratedTarget?
    ) {
        if (target == null) return
        if (target.page <= NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return
        val path = ntkFallbackKeyPath(manga, target)
        val client = try {
            getHttpClient()
        } catch (_: Throwable) {
            return
        }
        val inFlight = try {
            client.isNtkWebViewAckPreflightInFlight(path)
        } catch (_: Throwable) {
            false
        }
        if (!inFlight) return
        val waitMs = when {
            foreground && visiblePriority -> NTK_GENERATED_ACK_GATE_VISIBLE_WAIT_MS
            foreground -> NTK_GENERATED_ACK_GATE_FOREGROUND_WAIT_MS
            else -> NTK_GENERATED_ACK_GATE_BACKGROUND_WAIT_MS
        }
        if (waitMs <= 0L) {
            val stage = if (foreground && visiblePriority) {
                "ntk_generated_ack_gate_skip_visible"
            } else {
                "ntk_generated_ack_gate_skip_foreground"
            }
            logCacheEvent(
                stage,
                manga,
                image,
                foreground,
                "page=${target.page},visiblePriority=$visiblePriority"
            )
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        var ok = false
        while (SystemClock.elapsedRealtime() - startedAt < waitMs) {
            ok = try {
                client.hasRecentNtkServerAckProof(path)
            } catch (_: Throwable) {
                false
            }
            if (ok) break
            val stillInFlight = try {
                client.isNtkWebViewAckPreflightInFlight(path)
            } catch (_: Throwable) {
                false
            }
            if (!stillInFlight) break
            try {
                Thread.sleep(80L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        logCacheEvent(
            "ntk_generated_ack_gate_done",
            manga,
            image,
            foreground,
            "page=${target.page},ok=$ok,visiblePriority=$visiblePriority," +
                "waitMs=$waitMs,ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
    }

    private fun waitForVisibleInitialGeneratedFetches(manga: Manga, image: String) {
        val requestUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
        if (!isNtkGeneratedImageUrl(requestUrl)) return
        val startedAt = SystemClock.elapsedRealtime()
        var waited = false
        while (activeVisibleInitialGeneratedFetches.get() > 0) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= 1800L) break
            waited = true
            try {
                Thread.sleep(40L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while yielding generated background fetch").apply {
                    initCause(e)
                }
            }
        }
        if (waited) {
            Log.d(
                TAG,
                "reader_image_cache_event stage=ntk_generated_background_yield_visible_initial," +
                    "foreground=false,path=${manga.ntkEpisodePath?.trim().orEmpty()}," +
                    "image=${image.substringAfterLast('/').takeLast(48)}," +
                    "ms=${SystemClock.elapsedRealtime() - startedAt}," +
                    "activeVisibleInitial=${activeVisibleInitialGeneratedFetches.get()}"
            )
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
        val generatedReferer = ntkGeneratedImageReferer(manga, image)
        for (entry in Utils.viewerImageRequestHeaders(image, manga.baseMode).entries) {
            if (generatedReferer != null && entry.key.equals("Referer", ignoreCase = true)) {
                requestBuilder.addHeader(entry.key, generatedReferer)
            } else {
                requestBuilder.addHeader(entry.key, entry.value)
            }
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

    private fun ntkGeneratedImageReferer(manga: Manga, image: String): String? {
        ntkGeneratedTarget(image) ?: return null
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return null
        val root = try {
            getHttpClient().getUrl(manga.baseMode)
        } catch (_: Throwable) {
            ""
        }.trimEnd('/')
        if (!root.startsWith("http")) return null
        return root + path
    }

    private fun shouldBypassQuicForHintedInitialGeneratedFull(
        image: String,
        foregroundPriority: Boolean
    ): Boolean {
        if (foregroundPriority) return false
        val target = ntkGeneratedTarget(image) ?: return false
        return target.page == 1 &&
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
        if (initialTarget != null && hasNtkGeneratedNotFound(manga, image)) {
            throw IOException("Generated image not found: page=${initialTarget.page} code=404")
        }
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
            if (isInitialGeneratedHintMismatch(image)) {
                logCacheEvent(
                    "generated_extension_hint_mismatch_stop",
                    manga,
                    image,
                    foreground,
                    "page=${initialTarget?.page ?: 0},hintedCode=$hintedCode"
                )
                ViewerWarmupManager.logMetric(
                    "ntk_generated_extension_hint_mismatch_stop",
                    (initialTarget?.page ?: 0).toLong()
                )
                throw hintedFailure ?: IOException("Generated hinted extension request failed: $hintedImage")
            }
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
        val rangeFirstTarget = ntkGeneratedTarget(image)
        if (foreground &&
            rangeFirstTarget != null &&
            rangeFirstTarget.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            shouldUseInitialGeneratedRangeFirst(image, rangeFirstTarget)
        ) {
            logCacheEvent(
                "generated_initial_range_first_start",
                manga,
                image,
                true,
                "page=${rangeFirstTarget.page}"
            )
            requestInitialGeneratedRangeWithDirectHedge(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge,
                rangeFirstTarget.page,
                "generated_initial_range_first"
            )?.let { rangeResponse ->
                if (rangeResponse.isSuccessful &&
                    acceptNtkGeneratedForegroundResponse(manga, image, rangeResponse, true)
                ) {
                    rememberNtkGeneratedEpisodeExtension(rangeResponse.request.url.toString())
                    logCacheEvent(
                        "generated_initial_range_first_hit",
                        manga,
                        image,
                        true,
                        "page=${rangeFirstTarget.page},code=${rangeResponse.code}"
                    )
                    return rangeResponse
                }
                logCacheEvent(
                    "generated_initial_range_first_miss",
                    manga,
                    image,
                    true,
                    "page=${rangeFirstTarget.page},code=${rangeResponse.code}"
                )
                if (isPermanentGeneratedMissingCode(rangeResponse.code)) {
                    rememberNtkGeneratedNotFound(
                        manga,
                        image,
                        "generated_initial_range_first_${rangeResponse.code}"
                    )
                    rangeResponse.close()
                    throw IOException("Generated image not found: page=${rangeFirstTarget.page} code=${rangeResponse.code}")
                }
                rangeResponse.close()
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
                    throwIfKnownNtkGeneratedNotFound(manga, image, "generated_extension_fallback_skip")
                    val apiFallbackTask = foregroundApiFallbackTask ?: target?.let { startNtkApiFallbackImages(context, manga, it) }
                    if (target != null &&
                        target.page > NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
                        apiFallbackTask != null
                    ) {
                        requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = true)
                            ?.let { return it }
                        throwIfKnownNtkGeneratedNotFound(manga, image, "generated_api_race_after")
                    }
                    if (target != null &&
                        target.page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
                        apiFallbackTask != null
                    ) {
                        logCacheEvent(
                            "generated_initial_api_before_same_url_retry",
                            manga,
                            image,
                            true,
                            "page=${target.page}"
                        )
                        requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                            ?.let { return it }
                        throwIfKnownNtkGeneratedNotFound(manga, image, "generated_initial_api_race_after")
                        retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                            ?.let { return it }
                        throwIfKnownNtkGeneratedNotFound(manga, image, "generated_initial_api_fallback_after")
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
                        throwIfKnownNtkGeneratedNotFound(manga, image, "generated_initial_original_retry_after")
                        retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
                        throwIfKnownNtkGeneratedNotFound(manga, image, "generated_initial_native_ack_retry_after")
                        retryNtkGeneratedViaApiFallback(
                            context,
                            manga,
                            image,
                            foreground,
                            apiFallbackTask,
                            cancellation,
                            plainRequest = true
                        )
                            ?.let { return it }
                        logCacheEvent(
                            "generated_api_next_retry_skip_initial_strict_page",
                            manga,
                            image,
                            foreground,
                            "page=${target.page}"
                        )
                        ViewerWarmupManager.logMetric("ntk_generated_api_next_retry_skip_initial_strict_page", target.page.toLong())
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
                    throwIfKnownNtkGeneratedNotFound(manga, image, "generated_original_retry_after")
                    retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)?.let { return it }
                    throwIfKnownNtkGeneratedNotFound(manga, image, "generated_native_ack_retry_after")
                    if (apiFallbackTask != null) {
                        requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                            ?.let { return it }
                        throwIfKnownNtkGeneratedNotFound(manga, image, "generated_api_race_after")
                        retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                            ?.let { return it }
                        throwIfKnownNtkGeneratedNotFound(manga, image, "generated_api_fallback_after")
                    }
                }
            if (shouldPreferGeneratedApiBeforeOriginalRetry(foreground, initialFailure)) {
                val apiFallbackTask = foregroundApiFallbackTask ?: target?.let { startNtkApiFallbackImages(context, manga, it) }
                if (apiFallbackTask != null) {
                    requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                        ?.let { return it }
                    throwIfKnownNtkGeneratedNotFound(manga, image, "generated_preferred_api_race_after")
                    retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                        ?.let { return it }
                    throwIfKnownNtkGeneratedNotFound(manga, image, "generated_preferred_api_fallback_after")
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
            throwIfKnownNtkGeneratedNotFound(manga, image, "generated_final_original_retry_after")
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
            retryNtkGeneratedAfterNativeAck(context, manga, image, foreground, cancellation)
                ?.let { return it }
            if (!directFallbackRace && apiFallbackTask != null) {
                requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                    ?.let { return it }
                retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                    ?.let { return it }
            }
            return requestForForegroundMode(context, manga, image, foreground = false, cancellation = cancellation)
        }
        val failedCode = response?.code ?: imageFailureCode(initialFailure)
        if (failedCode == 404) rememberNtkGeneratedNotFound(manga, image, "request_fallback")
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
            if (foreground &&
                target != null &&
                target.page > NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
            ) {
                val apiFallbackTask = foregroundApiFallbackTask ?: startNtkApiFallbackImages(context, manga, target)
                requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = true)
                    ?.let { return it }
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
                rememberNtkGeneratedNotFound(manga, fallback.request.url.toString(), "extension_fallback")
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

    private fun shouldUseInitialGeneratedRangeFirst(
        image: String,
        target: NtkGeneratedTarget
    ): Boolean {
        if (target.page == 1 && isSupportedNtkGeneratedImageExtension(image)) return true
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            ntkGeneratedEpisodeExtensionMatches(image)
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
        if (isInitialGeneratedHintMismatch(image)) {
            logCacheEvent(
                "generated_extension_full_race_skip_hinted_mismatch",
                manga,
                image,
                true,
                "page=${target.page}"
            )
            ViewerWarmupManager.logMetric("ntk_generated_extension_full_race_skip_hinted_mismatch", target.page.toLong())
            return null
        }
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
        val retry = try {
            requestOriginalNtkGeneratedRetry(
                context,
                manga,
                image,
                foreground,
                cancellation,
                foregroundRaceAttempts,
                anchorHedge,
                target.page,
                "generated_original_retry"
            )
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            if (isCancellationFailure(t)) throw asIOException(t)
            if (isKnownNtkGeneratedNotFoundFailure(manga, image, t)) throw asIOException(t)
            logCacheEvent(
                "generated_original_retry_continue_after_error",
                manga,
                image,
                foreground,
                "page=${target.page},error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
            )
            null
        } ?: return null
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
        if (target.page == 1) return
        val path = ntkFallbackKeyPath(manga, target)
        if (target.page > 1 && !isNtkAckRecoveryLaunchHeld(path)) return
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
            if (foreground) {
                requestGeneratedRangeReassembled(context, manga, image, cancellation, page, stage)?.let {
                    return it
                }
            }
            if (foreground &&
                hasNtkGeneratedNotFound(manga, image) &&
                isLikelyInvalidGeneratedTail(manga, image)
            ) {
                throw IOException("Generated image past tail: $image").apply {
                    initCause(t)
                }
            }
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

    private fun requestInitialGeneratedRangeWithDirectHedge(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        cancellation: Cancellation?,
        foregroundRaceAttempts: Int,
        anchorHedge: Boolean,
        page: Int,
        stage: String
    ): Response? {
        val completion = ExecutorCompletionService<Response?>(foregroundRaceExecutor)
        val finished = AtomicBoolean(false)
        completion.submit(Callable {
            val response = if (page <= 1) {
                requestSharedInitialGeneratedRange(context, manga, image, cancellation, page, stage)
            } else {
                requestGeneratedRangeReassembled(context, manga, image, cancellation, page, stage)
            }
            if (response != null && response.isSuccessful) finished.set(true)
            response
        })
        val directHedgeDelayMs = initialGeneratedDirectHedgeDelayMs(ntkGeneratedTarget(image), page, foreground)
        val expectedResults = if (directHedgeDelayMs >= 0L) 2 else 1
        if (directHedgeDelayMs >= 0L) {
            completion.submit(Callable {
                try {
                    val delayMs = directHedgeDelayMs
                Thread.sleep(delayMs)
                cancellation?.throwIfCancelled()
                if (finished.get()) return@Callable null
                logCacheEvent(
                    "${stage}_direct_hedge_start",
                    manga,
                    image,
                    foreground,
                    "page=$page,delayMs=$delayMs"
                )
                val response = requestForForegroundMode(
                    context,
                    manga,
                    image,
                    foreground,
                    cancellation,
                    foregroundRaceAttempts,
                    anchorHedge
                )
                if (response.isSuccessful) {
                    finished.set(true)
                    logCacheEvent(
                        "${stage}_direct_hedge_hit",
                        manga,
                        image,
                        foreground,
                        "page=$page,code=${response.code}"
                    )
                }
                response
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                logCacheEvent(
                    "${stage}_direct_hedge_error",
                    manga,
                    image,
                    foreground,
                    "page=$page,error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
                )
                null
            }
            })
        } else {
            val skipReason = if (ntkGeneratedTarget(image)?.path?.startsWith("/webtoon/", ignoreCase = true) == true) {
                "webtoon_range_first"
            } else {
                "manhwa_range_first"
            }
            logCacheEvent(
                "${stage}_direct_hedge_skip",
                manga,
                image,
                foreground,
                "page=$page,reason=$skipReason"
            )
        }
        var firstFailure: Response? = null
        repeat(expectedResults) {
            val response = try {
                completion.take().get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (e: ExecutionException) {
                logCacheEvent(
                    "${stage}_hedge_error",
                    manga,
                    image,
                    foreground,
                    "page=$page,error=${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}"
                )
                null
            }
            if (response != null && response.isSuccessful) {
                firstFailure?.close()
                return response
            }
            if (response != null) {
                if (firstFailure == null) firstFailure = response else response.close()
            }
        }
        return firstFailure
    }

    private fun requestSharedInitialGeneratedRange(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        page: Int,
        stage: String
    ): Response? {
        val flightKey = initialGeneratedRangeFlightKey(manga.baseMode, image, page)
            ?: return requestGeneratedRangeReassembled(context, manga, image, cancellation, page, stage)
        val startedAt = SystemClock.elapsedRealtime()
        val task = FutureTask {
            try {
                logCacheEvent(
                    "${stage}_shared_range_start",
                    manga,
                    image,
                    true,
                    "page=$page"
                )
                requestGeneratedRangeReassembled(context, manga, image, cancellation, page, stage)?.use { response ->
                    snapshotGeneratedRangeResponse(response)
                }
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                logCacheEvent(
                    "${stage}_shared_range_error",
                    manga,
                    image,
                    true,
                    "page=$page,error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
                )
                null
            }
        }
        val existing = initialGeneratedRangeFlights.putIfAbsent(flightKey, task)
        val running = if (existing == null) {
            task.run()
            task
        } else {
            logCacheEvent(
                "${stage}_shared_range_join",
                manga,
                image,
                true,
                "page=$page"
            )
            ViewerWarmupManager.logMetric("reader_generated_initial_range_shared_join", page.toLong())
            existing
        }
        return try {
            val snapshot = running.get() ?: return null
            logCacheEvent(
                "${stage}_shared_range_hit",
                manga,
                image,
                true,
                "page=$page,bytes=${snapshot.bytes.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            responseFromSnapshot(snapshot)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: ExecutionException) {
            null
        } finally {
            initialGeneratedRangeFlights.remove(flightKey, running)
        }
    }

    private fun initialGeneratedDirectHedgeDelayMs(target: NtkGeneratedTarget?, page: Int, foreground: Boolean): Long {
        if (page <= 1) {
            return 0L
        }
        if (target?.path?.startsWith("/webtoon/", ignoreCase = true) == true) {
            return NTK_GENERATED_INITIAL_DIRECT_HEDGE_WEBTOON_DELAY_MS
        }
        if (target?.path?.startsWith("/manhwa/", ignoreCase = true) == true &&
            page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
        ) {
            return if (foreground) 0L else -1L
        }
        return NTK_GENERATED_INITIAL_DIRECT_HEDGE_MANHWA_DELAY_MS
    }

    private fun requestGeneratedRangeReassembled(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        page: Int,
        stage: String
    ): Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        if (target.page != page) return null
        if (page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return null
        val firstChunkBytes = when (page) {
            1 -> NTK_GENERATED_RANGE_INITIAL_FIRST_CHUNK_BYTES
            in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES -> NTK_GENERATED_RANGE_ADJACENT_FIRST_CHUNK_BYTES
            else -> NTK_GENERATED_RANGE_CHUNK_BYTES
        }
        val chunkBytes = when (page) {
            1 -> NTK_GENERATED_RANGE_INITIAL_CHUNK_BYTES
            in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES -> NTK_GENERATED_RANGE_ADJACENT_CHUNK_BYTES
            else -> NTK_GENERATED_RANGE_CHUNK_BYTES
        }
        val startedAt = SystemClock.elapsedRealtime()
        logCacheEvent(
            "${stage}_range_reassemble_start",
            manga,
            image,
            true,
            "page=$page,firstChunk=$firstChunkBytes,chunk=$chunkBytes"
        )
        val first = requestGeneratedFirstRangeChunk(
            context,
            manga,
            image,
            firstChunkBytes,
            cancellation,
            page
        )
            ?: run {
                if (hasNtkGeneratedNotFound(manga, image)) {
                    throw IOException("Generated image not found: page=$page code=520")
                }
                return null
            }
        first.use { firstResponse ->
            if (firstResponse.code == 200) {
                val bytes = firstResponse.body?.bytes() ?: return null
                if (!looksLikeImage(bytes)) return null
                logCacheEvent(
                    "${stage}_range_reassemble_full_hit",
                    manga,
                    image,
                    true,
                    "page=$page,bytes=${bytes.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return responseFromBytes(firstResponse, bytes)
            }
            if (firstResponse.code != 206) {
                logCacheEvent(
                    "${stage}_range_reassemble_miss",
                    manga,
                    image,
                    true,
                    "page=$page,code=${firstResponse.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return null
            }
            val firstBytes = firstResponse.body?.bytes() ?: return null
            val total = parseContentRangeTotal(firstResponse.header("Content-Range"))
            if (total <= 0L || total > NTK_GENERATED_RANGE_MAX_BYTES) {
                logCacheEvent(
                    "${stage}_range_reassemble_skip_size",
                    manga,
                    image,
                    true,
                    "page=$page,total=$total,firstBytes=${firstBytes.size}"
                )
                return null
            }
            val out = ByteArrayOutputStream(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            out.write(firstBytes)
            val remainingBytes = readGeneratedRangeTailChunks(
                context,
                manga,
                image,
                cancellation,
                page,
                stage,
                firstBytes.size.toLong(),
                total,
                chunkBytes
            ) ?: return null
            for (bytes in remainingBytes) {
                out.write(bytes)
            }
            val bytes = out.toByteArray()
            if (!looksLikeImage(bytes)) {
                logCacheEvent(
                    "${stage}_range_reassemble_invalid",
                    manga,
                    image,
                    true,
                    "page=$page,bytes=${bytes.size}"
                )
                return null
            }
            logCacheEvent(
                "${stage}_range_reassemble_hit",
                manga,
                image,
                true,
                "page=$page,bytes=${bytes.size},chunks=${(bytes.size + chunkBytes - 1) / chunkBytes},ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return responseFromBytes(firstResponse, bytes)
        }
    }

    private fun readGeneratedRangeTailChunks(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        page: Int,
        stage: String,
        start: Long,
        total: Long,
        chunkBytes: Int
    ): List<ByteArray>? {
        if (start >= total) return emptyList()
        data class RangeRequest(val start: Long, val end: Long)

        val requests = ArrayList<RangeRequest>()
        var nextStart = start
        while (nextStart < total) {
            val nextEnd = minOf(total - 1, nextStart + chunkBytes - 1)
            requests += RangeRequest(nextStart, nextEnd)
            nextStart = nextEnd + 1
        }
        if (page == 1 && requests.size > 1) {
            val futures = requests.mapIndexed { index, request ->
                foregroundRaceExecutor.submit(Callable<Pair<Int, ByteArray>?> {
                    cancellation?.throwIfCancelled()
                    val chunk = requestGeneratedRangeChunk(
                        context,
                        manga,
                        image,
                        request.start,
                        request.end,
                        cancellation
                    ) ?: return@Callable null
                    chunk.use { response ->
                        if (response.code != 206 && response.code != 200) {
                            logCacheEvent(
                                "${stage}_range_reassemble_chunk_miss",
                                manga,
                                image,
                                true,
                                "page=$page,code=${response.code},start=${request.start},end=${request.end}"
                            )
                            return@Callable null
                        }
                        val bytes = response.body?.bytes() ?: return@Callable null
                        if (bytes.isEmpty()) return@Callable null
                        index to bytes
                    }
                })
            }
            val ordered = arrayOfNulls<ByteArray>(requests.size)
            try {
                var remaining = futures.size
                while (remaining > 0) {
                    var progressed = false
                    for (future in futures) {
                        if (!future.isDone) continue
                        val result = try {
                            future.get()
                        } catch (_: Exception) {
                            null
                        } ?: return null
                        if (ordered[result.first] == null) {
                            ordered[result.first] = result.second
                            remaining--
                            progressed = true
                        }
                    }
                    if (!progressed) {
                        try {
                            Thread.sleep(20L)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return null
                        }
                    }
                }
            } finally {
                futures.forEach { future ->
                    if (!future.isDone) future.cancel(true)
                }
            }
            logCacheEvent(
                "${stage}_range_reassemble_tail_parallel",
                manga,
                image,
                true,
                "page=$page,chunks=${requests.size}"
            )
            return ordered.map { it ?: return null }
        }
        val ordered = ArrayList<ByteArray>(requests.size)
        for (request in requests) {
            cancellation?.throwIfCancelled()
            val chunk = requestGeneratedRangeChunk(
                context,
                manga,
                image,
                request.start,
                request.end,
                cancellation
            ) ?: return null
            chunk.use { response ->
                if (response.code != 206 && response.code != 200) {
                    logCacheEvent(
                        "${stage}_range_reassemble_chunk_miss",
                        manga,
                        image,
                        true,
                        "page=$page,code=${response.code},start=${request.start},end=${request.end}"
                    )
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                ordered += bytes
            }
        }
        return ordered
    }

    private fun requestGeneratedFirstRangeChunk(
        context: Context,
        manga: Manga,
        image: String,
        preferredBytes: Int,
        cancellation: Cancellation?,
        page: Int
    ): Response? {
        val attempts = if (page == 1) {
            intArrayOf(2048, preferredBytes, 512, 32)
        } else {
            intArrayOf(preferredBytes, 2048, 512, 32)
        }
            .filter { it > 0 }
            .distinct()
        for (byteCount in attempts) {
            val response = requestGeneratedRangeChunk(
                context,
                manga,
                image,
                0L,
                byteCount.toLong() - 1L,
                cancellation
            )
            if (response == null) {
                logCacheEvent(
                    "generated_range_first_chunk_retry",
                    manga,
                    image,
                    true,
                    "bytes=$byteCount,result=null"
                )
                continue
            }
            if (response.code == 200 || response.code == 206) {
                return response
            }
            if (isPermanentGeneratedMissingCode(response.code)) {
                rememberNtkGeneratedNotFound(manga, image, "generated_range_first_chunk_${response.code}")
                logCacheEvent(
                    "generated_range_first_chunk_not_found",
                    manga,
                    image,
                    true,
                    "bytes=$byteCount,code=${response.code}"
                )
                response.close()
                return null
            }
            logCacheEvent(
                "generated_range_first_chunk_retry",
                manga,
                image,
                true,
                "bytes=$byteCount,code=${response.code}"
            )
            response.close()
        }
        return null
    }

    private fun requestGeneratedRangeChunk(
        context: Context,
        manga: Manga,
        image: String,
        start: Long,
        end: Long,
        cancellation: Cancellation?
    ): Response? {
        val request = requestFor(manga, image)
            .newBuilder()
            .removeHeader("X-MangaViewer-Foreground")
            .header("X-MangaViewer-No-Quic", "1")
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$start-$end")
            .build()
        val headers = LinkedHashMap<String, String>()
        for (name in request.headers.names()) {
            request.header(name)?.let { headers[name] = it }
        }
        return try {
            getHttpClient().fetchNtkGeneratedImageRange(request.url.toString(), headers, start, end)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "generated_range_chunk_error",
                manga,
                image,
                true,
                "start=$start,end=$end,error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
            )
            null
        }
    }

    private fun parseContentRangeTotal(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        return Regex("/(\\d+)\\s*$").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
    }

    private fun responseFromBytes(source: Response, bytes: ByteArray): Response {
        return source.newBuilder()
            .code(200)
            .message("OK")
            .protocol(source.protocol.takeIf { it != Protocol.HTTP_2 } ?: Protocol.HTTP_1_1)
            .removeHeader("Content-Range")
            .removeHeader("Content-Encoding")
            .header("Content-Length", bytes.size.toString())
            .header("x-mangaviewer-transport", "generated-range")
            .body(ResponseBody.create(source.body?.contentType(), bytes))
            .build()
    }

    private fun snapshotGeneratedRangeResponse(source: Response): GeneratedRangeSnapshot? {
        val body = source.body ?: return null
        val bytes = body.bytes()
        if (bytes.isEmpty()) return null
        return GeneratedRangeSnapshot(
            request = source.request,
            protocol = source.protocol,
            code = source.code,
            message = source.message,
            headers = source.headers,
            contentType = body.contentType(),
            bytes = bytes
        )
    }

    private fun responseFromSnapshot(snapshot: GeneratedRangeSnapshot): Response {
        return Response.Builder()
            .request(snapshot.request)
            .protocol(snapshot.protocol.takeIf { it != Protocol.HTTP_2 } ?: Protocol.HTTP_1_1)
            .code(snapshot.code)
            .message(snapshot.message.ifEmpty { "OK" })
            .headers(snapshot.headers)
            .removeHeader("Content-Range")
            .removeHeader("Content-Encoding")
            .header("Content-Length", snapshot.bytes.size.toString())
            .header("x-mangaviewer-transport", "generated-range-shared")
            .body(ResponseBody.create(snapshot.contentType, snapshot.bytes))
            .build()
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
                requestGeneratedDirectOriginal(context, manga, image).also { response ->
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
            if (sameUrl) {
                Log.d(
                    TAG,
                    "ntk_generated_image_race_api_skip_same_url page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return@Callable null
            }
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
                Log.d(
                    TAG,
                    "ntk_generated_image_race_api_error page=${target.page},sameUrl=$sameUrl,error=${t.javaClass.simpleName},ms=${SystemClock.elapsedRealtime() - startedAt},summary=${throwableSummary(t)}"
                )
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

    private fun requestGeneratedDirectFallbacks(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation? = null
    ): Response? {
        val first = try {
            request(context, manga, image, cancellation)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            null
        }
        if (first != null && first.isSuccessful) return first
        first?.close()
        for (candidate in ntkGeneratedExtensionFallbacks(image)) {
            val fallback = try {
                request(context, manga, candidate, cancellation)
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
        if (isInitialGeneratedHintMismatch(image)) {
            logCacheEvent(
                "generated_native_ack_retry_skip_hinted_mismatch",
                manga,
                image,
                foreground,
                "page=${target.page}"
            )
            ViewerWarmupManager.logMetric("ntk_generated_native_ack_retry_skip_hinted_mismatch", target.page.toLong())
            return null
        }
        val path = ntkFallbackKeyPath(manga, target)
        logCacheEvent(
            "generated_native_ack_retry_consider",
            manga,
            image,
            foreground,
            "page=${target.page}"
        )
        if (isNtkAckRecoveryLaunchHeld(path)) {
            if (shouldAllowGeneratedNativeAckDuringLaunchHold(path, target, foreground)) {
                val acked = waitForLaunchHoldNtkAckProofFromImageWorker(
                    getHttpClient(),
                    target,
                    path,
                    manga,
                    image,
                    foreground
                )
                logCacheEvent(
                    "generated_native_ack_retry_launch_hold_ack_done",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},acked=$acked"
                )
                if (acked) {
                    ViewerWarmupManager.logMetric("ntk_generated_image_launch_hold_native_ack_retry", target.page.toLong())
                    retryNtkGeneratedViaApiFallback(
                        context,
                        manga,
                        image,
                        foreground,
                        cancellation = cancellation,
                        plainRequest = true
                    )?.let { return it }
                    val retry = try {
                        requestForForegroundMode(context, manga, image, foreground, cancellation)
                    } catch (t: Throwable) {
                        rethrowIfFatal(t)
                        null
                    }
                    if (retry != null && retry.isSuccessful) return retry
                    retry?.close()
                }
                releaseNtkAckRecoveryAfterAckProofFailure(path)
                logCacheEvent(
                    "generated_native_ack_retry_launch_hold_released",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},acked=false"
                )
            } else {
                logCacheEvent(
                    "generated_native_ack_retry_skip",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},reason=launch_first_drawable_hold"
                )
                return null
            }
        }
        try {
            cancellation?.throwIfCancelled()
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "generated_native_ack_retry_cancelled",
                manga,
                image,
                foreground,
                "page=${target.page},summary=${throwableSummary(t)}"
            )
            throw asIOException(t)
        }
        logCacheEvent(
            "generated_native_ack_retry_start",
            manga,
            image,
            foreground,
            "page=${target.page}"
        )
        val client = getHttpClient()
        val acked = waitForRecentNtkAckProofFromImageWorker(
            client,
            path,
            manga,
            image,
            foreground,
            target.page
        )
        logCacheEvent(
            "generated_native_ack_retry_ack_done",
            manga,
            image,
            foreground,
            "page=${target.page},acked=$acked"
        )
        if (!acked && foreground && target.page == 1) {
            logCacheEvent(
                "generated_native_ack_retry_without_proof",
                manga,
                image,
                true,
                "page=${target.page}"
            )
            retryNtkGeneratedViaApiFallback(
                context,
                manga,
                image,
                true,
                cancellation = cancellation,
                plainRequest = false
            )?.let { return it }
            val retry = try {
                requestForForegroundMode(context, manga, image, true, cancellation)
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                null
            }
            if (retry != null && retry.isSuccessful) return retry
            retry?.close()
        }
        if (!acked) return null
        ViewerWarmupManager.logMetric("ntk_generated_image_native_ack_retry", target.page.toLong())
        retryNtkGeneratedViaApiFallback(
            context,
            manga,
            image,
            foreground,
            cancellation = cancellation,
            plainRequest = true
        )?.let { return it }
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

    private fun waitForRecentNtkAckProofFromImageWorker(
        client: CustomHttpClient,
        path: String,
        manga: Manga,
        image: String,
        foreground: Boolean,
        page: Int
    ): Boolean {
        val initialProof = try {
            client.hasRecentNtkServerAckProof(path)
        } catch (_: Throwable) {
            false
        }
        if (initialProof) return true
        val inFlight = try {
            client.isNtkWebViewAckPreflightInFlight(path)
        } catch (_: Throwable) {
            false
        }
        if (!inFlight) {
            logCacheEvent(
                "generated_native_ack_retry_no_webview",
                manga,
                image,
                foreground,
                "page=$page"
            )
            return false
        }
        val waitMs = if (foreground && page == 1) 360L else if (foreground) 1_200L else 320L
        val startedAt = SystemClock.elapsedRealtime()
        var ok = false
        while (SystemClock.elapsedRealtime() - startedAt < waitMs) {
            ok = try {
                client.hasRecentNtkServerAckProof(path)
            } catch (_: Throwable) {
                false
            }
            if (ok) break
            val stillInFlight = try {
                client.isNtkWebViewAckPreflightInFlight(path)
            } catch (_: Throwable) {
                false
            }
            if (!stillInFlight) break
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        logCacheEvent(
            "generated_native_ack_retry_light_wait_done",
            manga,
            image,
            foreground,
            "page=$page,acked=$ok,waitMs=$waitMs,ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return ok
    }

    private fun shouldAllowGeneratedNativeAckDuringLaunchHold(
        path: String,
        target: NtkGeneratedTarget,
        foreground: Boolean
    ): Boolean {
        return foreground && target.page == 1 && path.isNotBlank()
    }

    private fun waitForLaunchHoldNtkAckProofFromImageWorker(
        client: CustomHttpClient,
        target: NtkGeneratedTarget,
        path: String,
        manga: Manga,
        image: String,
        foreground: Boolean
    ): Boolean {
        val initialProof = try {
            client.hasRecentNtkServerAckProof(path)
        } catch (_: Throwable) {
            false
        }
        if (initialProof) return true
        val key = "${target.baseUrl}$path|native-launch-hold"
        val created = FutureTask {
            val startedAt = SystemClock.elapsedRealtime()
            logCacheEvent(
                "generated_native_ack_launch_hold_start",
                manga,
                image,
                foreground,
                "page=${target.page},path=$path"
            )
            val ok = try {
                client.performNtkNativeAckBypassIgnoringWebViewInFlight(target.baseUrl, path, path)
            } catch (_: Throwable) {
                false
            }
            logCacheEvent(
                "generated_native_ack_launch_hold_done",
                manga,
                image,
                foreground,
                "page=${target.page},path=$path,success=$ok,ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            ok
        }
        val task = ntkGeneratedAckRecoveryFlights.putIfAbsent(key, created) ?: created.also {
            try {
                ntkApiFallbackExecutor.execute(it)
            } catch (_: Exception) {
                it.run()
            }
        }
        if (task !== created) {
            logCacheEvent(
                "generated_native_ack_launch_hold_join",
                manga,
                image,
                foreground,
                "page=${target.page},path=$path"
            )
        }
        val waitMs = if (foreground) {
            NTK_GENERATED_ACK_GATE_FOREGROUND_WAIT_MS
        } else {
            NTK_GENERATED_ACK_GATE_BACKGROUND_WAIT_MS
        }
        val ok = try {
            task.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            if (task.isDone) ntkGeneratedAckRecoveryFlights.remove(key, task)
        }
        val proof = try {
            client.hasRecentNtkServerAckProof(path)
        } catch (_: Throwable) {
            false
        }
        logCacheEvent(
            "generated_native_ack_launch_hold_wait_done",
            manga,
            image,
            foreground,
            "page=${target.page},ok=$ok,proof=$proof,waitMs=$waitMs,done=${task.isDone}"
        )
        return ok || proof
    }

    private fun retryNtkGeneratedViaApiFallback(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        runningTask: FutureTask<List<String>?>? = null,
        cancellation: Cancellation? = null,
        plainRequest: Boolean = false
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
        if (replacement == image && foreground) {
            ntkApiFallbackImages.remove("${target.baseUrl}${ntkFallbackKeyPath(manga, target)}")
            logCacheEvent(
                "generated_api_retry_skip_same_url",
                manga,
                image,
                true,
                "page=${target.page},plain=$plainRequest"
            )
            return null
        }
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
            logCacheEvent(
                "generated_api_retry_request",
                manga,
                image,
                foreground,
                "page=${target.page},sameUrl=${replacement == image},plain=$plainRequest"
            )
            if (plainRequest) {
                request(context, manga, replacement, cancellation)
            } else {
                requestForForegroundMode(context, manga, replacement, foreground, cancellation)
            }
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "generated_api_retry_error",
                manga,
                image,
                foreground,
                "page=${target.page},sameUrl=${replacement == image},plain=$plainRequest,error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
            )
            null
        }
        if (retry != null && retry.isSuccessful) {
            logCacheEvent(
                "generated_api_retry_hit",
                manga,
                image,
                foreground,
                "page=${target.page},sameUrl=${replacement == image},plain=$plainRequest,code=${retry.code}"
            )
            return retry
        }
        if (retry != null) {
            logCacheEvent(
                "generated_api_retry_miss",
                manga,
                image,
                foreground,
                "page=${target.page},sameUrl=${replacement == image},plain=$plainRequest,code=${retry.code}"
            )
        }
        retry?.close()
        return null
    }

    private fun retryNtkGeneratedViaNextApiFallback(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean,
        runningTask: FutureTask<List<String>?>? = null,
        cancellation: Cancellation? = null
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        val images = fetchNtkApiFallbackImages(
            context,
            manga,
            target,
            runningTask,
            waitMs = if (foreground) 1_200L else 6_000L
        ) ?: return null
        val start = target.page
        val endExclusive = minOf(images.size, start + 5)
        for (index in start until endExclusive) {
            cancellation?.throwIfCancelled()
            val candidate = images.getOrNull(index) ?: continue
            val candidateTarget = ntkGeneratedTarget(candidate) ?: continue
            if (!isCompatibleNtkGeneratedEpisode(target, candidateTarget) ||
                candidateTarget.page <= target.page
            ) continue
            val claimKey = "${candidateTarget.baseUrl}|${candidateTarget.path}|${candidateTarget.page}"
            if (!ntkGeneratedReplacementClaims.add(claimKey)) {
                logCacheEvent(
                    "generated_api_next_retry_claim_skip",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},candidatePage=${candidateTarget.page}"
                )
                continue
            }
            val retry = try {
                logCacheEvent(
                    "generated_api_next_retry_request",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},candidatePage=${candidateTarget.page}"
                )
                request(context, manga, candidate, cancellation)
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                logCacheEvent(
                    "generated_api_next_retry_error",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},candidatePage=${candidateTarget.page},error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
                )
                ntkGeneratedReplacementClaims.remove(claimKey)
                null
            }
            if (retry != null && retry.isSuccessful) {
                logCacheEvent(
                    "generated_api_next_retry_hit",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},candidatePage=${candidateTarget.page},code=${retry.code}"
                )
                return retry.newBuilder()
                    .header("x-mangaviewer-generated-replacement", "1")
                    .header("x-mangaviewer-generated-source-page", target.page.toString())
                    .header("x-mangaviewer-generated-replacement-page", candidateTarget.page.toString())
                    .build()
            }
            ntkGeneratedReplacementClaims.remove(claimKey)
            if (retry != null) {
                logCacheEvent(
                    "generated_api_next_retry_miss",
                    manga,
                    image,
                    foreground,
                    "page=${target.page},candidatePage=${candidateTarget.page},code=${retry.code}"
                )
            }
            retry?.close()
        }
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
            copy.ntkImageWorkId = source.ntkImageWorkId
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
                context,
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

    private fun isCurrentNtkPathGeneratedPage(
        manga: Manga,
        expected: NtkGeneratedTarget,
        actual: NtkGeneratedTarget
    ): Boolean {
        if (actual.page != expected.page) return false
        val currentPath = manga.ntkEpisodePath?.trim().orEmpty()
        if (currentPath.isBlank()) return false
        return actual.path.trimEnd('/').equals(currentPath.trimEnd('/'), ignoreCase = true)
    }

    private fun isCompatibleNtkGeneratedEpisode(
        expected: NtkGeneratedTarget,
        actual: NtkGeneratedTarget
    ): Boolean {
        if (actual.path == expected.path) return true
        val expectedParts = expected.path.trim('/').split('/')
        val actualParts = actual.path.trim('/').split('/')
        if (expectedParts.size < 3 || actualParts.size < 3) return false
        if (!expectedParts[0].equals(actualParts[0], ignoreCase = true)) return false
        return expectedParts[2] == actualParts[2]
    }

    private fun shouldRaceForegroundImage(image: String): Boolean {
        return true
    }

    private fun requestForegroundRace(
        context: Context,
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
        val firstEarlyNtkImage = isFirstEarlyNtkImage(manga, image)
        val attempts = foregroundRaceAttempts(foregroundRequest, raceAttempts, firstEarlyNtkImage)
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
                    val response = call.execute()
                    if (completed.get()) {
                        response.close()
                        throw IOException("Foreground image race already completed")
                    }
                    ForegroundRaceResult(attempt, call, response)
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
        fun submitGeneratedDirect(target: NtkGeneratedTarget) {
            completion.submit(Callable {
                cancellation?.throwIfCancelled()
                if (completed.get()) throw IOException("Foreground image race already completed")
                val response = requestGeneratedDirectOriginal(context, manga, image, cancellation)
                    ?: throw IOException("Foreground generated direct race failed")
                if (completed.get()) {
                    response.close()
                    throw IOException("Foreground image race already completed")
                }
                ForegroundRaceResult(
                    ForegroundRaceAttempt("generated-direct", getHttpClient().imageClient, foregroundRequest),
                    null,
                    response
                ).also {
                    logCacheEvent(
                        "foreground_race_generated_direct_done",
                        manga,
                        image,
                        true,
                        "page=${target.page},code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
            })
        }
        attempts.forEachIndexed { index, attempt ->
            val delayMs = if (firstEarlyNtkImage) 0L else FOREGROUND_RACE_DELAY_MS * index
            submit(attempt, delayMs)
        }
        val generatedTarget = ntkGeneratedTarget(image)
        val includeGeneratedDirectLane = generatedTarget != null &&
            generatedTarget.page > NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            isInitialGeneratedTransientCacheReady(context, manga, image)
        if (generatedTarget != null &&
            generatedTarget.page > NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            !includeGeneratedDirectLane
        ) {
            logCacheEvent(
                "foreground_race_generated_direct_defer_initial_pages",
                manga,
                image,
                true,
                "page=${generatedTarget.page}"
            )
        }
        if (includeGeneratedDirectLane) {
            submitGeneratedDirect(generatedTarget!!)
            logCacheEvent(
                "foreground_race_generated_direct_start",
                manga,
                image,
                true,
                "page=${generatedTarget.page},total=${attempts.size + 1}"
            )
        }
        val generatedAnchor = generatedTarget?.page == 1
        val generatedFastFallback = false
        var failure: Throwable? = null
        val totalAttempts = attempts.size + (if (includeGeneratedDirectLane) 1 else 0)
        repeat(totalAttempts) { completedIndex ->
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
                    if (response.header("x-mangaviewer-partial-image") == "1") {
                        failure = IOException("Partial foreground image response")
                        logCacheEvent(
                            "foreground_race_partial_miss",
                            manga,
                            image,
                            true,
                            "transport=${result.attempt.transport},completed=$completedIndex,total=${attempts.size},code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        response.close()
                        return@repeat
                    }
                    if (completedIndex > 0) ViewerWarmupManager.logMetric("reader_foreground_image_race_won", completedIndex.toLong())
                    completed.set(true)
                    for (call in calls) {
                        if (call !== result.call) call.cancel()
                    }
                    closeCompletedForegroundRaceLosers(completion, result.response)
                    logCacheEvent(
                        "foreground_race_win",
                        manga,
                        image,
                        true,
                        "transport=${result.attempt.transport},completed=$completedIndex,total=$totalAttempts,code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return response
                }
                failure = IOException("Image request failed: ${response.code}")
                if (isPermanentGeneratedMissingCode(response.code)) {
                    rememberNtkGeneratedNotFound(manga, response.request.url.toString(), "foreground_race")
                }
                logCacheEvent(
                    "foreground_race_miss",
                    manga,
                    image,
                    true,
                    "transport=${result.attempt.transport},completed=$completedIndex,total=$totalAttempts,code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                response.close()
                if (generatedFastFallback && completedIndex == 0) {
                    for (call in calls) call.cancel()
                    throw IOException("Foreground generated race fast fallback", failure)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                for (call in calls) call.cancel()
                throw java.io.InterruptedIOException("Interrupted while racing image request").apply {
                    initCause(e)
                }
            } catch (e: ExecutionException) {
                failure = e.cause ?: e
                if (generatedFastFallback && completedIndex == 0) {
                    logCacheEvent(
                        "foreground_race_generated_anchor_fast_fallback",
                        manga,
                        image,
                        true,
                        "page=${generatedTarget?.page ?: 0},error=${failure?.javaClass?.simpleName ?: "ExecutionException"}," +
                            "ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    for (call in calls) call.cancel()
                    throw IOException("Foreground generated race fast fallback", failure)
                }
            }
        }
        for (call in calls) call.cancel()
        throw IOException("Foreground image race failed", failure)
    }

    private fun closeCompletedForegroundRaceLosers(
        completion: ExecutorCompletionService<ForegroundRaceResult>,
        winningResponse: Response
    ) {
        while (true) {
            val future = completion.poll() ?: return
            try {
                val response = future.get().response
                if (response !== winningResponse) {
                    response.close()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestGeneratedDirectOriginal(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation? = null
    ): Response? {
        return try {
            request(context, manga, image, cancellation)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            null
        }
    }

    private fun foregroundRaceAttempts(
        foregroundRequest: Request,
        raceAttempts: Int,
        firstEarlyNtkImage: Boolean
    ): List<ForegroundRaceAttempt> {
        val httpClient = getHttpClient()
        val attempts = raceAttempts.coerceIn(1, 2)
        val foregroundAttempts = List(attempts) { index ->
            ForegroundRaceAttempt("image-${index + 1}", httpClient.imageClient, foregroundRequest)
        }
        val fullRequest = foregroundRequest.newBuilder()
            .removeHeader("X-MangaViewer-Foreground")
            .header("X-MangaViewer-No-Quic", "1")
            .build()
        val fullAttempt = ForegroundRaceAttempt("image-full", httpClient.imageClient, fullRequest)
        val generatedTarget = ntkGeneratedTarget(foregroundRequest.url.toString())
        if (generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            return listOf(fullAttempt) + foregroundAttempts
        }
        if (firstEarlyNtkImage || generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            return listOf(fullAttempt) + foregroundAttempts
        }
        return foregroundAttempts + fullAttempt
    }

    private fun isFirstEarlyNtkImage(manga: Manga, image: String): Boolean {
        val path = manga.ntkEpisodePath ?: return false
        val first = earlyNtkImageUrls(path, 0L).firstOrNull() ?: return false
        val target = ntkGeneratedTarget(image) ?: return normalizedNtkImageIdentity(first) == normalizedNtkImageIdentity(image)
        return sameNtkGeneratedPage(first, target)
    }

    private fun sameNtkGeneratedPage(candidate: String, target: NtkGeneratedTarget): Boolean {
        val candidateTarget = ntkGeneratedTarget(candidate) ?: return false
        return candidateTarget.path == target.path && candidateTarget.page == target.page
    }

    private fun normalizedNtkImageIdentity(image: String): String {
        return image.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("//")
            .substringBefore('#')
            .substringBefore('?')
            .lowercase()
    }


    private fun shouldTryNtkGeneratedExtensionFallback(image: String): Boolean {
        val lower = image.lowercase()
        return (lower.contains("://toonflix.app/")
                || lower.contains("://i.toonflix.app/")
                || Regex("://flysky\\d*m\\.com/").containsMatchIn(lower)
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
            val key = "${numericMatch.groupValues[1].lowercase()}/${numericMatch.groupValues[2]}/${numericMatch.groupValues[3]}"
            return NtkGeneratedImageRef(key, numericMatch.groupValues[5].lowercase())
        }
        val blacktoonMatch = Regex("^(?:https?://[^/]+)/blacktoon/episodes/(\\d+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (blacktoonMatch != null) {
            val key = "webtoon/${blacktoonMatch.groupValues[1]}/${blacktoonMatch.groupValues[2]}"
            return NtkGeneratedImageRef(key, blacktoonMatch.groupValues[4].lowercase())
        }
        val slugMatch = Regex("^(?:https?://[^/]+)/wt/episodes/([^/?#]+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image) ?: return null
        val key = "wt/${slugMatch.groupValues[1]}/${slugMatch.groupValues[2]}"
        return NtkGeneratedImageRef(key, slugMatch.groupValues[4].lowercase())
    }

    private fun rememberNtkGeneratedEpisodeExtension(image: String) {
        val ref = ntkGeneratedImageRef(image) ?: return
        val previous = ntkGeneratedEpisodeExtensions.put(ref.episodeKey, ref.extension)
        if (previous != ref.extension) {
            Log.d(TAG, "ntk_generated_episode_extension_hint key=${ref.episodeKey},extension=${ref.extension},previous=${previous.orEmpty()}")
        }
    }

    private fun rememberNtkGeneratedNotFound(manga: Manga, image: String, source: String) {
        val target = ntkGeneratedTarget(image) ?: return
        val key = ntkGeneratedPageStateKey(manga, target)
        if (ntkGeneratedNotFoundPages.add(key)) {
            Log.d(TAG, "ntk_generated_not_found key=$key,source=$source,image=${image.substringAfterLast('/').takeLast(64)}")
        }
    }

    private fun isPermanentGeneratedMissingCode(code: Int): Boolean {
        return code == 404 || code == 410 || code == 520
    }

    private fun hasNtkGeneratedNotFound(manga: Manga, image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        return ntkGeneratedNotFoundPages.contains(ntkGeneratedPageStateKey(manga, target))
    }

    fun isKnownNtkGeneratedNotFound(manga: Manga, image: String): Boolean {
        return hasNtkGeneratedNotFound(manga, image)
    }

    private fun isKnownNtkGeneratedNotFoundFailure(manga: Manga, image: String, throwable: Throwable? = null): Boolean {
        if (hasNtkGeneratedNotFound(manga, image)) return true
        var current = throwable
        while (current != null) {
            if (current.message.orEmpty().contains("Generated image not found")) return true
            current = current.cause
        }
        return false
    }

    private fun throwIfKnownNtkGeneratedNotFound(manga: Manga, image: String, source: String) {
        val target = ntkGeneratedTarget(image) ?: return
        if (!isKnownNtkGeneratedNotFoundFailure(manga, image)) return
        logCacheEvent(
            "generated_known_not_found_abort",
            manga,
            image,
            true,
            "page=${target.page},source=$source"
        )
        throw IOException("Generated image not found: page=${target.page}")
    }

    private fun ntkGeneratedPageStateKey(manga: Manga, target: NtkGeneratedTarget): String {
        val path = manga.ntkEpisodePath?.takeIf { it.isNotBlank() } ?: target.path
        return "$path|${target.page}"
    }

    private fun rememberEarlyNtkGeneratedSuccess(manga: Manga, image: String) {
        val path = manga.ntkEpisodePath
        val target = ntkGeneratedTarget(image) ?: return
        if (path.isNullOrBlank() || target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return
        val existing = earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
        val merged = if (existing.isEmpty()) {
            listOf(image)
        } else {
            val replaced = ArrayList<String>(existing.size + 1)
            var inserted = false
            for (url in existing) {
                val existingTarget = ntkGeneratedTarget(url)
                if (existingTarget != null && existingTarget.page == target.page) {
                    if (!inserted) {
                        replaced.add(image)
                        inserted = true
                    }
                } else {
                    replaced.add(url)
                }
            }
            if (!inserted) {
                val insertIndex = (target.page - 1).coerceIn(0, replaced.size)
                replaced.add(insertIndex, image)
            }
            replaced
        }
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

    private fun isInitialGeneratedHintMismatch(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        return ntkGeneratedEpisodeExtensionHinted(image) &&
            !ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun shouldDropMismatchedHintedInitialGenerated(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return false
        return ntkGeneratedEpisodeExtensionHinted(image) &&
            !ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun isVerifiedInitialGeneratedVariant(
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget?
    ): Boolean {
        if (target == null || target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return false
        if (!ntkGeneratedEpisodeExtensionMatches(image)) return false
        val ref = ntkGeneratedImageRef(image) ?: return false
        val activePath = earlyNtkPathKey(manga.ntkEpisodePath)
        return target.path != activePath || ref.extension != "jpg"
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
            || isLikelyAdChromeImageCandidate(candidate)
            || isDisallowedNtkImageAssetUrl(lower)
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

    private fun isLikelyAdChromeImageCandidate(url: String): Boolean {
        val path = try {
            Uri.parse(url).path.orEmpty().lowercase()
        } catch (_: Exception) {
            url.lowercase()
        }
        if (path.contains("/data/banner/")
            || path.contains("/banner/")
            || path.contains("/banners/")
            || path.contains("/advert/")
            || path.contains("/advertise/")
            || path.contains("/advertisement/")
            || path.contains("/ads/")
            || path.contains("/popup/")
            || path.contains("/popups/")
            || path.contains("/sponsor/")
            || path.contains("/promotion/")
            || path.contains("/promotions/")
        ) return true
        val name = path.substringAfterLast('/')
        return name.startsWith("ad_")
            || name.startsWith("ad-")
            || name.startsWith("banner_")
            || name.startsWith("banner-")
            || name.startsWith("popup_")
            || name.startsWith("popup-")
    }

    private fun isNtkProtectedImageUrl(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty()) return false
        if (host.contains("naver") || host.contains("pstatic")) return false
        if (isDisallowedNtkImageAssetUrl(url)) return false
        return host == "toonflix.app" ||
            host.endsWith(".toonflix.app") ||
            Regex("^flysky\\d*m\\.com$").matches(host) ||
            Regex("^fvcdn\\d*\\.com$").matches(host) ||
            Regex("^aws-cdn\\d*\\.site$").matches(host) ||
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
            if (bounds.outWidth < MIN_REAL_IMAGE_DIMENSION_PX || bounds.outHeight < MIN_REAL_IMAGE_DIMENSION_PX) return false
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
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) {
            if (bytes.size < MIN_REAL_GIF_IMAGE_BYTES || bytes.size < 10) return false
            val width = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8)
            val height = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
            return width >= MIN_REAL_GIF_DIMENSION_PX && height >= MIN_REAL_GIF_DIMENSION_PX
        }
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
    private const val MIN_REAL_IMAGE_DIMENSION_PX = 64
    private const val MIN_REAL_GIF_IMAGE_BYTES = 2048
    private const val MIN_REAL_GIF_DIMENSION_PX = 64
    private val HTML_IMAGE_URL_PATTERN = Regex(
        "https?://[^\\\"'<>\\s)]+\\.(?:jpg|jpeg|png|webp|gif)(?:[?#][^\\\"'<>\\s)]*)?",
        RegexOption.IGNORE_CASE
    )
}
