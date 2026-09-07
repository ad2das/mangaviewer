package ml.melun.mangaview.content

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.PageId

internal fun cancelActiveDecode(page: PageRecord): Boolean = when (val active = page.decode) {
    is DecodeState.Decoding -> {
        page.decode = active.copy(cancelRequested = true)
        active.job.cancel()
        true
    }
    is DecodeState.Uploading -> {
        page.decode = active.copy(cancelRequested = true)
        active.job.cancel()
        true
    }
    else -> false
}

internal fun acceptDecodeStopped(command: PipelineCommand.DecodeStopped, generation: Long,
    pages: Map<PageId, PageRecord>) {
    val page = pages[command.pageId] ?: return
    if (command.generation != generation) return
    when (val active = page.decode) {
        is DecodeState.Decoding -> {
            if (command.upload || active.token != command.token || !active.cancelRequested) return
            check(active.job.isCompleted) { "Decode capacity released before worker completion" }
        }
        is DecodeState.Uploading -> {
            if (!command.upload || active.token != command.token || !active.cancelRequested) return
            check(active.job.isCompleted) { "Upload capacity released before worker completion" }
        }
        else -> return
    }
    page.decode = DecodeState.Idle
}

internal fun CoroutineScope.notifyCancellation(job: Job, commands: SendChannel<PipelineCommand>,
    command: PipelineCommand) {
    job.invokeOnCompletion {
        if (job.isCancelled) launch {
            try {
                commands.send(command)
            } catch (_: ClosedSendChannelException) {
                // Shutdown already removed all records; no resource is carried by this notification.
            }
        }
    }
}
