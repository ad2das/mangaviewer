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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.PageRepository
import ml.melun.mangaview.data.cache.RawPageStore
import ml.melun.mangaview.data.cache.CompleteEpisodeSnapshotStore
import ml.melun.mangaview.data.cache.HomeCatalogSnapshotStore
import ml.melun.mangaview.data.db.DeferredViewerDatabase
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.data.network.ProviderImageTransport
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.data.offline.OfflineDownloadManager
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.data.settings.ViewerSettingsStoreFactory
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.SourceTransport
import ml.melun.mangaview.source.ObservedSourceTransport
import ml.melun.mangaview.source.SourceExchangeObserver
import ml.melun.mangaview.source.ntk.NtkConfig
import ml.melun.mangaview.source.ntk.NtkContentSource
import ml.melun.mangaview.source.ntk.NtkBrowserService
import ml.melun.mangaview.source.ntk.NtkBrowserIdentity
import ml.melun.mangaview.source.ntk.NtkWebViewAccessGateway
import ml.melun.mangaview.source.wfwf.WfwfConfig
import ml.melun.mangaview.source.wfwf.WfwfContentSource
import ml.melun.mangaview.source.wfwf.WfwfOriginResolver
import ml.melun.mangaview.source.newxtoon.NewxtoonConfig
import ml.melun.mangaview.source.newxtoon.NewxtoonContentSource
import ml.melun.mangaview.source.goodtoon.GoodtoonConfig
import ml.melun.mangaview.source.goodtoon.GoodtoonContentSource
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
    /** Remembers the last delivered home rows so the home tab can paint before the network. */
    val homeCatalogCache = HomeCatalogSnapshotStore(
        File(appContext.applicationInfo.dataDir, "app_home_catalog_cache_v1"),
        ioDispatcher,
    )
    val episodeCatalogCache = ml.melun.mangaview.data.cache.EpisodeCatalogSnapshotStore(
        File(appContext.applicationInfo.dataDir, "app_episode_catalog_cache_v1"), ioDispatcher,
    )
    private val database = DeferredViewerDatabase(appContext, ioDispatcher)
    private val transportFactory = OkHttpTransportFactory(ioDispatcher)
    private val origins by lazy { ProviderOriginDirectory(appContext, ioDispatcher, userAgent()) }
    private fun resilient(transport: SourceTransport) = ProviderOriginTransport(transportFactory.protect(transport), origins)
    private val ntkBrowserIdentity by lazy { NtkBrowserIdentity.forDevice(appContext, "primary") }
    private val ntkGateway = NtkWebViewAccessGateway(
        appContext,
        userAgent(),
        { ntkBrowserIdentity },
        NtkBrowserService::class.java,
    )
    private val ntkSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createNtkSource)
    private val wfwfSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createWfwfSource)
    private val newxtoonSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createNewxtoonSource)
    private val newxtoonClearanceLazy = lazy { NewxtoonClearance(appContext) }
    private val newxtoonClearance by newxtoonClearanceLazy
    internal val newxtoonClearanceState: NewxtoonClearance get() = newxtoonClearance
    private val goodtoonSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createGoodtoonSource)
    val sources = SourceRegistry(
        registrations = listOf(
            SourceRegistration(NTK_ID, "NTK") {
                // Constructing the selected source is cheap. Actual browser/transport startup
                // is deferred until an operation needs it, so a complete cached resume stays
                // independent of provider work.
                OfflineContentSource(ntkSource.value, offlineStore)
            },
            SourceRegistration(WFWF_ID, "WFWF", searchMode = SearchMode.TITLE) {
                OfflineContentSource(wfwfSource.value, offlineStore)
            },
            SourceRegistration(NEWXTOON_ID, "뉴엑스툰", distinguishesKinds = false, searchMode = SearchMode.COMBINED) {
                OfflineContentSource(newxtoonSource.value, offlineStore)
            },
            SourceRegistration(GOODTOON_ID, "굿툰", distinguishesKinds = false, searchMode = SearchMode.COMBINED) {
                OfflineContentSource(goodtoonSource.value, offlineStore)
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
    private val settingsStore = ViewerSettingsStoreFactory().open(
        appContext,
        applicationScope,
        ioDispatcher,
    )
    val userLibrary = UserLibraryRepository(
        dao = database.viewer,
        settingsStore = settingsStore,
    )
    val artworkLoader = SeriesArtworkLoader(sources, ioDispatcher, applicationScope)
    val account = ml.melun.mangaview.account.AccountSync(appContext, applicationScope, ioDispatcher,
        ml.melun.mangaview.account.LocalCloudLibrary(database::database)) { series ->
            sources.require(series.sourceId).episodes(series).items
        }
    val engine: EngineAppGraph by lazy {
        EngineAppGraph(appContext, applicationScope, sourceDispatcher, ioDispatcher, database, userLibrary, userAgent(),
            java.net.URI(DEFAULT_NTK_ORIGIN), offlineStore, { networkEvidenceObserver }, origins, newxtoonClearanceLazy,
            { newxtoonClearance.sourceUserAgent })
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

    internal fun activateNtkForStartupBenchmarkOnly() =
        activateNtkStartupBenchmark(appContext) { ntkSource.value }

    override fun close() {
        try {
            if (ntkSource.isInitialized()) ntkSource.value.close()
        } finally {
            try {
                if (wfwfSource.isInitialized()) wfwfSource.value.close()
            } finally {
                try {
                    if (newxtoonSource.isInitialized()) newxtoonSource.value.close()
                } finally {
                    try {
                        if (goodtoonSource.isInitialized()) goodtoonSource.value.close()
                    } finally {
                        ntkGateway.close()
                    }
                }
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
        // The browser warm runs from [primeAfterFirstFrame] instead of the first source
        // activation: a home refresh must not spawn the isolated WebView process inside the
        // launch frames, and the viewer's resolve path starts the browser on demand anyway.
        initialize = ::initializeNtkSource,
    )

    private suspend fun initializeNtkSource(): DeferredSourceResource {
        coroutineContext.ensureActive()
        val initialOrigin = seededProviderOrigin(origins, "ntk", DEFAULT_NTK_ORIGIN)
        val transport = createNtkTransport()
        val documentTransport = ObservedSourceTransport(resilient(transportFactory.create()), "catalog-ntk-document") { networkEvidenceObserver }
        // Cover artwork sits on an image CDN whose chain the platform cannot build, so those hosts
        // leave through a relaxed client while every other host keeps the normal transport.
        val artworkTransport = ProviderImageTransport(documentTransport, transportFactory.createForProviderImages())
        try {
            coroutineContext.ensureActive()
            val source = NtkContentSource(
                NtkConfig(
                    initialOrigin = initialOrigin,
                    userAgent = userAgent(),
                    browserIdentity = ntkBrowserIdentity,
                ),
                transport,
                ntkGateway,
                documentTransport = documentTransport,
                artworkTransport = artworkTransport,
            )
            transport.warmConnections(listOf(initialOrigin), preferQuic = false)
            transport.warmConnections(listOf(initialOrigin), preferQuic = true)
            preconnectOrigin(applicationScope, ioDispatcher, documentTransport, initialOrigin, ORIGIN_PRECONNECT_TIMEOUT_MILLIS)
            return DeferredSourceResource(source) {
                source.close()
                (transport as? Closeable)?.close()
                documentTransport.close()
                artworkTransport.close()
            }
        } catch (failure: Throwable) {
            (transport as? Closeable)?.close()
            documentTransport.close()
            artworkTransport.close()
            throw failure
        }
    }

    private fun createNtkTransport(): SourceTransport = ObservedSourceTransport(
        resilient(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HttpEngineSourceTransport(appContext, userAgent())
        } else {
            transportFactory.create()
        }), "catalog-ntk", { networkEvidenceObserver })

    private fun createWfwfSource(): DeferredContentSource = DeferredContentSource(
        id = WFWF_ID,
        scope = applicationScope,
        start = CoroutineStart.LAZY,
        initialize = ::initializeWfwfSource,
    )

    private suspend fun initializeWfwfSource(): DeferredSourceResource {
        coroutineContext.ensureActive()
        val initialOrigin = seededProviderOrigin(origins, "wfwf", DEFAULT_WFWF_ORIGIN)
        val transport = createWfwfTransport()
        // Origin discovery must reach the mirror it names; the directory-rewriting transport would
        // fold every probe back onto the persisted origin, which can itself be a moved-address stub.
        val probeTransport = createWfwfRawTransport()
        try {
            val source = WfwfContentSource(
                WfwfConfig(initialOrigin, userAgent()),
                transport,
                applicationScope,
                originResolver = WfwfOriginResolver(probeTransport, userAgent(), probeParallelism = 4,
                    onProbe = { android.util.Log.i("WfwfOrigin", it) }),
                onOriginResolved = { origins.remember("wfwf", it) },
            )
            transport.warmConnections(listOf(initialOrigin), preferQuic = false)
            source.warm()
            preconnectOrigin(applicationScope, ioDispatcher, transport, initialOrigin, ORIGIN_PRECONNECT_TIMEOUT_MILLIS)
            return DeferredSourceResource(source) {
                (transport as? Closeable)?.close()
                (probeTransport as? Closeable)?.close()
            }
        } catch (failure: Throwable) {
            (transport as? Closeable)?.close()
            (probeTransport as? Closeable)?.close()
            throw failure
        }
    }

    private fun createWfwfRawTransport(): SourceTransport =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HttpEngineSourceTransport(
                appContext,
                userAgent(),
                protocolAlternatesEnabled = false,
            )
        } else {
            transportFactory.create()
        }

    private fun createWfwfTransport(): SourceTransport = ObservedSourceTransport(
        resilient(createWfwfRawTransport()), "catalog-wfwf", { networkEvidenceObserver })

    private fun createNewxtoonSource(): DeferredContentSource = DeferredContentSource(
        id = NEWXTOON_ID,
        scope = applicationScope,
        start = CoroutineStart.LAZY,
        initialize = ::initializeNewxtoonSource,
    )

    /**
     * Runs once the library's first frame is on screen: starts account restore, forces the reader
     * engine graph, whose construction warms the GL renderer and preconnects the provider origins,
     * then warms only the provider the reader is most likely to open next — the newxtoon catalog or
     * the NTK browser process — when that was the source selected last session. Everything here is
     * deferred past the launch frames; a user who never opens that provider pays nothing for it.
     */
    fun primeAfterFirstFrame() {
        account.activate()
        // Building the engine graph creates the work coordinator, the OkHttp clients, a decode
        // thread and the native decoder before it can warm the GL renderer. That is warming work,
        // not launch work, so it is built off the main thread: the launch frames and the first
        // scroll never wait behind it, and a reader that opens later claims the same graph once
        // construction has long finished.
        applicationScope.launch(ioDispatcher) {
            engine
            val lastSource = runCatching { settingsStore.settings.first() }.getOrNull()?.sourceKey
            when (lastSource) {
                NEWXTOON_ID.value -> prefetchNewxtoonCatalog()
                NTK_ID.value -> ntkGateway.warm(DEFAULT_NTK_ORIGIN)
            }
        }
    }

    /**
     * Preloads the catalog landing documents into the disk cache so the first catalog paint never
     * waits on the fetch bridge. Documents ride the worker route, so no clearance work happens
     * here; the clearance fallback solves on demand only if the worker refuses a document.
     */
    fun prefetchNewxtoonCatalog() {
        applicationScope.launch {
            // A reader session for another source owns the main thread; defer the speculative
            // catalog prefetch instead of stalling that reader.
            ViewerSessionActivity.awaitForeignIdle(NEWXTOON_ID.value)
            val source = runCatching { newxtoonSource.value }.getOrNull() ?: return@launch
            val latestStarted = System.nanoTime()
            runCatching {
                source.catalog(ml.melun.mangaview.source.CatalogQuery(
                    ml.melun.mangaview.source.SeriesKind.COMIC,
                    ml.melun.mangaview.source.CatalogOrder.LATEST))
            }
            android.util.Log.i("NewxtoonWarm", "catalog latest ms=${(System.nanoTime() - latestStarted) / 1_000_000} " +
                "native=${!newxtoonClearance.solvedViewReady}")
            ViewerSessionActivity.awaitForeignIdle(NEWXTOON_ID.value)
            runCatching {
                source.catalog(ml.melun.mangaview.source.CatalogQuery(
                    ml.melun.mangaview.source.SeriesKind.COMIC,
                    ml.melun.mangaview.source.CatalogOrder.POPULAR))
            }
        }
    }

    private suspend fun initializeNewxtoonSource(): DeferredSourceResource {
        coroutineContext.ensureActive()
        val transport = newxtoonTransport(newxtoonClearance, transportFactory) { networkEvidenceObserver }
        try {
            val source = NewxtoonContentSource(NewxtoonConfig(userAgent = newxtoonClearance.sourceUserAgent), transport,
                speculationScope = applicationScope)
            transport.warmConnections(listOf(ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN), preferQuic = false)
            // No speculative challenge or replay browser is stood up: documents ride the worker
            // route without clearance, and the clearance fallback solves on demand if the worker
            // ever refuses a document.
            return DeferredSourceResource(source) {
                (transport as? Closeable)?.close()
            }
        } catch (failure: Throwable) {
            (transport as? Closeable)?.close()
            throw failure
        }
    }

    private fun createGoodtoonSource(): DeferredContentSource = DeferredContentSource(
        id = GOODTOON_ID,
        scope = applicationScope,
        start = CoroutineStart.LAZY,
        initialize = ::initializeGoodtoonSource,
    )

    private suspend fun initializeGoodtoonSource(): DeferredSourceResource {
        coroutineContext.ensureActive()
        val initialOrigin = seededProviderOrigin(origins, "goodtoon", DEFAULT_GOODTOON_ORIGIN)
        val transport = createGoodtoonTransport()
        try {
            val source = GoodtoonContentSource(
                GoodtoonConfig(initialOrigin, userAgent()),
                transport,
                applicationScope,
                originProbeObserver = { android.util.Log.i("GoodtoonOrigin", it) },
                onOriginResolved = { origins.remember("goodtoon", it) },
            )
            transport.warmConnections(listOf(initialOrigin), preferQuic = false)
            source.warm()
            preconnectOrigin(applicationScope, ioDispatcher, transport, initialOrigin, ORIGIN_PRECONNECT_TIMEOUT_MILLIS)
            return DeferredSourceResource(source) {
                (transport as? Closeable)?.close()
            }
        } catch (failure: Throwable) {
            (transport as? Closeable)?.close()
            throw failure
        }
    }

    private fun createGoodtoonTransport(): SourceTransport = ObservedSourceTransport(
        resilient(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HttpEngineSourceTransport(
                appContext,
                userAgent(),
                protocolAlternatesEnabled = false,
            )
        } else {
            transportFactory.create()
        }), "catalog-goodtoon", { networkEvidenceObserver })

    private fun userAgent(): String =
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    private companion object {
        const val DEFAULT_NTK_ORIGIN = ml.melun.mangaview.source.ntk.NtkOriginResolver.DEFAULT_ORIGIN
        const         val DEFAULT_WFWF_ORIGIN = ml.melun.mangaview.source.wfwf.DEFAULT_WFWF_ORIGIN
        val DEFAULT_GOODTOON_ORIGIN = ml.melun.mangaview.source.goodtoon.DEFAULT_GOODTOON_ORIGIN
        const val ORIGIN_PRECONNECT_TIMEOUT_MILLIS = 4_000L
        val NTK_ID = SourceId("ntk")
        val WFWF_ID = SourceId("wfwf")
        val NEWXTOON_ID = SourceId("newxtoon")
        val GOODTOON_ID = SourceId("goodtoon")
    }
}

/**
 * Seeds a catalog source from the verified persisted origin so warm-up and re-discovery start at
 * the known address instead of the shipped default. Falls back when nothing valid is stored.
 */
internal suspend fun seededProviderOrigin(origins: ProviderOrigins, provider: String, fallback: String): String =
    try {
        origins.current(provider, fallback)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        fallback
    }

/**
 * Builds the newxtoon transport around the clearance's persistent browser identity. Cloudflare
 * binds the clearance cookie to the client hints the solving WebView sent, so every route —
 * direct and SNI recovery — repeats them and document reads replay through the same browser.
 */
private fun newxtoonTransport(
    clearance: NewxtoonClearance,
    transportFactory: OkHttpTransportFactory,
    observer: () -> SourceExchangeObserver?,
): SourceTransport {
    val hints = clearance.clientHints
    val browserHeaders = OkHttpTransportFactory.browserHeaders(hints)
    val clearanceDocuments = NewxtoonClearanceTransport(
        // The relay shapes the TLS record layer exactly like the challenge browser, so the
        // edge treats the native request as the same client that owns the clearance instead
        // of drawing a fresh challenge for a plain ClientHello.
        transportFactory.createRelayed(clearance.cookieJar, browserHeaders),
        ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN,
        clearance::solve,
        clearance::solveFresh,
        clearance::fetchPage,
        clearance::solvedViewReady,
        clearance::clearanceVerified,
        clearance::markReplayRefused,
        clearance.documents,
        clearance.refreshScope,
    )
    // Documents ride the worker first, whose subrequests are not challenged, so the catalog and
    // chapter pages open without any clearance; the clearance route stays behind it as fallback.
    val documents = NewxtoonWorkerTransport(transportFactory.create(), clearanceDocuments)
    // Artwork never enters the clearance path: the pull zone serves it to the app directly as
    // long as the origin rides along as the referer, so no Cloudflare hop is involved.
    val images = transportFactory.createForBunnyImages(
        headers = linkedMapOf(
            "Referer" to ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN + "/",
            "User-Agent" to clearance.sourceUserAgent,
        ),
    )
    return ObservedSourceTransport(NewxtoonImageTransport(documents, images), "catalog-newxtoon", observer)
}

