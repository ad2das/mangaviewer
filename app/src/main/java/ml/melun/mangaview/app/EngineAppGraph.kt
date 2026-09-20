package ml.melun.mangaview.app

import android.content.Context
import android.os.Build
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ml.melun.mangaview.data.db.DeferredViewerDatabase
import ml.melun.mangaview.data.engine.EnginePositionStore
import ml.melun.mangaview.data.engine.EngineRawStorage
import ml.melun.mangaview.data.engine.RoomEnginePublicationIndex
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.engine.api.EnginePositionPort
import ml.melun.mangaview.engine.api.EpisodePlanObserver
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.wfwf.DEFAULT_WFWF_ORIGIN
import ml.melun.mangaview.source.goodtoon.DEFAULT_GOODTOON_ORIGIN
import ml.melun.mangaview.source.ntk.NtkBrowserIdentity
import ml.melun.mangaview.source.ntk.NtkEngineBrowserClient
import ml.melun.mangaview.source.ntk.NtkEngineAuthorization
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.source.ObservedSourceTransport
import ml.melun.mangaview.source.SourceExchangeObserver

internal class EngineAppGraph(
    context: Context,
    scope: CoroutineScope,
    private val parsingDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
    database: DeferredViewerDatabase,
    private val library: UserLibraryRepository,
    private val userAgent: String,
    private val ntkOrigin: URI,
    private val offlineEpisodes: OfflineEpisodeStore,
    networkEvidenceObserver: () -> SourceExchangeObserver? = { null },
    private val origins: ProviderOriginDirectory = ProviderOriginDirectory(context, ioDispatcher, userAgent),
    private val newxtoonClearance: NewxtoonClearance? = null,
    private val newxtoonUserAgent: String = userAgent,
) {
    init {
        // Route the engine's JVM-only page/decode timing hook to logcat on device.
        ml.melun.mangaview.engine.content.EnginePageWork.observer =
            { message -> android.util.Log.d("NtkPageWork", message) }
    }
    // One body beyond the twelve background transfers and two visible reserves is kept for the
    // document-end original so a fast reader cannot outrun a displayable episode end.
    private val workLimits = WorkLimits(network = 16, bodies = 15, backgroundNetwork = 12)
    val coordinator: WorkCoordinatorPort = WorkCoordinator(scope, workLimits)
    private val openingMemory: ml.melun.mangaview.viewer.runtime.ViewerMemoryEnvironment =
        ml.melun.mangaview.viewer.runtime.ViewerMemoryEnvironment(context) {
            openings.cancelPrediction()
            renderers.cancel()
        }
    val renderers = EngineRendererPreparation(scope, ioDispatcher,
        create = {
            ml.melun.mangaview.viewer.runtime.EngineSurfaceOwner(
                ml.melun.mangaview.engine.api.DeviceMemoryBudget.fromPhysicalRam(openingMemory.totalPhysicalBytes).glResidentBytes,
                {}, { android.util.Log.w("EnginePreparation", "Renderer preparation failed", it) }, {},
                bufferedCompositor = Build.VERSION.SDK_INT >= 31)
        }, prepare = { it.prepare() }, dispose = { it.close() },
        reportFailure = { android.util.Log.w("EnginePreparation", "Renderer preparation failed", it) })
    // Create and prepare the GL owner as soon as the engine graph exists so a direct reader
    // launch attaches a warm renderer instead of paying native context setup on the first frame.
    init { renderers.warm() }
    private val openingDecode = AndroidWorkDispatcher("viewer-opening-decode", 1, android.os.Process.THREAD_PRIORITY_BACKGROUND)
    private val openingPixels = EngineOpeningPixels(
        ml.melun.mangaview.engine.content.EnginePixelWork(
            ml.melun.mangaview.viewer.runtime.NativeEngineImageDecoder(), openingDecode.coroutineDispatcher),
        { context.resources.displayMetrics.let { ml.melun.mangaview.engine.api.EngineViewport(it.widthPixels, it.heightPixels) } },
        minOf(32L * 1024 * 1024, ml.melun.mangaview.engine.api.DeviceMemoryBudget
            .fromPhysicalRam(openingMemory.totalPhysicalBytes).glResidentBytes / 4).coerceAtLeast(1))
    val openings = EngineOpeningPreparations(scope, ioDispatcher, coordinator, { target ->
        session(ViewerLaunchSpec(target.seriesId.sourceId, target.seriesId, target))
    }, { android.util.Log.w("EngineOpening", "Opening preparation failed", it) }, openingPixels)
    @Volatile var episodeEvidenceObserver: EpisodePlanObserver? = null
    @Volatile var ntkAuthorizationEvidenceObserver: ((NtkEngineAuthorization) -> Unit)? = null
    private val observations = EpisodePlanObserver { episode, document, plan ->
        episodeEvidenceObserver?.observed(episode, document, plan)
    }
    private val positionStore = EnginePositionStore(database::database, ioDispatcher)
    val positions: EnginePositionPort = positionStore
    private val transportFactory = OkHttpTransportFactory(ioDispatcher, parallelism = workLimits.network)
    private fun resilient(
        transport: ml.melun.mangaview.source.SourceTransport,
        cookieJar: okhttp3.CookieJar = okhttp3.CookieJar.NO_COOKIES,
        headers: Map<String, String> = emptyMap(),
    ) = ProviderOriginTransport(transportFactory.protect(transport, cookieJar, headers), origins)
    private val transport = ObservedSourceTransport(resilient(transportFactory.create()), "engine", networkEvidenceObserver)
    // The engine transport exists for the reader, so nothing warms it while the home screen is up.
    // Open one bodyless exchange per disk-resolved document origin during startup so the first
    // chapter fetch reuses a pooled connection instead of paying DNS and TLS on the open path.
    init {
        listOf("wfwf" to DEFAULT_WFWF_ORIGIN, "goodtoon" to DEFAULT_GOODTOON_ORIGIN).forEach { (provider, fallback) ->
            scope.launch(ioDispatcher) {
                val origin = try {
                    origins.current(provider, fallback)
                } catch (failure: Throwable) {
                    fallback
                }
                val started = System.nanoTime()
                val warmed = try {
                    transport.execute(ml.melun.mangaview.source.SourceRequest(
                        url = origin,
                        method = ml.melun.mangaview.source.SourceHttpMethod.HEAD,
                        headers = mapOf("Accept" to "text/html,*/*;q=0.1"),
                        totalTimeoutMillis = ENGINE_ORIGIN_PRECONNECT_TIMEOUT_MILLIS,
                        preferQuic = false,
                        priority = ml.melun.mangaview.source.PageFetchPriority.BACKGROUND,
                    )).close()
                    true
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    false
                }
                android.util.Log.i("EngineWarm", "$provider origin=$origin ok=$warmed " +
                    "ms=${(System.nanoTime() - started) / 1_000_000}")
            }
        }
    }
    private val newxtoonTransport = lazy {
        val clearance = newxtoonClearance
        val jar = clearance?.cookieJar ?: okhttp3.CookieJar.NO_COOKIES
        // Cloudflare binds cf_clearance to the client hints the solving WebView sent, so the
        // engine repeats that browser identity on every native request just like the catalog.
        val headers = clearance?.let { OkHttpTransportFactory.browserHeaders(it.clientHints) }.orEmpty()
        val base = if (clearance == null) resilient(transportFactory.create(jar), jar)
        // The relay shapes the TLS record layer exactly like the challenge browser, so the edge
        // treats the engine's native requests as the same client that owns the clearance.
        else ProviderOriginTransport(transportFactory.createRelayed(jar, headers), origins)
        val guarded = if (clearance == null) base
        else NewxtoonClearanceTransport(base,
            ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN, clearance::solve,
            clearance::solveFresh, clearance::fetchPage, clearance::solvedViewReady,
            clearance::clearanceVerified, clearance::markReplayRefused,
            clearance.documents, clearance.refreshScope)
        // Artwork is served straight from the Bunny pull zone with the origin as referer, so an
        // image request never reaches the clearance route and never touches Cloudflare.
        val routed = if (clearance == null) guarded else NewxtoonImageTransport(guarded,
            transportFactory.createForBunnyImages(headers = linkedMapOf(
                "Referer" to ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN + "/",
                "User-Agent" to newxtoonUserAgent)))
        ObservedSourceTransport(routed, "engine", networkEvidenceObserver)
    }
    private val ntkPageTransport = lazy {
        // Match NTK's existing Chromium TLS transport for its image CDN hosts.
        // Construction is lazy and does not preconnect or request page content.
        ObservedSourceTransport(resilient(if (Build.VERSION.SDK_INT >= 34) {
            HttpEngineSourceTransport(context.applicationContext, userAgent, maximumSimultaneousBodyReads = workLimits.bodies)
        } else transportFactory.create()), "engine", networkEvidenceObserver)
    }
    private val storage = EngineRawStorage(File(context.applicationInfo.dataDir, "app_engine_pages_v1"),
        RoomEnginePublicationIndex(database::database), ioDispatcher, positions)
    private val completeEpisodes = ml.melun.mangaview.data.engine.EngineCompleteEpisodeStore(
        File(context.applicationInfo.dataDir, "app_engine_episode_plans_v1"), storage, ioDispatcher,
        reportFailure = { android.util.Log.w("EngineEpisodeCache", "Cached episode metadata unavailable", it) })
    private val ntkIdentity = NtkBrowserIdentity.forDevice(context, "engine")
    private val ntkBrowser by lazy {
        NtkEngineBrowserClient(context, userAgent, ntkIdentity,
            captureEvidence = { ntkAuthorizationEvidenceObserver != null }) {
            ntkAuthorizationEvidenceObserver?.invoke(it)
        }
    }

    fun session(spec: ViewerLaunchSpec): EngineViewerWork {
        val live = when (spec.sourceId.value) {
            "wfwf" -> EngineWfwfSessionWork(userAgent, URI(DEFAULT_WFWF_ORIGIN), transport, storage, positions,
                parsingDispatcher, library::readingPosition, spec.initialPosition, observations, spec.initialAnchor)
            "newxtoon" -> EngineNewxtoonSessionWork(newxtoonUserAgent, URI(
                ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN), newxtoonTransport.value, storage, positions,
                parsingDispatcher, library::readingPosition, spec.initialPosition, observations, spec.initialAnchor)
            "goodtoon" -> EngineGoodtoonSessionWork(userAgent, URI(DEFAULT_GOODTOON_ORIGIN), transport, storage, positions,
                parsingDispatcher, library::readingPosition, spec.initialPosition, observations, spec.initialAnchor)
            "ntk" -> EngineNtkSessionWork(userAgent, ntkOrigin, transport, storage, positions,
                parsingDispatcher, ntkBrowser, ntkIdentity, library::readingPosition, spec.initialPosition, observations, ntkPageTransport.value,
                spec.initialAnchor)
            else -> error("Unknown engine source")
        }
        val cached = ml.melun.mangaview.engine.content.EngineCachedSessionWork(live, completeEpisodes)
        val composed = object : EngineViewerWork, EngineSessionWork by cached {
            override fun episodes(seriesId: ml.melun.mangaview.core.SeriesId,
                priority: ml.melun.mangaview.engine.api.WorkPriority) = live.episodes(seriesId, priority)
        }
        return EngineOfflineSessionWork(composed, offlineEpisodes)
    }

    suspend fun close() {
        var primary: Throwable? = null
        suspend fun closeOwned(action: suspend () -> Unit) {
            try { action() } catch (failure: Throwable) {
                val first = primary
                if (first == null) primary = failure else if (first !== failure) first.addSuppressed(failure)
            }
        }
        closeOwned { openings.close() }
        closeOwned { renderers.close() }
        closeOwned { coordinator.close() }
        closeOwned { openingMemory.close() }
        closeOwned { openingDecode.closeAndAwait() }
        val transports = listOfNotNull(transport, ntkPageTransport.takeIf { it.isInitialized() }?.value,
            newxtoonTransport.takeIf { it.isInitialized() }?.value)
        for (owned in transports) closeOwned { owned.close() }
        primary?.let { throw it }
    }
    suspend fun saveBookmark(anchor: ml.melun.mangaview.engine.api.SourceAnchor, offset: Long) {
        val request = ml.melun.mangaview.engine.api.WorkRequest(
            ml.melun.mangaview.engine.api.WorkKey("library", anchor.pageId.toString(), "bookmark.save",
                "$anchor:$offset", Unit::class.java), ml.melun.mangaview.engine.api.WorkDomain.STORAGE,
            ml.melun.mangaview.engine.api.WorkPriority.INTERACTIVE,
            execute = { positionStore.saveBookmark(anchor, offset) },
        )
        val subscription = coordinator.submit(request)
        try { subscription.await() } finally {
            subscription.close()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { subscription.awaitReleased() }
        }
    }
    suspend fun storageOwnership() = storage.ownership()

    private companion object {
        const val ENGINE_ORIGIN_PRECONNECT_TIMEOUT_MILLIS = 4_000L
    }
}
