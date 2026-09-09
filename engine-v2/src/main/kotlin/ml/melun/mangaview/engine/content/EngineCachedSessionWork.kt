package ml.melun.mangaview.engine.content

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.engine.api.*

/** Complete-cache validation and its pins share the live plan's coordinator ownership. */
class EngineCachedSessionWork(
    private val live: EngineSessionWork,
    private val cache: EngineEpisodeCachePort,
) : EngineSessionWork by live {
    override fun episode(episodeId: EpisodeId, priority: WorkPriority): WorkRequest<EpisodeAccessPlan> {
        val online = live.episode(episodeId, priority)
        val key = online.key
        return WorkRequest(key.copy(operation = "cached.${key.operation}"), WorkDomain.CONTROL,
            priority, authEpoch = online.authEpoch, execute = { parent ->
                val cached = parent.dependency(WorkRequest(
                    WorkKey(key.principal, key.resource, "episode.cache.open", key.contentRevision, Lookup::class.java),
                    WorkDomain.STORAGE, parent.priority.value, authEpoch = online.authEpoch,
                    execute = { Lookup(cache.open(episodeId)) }, dispose = { it.episode?.close() }))
                cached.episode?.plan?.also {
                    require(it.localOnly && it.manifest.id == episodeId && it.authEpoch == online.authEpoch)
                } ?: parent.dependency(online).also { plan ->
                    parent.useDependency(WorkRequest(
                        WorkKey(key.principal, key.resource, "episode.cache.remember", plan.documentSha256, Unit::class.java),
                        WorkDomain.STORAGE, parent.priority.value, authEpoch = online.authEpoch,
                        execute = { cache.remember(plan) })) { Unit }
                }
            })
    }

    private class Lookup(val episode: CachedEngineEpisode?)
}
