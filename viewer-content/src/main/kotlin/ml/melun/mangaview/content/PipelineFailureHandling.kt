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
    val demanded = page.demand != null
    val terminal = page.fetchFailures >= MAX_FETCH_RETRIES + MAX_AUTO_RETRIES
    if (terminal || (!demanded && page.fetchFailures > MAX_FETCH_RETRIES)) {
        page.raw = RawState.Failed
        reportFetchFailure(page, pageId, generation, failure, sink)
        return
    }
    page.raw = RawState.WaitingRetry(retries.add(pageId, page.fetchFailures))
    // A demanded page keeps retrying in the background, but the reader still gets one failure
    // signal when the fast retries run out so a broken page is never silently stuck.
    if (page.fetchFailures > MAX_FETCH_RETRIES) {
        reportFetchFailure(page, pageId, generation, failure, sink)
    }
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
    val demanded = page.demand != null
    val terminal = page.decodeFailures >= MAX_DECODE_RETRIES + MAX_AUTO_RETRIES
    if (terminal || (!demanded && page.decodeFailures > MAX_DECODE_RETRIES)) {
        page.decode = DecodeState.Failed
        reportDecodeFailure(page, pageId, phase, generation, failure, sink)
        return
    }
    page.decode = DecodeState.WaitingRetry(retries.add(pageId, page.decodeFailures))
    if (page.decodeFailures > MAX_DECODE_RETRIES) {
        reportDecodeFailure(page, pageId, phase, generation, failure, sink)
    }
}

private fun reportFetchFailure(
    page: PageRecord,
    pageId: PageId,
    generation: Long,
    failure: Throwable,
    sink: ContentPipelineSink,
) {
    if (page.fetchFailureReported) return
    page.fetchFailureReported = true
    sink.emit(ContentPipelineEvent.PageFailed(
        generation,
        pageId,
        PipelineFailurePhase.FETCH,
        page.demand?.demandClass ?: DemandClass.BEHIND,
        failure,
    ))
}

private fun reportDecodeFailure(
    page: PageRecord,
    pageId: PageId,
    phase: PipelineFailurePhase,
    generation: Long,
    failure: Throwable,
    sink: ContentPipelineSink,
) {
    if (page.decodeFailureReported) return
    page.decodeFailureReported = true
    sink.emit(ContentPipelineEvent.PageFailed(
        generation,
        pageId,
        phase,
        page.demand?.demandClass ?: DemandClass.BEHIND,
        failure,
    ))
}
