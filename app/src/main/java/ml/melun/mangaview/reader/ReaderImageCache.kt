package ml.melun.mangaview.reader

import android.content.Context
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.Utils
import ml.melun.mangaview.mangaview.Manga
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

object ReaderImageCache {
    private const val DIR_NAME = "reader_image_cache_v1"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val TARGET_CACHE_BYTES = 384L * 1024L * 1024L
    private val flights = ConcurrentHashMap<String, FutureTask<FetchResult>>()

    private data class FetchResult(
        val file: File,
        val bytes: ByteArray?
    )

    fun getOrFetchFile(context: Context, manga: Manga, image: String): File {
        if (!manga.isOnline) return File(image)
        return getOrFetch(context, manga, image, false).file
    }

    @Deprecated("Use getOrFetchFile and decode from disk to avoid large byte-array memory spikes.")
    fun getOrFetchBytes(context: Context, manga: Manga, image: String): ByteArray {
        if (!manga.isOnline) return getOrFetchFile(context, manga, image).readBytes()
        val result = getOrFetch(context, manga, image, true)
        return result.bytes ?: result.file.readBytes()
    }

    private fun getOrFetch(context: Context, manga: Manga, image: String, wantBytes: Boolean): FetchResult {
        val appContext = context.applicationContext
        val key = key(manga.baseMode, image)
        val finalFile = File(cacheDir(appContext), "$key.img")
        if (isUsableImage(finalFile)) {
            finalFile.setLastModified(System.currentTimeMillis())
            return FetchResult(finalFile, if (wantBytes) finalFile.readBytes() else null)
        }
        val task = FutureTask {
            if (wantBytes) {
                val bytes = downloadBytes(appContext, manga, image)
                writeAtomically(appContext, finalFile, bytes)
                FetchResult(finalFile, bytes)
            } else {
                FetchResult(downloadAtomically(appContext, manga, image, finalFile), null)
            }
        }
        val running = flights.putIfAbsent(key, task) ?: task.also { it.run() }
        return try {
            running.get()
        } finally {
            flights.remove(key, running)
        }
    }

    private fun cacheDir(context: Context): File {
        return File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    }

    private fun key(baseMode: Int, image: String): String {
        val normalized = Utils.viewerImageRequestUrl(image, baseMode)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseMode|$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun downloadAtomically(context: Context, manga: Manga, image: String, finalFile: File): File {
        val tmp = File(finalFile.parentFile, "${finalFile.name}.part.${System.nanoTime()}")
        try {
            request(context, manga, image).use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Image request failed: ${response.code}")
                val body = response.body ?: throw java.io.IOException("Empty image body")
                FileOutputStream(tmp).use { out -> body.byteStream().copyTo(out) }
            }
            if (!isUsableImage(tmp)) throw java.io.IOException("Invalid image cache file")
            replace(tmp, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            trimCache(context)
            return finalFile
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun downloadBytes(context: Context, manga: Manga, image: String): ByteArray {
        request(context, manga, image).use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Image request failed: ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty image body")
            val bytes = body.bytes()
            if (!looksLikeImage(bytes)) throw java.io.IOException("Invalid image bytes")
            return bytes
        }
    }

    private fun request(context: Context, manga: Manga, image: String): okhttp3.Response {
        val requestBuilder = Request.Builder().url(Utils.viewerImageRequestUrl(image, manga.baseMode))
        for (entry in Utils.viewerImageRequestHeaders(image, manga.baseMode).entries) {
            requestBuilder.addHeader(entry.key, entry.value)
        }
        return getHttpClient().imageClient.newCall(requestBuilder.build()).execute()
    }

    private fun writeAtomically(context: Context, finalFile: File, bytes: ByteArray) {
        val tmp = File(finalFile.parentFile, "${finalFile.name}.part.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!isUsableImage(tmp)) throw java.io.IOException("Invalid image cache file")
            replace(tmp, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            trimCache(context)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
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
            file.inputStream().use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                read > 0 && looksLikeImage(header.copyOf(read))
            }
        } catch (_: Exception) {
            false
        }
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
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true
        return false
    }

    private fun trimCache(context: Context) {
        val dir = cacheDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= TARGET_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
