package ml.melun.mangaview.reader

import android.graphics.Bitmap
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import java.util.LinkedHashMap
import java.util.Locale

object ReaderPreparedStore {
    private const val MAX_ENTRIES = 24
    private const val DEFAULT_SOFT_BITMAP_BYTES = 16L * 1024L * 1024L
    private const val DEFAULT_HARD_BITMAP_BYTES = 24L * 1024L * 1024L
    private const val NTK_SOFT_BITMAP_BYTES = 12L * 1024L * 1024L
    private const val NTK_HARD_BITMAP_BYTES = 16L * 1024L * 1024L
    private const val MAX_PINNED_START_BITMAPS = 1

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
        fun onFailed()
    }

    data class Snapshot(
        val manga: Manga,
        val title: Title?,
        val images: List<String>?,
        val startPage: Int,
        val bitmaps: Map<Int, Bitmap>,
        val status: Status
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
        private var imageUrls: List<String>? = null
        private var resolvedStartPage = requestedStartPage
        private var currentStatus = Status.PENDING

        fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

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
            synchronized(lock) {
                bitmapMap.clear()
                pinStartBitmap = false
            }
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
            val callbacks: List<Listener>
            synchronized(lock) {
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
                images = imageUrls?.let { ArrayList(it) },
                startPage = resolvedStartPage,
                bitmaps = LinkedHashMap(bitmapMap),
                status = currentStatus
            )
        }

        internal fun bitmapBytes(): Long = synchronized(lock) {
            bitmapMap.values.sumOf { bitmapBytes(it).toLong() }
        }

        internal fun trimOldestBitmap(allowPinnedStart: Boolean): Boolean = synchronized(lock) {
            val iterator = bitmapMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == resolvedStartPage && pinStartBitmap && !allowPinnedStart) continue
                iterator.remove()
                if (entry.key == resolvedStartPage) {
                    pinStartBitmap = false
                    ViewerWarmupManager.logMetric("prepared_start_evicted", 1L)
                }
                return true
            }
            false
        }

        internal fun hasPinnedStartBitmap(): Boolean = synchronized(lock) {
            pinStartBitmap && usableBitmapLocked(bitmapMap[resolvedStartPage])
        }

        internal fun demoteStartPin(): Boolean = synchronized(lock) {
            if (!pinStartBitmap) return@synchronized false
            pinStartBitmap = false
            true
        }

        internal fun hasBitmap(): Boolean = synchronized(lock) {
            bitmapMap.values.any { usableBitmapLocked(it) }
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
    }

    private val entries = LinkedHashMap<String, Entry>(32, 0.75f, true)

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
    fun remove(key: String?) {
        if (!key.isNullOrEmpty()) entries.remove(key)
    }

    @JvmStatic
    fun clearBitmaps(key: String?) {
        get(key)?.clearBitmaps()
    }

    private fun shouldReplaceExistingEntry(entry: Entry): Boolean {
        return entry.isFailed()
    }

    @JvmStatic
    fun shouldReplaceExistingEntryForTest(failed: Boolean): Boolean {
        return failed
    }

    @JvmStatic
    fun put(entry: Snapshot): String {
        val key = "legacy:${System.nanoTime()}"
        val storeEntry = createOrGet(key, entry.manga, entry.title, entry.startPage, 0)
        entry.images?.let { storeEntry.setImages(it, entry.startPage) }
        for (bitmap in entry.bitmaps) storeEntry.putBitmap(bitmap.key, bitmap.value, bitmap.key == entry.startPage, true)
        return key
    }

    private fun trimLocked() {
        while (entries.size > MAX_ENTRIES) {
            val iterator = entries.keys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
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
