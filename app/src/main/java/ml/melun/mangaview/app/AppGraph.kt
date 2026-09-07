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
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.cache.RawPageStore
import ml.melun.mangaview.data.cache.CompleteEpisodeSnapshotStore
import ml.melun.mangaview.data.db.DeferredViewerDatabase
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.data.settings.ViewerSettingsStoreFactory
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.ObservedSourceTransport
import ml.melun.mangaview.source.SourceExchangeObserver
import ml.melun.mangaview.source.SourceHttpMethod
import ml.melun.mangaview.source.SourceRequest
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.ntk.NtkConfig
import ml.melun.mangaview.source.ntk.NtkContentSource
import ml.melun.mangaview.source.ntk.NtkBrowserService
import ml.melun.mangaview.source.ntk.NtkBrowserIdentity
import ml.melun.mangaview.source.ntk.NtkWebViewAccessGateway
import ml.melun.mangaview.source.wfwf.WfwfConfig
import ml.melun.mangaview.source.wfwf.WfwfContentSource
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.viewer.runtime.PipelineRawPagePort
import ml.melun.mangaview.viewer.runtime.ViewerCachedResume
import ml.melun.mangaview.ui.library.SeriesArtworkLoader

internal data class ViewerDependencies(
    val source: ContentSource,
    val repository: PageRepository,
    val rawPages: RawPagePort,
    val sourceDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
    val loadPosition: suspend () -> ReadingPosition?,
    val persistPosition: (ReadingPosition) -> Unit,
    val saveBookmark: (ReadingPosition) -> Unit,
    val cachedResume: ViewerCachedResume,
)

internal class AppGraph(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val sourceDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {
    private val appContext = context.applicationContext
    @Volatile var networkEvidenceObserver: SourceExchangeObserver? = null
    val offlineStore = OfflineEpisodeStore(
        File(appContext.applicationInfo.dataDir, "app_offline_episodes_v2"),
        ioDispatcher,
    )
    private val database = DeferredViewerDatabase(appContext, ioDispatcher)
    private val transportFactory = OkHttpTransportFactory(ioDispatcher)
    private val ntkBrowserIdentity = NtkBrowserIdentity.forDevice(appContext, "primary")
    private val ntkGateway = NtkWebViewAccessGateway(
        appContext,
        userAgent(),
        ntkBrowserIdentity,
        NtkBrowserService::class.java,
    )
    private val ntkSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createNtkSource)
    private val wfwfSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createWfwfSource)
    val sources = SourceRegistry(
        registrations = listOf(
            SourceRegistration(NTK_ID, "NTK") {
                // Constructing the selected source is cheap. Actual browser/transport startup
                // is deferred until an operation needs it, so a complete cached resume stays
                // independent of provider work.
                OfflineContentSource(ntkSource.value, offlineStore)
            },
            SourceRegistration(WFWF_ID, "WFWF") {
                OfflineContentSource(wfwfSource.value, offlineStore)
            },
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
    val repository = PageRepository(applicationScope, sources::require, pageStore, offlineStore)
    private val resumeSnapshots = CompleteEpisodeSnapshotStore(
        File(appContext.applicationInfo.dataDir, "app_complete_resume_v1"), pageStore, ioDispatcher,
    )
    val offlineDownloads = OfflineDownloadManager(applicationScope, sources::require, repository, offlineStore)
    val userLibrary = UserLibraryRepository(
        dao = database.viewer,
        settingsStore = ViewerSettingsStoreFactory().open(
            appContext,
            applicationScope,
            ioDispatcher,
        ),
    )
    val artworkLoader = SeriesArtworkLoader(sources, ioDispatcher)
    val engine: EngineAppGraph by lazy {
        EngineAppGraph(appContext, applicationScope, sourceDispatcher, ioDispatcher, database, userLibrary, userAgent(),
            java.net.URI(DEFAULT_NTK_ORIGIN), { networkEvidenceObserver })
    }

    init {
        applicationScope.launch(ioDispatcher) { offlineStore.load() }
    }

    fun viewer(spec: ViewerLaunchSpec): ViewerDependencies {
        val source = sources.require(spec.sourceId)
        val rawPages = PipelineRawPagePort(source, pageStore, offlineStore)
        return ViewerDependencies(
            source = source,
            repository = repository,
            rawPages = rawPages,
            cachedResume = ViewerCachedResume(resumeSnapshots, rawPages),
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

    /**
     * Replays the pre-deferred NTK activation schedule for the debug startup comparison only.
     *
     * Production code never calls this method. Keeping the guard here prevents an accidental
     * release invocation from changing the activation policy, while the instrumentation APK can
     * compare both schedules against the same installed debug APK and app data.
     */
    internal fun activateNtkForStartupBenchmarkOnly() {
        check(appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Startup benchmark activation is available only in a debuggable application"
        }
        check(ntkSource.value.start()) {
            "NTK source was already activated; startup comparison requires a fresh target process"
        }
    }

    override fun close() {
        try {
            if (ntkSource.isInitialized()) ntkSource.value.close()
        } finally {
            try {
                if (wfwfSource.isInitialized()) wfwfSource.value.close()
            } finally {
                ntkGateway.close()
            }
        }
    }

    private fun createNtkSource(): DeferredContentSource = DeferredContentSource(
        id = NTK_ID,
        scope = applicationScope,
        start = CoroutineStart.LAZY,
        preInitializationPrepare = { episodeId, intent ->
            ntkGateway.prepare(DEFAULT_NTK_ORIGIN, episodeId.remoteKey, intent)
        },
        beforeFirstStart = { ntkGateway.warm(DEFAULT_NTK_ORIGIN) },
        initialize = ::initializeNtkSource,
    )

    private suspend fun initializeNtkSource(): DeferredSourceResource {
        coroutineContext.ensureActive()
        val transport = createNtkTransport()
        val documentTransport = ObservedSourceTransport(transportFactory.create(), "catalog-ntk-document") { networkEvidenceObserver }
        try {
            coroutineContext.ensureActive()
            val source = NtkContentSource(
                NtkConfig(
                    initialOrigin = DEFAULT_NTK_ORIGIN,
                    userAgent = userAgent(),
                    browserIdentity = ntkBrowserIdentity,
                ),
                transport,
                ntkGateway,
                documentTransport = documentTransport,
            )
            transport.warmConnections(listOf(DEFAULT_NTK_ORIGIN), preferQuic = false)
            transport.warmConnections(listOf(DEFAULT_NTK_ORIGIN), preferQuic = true)
            preconnectNtkDocumentOrigin(documentTransport)
            return DeferredSourceResource(source) {
                source.close()
                (transport as? Closeable)?.close()
                documentTransport.close()
            }
        } catch (failure: Throwable) {
            (transport as? Closeable)?.close()
            documentTransport.close()
            throw failure
        }
    }

    /**
     * HttpEngine construction alone does not resolve DNS or establish TLS. Open one bodyless H2
     * exchange while the library UI is loading so the first exact episode document does not pay
     * that cold connection cost. This is deliberately limited to the public document origin;
     * signed image URLs are never probed or consumed by connection warming.
     */
    private fun preconnectNtkDocumentOrigin(transport: SourceTransport) {
        applicationScope.launch(ioDispatcher) {
            runCatching {
                transport.execute(
                    SourceRequest(
                        url = DEFAULT_NTK_ORIGIN,
                        method = SourceHttpMethod.HEAD,
                        headers = mapOf("Accept" to "text/html,*/*;q=0.1"),
                        totalTimeoutMillis = NTK_PRECONNECT_TIMEOUT_MILLIS,
                        preferQuic = false,
                        priority = PageFetchPriority.BACKGROUND,
                    ),
                ).close()
            }
        }
    }

    private fun createNtkTransport(): SourceTransport = ObservedSourceTransport(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HttpEngineSourceTransport(appContext, userAgent())
        } else {
            transportFactory.create()
        }, "catalog-ntk", { networkEvidenceObserver })

    private fun createWfwfSource(): DeferredContentSource = DeferredContentSource(
        id = WFWF_ID,
        scope = applicationScope,
        start = CoroutineStart.LAZY,
        initialize = ::initializeWfwfSource,
    )

    private suspend fun initializeWfwfSource(): DeferredSourceResource {
        coroutineContext.ensureActive()
        val transport = createWfwfTransport()
        try {
            val source = WfwfContentSource(
                WfwfConfig(DEFAULT_WFWF_ORIGIN, userAgent()),
                transport,
                applicationScope,
            )
            transport.warmConnections(listOf(DEFAULT_WFWF_ORIGIN), preferQuic = false)
            source.warm()
            return DeferredSourceResource(source) {
                (transport as? Closeable)?.close()
            }
        } catch (failure: Throwable) {
            (transport as? Closeable)?.close()
            throw failure
        }
    }

    private fun createWfwfTransport(): SourceTransport = ObservedSourceTransport(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HttpEngineSourceTransport(
                appContext,
                userAgent(),
                protocolAlternatesEnabled = false,
            )
        } else {
            transportFactory.create()
        }, "catalog-wfwf", { networkEvidenceObserver })

    private fun userAgent(): String =
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    private companion object {
        const val DEFAULT_NTK_ORIGIN = "https://toki31.com"
        const val DEFAULT_WFWF_ORIGIN = ml.melun.mangaview.source.wfwf.DEFAULT_WFWF_ORIGIN
        const val NTK_PRECONNECT_TIMEOUT_MILLIS = 4_000L
        val NTK_ID = SourceId("ntk")
        val WFWF_ID = SourceId("wfwf")
    }
}
