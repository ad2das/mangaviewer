package ml.melun.mangaview.ui.library

import ml.melun.mangaview.app.EngineOpeningPreparations
import ml.melun.mangaview.core.EpisodeId

internal class LibraryEpisodeWarmer(private val openings: () -> EngineOpeningPreparations) {
    private var episodeId: EpisodeId? = null

    fun warm(target: EpisodeId) {
        if (episodeId == target) return
        episodeId = target
        openings().warm(target)
    }

    fun cancel() {
        if (episodeId != null) openings().cancelPrediction()
        episodeId = null
    }
}
