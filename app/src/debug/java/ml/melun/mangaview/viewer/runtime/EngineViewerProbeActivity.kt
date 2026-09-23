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
    private val diagnostics = EngineViewerDiagnostics()
    private lateinit var runtime: EngineViewerRuntime

    /** One logcat line per transport phase so request timings are attributable per site. */
    private val networkObserver = ml.melun.mangaview.source.SourceExchangeObserver { evidence ->
        android.util.Log.i("ViewerProbeNet", "id=${evidence.requestId} phase=${evidence.phase} at=${evidence.atNanos} " +
            "status=${evidence.statusCode ?: 0} bytes=${evidence.bodyBytes} url=${evidence.requestUrl}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = ViewerLaunchSpec.from(intent)
        val appGraph = (application as ViewerApplication).graph
        appGraph.networkEvidenceObserver = networkObserver
        val graph = appGraph.engine
        val metrics = resources.displayMetrics
        val error = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK) }
        diagnostics.opened(System.nanoTime())
        runtime = EngineViewerRuntime(this, scope, graph.coordinator, graph.session(spec), graph.positions,
            spec.episodeId, EngineViewport(metrics.widthPixels, metrics.heightPixels), { decode.coroutineDispatcher },
            { latest = it; diagnostics.snapshot(it, System.nanoTime()) }, { frame ->
                lastFrame = frame
                diagnostics.presented(frame)
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
            }, reportRendererClosed = { rendererId, submittedCount, atNanos ->
                diagnostics.rendererClosed(rendererId, submittedCount, atNanos)
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
            finally {
                logStartupTiming()
                decode.close(); scope.cancel(); ended.countDown()
            }
        }
        super.onDestroy()
    }

    /** One logcat line per run: milestone nanos for manifest / page / decode / present attribution. */
    private fun logStartupTiming() {
        val timing = diagnostics.startup() ?: return
        android.util.Log.i("ViewerProbeTiming", listOf(
            "page=${timing.presentedPageKey}",
            "opened=${timing.openStartedAtNanos}",
            "manifest=${timing.manifestReadyAtNanos ?: 0}",
            "submitted=${timing.firstActualSubmittedAtNanos ?: 0}",
            "presented=${timing.firstActualPresentedAtNanos ?: 0}",
            "viewport=${timing.firstCompleteViewportSubmittedAtNanos ?: 0}",
        ).joinToString(" "))
    }

    fun awaitImage(seconds: Long): Boolean = image.await(seconds, TimeUnit.SECONDS)
    fun awaitViewport(seconds: Long): Boolean = viewport.await(seconds, TimeUnit.SECONDS)
    fun awaitClosed(seconds: Long): Boolean = ended.await(seconds, TimeUnit.SECONDS)
    fun failure(): Throwable? = failure.get()
}
