package ml.melun.mangaview.data.cache

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.db.RawPageDao
import ml.melun.mangaview.data.db.RawPageEntity
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawPageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesVerifiesAndPublishesExactlyOneFinalFile() = runTest {
        val root = temporaryFolder.newFolder("cache")
        val dao = InMemoryRawPageDao()
        val store = store(root, dao)
        val bytes = ImageHeaderProbeTest.png(1_080, 9_000)

        val written = store.write(pageId(), opened(bytes))
        val found = store.find(pageId())

        assertEquals(bytes.size.toLong(), written.byteCount)
        assertEquals(1_080, written.dimensions.widthPx)
        assertEquals(9_000, written.dimensions.heightPx)
        assertNotNull(found)
        assertEquals(listOf(written.file.name), root.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun invalidBodyNeverBecomesAVisibleCacheEntry() = runTest {
        val root = temporaryFolder.newFolder("cache-invalid")
        val dao = InMemoryRawPageDao()
        val store = store(root, dao)
        val html = "<html>challenge</html>".toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { store.write(pageId(), opened(html)) }
        }

        assertFalse(root.listFiles().orEmpty().any { it.extension == "page" })
        assertEquals(0, dao.entries.size)
    }

    @Test
    fun aFreshCacheDoesNotQueryRoomForEveryKnownMissingPage() = runTest {
        val root = temporaryFolder.root.resolve("not-created-yet")
        val dao = InMemoryRawPageDao()
        val store = store(root, dao)

        repeat(20) { ordinal ->
            val id = PageId.at(pageId().episodeId, ordinal)
            assertEquals(null, store.find(id))
        }

        assertEquals(0, dao.findCalls)
        val bytes = ImageHeaderProbeTest.png(1_080, 2_000)
        val written = store.write(pageId(), opened(bytes))
        assertEquals(written, store.find(pageId()))
        assertEquals(0, dao.findCalls)
    }

    private fun store(root: File, dao: RawPageDao): RawPageStore = RawPageStore(
        root = root,
        dao = dao,
        ioDispatcher = Dispatchers.IO,
        publisher = AtomicFilePublisher { staging, destination ->
            check(staging.renameTo(destination)) { "Test publish failed" }
        },
        nowMillis = { 100L },
    )

    private fun opened(bytes: ByteArray): OpenedPage = OpenedPage(
        stream = ByteArrayPageStream(bytes),
        contentLength = bytes.size.toLong(),
        contentType = "image/unknown",
        entityTag = null,
        lastModified = null,
    )

    private fun pageId(): PageId {
        val series = SeriesId(SourceId("source"), "series")
        return PageId(EpisodeId(series, "episode"), "page")
    }
}

internal class ByteArrayPageStream(private val bytes: ByteArray) : PageByteStream {
    private var position = 0

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        if (position == bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}

internal class InMemoryRawPageDao : RawPageDao {
    val entries = linkedMapOf<String, RawPageEntity>()
    var findCalls = 0
    var upsertAllCalls = 0

    override suspend fun find(cacheKey: String): RawPageEntity? {
        findCalls += 1
        return entries[cacheKey]
    }

    override suspend fun upsert(entity: RawPageEntity) {
        entries[entity.cacheKey] = entity
    }

    override suspend fun upsertAll(entities: List<RawPageEntity>) {
        upsertAllCalls += 1
        entities.forEach { entries[it.cacheKey] = it }
    }

    override suspend fun touch(cacheKey: String, atMillis: Long) {
        entries[cacheKey]?.let { entries[cacheKey] = it.copy(lastAccessEpochMillis = atMillis) }
    }

    override suspend fun delete(cacheKey: String) {
        entries.remove(cacheKey)
    }

    override suspend fun oldestFirst(): List<RawPageEntity> =
        entries.values.sortedBy(RawPageEntity::lastAccessEpochMillis)

    override suspend fun deleteAll() {
        entries.clear()
    }
}
