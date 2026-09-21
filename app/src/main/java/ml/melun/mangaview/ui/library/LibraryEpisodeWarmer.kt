package ml.melun.mangaview.ui.library

import ml.melun.mangaview.app.EngineOpeningPreparations
import ml.melun.mangaview.core.EpisodeId

internal class LibraryEpisodeWarmer(private val openings: () -> EngineOpeningPreparations) {
    private var episodeId: EpisodeId? = null
    private var foreground = false
    private var primed = false

    fun foreground(value: Boolean, state: LibraryState) {
        foreground = value
        // onPause precedes ViewerActivity.onCreate; retain its prediction for claim().
        if (value) continuation(state)
    }

    /**
     * Enables continuation warming once the library has drawn its first frame. State emitted
     * before that is ignored so the reader engine graph is not built on the launch path.
     */
    fun activate(state: LibraryState) {
        primed = true
        continuation(state)
    }

    fun continuation(state: LibraryState) {
        if (foreground && primed) mostLikelyContinuation(state)?.let(::warm)
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
