package ml.melun.mangaview.viewer

sealed interface ViewerCommand {
    data class LoadNextEpisode(val token: EpisodeOperationToken) : ViewerCommand

    data class FetchPage(val token: OperationToken) : ViewerCommand

    data class DecodePage(
        val token: OperationToken,
        val encoded: VerifiedPageRef,
        val band: PixelBand,
    ) : ViewerCommand

    data class CancelGeneration(val generation: Long) : ViewerCommand

    data class CancelDecode(val token: OperationToken) : ViewerCommand

    data class ReleasePixel(val pixel: PixelRef) : ViewerCommand
}

data class Reduction(
    val state: ViewerState,
    val commands: List<ViewerCommand>,
)
