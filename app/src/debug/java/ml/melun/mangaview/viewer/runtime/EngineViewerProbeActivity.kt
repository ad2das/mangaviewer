package ml.melun.mangaview.viewer.runtime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.app.AndroidWorkDispatcher
import ml.melun.mangaview.engine.api.EngineRuntimeSnapshot
import ml.melun.mangaview.engine.api.EngineViewport

/** Live vertical-slice host. Not a corpus or performance qualification entry point. */
class EngineViewerProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decode = AndroidWorkDispatcher("engine-decode", 2)
    private val image = CountDownLatch(1)
    private val viewport = CountDownLatch(1)
    private val ended = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    @Volatile internal var latest: EngineRuntimeSnapshot? = null
    @Volatile internal var lastFrame: EngineSurfacePresentation? = null
    @Volatile internal var firstImageAtNanos: Long = 0
    @Volatile internal var firstViewportAtNanos: Long = 0
    private lateinit var runtime: EngineViewerRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = ViewerLaunchSpec.from(intent)
        val graph = (application as ViewerApplication).graph.engine
        val metrics = resources.displayMetrics
        val error = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK) }
        runtime = EngineViewerRuntime(this, scope, graph.coordinator, graph.session(spec), graph.positions,
            spec.episodeId, EngineViewport(metrics.widthPixels, metrics.heightPixels), decode.coroutineDispatcher,
            { latest = it }, { frame ->
                lastFrame = frame
                if (frame.swapSucceeded && frame.scene.placements.isNotEmpty() && firstImageAtNanos == 0L) {
                    firstImageAtNanos = frame.submittedAtNanos
                    image.countDown()
                }
                if (frame.swapSucceeded && frame.scene.completeCoverage && firstViewportAtNanos == 0L) {
                    firstViewportAtNanos = frame.submittedAtNanos
                    viewport.countDown()
                }
            }, { problem ->
                failure.compareAndSet(null, problem)
                error.text = problem.stackTraceToString()
                image.countDown()
                viewport.countDown()
            })
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(runtime.surface, FrameLayout.LayoutParams(-1, -1))
            addView(error, FrameLayout.LayoutParams(-1, -2))
        })
        runtime.open()
    }

    override fun onStart() { super.onStart(); runtime.enterForeground() }
    override fun onStop() { runtime.enterBackground(); super.onStop() }
    override fun onDestroy() {
        scope.launch(NonCancellable) {
            try { runtime.close() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            finally { decode.close(); scope.cancel(); ended.countDown() }
        }
        super.onDestroy()
    }

    fun awaitImage(seconds: Long): Boolean = image.await(seconds, TimeUnit.SECONDS)
    fun awaitViewport(seconds: Long): Boolean = viewport.await(seconds, TimeUnit.SECONDS)
    fun awaitClosed(seconds: Long): Boolean = ended.await(seconds, TimeUnit.SECONDS)
    fun failure(): Throwable? = failure.get()
}
