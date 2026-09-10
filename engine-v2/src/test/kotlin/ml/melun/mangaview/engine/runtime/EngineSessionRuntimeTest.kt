package ml.melun.mangaview.engine.runtime

import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.ReadingPosition
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
import ml.melun.mangaview.engine.api.WorkLimits
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

    @Test fun reacquiringIdenticalPreparedPagesDoesNotRepeatContentUpdates() = runTest {
        var executions = 0
        val source = Source().apply { pageCount = 8; earlyGeometry = true; beforePage = { executions++ } }
        val coordinator = WorkCoordinator(this)
        val reducer = EngineSession(1, episode, EngineViewport(100, 100)) { 0L }
        val updates = mutableListOf<EngineRuntimeSnapshot>()
        val receipts = mutableListOf<InputReceipt>()
        val runtime = EngineSessionRuntime(this, coordinator, reducer, source, episode,
            { snapshot, values -> updates += snapshot; receipts += values }, { _, failure -> throw failure })
        try {
            runtime.open()
            runCurrent()
            assertEquals(8, runtime.snapshot.pages.size)
            val originalExecutions = executions
            val preparedAt = runtime.diagnosticSnapshot().launchPreparation.allFirstVerifiedPreparedAtNanos
            for ((index, delta) in listOf(700L, -700L, 700L, -700L).withIndex()) {
                runtime.input(InputSample(index + 1L, 1, 0, delta * 1024))
                val afterInput = updates.size
                val snapshot = runtime.snapshot
                runCurrent()
                assertEquals("Identical ready results must not repeat rendering", afterInput, updates.size)
                assertEquals(snapshot, runtime.snapshot)
            }
            assertTrue("The test must exercise newly acquired original subscriptions", executions > originalExecutions)
            assertEquals(preparedAt, runtime.diagnosticSnapshot().launchPreparation.allFirstVerifiedPreparedAtNanos)
            assertEquals((1L..4L).toList(), receipts.filter { it.outcome == InputOutcome.APPLIED }.map { it.sample.sequence })
            assertEquals(SourceAnchor(PageId.at(episode, 0), 0), runtime.snapshot.session.anchor)
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
    }

    @Test fun headerGeometryResolvesOrderedMovementBeforeOriginalBodiesAreReady() = runTest {
        val tail = CompletableDeferred<Unit>()
        val source = Source().apply { earlyGeometry = true; beforePage = { tail.await() } }
        val receipts = mutableListOf<InputReceipt>()
        val failures = mutableListOf<Throwable>()
        val (runtime, coordinator) = runtime(source, receipts, failures)
        try {
            runtime.open()
            runCurrent()
            assertNotNull(runtime.snapshot.session.anchor)
            assertTrue(runtime.snapshot.pages.isEmpty())
            assertNull(runtime.diagnosticSnapshot().launchPreparation.allFirstVerifiedPreparedAtNanos)
            runtime.input(InputSample(1, 1, 0, 150 * 1024L))
            runCurrent()
            assertEquals(0, runtime.snapshot.session.pendingInputCount)
            assertEquals(PageId.at(episode, 1), runtime.snapshot.session.anchor!!.pageId)
            assertEquals(50 * SourceAnchor.SOURCE_UNITS_PER_PIXEL, runtime.snapshot.session.anchor!!.sourceYQ32)
            assertEquals(InputOutcome.APPLIED, receipts.last().outcome)
            assertEquals(150 * 1024L, receipts.last().appliedScreenUnits)
            assertTrue(runtime.snapshot.pages.isEmpty())
            tail.complete(Unit)
            runCurrent()
            assertEquals(3, runtime.snapshot.pages.size)
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
    }

    @Test fun fastGeometryOnlyMovementFinishesStartedOriginalsWithoutCancellingAndRestartingTheirBodies() = runTest {
        val tail = CompletableDeferred<Unit>()
        val attempts = mutableMapOf<PageId, Int>()
        var cancelledBodies = 0
        val source = Source().apply {
            earlyGeometry = true
            pageCount = 14
            beforePage = { id ->
                attempts[id] = attempts.getOrDefault(id, 0) + 1
                try { tail.await() } finally { if (!tail.isCompleted) cancelledBodies++ }
            }
        }
        val coordinator = WorkCoordinator(this, WorkLimits(network = 16, bodies = 14, backgroundNetwork = 12))
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { 0L }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode, { _, _ -> },
            { _, error -> throw error })
        try {
            runtime.open()
            runCurrent()
            // The reserved document-end original now starts immediately, so every page of the
            // short manifest is already attempted while all bodies remain gated.
            assertEquals(14, attempts.size)
            runtime.input(InputSample(1, 1, 0, 1250 * 1024L))
            runCurrent()
            assertEquals(PageId.at(episode, 12), runtime.snapshot.session.anchor!!.pageId)
            assertEquals(14, attempts.size)
            runtime.input(InputSample(2, 1, 0, -1250 * 1024L))
            runCurrent()
            assertEquals(PageId.at(episode, 0), runtime.snapshot.session.anchor!!.pageId)
            assertEquals(0, runtime.snapshot.session.pendingInputCount)
            assertEquals(0, cancelledBodies)
            assertTrue(runtime.snapshot.pages.isEmpty())
            tail.complete(Unit)
            runCurrent()
            assertEquals(14, runtime.diagnosticSnapshot().launchPreparation.verifiedPages.size)
            assertNotNull(runtime.diagnosticSnapshot().launchPreparation.allFirstVerifiedPreparedAtNanos)
            assertTrue(attempts.values.all { it == 1 })
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
    }

    @Test fun adjacentDocumentStartsWhileLegacyAnchorWaitsForOriginalDimensions() = runTest {
        val next = episode.copy(remoteKey = "2")
        val saved = ReadingPosition(PageId.at(episode, 1), 17 * 1024L)
        val source = Source().apply { nextEpisode = next; legacyPosition = saved }
        val original = CompletableDeferred<Unit>()
        source.beforePage = { if (it == saved.pageId) original.await() }
        val (runtime, coordinator) = runtime(source)
        try {
            runtime.open()
            runCurrent()
            assertNull(runtime.snapshot.session.anchor)
            assertTrue(next in source.requestedEpisodes)
            assertTrue(next in runtime.snapshot.plans)
            assertEquals(WorkPriority.FOCUS, source.startedPriorities[saved.pageId])
            assertTrue(PageId.at(episode, 2) in source.startedPriorities)
            original.complete(Unit)
            runCurrent()
            val anchor = requireNotNull(runtime.snapshot.session.anchor)
            assertEquals(saved.pageId, anchor.pageId)
            assertEquals(17 * SourceAnchor.SOURCE_UNITS_PER_PIXEL, anchor.sourceYQ32)
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    @Test fun inputReplayYieldsToOtherOwnerWorkAndResumesWithoutAnotherInputEvent() = runTest {
        val source = Source()
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { testScheduler.currentTime * 1_000_000L }
        session.engageStartupInputBarrier()
        val receipts = mutableListOf<InputReceipt>()
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, values -> receipts += values }, { _, failure -> throw failure })
        runtime.open()
        runCurrent()
        (1..100).forEach { index -> runtime.input(InputSample(index.toLong(), 1, 0,
            (if (index % 2 == 0) -37L else 500L) * 1024)) }
        runtime.releaseStartupInput()
        assertTrue(runtime.snapshot.session.pendingInputCount > 0)
        var pendingWhenOtherWorkRan = 0
        launch { pendingWhenOtherWorkRan = runtime.snapshot.session.pendingInputCount }
        runCurrent()
        assertTrue(pendingWhenOtherWorkRan > 0)
        advanceUntilIdle()
        assertEquals(0, runtime.snapshot.session.pendingInputCount)
        assertEquals((1L..100L).toList(), receipts.filter { it.outcome != InputOutcome.DEFERRED }.map { it.sample.sequence })

        runtime.close()
        coordinator.close()
        assertEquals(0, source.livePages)
    }

    @Test fun closeCancelsThePostedInputContinuationAndReportsEveryAcceptedInput() = runTest {
        val source = Source()
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { testScheduler.currentTime * 1_000_000L }
        session.engageStartupInputBarrier()
        val receipts = mutableListOf<InputReceipt>()
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, values -> receipts += values }, { _, failure -> throw failure })
        runtime.open()
        runCurrent()
        (1..100).forEach { runtime.input(InputSample(it.toLong(), 1, 0, 1024)) }
        runtime.releaseStartupInput()
        assertTrue(runtime.snapshot.session.pendingInputCount > 0)
        runtime.close()
        val countAtClose = receipts.size
        advanceUntilIdle()
        assertEquals(countAtClose, receipts.size)
        assertEquals((1L..100L).toList(), receipts.filter { it.outcome != InputOutcome.DEFERRED }.map { it.sample.sequence })
        assertTrue(receipts.any { it.outcome == InputOutcome.CANCELLED })
        coordinator.close()
        assertEquals(0, source.livePages)
    }

    @Test fun publishedMetadataSurvivesInputBackgroundAndCloseWithoutChangingOldSnapshots() = runTest {
        val source = Source()
        val (runtime, coordinator) = runtime(source)
        runtime.open()
        runCurrent()
        val before = runtime.snapshot
        runtime.input(InputSample(1, 1, 0, 10 * 1_024L))
        runCurrent()
        val moved = runtime.snapshot
        assertNotEquals(before.session.anchor, moved.session.anchor)
        assertSame(before.plans, moved.plans)
        assertSame(before.pages, moved.pages)
        assertThrows(UnsupportedOperationException::class.java) { (before.pages as MutableMap).clear() }
        runtime.foreground(false)
        runCurrent()
        assertTrue(runtime.snapshot.pages.isEmpty())
        assertEquals(3, before.pages.size)
        runtime.foreground(true)
        runCurrent()
        assertEquals(before.pages, runtime.snapshot.pages)
        runtime.close()
        assertTrue(runtime.snapshot.plans.isEmpty())
        assertTrue(runtime.snapshot.pages.isEmpty())
        assertEquals(1, before.plans.size)
        assertEquals(3, before.pages.size)
        assertEquals(0, source.livePages)
        coordinator.close()
    }

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
        source.beforePage = { id ->
            if (id == PageId.at(episode, 0)) {
                attempts++
                if (attempts == 1) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
                }
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
        source.beforePage = { id ->
            if (id == PageId.at(episode, 0)) { attempts++; if (attempts == 1) error("offline") }
        }
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

    @Test fun forwardPagesFinishBeforeEarlierPagesAndNextEpisodeStartsBeforeBoundary() = runTest {
        val source = Source().apply {
            pageCount = 6
            initialAnchor = SourceAnchor(PageId.at(episode, 2), 0)
            nextEpisode = episode.copy(remoteKey = "2")
        }
        val requested = mutableListOf<PageId>()
        val gate = CompletableDeferred<Unit>()
        source.beforePage = { id ->
            requested += id
            if (id == PageId.at(episode, 4)) gate.await()
        }
        val failures = mutableListOf<Throwable>()
        val (runtime, coordinator) = runtime(source, failures = failures)
        runtime.open()
        runCurrent()
        assertEquals(listOf(2, 3, 4, 5).map { PageId.at(episode, it) },
            requested.filter { it.episodeId == episode }.distinct())
        assertTrue(source.requestedEpisodes.contains(source.nextEpisode))
        assertEquals((0 until source.pageCount).map { PageId.at(source.nextEpisode!!, it) },
            requested.filter { it.episodeId == source.nextEpisode }.distinct())
        assertFalse(gate.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(2, 3, 4, 5, 1, 0).map { PageId.at(episode, it) },
            requested.filter { it.episodeId == episode }.distinct())
        assertTrue(source.requestedEpisodes.contains(source.nextEpisode))
        assertEquals(PageId.at(episode, 2), runtime.snapshot.session.anchor!!.pageId)
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals((0 until source.pageCount).map { PageId.at(source.nextEpisode!!, it) },
            requested.filter { it.episodeId == source.nextEpisode }.distinct())
        runtime.close()
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun openingOriginalsFillSpareNextBodyPermitsBeforeRenderingDespiteASlowCurrentTail() = runTest {
        val next = episode.copy(remoteKey = "2")
        val source = Source().apply { pageCount = 20; nextEpisode = next }
        val currentGate = CompletableDeferred<Unit>()
        val openingGate = CompletableDeferred<Unit>()
        val nextGates = (0 until source.pageCount).associate { PageId.at(next, it) to CompletableDeferred<Unit>() }
        var activeNext = 0
        var peakNext = 0
        source.beforePage = { id ->
            if (id == PageId.at(episode, 0)) openingGate.await()
            if (id == PageId.at(episode, 19)) currentGate.await()
            nextGates[id]?.let { gate ->
                activeNext++
                peakNext = maxOf(peakNext, activeNext)
                try { gate.await() } finally { activeNext-- }
            }
        }
        val failures = mutableListOf<Throwable>()
        val coordinator = WorkCoordinator(this, WorkLimits(network = 16, bodies = 14, backgroundNetwork = 12))
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { 0L }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, _ -> }, { _, error -> failures += error }, awaitInitialPresentation = true)
        try {
            runtime.open()
            runCurrent()
            assertEquals(2, activeNext)
            assertEquals(listOf(0, 1).map { PageId.at(next, it) },
                source.startedPriorities.keys.filter { it.episodeId == next })
            openingGate.complete(Unit)
            runCurrent()
            assertEquals(11, activeNext)
            assertTrue(PageId.at(episode, 19) in source.startedPriorities)
            assertFalse(currentGate.isCompleted)
            currentGate.complete(Unit)
            runCurrent()
            assertEquals(12, activeNext)
            assertEquals((0 until 12).map { PageId.at(next, it) },
                source.startedPriorities.keys.filter { it.episodeId == next })
            nextGates.getValue(PageId.at(next, 1)).complete(Unit)
            runCurrent()
            assertEquals(12, activeNext)
            assertTrue(PageId.at(next, 12) in source.startedPriorities)
            assertFalse(PageId.at(next, 13) in source.startedPriorities)
            assertEquals(12, peakNext)
            assertEquals(PageId.at(episode, 0), runtime.snapshot.session.anchor!!.pageId)
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            runtime.close()
            assertEquals(0, activeNext)
            assertEquals(0, source.livePages)
            assertEquals(0, coordinator.snapshot().subscribers)
            coordinator.close()
        }
    }

    @Test fun completedCurrentEpisodeFillsBoundedNextEpisodeWindowWithoutWaitingForItsFirstBodies() = runTest {
        val next = episode.copy(remoteKey = "2")
        val source = Source().apply { pageCount = 20; nextEpisode = next }
        val currentGate = CompletableDeferred<Unit>()
        val nextGates = (0 until source.pageCount).associate { PageId.at(next, it) to CompletableDeferred<Unit>() }
        var activeNext = 0
        var peakNext = 0
        source.beforePage = { id ->
            if (id == PageId.at(episode, 19)) currentGate.await()
            nextGates[id]?.let { gate ->
                activeNext++
                peakNext = maxOf(peakNext, activeNext)
                try { gate.await() } finally { activeNext-- }
            }
        }
        val failures = mutableListOf<Throwable>()
        val coordinator = WorkCoordinator(this, WorkLimits(network = 16, bodies = 14, backgroundNetwork = 12))
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { 0L }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, _ -> }, { _, error -> failures += error }, awaitInitialPresentation = true)
        try {
            runtime.open()
            runCurrent()
            assertEquals(11, activeNext)
            assertTrue(PageId.at(episode, 19) in source.startedPriorities)
            assertEquals((0 until 11).map { PageId.at(next, it) },
                source.startedPriorities.keys.filter { it.episodeId == next })
            currentGate.complete(Unit)
            runCurrent()
            assertEquals(12, activeNext)
            assertEquals((0 until 12).map { PageId.at(next, it) },
                source.startedPriorities.keys.filter { it.episodeId == next })
            nextGates.getValue(PageId.at(next, 1)).complete(Unit)
            runCurrent()
            assertEquals(12, activeNext)
            assertTrue(PageId.at(next, 12) in source.startedPriorities)
            assertFalse(PageId.at(next, 13) in source.startedPriorities)
            assertEquals(12, peakNext)
            assertEquals(PageId.at(episode, 0), runtime.snapshot.session.anchor!!.pageId)
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            runtime.close()
            assertEquals(0, activeNext)
            assertEquals(0, source.livePages)
            assertEquals(0, coordinator.snapshot().subscribers)
            coordinator.close()
        }
    }

    @Test fun preparedCurrentEpisodeAuthorizesOneFurtherDocumentWhileNextBodiesArePending() = runTest {
        val next = episode.copy(remoteKey = "2")
        val further = episode.copy(remoteKey = "3")
        val source = Source().apply { nextEpisode = next; followingEpisode = further }
        val currentGate = CompletableDeferred<Unit>()
        val nextGate = CompletableDeferred<Unit>()
        var activeNext = 0
        source.beforePage = { id ->
            if (id.episodeId == episode) currentGate.await()
            if (id.episodeId == next) {
                activeNext++
                try { nextGate.await() } finally { activeNext-- }
            }
        }
        val failures = mutableListOf<Throwable>()
        val (runtime, coordinator) = runtime(source, failures = failures)
        try {
            runtime.open()
            runCurrent()
            assertTrue(next in source.requestedEpisodes)
            assertFalse(further in source.requestedEpisodes)
            currentGate.complete(Unit)
            runCurrent()
            assertTrue(activeNext > 0)
            assertTrue(further in source.requestedEpisodes)
            assertEquals(3, source.requestedEpisodes.size)
            assertTrue(source.startedPriorities.keys.none { it.episodeId == further })
            assertEquals(PageId.at(episode, 0), runtime.snapshot.session.anchor!!.pageId)
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            runtime.close()
            assertEquals(0, activeNext)
            assertEquals(0, source.livePages)
            assertEquals(0, coordinator.snapshot().subscribers)
            coordinator.close()
        }
    }

    @Test fun twoOriginalWindowAdvancesPastOneSlowBodyAndClosesBothActiveBodies() = runTest {
        val source = Source().apply { pageCount = 6 }
        val gates = (1 until source.pageCount).associate { PageId.at(episode, it) to CompletableDeferred<Unit>() }
        val requested = mutableListOf<PageId>()
        var active = 0
        var peak = 0
        source.beforePage = { id ->
            requested += id
            gates[id]?.let { gate ->
                active++
                peak = maxOf(peak, active)
                try { gate.await() } finally { active-- }
            }
        }
        val (runtime, coordinator) = runtime(source)
        runtime.open()
        runCurrent()
        assertEquals(listOf(0, 1, 2).map { PageId.at(episode, it) }, requested)
        assertEquals(2, active)
        gates.getValue(PageId.at(episode, 2)).complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3).map { PageId.at(episode, it) }, requested)
        assertEquals(2, active)
        assertEquals(2, peak)
        assertFalse(gates.getValue(PageId.at(episode, 1)).isCompleted)
        runtime.close()
        assertEquals(0, active)
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
        assertEquals(0, coordinator.snapshot().active)
        coordinator.close()
    }

    @Test fun originalPipelineOverlapsFocusAndSurvivesMissingCurrentTextures() = runTest {
        val source = Source().apply { pageCount = 6 }
        val requested = mutableListOf<PageId>()
        val first = CompletableDeferred<Unit>()
        val nearby = CompletableDeferred<Unit>()
        val distant = CompletableDeferred<Unit>()
        source.beforePage = { id ->
            requested += id
            when (id) {
                PageId.at(episode, 0) -> first.await()
                PageId.at(episode, 1), PageId.at(episode, 2) -> nearby.await()
                else -> distant.await()
            }
        }
        val (runtime, coordinator) = runtime(source)
        runtime.open()
        runCurrent()
        assertEquals(listOf(0, 1, 2).map { PageId.at(episode, it) }, requested)
        assertEquals(WorkPriority.FOCUS, source.startedPriorities[PageId.at(episode, 0)])
        assertEquals(WorkPriority.NEXT_IMAGE, source.startedPriorities[PageId.at(episode, 1)])
        assertEquals(WorkPriority.NEXT_IMAGE, source.startedPriorities[PageId.at(episode, 2)])
        nearby.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3, 4).map { PageId.at(episode, it) }, requested)
        assertFalse(first.isCompleted)
        assertFalse(runtime.snapshot.session.completeViewport)
        first.complete(Unit)
        runCurrent()
        assertEquals(5, requested.size)
        assertEquals(listOf(0, 1, 2, 3, 4).map { PageId.at(episode, it) }, requested)
        distant.complete(Unit)
        runCurrent()
        assertEquals((0 until source.pageCount).map { PageId.at(episode, it) }, requested)
        assertEquals(source.pageCount, runtime.diagnosticSnapshot().launchPreparation.verifiedPages.size)
        runtime.close()
        assertEquals(0, source.livePages)
        coordinator.close()
    }

    @Test fun nextDocumentOverlapsOriginalBodiesWithoutWaitingForTextures() = runTest {
        val next = episode.copy(remoteKey = "2")
        val source = Source().apply { pageCount = 6; nextEpisode = next }
        val bodyGate = CompletableDeferred<Unit>()
        val documentGate = CompletableDeferred<Unit>()
        var documentCancelled = false
        source.beforePage = { if (it.episodeId == episode) bodyGate.await() }
        source.beforeEpisode = { if (it == next) {
            try { documentGate.await() } finally { documentCancelled = !documentGate.isCompleted }
        } }
        val (runtime, coordinator) = runtime(source)
        try {
            runtime.open()
            runCurrent()
            assertEquals(3, source.startedPriorities.size)
            assertTrue(runtime.diagnosticSnapshot().launchPreparation.verifiedPages.isEmpty())
            assertTrue(next in source.requestedEpisodes)
            assertFalse(bodyGate.isCompleted)
            runtime.input(InputSample(1, 1, 0, 150 * 1_024L))
            runCurrent()
            assertFalse(documentCancelled)
            documentGate.complete(Unit)
            runCurrent()
            assertTrue(next in runtime.snapshot.plans)
            assertTrue(source.startedPriorities.keys.none { it.episodeId == next })
            bodyGate.complete(Unit)
            runCurrent()
            assertTrue(source.startedPriorities.keys.any { it.episodeId == next })
            assertEquals(1, source.requestedEpisodes.count { it == next })
        } finally {
            runtime.close()
            coordinator.close()
        }
        assertEquals(0, source.livePages)
    }

    @Test fun twoPageOriginalHorizonCrossesTheKnownEpisodeBoundaryBeforeLastBodyCompletes() = runTest {
        val next = episode.copy(remoteKey = "2")
        val source = Source().apply {
            pageCount = 6
            nextEpisode = next
            initialAnchor = SourceAnchor(PageId.at(episode, 4), 0)
        }
        val lastBody = CompletableDeferred<Unit>()
        val nextBody = CompletableDeferred<Unit>()
        source.beforePage = { id ->
            if (id == PageId.at(episode, 5)) lastBody.await()
            if (id.episodeId == next) nextBody.await()
        }
        val (runtime, coordinator) = runtime(source)
        try {
            runtime.open()
            runCurrent()
            assertFalse(lastBody.isCompleted)
            assertTrue(PageId.at(next, 0) in source.startedPriorities)
            assertFalse(PageId.at(next, 1) in source.startedPriorities)
            assertEquals(WorkPriority.NEXT_IMAGE, source.startedPriorities[PageId.at(next, 0)])
            assertEquals(PageId.at(episode, 4), runtime.snapshot.session.anchor!!.pageId)
            assertEquals(0, runtime.snapshot.session.pendingInputCount)
        } finally {
            runtime.close()
            coordinator.close()
        }
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    @Test fun missingVisibleDimensionsDoNotStopNearbyBodiesOrDuplicatePromotedOriginal() = runTest {
        val source = Source().apply { pageCount = 6 }
        val gates = (1 until source.pageCount).associate { PageId.at(episode, it) to CompletableDeferred<Unit>() }
        val requested = mutableListOf<PageId>()
        source.beforePage = { id -> requested += id; gates[id]?.await() }
        val receipts = mutableListOf<InputReceipt>()
        val (runtime, coordinator) = runtime(source, receipts)
        try {
            runtime.open()
            runCurrent()
            assertEquals(listOf(0, 1, 2).map { PageId.at(episode, it) }, requested)
            val sample = InputSample(1, 1, 0, 150 * 1_024L)
            runtime.input(sample)
            runCurrent()
            assertTrue(runtime.snapshot.session.completeViewport)
            assertTrue(PageId.at(episode, 1) in runtime.snapshot.session.requiredDimensions)
            assertEquals(1, runtime.snapshot.session.pendingInputCount)
            // The promoted body retains its admitted permit until completion. Free its peer's
            // background permit; another nearby body must start while the visible body is blocked.
            gates.getValue(PageId.at(episode, 2)).complete(Unit)
            runCurrent()
            assertEquals(listOf(0, 1, 2, 3).map { PageId.at(episode, it) }, requested)
            assertEquals(1, requested.count { it == PageId.at(episode, 1) })
            gates.getValue(PageId.at(episode, 1)).complete(Unit)
            runCurrent()
            val resolved = receipts.last { it.sample.sequence == sample.sequence }
            assertEquals(InputOutcome.APPLIED, resolved.outcome)
            assertEquals(sample.deltaScreenUnits, resolved.appliedScreenUnits)
            assertEquals(0, runtime.snapshot.session.pendingInputCount)
        } finally {
            runtime.close()
            coordinator.close()
        }
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun failedReadAheadDoesNotInterruptReadingOrSpinAndVisibleDemandRetries() = runTest {
        val source = Source()
        val failedPage = PageId.at(episode, 1)
        var attempts = 0
        source.beforePage = { id ->
            if (id == failedPage) { attempts++; error("unavailable original") }
        }
        val failures = mutableListOf<Throwable>()
        val (runtime, coordinator) = runtime(source, failures = failures)
        runtime.open()
        runCurrent()
        assertTrue(runtime.snapshot.session.completeViewport)
        assertTrue(failures.isEmpty())
        assertEquals(1, attempts)
        repeat(3) { runtime.resize(EngineViewport(100, 100)); runCurrent() }
        assertEquals(1, attempts)
        runtime.input(InputSample(1, 1, 0, 150 * 1_024L))
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, failures.size)
        source.beforePage = {}
        runtime.retryFailures()
        runCurrent()
        assertTrue(runtime.snapshot.session.completeViewport)
        assertEquals(failedPage, runtime.snapshot.session.anchor!!.pageId)
        runtime.close()
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun promotedReadAheadFailureIsReportedForTheNowVisiblePage() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforePage = { id ->
            if (id == PageId.at(episode, 1)) { gate.await(); error("visible failure") }
        }
        val failures = mutableListOf<Throwable>()
        val (runtime, coordinator) = runtime(source, failures = failures)
        runtime.open()
        runCurrent()
        runtime.input(InputSample(1, 1, 0, 150 * 1_024L))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, failures.size)
        runtime.close()
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun nextManifestFailureWaitsForExplicitRetryWithoutInterruptingCurrentEpisode() = runTest {
        val source = Source().apply { nextEpisode = episode.copy(remoteKey = "2") }
        var attempts = 0
        source.beforeEpisode = { id ->
            if (id == source.nextEpisode) { attempts++; error("next episode offline") }
        }
        val failures = mutableListOf<Throwable>()
        val (runtime, coordinator) = runtime(source, failures = failures)
        runtime.open()
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(failures.isEmpty())
        assertTrue(runtime.snapshot.session.completeViewport)
        repeat(3) { runtime.resize(EngineViewport(100, 100)); runCurrent() }
        assertEquals(1, attempts)
        source.beforeEpisode = {}
        runtime.retryFailures()
        runCurrent()
        assertTrue(runtime.snapshot.plans.containsKey(source.nextEpisode))
        runtime.close()
        assertEquals(0, coordinator.snapshot().subscribers)
        coordinator.close()
    }

    @Test fun closeCancelsInputHeldForTheStartupFrameWithoutApplyingIt() = runTest {
        val source = Source()
        val receipts = mutableListOf<InputReceipt>()
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(2, episode, EngineViewport(100, 100),
            { testScheduler.currentTime * 1_000_000L }).apply { engageStartupInputBarrier() }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, values -> receipts += values }, { _, failure -> throw failure })
        runtime.open()
        val sample = InputSample(1, 1, 0, 150 * 1_024L)
        runtime.input(sample)
        runCurrent()
        assertEquals(InputOutcome.DEFERRED, receipts.last { it.sample == sample }.outcome)

        runtime.close()

        val cancelled = receipts.last { it.sample == sample }
        assertEquals(InputOutcome.CANCELLED, cancelled.outcome)
        assertEquals(0L, cancelled.appliedScreenUnits)
        coordinator.close()
    }

    @Test fun launchPreparationRetainsFirstVerifiedEvidenceAcrossDelayPruningAndNavigation() = runTest {
        val source = Source()
        val delayed = CompletableDeferred<Unit>()
        source.beforePage = { if (it == PageId.at(episode, 1)) delayed.await() }
        var observedAt = 0L
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { testScheduler.currentTime * 1_000_000L }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, _ -> }, { _, failure -> throw failure }, observationClock = { observedAt += 10; observedAt })

        runtime.open()
        runCurrent()
        var preparation = runtime.diagnosticSnapshot().launchPreparation
        assertEquals(10L, preparation.manifestAcceptedAtNanos)
        assertEquals((0 until 3).map { PageId.at(episode, it) }, preparation.manifestPageIds)
        assertEquals(setOf(PageId.at(episode, 0), PageId.at(episode, 2)), preparation.verifiedPages.keys)
        assertNull(preparation.allFirstVerifiedPreparedAtNanos)

        delayed.complete(Unit)
        runCurrent()
        preparation = runtime.diagnosticSnapshot().launchPreparation
        assertEquals(listOf(20L, 30L, 40L), preparation.verifiedPages.values.map { it.firstVerifiedAtNanos })
        assertEquals(40L, preparation.allFirstVerifiedPreparedAtNanos)

        runtime.foreground(false)
        assertTrue(runtime.snapshot.pages.isEmpty())
        val retained = runtime.diagnosticSnapshot().launchPreparation
        assertEquals(preparation, retained)

        runtime.foreground(true)
        runtime.navigate(episode.copy(remoteKey = "2"))
        runCurrent()
        assertEquals(retained, runtime.diagnosticSnapshot().launchPreparation)

        runtime.close()
        coordinator.close()
    }

    @Test fun crossingTwoDocumentsPreservesVerifiedReversePixelsUntilNavigationClearsThem() = runTest {
        val next = episode.copy(remoteKey = "2")
        val further = episode.copy(remoteKey = "3")
        val source = Source().apply { nextEpisode = next; followingEpisode = further }
        val (runtime, coordinator) = runtime(source)
        try {
            runtime.open()
            runCurrent()
            val original = runtime.snapshot.pages.filterKeys { it.episodeId == episode }
            assertEquals(3, original.size)
            runtime.input(InputSample(1, 1, 0, 650 * 1_024L))
            runCurrent()
            assertEquals(further, runtime.snapshot.session.anchor!!.pageId.episodeId)
            assertEquals(original, runtime.snapshot.pages.filterKeys { it.episodeId == episode })
            runtime.input(InputSample(2, 1, 0, -650 * 1_024L))
            runCurrent()
            assertEquals(PageId.at(episode, 0), runtime.snapshot.session.anchor!!.pageId)
            assertEquals(0L, runtime.snapshot.session.anchor!!.sourceYQ32)
            runtime.navigate(further)
            runCurrent()
            assertTrue(runtime.snapshot.pages.keys.none { it.episodeId == episode })
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
        assertTrue(runtime.snapshot.pages.isEmpty())
    }

    @Test fun verifiedMetadataFeedsTheTileHorizonWithoutPinningEveryOriginal() = runTest {
        val source = Source().apply { pageCount = 9 }
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 250)) { testScheduler.currentTime * 1_000_000L }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, _ -> }, { _, failure -> throw failure })
        try {
            runtime.open()
            runCurrent()
            assertEquals(9, runtime.snapshot.pages.size)
            assertEquals(3, source.livePages)
            val plan = EngineTilePlanner(1_000_000, targetTileHeightPx = 102, preparationViewports = 2).plan(runtime.snapshot)
            assertTrue(plan.completeGeometry)
            assertEquals((0..2).map { PageId.at(episode, it) }.toSet(), plan.placements.map { it.tile.pageId }.toSet())
            assertTrue(PageId.at(episode, 6) in plan.demands.map { it.tile.pageId })
            assertTrue(plan.plannedTextureBytes <= 1_000_000)
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
        assertTrue(runtime.snapshot.pages.isEmpty())
    }

    @Test fun originalsPrepareBeforeFirstSubmissionWithVisiblePriorityPreserved() = runTest {
        val source = Source().apply { pageCount = 20 }
        val first = CompletableDeferred<Unit>()
        source.beforePage = { if (it == PageId.at(episode, 0)) first.await() }
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { testScheduler.currentTime * 1_000_000L }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, _ -> }, { _, failure -> throw failure }, awaitInitialPresentation = true)
        try {
            runtime.open()
            runCurrent()
            assertEquals((0..2).map { PageId.at(episode, it) }.toSet(), source.startedPriorities.keys)
            assertFalse(runtime.snapshot.session.completeViewport)
            first.complete(Unit)
            runCurrent()
            assertEquals((0 until 20).map { PageId.at(episode, it) }.toSet(), source.startedPriorities.keys)
            assertEquals(WorkPriority.VISIBLE, source.startedPriorities[PageId.at(episode, 1)])
            assertEquals(WorkPriority.VISIBLE, source.startedPriorities[PageId.at(episode, 2)])
            runtime.initialViewportSubmitted(session.snapshot.generation - 1)
            runCurrent()
            assertEquals(20, source.startedPriorities.size)
            runtime.initialViewportSubmitted(session.snapshot.generation)
            runCurrent()
            assertEquals(20, source.startedPriorities.size)
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    @Test fun bulkBodiesWaitForEveryOpeningOriginalButNeverForRendering() = runTest {
        val source = Source().apply { pageCount = 20 }
        val second = CompletableDeferred<Unit>()
        source.beforePage = { if (it == PageId.at(episode, 1)) second.await() }
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 150)) { testScheduler.currentTime * 1_000_000L }
        val runtime = EngineSessionRuntime(this, coordinator, session, source, episode,
            { _, _ -> }, { _, failure -> throw failure }, awaitInitialPresentation = true)
        try {
            runtime.open()
            runCurrent()
            assertFalse(runtime.snapshot.session.completeViewport)
            assertEquals((0..3).map { PageId.at(episode, it) }.toSet(), source.startedPriorities.keys)
            second.complete(Unit)
            runCurrent()
            assertTrue(runtime.snapshot.session.completeViewport)
            assertEquals((0 until 20).map { PageId.at(episode, it) }.toSet(), source.startedPriorities.keys)
            assertEquals(WorkPriority.VISIBLE, source.startedPriorities[PageId.at(episode, 1)])
            assertEquals(0L, session.snapshot.inputRevision)
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    @Test fun forwardReadAheadStartsTheFinalOriginalInTheFirstBulkWave() = runTest {
        val source = Source().apply { pageCount = 60 }
        val gate = CompletableDeferred<Unit>()
        source.beforePage = { if (it == PageId.at(episode, 30)) gate.await() }
        val (runtime, coordinator) = runtime(source)
        try {
            runtime.open()
            runCurrent()
            val order = source.startedPriorities.keys.filter { it.episodeId == episode }
            assertTrue("final original was not in the early read-ahead wave: $order",
                order.indexOf(PageId.at(episode, 59)) in 1 until 15)
        } finally { gate.complete(Unit); runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    @Test fun steadySubpixelScrollingReusesPageRequestsAndNavigationRefreshesThem() = runTest {
        val source = Source()
        val receipts = mutableListOf<InputReceipt>()
        val (runtime, coordinator) = runtime(source, receipts)
        try {
            runtime.open()
            runCurrent()
            runtime.input(InputSample(1, 1, 0, 1024))
            runCurrent()
            val constructed = source.constructedPageRequests
            for (sequence in 2L..101L) runtime.input(InputSample(sequence, 1, 0, 1))
            runCurrent()
            assertEquals(constructed, source.constructedPageRequests)
            assertEquals(0, runtime.snapshot.session.pendingInputCount)
            assertEquals(101, receipts.count { it.outcome == InputOutcome.APPLIED })
            assertEquals((1024L + 100L) * SourceAnchor.SOURCE_UNITS_PER_PIXEL / 1024,
                runtime.snapshot.session.anchor?.sourceYQ32)
            runtime.navigate(episode)
            runCurrent()
            assertTrue(source.constructedPageRequests > constructed)
            assertTrue(runtime.snapshot.session.completeViewport)
            assertEquals(0L, runtime.snapshot.session.anchor?.sourceYQ32)
        } finally { runtime.close(); coordinator.close() }
        assertEquals(0, source.livePages)
        assertEquals(0, coordinator.snapshot().subscribers)
    }

    private fun TestScope.runtime(source: Source, receipts: MutableList<InputReceipt> = mutableListOf(),
        failures: MutableList<Throwable> = mutableListOf()): Pair<EngineSessionRuntime, WorkCoordinator> {
        val coordinator = WorkCoordinator(this)
        val session = EngineSession(1, episode, EngineViewport(100, 100)) { testScheduler.currentTime * 1_000_000L }
        return EngineSessionRuntime(this, coordinator, session, source, episode,
            { _: EngineRuntimeSnapshot, values -> receipts += values }, { _, failure -> failures += failure }) to coordinator
    }

    private inner class Source : EngineSessionWork {
        var earlyGeometry = false
        var beforePage: suspend (PageId) -> Unit = {}
        var beforeEpisode: suspend (EpisodeId) -> Unit = {}
        var livePages = 0
        var constructedPageRequests = 0
        var pageCount = 3
        var initialAnchor: SourceAnchor? = null
        var legacyPosition: ReadingPosition? = null
        var nextEpisode: EpisodeId? = null
        var followingEpisode: EpisodeId? = null
        val requestedEpisodes = mutableListOf<EpisodeId>()
        val startedPriorities = mutableMapOf<PageId, WorkPriority>()

        fun plan(id: EpisodeId): EpisodeAccessPlan {
            val pages = (0 until pageCount).map { PageSpec(PageId.at(id, it), it) }
            val manifest = EpisodeManifest(id, id.remoteKey, pages,
                previousEpisodeId = when (id) { nextEpisode -> episode; followingEpisode -> nextEpisode; else -> null },
                nextEpisodeId = when (id) { episode -> nextEpisode; nextEpisode -> followingEpisode; else -> null })
            return EpisodeAccessPlan(manifest, "revision", "0".repeat(64), URI("https://test.example/read"), 0,
                pages.map { PageAccessPlan(it.id, it.ordinal.toString(), listOf(URI("https://test.example/page.png"))) })
        }

        override fun position(episodeId: EpisodeId) = request(episodeId.toString(), "position",
            SessionPosition::class.java, WorkDomain.STORAGE, WorkPriority.FOCUS) { SessionPosition(initialAnchor, legacyPosition) }

        override fun episode(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "episode", EpisodeAccessPlan::class.java, WorkDomain.CONTROL, priority) { requestedEpisodes += episodeId; beforeEpisode(episodeId); plan(episodeId) }

        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = request(episodeId.toString(),
            "navigation", AdjacentEpisodes::class.java, WorkDomain.NETWORK, priority) { AdjacentEpisodes(null, null) }

        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority): WorkRequest<StoredPage> {
            constructedPageRequests++
            return WorkRequest(
            WorkKey("test", pageId.toString(), "page", "revision", StoredPage::class.java), WorkDomain.BODY,
            priority, execute = { context ->
                startedPriorities[pageId] = context.priority.value
                if (earlyGeometry) context.publishMetadata(ml.melun.mangaview.engine.api.WorkMetadata.PageGeometry(
                    pageId, plan.contentRevision, PageDimensions(100, 100)))
                beforePage(pageId)
                livePages++
                StoredPage(pageId, plan.contentRevision, File("immutable-${pageId.remoteKey}.png"), 1,
                    "1".repeat(64), PageDimensions(100, 100), "image/png")
            }, dispose = { livePages-- },
        )
        }

        private fun <T : Any> request(resource: String, operation: String, type: Class<T>, domain: WorkDomain,
            priority: WorkPriority, execute: suspend () -> T) = WorkRequest(WorkKey("test", resource, operation,
            "1", type), domain, priority, execute = { execute() })
    }
}
