package ml.melun.mangaview.engine.runtime

import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.InputReceipt
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.PageAccessPlan
import ml.melun.mangaview.engine.api.SessionPosition
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.session.EngineSession
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.AdjacentEpisodes
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineSessionRuntimeTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "1")

    @Test fun coldInputIsConservedAcrossDelayedPageDimensionsAndReleasedOnClose() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforePage = { gate.await() }
        val receipts = mutableListOf<InputReceipt>()
        val (runtime, coordinator) = runtime(source, receipts)
        runtime.open()
        runCurrent()
        val input = InputSample(1, 1, 0, 150 * 1_024L)
        runtime.input(input)
        assertEquals(InputOutcome.DEFERRED, receipts.last().outcome)
        assertEquals(1, runtime.snapshot.session.pendingInputCount)
        gate.complete(Unit)
        runCurrent()
        val receipt = receipts.last { it.sample.sequence == 1L }
        assertEquals(InputOutcome.APPLIED, receipt.outcome)
        assertEquals(input.deltaScreenUnits, receipt.appliedScreenUnits)
        assertEquals(0, runtime.snapshot.session.pendingInputCount)
        assertEquals(PageId.at(episode, 1), runtime.snapshot.session.anchor!!.pageId)
        assertEquals(50L * SourceAnchor.SOURCE_UNITS_PER_PIXEL, runtime.snapshot.session.anchor!!.sourceYQ32)
        runtime.close()
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun closingRuntimeDoesNotCancelAnotherConsumerOfTheSameOriginal() = runTest {
        val source = Source()
        val (runtime, coordinator) = runtime(source)
        val plan = source.plan(episode)
        val other = coordinator.acquire(source.page(plan, plan.pages.first().pageId, WorkPriority.OFFLINE))
        runtime.open()
        runCurrent()
        runtime.close()
        assertEquals(1, source.livePages)
        assertEquals(1, coordinator.snapshot().subscribers)
        other.awaitReleased()
        assertEquals(0, source.livePages)
        coordinator.close()
    }

    @Test fun lateOldGenerationCannotPopulateTheNewEpisode() = runTest {
        val source = Source()
        val oldStarted = CompletableDeferred<Unit>()
        val oldCleanup = CompletableDeferred<Unit>()
        source.beforePage = { id ->
            if (id.episodeId == episode) {
                oldStarted.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { oldCleanup.await() } }
            }
        }
        val (runtime, coordinator) = runtime(source)
        runtime.open()
        oldStarted.await()
        val next = episode.copy(remoteKey = "2")
        runtime.navigate(next)
        runCurrent()
        assertEquals(next, runtime.snapshot.session.anchor!!.pageId.episodeId)
        assertTrue(runtime.ownership().retiring > 0)
        oldCleanup.complete(Unit)
        runCurrent()
        assertTrue(runtime.snapshot.pages.keys.all { it.episodeId == next })
        runtime.close()
        assertEquals(0, source.livePages)
        coordinator.close()
    }

    @Test fun foregroundReturnWaitsForThePreviousSameKeyCleanup() = runTest {
        val source = Source()
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        var attempts = 0
        source.beforePage = {
            attempts++
            if (attempts == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            }
        }
        val (runtime, coordinator) = runtime(source)
        runtime.open()
        entered.await()
        runtime.foreground(false)
        runtime.foreground(true)
        runCurrent()
        assertEquals(1, attempts)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(runtime.snapshot.session.completeViewport)
        assertTrue(attempts >= 2)
        runtime.close()
        coordinator.close()
    }

    @Test fun closeWaitsForActualExecutorCleanupAndEveryCallerSeesCompletion() = runTest {
        val source = Source()
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        source.beforePage = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        val (runtime, coordinator) = runtime(source)
        runtime.open()
        entered.await()
        val first = async { runtime.close() }
        val second = async { runtime.close() }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        cleanup.complete(Unit)
        first.await()
        second.await()
        assertEquals(0, runtime.ownership().active)
        assertEquals(0, runtime.ownership().retiring)
        coordinator.close()
    }

    @Test fun aFailedPageDoesNotSpinAndExplicitRetryCanRecoverIt() = runTest {
        val source = Source()
        var attempts = 0
        source.beforePage = { attempts++; if (attempts == 1) error("offline") }
        val failures = mutableListOf<Throwable>()
        val (runtime, coordinator) = runtime(source, failures = failures)
        runtime.open()
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, failures.size)
        repeat(3) { runtime.resize(EngineViewport(100, 100)); runCurrent() }
        assertEquals(1, attempts)
        runtime.retryFailures()
        runCurrent()
        assertTrue(runtime.snapshot.session.completeViewport)
        runtime.close()
        coordinator.close()
    }

    @Test fun retryFromTheFailureCallbackWaitsForItsFailedSubscriptionToFinish() = runTest {
        val coordinator = WorkCoordinator(this)
        val source = Source()
        var attempts = 0
        source.beforePage = { attempts++; if (attempts == 1) error("retry immediately") }
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { 0L }
        lateinit var runtime: EngineSessionRuntime
        runtime = EngineSessionRuntime(this, coordinator, session, source, episode, { _, _ -> }, { _, _ ->
            runtime.retryFailures()
        })
        runtime.open()
        runCurrent()
        assertTrue(runtime.snapshot.session.completeViewport)
        assertEquals(0, runtime.ownership().failed)
        runtime.close()
        coordinator.close()
    }

    private fun TestScope.runtime(source: Source, receipts: MutableList<InputReceipt> = mutableListOf(),
        failures: MutableList<Throwable> = mutableListOf()): Pair<EngineSessionRuntime, WorkCoordinator> {
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { testScheduler.currentTime * 1_000_000L }
        return EngineSessionRuntime(this, coordinator, session, source, episode,
            { _: EngineRuntimeSnapshot, values -> receipts += values }, { _, failure -> failures += failure }) to coordinator
    }

    private inner class Source : EngineSessionWork {
        var beforePage: suspend (PageId) -> Unit = {}
        var livePages = 0

        fun plan(id: EpisodeId): EpisodeAccessPlan {
            val pages = (0..2).map { PageSpec(PageId.at(id, it), it) }
            val manifest = EpisodeManifest(id, id.remoteKey, pages)
            return EpisodeAccessPlan(manifest, "revision", "0".repeat(64), URI("https://test.example/read"), 0,
                pages.map { PageAccessPlan(it.id, it.ordinal.toString(), listOf(URI("https://test.example/page.png"))) })
        }

        override fun position(episodeId: EpisodeId) = request(episodeId.toString(), "position",
            SessionPosition::class.java, WorkDomain.STORAGE, WorkPriority.FOCUS) { SessionPosition(null) }

        override fun episode(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "episode", EpisodeAccessPlan::class.java, WorkDomain.CONTROL, priority) { plan(episodeId) }

        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "navigation", AdjacentEpisodes::class.java, WorkDomain.NETWORK, priority) { AdjacentEpisodes(null, null) }

        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = WorkRequest(
            WorkKey("test", pageId.toString(), "page", "revision", StoredPage::class.java), WorkDomain.BODY,
            priority, execute = {
                beforePage(pageId)
                livePages++
                StoredPage(pageId, plan.contentRevision, File("immutable-${pageId.remoteKey}.png"), 1,
                    "1".repeat(64), PageDimensions(100, 100), "image/png")
            }, dispose = { livePages-- },
        )

        private fun <T : Any> request(resource: String, operation: String, type: Class<T>, domain: WorkDomain,
            priority: WorkPriority, execute: suspend () -> T) = WorkRequest(WorkKey("test", resource, operation,
            "1", type), domain, priority, execute = { execute() })
    }
}
