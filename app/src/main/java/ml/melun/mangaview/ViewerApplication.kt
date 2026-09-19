package ml.melun.mangaview

import android.app.Application
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ml.melun.mangaview.app.AppGraph
import ml.melun.mangaview.app.AppWorkDispatchers
import ml.melun.mangaview.app.StartupMainThreadPolicy
import ml.melun.mangaview.source.ntk.NtkBrowserProcess
import ml.melun.mangaview.source.ntk.NtkWebViewStartup
import ml.melun.mangaview.source.ntk.NtkWebViewStartupOwner

class ViewerApplication : Application(), NtkWebViewStartupOwner,
    ml.melun.mangaview.source.ntk.NtkBrowserProxyOwner {
    override val ntkWebViewStartup = NtkWebViewStartup()
    private var browserNetwork: ml.melun.mangaview.app.NtkBrowserNetwork? = null
    override fun ntkBrowserProxyCredentials(host: String, realm: String) = browserNetwork?.credentials(host, realm)
    private val workDispatchers = AppWorkDispatchers()
    private val applicationScope = CoroutineScope(SupervisorJob() + workDispatchers.source)
    internal lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Exit-history scanning hits the binder and the filesystem; keep it off the startup path.
        applicationScope.launch(workDispatchers.io) { CrashLog.scanLastExit(this@ViewerApplication) }
        if (NtkBrowserProcess.isCurrent(this)) {
            NtkBrowserProcess.configureWebViewStorage(this)
            val network = ml.melun.mangaview.app.NtkBrowserNetwork().also { browserNetwork = it }
            ntkWebViewStartup.start(this, network::configure)
            return
        }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        graph = StartupMainThreadPolicy.detectUnexpectedDiskIo(debuggable) {
            AppGraph(this, applicationScope, workDispatchers.source, workDispatchers.io)
        }
        // Construct the engine graph (and its warmed renderer owner) before any reader launch so a
        // direct-entry startup does not pay graph construction inside the first-image window.
        graph.engine
        // The newxtoon replay browser takes seconds to spin up, so a proven user gets it for free
        // instead of behind the first catalog tap.
        graph.warmProtectedSources()
    }

    override fun onTerminate() {
        browserNetwork?.close()
        if (::graph.isInitialized) graph.close()
        applicationScope.cancel()
        workDispatchers.close()
        super.onTerminate()
    }
}
