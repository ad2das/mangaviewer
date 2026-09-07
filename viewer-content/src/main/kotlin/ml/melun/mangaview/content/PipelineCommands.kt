package ml.melun.mangaview.content

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.session.DemandSnapshot

internal sealed interface PipelineCommand {
    data class RegisterManifest(val generation: Long, val manifest: EpisodeManifest) : PipelineCommand
    data class UpdateDemand(
        val snapshot: DemandSnapshot,
        val displayWidthPx: Int,
        val viewportHeightPx: Int,
    ) : PipelineCommand
    data class SetForeground(val foreground: Boolean) : PipelineCommand
    data class SetRendererEpoch(val epoch: Long) : PipelineCommand
    data class EpisodeFinished(val token: Long, val result: Result<EpisodeManifest>) : PipelineCommand
    data class EpisodeStopped(val token: Long) : PipelineCommand
    data object MemoryPressure : PipelineCommand
    data class FetchFinished(
        val generation: Long,
        val pageId: PageId,
        val token: Long,
        val result: Result<EncodedPageRef>,
    ) : PipelineCommand
    data class FetchResponseStarted(
        val generation: Long,
        val pageId: PageId,
        val token: Long,
    ) : PipelineCommand
    data class FetchStopped(val generation: Long, val pageId: PageId, val token: Long) : PipelineCommand
    data class DecodeStopped(val generation: Long, val pageId: PageId, val token: Long,
        val upload: Boolean = false) : PipelineCommand
    data class DecodeFinished(
        val generation: Long,
        val pageId: PageId,
        val token: Long,
        val range: SourceRowRange,
        val result: Result<ResourceHandoff<CpuTileLease>>,
    ) : PipelineCommand
    data class UploadFinished(
        val generation: Long,
        val pageId: PageId,
        val token: Long,
        val result: Result<ResourceHandoff<TextureRef>>,
    ) : PipelineCommand
    data object RetryDue : PipelineCommand
    data class Snapshot(val reply: CompletableDeferred<ContentPipelineSnapshot>) : PipelineCommand
    data class Close(val reply: CompletableDeferred<Unit>) : PipelineCommand
}

internal fun PipelineCommand.releaseUndelivered() {
    when (this) {
        is PipelineCommand.DecodeFinished -> result.getOrNull()?.close()
        is PipelineCommand.UploadFinished -> result.getOrNull()?.close()
        is PipelineCommand.Snapshot -> reply.cancel()
        is PipelineCommand.Close -> reply.cancel()
        else -> Unit
    }
}

internal suspend fun SendChannel<PipelineCommand>.sendCompletion(command: PipelineCommand) {
    try {
        send(command)
    } catch (failure: Throwable) {
        command.releaseUndelivered()
        throw failure
    }
}
