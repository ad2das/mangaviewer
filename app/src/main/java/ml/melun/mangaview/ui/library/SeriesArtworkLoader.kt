package ml.melun.mangaview.ui.library

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.SourceSeries

/**
 * Decodes cover artwork at the edge length it is actually drawn at, keeps it in a byte-bounded
 * LRU, and deduplicates concurrent requests for the same thumbnail. Small decodes keep the
 * per-frame texture upload cheap, which is what long catalog scrolls spend their time on.
 */
internal class SeriesArtworkLoader(
    private val sources: SourceRegistry,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4 / 1_024
    }
    private val inFlight = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    suspend fun load(series: SourceSeries, targetEdgePx: Int): ImageBitmap? {
        val artwork = series.thumbnailKey?.takeIf(String::isNotBlank) ?: return null
        val edge = bucketEdge(targetEdgePx)
        val key = "${series.id.sourceId.value}:${series.id.remoteKey}:$artwork@$edge"
        cache.get(key)?.let { return it }
        val (deferred, owner) = synchronized(inFlight) {
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<ImageBitmap?>().also { inFlight[key] = it } to true
            }
        }
        if (!owner) return deferred.await()
        try {
            val decoded = runCatching {
                withContext(NonCancellable + ioDispatcher) { fetchAndDecode(series, edge) }
            }.getOrNull()
            if (decoded != null) cache.put(key, decoded)
            deferred.complete(decoded)
            return decoded
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
        }
    }

    private suspend fun fetchAndDecode(series: SourceSeries, edge: Int): ImageBitmap? {
        val opened = sources.require(series.id.sourceId).openArtwork(series) ?: return null
        val bytes = opened.readArtworkBytes() ?: return null
        return decode(bytes, edge)?.asImageBitmap()
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

    private fun decode(bytes: ByteArray, edge: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val limit = edge.coerceAtLeast(MIN_DECODE_EDGE)
        var sample = 1
        while (bounds.outWidth / sample > limit || bounds.outHeight / sample > limit) {
            if (sample > MAX_SAMPLE) break
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (bounds.outMimeType == "image/jpeg") {
                android.graphics.Bitmap.Config.RGB_565
            } else {
                android.graphics.Bitmap.Config.ARGB_8888
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun bucketEdge(px: Int): Int = when {
        px <= 256 -> 256
        px <= 384 -> 384
        px <= 512 -> 448
        px <= 768 -> 768
        px <= 1024 -> 1024
        else -> 1536
    }

    private companion object {
        const val MAX_CACHE_KB = 32 * 1_024
        const val MAX_ARTWORK_BYTES = 8 * 1_024 * 1_024
        const val MIN_DECODE_EDGE = 128
        const val MAX_SAMPLE = 64
    }
}
