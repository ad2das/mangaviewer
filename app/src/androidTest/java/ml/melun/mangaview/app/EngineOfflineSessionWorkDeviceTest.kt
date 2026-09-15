package ml.melun.mangaview.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.core.lowerHex
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.SessionPosition
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EngineOfflineSessionWorkDeviceTest {
    private val series = SeriesId(SourceId("wfwf"), "series-device")
    private val episode = SourceEpisode(EpisodeId(series, "ep-1"), "episode one")
    private val next = EpisodeId(series, "ep-2")

    private class NoNetworkLiveWork : EngineViewerWork {
        var episodeExecutions = 0
        var pageExecutions = 0

        private fun <T : Any> failing(type: Class<T>, resource: String, operation: String,
            priority: WorkPriority = WorkPriority.FOCUS, record: (() -> Unit)? = null,
        ) = WorkRequest(WorkKey("offline-device", resource, operation, "device", type),
            WorkDomain.CONTROL, priority, execute = {
                record?.invoke()
                throw IOException("device test: no network")
            })

        override fun position(episodeId: EpisodeId) = failing(SessionPosition::class.java,
            episodeId.toString(), "position")

        override fun episodes(seriesId: SeriesId, priority: WorkPriority) =
            failing(EngineEpisodeCatalog::class.java, seriesId.toString(), "catalog.episodes", priority)

        override fun episode(episodeId: EpisodeId, priority: WorkPriority) =
            failing(EpisodeAccessPlan::class.java, episodeId.toString(), "episode", priority,
                { episodeExecutions++ })

        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) =
            failing(AdjacentEpisodes::class.java, episodeId.toString(), "navigation", priority)

        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) =
            failing(StoredPage::class.java, pageId.toString(), "page", priority, { pageExecutions++ })
    }

    @Test fun downloadedEpisodeIsServedFromDiskWithoutTouchingTheLivePlan() = withStore { store, live ->
        val subject = EngineOfflineSessionWork(live, store)
        val coordinator = coordinator()
        try {
            val plan = await(coordinator, subject.episode(episode.id, WorkPriority.FOCUS))
            assertTrue(plan.localOnly)
            assertTrue(plan.contentRevision.startsWith("offline:"))
            assertEquals(episode.id, plan.manifest.id)
            assertEquals(0, live.episodeExecutions)

            val pageId = plan.manifest.pages.first().id
            val stored = await(coordinator, subject.page(plan, pageId, WorkPriority.FOCUS))
            assertEquals(0, live.pageExecutions)
            assertTrue(stored.file.isFile)
            assertEquals(plan.contentRevision, stored.contentRevision)
            assertEquals(2_048L, stored.byteCount)
            assertEquals("image/jpeg", stored.mediaType)
            assertEquals(PageDimensions(800, 1200), stored.dimensions)
        } finally {
            coordinator.close()
        }
    }

    @Test fun navigationAndCatalogFallBackToTheDownloadWhenTheNetworkFails() = withStore { store, live ->
        val subject = EngineOfflineSessionWork(live, store)
        val coordinator = coordinator()
        try {
            val navigation = await(coordinator, subject.navigation(episode.id, WorkPriority.FOCUS))
            assertEquals(next, navigation.next)

            val catalog = await(coordinator, subject.episodes(series, WorkPriority.FOCUS))
            assertEquals(listOf(episode.id), catalog.episodes.map { it.id })
        } finally {
            coordinator.close()
        }
    }

    @Test fun anEpisodeThatWasNeverDownloadedStillUsesTheLivePlan() = withStore { store, live ->
        val subject = EngineOfflineSessionWork(live, store)
        val coordinator = coordinator()
        try {
            try {
                await(coordinator, subject.episode(EpisodeId(series, "ep-missing"), WorkPriority.FOCUS))
                fail("expected the live plan to fail without a network")
            } catch (expected: IOException) {
                assertEquals(1, live.episodeExecutions)
            }
        } finally {
            coordinator.close()
        }
    }

    private fun coordinator() = WorkCoordinator(CoroutineScope(Dispatchers.Default + SupervisorJob()))

    private suspend fun <T : Any> await(coordinator: WorkCoordinator, request: WorkRequest<T>): T {
        val subscription = coordinator.submit(request)
        return try {
            subscription.await()
        } finally {
            subscription.close()
            withContext(NonCancellable) { subscription.awaitReleased() }
        }
    }

    private fun withStore(block: suspend (OfflineEpisodeStore, NoNetworkLiveWork) -> Unit) = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "offline-device-${System.nanoTime()}")
        try {
            assertTrue("test root must be creatable", root.mkdirs())
            val store = OfflineEpisodeStore(File(root, "store"), Dispatchers.IO)
            val cached = listOf(0, 1).map { index -> cachedPage(root, index) }
            val pages = cached.mapIndexed { index, page ->
                PageSpec(page.pageId, index, page.dimensions, page.byteCount, page.sha256)
            }
            store.save(SourceSeries(series, "series under test"), episode,
                EpisodeManifest(episode.id, "episode one", pages, nextEpisodeId = next), cached)
            block(store, NoNetworkLiveWork())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun cachedPage(root: File, index: Int): CachedPage {
        val id = PageId(episode.id, "p${index + 1}")
        val file = File(root, "raw-$index.jpg")
        file.writeBytes(ByteArray(2_048) { ((it * 31 + index) and 0x7F).toByte() })
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).lowerHex()
        return CachedPage(id, file, file.length(), sha, "image/jpeg", PageDimensions(800, 1200))
    }
}
