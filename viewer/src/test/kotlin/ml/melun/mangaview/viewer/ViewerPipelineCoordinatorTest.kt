package ml.melun.mangaview.viewer

import java.util.concurrent.Executors
import ml.melun.mangaview.core.PageDimensions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ViewerPipelineCoordinatorTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun newlyVisibleFetchCompletionBypassesAnOlderColdCompletionBurst() = runTest {
        val actorThread = AtomicBoolean(true)
        val commands = mutableListOf<ViewerCommand>()
        val manifest = ViewerFixtures.manifest(
            pageCount = 30,
            dimensions = { PageDimensions(1_080, 1_920) },
        )
        val coordinator = ViewerPipelineCoordinator(
            scope = this,
            reducer = ViewerFixtures.reducer(),
            framePlanner = FramePlanner(),
            renderPort = RenderPort {},
            workPort = ViewerWorkPort(commands::add),
            isActorThread = actorThread::get,
        )
        coordinator.post(ViewerEvent.OpenEpisode(1L, manifest, ViewerFixtures.viewport, 1L))
        val initial = commands.filterIsInstance<ViewerCommand.FetchPage>().single()
        coordinator.post(ViewerEvent.FetchResponseStarted(initial.token, 2L))
        coordinator.post(ViewerEvent.FetchSucceeded(
            initial.token,
            VerifiedPageRef("initial", 1_000L, "initial-sha", PageDimensions(1_080, 1_920)),
            10L,
            3L,
        ))
        coordinator.post(ViewerEvent.SurfaceAttachmentChanged(true, 4L))
        coordinator.post(ViewerEvent.ContentFramePresented(5L))
        val burst = commands.filterIsInstance<ViewerCommand.FetchPage>()
            .filter { it.token != initial.token }
        val target = burst.last()
        commands.clear()

        actorThread.set(false)
        burst.forEachIndexed { index, fetch ->
            coordinator.post(ViewerEvent.FetchSucceeded(
                token = fetch.token,
                encoded = VerifiedPageRef(
                    cacheKey = "page-$index",
                    byteCount = 1_000L,
                    sha256 = "sha-$index",
                    dimensions = PageDimensions(1_080, 1_920),
                ),
                elapsedMillis = 10L,
                atNanos = 10L + index,
            ))
        }
        actorThread.set(true)
        val targetTop = requireNotNull(coordinator.state.value?.layout?.topOf(target.token.pageId))
        coordinator.post(ViewerEvent.UserScroll(targetTop, 20_000L, 100L))
        runCurrent()

        val visibleDecode = commands.filterIsInstance<ViewerCommand.DecodePage>().last()
        assertEquals(target.token.pageId, visibleDecode.token.pageId)
        coordinator.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun actorInputBypassesAnOlderSpeculativeWorkerCompletion() = runTest {
        val actorThread = AtomicBoolean(true)
        val frames = mutableListOf<FramePlan>()
        val commands = mutableListOf<ViewerCommand>()
        val coordinator = ViewerPipelineCoordinator(
            scope = this,
            reducer = ViewerFixtures.reducer(),
            framePlanner = FramePlanner(),
            renderPort = RenderPort(frames::add),
            workPort = ViewerWorkPort(commands::add),
            isActorThread = actorThread::get,
        )
        coordinator.post(
            ViewerEvent.OpenEpisode(1L, ViewerFixtures.manifest(30), ViewerFixtures.viewport, 1L),
        )
        coordinator.post(ViewerEvent.SurfaceAttachmentChanged(true, 2L))
        val initial = commands.filterIsInstance<ViewerCommand.FetchPage>().single()

        actorThread.set(false)
        coordinator.post(ViewerEvent.FetchSucceeded(
            initial.token,
            VerifiedPageRef("initial", 1_000L, "initial-sha", PageDimensions(1_080, 1_920)),
            10L,
            3L,
        ))
        actorThread.set(true)
        coordinator.post(ViewerEvent.UserScroll(FixedPx.fromPixels(640), 12_000L, 4L))

        assertEquals(FixedPx.fromPixels(640), frames.last().scrollOffset)
        runCurrent()
        assertTrue(requireNotNull(coordinator.state.value).firstResponseReceived)
        coordinator.close()
    }

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
