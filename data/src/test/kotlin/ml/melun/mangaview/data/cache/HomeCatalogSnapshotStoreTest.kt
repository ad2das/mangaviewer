package ml.melun.mangaview.data.cache

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeCatalogSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(root: File) = HomeCatalogSnapshotStore(root, Dispatchers.IO) { 1_700_000_000_000L }

    private fun series(key: String, status: SeriesStatus? = null) = SourceSeries(
        SeriesId(SourceId("goodtoon"), key),
        "제목 $key",
        subtitle = "부제 $key",
        thumbnailKey = "/thumb/$key.jpg",
        status = status,
    )

    @Test fun savedRowsSurviveStoreRecreationWithEveryField() = runTest {
        val root = temporary.newFolder()
        val popular = listOf(series("a", SeriesStatus.ONGOING), series("b"))
        val latest = listOf(series("c", SeriesStatus.COMPLETED))
        val new = emptyList<SourceSeries>()
        store(root).save(SourceId("goodtoon"), SeriesKind.WEBTOON, popular, latest, new)

        val reopened = requireNotNull(store(root).load(SourceId("goodtoon"), SeriesKind.WEBTOON))
        assertEquals(popular, reopened.popular)
        assertEquals(latest, reopened.latest)
        assertEquals(emptyList<SourceSeries>(), reopened.new)
        assertEquals(1_700_000_000_000L, reopened.savedAtEpochMillis)
    }

    @Test fun sourceAndKindSelectTheirOwnSnapshot() = runTest {
        val root = temporary.newFolder()
        val cache = store(root)
        cache.save(SourceId("goodtoon"), SeriesKind.WEBTOON, listOf(series("webtoon")), emptyList(), emptyList())
        cache.save(SourceId("goodtoon"), SeriesKind.COMIC, listOf(series("comic")), emptyList(), emptyList())
        cache.save(SourceId("wfwf"), SeriesKind.WEBTOON, listOf(series("wfwf")), emptyList(), emptyList())

        assertEquals("webtoon", cache.load(SourceId("goodtoon"), SeriesKind.WEBTOON)?.popular?.first()?.id?.remoteKey)
        assertEquals("comic", cache.load(SourceId("goodtoon"), SeriesKind.COMIC)?.popular?.first()?.id?.remoteKey)
        assertEquals("wfwf", cache.load(SourceId("wfwf"), SeriesKind.WEBTOON)?.popular?.first()?.id?.remoteKey)
        assertNull(cache.load(SourceId("ntk"), SeriesKind.WEBTOON))
    }

    @Test fun corruptSnapshotIsIgnoredInsteadOfCrashingTheHomeTab() = runTest {
        val root = temporary.newFolder()
        val cache = store(root)
        cache.save(SourceId("goodtoon"), SeriesKind.WEBTOON, listOf(series("a")), emptyList(), emptyList())
        root.listFiles().orEmpty().filter { it.name.endsWith(".bin") }.forEach { it.writeBytes(ByteArray(4)) }

        assertNull(cache.load(SourceId("goodtoon"), SeriesKind.WEBTOON))
    }

    @Test fun replacingASnapshotLeavesNoStagingFileBehind() = runTest {
        val root = temporary.newFolder()
        val cache = store(root)
        cache.save(SourceId("goodtoon"), SeriesKind.WEBTOON, listOf(series("old")), emptyList(), emptyList())
        cache.save(SourceId("goodtoon"), SeriesKind.WEBTOON, listOf(series("new")), emptyList(), emptyList())

        assertEquals("new", cache.load(SourceId("goodtoon"), SeriesKind.WEBTOON)?.popular?.first()?.id?.remoteKey)
        assertEquals(listOf("goodtoon-webtoon.bin"), root.listFiles().orEmpty().map { it.name })
    }
}
