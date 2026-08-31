package ml.melun.mangaview.data.cache

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.data.db.RawPageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RawPageIndexWriterTest {
    @Test
    fun concurrentPublicationsCommitInOneDurableBatch() = runTest {
        val dao = InMemoryRawPageDao()
        val writer = RawPageIndexWriter(dao)

        val writes = List(6) { ordinal ->
            launch { writer.upsert(entity(ordinal)) }
        }
        writes.joinAll()

        assertEquals(6, dao.entries.size)
        assertEquals(1, dao.upsertAllCalls)
    }

    private fun entity(ordinal: Int) = RawPageEntity(
        cacheKey = "cache-$ordinal",
        sourceKey = "source",
        seriesKey = "series",
        episodeKey = "episode",
        pageKey = "page-$ordinal",
        relativePath = "cache-$ordinal.page",
        byteCount = 1L,
        sha256 = "sha-$ordinal",
        mediaType = "image/png",
        widthPx = 1,
        heightPx = 1,
        createdAtEpochMillis = 1L,
        lastAccessEpochMillis = 1L,
    )
}
