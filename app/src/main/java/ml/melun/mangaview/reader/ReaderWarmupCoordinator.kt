package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Looper
import com.bumptech.glide.Glide
import ml.melun.mangaview.MainApplication.p
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.Utils
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Decoder
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.NtkWebViewFallbackManager
import ml.melun.mangaview.mangaview.Title
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.model.PageItem
import ml.melun.mangaview.repository.MangaRepository
import ml.melun.mangaview.runtime.AppDispatchers
import ml.melun.mangaview.runtime.BackgroundPrefetchBudget
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

object ReaderWarmupCoordinator {
    enum class WarmupProfile {
        URL_ONLY,
        FIRST_BYTE,
        FIRST_BITMAP,
        ADJACENT_BYTES,
        LAUNCH_WINDOW
    }

    private const val DEFAULT_LAUNCH_WINDOW_DECODE_PAGES = 8
    private const val DEFAULT_LAUNCH_WINDOW_BYTE_PAGES = 16
    private const val NTK_LAUNCH_WINDOW_DECODE_PAGES = 8
    private const val NTK_LAUNCH_WINDOW_BYTE_PAGES = 32
    // Decode a real full-quality rolling runway while EpisodeActivity is still visible. Four
    // pages were too shallow for consecutive physical fast swipes; twelve kept too many native
    // hardware bitmaps alive and could trigger NativeAlloc GC under the finger. Eight covers the
    // first gestures while the byte-ready rolling pipeline continues ahead without delaying or
    // constraining scrolling.
    private const val NTK_AUTHORITATIVE_RUNWAY_DECODE_PAGES = 8
    private const val NTK_AUTHORITATIVE_CRITICAL_RUNWAY_DECODE_PAGES = 4

    @JvmStatic
    fun authoritativeNtkRunwayDecodePages(): Int = NTK_AUTHORITATIVE_RUNWAY_DECODE_PAGES
    private const val WFWF_LAUNCH_WINDOW_DECODE_PAGES = 32
    private const val WFWF_LAUNCH_WINDOW_BYTE_PAGES = 48
    private const val FIRST_BITMAP_BACKFILL_PAGES = 6
    private const val FIRST_BITMAP_BACKFILL_SCREENFULS = 2.5f
    private const val DEFAULT_LAUNCH_WINDOW_SCREENFULS = 3f
    private const val NTK_LAUNCH_WINDOW_SCREENFULS = 4f
    private const val WFWF_LAUNCH_WINDOW_SCREENFULS = 4f
    private const val GENERATED_NTK_SOFTWARE_PAGE_LIMIT = 4
    private const val GENERATED_NTK_SOFTWARE_BYTE_LIMIT = 24L * 1024L * 1024L
    // The authoritative runway is the only software batch allowed to use the prepared store's
    // NTK hard budget. It must either retain every requested region-tile page or remain unready;
    // a hardware/full-bitmap substitute would only defer the upload stall to the first gesture.
    private const val NTK_AUTHORITATIVE_RUNWAY_SOFTWARE_BYTE_LIMIT = 128L * 1024L * 1024L
    private const val NTK_PREPARED_TILE_SOURCE_HEIGHT =
        ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val knownUrlsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val knownUrlsCountProofInFlight = ConcurrentHashMap.newKeySet<String>()
    private val authoritativeNtkWarmups = ConcurrentHashMap.newKeySet<String>()
    private val authoritativeNtkWarmupRemovers = ConcurrentHashMap<String, () -> Unit>()
    private val authoritativeNtkEntryPaths = ConcurrentHashMap<String, String>()
    private val authoritativeNtkGenerations = ConcurrentHashMap<String, Long>()
    private val authoritativeNtkClaims = ConcurrentHashMap<String, Long>()
    private val authoritativeNtkForegroundPromotions = ConcurrentHashMap<String, Long>()
    private val authoritativeNtkGenerationSequence = AtomicLong()
    private val preparedByteSubscriberSequence = AtomicLong()
    private val firstBitmapBackfillInFlight = ConcurrentHashMap.newKeySet<String>()
    private val entryLocks = Array(4096) { Any() }

    private data class SourcePreloadProfile(
        val visibleProfile: WarmupProfile,
        val exactVisibleProfile: WarmupProfile,
        val tapProfile: WarmupProfile,
        val launchDecodePages: Int,
        val launchBytePages: Int,
        val adjacentBytePages: Int,
        val launchDecodeScreenfuls: Float
    )

    private data class PreparedBitmap(
        val index: Int,
        val bitmap: Bitmap
    )

    private data class PreparedDrawable(
        val index: Int,
        val bitmap: Bitmap? = null,
        val tilePage: ReaderPreparedStore.PreparedTilePage? = null
    )

    private fun recyclePreparedDrawable(prepared: PreparedDrawable) {
        prepared.tilePage?.tiles?.forEach { tile ->
            if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
        }
        // Bitmap pages may be borrowed from ViewerWarmupManager. They are deliberately left to
        // their producer; only region tiles are guaranteed to be owned by this batch.
    }

    private fun recyclePreparedBatch(tilePages: Map<Int, ReaderPreparedStore.PreparedTilePage>) {
        for (page in tilePages.values) {
            for (tile in page.tiles) {
                if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
            }
        }
    }

    /**
     * Assigns the small software prefix of one generated-NTK launch batch in launch order.
     * Fetch/bounds work may still run in parallel; only the sub-millisecond budget decision is
     * serialized so a later completion cannot steal the software budget from the launch anchor.
     */
    private class GeneratedNtkSoftwarePolicy(
        order: List<Int>,
        private val softwarePageLimit: Int = GENERATED_NTK_SOFTWARE_PAGE_LIMIT,
        private val softwareByteLimit: Long = GENERATED_NTK_SOFTWARE_BYTE_LIMIT
    ) {
        private val monitor = Object()
        private val candidates = order.distinct().take(softwarePageLimit.coerceAtLeast(0))
        private val ranks = candidates.withIndex().associate { it.value to it.index }
        private val decisions = HashMap<Int, Boolean>(candidates.size)
        private val reservations = HashMap<Int, Long>(candidates.size)
        private var nextRank = 0
        private var softwarePages = 0
        private var softwareBytes = 0L

        fun isCandidate(index: Int): Boolean = ranks.containsKey(index)

        fun chooseSoftware(index: Int, expectedBytes: Long): Boolean {
            val rank = ranks[index] ?: return false
            synchronized(monitor) {
                decisions[index]?.let { return it }
                while (rank > nextRank && !decisions.containsKey(index)) {
                    try {
                        monitor.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return rejectLocked(index)
                    }
                }
                decisions[index]?.let { return it }
                val safeBytes = expectedBytes.coerceAtLeast(0L)
                val accepted = safeBytes > 0L &&
                    softwarePages < softwarePageLimit &&
                    safeBytes <= softwareByteLimit &&
                    softwareBytes <= softwareByteLimit - safeBytes
                decisions[index] = accepted
                if (accepted) {
                    reservations[index] = safeBytes
                    softwarePages++
                    softwareBytes += safeBytes
                }
                advanceLocked()
                monitor.notifyAll()
                return accepted
            }
        }

        fun confirmSoftware(index: Int, bitmap: Bitmap): Boolean {
            synchronized(monitor) {
                if (decisions[index] != true || bitmap.isRecycled ||
                    bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.isMutable
                ) {
                    cancelLocked(index)
                    return false
                }
                return confirmSoftwareBytesLocked(index, bitmapAllocationBytes(bitmap))
            }
        }

        fun confirmSoftwareBytes(index: Int, actualBytes: Long): Boolean {
            synchronized(monitor) {
                if (decisions[index] != true) {
                    cancelLocked(index)
                    return false
                }
                return confirmSoftwareBytesLocked(index, actualBytes)
            }
        }

        private fun confirmSoftwareBytesLocked(index: Int, actualBytes: Long): Boolean {
            val reserved = reservations[index] ?: 0L
            val nextBytes = softwareBytes - reserved + actualBytes
            if (actualBytes <= 0L || nextBytes > softwareByteLimit) {
                cancelLocked(index)
                return false
            }
            reservations[index] = actualBytes
            softwareBytes = nextBytes
            return true
        }

        fun acceptExisting(index: Int, bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled) return false
            if (!isCandidate(index)) {
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    bitmap.isHardwareConfigCompat()
            }
            val wantsSoftware = chooseSoftware(
                index,
                if (bitmap.isHardwareConfigCompat()) {
                    estimatedArgbAllocationBytes(bitmap.width, bitmap.height, 1)
                } else {
                    bitmapAllocationBytes(bitmap)
                }
            )
            return if (wantsSoftware) {
                bitmap.config == Bitmap.Config.ARGB_8888 && !bitmap.isMutable &&
                    confirmSoftware(index, bitmap)
            } else {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    bitmap.isHardwareConfigCompat()
            }
        }

        fun cancel(index: Int) {
            val rank = ranks[index] ?: return
            synchronized(monitor) {
                if (decisions.containsKey(index)) {
                    cancelLocked(index)
                    monitor.notifyAll()
                    return
                }
                while (rank > nextRank && !decisions.containsKey(index)) {
                    try {
                        monitor.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                rejectLocked(index)
                monitor.notifyAll()
            }
        }

        private fun rejectLocked(index: Int): Boolean {
            cancelLocked(index)
            decisions[index] = false
            advanceLocked()
            return false
        }

        private fun cancelLocked(index: Int) {
            if (decisions[index] == true) {
                softwarePages = (softwarePages - 1).coerceAtLeast(0)
                softwareBytes = (softwareBytes - (reservations.remove(index) ?: 0L)).coerceAtLeast(0L)
            }
            decisions[index] = false
            advanceLocked()
        }

        private fun advanceLocked() {
            while (nextRank < candidates.size && decisions.containsKey(candidates[nextRank])) {
                nextRank++
            }
        }
    }

    private data class AuthoritativeOwnership(
        val entry: ReaderPreparedStore.Entry,
        val generation: Long
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
        val hasStartDrawable = snapshotHasDrawable(snapshot, snapshot.startPage)
        if (hasStartDrawable) {
            ml.melun.mangaview.glide.ViewerWarmupManager.logMetric("prepared_ready_bitmap_hit", 1L)
            return key
        }
        ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(
            "prepared_miss_" + snapshot.status.name.lowercase(Locale.ROOT),
            1L
        )
        if (snapshot.status == ReaderPreparedStore.Status.FAILED) return null
        schedule(context.applicationContext, entry, exactEpisode, launchProfile(launchTitle))
        return key
    }

    @JvmStatic
    fun strictWindowReadyKey(
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
        val snapshot = ReaderPreparedStore.get(key)?.snapshot() ?: return null
        val hasStartDrawable = snapshotHasDrawable(snapshot, snapshot.startPage)
        return if (hasStartDrawable && snapshot.status == ReaderPreparedStore.Status.WINDOW_READY) key else null
    }

    @JvmStatic
    fun primeVisible(context: Context?, manga: Manga?, title: Title?) {
        val profile = visibleContinueProfile(title ?: manga?.title)
        val entry = createEntry(context, manga, title, 0, false, profile) ?: return
        scheduleVisibleContinueWithForward(context!!.applicationContext, entry, profile)
    }

    /**
     * A visible continue card is strong UX intent, but it is not a license to warm the title in
     * both directions. Finish the saved episode first, then prepare exactly one newer episode.
     * User navigation suppresses the second stage, so speculative work never competes with the
     * reader the user actually opened.
     */
    private fun scheduleVisibleContinueWithForward(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        profile: WarmupProfile
    ) {
        val immediateFlag = AtomicBoolean(false)
        if (inFlight.putIfAbsent(entry.key, immediateFlag) != null) return
        AppDispatchers.submitImageWarmup {
            try {
                prepareEntry(appContext, entry, false, profile, immediateFlag)
                if (BackgroundPrefetchBudget.isNonCriticalPrefetchSuppressed()) return@submitImageWarmup
                val next = forwardNextEpisode(entry.manga, entry.title) ?: return@submitImageWarmup
                val nextProfile = forwardVisibleProfile()
                val nextEntry = createEntry(appContext, next, entry.title, 0, true, nextProfile)
                    ?: return@submitImageWarmup
                val nextFlag = AtomicBoolean(false)
                if (inFlight.putIfAbsent(nextEntry.key, nextFlag) == null) {
                    try {
                        prepareEntry(appContext, nextEntry, true, nextProfile, nextFlag)
                        ViewerWarmupManager.logMetric("continue_forward_next_warmup_ready", next.id.toLong())
                    } finally {
                        inFlight.remove(nextEntry.key, nextFlag)
                    }
                }
            } finally {
                inFlight.remove(entry.key, immediateFlag)
            }
        }
    }

    private fun forwardNextEpisode(current: Manga, title: Title?): Manga? {
        attachTitle(current, title)
        val next = current.nextEp() ?: return null
        attachTitle(next, title)
        return next
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
        if (isNtkWarmup(title ?: manga?.title)) return
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
    fun primeKnownUrls(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean,
        images: List<String>?,
        startPage: Int,
        decodeLimit: Int
    ): String? {
        if (context == null || manga == null || images.isNullOrEmpty()) return null
        val appContext = context.applicationContext
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val width = normalizeWidth(appContext, viewerWidth)
        val resolvedStart = startPage.coerceIn(0, images.lastIndex)
        val key = stableKey(manga, launchTitle, resolvedStart, width, exactEpisode)
        val entry = ReaderPreparedStore.createOrGet(
            key,
            manga,
            launchTitle,
            resolvedStart,
            width,
            pinStartBitmap = true
        )
        val safeImages = ArrayList(images)
        entry.setImages(safeImages, resolvedStart)
        val safeDecodeLimit = decodeLimit.coerceIn(0, safeImages.size)
        val ntkPreparedBatch = isNtkEpisodePath(manga.ntkEpisodePath?.trim().orEmpty())
        val pagePipeline = if (ntkPreparedBatch) {
            ReaderPagePipelineRegistry.createOrGet(key, safeImages.size)
        } else {
            null
        }
        val launchRunwayOnly = isNtkLaunchRunwayOnly(
            ntkPreparedBatch,
            safeDecodeLimit,
            safeImages.size
        )
        val alreadyWindowReady = entry.snapshot().let { snapshot ->
            (snapshot.status == ReaderPreparedStore.Status.WINDOW_READY ||
                launchRunwayOnly && snapshot.status == ReaderPreparedStore.Status.FIRST_BITMAP_READY) &&
                launchDecodeOrder(resolvedStart, safeImages.size, safeDecodeLimit).all { index ->
                    if (launchRunwayOnly) {
                        snapshotHasAuthoritativeNtkRunwayTilePage(snapshot, index)
                    } else {
                        snapshotHasDrawable(snapshot, index)
                    }
                }
        }
        if (safeDecodeLimit > 0 && !alreadyWindowReady && knownUrlsInFlight.add(key)) {
            if (manga.ntkImageCount <= 0) {
                startPreparedGeneratedHeadCountProof(appContext, manga, safeImages)
            }
            val scheduledAt = android.os.SystemClock.elapsedRealtime()
            val authoritativeGeneration = authoritativeNtkGenerations[key]
            val authoritativePath = authoritativeNtkEntryPaths[key]
            val ownerCurrent = {
                if (authoritativeGeneration != null) {
                    // ReaderV2 may retain this exact Entry as its live listener owner while the
                    // store map is trimmed/cleared after hand-off. The authoritative generation
                    // is the cancellation token; requiring map identity here aborted pages 2..3
                    // after pages 0..1 had already reached the physical surface.
                    isAuthoritativeNtkGenerationCurrent(
                        key,
                        authoritativePath,
                        authoritativeGeneration
                    )
                } else {
                    ReaderPreparedStore.get(key) === entry
                }
            }
            val decodeTask = Runnable {
                val decoded = HashSet<Int>()
                val published = HashSet<Int>()
                val order = launchDecodeOrder(resolvedStart, safeImages.size, safeDecodeLimit)
                val generatedSoftwarePolicy = if (launchRunwayOnly) {
                    GeneratedNtkSoftwarePolicy(
                        order,
                        softwarePageLimit = order.size,
                        softwareByteLimit = NTK_AUTHORITATIVE_RUNWAY_SOFTWARE_BYTE_LIMIT
                    )
                } else {
                    GeneratedNtkSoftwarePolicy(order)
                }
                try {
                    android.util.Log.d(
                        "ViewerPerf",
                        "reader_ntk_prepared_decode_batch_start path=${manga.ntkEpisodePath}," +
                            "count=${order.size},queueMs=${android.os.SystemClock.elapsedRealtime() - scheduledAt}," +
                            "parallel=$ntkPreparedBatch"
                    )
                    val preparedBitmaps = LinkedHashMap<Int, Bitmap>(order.size)
                    val preparedTilePages = LinkedHashMap<Int, ReaderPreparedStore.PreparedTilePage>(order.size)
                    val criticalOrder = if (ntkPreparedBatch) {
                        order.take(minOf(NTK_AUTHORITATIVE_CRITICAL_RUNWAY_DECODE_PAGES, order.size))
                    } else {
                        emptyList()
                    }
                    if (ntkPreparedBatch) {
                        if (!ownerCurrent()) return@Runnable
                        val completion = AppDispatchers.ntkSurfaceImageCompletionService<PreparedDrawable>()
                        fun submitPreparedFailure(index: Int, failure: Throwable) {
                            completion.submit(AppDispatchers.safeCallable {
                                generatedSoftwarePolicy.cancel(index)
                                throw (failure as? Exception ?: RuntimeException(failure))
                            })
                        }
                        fun submitPreparedDecode(index: Int) {
                            val pipelineDemand = if (index in criticalOrder) {
                                ReaderPagePipeline.Demand.PRE_ACTIVATION_CRITICAL
                            } else {
                                ReaderPagePipeline.Demand.PHYSICAL_RUNWAY
                            }
                            val byteRequest = pagePipeline?.requestBytes(index, pipelineDemand)
                            val byteLease = byteRequest?.lease
                            if (pagePipeline != null &&
                                byteRequest?.disposition != ReaderPagePipeline.RequestDisposition.STARTED_BYTES
                            ) {
                                throw java.util.concurrent.CancellationException(
                                    "Prepared page already has owner index=$index state=${byteRequest?.disposition}"
                                )
                            }
                            if (pagePipeline == null || byteLease == null) {
                                submitPreparedFailure(
                                    index,
                                    java.util.concurrent.CancellationException("Prepared pipeline lease missing")
                                )
                                return
                            }
                            val image = safeImages[index]
                            val flight = ReaderImageCache.acquireForegroundByteFlight(
                                appContext,
                                manga,
                                image,
                                byteLease.pageKey,
                                pipelineDemand
                            )
                            // A cached snapshot and its callback may race. Only the first terminal
                            // event is allowed to claim the pipeline byte lease and submit decode.
                            val terminalSubmitted = AtomicBoolean(false)
                            fun onBytesReady(file: File) {
                                if (!terminalSubmitted.compareAndSet(false, true)) return
                                android.util.Log.d(
                                    "ViewerPerf",
                                    "reader_ntk_pipeline_bytes_ready path=${manga.ntkEpisodePath}," +
                                        "index=$index,elapsedMs=${android.os.SystemClock.elapsedRealtime() - scheduledAt}," +
                                        "bytes=${file.length()}"
                                )
                                if (!ownerCurrent() || !file.exists()) {
                                    pagePipeline.failRetryable(index, byteLease.leaseId)
                                    submitPreparedFailure(
                                        index,
                                        java.util.concurrent.CancellationException("Prepared byte owner cancelled")
                                    )
                                    return
                                }
                                val canonical = ReaderPreparedStore.canonicalOriginalAssetIdentity(image)
                                if (!pagePipeline.acceptBytes(
                                        ReaderPagePipeline.ByteCompletion(
                                            pagePipeline.episodeEpoch,
                                            index,
                                            canonical,
                                            file.absolutePath,
                                            byteLease.leaseId
                                        )
                                    )
                                ) {
                                    submitPreparedFailure(
                                        index,
                                        java.util.concurrent.CancellationException("Prepared byte completion rejected")
                                    )
                                    return
                                }
                                val decodeRequest = pagePipeline.requestDrawable(index, pipelineDemand)
                                val decodeLease = decodeRequest.lease
                                if (decodeRequest.disposition != ReaderPagePipeline.RequestDisposition.STARTED_DECODE ||
                                    decodeLease == null
                                ) {
                                    submitPreparedFailure(
                                        index,
                                        java.util.concurrent.CancellationException("Prepared decode lease rejected")
                                    )
                                    return
                                }
                                completion.submit(AppDispatchers.safeCallable {
                                    val decodeStartedAt = android.os.SystemClock.elapsedRealtime()
                                    if (!ownerCurrent()) {
                                        pagePipeline.failRetryable(index, decodeLease.leaseId)
                                        generatedSoftwarePolicy.cancel(index)
                                        throw java.util.concurrent.CancellationException("Prepared owner cancelled")
                                    }
                                    val prepared = decodePreparedNtkDrawable(
                                        appContext,
                                        manga,
                                        index,
                                        image,
                                        width,
                                        generatedSoftwarePolicy,
                                        requireImmutableTilePage = launchRunwayOnly,
                                        pagePipeline = pagePipeline,
                                        decodeLease = decodeLease
                                    )
                                    android.util.Log.d(
                                        "ViewerPerf",
                                        "reader_ntk_pipeline_decode_ready path=${manga.ntkEpisodePath}," +
                                            "index=$index,decodeMs=${android.os.SystemClock.elapsedRealtime() - decodeStartedAt}," +
                                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - scheduledAt}," +
                                            "tiles=${prepared.tilePage?.tiles?.size ?: 0}," +
                                            "height=${prepared.tilePage?.pageHeight ?: 0}"
                                    )
                                    if (!ownerCurrent()) {
                                        recyclePreparedDrawable(prepared)
                                        generatedSoftwarePolicy.cancel(index)
                                        throw java.util.concurrent.CancellationException("Prepared owner changed")
                                    }
                                    prepared
                                })
                            }
                            fun enqueueProducer() {
                                AppDispatchers.submitNtkSourceImage {
                                    flight.runProducer {
                                        ReaderImageCache.getOrFetchFileForeground(
                                            appContext,
                                            manga,
                                            image,
                                            null,
                                            visiblePriority = true
                                        )
                                    }
                                }
                            }
                            val subscriber = ReaderImageCache.ByteFlightSubscriber { result ->
                                when (result) {
                                    is ReaderImageCache.ByteFlightResult.Ready -> onBytesReady(result.file)
                                    ReaderImageCache.ByteFlightResult.RetryProducer -> {
                                        if (flight.claimProducer()) enqueueProducer()
                                    }
                                    is ReaderImageCache.ByteFlightResult.Failed -> {
                                        if (terminalSubmitted.compareAndSet(false, true)) {
                                            pagePipeline.failRetryable(index, byteLease.leaseId)
                                            submitPreparedFailure(index, result.error)
                                        }
                                    }
                                }
                            }
                            when (val snapshot = flight.subscribeOrSnapshot(
                                preparedByteSubscriberSequence.incrementAndGet(),
                                subscriber
                            )) {
                                is ReaderImageCache.ByteFlightSnapshot.Ready -> onBytesReady(snapshot.file)
                                is ReaderImageCache.ByteFlightSnapshot.Subscribed -> {
                                    if (snapshot.startProducer) enqueueProducer()
                                }
                                ReaderImageCache.ByteFlightSnapshot.Cancelled -> {
                                    if (terminalSubmitted.compareAndSet(false, true)) {
                                        pagePipeline.failRetryable(index, byteLease.leaseId)
                                        submitPreparedFailure(
                                            index,
                                            java.util.concurrent.CancellationException("Prepared byte flight cancelled")
                                        )
                                    }
                                }
                            }
                        }
                        // Byte subscriptions are non-blocking, while the completion service admits
                        // only actual decode work to its four CPU workers.
                        val deferredOrder = order.drop(criticalOrder.size)
                        criticalOrder.forEach(::submitPreparedDecode)
                        val completed = HashMap<Int, PreparedDrawable>(order.size)
                        var decodeFailure: Throwable? = null
                        var nextDeferred = 0
                        repeat(order.size) {
                            try {
                                val prepared = completion.take().get()
                                if (launchRunwayOnly &&
                                    (prepared.bitmap != null ||
                                        !isAuthoritativeNtkRunwayTilePage(
                                            prepared.tilePage,
                                            safeImages[prepared.index]
                                        ))
                                ) {
                                    recyclePreparedDrawable(prepared)
                                    throw java.io.IOException(
                                        "Authoritative launch runway requires immutable region tiles"
                                    )
                                }
                                decoded.add(prepared.index)
                                completed[prepared.index] = prepared
                                if (launchRunwayOnly && ownerCurrent()) {
                                    val bitmap = prepared.bitmap
                                    val tilePage = prepared.tilePage
                                    // Publication transfers the drawable identity to the prepared
                                    // entry and any attached ReaderSession listener. From this point
                                    // on a later sibling failure/cancellation must not recycle tiles
                                    // that may already be referenced by the real reader surface.
                                    published.add(prepared.index)
                                    entry.putDrawableBatch(
                                        if (bitmap == null) emptyMap() else mapOf(prepared.index to bitmap),
                                        if (tilePage == null) emptyMap() else mapOf(prepared.index to tilePage),
                                        windowComplete = false
                                    )
                                }
                            } catch (failure: Throwable) {
                                if (decodeFailure == null) decodeFailure = failure
                            } finally {
                                // Keep exactly four decode/byte producers admitted. Refill one
                                // slot after every completion instead of waiting for all critical
                                // pages, which left the pool idle behind one slow critical body.
                                if (nextDeferred < deferredOrder.size) {
                                    submitPreparedDecode(deferredOrder[nextDeferred++])
                                }
                            }
                        }
                        val missingCritical = criticalOrder.filter { it !in completed }
                        if (missingCritical.isNotEmpty()) {
                            for (prepared in completed.values) {
                                if (prepared.index !in published) recyclePreparedDrawable(prepared)
                            }
                            throw java.io.IOException(
                                "Prepared critical runway failed indexes=${missingCritical.joinToString("|")}",
                                decodeFailure
                            )
                        }
                        if (decodeFailure != null) {
                            android.util.Log.d(
                                "ViewerPerf",
                                "reader_ntk_prepared_deferred_partial path=${manga.ntkEpisodePath}," +
                                    "prepared=${completed.size},requested=${order.size}," +
                                    "missing=${order.filter { it !in completed }.joinToString("|")}," +
                                    "error=${decodeFailure?.javaClass?.simpleName.orEmpty()}"
                            )
                        }
                        for (index in order) {
                            val prepared = completed[index] ?: continue
                            prepared.bitmap?.let { preparedBitmaps[index] = it }
                            prepared.tilePage?.let { preparedTilePages[index] = it }
                        }
                    } else {
                        for (index in order) {
                            val bitmap = decodePage(
                                appContext,
                                manga,
                                index,
                                safeImages[index],
                                width,
                                generatedSoftwarePolicy
                            )
                            decoded.add(index)
                            preparedBitmaps[index] = bitmap
                        }
                    }
                    if (!launchRunwayOnly) {
                        check(preparedBitmaps.size + preparedTilePages.size == order.size) {
                            "Incomplete prepared drawable batch"
                        }
                    }
                    if (launchRunwayOnly) {
                        check(preparedBitmaps.isEmpty() &&
                            criticalOrder.all { it in preparedTilePages }
                        ) {
                            "Authoritative launch runway contains a non-tile drawable"
                        }
                        check(criticalOrder.all { index ->
                            isAuthoritativeNtkRunwayTilePage(
                                preparedTilePages[index],
                                safeImages[index]
                            )
                        }) {
                            "Authoritative launch runway contains an invalid tile page"
                        }
                    }
                    if (!ownerCurrent()) {
                        recyclePreparedBatch(preparedTilePages.filterKeys { it !in published })
                        return@Runnable
                    }
                    if (launchRunwayOnly) {
                        check(criticalOrder.all { index ->
                            isAuthoritativeNtkRunwayTilePage(
                                pagePipeline?.preparedTilePage(index),
                                safeImages[index]
                            )
                        }) {
                            "Authoritative launch runway missing from page pipeline"
                        }
                    }
                    if (!launchRunwayOnly) {
                        entry.putDrawableBatch(preparedBitmaps, preparedTilePages, windowComplete = true)
                    }
                    android.util.Log.d(
                        "ViewerPerf",
                        (if (launchRunwayOnly) {
                            "reader_ntk_prepared_launch_runway_ready"
                        } else {
                            "reader_ntk_prepared_decode_batch_ready"
                        }) + " path=${manga.ntkEpisodePath}," +
                            "count=${preparedBitmaps.size + preparedTilePages.size}," +
                            "bitmaps=${preparedBitmaps.size},tilePages=${preparedTilePages.size}," +
                            "totalMs=${android.os.SystemClock.elapsedRealtime() - scheduledAt}"
                    )
                    for (index in launchDecodeOrder(resolvedStart, safeImages.size, safeDecodeLimit + 2)) {
                        if (index !in decoded) fetchImageFile(appContext, manga, safeImages[index])
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "ViewerPerf",
                        "reader_ntk_prepared_decode_batch_failed path=${manga.ntkEpisodePath}," +
                            "error=${e.javaClass.simpleName},message=${e.message.orEmpty()}",
                        e
                    )
                    ml.melun.mangaview.glide.ViewerWarmupManager.logMetric("prepared_known_urls_soft_fail", 1L)
                    if (ownerCurrent()) entry.fail()
                } finally {
                    knownUrlsInFlight.remove(key)
                }
            }
            if (ntkPreparedBatch) {
                // Do not put a real viewer hand-off behind USER_ACTION's single core worker.  The
                // critical lane only coordinates; bitmap CPU work fans out on the surface pool.
                AppDispatchers.submitNtkViewerCritical(decodeTask)
            } else {
                AppDispatchers.submitUserAction(decodeTask)
            }
        }
        return key
    }

    /**
     * Starts the real NTK reader pipeline while EpisodeActivity is still visible.
     *
     * The empty prepared entry is published immediately so a concurrent viewer
     * launch joins this owner. Actual bitmap work starts only after the native
     * pipeline publishes a complete authoritative manifest; every authoritative
     * page is then fetched, decoded and prepareToDraw()'d into that same entry.
     */
    @JvmStatic
    fun primeAuthoritativeNtkEpisode(
        context: Context?,
        manga: Manga?,
        title: Title?,
        exactEpisode: Boolean
    ): String? = primeAuthoritativeNtkEpisodeInternal(
        context, manga, title, exactEpisode, preemptSiblings = false
    )

    /**
     * The top EpisodeActivity owns the user's current navigation intent. Its eager target must
     * preempt source/decode owners left by a previous title, while remaining speculative until
     * the user physically presses the staged row.
     */
    @JvmStatic
    fun primeFocusedAuthoritativeNtkEpisode(
        context: Context?,
        manga: Manga?,
        title: Title?,
        exactEpisode: Boolean
    ): String? = primeAuthoritativeNtkEpisodeInternal(
        context, manga, title, exactEpisode, preemptSiblings = true
    )

    private fun primeAuthoritativeNtkEpisodeInternal(
        context: Context?,
        manga: Manga?,
        title: Title?,
        exactEpisode: Boolean,
        preemptSiblings: Boolean
    ): String? {
        if (context == null || manga == null) return null
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/manhwa/", ignoreCase = true) &&
            !path.startsWith("/webtoon/", ignoreCase = true)
        ) {
            return null
        }
        val appContext = context.applicationContext
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val candidateEntry = createEntry(
            appContext,
            manga,
            launchTitle,
            0,
            exactEpisode,
            WarmupProfile.LAUNCH_WINDOW
        ) ?: return null
        val ownership = authoritativeNtkOwnership(
            appContext,
            candidateEntry,
            manga,
            launchTitle,
            exactEpisode,
            path
        ) ?: return null
        val entry = ownership.entry
        val generation = ownership.generation
        // EpisodeActivity needs only the stable hand-off key on its first traversal. Manifest
        // scans, listener registration and native resolver startup stay on the viewer-critical
        // lane so opening the episode list never serializes them on the UI thread.
        if (reserveAuthoritativeTask(authoritativeNtkClaims, entry.key, generation)) {
            AppDispatchers.submitNtkViewerCritical {
                try {
                    if (!isAuthoritativeNtkGenerationCurrent(entry.key, path, generation)) {
                        return@submitNtkViewerCritical
                    }
                    if (preemptSiblings) {
                        cancelOtherAuthoritativeNtkEpisodes(path, clearPreparedBitmaps = true)
                        if (!isAuthoritativeNtkGenerationCurrent(entry.key, path, generation)) {
                            return@submitNtkViewerCritical
                        }
                    }
                    startAuthoritativeNtkEpisode(
                        appContext,
                        entry,
                        manga,
                        launchTitle,
                        exactEpisode,
                        path,
                        generation
                    )
                    if (preemptSiblings) {
                        android.util.Log.d(
                            "ViewerPerf",
                            "reader_ntk_episode_focused_prime path=$path,key=${entry.key}," +
                                "generation=$generation"
                        )
                    }
                } finally {
                    authoritativeNtkClaims.remove(entry.key, generation)
                }
            }
        }
        return entry.key
    }

    /**
     * Claims a physically selected NTK row without doing network, decode or
     * WebView work on the input-dispatch thread. The empty prepared entry is
     * installed synchronously so ACTION_UP can launch against the same owner;
     * stale speculative owners are cancelled before the selected pipeline is
     * promoted on the NTK critical executor.
     */
    @JvmStatic
    fun claimAuthoritativeNtkEpisode(
        context: Context?,
        manga: Manga?,
        title: Title?,
        exactEpisode: Boolean
    ): String? {
        if (context == null || manga == null) return null
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!isNtkEpisodePath(path)) return null
        val appContext = context.applicationContext
        val launchTitle = title ?: manga.title
        val candidateEntry = createEntry(
            appContext,
            manga,
            launchTitle,
            0,
            exactEpisode,
            WarmupProfile.LAUNCH_WINDOW
        ) ?: return null
        val ownership = authoritativeNtkOwnership(
            appContext,
            candidateEntry,
            manga,
            launchTitle,
            exactEpisode,
            path
        ) ?: return null
        val entry = ownership.entry
        val generation = ownership.generation
        // A visible-row prime may already own this exact key. ACTION_DOWN cancels stale siblings
        // and joins the selected manifest/decode owner, but staging must not publish foreground
        // viewer state. EpisodeActivity does that only after the inline reader is activated.
        if (reserveAuthoritativeTask(authoritativeNtkForegroundPromotions, entry.key, generation)) {
            AppDispatchers.submitNtkViewerCritical {
                try {
                    if (!isAuthoritativeNtkGenerationCurrent(entry.key, path, generation)) {
                        return@submitNtkViewerCritical
                    }
                    cancelOtherAuthoritativeNtkEpisodes(path, clearPreparedBitmaps = true)
                    if (!isAuthoritativeNtkGenerationCurrent(entry.key, path, generation)) {
                        return@submitNtkViewerCritical
                    }
                    startAuthoritativeNtkEpisode(
                        appContext,
                        entry,
                        manga,
                        launchTitle,
                        exactEpisode,
                        path,
                        generation
                    )
                    android.util.Log.d(
                        "ViewerPerf",
                        "reader_ntk_episode_selected_claim path=$path,key=${entry.key}," +
                            "generation=$generation"
                    )
                } finally {
                    authoritativeNtkForegroundPromotions.remove(entry.key, generation)
                }
            }
        }
        return entry.key
    }

    /** Cancels a speculative authoritative owner for a row that left the warm window. */
    @JvmStatic
    fun cancelAuthoritativeNtkEpisode(path: String?, clearPreparedBitmaps: Boolean) {
        val normalized = path?.trim().orEmpty()
        if (!isNtkEpisodePath(normalized)) return
        cancelAuthoritativeNtkEpisodesMatching(
            shouldCancel = { it.equals(normalized, ignoreCase = true) },
            clearPreparedBitmaps = clearPreparedBitmaps
        )
    }

    private fun cancelOtherAuthoritativeNtkEpisodes(
        keepPath: String,
        clearPreparedBitmaps: Boolean
    ) {
        cancelAuthoritativeNtkEpisodesMatching(
            shouldCancel = { !it.equals(keepPath, ignoreCase = true) },
            clearPreparedBitmaps = clearPreparedBitmaps
        )
    }

    private fun cancelAuthoritativeNtkEpisodesMatching(
        shouldCancel: (String) -> Boolean,
        clearPreparedBitmaps: Boolean
    ) {
        for ((key, entryPath) in authoritativeNtkEntryPaths.entries) {
            if (!shouldCancel(entryPath)) continue
            val cancelledGeneration = synchronized(lockForEntry(key)) {
                val generation = authoritativeNtkGenerations.remove(key)
                if (generation != null) {
                    authoritativeNtkClaims.remove(key, generation)
                    authoritativeNtkForegroundPromotions.remove(key, generation)
                }
                authoritativeNtkWarmupRemovers.remove(key)?.invoke()
                authoritativeNtkWarmups.remove(key)
                if (clearPreparedBitmaps) {
                    ReaderPreparedStore.remove(key)
                    authoritativeNtkEntryPaths.remove(key, entryPath)
                }
                generation
            }
            android.util.Log.d(
                "ViewerPerf",
                "reader_ntk_episode_authoritative_cancel path=$entryPath," +
                    "clearPrepared=$clearPreparedBitmaps,generation=${cancelledGeneration ?: 0L}"
            )
        }
    }

    private fun authoritativeNtkOwnership(
        appContext: Context,
        candidateEntry: ReaderPreparedStore.Entry,
        manga: Manga,
        title: Title?,
        exactEpisode: Boolean,
        path: String
    ): AuthoritativeOwnership? {
        synchronized(lockForEntry(candidateEntry.key)) {
            // Cancellation removes the prepared-store entry under this same lock. A caller may
            // have obtained the old object immediately before that removal; replace it here so a
            // new generation never decodes into an orphaned Entry that the inline reader cannot claim.
            val ownedEntry = ReaderPreparedStore.get(candidateEntry.key) ?: createEntry(
                appContext,
                manga,
                title,
                candidateEntry.requestedWidth,
                exactEpisode,
                WarmupProfile.LAUNCH_WINDOW
            ) ?: return null
            authoritativeNtkEntryPaths[ownedEntry.key] = path
            val generation = authoritativeNtkGenerations[ownedEntry.key] ?: authoritativeNtkGenerationSequence
                .incrementAndGet()
                .also { authoritativeNtkGenerations[ownedEntry.key] = it }
            return AuthoritativeOwnership(ownedEntry, generation)
        }
    }

    private fun reserveAuthoritativeTask(
        reservations: ConcurrentHashMap<String, Long>,
        key: String,
        generation: Long
    ): Boolean {
        while (isAuthoritativeNtkGenerationCurrent(key, null, generation)) {
            val existing = reservations.putIfAbsent(key, generation) ?: return true
            if (existing == generation) return false
            if (reservations.replace(key, existing, generation)) return true
        }
        return false
    }

    private fun isAuthoritativeNtkGenerationCurrent(
        key: String,
        expectedPath: String?,
        generation: Long
    ): Boolean {
        if (authoritativeNtkGenerations[key] != generation) return false
        return expectedPath == null || authoritativeNtkEntryPaths[key]
            ?.equals(expectedPath, ignoreCase = true) == true
    }

    private fun startAuthoritativeNtkEpisode(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        manga: Manga,
        launchTitle: Title?,
        exactEpisode: Boolean,
        path: String,
        generation: Long
    ) {
        if (!isAuthoritativeNtkGenerationCurrent(entry.key, path, generation)) return
        if (primeCompleteAuthoritativeNtkManifest(appContext, entry, manga, launchTitle, exactEpisode)) return
        val registered = synchronized(lockForEntry(entry.key)) {
            isAuthoritativeNtkGenerationCurrent(entry.key, path, generation) &&
                authoritativeNtkWarmups.add(entry.key)
        }
        if (!registered) return

        lateinit var removeListener: () -> Unit
        val authoritySubscription = NtkSourceSpoolRegistry.addAuthoritativeManifestListener(
            NtkAuthoritativeManifestListener { changedPath, _ ->
                if (changedPath.equals(path, ignoreCase = true)) {
                    // Typed authority notification performs no network or decode under the
                    // registry mutation lock. The critical lane claims the exact manifest.
                    AppDispatchers.submitNtkViewerCritical {
                        if (!isAuthoritativeNtkGenerationCurrent(entry.key, path, generation) ||
                            !authoritativeNtkWarmups.contains(entry.key)
                        ) return@submitNtkViewerCritical
                        if (primeCompleteAuthoritativeNtkManifest(
                                appContext,
                                entry,
                                manga,
                                launchTitle,
                                exactEpisode
                            )
                        ) finishAuthoritativeManifestWait(entry.key, path, generation)
                    }
                }
            }
        )
        removeListener = { authoritySubscription.close() }
        val keepListener = synchronized(lockForEntry(entry.key)) {
            if (isAuthoritativeNtkGenerationCurrent(entry.key, path, generation) &&
                authoritativeNtkWarmups.contains(entry.key)
            ) {
                authoritativeNtkWarmupRemovers[entry.key] = removeListener
                true
            } else false
        }
        if (!keepListener) {
            removeListener()
            return
        }

        // Close the listener registration race, still without holding the input ownership lock.
        if (primeCompleteAuthoritativeNtkManifest(appContext, entry, manga, launchTitle, exactEpisode)) {
            finishAuthoritativeManifestWait(entry.key, path, generation)
            return
        }
        if (!isAuthoritativeNtkGenerationCurrent(entry.key, path, generation)) return
        try {
            manga.startNtkEarlyViewerApiPrefetch(getHttpClient())
            android.util.Log.d(
                "ViewerPerf",
                "reader_ntk_episode_eager_native_start path=$path,key=${entry.key}," +
                    "generation=$generation"
            )
        } catch (e: Throwable) {
            android.util.Log.d(
                "ViewerPerf",
                "reader_ntk_episode_eager_native_error path=$path,$e"
            )
        }
    }

    private fun finishAuthoritativeManifestWait(key: String, path: String, generation: Long) {
        val remover = synchronized(lockForEntry(key)) {
            if (!isAuthoritativeNtkGenerationCurrent(key, path, generation)) null
            else {
                authoritativeNtkWarmups.remove(key)
                authoritativeNtkWarmupRemovers.remove(key)
            }
        }
        remover?.invoke()
    }

    private fun isNtkEpisodePath(path: String): Boolean {
        return path.startsWith("/manhwa/", ignoreCase = true) ||
            path.startsWith("/webtoon/", ignoreCase = true)
    }

    private fun isNtkLaunchRunwayOnly(
        ntkPreparedBatch: Boolean,
        decodeLimit: Int,
        imageCount: Int
    ): Boolean {
        return ntkPreparedBatch && decodeLimit > 0 && decodeLimit < imageCount
    }

    private fun primeCompleteAuthoritativeNtkManifest(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        manga: Manga,
        title: Title?,
        exactEpisode: Boolean
    ): Boolean {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isEmpty()) return false
        val authoritative = NtkSourceSpoolRegistry.currentAuthoritativeManifest(path)
            ?: return false
        if (!authoritative.isProductionClaimable) return false
        val seal = authoritative.seal
        val authoritativeCount = seal.pageCount
        val authoritativeUrls = ArrayList(seal.normalizedCanonicalAssets)
        // NTK's current reader has exactly one source/decode owner: NtkEpisodeStripPipeline.
        // ReaderPreparedStore is only the immutable manifest hand-off here. Publishing decoded
        // pages from this coordinator would race the strip source spool, duplicate every initial
        // decode and steal the CPU/GPU window needed by the first physical scroll.
        // Publish the immutable hand-off only after typed response-bound authority is OWNED.
        manga.ntkImageCount = authoritativeCount
        manga.setImgs(ArrayList(authoritativeUrls))
        entry.setImages(authoritativeUrls, entry.requestedStartPage)
        getHttpClient()?.cancelActiveNtkMobileViewerDocumentFetches(
            path,
            "authoritative-strip-manifest"
        )
        NtkWebViewFallbackManager.completeAuthoritativeGeneratedNativeReader(
            appContext,
            path,
            "authoritative-strip-manifest"
        )
        android.util.Log.d(
            "ViewerPerf",
            "reader_ntk_episode_eager_authoritative_prime path=$path,count=$authoritativeCount," +
                "producer=strip-only,proofKind=${authoritative.proof.kind},key=true," +
                "manifestDigest=${seal.digestSha256.take(12)},state=" +
                "${NtkSourceSpoolRegistry.currentSnapshot(path)?.state}"
        )
        return true
    }

    private fun startPreparedGeneratedHeadCountProof(
        context: Context,
        manga: Manga,
        images: List<String>
    ) {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (path.isEmpty() || !knownUrlsCountProofInFlight.add(path)) return
        AppDispatchers.submitUserAction {
            try {
                confirmPreparedGeneratedHeadCount(context, manga, images)
            } finally {
                knownUrlsCountProofInFlight.remove(path)
            }
        }
    }

    @JvmStatic
    fun ownsKnownUrlsDecode(key: String?): Boolean {
        return !key.isNullOrEmpty() && knownUrlsInFlight.contains(key)
    }

    private fun confirmPreparedGeneratedHeadCount(
        context: Context,
        manga: Manga,
        images: List<String>
    ) {
        if (images.size < 2 || manga.ntkImageCount > 0) return
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.matches(Regex("^/(?:manhwa|webtoon)/\\d{1,12}/\\d{1,12}$", RegexOption.IGNORE_CASE))) return
        val pagePattern = Regex("^(.*?/p)(\\d{3,6})(\\.(?:jpg|jpeg|png|webp))(?:[?#].*)?$", RegexOption.IGNORE_CASE)
        val parsed = images.mapNotNull { pagePattern.matchEntire(it.trim()) }
        if (parsed.size != images.size) return
        val pages = parsed.mapNotNull { it.groupValues[2].toIntOrNull() }
        if (pages != (1..images.size).toList()) return
        val tail = parsed.last()
        val nextPage = images.size + 1
        val nextUrl = tail.groupValues[1] + nextPage.toString().padStart(tail.groupValues[2].length, '0') + tail.groupValues[3]
        val client = getHttpClient() ?: return
        val headers = linkedMapOf(
            "accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Referer" to client.getUrl(path),
            "User-Agent" to client.agent,
            "Accept-Encoding" to "identity"
        )
        val reachability = client.ntkImageHeaderReachability(nextUrl, headers, 900L)
        if (reachability != 0) {
            android.util.Log.d(
                "ViewerPerf",
                "viewer_ntk_prepared_head_count_unresolved path=$path,count=${images.size}," +
                    "next=$nextPage,reachability=$reachability"
            )
            return
        }
        manga.ntkImageCount = images.size
        ReaderImageCache.rememberTrustedNtkImageApiCount(path, images.size)
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
            path,
            images,
            "prepared-generated-tail-proof"
        )
        client.cancelActiveNtkMobileViewerDocumentFetches(
            path,
            "prepared-generated-tail-proof"
        )
        NtkWebViewFallbackManager.completeAuthoritativeGeneratedNativeReader(
            context,
            path,
            "prepared-generated-tail-proof"
        )
        android.util.Log.d(
            "ViewerPerf",
            "viewer_ntk_prepared_head_count_confirmed path=$path,count=${images.size},missingPage=$nextPage"
        )
    }

    @JvmStatic
    fun prepareKnownUrlsBlocking(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean,
        images: List<String>?,
        startPage: Int,
        decodeLimit: Int,
        fetchAllBytes: Boolean
    ): String? {
        if (context == null || manga == null || images.isNullOrEmpty()) return null
        val appContext = context.applicationContext
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val width = normalizeWidth(appContext, viewerWidth)
        val resolvedStart = startPage.coerceIn(0, images.lastIndex)
        val key = stableKey(manga, launchTitle, resolvedStart, width, exactEpisode)
        val entry = ReaderPreparedStore.createOrGet(
            key,
            manga,
            launchTitle,
            resolvedStart,
            width,
            pinStartBitmap = true
        )
        val safeImages = ArrayList(images)
        entry.setImages(safeImages, resolvedStart)
        if (fetchAllBytes) {
            fetchAllImageBytesBlocking(appContext, manga, safeImages)
        }
        val safeDecodeLimit = decodeLimit.coerceIn(1, safeImages.size)
        val order = launchDecodeOrder(resolvedStart, safeImages.size, safeDecodeLimit)
        val generatedSoftwarePolicy = GeneratedNtkSoftwarePolicy(order)
        val preparedBitmaps = LinkedHashMap<Int, Bitmap>(order.size)
        for (index in order) {
            val bitmap = decodePage(
                appContext,
                manga,
                index,
                safeImages[index],
                width,
                generatedSoftwarePolicy
            )
            preparedBitmaps[index] = bitmap
        }
        entry.putBitmapBatch(preparedBitmaps, windowComplete = true)
        return readyKey(appContext, manga, launchTitle, viewerWidth, exactEpisode) ?: key
    }

    @JvmStatic
    fun prepareKnownUrlsViewportBlocking(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean,
        images: List<String>?,
        startPage: Int,
        maxDecodePages: Int,
        screenfuls: Float
    ): String? {
        if (context == null || manga == null || images.isNullOrEmpty()) return null
        val appContext = context.applicationContext
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val width = normalizeWidth(appContext, viewerWidth)
        val resolvedStart = startPage.coerceIn(0, images.lastIndex)
        val key = stableKey(manga, launchTitle, resolvedStart, width, exactEpisode)
        val entry = ReaderPreparedStore.createOrGet(
            key,
            manga,
            launchTitle,
            resolvedStart,
            width,
            pinStartBitmap = true
        )
        val safeImages = ArrayList(images)
        entry.setImages(safeImages, resolvedStart)
        val limit = maxDecodePages.coerceIn(1, safeImages.size)
        val order = launchDecodeOrder(resolvedStart, safeImages.size, limit)
        val generatedSoftwarePolicy = GeneratedNtkSoftwarePolicy(order)
        val requiredHeight = (viewportHeightPx(appContext) * screenfuls.coerceAtLeast(1f)).toInt()
        var coveredHeight = 0
        var lastPosition = -1
        val completion = AppDispatchers.ioCompletionService<PreparedBitmap>()
        for (index in order) {
            completion.submit(AppDispatchers.safeCallable {
                PreparedBitmap(
                    index,
                    decodePage(
                        appContext,
                        manga,
                        index,
                        safeImages[index],
                        width,
                        generatedSoftwarePolicy
                    )
                )
            })
        }
        repeat(order.size) { received ->
            val prepared = completion.take().get()
            val index = prepared.index
            val bitmap = prepared.bitmap
            val position = order.indexOf(index)
            coveredHeight += bitmap.height.coerceAtLeast(1)
            lastPosition = max(lastPosition, position)
            val windowComplete = coveredHeight >= requiredHeight || received == order.lastIndex
            entry.putBitmap(index, bitmap, index == resolvedStart, windowComplete)
            android.util.Log.d(
                "ViewerPerf",
                "reader_prepare_viewport_bitmap path=${manga.ntkEpisodePath}," +
                    "index=$index,height=${bitmap.height},covered=$coveredHeight," +
                    "required=$requiredHeight,complete=$windowComplete,parallel=true"
            )
        }
        if (lastPosition >= 0) {
            val nextStart = (lastPosition + 1).coerceAtMost(order.size)
            val nextIndexes = order.drop(nextStart).take(2)
            if (nextIndexes.isNotEmpty()) {
                AppDispatchers.submitIo {
                    for (index in nextIndexes) {
                        fetchVisibleImageFile(appContext, manga, safeImages[index])
                    }
                }
            }
        }
        return strictWindowReadyKey(appContext, manga, launchTitle, viewerWidth, exactEpisode)
    }

    @JvmStatic
    fun pendingKey(
        context: Context?,
        manga: Manga?,
        title: Title?,
        viewerWidth: Int,
        exactEpisode: Boolean
    ): String? {
        if (context == null || manga == null) return null
        val launchTitle = title ?: manga.title
        attachTitle(manga, launchTitle)
        val width = normalizeWidth(context.applicationContext, viewerWidth)
        val startPage = requestedStartPage(manga, exactEpisode)
        val key = stableKey(manga, launchTitle, startPage, width, exactEpisode)
        if (ReaderPreparedStore.get(key) != null) return key
        if (ReaderPreparedStore.findReadyCompatible(key) != null) return key
        return null
    }

    @JvmStatic
    fun requestedLaunchStartPage(manga: Manga?, exactEpisode: Boolean): Int {
        if (manga == null) return 0
        return requestedStartPage(manga, exactEpisode)
    }

    @JvmStatic
    fun primeAdjacent(context: Context?, manga: Manga?, title: Title?) {
        if (isNtkWarmup(title ?: manga?.title)) return
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
        backfillLaunchWindowAfterFirstBitmap(context.applicationContext, entry, exactEpisode, profile)
        return readyKey(context, manga, title, viewerWidth, exactEpisode)
    }

    private fun backfillLaunchWindowAfterFirstBitmap(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        exactEpisode: Boolean,
        profile: WarmupProfile
    ) {
        if (profile != WarmupProfile.FIRST_BITMAP) return
        if (p != null && p.getDataSave()) return
        schedule(appContext, entry, exactEpisode, WarmupProfile.LAUNCH_WINDOW)
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
        val launchWindow = profile == WarmupProfile.LAUNCH_WINDOW
        val userPriority = launchWindow || profile == WarmupProfile.FIRST_BITMAP
        if (snapshot.status == ReaderPreparedStore.Status.WINDOW_READY ||
            snapshot.status == ReaderPreparedStore.Status.FIRST_BITMAP_READY && !launchWindow ||
            snapshot.status == ReaderPreparedStore.Status.URLS_READY && profile == WarmupProfile.URL_ONLY ||
            snapshot.status == ReaderPreparedStore.Status.BYTES_READY && isByteReadyProfile(profile)
        ) {
            return
        }
        val immediateFlag = AtomicBoolean(launchWindow)
        val existing = inFlight.putIfAbsent(entry.key, immediateFlag)
        if (existing != null) {
            if (launchWindow && existing.compareAndSet(false, true)) {
                AppDispatchers.submitUserAction {
                    prepareEntry(appContext, entry, exactEpisode, WarmupProfile.LAUNCH_WINDOW)
                }
            } else if (profile == WarmupProfile.FIRST_BITMAP) {
                AppDispatchers.submitUserAction {
                    prepareEntry(appContext, entry, exactEpisode, WarmupProfile.FIRST_BITMAP)
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
        if (userPriority) AppDispatchers.submitUserAction(task) else AppDispatchers.submitImageWarmup(task)
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
        val client = getHttpClient()
        if (client?.isNtk == true) {
            return MangaRepository.fetchViewerInitial(manga, cancellation)
        }
        if (profile == WarmupProfile.LAUNCH_WINDOW) {
            cancellation.prioritizeWebViewFallback()
            return MangaRepository.fetchViewerInitial(manga, cancellation)
        }
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
                val bitmap = decodePage(appContext, manga, startPage, urls[startPage], width)
                entry.putBitmap(startPage, bitmap, true, false)
                startFirstBitmapBackfill(appContext, entry, manga, urls, startPage, width)
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
                val decodeOrder = launchDecodeOrder(startPage, urls.size, sourceProfile.launchDecodePages)
                val byteOrder = launchDecodeOrder(startPage, urls.size, sourceProfile.launchBytePages)
                val decoded = HashSet<Int>()
                val existingBitmaps = entry.snapshot().bitmaps
                var decodedHeightPx = 0f
                val targetHeightPx = viewportHeightPx(appContext) * sourceProfile.launchDecodeScreenfuls
                for ((position, index) in decodeOrder.withIndex()) {
                    if (index != startPage && BackgroundPrefetchBudget.isNonCriticalPrefetchSuppressed()) break
                    val existing = existingBitmaps[index]
                    if (existing != null && !existing.isRecycled) {
                        decoded.add(index)
                        decodedHeightPx += drawnHeight(width, existing.width, existing.height)
                        val complete = position == decodeOrder.lastIndex || (decodedHeightPx >= targetHeightPx && index != startPage)
                        if (complete) entry.putBitmap(index, existing, index == startPage, true)
                        if (complete) break
                        continue
                    }
                    val bitmap = decodePage(appContext, manga, index, urls[index], width)
                    decoded.add(index)
                    decodedHeightPx += drawnHeight(width, bitmap.width, bitmap.height)
                    val complete = position == decodeOrder.lastIndex || (decodedHeightPx >= targetHeightPx && index != startPage)
                    entry.putBitmap(index, bitmap, index == startPage, complete)
                    if (complete) break
                }
                if (BackgroundPrefetchBudget.isNonCriticalPrefetchSuppressed()) return
                for (index in byteOrder) {
                    if (!decoded.contains(index)) fetchImageFile(appContext, manga, urls[index])
                }
            }
        }
    }

    private fun startFirstBitmapBackfill(
        appContext: Context,
        entry: ReaderPreparedStore.Entry,
        manga: Manga,
        urls: List<String>,
        startPage: Int,
        width: Int
    ) {
        if (p != null && p.getDataSave()) return
        if (BackgroundPrefetchBudget.isNonCriticalPrefetchSuppressed()) return
        if (!firstBitmapBackfillInFlight.add(entry.key)) return
        try {
            val sourceProfile = sourceProfile(entry.title ?: manga.title)
            val decodeOrder = launchDecodeOrder(
                startPage,
                urls.size,
                minOf(sourceProfile.launchDecodePages, FIRST_BITMAP_BACKFILL_PAGES)
            ).filter { it != startPage }
            val byteOrder = launchDecodeOrder(startPage, urls.size, sourceProfile.launchBytePages)
            val targetHeightPx = viewportHeightPx(appContext) * FIRST_BITMAP_BACKFILL_SCREENFULS
            val completion = AppDispatchers.ioCompletionService<Unit>()
            var submitted = 0
            var plannedHeightPx = 0f
            for (index in decodeOrder) {
                val image = urls.getOrNull(index) ?: continue
                val existing = entry.snapshot().bitmaps[index]
                if (existing != null && !existing.isRecycled) {
                    plannedHeightPx += drawnHeight(width, existing.width, existing.height)
                    continue
                }
                completion.submit(AppDispatchers.safeCallable {
                    val bitmap = decodePage(appContext, manga, index, image, width)
                    entry.putBitmap(index, bitmap, false, false)
                })
                submitted++
                plannedHeightPx += viewportHeightPx(appContext).toFloat()
                if (plannedHeightPx >= targetHeightPx) break
            }
            AppDispatchers.submitImageWarmup {
                try {
                    if (submitted > 0) {
                        repeat(submitted) {
                            try {
                                completion.take()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    for (index in byteOrder) {
                        if (index != startPage && index !in decodeOrder) fetchImageFile(appContext, manga, urls[index])
                    }
                } finally {
                    firstBitmapBackfillInFlight.remove(entry.key)
                }
            }
        } catch (e: Exception) {
            firstBitmapBackfillInFlight.remove(entry.key)
            ml.melun.mangaview.report.CrashReporter.record(e)
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

    private fun launchDecodeOrder(startPage: Int, count: Int, limit: Int): List<Int> {
        if (count <= 0 || limit <= 0) return emptyList()
        val result = ArrayList<Int>(minOf(count, limit))
        val anchor = startPage.coerceIn(0, count - 1)
        for (index in anchor until count) {
            if (result.size >= limit) return result
            result.add(index)
        }
        var index = anchor - 1
        while (index >= 0 && result.size < limit) {
            result.add(index)
            index--
        }
        return result
    }

    private fun visibleContinueProfile(title: Title?): WarmupProfile {
        if (p != null && p.getDataSave()) return WarmupProfile.URL_ONLY
        return WarmupProfile.FIRST_BYTE
    }

    private fun forwardVisibleProfile(): WarmupProfile =
        if (p != null && p.getDataSave()) WarmupProfile.URL_ONLY else WarmupProfile.FIRST_BYTE

    @JvmStatic
    fun visibleContinueProfileForTest(dataSave: Boolean): WarmupProfile =
        if (dataSave) WarmupProfile.URL_ONLY else WarmupProfile.FIRST_BYTE

    @JvmStatic
    fun forwardNextEpisodeIdForTest(current: Manga, title: Title?): Int =
        forwardNextEpisode(current, title)?.id ?: 0

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
        val source = (title?.sourceSite ?: "").trim().lowercase(Locale.ROOT)
        if (source == "wfwf" || (source.isEmpty() && getHttpClient()?.isNtk == false))
            return tapProfile(title)
        return WarmupProfile.LAUNCH_WINDOW
    }

    private fun shouldPinStart(profile: WarmupProfile): Boolean {
        return profile == WarmupProfile.FIRST_BITMAP || profile == WarmupProfile.LAUNCH_WINDOW
    }

    private fun sourceProfile(title: Title?): SourcePreloadProfile {
        return sourceProfile(title?.sourceSite)
    }

    private fun isNtkWarmup(title: Title?): Boolean {
        val source = (title?.sourceSite ?: "").trim().lowercase(Locale.ROOT)
        return source == "ntk" || p?.isNtkSite == true || getHttpClient()?.isNtk == true
    }

    private fun sourceProfile(sourceSite: String?): SourcePreloadProfile {
        val source = (sourceSite ?: "").trim().lowercase(Locale.ROOT).ifEmpty {
            if (getHttpClient()?.isNtk == false) "wfwf" else ""
        }
        return when (source) {
            "ntk" -> SourcePreloadProfile(
                visibleProfile = WarmupProfile.URL_ONLY,
                exactVisibleProfile = WarmupProfile.FIRST_BYTE,
                tapProfile = WarmupProfile.FIRST_BITMAP,
                launchDecodePages = NTK_LAUNCH_WINDOW_DECODE_PAGES,
                launchBytePages = NTK_LAUNCH_WINDOW_BYTE_PAGES,
                adjacentBytePages = 12,
                launchDecodeScreenfuls = NTK_LAUNCH_WINDOW_SCREENFULS
            )
            "wfwf" -> SourcePreloadProfile(
                visibleProfile = WarmupProfile.URL_ONLY,
                exactVisibleProfile = WarmupProfile.URL_ONLY,
                tapProfile = WarmupProfile.FIRST_BITMAP,
                launchDecodePages = WFWF_LAUNCH_WINDOW_DECODE_PAGES,
                launchBytePages = WFWF_LAUNCH_WINDOW_BYTE_PAGES,
                adjacentBytePages = 3,
                launchDecodeScreenfuls = WFWF_LAUNCH_WINDOW_SCREENFULS
            )
            else -> SourcePreloadProfile(
                visibleProfile = WarmupProfile.URL_ONLY,
                exactVisibleProfile = WarmupProfile.FIRST_BYTE,
                tapProfile = WarmupProfile.FIRST_BITMAP,
                launchDecodePages = DEFAULT_LAUNCH_WINDOW_DECODE_PAGES,
                launchBytePages = DEFAULT_LAUNCH_WINDOW_BYTE_PAGES,
                adjacentBytePages = 10,
                launchDecodeScreenfuls = DEFAULT_LAUNCH_WINDOW_SCREENFULS
            )
        }
    }

    private fun drawnHeight(targetWidth: Int, sourceWidth: Int, sourceHeight: Int): Float {
        if (targetWidth <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return 0f
        return targetWidth * (sourceHeight / sourceWidth.toFloat())
    }

    private fun viewportHeightPx(context: Context): Int {
        return max(1, context.resources.displayMetrics.heightPixels)
    }

    private fun attachTitle(manga: Manga, title: Title?) {
        if (title == null) return
        if (manga.title !== title) {
            manga.title = title
        } else if (manga.titleId != title.id) {
            manga.titleId = title.id
        }
        val episodes = Utils.snapshotEpisodes(title)
        if (episodes.isEmpty()) return
        val current = manga.eps
        val alreadyAttached = current != null && current.size == episodes.size &&
            current.indices.all { index -> current[index] === episodes[index] }
        if (!alreadyAttached) manga.setEps(episodes)
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
        val rawPath = manga.ntkEpisodePath ?: ""
        val canonicalPath = canonicalNtkEpisodePath(rawPath)
        val path = if (canonicalPath.isNotEmpty()) canonicalPath else rawPath.lowercase(Locale.ROOT)
        val episodeIdentity = if (canonicalPath.isNotEmpty()) "path" else manga.id.toString()
        val titleId = title?.id ?: manga.titleId
        return "reader:$source:${manga.baseMode}:$titleId:$episodeIdentity:$path:$startPage:$width:$exactEpisode"
    }

    private fun canonicalNtkEpisodePath(path: String?): String {
        val normalized = path?.trim()?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')
            ?.lowercase(Locale.ROOT).orEmpty()
        return Regex("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$", RegexOption.IGNORE_CASE)
            .matchEntire(normalized)?.value.orEmpty()
    }

    private fun fetchImageFile(context: Context, manga: Manga, image: String): File? {
        return if (manga.isOnline) ReaderImageCache.getOrFetchFile(context, manga, image) else null
    }

    private fun fetchVisibleImageFile(context: Context, manga: Manga, image: String): File? {
        return if (manga.isOnline) {
            ReaderImageCache.getOrFetchFileForeground(context, manga, image, null, true)
        } else {
            null
        }
    }

    private fun fetchAllImageBytesBlocking(context: Context, manga: Manga, images: List<String>) {
        if (!manga.isOnline || images.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(8, images.size)) { runnable ->
            Thread(runnable, "ReaderPreparedFullBytes").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
        try {
            val futures = images.map { image ->
                pool.submit<File?> {
                    ReaderImageCache.cachedFile(context, manga, image)
                        ?: ReaderImageCache.getOrFetchFileForeground(context, manga, image, null, true)
                }
            }
            for (future in futures) {
                future.get(45, TimeUnit.SECONDS)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun snapshotHasDrawable(snapshot: ReaderPreparedStore.Snapshot, index: Int): Boolean {
        val bitmap = snapshot.bitmaps[index]
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) return true
        val page = snapshot.tilePages[index] ?: return false
        return page.pageWidth > 0 && page.pageHeight > 0 && page.tiles.isNotEmpty() &&
            page.tiles.all { tile ->
                !tile.bitmap.isRecycled && tile.bitmap.width > 0 && tile.bitmap.height > 0 &&
                    tile.sourceWidth == page.pageWidth && tile.sourceHeight == page.pageHeight &&
                    tile.sourceTop >= 0 && tile.sourceBottom > tile.sourceTop &&
                    tile.sourceBottom <= page.pageHeight &&
                    tile.bitmap.hasImmutableExactPixelConfig() &&
                    tile.hasExactSourcePixelStorage()
            }
    }

    private fun snapshotHasAuthoritativeNtkRunwayTilePage(
        snapshot: ReaderPreparedStore.Snapshot,
        index: Int
    ): Boolean {
        if (snapshot.bitmaps.containsKey(index)) return false
        val expectedImage = snapshot.images?.getOrNull(index) ?: return false
        return isAuthoritativeNtkRunwayTilePage(snapshot.tilePages[index], expectedImage)
    }

    private fun isAuthoritativeNtkRunwayTilePage(
        page: ReaderPreparedStore.PreparedTilePage?,
        expectedImage: String? = null
    ): Boolean {
        if (page == null || page.pageWidth <= 0 || page.pageHeight <= 0 || page.tiles.isEmpty()) {
            return false
        }
        val proof = page.originalProof ?: return false
        if (proof.variant != ReaderPreparedStore.PreparedAssetVariant.ORIGINAL ||
            proof.inSampleSize != 1 || proof.postDecodeResized ||
            proof.originalWidth != page.pageWidth || proof.originalHeight != page.pageHeight ||
            proof.canonicalAsset.isEmpty()
        ) {
            return false
        }
        if (expectedImage != null &&
            proof.canonicalAsset != ReaderPreparedStore.canonicalOriginalAssetIdentity(expectedImage)
        ) {
            return false
        }
        if (!hasAuthoritativeNtkRunwayTileSourceLayout(
                page.pageHeight,
                page.tiles.map { it.sourceTop },
                page.tiles.map { it.sourceBottom }
            )
        ) {
            return false
        }
        return page.tiles.all { tile ->
                tile.sourceWidth == page.pageWidth &&
                tile.sourceHeight == page.pageHeight &&
                tile.bitmap.hasImmutableExactPixelConfig() &&
                tile.hasExactSourcePixelStorage()
        }
    }

    private fun hasAuthoritativeNtkRunwayTileSourceLayout(
        pageHeight: Int,
        sourceTops: List<Int>,
        sourceBottoms: List<Int>
    ): Boolean {
        if (pageHeight <= 0 || sourceTops.isEmpty() || sourceTops.size != sourceBottoms.size) {
            return false
        }
        var expectedTop = 0
        for (index in sourceTops.indices) {
            val top = sourceTops[index]
            val bottom = sourceBottoms[index]
            if (top != expectedTop || bottom <= top || bottom > pageHeight) return false
            val sourceSpan = bottom - top
            val tail = bottom == pageHeight
            if ((!tail && sourceSpan != NTK_PREPARED_TILE_SOURCE_HEIGHT) ||
                (tail && sourceSpan > NTK_PREPARED_TILE_SOURCE_HEIGHT)
            ) {
                return false
            }
            expectedTop = bottom
        }
        return expectedTop == pageHeight
    }

    private fun decodePreparedNtkDrawable(
        context: Context,
        manga: Manga,
        index: Int,
        image: String,
        width: Int,
        generatedSoftwarePolicy: GeneratedNtkSoftwarePolicy,
        requireImmutableTilePage: Boolean = false,
        pagePipeline: ReaderPagePipeline? = null,
        byteLease: ReaderPagePipeline.StageLease? = null,
        decodeLease: ReaderPagePipeline.StageLease? = null
    ): PreparedDrawable {
        if (isDirectGeneratedNtkImage(manga, image)) {
            decodePreparedNtkTilePage(
                context,
                manga,
                index,
                image,
                width,
                generatedSoftwarePolicy,
                authoritativeOriginal = requireImmutableTilePage,
                pagePipeline = pagePipeline,
                byteLease = byteLease,
                preclaimedDecodeLease = decodeLease
            )?.let { page ->
                if (requireImmutableTilePage &&
                    !isAuthoritativeNtkRunwayTilePage(page, image)
                ) {
                    for (tile in page.tiles) {
                        if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
                    }
                    generatedSoftwarePolicy.cancel(index)
                    throw java.io.IOException("Invalid authoritative launch runway tile page")
                }
                return PreparedDrawable(index = index, tilePage = page)
            }
        }
        if (requireImmutableTilePage) {
            generatedSoftwarePolicy.cancel(index)
            throw java.io.IOException("Authoritative launch runway region decode failed")
        }
        return PreparedDrawable(
            index = index,
            bitmap = decodePage(context, manga, index, image, width, generatedSoftwarePolicy)
        )
    }

    private fun decodePreparedNtkTilePage(
        context: Context,
        manga: Manga,
        index: Int,
        image: String,
        targetWidth: Int,
        generatedSoftwarePolicy: GeneratedNtkSoftwarePolicy,
        authoritativeOriginal: Boolean,
        pagePipeline: ReaderPagePipeline? = null,
        byteLease: ReaderPagePipeline.StageLease? = null,
        preclaimedDecodeLease: ReaderPagePipeline.StageLease? = null
    ): ReaderPreparedStore.PreparedTilePage? {
        var decodeLease: ReaderPagePipeline.StageLease? = preclaimedDecodeLease
        val lease = try {
            ReaderImageCache.leaseFile(context, manga, image, foreground = true)
        } catch (failure: Throwable) {
            if (pagePipeline != null && byteLease != null) {
                pagePipeline.failRetryable(index, byteLease.leaseId)
            }
            generatedSoftwarePolicy.cancel(index)
            return null
        }
        try {
            val source = lease.file
            if (pagePipeline != null && byteLease != null) {
                val canonical = ReaderPreparedStore.canonicalOriginalAssetIdentity(image)
                if (!pagePipeline.acceptBytes(
                        ReaderPagePipeline.ByteCompletion(
                            pagePipeline.episodeEpoch,
                            index,
                            canonical,
                            source.absolutePath,
                            byteLease.leaseId
                        )
                    )
                ) return null
                val decodeRequest = pagePipeline.requestDrawable(index, byteLease.demand)
                decodeLease = decodeRequest.lease
                if (decodeRequest.disposition != ReaderPagePipeline.RequestDisposition.STARTED_DECODE ||
                    decodeLease == null
                ) return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            val sourceWidth = bounds.outWidth
            val sourceHeight = bounds.outHeight
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                generatedSoftwarePolicy.cancel(index)
                return null
            }
            // A launch runway proves server-original quality, so it always decodes encoded source
            // pixels at sample 1 even when the encoded original is wider than the viewport.
            val sample = preparedNtkTileSampleSize(
                authoritativeOriginal,
                sourceWidth,
                targetWidth
            )
            val wantsSoftwareTiles = generatedSoftwarePolicy.chooseSoftware(
                index,
                estimatedArgbAllocationBytes(sourceWidth, sourceHeight, sample)
            )
            if (!wantsSoftwareTiles) return null
            val decoder = try {
                BitmapRegionDecoder.newInstance(source.absolutePath, false)
            } catch (_: Throwable) {
                null
            } ?: run {
                generatedSoftwarePolicy.cancel(index)
                return null
            }
            val tiles = ArrayList<ReaderTile>((sourceHeight + NTK_PREPARED_TILE_SOURCE_HEIGHT - 1) /
                NTK_PREPARED_TILE_SOURCE_HEIGHT)
            var success = false
            try {
                val rect = Rect()
                var top = 0
                while (top < sourceHeight) {
                    val bottom = minOf(sourceHeight, top + NTK_PREPARED_TILE_SOURCE_HEIGHT)
                    rect.set(0, top, sourceWidth, bottom)
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sample
                        inMutable = false
                        inScaled = false
                    }
                    val bitmap = decoder.decodeRegion(rect, options)
                        ?: throw java.io.IOException("Prepared tile region decode failed")
                    if (bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.isMutable ||
                        bitmap.width != sourceWidth || bitmap.height != bottom - top
                    ) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        throw java.io.IOException("Prepared tile must be immutable ARGB_8888")
                    }
                    tiles.add(ReaderTile(top, bottom, sourceWidth, sourceHeight, bitmap))
                    top = bottom
                }
                success = tiles.isNotEmpty()
                if (success) {
                    val actualBytes = tiles.sumOf { tile -> bitmapAllocationBytes(tile.bitmap) }
                    if (!generatedSoftwarePolicy.confirmSoftwareBytes(index, actualBytes)) {
                        success = false
                    }
                }
                return if (success) {
                    val proof = if (authoritativeOriginal) {
                        ReaderPreparedStore.PreparedOriginalProof(
                            canonicalAsset = ReaderPreparedStore.canonicalOriginalAssetIdentity(image),
                            variant = ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
                            originalWidth = sourceWidth,
                            originalHeight = sourceHeight,
                            inSampleSize = sample,
                            postDecodeResized = false
                        )
                    } else {
                        null
                    }
                    val page = ReaderPreparedStore.PreparedTilePage(sourceWidth, sourceHeight, tiles, proof)
                    if (pagePipeline != null && decodeLease != null) {
                        val canonical = ReaderPreparedStore.canonicalOriginalAssetIdentity(image)
                        val accepted = pagePipeline.acceptTiles(
                            ReaderPagePipeline.TileCompletion(
                                pagePipeline.episodeEpoch,
                                index,
                                canonical,
                                decodeLease!!.leaseId,
                                ReaderPagePipeline.TileIdentity(canonical, sourceWidth, sourceHeight)
                            ),
                            page
                        )
                        if (!accepted) {
                            success = false
                            null
                        } else {
                            page
                        }
                    } else {
                        page
                    }
                } else {
                    null
                }
            } catch (_: Throwable) {
                generatedSoftwarePolicy.cancel(index)
                return null
            } finally {
                if (!success) {
                    for (tile in tiles) {
                        if (!tile.bitmap.isRecycled) tile.bitmap.recycle()
                    }
                }
                decoder.recycle()
            }
        } catch (_: Throwable) {
            decodeLease?.let { pagePipeline?.failRetryable(index, it.leaseId) }
            generatedSoftwarePolicy.cancel(index)
            return null
        } finally {
            lease.close()
        }
    }

    private fun decodePage(
        context: Context,
        manga: Manga,
        index: Int,
        image: String,
        width: Int,
        generatedSoftwarePolicy: GeneratedNtkSoftwarePolicy? = null
    ): Bitmap {
        val directGeneratedNtk = isDirectGeneratedNtkImage(manga, image)
        if (!directGeneratedNtk) {
            // A mixed/legacy URL can still occupy a launch-order slot. Resolve it now so later
            // generated candidates never wait forever for a decision that cannot be made.
            generatedSoftwarePolicy?.cancel(index)
        }
        try {
            ViewerWarmupManager.getDecodedBitmap(PageItem(index, image, manga), false, p?.reverse ?: false, width)?.let {
                val reusableForPreparedSurface = when {
                    it.isRecycled -> false
                    !directGeneratedNtk -> true
                    generatedSoftwarePolicy != null -> generatedSoftwarePolicy.acceptExisting(index, it)
                    else -> it.config == Bitmap.Config.ARGB_8888 && !it.isMutable
                }
                if (reusableForPreparedSurface) {
                    return it
                }
            }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val metric = index == 0 && manga.isOnline
            val source = fetchVisibleImageFile(context, manga, image)
            val fetchedAt = android.os.SystemClock.elapsedRealtime()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (source != null) {
                BitmapFactory.decodeFile(source.absolutePath, bounds)
            } else {
                decodeLocal(context, image, bounds)
            }
            val boundsAt = android.os.SystemClock.elapsedRealtime()
            val sample = sampleSize(bounds.outWidth, width)
            val bitmapPool = if (directGeneratedNtk) null else Glide.get(context).bitmapPool
            // Always resolve the policy slot, including on pre-O devices where hardware bitmaps
            // do not exist. Otherwise rank zero can remain undecided and block parallel followers.
            val policyWantsSoftware = if (directGeneratedNtk) {
                generatedSoftwarePolicy?.chooseSoftware(
                    index,
                    estimatedArgbAllocationBytes(bounds.outWidth, bounds.outHeight, sample)
                ) ?: true
            } else {
                false
            }
            val wantsSoftware = directGeneratedNtk &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || policyWantsSoftware)
            var raw = (if (directGeneratedNtk) {
                decodeDirectGeneratedPage(context, source, image, sample, wantsSoftware)
            } else if (source != null) {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                }
                decodeFileWithBitmapPool(source, bounds, options, bitmapPool!!)
            } else {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                }
                decodeLocal(context, image, options)
            }) ?: throw java.io.IOException("Bitmap decode failed")
            if (directGeneratedNtk && wantsSoftware && generatedSoftwarePolicy != null &&
                !generatedSoftwarePolicy.confirmSoftware(index, raw)
            ) {
                // Bounds can under-report exotic formats. Enforce the real allocation cap by
                // replacing the just-decoded software bitmap with a hardware-backed instance.
                if (!raw.isRecycled) raw.recycle()
                raw = decodeDirectGeneratedPage(context, source, image, sample, false)
                    ?: throw java.io.IOException("Hardware bitmap decode failed")
            }
            val rawAt = android.os.SystemClock.elapsedRealtime()
            if (!manga.isOnline) return prepareDecodedBitmapForDraw(raw)
            val decoded = if (directGeneratedNtk) {
                raw
            } else {
                Decoder(manga.seed, manga.id).decode(raw, width, bitmapPool!!)
            }
            if (decoded !== raw && !raw.isRecycled) bitmapPool?.put(raw)
            if (metric) {
                val finishedAt = android.os.SystemClock.elapsedRealtime()
                ViewerWarmupManager.logMetric("reader_warmup_first_fetch_ms", fetchedAt - startedAt)
                ViewerWarmupManager.logMetric("reader_warmup_first_bounds_ms", boundsAt - fetchedAt)
                ViewerWarmupManager.logMetric("reader_warmup_first_raw_decode_ms", rawAt - boundsAt)
                ViewerWarmupManager.logMetric("reader_warmup_first_transform_ms", finishedAt - rawAt)
                ViewerWarmupManager.logMetric("reader_warmup_first_decode_ms", finishedAt - startedAt)
                ViewerWarmupManager.logMetric(
                    "reader_warmup_first_hardware_bitmap",
                    if (decoded.isHardwareConfigCompat()) 1L else 0L
                )
            }
            // The bounded software prefix is prepared later, after the production EpisodeActivity
            // ViewRoot is attached. Hardware pages need no Java-heap duplicate or hidden draw.
            return if (directGeneratedNtk) decoded else prepareDecodedBitmapForDraw(decoded)
        } catch (error: Throwable) {
            generatedSoftwarePolicy?.cancel(index)
            throw error
        }
    }

    /**
     * Gives HWUI a chance to prepare a genuinely decoded bitmap before the prepared store makes
     * it visible to the reader. Warmup decoding normally runs on an app dispatcher; if a legacy
     * blocking caller ever invokes it on the main thread, skip the best-effort hint instead of
     * moving GPU preparation onto the UI hot path.
     */
    private fun prepareDecodedBitmapForDraw(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled || Looper.myLooper() == Looper.getMainLooper()) return bitmap
        try {
            bitmap.prepareToDraw()
        } catch (_: Throwable) {
            // Rendering remains correct without the optional HWUI preparation hint.
        }
        return bitmap
    }

    /**
     * Exact generated NTK pages are immutable and are only read by the prepared store and
     * renderer. A small launch prefix uses dedicated software ARGB_8888 bitmaps because API 35
     * cannot pre-import a hardware bitmap into EpisodeActivity's RenderThread context. Remaining
     * pages stay hardware-backed so long episodes do not duplicate the whole chapter on the Java
     * heap. The software bitmap identities are handed intact to the attached Episode ViewRoot.
     *
     * This path deliberately avoids Glide's mutable inBitmap pool: the prepared store owns a
     * finite, width-sampled launch batch and the renderer must observe the exact same instances.
     */
    private fun decodeDirectGeneratedPage(
        context: Context,
        source: File?,
        image: String,
        sample: Int,
        software: Boolean
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = if (software || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Bitmap.Config.ARGB_8888
            } else {
                Bitmap.Config.HARDWARE
            }
            inMutable = false
            inSampleSize = max(1, sample)
        }
        val decoded = decodePageSource(context, source, image, options) ?: return null
        val desiredConfig = if (software || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Bitmap.Config.ARGB_8888
        } else {
            Bitmap.Config.HARDWARE
        }
        if (decoded.config == desiredConfig && !decoded.isMutable) return decoded
        val normalized = try {
            decoded.copy(desiredConfig, false)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (normalized !== decoded && !decoded.isRecycled) decoded.recycle()
        return normalized
    }

    private fun estimatedArgbAllocationBytes(width: Int, height: Int, sample: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        val safeSample = max(1, sample)
        val decodedWidth = (width.toLong() + safeSample - 1L) / safeSample
        val decodedHeight = (height.toLong() + safeSample - 1L) / safeSample
        return try {
            Math.multiplyExact(Math.multiplyExact(decodedWidth, decodedHeight), 4L)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    private fun bitmapAllocationBytes(bitmap: Bitmap): Long {
        return try {
            bitmap.allocationByteCount.toLong()
        } catch (_: Throwable) {
            bitmap.byteCount.toLong()
        }
    }

    private fun decodePageSource(
        context: Context,
        source: File?,
        image: String,
        options: BitmapFactory.Options
    ): Bitmap? {
        return if (source != null) {
            BitmapFactory.decodeFile(source.absolutePath, options)
        } else {
            decodeLocal(context, image, options)
        }
    }

    private fun decodeFileWithBitmapPool(
        source: File,
        bounds: BitmapFactory.Options,
        options: BitmapFactory.Options,
        bitmapPool: com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
    ): Bitmap? {
        val sample = max(1, options.inSampleSize)
        val decodedWidth = max(1, (bounds.outWidth + sample - 1) / sample)
        val decodedHeight = max(1, (bounds.outHeight + sample - 1) / sample)
        val decodeConfig = options.inPreferredConfig ?: Bitmap.Config.ARGB_8888
        val reusable = bitmapPool.getDirty(decodedWidth, decodedHeight, decodeConfig)
        options.inMutable = true
        options.inBitmap = reusable
        return try {
            val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
            if (decoded !== reusable && !reusable.isRecycled) bitmapPool.put(reusable)
            decoded
        } catch (_: IllegalArgumentException) {
            options.inBitmap = null
            if (!reusable.isRecycled) bitmapPool.put(reusable)
            BitmapFactory.decodeFile(source.absolutePath, options)
        }
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

    private fun isDirectGeneratedNtkImage(manga: Manga, image: String): Boolean {
        val path = manga.ntkEpisodePath?.trim().orEmpty()
        if (!path.startsWith("/manhwa/", ignoreCase = true) &&
            !path.startsWith("/webtoon/", ignoreCase = true)
        ) {
            return false
        }
        return Regex("/p\\d{3,6}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(image)
    }

    private fun preparedNtkTileSampleSize(
        authoritativeOriginal: Boolean,
        sourceWidth: Int,
        targetWidth: Int
    ): Int {
        return if (authoritativeOriginal) 1 else sampleSize(sourceWidth, targetWidth)
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
    fun exactVisibleProfileForTest(sourceSite: String?): WarmupProfile {
        return sourceProfile(sourceSite).exactVisibleProfile
    }

    @JvmStatic
    fun tapProfileForTest(sourceSite: String?): WarmupProfile {
        return sourceProfile(sourceSite).tapProfile
    }

    @JvmStatic
    fun launchProfileForTest(dataSave: Boolean): WarmupProfile {
        return launchProfileForTest(dataSave, null)
    }

    @JvmStatic
    fun launchProfileForTest(dataSave: Boolean, sourceSite: String?): WarmupProfile {
        if (dataSave) return WarmupProfile.URL_ONLY
        return if ((sourceSite ?: "").trim().lowercase(Locale.ROOT) == "wfwf")
            WarmupProfile.FIRST_BITMAP
        else
            WarmupProfile.LAUNCH_WINDOW
    }

    @JvmStatic
    fun adjacentByteLimitForTest(sourceSite: String?): Int {
        return sourceProfile(sourceSite).adjacentBytePages
    }

    @JvmStatic
    fun stableEpisodeComponentForTest(path: String?, mangaId: Int): String {
        val canonicalPath = canonicalNtkEpisodePath(path)
        val identity = if (canonicalPath.isNotEmpty()) "path" else mangaId.toString()
        val keyPath = if (canonicalPath.isNotEmpty()) canonicalPath
            else path?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return "$identity:$keyPath"
    }

    @JvmStatic
    fun authoritativeNtkRunwaySoftwarePageLimitForTest(): Int {
        return NTK_AUTHORITATIVE_RUNWAY_DECODE_PAGES
    }

    @JvmStatic
    fun authoritativeNtkRunwaySoftwareByteLimitForTest(): Long {
        return NTK_AUTHORITATIVE_RUNWAY_SOFTWARE_BYTE_LIMIT
    }

    @JvmStatic
    fun authoritativeNtkRunwayTileSourceHeightForTest(): Int {
        return NTK_PREPARED_TILE_SOURCE_HEIGHT
    }

    @JvmStatic
    fun isNtkLaunchRunwayOnlyForTest(decodeLimit: Int, imageCount: Int): Boolean {
        return isNtkLaunchRunwayOnly(true, decodeLimit, imageCount)
    }

    @JvmStatic
    fun hasAuthoritativeNtkRunwayTileSourceLayoutForTest(
        pageHeight: Int,
        sourceTops: IntArray,
        sourceBottoms: IntArray
    ): Boolean {
        return hasAuthoritativeNtkRunwayTileSourceLayout(
            pageHeight,
            sourceTops.toList(),
            sourceBottoms.toList()
        )
    }

    @JvmStatic
    fun authoritativeNtkRunwaySampleSizeForTest(sourceWidth: Int, targetWidth: Int): Int {
        return preparedNtkTileSampleSize(true, sourceWidth, targetWidth)
    }

    @JvmStatic
    fun claimMarksForegroundDuringStagingForTest(): Boolean = false
}
