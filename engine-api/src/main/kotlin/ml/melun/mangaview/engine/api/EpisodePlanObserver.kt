package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.EpisodeId

/** Optional observation of the exact completed document/plan pair; implementations must not perform I/O here. */
fun interface EpisodePlanObserver {
    fun observed(episodeId: EpisodeId, document: SourceDocument, plan: EpisodeAccessPlan)
}
