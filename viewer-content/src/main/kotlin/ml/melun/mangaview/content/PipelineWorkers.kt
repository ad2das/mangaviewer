package ml.melun.mangaview.content

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Executes actor-selected work; owns only worker-to-actor resource handoff and completion. */
internal class PipelineWorkers(
    private val scope: CoroutineScope,
    private val dispatchers: ContentPipelineDispatchers,
    private val commands: SendChannel<PipelineCommand>,
    private val decoder: ImageDecodePort,
    private val uploader: TextureUploadPort,
) {
    fun decode(plan: PipelineDecodePlan, generation: Long, width: Int, token: Long): DecodeState.Decoding {
        val pageId = plan.page.page.id
        val request = DecodeRequest(generation, plan.page.page, plan.encoded, plan.encoded.dimensions,
            width, plan.range, plan.target.demandClass)
        val dispatcher = if (plan.hard) dispatchers.hardDecode else dispatchers.warmDecode
        val job = scope.launch(dispatcher) {
            val result = pipelineWorkerResult {
                ResourceHandoff(decoder.decode(request), CpuTileLease::close)
            } ?: return@launch
            commands.sendCompletion(PipelineCommand.DecodeFinished(generation, pageId, token, plan.range, result))
        }
        scope.notifyCancellation(job, commands, PipelineCommand.DecodeStopped(generation, pageId, token))
        return DecodeState.Decoding(token, plan.range, plan.hard, job,
            reservedByteCount = decodeReservationBytes(plan, width))
    }

    fun upload(page: PageRecord, generation: Long, rendererEpoch: Long, token: Long,
        range: SourceRowRange, hard: Boolean, pixels: CpuTileLease): DecodeState.Uploading {
        val pageId = page.page.id
        val reservation = (page.decode as? DecodeState.Decoding)?.reservedByteCount ?: pixels.byteCount
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var handedOff = false
            var completed: ResourceHandoff<TextureRef>? = null
            var delivered = false
            try {
                val result = pipelineWorkerResult {
                    withContext(dispatchers.upload) {
                        handedOff = true
                        ResourceHandoff(uploader.upload(rendererEpoch, pixels), uploader::release)
                            .also { completed = it }
                    }
                } ?: return@launch
                commands.sendCompletion(PipelineCommand.UploadFinished(generation, pageId, token, result))
                delivered = true
            } finally {
                if (!delivered) completed?.close()
                if (!handedOff) pixels.close()
            }
        }
        scope.notifyCancellation(job, commands, PipelineCommand.DecodeStopped(generation, pageId, token, upload = true))
        return DecodeState.Uploading(token, range, hard, job, reservedByteCount = reservation)
    }
}
