package ml.melun.mangaview.data.network

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Bounded reuse of the direct read buffers [HttpEngineBodyPageStream] hands to HttpEngine. Every
 * body read used to allocate a fresh direct buffer; each stream only owns one buffer at a time, so
 * a finished stream returns its buffer and the next transfer of the same read size reuses it.
 * Retention is bounded per capacity, and a buffer is never handed to two streams: a repeated
 * release of an already-pooled buffer is ignored, and the stream itself releases at most once.
 */
internal class DirectByteBufferPool(
    private val maximumPerCapacity: Int = MAX_PER_CAPACITY,
) : Closeable {
    private val lock = Any()
    private val pooled = mutableMapOf<Int, ArrayDeque<ByteBuffer>>()

    init {
        require(maximumPerCapacity > 0) { "Direct buffer pool capacity must be positive" }
    }

    fun borrow(capacity: Int): ByteBuffer {
        require(capacity > 0) { "Direct buffer capacity must be positive" }
        return synchronized(lock) {
            pooled[capacity]?.removeFirstOrNull() ?: ByteBuffer.allocateDirect(capacity)
        }
    }

    fun release(buffer: ByteBuffer) {
        if (!buffer.isDirect || buffer.isReadOnly) return
        val capacity = buffer.capacity()
        synchronized(lock) {
            val buffers = pooled.getOrPut(capacity) { ArrayDeque() }
            if (buffers.size >= maximumPerCapacity) return
            if (buffers.any { it === buffer }) return
            buffer.clear()
            buffers.addLast(buffer)
        }
    }

    /** Drops retained references; buffers already borrowed by live streams stay valid. */
    override fun close() {
        synchronized(lock) { pooled.clear() }
    }

    companion object {
        const val MAX_PER_CAPACITY = 4
    }
}
