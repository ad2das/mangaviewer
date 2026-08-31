package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSchedulerInteractionTest {
    @Test
    fun offscreenDecodeWaitsForTheFirstActualSurfacePresentationEvenWhenIdle() {
        val dimensions = PageDimensions(1_080, 1_920)
        val manifest = ViewerFixtures.manifest(4, dimensions = { dimensions })
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(9L, manifest, ViewerFixtures.viewport, 1L),
        )).state
        val visible = manifest.pages[0].id
        val ahead = manifest.pages[2].id
        val state = opened
            .replacePage(visible, opened.pages.getValue(visible).copy(
                encoded = VerifiedPageRef("visible", 1L, "visible-sha", dimensions),
                pixel = PixelRef(1L, dimensions, 1L),
                isPresented = true,
            ))
            .replacePage(ahead, opened.pages.getValue(ahead).copy(
                encoded = VerifiedPageRef("ahead", 1L, "ahead-sha", dimensions),
            ))
            .copy(
                ownership = WorkOwnership(),
                firstResponseReceived = true,
                networkConcurrency = 2,
                interactionActive = false,
                hasPresentedContent = false,
                surfacePresentationReady = false,
            )

        val beforePresentation = WorkScheduler(DemandPlanner()).schedule(state).commands
            .filterIsInstance<ViewerCommand.DecodePage>()
        val afterPresentation = WorkScheduler(DemandPlanner()).schedule(state.copy(
            hasPresentedContent = true,
            surfacePresentationReady = true,
        )).commands.filterIsInstance<ViewerCommand.DecodePage>()

        assertEquals(emptyList<ViewerCommand.DecodePage>(), beforePresentation)
        assertEquals(ahead, afterPresentation.single().token.pageId)
    }

    @Test
    fun offscreenWarmDecodeNeverCompetesWithAnActiveGesture() {
        val dimensions = PageDimensions(1_080, 1_920)
        val manifest = ViewerFixtures.manifest(6, dimensions = { dimensions })
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L),
        )).state
        val visible = manifest.pages[0].id
        val ahead = manifest.pages[2].id
        val state = opened
            .replacePage(visible, opened.pages.getValue(visible).copy(
                encoded = VerifiedPageRef("visible", 1L, "visible-sha", dimensions),
                pixel = PixelRef(1L, dimensions, 1L),
                isPresented = true,
            ))
            .replacePage(ahead, opened.pages.getValue(ahead).copy(
                encoded = VerifiedPageRef("ahead", 1L, "ahead-sha", dimensions),
            ))
            .copy(
                ownership = WorkOwnership(),
                firstResponseReceived = true,
                networkConcurrency = 2,
                interactionActive = true,
                hasPresentedContent = false,
            )

        val decodes = WorkScheduler(DemandPlanner()).schedule(state).commands
            .filterIsInstance<ViewerCommand.DecodePage>()

        assertEquals(emptyList<ViewerCommand.DecodePage>(), decodes)
    }

    @Test
    fun offscreenWarmDecodeWaitsUntilPresentedContentStopsMoving() {
        val dimensions = PageDimensions(1_080, 1_920)
        val manifest = ViewerFixtures.manifest(6, dimensions = { dimensions })
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L),
        )).state
        val visible = manifest.pages[0].id
        val ahead = manifest.pages[2].id
        val state = opened
            .replacePage(visible, opened.pages.getValue(visible).copy(
                encoded = VerifiedPageRef("visible", 1L, "visible-sha", dimensions),
                pixel = PixelRef(1L, dimensions, 1L),
                isPresented = true,
            ))
            .replacePage(ahead, opened.pages.getValue(ahead).copy(
                encoded = VerifiedPageRef("ahead", 1L, "ahead-sha", dimensions),
            ))
            .copy(
                ownership = WorkOwnership(),
                firstResponseReceived = true,
                networkConcurrency = 2,
                interactionActive = true,
                hasPresentedContent = true,
                surfacePresentationReady = true,
            )

        val decodes = WorkScheduler(DemandPlanner()).schedule(state).commands
            .filterIsInstance<ViewerCommand.DecodePage>()

        assertEquals(emptyList<ViewerCommand.DecodePage>(), decodes)
    }

    @Test
    fun movingReaderKeepsCurrentEpisodeFetchesAheadOfAdjacentWork() {
        val dimensions = PageDimensions(1_080, 1_920)
        val manifest = ViewerFixtures.manifest(8, dimensions = { dimensions })
        val opened = requireNotNull(ViewerFixtures.reducer().reduce(
            null,
            ViewerEvent.OpenEpisode(3L, manifest, ViewerFixtures.viewport, 1L),
        )).state
        val state = opened.copy(
            ownership = WorkOwnership(),
            firstResponseReceived = true,
            networkConcurrency = 6,
            interactionActive = true,
            hasPresentedContent = true,
            surfacePresentationReady = true,
        )

        val fetches = WorkScheduler(DemandPlanner()).schedule(state).commands
            .filterIsInstance<ViewerCommand.FetchPage>()

        assertTrue(fetches.isNotEmpty())
        assertTrue(fetches.all { it.token.pageId.episodeId == state.currentEpisodeId })
    }
}
