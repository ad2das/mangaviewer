package ml.melun.mangaview.engine.api

import java.io.Closeable
import ml.melun.mangaview.core.EpisodeId

/** Metadata alone is not a cache hit: open must verify and pin every original in manifest order. */
interface EngineEpisodeCachePort {
    suspend fun remember(plan: EpisodeAccessPlan)
    suspend fun open(episodeId: EpisodeId): CachedEngineEpisode?
}

interface CachedEngineEpisode : Closeable {
    val plan: EpisodeAccessPlan
}
