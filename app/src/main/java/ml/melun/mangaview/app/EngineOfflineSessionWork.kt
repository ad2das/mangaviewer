package ml.melun.mangaview.app

import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.lowerHex
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.data.offline.OfflineEpisodeStore
import ml.melun.mangaview.engine.api.AccessPrerequisite
import ml.melun.mangaview.engine.api.EpisodeAccessPlan
import ml.melun.mangaview.engine.api.PageAccessPlan
import ml.melun.mangaview.engine.api.StoredPage
import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.source.AdjacentEpisodes

/**
 * Downloaded episodes are read from their local publication before any network plan is built.
 * The plan is local-only, so page work never reactivates source addresses; adjacency and the
 * episode catalog only fall back to the download when the network path is unavailable.
 */
internal class EngineOfflineSessionWork(
    private val live: EngineViewerWork,
    private val offline: OfflineEpisodeStore,
) : EngineViewerWork by live {
    override fun episode(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<EpisodeAccessPlan> {
        val online = live.episode(episodeId, priority)
        return WorkRequest(online.key.copy(operation = "episode.offline"), WorkDomain.CONTROL, priority,
            authEpoch = online.authEpoch, execute = { parent ->
                offlinePlan(episodeId) ?: parent.dependency(online)
            })
    }

    override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority): WorkRequest<StoredPage> {
        if (!OfflineEnginePlans.owns(plan)) return live.page(plan, pageId, priority)
        val online = live.page(plan, pageId, priority)
        return WorkRequest(online.key.copy(operation = "content.page.offline"), WorkDomain.CONTROL, priority,
            authEpoch = online.authEpoch, execute = {
                val stored = offline.find(pageId)
                    ?: throw IOException("Offline page '${pageId.remoteKey}' is no longer available")
                StoredPage(pageId, plan.contentRevision, stored.file, stored.byteCount, stored.sha256,
                    stored.dimensions, stored.mediaType)
            })
    }

    override fun navigation(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<AdjacentEpisodes> {
        val online = live.navigation(episodeId, priority)
        return WorkRequest(online.key.copy(operation = "catalog.navigation.offline"), WorkDomain.CONTROL, priority,
            authEpoch = online.authEpoch, execute = { parent ->
                try {
                    parent.dependency(online)
                } catch (unavailable: IOException) {
                    val manifest = offline.manifest(episodeId) ?: throw unavailable
                    val local = AdjacentEpisodes(manifest.previousEpisodeId, manifest.nextEpisodeId)
                    if (local.previous == null && local.next == null) throw unavailable
                    local
                }
            })
    }

    override fun episodes(seriesId: SeriesId, priority: WorkPriority): WorkRequest<EngineEpisodeCatalog> {
        val online = live.episodes(seriesId, priority)
        return WorkRequest(online.key.copy(operation = "catalog.episodes.offline"), WorkDomain.CONTROL, priority,
            authEpoch = online.authEpoch, execute = { parent ->
                try {
                    parent.dependency(online)
                } catch (unavailable: IOException) {
                    val local = offline.episodes(seriesId)
                    if (local.isEmpty()) throw unavailable else EngineEpisodeCatalog(seriesId, local)
                }
            })
    }

    private suspend fun offlinePlan(episodeId: EpisodeId): EpisodeAccessPlan? {
        val manifest = offline.manifest(episodeId) ?: return null
        val pages = mutableListOf<Pair<PageSpec, CachedPage>>()
        for (spec in manifest.pages) {
            val stored = offline.find(spec.id) ?: return null
            if (stored.pageId != spec.id) return null
            pages += spec to stored
        }
        return OfflineEnginePlans.plan(manifest, pages)
    }
}

/** Pure publication mapping from a verified offline manifest to a local-only access plan. */
internal object OfflineEnginePlans {
    private const val REVISION_PREFIX = "offline:"
    private const val LOCAL_HOST = "offline.invalid"

    fun owns(plan: EpisodeAccessPlan): Boolean =
        plan.localOnly && plan.contentRevision.startsWith(REVISION_PREFIX)

    fun plan(manifest: EpisodeManifest, pages: List<Pair<PageSpec, CachedPage>>): EpisodeAccessPlan {
        require(pages.map { it.first.id } == manifest.pages.map { it.id }) {
            "Offline publication must match the manifest exactly, including order"
        }
        require(pages.all { (spec, stored) -> stored.pageId == spec.id && stored.file.isFile }) {
            "Offline page files must exist in manifest order"
        }
        val digest = documentDigest(manifest)
        val access = pages.mapIndexed { index, (spec, _) ->
            PageAccessPlan(spec.id, "offline-$index", listOf(localUri("page/${spec.id.remoteKey}")))
        }
        return EpisodeAccessPlan(manifest, REVISION_PREFIX + digest, digest, localUri("episode/${manifest.id.remoteKey}"),
            authEpoch = 0L, pages = access, prerequisites = emptyList<AccessPrerequisite>(), localOnly = true)
    }

    private fun documentDigest(manifest: EpisodeManifest): String {
        val fields = buildList {
            add(manifest.id.toString())
            add(manifest.title)
            add("${manifest.previousEpisodeId}->${manifest.nextEpisodeId}")
            for (spec in manifest.pages) {
                add(listOf(spec.ordinal.toString(), spec.fingerprint.orEmpty(),
                    (spec.encodedLength ?: -1L).toString(),
                    spec.dimensions?.let { "${it.widthPx}x${it.heightPx}" }.orEmpty()).joinToString(":"))
            }
        }
        // Same digest as hashing the joined `"${length}:$field"` text, fed incrementally. The
        // manifest contributes one field per page, so the joined form allocated a large String
        // and an equally large byte array on every plan build.
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            digest.update(field.length.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(field.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().lowerHex()
    }

    /** Never dereferenced: local-only plans must not reactivate source addresses. */
    private fun localUri(path: String): URI = URI("https", LOCAL_HOST, "/$path", null)
}
