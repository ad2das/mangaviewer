package ml.melun.mangaview.viewer.runtime

import android.app.Activity
import android.os.Bundle
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.engine.api.EngineViewport

/** Synthetic empty-scene EGL cost control. It cannot qualify source pixels or real content. */
internal class EngineSwapBaselineActivity : Activity(), SurfaceHolder.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val records = mutableListOf<EngineSurfacePresentation>()
    private val delivered = mutableMapOf<Long, Long>()
    private val errors = mutableListOf<Throwable>()
    val ready = CompletableDeferred<Unit>()
    private lateinit var owner: EngineSurfaceOwner
    private var viewport = EngineViewport(1, 1)
    private var attached = false
    private var running = false
    private var closed = false
    private var revision = 0L
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            owner.offer(EngineSurfaceScene(1, 1, ++revision, 1, viewport, null, emptyList()))
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner = EngineSurfaceOwner(1024L * 1024, { frame ->
            synchronized(records) {
                check(records.size < 4096) { "Baseline observation capacity exceeded" }
                records += frame
                delivered[frame.identity.token] = System.nanoTime()
            }
        }, { failure -> synchronized(errors) { errors += failure }; ready.completeExceptionally(failure) }, {},
            maximumPendingForVerification = intent.getIntExtra("baselineMaximumPending", 0).takeIf { it > 0 },
            presentationPollMillisForVerification = intent.getLongExtra("baselinePollMillis", 0).takeIf { it > 0 })
        val width = intent.getIntExtra("baselineWidth", 1080)
        val height = intent.getIntExtra("baselineHeight", 2138)
        require(width > 0 && height > 0)
        viewport = EngineViewport(width, height)
        val surface = SurfaceView(this).apply { holder.addCallback(this@EngineSwapBaselineActivity) }
        setContentView(FrameLayout(this).apply { addView(surface, FrameLayout.LayoutParams(width, height)) })
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (attached || closed) return
        require(width == viewport.widthPx && height == viewport.heightPx)
        attached = true
        scope.launch {
            try {
                check(owner.attach(holder.surface, width, height, 60F))
                owner.setSwapIntervalForVerification(intent.getIntExtra("baselineSwapInterval", 1))
                ready.complete(Unit)
            } catch (failure: Throwable) { ready.completeExceptionally(failure) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        Choreographer.getInstance().removeFrameCallback(tick)
        if (attached && !closed) runBlocking { owner.detach() }
        attached = false
    }

    fun start() {
        check(attached && ready.isCompleted && !closed && !running)
        running = true
        Choreographer.getInstance().postFrameCallback(tick)
    }

    suspend fun stopAndClose() {
        if (closed) return
        running = false
        Choreographer.getInstance().removeFrameCallback(tick)
        owner.close()
        closed = true
        check(owner.closedSubmissionCount == synchronized(records) { records.size.toLong() })
        synchronized(errors) { errors.firstOrNull()?.let { throw it } }
    }

    fun snapshot(): List<EngineSurfacePresentation> = synchronized(records) { records.toList() }
    fun deliveredAt(token: Long): Long = synchronized(records) { delivered.getValue(token) }

    override fun onDestroy() {
        try { runBlocking { stopAndClose() } } finally { scope.cancel(); super.onDestroy() }
    }
}
