package ml.melun.mangaview.app

import java.io.File
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.AdjacentEpisodes
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineOpeningPreparationsTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "1")

    @Test fun viewerSharesPreparedPlanAndSavedOpeningPagesAndOwnsThemAfterHandoff() = runTest {
        val source = Source()
        val coordinator = WorkCoordinator(this)
        val openings = EngineOpeningPreparations(this, StandardTestDispatcher(testScheduler), coordinator, { source })
        openings.warm(episode)
        runCurrent()
        assertEquals(listOf(episode), source.episodes)
        assertEquals((2..7).map { PageId.at(episode, it) }.toSet(), source.pageCalls.keys)
        val handoff = openings.claim(episode)
        handoff.awaitPredecessor()
        val plan = coordinator.submit(source.episode(episode, WorkPriority.FOCUS))
        val page = coordinator.submit(source.page(plan.await(), PageId.at(episode, 2), WorkPriority.FOCUS))
        page.await()
        openings.cancelPrediction()
        handoff.releasePreparation()
        assertEquals(1, source.episodes.size)
        assertEquals(1, source.pageCalls[PageId.at(episode, 2)])
        assertEquals(1, source.livePages)
        // Autosave-driven library observations must not launch competing work during reading.
        openings.warm(episode.copy(remoteKey = "2"))
        runCurrent()
        assertEquals(1, source.episodes.size)
        page.close(); page.awaitReleased()
        plan.close(); plan.awaitReleased()
        handoff.close(); openings.close()
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun cancellationChainDrainsEvenWhenReplacementNeverStarted() = runTest {
        val source = Source()
        val cleanup = CompletableDeferred<Unit>()
        source.beforeEpisode = { try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } } }
        val coordinator = WorkCoordinator(this)
        val openings = EngineOpeningPreparations(this, StandardTestDispatcher(testScheduler), coordinator, { source })
        openings.warm(episode); runCurrent()
        openings.warm(episode.copy(remoteKey = "2"))
        openings.cancelPrediction()
        val handoff = openings.claim(episode.copy(remoteKey = "3"))
        val draining = async { handoff.awaitPredecessor() }
        runCurrent()
        assertFalse(draining.isCompleted)
        cleanup.complete(Unit)
        draining.await(); handoff.close(); openings.close()
        assertEquals(listOf(episode), source.episodes)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun failedPageReleasesAllSiblingSubscriptionsAndPlan() = runTest {
        val source = Source().apply { failPage = true }
        val failures = mutableListOf<Throwable>()
        val coordinator = WorkCoordinator(this)
        val openings = EngineOpeningPreparations(this, StandardTestDispatcher(testScheduler), coordinator, { source }, failures::add)
        openings.warm(episode); runCurrent(); openings.close()
        assertEquals(1, failures.size)
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    private inner class Source : EngineSessionWork {
        val episodes = mutableListOf<EpisodeId>()
        val pageCalls = mutableMapOf<PageId, Int>()
        var livePages = 0
        var failPage = false
        var beforeEpisode: suspend () -> Unit = {}
        override fun position(episodeId: EpisodeId) = request(episodeId.toString(), "position",
            SessionPosition::class.java, WorkDomain.STORAGE, WorkPriority.FOCUS) {
            SessionPosition(null, ReadingPosition(PageId.at(episodeId, 2), 0))
        }
        override fun episode(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(), "episode",
            EpisodeAccessPlan::class.java, WorkDomain.CONTROL, priority) {
            episodes += episodeId
            beforeEpisode()
            val pages = (0 until 10).map { PageSpec(PageId.at(episodeId, it), it) }
            EpisodeAccessPlan(EpisodeManifest(episodeId, "Title", pages), "revision", "0".repeat(64),
                URI("https://test.example/read"), 0,
                pages.map { PageAccessPlan(it.id, it.ordinal.toString(), listOf(URI("https://test.example/page.png"))) })
        }
        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(), "navigation",
            AdjacentEpisodes::class.java, WorkDomain.NETWORK, priority) { AdjacentEpisodes(null, null) }
        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = WorkRequest(
            WorkKey("test", pageId.toString(), "page", "revision", StoredPage::class.java), WorkDomain.BODY,
            priority, execute = {
                pageCalls[pageId] = (pageCalls[pageId] ?: 0) + 1
                if (failPage && pageId == PageId.at(pageId.episodeId, 3)) error("page unavailable")
                livePages++
                StoredPage(pageId, "revision", File("immutable.png"), 1, "1".repeat(64), PageDimensions(100, 100), "image/png")
            }, dispose = { livePages-- })
        private fun <T : Any> request(resource: String, operation: String, type: Class<T>, domain: WorkDomain,
            priority: WorkPriority, execute: suspend () -> T) = WorkRequest(
            WorkKey("test", resource, operation, "revision", type), domain, priority, execute = { execute() })
    }
}
