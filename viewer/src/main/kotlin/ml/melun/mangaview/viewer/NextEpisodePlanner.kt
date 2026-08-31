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
        // The adjacent provider handshake may initialize Chromium and run challenge JavaScript.
        // It must never share the critical path with the current episode's first actual frame.
        if (!state.hasPresentedContent || !state.surfacePresentationReady || state.interactionActive) {
            return false
        }
        val progress = state.episodeProgress[episodeId] ?: return false
        if (episodeId == state.currentEpisodeId && progress.allVerified) return true
        val measuredVelocity = state.velocityUnitsPerSecond
        if (measuredVelocity < 0L) return false
        // After a real current-episode frame exists, an idle reader may still fling immediately.
        // A conservative physical runway starts adjacent work early without placing it ahead of
        // the visible episode's first pixels.
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
