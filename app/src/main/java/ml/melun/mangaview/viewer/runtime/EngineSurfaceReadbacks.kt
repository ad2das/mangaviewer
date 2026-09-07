package ml.melun.mangaview.viewer.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import ml.melun.mangaview.engine.api.FrameIdentity

/** GL-thread receipts for the native, bounded PBO/fence owner. */
internal class EngineSurfaceReadbacks(private val native: Long) {
    private class Waiting(val frame: FrameIdentity, val top: Int, val bottom: Int,
        val receiver: CompletableDeferred<EngineReadbackPacket>)
    private val waiting = linkedMapOf<Long, Waiting>()
    val pending: Boolean get() = waiting.isNotEmpty()

    fun request(frame: FrameIdentity, top: Int, bottom: Int): CompletableDeferred<EngineReadbackPacket> {
        check(frame.token !in waiting)
        check(OwnedRendererBridge.nativeRequestReadback(native, frame.token, frame.sessionId,
            frame.rendererEpoch, frame.surfaceEpoch, top, bottom)) { "Native readback admission rejected" }
        return CompletableDeferred<EngineReadbackPacket>().also { waiting[frame.token] = Waiting(frame, top, bottom, it) }
    }

    fun poll() {
        waiting.keys.toList().forEach { token ->
            val packet = OwnedRendererBridge.nativeTakeReadback(native, token) ?: return@forEach
            val expected = waiting.remove(token) ?: return@forEach
            try {
                val parsed = EngineReadbackPacket.parse(packet)
                require(parsed.token == expected.frame.token && parsed.sessionId == expected.frame.sessionId &&
                    parsed.rendererEpoch == expected.frame.rendererEpoch && parsed.surfaceEpoch == expected.frame.surfaceEpoch &&
                    parsed.top == expected.top.toLong() && parsed.bottom == expected.bottom.toLong()) { "Readback belongs to another frame or strip" }
                expected.receiver.complete(parsed)
            } catch (failure: Throwable) { expected.receiver.completeExceptionally(failure) }
        }
    }

    /** Called only after native context destruction has settled every PBO and fence. */
    fun destroyed() {
        waiting.values.forEach { it.receiver.completeExceptionally(CancellationException("Renderer destroyed during readback")) }
        waiting.clear()
    }
}
