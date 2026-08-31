package ml.melun.mangaview.app

import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.cache.RawPageStore
import ml.melun.mangaview.data.db.DeferredViewerDatabase
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.data.settings.ViewerSettingsStoreFactory
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.ntk.NtkConfig
import ml.melun.mangaview.source.ntk.NtkContentSource
import ml.melun.mangaview.source.ntk.NtkAccessGatewayPool
import ml.melun.mangaview.source.ntk.NtkBrowserServiceSecondary
import ml.melun.mangaview.source.ntk.NtkWebViewAccessGateway
import ml.melun.mangaview.source.wfwf.WfwfConfig
import ml.melun.mangaview.source.wfwf.WfwfContentSource
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec

internal data class ViewerDependencies(
    val source: ContentSource,
    val repository: PageRepository,
    val sourceDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
    val loadPosition: suspend () -> ReadingPosition?,
    val persistPosition: (ReadingPosition) -> Unit,
    val saveBookmark: (ReadingPosition) -> Unit,
)

internal class AppGraph(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val sourceDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {
    private val appContext = context.applicationContext
    private val database = DeferredViewerDatabase(appContext, ioDispatcher)
    private val transportFactory = OkHttpTransportFactory(ioDispatcher)
    private val ntkBrowserGateways = listOf(
        NtkWebViewAccessGateway(appContext, userAgent()),
        NtkWebViewAccessGateway(
            appContext,
            userAgent(),
            NtkBrowserServiceSecondary::class.java,
        ),
    )
    private val ntkGateway = NtkAccessGatewayPool(ntkBrowserGateways)
    private val ntkSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createNtkSource)
    val sources = SourceRegistry(
        registrations = listOf(
            SourceRegistration(NTK_ID, "NTK") {
                ntkBrowserGateways.forEach(NtkWebViewAccessGateway::warm)
                ntkSource.value.also { it.start() }
            },
            SourceRegistration(WFWF_ID, "WFWF", ::createWfwfSource),
        ),
    )
    private val pageStore = RawPageStore(
        // RawPageStore creates this private directory on its IO dispatcher at the first write.
        // Context.getDir() would perform that filesystem work while Application.onCreate is
        // constructing the graph on the main thread.
        root = File(appContext.applicationInfo.dataDir, "app_viewer_page_cache_v2"),
        dao = database.rawPages,
        ioDispatcher = ioDispatcher,
    )
    private val pageRepository = PageRepository(applicationScope, sources::require, pageStore)
    val userLibrary = UserLibraryRepository(
        dao = database.viewer,
        settingsStore = ViewerSettingsStoreFactory().open(
            appContext,
            applicationScope,
            ioDispatcher,
        ),
    )

    init {
        // Users reach NTK through the library, so starting its browser acknowledgement runtime
        // and transport while the app shell is appearing removes process/engine construction
        // from the first visible page without delaying or blocking the UI.
        ntkBrowserGateways.forEach(NtkWebViewAccessGateway::warm)
        ntkSource.value.start()
    }

    fun viewer(spec: ViewerLaunchSpec): ViewerDependencies {
        val source = sources.require(spec.sourceId)
        return ViewerDependencies(
            source = source,
            repository = pageRepository,
            sourceDispatcher = sourceDispatcher,
            ioDispatcher = ioDispatcher,
            loadPosition = {
                spec.initialPosition ?: userLibrary.readingPosition(spec.episodeId)
            },
            persistPosition = { position ->
                applicationScope.launch(ioDispatcher) {
                    userLibrary.saveProgress(position.pageId, position.offsetInPageUnits)
                }
            },
            saveBookmark = { position ->
                applicationScope.launch(ioDispatcher) {
                    userLibrary.addBookmark(position.pageId, position.offsetInPageUnits)
                }
            },
        )
    }

    override fun close() {
        try {
            if (ntkSource.isInitialized()) ntkSource.value.close()
        } finally {
            ntkGateway.close()
        }
    }

    private fun createNtkSource(): DeferredContentSource = DeferredContentSource(
        id = NTK_ID,
        scope = applicationScope,
        start = CoroutineStart.LAZY,
        initialize = ::initializeNtkSource,
    )

    private suspend fun initializeNtkSource(): DeferredSourceResource {
        coroutineContext.ensureActive()
        val transport = createNtkTransport()
        try {
            coroutineContext.ensureActive()
            val source = NtkContentSource(
                NtkConfig(DEFAULT_NTK_ORIGIN, userAgent()),
                transport,
                ntkGateway,
                prefetchScope = applicationScope,
            )
            return DeferredSourceResource(source) {
                (transport as? Closeable)?.close()
            }
        } catch (failure: Throwable) {
            (transport as? Closeable)?.close()
            throw failure
        }
    }

    private fun createNtkTransport(): SourceTransport =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HttpEngineSourceTransport(appContext, userAgent())
        } else {
            transportFactory.create()
        }

    private fun createWfwfSource(): ContentSource = WfwfContentSource(
        WfwfConfig(DEFAULT_WFWF_ORIGIN, userAgent()),
        transportFactory.create(),
        applicationScope,
    )

    private fun userAgent(): String =
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    private companion object {
        const val DEFAULT_NTK_ORIGIN = "https://toki31.com"
        const val DEFAULT_WFWF_ORIGIN = "https://wfwf487.com"
        val NTK_ID = SourceId("ntk")
        val WFWF_ID = SourceId("wfwf")
    }
}
