package ml.melun.mangaview.viewer.runtime

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ml.melun.mangaview.content.ContentPipelineSnapshot
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.viewer.FixedPx
import ml.melun.mangaview.viewer.Viewport

/** Real SessionViewerRuntime and its single SurfaceView, isolated from product navigation. */
internal class SessionMemoryProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: FrameLayout
    lateinit var fixture: SessionMemoryFixture
        private set
    var runtime: SessionViewerRuntime? = null
        private set
    val failures = mutableListOf<Throwable>()
    val frames = mutableListOf<NativePresentationEvidence>()
    val terminalSnapshots = mutableListOf<ContentPipelineSnapshot>()
    private var closeLatch: CountDownLatch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture = SessionMemoryFixture(File(cacheDir, "memory-regression-${System.nanoTime()}"))
        host = FrameLayout(this)
        setContentView(host)
    }

    fun startCycle() {
        check(runtime == null && host.childCount == 0)
        frames.clear()
        closeLatch = null
        val initial = ReadingPosition(fixture.pages[2].id, FixedPx.fromPixels(137).units)
        runtime = SessionViewerRuntime(
            context = this, scope = scope, sourceDispatcher = Dispatchers.IO,
            ioDispatcher = Dispatchers.IO, hardDecodeDispatcher = Dispatchers.Default,
            warmDecodeDispatcher = Dispatchers.Default, uploadDispatcher = Dispatchers.Default,
            source = fixture, rawPages = fixture, episodeId = fixture.episode,
            loadPosition = { initial }, persistPosition = {},
            initialViewport = Viewport(FixedPx.fromPixels(host.width), FixedPx.fromPixels(host.height)),
            reportGestureBoundary = { _, _ -> }, reportMotionFrame = { _, _ -> },
            reportOpened = {}, reportPresentedFrame = { frames += it },
            reportFailure = { failures += it },
        ).also {
            host.addView(it.surface, FrameLayout.LayoutParams(-1, -1))
            it.open()
        }
    }

    fun readyForCycle(): Boolean = host.width > 0 && host.height > 0

    fun screenBounds(): Rect {
        val surface = requireNotNull(runtime).surface
        val point = IntArray(2)
        surface.getLocationOnScreen(point)
        return Rect(point[0], point[1], point[0] + surface.width, point[1] + surface.height)
    }

    fun closeCycle(): CountDownLatch {
        closeLatch?.let { return it }
        val finished = CountDownLatch(1).also { closeLatch = it }
        val closing = runtime ?: return finished.also { it.countDown() }
        closing.close {
            runOnUiThread {
                terminalSnapshots += closing.resourceSnapshot()
                host.removeView(closing.surface)
                runtime = null
                finished.countDown()
            }
        }
        return finished
    }

    override fun onDestroy() {
        val closing = runtime
        if (closing == null) scope.cancel() else closing.close { scope.cancel() }
        super.onDestroy()
    }
}
