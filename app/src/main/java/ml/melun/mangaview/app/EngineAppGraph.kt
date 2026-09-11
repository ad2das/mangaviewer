package ml.melun.mangaview.app

import android.content.Context
import android.os.Build
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.data.db.DeferredViewerDatabase
import ml.melun.mangaview.data.engine.EnginePositionStore
import ml.melun.mangaview.data.engine.EngineRawStorage
import ml.melun.mangaview.data.engine.RoomEnginePublicationIndex
import ml.melun.mangaview.data.library.UserLibraryRepository
import ml.melun.mangaview.data.network.OkHttpTransportFactory
import ml.melun.mangaview.data.network.HttpEngineSourceTransport
import ml.melun.mangaview.engine.api.EnginePositionPort
import ml.melun.mangaview.engine.api.EpisodePlanObserver
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.wfwf.DEFAULT_WFWF_ORIGIN
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
    networkEvidenceObserver: () -> SourceExchangeObserver? = { null },
    private val origins: ProviderOriginDirectory = ProviderOriginDirectory(context, ioDispatcher, userAgent),
) {
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
    private fun resilient(transport: ml.melun.mangaview.source.SourceTransport) =
        ProviderOriginTransport(transportFactory.protect(transport), origins)
    private val transport = ObservedSourceTransport(resilient(transportFactory.create()), "engine", networkEvidenceObserver)
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
    private val ntkBrowser by lazy {
        NtkEngineBrowserClient(context, userAgent, NtkBrowserIdentity.forDevice(context, "engine"),
            captureEvidence = { ntkAuthorizationEvidenceObserver != null }) {
            ntkAuthorizationEvidenceObserver?.invoke(it)
        }
    }

    fun session(spec: ViewerLaunchSpec): EngineViewerWork {
        val live = when (spec.sourceId.value) {
            "wfwf" -> EngineWfwfSessionWork(userAgent, URI(DEFAULT_WFWF_ORIGIN), transport, storage, positions,
                parsingDispatcher, library::readingPosition, spec.initialPosition, observations, spec.initialAnchor)
            "newxtoon" -> EngineNewxtoonSessionWork(userAgent, URI(
                ml.melun.mangaview.source.newxtoon.DEFAULT_NEWXTOON_ORIGIN), transport, storage, positions,
                parsingDispatcher, library::readingPosition, spec.initialPosition, observations, spec.initialAnchor)
            "ntk" -> EngineNtkSessionWork(userAgent, ntkOrigin, transport, storage, positions,
                parsingDispatcher, ntkBrowser, library::readingPosition, spec.initialPosition, observations, ntkPageTransport.value,
                spec.initialAnchor)
            else -> error("Unknown engine source")
        }
        val cached = ml.melun.mangaview.engine.content.EngineCachedSessionWork(live, completeEpisodes)
        return object : EngineViewerWork, EngineSessionWork by cached {
            override fun episodes(seriesId: ml.melun.mangaview.core.SeriesId,
                priority: ml.melun.mangaview.engine.api.WorkPriority) = live.episodes(seriesId, priority)
        }
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
        val transports = listOfNotNull(transport, ntkPageTransport.takeIf { it.isInitialized() }?.value)
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
}
