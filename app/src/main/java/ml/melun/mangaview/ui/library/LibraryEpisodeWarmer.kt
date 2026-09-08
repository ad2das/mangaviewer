package ml.melun.mangaview.ui.library

import ml.melun.mangaview.app.EngineOpeningPreparations
import ml.melun.mangaview.core.EpisodeId

internal class LibraryEpisodeWarmer(private val openings: () -> EngineOpeningPreparations) {
    private var episodeId: EpisodeId? = null
    private var foreground = false

    fun foreground(value: Boolean, state: LibraryState) {
        foreground = value
        // onPause precedes ViewerActivity.onCreate; retain its prediction for claim().
        if (value) continuation(state)
    }

    fun continuation(state: LibraryState) {
        if (foreground) mostLikelyContinuation(state)?.let(::warm)
    }

    fun warm(target: EpisodeId) {
        episodeId = target
        openings().warm(target)
    }

    fun cancel() {
        if (episodeId != null) openings().cancelPrediction()
        episodeId = null
    }
}
