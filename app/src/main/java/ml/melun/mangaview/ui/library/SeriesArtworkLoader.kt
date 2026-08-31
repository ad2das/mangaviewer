package ml.melun.mangaview.ui.library

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.SourceSeries

internal class SeriesArtworkLoader(
    private val sources: SourceRegistry,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun load(series: SourceSeries): ImageBitmap? {
        val artwork = series.thumbnailKey?.takeIf(String::isNotBlank) ?: return null
        val key = "${series.id.sourceId.value}:${series.id.remoteKey}:$artwork"
        synchronized(cache) { cache[key] }?.let { return it }
        return withContext(ioDispatcher) {
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val opened = sources.require(series.id.sourceId).openArtwork(series) ?: return@withContext null
            val bytes = opened.readArtworkBytes() ?: return@withContext null
            val decoded = decode(bytes)?.asImageBitmap() ?: return@withContext null
            synchronized(cache) { cache[key] = decoded }
            decoded
        }
    }

    private suspend fun OpenedPage.readArtworkBytes(): ByteArray? = use { opened ->
        val expected = opened.contentLength?.takeIf { it in 1..MAX_ARTWORK_BYTES.toLong() }?.toInt() ?: 8_192
        val output = ByteArrayOutputStream(expected)
        val buffer = ByteArray(16 * 1_024)
        while (output.size() <= MAX_ARTWORK_BYTES) {
            val read = opened.stream.readAtMost(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            if (output.size() + read > MAX_ARTWORK_BYTES) return@use null
            output.write(buffer, 0, read)
        }
        output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun decode(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_EDGE * 2 || bounds.outHeight / sample > MAX_DECODE_EDGE * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 48
        const val MAX_ARTWORK_BYTES = 8 * 1_024 * 1_024
        const val MAX_DECODE_EDGE = 640
    }
}
