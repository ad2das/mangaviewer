package ml.melun.mangaview.reader

import android.graphics.Bitmap
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import java.util.LinkedHashMap

object ReaderPreparedStore {
    private const val MAX_ENTRIES = 24
    private const val MAX_BITMAP_BYTES = 48L * 1024L * 1024L
    private const val MAX_PINNED_START_BITMAPS = 3

    enum class Status {
        PENDING,
        URLS_READY,
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
                if (currentStatus == Status.PENDING) currentStatus = Status.URLS_READY
                callbacks = listeners.toList()
            }
            for (listener in callbacks) listener.onUrlsReady(safeImages, startPage)
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
            existing.updateStartPin(pinStartBitmap)
            return existing
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
        while (totalBitmapBytesLocked() > MAX_BITMAP_BYTES) {
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
    fun maxBitmapBytesForTest(): Long = MAX_BITMAP_BYTES
}
