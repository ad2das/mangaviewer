package ml.melun.mangaview

import android.app.Application
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ml.melun.mangaview.data.reset.LegacyDataResetter
import ml.melun.mangaview.app.AppGraph
import ml.melun.mangaview.app.AppWorkDispatchers
import ml.melun.mangaview.app.StartupMainThreadPolicy
import ml.melun.mangaview.source.ntk.NtkBrowserProcess
import ml.melun.mangaview.viewer.nativebridge.ViewerNativeBridge

class ViewerApplication : Application() {
    private val workDispatchers = AppWorkDispatchers()
    private val applicationScope = CoroutineScope(SupervisorJob() + workDispatchers.source)
    internal lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (NtkBrowserProcess.isCurrent(this)) return
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        graph = StartupMainThreadPolicy.detectUnexpectedDiskIo(debuggable) {
            AppGraph(this, applicationScope, workDispatchers.source, workDispatchers.io)
        }
        applicationScope.launch { ViewerNativeBridge.load() }
        applicationScope.launch(workDispatchers.io) {
            LegacyDataResetter(this@ViewerApplication, workDispatchers.io).runOnce()
        }
    }

    override fun onTerminate() {
        if (::graph.isInitialized) graph.close()
        applicationScope.cancel()
        workDispatchers.close()
        super.onTerminate()
    }
}
