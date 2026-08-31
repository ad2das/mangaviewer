package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId

class NextEpisodePlanner(
    private val leadSeconds: Long = 12L,
    private val idleForwardScreenfulsPerSecond: Long = 4L,
) {
    init {
        require(leadSeconds > 0L) { "Next episode lead time must be positive" }
        require(idleForwardScreenfulsPerSecond > 0L) {
            "Idle forward velocity must be positive"
        }
    }

    fun shouldPrepare(
        state: ViewerState,
        episodeId: EpisodeId = state.currentEpisodeId,
    ): Boolean {
        val progress = state.episodeProgress[episodeId] ?: return false
        if (episodeId == state.currentEpisodeId && progress.allVerified) return true
        val measuredVelocity = state.velocityUnitsPerSecond
        if (measuredVelocity < 0L) return false
        // A reader can fling immediately, before the first network response provides a measured
        // velocity. Use a conservative physical fling runway at rest so adjacent manifest work
        // starts with the viewer instead of after the user has already reached a placeholder.
        // The viewer still opens and accepts input immediately; this only schedules source work.
        val idleForwardVelocity = saturatingMultiply(
            state.viewport.height.units,
            idleForwardScreenfulsPerSecond,
        )
        val velocity = maxOf(measuredVelocity, idleForwardVelocity)
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
