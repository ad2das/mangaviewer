package ml.melun.mangaview.engine.api

import ml.melun.mangaview.core.EpisodeId

/**
 * Optional disk boundary for episode documents. A stored body lets the plan resolve without the
 * provider's cold document round trip; the caller evicts entries whose stored body no longer parses.
 */
interface EpisodeDocumentStore {
    fun load(episodeId: EpisodeId): SourceDocument?
    fun save(episodeId: EpisodeId, document: SourceDocument)
    fun remove(episodeId: EpisodeId)
}
