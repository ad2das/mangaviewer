package ml.melun.mangaview.engine.runtime

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.content.EngineTileWork
import ml.melun.mangaview.engine.work.WorkCoordinator
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineRenderRuntimeTest {
    private val id = PageId.at(EpisodeId(SeriesId(SourceId("test"), "1"), "1"), 0)
    private val dimensions = PageDimensions(100, 1000)

    @Test fun disabledStartupMetadataDoesNotQueueBlankBuffersBeforeTheFirstImage() = runTest {
        val fixture = Fixture(this, waitForComplete = true)
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        fixture.runtime.enabled(false)
        val initial = snapshot()
        fixture.runtime.update(initial)
        fixture.runtime.update(initial.copy(session = initial.session.copy(geometryRevision = 2)))
        runCurrent()
        assertTrue("A disabled renderer with no prior scene has nothing to clear", fixture.scenes.isEmpty())
        fixture.runtime.enabled(true)
        runCurrent()
        assertTrue("Attachment must not substitute a blank for the pending first image", fixture.scenes.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.scenes.size)
        assertTrue(fixture.scenes.single().completeCoverage)
        assertEquals(initial.session.anchor, fixture.scenes.single().session.anchor)
        fixture.runtime.enabled(false)
        runCurrent()
        assertEquals(2, fixture.scenes.size)
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertTrue(fixture.uploader.live.isEmpty())
        fixture.close()
    }

    @Test fun disablingBeforeFirstCompleteCoverageStillClearsSubmittedPartialPixels() = runTest {
        val fixture = Fixture(this, textureBudget = 240_000, tileHeight = 102)
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { if (it.sourceTop > 250) gate.await() }
        fixture.runtime.update(snapshot())
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isNotEmpty())
        assertFalse(fixture.scenes.last().completeCoverage)
        fixture.runtime.enabled(false)
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertTrue(fixture.uploader.live.isEmpty())
        gate.complete(Unit)
        fixture.close()
    }

    @Test fun inputMovesReadyPixelsImmediatelyWhileTheRemainingDecodeIsBlocked() = runTest {
        val fixture = Fixture(this, textureBudget = 240_000, tileHeight = 102)
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { if (it.sourceTop > 250) gate.await() }
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val before = fixture.scenes.last()
        assertFalse(before.completeCoverage)
        val ready = before.quads.single()
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val region = initial.session.visibleRegions.single()
        val moved = initial.copy(session = initial.session.copy(
            inputRevision = 2, geometryRevision = 2,
            anchor = SourceAnchor(id, 260 * q),
            visibleRegions = listOf(region.copy(sourceTopQ32 = 260 * q, sourceBottomQ32 = 360 * q))))
        fixture.runtime.update(moved)
        runCurrent()
        val after = fixture.scenes.last()
        assertEquals(2L, after.session.inputRevision)
        assertEquals(moved.session.anchor, after.session.anchor)
        assertFalse(after.completeCoverage)
        assertEquals(ready.texture.key, after.quads.single().texture.key)
        assertEquals(ready.topScreenUnits - 10 * 1024L, after.quads.single().topScreenUnits)
        assertFalse(gate.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(moved.session.anchor, fixture.scenes.last().session.anchor)
        assertTrue(fixture.failures.isEmpty())
        fixture.close()
    }

    @Test fun fullSceneReleasesFileAndCpuBorrowsThenEmptySceneAllowsGpuRetirement() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.update(snapshot())
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(1, fixture.uploader.live.size)
        assertEquals(0, fixture.files)
        assertEquals(1, fixture.pixelCloses)
        fixture.runtime.enabled(false)
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertEquals(0, fixture.uploader.live.size)
        fixture.close()
    }

    @Test fun generationReplacementCannotReuseAClosedTexture() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.update(snapshot())
        runCurrent()
        val oldKey = fixture.scenes.last().quads.single().texture.key
        fixture.runtime.update(snapshot().let { it.copy(session = it.session.copy(generation = 2)) })
        runCurrent()
        val current = fixture.scenes.last()
        assertEquals(2L, current.session.generation)
        assertTrue(current.completeCoverage)
        assertNotEquals(oldKey, current.quads.single().texture.key)
        assertFalse(oldKey in fixture.uploader.live)
        fixture.close()
    }

    @Test fun closeCallbackFailureStillClearsSceneAndReleasesGpuOwnership() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.update(snapshot())
        runCurrent()
        fixture.failScene = true
        repeat(2) {
            try { fixture.runtime.close(); fail("Expected callback failure") }
            catch (_: IllegalStateException) { }
        }
        assertTrue(fixture.uploader.live.isEmpty())
        assertEquals(0, fixture.coordinator.snapshot().subscribers)
        fixture.coordinator.close()
    }

    @Test fun speculativeDecodeFailureIsRetriedAndReportedOnlyWhenVisible() = runTest {
        val fixture = Fixture(this, 240_000)
        var attempts = 0
        fixture.beforeDecode = { tile ->
            if (tile.sourceTop == 400) { attempts++; error("decode failed") }
        }
        fixture.runtime.update(snapshot())
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(1, attempts)
        assertTrue(fixture.failures.isEmpty())
        repeat(3) { fixture.runtime.update(snapshot()); runCurrent() }
        assertEquals(1, attempts)
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val next = snapshot().let { it.copy(session = it.session.copy(
            anchor = SourceAnchor(id, 450 * q), inputRevision = 2,
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 450 * q, 550 * q, 0, 102400)))) }
        fixture.runtime.update(next)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, fixture.failures.size)
        assertFalse(fixture.scenes.last().completeCoverage)
        fixture.beforeDecode = {}
        fixture.runtime.retryFailures()
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun diagnosticSnapshotDistinguishesHeldVisibleDecodeFromResidentCoverage() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = {
            entered.complete(Unit)
            gate.await()
        }
        fixture.runtime.update(snapshot())
        runCurrent()
        entered.await()

        val held = fixture.runtime.diagnosticSnapshot()
        assertEquals(setOf(id), held.plannedVisibleTiles.map { it.pageId }.toSet())
        assertTrue(held.residentTextureTiles.isEmpty())
        assertTrue(held.completeGeometry)
        assertFalse(held.completeCoverage)
        assertEquals(1, held.work.active)
        assertEquals(0, held.work.ready)

        gate.complete(Unit)
        runCurrent()

        val released = fixture.runtime.diagnosticSnapshot()
        assertEquals(released.plannedVisibleTiles, released.residentTextureTiles)
        assertTrue(released.completeCoverage)
        assertEquals(1, released.work.ready)
        fixture.close()
    }

    @Test fun forwardAndReverseWithinPreparedDistanceReuseExactTexturesImmediately() = runTest {
        val fixture = Fixture(this, 400_000, tileHeight = 102, preparationViewports = 2)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val originalKeys = fixture.scenes.last().quads.map { it.texture.key }
        assertTrue(fixture.scenes.last().completeCoverage)
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val forward = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 450 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 450 * q, 550 * q, 0, 102400))))
        fixture.runtime.update(forward)
        assertTrue("Forward scene must be ready before any new asynchronous work", fixture.scenes.last().completeCoverage)
        assertEquals(2L, fixture.scenes.last().session.inputRevision)
        runCurrent()
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 3)))
        assertTrue("Reverse scene must reuse retained trailing tiles", fixture.scenes.last().completeCoverage)
        assertEquals(originalKeys, fixture.scenes.last().quads.map { it.texture.key })
        assertEquals(3L, fixture.scenes.last().session.inputRevision)
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun returningOutsideThePreparationHorizonReusesTexturesWhenBudgetHasRoom() = runTest {
        val fixture = Fixture(this, 400_000, tileHeight = 102, preparationViewports = 2)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val originalKeys = fixture.scenes.last().quads.map { it.texture.key }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 400_000)
        fixture.beforeDecode = { error("Returning to a retained texture must not decode again") }
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 3)))
        assertTrue("Spare budget must retain recently displayed original pixels", fixture.scenes.last().completeCoverage)
        assertEquals(originalKeys, fixture.scenes.last().quads.map { it.texture.key })
        runCurrent()
        assertTrue(fixture.failures.isEmpty())
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun currentPreparationEvictsOldResidencyWhenThereIsNoSpareBudget() = runTest {
        val fixture = Fixture(this, 160_000, tileHeight = 102, preparationViewports = 2)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val originalKeys = fixture.scenes.last().quads.map { it.texture.key }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertTrue(originalKeys.none { it in fixture.uploader.live })
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 160_000)
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 3)))
        assertFalse(fixture.scenes.last().completeCoverage)
        runCurrent()
        assertTrue(fixture.scenes.last().completeCoverage)
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 160_000)
        fixture.close()
    }

    @Test fun heldDecodeKeepsCompleteOldSceneAndItsOwnershipUntilCompleteReplacement() = runTest {
        val fixture = Fixture(this, 160_000, tileHeight = 102, preparationViewports = 2, waitForComplete = true)
        val initial = snapshot()
        val firstGate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { firstGate.await() }
        fixture.runtime.update(initial)
        runCurrent()
        assertTrue("Startup must not submit a partial scene", fixture.scenes.isEmpty())
        firstGate.complete(Unit)
        runCurrent()
        val displayed = fixture.scenes.last()
        val keys = displayed.quads.map { it.texture.key }
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertEquals(displayed, fixture.scenes.last())
        assertTrue(keys.all { it in fixture.uploader.live })
        assertFalse(fixture.runtime.diagnosticSnapshot().completeCoverage)
        assertTrue(fixture.runtime.diagnosticSnapshot().residentTextureTiles.containsAll(displayed.quads.map { it.texture.tile }))
        gate.complete(Unit)
        runCurrent()
        assertEquals(far.session.anchor, fixture.scenes.last().session.anchor)
        assertTrue(fixture.scenes.all { it.completeCoverage })
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 160_000)
        fixture.close()
        assertEquals(0, fixture.coordinator.snapshot().subscribers)
    }

    @Test fun disablingWhileWaitingClearsDisplayedReferencesAndCancelsPendingDecode() = runTest {
        val fixture = Fixture(this, 160_000, tileHeight = 102, waitForComplete = true)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        fixture.runtime.update(initial.copy(session = initial.session.copy(anchor = SourceAnchor(id, 850 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 850 * q, 950 * q, 0, 102400)))))
        runCurrent()
        fixture.runtime.enabled(false)
        runCurrent()
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertTrue(fixture.uploader.live.isEmpty())
        fixture.close()
        assertEquals(0, fixture.files)
    }

    @Test fun singleViewportBudgetReleasesNativeReferencesWithoutSubmittingABlankFrame() = runTest {
        val fixture = Fixture(this, 80_000, waitForComplete = true)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val displayed = fixture.scenes.last()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(anchor = SourceAnchor(id, 650 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 650 * q, 750 * q, 0, 102400))))
        fixture.runtime.update(far)
        runCurrent()
        assertEquals("Clearing texture references must not swap an empty buffer", displayed, fixture.scenes.last())
        assertTrue("Old native references must retire before the pending upload", fixture.uploader.live.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(far.session.anchor, fixture.scenes.last().session.anchor)
        assertTrue(fixture.scenes.all { it.completeCoverage })
        assertTrue(fixture.uploader.live.values.sumOf { it.byteCount } <= 80_000)
        fixture.close()
    }

    @Test fun offscreenGeometryDoesNotRedrawButNewInputStillSubmits() = runTest {
        val fixture = Fixture(this)
        val initial = snapshot()
        fixture.runtime.update(initial)
        runCurrent()
        val count = fixture.scenes.size
        val requests = fixture.pageRequestBuilds
        val updated = initial.copy(session = initial.session.copy(geometryRevision = 100))
        fixture.runtime.update(updated)
        runCurrent()
        assertEquals(count, fixture.scenes.size)
        fixture.runtime.update(updated.copy(session = updated.session.copy(inputRevision = 2)))
        runCurrent()
        assertEquals(count + 1, fixture.scenes.size)
        assertEquals(2L, fixture.scenes.last().session.inputRevision)
        assertEquals(100L, fixture.scenes.last().session.geometryRevision)
        assertEquals("Ready scrolling must not rebuild identical tile work", requests, fixture.pageRequestBuilds)
        fixture.close()
    }

    @Test fun renewedAccessPlanRebuildsWorkEvenWhenItsImageIdentityIsUnchanged() = runTest {
        val fixture = Fixture(this)
        val manifest = ml.melun.mangaview.core.EpisodeManifest(id.episodeId, "test",
            listOf(ml.melun.mangaview.core.PageSpec(id, 0)))
        fun access(epoch: Long) = EpisodeAccessPlan(manifest, "1", "0".repeat(64),
            java.net.URI("https://test.example/read"), epoch,
            listOf(PageAccessPlan(id, "0", listOf(java.net.URI("https://test.example/image")))))
        val initial = snapshot().copy(plans = mapOf(id.episodeId to access(0)))
        fixture.runtime.update(initial)
        runCurrent()
        val requests = fixture.pageRequestBuilds
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 2)))
        runCurrent()
        assertEquals(requests, fixture.pageRequestBuilds)
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 2),
            plans = mapOf(id.episodeId to access(1))))
        runCurrent()
        assertTrue(fixture.pageRequestBuilds > requests)
        assertTrue(fixture.scenes.last().completeCoverage)
        fixture.close()
    }

    @Test fun frameWorkObserverReportsRefreshKindsWithoutChangingSceneBehaviour() = runTest {
        val events = mutableListOf<Pair<Int, Long>>()
        val fixture = Fixture(this, frameWorkObserver = FrameWorkObserver { kind, atNanos -> events += kind to atNanos })
        fixture.runtime.update(snapshot())
        runCurrent()
        assertTrue(events.any { it.first == FrameWorkObserver.SNAPSHOT_UPDATE })

        fixture.runtime.retryFailures()
        assertTrue(events.any { it.first == FrameWorkObserver.WORK_RESULT })
        assertTrue(events.all { it.second > 0L })
        fixture.close()
    }

    @Test fun queuedFrameCoalescesUpdatesIntoOneNewestStateDrain() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler)
        val initial = snapshot()
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        fixture.runtime.update(initial)
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 350 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 350 * q, 450 * q, 0, 102400)))))
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 3,
            anchor = SourceAnchor(id, 450 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 450 * q, 550 * q, 0, 102400)))))
        assertEquals("Coalesced updates must request a single frame", 1, scheduler.posts)
        assertTrue("No scene may be submitted before the frame", fixture.scenes.isEmpty())
        scheduler.deliver(fixture.runtime)
        assertEquals(1, fixture.scenes.size)
        assertEquals(3L, fixture.scenes.single().session.inputRevision)
        assertEquals(SourceAnchor(id, 450 * q), fixture.scenes.single().session.anchor)
        fixture.close()
    }

    @Test fun decodeCompletionJoinsThePendingFrameInsteadOfRequestingAnother() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler)
        fixture.runtime.update(snapshot())
        assertEquals(1, scheduler.posts)
        assertTrue(fixture.scenes.isEmpty())
        scheduler.deliver(fixture.runtime)
        assertEquals(1, fixture.scenes.size)
        assertFalse(fixture.scenes.single().completeCoverage)
        runCurrent()
        assertEquals("Upload completion must request exactly one next frame", 2, scheduler.posts)
        assertEquals("No scene may be submitted without a delivery", 1, fixture.scenes.size)
        scheduler.deliver(fixture.runtime)
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(2, scheduler.posts)
        fixture.close()
    }

    @Test fun supersededSnapshotUsesThePendingFrameWithoutStaleCoordinates() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler)
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val first = snapshot()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        fixture.runtime.update(first)
        scheduler.deliver(fixture.runtime)
        assertEquals(SourceAnchor(id, 250 * q), fixture.scenes.last().session.anchor)
        val second = first.copy(session = first.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 650 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 650 * q, 750 * q, 0, 102400))))
        fixture.runtime.update(second)
        assertEquals("A superseding snapshot must reuse the already requested frame", 2, scheduler.posts)
        val submitted = fixture.scenes.size
        gate.complete(Unit)
        runCurrent()
        assertEquals("A decode completion must not submit outside a delivery", submitted, fixture.scenes.size)
        assertTrue("No scene may carry the superseded coordinates",
            fixture.scenes.none { it.session.inputRevision > 1L })
        scheduler.deliver(fixture.runtime)
        val drained = fixture.scenes.last()
        assertEquals(second.session, drained.session)
        assertTrue("The superseded tile must not be placed at the new coordinates", drained.quads.isEmpty())
        runCurrent()
        scheduler.deliver(fixture.runtime)
        assertEquals(second.session, fixture.scenes.last().session)
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(600, fixture.scenes.last().quads.single().texture.tile.sourceTop)
        fixture.close()
    }

    @Test fun workArrivingInsideAFramePassDefersToExactlyOneFollowupFrame() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler)
        val initial = snapshot()
        var reentered = false
        var scenesBeforeReentrantUpdate = 0
        var scenesAfterReentrantUpdate = 0
        fixture.onViewportReady = { ready ->
            if (!reentered) {
                reentered = true
                scenesBeforeReentrantUpdate = fixture.scenes.size
                fixture.runtime.update(initial.copy(session = ready.copy(inputRevision = ready.inputRevision + 1)))
                scenesAfterReentrantUpdate = fixture.scenes.size
            }
        }
        fixture.runtime.update(initial)
        assertEquals(1, scheduler.posts)
        scheduler.deliver(fixture.runtime)
        runCurrent()
        scheduler.deliver(fixture.runtime)
        assertTrue(reentered)
        assertEquals("The reentrant request must not run a nested drain",
            scenesBeforeReentrantUpdate, scenesAfterReentrantUpdate)
        assertEquals("Reentrancy must not submit a second scene in the same pass", 2, fixture.scenes.size)
        assertEquals("Exactly one followup frame must be requested", 3, scheduler.posts)
        assertEquals(1L, fixture.scenes.last().session.inputRevision)
        scheduler.deliver(fixture.runtime)
        assertEquals(2L, fixture.scenes.last().session.inputRevision)
        assertEquals(3, scheduler.posts)
        fixture.close()
    }

    @Test fun scheduledFramesStillRecordEveryRequestForProvenance() = runTest {
        val events = mutableListOf<Int>()
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler,
            frameWorkObserver = FrameWorkObserver { kind, atNanos -> events += kind; assertTrue(atNanos > 0L) })
        val initial = snapshot()
        fixture.runtime.update(initial)
        fixture.runtime.update(initial.copy(session = initial.session.copy(inputRevision = 2)))
        scheduler.deliver(fixture.runtime)
        runCurrent()
        assertEquals("Every input must be recorded even when frames coalesce",
            2, events.count { it == FrameWorkObserver.SNAPSHOT_UPDATE })
        assertTrue(events.any { it == FrameWorkObserver.WORK_RESULT })
        assertEquals(2, scheduler.posts)
        fixture.runtime.enabled(false)
        assertEquals("Disable records its own request and cancels the queued frame",
            3, events.count { it == FrameWorkObserver.SNAPSHOT_UPDATE })
        assertFalse(scheduler.pending)
        fixture.runtime.retryFailures()
        assertTrue(events.count { it == FrameWorkObserver.WORK_RESULT } >= 2)
        fixture.close()
    }

    @Test fun disablingCancelsTheQueuedFrameAndRetiresPixelsInline() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler)
        fixture.runtime.update(snapshot())
        scheduler.deliver(fixture.runtime)
        runCurrent()
        assertEquals(2, scheduler.posts)
        scheduler.deliver(fixture.runtime)
        assertTrue(fixture.scenes.last().completeCoverage)
        assertEquals(1, fixture.uploader.live.size)
        fixture.runtime.update(snapshot().copy(session = snapshot().session.copy(inputRevision = 2)))
        assertEquals(3, scheduler.posts)
        assertTrue(scheduler.pending)
        fixture.runtime.enabled(false)
        assertEquals("Disable must cancel the queued frame", 1, scheduler.cancels)
        assertFalse(scheduler.pending)
        assertTrue("Disable must retire pixels before detach", fixture.scenes.last().quads.isEmpty())
        val submitted = fixture.scenes.size
        runCurrent()
        assertTrue("Retirement must complete once in-flight work settles", fixture.uploader.live.isEmpty())
        scheduler.deliver(fixture.runtime)
        assertEquals("A canceled delivery must not run", submitted, fixture.scenes.size)
        fixture.close()
    }

    @Test fun generationChangeRetiresTheOldSceneBeforeTheQueuedFrameRuns() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler)
        fixture.runtime.update(snapshot())
        scheduler.deliver(fixture.runtime)
        runCurrent()
        scheduler.deliver(fixture.runtime)
        assertTrue(fixture.scenes.last().completeCoverage)
        val scenes = fixture.scenes.size
        fixture.runtime.update(snapshot().copy(session = snapshot().session.copy(generation = 2)))
        assertEquals("Generation invalidation must retire pixels synchronously", scenes + 1, fixture.scenes.size)
        assertTrue(fixture.scenes.last().quads.isEmpty())
        assertEquals("Current invalidation must apply immediately", 2L, fixture.scenes.last().session.generation)
        assertTrue(scheduler.pending)
        runCurrent()
        assertTrue("Old-generation resources must retire once work settles", fixture.uploader.live.isEmpty())
        scheduler.deliver(fixture.runtime)
        runCurrent()
        scheduler.deliver(fixture.runtime)
        assertEquals(2L, fixture.scenes.last().session.generation)
        assertTrue(fixture.scenes.last().completeCoverage)
        fixture.close()
    }

    @Test fun closeCancelsTheQueuedFrameAndIgnoresLateDelivery() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, refreshScheduler = scheduler)
        fixture.runtime.update(snapshot())
        assertTrue(scheduler.pending)
        fixture.close()
        assertEquals(1, scheduler.cancels)
        assertFalse(scheduler.pending)
        val submitted = fixture.scenes.size
        scheduler.deliver(fixture.runtime)
        assertEquals(submitted, fixture.scenes.size)
    }

    @Test fun failedClearLatchBlocksFurtherFramesUntilRetry() = runTest {
        val scheduler = ManualRefreshScheduler()
        val fixture = Fixture(this, 80_000, waitForComplete = true, refreshScheduler = scheduler)
        val initial = snapshot()
        fixture.runtime.update(initial)
        scheduler.deliver(fixture.runtime)
        runCurrent()
        scheduler.deliver(fixture.runtime)
        val displayed = fixture.scenes.last()
        assertTrue(displayed.completeCoverage)
        assertEquals(2, scheduler.posts)
        val gate = CompletableDeferred<Unit>()
        fixture.beforeDecode = { gate.await() }
        fixture.failClear = true
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val far = initial.copy(session = initial.session.copy(inputRevision = 2,
            anchor = SourceAnchor(id, 650 * q),
            visibleRegions = listOf(VisiblePageRegion(id, dimensions, 650 * q, 750 * q, 0, 102400))))
        fixture.runtime.update(far)
        assertEquals(3, scheduler.posts)
        scheduler.deliver(fixture.runtime)
        assertEquals("The failed clear must be reported once", 1, fixture.failures.size)
        assertEquals("A failed clear must not re-arm a frame", 3, scheduler.posts)
        assertEquals(displayed, fixture.scenes.last())
        fixture.runtime.update(far.copy(session = far.session.copy(inputRevision = 3)))
        assertEquals("A latched clear failure must not request frames", 3, scheduler.posts)
        assertEquals(displayed, fixture.scenes.last())
        fixture.failClear = false
        fixture.runtime.retryFailures()
        assertEquals(4, scheduler.posts)
        scheduler.deliver(fixture.runtime)
        assertEquals("Inline clear completion must request exactly the next frame", 5, scheduler.posts)
        assertEquals(displayed, fixture.scenes.last())
        scheduler.deliver(fixture.runtime)
        assertEquals(5, scheduler.posts)
        runCurrent()
        assertTrue("Clear-released references must retire once work settles", fixture.uploader.live.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(6, scheduler.posts)
        scheduler.deliver(fixture.runtime)
        assertEquals(far.session.anchor, fixture.scenes.last().session.anchor)
        assertTrue(fixture.scenes.last().completeCoverage)
        fixture.close()
    }

    private inner class Fixture(scope: TestScope, textureBudget: Long = 80_000,
        tileHeight: Int = 202, preparationViewports: Int = 0, waitForComplete: Boolean = false,
        frameWorkObserver: FrameWorkObserver? = null, refreshScheduler: ManualRefreshScheduler? = null,
    ) {
        val coordinator = WorkCoordinator(scope)
        val uploader = Uploader()
        val scenes = mutableListOf<EngineDrawScene>()
        var files = 0
        var pixelCloses = 0
        var pageRequestBuilds = 0
        var failScene = false
        var failClear = false
        var beforeDecode: suspend (EngineTileSpec) -> Unit = {}
        var onViewportReady: (EngineSessionSnapshot) -> Unit = {}
        val failures = mutableListOf<Throwable>()
        private val tileWork = EngineTileWork(EngineImageDecoder { _, tile ->
            beforeDecode(tile)
            object : EnginePixels {
                override val tile = tile
                override val byteCount = tile.byteCount
                override fun close() { pixelCloses++ }
            }
        }, StandardTestDispatcher(scope.testScheduler), uploader)
        val runtime = EngineRenderRuntime(scope, coordinator,
            EngineTilePlanner(textureBudget, tileHeight, preparationViewports), tileWork, uploader,
            { _, priority ->
                pageRequestBuilds++
                WorkRequest(WorkKey("test", "page", "read", "1", StoredPage::class.java), WorkDomain.STORAGE, priority,
                    execute = { files++; StoredPage(id, "1", File("original.png"), 1, "1".repeat(64), dimensions, "image/png") },
                    dispose = { files-- })
            }, { scene ->
                if (failScene) error("frame callback failed")
                scenes += scene
                uploader.scene(scene.quads.map { it.texture.key }.toSet())
            }, { if (failClear) error("clear failed") else uploader.scene(emptySet()) },
            { _, failure -> failures += failure }, waitForComplete,
            reportSceneFailure = { failures += it },
            reportViewportReady = { snapshot -> onViewportReady(snapshot) },
            frameWorkObserver = frameWorkObserver, refreshScheduler = refreshScheduler)

        suspend fun close() { runtime.close(); coordinator.close(); assertTrue(uploader.live.isEmpty()) }
    }

    private class Uploader : EngineTextureUploader {
        override val rendererId = 1L
        override val rendererEpoch = 1L
        val live = linkedMapOf<Long, EngineTexture>()
        private val retiring = linkedMapOf<Long, CompletableDeferred<Unit>>()
        private var references = emptySet<Long>()
        private var key = 0L
        override suspend fun upload(pixels: EnginePixels, expectedEpoch: Long) =
            EngineTexture(pixels.tile, rendererId, expectedEpoch, ++key, pixels.byteCount).also { live[it.key] = it }
        override suspend fun release(texture: EngineTexture) {
            val completion = CompletableDeferred<Unit>()
            retiring[texture.key] = completion
            collect()
            completion.await()
        }
        fun scene(keys: Set<Long>) { references = keys; collect() }
        private fun collect() {
            retiring.keys.filter { it !in references }.forEach {
                live.remove(it)
                retiring.remove(it)!!.complete(Unit)
            }
        }
    }

    private fun snapshot(): EngineRuntimeSnapshot {
        val q = SourceAnchor.SOURCE_UNITS_PER_PIXEL
        val state = EngineSessionSnapshot(1, 1, EngineSessionPhase.ACTIVE, EngineViewport(100, 100),
            SourceAnchor(id, 250 * q), 1, 1, 0,
            listOf(VisiblePageRegion(id, dimensions, 250 * q, 350 * q, 0, 102400)), emptySet(), emptySet(), true)
        return EngineRuntimeSnapshot(state, emptyMap(), mapOf(id to PageContentIdentity(id, "1", "1".repeat(64), dimensions, 1)))
    }
}

/** Mirrors the platform frame port: one pending delivery, explicit cancel, manual delivery. */
private class ManualRefreshScheduler : EngineRefreshScheduler {
    var posts = 0
        private set
    var cancels = 0
        private set
    var pending = false
        private set

    override fun post() {
        posts++
        pending = true
    }

    override fun cancel() {
        cancels++
        pending = false
    }

    fun deliver(runtime: EngineRenderRuntime) {
        pending = false
        runtime.refreshOnFrame()
    }
}
