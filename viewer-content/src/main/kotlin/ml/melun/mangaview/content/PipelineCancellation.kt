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
    // The Stopped notification must fire on every completion, not only on isCancelled: cancel()
    // on a job that already completed normally leaves isCancelled false, while the actor has
    // already marked the record cancelRequested and dropped the matching Finished result. The
    // accept* guards make a spurious Stopped a no-op, so an unconditional send is safe and it
    // is the only thing that releases the slot when cancel loses the race.
    job.invokeOnCompletion {
        launch {
            try {
                commands.send(command)
            } catch (_: ClosedSendChannelException) {
                // Shutdown already removed all records; no resource is carried by this notification.
            }
        }
    }
}
