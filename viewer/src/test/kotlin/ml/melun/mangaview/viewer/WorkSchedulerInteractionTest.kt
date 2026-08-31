package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.PageDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkSchedulerInteractionTest {
    @Test
    fun oneWarmDecodeContinuesDuringInputBeforeTheFirstPresentation() {
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

        assertEquals(1, decodes.size)
        assertEquals(ahead, decodes.single().token.pageId)
        assertEquals(WorkPriority.WARM, decodes.single().token.priority)
    }
}
