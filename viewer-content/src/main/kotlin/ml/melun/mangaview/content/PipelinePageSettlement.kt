package ml.melun.mangaview.content

import ml.melun.mangaview.core.PageId

/** Fault isolation and release for the pipeline's page ledger. */
internal class PipelinePageSettlement(
    private val pages: LinkedHashMap<PageId, PageRecord>,
) {
    /** Force-releases the operation a faulted handler was carrying so its lane cannot wedge. */
    fun settleFaultedOperation(command: PipelineCommand) {
        when (command) {
            is PipelineCommand.FetchFinished -> settleFetchFinished(command)
            is PipelineCommand.FetchStopped -> settleFetchStopped(command)
            is PipelineCommand.DecodeFinished -> settleDecodeFinished(command)
            is PipelineCommand.UploadFinished -> settleUploadFinished(command)
            is PipelineCommand.DecodeStopped -> settleDecodeStopped(command)
            else -> Unit
        }
    }

    private fun settleFetchFinished(command: PipelineCommand.FetchFinished) {
        val active = pages[command.pageId]?.raw as? RawState.Fetching ?: return
        if (active.token != command.token) return
        pages[command.pageId]?.raw = RawState.Absent
    }

    private fun settleFetchStopped(command: PipelineCommand.FetchStopped) {
        val active = pages[command.pageId]?.raw as? RawState.Fetching ?: return
        if (active.token != command.token) return
        pages[command.pageId]?.raw = RawState.Stranded(active.token, active.job, active.cancelRequested)
    }

    private fun settleDecodeFinished(command: PipelineCommand.DecodeFinished) {
        val active = pages[command.pageId]?.decode as? DecodeState.Decoding ?: return
        if (active.token != command.token) return
        pages[command.pageId]?.decode = DecodeState.Idle
    }

    private fun settleUploadFinished(command: PipelineCommand.UploadFinished) {
        val active = pages[command.pageId]?.decode as? DecodeState.Uploading ?: return
        if (active.token != command.token) return
        pages[command.pageId]?.decode = DecodeState.Idle
    }

    private fun settleDecodeStopped(command: PipelineCommand.DecodeStopped) {
        val page = pages[command.pageId] ?: return
        val active = page.decode
        if (active is DecodeState.Decoding && !command.upload) {
            if (active.token == command.token) page.decode = DecodeState.Idle
        } else if (active is DecodeState.Uploading && command.upload) {
            if (active.token == command.token) page.decode = DecodeState.Idle
        }
    }

    /** Cancels every lane, releases every resident and clears the ledger. */
    fun releaseAll(
        episodeWork: PipelineEpisodeWork,
        retryCoordinator: PipelineRetryCoordinator,
        uploader: TextureUploadPort,
    ) {
        episodeWork.cancel()
        retryCoordinator.clear()
        pages.values.forEach { page ->
            when (val raw = page.raw) {
                is RawState.Fetching -> raw.job.cancel()
                is RawState.Stranded -> raw.job.cancel()
                else -> Unit
            }
            when (val decode = page.decode) {
                is DecodeState.Decoding -> decode.job.cancel()
                is DecodeState.Uploading -> decode.job.cancel()
                else -> Unit
            }
            page.residents.forEach(uploader::release)
            page.residents = emptyList()
        }
        pages.clear()
    }
}
