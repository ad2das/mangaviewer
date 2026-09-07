package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import ml.melun.mangaview.engine.api.FrameIdentity

internal data class EngineRasterizationInfo(val subpixelBits: Int, val sampleBuffers: Int, val samples: Int)

internal data class EngineCapturedFrame(
    val rendererId: Long,
    val identity: FrameIdentity,
    val scene: EngineSurfaceScene,
    val pixels: EngineReadbackPacket,
    val rasterizationInfo: EngineRasterizationInfo? = null,
) {
    init {
        require(rendererId > 0 && pixels.sessionId == identity.sessionId && pixels.token == identity.token &&
            pixels.rendererEpoch == identity.rendererEpoch && pixels.surfaceEpoch == identity.surfaceEpoch)
        if (pixels.status == EngineReadbackPacket.Status.OK) require(pixels.width == scene.viewport.widthPx.toLong())
    }
}

/** GL-thread state for one capture awaiting a natural submission. Never supplies or redraws a scene. */
internal class EngineNextFrameCapture {
    private var pending: Ticket? = null

    fun request(surfaceEpoch: Long, top: Int, bottom: Int?): Ticket {
        check(pending == null) { "A capture already awaits the next frame" }
        return Ticket(surfaceEpoch, top, bottom).also { pending = it }
    }

    fun bind(rendererId: Long, identity: FrameIdentity, scene: EngineSurfaceScene, readbacks: EngineSurfaceReadbacks,
             rasterizationInfo: (() -> EngineRasterizationInfo)? = null) {
        val ticket = pending ?: return
        pending = null
        try {
            check(ticket.surfaceEpoch == identity.surfaceEpoch) { "Surface changed before the captured frame" }
            val bottom = ticket.bottom ?: scene.viewport.heightPx
            require(ticket.top >= 0 && bottom > ticket.top && bottom <= scene.viewport.heightPx)
            ticket.rendererId = rendererId
            ticket.identity = identity
            ticket.scene = scene
            ticket.rasterizationInfo = rasterizationInfo?.invoke()
            val receipt = readbacks.request(identity, ticket.top, bottom)
            ticket.receipt = receipt
            ticket.bound.complete(receipt)
        } catch (failure: Throwable) { ticket.bound.completeExceptionally(failure) }
    }

    fun cancel(ticket: Ticket): CompletableDeferred<EngineReadbackPacket>? {
        if (pending === ticket) {
            pending = null
            ticket.bound.cancel()
        }
        return ticket.receipt
    }

    fun invalidate() {
        pending?.bound?.completeExceptionally(CancellationException("Surface ended before the next frame"))
        pending = null
    }

    class Ticket(val surfaceEpoch: Long, val top: Int, val bottom: Int?) {
        val bound = CompletableDeferred<CompletableDeferred<EngineReadbackPacket>>()
        var receipt: CompletableDeferred<EngineReadbackPacket>? = null
        var rendererId = 0L
        var identity: FrameIdentity? = null
        var scene: EngineSurfaceScene? = null
        var rasterizationInfo: EngineRasterizationInfo? = null
        fun result(packet: EngineReadbackPacket) = EngineCapturedFrame(rendererId, requireNotNull(identity), requireNotNull(scene), packet, rasterizationInfo)
    }
}
