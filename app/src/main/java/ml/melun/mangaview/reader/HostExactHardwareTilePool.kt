package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.IdentityHashMap
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-emulator exact-pixel storage whose native AHardwareBuffers survive page retirement.
 *
 * ART accounts a newly allocated Bitmap's complete native pixel payload and requests a concurrent
 * compacting GC for almost every manga page. One private mutable region-decode scratch and a
 * bounded set of native buffers are allocated before measured scrolling, then reused. Published
 * Tiny Bitmap identities are immutable lease tokens and are never reused; only their backing
 * slots return here after Session, Surface, HWUI and native-JNI retirement have all completed.
 */
internal object HostExactHardwareTilePool {
    private const val SCRATCH_WIDTH_BUCKET = 256
    private const val MIN_SCRATCH_WIDTH = 2_048
    // A launch chapter remains immutable until its successor is physically presented, while the
    // first five successor pages must already be drawable. At 800x2048 the measured protected
    // launch+successor set is about 118 MiB. Waiting until 256 MiB before signalling pressure let
    // thirty 6.25 MiB slots accumulate across ordinary 14-page chapters, so every new page still
    // allocated instead of reusing a retired slot and repeatedly triggered process-wide
    // NativeAlloc GC. A 128 MiB settled pool plus decoder/renderer native state kept this process
    // above ART's native-allocation pressure line during long continuous reading, producing a
    // compacting collection for nearly every later page. Even the later 96 MiB target still
    // reached about 155 MiB of total native heap once renderer state was included and collections
    // overlapped successor-page decode. Settle at 64 MiB: sixteen common compact pages still cover
    // the exact viewport plus four-page directional runway, while encoded originals and the
    // monotonic drawable-completion ledger make older pixels losslessly rehydratable. A tall/wide
    // atomic page may still use the separate 128 MiB page envelope and the unchanged 320 MiB hard
    // boundary envelope; idle slots then compact back to the settled bound.
    private const val MAX_POOL_BYTES = 64L * 1024L * 1024L
    private const val MAX_ATOMIC_PAGE_BYTES = 128L * 1024L * 1024L
    private const val HARD_MAX_POOL_BYTES = 320L * 1024L * 1024L
    private const val MAX_SCRATCH_BYTES = 48L * 1024L * 1024L
    private const val ACQUIRE_TIMEOUT_MS = 30_000L
    // A compatible slot is already paid native storage. Its release is gated by the outgoing
    // SurfaceControl commit and native renderer retirement, which can legitimately take several
    // seconds on gfxstream while a preceding physical segment drains. Waiting through that
    // bounded lifecycle on the background decoder is cheaper and
    // smoother than allocating an equivalent AHardwareBuffer and provoking process-wide
    // NativeAlloc GC while the old slot is about to become reusable.
    private const val COMPATIBLE_RETIREMENT_GRACE_MS = 5_000L
    // The display-priority grace already absorbs the short gap between consecutive gestures.
    // Once activity has then remained quiet for another 1.5 seconds, retaining a 200+ MiB
    // overcommit only delays reuse and lets its eventual close overlap the next-episode warmup.
    // Compact at that genuine idle edge and recheck physical motion between native closes.
    private const val IDLE_COMPACTION_QUIET_MS = 1_500L
    // These 1x1 Bitmaps are immutable lease identities, not pixel storage. Creating one still
    // enters NativeAllocationRegistry and can request a process-wide NativeAlloc GC. Keep a
    // rolling reserve large enough for long chapters and refill it only after physical activity
    // has gone quiet. Published identities are never returned or reused.
    private const val TOKEN_RESERVE_TARGET = 512
    private const val TOKEN_RESERVE_LOW_WATERMARK = 128
    // Token creation enters NativeAllocationRegistry even though each identity is 1x1. Keep its
    // heavier bulk refill on the original long idle fence, independently from slot compaction.
    private const val TOKEN_RESERVE_REFILL_QUIET_MS = 5_000L

    private data class Slot(
        val nativeHandle: Long,
        val capacityWidth: Int,
        val capacityHeight: Int,
        val bytes: Long,
        var inUse: Boolean = false,
        /** True only after the current immutable token has entered the Surface retirement path. */
        var retirementPending: Boolean = false,
    )

    private data class Scratch(
        val bitmap: Bitmap,
        val capacityWidth: Int,
        val capacityHeight: Int,
        var inUse: Boolean = false,
    )

    /** Pool-accounting reservation whose slow native close/allocation work runs without [lock]. */
    private data class SlotAllocationPlan(
        val reusable: List<Slot>,
        val victims: List<Slot>,
        val newCount: Int,
        val newBytes: Long,
        val reclaimedBytes: Long,
        val reservedPoolBytes: Long,
        val transientOvercommit: Boolean,
    )

    private val lock = Object()
    /** Serializes emulator-wide AHardwareBuffer growth without holding the pool bookkeeping lock. */
    private val slotAllocationLock = Any()
    private val slots = ArrayList<Slot>()
    private val owners = IdentityHashMap<Bitmap, Slot>()
    /**
     * Incremental resource index read by the display producer without taking [lock].
     *
     * Bitmap is final and does not override Object.equals/hashCode, so ConcurrentHashMap keys
     * retain the required exact-reference semantics. Ownership mutations remain serialized by
     * [lock], while updating only the affected tokens avoids copying every active page resource
     * during each retirement. Tokens are not exposed until a complete page commit returns.
     */
    private val nativeResourceSnapshot = ConcurrentHashMap<Bitmap, Slot>()
    private val pressureListeners = CopyOnWriteArraySet<(Long) -> Unit>()
    private val idleCompactionPosted = AtomicBoolean(false)
    private val tokenRefillPosted = AtomicBoolean(false)
    private val tokenCreationLock = Any()
    /**
     * Mirrors the native exact-file decoder's process-wide scratch mutex at the JVM boundary.
     *
     * Adjacent-runway pages can be submitted together.  The native decoder serializes them on a
     * private mutex, so checking physical motion before the JNI call allowed every submitted page
     * to pass while idle and then wait invisibly inside native code.  Those queued pages continued
     * decoding through later touch/fling intervals.  Owning this monitor before the admission
     * check makes every page re-check motion only when it can actually enter native decode.
     */
    private val exactFileDecodeAdmissionLock = Any()
    private val freshTokenReserve = ArrayDeque<Bitmap>(TOKEN_RESERVE_TARGET)
    private var tokenReservePrimed = false
    private val idleCompactionExecutor = ScheduledThreadPoolExecutor(
        1,
        { command ->
            Thread(
                {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                    command.run()
                },
                "host-exact-idle-compaction",
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        setKeepAliveTime(30L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        setRemoveOnCancelPolicy(true)
    }
    private var scratch: Scratch? = null
    private var allocatedBytes = 0L
    private var lastPressureSignalAtMs = 0L
    @Volatile
    private var lastPoolActivityAtMs = 0L

    fun supported(hostGpuEmulatorRuntime: Boolean): Boolean =
        hostGpuEmulatorRuntime && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Allocates the process-wide logical identity reserve before a reader can become interactive.
     *
     * The exact native renderer needs a distinct, immutable identity for each published tile, but
     * Android still registers a 1x1 [Bitmap] with NativeAllocationRegistry. Creating the initial
     * reserve lazily from the first page decoder lets the resulting process-wide GC arrive several
     * seconds later, after the user has started scrolling. Application startup invokes this on a
     * background lane for the host-emulator renderer. Decoders use the same monitor, so an unusually
     * fast launch waits off-main for this one process initialization instead of duplicating it.
     */
    @JvmStatic
    fun primeProcessTokenReserve() {
        synchronized(tokenCreationLock) {
            if (tokenReservePrimed) return
            val startedAt = SystemClock.elapsedRealtime()
            try {
                while (freshTokenReserve.size < TOKEN_RESERVE_TARGET) {
                    freshTokenReserve.addLast(
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                    )
                }
                tokenReservePrimed = true
                Log.i(
                    TAG,
                    "process_token_reserve_primed count=${freshTokenReserve.size}," +
                        "ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
            } catch (failure: Throwable) {
                // A later decoder can finish a partial reserve. Do not declare it primed until the
                // complete target exists, otherwise per-page creation re-enters physical input.
                Log.w(
                    TAG,
                    "process_token_reserve_prime_failed count=${freshTokenReserve.size}",
                    failure,
                )
            }
        }
    }

    /**
     * The published resource index is the renderer-facing ownership authority.
     *
     * Retirement removes this entry before returning the slot to the pool, while page decode does
     * not publish it until the complete immutable page has succeeded.  Reading [owners] here used
     * to make every frame's exact-pixel validation enter the decoder/retirement monitor twice per
     * tile.  A background allocation that was descheduled while owning that monitor could then
     * stop ReaderSurfaceProducer for an entire display interval (or much longer).  The CHM edge is
     * both the required publication barrier and the exact lifetime boundary, so renderer reads do
     * not need the pool's mutation lock.
     */
    fun isActiveToken(bitmap: Bitmap): Boolean = nativeResourceSnapshot.containsKey(bitmap)

    fun hasExactStorage(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int): Boolean {
        val slot = nativeResourceSnapshot[bitmap] ?: return false
        val requiredWidth = HostExactDisplayStorageGeometry.capacityWidth(sourceWidth)
        val requiredHeight = HostExactDisplayStorageGeometry.contentHeight(
            sourceWidth,
            sourceHeight,
        )
        return sourceWidth > 0 && sourceHeight > 0 && requiredWidth > 0 && requiredHeight > 0 &&
            slot.capacityWidth >= requiredWidth && slot.capacityHeight >= requiredHeight
    }

    /** Zero means the logical token has no native exact-pixel storage. */
    fun nativeHandle(bitmap: Bitmap): Long =
        nativeResourceSnapshot[bitmap]?.nativeHandle ?: 0L

    fun storageBytes(bitmap: Bitmap): Long? = nativeResourceSnapshot[bitmap]?.bytes

    /**
     * Copies one already-rendered structural bitmap into the same immutable display-resolution
     * storage used by decoded pages. The returned 1x1 Bitmap is only a lifetime/identity token;
     * callers retain the original software bitmap for HWUI fallback and use this token solely for
     * native presentation.
     */
    fun copyStructuralBitmap(bitmap: Bitmap): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || bitmap.isRecycled ||
            bitmap.width <= 0 || bitmap.height <= 0 ||
            bitmap.config != Bitmap.Config.ARGB_8888
        ) return null
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val contentWidth = HostExactDisplayStorageGeometry.contentWidth(sourceWidth)
        val capacityWidth = HostExactDisplayStorageGeometry.capacityWidth(sourceWidth)
        val capacityHeight = HostExactDisplayStorageGeometry.capacityHeight(
            sourceWidth,
            sourceHeight,
        )
        val requiredBytes = capacityWidth.toLong() * capacityHeight.toLong() * 4L
        if (contentWidth <= 0 || capacityWidth <= 0 || capacityHeight <= 0 ||
            requiredBytes <= 0L || requiredBytes > MAX_ATOMIC_PAGE_BYTES
        ) return null
        val slot = acquireSlots(
            capacityWidth = capacityWidth,
            capacityHeight = capacityHeight,
            requiredBytes = requiredBytes,
            count = 1,
            deferWhilePhysicalMotion = false,
            // A transition card is part of the next physically visible frame. Waiting for an
            // older card with the same small bucket to retire leaves the direct presenter with
            // structure but no pixel source for the whole compatible-retirement grace period.
            // Keep ordinary image pages on the reuse-first path, but admit this bounded structural
            // overlap immediately; historical-card pruning still returns both slots normally.
            waitForCompatibleRetirement = false,
        )?.singleOrNull() ?: return null
        var token: Bitmap? = null
        var success = false
        try {
            if (!NtkRollingNativeBridge.nativeCopyExactBitmapToHardwareTile(
                    bitmap,
                    slot.nativeHandle,
                    sourceWidth,
                    0,
                    sourceHeight,
                    contentWidth,
                )
            ) return null
            token = createTokensForSlots(listOf(slot))?.singleOrNull() ?: return null
            success = true
            return token
        } finally {
            if (!success) {
                if (token != null) retire(token) else releaseSlot(slot)
            }
        }
    }

    /** Prevents host-buffer close work from overlapping a real touch/fling interval. */
    fun notePhysicalReaderActivity() {
        lastPoolActivityAtMs = SystemClock.elapsedRealtime()
    }

    fun isMaintenanceIdle(): Boolean {
        if (idleCompactionPosted.get()) return false
        return synchronized(lock) {
            allocatedBytes <= MAX_POOL_BYTES || slots.none { slot -> !slot.inUse }
        }
    }

    fun subscribePressure(listener: (minimumRetirementBytes: Long) -> Unit): Closeable {
        pressureListeners.add(listener)
        return Closeable { pressureListeners.remove(listener) }
    }

    /**
     * Publishes exact evidence that these logical tokens have left their Session owner and are
     * waiting only for Surface/native reference retirement. A blocked allocator may wait for a
     * compatible slot only after this marker exists; an arbitrary in-use slot is not evidence.
     */
    fun noteRetirementPending(bitmaps: Iterable<Bitmap>): Long {
        val unique = java.util.Collections.newSetFromMap(
            IdentityHashMap<Bitmap, Boolean>(),
        )
        bitmaps.forEach(unique::add)
        if (unique.isEmpty()) return 0L
        var pendingBytes = 0L
        synchronized(lock) {
            for (bitmap in unique) {
                val slot = owners[bitmap] ?: continue
                if (!slot.inUse) continue
                if (!slot.retirementPending) {
                    slot.retirementPending = true
                    pendingBytes += slot.bytes
                }
            }
            if (pendingBytes > 0L) lock.notifyAll()
        }
        return pendingBytes
    }

    /** Decodes a complete original into immutable logical row resources backed by pooled storage. */
    @Suppress("DEPRECATION")
    fun decodePage(
        encoded: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        tileCapacityHeight: Int,
        deferWhilePhysicalMotion: Boolean = false,
        sourceLeft: Int = 0,
        sourceRegionWidth: Int = sourceWidth,
    ): List<Bitmap>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || encoded.isEmpty() ||
            sourceWidth <= 0 || sourceHeight <= 0 || tileCapacityHeight <= 0 ||
            sourceLeft < 0 || sourceRegionWidth <= 0 ||
            sourceLeft.toLong() + sourceRegionWidth > sourceWidth.toLong()
        ) return null
        return decodePageWithDecoder(
            sourceWidth,
            sourceHeight,
            tileCapacityHeight,
            deferWhilePhysicalMotion,
            sourceLeft,
            sourceRegionWidth,
        ) {
            BitmapRegionDecoder.newInstance(encoded, 0, encoded.size, false)
        }
    }

    /** File-backed counterpart that never materializes the compressed body in ART's heap. */
    @Suppress("DEPRECATION")
    fun decodePage(
        encodedFile: File,
        sourceWidth: Int,
        sourceHeight: Int,
        tileCapacityHeight: Int,
        deferWhilePhysicalMotion: Boolean = false,
        sourceLeft: Int = 0,
        sourceRegionWidth: Int = sourceWidth,
    ): List<Bitmap>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !encodedFile.isFile ||
            encodedFile.length() <= 0L || sourceWidth <= 0 || sourceHeight <= 0 ||
            tileCapacityHeight <= 0 || sourceLeft < 0 || sourceRegionWidth <= 0 ||
            sourceLeft.toLong() + sourceRegionWidth > sourceWidth.toLong()
        ) return null
        val contentWidth = HostExactDisplayStorageGeometry.contentWidth(sourceRegionWidth)
        val capacityWidth = HostExactDisplayStorageGeometry.capacityWidth(sourceRegionWidth)
        // The encoded original remains the rehydration authority. This pool retains only pixels
        // that the fixed-width native display surface can sample, with bucketed height capacity
        // so adjacent chapters reuse storage instead of accumulating original-width classes.
        // A page shorter than one canonical source tile has no second tile which could ever use
        // the unused rows.  Sizing every ordinary 850x12xx manga page as if it were a complete
        // 2048-row tile wastes roughly 2.5 MiB per page and pushes an otherwise bounded chapter
        // boundary through ART's native-allocation pressure line.  Multi-tile pages retain the
        // canonical capacity class so every full tile (and its shorter final sibling) can share
        // the same atomic batch.
        val capacityLogicalHeight = minOf(sourceHeight, tileCapacityHeight)
        val capacityHeight = HostExactDisplayStorageGeometry.capacityHeight(
            sourceRegionWidth,
            capacityLogicalHeight,
        )
        val requiredBytes = capacityWidth.toLong() * capacityHeight.toLong() * 4L
        val tileCount = (sourceHeight + tileCapacityHeight - 1) / tileCapacityHeight
        if (requiredBytes <= 0L || tileCount <= 0 ||
            requiredBytes > MAX_ATOMIC_PAGE_BYTES / tileCount.toLong()
        ) return null
        val reservedSlots = acquireSlots(
            capacityWidth = capacityWidth,
            capacityHeight = capacityHeight,
            requiredBytes = requiredBytes,
            count = tileCount,
            deferWhilePhysicalMotion = deferWhilePhysicalMotion,
        ) ?: return null
        var decodedTiles: List<Bitmap> = emptyList()
        var success = false
        try {
            val nativeHandles = LongArray(reservedSlots.size) { index ->
                reservedSlots[index].nativeHandle
            }
            val nativeDecodeSucceeded = synchronized(exactFileDecodeAdmissionLock) {
                // This must remain inside the JVM mirror of native's scratch mutex.  Moving it
                // outside recreates a queue of already-admitted decodes that outlives a gesture.
                awaitOptionalPhysicalMotionAdmission(deferWhilePhysicalMotion)
                NtkRollingNativeBridge.nativeDecodeExactFileToHardwareTiles(
                    encodedFile.absolutePath,
                    nativeHandles,
                    sourceWidth,
                    sourceHeight,
                    sourceLeft,
                    sourceRegionWidth,
                    tileCapacityHeight,
                    contentWidth,
                )
            }
            if (!nativeDecodeSucceeded) return null
            decodedTiles = createTokensForSlots(reservedSlots) ?: return null
            success = true
            return decodedTiles
        } finally {
            if (!success) {
                if (decodedTiles.isNotEmpty()) {
                    retireAll(decodedTiles)
                } else {
                    reservedSlots.forEach(::releaseSlot)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun decodePageWithDecoder(
        sourceWidth: Int,
        sourceHeight: Int,
        tileCapacityHeight: Int,
        deferWhilePhysicalMotion: Boolean,
        sourceLeft: Int,
        sourceRegionWidth: Int,
        createDecoder: () -> BitmapRegionDecoder,
    ): List<Bitmap>? {
        val contentWidth = HostExactDisplayStorageGeometry.contentWidth(sourceRegionWidth)
        val capacityWidth = HostExactDisplayStorageGeometry.capacityWidth(sourceRegionWidth)
        val capacityLogicalHeight = minOf(sourceHeight, tileCapacityHeight)
        val capacityHeight = HostExactDisplayStorageGeometry.capacityHeight(
            sourceRegionWidth,
            capacityLogicalHeight,
        )
        val requiredBytes = capacityWidth.toLong() * capacityHeight.toLong() * 4L
        val tileCount = (sourceHeight + tileCapacityHeight - 1) / tileCapacityHeight
        if (requiredBytes <= 0L || tileCount <= 0 ||
            requiredBytes > MAX_ATOMIC_PAGE_BYTES / tileCount.toLong()
        ) return null
        val scratchLease = acquireScratch(
            sourceRegionWidth,
            minOf(sourceHeight, tileCapacityHeight),
        ) ?: return null
        // Reserve the whole page as one transaction. Reserving one tile and then waiting for the
        // next lets a wide page consume the remaining headroom while its own completion is the
        // only event that could release older storage, producing a deterministic 30-second stall.
        // The single scratch lease remains the decode admission gate, so competing pages cannot
        // each reserve a complete batch and then wait on one another for the scratch bitmap.
        val reservedSlots = try {
            acquireSlots(
                capacityWidth = capacityWidth,
                capacityHeight = capacityHeight,
                requiredBytes = requiredBytes,
                count = tileCount,
                deferWhilePhysicalMotion = deferWhilePhysicalMotion,
            )
        } catch (failure: Throwable) {
            releaseScratch(scratchLease)
            throw failure
        } ?: run {
            releaseScratch(scratchLease)
            return null
        }
        val decoder = createDecoder()
        var copiedSlots = 0
        var decodedTiles: List<Bitmap> = emptyList()
        var success = false
        try {
            awaitOptionalPhysicalMotionAdmission(deferWhilePhysicalMotion)
            val region = Rect()
            var top = 0
            while (top < sourceHeight) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Host exact hardware decode interrupted")
                }
                val bottom = minOf(sourceHeight, top + tileCapacityHeight)
                val span = bottom - top
                scratchLease.bitmap.reconfigure(sourceRegionWidth, span, Bitmap.Config.ARGB_8888)
                region.set(sourceLeft, top, sourceLeft + sourceRegionWidth, bottom)
                val decoded = decoder.decodeRegion(
                    region,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = 1
                        inScaled = false
                        inMutable = true
                        inBitmap = scratchLease.bitmap
                    },
                ) ?: return null
                if (decoded !== scratchLease.bitmap || decoded.width != sourceRegionWidth ||
                    decoded.height != span || decoded.config != Bitmap.Config.ARGB_8888 ||
                    !decoded.isMutable
                ) {
                    if (decoded !== scratchLease.bitmap && !decoded.isRecycled) decoded.recycle()
                    return null
                }
                val slot = reservedSlots[copiedSlots]
                if (!NtkRollingNativeBridge.nativeCopyExactBitmapToHardwareTile(
                        scratchLease.bitmap,
                        slot.nativeHandle,
                        sourceRegionWidth,
                        0,
                        span,
                        contentWidth,
                    )
                ) {
                    return null
                }
                copiedSlots += 1
                top = bottom
            }
            decodedTiles = createTokensForSlots(reservedSlots) ?: return null
            success = true
            return decodedTiles
        } finally {
            decoder.recycle()
            releaseScratch(scratchLease)
            if (!success) {
                if (decodedTiles.isNotEmpty()) {
                    retireAll(decodedTiles)
                } else {
                    reservedSlots.forEach(::releaseSlot)
                }
            }
        }
    }

    /** Returns true only when this exact immutable wrapper owned a reusable pool slot. */
    fun retire(bitmap: Bitmap): Boolean = retireAll(listOf(bitmap)).any { it === bitmap }

    /** Returns every exact identity retired by this call, using identity rather than equals(). */
    fun retireAll(bitmaps: Iterable<Bitmap>): Set<Bitmap> {
        val unique = java.util.Collections.newSetFromMap(
            IdentityHashMap<Bitmap, Boolean>(),
        )
        bitmaps.forEach { bitmap -> unique.add(bitmap) }
        if (unique.isEmpty()) return unique
        val retired = ArrayList<Pair<Bitmap, Slot>>(unique.size)
        synchronized(lock) {
            for (bitmap in unique) {
                val slot = owners.remove(bitmap) ?: continue
                check(nativeResourceSnapshot.remove(bitmap) === slot)
                retired += bitmap to slot
            }
        }
        if (retired.isEmpty()) return java.util.Collections.newSetFromMap(
            IdentityHashMap<Bitmap, Boolean>(),
        )
        val retiredIdentities = java.util.Collections.newSetFromMap(
            IdentityHashMap<Bitmap, Boolean>(),
        )
        for ((bitmap, _) in retired) {
            retiredIdentities.add(bitmap)
        }
        // These are 1x1 logical identity tokens, not pixel owners. The native-resource index no
        // longer contains them, so every stale handoff now fails exact-storage
        // validation and the backing HardwareBuffer can safely return to the pool. Calling
        // Bitmap.recycle() here entered Android's graphics lifetime machinery for 17-44 ms even
        // for one token while the Surface state lock excluded input/frame construction. Never
        // reuse the wrappers; let their tiny Java/native objects become ordinarily GC-reachable.
        synchronized(lock) {
            for ((_, slot) in retired) {
                check(slot.inUse)
                slot.retirementPending = false
                slot.inUse = false
            }
            lastPoolActivityAtMs = SystemClock.elapsedRealtime()
            lock.notifyAll()
        }
        scheduleIdleCompactionIfNeeded()
        scheduleTokenReserveRefillIfNeeded()
        return retiredIdentities
    }

    /** Creates a page's immutable logical tokens before any token can escape to a consumer. */
    private fun createTokensForSlots(pageSlots: List<Slot>): List<Bitmap>? {
        if (pageSlots.isEmpty()) return emptyList()
        val tokens = takeFreshTokens(pageSlots.size) ?: return null
        val committed = runCatching {
            synchronized(lock) {
                check(pageSlots.all(Slot::inUse))
                check(owners.values.none { owner -> pageSlots.any { slot -> owner === slot } })
                tokens.forEachIndexed { index, token ->
                    val slot = pageSlots[index]
                    check(owners.put(token, slot) == null)
                    check(nativeResourceSnapshot.put(token, slot) == null)
                }
            }
        }.isSuccess
        if (committed) return tokens
        synchronized(lock) {
            tokens.forEach { token ->
                owners.remove(token)
                nativeResourceSnapshot.remove(token)
            }
        }
        // No token escaped before the failed atomic publication, so returning these exact fresh
        // identities is safe. A token that ever commits is deliberately never returned here.
        returnFreshTokens(tokens)
        return null
    }

    /** Allocates the first reserve before decoded pages can enter physical scrolling. */
    private fun takeFreshTokens(count: Int): ArrayList<Bitmap>? {
        if (count <= 0) return ArrayList()
        synchronized(tokenCreationLock) {
            val target = if (tokenReservePrimed) count else maxOf(count, TOKEN_RESERVE_TARGET)
            try {
                while (freshTokenReserve.size < target) {
                    freshTokenReserve.addLast(
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                    )
                }
                tokenReservePrimed = true
            } catch (_: Throwable) {
                if (freshTokenReserve.size < count) return null
            }
            return ArrayList<Bitmap>(count).also { result ->
                repeat(count) { result += freshTokenReserve.removeFirst() }
            }
        }
    }

    /** Only unpublished identities may re-enter the fresh reserve. */
    private fun returnFreshTokens(tokens: List<Bitmap>) {
        synchronized(tokenCreationLock) {
            tokens.asReversed().forEach(freshTokenReserve::addFirst)
        }
    }

    private fun scheduleTokenReserveRefillIfNeeded() {
        val needed = synchronized(tokenCreationLock) {
            tokenReservePrimed && freshTokenReserve.size < TOKEN_RESERVE_LOW_WATERMARK
        }
        if (!needed || !tokenRefillPosted.compareAndSet(false, true)) return
        scheduleTokenReserveRefillWake(TOKEN_RESERVE_REFILL_QUIET_MS)
    }

    private fun scheduleTokenReserveRefillWake(delayMs: Long) {
        try {
            idleCompactionExecutor.schedule(
                ::drainTokenReserveRefillAfterQuiet,
                delayMs.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RuntimeException) {
            tokenRefillPosted.set(false)
        }
    }

    private fun drainTokenReserveRefillAfterQuiet() {
        val remainingQuietMs = TOKEN_RESERVE_REFILL_QUIET_MS -
            (SystemClock.elapsedRealtime() - lastPoolActivityAtMs)
        if (remainingQuietMs > 0L) {
            scheduleTokenReserveRefillWake(remainingQuietMs)
            return
        }
        try {
            synchronized(tokenCreationLock) {
                while (freshTokenReserve.size < TOKEN_RESERVE_TARGET) {
                    freshTokenReserve.addLast(
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                    )
                }
            }
        } catch (_: Throwable) {
            // The current reserve remains valid. A later retirement/quiet edge retries.
        } finally {
            tokenRefillPosted.set(false)
            scheduleTokenReserveRefillIfNeeded()
        }
    }

    private fun acquireSlots(
        capacityWidth: Int,
        capacityHeight: Int,
        requiredBytes: Long,
        count: Int,
        deferWhilePhysicalMotion: Boolean,
        waitForCompatibleRetirement: Boolean = true,
    ): List<Slot>? {
        if (capacityWidth <= 0 || capacityHeight <= 0 || requiredBytes <= 0L || count <= 0 ||
            requiredBytes > MAX_ATOMIC_PAGE_BYTES / count.toLong()
        ) return null
        val deadline = SystemClock.elapsedRealtime() + ACQUIRE_TIMEOUT_MS
        var allocationPlan: SlotAllocationPlan? = null
        var compatibleRetirementDeadlineMs = 0L
        var retirementSelectionDeadlineMs = 0L
        synchronized(lock) {
            while (allocationPlan == null) {
                if (deferWhilePhysicalMotion &&
                    NtkReaderTransferPacer.isPhysicalMotionActive()
                ) {
                    throw NtkPhysicalMotionDecodeDeferredException()
                }
                val reusable = slots.asSequence()
                    .filter { !it.inUse && it.capacityWidth >= capacityWidth &&
                        it.capacityHeight >= capacityHeight }
                    .sortedBy(Slot::bytes)
                    .take(count)
                    .toList()
                val newCount = count - reusable.size
                val newBytes = requiredBytes * newCount.toLong()
                val compatibleSlotCount = slots.count { slot ->
                    slot.capacityWidth >= capacityWidth &&
                        slot.capacityHeight >= capacityHeight
                }
                val pendingCompatibleSlotCount = slots.count { slot ->
                    slot.inUse && slot.retirementPending &&
                        slot.capacityWidth >= capacityWidth &&
                        slot.capacityHeight >= capacityHeight
                }
                val newAllocationExceedsSettledTarget = newCount > 0 &&
                    allocatedBytes > MAX_POOL_BYTES - newBytes
                if (waitForCompatibleRetirement && newAllocationExceedsSettledTarget) {
                    val now = SystemClock.elapsedRealtime()
                    val physicalMotionActive = NtkReaderTransferPacer.isPhysicalMotionActive()
                    if (retirementSelectionDeadlineMs == 0L) {
                        retirementSelectionDeadlineMs =
                            minOf(deadline, now + RETIREMENT_SELECTION_GRACE_MS)
                        // Under physical input the listener deliberately defers page-table and
                        // Surface retirement. Preserve that safety policy, but do not make this
                        // background allocator wait for work which cannot start until motion ends.
                        // The queued pressure request still trims the overcommit at the idle edge.
                        signalPressureLocked(newBytes.coerceAtLeast(requiredBytes))
                    }
                    if (HostExactCompatibleRetirementWaitPolicy
                            .shouldWaitForScheduledRetirement(
                                waitEnabled = true,
                                physicalMotionActive = physicalMotionActive,
                                missingSlotCount = newCount,
                                pendingCompatibleSlotCount = pendingCompatibleSlotCount,
                            )
                    ) {
                        if (compatibleRetirementDeadlineMs == 0L) {
                            compatibleRetirementDeadlineMs =
                                minOf(deadline, now + COMPATIBLE_RETIREMENT_GRACE_MS)
                        }
                        val remainingGrace = compatibleRetirementDeadlineMs - now
                        if (remainingGrace > 0L) {
                            // This exact geometry now has a real retirement owner. Give its
                            // Surface/native reference fence a bounded interval to return storage.
                            lock.wait(remainingGrace.coerceAtMost(32L))
                            continue
                        }
                    } else if (HostExactCompatibleRetirementWaitPolicy
                            .shouldWaitForRetirementSelection(
                                waitEnabled = true,
                                physicalMotionActive = physicalMotionActive,
                                missingSlotCount = newCount,
                                compatibleSlotCount = compatibleSlotCount,
                                pendingCompatibleSlotCount = pendingCompatibleSlotCount,
                            )
                    ) {
                        val remainingSelection = retirementSelectionDeadlineMs - now
                        if (remainingSelection > 0L) {
                            // Yield briefly for the asynchronous pressure listener to mark the
                            // exact tokens it selected. Do not turn an unproved owner into a
                            // multi-second decode stall.
                            lock.wait(remainingSelection.coerceAtMost(32L))
                            continue
                        }
                    }
                }
                val reusableSet = java.util.Collections.newSetFromMap(
                    IdentityHashMap<Slot, Boolean>(),
                ).apply { addAll(reusable) }
                val idleVictims = slots.asSequence()
                    .filter { !it.inUse && it !in reusableSet }
                    .sortedBy(Slot::bytes)
                    .toList()
                val victims = ArrayList<Slot>()
                var reclaimedBytes = 0L
                for (victim in idleVictims) {
                    if (allocatedBytes - reclaimedBytes <=
                        HARD_MAX_POOL_BYTES - newBytes
                    ) break
                    victims += victim
                    reclaimedBytes += victim.bytes
                }
                val fitsTarget =
                    allocatedBytes - reclaimedBytes <= MAX_POOL_BYTES - newBytes
                val fitsHardLimit =
                    allocatedBytes - reclaimedBytes <= HARD_MAX_POOL_BYTES - newBytes
                if (fitsTarget || fitsHardLimit) {
                    // HARD_MAX_POOL_BYTES is the deliberate atomic episode-boundary envelope:
                    // the current physical viewport and the four-page successor runway can need
                    // the full 320 MiB together. Signalling pressure after already approving that
                    // envelope made the listener erase the nearest forward pages, immediately
                    // decode them again, and loop forever at the boundary. Once the predecessor
                    // retires, releaseSlot() schedules the existing quiet compactor back to the
                    // 64 MiB settled target. Pressure is still signalled below when even the hard
                    // envelope cannot admit the complete page transaction.
                    for (victim in victims) {
                        check(!victim.inUse)
                        check(owners.values.none { owner -> owner === victim })
                        check(slots.remove(victim))
                        allocatedBytes -= victim.bytes
                    }
                    reusable.forEach { slot ->
                        check(!slot.inUse)
                        slot.inUse = true
                    }
                    // Reserve the complete native allocation in pool accounting before dropping
                    // the monitor. A second caller can neither exceed the cap nor claim these
                    // reusable slots while gfxstream performs its comparatively slow work.
                    val allocationLimit =
                        if (fitsTarget) MAX_POOL_BYTES else HARD_MAX_POOL_BYTES
                    check(newBytes <= allocationLimit - allocatedBytes)
                    allocatedBytes += newBytes
                    lastPoolActivityAtMs = SystemClock.elapsedRealtime()
                    allocationPlan = SlotAllocationPlan(
                        reusable = reusable,
                        victims = victims,
                        newCount = newCount,
                        newBytes = newBytes,
                        reclaimedBytes = reclaimedBytes,
                        reservedPoolBytes = allocatedBytes,
                        transientOvercommit = !fitsTarget,
                    )
                    continue
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    val ownedBytes = owners.values.sumOf(Slot::bytes)
                    val ownedCapacity = owners.values
                        .groupingBy { "${it.capacityWidth}x${it.capacityHeight}" }
                        .eachCount()
                        .entries
                        .sortedBy { it.key }
                        .joinToString(separator = "|") { (capacity, ownedCount) ->
                            "$capacity:$ownedCount"
                        }
                    Log.e(
                        TAG,
                        "slot_batch_acquire_timeout allocated=$allocatedBytes,slots=${slots.size}," +
                            "owners=${owners.size},ownedBytes=$ownedBytes," +
                            "ownedCapacity=$ownedCapacity,requiredBatch=$newBytes,pageSlots=$count," +
                            "capacity=${capacityWidth}x$capacityHeight",
                    )
                    return null
                }
                val now = SystemClock.elapsedRealtime()
                signalPressureLocked(
                    (allocatedBytes - reclaimedBytes - (MAX_POOL_BYTES - newBytes))
                        .coerceAtLeast(1L),
                )
                lock.wait(remaining.coerceAtMost(250L))
            }
        }
        return fulfillSlotAllocationPlan(
            plan = checkNotNull(allocationPlan),
            capacityWidth = capacityWidth,
            capacityHeight = capacityHeight,
            requiredBytes = requiredBytes,
            pageSlotCount = count,
        )
    }

    private fun awaitOptionalPhysicalMotionAdmission(enabled: Boolean) {
        if (!enabled) return
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Host exact decode admission interrupted")
        }
        if (NtkReaderTransferPacer.isPhysicalMotionActive()) {
            throw NtkPhysicalMotionDecodeDeferredException()
        }
    }

    /** Slow gfxstream lifetime calls intentionally execute before the brief commit monitor. */
    private fun fulfillSlotAllocationPlan(
        plan: SlotAllocationPlan,
        capacityWidth: Int,
        capacityHeight: Int,
        requiredBytes: Long,
        pageSlotCount: Int,
    ): List<Slot>? {
        var compactionFailure: Throwable? = null
        for (victim in plan.victims) {
            try {
                NtkRollingNativeBridge.nativeReleaseExactHardwareBuffer(
                    victim.nativeHandle,
                )
            } catch (failure: Throwable) {
                if (compactionFailure == null) compactionFailure = failure
            }
        }
        if (compactionFailure != null) {
            Log.e(TAG, "idle slot compaction failed", compactionFailure)
            rollbackSlotAllocationPlan(plan)
            return null
        }
        if (plan.victims.isNotEmpty()) {
            Log.i(
                TAG,
                "idle_slots_compacted count=${plan.victims.size},bytes=${plan.reclaimedBytes}," +
                    "allocated=${plan.reservedPoolBytes},requiredBatch=${plan.newBytes}," +
                    "capacity=${capacityWidth}x$capacityHeight",
            )
        }

        val allocatedHandles = ArrayList<Long>(plan.newCount)
        var allocationFailure: Throwable? = null
        repeat(plan.newCount) {
            val nativeHandle = try {
                allocateExactHardwareBufferSerially(
                    capacityWidth = capacityWidth,
                    capacityHeight = capacityHeight,
                )
            } catch (failure: Throwable) {
                allocationFailure = failure
                0L
            }
            if (nativeHandle == 0L) return@repeat
            allocatedHandles += nativeHandle
        }
        if (allocationFailure != null || allocatedHandles.size != plan.newCount) {
            allocatedHandles.forEach { nativeHandle ->
                runCatching {
                    NtkRollingNativeBridge.nativeReleaseExactHardwareBuffer(nativeHandle)
                }
            }
            allocationFailure?.let { failure ->
                Log.e(TAG, "exact hardware slot allocation failed", failure)
            }
            rollbackSlotAllocationPlan(plan)
            return null
        }
        val allocatedSlots = allocatedHandles.mapTo(ArrayList(plan.newCount)) { nativeHandle ->
            Slot(
                nativeHandle = nativeHandle,
                capacityWidth = capacityWidth,
                capacityHeight = capacityHeight,
                bytes = requiredBytes,
                inUse = true,
                retirementPending = false,
            )
        }
        val poolStats = synchronized(lock) {
            check(allocatedSlots.none(slots::contains))
            slots.addAll(allocatedSlots)
            slots.size to allocatedBytes
        }
        // Reuse is the steady-state scroll hot path. Logging every reused page makes logd contend
        // with decode and input threads; retain diagnostics only for physical pool growth.
        if (allocatedSlots.isNotEmpty()) {
            Log.i(
                TAG,
                "slot_batch_acquired pageSlots=$pageSlotCount,reused=${plan.reusable.size}," +
                    "allocated=${allocatedSlots.size},slots=${poolStats.first}," +
                    "bytes=${poolStats.second},capacity=${capacityWidth}x$capacityHeight," +
                    "transientOvercommit=${plan.transientOvercommit}",
            )
        }
        return plan.reusable + allocatedSlots
    }

    /**
     * gfxstream performs HardwareBuffer allocation and host-resource registration through shared
     * emulator services. Concurrent callers made otherwise short allocations overlap one another
     * and the renderer, producing a 105 ms native presentation interval. Keep only the native call
     * serial. Adding a time-based recovery delay reduced late-session presentation cadence even
     * though the calls no longer overlapped, so admission remains work-driven and immediate.
     */
    private fun allocateExactHardwareBufferSerially(
        capacityWidth: Int,
        capacityHeight: Int,
    ): Long = synchronized(slotAllocationLock) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Host exact slot allocation interrupted")
        }
        NtkRollingNativeBridge.nativeAllocateExactHardwareBuffer(
            capacityWidth,
            capacityHeight,
        )
    }

    private fun rollbackSlotAllocationPlan(plan: SlotAllocationPlan) {
        synchronized(lock) {
            plan.reusable.forEach { slot ->
                check(slot.inUse)
                check(owners.values.none { owner -> owner === slot })
                slot.retirementPending = false
                slot.inUse = false
            }
            check(allocatedBytes >= plan.newBytes)
            allocatedBytes -= plan.newBytes
            lastPoolActivityAtMs = SystemClock.elapsedRealtime()
            lock.notifyAll()
        }
        scheduleIdleCompactionIfNeeded()
    }

    private fun acquireScratch(sourceWidth: Int, capacityHeight: Int): Scratch? {
        val requiredWidth = maxOf(MIN_SCRATCH_WIDTH, roundWidth(sourceWidth))
        val requiredBytes = requiredWidth.toLong() * capacityHeight.toLong() * 4L
        if (requiredBytes <= 0L || requiredBytes > MAX_SCRATCH_BYTES) return null
        val deadline = SystemClock.elapsedRealtime() + ACQUIRE_TIMEOUT_MS
        synchronized(lock) {
            while (true) {
                val current = scratch
                if (current != null && !current.inUse &&
                    current.capacityWidth >= requiredWidth &&
                    current.capacityHeight >= capacityHeight
                ) {
                    current.inUse = true
                    return current
                }
                if (current == null || !current.inUse) {
                    current?.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    val bitmap = runCatching {
                        Bitmap.createBitmap(
                            requiredWidth,
                            capacityHeight,
                            Bitmap.Config.ARGB_8888,
                        )
                    }.getOrNull() ?: return null
                    return Scratch(bitmap, requiredWidth, capacityHeight, true).also {
                        scratch = it
                    }
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                lock.wait(remaining.coerceAtMost(250L))
            }
        }
    }

    private fun releaseScratch(value: Scratch) {
        synchronized(lock) {
            check(scratch === value && value.inUse)
            value.inUse = false
            lock.notifyAll()
        }
    }

    private fun releaseSlot(slot: Slot) {
        synchronized(lock) {
            check(slot.inUse)
            slot.retirementPending = false
            slot.inUse = false
            lastPoolActivityAtMs = SystemClock.elapsedRealtime()
            lock.notifyAll()
        }
        scheduleIdleCompactionIfNeeded()
    }

    /** Must be called with [lock] held. Listener implementations only enqueue cleanup work. */
    private fun signalPressureLocked(minimumRetirementBytes: Long) {
        if (minimumRetirementBytes <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPressureSignalAtMs < PRESSURE_SIGNAL_MIN_INTERVAL_MS) return
        lastPressureSignalAtMs = now
        pressureListeners.forEach { listener ->
            runCatching { listener(minimumRetirementBytes) }.onFailure { failure ->
                Log.w(TAG, "exact tile pool pressure listener failed", failure)
            }
        }
    }

    /**
     * Removes only idle overcommit storage under [lock], then closes gfxstream handles off-lock.
     *
     * A moving viewport releases and reacquires one page every few hundred milliseconds. Immediate
     * compaction turned the 320 MiB admission ceiling into an allocate/close loop and made the next
     * verified page pay gfxstream allocation again. Keep the hard cap unchanged, but wait for a
     * genuine quiet interval before converging to the 64 MiB steady-state target. Test/PSS idle
     * remains false for the entire debounce and close, so checkpoints cannot sample overcommit.
     */
    private fun scheduleIdleCompactionIfNeeded() {
        val needed = synchronized(lock) {
            allocatedBytes > MAX_POOL_BYTES && slots.any { slot -> !slot.inUse }
        }
        if (!needed || !idleCompactionPosted.compareAndSet(false, true)) return
        scheduleIdleCompactionWake(IDLE_COMPACTION_QUIET_MS)
    }

    private fun scheduleIdleCompactionWake(delayMs: Long) {
        try {
            idleCompactionExecutor.schedule(
                ::drainIdleCompactionAfterQuiet,
                delayMs.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RuntimeException) {
            idleCompactionPosted.set(false)
        }
    }

    private fun drainIdleCompactionAfterQuiet() {
        val remainingQuietMs = IDLE_COMPACTION_QUIET_MS -
            (SystemClock.elapsedRealtime() - lastPoolActivityAtMs)
        if (remainingQuietMs > 0L || NtkReaderTransferPacer.isPhysicalMotionActive()) {
            scheduleIdleCompactionWake(remainingQuietMs.coerceAtLeast(32L))
            return
        }
        try {
            compactIdleSlotsToTarget()
        } finally {
            idleCompactionPosted.set(false)
            val retry = synchronized(lock) {
                allocatedBytes > MAX_POOL_BYTES && slots.any { slot -> !slot.inUse }
            }
            if (retry) scheduleIdleCompactionIfNeeded()
        }
    }

    private fun compactIdleSlotsToTarget() {
        while (true) {
            // One native close at a time keeps the operation interruptible at the next real
            // gesture. Selecting the complete victim set up front made every handle close even
            // when input resumed after the quiet check.
            if (NtkReaderTransferPacer.isPhysicalMotionActive()) return
            val victim = synchronized(lock) {
                val bytesToFree = (allocatedBytes - MAX_POOL_BYTES).coerceAtLeast(0L)
                if (bytesToFree <= 0L) return
                val selected = slots.asSequence()
                    .filter { slot -> !slot.inUse }
                    .sortedBy(Slot::bytes)
                    .firstOrNull()
                    ?: return
                check(owners.values.none { owner -> owner === selected })
                check(slots.remove(selected))
                allocatedBytes -= selected.bytes
                lock.notifyAll()
                selected
            }
            runCatching {
                NtkRollingNativeBridge.nativeReleaseExactHardwareBuffer(victim.nativeHandle)
            }.onFailure { failure ->
                Log.e(TAG, "transient idle slot close failed", failure)
            }
            val remaining = synchronized(lock) { allocatedBytes }
            Log.i(
                TAG,
                "transient_idle_slots_compacted count=1,bytes=${victim.bytes}," +
                    "allocated=$remaining,target=$MAX_POOL_BYTES",
            )
        }
    }

    private fun roundWidth(width: Int): Int {
        if (width <= 0 || width > Int.MAX_VALUE - (SCRATCH_WIDTH_BUCKET - 1)) return width
        return ((width + SCRATCH_WIDTH_BUCKET - 1) / SCRATCH_WIDTH_BUCKET) *
            SCRATCH_WIDTH_BUCKET
    }

    private const val TAG = "HostExactTilePool"
    private const val RETIREMENT_SELECTION_GRACE_MS = 64L
    private const val PRESSURE_SIGNAL_MIN_INTERVAL_MS = 96L
}
