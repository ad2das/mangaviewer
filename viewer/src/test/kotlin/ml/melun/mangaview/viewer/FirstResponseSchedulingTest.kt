package ml.melun.mangaview.viewer

import java.util.Random
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstResponseSchedulingTest {
    @Test
    fun visiblePagePreemptsAnInFlightHardDecodeThatTheGestureLeftBehind() {
        val dimensions = PageDimensions(1_080, 1_920)
        val manifest = ViewerFixtures.manifest(8, dimensions = { dimensions })
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(94L, manifest, ViewerFixtures.viewport, 1L),
        ))
        val initialFetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchResponseStarted(initialFetch.token, 2L),
        ))
        val targetFetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
            .first { it.token.pageId == manifest.pages[2].id }
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                initialFetch.token,
                VerifiedPageRef("initial", 1_000L, "initial-sha", dimensions),
                10L,
                3L,
            ),
        ))
        val abandonedDecode = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                targetFetch.token,
                VerifiedPageRef("target", 1_000L, "target-sha", dimensions),
                10L,
                4L,
            ),
        ))
        val targetTop = requireNotNull(reduction.state.layout.topOf(targetFetch.token.pageId))
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.UserScroll(targetTop, targetTop.units, 5L),
        ))

        assertEquals(
            abandonedDecode.token,
            reduction.commands.filterIsInstance<ViewerCommand.CancelDecode>().single().token,
        )
        assertEquals(
            targetFetch.token.pageId,
            reduction.state.ownership.decodes.getValue(targetFetch.token.pageId).pageId,
        )
    }

    @Test
    fun appendedEpisodeRawRunwayIsPromotedAheadOfOrdinaryOffscreenWork() {
        val next = ViewerFixtures.manifest(5, episodeKey = "next")
        val current = ViewerFixtures.manifest(20).copy(nextEpisodeId = next.id)
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(88L, current, ViewerFixtures.viewport, 1L),
        ))
        val append = opened.commands.filterIsInstance<ViewerCommand.LoadNextEpisode>().single()
        val appended = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.NextEpisodeSucceeded(append.token, next, 2L),
        )).state

        val demands = DemandPlanner().plan(appended)
        val firstOffscreen = demands.indexOfFirst { it.distanceUnits > 0L }
        val promoted = demands.indexOfFirst { it.pageId == next.pages.first().id }
        assertTrue(promoted >= firstOffscreen)
        assertEquals(promoted, firstOffscreen)
        assertTrue(demands[promoted].decodeBand == null)
    }

    @Test
    fun aNewlyVisiblePageGetsAFastFullQualitySliceEvenAfterEarlierPixelsExist() {
        val dimensions = PageDimensions(1_080, 5_000)
        val manifest = ViewerFixtures.manifest(3, dimensions = { dimensions })
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(89L, manifest, ViewerFixtures.viewport, 1L),
        )).state
        val first = manifest.pages[0].id
        val target = manifest.pages[1].id
        val withEarlierPixels = opened
            .replacePage(first, opened.pages.getValue(first).copy(
                pixel = PixelRef(1L, dimensions, 1L),
                isPresented = true,
            ))
            .replacePage(target, opened.pages.getValue(target).copy(
                encoded = VerifiedPageRef("target", 1L, "target-sha", dimensions),
            ))
        val targetTop = requireNotNull(withEarlierPixels.layout.topOf(target))
        val atTarget = withEarlierPixels.copy(
            scroll = ScrollController().navigate(
                withEarlierPixels.layout,
                withEarlierPixels.viewport,
                target,
                FixedPx.ZERO,
                revision = withEarlierPixels.userInputRevision,
            ),
        )

        val demand = DemandPlanner().plan(atTarget).first { it.pageId == target }
        val band = requireNotNull(demand.decodeBand)
        assertEquals(1_080, band.displayWidthPx)
        assertTrue(band.sourceBottomPx - band.sourceTopPx <= 256)
        assertEquals(targetTop, atTarget.scroll.contentOffset)
    }

    @Test
    fun firstDecodeIsAFullQualitySliceEvenWhenNoGestureIsActiveAtManifestCompletion() {
        val dimensions = PageDimensions(1_080, 1_920)
        val manifest = ViewerFixtures.manifest(2, dimensions = { dimensions })
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(90L, manifest, ViewerFixtures.viewport, 1L),
        ))
        val fetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                fetch.token,
                VerifiedPageRef("initial", 1_000L, "initial-sha", dimensions),
                10L,
                2L,
            ),
        ))

        val firstDecode = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        assertEquals(1_080, firstDecode.band.displayWidthPx)
        assertTrue(firstDecode.band.sourceBottomPx - firstDecode.band.sourceTopPx <= 256)
    }

    @Test
    fun firstGestureUsesOneSmallPixelSliceAndKeepsVisibleDecodingActive() {
        val dimensions = PageDimensions(1_080, 5_000)
        val manifest = ViewerFixtures.manifest(3, dimensions = { dimensions })
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 91L,
                manifest = manifest,
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
                initialInteractionActive = true,
            ),
        ))
        val fetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchResponseStarted(fetch.token, 2L),
        ))
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                fetch.token,
                VerifiedPageRef("initial", 1_000L, "initial-sha", dimensions),
                10L,
                3L,
            ),
        ))
        val bootstrap = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        assertTrue(bootstrap.band.sourceBottomPx - bootstrap.band.sourceTopPx <= 256)
        val tile = bootstrap.band.toTile(dimensions)
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.DecodeSucceeded(
                bootstrap.token,
                PixelRef(tile.handle, dimensions, tile.allocationBytes, listOf(tile)),
                10L,
                4L,
            ),
        ))

        assertTrue(reduction.state.startupMotionPending)
        val continued = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        assertEquals(WorkPriority.HARD, continued.token.priority)
        assertTrue(
            continued.band.sourceBottomPx <= bootstrap.band.sourceTopPx ||
                continued.band.sourceTopPx >= bootstrap.band.sourceBottomPx,
        )
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.InteractionChanged(false, 5L),
        ))
        assertFalse(reduction.state.startupMotionPending)
    }

    @Test
    fun nearestVerifiedVisiblePageOwnsForegroundDecodeAfterGeometryMovesTheCenter() {
        val manifest = ViewerFixtures.manifest(5)
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 70L,
                manifest = manifest,
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
                initialScroll = FixedPx.fromPixels(900),
                initialVelocityUnitsPerSecond = FixedPx.fromPixels(2_000).units,
            ),
        ))
        val targetFetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchResponseStarted(targetFetch.token, 2L),
        ))

        val short = PageDimensions(1_000, 500)
        val adjustedLayout = reduction.state.layout
            .resolve(manifest.pages[0].id, short)
            .resolve(manifest.pages[1].id, short)
        val adjustedScroll = ScrollController().preserveAnchor(
            adjustedLayout,
            reduction.state.viewport,
            reduction.state.scroll,
        )
        reduction = reduction.copy(state = reduction.state.copy(
            layout = adjustedLayout,
            scroll = adjustedScroll,
        ))

        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                targetFetch.token,
                VerifiedPageRef("target", 1L, "target-sha", short),
                elapsedMillis = 10L,
                atNanos = 3L,
            ),
        ))

        assertEquals(manifest.pages[2].id, reduction.state.layout.pageAt(FixedPx(
            reduction.state.scroll.contentOffset.units + reduction.state.viewport.height.units / 2L,
        )))
        val decode = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        assertEquals(manifest.pages[1].id, decode.token.pageId)
        assertEquals(WorkPriority.HARD, decode.token.priority)
    }

    @Test
    fun coveredForegroundImmediatelyDecodesTheTouchingForwardPage() {
        val dimensions = PageDimensions(1_080, 1_920)
        val manifest = ViewerFixtures.manifest(6, dimensions = { dimensions })
        val reducer = ViewerFixtures.reducer()
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L),
        ))
        val initialFetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchResponseStarted(initialFetch.token, 2L),
        ))
        val forwardFetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
            .single { it.token.pageId == manifest.pages[1].id }
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                initialFetch.token,
                VerifiedPageRef("current", 1_000L, "current-sha", dimensions),
                10L,
                3L,
            ),
        ))
        val hardDecode = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>()
            .single { it.token.pageId == manifest.pages[0].id }
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.SurfaceAttachmentChanged(true, 4L),
        ))
        val band = hardDecode.band
        val displayHeight = ((band.sourceBottomPx - band.sourceTopPx).toLong() *
            band.displayWidthPx / dimensions.widthPx).toInt()
        val tile = PixelTileRef(
            handle = 1L,
            sourceTopPx = band.sourceTopPx,
            sourceBottomPx = band.sourceBottomPx,
            displayHeightPx = displayHeight,
            allocationBytes = band.displayWidthPx.toLong() * displayHeight * 4L,
            displayWidthPx = band.displayWidthPx,
        )
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.DecodeSucceeded(
                hardDecode.token,
                PixelRef(tile.handle, dimensions, tile.allocationBytes, listOf(tile)),
                10L,
                5L,
            ),
        ))
        val currentId = manifest.pages[0].id
        var fillSequence = 100L
        while (reduction.state.pages.getValue(currentId).isPresented.not()) {
            val fill = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>()
                .single { it.token.pageId == currentId }
            val fillTile = fill.band.toTile(dimensions).copy(handle = fillSequence++)
            reduction = requireNotNull(reducer.reduce(
                reduction.state,
                ViewerEvent.DecodeSucceeded(
                    fill.token,
                    PixelRef(fillTile.handle, dimensions, fillTile.allocationBytes, listOf(fillTile)),
                    10L,
                    fillSequence,
                ),
            ))
        }
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.InteractionChanged(true, 6L),
        ))
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                forwardFetch.token,
                VerifiedPageRef("next", 1_000L, "next-sha", dimensions),
                10L,
                7L,
            ),
        ))
        assertTrue(reduction.state.interactionActive)
        val warmDecode = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>()
            .single { it.token.pageId == manifest.pages[1].id }
        assertEquals(WorkPriority.HARD, warmDecode.token.priority)
    }

    @Test
    fun initialVisibleAnchorExclusivelyOwnsFetchUntilItsValidatedResponseStarts() {
        val reducer = ViewerFixtures.reducer()
        val manifest = ViewerFixtures.manifest(20)
        val requestedAnchor = manifest.pages[7].id
        var reduction = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(
                generation = 71L,
                manifest = manifest,
                viewport = ViewerFixtures.viewport,
                atNanos = 1L,
                initialPosition = ReadingPosition(requestedAnchor, 0L),
            ),
        ))
        val firstFetch = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()

        assertEquals(requestedAnchor, reduction.state.scroll.anchor.pageId)
        assertEquals(requestedAnchor, firstFetch.token.pageId)
        assertEquals(WorkPriority.HARD, firstFetch.token.priority)
        assertEquals(setOf(requestedAnchor), reduction.state.ownership.fetches.keys)

        val scrollBefore = reduction.state.scroll.contentOffset
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.UserScroll(FixedPx.fromPixels(300), 4_000L, 2L),
        ))

        assertTrue(reduction.state.scroll.contentOffset > scrollBefore)
        assertEquals(setOf(requestedAnchor), reduction.state.ownership.fetches.keys)
        assertTrue(reduction.commands.none { it is ViewerCommand.FetchPage })

        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchFailed(firstFetch.token, "timeout", 10L, 3L),
        ))
        assertFalse(reduction.state.firstResponseReceived)
        assertTrue(reduction.state.ownership.fetches.isEmpty())
        assertTrue(reduction.commands.none { it is ViewerCommand.FetchPage })

        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.RetryWakeup(13L),
        ))
        val retry = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        assertEquals(requestedAnchor, retry.token.pageId)
        assertEquals(setOf(requestedAnchor), reduction.state.ownership.fetches.keys)

        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchResponseStarted(retry.token, atNanos = 14L),
        ))
        val nextFetches = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()

        assertTrue(reduction.state.firstResponseReceived)
        assertEquals(6, reduction.state.networkConcurrency)
        assertEquals(5, nextFetches.size)
        val targetIndex = manifest.pages.indexOfFirst { it.id == requestedAnchor }
        assertTrue(nextFetches.all { command ->
            manifest.pages.indexOfFirst { it.id == command.token.pageId } > targetIndex
        })
        assertEquals(0, nextFetches.count { it.token.priority == WorkPriority.HARD })
        assertEquals(5, nextFetches.count { it.token.priority == WorkPriority.WARM })
        assertEquals(5, nextFetches.map { it.token.pageId }.toSet().size)
        assertTrue(nextFetches.none { it.token.pageId == requestedAnchor })

        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.FetchSucceeded(
                retry.token,
                VerifiedPageRef("target", 1L, "target-sha"),
                elapsedMillis = 20L,
                atNanos = 15L,
            ),
        ))
        assertTrue(reduction.state.pages.getValue(requestedAnchor).encoded != null)
        assertEquals(6, reduction.state.networkConcurrency)
    }

    @Test
    fun staleResponseStartCannotExpandNetworkConcurrency() {
        val reducer = ViewerFixtures.reducer()
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(3), ViewerFixtures.viewport, 1L),
        ))
        val owner = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single().token
        val stale = owner.copy(operationSequence = owner.operationSequence + 99L)

        val ignored = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.FetchResponseStarted(stale, 2L),
        ))

        assertFalse(ignored.state.firstResponseReceived)
        assertEquals(1, ignored.state.networkConcurrency)
        assertEquals(setOf(owner.pageId), ignored.state.ownership.fetches.keys)
    }

    @Test
    fun randomizedPreResponseScrollKeepsOneOwnerOnTheCurrentVisibleTarget() {
        val random = Random(0xF1A57L)
        repeat(200) { sample ->
            val pageCount = 1 + random.nextInt(500)
            val manifest = ViewerFixtures.manifest(pageCount, episodeKey = "random-$sample")
            val anchor = manifest.pages[random.nextInt(pageCount)].id
            val reducer = ViewerFixtures.reducer()
            var reduction = requireNotNull(reducer.reduce(
                null,
                ViewerEvent.OpenEpisode(
                    generation = sample + 1L,
                    manifest = manifest,
                    viewport = ViewerFixtures.viewport,
                    atNanos = 1L,
                    initialPosition = ReadingPosition(anchor, random.nextInt(500).toLong()),
                ),
            ))
            var retargets = 0
            var previousTarget = reduction.state.initialTargetPageId
            repeat(20) { step ->
                val direction = if (random.nextBoolean()) 1 else -1
                val pixels = direction * (1 + random.nextInt(2_500))
                reduction = requireNotNull(reducer.reduce(
                    reduction.state,
                    ViewerEvent.UserScroll(
                        FixedPx.fromPixels(pixels),
                        pixels.toLong() * 20L,
                        step + 2L,
                    ),
                ))
                val scheduledFetches = reduction.commands.filterIsInstance<ViewerCommand.FetchPage>()
                val visibleTarget = reduction.state.initialTargetPageId
                if (visibleTarget != previousTarget) retargets += 1
                previousTarget = visibleTarget
                assertTrue(scheduledFetches.all { it.token.pageId == visibleTarget })
                assertEquals(setOf(visibleTarget), reduction.state.ownership.fetches.keys)
                assertEquals(1, reduction.state.networkConcurrency)
                assertFalse(reduction.state.firstResponseReceived)
                assertTrue(retargets <= 1)
            }
        }
    }

    @Test
    fun preResponseGestureAtomicallyMovesTheSingleFetchToTheVisiblePage() {
        val reducer = ViewerFixtures.reducer()
        val manifest = ViewerFixtures.manifest(20)
        val opened = requireNotNull(reducer.reduce(
            null,
            ViewerEvent.OpenEpisode(81L, manifest, ViewerFixtures.viewport, 1L),
        ))
        val original = opened.commands.filterIsInstance<ViewerCommand.FetchPage>().single().token

        val moved = requireNotNull(reducer.reduce(
            opened.state,
            ViewerEvent.UserScroll(FixedPx.fromPixels(8_000), 20_000L, 2L),
        ))

        val cancellation = moved.commands.filterIsInstance<ViewerCommand.CancelFetch>().single()
        val replacement = moved.commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        assertEquals(original, cancellation.token)
        assertTrue(replacement.token.pageId != original.pageId)
        assertEquals(moved.state.initialTargetPageId, replacement.token.pageId)
        assertEquals(setOf(replacement.token.pageId), moved.state.ownership.fetches.keys)
        assertEquals(1, moved.state.networkConcurrency)
        assertFalse(moved.state.firstResponseReceived)
    }

    private fun PixelBand.toTile(dimensions: PageDimensions): PixelTileRef {
        val displayHeight = ((sourceBottomPx - sourceTopPx).toLong() *
            displayWidthPx / dimensions.widthPx).toInt()
        return PixelTileRef(
            handle = 99L,
            sourceTopPx = sourceTopPx,
            sourceBottomPx = sourceBottomPx,
            displayHeightPx = displayHeight,
            allocationBytes = displayWidthPx.toLong() * displayHeight * 4L,
            displayWidthPx = displayWidthPx,
        )
    }
}
