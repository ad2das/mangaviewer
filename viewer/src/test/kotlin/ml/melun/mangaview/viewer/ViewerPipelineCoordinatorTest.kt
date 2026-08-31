package ml.melun.mangaview.viewer

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPipelineCoordinatorTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun actorThreadPostReducesAndSubmitsBeforeReturning() = runTest {
        val frames = mutableListOf<FramePlan>()
        val coordinator = ViewerPipelineCoordinator(
            scope = this,
            reducer = ViewerFixtures.reducer(),
            framePlanner = FramePlanner(),
            renderPort = RenderPort(frames::add),
            workPort = ViewerWorkPort {},
            isActorThread = { true },
        )

        assertTrue(coordinator.post(
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(30), ViewerFixtures.viewport, 1L),
        ))
        assertTrue(coordinator.post(ViewerEvent.SurfaceAttachmentChanged(true, 2L)))
        assertTrue(coordinator.post(ViewerEvent.UserScroll(FixedPx.fromPixels(640), 12_000L, 3L)))

        assertEquals(FixedPx.fromPixels(640), frames.last().scrollOffset)
        coordinator.close()
    }

    @Test
    fun suppliedActorDispatcherAlsoOwnsRenderSubmission() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "shared-display")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val renderedThread = CompletableDeferred<String>()
        val coordinator = ViewerPipelineCoordinator(
            scope = this,
            reducer = ViewerFixtures.reducer(),
            framePlanner = FramePlanner(),
            renderPort = RenderPort { renderedThread.complete(Thread.currentThread().name) },
            workPort = ViewerWorkPort {},
            actorDispatcher = dispatcher,
        )
        try {
            coordinator.emit(
                ViewerEvent.OpenEpisode(
                    1L,
                    ViewerFixtures.manifest(30),
                    ViewerFixtures.viewport,
                    1L,
                ),
            )
            coordinator.emit(ViewerEvent.SurfaceAttachmentChanged(true, 2L))

            assertTrue(
                withTimeout(5_000L) { renderedThread.await() }.startsWith("shared-display"),
            )
        } finally {
            coordinator.close()
            dispatcher.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun stateOnlyEventsDoNotSubmitDuplicateVisualFrames() = runTest {
        val frames = mutableListOf<FramePlan>()
        val coordinator = ViewerPipelineCoordinator(
            scope = this,
            reducer = ViewerFixtures.reducer(),
            framePlanner = FramePlanner(),
            renderPort = RenderPort(frames::add),
            workPort = ViewerWorkPort {},
        )

        coordinator.emit(
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(30), ViewerFixtures.viewport, 1L),
        )
        coordinator.emit(ViewerEvent.SurfaceAttachmentChanged(true, 2L))
        runCurrent()
        val visibleFrameCount = frames.size

        coordinator.emit(ViewerEvent.RetryWakeup(3L))
        runCurrent()

        assertEquals(visibleFrameCount, frames.size)
        coordinator.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun channelSerializesInputAndPublishesAnImmediatePlaceholderFrame() = runTest {
        val frames = mutableListOf<FramePlan>()
        val commands = mutableListOf<ViewerCommand>()
        val coordinator = ViewerPipelineCoordinator(
            scope = this,
            reducer = ViewerFixtures.reducer(),
            framePlanner = FramePlanner(),
            renderPort = RenderPort(frames::add),
            workPort = ViewerWorkPort(commands::add),
        )

        coordinator.emit(
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(30), ViewerFixtures.viewport, 1L),
        )
        coordinator.emit(ViewerEvent.SurfaceAttachmentChanged(true, 2L))
        coordinator.emit(ViewerEvent.UserScroll(FixedPx.fromPixels(640), 12_000L, 3L))
        runCurrent()

        assertTrue(commands.any { it is ViewerCommand.FetchPage })
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.first().pages.all { it.pixel == null })
        assertEquals(FixedPx.fromPixels(640), frames.last().scrollOffset)
        coordinator.close()
    }
}
