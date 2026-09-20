package ml.melun.mangaview.content

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.viewer.session.DemandSnapshot
import ml.melun.mangaview.viewer.session.DemandClass

data class ContentPipelineDispatchers(
    val network: CoroutineDispatcher,
    val hardDecode: CoroutineDispatcher,
    val warmDecode: CoroutineDispatcher,
    val upload: CoroutineDispatcher,
)

fun interface PipelineClock {
    fun nowMillis(): Long
}

class ViewerContentPipeline(
    parentContext: CoroutineContext,
    private val dispatchers: ContentPipelineDispatchers,
    private val rawPages: RawPagePort,
    private val decoder: ImageDecodePort,
    private val uploader: TextureUploadPort,
    private val sink: ContentPipelineSink,
    private val clock: PipelineClock = PipelineClock(System::currentTimeMillis),
    private val networkLimit: Int = 4,
    episodeManifests: EpisodeManifestPort? = null,
    private val residentMemoryBudgetBytes: Long = adaptiveResidentBudgetBytes(null),
    private val portTimeouts: PortTimeouts = PortTimeouts(),
) {
    init {
        require(networkLimit in 1..6)
        require(residentMemoryBudgetBytes > 0L)
    }

    private val ownerJob = SupervisorJob(parentContext[Job])
    private val shutdown = PipelineShutdown()
    private val scope = CoroutineScope(parentContext + ownerJob)
    private val commands = Channel<PipelineCommand>(64, onUndeliveredElement = PipelineCommand::releaseUndelivered)
    private val demandUpdates = Channel<PipelineCommand.UpdateDemand>(Channel.CONFLATED)
    private val workers = PipelineWorkers(scope, dispatchers, commands, decoder, uploader, portTimeouts)
    private val pages = LinkedHashMap<PageId, PageRecord>()
    private val retiring = RetiringPipelineWork()
    private var generation = 0L
    private var rendererEpoch = 0L
    private var displayWidthPx = 0
    private var viewportHeightPx = 0
    private var foreground = true
    private var networkRampOpen = false
    private var operationToken = 1L
    private val retryCoordinator = PipelineRetryCoordinator(scope, clock) {
        commands.sendCompletion(PipelineCommand.RetryDue)
    }
    private val episodeWork = PipelineEpisodeWork(
        scope, dispatchers.network, commands, episodeManifests, retryCoordinator, clock, sink, portTimeouts,
    )
    private var memoryPressure = false
    private var memoryPressureUntilMillis = 0L
    // A demand update can cross ahead of its generation's manifest register on the two channels;
    // stash the newest one instead of dropping the whole demand set for that generation.
    private var pendingDemand: PipelineCommand.UpdateDemand? = null
    private val pressureRequested = AtomicBoolean(false)
    private val latestSnapshot = AtomicReference(EMPTY_CONTENT_PIPELINE_SNAPSHOT)
    private val actor = scope.launch { commandLoop() }

    suspend fun registerManifest(generation: Long, manifest: EpisodeManifest) {
        commands.send(PipelineCommand.RegisterManifest(generation, manifest))
    }

    suspend fun updateDemand(
        snapshot: DemandSnapshot,
        displayWidthPx: Int,
        viewportHeightPx: Int,
    ) {
        require(displayWidthPx > 0 && viewportHeightPx > 0)
        demandUpdates.send(PipelineCommand.UpdateDemand(snapshot, displayWidthPx, viewportHeightPx))
    }

    fun offerDemand(snapshot: DemandSnapshot, displayWidthPx: Int, viewportHeightPx: Int) {
        require(displayWidthPx > 0 && viewportHeightPx > 0)
        demandUpdates.trySend(PipelineCommand.UpdateDemand(
            snapshot,
            displayWidthPx,
            viewportHeightPx,
        ))
    }

    suspend fun setForeground(value: Boolean) {
        commands.send(PipelineCommand.SetForeground(value))
    }

    suspend fun setRendererEpoch(epoch: Long) {
        require(epoch > 0L)
        commands.send(PipelineCommand.SetRendererEpoch(epoch))
    }

    fun onMemoryPressure() {
        pressureRequested.set(true)
        commands.trySend(PipelineCommand.MemoryPressure)
    }

    suspend fun snapshot(): ContentPipelineSnapshot {
        val reply = CompletableDeferred<ContentPipelineSnapshot>()
        commands.send(PipelineCommand.Snapshot(reply))
        return reply.await()
    }

    fun currentSnapshot(): ContentPipelineSnapshot = latestSnapshot.get()

    suspend fun closeAndJoin() = shutdown.close(ownerJob, actor, commands)

    private suspend fun commandLoop() {
        try {
            consumeCommands()
        } finally {
            commands.cancel()
            demandUpdates.cancel()
            releaseAll()
            retiring.clear()
        }
    }

    private suspend fun consumeCommands() {
        while (true) {
            val command = select<PipelineCommand?> {
                commands.onReceiveCatching { it.getOrNull() }
                demandUpdates.onReceiveCatching { it.getOrNull() }
            } ?: break
            try {
                handleCommand(command)
                if (pressureRequested.getAndSet(false)) trimForMemoryPressure()
                latestSnapshot.set(snapshotState())
                if (ownerJob.isActive && !shutdown.started) scheduleWork()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // One malformed command must never kill the actor; the remaining work continues.
                faultIsolate(command, failure)
            }
        }
    }

    private fun faultIsolate(command: PipelineCommand, failure: Throwable) {
        command.releaseUndelivered()
        settleFaultedOperation(command)
        runCatching { sink.emit(ContentPipelineEvent.PipelineFaulted(generation, failure)) }
    }

    /** Force-releases the operation a faulted handler was carrying so its lane cannot wedge. */
    private fun settleFaultedOperation(command: PipelineCommand) {
        when (command) {
            is PipelineCommand.FetchFinished -> {
                val active = pages[command.pageId]?.raw as? RawState.Fetching ?: return
                if (active.token != command.token) return
                pages[command.pageId]?.raw = RawState.Absent
            }
            is PipelineCommand.FetchStopped -> {
                val active = pages[command.pageId]?.raw as? RawState.Fetching ?: return
                if (active.token != command.token) return
                pages[command.pageId]?.raw = RawState.Stranded(active.token, active.job, active.cancelRequested)
            }
            is PipelineCommand.DecodeFinished -> {
                val active = pages[command.pageId]?.decode as? DecodeState.Decoding ?: return
                if (active.token != command.token) return
                pages[command.pageId]?.decode = DecodeState.Idle
            }
            is PipelineCommand.UploadFinished -> {
                val active = pages[command.pageId]?.decode as? DecodeState.Uploading ?: return
                if (active.token != command.token) return
                pages[command.pageId]?.decode = DecodeState.Idle
            }
            is PipelineCommand.DecodeStopped -> {
                val page = pages[command.pageId] ?: return
                val active = page.decode
                if (active is DecodeState.Decoding && !command.upload) {
                    if (active.token == command.token) page.decode = DecodeState.Idle
                } else if (active is DecodeState.Uploading && command.upload) {
                    if (active.token == command.token) page.decode = DecodeState.Idle
                }
            }
            else -> Unit
        }
    }

    private fun handleCommand(command: PipelineCommand) {
        when (command) {
            is PipelineCommand.FetchFinished -> fetchFinished(command)
            is PipelineCommand.FetchStopped -> acceptFetchStopped(command, generation, pages, retryCoordinator, sink)
            is PipelineCommand.FetchTimedOut -> fetchTimedOut(command)
            is PipelineCommand.DecodeStopped -> acceptDecodeStopped(command, generation, pages)
            is PipelineCommand.DecodeTimedOut -> decodeTimedOut(command)
            is PipelineCommand.FetchResponseStarted -> acceptFetchResponse(
                command, generation, pages, { networkRampOpen = true }, sink,
            )
            is PipelineCommand.DecodeFinished -> decodeFinished(command)
            is PipelineCommand.UploadFinished -> uploadFinished(command)
            else -> handleControl(command)
        }
    }

    private fun handleControl(command: PipelineCommand) {
        when (command) {
            is PipelineCommand.RegisterManifest -> register(command)
            is PipelineCommand.UpdateDemand -> updateDemand(command)
            is PipelineCommand.SetForeground -> handleForeground(command.foreground)
            is PipelineCommand.SetRendererEpoch -> handleRendererEpoch(command.epoch)
            is PipelineCommand.EpisodeFinished -> episodeWork.finished(command)
            is PipelineCommand.EpisodeStopped -> episodeWork.stopped(command)
            is PipelineCommand.EpisodeTimedOut -> episodeWork.timedOut(command)
            PipelineCommand.MemoryPressure -> Unit
            PipelineCommand.RetryDue -> retryDue()
            is PipelineCommand.Snapshot -> command.reply.complete(snapshotState())
            is PipelineCommand.Close -> {
                releaseAll()
                command.reply.complete(Unit)
                commands.close()
                demandUpdates.close()
            }
            else -> error("Worker result must be handled before control dispatch")
        }
    }

    private fun register(command: PipelineCommand.RegisterManifest) {
        if (command.generation < generation) return
        if (command.generation > generation) beginGeneration(command.generation)
        command.manifest.pages.forEach { page ->
            val record = pages.putIfAbsent(page.id, PageRecord(page))
            // A same-generation re-register carries the corrected spec; decode geometry must
            // follow the newest dimensions or the source-range invariant crashes the decoder.
            if (record != null && record.page != page) record.page = page
        }
        pendingDemand?.takeIf { it.snapshot.generation == generation }?.let(::applyDemand)
        pendingDemand = null
    }

    private fun updateDemand(command: PipelineCommand.UpdateDemand) {
        if (command.snapshot.generation > generation) {
            pendingDemand = command
            return
        }
        if (command.snapshot.generation != generation) return
        applyDemand(command)
    }

    private fun applyDemand(command: PipelineCommand.UpdateDemand) {
        displayWidthPx = command.displayWidthPx
        viewportHeightPx = command.viewportHeightPx
        episodeWork.demand(generation, command.snapshot.nextEpisode)
        val targets = command.snapshot.demands.mapIndexed { rank, demand ->
            demand.pageId to DemandTarget(demand.demandClass, demand.sourceRange, rank)
        }.toMap()
        if (pages.values.any { it.demand != targets[it.page.id] }) clearMemoryPressure()
        pages.values.forEach { page ->
            val previous = page.demand
            val current = targets[page.page.id]
            page.demand = current
            if (current != null) reviveFailedOperationOnPromotion(page, previous, current, retryCoordinator)
        }
        retargetPipelineOperations(pages.values)
        evictExcessResidents(
            pages.values, generation, displayWidthPx, viewportHeightPx, sink, uploader, residentMemoryBudgetBytes,
        )
    }

    private fun handleForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) {
            episodeWork.cancel()
            pages.values.forEach(::cancelActiveDecode)
        } else {
            clearMemoryPressure()
        }
    }

    private fun handleRendererEpoch(epoch: Long) {
        if (rendererEpoch == epoch) return
        pages.values.forEach { page ->
            page.residents.forEach(uploader::release)
            page.residents = emptyList()
            if (!cancelActiveDecode(page)) page.decode = DecodeState.Idle
            // The epoch resets the lane; its failure history belongs to the old renderer too.
            page.decodeFailures = 0
            page.decodeFailureReported = false
        }
        rendererEpoch = epoch
    }

    private fun beginGeneration(next: Long) {
        retiring.retain(pages.values)
        releaseAll()
        generation = next
        episodeWork.demand(next, null)
        networkRampOpen = false
    }

    private fun scheduleWork() {
        preemptFetchForHardDemand()
        scheduleFetches(priorityOnly = true)
        val viewportReady = pages.values.none {
            it.demand?.demandClass?.let(::hardLane) == true && it.raw !is RawState.Verified
        }
        episodeWork.schedule(networkCapacity(), foreground && networkRampOpen && viewportReady)
        scheduleFetches(priorityOnly = false)
        if (!foreground || rendererEpoch <= 0L || displayWidthPx <= 0) return
        scheduleDecodeLane(hard = true)
        if (!memoryPressure) scheduleDecodeLane(hard = false)
    }

    private fun networkCapacity(): Int = (if (networkRampOpen) networkLimit else 1) -
        pages.values.count { it.raw is RawState.Fetching } -
        retiring.records().count { it.raw is RawState.Fetching } - episodeWork.activeCount

    private fun scheduleFetches(priorityOnly: Boolean) {
        var capacity = networkCapacity()
        if (capacity <= 0) return
        pipelineCandidates(pages.values).filter { it.raw == RawState.Absent && !retiring.hasFetch(it.page.id) }
            .filter { !priorityOnly || requireNotNull(it.demand).demandClass <= DemandClass.CURRENT_FORWARD_NEAR }
            .take(capacity).forEach { page ->
            val token = nextToken()
            val pageId = page.page.id
            val priority = fetchPriority(requireNotNull(page.demand).demandClass, !networkRampOpen)
            val operationGeneration = generation
            val job = scope.launch(dispatchers.network) {
                val result = pipelineWorkerResult {
                    rawPages.find(pageId) ?: rawPages.fetch(pageId, priority) {
                        commands.trySend(PipelineCommand.FetchResponseStarted(
                            operationGeneration,
                            pageId,
                            token,
                        ))
                    }
                } ?: return@launch
                commands.sendCompletion(PipelineCommand.FetchFinished(
                    operationGeneration, pageId, token, result,
                ))
            }
            page.raw = RawState.Fetching(token, job)
            scope.notifyCancellation(job, commands, PipelineCommand.FetchStopped(operationGeneration, pageId, token))
            scope.launchPortWatchdog(job, portTimeouts.fetchMillis) {
                commands.sendCompletion(PipelineCommand.FetchTimedOut(operationGeneration, pageId, token))
            }
            capacity -= 1
        }
    }

    private fun preemptFetchForHardDemand() {
        if (networkCapacity() <= 0 && pages.values.any {
                it.raw == RawState.Absent && it.demand?.demandClass?.let(::hardLane) == true
            }) episodeWork.cancel()
        // Before the first verified response the lane holds a single slot; a hard demand must
        // still preempt the occupying background fetch, so the effective limit applies, not the
        // configured one.
        preemptObsoleteFetch(pages.values, if (networkRampOpen) networkLimit else 1)
    }

    private fun scheduleDecodeLane(hard: Boolean) {
        val plan = nextDecodePlan(pages.values, hard, rendererEpoch, displayWidthPx, retiring.records()) ?: return
        val reservation = decodeReservationBytes(plan, displayWidthPx)
        if (!canAdmitDecode(plan, reservation, pages.values, retiring.records(), residentMemoryBudgetBytes)) return
        plan.page.decode = workers.decode(plan, generation, displayWidthPx, nextToken())
    }

    private fun fetchFinished(command: PipelineCommand.FetchFinished) {
        val page = pages[command.pageId] ?: return
        val active = page.raw as? RawState.Fetching ?: return
        if (command.generation != generation || active.token != command.token || active.cancelRequested) return
        networkRampOpen = true
        command.result.fold(
            onSuccess = { encoded ->
                check(encoded.pageId == command.pageId)
                page.raw = RawState.Verified(encoded)
                page.fetchFailures = 0
                page.fetchFailureReported = false
                sink.emit(ContentPipelineEvent.RawVerified(generation, encoded))
            },
            onFailure = { failure ->
                handleFetchFailure(
                    page, command.pageId, failure, generation, retryCoordinator, sink,
                )
            },
        )
    }

    /** Releases the fetch lane when the physical call outlives its deadline. */
    private fun fetchTimedOut(command: PipelineCommand.FetchTimedOut) {
        val page = pages[command.pageId] ?: return
        val active = page.raw as? RawState.Fetching ?: return
        if (command.generation != generation || active.token != command.token) return
        page.raw = RawState.Stranded(active.token, active.job, active.cancelRequested)
    }

    /** Releases a decode or upload lane when the port outlives its deadline. */
    private fun decodeTimedOut(command: PipelineCommand.DecodeTimedOut) {
        val page = pages[command.pageId] ?: return
        if (command.generation != generation) return
        val active = page.decode
        val matches = when (active) {
            is DecodeState.Decoding -> command.phase == PipelineFailurePhase.DECODE && active.token == command.token
            is DecodeState.Uploading -> command.phase == PipelineFailurePhase.UPLOAD && active.token == command.token
            else -> false
        }
        if (!matches) return
        handleDecodeFailure(
            page, command.pageId, command.phase,
            PortSuspensionTimeoutException(command.phase.name.lowercase()),
            generation, retryCoordinator, sink,
        )
    }

    private fun decodeFinished(command: PipelineCommand.DecodeFinished) {
        val page = pages[command.pageId]
        val active = page?.decode as? DecodeState.Decoding
        if (page == null || active == null || command.generation != generation ||
            active.token != command.token || active.cancelRequested) {
            command.result.getOrNull()?.close()
            return
        }
        command.result.fold(
            onSuccess = { pixels ->
                page.decode = workers.upload(page, generation, rendererEpoch, command.token,
                    command.range, active.hardLane, pixels.take())
            },
            onFailure = { failure ->
                handleDecodeFailure(
                    page, command.pageId, PipelineFailurePhase.DECODE, failure,
                    generation, retryCoordinator, sink,
                )
            },
        )
    }

    private fun uploadFinished(command: PipelineCommand.UploadFinished) {
        val page = pages[command.pageId]
        val active = page?.decode as? DecodeState.Uploading
        if (page == null || active == null || command.generation != generation ||
            active.token != command.token || active.cancelRequested) {
            command.result.getOrNull()?.close()
            return
        }
        command.result.fold(
            onSuccess = { handoff ->
                val texture = handoff.take()
                if (texture.rendererEpoch != rendererEpoch) {
                    uploader.release(texture)
                } else {
                    page.residents = page.residents + texture
                    val evicted = evictExcessResidents(
                        pages.values, generation, displayWidthPx, viewportHeightPx, sink, uploader,
                        residentMemoryBudgetBytes,
                    )
                    if (texture !in evicted) {
                        sink.emit(ContentPipelineEvent.TextureReady(generation, texture))
                    }
                }
                page.decode = DecodeState.Idle
                page.decodeFailures = 0
                page.decodeFailureReported = false
            },
            onFailure = { failure ->
                handleDecodeFailure(
                    page, command.pageId, PipelineFailurePhase.UPLOAD, failure,
                    generation, retryCoordinator, sink,
                )
            },
        )
    }

    private fun retryDue() {
        releaseDueRetries(retryCoordinator, pages)
        if (memoryPressure && clock.nowMillis() >= memoryPressureUntilMillis) {
            memoryPressure = false
            memoryPressureUntilMillis = 0L
        }
    }

    private fun releaseAll() {
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

    private fun snapshotState(): ContentPipelineSnapshot = contentPipelineSnapshot(
        generation, rendererEpoch, pages.values, retryCoordinator.wakeupCount, retiring.records(),
    ).copy(activeManifests = episodeWork.activeCount)

    private fun trimForMemoryPressure() {
        memoryPressure = true
        memoryPressureUntilMillis = clock.nowMillis() + MEMORY_PRESSURE_COOLDOWN_MILLIS
        retryCoordinator.memoryPressureWakeup(memoryPressureUntilMillis)
        pages.values.filter { it.demand?.demandClass?.let(::hardLane) != true }
            .forEach(::cancelActiveDecode)
        evictColdResidents(pages.values, generation, sink, uploader)
    }

    private fun clearMemoryPressure() {
        if (!memoryPressure && memoryPressureUntilMillis == 0L) return
        memoryPressure = false
        memoryPressureUntilMillis = 0L
        retryCoordinator.memoryPressureWakeup(null)
    }

    private fun nextToken(): Long = operationToken++.also { check(it > 0L) }
}
