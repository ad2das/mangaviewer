package ml.melun.mangaview.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest

/** State accessed only by the content actor; workers carry results and never choose demand. */
internal class PipelineEpisodeWork(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val commands: SendChannel<PipelineCommand>,
    private val source: EpisodeManifestPort?,
    private val retries: PipelineRetryCoordinator,
    private val clock: PipelineClock,
    private val sink: ContentPipelineSink,
) {
    private var generation = 0L
    private var target: EpisodeId? = null
    private var completed = false
    private var failures = 0
    private var retryAt = 0L
    private var sequence = 0L
    private var active: Active? = null
    val activeCount: Int get() = if (active != null) 1 else 0

    fun demand(nextGeneration: Long, episode: EpisodeId?) {
        if (generation == nextGeneration && target == episode) return
        cancel()
        generation = nextGeneration
        target = episode
        completed = false
        failures = 0
        retryAt = 0L
        retries.episodeRetry(null)
    }

    fun cancel() {
        active?.let { it.cancelRequested = true; it.job.cancel() }
    }

    fun schedule(capacity: Int, allowed: Boolean) {
        val port = source ?: return
        val episode = target ?: return
        if (active != null || capacity <= 0 || !allowed || completed ||
            failures > MAX_FETCH_RETRIES || retryAt > clock.nowMillis()) return
        val token = ++sequence
        val job = scope.launch(dispatcher) {
            val result = pipelineWorkerResult {
                port.load(episode).also { require(it.id == episode) { "Wrong adjacent episode manifest" } }
            } ?: return@launch
            sendIfOpen(PipelineCommand.EpisodeFinished(token, result))
        }
        active = Active(token, generation, episode, job)
        job.invokeOnCompletion {
            scope.launch { sendIfOpen(PipelineCommand.EpisodeStopped(token)) }
        }
    }

    private suspend fun sendIfOpen(command: PipelineCommand) {
        try { commands.send(command) } catch (_: ClosedSendChannelException) {
            // The actor already retired the operation during joined shutdown.
        }
    }

    fun finished(command: PipelineCommand.EpisodeFinished) {
        active?.takeIf { it.token == command.token }?.result = command.result
    }

    fun stopped(command: PipelineCommand.EpisodeStopped) {
        val old = active?.takeIf { it.token == command.token } ?: return
        check(old.job.isCompleted) { "Manifest capacity released before physical completion" }
        active = null
        if (old.cancelRequested || old.generation != generation || old.episode != target) return
        val result = old.result ?: return
        result.fold(onSuccess = {
            completed = true
            sink.emit(ContentPipelineEvent.ManifestReady(generation, it))
        }, onFailure = {
            failures += 1
            if (failures <= MAX_FETCH_RETRIES) {
                retryAt = clock.nowMillis() + retryDelay(failures)
                retries.episodeRetry(retryAt)
            } else sink.emit(ContentPipelineEvent.ManifestFailed(generation, old.episode, it))
        })
    }

    private data class Active(
        val token: Long,
        val generation: Long,
        val episode: EpisodeId,
        val job: Job,
        var cancelRequested: Boolean = false,
        var result: Result<EpisodeManifest>? = null,
    )
}
