package ml.melun.mangaview.app

import java.util.Collections
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.engine.api.EngineSessionWork
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.source.SourceEpisode

internal interface EngineViewerWork : EngineSessionWork {
    fun episodes(seriesId: SeriesId, priority: WorkPriority): WorkRequest<EngineEpisodeCatalog>
}

internal class EngineEpisodeCatalog(seriesId: SeriesId, episodes: List<SourceEpisode>) {
    val episodes: List<SourceEpisode> = Collections.unmodifiableList(episodes.toList())
    init {
        require(episodes.isNotEmpty() && episodes.all { it.id.seriesId == seriesId })
        require(episodes.map { it.id }.distinct().size == episodes.size)
    }
}
