package ml.melun.mangaview.source.ntk

import android.os.Message

/** Serial, service-main-thread subscriber cleanup. Detachment is not renderer reclamation. */
internal class NtkBrowserRequestCancellation(
    private val current: () -> RemoteRequest?,
    private val finish: (RemoteRequest, Boolean) -> Unit,
    private val forgetDelivery: (Long) -> Unit,
) {
    fun cancel(message: Message, quiesce: Boolean) {
        val requestId = message.data.getLong(NtkBrowserProtocol.KEY_REQUEST_ID, INVALID_REQUEST_ID)
        if (requestId <= 0L) {
            message.replyTo?.let { sendError(requestId, it, "NTK browser request id is invalid") }
            return
        }
        current()?.takeIf { it.contains(requestId) }?.let { request ->
            request.remove(requestId)
            // One subscriber must not park or destroy another subscriber's document.
            if (request.isEmpty()) finish(request, quiesce)
        }
        forgetDelivery(requestId)
        // Only emitted after synchronous cleanup succeeds. Parked scripts may still run.
        message.replyTo?.let { sendRequestDetached(requestId, it) }
    }
}
