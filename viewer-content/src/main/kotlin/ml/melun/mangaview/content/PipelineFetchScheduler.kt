package ml.melun.mangaview.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.session.DemandClass

/** The fetch lane: admission, preemption, completion and deadline bookkeeping. */
internal class PipelineFetchScheduler(
    private val scope: CoroutineScope,
    private val pages: LinkedHashMap<PageId, PageRecord>,
    private val commands: Channel<PipelineCommand>,
    private val retiring: RetiringPipelineWork,
    private val episodeWork: PipelineEpisodeWork,
    private val retryCoordinator: PipelineRetryCoordinator,
    private val sink: ContentPipelineSink,
    private val rawPages: RawPagePort,
    private val networkDispatcher: CoroutineDispatcher,
    private val portTimeouts: PortTimeouts,
    private val networkLimit: Int,
    private val networkRampOpen: () -> Boolean,
    private val openNetworkRamp: () -> Unit,
    private val generation: () -> Long,
    private val token: () -> Long,
) {
    fun schedule(priorityOnly: Boolean) {
        var capacity = capacity()
        if (capacity <= 0) return
        pipelineCandidates(pages.values).filter { it.raw == RawState.Absent && !retiring.hasFetch(it.page.id) }
            .filter { !priorityOnly || requireNotNull(it.demand).demandClass <= DemandClass.CURRENT_FORWARD_NEAR }
            .take(capacity).forEach { page ->
                val operationToken = token()
                val pageId = page.page.id
                val priority = fetchPriority(requireNotNull(page.demand).demandClass, !networkRampOpen())
                val operationGeneration = generation()
                val job = scope.launch(networkDispatcher) {
                    val result = pipelineWorkerResult {
                        rawPages.find(pageId) ?: rawPages.fetch(pageId, priority) {
                            commands.trySend(PipelineCommand.FetchResponseStarted(
                                operationGeneration,
                                pageId,
                                operationToken,
                            ))
                        }
                    } ?: return@launch
                    commands.sendCompletion(PipelineCommand.FetchFinished(
                        operationGeneration, pageId, operationToken, result,
                    ))
                }
                page.raw = RawState.Fetching(operationToken, job)
                scope.notifyCancellation(
                    job, commands, PipelineCommand.FetchStopped(operationGeneration, pageId, operationToken),
                )
                scope.launchPortWatchdog(job, portTimeouts.fetchMillis) {
                    commands.sendCompletion(PipelineCommand.FetchTimedOut(operationGeneration, pageId, operationToken))
                }
                capacity -= 1
            }
    }

    fun preemptForHardDemand() {
        if (capacity() <= 0 && pages.values.any {
                it.raw == RawState.Absent && it.demand?.demandClass?.let(::hardLane) == true
            }) episodeWork.cancel()
        // Before the first verified response the lane holds a single slot; a hard demand must
        // still preempt the occupying background fetch, so the effective limit applies, not the
        // configured one.
        preemptObsoleteFetch(pages.values, if (networkRampOpen()) networkLimit else 1)
    }

    fun finished(command: PipelineCommand.FetchFinished) {
        val page = pages[command.pageId] ?: return
        val active = page.raw as? RawState.Fetching ?: return
        if (command.generation != generation() || active.token != command.token || active.cancelRequested) return
        openNetworkRamp()
        command.result.fold(
            onSuccess = { encoded ->
                check(encoded.pageId == command.pageId)
                page.raw = RawState.Verified(encoded)
                page.fetchFailures = 0
                page.fetchFailureReported = false
                sink.emit(ContentPipelineEvent.RawVerified(generation(), encoded))
            },
            onFailure = { failure ->
                handleFetchFailure(
                    page, command.pageId, failure, generation(), retryCoordinator, sink,
                )
            },
        )
    }

    /** Releases the fetch lane when the physical call outlives its deadline. */
    fun timedOut(command: PipelineCommand.FetchTimedOut) {
        val page = pages[command.pageId] ?: return
        val active = page.raw as? RawState.Fetching ?: return
        if (command.generation != generation() || active.token != command.token) return
        page.raw = RawState.Stranded(active.token, active.job, active.cancelRequested)
    }

    fun capacity(): Int = (if (networkRampOpen()) networkLimit else 1) -
        pages.values.count { it.raw is RawState.Fetching } -
        retiring.records().count { it.raw is RawState.Fetching } - episodeWork.activeCount
}
