package ml.melun.mangaview.data.network

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import ml.melun.mangaview.source.PageByteStream

/** One direct buffer and one consumer read form a bounded pull bridge to HttpEngine. */
internal class HttpEngineBodyPageStream(
    private val expectedLength: Long?,
    private val requestRead: (ByteBuffer) -> Unit,
    private val cancelExchange: (Throwable) -> Unit,
    private val finished: (HttpEngineBodyPageStream) -> Unit,
    private val maximumBytes: Long = MAX_BODY_BYTES,
) : PageByteStream {
    private val lock = Any()
    private val readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_BYTES)
    private val cancelSignaled = AtomicBoolean(false)
    private var pending: PendingRead? = null
    private var receivedBytes = 0L
    private var requestComplete = false
    private var failure: Throwable? = null
    private var closed = false

    init {
        require(maximumBytes > 0L) { "HTTP engine body limit must be positive" }
        require(expectedLength == null || expectedLength in 1..maximumBytes) {
            "HTTP engine content length is outside its bounds"
        }
    }

    override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
        require(offset >= 0 && byteCount >= 0 && offset <= destination.size - byteCount) {
            "HTTP engine body read range is invalid"
        }
        if (byteCount == 0) return 0
        return suspendCancellableCoroutine { continuation ->
            enqueue(PendingRead(destination, offset, byteCount, continuation))
        }
    }

    fun onReadCompleted(buffer: ByteBuffer) {
        if (buffer !== readBuffer) {
            failFromBody(IOException("HTTP engine returned an unowned read buffer"))
            return
        }
        buffer.flip()
        val count = buffer.remaining()
        if (count <= 0) {
            failFromBody(IOException("HTTP engine returned a zero-byte body chunk"))
            return
        }
        val outcome = synchronized(lock) {
            val waiter = pending
            when {
                failure != null || closed -> ReadOutcome.Ignore
                requestComplete -> failureOutcome("HTTP engine read completed after request termination")
                waiter == null -> failureOutcome("HTTP engine read completed without consumer demand")
                count > waiter.byteCount -> failureOutcome("HTTP engine exceeded consumer demand")
                receivedBytes > maximumBytes - count -> failureOutcome(
                    "HTTP engine response exceeds $maximumBytes bytes",
                )
                expectedLength != null && receivedBytes > expectedLength - count -> failureOutcome(
                    "HTTP engine response exceeds declared content length",
                )
                else -> {
                    buffer.get(waiter.destination, waiter.offset, count)
                    receivedBytes += count
                    pending = null
                    ReadOutcome.Bytes(waiter, count)
                }
            }
        }
        complete(outcome)
    }

    fun completeSuccess() {
        val outcome = synchronized(lock) {
            if (requestComplete || closed) return
            requestComplete = true
            val lengthFailure = when {
                receivedBytes == 0L -> IOException("HTTP engine response body is empty")
                expectedLength != null && receivedBytes != expectedLength -> IOException(
                    "HTTP engine response length $receivedBytes does not match $expectedLength",
                )
                else -> null
            }
            if (lengthFailure == null) {
                pending?.let { ReadOutcome.End(it) } ?: ReadOutcome.Ignore
            } else {
                failure = lengthFailure
                pending?.let { ReadOutcome.Failed(it, lengthFailure, cancel = false) }
                    ?: ReadOutcome.Ignore
            }.also { pending = null }
        }
        complete(outcome)
    }

    fun fail(cause: Throwable) {
        val outcome = synchronized(lock) {
            if (requestComplete || closed) return
            requestComplete = true
            failure = cause
            pending?.let { ReadOutcome.Failed(it, cause, cancel = false) }
                ?.also { pending = null } ?: ReadOutcome.Ignore
        }
        complete(outcome)
    }

    override fun close() {
        val outcome = synchronized(lock) {
            if (closed) return
            closed = true
            val cause = failure ?: IOException("HTTP engine body stream is closed")
            failure = cause
            val cancel = !requestComplete
            pending?.let { ReadOutcome.Failed(it, cause, cancel) }
                ?.also { pending = null } ?: ReadOutcome.CancelOnly(cancel, cause)
        }
        complete(outcome)
        finished(this)
    }

    private fun enqueue(waiter: PendingRead) {
        val immediate = synchronized(lock) {
            when {
                closed -> ReadOutcome.Failed(waiter, IOException("HTTP engine body stream is closed"), false)
                failure != null -> ReadOutcome.Failed(waiter, requireNotNull(failure), false)
                requestComplete -> ReadOutcome.End(waiter)
                pending != null -> ReadOutcome.Failed(
                    waiter,
                    IllegalStateException("Concurrent HTTP engine body reads are forbidden"),
                    false,
                )
                else -> {
                    pending = waiter
                    readBuffer.clear()
                    readBuffer.limit(minOf(readBuffer.capacity(), waiter.byteCount))
                    null
                }
            }
        }
        if (immediate != null) {
            complete(immediate)
            return
        }
        waiter.continuation.invokeOnCancellation { cancelPending(waiter) }
        if (synchronized(lock) { pending === waiter && !closed && failure == null }) {
            runCatching { requestRead(readBuffer) }.onFailure(::failFromBody)
        }
    }

    private fun cancelPending(waiter: PendingRead) {
        val cause = IOException("HTTP engine body consumer was canceled")
        val shouldCancel = synchronized(lock) {
            if (pending !== waiter || requestComplete || closed) return
            pending = null
            failure = cause
            true
        }
        if (shouldCancel) signalCancel(cause)
    }

    private fun failFromBody(cause: Throwable) {
        val outcome = synchronized(lock) {
            if (requestComplete || closed || failure != null) return
            failure = cause
            pending?.let { ReadOutcome.Failed(it, cause, cancel = true) }
                ?.also { pending = null } ?: ReadOutcome.CancelOnly(true, cause)
        }
        complete(outcome)
    }

    private fun failureOutcome(message: String): ReadOutcome {
        val cause = IOException(message)
        failure = cause
        val waiter = pending
        pending = null
        return waiter?.let { ReadOutcome.Failed(it, cause, cancel = true) }
            ?: ReadOutcome.CancelOnly(true, cause)
    }

    private fun complete(outcome: ReadOutcome) {
        when (outcome) {
            is ReadOutcome.Bytes -> resume(outcome.waiter, outcome.count)
            is ReadOutcome.End -> resume(outcome.waiter, -1)
            is ReadOutcome.Failed -> {
                resumeFailure(outcome.waiter, outcome.cause)
                if (outcome.cancel) signalCancel(outcome.cause)
            }
            is ReadOutcome.CancelOnly -> if (outcome.cancel) signalCancel(outcome.cause)
            ReadOutcome.Ignore -> Unit
        }
    }

    private fun signalCancel(cause: Throwable) {
        if (cancelSignaled.compareAndSet(false, true)) cancelExchange(cause)
    }

    private fun resume(waiter: PendingRead, value: Int) {
        if (waiter.continuation.isActive) {
            waiter.continuation.resume(value) { _, _, _ -> }
        }
    }

    private fun resumeFailure(waiter: PendingRead, cause: Throwable) {
        if (waiter.continuation.isActive) waiter.continuation.resumeWithException(cause)
    }

    private data class PendingRead(
        val destination: ByteArray,
        val offset: Int,
        val byteCount: Int,
        val continuation: CancellableContinuation<Int>,
    )

    private sealed interface ReadOutcome {
        data class Bytes(val waiter: PendingRead, val count: Int) : ReadOutcome
        data class End(val waiter: PendingRead) : ReadOutcome
        data class Failed(
            val waiter: PendingRead,
            val cause: Throwable,
            val cancel: Boolean,
        ) : ReadOutcome
        data class CancelOnly(val cancel: Boolean, val cause: Throwable) : ReadOutcome
        data object Ignore : ReadOutcome
    }

    companion object {
        const val MAX_BODY_BYTES = 512L * 1_024L * 1_024L
        private const val READ_BUFFER_BYTES = 128 * 1_024
    }
}
