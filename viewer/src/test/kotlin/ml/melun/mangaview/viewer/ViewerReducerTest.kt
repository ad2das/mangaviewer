package ml.melun.mangaview.viewer

import java.util.ArrayDeque
import java.util.Random
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerReducerTest {
    @Test
    fun renderConfirmationOpensWarmWorkGateAndBackgroundClosesItAgain() {
        val reducer = ViewerFixtures.reducer()
        var state = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(10L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        )).state
        state = requireNotNull(reducer.reduce(
            state,
            ViewerEvent.SurfaceAttachmentChanged(true, 2L),
        )).state

        val presented = requireNotNull(reducer.reduce(
            state,
            ViewerEvent.ContentFramePresented(3L),
        )).state
        val background = requireNotNull(reducer.reduce(
            presented,
            ViewerEvent.EnterBackground(4L),
        )).state

        assertTrue(presented.hasPresentedContent)
        assertTrue(presented.surfacePresentationReady)
        assertFalse(background.surfacePresentationReady)
    }

    @Test
    fun interactionLifecycleIsExplicitAndSettlesWithoutSyntheticScroll() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        ))

        val moving = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.InteractionChanged(true, 2L),
        ))
        assertTrue(moving.state.interactionActive)
        assertEquals(opened.state.scroll, moving.state.scroll)

        val settled = requireNotNull(reducer.reduce(
            moving.state,
            ViewerEvent.InteractionChanged(false, 3L),
        ))
        assertFalse(settled.state.interactionActive)
        assertEquals(opened.state.scroll, settled.state.scroll)
    }

    @Test
    fun startingMotionCancelsOnlyOffscreenWarmDecodeAfterContentIsVisible() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        )).state
        val hardPage = opened.pageOrder[0]
        val warmPage = opened.pageOrder[1]
        val hardClaim = opened.ownership.claim(1L, hardPage, WorkKind.DECODE, 1, WorkPriority.HARD)
        val warmClaim = hardClaim.ownership.claim(1L, warmPage, WorkKind.DECODE, 1, WorkPriority.WARM)
        val moving = requireNotNull(reducer.reduce(
            opened.copy(
                ownership = warmClaim.ownership,
                firstResponseReceived = true,
                networkConcurrency = 2,
                hasPresentedContent = true,
                surfacePresentationReady = true,
            ),
            ViewerEvent.InteractionChanged(true, 2L),
        ))

        assertEquals(hardClaim.token, moving.state.ownership.owner(WorkKind.DECODE, hardPage))
        assertEquals(null, moving.state.ownership.owner(WorkKind.DECODE, warmPage))
        assertEquals(
            warmClaim.token,
            moving.commands.filterIsInstance<ViewerCommand.CancelDecode>().single().token,
        )
    }

    @Test
    fun startingMotionRelinquishesOffscreenFetchButKeepsVisibleFetch() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(7L, ViewerFixtures.manifest(8), ViewerFixtures.viewport, 1L),
        )).state
        val visiblePage = opened.pageOrder.first()
        val offscreenPage = PageId(
            ViewerFixtures.manifest(1, episodeKey = "adjacent").id,
            "p0001",
        )
        val visibleClaim = WorkOwnership().claim(
            7L,
            visiblePage,
            WorkKind.FETCH,
            1,
            WorkPriority.HARD,
        )
        val offscreenClaim = visibleClaim.ownership.claim(
            7L,
            offscreenPage,
            WorkKind.FETCH,
            1,
            WorkPriority.COLD,
        )

        val moving = requireNotNull(reducer.reduce(
            opened.copy(
                ownership = offscreenClaim.ownership,
                firstResponseReceived = true,
                networkConcurrency = 2,
                hasPresentedContent = true,
                surfacePresentationReady = true,
            ),
            ViewerEvent.InteractionChanged(true, 2L),
        ))

        assertEquals(visibleClaim.token, moving.state.ownership.owner(WorkKind.FETCH, visiblePage))
        assertEquals(null, moving.state.ownership.owner(WorkKind.FETCH, offscreenPage))
        assertEquals(
            offscreenClaim.token,
            moving.commands.filterIsInstance<ViewerCommand.CancelFetch>().single().token,
        )
    }

    @Test
    fun inputReceivedBeforeManifestIsPreservedAsRealDisplacingInput() {
        val manifest = ViewerFixtures.manifest(4)
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 1L,
                manifest = manifest,
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
                initialScroll = FixedPx.fromPixels(900),
                initialVelocityUnitsPerSecond = FixedPx.fromPixels(2_000).units,
            ),
        ))

        assertTrue(opened.state.scroll.contentOffset > FixedPx.ZERO)
        assertEquals(manifest.pages[0].id, opened.state.scroll.anchor.pageId)
        assertEquals(manifest.pages[1].id, opened.state.initialTargetPageId)
        assertNotEquals(opened.state.scroll.anchor.pageId, opened.state.initialTargetPageId)
        assertEquals(
            opened.state.initialTargetPageId,
            opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single().token.pageId,
        )
        assertEquals(1, opened.state.coldFetchSweep.cursor)
        assertEquals(1L, opened.state.userInputRevision)
    }

    @Test
    fun clampedInputReceivedBeforeManifestDoesNotForgeDisplacementProof() {
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 1L,
                manifest = ViewerFixtures.manifest(4),
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
                initialScroll = FixedPx.fromPixels(-900),
            ),
        ))

        assertEquals(FixedPx.ZERO, opened.state.scroll.contentOffset)
        assertEquals(0L, opened.state.userInputRevision)
    }

    @Test
    fun savedPageAnchorOpensAtTheExactPageOffset() {
        val manifest = ViewerFixtures.manifest(4)
        val page = manifest.pages[2].id
        val offset = FixedPx.fromPixels(37)

        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 1L,
                manifest = manifest,
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
                initialPosition = ReadingPosition(page, offset.units),
            ),
        ))

        assertEquals(page, opened.state.scroll.anchor.pageId)
        assertEquals(offset.units, opened.state.scroll.anchor.offsetInPageUnits)
        assertEquals(
            requireNotNull(opened.state.layout.topOf(page)) + offset,
            opened.state.scroll.contentOffset,
        )
    }

    @Test
    fun tallPageDecodesSparseBandsWithoutChangingTheAnchor() {
        val dimensions = PageDimensions(800, 500_000)
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                1L,
                ViewerFixtures.manifest(1, dimensions = { dimensions }),
                ViewerFixtures.viewport,
                1L,
            ),
        ))
        val fetch = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        val verified = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.FetchSucceeded(
                fetch.token,
                VerifiedPageRef("tall", 1_000L, "sha", dimensions),
                10L,
                2L,
            ),
        ))
        val firstDecode = verified.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        val band = firstDecode.band
        val viewportCenterSource = multiplyDivideFloorExact(
            verified.state.viewport.height.units / 2L,
            dimensions.heightPx.toLong(),
            verified.state.layout.totalHeight.units,
        ).toInt()
        assertTrue(viewportCenterSource in band.sourceTopPx until band.sourceBottomPx)
        val tile = PixelTileRef(
            handle = 90L,
            sourceTopPx = band.sourceTopPx,
            sourceBottomPx = band.sourceBottomPx,
            displayHeightPx = 512,
            allocationBytes = 800L * 512L * 4L,
            displayWidthPx = band.displayWidthPx,
        )
        val anchor = verified.state.scroll

        val decoded = requireNotNull(reducer.reduce(
            verified.state,
            ViewerEvent.DecodeSucceeded(
                firstDecode.token,
                PixelRef(tile.handle, dimensions, tile.allocationBytes, listOf(tile)),
                12L,
                3L,
            ),
        ))

        assertEquals(anchor, decoded.state.scroll)
        assertEquals(listOf(tile), decoded.state.pages.values.single().pixel?.tiles)
        val next = decoded.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        assertTrue(next.band.sourceTopPx >= band.sourceBottomPx)
    }

    @Test
    fun duplicateSparseCompletionCannotRecycleAStillResidentBand() {
        val dimensions = PageDimensions(800, 20_000)
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                1L,
                ViewerFixtures.manifest(1, dimensions = { dimensions }),
                ViewerFixtures.viewport,
                1L,
            ),
        ))
        val fetch = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        val verified = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.FetchSucceeded(
                fetch.token,
                VerifiedPageRef("page", 1_000L, "sha", dimensions),
                5L,
                2L,
            ),
        ))
        val decode = verified.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        val tile = PixelTileRef(
            44L,
            decode.band.sourceTopPx,
            decode.band.sourceBottomPx,
            2_048,
            contentVersion = 81L,
            allocationBytes = 800L * 2_048L * 4L,
            displayWidthPx = decode.band.displayWidthPx,
        )
        val pixel = PixelRef(tile.handle, dimensions, tile.allocationBytes, listOf(tile))
        val accepted = requireNotNull(reducer.reduce(
            verified.state,
            ViewerEvent.DecodeSucceeded(decode.token, pixel, 5L, 3L),
        ))

        val duplicate = requireNotNull(reducer.reduce(
            accepted.state,
            ViewerEvent.DecodeSucceeded(decode.token, pixel, 5L, 4L),
        ))

        assertTrue(duplicate.commands.none { command ->
            command is ViewerCommand.ReleasePixel && command.pixel == pixel
        })
        assertTrue(duplicate.state.pages.values.single().pixel?.tiles?.contains(tile) == true)
    }

    @Test
    fun presentedPageCanBeDecodedAgainAfterMemoryEviction() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        ))
        val pageId = opened.state.pageOrder.last()
        val claim = opened.state.ownership.claim(
            1L,
            pageId,
            WorkKind.DECODE,
            1,
            WorkPriority.HARD,
        )
        val token = claim.token
        val verified = opened.state.replacePage(
            pageId,
            opened.state.pages.getValue(pageId).copy(
                milestone = PageMilestone.PRESENTED,
                residency = PageResidency.VERIFIED,
                encoded = VerifiedPageRef("cache", 100L, "sha"),
                pixel = null,
                isPresented = false,
            ),
        ).copy(ownership = claim.ownership)
        val decoded = requireNotNull(reducer.reduce(
            verified,
            ViewerEvent.DecodeSucceeded(
                token,
                PixelRef(91L, PageDimensions(900, 1_300), 4_000L),
                5L,
                3L,
            ),
        ))

        val runtime = decoded.state.pages.getValue(pageId)
        assertEquals(PageMilestone.PRESENTED, runtime.milestone)
        assertEquals(PageResidency.RESIDENT, runtime.residency)
        assertTrue(runtime.pixel != null)
    }

    @Test
    fun warmDecodeAdmissionCannotEvictAndImmediatelyRedecodeTheSamePage() {
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(40), ViewerFixtures.viewport, 1L),
        ))
        val pending = ArrayDeque<ViewerCommand>().apply { addAll(reduction.commands) }
        val decodeCounts = mutableMapOf<ml.melun.mangaview.core.PageId, Int>()
        var now = 2L
        var handle = 100L
        var steps = 0
        while (pending.isNotEmpty() && steps++ < 1_000) {
            val event = when (val command = pending.removeFirst()) {
                is ViewerCommand.FetchPage -> ViewerEvent.FetchSucceeded(
                    command.token,
                    VerifiedPageRef(
                        "cache-${command.token.pageId.remoteKey}",
                        100L,
                        "sha",
                        PageDimensions(900, 1_350),
                    ),
                    20L,
                    now++,
                )
                is ViewerCommand.DecodePage -> {
                    decodeCounts[command.token.pageId] =
                        decodeCounts.getOrDefault(command.token.pageId, 0) + 1
                    ViewerEvent.DecodeSucceeded(
                        command.token,
                        PixelRef(handle++, PageDimensions(900, 1_350), 8L * 1_024L * 1_024L),
                        8L,
                        now++,
                    )
                }
                is ViewerCommand.LoadNextEpisode -> ViewerEvent.NextEpisodeSucceeded(
                    command.token,
                    ViewerFixtures.manifest(
                        1,
                        episodeKey = command.token.targetEpisodeId.remoteKey,
                    ),
                    now++,
                )
                is ViewerCommand.CancelDecode,
                is ViewerCommand.CancelFetch,
                is ViewerCommand.CancelGeneration,
                is ViewerCommand.ReleasePixel,
                -> continue
            }
            reduction = requireNotNull(reducer.reduce(reduction.state, event))
            pending.addAll(reduction.commands)
        }

        assertTrue(
            "Scheduler did not quiesce: pending=${pending.take(5)} counts=$decodeCounts",
            pending.isEmpty(),
        )
        assertTrue("No page was decoded: $decodeCounts", decodeCounts.isNotEmpty())
        assertTrue(decodeCounts.values.all { it == 1 })
    }

    @Test
    fun inputMovesImmediatelyBeforeAnyImageIsReady() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(
            reducer.reduce(
                null,
                ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(100), ViewerFixtures.viewport, 1L),
            ),
        )
        val before = opened.state.scroll.contentOffset

        val moved = requireNotNull(
            reducer.reduce(opened.state, ViewerEvent.UserScroll(FixedPx.fromPixels(700), 20_000L, 2L)),
        )

        assertTrue(moved.state.pages.values.all { it.pixel == null })
        assertNotEquals(before, moved.state.scroll.contentOffset)
        assertEquals(ScrollMutationCause.USER_INPUT, moved.state.scroll.lastCause)
        assertEquals(1L, moved.state.userInputRevision)
    }

    @Test
    fun geometryCorrectionCannotErasePriorUserInputProof() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(4), ViewerFixtures.viewport, 1L),
        ))
        val moved = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.UserScroll(FixedPx.fromPixels(2_000), 20_000L, 2L),
        ))
        val fetch = moved.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        val offsetBeforeCorrection = moved.state.scroll.contentOffset

        val corrected = requireNotNull(reducer.reduce(
            moved.state,
            ViewerEvent.FetchSucceeded(
                fetch.token,
                VerifiedPageRef(
                    "resolved",
                    1_000L,
                    "sha",
                    PageDimensions(1_080, 3_240),
                ),
                10L,
                3L,
            ),
        ))

        assertEquals(offsetBeforeCorrection, corrected.state.scroll.contentOffset)
        assertEquals(ScrollMutationCause.USER_INPUT, corrected.state.scroll.lastCause)
        assertEquals(1L, corrected.state.userInputRevision)
        assertEquals(
            1L,
            ViewerTelemetryPlanner().snapshot(corrected.state, 4L).userInputRevision,
        )
    }

    @Test
    fun programmaticMovementAndNonDisplacingInputCannotForgeUserInputProof() {
        val reducer = ViewerFixtures.reducer()
        val manifest = ViewerFixtures.manifest(4)
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 1L,
                manifest = manifest,
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
                initialPosition = ReadingPosition(manifest.pages[1].id, 0L),
            ),
        ))
        val fetch = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        val initialOffset = opened.state.scroll.contentOffset
        val corrected = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.FetchSucceeded(
                fetch.token,
                VerifiedPageRef(
                    "resolved",
                    1_000L,
                    "sha",
                    PageDimensions(1_080, 3_240),
                ),
                10L,
                2L,
            ),
        ))
        assertEquals(initialOffset, corrected.state.scroll.contentOffset)
        assertEquals(opened.state.scroll.anchor, corrected.state.scroll.anchor)

        val background = requireNotNull(reducer.reduce(
            corrected.state,
            ViewerEvent.EnterBackground(3L),
        ))
        val foreground = requireNotNull(reducer.reduce(
            background.state,
            ViewerEvent.ReturnForeground(4L),
        ))
        assertEquals(0L, foreground.state.userInputRevision)

        val atTop = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(2L, manifest, ViewerFixtures.viewport, 5L),
        ))
        val clamped = requireNotNull(reducer.reduce(
            atTop.state,
            ViewerEvent.UserScroll(FixedPx.fromPixels(-500), -10_000L, 6L),
        ))
        val zero = requireNotNull(reducer.reduce(
            clamped.state,
            ViewerEvent.UserScroll(FixedPx.ZERO, 0L, 7L),
        ))
        assertEquals(FixedPx.ZERO, zero.state.scroll.contentOffset)
        assertEquals(0L, zero.state.userInputRevision)
    }

    @Test
    fun userInputRevisionExactlyTracksDisplacingInputAcrossRandomStateChanges() {
        val reducer = ViewerFixtures.reducer()
        var state = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(9L, ViewerFixtures.manifest(100), ViewerFixtures.viewport, 1L),
        )).state
        val random = Random(724L)
        var expectedRevision = 0L
        var now = 2L
        repeat(500) { step ->
            val event: ViewerEvent = when (random.nextInt(6)) {
                0, 1, 2 -> ViewerEvent.UserScroll(
                    FixedPx.fromPixels(random.nextInt(4_001) - 2_000),
                    random.nextLong(),
                    now++,
                )
                3 -> ViewerEvent.ViewportChanged(
                    Viewport(
                        FixedPx.fromPixels(if (step % 2 == 0) 720 else 1_080),
                        FixedPx.fromPixels(if (step % 3 == 0) 1_280 else 1_920),
                    ),
                    now++,
                )
                4 -> ViewerEvent.SurfaceAttachmentChanged(step % 2 == 0, now++)
                else -> if (step % 2 == 0) {
                    ViewerEvent.EnterBackground(now++)
                } else {
                    ViewerEvent.ReturnForeground(now++)
                }
            }
            val previousOffset = state.scroll.contentOffset
            val previousProof = state.userInputRevision
            state = requireNotNull(reducer.reduce(state, event)).state
            val displacedInput = event is ViewerEvent.UserScroll &&
                event.delta != FixedPx.ZERO && state.scroll.contentOffset != previousOffset
            if (displacedInput) expectedRevision++
            assertTrue(state.userInputRevision >= previousProof)
            assertEquals(expectedRevision, state.userInputRevision)
        }
    }

    @Test
    fun randomOutOfOrderWorkNeverCreatesTwoOwnersForOnePage() {
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(
            reducer.reduce(
                null,
                ViewerEvent.OpenEpisode(7L, ViewerFixtures.manifest(120), ViewerFixtures.viewport, 1L),
            ),
        )
        val pending = ArrayDeque<ViewerCommand>(reduction.commands)
        val random = Random(88L)
        var now = 2L
        var handle = 1L
        repeat(500) {
            if (pending.isEmpty()) {
                val delta = if (random.nextBoolean()) 1_200 else -700
                reduction = requireNotNull(
                    reducer.reduce(
                        reduction.state,
                        ViewerEvent.UserScroll(FixedPx.fromPixels(delta), delta.toLong(), now++),
                    ),
                )
            } else {
                val command = pending.removeFirst()
                val event = completedEvent(command, now++, handle++)
                reduction = requireNotNull(reducer.reduce(reduction.state, event))
            }
            pending.addAll(reduction.commands)
            assertUniqueOwners(reduction.state)
        }
    }

    @Test
    fun staleGenerationAndDuplicateCompletionCannotReplacePixels() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(
            reducer.reduce(
                null,
                ViewerEvent.OpenEpisode(3L, ViewerFixtures.manifest(20), ViewerFixtures.viewport, 1L),
            ),
        )
        val fetch = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        val stale = fetch.token.copy(generation = 2L)
        val encoded = VerifiedPageRef("cache", 100L, "digest")

        val ignored = requireNotNull(
            reducer.reduce(opened.state, ViewerEvent.FetchSucceeded(stale, encoded, 10L, 2L)),
        )

        assertEquals(opened.state.pages, ignored.state.pages)
        assertEquals(fetch.token, ignored.state.ownership.fetches[fetch.token.pageId])
    }

    @Test
    fun staleSameAttemptFetchCompletionCannotCompleteAReclaimedOperation() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(
            reducer.reduce(
                null,
                ViewerEvent.OpenEpisode(4L, ViewerFixtures.manifest(20), ViewerFixtures.viewport, 1L),
            ),
        )
        val first = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single().token
        val released = opened.state.ownership.release(first)
        val reclaimed = released.claim(
            generation = first.generation,
            pageId = first.pageId,
            kind = first.kind,
            attempt = first.attempt,
            priority = first.priority,
        )
        val state = opened.state.copy(ownership = reclaimed.ownership)

        val stale = requireNotNull(
            reducer.reduce(
                state,
                ViewerEvent.FetchSucceeded(first, VerifiedPageRef("stale", 10L, "stale"), 1L, 2L),
            ),
        )

        assertNotEquals(first.operationSequence, reclaimed.token.operationSequence)
        assertEquals(reclaimed.token, stale.state.ownership.owner(WorkKind.FETCH, first.pageId))
        assertEquals(null, stale.state.pages.getValue(first.pageId).encoded)
    }

    @Test
    fun staleSameAttemptDecodeCompletionCannotReplaceAReclaimedOperation() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(
            reducer.reduce(
                null,
                ViewerEvent.OpenEpisode(5L, ViewerFixtures.manifest(20), ViewerFixtures.viewport, 1L),
            ),
        )
        val pageId = opened.state.pageOrder.last()
        val first = opened.state.ownership.claim(
            generation = 5L,
            pageId = pageId,
            kind = WorkKind.DECODE,
            attempt = 1,
            priority = WorkPriority.HARD,
        )
        val reclaimed = first.ownership.release(first.token).claim(
            generation = 5L,
            pageId = pageId,
            kind = WorkKind.DECODE,
            attempt = 1,
            priority = WorkPriority.HARD,
        )
        val encoded = VerifiedPageRef("cache", 100L, "sha")
        val state = opened.state.replacePage(
            pageId,
            opened.state.pages.getValue(pageId).copy(
                milestone = PageMilestone.DECODING,
                residency = PageResidency.VERIFIED,
                encoded = encoded,
            ),
        ).copy(ownership = reclaimed.ownership)
        val stalePixel = PixelRef(923L, PageDimensions(900, 1_300), 4_000L)

        val stale = requireNotNull(
            reducer.reduce(
                state,
                ViewerEvent.DecodeSucceeded(first.token, stalePixel, 1L, 2L),
            ),
        )

        assertNotEquals(first.token.operationSequence, reclaimed.token.operationSequence)
        assertEquals(reclaimed.token, stale.state.ownership.owner(WorkKind.DECODE, pageId))
        assertEquals(null, stale.state.pages.getValue(pageId).pixel)
        assertTrue(stale.commands.contains(ViewerCommand.ReleasePixel(stalePixel)))
    }

    @Test
    fun homeFreezesAnchorAndCancelsOnlyDecodeWork() {
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(
            reducer.reduce(
                null,
                ViewerEvent.OpenEpisode(9L, ViewerFixtures.manifest(40), ViewerFixtures.viewport, 1L),
            ),
        )
        val fetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        reduction = requireNotNull(
            reducer.reduce(
                reduction.state,
                ViewerEvent.FetchSucceeded(
                    fetch.token,
                    VerifiedPageRef("p0", 10L, "sha", PageDimensions(900, 1_350)),
                    20L,
                    2L,
                ),
            ),
        )
        assertTrue(reduction.state.ownership.decodes.isNotEmpty())
        val anchor = reduction.state.scroll
        val activeFetches = reduction.state.ownership.fetches
        val attached = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.SurfaceAttachmentChanged(true, 3L),
        ))

        val background = requireNotNull(reducer.reduce(attached.state, ViewerEvent.EnterBackground(4L)))

        assertEquals(anchor, background.state.scroll)
        assertEquals(activeFetches, background.state.ownership.fetches)
        assertTrue(background.state.ownership.decodes.isEmpty())
        assertTrue(background.commands.any { it is ViewerCommand.CancelDecode })
        assertTrue(background.state.surfaceAttached)
        val detached = requireNotNull(reducer.reduce(
            background.state,
            ViewerEvent.SurfaceAttachmentChanged(false, 5L),
        ))
        assertFalse(detached.state.surfaceAttached)
    }

    private fun completedEvent(command: ViewerCommand, now: Long, handle: Long): ViewerEvent =
        when (command) {
            is ViewerCommand.LoadNextEpisode -> ViewerEvent.NextEpisodeSucceeded(
                command.token,
                ViewerFixtures.manifest(
                    1,
                    episodeKey = command.token.targetEpisodeId.remoteKey,
                ),
                now,
            )
            is ViewerCommand.FetchPage -> ViewerEvent.FetchSucceeded(
                command.token,
                VerifiedPageRef("cache-$handle", 1_000L, "sha-$handle"),
                20L,
                now,
            )
            is ViewerCommand.DecodePage -> ViewerEvent.DecodeSucceeded(
                command.token,
                PixelRef(handle, PageDimensions(900, 800 + (handle % 8L).toInt() * 500), 4_000L),
                8L,
                now,
            )
            is ViewerCommand.CancelDecode,
            is ViewerCommand.CancelFetch,
            is ViewerCommand.CancelGeneration,
            is ViewerCommand.ReleasePixel,
            -> ViewerEvent.UserScroll(FixedPx.ZERO, 0L, now)
        }

    private fun assertUniqueOwners(state: ViewerState) {
        assertEquals(state.ownership.fetches.size, state.ownership.fetches.values.map { it.pageId }.toSet().size)
        assertEquals(state.ownership.decodes.size, state.ownership.decodes.values.map { it.pageId }.toSet().size)
        assertTrue(state.ownership.fetches.values.all { it.kind == WorkKind.FETCH })
        assertTrue(state.ownership.decodes.values.all { it.kind == WorkKind.DECODE })
        assertEquals(
            state.pageOrder.filter { state.pages.getValue(it).pixel != null },
            state.residentPageIds,
        )
        assertEquals(
            state.residentPageIds.sumOf { state.pages.getValue(it).pixel?.allocationBytes ?: 0L },
            state.residentBytes,
        )
        state.episodeProgress.forEach { (episodeId, progress) ->
            assertEquals(
                state.pages.values.count { it.spec.id.episodeId == episodeId && it.encoded != null },
                progress.verifiedCount,
            )
        }
        assertEquals(
            state.pageOrder.indices.filter { state.pages.getValue(state.pageOrder[it]).encoded == null },
            state.pageOrder.indices.filter(state.coldFetchSweep::isPending),
        )
    }
}
