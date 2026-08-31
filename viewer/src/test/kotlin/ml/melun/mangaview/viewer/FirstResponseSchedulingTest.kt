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
    fun firstGestureUsesOneSmallPixelSliceThenRestoresFullThroughput() {
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
        assertTrue(reduction.commands.none { it is ViewerCommand.DecodePage })
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.InteractionChanged(false, 5L),
        ))
        val fullThroughput = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>().single()
        assertEquals(512, fullThroughput.band.sourceBottomPx - fullThroughput.band.sourceTopPx)
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
    fun settledForwardGestureStartsOneWarmDecodeBehindThePresentedPage() {
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
        reduction = reduction.copy(
            state = reduction.state.replacePage(
                currentId,
                reduction.state.pages.getValue(currentId).copy(isPresented = true),
            ),
        )
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
        assertTrue(reduction.commands.none { command ->
            command is ViewerCommand.DecodePage && command.token.priority == WorkPriority.WARM
        })
        reduction = requireNotNull(reducer.reduce(
            reduction.state,
            ViewerEvent.InteractionChanged(false, 8L),
        ))

        val warmDecode = reduction.commands.filterIsInstance<ViewerCommand.DecodePage>()
            .single { it.token.pageId == manifest.pages[1].id }
        assertFalse(reduction.state.interactionActive)
        assertEquals(WorkPriority.WARM, warmDecode.token.priority)
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
    fun randomizedPreResponseScrollKeepsTheSingleLaunchTargetOwner() {
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
            val launchTarget = reduction.state.initialTargetPageId

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
                assertTrue(scheduledFetches.all { it.token.pageId == launchTarget })
                assertEquals(setOf(launchTarget), reduction.state.ownership.fetches.keys)
                assertEquals(1, reduction.state.networkConcurrency)
                assertFalse(reduction.state.firstResponseReceived)
            }
        }
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
