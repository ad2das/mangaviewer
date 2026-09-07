package ml.melun.mangaview.content

internal fun acceptFetchResponse(
    command: PipelineCommand.FetchResponseStarted,
    generation: Long,
    pages: Map<ml.melun.mangaview.core.PageId, PageRecord>,
    opened: () -> Unit,
    sink: ContentPipelineSink,
) {
    val active = pages[command.pageId]?.raw as? RawState.Fetching ?: return
    if (command.generation != generation || active.token != command.token) return
    opened()
    sink.emit(ContentPipelineEvent.ResponseStarted(generation, command.pageId))
}

internal fun releaseDueRetries(
    retries: PipelineRetryCoordinator,
    pages: Map<ml.melun.mangaview.core.PageId, PageRecord>,
) {
    retries.removeDue().forEach { pageId ->
        val page = pages[pageId] ?: return@forEach
        if (page.raw is RawState.WaitingRetry) page.raw = RawState.Absent
        if (page.decode is DecodeState.WaitingRetry) page.decode = DecodeState.Idle
    }
}
