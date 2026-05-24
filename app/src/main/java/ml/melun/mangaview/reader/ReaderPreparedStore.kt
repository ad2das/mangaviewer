package ml.melun.mangaview.reader

import android.graphics.Bitmap
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.mangaview.Title
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ReaderPreparedStore {
    data class Entry(
        val manga: Manga,
        val title: Title?,
        val images: List<String>,
        val startPage: Int,
        val bitmaps: Map<Int, Bitmap>
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    @JvmStatic
    fun put(entry: Entry): String {
        val key = UUID.randomUUID().toString()
        entries[key] = entry
        return key
    }

    @JvmStatic
    fun take(key: String?): Entry? {
        if (key.isNullOrEmpty()) return null
        return entries.remove(key)
    }
}
