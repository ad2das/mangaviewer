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
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private const val NTK_GENERATED_ANCHOR_RACE_FAST_FAIL_MS = 900L
    private const val NTK_VERIFIED_ANCHOR_HANDOFF_DELAY_MS = 0L
    private const val FOREGROUND_STREAM_RACE_ATTEMPTS = 1
    private const val FOREGROUND_STREAM_JOIN_TIMEOUT_MS = 900L
    private const val FOREGROUND_INITIAL_STREAM_JOIN_TIMEOUT_MS = 180L
    private const val FOREGROUND_INITIAL_ANCHOR_STREAM_JOIN_TIMEOUT_MS = 180L
    private const val NTK_GENERATED_INITIAL_ANCHOR_RECOVERY_HEDGE_MS = 120L
    private const val NTK_GENERATED_INITIAL_RECOVERY_HEDGE_MS = 300L
    private const val NTK_GENERATED_INITIAL_RECOVERY_SAME_URL_HEDGE_MS = 350L
    private const val NTK_GENERATED_INITIAL_RECOVERY_PAGES = 18
    private const val NTK_GENERATED_PREVIEW_RANGE_CANDIDATES = 3
    private const val NTK_GENERATED_INITIAL_COMPLETE_RACE_ANCHOR_CANDIDATES = 1
    private const val NTK_GENERATED_INITIAL_COMPLETE_RACE_NEAR_CANDIDATES = 3
    private const val NTK_GENERATED_VISIBLE_RANGE_PREVIEW_DEADLINE_MS = 1800L
    private const val NTK_GENERATED_INITIAL_FOREGROUND_RACE_CANDIDATES = 4
    private const val FOREGROUND_STREAM_HANDOFF_TTL_MS = 2500L
    private const val FOREGROUND_STREAM_STALE_MS = 9000L
    private const val NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES = 18
    private const val NTK_GENERATED_INITIAL_TRANSIENT_RETRY_DELAY_MS = 180L
    private const val NTK_GENERATED_INITIAL_DIRECT_HEDGE_WEBTOON_DELAY_MS = 140L
    private const val NTK_GENERATED_INITIAL_DIRECT_HEDGE_MANHWA_DELAY_MS = 120L
    private const val EARLY_NTK_IMAGE_URL_TTL_MS = 120000L
    private const val EARLY_NTK_APPEND_IMAGE_URL_TTL_MS = 30000L
    private const val EARLY_NTK_IMAGE_URL_STARTED_SKEW_MS = 1200L
    private const val EARLY_NTK_IMAGE_TRANSPORT_SYNC_LIMIT = 12
    private const val NTK_ACK_RECOVERY_LAUNCH_HOLD_MS = 0L
    private const val NTK_ACK_RECOVERY_AFTER_FIRST_DRAWABLE_QUIET_MS = 0L
    private const val NTK_GENERATED_ACK_GATE_VISIBLE_WAIT_MS = 900L
    private const val NTK_GENERATED_ACK_GATE_FOREGROUND_WAIT_MS = 700L
    private const val NTK_GENERATED_ACK_GATE_BACKGROUND_WAIT_MS = 3600L
    private const val MAX_DIRECT_STREAM_DECODE_BYTES = 16L * 1024L * 1024L
    private const val MAX_DIRECT_STREAM_BITMAP_BYTES = 2L * 1024L * 1024L
    private const val DIRECT_STREAM_TILE_ASPECT_RATIO = 3.0f
    private const val DIRECT_STREAM_TILE_MIN_ESTIMATED_BYTES = 12L * 1024L * 1024L
    private const val FULL_DECODE_VALIDATION_MAX_BYTES = 256L * 1024L
    private const val NTK_GENERATED_RANGE_CHUNK_BYTES = 64 * 1024
    private const val NTK_GENERATED_RANGE_INITIAL_FIRST_CHUNK_BYTES = 512 * 1024
    private const val NTK_GENERATED_RANGE_ADJACENT_FIRST_CHUNK_BYTES = 512 * 1024
    private const val NTK_GENERATED_RANGE_INITIAL_CHUNK_BYTES = 256 * 1024
    private const val NTK_GENERATED_RANGE_ADJACENT_CHUNK_BYTES = 256 * 1024
    private const val NTK_GENERATED_RANGE_MAX_BYTES = 24L * 1024L * 1024L
    private const val NTK_GENERATED_BACKGROUND_FETCH_PARALLELISM = 2
    private const val NTK_GENERATED_FOREGROUND_FETCH_PARALLELISM = 8
    private val flights = ConcurrentHashMap<String, FutureTask<File>>()
    private val foregroundStreams = ConcurrentHashMap<String, FutureTask<ByteArray?>>()
    private val initialVisibleGeneratedPreviewFlights = ConcurrentHashMap<String, FutureTask<ByteArray?>>()
    private val initialGeneratedCompleteBytesFlights = ConcurrentHashMap<String, FutureTask<ByteArray?>>()
    private val initialGeneratedRangeFlights = ConcurrentHashMap<String, FutureTask<GeneratedRangeSnapshot?>>()
    private val initialGeneratedPrefixFlights = ConcurrentHashMap<String, FutureTask<GeneratedRangeSnapshot?>>()
    private val ntkAnchorAssetListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>>()
    private val ntkInitialGeneratedAssetListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>>()
    private val foregroundStreamStartedAt = ConcurrentHashMap<String, Long>()
    private val activeNtkEpisodeCalls = ConcurrentHashMap<String, Call>()
    private val activeNtkEpisodeCallIds = AtomicLong(0L)
    private val earlyNtkImageUrls = ConcurrentHashMap<String, EarlyNtkImageUrls>()
    private val speculativeNtkGeneratedUrls = ConcurrentHashMap<String, EarlyNtkImageUrls>()
    private val earlyNtkImageUrlListeners = CopyOnWriteArrayList<(String, List<String>) -> Unit>()
    private val trustedNtkImageApiCounts = ConcurrentHashMap<String, TrustedNtkImageApiCount>()
    private val earlyNtkGeneratedSuccessUrls = ConcurrentHashMap<String, EarlyNtkImageUrls>()
    private val ntkApiFallbackFlights = ConcurrentHashMap<String, FutureTask<List<String>?>>()
    private val ntkApiFallbackImages = ConcurrentHashMap<String, List<String>>()
    private val ntkGeneratedAckRecoveryFlights = ConcurrentHashMap<String, FutureTask<Boolean>>()
    private val ntkAckRecoveryLaunchHolds = ConcurrentHashMap<String, Long>()
    private val ntkAckRecoveryPriorityPath = AtomicReference("")
    private val ntkGeneratedEpisodeExtensions = ConcurrentHashMap<String, String>()
    private val ntkGeneratedPageExtensions = ConcurrentHashMap<String, String>()
    private val ntkGeneratedNotFoundPages = ConcurrentHashMap.newKeySet<String>()
    private val ntkGeneratedResolvedPages = ConcurrentHashMap.newKeySet<String>()
    private val ntkGeneratedHardBlockedPages = ConcurrentHashMap.newKeySet<String>()
    private val ntkGeneratedReplacementClaims = ConcurrentHashMap.newKeySet<String>()
    private val ntkAnchorAssetFiles = ConcurrentHashMap<String, File>()
    private val ntkInitialGeneratedAssetFiles = ConcurrentHashMap<String, File>()
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
    private val initialVisibleForegroundExecutor = Executors.newFixedThreadPool(4, ThreadFactory { runnable ->
        Thread(runnable, "ReaderInitialVisibleForeground").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    })
    private val foregroundCachePublishExecutor = Executors.newFixedThreadPool(4, ThreadFactory { runnable ->
        Thread(runnable, "ReaderForegroundCachePublish").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
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

    private data class TrustedNtkImageApiCount(
        val count: Int,
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
        initialVisibleGeneratedPreviewFlights.values.forEach { it.cancel(true) }
        initialVisibleGeneratedPreviewFlights.clear()
        initialGeneratedCompleteBytesFlights.values.forEach { it.cancel(true) }
        initialGeneratedCompleteBytesFlights.clear()
        activeNtkEpisodeCalls.values.forEach { it.cancel() }
        activeNtkEpisodeCalls.clear()
        initialGeneratedRangeFlights.values.forEach { it.cancel(true) }
        initialGeneratedRangeFlights.clear()
        initialGeneratedPrefixFlights.values.forEach { it.cancel(true) }
        initialGeneratedPrefixFlights.clear()
        ntkAnchorAssetListeners.clear()
        ntkInitialGeneratedAssetListeners.clear()
        foregroundStreamStartedAt.clear()
        earlyNtkImageUrls.clear()
        speculativeNtkGeneratedUrls.clear()
        trustedNtkImageApiCounts.clear()
        earlyNtkGeneratedSuccessUrls.clear()
        ntkApiFallbackFlights.values.forEach { it.cancel(true) }
        ntkApiFallbackFlights.clear()
        ntkApiFallbackImages.clear()
        ntkGeneratedAckRecoveryFlights.values.forEach { it.cancel(true) }
        ntkGeneratedAckRecoveryFlights.clear()
        ntkAckRecoveryLaunchHolds.clear()
        ntkAckRecoveryPriorityPath.set("")
        ntkGeneratedEpisodeExtensions.clear()
        ntkGeneratedPageExtensions.clear()
        ntkGeneratedNotFoundPages.clear()
        ntkGeneratedResolvedPages.clear()
        ntkGeneratedHardBlockedPages.clear()
        ntkGeneratedReplacementClaims.clear()
        ntkAnchorAssetFiles.clear()
        ntkInitialGeneratedAssetFiles.clear()
        activeReads.clear()
        Log.d(TAG, "reader_image_cache_volatile_clear_for_test generation=$generation")
    }

    fun cancelNtkEpisodeVolatile(manga: Manga?) {
        val path = earlyNtkPathKey(manga?.ntkEpisodePath)
        if (manga == null || path.isEmpty()) return
        val streamPrefix = "ntk-generated-stream|${manga.baseMode}|$path|"
        val previewPrefix = "ntk-visible-preview|$streamPrefix"
        val rangePrefix = "ntk-generated-range|${manga.baseMode}|$path|"
        val fallbackSuffix = path
        val episodeKeys = ntkGeneratedEpisodeHintKeys(path)
        val cancelledStreams = cancelFutureTasks(foregroundStreams) { it.startsWith(streamPrefix) }
        val cancelledPreviews = cancelFutureTasks(initialVisibleGeneratedPreviewFlights) { it.startsWith(previewPrefix) }
        val completePrefix = "ntk-initial-complete|${manga.baseMode}|$path|"
        val cancelledComplete = cancelFutureTasks(initialGeneratedCompleteBytesFlights) { it.startsWith(completePrefix) }
        val cancelledCalls = cancelActiveNtkEpisodeCalls { it == path }
        foregroundStreamStartedAt.keys.removeAll { it.startsWith(streamPrefix) }
        val cancelledRanges = cancelFutureTasks(initialGeneratedRangeFlights) { it.startsWith(rangePrefix) }
        val cancelledPrefixes = cancelFutureTasks(initialGeneratedPrefixFlights) { it.startsWith(rangePrefix) }
        ntkAnchorAssetListeners.keys.removeAll { it.startsWith("${manga.baseMode}|$path|") }
        ntkInitialGeneratedAssetListeners.keys.removeAll { it.startsWith("${manga.baseMode}|$path|") }
        val cancelledApi = cancelFutureTasks(ntkApiFallbackFlights) { it.endsWith(fallbackSuffix) }
        val cancelledAck = cancelFutureTasks(ntkGeneratedAckRecoveryFlights) { it.endsWith(fallbackSuffix) }
        earlyNtkImageUrls.remove(path)
        speculativeNtkGeneratedUrls.remove(path)
        trustedNtkImageApiCounts.remove(path)
        earlyNtkGeneratedSuccessUrls.remove(path)
        ntkApiFallbackImages.keys.removeAll { it.endsWith(fallbackSuffix) }
        ntkAckRecoveryLaunchHolds.remove(path)
        ntkAckRecoveryPriorityPath.compareAndSet(path, "")
        episodeKeys.forEach { ntkGeneratedEpisodeExtensions.remove(it) }
        episodeKeys.forEach { key -> ntkGeneratedPageExtensions.keys.removeAll { it.startsWith("$key|") } }
        ntkGeneratedNotFoundPages.removeAll { it.startsWith("$path|") }
        ntkGeneratedResolvedPages.removeAll { it.startsWith("$path|") }
        ntkGeneratedHardBlockedPages.removeAll { it.startsWith("$path|") }
        ntkGeneratedReplacementClaims.removeAll { it.contains("|$path|") }
        ntkAnchorAssetFiles.keys.removeAll { it.contains("|$path|") }
        ntkInitialGeneratedAssetFiles.keys.removeAll { it.contains("|$path|") }
        if (cancelledStreams > 0 ||
            cancelledPreviews > 0 ||
            cancelledComplete > 0 ||
            cancelledCalls > 0 ||
            cancelledRanges > 0 ||
            cancelledPrefixes > 0 ||
            cancelledApi > 0 ||
            cancelledAck > 0
        ) {
            Log.d(
                TAG,
                "reader_image_cache_ntk_episode_volatile_cancel path=$path," +
                    "streams=$cancelledStreams,previews=$cancelledPreviews,complete=$cancelledComplete,calls=$cancelledCalls," +
                    "ranges=$cancelledRanges,prefixes=$cancelledPrefixes,api=$cancelledApi,ack=$cancelledAck"
            )
        }
    }

    @JvmStatic
    fun cancelOtherNtkEpisodeVolatile(currentPath: String?, reason: String) {
        val keepPath = earlyNtkPathKey(currentPath)
        if (keepPath.isEmpty()) return
        val cancelledStreams = cancelFutureTasks(foregroundStreams) { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val cancelledPreviews = cancelFutureTasks(initialVisibleGeneratedPreviewFlights) { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val cancelledComplete = cancelFutureTasks(initialGeneratedCompleteBytesFlights) { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val cancelledCalls = cancelActiveNtkEpisodeCalls { it != keepPath }
        foregroundStreamStartedAt.keys.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val cancelledRanges = cancelFutureTasks(initialGeneratedRangeFlights) { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val cancelledPrefixes = cancelFutureTasks(initialGeneratedPrefixFlights) { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkAnchorAssetListeners.keys.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkInitialGeneratedAssetListeners.keys.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val cancelledApi = cancelFutureTasks(ntkApiFallbackFlights) { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val cancelledAck = cancelFutureTasks(ntkGeneratedAckRecoveryFlights) { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        val removedEarly = earlyNtkImageUrls.keys.removeAll { it != keepPath && isNtkEpisodePathKey(it) }
        trustedNtkImageApiCounts.keys.removeAll { it != keepPath && isNtkEpisodePathKey(it) }
        earlyNtkGeneratedSuccessUrls.keys.removeAll { it != keepPath && isNtkEpisodePathKey(it) }
        ntkApiFallbackImages.keys.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkAckRecoveryLaunchHolds.keys.removeAll { it != keepPath && isNtkEpisodePathKey(it) }
        val priority = ntkAckRecoveryPriorityPath.get()
        if (priority.isNotEmpty() && priority != keepPath) ntkAckRecoveryPriorityPath.compareAndSet(priority, "")
        ntkGeneratedNotFoundPages.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkGeneratedResolvedPages.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkGeneratedHardBlockedPages.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkGeneratedReplacementClaims.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkAnchorAssetFiles.keys.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        ntkInitialGeneratedAssetFiles.keys.removeAll { key ->
            val path = ntkEpisodePathFromVolatileKey(key)
            path != null && path != keepPath
        }
        if (cancelledStreams > 0 ||
            cancelledPreviews > 0 ||
            cancelledComplete > 0 ||
            cancelledCalls > 0 ||
            cancelledRanges > 0 ||
            cancelledPrefixes > 0 ||
            cancelledApi > 0 ||
            cancelledAck > 0 ||
            removedEarly
        ) {
            Log.d(
                TAG,
                    "reader_image_cache_other_ntk_episode_volatile_cancel keep=$keepPath," +
                    "reason=$reason,streams=$cancelledStreams,previews=$cancelledPreviews,complete=$cancelledComplete,calls=$cancelledCalls,ranges=$cancelledRanges," +
                    "prefixes=$cancelledPrefixes," +
                    "api=$cancelledApi,ack=$cancelledAck,early=$removedEarly"
            )
        }
    }

    private fun cancelActiveNtkEpisodeCalls(shouldCancelPath: (String) -> Boolean): Int {
        return cancelActiveNtkEpisodeCallsByKey { key ->
            val path = ntkEpisodePathFromVolatileKey(key) ?: return@cancelActiveNtkEpisodeCallsByKey false
            shouldCancelPath(path)
        }
    }

    private fun cancelActiveNtkEpisodeCallsByKey(shouldCancelKey: (String) -> Boolean): Int {
        var count = 0
        for ((key, call) in activeNtkEpisodeCalls.entries) {
            if (shouldCancelKey(key) && activeNtkEpisodeCalls.remove(key, call)) {
                call.cancel()
                count++
            }
        }
        return count
    }

    private fun trackActiveNtkEpisodeCall(manga: Manga, image: String, call: Call): String? {
        val path = manga.ntkEpisodePath?.trim()?.takeIf { isNtkEpisodePathKey(it) }
            ?: ntkGeneratedTarget(image)?.path?.takeIf { isNtkEpisodePathKey(it) }
            ?: return null
        val callKey = "$path|img-${key(manga.baseMode, image)}|call-${activeNtkEpisodeCallIds.incrementAndGet()}"
        activeNtkEpisodeCalls[callKey] = call
        return callKey
    }

    private fun untrackActiveNtkEpisodeCall(key: String?) {
        if (key != null) activeNtkEpisodeCalls.remove(key)
    }

    fun cancelNtkGeneratedForegroundWorkForImages(
        manga: Manga,
        images: List<String>,
        reason: String
    ): Int {
        if (images.isEmpty()) return 0
        val streamKeys = HashSet<String>()
        val imageKeys = HashSet<String>()
        val completeKeys = HashSet<String>()
        for (image in images) {
            val target = ntkGeneratedTarget(image) ?: continue
            streamKeys.add(foregroundStreamKey(manga, image))
            imageKeys.add(key(manga.baseMode, image))
            if (target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
                completeKeys.add(initialGeneratedCompleteBytesFlightKey(manga, target, cacheGeneration.get()))
            }
        }
        if (streamKeys.isEmpty() && imageKeys.isEmpty()) return 0
        val cancelledStreams = cancelFutureTasks(foregroundStreams) { streamKeys.contains(it) }
        val cancelledPreviews = cancelFutureTasks(initialVisibleGeneratedPreviewFlights) { key ->
            streamKeys.any { streamKey -> key == "ntk-visible-preview|$streamKey" }
        }
        val cancelledComplete = cancelFutureTasks(initialGeneratedCompleteBytesFlights) { completeKeys.contains(it) }
        foregroundStreamStartedAt.keys.removeAll { streamKeys.contains(it) }
        val cancelledCalls = cancelActiveNtkEpisodeCallsByKey { activeKey ->
            val activeImageKey = ntkImageKeyFromVolatileCallKey(activeKey)
                ?: return@cancelActiveNtkEpisodeCallsByKey false
            imageKeys.contains(activeImageKey)
        }
        val total = cancelledStreams + cancelledPreviews + cancelledComplete + cancelledCalls
        if (total > 0) {
            Log.d(
                TAG,
                "reader_image_cache_cancel_generated_work reason=$reason,path=${manga.ntkEpisodePath}," +
                    "streams=$cancelledStreams,previews=$cancelledPreviews,complete=$cancelledComplete,calls=$cancelledCalls,images=${images.size}"
            )
        }
        return total
    }

    private fun ntkImageKeyFromVolatileCallKey(key: String): String? {
        val marker = "|img-"
        val start = key.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = key.indexOf('|', valueStart)
        if (end <= valueStart) return null
        return key.substring(valueStart, end)
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

    private fun isNtkEpisodePathKey(path: String): Boolean {
        return path.startsWith("/webtoon/") || path.startsWith("/manhwa/")
    }

    private fun ntkEpisodePathFromVolatileKey(key: String): String? {
        return Regex("(/(?:webtoon|manhwa)/[^|/?#]+/[^|/?#]+)", RegexOption.IGNORE_CASE)
            .find(key)
            ?.groupValues
            ?.getOrNull(1)
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
        cachedImageFile(appContext, manga, image)?.let { return it }
        return cachedInitialGeneratedAssetFile(appContext, manga, image, foreground = true)
    }

    fun cachedExactFile(context: Context, manga: Manga, image: String): File? {
        if (!manga.isOnline) return File(image)
        val finalFile = File(cacheDir(context.applicationContext), "${key(manga.baseMode, image)}.img")
        return finalFile.takeIf { isUsableImage(it) }
    }

    fun hasActiveFetch(manga: Manga, image: String): Boolean {
        if (!manga.isOnline) return false
        val logicalStreamKey = foregroundStreamKey(manga, image)
        return ntkGeneratedCacheLookupImages(image).any { candidate ->
            val key = key(manga.baseMode, candidate)
            flights.containsKey(key)
        } || foregroundStreams.containsKey(logicalStreamKey)
    }

    @JvmStatic
    fun clearNtkGeneratedEpisodeExtensionHintsForTest() {
        ntkGeneratedEpisodeExtensions.clear()
        ntkGeneratedPageExtensions.clear()
        ntkGeneratedNotFoundPages.clear()
        ntkGeneratedResolvedPages.clear()
        ntkGeneratedHardBlockedPages.clear()
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
    fun prioritizeNtkAckRecovery(path: String?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return
        val previous = ntkAckRecoveryPriorityPath.getAndSet(key)
        if (previous != key) {
            Log.d(TAG, "reader_ntk_ack_recovery_priority path=$key,previous=$previous")
        }
    }

    @JvmStatic
    fun clearNtkAckRecoveryPriority(path: String?, reason: String) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return
        if (ntkAckRecoveryPriorityPath.compareAndSet(key, "")) {
            Log.d(TAG, "reader_ntk_ack_recovery_priority_clear path=$key,reason=$reason")
        }
    }

    @JvmStatic
    fun isNtkAckRecoveryPriorityForPath(path: String?): Boolean {
        val key = earlyNtkPathKey(path)
        return key.isNotEmpty() && ntkAckRecoveryPriorityPath.get() == key
    }

    private fun ntkAckRecoveryPriorityBlocker(path: String): String? {
        val priority = ntkAckRecoveryPriorityPath.get()
        if (priority.isEmpty() || priority == path) return null
        val hasPriorityProof = try {
            getHttpClient().hasRecentStrictNtkAdAckProof(priority)
        } catch (_: Exception) {
            false
        }
        if (hasPriorityProof) {
            ntkAckRecoveryPriorityPath.compareAndSet(priority, "")
            Log.d(TAG, "reader_ntk_ack_recovery_priority_clear path=$priority,reason=strict_proof_seen")
            return null
        }
        return priority
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

    @JvmStatic
    fun isInitialNtkGeneratedStreamActiveForPath(path: String?): Boolean {
        return earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
            .any { image -> ntkGeneratedTarget(image) != null }
    }

    fun addEarlyNtkImageUrlsListener(listener: (String, List<String>) -> Unit): () -> Unit {
        earlyNtkImageUrlListeners.add(listener)
        return { earlyNtkImageUrlListeners.remove(listener) }
    }

    @JvmStatic
    fun rememberEarlyNtkImageUrls(path: String?, urls: List<String>?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || urls.isNullOrEmpty()) return
        val trustedRaw = urls.mapNotNull {
            it?.trim()?.takeIf { value -> isTrustedNtkImageUrl(value) || isNaverWebtoonPageImage(value) }
        }
        val trusted = compactEarlyNtkGeneratedPageUrls(compactEarlyNtkEquivalentUrls(trustedRaw))
        if (trusted.isEmpty()) return
        val existing = earlyNtkImageUrls[key]
        val incomingAuthoritative = isAuthoritativeEarlyNtkGeneratedUrlList(key, trusted)
        val existingAuthoritative = existing?.urls?.let { isAuthoritativeEarlyNtkGeneratedUrlList(key, it) } ?: false
        val incomingGenerated = trusted.any { ntkGeneratedTarget(it) != null }
        val incomingGeneratedManhwa = trusted.any {
            ntkGeneratedTarget(it)?.path?.trimStart('/')?.startsWith("manhwa/", ignoreCase = true) == true
        }
        if (
            existing != null &&
            existingAuthoritative &&
            incomingGenerated &&
            trusted.size <= existing.urls.size &&
            generatedUrlPagePrefixMatches(existing.urls, trusted)
        ) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_speculative_generated_skip_authoritative path=$key," +
                    "existing=${existing.urls.size},incoming=${trusted.size}," +
                    "first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (incomingGeneratedManhwa || (incomingGenerated && !incomingAuthoritative)) {
            speculativeNtkGeneratedUrls[key] = EarlyNtkImageUrls(
                Collections.unmodifiableList(ArrayList(trusted)),
                SystemClock.elapsedRealtime()
            )
            Log.d(
                TAG,
                "reader_early_ntk_urls_speculative_generated_hold path=$key," +
                    "count=${trusted.size},manhwa=$incomingGeneratedManhwa," +
                    "first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (existing != null && existing.urls == trusted) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_remember_skip_identical path=$key," +
                    "count=${trusted.size},first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (
            existing != null &&
            existingAuthoritative &&
            incomingAuthoritative &&
            trusted.size <= existing.urls.size &&
            generatedUrlPagePrefixMatches(existing.urls, trusted)
        ) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_remember_skip_authoritative_subset path=$key," +
                    "existing=${existing.urls.size},incoming=${trusted.size}," +
                    "first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (
            existing != null &&
            existing.urls.size > trusted.size &&
            incomingAuthoritative &&
            generatedUrlPagePrefixMatches(existing.urls, trusted)
        ) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_remember_skip_authoritative_shrink path=$key," +
                    "existing=${existing.urls.size},incoming=${trusted.size}," +
                    "first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (existing != null && existingAuthoritative && !incomingAuthoritative) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_remember_skip_unverified_over_authoritative path=$key," +
                    "existing=${existing.urls.size},incoming=${trusted.size},first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (existing != null && !existingAuthoritative && incomingAuthoritative) {
            publishEarlyNtkImageUrls(key, trusted)
            Log.d(
                TAG,
                "reader_early_ntk_urls_remember_replace_authoritative path=$key," +
                    "existing=${existing.urls.size},incoming=${trusted.size},first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (existing != null && existing.urls.size > trusted.size) {
            if (hasKnownMissingGeneratedTail(existing.urls, trusted.size)) {
                val trustedComplete = hasTrustedCompleteNtkImageApiCount(key, trusted.size)
                val replacement = if (trustedComplete) {
                    trusted
                } else {
                    mergeInitialGeneratedPageUrls(existing.urls, trusted) ?: existing.urls
                }
                if (replacement == existing.urls) {
                    Log.d(
                        TAG,
                        "reader_early_ntk_urls_remember_preserve_larger_partial path=$key," +
                            "existing=${existing.urls.size},incoming=${trusted.size}," +
                            "first=${safeImageName(trusted.firstOrNull())}"
                    )
                    return
                }
                publishEarlyNtkImageUrls(key, replacement)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_replace_missing_tail path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size}," +
                        "stored=${replacement.size},complete=$trustedComplete," +
                        "first=${safeImageName(replacement.firstOrNull())}"
                )
                return
            }
            val verifiedSubset = verifiedGeneratedSubsetReplacement(existing.urls, trusted)
            if (verifiedSubset != null) {
                publishEarlyNtkImageUrls(key, verifiedSubset)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_replace_verified_subset path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size}," +
                        "replacement=${verifiedSubset.size},first=${safeImageName(verifiedSubset.firstOrNull())}"
                )
                return
            }
            val merged = mergeInitialGeneratedPageUrls(existing.urls, trusted)
            if (merged != null && merged != existing.urls) {
                publishEarlyNtkImageUrls(key, merged)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_merge_smaller path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size},merged=${merged.size}," +
                        "first=${safeImageName(merged.firstOrNull())}"
                )
                return
            }
            val incomingPreferred = preferIncomingInitialGeneratedPages(existing.urls, trusted)
            if (incomingPreferred != null && incomingPreferred != existing.urls) {
                publishEarlyNtkImageUrls(key, incomingPreferred)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_prefer_incoming_initial path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size},merged=${incomingPreferred.size}," +
                        "first=${safeImageName(incomingPreferred.firstOrNull())}"
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
            val preservedVerified = preserveExistingVerifiedPageImages(trusted, existing.urls)
            if (preservedVerified != null && preservedVerified != trusted) {
                publishEarlyNtkImageUrls(key, preservedVerified)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_preserve_verified_images path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size},first=${safeImageName(preservedVerified.firstOrNull())}"
                )
                return
            }
            val preserved = preserveExistingInitialGeneratedVariants(trusted, existing.urls)
            if (preserved != null && preserved != trusted) {
                publishEarlyNtkImageUrls(key, preserved)
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_remember_preserve_verified path=$key," +
                        "existing=${existing.urls.size},incoming=${trusted.size},first=${safeImageName(preserved.firstOrNull())}"
                )
                return
            }
        }
        publishEarlyNtkImageUrls(key, trusted)
        Log.d(TAG, "reader_early_ntk_urls_remember path=$key,count=${trusted.size},first=${safeImageName(trusted.firstOrNull())}")
    }

    @JvmStatic
    fun rememberAuthoritativeNtkImageUrlsFromBrowser(path: String?, urls: List<String>?, source: String?) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || urls.isNullOrEmpty()) return
        val trustedRaw = urls.mapNotNull {
            val value = it?.trim()?.takeIf { candidate ->
                isTrustedNtkImageUrl(candidate) || isNaverWebtoonPageImage(candidate)
            } ?: return@mapNotNull null
            if (!authoritativeBrowserImageMatchesPath(key, value)) {
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_authoritative_browser_skip_mismatch path=$key," +
                        "source=${source.orEmpty()},image=${safeImageName(value)}"
                )
                return@mapNotNull null
            }
            value
        }
        val trusted = compactEarlyNtkGeneratedPageUrls(compactEarlyNtkEquivalentUrls(trustedRaw))
        if (trusted.isEmpty()) return
        val existing = earlyNtkImageUrls[key]
        val incomingAuthoritative = isAuthoritativeEarlyNtkGeneratedUrlList(key, trusted)
        val existingAuthoritative = existing?.urls?.let { isAuthoritativeEarlyNtkGeneratedUrlList(key, it) } ?: false
        if (
            existing != null &&
            existingAuthoritative &&
            incomingAuthoritative &&
            trusted.size < existing.urls.size &&
            generatedUrlPagePrefixMatches(existing.urls, trusted)
        ) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_authoritative_browser_skip_shrink path=$key," +
                    "existing=${existing.urls.size},incoming=${trusted.size}," +
                    "source=${source.orEmpty()},first=${safeImageName(trusted.firstOrNull())}"
            )
            return
        }
        if (existing != null && existing.urls == trusted) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_authoritative_browser_skip_identical path=$key," +
                    "count=${trusted.size},source=${source.orEmpty()}"
            )
            return
        }
        speculativeNtkGeneratedUrls.remove(key)
        trustedNtkImageApiCounts[key] = TrustedNtkImageApiCount(trusted.size, SystemClock.elapsedRealtime())
        trusted.forEach { rememberNtkGeneratedResolvedPage(it, "browser_${source.orEmpty()}") }
        publishEarlyNtkImageUrls(key, trusted)
        Log.d(
            TAG,
            "reader_early_ntk_urls_authoritative_browser path=$key,count=${trusted.size}," +
                "source=${source.orEmpty()},first=${safeImageName(trusted.firstOrNull())}"
        )
    }

    private fun authoritativeBrowserImageMatchesPath(key: String, image: String): Boolean {
        val normalizedKey = key.trim('/').lowercase()
        if (normalizedKey.isBlank()) return false
        val generatedTarget = ntkGeneratedTarget(image)
        if (generatedTarget != null) {
            val targetKey = generatedTarget.path.trim('/').lowercase()
            return targetKey == normalizedKey
        }
        if (isNaverWebtoonPageImage(image)) {
            return normalizedKey.startsWith("webtoon/")
        }
        if (!isTrustedNtkImageUrl(image)) return false
        val identity = normalizedNtkImageIdentity(image)
        val directPage = Regex(
            "(^|/)(manhwa|webtoon)/[^/]+/[^/]+/p\\d{3}\\.(jpg|jpeg|png|webp)$",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(identity)
        return !directPage || identity.contains("/$normalizedKey/")
    }

    private fun generatedUrlPagePrefixMatches(existing: List<String>, incoming: List<String>): Boolean {
        if (incoming.isEmpty() || incoming.size > existing.size) return false
        for (index in incoming.indices) {
            val existingRef = ntkGeneratedImageRef(existing[index])
            val incomingRef = ntkGeneratedImageRef(incoming[index])
            if (existingRef != null || incomingRef != null) {
                if (existingRef?.pageKey != incomingRef?.pageKey) return false
                continue
            }
            if (!existing[index].equals(incoming[index], ignoreCase = true)) return false
        }
        return true
    }

    @JvmStatic
    fun hasAuthoritativeCompleteEarlyNtkImageUrls(path: String?, expected: Int, minCreatedAtMs: Long): Boolean {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || expected <= 0) return false
        val entry = earlyNtkImageUrls[key] ?: return false
        if (entry.createdAtMs < minCreatedAtMs || entry.urls.size < expected) return false
        return isAuthoritativeEarlyNtkGeneratedUrlList(key, entry.urls.take(expected))
    }

    private fun isAuthoritativeEarlyNtkGeneratedUrlList(key: String, urls: List<String>): Boolean {
        if (urls.isEmpty()) return false
        var sawGenerated = false
        var sawTrustedDirect = false
        for (url in urls) {
            val target = ntkGeneratedTarget(url)
            if (target == null) {
                if (isTrustedNtkImageUrl(url)) {
                    sawTrustedDirect = true
                    continue
                }
                if (!isNaverWebtoonPageImage(url)) return false
                continue
            }
            val targetKey = earlyNtkPathKey(target.path)
            if (targetKey.isNotEmpty() && !targetKey.equals(key, ignoreCase = true)) {
                val keyParts = key.trim('/').split('/')
                val targetParts = targetKey.trim('/').split('/')
                if (keyParts.size < 3 ||
                    targetParts.size < 3 ||
                    !keyParts[0].equals(targetParts[0], ignoreCase = true) ||
                    keyParts[2] != targetParts[2]
                ) {
                    return false
                }
            }
            sawGenerated = true
            if (!isAuthoritativeEarlyNtkGeneratedUrl(key, url, target)) return false
        }
        return sawGenerated || sawTrustedDirect || urls.all { isNaverWebtoonPageImage(it) }
    }

    private fun isAuthoritativeEarlyNtkGeneratedUrl(
        key: String,
        url: String,
        target: NtkGeneratedTarget
    ): Boolean {
        val identity = try {
            CustomHttpClient.cachedNtkImageIdentity(key)
        } catch (_: Throwable) {
            null
        }
        val parts = key.trim('/').split('/')
        val targetParts = target.path.trim('/').split('/')
        if (parts.size >= 3 && targetParts.size >= 3) {
            val segment = parts[0]
            val pathWorkId = parts[1]
            val pathEpisodeId = parts[2]
            if (!segment.equals(targetParts[0], ignoreCase = true)) return false
            if (identity != null) {
                return targetParts[1] == identity.workId && targetParts[2] == identity.episodeId
            }
            if (segment.equals("webtoon", ignoreCase = true) &&
                pathWorkId.matches(Regex("\\d{1,12}")) &&
                pathEpisodeId.matches(Regex("\\d{1,12}")) &&
                targetParts[1] == pathWorkId &&
                targetParts[2] == pathEpisodeId
            ) {
                val ref = ntkGeneratedImageRef(url)
                if (ref?.extension == "jpeg") return false
            }
        }
        return hasNtkGeneratedResolvedPage(target) || ntkGeneratedEpisodeExtensionMatches(url)
    }

    private fun publishEarlyNtkImageUrls(key: String, urls: List<String>) {
        val stored = Collections.unmodifiableList(ArrayList(urls))
        earlyNtkImageUrls[key] = EarlyNtkImageUrls(stored, SystemClock.elapsedRealtime())
        rememberInitialGeneratedExtensions(stored)
        notifyEarlyNtkImageUrlsChanged(key, stored)
        prepareEarlyNtkImageTransportAfterPublish(key, stored)
    }

    private fun notifyEarlyNtkImageUrlsChanged(key: String, urls: List<String>) {
        if (earlyNtkImageUrlListeners.isEmpty()) return
        val snapshot = Collections.unmodifiableList(ArrayList(urls))
        for (listener in earlyNtkImageUrlListeners) {
            try {
                listener(key, snapshot)
            } catch (e: Throwable) {
                Log.w(TAG, "reader_early_ntk_urls_listener_error path=$key", e)
            }
        }
    }

    private fun hasKnownMissingGeneratedTail(existing: List<String>, verifiedCount: Int): Boolean {
        if (verifiedCount <= 0 || verifiedCount >= existing.size) return false
        val last = minOf(existing.lastIndex, verifiedCount + NTK_GENERATED_INITIAL_RECOVERY_PAGES)
        for (index in verifiedCount..last) {
            val image = existing.getOrNull(index) ?: continue
            val target = ntkGeneratedTarget(image) ?: continue
            if (ntkGeneratedNotFoundPages.contains(ntkGeneratedImageStateKey(image, target))) {
                return true
            }
        }
        return false
    }

    private fun hasTrustedCompleteNtkImageApiCount(key: String, count: Int): Boolean {
        val trustedCount = trustedNtkImageApiCounts[key] ?: return false
        if (trustedCount.count != count) return false
        return SystemClock.elapsedRealtime() - trustedCount.createdAtMs <= EARLY_NTK_APPEND_IMAGE_URL_TTL_MS
    }

    @JvmStatic
    fun rememberTrustedNtkImageApiCount(path: String?, count: Int) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || count <= 0) return
        val now = SystemClock.elapsedRealtime()
        val existing = trustedNtkImageApiCounts[key]
        if (existing == null || count >= existing.count || existing.createdAtMs < now - EARLY_NTK_APPEND_IMAGE_URL_TTL_MS) {
            trustedNtkImageApiCounts[key] = TrustedNtkImageApiCount(count, now)
            Log.d(TAG, "reader_ntk_trusted_api_count_remember path=$key,count=$count")
        }
    }

    @JvmStatic
    fun trustedNtkImageApiCount(path: String?, minCreatedAtMs: Long): Int {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return 0
        val entry = trustedNtkImageApiCounts[key] ?: return 0
        val now = SystemClock.elapsedRealtime()
        if (entry.createdAtMs < minCreatedAtMs || now - entry.createdAtMs > EARLY_NTK_APPEND_IMAGE_URL_TTL_MS) {
            trustedNtkImageApiCounts.remove(key, entry)
            return 0
        }
        return entry.count
    }

    private fun compactEarlyNtkGeneratedPageUrls(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        val seenGeneratedPages = LinkedHashSet<String>()
        val compacted = ArrayList<String>(urls.size)
        var changed = false
        urls.forEach { url ->
            val target = ntkGeneratedTarget(url)
            if (target != null) {
                val key = "${target.path}|${target.page}"
                if (!seenGeneratedPages.add(key)) {
                    changed = true
                    return@forEach
                }
            }
            compacted.add(url)
        }
        if (changed) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_compact_generated before=${urls.size},after=${compacted.size}," +
                    "first=${safeImageName(compacted.firstOrNull())}"
            )
        }
        return if (changed) compacted else urls
    }

    private fun compactEarlyNtkEquivalentUrls(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        val seen = LinkedHashSet<String>()
        val compacted = ArrayList<String>(urls.size)
        var changed = false
        urls.forEach { url ->
            val key = url
                .trim()
                .replace(Regex("(?i)^https?://"), "//")
                .substringBefore('#')
            if (!seen.add(key)) {
                changed = true
                return@forEach
            }
            compacted.add(url)
        }
        if (changed) {
            Log.d(
                TAG,
                "reader_early_ntk_urls_compact_equivalent before=${urls.size},after=${compacted.size}," +
                    "first=${safeImageName(compacted.firstOrNull())}"
            )
        }
        return if (changed) compacted else urls
    }

    private fun shouldReplaceWithVerifiedGeneratedSubset(existing: List<String>, incoming: List<String>): Boolean {
        return verifiedGeneratedSubsetReplacement(existing, incoming) != null
    }

    private fun verifiedGeneratedSubsetReplacement(existing: List<String>, incoming: List<String>): List<String>? {
        if (existing.isEmpty() || incoming.isEmpty()) return null
        if (existing.size <= incoming.size) return null
        val incomingTargets = incoming.map { ntkGeneratedTarget(it) ?: return null }
        val existingTargets = existing.map { ntkGeneratedTarget(it) ?: return null }
        val path = incomingTargets.first().path
        if (incomingTargets.any { it.path != path } || existingTargets.any { it.path != path }) return null
        val incomingPages = incomingTargets.map { it.page }.toSet()
        val existingPages = existingTargets.map { it.page }.toSet()
        if (!existingPages.containsAll(incomingPages)) return null
        if (incomingPages.size == existingPages.size) return null
        if (incomingPages.size <= 1) return null
        val sortedIncoming = incomingPages.sorted()
        val firstIncoming = sortedIncoming.first()
        if (firstIncoming > 2) return null
        if (sortedIncoming != (firstIncoming..sortedIncoming.last()).toList()) return null
        val mergedByPage = LinkedHashMap<Int, String>()
        existing.forEachIndexed { position, image ->
            val target = existingTargets.getOrNull(position) ?: return null
            mergedByPage[target.page] = image
        }
        incoming.forEachIndexed { position, image ->
            val target = incomingTargets.getOrNull(position) ?: return null
            val existingImage = mergedByPage[target.page]
            mergedByPage[target.page] = if (existingImage != null &&
                shouldKeepExistingGeneratedVariant(existingImage, image)
            ) {
                existingImage
            } else {
                image
            }
        }
        return compactEarlyNtkGeneratedPageUrls(
            mergedByPage.entries
                .sortedBy { it.key }
                .map { it.value }
        )
    }

    @JvmStatic
    fun shouldReplaceWithVerifiedGeneratedSubsetForTest(existing: List<String>, incoming: List<String>): Boolean {
        return shouldReplaceWithVerifiedGeneratedSubset(existing, incoming)
    }

    private fun prepareEarlyNtkImageTransport(key: String, urls: List<String>) {
        try {
            getHttpClient().prepareNtkImageTransportForViewerUrls(urls, key)
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            Log.d(TAG, "reader_early_ntk_transport_prepare_error path=$key,error=${t.javaClass.simpleName}")
        }
    }

    private fun prepareEarlyNtkImageTransportAfterPublish(key: String, urls: List<String>) {
        if (urls.size <= EARLY_NTK_IMAGE_TRANSPORT_SYNC_LIMIT) {
            prepareEarlyNtkImageTransport(key, urls)
            return
        }
        val snapshot = Collections.unmodifiableList(ArrayList(urls))
        try {
            foregroundCachePublishExecutor.execute {
                prepareEarlyNtkImageTransport(key, snapshot)
            }
        } catch (e: RuntimeException) {
            Log.d(TAG, "reader_early_ntk_transport_prepare_enqueue_error path=$key,error=${e.javaClass.simpleName}")
        }
    }

    private fun rememberInitialGeneratedExtensions(urls: List<String>) {
        if (urls.any { ntkGeneratedTarget(it) != null }) {
            Log.d(
                TAG,
                "ntk_generated_extension_hint_skip_unverified_early_urls count=${urls.size}," +
                    "first=${safeImageName(urls.firstOrNull())}"
            )
        }
    }

    private fun shouldKeepExistingGeneratedVariant(existing: String, incoming: String): Boolean {
        val existingTarget = ntkGeneratedTarget(existing) ?: return false
        val incomingTarget = ntkGeneratedTarget(incoming) ?: return false
        if (existingTarget.path != incomingTarget.path || existingTarget.page != incomingTarget.page) return false
        val existingRef = ntkGeneratedImageRef(existing) ?: return false
        val incomingRef = ntkGeneratedImageRef(incoming) ?: return false
        if (existingRef.extension == incomingRef.extension) return false
        if (existingRef.extension == "jpg" && incomingRef.extension == "jpeg") {
            if (hasNtkGeneratedNotFoundInitialExtension(existingRef, "jpg")) return false
            return ntkGeneratedEpisodeExtensionMatches(existing) ||
                !ntkGeneratedEpisodeExtensionMatches(incoming) ||
                hasNtkGeneratedNotFoundInitialExtension(incomingRef, "jpeg")
        }
        return false
    }

    private fun ntkGeneratedInitialRecoveryPages(manga: Manga): Int {
        return if (manga.ntkEpisodePath.orEmpty().startsWith("/manhwa/", ignoreCase = true)) {
            3
        } else {
            NTK_GENERATED_INITIAL_RECOVERY_PAGES
        }
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
            if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) continue
            val hinted = ntkGeneratedImageWithHintedExtension(merged[index])
            val replacement = replacements[target.page]
                ?: hinted.takeIf { it != merged[index] }
                ?: continue
            seenPages.add(target.page)
            if (shouldKeepExistingGeneratedVariant(merged[index], replacement)) continue
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

    private fun preferIncomingInitialGeneratedPages(
        existing: List<String>,
        incoming: List<String>
    ): List<String>? {
        if (existing.isEmpty() || incoming.isEmpty()) return null
        val incomingByPage = LinkedHashMap<Int, String>()
        incoming.forEach { image ->
            val target = ntkGeneratedTarget(image) ?: return@forEach
            if (target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
                incomingByPage[target.page] = image
            }
        }
        if (incomingByPage.isEmpty()) return null
        val merged = ArrayList(existing)
        val seenPages = HashSet<Int>()
        var changed = false
        for (index in merged.indices) {
            val target = ntkGeneratedTarget(merged[index]) ?: continue
            if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) continue
            val replacement = incomingByPage[target.page] ?: continue
            seenPages.add(target.page)
            val replacementTarget = ntkGeneratedTarget(replacement) ?: continue
            if (replacementTarget.page != target.page) continue
            if (merged[index] != replacement) {
                merged[index] = replacement
                changed = true
            }
        }
        incomingByPage.forEach { (page, image) ->
            if (!seenPages.contains(page)) {
                val insertIndex = (page - 1).coerceIn(0, merged.size)
                merged.add(insertIndex, image)
                changed = true
            }
        }
        return if (changed) compactEarlyNtkGeneratedPageUrls(merged) else null
    }

    private fun preserveExistingVerifiedPageImages(
        incoming: List<String>,
        existing: List<String>
    ): List<String>? {
        if (incoming.isEmpty() || existing.isEmpty()) return null
        if (incoming.none { ntkGeneratedTarget(it) != null }) return null
        val merged = ArrayList(incoming)
        var changed = false
        val limit = minOf(existing.size, merged.size)
        for (index in 0 until limit) {
            val verified = existing[index]
            if (ntkGeneratedTarget(verified) != null) continue
            if (!isTrustedNtkImageUrl(verified) && !isNaverWebtoonPageImage(verified)) continue
            val incomingTarget = ntkGeneratedTarget(merged[index]) ?: continue
            if (incomingTarget.page > NTK_GENERATED_INITIAL_RECOVERY_PAGES) continue
            if (merged[index] != verified) {
                merged[index] = verified
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
            if (ref.extension != "jpg" &&
                !hasNtkGeneratedNotFoundInitialExtension(ref, ref.extension)
            ) {
                preferredByPage[target.page] = image
            } else if (ref.extension != "jpg") {
                Log.d(
                    TAG,
                    "reader_early_ntk_urls_preserve_skip_missing page=${target.page},extension=${ref.extension}," +
                        "image=${safeImageName(image)}"
                )
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

    fun earlyNtkGeneratedSuccessImageUrls(path: String?, minCreatedAtMs: Long): List<String> {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return emptyList()
        val entry = earlyNtkGeneratedSuccessUrls[key] ?: return emptyList()
        val ageMs = SystemClock.elapsedRealtime() - entry.createdAtMs
        if (entry.createdAtMs + EARLY_NTK_IMAGE_URL_STARTED_SKEW_MS < minCreatedAtMs ||
            ageMs > EARLY_NTK_IMAGE_URL_TTL_MS
        ) {
            earlyNtkGeneratedSuccessUrls.remove(key, entry)
            return emptyList()
        }
        return entry.urls
    }

    private fun rememberEarlyNtkGeneratedSuccessUrls(path: String?, urls: List<String>) {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty() || urls.isEmpty()) return
        earlyNtkGeneratedSuccessUrls[key] = EarlyNtkImageUrls(
            Collections.unmodifiableList(ArrayList(urls)),
            SystemClock.elapsedRealtime()
        )
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
        for (entry in ntkApiFallbackImages.entries) {
            val images = entry.value
            if (images.isEmpty()) continue
            val compatible = images.all { image ->
                val target = ntkGeneratedTarget(image) ?: return@all false
                ntkApiFallbackImageMatchesPathKey(target, key)
            }
            if (compatible) return images
        }
        return emptyList()
    }

    private fun ntkApiFallbackImageMatchesPathKey(target: NtkGeneratedTarget, key: String): Boolean {
        if (target.path == key) return true
        val requested = key.trim('/').split('/')
        val actual = target.path.trim('/').split('/')
        if (requested.size < 3 || actual.size < 3) return false
        if (!requested[0].equals("webtoon", ignoreCase = true)) return false
        if (!actual[0].equals("webtoon", ignoreCase = true)) return false
        return requested[2] == actual[2]
    }

    fun hasActiveInitialNtkGeneratedFetch(manga: Manga?, image: String?): Boolean {
        if (manga == null || image.isNullOrBlank()) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        val streamKey = foregroundStreamKey(manga, image)
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

    fun primeInitialContinuousGeneratedPrefixes(
        context: Context,
        manga: Manga,
        images: List<String>?
    ) {
        if (!manga.isOnline || images.isNullOrEmpty()) return
        val appContext = context.applicationContext
        for (image in images) {
            val target = ntkGeneratedTarget(image) ?: continue
            if (target.page !in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) continue
            if (!ntkGeneratedTargetMatchesMangaEpisode(manga, target)) continue
            if (cachedFile(appContext, manga, image) != null) continue
            val flightKey = initialGeneratedRangeFlightKey(manga.baseMode, image, target.page) ?: continue
            val task = FutureTask {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val response = requestGeneratedFirstRangeChunkRace(
                        appContext,
                        manga,
                        image,
                        NTK_GENERATED_RANGE_ADJACENT_FIRST_CHUNK_BYTES,
                        null,
                        target.page
                    ) ?: run {
                        logCacheEvent(
                            "initial_continuous_prefix_miss",
                            manga,
                            image,
                            true,
                            "page=${target.page},result=null"
                        )
                        return@FutureTask null
                    }
                    response.use {
                        if (it.code != 200 && it.code != 206) {
                            logCacheEvent(
                                "initial_continuous_prefix_miss",
                                manga,
                                image,
                                true,
                                "page=${target.page},code=${it.code}"
                            )
                            return@FutureTask null
                        }
                        if (!validateNtkImageResponseUrl(manga, image, it, true, "initial_continuous_prefix")) {
                            return@FutureTask null
                        }
                        val snapshot = snapshotGeneratedRangeResponse(it) ?: return@FutureTask null
                        logCacheEvent(
                            "initial_continuous_prefix_hit",
                            manga,
                            image,
                            true,
                            "page=${target.page},code=${snapshot.code},bytes=${snapshot.bytes.size}," +
                                "total=${parseContentRangeTotal(snapshot.headers["Content-Range"])}," +
                                "ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        ViewerWarmupManager.logMetric("reader_initial_continuous_prefix_hit", target.page.toLong())
                        snapshot
                    }
                } catch (t: Throwable) {
                    rethrowIfFatal(t)
                    logCacheEvent(
                        "initial_continuous_prefix_error",
                        manga,
                        image,
                        true,
                        "page=${target.page},error=${throwableSummary(t)}"
                    )
                    null
                }
            }
            val existing = initialGeneratedPrefixFlights.putIfAbsent(flightKey, task)
            if (existing != null) {
                logCacheEvent("initial_continuous_prefix_join", manga, image, true, "page=${target.page}")
                continue
            }
            foregroundRaceExecutor.execute {
                try {
                    task.run()
                } finally {
                    scheduleInitialGeneratedPrefixExpiry(flightKey, task)
                }
            }
            logCacheEvent("initial_continuous_prefix_start", manga, image, true, "page=${target.page}")
        }
    }

    fun runWhenNtkAnchorAssetReady(
        context: Context,
        manga: Manga,
        image: String,
        callback: Runnable
    ): Boolean {
        val assetKey = ntkAnchorAssetKey(manga, image) ?: return false
        fun isReady(): Boolean {
            val file = ntkAnchorAssetFiles[assetKey] ?: return false
            if (isUsableImage(file)) return true
            ntkAnchorAssetFiles.remove(assetKey, file)
            return false
        }
        if (isReady()) {
            foregroundRaceExecutor.execute(callback)
            return true
        }
        val listeners = ntkAnchorAssetListeners.computeIfAbsent(assetKey) { CopyOnWriteArrayList() }
        listeners.add(callback)
        if (isReady() && ntkAnchorAssetListeners.remove(assetKey, listeners)) {
            for (listener in listeners) foregroundRaceExecutor.execute(listener)
        }
        logCacheEvent("ntk_anchor_asset_listener_registered", manga, image, true, "asset=$assetKey")
        return true
    }

    fun runWhenNtkInitialGeneratedAssetReady(
        context: Context,
        manga: Manga,
        image: String,
        callback: Runnable
    ): Boolean {
        val assetKey = ntkInitialGeneratedAssetKey(manga, image) ?: return false
        fun readyFile(): File? {
            val known = ntkInitialGeneratedAssetFiles[assetKey]
            if (known != null) {
                if (isUsableImage(known)) return known
                ntkInitialGeneratedAssetFiles.remove(assetKey, known)
            }
            val file = File(cacheDir(context), "${key(manga.baseMode, image)}.img")
            if (!isUsableImage(file)) return null
            ntkInitialGeneratedAssetFiles[assetKey] = file
            return file
        }
        if (readyFile() != null) {
            foregroundRaceExecutor.execute(callback)
            return true
        }
        val listeners = ntkInitialGeneratedAssetListeners.computeIfAbsent(assetKey) { CopyOnWriteArrayList() }
        listeners.add(callback)
        if (readyFile() != null && ntkInitialGeneratedAssetListeners.remove(assetKey, listeners)) {
            for (listener in listeners) foregroundRaceExecutor.execute(listener)
        }
        logCacheEvent("ntk_initial_generated_asset_listener_registered", manga, image, true, "asset=$assetKey")
        return true
    }

    fun completeInitialContinuousGeneratedPrefixes(
        context: Context,
        manga: Manga,
        images: List<String>?,
        reason: String
    ) {
        if (!manga.isOnline || images.isNullOrEmpty()) return
        val appContext = context.applicationContext
        images.forEach { image ->
            val target = ntkGeneratedTarget(image) ?: return@forEach
            if (target.page !in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return@forEach
            if (!ntkGeneratedTargetMatchesMangaEpisode(manga, target)) return@forEach
            initialVisibleForegroundExecutor.execute {
                completeInitialContinuousGeneratedPrefix(appContext, manga, image, target.page, reason)
            }
        }
    }

    private fun scheduleInitialGeneratedPrefixExpiry(key: String, task: FutureTask<GeneratedRangeSnapshot?>) {
        foregroundRaceExecutor.execute {
            try {
                Thread.sleep(FOREGROUND_STREAM_HANDOFF_TTL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (task.isDone) initialGeneratedPrefixFlights.remove(key, task)
            }
        }
    }

    private fun completeInitialContinuousGeneratedPrefix(
        context: Context,
        manga: Manga,
        image: String,
        page: Int,
        reason: String
    ) {
        if (cachedFile(context, manga, image) != null) return
        val target = ntkGeneratedTarget(image) ?: return
        val flightKey = initialGeneratedRangeFlightKey(manga.baseMode, image, page) ?: return
        val startedAt = SystemClock.elapsedRealtime()
        val snapshot = initialGeneratedPrefixFlights[flightKey]?.let { task ->
            try {
                task.get(650L, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: ExecutionException) {
                null
            }
        }
        if (snapshot != null && cacheInitialContinuousSnapshotTail(context, manga, image, page, reason, snapshot, startedAt)) {
            initialGeneratedPrefixFlights.remove(flightKey)
            return
        }
        val started = startForegroundStreamFetch(
            context,
            manga,
            image,
            null,
            false,
            null,
            page - 1,
            true
        )
        logCacheEvent(
            "initial_continuous_anchor_stream_fallback",
            manga,
            image,
            true,
            "page=$page,reason=$reason,started=$started,prefix=${snapshot != null}," +
                "ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
    }

    private fun cacheInitialContinuousSnapshotTail(
        context: Context,
        manga: Manga,
        image: String,
        page: Int,
        reason: String,
        snapshot: GeneratedRangeSnapshot,
        startedAt: Long
    ): Boolean {
        val bytes = when (snapshot.code) {
            200 -> snapshot.bytes
            206 -> {
                val total = parseContentRangeTotal(snapshot.headers["Content-Range"])
                if (total <= 0L || total > NTK_GENERATED_RANGE_MAX_BYTES) return false
                val tail = readGeneratedRangeTailChunks(
                    context,
                    manga,
                    snapshot.request.url.toString(),
                    null,
                    page,
                    "initial_continuous_anchor_$reason",
                    snapshot.bytes.size.toLong(),
                    total,
                    NTK_GENERATED_RANGE_ADJACENT_CHUNK_BYTES
                ) ?: return false
                val out = ByteArrayOutputStream(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                out.write(snapshot.bytes)
                for (chunk in tail) out.write(chunk)
                out.toByteArray()
            }
            else -> return false
        }
        if (bytes.isEmpty() || bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES || !looksLikeImage(bytes)) return false
        val generation = cacheGeneration.get()
        val actualImage = snapshot.request.url.toString()
        cacheForegroundBytes(context, manga, actualImage, bytes, generation, trustedImageBytes = true)
        rememberEarlyNtkGeneratedSuccess(manga, actualImage)
        rememberNtkGeneratedEpisodeExtension(actualImage)
        if (actualImage != image) {
            cacheForegroundBytes(context, manga, image, bytes, generation, trustedImageBytes = true)
        }
        logCacheEvent(
            "initial_continuous_anchor_range_complete",
            manga,
            image,
            true,
            "page=$page,reason=$reason,code=${snapshot.code},bytes=${bytes.size}," +
                "ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
        ViewerWarmupManager.logMetric("reader_initial_continuous_anchor_range_complete", page.toLong())
        return true
    }

    private fun isVerifiedEarlyNtkGeneratedUrl(
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget?
    ): Boolean {
        if (target == null || target.page <= 1) return false
        val path = manga.ntkEpisodePath?.takeIf { it.isNotBlank() } ?: return false
        val earlyUrls = earlyNtkImageUrls(path, 0L)
        if (earlyUrls.any { sameNtkGeneratedPage(it, target) }) return true
        if (!ntkGeneratedTargetMatchesMangaEpisode(manga, target)) return false
        return earlyUrls.any { sameNtkGeneratedPage(it, target) }
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
        return true
    }

    private fun canStreamInitialVisibleGeneratedWithoutPermit(
        manga: Manga,
        target: NtkGeneratedTarget?,
        pageIndex: Int,
        visiblePriority: Boolean
    ): Boolean {
        if (!visiblePriority) return false
        val page = target?.page ?: return false
        if (page !in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        if (pageIndex >= 0 && pageIndex != page - 1) return false
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/", ignoreCase = true) &&
            !path.startsWith("/manhwa/", ignoreCase = true)
        ) {
            return false
        }
        return ntkGeneratedTargetMatchesMangaEpisode(manga, target)
    }

    private fun isActiveNearGeneratedPageWithVerifiedAnchor(
        manga: Manga,
        target: NtkGeneratedTarget
    ): Boolean {
        if (target.page !in 2..4) return false
        val path = manga.ntkEpisodePath?.takeIf { it.isNotBlank() } ?: return false
        if (!ntkGeneratedTargetMatchesMangaEpisode(manga, target)) return false
        return earlyNtkImageUrls(path, 0L).any { candidate ->
            val candidateTarget = ntkGeneratedTarget(candidate)
            candidateTarget?.let {
                ntkGeneratedTargetsSameEpisode(it, target) && it.page == 1
            } == true
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
        pageIndex: Int = -1,
        visiblePriority: Boolean = true
    ): Boolean {
        if (!manga.isOnline) return false
        if (isNtkProtectedViewerApiImage(image)) {
            logCacheEvent(
                "foreground_stream_skip_protected_api_browser_owned",
                manga,
                image,
                true,
                "page=$pageIndex"
            )
            return false
        }
        val appContext = context.applicationContext
        val generatedTarget = ntkGeneratedTarget(image)
        val permitlessVerifiedEarlyGenerated = permit == null &&
            canStreamVerifiedEarlyGeneratedWithoutPermit(appContext, manga, image, generatedTarget)
        val permitlessInitialVisibleGenerated = permit == null &&
            (
                permitlessVerifiedEarlyGenerated ||
                    canStreamInitialVisibleGeneratedWithoutPermit(
                        manga,
                        generatedTarget,
                        pageIndex,
                        visiblePriority
                    )
            )
        if (visiblePriority &&
            generatedTarget != null &&
            generatedTarget.page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            isFastOkHttpGeneratedImageUrl(image) &&
            permit == null &&
            !permitlessInitialVisibleGenerated
        ) {
            logCacheEvent(
                "foreground_stream_skip_fast_okhttp_initial",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${generatedTarget.page}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_skip_fast_okhttp_initial", generatedTarget.page.toLong())
            return false
        }
        if (generatedTarget != null && generatedTarget.page > 1 && permit == null && !permitlessInitialVisibleGenerated) {
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
        if (cachedFile(appContext, manga, image) != null) {
            logCacheEvent(
                "foreground_stream_skip_cached",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${generatedTarget?.page ?: 0}"
            )
            return false
        }
        val key = key(manga.baseMode, image)
        val streamKey = foregroundStreamKey(manga, image)
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
        val verifiedInitialVisiblePreview =
            visiblePriority &&
                permitlessVerifiedEarlyGenerated &&
                generatedTarget?.page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
        val allowVerifiedGeneratedVariant = verifiedInitialGeneratedVariant
            || verifiedInitialVisiblePreview
        if (visiblePriority && verifiedInitialGeneratedVariant && generatedTarget != null) {
            cancelOtherInitialGeneratedForegroundStreams(manga, image, generatedTarget)
        }
        if (hasActiveFetch(manga, image) && !allowVerifiedGeneratedVariant) {
            logCacheEvent(
                "foreground_stream_skip_active_fetch",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${generatedTarget?.page ?: 0}"
            )
            return false
        }
        val finalFile = File(cacheDir(appContext), "$key.img")
        if (flights.containsKey(key) && !allowVerifiedGeneratedVariant) {
            logCacheEvent(
                "foreground_stream_skip_active_flight",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${generatedTarget?.page ?: 0}"
            )
            return false
        }
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
                    visiblePriority = visiblePriority
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
                return startForegroundStreamFetch(context, manga, image, cancellation, anchorHedge, permit, pageIndex, visiblePriority)
            }
            if (existing.isDone && foregroundStreams.remove(streamKey, existing)) {
                foregroundStreamStartedAt.remove(streamKey)
                return startForegroundStreamFetch(context, manga, image, cancellation, anchorHedge, permit, pageIndex, visiblePriority)
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
            "activeStream=false,page=$pageIndex,lane=${permit?.lane},phase=${permit?.phaseAtGrant},permit=${permit?.permitId},visiblePriority=$visiblePriority"
        )
        ViewerWarmupManager.logMetric("reader_foreground_stream_async_start", 1L)
        return try {
            val initialVisibleGeneratedStream = visiblePriority &&
                generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
                (shouldPrioritizeHintedInitialGeneratedFullFetch(image) || isFastOkHttpGeneratedImageUrl(image))
            val initialVisibleTrustedApiStream = shouldPrioritizeInitialTrustedNtkApiImage(
                image,
                pageIndex,
                visiblePriority,
                generatedTarget
            )
            val streamExecutor = if (initialVisibleGeneratedStream || initialVisibleTrustedApiStream) {
                logCacheEvent(
                    "foreground_stream_initial_visible_executor",
                    manga,
                    image,
                    true,
                    "page=$pageIndex,api=$initialVisibleTrustedApiStream"
                )
                ViewerWarmupManager.logMetric("reader_foreground_stream_initial_visible_executor", 1L)
                initialVisibleForegroundExecutor
            } else {
                foregroundRaceExecutor
            }
            streamExecutor.execute {
                try {
                    task.run()
                } finally {
                    scheduleForegroundStreamHandoffExpiry(streamKey, task)
                }
            }
            if (shouldSkipInitialGeneratedRecoveryHedge(image, visiblePriority, generatedTarget)) {
                logCacheEvent(
                    "download_initial_recovery_skip_fast_visible",
                    manga,
                    image,
                    true,
                    "page=${generatedTarget?.page ?: 0}"
                )
            } else {
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
            }
            true
        } catch (_: Exception) {
            foregroundStreams.remove(streamKey, task)
            foregroundStreamStartedAt.remove(streamKey)
            false
        }
    }

    fun startVisibleInitialGeneratedPreviewFetch(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        pageIndex: Int = -1
    ): Boolean {
        if (!manga.isOnline) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        val appContext = context.applicationContext
        if (cachedFile(appContext, manga, image) != null) return false
        val streamKey = foregroundStreamKey(manga, image)
        if (target.page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            hasActiveFetch(manga, image)
        ) {
            logCacheEvent(
                "visible_initial_preview_skip_active_fetch",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${target.page}"
            )
            return false
        }
        if (target.page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            hasFreshInitialForegroundStream(streamKey, image)
        ) {
            logCacheEvent(
                "visible_initial_preview_skip_active_foreground_stream",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${target.page}"
            )
            return false
        }
        val previewKey = "ntk-visible-preview|$streamKey"
        val startedAt = SystemClock.elapsedRealtime()
        val generation = cacheGeneration.get()
        val task = FutureTask<ByteArray?> {
            try {
                val bytes = tryInitialGeneratedForegroundRangeBytes(
                    appContext,
                    manga,
                    image,
                    cancellation,
                    generation
                )
                logCacheEvent(
                    "visible_initial_preview_async_done",
                    manga,
                    image,
                    true,
                    "page=$pageIndex,generatedPage=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},bytes=${bytes?.size ?: 0}"
                )
                bytes
            } catch (t: Throwable) {
                logCacheEvent(
                    "visible_initial_preview_async_error",
                    manga,
                    image,
                    true,
                    "page=$pageIndex,generatedPage=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},error=${throwableSummary(t)}"
                )
                null
            }
        }
        val existing = initialVisibleGeneratedPreviewFlights.putIfAbsent(previewKey, task)
        if (existing != null) {
            if (existing.isDone && initialVisibleGeneratedPreviewFlights.remove(previewKey, existing)) {
                return startVisibleInitialGeneratedPreviewFetch(context, manga, image, cancellation, pageIndex)
            }
            logCacheEvent(
                "visible_initial_preview_join",
                manga,
                image,
                true,
                "page=$pageIndex,generatedPage=${target.page}"
            )
            return false
        }
        logCacheEvent(
            "visible_initial_preview_async_start",
            manga,
            image,
            true,
            "page=$pageIndex,generatedPage=${target.page}"
        )
        return try {
            initialVisibleForegroundExecutor.execute {
                try {
                    task.run()
                } finally {
                    initialVisibleGeneratedPreviewFlights.remove(previewKey, task)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            initialVisibleGeneratedPreviewFlights.remove(previewKey, task)
            false
        }
    }

    private fun shouldSkipInitialGeneratedRecoveryHedge(
        image: String,
        visiblePriority: Boolean,
        target: NtkGeneratedTarget?
    ): Boolean {
        if (!visiblePriority) return false
        if (target?.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        return isFastOkHttpGeneratedImageUrl(image)
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
        if (target.page !in 1..ntkGeneratedInitialRecoveryPages(manga)) return
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
                val recoveryDelayMs = when {
                    target.page == 1 -> NTK_GENERATED_INITIAL_ANCHOR_RECOVERY_HEDGE_MS
                    target.page in 2..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES ->
                        NTK_GENERATED_INITIAL_TRANSIENT_RETRY_DELAY_MS
                    else -> NTK_GENERATED_INITIAL_RECOVERY_HEDGE_MS
                }
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
                if (cachedAnchorAssetFile(context, manga, image, foreground = true) != null) return@execute
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
                downloadAtomically(context, manga, image, finalFile, true, cancellation, generation)
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
        if (isNtkProtectedViewerApiImage(image)) {
            logCacheEvent(
                "foreground_decode_skip_protected_api_browser_owned",
                manga,
                image,
                true,
                "page=$pageIndex"
            )
            return null
        }
        val generatedTarget = ntkGeneratedTarget(image)
        cancellation?.throwIfCancelled()
        val appContext = context.applicationContext
        val preferForegroundBytes = shouldPreferForegroundBytesForInitialGenerated(image)
        if (cachedFile(appContext, manga, image) != null && !preferForegroundBytes) return null
        if (!preferForegroundBytes) {
            awaitForegroundStreamVariantFile(appContext, manga, image, true)?.file?.let {
                return null
            }
        }
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
        val key = foregroundStreamKey(manga, image)
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
                val target = ntkGeneratedTarget(image)
                val effectiveJoinTimeoutMs = foregroundStreamJoinBudgetMs(key, image, startedAt)
                if (effectiveJoinTimeoutMs <= 0L) {
                    logCacheEvent(
                        "foreground_stream_join_skip_initial_recovery",
                        manga,
                        image,
                        true,
                        "page=${target?.page ?: 0},timeoutMs=${foregroundStreamJoinTimeoutMs(image)}"
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
            decodeForegroundCachedFileIfPresent(
                appContext,
                manga,
                image,
                startedAt,
                autoCut,
                allowSplit,
                targetWidth,
                "foreground_stream_run_done"
            )?.let { return it }
            return decodeForegroundBytes(
                task,
                startedAt,
                autoCut,
                allowSplit,
                targetWidth,
                boundedWait = false,
                fastInitialGenerated = generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
            )
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

    private fun cancelOtherInitialGeneratedForegroundStreams(
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget
    ) {
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return
        val keepKey = foregroundStreamKey(manga, image)
        val keepExtension = keepKey.substringAfterLast('|', "")
        val streamPath = foregroundStreamEpisodePath(manga.ntkEpisodePath, target)
        val pagePrefix = "ntk-generated-stream|${manga.baseMode}|$streamPath|${target.page}|"
        foregroundStreams.entries.forEach { entry ->
            val streamKey = entry.key
            if (!streamKey.startsWith(pagePrefix) || streamKey == keepKey) return@forEach
            val cancelExtension = streamKey.substringAfterLast('|', "")
            if (shouldKeepInitialJpgJpegHedgePair(target, keepExtension, cancelExtension)) return@forEach
            val task = entry.value
            if (!foregroundStreams.remove(streamKey, task)) return@forEach
            foregroundStreamStartedAt.remove(streamKey)
            task.cancel(true)
            logCacheEvent(
                "foreground_stream_cancel_same_page_variant",
                manga,
                image,
                true,
                "page=${target.page},cancelled=$streamKey"
            )
        }
    }

    private fun shouldKeepInitialJpgJpegHedgePair(
        target: NtkGeneratedTarget,
        keepExtension: String,
        cancelExtension: String
    ): Boolean {
        return false
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
        if (isNtkProtectedViewerApiImage(image)) {
            logCacheEvent(
                "foreground_stream_fetch_skip_protected_api_browser_owned",
                manga,
                image,
                true,
                "page=${ntkProtectedApiGeneratedTarget(image)?.page ?: 0}"
            )
            return null
        }
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
        val generatedTarget = ntkGeneratedTarget(image)
        val prioritizeInitialHintedFullFetch = shouldPrioritizeHintedInitialGeneratedFullFetch(image)
        val fastInitialVisibleGeneratedRange =
            visiblePriority &&
                generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
                isFastOkHttpGeneratedImageUrl(image)
        val anchorInitialVisibleGeneratedRange =
            fastInitialVisibleGeneratedRange && generatedTarget?.page == 1
        val anchorInitialCompleteRace =
            generatedTarget?.page == 1 &&
                (prioritizeInitialHintedFullFetch || anchorInitialVisibleGeneratedRange)
        if (prioritizeInitialHintedFullFetch) {
            logCacheEvent(
                "foreground_stream_hinted_initial_priority",
                manga,
                image,
                true,
                "page=${generatedTarget?.page ?: 0}"
            )
            ViewerWarmupManager.logMetric("reader_foreground_stream_hinted_initial_priority", 1L)
        }
        if (visiblePriority && anchorInitialCompleteRace) {
            tryInitialGeneratedForegroundRangeBytes(
                context,
                manga,
                image,
                cancellation,
                generation,
                visibleCompleteOnly = true
            )?.let { bytes ->
                return bytes
            }
            if ((generatedTarget?.page ?: 0) > 1) {
                logCacheEvent(
                    "foreground_initial_range_preview_only_miss",
                    manga,
                    image,
                    true,
                    "page=${generatedTarget?.page ?: 0},fast=$fastInitialVisibleGeneratedRange"
                )
                return null
            }
        }
        val shouldGateInitialVisible = visiblePriority && anchorInitialCompleteRace
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
            val racedResponse = if (anchorInitialCompleteRace) {
                retryNtkGeneratedInitialFullRace(context, manga, image, cancellation)
            } else {
                null
            }
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
            val actualGeneratedTarget = ntkGeneratedTarget(actualImage)
            rememberNtkGeneratedEpisodeExtension(actualImage)
            rememberEarlyNtkGeneratedSuccess(manga, actualImage)
            val body = it.body ?: return null
            val contentLength = body.contentLength()
            if (contentLength > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val bodyStartAt = SystemClock.elapsedRealtime()
            val bytes = body.bytes()
            if (bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val bytesAt = SystemClock.elapsedRealtime()
            if (visiblePriority && prioritizeInitialHintedFullFetch) {
                logCacheEvent(
                    "foreground_stream_body_read",
                    manga,
                    image,
                    true,
                    "headersMs=${headersAt - startedAt},bodyMs=${bytesAt - bodyStartAt},bytes=${bytes.size}"
                )
            }
            val partialImage = it.header("x-mangaviewer-partial-image") == "1"
            if (partialImage) {
                val visibleInitialGenerated =
                    visiblePriority &&
                        generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
                if (visibleInitialGenerated) {
                    waitCachedFullBytesAfterPartial(context, manga, image, actualImage)?.let { fullBytes ->
                        if (isCompleteImageBytes(actualImage, fullBytes)) {
                            ViewerWarmupManager.logMetric(
                                "reader_foreground_stream_partial_full_handoff",
                                fullBytes.size.toLong()
                            )
                            logCacheEvent(
                                "foreground_stream_partial_full_handoff",
                                manga,
                                image,
                                true,
                                "page=${generatedTarget?.page ?: 0},bytes=${fullBytes.size}"
                            )
                            return fullBytes
                        }
                    }
                    ViewerWarmupManager.logMetric("reader_foreground_stream_partial_rejected_visible", bytes.size.toLong())
                    logCacheEvent(
                        "foreground_stream_partial_rejected_visible_complete_only",
                        manga,
                        image,
                        true,
                        "page=${generatedTarget?.page ?: 0},bytes=${bytes.size},contentLength=$contentLength"
                    )
                    return null
                }
                if (isDecodableImageBytes(bytes)) {
                    ViewerWarmupManager.logMetric("reader_foreground_stream_partial_preview", bytes.size.toLong())
                    logCacheEvent(
                        "foreground_stream_partial_preview",
                        manga,
                        image,
                        true,
                        "bytes=${bytes.size},contentLength=$contentLength"
                    )
                    return bytes
                }
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
                val cacheTarget = ntkGeneratedTarget(cacheImage)
                val deferInitialVisiblePublish = visiblePriority &&
                    prioritizeInitialHintedFullFetch &&
                    cacheTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
                val cacheStartAt = SystemClock.elapsedRealtime()
                val publish = Runnable {
                    cacheForegroundBytes(context, manga, cacheImage, bytes, generation, trustedImageBytes = true)
                    rememberVerifiedForegroundNtkAnchor(manga, cacheImage)
                    if (cacheImage != image) {
                        cacheForegroundBytes(
                            context,
                            manga,
                            image,
                            bytes,
                            generation,
                            trustedImageBytes = true
                        )
                        logCacheEvent(
                            "foreground_cached_actual_url",
                            manga,
                            cacheImage,
                            true,
                            "requested=${image.substringAfterLast('/').takeLast(64)}"
                        )
                    }
                    logCacheEvent(
                        "foreground_stream_cache_publish",
                        manga,
                        cacheImage,
                        true,
                        "cacheMs=${SystemClock.elapsedRealtime() - cacheStartAt},totalMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
                if (deferInitialVisiblePublish) {
                    logCacheEvent(
                        "foreground_stream_cache_publish_async_initial",
                        manga,
                        cacheImage,
                        true,
                        "page=${ntkGeneratedTarget(cacheImage)?.page ?: 0},bytes=${bytes.size}"
                    )
                    try {
                        foregroundCachePublishExecutor.execute(publish)
                    } catch (_: RejectedExecutionException) {
                        publish.run()
                    }
                } else {
                    publish.run()
                }
            }
            ViewerWarmupManager.logMetric("reader_foreground_stream_headers_ms", headersAt - startedAt)
            ViewerWarmupManager.logMetric("reader_foreground_stream_body_ms", bytesAt - headersAt)
            ViewerWarmupManager.logMetric("reader_foreground_stream_bytes", if (contentLength >= 0L) contentLength else bytes.size.toLong())
            if (partialImage) ViewerWarmupManager.logMetric("reader_foreground_stream_partial_bytes", bytes.size.toLong())
            return bytes
        }
    }

    private fun tryInitialGeneratedForegroundRangeBytes(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        generation: Long,
        visibleCompleteOnly: Boolean = false
    ): ByteArray? {
        val target = ntkGeneratedTarget(image) ?: return null
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return null
        val imagePath = try {
            Uri.parse(image).encodedPath.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val rangePreviewEligible =
            imagePath.contains("/wt/episodes/", ignoreCase = true) ||
            imagePath.contains("/black/episodes/", ignoreCase = true) ||
            imagePath.contains("/blacktoon/episodes/", ignoreCase = true) ||
            target.path.startsWith("/webtoon/", ignoreCase = true) ||
            target.path.startsWith("/manhwa/", ignoreCase = true)
        val startedAt = SystemClock.elapsedRealtime()
        if (visibleCompleteOnly) {
            val rangeFirstInitial = shouldUseRangeFirstForInitialGenerated(image, target)
            if (target.page == 1 && !rangeFirstInitial) {
                requestInitialGeneratedForegroundModeCompleteBytes(
                    context,
                    manga,
                    image,
                    target,
                    cancellation,
                    generation,
                    startedAt
                )?.let { return it }
            }
            if ((target.page == 1 && !rangeFirstInitial) || hasNtkAnchorAssetForEpisode(manga)) {
                requestInitialGeneratedCompleteBytesRace(
                    context,
                    manga,
                    image,
                    target,
                    cancellation,
                    generation
                )?.let { return it }
                logCacheEvent(
                    "foreground_initial_complete_race_miss",
                    manga,
                    image,
                    true,
                    "page=${target.page}"
                )
            } else {
                logCacheEvent(
                    if (rangeFirstInitial) "foreground_initial_complete_race_skip_range_first"
                    else "foreground_initial_complete_race_defer_until_anchor",
                    manga,
                    image,
                    true,
                    "page=${target.page}"
                )
            }
        }
        if (rangePreviewEligible) {
            requestGeneratedPreviewRangeChunkRace(
                context,
                manga,
                image,
                NTK_GENERATED_RANGE_ADJACENT_FIRST_CHUNK_BYTES,
                cancellation
            )?.use { previewResponse ->
                if (previewResponse.code == 200 || previewResponse.code == 206) {
                    val previewBytes = readGeneratedPreviewResponseBytes(
                        previewResponse,
                        NTK_GENERATED_RANGE_ADJACENT_FIRST_CHUNK_BYTES
                    )
                    if (previewBytes.isNotEmpty() &&
                        previewBytes.size <= MAX_DIRECT_STREAM_DECODE_BYTES &&
                        isDecodableImageBytes(previewBytes)
                    ) {
                        val actualUrl = previewResponse.request.url.toString()
                        val actualTarget = ntkGeneratedTarget(actualUrl)
                        val sameGeneratedPage = actualTarget != null &&
                            actualTarget.path == target.path &&
                            actualTarget.page == target.page
                        if (!sameGeneratedPage &&
                            !validateNtkImageResponseUrl(
                                manga,
                                image,
                                previewResponse,
                                true,
                                "foreground_initial_range_preview"
                            )
                        ) {
                            logCacheEvent(
                                "foreground_initial_range_preview_rejected",
                                manga,
                                image,
                                true,
                                "page=${target.page},code=${previewResponse.code},bytes=${previewBytes.size},reason=url_mismatch,ms=${SystemClock.elapsedRealtime() - startedAt}"
                            )
                            return@use
                        }
                        val total = parseContentRangeTotal(previewResponse.header("Content-Range"))
                        rememberNtkGeneratedEpisodeExtension(actualUrl)
                        rememberEarlyNtkGeneratedSuccess(manga, actualUrl)
                        val responsePartialImage =
                            previewResponse.header("x-mangaviewer-partial-image") == "1" ||
                                previewResponse.code == 206
                        val completeRangeImage =
                            responsePartialImage &&
                                total > 0L &&
                                previewBytes.size.toLong() >= total
                        val partialImage = responsePartialImage && !completeRangeImage
                        if (completeRangeImage) {
                            logCacheEvent(
                                "foreground_initial_range_complete_first_chunk",
                                manga,
                                image,
                                true,
                                "page=${target.page},bytes=${previewBytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
                            )
                        }
                        if (partialImage) {
                            reassembleInitialGeneratedPreviewFullBytes(
                                context,
                                manga,
                                image,
                                actualUrl,
                                previewBytes,
                                total,
                                target.page,
                                cancellation,
                                generation,
                                startedAt
                            )?.let { fullBytes ->
                                ViewerWarmupManager.logMetric(
                                    "reader_foreground_initial_range_full_bytes",
                                    fullBytes.size.toLong()
                                )
                                logCacheEvent(
                                    "foreground_initial_range_preview_reassembled",
                                    manga,
                                    image,
                                    true,
                                    "page=${target.page},bytes=${fullBytes.size},previewBytes=${previewBytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
                                )
                                return fullBytes
                            }
                        }
                        if (partialImage && visibleCompleteOnly) {
                            if (target.page == 1) {
                                scheduleInitialGeneratedFullCacheAfterPreview(
                                    context,
                                    manga,
                                    image,
                                    generation
                                )
                            }
                            logCacheEvent(
                                "foreground_initial_partial_rejected_complete_only",
                                manga,
                                image,
                                true,
                                "page=${target.page},bytes=${previewBytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
                            )
                            return@use
                        }
                        if (partialImage) {
                            publishInitialGeneratedPreviewBytes(
                                context,
                                manga,
                                image,
                                previewBytes,
                                generation
                            )
                        } else {
                            val cacheImage = foregroundResponseCacheImage(image, previewResponse)
                            cacheForegroundBytes(
                                context,
                                manga,
                                cacheImage,
                                previewBytes,
                                generation,
                                trustedImageBytes = true
                            )
                            rememberVerifiedForegroundNtkAnchor(manga, cacheImage)
                            if (cacheImage != image) {
                                cacheForegroundBytes(
                                    context,
                                    manga,
                                    image,
                                    previewBytes,
                                    generation,
                                    trustedImageBytes = true
                                )
                            }
                        }
                        if (partialImage && target.page == 1) {
                            scheduleInitialGeneratedFullCacheAfterPreview(
                                context,
                                manga,
                                image,
                                generation
                            )
                        }
                        ViewerWarmupManager.logMetric(
                            "reader_foreground_initial_range_preview_bytes",
                            previewBytes.size.toLong()
                        )
                        logCacheEvent(
                            "foreground_initial_range_preview_hit",
                            manga,
                            image,
                            true,
                            "page=${target.page},code=${previewResponse.code},partial=$partialImage,bytes=${previewBytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        return previewBytes
                    }
                    logCacheEvent(
                        "foreground_initial_range_preview_rejected",
                        manga,
                        image,
                        true,
                        "page=${target.page},code=${previewResponse.code},bytes=${previewBytes.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                }
            }
        }
        if (target.page != 1) return null
        val response = requestInitialGeneratedRangeWithDirectHedge(
            context,
            manga,
            image,
            foreground = true,
            cancellation,
            foregroundRaceAttempts = FOREGROUND_STREAM_RACE_ATTEMPTS,
            anchorHedge = false,
            page = target.page,
            stage = "foreground_initial_range_full"
        ) ?: run {
            logCacheEvent(
                "foreground_initial_range_full_miss",
                manga,
                image,
                true,
                "page=${target.page},result=null"
            )
            return null
        }
        response.use {
            if (it.code != 200 && it.code != 206) {
                logCacheEvent(
                    "foreground_initial_range_full_miss",
                    manga,
                    image,
                    true,
                    "page=${target.page},code=${it.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return null
            }
            if (!validateNtkImageResponseUrl(manga, image, it, true, "foreground_initial_range_full")) {
                return null
            }
            val body = it.body ?: return null
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            if (!hasDecodableImageBounds(bytes)) {
                logCacheEvent(
                    "foreground_initial_range_full_rejected",
                    manga,
                    image,
                    true,
                    "page=${target.page},bytes=${bytes.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return null
            }
            val total = parseContentRangeTotal(it.header("Content-Range"))
            val actualImage = it.request.url.toString()
            if (total <= 0L || bytes.size.toLong() >= total) {
                val cacheImage = foregroundResponseCacheImage(image, it)
                cacheForegroundBytes(context, manga, cacheImage, bytes, generation, trustedImageBytes = true)
                rememberVerifiedForegroundNtkAnchor(manga, cacheImage)
                if (cacheImage != image) {
                    cacheForegroundBytes(
                        context,
                        manga,
                        image,
                        bytes,
                        generation,
                        trustedImageBytes = true
                    )
                }
            }
            rememberNtkGeneratedEpisodeExtension(actualImage)
            rememberEarlyNtkGeneratedSuccess(manga, actualImage)
            ViewerWarmupManager.logMetric("reader_foreground_initial_range_full_bytes", bytes.size.toLong())
            logCacheEvent(
                "foreground_initial_range_full_hit",
                manga,
                image,
                true,
                "page=${target.page},code=${it.code},bytes=${bytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return bytes
        }
    }

    private fun reassembleInitialGeneratedPreviewFullBytes(
        context: Context,
        manga: Manga,
        requestedImage: String,
        actualImage: String,
        firstBytes: ByteArray,
        total: Long,
        page: Int,
        cancellation: Cancellation?,
        generation: Long,
        startedAt: Long
    ): ByteArray? {
        if (firstBytes.isEmpty()) return null
        if (total <= firstBytes.size || total > MAX_DIRECT_STREAM_DECODE_BYTES) return null
        val remaining = readGeneratedRangeTailChunks(
            context,
            manga,
            actualImage,
            cancellation,
            page,
            "foreground_initial_range_preview_full",
            firstBytes.size.toLong(),
            total,
            NTK_GENERATED_RANGE_ADJACENT_CHUNK_BYTES
        ) ?: return null
        val out = ByteArrayOutputStream(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        out.write(firstBytes)
        for (bytes in remaining) out.write(bytes)
        val fullBytes = out.toByteArray()
        if (fullBytes.size.toLong() < total) return null
        if (!looksLikeImage(fullBytes) || !hasDecodableImageBounds(fullBytes)) return null
        val cacheImage = actualImage.ifBlank { requestedImage }
        cacheForegroundBytes(
            context,
            manga,
            cacheImage,
            fullBytes,
            generation,
            trustedImageBytes = true
        )
        rememberVerifiedForegroundNtkAnchor(manga, cacheImage)
        if (cacheImage != requestedImage) {
            cacheForegroundBytes(
                context,
                manga,
                requestedImage,
                fullBytes,
                generation,
                trustedImageBytes = true
            )
        }
        logCacheEvent(
            "foreground_initial_range_preview_full_cached",
            manga,
            requestedImage,
            true,
            "page=$page,bytes=${fullBytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return fullBytes
    }

    private fun requestInitialGeneratedForegroundModeCompleteBytes(
        context: Context,
        manga: Manga,
        image: String,
        expected: NtkGeneratedTarget,
        cancellation: Cancellation?,
        generation: Long,
        startedAt: Long
    ): ByteArray? {
        val response = try {
            requestForForegroundMode(
                context,
                manga,
                image,
                foreground = true,
                cancellation = cancellation,
                foregroundRaceAttempts = FOREGROUND_STREAM_RACE_ATTEMPTS,
                anchorHedge = false
            )
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "foreground_initial_direct_first_error",
                manga,
                image,
                true,
                "page=${expected.page},error=${t.javaClass.simpleName},summary=${throwableSummary(t)},ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return null
        }
        response.use {
            if (it.code != 200 && it.code != 206) {
                logCacheEvent(
                    "foreground_initial_direct_first_miss",
                    manga,
                    image,
                    true,
                    "page=${expected.page},code=${it.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return null
            }
            val actualImage = it.request.url.toString()
            val actualTarget = ntkGeneratedTarget(actualImage)
            val actualMatches = actualTarget != null && isCompatibleNtkGeneratedPage(expected, actualTarget)
            if (!actualMatches && !isTrustedNtkGeneratedStreamResponse(image, actualImage)) {
                logCacheEvent(
                    "foreground_initial_direct_first_rejected",
                    manga,
                    image,
                    true,
                    "page=${expected.page},actual=${safeImageName(actualImage)}"
                )
                return null
            }
            val body = it.body ?: return null
            val contentLength = body.contentLength()
            if (contentLength > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            val total = parseContentRangeTotal(it.header("Content-Range"))
            val incompleteRange = it.code == 206 && !(total > 0L && bytes.size.toLong() >= total)
            if (incompleteRange || !isCompleteImageBytes(actualImage, bytes)) {
                logCacheEvent(
                    "foreground_initial_direct_first_rejected",
                    manga,
                    image,
                    true,
                    "page=${expected.page},code=${it.code},bytes=${bytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return null
            }
            val cacheImage = foregroundResponseCacheImage(image, it)
            cacheForegroundBytes(context, manga, cacheImage, bytes, generation, trustedImageBytes = true)
            rememberVerifiedForegroundNtkAnchor(manga, cacheImage)
            rememberNtkGeneratedEpisodeExtension(actualImage)
            rememberEarlyNtkGeneratedSuccess(manga, actualImage)
            if (cacheImage != image) {
                cacheForegroundBytes(context, manga, image, bytes, generation, trustedImageBytes = true)
            }
            logCacheEvent(
                "foreground_initial_direct_first_hit",
                manga,
                cacheImage,
                true,
                "page=${expected.page},code=${it.code},bytes=${bytes.size},ms=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return bytes
        }
    }

    private data class InitialCompleteBytes(
        val image: String,
        val bytes: ByteArray,
        val source: String,
        val code: Int
    )

    private fun requestInitialGeneratedCompleteBytesRace(
        context: Context,
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget,
        cancellation: Cancellation?,
        generation: Long
    ): ByteArray? {
        val flightKey = initialGeneratedCompleteBytesFlightKey(manga, target, generation)
        val task = FutureTask<ByteArray?> {
            requestInitialGeneratedCompleteBytesRaceCore(
                context,
                manga,
                image,
                target,
                cancellation,
                generation
            )
        }
        val existing = initialGeneratedCompleteBytesFlights.putIfAbsent(flightKey, task)
        if (existing != null && existing.isCancelled && initialGeneratedCompleteBytesFlights.remove(flightKey, existing)) {
            return requestInitialGeneratedCompleteBytesRace(context, manga, image, target, cancellation, generation)
        }
        val flight = existing ?: task
        if (existing != null) {
            logCacheEvent(
                "foreground_initial_complete_race_join",
                manga,
                image,
                true,
                "page=${target.page}"
            )
        } else {
            try {
                task.run()
            } finally {
                initialGeneratedCompleteBytesFlights.remove(flightKey, task)
            }
        }
        return try {
            cancellation?.throwIfCancelled()
            flight.get(if (target.page == 1) 3500L else 3000L, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            logCacheEvent(
                "foreground_initial_complete_race_join_timeout",
                manga,
                image,
                true,
                "page=${target.page}"
            )
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        }
    }

    private fun initialGeneratedCompleteBytesFlightKey(
        manga: Manga,
        target: NtkGeneratedTarget,
        generation: Long
    ): String {
        val path = ntkFallbackKeyPath(manga, target)
        return "ntk-initial-complete|${manga.baseMode}|$path|${target.page}|$generation"
    }

    private fun requestInitialGeneratedCompleteBytesRaceCore(
        context: Context,
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget,
        cancellation: Cancellation?,
        generation: Long
    ): ByteArray? {
        val startedAt = SystemClock.elapsedRealtime()
        val candidates = ntkInitialCompleteRaceCandidates(
            manga,
            image,
            target,
            if (target.page == 1) {
                NTK_GENERATED_INITIAL_COMPLETE_RACE_ANCHOR_CANDIDATES
            } else {
                NTK_GENERATED_INITIAL_COMPLETE_RACE_NEAR_CANDIDATES
            }
        )
        if (candidates.isEmpty()) return null
        val completion = ExecutorCompletionService<InitialCompleteBytes?>(foregroundRaceExecutor)
        val includeRangeCandidates = target.page != 1
        val futures = ArrayList<Future<InitialCompleteBytes?>>(
            candidates.size * if (includeRangeCandidates) 2 else 1
        )
        logCacheEvent(
            "foreground_initial_complete_race_start",
            manga,
            image,
            true,
            "page=${target.page},candidates=${candidates.size},range=$includeRangeCandidates"
        )
        for (candidate in candidates) {
            futures += completion.submit(Callable {
                requestInitialGeneratedFullBytesCandidate(
                    context,
                    manga,
                    image,
                    candidate,
                    target,
                    cancellation
                )
            })
            if (includeRangeCandidates) {
                futures += completion.submit(Callable {
                    requestInitialGeneratedRangeCompleteBytesCandidate(
                        context,
                        manga,
                        image,
                        candidate,
                        target,
                        cancellation
                    )
                })
            }
        }
        val deadlineMs = startedAt + if (target.page == 1) 2500L else 3000L
        var remaining = futures.size
        return try {
            while (remaining > 0) {
                val waitMs = deadlineMs - SystemClock.elapsedRealtime()
                if (waitMs <= 0L) break
                val future = completion.poll(minOf(waitMs, 250L), TimeUnit.MILLISECONDS) ?: continue
                remaining--
                val result = try {
                    future.get()
                } catch (_: Exception) {
                    null
                } ?: continue
                if (!isCompleteImageBytes(result.image, result.bytes)) continue
                cacheForegroundBytes(
                    context,
                    manga,
                    result.image,
                    result.bytes,
                    generation,
                    trustedImageBytes = true
                )
                rememberVerifiedForegroundNtkAnchor(manga, result.image)
                rememberNtkGeneratedEpisodeExtension(result.image)
                rememberEarlyNtkGeneratedSuccess(manga, result.image)
                if (result.image != image) {
                    cacheForegroundBytes(
                        context,
                        manga,
                        image,
                        result.bytes,
                        generation,
                        trustedImageBytes = true
                    )
                }
                logCacheEvent(
                    "foreground_initial_complete_race_win",
                    manga,
                    result.image,
                    true,
                    "page=${target.page},source=${result.source},code=${result.code},bytes=${result.bytes.size},ms=${SystemClock.elapsedRealtime() - startedAt},candidates=${candidates.size}"
                )
                futures.forEach { if (!it.isDone) it.cancel(true) }
                return result.bytes
            }
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    private fun ntkInitialCompleteRaceCandidates(
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget,
        limit: Int
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        candidates.addAll(ntkPreferredGeneratedForegroundCandidates(manga, image, limit))
        if (candidates.size <= 1 && target.path.startsWith("/webtoon/", ignoreCase = true)) {
            val parts = target.path.trim('/').split('/')
            if (parts.size >= 3) {
                val workId = manga.ntkImageWorkId.trim().ifBlank { parts[1] }
                val episodeId = manga.ntkImageEpisodeId.trim().ifBlank { parts[2] }
                val pageName = "p%03d.%s".format(
                    Locale.ROOT,
                    target.page,
                    ntkGeneratedImageRef(image)?.extension?.takeIf { it.isNotBlank() } ?: "jpg"
                )
                if (workId.matches(Regex("\\d{1,12}")) && episodeId.matches(Regex("\\d{1,12}"))) {
                    val suffix = "/black/episodes/$workId/$episodeId/$pageName"
                    candidates.add("http://fifa.worldcup73.xyz$suffix")
                    candidates.add("http://aws-cdn1.site$suffix")
                    candidates.add("https://aws-cdn1.site$suffix")
                    candidates.add("https://fifa.worldcup73.xyz$suffix")
                }
            }
        }
        val takeLimit = if (target.page == 1 && candidates.size > 1) {
            maxOf(limit, 4)
        } else {
            limit
        }
        return candidates
            .filterNot { Uri.parse(it).host.equals("moamoabon.com", ignoreCase = true) }
            .distinct()
            .sortedWith(
                compareBy<String> { generatedPreviewRangeCandidateRank(it) }
                    .thenBy { it.length }
            )
            .take(takeLimit.coerceAtLeast(1))
    }

    private fun requestInitialGeneratedFullBytesCandidate(
        context: Context,
        manga: Manga,
        requestedImage: String,
        candidate: String,
        expected: NtkGeneratedTarget,
        cancellation: Cancellation?
    ): InitialCompleteBytes? {
        val candidateTarget = ntkGeneratedTarget(candidate) ?: return null
        if (!isCompatibleNtkGeneratedPage(expected, candidateTarget)) return null
        val startedAt = SystemClock.elapsedRealtime()
        val request = requestFor(manga, candidate, foregroundPriority = true)
            .newBuilder()
            .removeHeader("X-MangaViewer-Foreground")
            .header("X-MangaViewer-No-Quic", "1")
            .build()
        val client = if (isNtkGeneratedImageUrl(candidate)) {
            getHttpClient().ntkForegroundImageFastClient()
        } else {
            getHttpClient().imageClient
        }
        val call = client.newCall(request)
        val activeCallKey = trackActiveNtkEpisodeCall(manga, candidate, call)
        cancellation?.track(call)
        return try {
            val response = call.execute()
            response.use {
                if (it.code != 200 && it.code != 206) {
                    if (isPermanentGeneratedMissingCode(it.code)) {
                        rememberNtkGeneratedNotFound(
                            manga,
                            it.request.url.toString(),
                            "foreground_initial_complete_full"
                        )
                    }
                    logCacheEvent(
                        "foreground_initial_complete_full_miss",
                        manga,
                        candidate,
                        true,
                        "page=${expected.page},code=${it.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return null
                }
                val actualImage = it.request.url.toString()
                val actualTarget = ntkGeneratedTarget(actualImage)
                val actualMatches = actualTarget != null && isCompatibleNtkGeneratedPage(expected, actualTarget)
                if (!actualMatches && !isTrustedNtkGeneratedStreamResponse(candidate, actualImage)) {
                    logCacheEvent(
                        "foreground_initial_complete_full_rejected",
                        manga,
                        candidate,
                        true,
                        "page=${expected.page},reason=url_mismatch,actual=${safeImageName(actualImage)}"
                    )
                    return null
                }
                val body = it.body ?: return null
                val contentLength = body.contentLength()
                if (contentLength > MAX_DIRECT_STREAM_DECODE_BYTES) return null
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return null
                val total = parseContentRangeTotal(it.header("Content-Range"))
                val incompleteRange = it.code == 206 && !(total > 0L && bytes.size.toLong() >= total)
                if (incompleteRange || !isCompleteImageBytes(actualImage, bytes)) {
                    logCacheEvent(
                        "foreground_initial_complete_full_rejected",
                        manga,
                        candidate,
                        true,
                        "page=${expected.page},code=${it.code},bytes=${bytes.size},total=$total,ms=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    return null
                }
                InitialCompleteBytes(actualImage, bytes, "full-get", it.code)
            }
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "foreground_initial_complete_full_error",
                manga,
                candidate,
                true,
                "page=${expected.page},error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
            )
            null
        } finally {
            cancellation?.untrack(call)
            untrackActiveNtkEpisodeCall(activeCallKey)
        }
    }

    private fun requestInitialGeneratedRangeCompleteBytesCandidate(
        context: Context,
        manga: Manga,
        requestedImage: String,
        candidate: String,
        expected: NtkGeneratedTarget,
        cancellation: Cancellation?
    ): InitialCompleteBytes? {
        val candidateTarget = ntkGeneratedTarget(candidate) ?: return null
        if (!isCompatibleNtkGeneratedPage(expected, candidateTarget)) return null
        val response = requestGeneratedRangeReassembled(
            context,
            manga,
            candidate,
            cancellation,
            expected.page,
            "foreground_initial_complete_race"
        ) ?: return null
        response.use {
            if (it.code != 200 && it.code != 206) return null
            val actualImage = it.request.url.toString()
            val actualTarget = ntkGeneratedTarget(actualImage)
            val actualMatches = actualTarget != null && isCompatibleNtkGeneratedPage(expected, actualTarget)
            if (!actualMatches && !isTrustedNtkGeneratedStreamResponse(candidate, actualImage)) {
                logCacheEvent(
                    "foreground_initial_complete_range_rejected",
                    manga,
                    requestedImage,
                    true,
                    "page=${expected.page},reason=url_mismatch,actual=${safeImageName(actualImage)}"
                )
                return null
            }
            val bytes = it.body?.bytes() ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return null
            if (!isCompleteImageBytes(actualImage, bytes)) {
                logCacheEvent(
                    "foreground_initial_complete_range_rejected",
                    manga,
                    requestedImage,
                    true,
                    "page=${expected.page},bytes=${bytes.size}"
                )
                return null
            }
            return InitialCompleteBytes(actualImage, bytes, "range-full", it.code)
        }
    }

    private fun requestGeneratedPreviewRangeChunkRace(
        context: Context,
        manga: Manga,
        image: String,
        preferredBytes: Int,
        cancellation: Cancellation?
    ): Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        val candidates = if (target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            ntkPreferredGeneratedPreviewRangeCandidates(manga, image)
        } else {
            listOf(image)
        }
        if (candidates.size <= 1) {
            return requestGeneratedRangeChunk(
                context,
                manga,
                image,
                0L,
                preferredBytes.toLong() - 1L,
                cancellation
            )
        }
        val completion = ExecutorCompletionService<Pair<String, Response>?>(foregroundRaceExecutor)
        val futures = ArrayList<Future<Pair<String, Response>?>>(candidates.size)
        val startedAt = SystemClock.elapsedRealtime()
        for (candidate in candidates) {
            futures += completion.submit(Callable {
                cancellation?.throwIfCancelled()
                val response = requestGeneratedRangeChunk(
                    context,
                    manga,
                    candidate,
                    0L,
                    preferredBytes.toLong() - 1L,
                    cancellation
                ) ?: return@Callable null
                if (response.code == 200 || response.code == 206) {
                    val bytes = readGeneratedPreviewResponseBytes(response, preferredBytes)
                    return@Callable if (bytes.isNotEmpty() &&
                        bytes.size <= MAX_DIRECT_STREAM_DECODE_BYTES &&
                        isDecodableImageBytes(bytes)
                    ) {
                        val total = parseContentRangeTotal(response.header("Content-Range"))
                        val partialImage = response.code == 206 &&
                            !(total > 0L && bytes.size.toLong() >= total)
                        val memoryResponse = responseFromBytes(
                            response,
                            bytes,
                            partialImage = partialImage
                        )
                        response.close()
                        candidate to memoryResponse
                    } else {
                        response.close()
                        null
                    }
                }
                response.close()
                null
            })
        }
        return try {
            var remaining = futures.size
            val deadlineMs = startedAt + NTK_GENERATED_VISIBLE_RANGE_PREVIEW_DEADLINE_MS
            while (remaining > 0) {
                val waitMs = deadlineMs - SystemClock.elapsedRealtime()
                if (waitMs <= 0L) break
                val future = completion.poll(minOf(waitMs, 150L), TimeUnit.MILLISECONDS) ?: continue
                remaining--
                val pair = future.get()
                if (pair != null) {
                    logCacheEvent(
                        "generated_range_preview_cdn_race_hit",
                        manga,
                        pair.first,
                        true,
                        "page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},candidates=${candidates.size}"
                    )
                    return pair.second
                }
            }
            logCacheEvent(
                "generated_range_preview_cdn_race_timeout",
                manga,
                image,
                true,
                "page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},candidates=${candidates.size}"
            )
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        } finally {
            futures.forEach { future ->
                if (!future.isDone) future.cancel(true)
            }
        }
    }

    private fun readGeneratedPreviewBytes(response: Response, maxBytes: Int): ByteArray {
        val body = response.body ?: return ByteArray(0)
        val limit = maxBytes.coerceAtLeast(1)
        val input = body.byteStream()
        val out = ByteArrayOutputStream(limit)
        val buffer = ByteArray(16 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun readGeneratedPreviewResponseBytes(response: Response, maxPreviewBytes: Int): ByteArray {
        if (response.code != 200) return readGeneratedPreviewBytes(response, maxPreviewBytes)
        val body = response.body ?: return ByteArray(0)
        val contentLength = body.contentLength()
        if (contentLength > MAX_DIRECT_STREAM_DECODE_BYTES) return ByteArray(0)
        val capacity = if (contentLength > 0L) {
            contentLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            maxPreviewBytes.coerceAtLeast(1)
        }
        val input = body.byteStream()
        val out = ByteArrayOutputStream(capacity)
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read.toLong()
            if (total > MAX_DIRECT_STREAM_DECODE_BYTES) return ByteArray(0)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ntkPreferredGeneratedPreviewRangeCandidates(manga: Manga, image: String): List<String> {
        val target = ntkGeneratedTarget(image) ?: return listOf(image)
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return listOf(image)
        val limit = NTK_GENERATED_PREVIEW_RANGE_CANDIDATES
        return ntkPreferredGeneratedForegroundCandidates(
            manga,
            image,
            limit
        )
    }

    private fun ntkPreferredGeneratedForegroundCandidates(
        manga: Manga,
        image: String,
        limit: Int
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        val hinted = ntkGeneratedImageWithHintedExtension(image)
        if (hinted != image) candidates.add(hinted)
        candidates.add(image)
        if (ntkGeneratedTarget(image)?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            candidates.addAll(ntkGeneratedExtensionFallbacks(image))
        }
        candidates.addAll(ntkEquivalentGeneratedCdnImagesForActiveEpisode(manga, image))
        return candidates
            .filterNot { Uri.parse(it).host.equals("moamoabon.com", ignoreCase = true) }
            .distinct()
            .sortedWith(
                compareBy<String> { generatedPreviewRangeCandidateRank(it) }
                    .thenBy { it.length }
            )
            .take(limit.coerceAtLeast(1))
            .ifEmpty { listOf(image) }
    }

    private fun generatedPreviewRangeCandidateRank(image: String): Int {
        val uri = try {
            Uri.parse(image)
        } catch (_: Throwable) {
            null
        } ?: return 100
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        return when {
            host == "fifa.worldcup73.xyz" && scheme == "http" && path.contains("/black/episodes/") -> 0
            host == "fifa.worldcup73.xyz" && path.contains("/black/episodes/") -> 1
            host == "aws-cdn1.site" && scheme == "http" && path.contains("/black/episodes/") -> 2
            host == "aws-cdn1.site" && path.contains("/black/episodes/") -> 3
            path.contains("/black/episodes/") -> 4
            else -> 20
        }
    }

    private fun scheduleInitialGeneratedFullCacheAfterPreview(
        context: Context,
        manga: Manga,
        image: String,
        generation: Long
    ) {
        foregroundCachePublishExecutor.execute {
            try {
                requestForForegroundMode(
                    context,
                    manga,
                    image,
                    foreground = true,
                    cancellation = null,
                    foregroundRaceAttempts = 1,
                    anchorHedge = false
                ).use { response ->
                    if (!response.isSuccessful) return@execute
                    if (!validateNtkImageResponseUrl(manga, image, response, true, "foreground_initial_range_preview_full")) {
                        return@execute
                    }
                    val body = response.body ?: return@execute
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || bytes.size > MAX_DIRECT_STREAM_DECODE_BYTES) return@execute
                    val cacheImage = foregroundResponseCacheImage(image, response)
                    cacheForegroundBytes(context, manga, cacheImage, bytes, generation, trustedImageBytes = true)
                    rememberVerifiedForegroundNtkAnchor(manga, cacheImage)
                    if (cacheImage != image) {
                        cacheForegroundBytes(context, manga, image, bytes, generation, trustedImageBytes = true)
                    }
                    rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
                    rememberEarlyNtkGeneratedSuccess(manga, response.request.url.toString())
                    logCacheEvent(
                        "foreground_initial_range_preview_full_cached",
                        manga,
                        image,
                        true,
                        "bytes=${bytes.size},code=${response.code}"
                    )
                }
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                logCacheEvent(
                    "foreground_initial_range_preview_full_error",
                    manga,
                    image,
                    true,
                    "error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
                )
            }
        }
    }

    private fun publishInitialGeneratedPreviewBytes(
        context: Context,
        manga: Manga,
        image: String,
        bytes: ByteArray,
        generation: Long
    ) {
        if (generation != cacheGeneration.get()) return
        val assetKey = ntkInitialGeneratedAssetKey(manga, image) ?: return
        val finalFile = File(cacheDir(context), "${key(manga.baseMode, image)}.preview.img")
        val tmp = File(finalFile.parentFile, "${finalFile.name}.${System.nanoTime()}")
        try {
            if (bytes.size < 32 || !looksLikeImage(bytes)) return
            val existing = ntkInitialGeneratedAssetFiles[assetKey]
            if (existing != null) {
                if (existing.isFile && existing.length() >= 32L) {
                    logCacheEvent(
                        "foreground_initial_range_preview_publish_skip_existing",
                        manga,
                        image,
                        true,
                        "asset=$assetKey,bytes=${existing.length()}"
                    )
                    return
                }
                ntkInitialGeneratedAssetFiles.remove(assetKey, existing)
            }
            FileOutputStream(tmp).use { it.write(bytes) }
            replace(tmp, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            val previous = ntkInitialGeneratedAssetFiles.put(assetKey, finalFile)
            logCacheEvent(
                "foreground_initial_range_preview_published",
                manga,
                image,
                true,
                "asset=$assetKey,bytes=${finalFile.length()},previous=${previous?.name.orEmpty()}"
            )
            ntkInitialGeneratedAssetListeners.remove(assetKey)?.let { listeners ->
                logCacheEvent(
                    "ntk_initial_generated_asset_listener_fire",
                    manga,
                    image,
                    true,
                    "asset=$assetKey,count=${listeners.size},source=foreground_initial_range_preview"
                )
                for (listener in listeners) foregroundRaceExecutor.execute(listener)
            }
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            tmp.delete()
            logCacheEvent(
                "foreground_initial_range_preview_publish_error",
                manga,
                image,
                true,
                "error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
            )
        }
    }

    private fun hasDecodableImageBounds(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun isDecodableImageBytes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun isCompleteImageBytes(image: String, bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (!looksLikeImage(bytes) || !hasDecodableImageBounds(bytes)) return false
        val lower = image.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ->
                bytes.size >= 2 &&
                    (bytes[bytes.size - 2].toInt() and 0xff) == 0xff &&
                    (bytes[bytes.size - 1].toInt() and 0xff) == 0xd9
            lower.endsWith(".png") -> {
                val iend = byteArrayOf(
                    0x00, 0x00, 0x00, 0x00,
                    0x49, 0x45, 0x4e, 0x44,
                    0xae.toByte(), 0x42, 0x60, 0x82.toByte()
                )
                bytes.size >= iend.size &&
                    bytes.copyOfRange(bytes.size - iend.size, bytes.size).contentEquals(iend)
            }
            lower.endsWith(".webp") ->
                bytes.size >= 12 &&
                    bytes[0] == 0x52.toByte() &&
                    bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() &&
                    bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x57.toByte() &&
                    bytes[9] == 0x45.toByte() &&
                    bytes[10] == 0x42.toByte() &&
                    bytes[11] == 0x50.toByte() &&
                    (
                        ((bytes[4].toInt() and 0xff).toLong()) or
                            ((bytes[5].toInt() and 0xff).toLong() shl 8) or
                            ((bytes[6].toInt() and 0xff).toLong() shl 16) or
                            ((bytes[7].toInt() and 0xff).toLong() shl 24)
                    ) + 8L == bytes.size.toLong()
            lower.endsWith(".gif") -> bytes.last() == 0x3b.toByte()
            else -> true
        }
    }

    private fun foregroundPartialPreviewSampleSize(width: Int): Int {
        var sample = 1
        while (width / sample > 1440) {
            sample *= 2
        }
        return sample
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
        val raceCurrentManhwaJpg = shouldRaceInitialManhwaJpgAlternates(image, target)
        if (!raceCurrentManhwaJpg && !ntkGeneratedEpisodeExtensionHinted(image)) return null
        if (!raceCurrentManhwaJpg && ntkGeneratedEpisodeExtensionMatches(image)) return null
        val candidates = (if (raceCurrentManhwaJpg) listOf(image) else emptyList()) +
            ntkGeneratedExtensionFallbacks(image)
        val maxCandidates = if (raceCurrentManhwaJpg) 4 else 3
        val distinctCandidates = candidates
            .filter { raceCurrentManhwaJpg || it != image }
            .distinct()
            .take(maxCandidates)
        if (distinctCandidates.size <= 1) return null
        val startedAt = SystemClock.elapsedRealtime()
        val completion = ExecutorCompletionService<okhttp3.Response?>(foregroundRaceExecutor)
        val futures = ArrayList<Future<okhttp3.Response?>>(distinctCandidates.size)
        for (candidate in distinctCandidates) {
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

    fun isTrustedInitialNtkApiImageForEarlyStream(image: String): Boolean {
        return ntkGeneratedTarget(image) == null &&
            isTrustedNtkImageUrl(image) &&
            !isDisallowedNtkImageAssetUrl(image)
    }

    private fun shouldPrioritizeInitialTrustedNtkApiImage(
        image: String,
        pageIndex: Int,
        visiblePriority: Boolean,
        generatedTarget: NtkGeneratedTarget?
    ): Boolean {
        if (!visiblePriority || pageIndex > 0 || generatedTarget != null) return false
        return isTrustedInitialNtkApiImageForEarlyStream(image)
    }

    private fun shouldPrioritizeHintedInitialGeneratedFullFetch(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page == 1 && isSupportedNtkGeneratedImageExtension(image)) return true
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun shouldRaceInitialManhwaJpgAlternates(
        image: String,
        target: NtkGeneratedTarget
    ): Boolean {
        if (target.page != 1) return false
        if (!target.path.startsWith("/manhwa/", ignoreCase = true)) return false
        val ref = ntkGeneratedImageRef(image) ?: return false
        return ref.extension == "jpg" && !ntkGeneratedEpisodeExtensionMatches(image)
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
        waitTimeoutMs: Long = FOREGROUND_STREAM_JOIN_TIMEOUT_MS,
        fastInitialGenerated: Boolean = false
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
        if (!fastInitialGenerated && shouldPreferFileDecodeAfterForegroundCache(bounds, bytes.size, autoCut)) {
            ViewerWarmupManager.logMetric("reader_foreground_stream_file_decode_preferred", bytes.size.toLong())
            return null
        }
        val decodeTargetWidth = foregroundDecodeTargetWidth(bounds.outWidth, bounds.outHeight, targetWidth, autoCut, allowSplit)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = if (fastInitialGenerated) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inSampleSize = foregroundSampleSize(bounds.outWidth, decodeTargetWidth, fastInitialGenerated)
            if (fastInitialGenerated) {
                applyInitialGeneratedDecodeScale(bounds.outWidth, decodeTargetWidth)
            }
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
                return decodeForegroundFile(
                    cached,
                    startedAt,
                    autoCut,
                    allowSplit,
                    targetWidth,
                    fastInitialGenerated = ntkGeneratedTarget(image)?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
                )
            }
            cachedImageFile(context, manga, image)?.let { cached ->
                cached.setLastModified(System.currentTimeMillis())
                logCacheEvent(
                    "foreground_stream_join_disk_hit",
                    manga,
                    image,
                    true,
                    "bytes=${cached.length()},waitMs=${SystemClock.elapsedRealtime() - waitStartedAt}"
                )
                ViewerWarmupManager.logMetric("reader_foreground_stream_join_disk_hit", 1L)
                return decodeForegroundFile(
                    cached,
                    startedAt,
                    autoCut,
                    allowSplit,
                    targetWidth,
                    fastInitialGenerated = ntkGeneratedTarget(image)?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
                )
            }
            if (task.isDone) {
                decodeForegroundCachedFileIfPresent(
                    context,
                    manga,
                    image,
                    startedAt,
                    autoCut,
                    allowSplit,
                    targetWidth,
                    "foreground_stream_join_done"
                )?.let { return it }
                if (shouldPreferForegroundBytesForInitialGenerated(image)) {
                    decodeForegroundBytes(
                        task,
                        startedAt,
                        autoCut,
                        allowSplit,
                        targetWidth,
                        boundedWait = false,
                        fastInitialGenerated = true
                    )?.let { return it }
                }
                return decodeForegroundBytes(
                    task,
                    startedAt,
                    autoCut,
                    allowSplit,
                    targetWidth,
                    boundedWait = false,
                    fastInitialGenerated = ntkGeneratedTarget(image)?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
                )
            }
            val elapsed = SystemClock.elapsedRealtime() - waitStartedAt
            val remaining = waitTimeoutMs - elapsed
            if (remaining <= 0L) {
                ViewerWarmupManager.logMetric("reader_foreground_stream_join_timeout", 1L)
                return null
            }
            try {
                val bytes = task.get(minOf(32L, remaining), TimeUnit.MILLISECONDS)
                if (bytes != null) {
                    decodeForegroundCachedFileIfPresent(
                        context,
                        manga,
                        image,
                        startedAt,
                        autoCut,
                        allowSplit,
                        targetWidth,
                        "foreground_stream_join_completed"
                    )?.let { return it }
                    if (shouldPreferForegroundBytesForInitialGenerated(image)) {
                        val bitmap = decodeForegroundBytes(
                            task,
                            startedAt,
                            autoCut,
                            allowSplit,
                            targetWidth,
                            boundedWait = false,
                            fastInitialGenerated = true
                        )
                        if (bitmap != null) return bitmap
                    }
                    val bitmap = decodeForegroundBytes(
                        task,
                        startedAt,
                        autoCut,
                        allowSplit,
                        targetWidth,
                        boundedWait = false,
                        fastInitialGenerated = ntkGeneratedTarget(image)?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
                    )
                    if (bitmap != null) return bitmap
                }
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

    private fun shouldPreferForegroundBytesForInitialGenerated(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
    }

    private fun decodeForegroundCachedFileIfPresent(
        context: Context,
        manga: Manga,
        image: String,
        startedAt: Long,
        autoCut: Boolean,
        allowSplit: Boolean,
        targetWidth: Int,
        stage: String
    ): Bitmap? {
        cachedAnchorAssetFile(context, manga, image, foreground = true)?.let { cached ->
            logCacheEvent(stage + "_anchor_asset_hit", manga, image, true, "bytes=${cached.length()}")
            ViewerWarmupManager.logMetric("reader_" + stage + "_anchor_asset_hit", 1L)
            rememberNtkGeneratedResolvedPage(image, stage + "_anchor_asset_hit")
            return decodeForegroundFile(
                cached,
                startedAt,
                autoCut,
                allowSplit,
                targetWidth,
                fastInitialGenerated = ntkGeneratedTarget(image)?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
            )
        }
        cachedImageFile(context, manga, image)?.let { cached ->
            cached.setLastModified(System.currentTimeMillis())
            logCacheEvent(stage + "_disk_hit", manga, image, true, "bytes=${cached.length()}")
            ViewerWarmupManager.logMetric("reader_" + stage + "_disk_hit", 1L)
            rememberNtkGeneratedResolvedPage(image, stage + "_disk_hit")
            return decodeForegroundFile(
                cached,
                startedAt,
                autoCut,
                allowSplit,
                targetWidth,
                fastInitialGenerated = ntkGeneratedTarget(image)?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
            )
        }
        return null
    }

    private fun decodeForegroundFile(
        file: File,
        startedAt: Long,
        autoCut: Boolean,
        allowSplit: Boolean,
        targetWidth: Int,
        fastInitialGenerated: Boolean = false
    ): Bitmap? {
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        val bytesAt = SystemClock.elapsedRealtime()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!fastInitialGenerated && shouldPreferFileDecodeAfterForegroundCache(bounds, bytes.size, autoCut)) return null
        val decodeTargetWidth = foregroundDecodeTargetWidth(bounds.outWidth, bounds.outHeight, targetWidth, autoCut, allowSplit)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = if (fastInitialGenerated) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inSampleSize = foregroundSampleSize(bounds.outWidth, decodeTargetWidth, fastInitialGenerated)
            if (fastInitialGenerated) {
                applyInitialGeneratedDecodeScale(bounds.outWidth, decodeTargetWidth)
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val decodedAt = SystemClock.elapsedRealtime()
        ViewerWarmupManager.logMetric("reader_foreground_stream_file_decode_ms", decodedAt - bytesAt)
        ViewerWarmupManager.logMetric("reader_foreground_stream_total_ms", decodedAt - startedAt)
        return bitmap
    }

    private fun foregroundStreamJoinTimeoutMs(image: String): Long {
        val target = ntkGeneratedTarget(image) ?: return FOREGROUND_STREAM_JOIN_TIMEOUT_MS
        if (target.page == 1) {
            return FOREGROUND_INITIAL_ANCHOR_STREAM_JOIN_TIMEOUT_MS
        }
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

    private fun cacheForegroundBytes(
        context: Context,
        manga: Manga,
        image: String,
        bytes: ByteArray,
        generation: Long,
        trustedImageBytes: Boolean = false
    ) {
        if (!writeForegroundBytesToCache(context, manga, image, bytes, generation, "foreground_cached", trustedImageBytes)) return
        rememberNtkGeneratedResolvedPage(image, "foreground_cached")
        publishNtkAnchorAssetFile(context, manga, image, "foreground_cached", trustedImageBytes)
        publishNtkInitialGeneratedAssetFile(context, manga, image, "foreground_cached", trustedImageBytes)
    }

    private fun BitmapFactory.Options.applyInitialGeneratedDecodeScale(sourceWidth: Int, targetWidth: Int) {
        if (sourceWidth <= 0 || targetWidth <= 0 || sourceWidth <= targetWidth) return
        inScaled = true
        inDensity = sourceWidth
        inTargetDensity = targetWidth
    }

    private fun publishNtkInitialGeneratedAssetFile(
        context: Context,
        manga: Manga,
        image: String,
        source: String,
        trustedImageBytes: Boolean = false
    ) {
        val assetKey = ntkInitialGeneratedAssetKey(manga, image) ?: return
        val file = File(cacheDir(context), "${key(manga.baseMode, image)}.img")
        if (trustedImageBytes) {
            if (!file.isFile || file.length() < 32L) return
        } else if (!isUsableImage(file)) {
            return
        }
        val previous = ntkInitialGeneratedAssetFiles.put(assetKey, file)
        if (previous?.absolutePath == file.absolutePath) return
        logCacheEvent(
            "ntk_initial_generated_asset_stream_satisfied",
            manga,
            image,
            true,
            "asset=$assetKey,source=$source,bytes=${file.length()}"
        )
        ntkInitialGeneratedAssetListeners.remove(assetKey)?.let { listeners ->
            logCacheEvent(
                "ntk_initial_generated_asset_listener_fire",
                manga,
                image,
                true,
                "asset=$assetKey,count=${listeners.size},source=$source"
            )
            for (listener in listeners) foregroundRaceExecutor.execute(listener)
        }
    }

    private fun publishNtkAnchorAssetFile(
        context: Context,
        manga: Manga,
        image: String,
        source: String,
        trustedImageBytes: Boolean = false
    ) {
        val assetKey = ntkAnchorAssetKey(manga, image) ?: return
        val file = File(cacheDir(context), "${key(manga.baseMode, image)}.img")
        if (trustedImageBytes) {
            if (!file.isFile || file.length() < 32L) return
        } else if (!isUsableImage(file)) {
            return
        }
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
        ntkAnchorAssetListeners.remove(assetKey)?.let { listeners ->
            logCacheEvent(
                "ntk_anchor_asset_listener_fire",
                manga,
                image,
                true,
                "asset=$assetKey,count=${listeners.size},source=$source"
            )
            for (listener in listeners) foregroundRaceExecutor.execute(listener)
        }
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

    private fun cachedInitialGeneratedAssetFile(
        context: Context,
        manga: Manga,
        image: String,
        foreground: Boolean
    ): File? {
        val assetKey = ntkInitialGeneratedAssetKey(manga, image) ?: return null
        val file = ntkInitialGeneratedAssetFiles[assetKey] ?: return null
        if (!file.isFile || file.length() < 32L) {
            ntkInitialGeneratedAssetFiles.remove(assetKey, file)
            return null
        }
        logCacheEvent(
            "ntk_initial_generated_asset_cached_hit",
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

    private fun ntkInitialGeneratedAssetKey(manga: Manga, image: String): String? {
        val target = ntkGeneratedTarget(image) ?: return null
        if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return null
        val path = earlyNtkPathKey(manga.ntkEpisodePath)
        if (path.isEmpty()) return null
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return null
        return "${manga.baseMode}|$path|${target.page}"
    }

    private fun writeForegroundBytesToCache(
        context: Context,
        manga: Manga,
        image: String,
        bytes: ByteArray,
        generation: Long,
        stage: String,
        trustedImageBytes: Boolean = false
    ): Boolean {
        if (bytes.isEmpty()) return false
        if (trustedImageBytes && !looksLikeImage(bytes)) return false
        if (generation != cacheGeneration.get()) {
            logCacheEvent("foreground_cache_generation_skip", manga, image, true, "generation=$generation,current=${cacheGeneration.get()}")
            return false
        }
        val finalFile = File(cacheDir(context), "${key(manga.baseMode, image)}.img")
        if (isUsableImage(finalFile)) return true
        val tmp = File(finalFile.parentFile, "${finalFile.name}.fg.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!trustedImageBytes && !isUsableImage(tmp)) {
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
                !isDisallowedNtkImageAssetUrl(actual) ||
                isTrustedNtkGeneratedStreamResponse(image, actual)
        } else {
            (isTrustedNtkImageUrl(actual) || isNaverWebtoonPageImage(actual)) &&
                !isDisallowedNtkImageAssetUrl(actual)
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

    private fun isTrustedNtkGeneratedStreamResponse(requested: String, actual: String): Boolean {
        if (ntkGeneratedTarget(requested) == null || isDisallowedNtkImageAssetUrl(actual)) return false
        return try {
            val actualUri = Uri.parse(actual)
            val actualPath = actualUri.path.orEmpty()
            actualPath.substringAfterLast('/').matches(
                Regex("stream\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
            )
        } catch (_: Exception) {
            false
        }
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

    private fun foregroundSampleSize(
        sourceWidth: Int,
        targetWidth: Int,
        fastInitialGenerated: Boolean = false
    ): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        if (fastInitialGenerated && sourceWidth > targetWidth) sample = max(sample, 2)
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
        val streamKey = foregroundStreamKey(manga, image)
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
                visiblePriority,
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
        val hinted = ntkGeneratedImageWithHintedExtension(image)
        if (hinted != image && !isAllowedInitialJpgHedgeAgainstJpegHint(image)) {
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
        visiblePriority: Boolean,
        priorityFullDownload: Boolean,
        freshInitialForegroundStream: Boolean,
        existing: FutureTask<File>
    ): Boolean {
        if (!foreground || (!visiblePriority && !priorityFullDownload)) return false
        if (existing.isDone || existing.isCancelled) return false
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return false
        return !freshInitialForegroundStream
    }

    private fun foregroundStreamJoinBudgetMs(streamKey: String, image: String, requestStartedAtMs: Long): Long {
        val baseTimeoutMs = foregroundStreamJoinTimeoutMs(image)
        val target = ntkGeneratedTarget(image)
        val streamStartedAt = foregroundStreamStartedAt[streamKey] ?: return baseTimeoutMs
        val streamAgeMs = max(0L, requestStartedAtMs - streamStartedAt)
        if (target != null && target.page >= 1) {
            return if (streamAgeMs < FOREGROUND_STREAM_STALE_MS) baseTimeoutMs else 0L
        }
        return max(0L, baseTimeoutMs - streamAgeMs)
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
        if (!ntkGeneratedTargetMatchesMangaEpisode(manga, target)) return false
        val dir = cacheDirectory ?: return true
        if (isUsableImage(File(dir, "${key(manga.baseMode, image)}.img"))) return false
        val assetKey = ntkInitialGeneratedAssetKey(manga, image)
        val assetFile = assetKey?.let { ntkInitialGeneratedAssetFiles[it] }
        return assetFile == null || !assetFile.isFile || assetFile.length() < 32L
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

    private fun foregroundStreamKey(manga: Manga, image: String): String {
        return foregroundStreamKey(manga.baseMode, image, manga.ntkEpisodePath)
    }

    private fun foregroundStreamKey(baseMode: Int, image: String, activePath: String? = null): String {
        val target = ntkGeneratedTarget(image) ?: return key(baseMode, image)
        val extension = Regex("\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            .orEmpty()
        val path = foregroundStreamEpisodePath(activePath, target)
        return "ntk-generated-stream|$baseMode|$path|${target.page}|$extension"
    }

    private fun foregroundStreamEpisodePath(activePath: String?, target: NtkGeneratedTarget): String {
        val keepPath = earlyNtkPathKey(activePath)
        if (keepPath.isBlank() || !isNtkEpisodePathKey(keepPath)) return target.path
        val activeParts = keepPath.trim('/').split('/')
        val targetParts = target.path.trim('/').split('/')
        if (activeParts.size < 3 || targetParts.size < 3) return target.path
        if (!activeParts[0].equals(targetParts[0], ignoreCase = true)) return target.path
        return if (activeParts[2] == targetParts[2]) keepPath else target.path
    }

    fun hasActiveInitialNtkForegroundStream(path: String?, baseMode: Int): Boolean {
        if (path.isNullOrBlank()) return false
        return earlyNtkImageUrls(path, 0L).any { image ->
            val target = ntkGeneratedTarget(image) ?: return@any false
            if (target.page != 1) return@any false
            val stream = foregroundStreams[foregroundStreamKey(baseMode, image, path)]
                ?: foregroundStreams[foregroundStreamKey(baseMode, image)]
                ?: return@any false
            !stream.isDone
        }
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
            Regex("^apihost\\d*\\.com/").containsMatchIn(lower) ||
            Regex("^//apihost\\d*\\.com/").containsMatchIn(lower) ||
            lower.startsWith("moamoabon.com/") ||
            lower.startsWith("//moamoabon.com/") ||
            Regex("^fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            Regex("^//fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            Regex("^aws-cdn\\d*\\.site/").containsMatchIn(lower) ||
            Regex("^//aws-cdn\\d*\\.site/").containsMatchIn(lower) ||
            Regex("^[a-z0-9-]+\\.worldcup\\d+\\.xyz/").containsMatchIn(lower) ||
            Regex("^//[a-z0-9-]+\\.worldcup\\d+\\.xyz/").containsMatchIn(lower)
        ) return true
        return try {
            val parsed = Uri.parse(value)
            val host = parsed.host?.lowercase().orEmpty()
            val path = parsed.path?.lowercase().orEmpty()
            if (!host.contains("naver") &&
                !host.contains("pstatic") &&
                isSafeNtkPageImagePath(path)
            ) return true
            host == "toonflix.app" || host.endsWith(".toonflix.app") ||
                Regex("^flysky\\d*m\\.com$").matches(host) ||
                Regex("^apihost\\d*\\.com$").matches(host) ||
                host == "moamoabon.com" ||
                Regex("^fvcdn\\d*\\.com$").matches(host) ||
                Regex("^aws-cdn\\d*\\.site$").matches(host) ||
                Regex("^[a-z0-9-]+\\.worldcup\\d+\\.xyz$").matches(host)
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
        val safePagePath = try {
            isSafeNtkPageImagePath(Uri.parse(value).path?.lowercase().orEmpty())
        } catch (_: Exception) {
            false
        }
        return ((lower.contains("://toonflix.app/") ||
            lower.contains("://i.toonflix.app/") ||
            Regex("://flysky\\d*m\\.com/").containsMatchIn(lower) ||
            Regex("://apihost\\d*\\.com/").containsMatchIn(lower) ||
            lower.contains("://moamoabon.com/") ||
            Regex("://fvcdn\\d*\\.com/").containsMatchIn(lower) ||
            Regex("://aws-cdn\\d*\\.site/").containsMatchIn(lower) ||
            safePagePath) &&
            (
                Regex("/(manhwa|webtoon)/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/black/episodes/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/blacktoon/episodes/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/wt/episodes/[^/?#]+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower)
                ))
    }

    private fun isFastOkHttpGeneratedImageUrl(value: String): Boolean {
        return try {
            val host = Uri.parse(value).host?.lowercase().orEmpty()
            Regex("^[a-z0-9-]+\\.worldcup\\d+\\.xyz$").matches(host) ||
                Regex("^aws-cdn\\d*\\.site$").matches(host)
        } catch (_: Exception) {
            false
        }
    }

    private fun isPlainHttpGeneratedImageUrl(value: String): Boolean {
        return try {
            val uri = Uri.parse(value)
            uri.scheme.equals("http", ignoreCase = true) && isNtkGeneratedImageUrl(value)
        } catch (_: Exception) {
            false
        }
    }

    private fun isSafeNtkPageImagePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.contains("/cdn-cgi/") ||
            path.contains("/challenge") ||
            path.contains("/turnstile") ||
            path.contains("/cloudflare") ||
            path.contains("/verification") ||
            path.contains("/captcha") ||
            path.contains("/banner") ||
            path.contains("/advert") ||
            path.contains("/sponsor") ||
            path.contains("/popup") ||
            path.contains("/ads/") ||
            path.contains("/ad/")
        ) return false
        return Regex("/(manhwa|webtoon)/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)$").containsMatchIn(path) ||
            Regex("/black/episodes/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)$").containsMatchIn(path) ||
            Regex("/blacktoon/episodes/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)$").containsMatchIn(path) ||
            Regex("/wt/episodes/[^/?#]+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)$").containsMatchIn(path)
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
        val initialForeground = foreground &&
            target != null &&
            target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
        if (initialForeground &&
            visiblePriority &&
            target?.page == 1 &&
            isFastOkHttpGeneratedImageUrl(requestUrl)
        ) {
            logCacheEvent(
                "ntk_generated_fetch_gate_bypass_fast_okhttp_visible",
                manga,
                image,
                foreground,
                "page=${target?.page ?: 0}"
            )
            return block()
        }
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
        val path = ntkFallbackKeyPath(manga, target)
        val client = try {
            getHttpClient()
        } catch (_: Throwable) {
            return
        }
        val expectedCount = when {
            manga.ntkImageCount > 0 -> manga.ntkImageCount
            target.page > 0 -> target.page
            else -> 1
        }
        val hasVerifiedSnapshot = hasAuthoritativeCompleteEarlyNtkImageUrls(
            path,
            expectedCount,
            SystemClock.elapsedRealtime() - 30_000L
        )
        val hasCompleteDirectManifest = hasCompleteDirectNtkManifestForPage(
            path,
            expectedCount,
            target,
            image
        )
        val hasStrictAck = try {
            client.hasRecentStrictNtkAdAckProof(path)
        } catch (_: Throwable) {
            false
        }
        if (foreground && visiblePriority && (hasVerifiedSnapshot || hasCompleteDirectManifest || hasStrictAck)) {
            logCacheEvent(
                "ntk_generated_ack_gate_bypass_verified_visible",
                manga,
                image,
                foreground,
                "page=${target.page},visiblePriority=true,verified=$hasVerifiedSnapshot," +
                    "directManifest=$hasCompleteDirectManifest,strictAck=$hasStrictAck"
            )
            return
        }
        if (!hasVerifiedSnapshot && !hasCompleteDirectManifest && !hasStrictAck) {
            logCacheEvent(
                "ntk_generated_ack_gate_block_unverified_visible",
                manga,
                image,
                foreground,
                "page=${target.page},visiblePriority=$visiblePriority," +
                    "expected=$expectedCount,known=${earlyNtkImageUrls(path, 0L).size}"
            )
            throw InterruptedIOException("Waiting for verified NTK snapshot")
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
        val activeCallKey = trackActiveNtkEpisodeCall(manga, image, call)
        cancellation?.track(call)
        return try {
            call.execute()
        } finally {
            cancellation?.untrack(call)
            untrackActiveNtkEpisodeCall(activeCallKey)
        }
    }

    private fun requestFor(
        manga: Manga,
        image: String,
        foregroundPriority: Boolean = false,
        anchorHedge: Boolean = false
    ): Request {
        val requestBuilder = Request.Builder().url(Utils.viewerImageRequestUrl(image, manga.baseMode))
        val ntkEpisodeReferer = ntkEpisodeImageReferer(manga, image)
        val ntkEpisodePath = manga.ntkEpisodePath?.trim().orEmpty()
        val ntkEpisodeCookie = if (ntkEpisodeReferer != null && !isNaverWebtoonPageImage(image)) {
            getHttpClient().getMergedNtkViewerImageCookieHeaderForPath(ntkEpisodePath)
        } else {
            ""
        }
        for (entry in Utils.viewerImageRequestHeaders(image, manga.baseMode).entries) {
            if (ntkEpisodeReferer != null && entry.key.equals("Referer", ignoreCase = true)) {
                requestBuilder.addHeader(entry.key, ntkEpisodeReferer)
            } else if (ntkEpisodeCookie.isNotBlank() && entry.key.equals("Cookie", ignoreCase = true)) {
                continue
            } else {
                requestBuilder.addHeader(entry.key, entry.value)
            }
        }
        if (ntkEpisodeCookie.isNotBlank()) {
            requestBuilder.addHeader("Cookie", ntkEpisodeCookie)
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

    private fun ntkEpisodeImageReferer(manga: Manga, image: String): String? {
        val requestUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
        naverWebtoonImageReferer(requestUrl)?.let { return it }
        if (ntkGeneratedTarget(image) == null && !isTrustedNtkImageUrl(requestUrl)) {
            return null
        }
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")) return null
        val root = try {
            getHttpClient().getUrl(manga.baseMode)
        } catch (_: Throwable) {
            ""
        }.trimEnd('/')
        if (!root.startsWith("http")) return null
        return root + ntkEncodedPath(path)
    }

    private fun ntkEncodedPath(path: String): String {
        val queryIndex = path.indexOf('?')
        val rawPath = if (queryIndex >= 0) path.substring(0, queryIndex) else path
        val suffix = if (queryIndex >= 0) path.substring(queryIndex) else ""
        val normalized = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        return normalized
            .split("/")
            .joinToString("/") { segment ->
                if (segment.isEmpty()) "" else Uri.encode(Uri.decode(segment))
            } + suffix
    }

    private fun naverWebtoonImageReferer(image: String): String? {
        if (!isNaverWebtoonPageImage(image)) return null
        return try {
            val uri = Uri.parse(image)
            val segments = uri.pathSegments
            val webtoonIndex = segments.indexOf("webtoon")
            if (webtoonIndex < 0 || segments.size <= webtoonIndex + 2) return null
            val titleId = segments[webtoonIndex + 1]
            val episodeNo = segments[webtoonIndex + 2]
            if (!titleId.matches(Regex("\\d{5,}")) || !episodeNo.matches(Regex("\\d+"))) return null
            "https://comic.naver.com/webtoon/detail?titleId=$titleId&no=$episodeNo"
        } catch (_: Throwable) {
            null
        }
    }

    private fun isNaverWebtoonPageImage(image: String): Boolean {
        return try {
            val uri = Uri.parse(Utils.viewerImageRequestUrl(image, 0))
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty().lowercase()
            host == "image-comic.pstatic.net" &&
                path.matches(Regex("^/webtoon/\\d{5,}/\\d+/[^/?#]+\\.(jpg|jpeg|png|webp)$")) &&
                !path.contains("thumbnail") &&
                !path.contains("/ad/") &&
                !path.contains("/ads/")
        } catch (_: Throwable) {
            false
        }
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
        if (initialTarget != null && hasNtkGeneratedHardBlocked(manga, image)) {
            throw IOException("Generated image Cloudflare hard block: page=${initialTarget.page} code=403")
        }
        if (initialTarget != null && hasNtkGeneratedNotFound(manga, image)) {
            val hintedNotFoundReplacement = ntkGeneratedImageWithHintedExtension(image)
            if (hintedNotFoundReplacement != image) {
                logCacheEvent(
                    "generated_not_found_retry_hinted_extension",
                    manga,
                    hintedNotFoundReplacement,
                    foreground,
                    "page=${initialTarget.page},source=${image.substringAfterLast('/').takeLast(64)}"
                )
                return requestWithNtkGeneratedFallback(
                    context,
                    manga,
                    hintedNotFoundReplacement,
                    foreground,
                    cancellation,
                    foregroundRaceAttempts,
                    anchorHedge,
                    skipInitialForegroundGeneratedRetryOnActiveFlight
                )
            }
            throw IOException("Generated image not found: page=${initialTarget.page} code=404")
        }
        val foregroundApiFallbackTask: FutureTask<List<String>?>? =
            if (foreground && initialTarget != null)
                startNtkApiFallbackImages(context, manga, initialTarget)
            else null
        val hintedImage = ntkGeneratedImageWithHintedExtension(image)
        if (hintedImage != image && !isAllowedInitialJpgHedgeAgainstJpegHint(image)) {
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
                val hardBlock = ntkImageHardBlock(rangeResponse)
                if (hardBlock.isNotEmpty()) {
                    rememberNtkGeneratedHardBlocked(
                        manga,
                        image,
                        "generated_initial_range_first_${rangeResponse.code}_$hardBlock"
                    )
                }
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
        val initialFailureCode = imageFailureCode(initialFailure)
        if (response == null && foreground && isNtkGeneratedChallengeBlockCode(initialFailureCode)) {
            val target = ntkGeneratedTarget(image)
            logCacheEvent(
                "generated_direct_blocked_api_only",
                manga,
                image,
                true,
                "page=${target?.page ?: 0},code=$initialFailureCode"
            )
            val apiFallbackTask = foregroundApiFallbackTask ?: target?.let {
                startNtkApiFallbackImages(context, manga, it)
            }
            if (apiFallbackTask != null) {
                requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                    ?.let { return it }
                retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                    ?.let { return it }
            }
            throw IOException("Generated direct image blocked before API fallback: code=$initialFailureCode image=$image")
        }
        if (response != null && response.isSuccessful &&
            acceptNtkGeneratedForegroundResponse(manga, image, response, foreground)
        ) {
            rememberNtkGeneratedEpisodeExtension(response.request.url.toString())
            return response
        }
        val responseFailureCode = response?.code ?: 0
        if (response != null && foreground && isNtkGeneratedChallengeBlockCode(responseFailureCode)) {
            val target = ntkGeneratedTarget(image)
            logCacheEvent(
                "generated_direct_blocked_response_api_only",
                manga,
                image,
                true,
                "page=${target?.page ?: 0},code=$responseFailureCode"
            )
            response.close()
            val apiFallbackTask = foregroundApiFallbackTask ?: target?.let {
                startNtkApiFallbackImages(context, manga, it)
            }
            if (apiFallbackTask != null) {
                requestForegroundGeneratedRace(context, manga, image, apiFallbackTask, includeDirectRetries = false)
                    ?.let { return it }
                retryNtkGeneratedViaApiFallback(context, manga, image, foreground, apiFallbackTask, cancellation)
                    ?.let { return it }
            }
            throw IOException("Generated direct image blocked before API fallback: code=$responseFailureCode image=$image")
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
        if (shouldUseRangeFirstForInitialGenerated(image, target)) return true
        if (target.page == 1 && isSupportedNtkGeneratedImageExtension(image)) return false
        return target.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES &&
            ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun shouldUseRangeFirstForInitialGenerated(
        image: String,
        target: NtkGeneratedTarget
    ): Boolean {
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        if (!isSupportedNtkGeneratedImageExtension(image)) return false
        return target.path.startsWith("/manhwa/", ignoreCase = true) ||
            target.path.startsWith("/webtoon/", ignoreCase = true)
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
        val useDirectOnlyAnchor =
            foreground &&
                page <= 1 &&
                (image.contains("/wt/episodes/", ignoreCase = true) ||
                    image.contains("/black/episodes/", ignoreCase = true) ||
                    image.contains("/blacktoon/episodes/", ignoreCase = true))
        if (useDirectOnlyAnchor) {
            return try {
                logCacheEvent(
                    "${stage}_direct_only_start",
                    manga,
                    image,
                    true,
                    "page=$page"
                )
                requestForForegroundMode(
                    context,
                    manga,
                    image,
                    true,
                    cancellation,
                    foregroundRaceAttempts,
                    anchorHedge
                ).also {
                    if (it.isSuccessful) {
                        logCacheEvent(
                            "${stage}_direct_only_hit",
                            manga,
                            image,
                            true,
                            "page=$page,code=${it.code}"
                        )
                    }
                }
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                logCacheEvent(
                    "${stage}_direct_only_error",
                    manga,
                    image,
                    true,
                    "page=$page,error=${t.javaClass.simpleName},summary=${throwableSummary(t)}"
                )
                null
            }
        }
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
            return if (foreground) 0L else 0L
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
        val first = requestGeneratedFirstRangeChunkRace(
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
            val actualImage = firstResponse.request.url.toString()
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
                actualImage,
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
        if (page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES && requests.size > 1) {
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

    private fun requestGeneratedFirstRangeChunkRace(
        context: Context,
        manga: Manga,
        image: String,
        preferredBytes: Int,
        cancellation: Cancellation?,
        page: Int
    ): Response? {
        val candidates = if (page == 1) {
            ntkPreferredGeneratedForegroundCandidates(
                manga,
                image,
                NTK_GENERATED_INITIAL_COMPLETE_RACE_ANCHOR_CANDIDATES
            )
        } else {
            ntkEquivalentGeneratedCdnImages(image)
        }
        if (page != 1 || candidates.size <= 1) {
            return requestGeneratedFirstRangeChunk(context, manga, image, preferredBytes, cancellation, page)
        }
        val completion = ExecutorCompletionService<Pair<String, Response>?>(foregroundRaceExecutor)
        val futures = ArrayList<Future<Pair<String, Response>?>>(candidates.size)
        val startedAt = SystemClock.elapsedRealtime()
        for (candidate in candidates) {
            futures += completion.submit(Callable {
                cancellation?.throwIfCancelled()
                val response = requestGeneratedFirstRangeChunk(
                    context,
                    manga,
                    candidate,
                    preferredBytes,
                    cancellation,
                    page
                ) ?: return@Callable null
                if (response.code == 200 || response.code == 206) {
                    return@Callable candidate to response
                }
                response.close()
                null
            })
        }
        var firstFailure: Response? = null
        return try {
            repeat(futures.size) {
                val pair = completion.take().get()
                if (pair != null) {
                    firstFailure?.close()
                    logCacheEvent(
                        "generated_range_first_chunk_cdn_race_hit",
                        manga,
                        pair.first,
                        true,
                        "page=$page,ms=${SystemClock.elapsedRealtime() - startedAt},candidates=${candidates.size}"
                    )
                    return pair.second
                }
            }
            firstFailure
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            firstFailure?.close()
            null
        } catch (e: ExecutionException) {
            firstFailure?.close()
            null
        } finally {
            futures.forEach { future ->
                if (!future.isDone) future.cancel(true)
            }
        }
    }

    private fun requestGeneratedFirstRangeChunk(
        context: Context,
        manga: Manga,
        image: String,
        preferredBytes: Int,
        cancellation: Cancellation?,
        page: Int
    ): Response? {
        val attempts = intArrayOf(preferredBytes, 2048, 512, 32)
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
            if (isNtkGeneratedChallengeBlockCode(response.code)) {
                logCacheEvent(
                    "generated_range_first_chunk_blocked",
                    manga,
                    image,
                    true,
                    "bytes=$byteCount,code=${response.code}"
                )
                response.close()
                return null
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
            val hardBlock = ntkImageHardBlock(response)
            if (hardBlock.isNotEmpty()) {
                rememberNtkGeneratedHardBlocked(
                    manga,
                    image,
                    "generated_range_first_chunk_${response.code}_$hardBlock"
                )
                logCacheEvent(
                    "generated_range_first_chunk_hardblock",
                    manga,
                    image,
                    true,
                    "bytes=$byteCount,code=${response.code},block=$hardBlock"
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

    private fun ntkEquivalentGeneratedCdnImages(image: String): List<String> {
        val target = ntkGeneratedTarget(image) ?: return listOf(image)
        if (!target.path.startsWith("/webtoon/", ignoreCase = true)) return listOf(image)
        val uri = try {
            Uri.parse(image)
        } catch (_: Exception) {
            null
        } ?: return listOf(image)
        val path = uri.encodedPath.orEmpty()
        val blackSuffix: String
        val blacktoonSuffix: String
        if (path.contains("/black/episodes/", ignoreCase = true)) {
            blackSuffix = path.substring(path.indexOf("/black/episodes/"))
            blacktoonSuffix = blackSuffix.replaceFirst("/black/episodes/", "/blacktoon/episodes/", ignoreCase = true)
        } else if (path.contains("/blacktoon/episodes/", ignoreCase = true)) {
            blacktoonSuffix = path.substring(path.indexOf("/blacktoon/episodes/"))
            blackSuffix = blacktoonSuffix.replaceFirst("/blacktoon/episodes/", "/black/episodes/", ignoreCase = true)
        } else if (path.contains("/wt/episodes/", ignoreCase = true)) {
            val wtSuffix = path.substring(path.indexOf("/wt/episodes/", ignoreCase = true) + "/wt/episodes/".length)
            val parts = wtSuffix.split('/')
            if (parts.size < 3) return listOf(image)
            val workId = parts[0]
            val episodeId = parts[1]
            val pageName = parts.subList(2, parts.size).joinToString("/")
            if (!workId.matches(Regex("\\d{1,12}"))) {
                return listOf(image)
            }
            blackSuffix = "/black/episodes/$workId/$episodeId/$pageName"
            blacktoonSuffix = "/blacktoon/episodes/$workId/$episodeId/$pageName"
        } else if (target.path.startsWith("/webtoon/", ignoreCase = true)) {
            val targetParts = target.path.trim('/').split('/')
            if (targetParts.size < 3) return listOf(image)
            val workId = targetParts[1]
            val episodeId = targetParts[2]
            if (!workId.matches(Regex("\\d{1,12}"))) return listOf(image)
            val pageName = Regex("/(p\\d{3}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?)$", RegexOption.IGNORE_CASE)
                .find(path)
                ?.groupValues
                ?.getOrNull(1)
                ?: return listOf(image)
            blackSuffix = "/black/episodes/$workId/$episodeId/$pageName"
            blacktoonSuffix = "/blacktoon/episodes/$workId/$episodeId/$pageName"
        } else {
            return listOf(image)
        }
        val urls = arrayListOf(image)
        urls += "https://moamoabon.com$blacktoonSuffix"
        urls += "http://fifa.worldcup73.xyz$blackSuffix"
        urls += "http://aws-cdn1.site$blackSuffix"
        urls += "https://aws-cdn1.site$blackSuffix"
        urls += "https://fifa.worldcup73.xyz$blackSuffix"
        return urls.distinct()
    }

    private fun ntkEquivalentGeneratedCdnImagesForActiveEpisode(manga: Manga, image: String): List<String> {
        val urls = LinkedHashSet<String>()
        urls.addAll(ntkEquivalentGeneratedCdnImages(image))
        val target = ntkGeneratedTarget(image) ?: return urls.toList()
        if (!target.path.startsWith("/webtoon/", ignoreCase = true)) return urls.toList()
        val activePath = manga.ntkEpisodePath?.trim().orEmpty()
        val activeParts = activePath.trim('/').split('/')
        val targetParts = target.path.trim('/').split('/')
        if (activeParts.size < 3 || targetParts.size < 3) return urls.toList()
        if (!activeParts[0].equals("webtoon", ignoreCase = true)) return urls.toList()
        if (!activeParts[1].equals(targetParts[1], ignoreCase = true)) return urls.toList()
        val pathEpisodeId = activeParts[2]
        val recordedEpisodeId = manga.ntkImageEpisodeId.trim()
        if (!pathEpisodeId.matches(Regex("\\d{1,12}")) ||
            !recordedEpisodeId.matches(Regex("\\d{1,12}")) ||
            pathEpisodeId == recordedEpisodeId
        ) {
            return urls.toList()
        }
        val alternateImage = Regex(
            "(/(?:black(?:toon)?|wt)/episodes/${Regex.escape(targetParts[1])}/)" +
                "${Regex.escape(targetParts[2])}(/p\\d{3}\\.)",
            RegexOption.IGNORE_CASE
        ).replace(image, "$1$recordedEpisodeId$2")
        if (alternateImage != image) {
            urls.addAll(ntkEquivalentGeneratedCdnImages(alternateImage))
            logCacheEvent(
                "ntk_generated_episode_candidate_race",
                manga,
                image,
                true,
                "page=${target.page},pathEpisodeId=$pathEpisodeId," +
                    "recordedEpisodeId=$recordedEpisodeId,candidates=${urls.size}"
            )
        }
        return urls.toList()
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

    private fun responseFromBytes(source: Response, bytes: ByteArray, partialImage: Boolean = false): Response {
        val builder = source.newBuilder()
            .code(if (partialImage) 206 else 200)
            .message(if (partialImage) "Partial Content" else "OK")
            .protocol(source.protocol.takeIf { it != Protocol.HTTP_2 } ?: Protocol.HTTP_1_1)
            .removeHeader("Content-Encoding")
            .header("Content-Length", bytes.size.toString())
            .header(
                "x-mangaviewer-transport",
                if (partialImage) "generated-range-preview" else "generated-range"
            )
            .body(ResponseBody.create(source.body?.contentType(), bytes))
        if (partialImage) {
            builder.header("x-mangaviewer-partial-image", "1")
        } else {
            builder.removeHeader("Content-Range")
            builder.removeHeader("x-mangaviewer-partial-image")
            builder.header("x-mangaviewer-full-image", "1")
        }
        return builder.build()
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

    private fun isNtkGeneratedChallengeBlockCode(code: Int): Boolean {
        return code == 403 || code == 429 || code == 530
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
        ntkAckRecoveryPriorityBlocker(path)?.let { priority ->
            logCacheEvent(
                "generated_webview_ack_recovery_skip",
                manga,
                image,
                true,
                "path=$path,reason=current_ack_priority,current=$priority"
            )
            return null
        }
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
            val client = getHttpClient()
            if (!client.hasCloudflareClearance() && !client.hasRecentStrictNtkAdAckProof(path)) {
                logCacheEvent(
                    "generated_webview_ack_recovery_skip_no_clearance",
                    manga,
                    image,
                    true,
                    "path=$path,ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return@FutureTask false
            }
            val ok = try {
                client.performNtkWebViewAckPreflight(path)
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
        requestDirectInitialGeneratedForeground(context, manga, image, cancellation, anchorHedge)?.let {
            return it
        }
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

    private fun requestDirectInitialGeneratedForeground(
        context: Context,
        manga: Manga,
        image: String,
        cancellation: Cancellation?,
        anchorHedge: Boolean
    ): okhttp3.Response? {
        val target = ntkGeneratedTarget(image) ?: return null
        if (!target.path.startsWith("/webtoon/", ignoreCase = true) &&
            !target.path.startsWith("/manhwa/", ignoreCase = true)
        ) return null
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return null
        if (image.contains("/wt/episodes/", ignoreCase = true)) {
            logCacheEvent(
                "foreground_direct_initial_generated_defer_wt_race",
                manga,
                image,
                true,
                "page=${target.page}"
            )
            return null
        }
        val equivalentCandidates = ntkEquivalentGeneratedCdnImagesForActiveEpisode(manga, image)
            .filterNot { Uri.parse(it).host.equals("moamoabon.com", ignoreCase = true) }
            .distinct()
        if (equivalentCandidates.size > 1) {
            return requestDirectInitialGeneratedCandidateRace(
                manga,
                image,
                equivalentCandidates,
                cancellation,
                anchorHedge,
                target
            )
        }
        val startedAt = SystemClock.elapsedRealtime()
        val request = requestFor(manga, image, foregroundPriority = true, anchorHedge = anchorHedge)
        val call = getHttpClient().imageClient.newCall(request)
        val activeCallKey = trackActiveNtkEpisodeCall(manga, image, call)
        cancellation?.track(call)
        return try {
            val response = call.execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                val actualImage = response.request.url.toString()
                logCacheEvent(
                    "foreground_direct_initial_generated_hit",
                    manga,
                    image,
                    true,
                    "page=${target.page},code=${response.code},bytes=${bytes?.size ?: 0},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                if (bytes == null || bytes.isEmpty()) {
                    response.close()
                    null
                } else {
                    val cachedResponse = responseFromBytes(response, bytes)
                    logCacheEvent(
                        "foreground_direct_initial_generated_handoff",
                        manga,
                        image,
                        true,
                        "page=${target.page},bytes=${bytes.size},actual=${safeImageName(actualImage)}"
                    )
                    foregroundCachePublishExecutor.execute {
                        try {
                            response.close()
                        } catch (t: Throwable) {
                            rethrowIfFatal(t)
                        }
                        rememberNtkGeneratedEpisodeExtension(actualImage)
                        rememberEarlyNtkGeneratedSuccess(manga, actualImage)
                    }
                    cachedResponse
                }
            } else {
                if (isPermanentGeneratedMissingCode(response.code)) {
                    rememberNtkGeneratedNotFound(
                        manga,
                        response.request.url.toString(),
                        "foreground_direct_initial_generated"
                    )
                }
                logCacheEvent(
                    "foreground_direct_initial_generated_miss",
                    manga,
                    image,
                    true,
                    "page=${target.page},code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                )
                response.close()
                null
            }
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "foreground_direct_initial_generated_error",
                manga,
                image,
                true,
                "page=${target.page},error=${t.javaClass.simpleName},ms=${SystemClock.elapsedRealtime() - startedAt}," +
                    "summary=${throwableSummary(t)}"
            )
            null
        } finally {
            cancellation?.untrack(call)
            untrackActiveNtkEpisodeCall(activeCallKey)
        }
    }

    private fun requestDirectInitialGeneratedCandidateRace(
        manga: Manga,
        image: String,
        candidates: List<String>,
        cancellation: Cancellation?,
        anchorHedge: Boolean,
        target: NtkGeneratedTarget
    ): okhttp3.Response? {
        val completion = ExecutorCompletionService<Pair<String, okhttp3.Response>?>(foregroundRaceExecutor)
        val calls = Collections.synchronizedList(ArrayList<Call>())
        val futures = ArrayList<Future<Pair<String, okhttp3.Response>?>>(candidates.size)
        val startedAt = SystemClock.elapsedRealtime()
        logCacheEvent(
            "foreground_direct_initial_generated_cdn_race_start",
            manga,
            image,
            true,
            "page=${target.page},candidates=${candidates.size}"
        )
        for (candidate in candidates) {
            futures += completion.submit(Callable {
                cancellation?.throwIfCancelled()
                val request = requestFor(manga, candidate, foregroundPriority = true, anchorHedge = anchorHedge)
                val call = getHttpClient().imageClient.newCall(request)
                val activeCallKey = trackActiveNtkEpisodeCall(manga, candidate, call)
                cancellation?.track(call)
                calls.add(call)
                try {
                    val response = call.execute()
                    if (response.isSuccessful &&
                        validateNtkImageResponseUrl(manga, image, response, true, "foreground_direct_initial_generated_cdn_race")
                    ) {
                        candidate to response
                    } else {
                        if (isPermanentGeneratedMissingCode(response.code)) {
                            rememberNtkGeneratedNotFound(
                                manga,
                                response.request.url.toString(),
                                "foreground_direct_initial_generated_cdn_race"
                            )
                        }
                        logCacheEvent(
                            "foreground_direct_initial_generated_cdn_race_miss",
                            manga,
                            candidate,
                            true,
                            "page=${target.page},code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        response.close()
                        null
                    }
                } finally {
                    cancellation?.untrack(call)
                    untrackActiveNtkEpisodeCall(activeCallKey)
                }
            })
        }
        return try {
            repeat(futures.size) {
                val result = completion.take().get()
                if (result != null) {
                    logCacheEvent(
                        "foreground_direct_initial_generated_cdn_race_hit",
                        manga,
                        result.first,
                        true,
                        "page=${target.page},ms=${SystemClock.elapsedRealtime() - startedAt},candidates=${candidates.size}"
                    )
                    return result.second
                }
            }
            null
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            logCacheEvent(
                "foreground_direct_initial_generated_cdn_race_error",
                manga,
                image,
                true,
                "page=${target.page},error=${t.javaClass.simpleName},ms=${SystemClock.elapsedRealtime() - startedAt}," +
                    "summary=${throwableSummary(t)}"
            )
            null
        } finally {
            futures.forEach { future ->
                if (!future.isDone) future.cancel(true)
            }
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
        val attempts = foregroundRaceAttempts(manga, foregroundRequest, raceAttempts, firstEarlyNtkImage)
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
                val activeCallKey = trackActiveNtkEpisodeCall(manga, image, call)
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
                    untrackActiveNtkEpisodeCall(activeCallKey)
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
        val generatedTarget = ntkGeneratedTarget(image)
        val wtEpisodeImage = image.contains("/wt/episodes/", ignoreCase = true)
        val includeGeneratedDirectLane = generatedTarget != null &&
            !wtEpisodeImage &&
            generatedTarget.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES
        if (generatedTarget != null && !includeGeneratedDirectLane) {
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
        attempts.forEachIndexed { index, attempt ->
            val delayMs = if (firstEarlyNtkImage) 0L else FOREGROUND_RACE_DELAY_MS * index
            submit(attempt, delayMs)
        }
        val generatedAnchor = generatedTarget?.page == 1
        val generatedFastFallback = generatedAnchor && !includeGeneratedDirectLane && !wtEpisodeImage
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
                    if (generatedFastFallback) {
                        for (call in calls) call.cancel()
                        throw IOException("Foreground generated anchor race timed out before fallback")
                    }
                    future = completion.take()
                }
                val result = future.get()
                val response = result.response
                if (response.isSuccessful) {
                    if (!validateNtkImageResponseUrl(manga, image, response, true, "foreground_race")) {
                        failure = IOException("Rejected foreground race image URL")
                        logCacheEvent(
                            "foreground_race_untrusted_actual_miss",
                            manga,
                            image,
                            true,
                            "transport=${result.attempt.transport},completed=$completedIndex,total=$totalAttempts," +
                                "code=${response.code},actual=${safeImageName(response.request.url.toString())}," +
                                "ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                        response.close()
                        return@repeat
                    }
                    if (response.header("x-mangaviewer-partial-image") == "1") {
                        logCacheEvent(
                            "foreground_race_partial_win",
                            manga,
                            image,
                            true,
                            "transport=${result.attempt.transport},completed=$completedIndex,total=$totalAttempts,code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
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
                if (isPermanentGeneratedMissingCode(response.code) &&
                    completedIndex >= totalAttempts - 1
                ) {
                    rememberNtkGeneratedNotFound(manga, response.request.url.toString(), "foreground_race")
                }
                val hardBlock = ntkImageHardBlock(response)
                if (hardBlock.isNotEmpty()) {
                    rememberNtkGeneratedHardBlocked(
                        manga,
                        response.request.url.toString(),
                        "foreground_race_${response.code}_$hardBlock"
                    )
                }
                logCacheEvent(
                    "foreground_race_miss",
                    manga,
                    image,
                    true,
                    "transport=${result.attempt.transport},completed=$completedIndex,total=$totalAttempts,code=${response.code},ms=${SystemClock.elapsedRealtime() - startedAt}"
                        + ",block=$hardBlock"
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

    private fun ntkImageHardBlock(response: Response): String {
        response.header("x-mangaviewer-ntk-image-hard-block")?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        if (response.code != 403) return ""
        val contentType = response.header("content-type").orEmpty().lowercase()
        if (!contentType.contains("text/html")) return ""
        val cloudflare = response.header("server").orEmpty().lowercase().contains("cloudflare") ||
            response.header("cf-ray").orEmpty().isNotEmpty() ||
            response.header("cf-cache-status").orEmpty().isNotEmpty()
        return if (cloudflare) "cloudflare-html-403" else ""
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
        val request = requestFor(manga, image, foregroundPriority = true, anchorHedge = false)
        val call = getHttpClient().ntkForegroundImageFastClient().newCall(request)
        val activeCallKey = trackActiveNtkEpisodeCall(manga, image, call)
        cancellation?.track(call)
        return try {
            call.execute()
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            null
        } finally {
            cancellation?.untrack(call)
            untrackActiveNtkEpisodeCall(activeCallKey)
        }
    }

    private fun foregroundRaceAttempts(
        manga: Manga,
        foregroundRequest: Request,
        raceAttempts: Int,
        firstEarlyNtkImage: Boolean
    ): List<ForegroundRaceAttempt> {
        val httpClient = getHttpClient()
        val attempts = raceAttempts.coerceIn(1, 2)
        val generatedTarget = ntkGeneratedTarget(foregroundRequest.url.toString())
        val imageRaceClient = if (generatedTarget != null) {
            httpClient.ntkForegroundImageFastClient()
        } else {
            httpClient.imageClient
        }
        if (isNaverWebtoonPageImage(foregroundRequest.url.toString())) {
            val directRequest = foregroundRequest.newBuilder()
                .removeHeader("X-MangaViewer-Foreground")
                .header("X-MangaViewer-No-Quic", "1")
                .build()
            return listOf(
                ForegroundRaceAttempt(
                    "naver-image-fast",
                    httpClient.externalViewerImageFastClient(),
                    directRequest
                ),
                ForegroundRaceAttempt("image-full", httpClient.imageClient, directRequest)
            )
        }
        if (generatedTarget != null &&
            foregroundRequest.url.toString().contains("/wt/episodes/", ignoreCase = true)
        ) {
            val equivalentCandidates = ntkPreferredGeneratedForegroundCandidates(
                manga,
                foregroundRequest.url.toString(),
                NTK_GENERATED_INITIAL_FOREGROUND_RACE_CANDIDATES
            )
            val blackEpisodeCandidates = equivalentCandidates.filter {
                it.contains("/black/episodes/", ignoreCase = true) ||
                    it.contains("/blacktoon/episodes/", ignoreCase = true)
            }
            if (blackEpisodeCandidates.isNotEmpty()) {
                val candidates = (blackEpisodeCandidates + equivalentCandidates)
                    .distinct()
                    .take(3)
                return candidates.mapIndexed { index, candidate ->
                    val request = foregroundRequest.newBuilder()
                        .url(candidate)
                        .removeHeader("X-MangaViewer-Foreground")
                        .header("X-MangaViewer-No-Quic", "1")
                        .build()
                    ForegroundRaceAttempt("generated-cdn-${index + 1}", imageRaceClient, request)
                }
            }
            val fullRequest = foregroundRequest.newBuilder()
                .removeHeader("X-MangaViewer-Foreground")
                .header("X-MangaViewer-No-Quic", "1")
                .build()
            return listOf(ForegroundRaceAttempt("image-full", imageRaceClient, fullRequest))
        }
        if (generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            val candidates = ntkPreferredGeneratedForegroundCandidates(
                manga,
                foregroundRequest.url.toString(),
                NTK_GENERATED_INITIAL_FOREGROUND_RACE_CANDIDATES
            )
            if (candidates.isNotEmpty()) {
                return candidates.mapIndexed { index, candidate ->
                    val request = foregroundRequest.newBuilder()
                        .url(candidate)
                        .removeHeader("X-MangaViewer-Foreground")
                        .header("X-MangaViewer-No-Quic", "1")
                        .build()
                    ForegroundRaceAttempt("generated-cdn-${index + 1}", imageRaceClient, request)
                }
            }
        }
        if (generatedTarget != null && generatedTarget.page > NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            val directRequest = foregroundRequest.newBuilder()
                .removeHeader("X-MangaViewer-Foreground")
                .header("X-MangaViewer-No-Quic", "1")
                .build()
            return listOf(ForegroundRaceAttempt("generated-single", imageRaceClient, directRequest))
        }
        val foregroundAttempts = List(attempts) { index ->
            ForegroundRaceAttempt("image-${index + 1}", imageRaceClient, foregroundRequest)
        }
        val fullRequest = foregroundRequest.newBuilder()
            .removeHeader("X-MangaViewer-Foreground")
            .header("X-MangaViewer-No-Quic", "1")
            .build()
        val fullAttempt = ForegroundRaceAttempt("image-full", imageRaceClient, fullRequest)
        if (generatedTarget?.page in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) {
            return listOf(fullAttempt) + foregroundAttempts.take(1)
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
        return ntkGeneratedTargetsSameEpisode(candidateTarget, target) && candidateTarget.page == target.page
    }

    private fun hasCompleteDirectNtkManifestForPage(
        path: String,
        expectedCount: Int,
        target: NtkGeneratedTarget,
        image: String
    ): Boolean {
        if (expectedCount <= 0) return false
        return try {
            val urls = earlyNtkImageUrls(path, 0L)
            if (urls.isEmpty()) return false
            val expectedPath = path.trim().trimStart('/')
            val requestedIdentity = normalizedNtkImageIdentity(image)
            val requiredCount = minOf(expectedCount, target.page.coerceAtLeast(1))
            if (urls.size < requiredCount) return false
            val expectedAtPage = urls.getOrNull(target.page - 1)
            if (
                expectedAtPage != null &&
                !isNtkProtectedViewerApiImage(expectedAtPage) &&
                (isTrustedNtkImageUrl(expectedAtPage) || isNaverWebtoonPageImage(expectedAtPage)) &&
                ntkDirectManifestPageMatches(expectedAtPage, expectedPath, target.page)
            ) {
                return true
            }
            if (
                expectedAtPage != null &&
                !isNtkProtectedViewerApiImage(expectedAtPage) &&
                normalizedNtkImageIdentity(expectedAtPage) == requestedIdentity
            ) {
                return true
            }
            for (candidate in urls.take(requiredCount)) {
                if (isNtkProtectedViewerApiImage(candidate)) return false
                val candidateIdentity = normalizedNtkImageIdentity(candidate)
                if (candidateIdentity == requestedIdentity) return true
                val candidateTarget = ntkGeneratedTarget(candidate)
                if (candidateTarget != null) {
                    if (!candidateTarget.path.trimStart('/').equals(expectedPath, ignoreCase = true) &&
                        !ntkGeneratedTargetsSameEpisode(candidateTarget, target)
                    ) {
                        return false
                    }
                    continue
                }
                if (!isTrustedNtkImageUrl(candidate) && !isNaverWebtoonPageImage(candidate)) return false
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun ntkDirectManifestPageMatches(image: String, expectedPath: String, page: Int): Boolean {
        return try {
            val path = Uri.parse(image).path?.trim('/')?.lowercase().orEmpty()
            if (path.isBlank()) return false
            val expected = expectedPath.trim('/').lowercase()
            val pageName = "p${page.coerceAtLeast(1).toString().padStart(3, '0')}"
            path.startsWith("$expected/") &&
                Regex("/${Regex.escape(pageName)}\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
                    .containsMatchIn("/$path")
        } catch (_: Throwable) {
            false
        }
    }

    private fun ntkGeneratedTargetMatchesMangaEpisode(
        manga: Manga,
        target: NtkGeneratedTarget
    ): Boolean {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isBlank()) return false
        if (target.path.equals(path, ignoreCase = true)) return true
        val activeParts = path.trim('/').split('/')
        val targetParts = target.path.trim('/').split('/')
        if (activeParts.size < 3 || targetParts.size < 3) return false
        if (!activeParts[0].equals(targetParts[0], ignoreCase = true)) return false
        return activeParts[2] == targetParts[2]
    }

    private fun ntkGeneratedTargetsSameEpisode(
        first: NtkGeneratedTarget,
        second: NtkGeneratedTarget
    ): Boolean {
        if (first.path.equals(second.path, ignoreCase = true)) return true
        val firstParts = first.path.trim('/').split('/')
        val secondParts = second.path.trim('/').split('/')
        if (firstParts.size < 3 || secondParts.size < 3) return false
        if (!firstParts[0].equals(secondParts[0], ignoreCase = true)) return false
        return firstParts[2] == secondParts[2]
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
                || Regex("://apihost\\d*\\.com/").containsMatchIn(lower)
                || lower.contains("://moamoabon.com/")
                || Regex("://fvcdn\\d*\\.com/").containsMatchIn(lower))
            && (
                Regex("/(manhwa|webtoon)/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
                    Regex("/black/episodes/\\d+/[^/?#]+/p\\d{3}\\.(jpg|jpeg|png|webp)(?:[?#].*)?$").containsMatchIn(lower) ||
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
        ntkProtectedApiGeneratedTarget(image)?.let { return it }
        val numericMatch = Regex("^(https?://[^/]+)/(manhwa|webtoon)/(\\d+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (numericMatch != null) {
            val segment = numericMatch.groupValues[2]
            val workId = numericMatch.groupValues[3]
            val episodeId = numericMatch.groupValues[4]
            val page = numericMatch.groupValues[5].toIntOrNull() ?: return null
            return NtkGeneratedTarget(numericMatch.groupValues[1], "/$segment/$workId/$episodeId", page)
        }
        val blacktoonMatch = Regex("^(https?://[^/]+)/(?:blacktoon|black)/episodes/(\\d+)/([^/?#]+)/p(\\d{3})\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
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

    private fun ntkProtectedApiGeneratedTarget(image: String): NtkGeneratedTarget? {
        return try {
            val uri = Uri.parse(image)
            if (!uri.path.equals("/api/m/i", ignoreCase = true)) return null
            val page = (uri.getQueryParameter("i")?.toIntOrNull() ?: return null) + 1
            val token = uri.getQueryParameter("a").orEmpty()
            val payload = token.substringBefore('.', "")
            if (payload.isEmpty()) return null
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = String(
                android.util.Base64.decode(
                    padded,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                ),
                Charsets.UTF_8
            )
            val scope = Regex("\"scope\"\\s*:\\s*\"([^\"]+)\"").find(json)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?: return null
            if (!scope.startsWith("/manhwa/") && !scope.startsWith("/webtoon/")) return null
            val base = "${uri.scheme}://${uri.host}"
            NtkGeneratedTarget(base, scope, page)
        } catch (_: Throwable) {
            null
        }
    }

    private fun canonicalNtkGeneratedImageForActiveEpisode(manga: Manga, image: String): String? {
        val target = ntkGeneratedTarget(image) ?: return null
        val activePath = manga.ntkEpisodePath?.trim().orEmpty()
        if (activePath.isBlank() || activePath == target.path) return null
        val activeParts = activePath.trim('/').split('/')
        val targetParts = target.path.trim('/').split('/')
        if (activeParts.size < 3 || targetParts.size < 3) return null
        if (!activeParts[0].equals(targetParts[0], ignoreCase = true)) return null
        if (isProtectedApiGeneratedImage(image)) {
            val activeEpisode = activeParts[2]
            val imageWorkId = manga.ntkImageWorkId.trim()
            val targetWork = targetParts[1]
            val identity = try {
                CustomHttpClient.cachedNtkImageIdentity(activePath)
            } catch (_: Throwable) {
                null
            }
            if (
                identity != null &&
                identity.workId == targetWork &&
                identity.episodeId == activeEpisode
            ) {
                return null
            }
            if (!activeEpisode.matches(Regex("\\d+")) ||
                (imageWorkId.matches(Regex("\\d{1,12}")) && imageWorkId == targetWork)
            ) {
                return null
            }
        }
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
            image.contains("/black/episodes/", ignoreCase = true) ->
                "${target.baseUrl}/black/episodes/$activeWork/$activeEpisode/$pageName"
            image.contains("/wt/episodes/", ignoreCase = true) ->
                "${target.baseUrl}/wt/episodes/$activeWork/$activeEpisode/$pageName"
            else ->
                "${target.baseUrl}/${activeParts[0]}/$activeWork/$activeEpisode/$pageName"
        }
    }

    private fun isProtectedApiGeneratedImage(image: String): Boolean {
        return image.contains("/black/episodes/", ignoreCase = true) ||
            image.contains("/blacktoon/episodes/", ignoreCase = true) ||
            image.contains("/wt/episodes/", ignoreCase = true)
    }

    private fun isNtkProtectedViewerApiImage(image: String): Boolean {
        return try {
            Uri.parse(image).path.equals("/api/m/i", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private data class NtkGeneratedImageRef(
        val episodeKey: String,
        val pageKey: String,
        val page: Int,
        val extension: String
    )

    private fun ntkGeneratedImageRef(image: String): NtkGeneratedImageRef? {
        val numericMatch = Regex("^(?:https?://[^/]+)/(manhwa|webtoon)/(\\d+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (numericMatch != null) {
            val key = "${numericMatch.groupValues[1].lowercase()}/${numericMatch.groupValues[2]}/${numericMatch.groupValues[3]}"
            val page = numericMatch.groupValues[4].toIntOrNull() ?: return null
            return NtkGeneratedImageRef(key, "$key|$page", page, numericMatch.groupValues[5].lowercase())
        }
        val blacktoonMatch = Regex("^(?:https?://[^/]+)/(?:blacktoon|black)/episodes/(\\d+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
        if (blacktoonMatch != null) {
            val key = "webtoon/${blacktoonMatch.groupValues[1]}/${blacktoonMatch.groupValues[2]}"
            val page = blacktoonMatch.groupValues[3].toIntOrNull() ?: return null
            return NtkGeneratedImageRef(key, "$key|$page", page, blacktoonMatch.groupValues[4].lowercase())
        }
        val slugMatch = Regex("^(?:https?://[^/]+)/wt/episodes/([^/?#]+)/([^/?#]+)/p(\\d{3})\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image) ?: return null
        val key = "wt/${slugMatch.groupValues[1]}/${slugMatch.groupValues[2]}"
        val page = slugMatch.groupValues[3].toIntOrNull() ?: return null
        return NtkGeneratedImageRef(key, "$key|$page", page, slugMatch.groupValues[4].lowercase())
    }

    private fun rememberNtkGeneratedEpisodeExtension(image: String) {
        val ref = ntkGeneratedImageRef(image) ?: return
        rememberNtkGeneratedResolvedPage(image, "episode_extension")
        if (ref.extension != "jpg" && hasNtkGeneratedNotFoundInitialExtension(ref, ref.extension)) {
            Log.d(
                TAG,
                "ntk_generated_extension_hint_skip_missing key=${ref.episodeKey},extension=${ref.extension},page=${ref.page}"
            )
            return
        }
        if (shouldPreserveNtkGeneratedPageScopedExtension(image, ref)) return
        val previousPage = ntkGeneratedPageExtensions.put(ref.pageKey, ref.extension)
        if (previousPage != ref.extension) {
            Log.d(TAG, "ntk_generated_page_extension_hint key=${ref.pageKey},extension=${ref.extension},previous=${previousPage.orEmpty()}")
        }
        val existingEpisode = ntkGeneratedEpisodeExtensions[ref.episodeKey]
        if (ref.extension == "jpg" && existingEpisode != null && existingEpisode != "jpg") {
            if (hasNtkGeneratedNotFoundInitialExtension(ref, existingEpisode)) {
                Log.d(
                    TAG,
                    "ntk_generated_episode_extension_replace_missing key=${ref.episodeKey},existing=$existingEpisode,incoming=jpg"
                )
            } else {
                Log.d(TAG, "ntk_generated_episode_extension_preserve key=${ref.episodeKey},existing=$existingEpisode,incoming=jpg")
                return
            }
        }
        val previousEpisode = ntkGeneratedEpisodeExtensions.put(ref.episodeKey, ref.extension)
        if (previousEpisode != ref.extension) {
            Log.d(TAG, "ntk_generated_episode_extension_hint key=${ref.episodeKey},extension=${ref.extension},previous=${previousEpisode.orEmpty()}")
        }
    }

    private fun shouldPreserveNtkGeneratedPageScopedExtension(
        image: String,
        ref: NtkGeneratedImageRef
    ): Boolean {
        if (ref.extension != "jpg") return false
        val existing = ntkGeneratedPageExtensions[ref.pageKey] ?: return false
        if (existing == "jpg") return false
        if (hasNtkGeneratedNotFoundInitialExtension(ref, existing)) return false
        val target = ntkGeneratedTarget(image) ?: return false
        return target.path.startsWith("/manhwa/", ignoreCase = true) &&
            ref.page == 1
    }

    private fun rememberNtkGeneratedNotFound(manga: Manga, image: String, source: String) {
        val target = ntkGeneratedTarget(image) ?: return
        if (hasNtkGeneratedResolvedPage(target)) {
            Log.d(
                TAG,
                "ntk_generated_not_found_skip_resolved key=${ntkGeneratedResolvedPageKey(target)}," +
                    "source=$source,image=${image.substringAfterLast('/').takeLast(64)}"
            )
            return
        }
        val key = ntkGeneratedImageStateKey(image, target)
        if (ntkGeneratedNotFoundPages.add(key)) {
            Log.d(TAG, "ntk_generated_not_found key=$key,source=$source,image=${image.substringAfterLast('/').takeLast(64)}")
        }
    }

    fun markNtkGeneratedNotFound(manga: Manga, image: String?, source: String) {
        val value = image?.trim()?.takeIf { it.isNotEmpty() } ?: return
        rememberNtkGeneratedNotFound(manga, value, source)
    }

    private fun rememberNtkGeneratedHardBlocked(manga: Manga, image: String, source: String) {
        val target = ntkGeneratedTarget(image) ?: return
        val key = ntkGeneratedImageStateKey(image, target)
        val challengeImage = ntkCurrentEpisodeChallengeImage(manga, image, target)
        try {
            if (challengeImage != null) {
                getHttpClient().markCloudflareChallenge(challengeImage)
                Log.d(
                    TAG,
                    "ntk_generated_hardblock_challenge image=${challengeImage.substringAfterLast('/').takeLast(64)}," +
                        "source=$source,original=${image.substringAfterLast('/').takeLast(64)}"
                )
            }
        } catch (t: Throwable) {
            rethrowIfFatal(t)
        }
        if (ntkGeneratedHardBlockedPages.add(key)) {
            Log.d(TAG, "ntk_generated_hardblock key=$key,source=$source,image=${image.substringAfterLast('/').takeLast(64)}")
        }
    }

    private fun ntkCurrentEpisodeChallengeImage(
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget
    ): String? {
        val page = target.page
        val path = manga.ntkEpisodePath
        val cached = cachedNtkApiFallbackImages(path)
            .firstOrNull { candidate -> ntkGeneratedTarget(candidate)?.page == page }
        if (!cached.isNullOrBlank()) return cached
        val early = earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
            .firstOrNull { candidate -> ntkGeneratedTarget(candidate)?.page == page }
        if (!early.isNullOrBlank()) return early
        if (ntkGeneratedTargetMatchesMangaEpisode(manga, target)) return image
        return canonicalNtkGeneratedImageForActiveEpisode(manga, image)
    }

    private fun isPermanentGeneratedMissingCode(code: Int): Boolean {
        return code == 404 || code == 410 || code == 520
    }

    private fun hasNtkGeneratedNotFound(manga: Manga, image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (hasNtkGeneratedResolvedPage(target)) {
            ntkGeneratedNotFoundPages.remove(ntkGeneratedImageStateKey(image, target))
            return false
        }
        return ntkGeneratedNotFoundPages.contains(ntkGeneratedImageStateKey(image, target))
    }

    private fun hasNtkGeneratedNotFoundExtension(
        ref: NtkGeneratedImageRef,
        extension: String?
    ): Boolean {
        val normalizedExtension = extension?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val path = ntkGeneratedStatePath(ref)
        if (ntkGeneratedResolvedPages.contains("$path|${ref.page}")) return false
        return ntkGeneratedNotFoundPages.contains("$path|${ref.page}|$normalizedExtension")
    }

    private fun hasNtkGeneratedNotFoundInitialExtension(
        ref: NtkGeneratedImageRef,
        extension: String?
    ): Boolean {
        val normalizedExtension = extension?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val path = ntkGeneratedStatePath(ref)
        if (!ntkGeneratedResolvedPages.contains("$path|${ref.page}") &&
            ntkGeneratedNotFoundPages.contains("$path|${ref.page}|$normalizedExtension")
        ) return true
        return (1..NTK_GENERATED_INITIAL_RECOVERY_PAGES)
            .any { page ->
                !ntkGeneratedResolvedPages.contains("$path|$page") &&
                    ntkGeneratedNotFoundPages.contains("$path|$page|$normalizedExtension")
            }
    }

    private fun ntkGeneratedStatePath(ref: NtkGeneratedImageRef): String {
        val parts = ref.episodeKey.trim('/').split('/')
        return if (parts.size >= 3 && parts[0].equals("wt", ignoreCase = true)) {
            "/webtoon/${parts[1]}/${parts[2]}"
        } else {
            "/${ref.episodeKey.trim('/')}"
        }
    }

    private fun hasNtkGeneratedHardBlocked(manga: Manga, image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        return ntkGeneratedHardBlockedPages.contains(ntkGeneratedImageStateKey(image, target))
    }

    fun isKnownNtkGeneratedNotFound(manga: Manga, image: String): Boolean {
        return hasNtkGeneratedNotFound(manga, image)
    }

    fun knownNtkGeneratedFirstNotFoundPage(path: String?): Int {
        val key = earlyNtkPathKey(path)
        if (key.isEmpty()) return -1
        var first = Int.MAX_VALUE
        val prefix = "$key|"
        for (entry in ntkGeneratedNotFoundPages) {
            if (!entry.startsWith(prefix)) continue
            val rest = entry.removePrefix(prefix)
            val page = rest.substringBefore('|').toIntOrNull() ?: continue
            val missingExtension = rest.substringAfter('|', "").trim().lowercase()
            if (ntkGeneratedResolvedPages.contains("$key|$page")) continue
            if (hasAlternateNtkGeneratedExtensionHint(key, page, missingExtension)) continue
            if (page > 0 && page < first) first = page
        }
        return if (first == Int.MAX_VALUE) -1 else first
    }

    private fun hasAlternateNtkGeneratedExtensionHint(
        pathKey: String,
        page: Int,
        missingExtension: String
    ): Boolean {
        if (missingExtension.isEmpty()) return false
        val hintKeys = ntkGeneratedEpisodeHintKeys(pathKey)
        for (episodeKey in hintKeys) {
            val pageHint = ntkGeneratedPageExtensions["$episodeKey|$page"]
                ?.takeIf { it.isNotBlank() }
            if (pageHint != null && pageHint != missingExtension) return true
            val episodeHint = ntkGeneratedEpisodeExtensions[episodeKey]
                ?.takeIf { it.isNotBlank() }
            if (episodeHint != null && episodeHint != missingExtension) return true
        }
        return false
    }

    private fun isKnownNtkGeneratedNotFoundFailure(manga: Manga, image: String, throwable: Throwable? = null): Boolean {
        val target = ntkGeneratedTarget(image)
        if (target != null && hasNtkGeneratedResolvedPage(target)) return false
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

    private fun ntkGeneratedImageStateKey(image: String, target: NtkGeneratedTarget): String {
        val extension = Regex("\\.(jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .find(image)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?: ""
        return "${target.path}|${target.page}|$extension"
    }

    private fun ntkGeneratedResolvedPageKey(target: NtkGeneratedTarget): String {
        return "${target.path}|${target.page}"
    }

    private fun hasNtkGeneratedResolvedPage(target: NtkGeneratedTarget): Boolean {
        return ntkGeneratedResolvedPages.contains(ntkGeneratedResolvedPageKey(target))
    }

    private fun rememberNtkGeneratedResolvedPage(image: String, source: String) {
        val target = ntkGeneratedTarget(image) ?: return
        val key = ntkGeneratedResolvedPageKey(target)
        val added = ntkGeneratedResolvedPages.add(key)
        val removedMissing = ntkGeneratedNotFoundPages.removeAll { it.startsWith("$key|") }
        if (added || removedMissing) {
            Log.d(
                TAG,
                "ntk_generated_resolved_page key=$key,source=$source," +
                    "removedMissing=$removedMissing,image=${image.substringAfterLast('/').takeLast(64)}"
            )
        }
    }

    private fun rememberEarlyNtkGeneratedSuccess(manga: Manga, image: String) {
        val path = manga.ntkEpisodePath
        val target = ntkGeneratedTarget(image) ?: return
        rememberNtkGeneratedResolvedPage(image, "early_success")
        if (path.isNullOrBlank() || target.page !in 1..ntkGeneratedInitialRecoveryPages(manga)) return
        val key = earlyNtkPathKey(path)
        val existing = earlyNtkImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
            .ifEmpty {
                speculativeNtkGeneratedUrls[key]
                    ?.takeIf { SystemClock.elapsedRealtime() - it.createdAtMs <= EARLY_NTK_IMAGE_URL_TTL_MS }
                    ?.urls
                    .orEmpty()
            }
        val existingSuccess = earlyNtkGeneratedSuccessImageUrls(path, SystemClock.elapsedRealtime() - 30000L)
        val mergedBase = if (existing.isEmpty()) {
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
        val merged = rebuildEarlyNtkGeneratedSiblingsFromSuccess(existing, mergedBase, image, target)
        val successMerged = if (existingSuccess.isEmpty()) {
            listOf(image)
        } else {
            val replaced = ArrayList<String>(existingSuccess.size + 1)
            var inserted = false
            for (url in existingSuccess) {
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
                val insertIndex = replaced.indexOfFirst {
                    val existingTarget = ntkGeneratedTarget(it)
                    existingTarget != null && existingTarget.page > target.page
                }.let { if (it < 0) replaced.size else it }
                replaced.add(insertIndex, image)
            }
            replaced
        }
        if (merged.isEmpty() && successMerged.isEmpty()) return
        if (merged != existing) rememberEarlyNtkImageUrls(path, merged)
        if (successMerged != existingSuccess) rememberEarlyNtkGeneratedSuccessUrls(path, successMerged)
        if (merged == existing && successMerged == existingSuccess) return
        logCacheEvent(
            "foreground_generated_success_early_merge",
            manga,
            image,
            true,
            "page=${target.page},count=${merged.size},success=${successMerged.size}"
        )
    }

    private fun rebuildEarlyNtkGeneratedSiblingsFromSuccess(
        existing: List<String>,
        merged: List<String>,
        image: String,
        target: NtkGeneratedTarget
    ): List<String> {
        if (existing.size < NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return merged
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return merged
        val match = Regex("(?i)^(.*?/p)(\\d{3})(\\.(?:jpg|jpeg|png|webp))([?#].*)?$")
            .find(image)
            ?: return merged
        val prefix = match.groupValues[1]
        val extension = match.groupValues[3]
        val suffix = match.groupValues.getOrNull(4).orEmpty()
        val rebuilt = ArrayList<String>(existing.size)
        for (page in 1..existing.size) {
            rebuilt.add(prefix + "%03d".format(Locale.ROOT, page) + extension + suffix)
        }
        val changed = rebuilt.indices.any { index ->
            val old = existing.getOrNull(index)
            val newTarget = ntkGeneratedTarget(rebuilt[index])
            old != rebuilt[index] &&
                newTarget != null &&
                ntkGeneratedTargetsSameEpisode(newTarget, target)
        }
        return if (changed) rebuilt else merged
    }

    private fun ntkGeneratedEpisodeExtensionMatches(image: String): Boolean {
        val ref = ntkGeneratedImageRef(image) ?: return false
        return ntkGeneratedHintedExtension(ref) == ref.extension
    }

    private fun ntkGeneratedEpisodeExtensionHinted(image: String): Boolean {
        val ref = ntkGeneratedImageRef(image) ?: return false
        return ntkGeneratedHintedExtension(ref) != null
    }

    private fun ntkGeneratedHintedExtension(ref: NtkGeneratedImageRef): String? {
        if (ref.extension != "jpg" && hasNtkGeneratedNotFoundInitialExtension(ref, ref.extension)) {
            return "jpg"
        }
        val pageExtension = ntkGeneratedPageExtensions[ref.pageKey]
            ?.takeUnless { it != "jpg" && hasNtkGeneratedNotFoundInitialExtension(ref, it) }
        val episodeExtension = ntkGeneratedEpisodeExtensions[ref.episodeKey]
            ?.takeUnless { it != "jpg" && hasNtkGeneratedNotFoundInitialExtension(ref, it) }
        if (
            pageExtension == "jpg" &&
            episodeExtension != null &&
            episodeExtension != "jpg" &&
            !ref.episodeKey.startsWith("webtoon/", ignoreCase = true)
        ) {
            return episodeExtension
        }
        return pageExtension ?: episodeExtension
    }

    private fun isInitialGeneratedHintMismatch(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_TRANSIENT_RETRY_PAGES) return false
        val ref = ntkGeneratedImageRef(image) ?: return false
        if (ref.extension != "jpg") return false
        return ntkGeneratedEpisodeExtensionHinted(image) &&
            !ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun shouldDropMismatchedHintedInitialGenerated(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return false
        val ref = ntkGeneratedImageRef(image) ?: return false
        if (ref.extension != "jpg") return false
        if (isAllowedInitialJpgHedgeAgainstJpegHint(image)) return false
        val hinted = ntkGeneratedHintedExtension(ref)
        if (hasNtkGeneratedNotFoundInitialExtension(ref, hinted)) return false
        return ntkGeneratedEpisodeExtensionHinted(image) &&
            !ntkGeneratedEpisodeExtensionMatches(image)
    }

    private fun isAllowedInitialJpgHedgeAgainstJpegHint(image: String): Boolean {
        val target = ntkGeneratedTarget(image) ?: return false
        if (target.page !in 1..NTK_GENERATED_INITIAL_RECOVERY_PAGES) return false
        if (target.path.startsWith("/webtoon/", ignoreCase = true)) return false
        val ref = ntkGeneratedImageRef(image) ?: return false
        if (ref.extension != "jpg") return false
        if (hasNtkGeneratedNotFoundInitialExtension(ref, "jpg")) return false
        return ntkGeneratedHintedExtension(ref) == "jpeg"
    }

    private fun isVerifiedInitialGeneratedVariant(
        manga: Manga,
        image: String,
        target: NtkGeneratedTarget?
    ): Boolean {
        if (target == null || target.page !in 1..ntkGeneratedInitialRecoveryPages(manga)) return false
        if (!ntkGeneratedEpisodeExtensionMatches(image)) return false
        val ref = ntkGeneratedImageRef(image) ?: return false
        val activePath = earlyNtkPathKey(manga.ntkEpisodePath)
        return target.path != activePath || ref.extension != "jpg"
    }

    private fun ntkGeneratedImageWithHintedExtension(image: String): String {
        val ref = ntkGeneratedImageRef(image) ?: return image
        val hinted = ntkGeneratedHintedExtension(ref) ?: return image
        if (hinted == ref.extension) return image
        if (ref.extension != "jpg" && !hasNtkGeneratedNotFoundInitialExtension(ref, ref.extension)) return image
        val match = Regex("(?i)\\.(jpg|jpeg|png|webp)([?#].*)?$").find(image) ?: return image
        val suffix = match.groupValues.getOrNull(2).orEmpty()
        return image.substring(0, match.range.first) + ".$hinted$suffix"
    }

    fun hintedNtkGeneratedImageUrl(image: String?): String? {
        val value = image?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ntkGeneratedImageWithHintedExtension(value).takeIf { it != value }
    }

    private fun ntkGeneratedExtensionFallbacks(image: String): List<String> {
        val match = Regex("(?i)\\.(jpg|jpeg|png|webp)([?#].*)?$").find(image) ?: return emptyList()
        val current = match.groupValues[1].lowercase()
        val suffix = match.groupValues.getOrNull(2).orEmpty()
        val prefix = image.substring(0, match.range.first)
        val hinted = ntkGeneratedImageRef(image)
            ?.let { ntkGeneratedHintedExtension(it) }
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
            Regex("^apihost\\d*\\.com$").matches(host) ||
            host == "moamoabon.com" ||
            Regex("^fvcdn\\d*\\.com$").matches(host) ||
            Regex("^aws-cdn\\d*\\.site$").matches(host) ||
            Regex("^[a-z0-9-]+\\.worldcup\\d+\\.xyz$").matches(host) ||
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
        val lastStartedAt = lastTrimStartedAt.get()
        val delayMs = if (lastStartedAt <= 0L) {
            TRIM_DEBOUNCE_MS
        } else {
            (TRIM_DEBOUNCE_MS - (now - lastStartedAt)).coerceAtLeast(0L)
        }
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
