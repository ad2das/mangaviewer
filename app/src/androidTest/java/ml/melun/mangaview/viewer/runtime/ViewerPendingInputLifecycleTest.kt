package ml.melun.mangaview.viewer.runtime

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic lifecycle race check, NOT a live-gesture corpus/performance qualification. */
@RunWith(AndroidJUnit4::class)
class ViewerPendingInputLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun homePersistsPendingDragBeforeTheNextFrame() = verifyPendingInput(close = false)

    @Test fun closePersistsPendingDragBeforeRejectingFurtherInput() = verifyPendingInput(close = true)

    private fun verifyPendingInput(close: Boolean) {
        lateinit var fixture: LifecycleFixture
        instrumentation.runOnMainSync { fixture = LifecycleFixture() }
        try {
            assertTrue("Runtime did not open", fixture.opened.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val runtime = fixture.runtime
                val initialRevision = runtime.userInputRevisionSnapshot()
                pendingDrag(runtime)
                assertEquals(initialRevision, runtime.userInputRevisionSnapshot())
                if (close) fixture.close() else runtime.enterBackground()
                assertEquals(initialRevision + 1, runtime.userInputRevisionSnapshot())
                assertEquals(FixedPx.fromPixels(500).units, fixture.saved.last().offsetInPageUnits)
                if (!close) {
                    runtime.enterForeground()
                    assertEquals(fixture.saved.last(), runtime.chromeSnapshot()?.position)
                }
            }
        } finally {
            instrumentation.runOnMainSync { fixture.close() }
            assertTrue("Runtime did not close", fixture.closed.await(10, TimeUnit.SECONDS))
            fixture.scope.cancel()
        }
    }

    private fun pendingDrag(runtime: SessionViewerRuntime) {
        val started = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, 400F, 900F, 0)
        val move = MotionEvent.obtain(started, started + 16, MotionEvent.ACTION_MOVE, 400F, 400F, 0)
        try {
            runtime.surface.onTouchEvent(down)
            runtime.surface.onTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }
    }
}

private class LifecycleFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val opened = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val saved = mutableListOf<ReadingPosition>()
    private var closing = false
    private val application = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as ViewerApplication
    private val episode = EpisodeId(SeriesId(SourceId("ntk"), "lifecycle-fixture"), "episode")
    private val source = object : ContentSource by application.graph.sources.require(episode.seriesId.sourceId) {
        override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) = Unit
        override suspend fun manifest(episodeId: EpisodeId) = EpisodeManifest(
            episodeId, "Lifecycle fixture", List(6) { index ->
                PageSpec(PageId.at(episodeId, index), index, PageDimensions(1080, 6000))
            },
        )
    }
    private val raw = object : RawPagePort {
        override suspend fun find(pageId: PageId): EncodedPageRef? = null
        override suspend fun fetch(
            pageId: PageId,
            priority: PageFetchPriority,
            responseStarted: () -> Unit,
        ): EncodedPageRef = awaitCancellation()
    }
    val runtime = createRuntime().also { it.open() }

    fun close() {
        if (closing) return
        closing = true
        runtime.close { closed.countDown() }
    }

    private fun createRuntime() = SessionViewerRuntime(
        context = application,
        scope = scope,
        sourceDispatcher = Dispatchers.IO,
        ioDispatcher = Dispatchers.IO,
        hardDecodeDispatcher = Dispatchers.Default,
        warmDecodeDispatcher = Dispatchers.Default,
        uploadDispatcher = Dispatchers.Default,
        source = source,
        rawPages = raw,
        episodeId = episode,
        loadPosition = { null },
        persistPosition = { saved += it },
        initialViewport = Viewport(FixedPx.fromPixels(1080), FixedPx.fromPixels(1800)),
        reportGestureBoundary = { _, _ -> },
        reportMotionFrame = { _, _ -> },
        reportOpened = { opened.countDown() },
        reportPresentedFrame = {},
        reportFailure = { throw AssertionError(it) },
    )
}
