package ml.melun.mangaview.data.cache

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.db.RawPageDao
import ml.melun.mangaview.data.db.RawPageEntity
import ml.melun.mangaview.source.OpenedPage

class RawPageStore(
    private val root: File,
    private val dao: RawPageDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val publisher: AtomicFilePublisher = PosixAtomicFilePublisher(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maximumBytes: Long = DEFAULT_CACHE_BYTES,
) : RawPageCache {
    private val indexWriter = RawPageIndexWriter(dao)
    private val recent = RecentRawPageIndex()
    private val bootstrapLock = Any()
    private val trimMutex = Mutex()
    private val activeWriteKeys = mutableSetOf<String>()
    private val activeWriteLock = Any()
    private val transferBuffers = PageTransferBufferPool(BUFFER_SIZE, MAX_CONCURRENT_TRANSFERS)
    @Volatile
    private var bootstrapState = CacheBootstrapState.UNKNOWN

    init {
        require(maximumBytes > 0L) { "Cache budget must be positive" }
    }

    override suspend fun find(pageId: PageId): CachedPage? = withContext(ioDispatcher) {
        val key = PageCacheKey.of(pageId)
        recent[key]?.let { cached ->
            if (cached.file.isFile && cached.file.length() == cached.byteCount) return@withContext cached
            recent.remove(key, cached)
        }
        if (wasEmptyAtFirstLookup()) return@withContext null
        val entity = dao.find(key) ?: return@withContext null
        val expectedName = fileName(key)
        if (entity.relativePath != expectedName) return@withContext discard(entity, null)
        val file = File(root, expectedName)
        if (!isValidStoredFile(file, entity)) return@withContext discard(entity, file)
        dao.touch(key, nowMillis())
        entity.toCachedPage(pageId, file).also { recent[key] = it }
    }

    override suspend fun write(
        pageId: PageId,
        openedPage: OpenedPage,
        onPreview: ((PageTransferPreview) -> Unit)?,
    ): CachedPage =
        withContext(ioDispatcher) {
            ensureRoot()
            val key = PageCacheKey.of(pageId)
            synchronized(activeWriteLock) { activeWriteKeys += key }
            val destination = File(root, fileName(key))
            val staging = File(root, "$key.${UUID.randomUUID()}.part")
            var published = false
            try {
                val result = streamToFile(pageId, openedPage, staging, onPreview)
                publisher.publish(staging, destination)
                published = true
                // Streaming previews point at the staging pathname. Publishing is an atomic
                // rename, so a decoder that was queued behind network I/O can no longer open
                // that pathname after the transfer finishes. Publish one final notification
                // with the stable cache pathname; the conflated preview queue then replaces any
                // stale staging notification without copying the encoded body.
                if (onPreview != null && result.header.supportsVerifiedPrefixDecode) {
                    onPreview(PageTransferPreview(pageId, destination, result.byteCount, result.header))
                }
                val entity = result.toEntity(pageId, key, destination.name, nowMillis())
                indexWriter.upsert(entity)
                val cached = entity.toCachedPage(pageId, destination).also { recent[key] = it }
                trimAfterCompletedWrite()
                cached
            } catch (failure: Throwable) {
                staging.delete()
                if (published) destination.delete()
                throw failure
            } finally {
                synchronized(activeWriteLock) { activeWriteKeys -= key }
            }
        }

    override suspend fun remove(pageId: PageId): Unit = withContext(ioDispatcher) {
        val key = PageCacheKey.of(pageId)
        recent.remove(key)
        dao.delete(key)
        File(root, fileName(key)).delete()
        Unit
    }

    suspend fun trimTo(maxBytes: Long) = withContext(ioDispatcher) {
        require(maxBytes >= 0L) { "Cache budget must not be negative" }
        trimToLocked(maxBytes, maxBytes)
    }

    private suspend fun trimAfterCompletedWrite() {
        val lowWatermark = multiplyFraction(maximumBytes, CACHE_LOW_WATERMARK_PERCENT)
        trimToLocked(maximumBytes, lowWatermark)
    }

    private suspend fun trimToLocked(triggerBytes: Long, targetBytes: Long) = trimMutex.withLock {
        var total = dao.totalBytes()
        if (total <= triggerBytes) return@withLock
        val entries = dao.oldestFirst()
        val protected = synchronized(activeWriteLock) { activeWriteKeys.toSet() }
        for (entry in entries) {
            if (total <= targetBytes) break
            if (entry.cacheKey in protected) continue
            dao.delete(entry.cacheKey)
            recent.remove(entry.cacheKey)
            File(root, entry.relativePath).delete()
            total -= entry.byteCount
        }
    }

    private fun multiplyFraction(value: Long, percent: Int): Long =
        (value / 100L) * percent + (value % 100L) * percent / 100L

    private suspend fun streamToFile(
        pageId: PageId,
        openedPage: OpenedPage,
        destination: File,
        onPreview: ((PageTransferPreview) -> Unit)?,
    ): WriteResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val headerProbe = IncrementalHeaderProbe(MAX_HEADER_BYTES)
        var total = 0L
        var nextPreviewAt = PREVIEW_INTERVAL_BYTES
        FileOutputStream(destination).use { output ->
            while (true) {
                openedPage.stream.awaitReadable()
                val finished = transferBuffers.use read@ { buffer ->
                    val count = openedPage.stream.readAtMost(buffer, 0, buffer.size)
                    if (count < 0) return@read true
                    require(count > 0) { "Page stream returned zero bytes" }
                    total = Math.addExact(total, count.toLong())
                    require(total <= MAX_PAGE_BYTES) { "Encoded page exceeds the size limit" }
                    digest.update(buffer, 0, count)
                    headerProbe.accept(buffer, count)
                    output.write(buffer, 0, count)
                    val previewHeader = headerProbe.value
                    if (onPreview != null && previewHeader?.supportsVerifiedPrefixDecode == true &&
                        total >= nextPreviewAt
                    ) {
                        output.flush()
                        onPreview(PageTransferPreview(pageId, destination, total, previewHeader))
                        nextPreviewAt = Math.addExact(total, PREVIEW_INTERVAL_BYTES)
                    }
                    false
                }
                if (finished) break
            }
        }
        validateLength(openedPage, total)
        val header = headerProbe.result()
        val sha256 = with(PageCacheKey) { digest.digest().toHex() }
        return WriteResult(total, sha256, header)
    }

    private fun validateLength(openedPage: OpenedPage, actual: Long) {
        require(actual > 0L) { "Encoded page is empty" }
        val declared = openedPage.contentLength
        require(declared == null || declared == actual) { "Encoded page length does not match response" }
    }

    private fun ensureRoot() {
        if (root.isDirectory || root.mkdirs()) return
        // Concurrent first writes may both observe a missing directory. One mkdir wins and the
        // other returns false even though the required directory now exists.
        require(root.isDirectory) { "Page cache root is unavailable" }
    }

    private fun wasEmptyAtFirstLookup(): Boolean {
        val known = bootstrapState
        if (known != CacheBootstrapState.UNKNOWN) return known == CacheBootstrapState.EMPTY
        return synchronized(bootstrapLock) {
            if (bootstrapState == CacheBootstrapState.UNKNOWN) {
                val hasPage = root.isDirectory && root.listFiles().orEmpty().any { it.extension == "page" }
                bootstrapState = if (hasPage) CacheBootstrapState.INDEXED else CacheBootstrapState.EMPTY
            }
            bootstrapState == CacheBootstrapState.EMPTY
        }
    }

    private fun isValidStoredFile(file: File, entity: RawPageEntity): Boolean {
        if (!file.isFile || file.length() != entity.byteCount) return false
        return runCatching {
            val probe = IncrementalHeaderProbe(MAX_HEADER_BYTES)
            val buffer = ByteArray(HEADER_INITIAL_BYTES)
            FileInputStream(file).use { input ->
                while (!probe.complete) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    probe.accept(buffer, count)
                }
            }
            probe.result()
        }.isSuccess
    }

    private suspend fun discard(entity: RawPageEntity, file: File?): CachedPage? {
        recent.remove(entity.cacheKey)
        dao.delete(entity.cacheKey)
        file?.delete()
        return null
    }

    private fun WriteResult.toEntity(
        pageId: PageId,
        cacheKey: String,
        relativePath: String,
        now: Long,
    ): RawPageEntity = RawPageEntity(
        cacheKey = cacheKey,
        sourceKey = pageId.episodeId.seriesId.sourceId.value,
        seriesKey = pageId.episodeId.seriesId.remoteKey,
        episodeKey = pageId.episodeId.remoteKey,
        pageKey = pageId.remoteKey,
        relativePath = relativePath,
        byteCount = byteCount,
        sha256 = sha256,
        mediaType = header.mediaType,
        widthPx = header.dimensions.widthPx,
        heightPx = header.dimensions.heightPx,
        createdAtEpochMillis = now,
        lastAccessEpochMillis = now,
    )

    private fun RawPageEntity.toCachedPage(pageId: PageId, file: File): CachedPage = CachedPage(
        pageId = pageId,
        file = file,
        byteCount = byteCount,
        sha256 = sha256,
        mediaType = mediaType,
        dimensions = ml.melun.mangaview.core.PageDimensions(widthPx, heightPx),
    )

    private fun fileName(key: String): String = "$key.page"

    private data class WriteResult(
        val byteCount: Long,
        val sha256: String,
        val header: ImageHeader,
    )

    private companion object {
        const val BUFFER_SIZE = 512 * 1_024
        const val MAX_CONCURRENT_TRANSFERS = 6
        const val PREVIEW_INTERVAL_BYTES = 128L * 1_024L
        const val HEADER_INITIAL_BYTES = 4 * 1_024
        const val MAX_HEADER_BYTES = 1 * 1_024 * 1_024
        const val MAX_PAGE_BYTES = 512L * 1_024L * 1_024L
        const val DEFAULT_CACHE_BYTES = 1L * 1_024L * 1_024L * 1_024L
        const val CACHE_LOW_WATERMARK_PERCENT = 90
    }

    private enum class CacheBootstrapState {
        UNKNOWN,
        EMPTY,
        INDEXED,
    }
}

/** Keeps large transfer arrays out of the managed-heap allocation/GC path during long episodes. */
internal class PageTransferBufferPool(
    private val bufferBytes: Int,
    maximumConcurrentTransfers: Int,
) {
    private val lanes = Semaphore(maximumConcurrentTransfers)
    private val available = ArrayDeque<ByteArray>(maximumConcurrentTransfers)

    init {
        require(bufferBytes > 0)
        require(maximumConcurrentTransfers > 0)
    }

    suspend fun <T> use(block: suspend (ByteArray) -> T): T = lanes.withPermit {
        val buffer = synchronized(available) {
            if (available.isEmpty()) ByteArray(bufferBytes) else available.removeFirst()
        }
        try {
            block(buffer)
        } finally {
            synchronized(available) { available.addLast(buffer) }
        }
    }
}

private class RecentRawPageIndex(private val maximumEntries: Int = 1_024) {
    private val values = LinkedHashMap<String, CachedPage>(maximumEntries, 0.75f, true)

    init {
        require(maximumEntries > 0)
    }

    operator fun get(key: String): CachedPage? = synchronized(values) { values[key] }

    operator fun set(key: String, value: CachedPage) = synchronized(values) {
        values[key] = value
        while (values.size > maximumEntries) {
            val oldest = values.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    fun remove(key: String) = synchronized(values) { values.remove(key) }

    fun remove(key: String, expected: CachedPage) = synchronized(values) {
        if (values[key] == expected) values.remove(key)
        Unit
    }
}

internal class RawPageIndexWriter(private val dao: RawPageDao) {
    private data class Pending(
        val entity: RawPageEntity,
        val committed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Any()
    private val pending = mutableListOf<Pending>()
    private var draining = false

    suspend fun upsert(entity: RawPageEntity) {
        val item = Pending(entity)
        val leader = synchronized(lock) {
            pending += item
            if (draining) false else true.also { draining = true }
        }
        if (leader) {
            // Let other concurrently completed files join this transaction without a timer or
            // durability relaxation. Every caller still resumes only after its batch commits.
            yield()
            drain()
        }
        item.committed.await()
    }

    private suspend fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) {
                    draining = false
                    return
                }
                pending.toList().also { pending.clear() }
            }
            runCatching { dao.upsertAll(batch.map(Pending::entity)) }
                .onSuccess { batch.forEach { it.committed.complete(Unit) } }
                .onFailure { failure ->
                    batch.forEach { it.committed.completeExceptionally(failure) }
                }
        }
    }
}

internal class IncrementalHeaderProbe(private val maximumBytes: Int) {
    private var bytes = ByteArray(minOf(INITIAL_BYTES, maximumBytes))
    private var used = 0
    private var header: ImageHeader? = null

    val complete: Boolean
        get() = header != null || used == maximumBytes

    val value: ImageHeader?
        get() = header

    fun accept(source: ByteArray, count: Int) {
        require(count in 0..source.size)
        var sourceOffset = 0
        while (!complete && sourceOffset < count) {
            growIfFull()
            val copied = minOf(count - sourceOffset, bytes.size - used)
            source.copyInto(bytes, used, sourceOffset, sourceOffset + copied)
            used += copied
            sourceOffset += copied
            header = runCatching { ImageHeaderProbe.inspect(bytes, used) }.getOrNull()
        }
    }

    fun result(): ImageHeader = header ?: ImageHeaderProbe.inspect(bytes, used)

    private fun growIfFull() {
        if (used < bytes.size || bytes.size == maximumBytes) return
        bytes = bytes.copyOf(minOf(maximumBytes, bytes.size * 2))
    }

    private companion object {
        const val INITIAL_BYTES = 4 * 1_024
    }
}
