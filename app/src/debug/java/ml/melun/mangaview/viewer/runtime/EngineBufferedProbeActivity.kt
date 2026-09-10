package ml.melun.mangaview.viewer.runtime

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import java.util.concurrent.CompletableFuture

/** A real window for compositor lifecycle and final-screen pixel verification. */
internal class EngineBufferedProbeActivity : Activity(), SurfaceHolder.Callback {
    val ready = CompletableFuture<SurfaceView>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hideNavigation = intent.getBooleanExtra("engineHideNavigationForTiming", false)
        if (hideNavigation) {
            window.setDecorFitsSystemWindows(false)
        }
        val surface = SurfaceView(this).also { it.holder.addCallback(this) }
        val root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val navigation = if (hideNavigation) insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()) else bars
            view.setPadding(maxOf(bars.left, navigation.left), bars.top,
                maxOf(bars.right, navigation.right), maxOf(bars.bottom, navigation.bottom))
            insets
        }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (hideNavigation) window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.navigationBars())
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        ready.complete(root.getChildAt(0) as SurfaceView)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}
