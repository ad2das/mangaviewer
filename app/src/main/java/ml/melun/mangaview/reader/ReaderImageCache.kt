package ml.melun.mangaview.reader

import android.content.Context
import android.net.Uri
import ml.melun.mangaview.MainApplication.getHttpClient
import ml.melun.mangaview.Utils
import ml.melun.mangaview.glide.ViewerWarmupManager
import ml.melun.mangaview.mangaview.Manga
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

object ReaderImageCache {
    private const val DIR_NAME = "reader_image_cache_v1"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val TARGET_CACHE_BYTES = 384L * 1024L * 1024L
    private const val TRIM_DEBOUNCE_MS = 30_000L
    private val flights = ConcurrentHashMap<String, FutureTask<File>>()
    private val activeReads = ConcurrentHashMap<String, AtomicInteger>()
    private val trimLock = Any()
    private val trimScheduled = AtomicBoolean(false)
    private val trimDirty = AtomicBoolean(false)
    private val lastTrimStartedAt = AtomicLong(0L)
    private val trimExecutor = Executors.newSingleThreadScheduledExecutor(ThreadFactory { runnable ->
        Thread(runnable, "ReaderImageCacheTrim").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    })

    class FileLease internal constructor(
        val file: File,
        private val key: String?
    ) : AutoCloseable {
        override fun close() {
            if (key != null) releaseActiveRead(key)
        }
    }

    fun leaseFile(context: Context, manga: Manga, image: String): FileLease {
        if (!manga.isOnline) return FileLease(File(image), null)
        val key = key(manga.baseMode, image)
        retainActiveRead(key)
        return try {
            val file = getOrFetch(context, manga, image)
            file.setLastModified(System.currentTimeMillis())
            FileLease(file, key)
        } catch (t: Throwable) {
            releaseActiveRead(key)
            throw t
        }
    }

    private fun retainActiveRead(key: String) {
        activeReads.compute(key) { _, count ->
            (count ?: AtomicInteger(0)).also { it.incrementAndGet() }
        }
    }

    private fun releaseActiveRead(key: String) {
        activeReads.computeIfPresent(key) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    fun getOrFetchFile(context: Context, manga: Manga, image: String): File {
        if (!manga.isOnline) return File(image)
        return getOrFetch(context, manga, image)
    }

    fun cachedFile(context: Context, manga: Manga, image: String): File? {
        if (!manga.isOnline) return File(image)
        val appContext = context.applicationContext
        val file = File(cacheDir(appContext), "${key(manga.baseMode, image)}.img")
        return if (isUsableImage(file)) file else null
    }

    private fun getOrFetch(context: Context, manga: Manga, image: String): File {
        val appContext = context.applicationContext
        val key = key(manga.baseMode, image)
        val finalFile = File(cacheDir(appContext), "$key.img")
        if (isUsableImage(finalFile)) {
            finalFile.setLastModified(System.currentTimeMillis())
            return finalFile
        }
        val task = FutureTask {
            downloadAtomically(appContext, manga, image, finalFile)
        }
        val running = flights.putIfAbsent(key, task) ?: task.also { it.run() }
        return try {
            running.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Interrupted while waiting for image cache").apply {
                initCause(e)
            }
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is Exception) throw cause
            if (cause is Error) throw cause
            throw java.io.IOException("Image cache fetch failed", cause)
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
            val requestUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
            requestWithNtkGeneratedFallback(context, manga, image).use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Image request failed: ${response.code} url=$requestUrl")
                val body = response.body ?: throw java.io.IOException("Empty image body")
                FileOutputStream(tmp).use { out -> body.byteStream().copyTo(out) }
            }
            if (!isUsableImage(tmp) && !replaceWithImageExtractedFromHtml(context, manga, image, tmp)) {
                throw java.io.IOException("Invalid image cache file")
            }
            replace(tmp, finalFile)
            finalFile.setLastModified(System.currentTimeMillis())
            scheduleTrim(context)
            return finalFile
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun request(context: Context, manga: Manga, image: String): okhttp3.Response {
        val requestBuilder = Request.Builder().url(Utils.viewerImageRequestUrl(image, manga.baseMode))
        for (entry in Utils.viewerImageRequestHeaders(image, manga.baseMode).entries) {
            requestBuilder.addHeader(entry.key, entry.value)
        }
        return getHttpClient().imageClient.newCall(requestBuilder.build()).execute()
    }

    private fun requestWithNtkGeneratedFallback(context: Context, manga: Manga, image: String): okhttp3.Response {
        val response = request(context, manga, image)
        if (response.isSuccessful || !shouldTryNtkGeneratedExtensionFallback(image)) return response
        response.close()
        for (candidate in ntkGeneratedExtensionFallbacks(image)) {
            val fallback = request(context, manga, candidate)
            if (fallback.isSuccessful) return fallback
            fallback.close()
        }
        return request(context, manga, image)
    }

    private fun shouldTryNtkGeneratedExtensionFallback(image: String): Boolean {
        val lower = image.lowercase()
        return lower.contains("://i.toonflix.app/")
            && Regex("/(manhwa|webtoon)/\\d+/[^/?#]+/p\\d{3}\\.(jpg|png|webp)(?:[?#].*)?$").containsMatchIn(lower)
    }

    private fun ntkGeneratedExtensionFallbacks(image: String): List<String> {
        val match = Regex("(?i)\\.(jpg|png|webp)([?#].*)?$").find(image) ?: return emptyList()
        val current = match.groupValues[1].lowercase()
        val suffix = match.groupValues.getOrNull(2).orEmpty()
        val prefix = image.substring(0, match.range.first)
        return listOf("jpg", "png", "webp")
            .filter { it != current }
            .map { "$prefix.$it$suffix" }
    }

    private fun replaceWithImageExtractedFromHtml(
        context: Context,
        manga: Manga,
        image: String,
        htmlFile: File
    ): Boolean {
        if (!looksLikeHtml(htmlFile)) return false
        val html = try {
            htmlFile.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val sourceUrl = Utils.viewerImageRequestUrl(image, manga.baseMode)
        for (candidate in extractImageCandidates(html, sourceUrl, manga.baseMode)) {
            val candidateFile = File(htmlFile.parentFile, "${htmlFile.name}.htmlimg.${System.nanoTime()}")
            try {
                request(context, manga, candidate).use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    FileOutputStream(candidateFile).use { out -> body.byteStream().copyTo(out) }
                }
                if (isUsableImage(candidateFile)) {
                    if (htmlFile.exists()) htmlFile.delete()
                    if (!candidateFile.renameTo(htmlFile)) {
                        candidateFile.copyTo(htmlFile, overwrite = true)
                        candidateFile.delete()
                    }
                    return true
                }
            } catch (_: Exception) {
                // Try the next extracted candidate.
            } finally {
                if (candidateFile.exists()) candidateFile.delete()
            }
        }
        return false
    }

    internal fun extractImageCandidates(html: String, sourceUrl: String, baseMode: Int): List<String> {
        if (html.isBlank()) return emptyList()
        val candidates = LinkedHashSet<String>()
        val document = Jsoup.parse(html)
        for (img in document.select("img")) {
            for (attr in HTML_IMAGE_ATTRS) {
                addHtmlImageCandidate(candidates, img.attr(attr), sourceUrl, baseMode)
            }
        }
        for (match in HTML_IMAGE_URL_PATTERN.findAll(html)) {
            addHtmlImageCandidate(candidates, match.value, sourceUrl, baseMode)
        }
        return candidates.toList()
    }

    @JvmStatic
    fun extractImageCandidatesForTest(html: String, sourceUrl: String, baseMode: Int): List<String> {
        return extractImageCandidates(html, sourceUrl, baseMode)
    }

    private fun addHtmlImageCandidate(
        candidates: MutableSet<String>,
        rawCandidate: String?,
        sourceUrl: String,
        baseMode: Int
    ) {
        val candidate = normalizeHtmlImageCandidate(rawCandidate, baseMode) ?: return
        if (candidate == sourceUrl) return
        if (!isAllowedHtmlImageCandidate(candidate, sourceUrl)) return
        candidates.add(candidate)
    }

    private fun normalizeHtmlImageCandidate(candidate: String?, baseMode: Int): String? {
        val raw = candidate?.trim()?.replace("&amp;", "&") ?: return null
        if (raw.isEmpty()
            || raw.startsWith("data:", ignoreCase = true)
            || raw.startsWith("javascript:", ignoreCase = true)
            || raw.startsWith("about:", ignoreCase = true)
        ) return null
        return Utils.viewerImageRequestUrl(raw, baseMode)
    }

    private fun isAllowedHtmlImageCandidate(candidate: String, sourceUrl: String): Boolean {
        val lower = candidate.lowercase()
        if (!lower.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)([?#].*)?$"))) return false
        if (lower.contains("sprite")
            || lower.contains("logo")
            || lower.contains("favicon")
            || lower.contains("banner")
            || lower.contains("advert")
            || lower.contains("sponsor")
            || lower.contains("popup")
            || lower.contains("/ad/")
            || lower.contains("/ads/")
            || lower.contains("/thumb")
            || lower.contains("/data/member/")
        ) return false

        val sourceName = fileName(sourceUrl)
        val candidateName = fileName(candidate)
        if (sourceName.isNotEmpty() && sourceName == candidateName) return true

        return lower.contains("/webtoon_uploads/")
            || lower.contains("/manhwa_uploads/")
            || lower.contains("/comic_uploads/")
            || lower.contains("/blacktoon/episodes/")
            || lower.contains("/webtoon/")
            || lower.contains("/manhwa/")
            || lower.contains("/comic/")
    }

    private fun fileName(url: String): String {
        return try {
            Uri.parse(url).lastPathSegment?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
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

    private fun looksLikeHtml(file: File): Boolean {
        if (!file.isFile || file.length() < 16L) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(64)
                val read = input.read(header)
                if (read <= 0) return false
                val text = String(header, 0, read, Charsets.UTF_8).trimStart().lowercase()
                text.startsWith("<!doctype html")
                    || text.startsWith("<html")
                    || text.startsWith("<head")
                    || text.startsWith("<body")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleTrim(context: Context) {
        val now = System.currentTimeMillis()
        val appContext = context.applicationContext
        if (!trimScheduled.compareAndSet(false, true)) {
            trimDirty.set(true)
            return
        }
        val delayMs = (TRIM_DEBOUNCE_MS - (now - lastTrimStartedAt.get())).coerceAtLeast(0L)
        trimExecutor.schedule({
            try {
                trimCache(appContext)
            } finally {
                trimScheduled.set(false)
                if (trimDirty.getAndSet(false)) scheduleTrim(appContext)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun trimCache(context: Context) = synchronized(trimLock) {
        val startedAt = System.currentTimeMillis()
        lastTrimStartedAt.set(startedAt)
        val dir = cacheDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= TARGET_CACHE_BYTES) break
            val key = file.name.removeSuffix(".img")
            if ((activeReads[key]?.get() ?: 0) > 0) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
        ViewerWarmupManager.logMetric("reader_cache_trim_ms", System.currentTimeMillis() - startedAt)
    }

    private val HTML_IMAGE_ATTRS = arrayOf("data-original", "data-src", "data-lazy-src", "data-url", "src")
    private val HTML_IMAGE_URL_PATTERN = Regex(
        "https?://[^\\\"'<>\\s)]+\\.(?:jpg|jpeg|png|webp|gif)(?:[?#][^\\\"'<>\\s)]*)?",
        RegexOption.IGNORE_CASE
    )
}
