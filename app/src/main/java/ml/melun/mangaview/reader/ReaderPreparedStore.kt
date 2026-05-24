package ml.melun.mangaview.reader

import android.graphics.Bitmap
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import java.util.LinkedHashMap

object ReaderPreparedStore {
    private const val MAX_ENTRIES = 24
    private const val MAX_BITMAP_BYTES = 48 * 1024 * 1024

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
        val requestedWidth: Int
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

        internal fun bitmapBytes(): Int = synchronized(lock) {
            bitmapMap.values.sumOf { bitmapBytes(it).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        internal fun trimOldestBitmap(): Boolean = synchronized(lock) {
            val iterator = bitmapMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == resolvedStartPage) continue
                iterator.remove()
                return true
            }
            false
        }
    }

    private val entries = LinkedHashMap<String, Entry>(32, 0.75f, true)

    @JvmStatic
    @Synchronized
    fun createOrGet(key: String, manga: Manga, title: Title?, startPage: Int, width: Int): Entry {
        val existing = entries[key]
        if (existing != null) return existing
        val entry = Entry(key, manga, title, startPage, width)
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
        trimBitmapBudgetLocked()
    }

    @Synchronized
    private fun trimBitmapBudget() {
        trimBitmapBudgetLocked()
    }

    private fun trimBitmapBudgetLocked() {
        while (totalBitmapBytesLocked() > MAX_BITMAP_BYTES) {
            var trimmed = false
            val iterator = entries.values.iterator()
            while (iterator.hasNext() && totalBitmapBytesLocked() > MAX_BITMAP_BYTES) {
                trimmed = iterator.next().trimOldestBitmap() || trimmed
            }
            if (!trimmed) return
        }
    }

    private fun totalBitmapBytesLocked(): Int {
        return entries.values.sumOf { it.bitmapBytes().toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun bitmapBytes(bitmap: Bitmap): Int {
        return try {
            bitmap.allocationByteCount
        } catch (_: Exception) {
            bitmap.byteCount
        }
    }
}
