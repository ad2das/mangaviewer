package ml.melun.mangaview.viewer

import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId

data class EpisodeOperationToken(
    val generation: Long,
    val fromEpisodeId: EpisodeId,
    val targetEpisodeId: EpisodeId,
    val attempt: Int,
) {
    init {
        require(generation > 0L) { "Generation must be positive" }
        require(targetEpisodeId.seriesId == fromEpisodeId.seriesId) {
            "Adjacent episodes must belong to one series"
        }
        require(targetEpisodeId != fromEpisodeId) { "An episode cannot append itself" }
        require(attempt > 0) { "Attempt must be positive" }
    }
}

data class EpisodeAppendRuntime(
    val owner: EpisodeOperationToken? = null,
    val retry: RetryState? = null,
    val terminal: Boolean = false,
    val boundaryPageId: PageId? = null,
) {
    init {
        require(!(terminal && owner != null)) { "A terminal append cannot have an owner" }
        require(!(terminal && retry != null)) { "A terminal append cannot be retryable" }
        require(!(terminal && boundaryPageId != null)) { "A terminal append cannot retain a boundary" }
        require(!(owner != null && retry != null)) { "An owned append cannot be retryable" }
    }
}
