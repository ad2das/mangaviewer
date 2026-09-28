package ml.melun.mangaview.engine.runtime

import java.util.Collections
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.engine.api.EngineSessionSnapshot
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.PageContentIdentity
import ml.melun.mangaview.engine.api.StoredPage

/**
 * Launch-episode preparation ledger: the accepted manifest and every page whose original was
 * verified and prepared. It owns no page or file lease, only the observations themselves.
 */
internal class LaunchPreparationRecorder(
    private val launchGeneration: Long,
    private val launchEpisode: EpisodeId,
    private val clock: () -> Long,
) {
    private var manifestAcceptedAtNanos: Long? = null
    private var manifestContentRevision: String? = null
    private var manifestPageIds: List<PageId> = emptyList()
    private val verifiedPages = linkedMapOf<PageId, EngineVerifiedPageObservation>()
    private var allPreparedAtNanos: Long? = null

    fun isLaunchEpisode(generation: Long, episode: EpisodeId): Boolean =
        generation == launchGeneration && episode == launchEpisode

    fun onManifestAccepted(expected: EpisodeId, generation: Long, plan: EpisodeAccessPlan) {
        if (!isLaunchEpisode(generation, expected) || manifestAcceptedAtNanos != null) return
        manifestPageIds = plan.manifest.pages.map { it.id }
        manifestContentRevision = plan.contentRevision
        manifestAcceptedAtNanos = clock().also { require(it > 0L) }
    }

    fun onPageAccepted(
        expected: PageId,
        page: StoredPage,
        identity: PageContentIdentity,
        generation: Long,
        snapshot: EngineSessionSnapshot,
    ) {
        if (!isLaunchEpisode(generation, expected.episodeId)) return
        if (page.contentRevision != manifestContentRevision || expected !in manifestPageIds ||
            expected in verifiedPages
        ) {
            return
        }
        val acceptedAt = clock().also { require(it > 0L) }
        verifiedPages[expected] = EngineVerifiedPageObservation(
            identity, manifestPageIds.indexOf(expected), generation,
            snapshot.inputRevision, snapshot.geometryRevision, acceptedAt,
        )
        if (allPreparedAtNanos == null && manifestPageIds.isNotEmpty() &&
            verifiedPages.keys.containsAll(manifestPageIds)
        ) {
            allPreparedAtNanos = acceptedAt
        }
    }

    fun snapshot(): EngineLaunchPreparationSnapshot = EngineLaunchPreparationSnapshot(
        launchGeneration, launchEpisode, manifestAcceptedAtNanos,
        Collections.unmodifiableList(manifestPageIds.toList()), immutableMap(verifiedPages),
        allPreparedAtNanos,
    )
}
