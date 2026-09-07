package ml.melun.mangaview.engine.session

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EngineSessionPhase
import ml.melun.mangaview.engine.api.EngineViewport
import ml.melun.mangaview.engine.api.DocumentBoundary
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.InputSample
import ml.melun.mangaview.engine.api.SessionEvent
import ml.melun.mangaview.engine.api.SourceAnchor
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSessionTest {
    private val episode = EpisodeId(SeriesId(SourceId("test"), "series"), "episode")

    @Test
    fun sourceDimensionsRemainAvailableForPositionPersistenceAfterResizeAndClose() {
        val dimensions = PageDimensions(100, 200)
        val anchor = SourceAnchor(PageId.at(episode, 0), 50L * SourceAnchor.SOURCE_UNITS_PER_PIXEL)
        val session = EngineSession(70, episode, EngineViewport(100, 100)) { 100L }
        session.dispatch(SessionEvent.PositionResolved(1, anchor))
        session.dispatch(SessionEvent.ManifestResolved(1, manifest(dimensions)))
        session.dispatch(SessionEvent.Resize(EngineViewport(200, 100)))
        val closed = session.dispatch(SessionEvent.Close).snapshot
        assertEquals(anchor, closed.anchor)
        assertEquals(dimensions, closed.anchorDimensions)
        assertTrue(closed.visibleRegions.isEmpty())
    }

    @Test
    fun eachInputGetsAnImmediateReceiptWhenQueuedBehindEarlierDeferredInput() {
        val now = 100L
        val session = EngineSession(1L, episode, EngineViewport(100, 100), { now })
        val first = session.dispatch(SessionEvent.Input(sample(1L, 10L)))
        val second = session.dispatch(SessionEvent.Input(sample(2L, -10L)))

        assertEquals(InputOutcome.DEFERRED, first.receipts.single().outcome)
        assertEquals(1, second.receipts.count { it.sample.sequence == 2L })
        assertEquals(InputOutcome.DEFERRED, second.receipts.single { it.sample.sequence == 2L }.outcome)
        assertEquals(2, second.snapshot.pendingInputCount)
    }

    @Test
    fun zeroDeltaInputInOpeningIsAppliedWithoutQueueing() {
        val session = EngineSession(18L, episode, EngineViewport(100, 100), { 100L })

        val update = session.dispatch(SessionEvent.Input(sample(1L, 0L)))

        assertEquals(InputOutcome.APPLIED, update.receipts.single().outcome)
        assertEquals(0L, update.receipts.single().appliedScreenUnits)
        assertEquals(0, update.snapshot.pendingInputCount)
    }

    @Test
    fun closePreventsLateCurrentGenerationGeometryFromReopeningOrAddingWork() {
        val session = EngineSession(2L, episode, EngineViewport(100, 100), { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, null))
        val manifest = manifest(PageDimensions(100, 200))
        session.dispatch(SessionEvent.ManifestResolved(1L, manifest))
        val closed = session.dispatch(SessionEvent.Close).snapshot

        val lateManifest = manifest(PageDimensions(200, 400))
        val late = session.dispatch(SessionEvent.ManifestResolved(1L, lateManifest)).snapshot
        val lateDimensions = session.dispatch(
            SessionEvent.DimensionsResolved(1L, PageId.at(episode, 0), PageDimensions(300, 600)),
        ).snapshot

        assertEquals(EngineSessionPhase.CLOSED, late.phase)
        assertEquals(closed.geometryRevision, late.geometryRevision)
        assertEquals(closed.requiredEpisodes, late.requiredEpisodes)
        assertEquals(closed.visibleRegions, late.visibleRegions)
        assertEquals(closed.geometryRevision, lateDimensions.geometryRevision)
        assertTrue(lateDimensions.requiredDimensions.isEmpty())
    }

    @Test
    fun enteredEpisodeIsTheStartBoundaryEvenWhenItNamesAnEarlierEpisode() {
        val session = EngineSession(3L, episode, EngineViewport(100, 100), { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, null))
        val previous = EpisodeId(episode.seriesId, "previous")
        val entered = EpisodeManifest(
            id = episode,
            title = "Entered",
            previousEpisodeId = previous,
            pages = listOf(PageSpec(PageId.at(episode, 0), 0, PageDimensions(100, 200))),
        )
        session.dispatch(SessionEvent.ManifestResolved(1L, entered))

        val update = session.dispatch(SessionEvent.Input(sample(1L, -1L)))

        assertEquals(InputOutcome.CLAMPED, update.receipts.single().outcome)
        assertEquals(ml.melun.mangaview.engine.api.DocumentBoundary.START,
            update.receipts.single().boundary?.boundary)
        assertTrue(update.snapshot.requiredEpisodes.isEmpty())
    }

    @Test
    fun deepAnchorDoesNotRequireDimensionsForEarlierPages() {
        val page0 = PageId.at(episode, 0)
        val page1 = PageId.at(episode, 1)
        val anchor = SourceAnchor(page1, 17L * SourceAnchor.SOURCE_UNITS_PER_PIXEL + 123L, 2048L)
        val session = EngineSession(4L, episode, EngineViewport(100, 100), { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, anchor))
        session.dispatch(
            SessionEvent.ManifestResolved(
                1L,
                EpisodeManifest(
                    episode,
                    "Deep",
                    listOf(
                        PageSpec(page0, 0),
                        PageSpec(page1, 1, dimensions = PageDimensions(100, 200)),
                    ),
                ),
            ),
        )

        val snapshot = session.snapshot

        assertEquals(anchor, snapshot.anchor)
        assertTrue(page0 !in snapshot.requiredDimensions)
        assertTrue(snapshot.visibleRegions.any { it.pageId == page1 })
    }

    @Test
    fun pendingForwardAndReverseMatchFullyKnownReferenceAfterDimensionsArrive() {
        val page0 = PageId.at(episode, 0)
        val page1 = PageId.at(episode, 1)
        val full = readySession(
            sessionId = 5L,
            viewport = EngineViewport(100, 50),
            pages = listOf(
                PageSpec(page0, 0, dimensions = PageDimensions(100, 100)),
                PageSpec(page1, 1, dimensions = PageDimensions(100, 100)),
            ),
        )
        val missing = EngineSession(6L, episode, EngineViewport(100, 50), { 100L })
        missing.dispatch(SessionEvent.PositionResolved(1L, SourceAnchor(page0, 0L)))
        missing.dispatch(
            SessionEvent.ManifestResolved(
                1L,
                EpisodeManifest(
                    episode,
                    "Pending",
                    listOf(
                        PageSpec(page0, 0, dimensions = PageDimensions(100, 100)),
                        PageSpec(page1, 1),
                    ),
                ),
            ),
        )

        val firstFull = full.dispatch(SessionEvent.Input(sample(1L, 150_000L)))
        val secondFull = full.dispatch(SessionEvent.Input(sample(2L, -50_000L)))
        val firstMissing = missing.dispatch(SessionEvent.Input(sample(1L, 150_000L)))
        val secondMissing = missing.dispatch(SessionEvent.Input(sample(2L, -50_000L)))

        assertEquals(InputOutcome.APPLIED, firstFull.receipts.single().outcome)
        assertEquals(InputOutcome.APPLIED, secondFull.receipts.single().outcome)
        assertEquals(InputOutcome.DEFERRED, firstMissing.receipts.single().outcome)
        assertEquals(InputOutcome.DEFERRED, secondMissing.receipts.single().outcome)

        val resolution = missing.dispatch(
            SessionEvent.DimensionsResolved(1L, page1, PageDimensions(100, 100)),
        )

        assertEquals(0, resolution.snapshot.pendingInputCount)
        assertEquals(full.snapshot.anchor, resolution.snapshot.anchor)
        assertEquals(full.snapshot.visibleRegions, resolution.snapshot.visibleRegions)
    }

    @Test
    fun knownEndClampsButUnknownContinuationDefersRemainingDisplacement() {
        val known = readySession(
            sessionId = 7L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(PageId.at(episode, 0), 0, PageDimensions(100, 200))),
        )
        val nextEpisode = EpisodeId(episode.seriesId, "next")
        val unknown = EngineSession(8L, episode, EngineViewport(100, 100), { 100L })
        unknown.dispatch(SessionEvent.PositionResolved(1L, SourceAnchor(PageId.at(episode, 0), 0L)))
        unknown.dispatch(
            SessionEvent.ManifestResolved(
                1L,
                EpisodeManifest(
                    episode,
                    "Unknown continuation",
                    listOf(PageSpec(PageId.at(episode, 0), 0, PageDimensions(100, 200))),
                    nextEpisodeId = nextEpisode,
                ),
            ),
        )

        val knownUpdate = known.dispatch(SessionEvent.Input(sample(1L, 300_000L)))
        val unknownUpdate = unknown.dispatch(SessionEvent.Input(sample(1L, 300_000L)))

        assertEquals(InputOutcome.CLAMPED, knownUpdate.receipts.single().outcome)
        assertEquals(DocumentBoundary.END, knownUpdate.receipts.single().boundary?.boundary)
        assertEquals(InputOutcome.DEFERRED, unknownUpdate.receipts.single().outcome)
        assertTrue(nextEpisode in unknownUpdate.snapshot.requiredEpisodes)
    }

    @Test
    fun unknownNavigationUsesProvisionalEndWithoutBackwardCorrection() {
        val page = PageId.at(episode, 0)
        val session = unknownReadySession(
            sessionId = 18L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 300))),
        )

        assertTrue(session.snapshot.completeViewport)
        assertTrue(episode in session.snapshot.requiredNavigation)
        val forward = session.dispatch(
            SessionEvent.Input(sample(1L, 250L * SourceAnchor.SCREEN_UNITS_PER_PIXEL)),
        )
        assertEquals(InputOutcome.DEFERRED, forward.receipts.single().outcome)
        assertEquals(200L * SourceAnchor.SCREEN_UNITS_PER_PIXEL,
            forward.receipts.single().appliedScreenUnits)
        assertEquals(200L * SourceAnchor.SOURCE_UNITS_PER_PIXEL,
            forward.snapshot.anchor?.sourceYQ32)

        val reverse = session.dispatch(
            SessionEvent.Input(sample(2L, -30L * SourceAnchor.SCREEN_UNITS_PER_PIXEL)),
        )
        assertEquals(InputOutcome.DEFERRED, reverse.receipts.single().outcome)
        assertEquals(2, reverse.snapshot.pendingInputCount)

        val resolved = session.dispatch(
            SessionEvent.NavigationResolved(1L, episode, null, null),
        )
        assertEquals(
            listOf(InputOutcome.CLAMPED, InputOutcome.APPLIED),
            resolved.receipts.map { it.outcome },
        )
        assertEquals(170L * SourceAnchor.SOURCE_UNITS_PER_PIXEL,
            resolved.snapshot.anchor?.sourceYQ32)
    }

    @Test
    fun resolvedNextEpisodeKeepsPendingInputUntilItsManifestArrives() {
        val page = PageId.at(episode, 0)
        val nextEpisode = EpisodeId(episode.seriesId, "next")
        val session = unknownReadySession(
            sessionId = 19L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 300))),
        )
        session.dispatch(SessionEvent.Input(sample(1L, 350L * SourceAnchor.SCREEN_UNITS_PER_PIXEL)))

        val navigation = session.dispatch(
            SessionEvent.NavigationResolved(1L, episode, null, nextEpisode),
        )
        assertEquals(1, navigation.snapshot.pendingInputCount)
        assertTrue(nextEpisode in navigation.snapshot.requiredEpisodes)

        val nextPage = PageId.at(nextEpisode, 0)
        val manifest = EpisodeManifest(
            id = nextEpisode,
            title = "Next",
            previousEpisodeId = episode,
            pages = listOf(PageSpec(nextPage, 0, dimensions = PageDimensions(100, 300))),
        )
        val arrival = session.dispatch(SessionEvent.ManifestResolved(1L, manifest))

        assertEquals(0, arrival.snapshot.pendingInputCount)
        assertEquals(InputOutcome.APPLIED, arrival.receipts.single().outcome)
        assertEquals(nextPage, arrival.snapshot.anchor?.pageId)
        assertEquals(50L * SourceAnchor.SOURCE_UNITS_PER_PIXEL,
            arrival.snapshot.anchor?.sourceYQ32)
    }

    @Test
    fun navigationResolutionIgnoresStaleAndClosedAndRejectsInvalidNeighbors() {
        val page = PageId.at(episode, 0)
        val staleSession = unknownReadySession(
            sessionId = 20L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 300))),
        )
        val before = staleSession.snapshot
        val stale = staleSession.dispatch(SessionEvent.NavigationResolved(2L, episode, null, null))
        assertEquals(before, stale.snapshot)

        val closedSession = unknownReadySession(
            sessionId = 21L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 300))),
        )
        val closed = closedSession.dispatch(SessionEvent.Close).snapshot
        assertEquals(
            closed,
            closedSession.dispatch(SessionEvent.NavigationResolved(1L, episode, null, null)).snapshot,
        )

        assertRejected {
            staleSession.dispatch(
                SessionEvent.NavigationResolved(1L, episode, episode, null),
            )
        }
        val foreign = EpisodeId(SeriesId(SourceId("foreign"), "series"), "episode")
        assertRejected {
            staleSession.dispatch(SessionEvent.NavigationResolved(1L, episode, foreign, null))
        }
    }

    @Test
    fun knownNavigationRepeatsAreNoOpButConflictsAreRejected() {
        val page = PageId.at(episode, 0)
        val session = readySession(
            sessionId = 22L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 300))),
        )
        val before = session.snapshot
        val repeat = session.dispatch(SessionEvent.NavigationResolved(1L, episode, null, null))
        assertEquals(before, repeat.snapshot)
        assertRejected {
            session.dispatch(SessionEvent.NavigationResolved(1L, episode, null, EpisodeId(episode.seriesId, "next")))
        }
    }

    @Test
    fun duplicateManifestRequiresTheSameNavigationKnowledge() {
        val page = PageId.at(episode, 0)
        val session = unknownReadySession(
            sessionId = 23L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 300))),
        )
        val manifest = EpisodeManifest(episode, "Unknown", listOf(PageSpec(page, 0,
            dimensions = PageDimensions(100, 300))))
        val before = session.snapshot

        val repeat = session.dispatch(SessionEvent.ManifestResolved(1L, manifest, navigationKnown = false))

        assertEquals(before, repeat.snapshot)
        assertRejected {
            session.dispatch(SessionEvent.ManifestResolved(1L, manifest, navigationKnown = true))
        }
    }

    @Test
    fun metadataArrivalPreservesSourceAnchorWithoutInput() {
        val page = PageId.at(episode, 0)
        val anchor = SourceAnchor(page, 7_654_321L, 4096L)
        val session = EngineSession(9L, episode, EngineViewport(100, 100), { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, anchor))
        session.dispatch(
            SessionEvent.ManifestResolved(1L, EpisodeManifest(
                episode, "Metadata", listOf(PageSpec(page, 0)),
            )),
        )
        val before = session.snapshot

        session.dispatch(SessionEvent.DimensionsResolved(1L, page, PageDimensions(200, 300)))

        assertEquals(anchor, before.anchor)
        assertEquals(anchor, session.snapshot.anchor)
        assertNotEquals(before.geometryRevision, session.snapshot.geometryRevision)
    }

    @Test
    fun resizeChangesProjectionButRetainsSourceAnchor() {
        val page = PageId.at(episode, 0)
        val anchor = SourceAnchor(page, 8_765_432L, 2048L)
        val session = readySession(
            sessionId = 10L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(200, 500))),
            anchor = anchor,
        )
        val before = session.snapshot

        session.dispatch(SessionEvent.Resize(EngineViewport(200, 100)))

        assertEquals(anchor, session.snapshot.anchor)
        assertNotEquals(before.visibleRegions, session.snapshot.visibleRegions)
    }

    @Test
    fun shortLastPageEndClampFillsViewportAcrossPrecedingPage() {
        val page0 = PageId.at(episode, 0)
        val page1 = PageId.at(episode, 1)
        val session = readySession(
            sessionId = 11L,
            viewport = EngineViewport(100, 50),
            pages = listOf(
                PageSpec(page0, 0, dimensions = PageDimensions(100, 200)),
                PageSpec(page1, 1, dimensions = PageDimensions(100, 10)),
            ),
        )

        val update = session.dispatch(SessionEvent.Input(sample(1L, 300_000L)))

        assertEquals(InputOutcome.CLAMPED, update.receipts.single().outcome)
        assertEquals(DocumentBoundary.END, update.receipts.single().boundary?.boundary)
        assertEquals(page1, update.receipts.single().boundary?.pageId)
        assertEquals(page0, update.snapshot.anchor?.pageId)
        assertEquals(160L * SourceAnchor.SOURCE_UNITS_PER_PIXEL,
            update.snapshot.anchor?.sourceYQ32)
        assertTrue(update.snapshot.completeViewport)
        assertEquals(listOf(page0, page1), update.snapshot.visibleRegions.map { it.pageId })
    }

    @Test
    fun staleGenerationEventsAreIgnoredAfterNavigation() {
        val page = PageId.at(episode, 0)
        val next = EpisodeId(episode.seriesId, "navigated")
        val session = readySession(
            sessionId = 12L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 200))),
        )
        val before = session.dispatch(SessionEvent.Navigate(next)).snapshot
        val lateManifest = EpisodeManifest(
            episode, "Late", listOf(PageSpec(page, 0, dimensions = PageDimensions(300, 400))),
        )

        val manifestUpdate = session.dispatch(SessionEvent.ManifestResolved(1L, lateManifest))
        val dimensionsUpdate = session.dispatch(
            SessionEvent.DimensionsResolved(1L, page, PageDimensions(400, 500)),
        )

        assertEquals(2L, before.generation)
        assertEquals(before, manifestUpdate.snapshot)
        assertEquals(before, dimensionsUpdate.snapshot)
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingDimensionsAreRejected() {
        val page = PageId.at(episode, 0)
        val session = readySession(
            sessionId = 13L,
            viewport = EngineViewport(100, 100),
            pages = listOf(PageSpec(page, 0, dimensions = PageDimensions(100, 200))),
        )
        session.dispatch(SessionEvent.DimensionsResolved(1L, page, PageDimensions(101, 200)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun manifestFromAnotherSeriesIsRejected() {
        val foreign = EpisodeId(SeriesId(SourceId("foreign"), "series"), "episode")
        val session = EngineSession(14L, episode, EngineViewport(100, 100), { 100L })
        session.dispatch(SessionEvent.ManifestResolved(
            1L,
            EpisodeManifest(foreign, "Foreign", listOf(PageSpec(PageId.at(foreign, 0), 0))),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingManifestIsRejected() {
        val page = PageId.at(episode, 0)
        val session = EngineSession(15L, episode, EngineViewport(100, 100), { 100L })
        session.dispatch(SessionEvent.ManifestResolved(
            1L,
            EpisodeManifest(episode, "One", listOf(PageSpec(page, 0))),
        ))
        session.dispatch(SessionEvent.ManifestResolved(
            1L,
            EpisodeManifest(episode, "Two", listOf(PageSpec(page, 0))),
        ))
    }

    @Test
    fun sourceUnitReversalHasNoDriftAndLargeGeometryDoesNotOverflow() {
        val page = PageId.at(episode, 0)
        val dimensions = PageDimensions(Int.MAX_VALUE, Int.MAX_VALUE)
        val session = readySession(
            sessionId = 16L,
            viewport = EngineViewport(Int.MAX_VALUE, 1),
            pages = listOf(PageSpec(page, 0, dimensions = dimensions)),
        )
        val initial = session.snapshot.anchor
        var sequence = 1L
        repeat(200) {
            session.dispatch(SessionEvent.Input(sample(sequence++, 1L)))
            session.dispatch(SessionEvent.Input(sample(sequence++, -1L)))
        }
        assertEquals(initial, session.snapshot.anchor)

        val large = session.dispatch(SessionEvent.Input(sample(sequence, Long.MAX_VALUE)))
        assertTrue(large.receipts.single().appliedScreenUnits in 0L..Long.MAX_VALUE)
    }

    @Test
    fun snapshotReadIsOwnedByConstructionThread() {
        val session = EngineSession(17L, episode, EngineViewport(100, 100), { 100L })
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                session.snapshot
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        thread.start()
        thread.join()

        assertNotNull(failure.get())
        assertTrue(failure.get() is IllegalStateException)
    }

    @Test
    fun stalePositionCannotMoveANavigatedSession() {
        val session = EngineSession(18L, episode, EngineViewport(100, 100), { 100L })
        session.dispatch(SessionEvent.Navigate(episode))
        session.dispatch(SessionEvent.ManifestResolved(2L, manifest(PageDimensions(100, 1000))))
        val before = session.snapshot
        val stale = session.dispatch(SessionEvent.PositionResolved(
            1L, SourceAnchor(PageId.at(episode, 0), 500L * SourceAnchor.SOURCE_UNITS_PER_PIXEL),
        ))
        assertEquals(before, stale.snapshot)
        assertTrue(stale.receipts.isEmpty())
    }

    @Test
    fun duplicatePositionCompletionCannotUndoAcceptedInput() {
        val session = readySession(19L, EngineViewport(100, 100),
            listOf(PageSpec(PageId.at(episode, 0), 0, dimensions = PageDimensions(100, 1000))))
        session.dispatch(SessionEvent.Input(sample(1L, 50L * 1024L)))
        val before = session.snapshot
        val duplicate = session.dispatch(SessionEvent.PositionResolved(1L, null))
        assertEquals(before, duplicate.snapshot)
        assertTrue(duplicate.receipts.isEmpty())
    }

    private fun sample(sequence: Long, delta: Long): InputSample = InputSample(
        sequence = sequence,
        gestureId = sequence,
        eventTimeNanos = 0L,
        deltaScreenUnits = delta,
    )

    private fun manifest(dimensions: PageDimensions): EpisodeManifest = EpisodeManifest(
        id = episode,
        title = "Test",
        pages = listOf(PageSpec(PageId.at(episode, 0), 0, dimensions = dimensions)),
    )

    private fun readySession(
        sessionId: Long,
        viewport: EngineViewport,
        pages: List<PageSpec>,
        anchor: SourceAnchor? = null,
    ): EngineSession {
        val session = EngineSession(sessionId, episode, viewport, { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, anchor))
        session.dispatch(SessionEvent.ManifestResolved(1L, EpisodeManifest(episode, "Ready", pages)))
        return session
    }

    private fun unknownReadySession(
        sessionId: Long,
        viewport: EngineViewport,
        pages: List<PageSpec>,
    ): EngineSession {
        val session = EngineSession(sessionId, episode, viewport, { 100L })
        session.dispatch(SessionEvent.PositionResolved(1L, null))
        session.dispatch(
            SessionEvent.ManifestResolved(
                1L,
                EpisodeManifest(episode, "Unknown", pages),
                navigationKnown = false,
            ),
        )
        return session
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
