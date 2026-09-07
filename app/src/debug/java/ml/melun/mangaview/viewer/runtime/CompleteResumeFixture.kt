package ml.melun.mangaview.viewer.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.data.cache.CompleteEpisodeSnapshotStore
import ml.melun.mangaview.data.cache.RawPageStore
import ml.melun.mangaview.data.db.RawPageDao
import ml.melun.mangaview.data.db.RawPageEntity
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent

/** Normal fixture source reads populate the real raw store; the second cycle may read cache only. */
internal class CompleteResumeFixture(
    directory: File,
    val images: SessionMemoryFixture = SessionMemoryFixture(File(directory, "source")),
) : ContentSource by images {
    private val dao = CompleteResumeFixtureDao()
    val cache = RawPageStore(File(directory, "raw"), dao, Dispatchers.IO)
    val snapshotRoot = File(directory, "snapshots")
    val prepareCalls = AtomicInteger()
    @Volatile var sourceAllowed = true

    fun snapshots() = CompleteEpisodeSnapshotStore(snapshotRoot, cache, Dispatchers.IO)

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) {
        prepareCalls.incrementAndGet()
        check(sourceAllowed) { "Cached resume called source.prepare" }
        images.prepare(episodeId, intent)
    }

    override suspend fun manifest(episodeId: EpisodeId) = images.manifest(episodeId).also {
        check(sourceAllowed) { "Cached resume called source.manifest" }
    }

    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage {
        check(sourceAllowed) { "Cached resume tried to fetch a source page" }
        val encoded = images.fetch(pageId, PageFetchPriority.VISIBLE) {}
        val file = File(encoded.path)
        val input = file.inputStream()
        return OpenedPage(object : PageByteStream {
            override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int =
                input.read(destination, offset, byteCount)
            override fun close() = input.close()
        }, file.length(), "image/png", null, null)
    }

    override suspend fun openPage(pageId: PageId, validation: PageValidation?, priority: PageFetchPriority): OpenedPage =
        openPage(pageId, validation)

    fun snapshotCount(): Int = snapshotRoot.listFiles().orEmpty().count { it.extension == "snapshot" }
    fun leaseCount(): Int = snapshotRoot.listFiles().orEmpty().count { it.name.startsWith(".lease-") }

    fun bodyDescriptorCount(): Int {
        val rawPath = File(snapshotRoot.parentFile, "raw").canonicalPath + "/"
        return File("/proc/self/fd").listFiles().orEmpty().count { descriptor ->
            runCatching { android.system.Os.readlink(descriptor.absolutePath).startsWith(rawPath) }.getOrDefault(false)
        }
    }
}

private class CompleteResumeFixtureDao : RawPageDao {
    private val entries = ConcurrentHashMap<String, RawPageEntity>()
    override suspend fun find(cacheKey: String): RawPageEntity? = entries[cacheKey]
    override suspend fun upsert(entity: RawPageEntity) { entries[entity.cacheKey] = entity }
    override suspend fun upsertAll(entities: List<RawPageEntity>) { entities.forEach { upsert(it) } }
    override suspend fun touch(cacheKey: String, atMillis: Long) {
        entries.computeIfPresent(cacheKey) { _, page -> page.copy(lastAccessEpochMillis = atMillis) }
    }
    override suspend fun delete(cacheKey: String) { entries.remove(cacheKey) }
    override suspend fun oldestFirst(): List<RawPageEntity> = entries.values.sortedBy { it.lastAccessEpochMillis }
    override suspend fun totalBytes(): Long = entries.values.sumOf { it.byteCount }
    override suspend fun deleteAll() = entries.clear()
}
