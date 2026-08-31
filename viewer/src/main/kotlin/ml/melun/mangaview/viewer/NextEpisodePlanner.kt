package ml.melun.mangaview.viewer

class NextEpisodePlanner(
    private val leadSeconds: Long = 12L,
) {
    init {
        require(leadSeconds > 0L) { "Next episode lead time must be positive" }
    }

    fun shouldPrepare(state: ViewerState): Boolean {
        val progress = state.episodeProgress[state.currentEpisodeId] ?: return false
        if (progress.allVerified) return true
        val velocity = state.velocityUnitsPerSecond
        if (velocity <= 0L) return false
        val lastIndex = state.layout.indexOf(progress.lastPageId) ?: return false
        val episodeBottom = Math.addExact(
            state.layout.topAt(lastIndex).units,
            state.layout.entries[lastIndex].height.units,
        )
        val viewportBottom = saturatingAdd(state.scroll.contentOffset.units, state.viewport.height.units)
        val remaining = (episodeBottom - viewportBottom).coerceAtLeast(0L)
        return remaining <= saturatingMultiply(velocity, leadSeconds)
    }

    private fun saturatingMultiply(value: Long, factor: Long): Long =
        if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
}
