package ml.melun.mangaview.content

import kotlinx.coroutines.CancellationException
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.session.DemandClass

internal suspend fun <T> pipelineWorkerResult(block: suspend () -> T): Result<T>? = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    null
} catch (failure: Throwable) {
    Result.failure(failure)
}

internal fun handleFetchFailure(
    page: PageRecord,
    pageId: PageId,
    failure: Throwable,
    generation: Long,
    retries: PipelineRetryCoordinator,
    sink: ContentPipelineSink,
) {
    page.fetchFailures += 1
    if (page.fetchFailures <= MAX_FETCH_RETRIES) {
        page.raw = RawState.WaitingRetry(retries.add(pageId, page.fetchFailures))
        return
    }
    page.raw = RawState.Failed
    sink.emit(ContentPipelineEvent.PageFailed(
        generation,
        pageId,
        PipelineFailurePhase.FETCH,
        page.demand?.demandClass ?: DemandClass.BEHIND,
        failure,
    ))
}

internal fun handleDecodeFailure(
    page: PageRecord,
    pageId: PageId,
    phase: PipelineFailurePhase,
    failure: Throwable,
    generation: Long,
    retries: PipelineRetryCoordinator,
    sink: ContentPipelineSink,
) {
    page.decodeFailures += 1
    if (page.decodeFailures <= MAX_DECODE_RETRIES) {
        val retryAt = retries.add(pageId, page.decodeFailures)
        page.decode = DecodeState.WaitingRetry(retryAt)
        return
    }
    page.decode = DecodeState.Failed
    sink.emit(ContentPipelineEvent.PageFailed(
        generation,
        pageId,
        phase,
        page.demand?.demandClass ?: DemandClass.BEHIND,
        failure,
    ))
}
