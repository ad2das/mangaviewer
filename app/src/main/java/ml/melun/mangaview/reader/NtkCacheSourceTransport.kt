package ml.melun.mangaview.reader

import android.content.Context
import ml.melun.mangaview.mangaview.Manga
import java.io.Closeable

fun interface NtkSourceEventListener {
    fun onSourceEvent(event: SourceEvent)
}

interface NtkSourceEventTransport {
    fun addSourceEventListener(listener: NtkSourceEventListener): Closeable
    fun applyPreGeometryPlan(episode: NtkEpisodeToken, plan: NtkPreGeometrySourcePlan)
    fun applySourceDemand(episode: NtkEpisodeToken, demand: NtkSourceDemandSnapshot)
}

/**
 * Thin strict-only transport boundary.
 *
 * The plan-owned session already owns its actor, lanes and physical Calls. This wrapper becomes
 * claimable only after exact binding; it never creates or restarts source work.
 */
internal class NtkCacheSourceTransport(
    private val strictSession: NtkStrictSourceSession
) : NtkEpisodeStripPipeline.SourceTransport, NtkStrictSourceTransport, Closeable {
    override val exactSealAtMs: Long
        get() = strictSession.exactSealAtMs

    val strictSessionId: Long
        get() = strictSession.sessionId

    fun quarantineDebugSnapshot(): NtkQuarantineDebugSnapshot =
        strictSession.quarantineDebugSnapshot()

    override fun register(
        request: NtkEpisodeStripPipeline.SourceRequest,
        completion: (Result<NtkEpisodeStripPipeline.SourceHandle>) -> Unit
    ) {
        completion(Result.failure(IllegalStateException(
            "Strict source transport forbids page-by-page registration"
        )))
    }

    override fun registerWithEvents(
        request: NtkEpisodeStripPipeline.SourceRequest,
        event: (SourceEvent) -> Unit,
        completion: (Result<NtkEpisodeStripPipeline.SourceHandle>) -> Unit
    ) = register(request, completion)

    override fun addSourceEventListener(listener: NtkSourceEventListener): Closeable =
        strictSession.addSourceEventListener(listener)

    override fun bindEpisode(
        episode: NtkEpisodeToken,
        manifestSeal: NtkEpisodeManifestSeal,
        initialPageIndex: Int,
        listener: NtkSourceEventListener
    ): Closeable = strictSession.bindEpisode(episode, manifestSeal, initialPageIndex, listener)

    override fun bindResidentBodies(
        episode: NtkEpisodeToken,
        manifestSeal: NtkEpisodeManifestSeal,
        listener: NtkStrictResidentBodyListener
    ): Closeable = strictSession.bindResidentBodies(episode, manifestSeal, listener)

    override fun onGeometrySealed(
        episode: NtkEpisodeToken,
        geometryDigest: String,
        exactStagePageIndexes: Set<Int>
    ) = strictSession.onGeometrySealed(episode, geometryDigest, exactStagePageIndexes)

    override fun onFirstActualFramePresented(episode: NtkEpisodeToken) =
        strictSession.onFirstActualFramePresented(episode)

    override fun requestPreparationDrain(
        episode: NtkEpisodeToken,
        completion: (NtkSourceDrainProof) -> Unit
    ) = strictSession.requestPreparationDrain(episode, completion)

    override fun applyPreGeometryPlan(
        episode: NtkEpisodeToken,
        plan: NtkPreGeometrySourcePlan
    ) = strictSession.applyPreGeometryPlan(episode, plan)

    override fun applySourceDemand(
        episode: NtkEpisodeToken,
        demand: NtkSourceDemandSnapshot
    ) = strictSession.applySourceDemand(episode, demand)

    override fun retire(episode: NtkEpisodeToken) = strictSession.retire(episode)

    override fun close() = strictSession.close()
}
