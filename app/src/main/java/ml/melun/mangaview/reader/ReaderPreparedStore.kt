package ml.melun.mangaview.reader

import android.graphics.Bitmap
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

object ReaderPreparedStore {
    private const val MAX_ENTRIES = 24
    private const val DEFAULT_SOFT_BITMAP_BYTES = 16L * 1024L * 1024L
    private const val DEFAULT_HARD_BITMAP_BYTES = 24L * 1024L * 1024L
    private const val NTK_SOFT_BITMAP_BYTES = 96L * 1024L * 1024L
    private const val NTK_HARD_BITMAP_BYTES = 128L * 1024L * 1024L
    private const val MAX_PINNED_START_BITMAPS = 3
    private const val LAUNCH_RUNWAY_PAGE_GAP_PX = 0
    private const val LAUNCH_RUNWAY_TILE_SOURCE_HEIGHT_PX =
        ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX

    enum class Status {
        PENDING,
        URLS_READY,
        BYTES_READY,
        FIRST_BITMAP_READY,
        WINDOW_READY,
        FAILED
    }

    interface Listener {
        fun onUrlsReady(images: List<String>, startPage: Int)
        fun onBitmapReady(index: Int, bitmap: Bitmap)
        fun onBitmapBatchReady(bitmaps: Map<Int, Bitmap>) {
            for ((index, bitmap) in bitmaps) onBitmapReady(index, bitmap)
        }
        fun onTilePageBatchReady(tilePages: Map<Int, PreparedTilePage>) {}
        fun onFailed()
    }

    enum class PreparedAssetVariant {
        ORIGINAL,
        PREVIEW
    }

    /**
     * Immutable evidence emitted only by the authoritative original-file region decoder.
     *
     * [canonicalAsset] identifies the exact manifest item whose encoded bounds produced the
     * tiles. The dimensions and decode flags describe that encoded asset, not the View or a
     * post-decode copy.
     */
    data class PreparedOriginalProof @JvmOverloads constructor(
        val canonicalAsset: String,
        val variant: PreparedAssetVariant,
        val originalWidth: Int,
        val originalHeight: Int,
        val inSampleSize: Int,
        val postDecodeResized: Boolean = false
    )

    data class PreparedTilePage @JvmOverloads constructor(
        val pageWidth: Int,
        val pageHeight: Int,
        val tiles: List<ReaderTile>,
        val originalProof: PreparedOriginalProof? = null
    )

    data class Snapshot(
        val manga: Manga,
        val title: Title?,
        val images: List<String>?,
        val startPage: Int,
        val bitmaps: Map<Int, Bitmap>,
        val status: Status,
        val requestedWidth: Int = 0,
        val tilePages: Map<Int, PreparedTilePage> = emptyMap()
    )

    data class LaunchRunwaySpec(
        val viewportWidth: Int,
        val viewportHeight: Int,
        val startPage: Int,
        val startOffsetPx: Int,
        val pageGapPx: Int,
        val tileSourceHeightPx: Int,
        val requiredAheadPx: Int
    )

    data class PreparedHandoff(
        val pipelineId: Long,
        val storeGeneration: Long,
        val images: List<String>,
        val contiguousTileLast: Int,
        val tilePages: Map<Int, PreparedTilePage>,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val startPage: Int,
        val startOffset: Int
    )

    enum class PublishResult { ACCEPTED, REJECTED_STALE, REJECTED_HANDOFF }

    /**
     * Exclusive ownership token for a fully prepared launch window.
     *
     * The snapshot keeps the original bitmap identities; it never clones or
     * recycles them. Closing the lease only releases the store's ownership and
     * is intentionally idempotent.
     */
    class PreparedLease internal constructor(
        val snapshot: Snapshot,
        val entryRequestedWidth: Int,
        private val entry: Entry
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                releaseLease(entry)
            }
        }
    }

    /**
     * Exclusive, two-phase ownership token for a physically drawable launch runway.
     *
     * A reservation pins the exact entry but does not consume it. [commit] makes the
     * hand-off permanent, after which closing the lease releases the store entry just
     * like [PreparedLease]. Closing before commit only gives up the reservation.
     */
    class LaunchRunwayLease internal constructor(
        val spec: LaunchRunwaySpec,
        private val entry: Entry,
        private val reservationToken: Long
    ) : Closeable {
        private val stateLock = Any()
        private val listeners = LinkedHashSet<Listener>()
        private var closed = false
        private var committed = false
        private var activeHandoff: PreparedHandoff? = null

        fun latestSnapshot(): Snapshot? {
            return synchronized(stateLock) {
                if (closed) null else entry.snapshotForLaunchRunwayLease(reservationToken)
            }
        }

        fun subscribe(listener: Listener): Snapshot? {
            return synchronized(stateLock) {
                if (closed) return@synchronized null
                val snapshot = entry.addLaunchRunwayListener(reservationToken, listener)
                    ?: return@synchronized null
                listeners.add(listener)
                snapshot
            }
        }

        fun unsubscribe(listener: Listener) {
            val remove = synchronized(stateLock) {
                if (!listeners.remove(listener)) return@synchronized false
                true
            }
            if (remove) entry.removeListener(listener)
        }

        fun commit(): Boolean {
            return synchronized(stateLock) {
                if (closed) return@synchronized false
                if (committed) return@synchronized true
                val committedNow = commitLaunchRunway(entry, reservationToken)
                if (committedNow) committed = true
                committedNow
            }
        }

        fun beginHandoff(pipelineId: Long): PreparedHandoff? {
            return synchronized(stateLock) {
                if (closed || pipelineId <= 0L) return@synchronized null
                activeHandoff?.let { return@synchronized it }
                entry.beginLaunchRunwayHandoff(reservationToken, pipelineId, spec)?.also {
                    activeHandoff = it
                }
            }
        }

        fun commitHandoff(handoff: PreparedHandoff): Boolean {
            return synchronized(stateLock) {
                if (closed || activeHandoff !== handoff) return@synchronized false
                if (committed) return@synchronized true
                val committedNow = entry.commitLaunchRunwayHandoff(reservationToken, handoff)
                if (committedNow) committed = true
                committedNow
            }
        }

        override fun close() {
            val removeListeners = synchronized(stateLock) {
                if (closed) return
                closed = true
                val copy = listeners.toList()
                listeners.clear()
                copy
            }
            for (listener in removeListeners) entry.removeListener(listener)
            releaseLaunchRunway(entry, reservationToken)
        }
    }

    internal data class ClaimAttempt(
        val snapshot: Snapshot?,
        val rejection: String?
    )

    internal data class LaunchRunwayReserveAttempt(
        val snapshot: Snapshot?,
        val rejection: String?
    )

    internal data class LeaseRelease(
        val activeLeases: Int,
        val removeEntry: Boolean
    )

    class Entry internal constructor(
        val key: String,
        val manga: Manga,
        val title: Title?,
        val requestedStartPage: Int,
        val requestedWidth: Int,
        private var pinStartBitmap: Boolean
    ) {
        private val lock = Any()
        private val listeners = LinkedHashSet<Listener>()
        private val bitmapMap = LinkedHashMap<Int, Bitmap>()
        private val tilePageMap = LinkedHashMap<Int, PreparedTilePage>()
        private var imageUrls: List<String>? = null
        private var resolvedStartPage = requestedStartPage
        private var currentStatus = Status.PENDING
        private var activeLeaseCount = 0
        private var claimed = false
        private var launchRunwayReservationToken = 0L
        private var launchRunwayReservationCommitted = false
        private var launchRunwayHandoffStarted = false
        private var deferredClearBitmaps = false
        private var deferredRemoval = false

        fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

        /**
         * Latest contiguous authoritative original-tile watermark for the exact launch entry.
         * This is intentionally independent from the smaller physical activation proof: pages
         * decoded after that proof may already be safe source pixels, without ever enlarging the
         * pre-activation surface or its prepare-to-draw set.
         */
        fun contiguousLaunchRunwayTileLast(spec: LaunchRunwaySpec): Int = synchronized(lock) {
            if (!validLaunchRunwaySpec(spec)) return@synchronized spec.startPage - 1
            val images = imageUrls ?: return@synchronized spec.startPage - 1
            if (spec.startPage !in images.indices) return@synchronized spec.startPage - 1
            var index = spec.startPage
            var last = spec.startPage - 1
            while (index < images.size) {
                if (bitmapMap.containsKey(index)) break
                val page = tilePageMap[index] ?: break
                if (!usableLaunchRunwayTilePageLocked(page, spec, images[index])) break
                last = index
                index++
            }
            last
        }

        fun addListener(listener: Listener): Snapshot {
            return synchronized(lock) {
                listeners.add(listener)
                snapshotLocked()
            }
        }

        fun removeListener(listener: Listener) {
            synchronized(lock) {
                listeners.remove(listener)
            }
        }

        fun updateStartPin(pin: Boolean) {
            synchronized(lock) {
                if (pin) pinStartBitmap = true
            }
            trimBitmapBudget()
        }

        fun clearBitmaps() {
            val deferred = synchronized(lock) {
                if (activeLeaseCount > 0) {
                    deferredClearBitmaps = true
                    true
                } else {
                    bitmapMap.clear()
                    tilePageMap.clear()
                    pinStartBitmap = false
                    false
                }
            }
            if (deferred) ViewerWarmupManager.logMetric("prepared_lease_clear_deferred", 1L)
            trimBitmapBudget()
        }

        fun setImages(images: List<String>, startPage: Int) {
            val callbacks: List<Listener>
            val safeImages = ArrayList(images)
            synchronized(lock) {
                imageUrls = safeImages
                resolvedStartPage = startPage
                if (currentStatus == Status.PENDING || currentStatus == Status.FAILED) currentStatus = Status.URLS_READY
                callbacks = listeners.toList()
            }
            for (listener in callbacks) listener.onUrlsReady(safeImages, startPage)
        }

        fun markBytesReady() {
            synchronized(lock) {
                if (currentStatus == Status.PENDING ||
                    currentStatus == Status.URLS_READY ||
                    currentStatus == Status.FAILED
                ) {
                    currentStatus = Status.BYTES_READY
                }
            }
        }

        fun putBitmap(index: Int, bitmap: Bitmap, first: Boolean, windowComplete: Boolean) {
            putBitmapInternal(index, bitmap, first, windowComplete, true)
        }

        fun putBitmapBatch(bitmaps: Map<Int, Bitmap>, windowComplete: Boolean) {
            putBitmapBatchInternal(bitmaps, windowComplete, true)
        }

        fun putDrawableBatch(
            bitmaps: Map<Int, Bitmap>,
            tilePages: Map<Int, PreparedTilePage>,
            windowComplete: Boolean
        ): PublishResult = putDrawableBatchInternal(bitmaps, tilePages, windowComplete, true)

        internal fun putBitmapFromCompatible(index: Int, bitmap: Bitmap, first: Boolean, windowComplete: Boolean) {
            putBitmapInternal(index, bitmap, first, windowComplete, false)
        }

        private fun putBitmapInternal(
            index: Int,
            bitmap: Bitmap,
            first: Boolean,
            windowComplete: Boolean,
            mirrorCompatible: Boolean
        ) {
            val callbacks: List<Listener>
            synchronized(lock) {
                tilePageMap.remove(index)
                bitmapMap[index] = bitmap
                currentStatus = when {
                    windowComplete -> Status.WINDOW_READY
                    first || currentStatus == Status.URLS_READY || currentStatus == Status.PENDING -> Status.FIRST_BITMAP_READY
                    else -> currentStatus
                }
                callbacks = listeners.toList()
            }
            trimBitmapBudget()
            for (listener in callbacks) listener.onBitmapReady(index, bitmap)
            if (mirrorCompatible) mirrorCompatibleBitmap(key, index, bitmap, first, windowComplete)
        }

        private fun putBitmapBatchInternal(
            bitmaps: Map<Int, Bitmap>,
            windowComplete: Boolean,
            mirrorCompatible: Boolean
        ) {
            if (bitmaps.isEmpty()) return
            val safeBitmaps = LinkedHashMap(bitmaps)
            val callbacks: List<Listener>
            synchronized(lock) {
                for (index in safeBitmaps.keys) tilePageMap.remove(index)
                bitmapMap.putAll(safeBitmaps)
                currentStatus = if (windowComplete) Status.WINDOW_READY else Status.FIRST_BITMAP_READY
                callbacks = listeners.toList()
            }
            trimBitmapBudget()
            for (listener in callbacks) listener.onBitmapBatchReady(safeBitmaps)
            if (mirrorCompatible) mirrorCompatibleBitmapBatch(key, safeBitmaps, windowComplete)
        }

        internal fun putBitmapBatchFromCompatible(
            bitmaps: Map<Int, Bitmap>,
            windowComplete: Boolean
        ) {
            putBitmapBatchInternal(bitmaps, windowComplete, false)
        }

        internal fun putDrawableBatchFromCompatible(
            bitmaps: Map<Int, Bitmap>,
            tilePages: Map<Int, PreparedTilePage>,
            windowComplete: Boolean
        ): PublishResult = putDrawableBatchInternal(bitmaps, tilePages, windowComplete, false)

        private fun putDrawableBatchInternal(
            bitmaps: Map<Int, Bitmap>,
            tilePages: Map<Int, PreparedTilePage>,
            windowComplete: Boolean,
            mirrorCompatible: Boolean
        ): PublishResult {
            if (bitmaps.isEmpty() && tilePages.isEmpty()) return PublishResult.ACCEPTED
            val safeBitmaps = LinkedHashMap(bitmaps)
            val safeTilePages = LinkedHashMap<Int, PreparedTilePage>(tilePages.size)
            for ((index, page) in tilePages) {
                safeTilePages[index] = page.copy(
                    tiles = Collections.unmodifiableList(ArrayList(page.tiles))
                )
            }
            val callbacks: List<Listener>
            synchronized(lock) {
                if (launchRunwayHandoffStarted) return PublishResult.REJECTED_HANDOFF
                for (index in safeBitmaps.keys) tilePageMap.remove(index)
                for (index in safeTilePages.keys) bitmapMap.remove(index)
                bitmapMap.putAll(safeBitmaps)
                tilePageMap.putAll(safeTilePages)
                currentStatus = if (windowComplete) Status.WINDOW_READY else Status.FIRST_BITMAP_READY
                callbacks = listeners.toList()
            }
            trimBitmapBudget()
            if (safeBitmaps.isNotEmpty()) {
                for (listener in callbacks) listener.onBitmapBatchReady(safeBitmaps)
            }
            if (safeTilePages.isNotEmpty()) {
                for (listener in callbacks) listener.onTilePageBatchReady(safeTilePages)
            }
            if (mirrorCompatible) {
                mirrorCompatibleDrawableBatch(key, safeBitmaps, safeTilePages, windowComplete)
            }
            return PublishResult.ACCEPTED
        }

        fun fail() {
            val callbacks: List<Listener>
            synchronized(lock) {
                currentStatus = Status.FAILED
                callbacks = listeners.toList()
            }
            for (listener in callbacks) listener.onFailed()
        }

        private fun snapshotLocked(): Snapshot {
            return Snapshot(
                manga = manga,
                title = title,
                images = imageUrls?.let { Collections.unmodifiableList(ArrayList(it)) },
                startPage = resolvedStartPage,
                bitmaps = Collections.unmodifiableMap(LinkedHashMap(bitmapMap)),
                status = currentStatus,
                requestedWidth = requestedWidth,
                tilePages = Collections.unmodifiableMap(LinkedHashMap(tilePageMap))
            )
        }

        internal fun tryClaimWindowReady(expectedManga: Manga?, expectedWidth: Int): ClaimAttempt {
            return synchronized(lock) {
                when {
                    claimed || launchRunwayReservationToken != 0L || deferredRemoval -> {
                        ClaimAttempt(null, "claimed")
                    }
                    currentStatus != Status.WINDOW_READY -> ClaimAttempt(null, "status")
                    imageUrls.isNullOrEmpty() -> ClaimAttempt(null, "images")
                    !Manga.sameEpisodeIdentity(manga, expectedManga) -> ClaimAttempt(null, "identity")
                    expectedWidth > 0 && requestedWidth > 0 && requestedWidth != expectedWidth -> {
                        ClaimAttempt(null, "width")
                    }
                    imageUrls!!.indices.any { !usableDrawableLocked(it) } -> {
                        ClaimAttempt(null, "drawable")
                    }
                    else -> {
                        claimed = true
                        deferredRemoval = true
                        activeLeaseCount++
                        ClaimAttempt(snapshotLocked(), null)
                    }
                }
            }
        }

        internal fun tryReserveLaunchRunway(
            reservationToken: Long,
            expectedManga: Manga?,
            expectedImages: List<String>,
            spec: LaunchRunwaySpec
        ): LaunchRunwayReserveAttempt {
            return synchronized(lock) {
                val rejection = launchRunwayRejectionLocked(expectedManga, expectedImages, spec)
                if (rejection != null) {
                    LaunchRunwayReserveAttempt(null, rejection)
                } else {
                    launchRunwayReservationToken = reservationToken
                    launchRunwayReservationCommitted = false
                    launchRunwayHandoffStarted = false
                    activeLeaseCount++
                    LaunchRunwayReserveAttempt(snapshotLocked(), null)
                }
            }
        }

        /**
         * Atomically restores Pipeline-owned immutable tiles and reserves them for hand-off.
         *
         * The Store budget may discard its own map entries while ReaderPagePipeline still owns
         * the exact tile payload. Restoring and reserving under one entry lock prevents the budget
         * trimmer from opening a gap between those two operations; no bitmap is cloned or decoded.
         */
        internal fun tryReserveLaunchRunwayFromPipeline(
            reservationToken: Long,
            expectedManga: Manga?,
            expectedImages: List<String>,
            spec: LaunchRunwaySpec,
            pipelineTilePages: Map<Int, PreparedTilePage>
        ): LaunchRunwayReserveAttempt {
            return synchronized(lock) {
                if (claimed || launchRunwayReservationToken != 0L || deferredRemoval) {
                    return@synchronized LaunchRunwayReserveAttempt(null, "claimed")
                }
                for ((index, page) in pipelineTilePages) {
                    if (index !in expectedImages.indices ||
                        !isCanonicalOriginalTilePage(page, expectedImages[index], spec.tileSourceHeightPx)
                    ) continue
                    bitmapMap.remove(index)
                    tilePageMap[index] = page.copy(
                        tiles = Collections.unmodifiableList(ArrayList(page.tiles))
                    )
                }
                if (pipelineTilePages.isNotEmpty()) currentStatus = Status.FIRST_BITMAP_READY
                val rejection = launchRunwayRejectionLocked(expectedManga, expectedImages, spec)
                if (rejection != null) {
                    LaunchRunwayReserveAttempt(null, rejection)
                } else {
                    launchRunwayReservationToken = reservationToken
                    launchRunwayReservationCommitted = false
                    launchRunwayHandoffStarted = false
                    activeLeaseCount++
                    LaunchRunwayReserveAttempt(snapshotLocked(), null)
                }
            }
        }

        internal fun snapshotForLaunchRunwayLease(reservationToken: Long): Snapshot? {
            return synchronized(lock) {
                if (launchRunwayReservationToken != reservationToken || activeLeaseCount <= 0) {
                    null
                } else {
                    snapshotLocked()
                }
            }
        }

        internal fun addLaunchRunwayListener(
            reservationToken: Long,
            listener: Listener
        ): Snapshot? {
            return synchronized(lock) {
                if (launchRunwayReservationToken != reservationToken || activeLeaseCount <= 0 ||
                    launchRunwayHandoffStarted
                ) {
                    null
                } else {
                    listeners.add(listener)
                    snapshotLocked()
                }
            }
        }

        internal fun beginLaunchRunwayHandoff(
            reservationToken: Long,
            pipelineId: Long,
            spec: LaunchRunwaySpec
        ): PreparedHandoff? = synchronized(lock) {
            if (launchRunwayReservationToken != reservationToken || activeLeaseCount <= 0 ||
                launchRunwayReservationCommitted || launchRunwayHandoffStarted
            ) return@synchronized null
            val images = imageUrls ?: return@synchronized null
            launchRunwayHandoffStarted = true
            listeners.clear()
            var contiguousLast = spec.startPage - 1
            var index = spec.startPage
            while (index < images.size) {
                val page = tilePageMap[index] ?: break
                if (!usableLaunchRunwayTilePageLocked(page, spec, images[index])) break
                contiguousLast = index
                index++
            }
            PreparedHandoff(
                pipelineId = pipelineId,
                storeGeneration = reservationToken,
                images = Collections.unmodifiableList(ArrayList(images)),
                contiguousTileLast = contiguousLast,
                tilePages = Collections.unmodifiableMap(LinkedHashMap(tilePageMap)),
                viewportWidth = spec.viewportWidth,
                viewportHeight = spec.viewportHeight,
                startPage = spec.startPage,
                startOffset = spec.startOffsetPx
            )
        }

        internal fun commitLaunchRunwayHandoff(
            reservationToken: Long,
            handoff: PreparedHandoff
        ): Boolean = synchronized(lock) {
            if (launchRunwayReservationToken != reservationToken ||
                handoff.storeGeneration != reservationToken || !launchRunwayHandoffStarted ||
                activeLeaseCount <= 0
            ) return@synchronized false
            for ((index, page) in handoff.tilePages) tilePageMap.remove(index, page)
            for (index in handoff.tilePages.keys) bitmapMap.remove(index)
            launchRunwayReservationCommitted = true
            claimed = true
            deferredRemoval = true
            true
        }

        internal fun commitLaunchRunway(reservationToken: Long): Boolean {
            return synchronized(lock) {
                if (launchRunwayReservationToken != reservationToken || activeLeaseCount <= 0) {
                    false
                } else if (launchRunwayReservationCommitted) {
                    true
                } else {
                    launchRunwayReservationCommitted = true
                    claimed = true
                    deferredRemoval = true
                    true
                }
            }
        }

        internal fun releaseLaunchRunway(reservationToken: Long): LeaseRelease {
            return synchronized(lock) {
                if (launchRunwayReservationToken != reservationToken || activeLeaseCount <= 0) {
                    return@synchronized LeaseRelease(activeLeaseCount, false)
                }
                launchRunwayReservationToken = 0L
                launchRunwayReservationCommitted = false
                launchRunwayHandoffStarted = false
                releaseLeaseLocked()
            }
        }

        private fun launchRunwayRejectionLocked(
            expectedManga: Manga?,
            expectedImages: List<String>,
            spec: LaunchRunwaySpec
        ): String? {
            if (claimed || launchRunwayReservationToken != 0L || deferredRemoval) return "claimed"
            if (currentStatus != Status.FIRST_BITMAP_READY && currentStatus != Status.WINDOW_READY) {
                return "status"
            }
            if (!validLaunchRunwaySpec(spec)) return "spec"
            val images = imageUrls ?: return "images"
            if (images.isEmpty() || images != expectedImages) return "manifest"
            if (!Manga.sameEpisodeIdentity(manga, expectedManga)) return "identity"
            if (requestedWidth != spec.viewportWidth) return "width"
            if (resolvedStartPage != spec.startPage || spec.startPage !in images.indices) return "start"
            if (!hasLaunchRunwayCoverageLocked(spec, images)) return "drawable"
            return null
        }

        private fun hasLaunchRunwayCoverageLocked(spec: LaunchRunwaySpec, images: List<String>): Boolean {
            val requiredPixels = spec.viewportHeight.toLong() + spec.requiredAheadPx.toLong()
            if (requiredPixels <= 0L) return false
            var coveredPixels = 0.0
            var index = spec.startPage
            while (index < images.size) {
                if (bitmapMap.containsKey(index)) return false
                val page = tilePageMap[index] ?: return false
                if (!usableLaunchRunwayTilePageLocked(page, spec, images[index])) return false
                val renderedHeight = renderedLaunchRunwayPageHeight(page, spec.viewportWidth)
                if (renderedHeight <= 0.0) return false
                if (index == spec.startPage) {
                    if (!validLaunchRunwayStartOffset(spec.startOffsetPx, renderedHeight)) return false
                    coveredPixels += initialLaunchRunwayPageContribution(
                        renderedHeight,
                        spec.startOffsetPx
                    )
                } else {
                    coveredPixels += renderedHeight
                }
                if (coveredPixels + 0.0001 >= requiredPixels.toDouble()) return true
                index++
            }
            // The physical runway may legitimately end with the episode. Every remaining
            // authoritative source pixel was proven above, so a shorter tail is complete rather
            // than missing. The Surface activation predicate separately enforces viewport fill.
            return index >= images.size && coveredPixels > 0.0
        }

        private fun usableLaunchRunwayTilePageLocked(
            page: PreparedTilePage,
            spec: LaunchRunwaySpec,
            expectedImage: String
        ): Boolean {
            return isCanonicalOriginalTilePage(page, expectedImage, spec.tileSourceHeightPx)
        }

        internal fun requestRemoval(): Boolean = synchronized(lock) {
            if (activeLeaseCount <= 0) return@synchronized false
            deferredRemoval = true
            true
        }

        internal fun releaseLease(): LeaseRelease = synchronized(lock) {
            if (activeLeaseCount <= 0) return@synchronized LeaseRelease(0, false)
            releaseLeaseLocked()
        }

        private fun releaseLeaseLocked(): LeaseRelease {
            activeLeaseCount--
            val removeEntry = activeLeaseCount == 0 && deferredRemoval
            if (activeLeaseCount == 0 && (deferredClearBitmaps || deferredRemoval)) {
                bitmapMap.clear()
                tilePageMap.clear()
                pinStartBitmap = false
                deferredClearBitmaps = false
            }
            return LeaseRelease(activeLeaseCount, removeEntry)
        }

        internal fun activeLeases(): Int = synchronized(lock) { activeLeaseCount }

        internal fun bitmapBytes(): Long = synchronized(lock) {
            bitmapMap.values.sumOf { bitmapBytes(it).toLong() } +
                tilePageMap.values.sumOf { page ->
                    val seen = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
                    page.tiles.sumOf { tile ->
                        if (seen.add(tile.bitmap)) bitmapBytes(tile.bitmap).toLong() else 0L
                    }
                }
        }

        internal fun trimOldestBitmap(allowPinnedStart: Boolean): Boolean = synchronized(lock) {
            if (activeLeaseCount > 0) return@synchronized false
            val iterator = bitmapMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == resolvedStartPage && pinStartBitmap && !allowPinnedStart) continue
                iterator.remove()
                tilePageMap.remove(entry.key)
                if (entry.key == resolvedStartPage) {
                    pinStartBitmap = false
                    ViewerWarmupManager.logMetric("prepared_start_evicted", 1L)
                }
                return true
            }
            val tileIterator = tilePageMap.entries.iterator()
            while (tileIterator.hasNext()) {
                val entry = tileIterator.next()
                if (entry.key == resolvedStartPage && pinStartBitmap && !allowPinnedStart) continue
                tileIterator.remove()
                if (entry.key == resolvedStartPage) {
                    pinStartBitmap = false
                    ViewerWarmupManager.logMetric("prepared_start_evicted", 1L)
                }
                return true
            }
            false
        }

        internal fun hasPinnedStartBitmap(): Boolean = synchronized(lock) {
            pinStartBitmap && usableDrawableLocked(resolvedStartPage)
        }

        internal fun demoteStartPin(): Boolean = synchronized(lock) {
            if (activeLeaseCount > 0) return@synchronized false
            if (!pinStartBitmap) return@synchronized false
            pinStartBitmap = false
            true
        }

        internal fun hasBitmap(): Boolean = synchronized(lock) {
            bitmapMap.values.any { usableBitmapLocked(it) } ||
                tilePageMap.values.any { usableTilePageLocked(it) }
        }

        internal fun sourceSite(): String {
            return (title?.sourceSite ?: "").trim().lowercase(Locale.ROOT)
        }

        internal fun isFailed(): Boolean = synchronized(lock) {
            currentStatus == Status.FAILED
        }

        private fun usableBitmapLocked(bitmap: Bitmap?): Boolean {
            return bitmap != null && !bitmap.isRecycled
        }

        private fun usableDrawableLocked(index: Int): Boolean {
            return usableBitmapLocked(bitmapMap[index]) || usableTilePageLocked(tilePageMap[index])
        }

        private fun usableTilePageLocked(page: PreparedTilePage?): Boolean {
            if (page == null || page.pageWidth <= 0 || page.pageHeight <= 0 || page.tiles.isEmpty()) {
                return false
            }
            var expectedTop = 0
            for (tile in page.tiles) {
                if (tile.sourceWidth != page.pageWidth ||
                    tile.sourceHeight != page.pageHeight ||
                    tile.sourceTop != expectedTop || tile.sourceBottom <= tile.sourceTop ||
                    tile.sourceBottom > page.pageHeight || tile.bitmap.isRecycled ||
                    !tile.hasExactSourcePixelStorage() ||
                    tile.bitmap.config != Bitmap.Config.ARGB_8888 || tile.bitmap.isMutable
                ) {
                    return false
                }
                expectedTop = tile.sourceBottom
            }
            return expectedTop == page.pageHeight
        }
    }

    private fun validLaunchRunwaySpec(spec: LaunchRunwaySpec): Boolean {
        return spec.viewportWidth > 0 &&
            spec.viewportHeight > 0 &&
            spec.startPage >= 0 &&
            spec.startOffsetPx >= 0 &&
            spec.requiredAheadPx >= 0 &&
            spec.pageGapPx == LAUNCH_RUNWAY_PAGE_GAP_PX &&
            spec.tileSourceHeightPx == LAUNCH_RUNWAY_TILE_SOURCE_HEIGHT_PX
    }

    private fun renderedLaunchRunwayPageHeight(page: PreparedTilePage, viewportWidth: Int): Double {
        if (page.pageWidth <= 0 || page.pageHeight <= 0 || viewportWidth <= 0) return 0.0
        return max(1.0, viewportWidth.toDouble() * page.pageHeight.toDouble() / page.pageWidth.toDouble())
    }

    /** startOffsetPx is the number of rendered pixels already consumed from the first page. */
    private fun validLaunchRunwayStartOffset(startOffsetPx: Int, renderedHeight: Double): Boolean {
        return startOffsetPx >= 0 && startOffsetPx.toDouble() < renderedHeight
    }

    private fun initialLaunchRunwayPageContribution(
        renderedHeight: Double,
        startOffsetPx: Int
    ): Double {
        return renderedHeight - startOffsetPx.toDouble()
    }

    private val entries = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private val launchRunwayReservationSequence = AtomicLong()

    /** Authoritative manifests already contain canonical original URLs; preserve them exactly. */
    @JvmStatic
    fun canonicalOriginalAssetIdentity(asset: String?): String = asset?.trim().orEmpty()

    /** Exact immutable source-tile contract shared by Store reservation and Session handoff. */
    @JvmStatic
    @JvmOverloads
    fun isCanonicalOriginalTilePage(
        page: PreparedTilePage,
        expectedImage: String?,
        tileSourceHeightPx: Int = LAUNCH_RUNWAY_TILE_SOURCE_HEIGHT_PX
    ): Boolean {
        val proof = page.originalProof
        if (tileSourceHeightPx <= 0 ||
            !isCanonicalOriginalProof(proof, expectedImage, page.pageWidth, page.pageHeight) ||
            page.pageWidth <= 0 ||
            page.pageHeight <= 0 || page.tiles.isEmpty()
        ) return false
        var expectedTop = 0
        for (tile in page.tiles) {
            val span = tile.sourceBottom - tile.sourceTop
            val tail = tile.sourceBottom == page.pageHeight
            if (tile.sourceWidth != page.pageWidth || tile.sourceHeight != page.pageHeight ||
                tile.sourceTop != expectedTop || span <= 0 || tile.sourceBottom > page.pageHeight ||
                (!tail && span != tileSourceHeightPx) || (tail && span > tileSourceHeightPx) ||
                tile.bitmap.isRecycled || !tile.hasExactSourcePixelStorage() ||
                tile.bitmap.config != Bitmap.Config.ARGB_8888 ||
                tile.bitmap.isMutable
            ) return false
            expectedTop = tile.sourceBottom
        }
        return expectedTop == page.pageHeight
    }

    @JvmStatic
    fun isCanonicalOriginalProof(
        proof: PreparedOriginalProof?,
        expectedImage: String?,
        pageWidth: Int,
        pageHeight: Int
    ): Boolean {
        val expectedAsset = canonicalOriginalAssetIdentity(expectedImage)
        return expectedAsset.isNotEmpty() && pageWidth > 0 && pageHeight > 0 &&
            proof != null && proof.variant == PreparedAssetVariant.ORIGINAL &&
            proof.canonicalAsset == expectedAsset && proof.inSampleSize == 1 &&
            !proof.postDecodeResized && proof.originalWidth == pageWidth &&
            proof.originalHeight == pageHeight
    }

    @JvmStatic
    @Synchronized
    fun createOrGet(key: String, manga: Manga, title: Title?, startPage: Int, width: Int): Entry {
        return createOrGet(key, manga, title, startPage, width, false)
    }

    @JvmStatic
    @Synchronized
    fun createOrGet(key: String, manga: Manga, title: Title?, startPage: Int, width: Int, pinStartBitmap: Boolean): Entry {
        val existing = entries[key]
        if (existing != null) {
            if (shouldReplaceExistingEntry(existing)) {
                if (existing.requestRemoval()) return existing
                entries.remove(key)
            } else {
                existing.updateStartPin(pinStartBitmap)
                return existing
            }
        }
        val entry = Entry(key, manga, title, startPage, width, pinStartBitmap)
        entries[key] = entry
        trimLocked()
        return entry
    }

    @JvmStatic
    @Synchronized
    fun get(key: String?): Entry? {
        if (key.isNullOrEmpty()) return null
        return entries[key]
    }

    @JvmStatic
    @Synchronized
    fun findReadyCompatible(key: String?): Entry? {
        if (key.isNullOrEmpty()) return null
        entries[key]?.let { entry ->
            if (entry.hasBitmap()) return entry
        }
        val prefix = compatiblePrefix(key) ?: return null
        return entries.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) && it.value.hasBitmap() }
            .map { it.value }
            .firstOrNull()
    }

    /**
     * Atomically consumes the exact prepared entry when its complete bitmap
     * window is compatible with the requested episode and render width.
     * Compatible-prefix fallback is deliberately not used here.
     */
    @JvmStatic
    @Synchronized
    fun claimWindowReady(key: String?, expectedManga: Manga?, requestedWidth: Int): PreparedLease? {
        if (key.isNullOrEmpty()) {
            logClaimRejected("key")
            return null
        }
        val entry = entries[key]
        if (entry == null) {
            logClaimRejected("missing")
            return null
        }
        val attempt = entry.tryClaimWindowReady(expectedManga, requestedWidth)
        val snapshot = attempt.snapshot
        if (snapshot == null) {
            logClaimRejected(attempt.rejection ?: "unknown")
            return null
        }
        ViewerWarmupManager.logMetric("prepared_claim_success", 1L)
        ViewerWarmupManager.logMetric("prepared_active_leases", activeLeaseCountLocked().toLong())
        return PreparedLease(snapshot, entry.requestedWidth, entry)
    }

    /**
     * Atomically reserves the exact prepared entry when its tile-only launch runway is already
     * physically large enough for the requested viewport and forward runway. No compatible-key
     * fallback is allowed.
     */
    @JvmStatic
    @Synchronized
    fun reserveLaunchRunway(
        key: String?,
        expectedManga: Manga?,
        expectedImages: List<String>?,
        spec: LaunchRunwaySpec?
    ): LaunchRunwayLease? {
        if (key.isNullOrEmpty()) {
            logLaunchRunwayRejected("key")
            return null
        }
        if (expectedManga == null || expectedImages.isNullOrEmpty() || spec == null) {
            logLaunchRunwayRejected("arguments")
            return null
        }
        val safeExpectedImages = ArrayList(expectedImages)
        val entry = entries[key]
        if (entry == null) {
            logLaunchRunwayRejected("missing")
            return null
        }
        val token = launchRunwayReservationSequence.incrementAndGet().coerceAtLeast(1L)
        val attempt = entry.tryReserveLaunchRunway(token, expectedManga, safeExpectedImages, spec)
        if (attempt.snapshot == null) {
            logLaunchRunwayRejected(attempt.rejection ?: "unknown")
            return null
        }
        ViewerWarmupManager.logMetric("prepared_runway_reserve_success", 1L)
        ViewerWarmupManager.logMetric("prepared_active_leases", activeLeaseCountLocked().toLong())
        return LaunchRunwayLease(spec, entry, token)
    }

    @JvmStatic
    @Synchronized
    fun reserveLaunchRunwayFromPipeline(
        key: String?,
        expectedManga: Manga?,
        expectedImages: List<String>?,
        spec: LaunchRunwaySpec?,
        pipeline: ReaderPagePipeline?
    ): LaunchRunwayLease? {
        if (key.isNullOrEmpty() || expectedManga == null || expectedImages.isNullOrEmpty() ||
            spec == null || pipeline == null
        ) {
            logLaunchRunwayRejected("pipeline_arguments")
            return null
        }
        val entry = entries[key] ?: run {
            logLaunchRunwayRejected("pipeline_missing")
            return null
        }
        val safeExpectedImages = ArrayList(expectedImages)
        val pipelinePages = LinkedHashMap<Int, PreparedTilePage>()
        for (index in safeExpectedImages.indices) {
            pipeline.preparedTilePage(index)?.let { pipelinePages[index] = it }
        }
        val token = launchRunwayReservationSequence.incrementAndGet().coerceAtLeast(1L)
        val attempt = entry.tryReserveLaunchRunwayFromPipeline(
            token,
            expectedManga,
            safeExpectedImages,
            spec,
            pipelinePages
        )
        if (attempt.snapshot == null) {
            logLaunchRunwayRejected(attempt.rejection ?: "pipeline_unknown")
            return null
        }
        ViewerWarmupManager.logMetric("prepared_pipeline_runway_reserve_success", 1L)
        ViewerWarmupManager.logMetric("prepared_active_leases", activeLeaseCountLocked().toLong())
        return LaunchRunwayLease(spec, entry, token)
    }

    @JvmStatic
    @Synchronized
    fun remove(key: String?) {
        if (key.isNullOrEmpty()) return
        val entry = entries[key] ?: return
        if (entry.requestRemoval()) {
            ViewerWarmupManager.logMetric("prepared_lease_remove_deferred", 1L)
            return
        }
        entries.remove(key)
    }

    @JvmStatic
    @Synchronized
    fun clearAll() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.requestRemoval()) {
                ViewerWarmupManager.logMetric("prepared_lease_remove_deferred", 1L)
            } else {
                iterator.remove()
            }
        }
    }

    @JvmStatic
    fun clearBitmaps(key: String?) {
        get(key)?.clearBitmaps()
    }

    @JvmStatic
    @Synchronized
    fun clearOtherNtkEpisodeBitmaps(currentPath: String?): Int {
        val keepPath = currentPath?.trim().orEmpty()
        if (keepPath.isEmpty()) return 0
        val targets = entries.values.filter { entry ->
            val path = entry.manga.ntkEpisodePath?.trim().orEmpty()
            path.isNotEmpty() && path != keepPath && entry.hasBitmap()
        }
        targets.forEach { it.clearBitmaps() }
        return targets.size
    }

    private fun shouldReplaceExistingEntry(entry: Entry): Boolean {
        return entry.isFailed()
    }

    private fun compatiblePrefix(key: String): String? {
        val marker = key.lastIndexOf(':')
        if (marker <= 0) return null
        val startMarker = key.lastIndexOf(':', marker - 1)
        if (startMarker <= 0) return null
        return key.substring(0, startMarker + 1)
    }

    @Synchronized
    private fun mirrorCompatibleBitmap(sourceKey: String, index: Int, bitmap: Bitmap, first: Boolean, windowComplete: Boolean) {
        val prefix = compatiblePrefix(sourceKey) ?: return
        val targets = entries.entries
            .filter { it.key != sourceKey && it.key.startsWith(prefix) }
            .map { it.value }
        for (entry in targets) {
            entry.putBitmapFromCompatible(index, bitmap, first, windowComplete)
        }
    }

    @Synchronized
    private fun mirrorCompatibleBitmapBatch(
        sourceKey: String,
        bitmaps: Map<Int, Bitmap>,
        windowComplete: Boolean
    ) {
        val prefix = compatiblePrefix(sourceKey) ?: return
        val targets = entries.entries
            .filter { it.key != sourceKey && it.key.startsWith(prefix) }
            .map { it.value }
        for (entry in targets) entry.putBitmapBatchFromCompatible(bitmaps, windowComplete)
    }

    @Synchronized
    private fun mirrorCompatibleDrawableBatch(
        sourceKey: String,
        bitmaps: Map<Int, Bitmap>,
        tilePages: Map<Int, PreparedTilePage>,
        windowComplete: Boolean
    ) {
        val prefix = compatiblePrefix(sourceKey) ?: return
        val targets = entries.entries
            .filter { it.key != sourceKey && it.key.startsWith(prefix) }
            .map { it.value }
        for (entry in targets) {
            entry.putDrawableBatchFromCompatible(bitmaps, tilePages, windowComplete)
        }
    }

    @JvmStatic
    fun shouldReplaceExistingEntryForTest(failed: Boolean): Boolean {
        return failed
    }

    @JvmStatic
    fun put(entry: Snapshot): String {
        val key = "legacy:${System.nanoTime()}"
        val storeEntry = createOrGet(key, entry.manga, entry.title, entry.startPage, entry.requestedWidth)
        entry.images?.let { storeEntry.setImages(it, entry.startPage) }
        storeEntry.putDrawableBatch(entry.bitmaps, entry.tilePages, windowComplete = true)
        return key
    }

    private fun trimLocked() {
        while (entries.size > MAX_ENTRIES) {
            var removed = false
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.requestRemoval()) continue
                iterator.remove()
                removed = true
                break
            }
            if (!removed) break
        }
        enforcePinnedStartLimitLocked()
        trimBitmapBudgetLocked()
    }

    @Synchronized
    private fun trimBitmapBudget() {
        trimBitmapBudgetLocked()
    }

    private fun trimBitmapBudgetLocked() {
        enforcePinnedStartLimitLocked()
        val hardCap = hardBitmapBytesLocked()
        while (totalBitmapBytesLocked() > hardCap) {
            if (trimOneBitmapLocked(false)) continue
            if (trimOneBitmapLocked(true)) continue
            break
        }
        ViewerWarmupManager.logMetric("prepared_bitmap_bytes", totalBitmapBytesLocked())
    }

    private fun trimOneBitmapLocked(allowPinnedStart: Boolean): Boolean {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.trimOldestBitmap(allowPinnedStart))
                return true
        }
        return false
    }

    private fun enforcePinnedStartLimitLocked() {
        var pinned = entries.values.count { it.hasPinnedStartBitmap() }
        if (pinned <= MAX_PINNED_START_BITMAPS)
            return
        val iterator = entries.values.iterator()
        while (iterator.hasNext() && pinned > MAX_PINNED_START_BITMAPS) {
            val entry = iterator.next()
            if (entry.hasPinnedStartBitmap() && entry.demoteStartPin())
                pinned--
        }
    }

    private fun totalBitmapBytesLocked(): Long {
        return entries.values.sumOf { it.bitmapBytes() }
    }

    private fun activeLeaseCountLocked(): Int {
        return entries.values.sumOf { it.activeLeases() }
    }

    @Synchronized
    private fun releaseLease(entry: Entry) {
        val release = entry.releaseLease()
        if (release.removeEntry) {
            if (entries[entry.key] === entry) entries.remove(entry.key)
        }
        if (release.activeLeases == 0) {
            trimLocked()
        }
        ViewerWarmupManager.logMetric("prepared_active_leases", activeLeaseCountLocked().toLong())
    }

    @Synchronized
    private fun commitLaunchRunway(entry: Entry, reservationToken: Long): Boolean {
        if (entries[entry.key] !== entry) return false
        val committed = entry.commitLaunchRunway(reservationToken)
        if (committed) ViewerWarmupManager.logMetric("prepared_runway_commit_success", 1L)
        return committed
    }

    @Synchronized
    private fun releaseLaunchRunway(entry: Entry, reservationToken: Long) {
        val release = entry.releaseLaunchRunway(reservationToken)
        if (release.removeEntry && entries[entry.key] === entry) {
            entries.remove(entry.key)
        }
        if (release.activeLeases == 0) trimLocked()
        ViewerWarmupManager.logMetric("prepared_active_leases", activeLeaseCountLocked().toLong())
    }

    private fun logClaimRejected(reason: String) {
        ViewerWarmupManager.logMetric("prepared_claim_rejected", 1L)
        ViewerWarmupManager.logMetric("prepared_claim_reject_$reason", 1L)
        ViewerWarmupManager.logMetric("prepared_active_leases", activeLeaseCountLocked().toLong())
    }

    private fun logLaunchRunwayRejected(reason: String) {
        ViewerWarmupManager.logMetric("prepared_runway_reserve_rejected", 1L)
        ViewerWarmupManager.logMetric("prepared_runway_reserve_reject_$reason", 1L)
        ViewerWarmupManager.logMetric("prepared_active_leases", activeLeaseCountLocked().toLong())
    }

    private fun hardBitmapBytesLocked(): Long {
        return if (entries.values.any { it.hasBitmap() && it.sourceSite() == "ntk" })
            NTK_HARD_BITMAP_BYTES
        else
            DEFAULT_HARD_BITMAP_BYTES
    }

    private fun bitmapBytes(bitmap: Bitmap): Int {
        return try {
            bitmap.allocationByteCount
        } catch (_: Exception) {
            bitmap.byteCount
        }
    }

    @JvmStatic
    fun maxPinnedStartBitmapsForTest(): Int = MAX_PINNED_START_BITMAPS

    @JvmStatic
    fun maxBitmapBytesForTest(): Long = DEFAULT_HARD_BITMAP_BYTES

    @JvmStatic
    fun softBitmapBytesForTest(sourceSite: String?): Long {
        return if ((sourceSite ?: "").trim().lowercase(Locale.ROOT) == "ntk")
            NTK_SOFT_BITMAP_BYTES
        else
            DEFAULT_SOFT_BITMAP_BYTES
    }

    @JvmStatic
    fun hardBitmapBytesForTest(sourceSite: String?): Long {
        return if ((sourceSite ?: "").trim().lowercase(Locale.ROOT) == "ntk")
            NTK_HARD_BITMAP_BYTES
        else
            DEFAULT_HARD_BITMAP_BYTES
    }
}
