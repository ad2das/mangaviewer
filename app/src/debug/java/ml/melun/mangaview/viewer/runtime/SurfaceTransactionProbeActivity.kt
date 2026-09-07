package ml.melun.mangaview.viewer.runtime

import android.app.Activity
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CompletableFuture

internal object SurfaceTransactionProbe {
    init { System.loadLibrary("viewer_native") }
    external fun run(surface: Surface, width: Int, height: Int): LongArray?
    external fun pendingCallbacks(): Int
}

class SurfaceTransactionProbeActivity : Activity(), SurfaceHolder.Callback {
    val ready = CompletableFuture<Triple<Surface, Int, Int>>()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(SurfaceView(this).apply { holder.addCallback(this@SurfaceTransactionProbeActivity) })
    }
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) ready.complete(Triple(holder.surface, width, height))
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}
