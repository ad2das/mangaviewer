package ml.melun.mangaview.data.network

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectByteBufferPoolTest {
    @Test
    fun releasedBufferIsReusedForTheSameCapacity() {
        val pool = DirectByteBufferPool()
        val buffer = pool.borrow(128)

        assertEquals(128, buffer.capacity())
        assertTrue(buffer.isDirect)
        pool.release(buffer)

        assertSame(buffer, pool.borrow(128))
    }

    @Test
    fun releaseNormalizesPositionAndLimitBeforeReuse() {
        val pool = DirectByteBufferPool()
        val buffer = pool.borrow(64)
        buffer.position(10)
        buffer.limit(20)

        pool.release(buffer)

        val reused = pool.borrow(64)
        assertEquals(0, reused.position())
        assertEquals(64, reused.limit())
    }

    @Test
    fun capacitiesArePooledSeparately() {
        val pool = DirectByteBufferPool()
        val small = pool.borrow(64)
        val large = pool.borrow(256)

        pool.release(small)
        pool.release(large)

        assertSame(small, pool.borrow(64))
        assertSame(large, pool.borrow(256))
    }

    @Test
    fun doubleReleaseIsIgnored() {
        val pool = DirectByteBufferPool(maximumPerCapacity = 2)
        val first = pool.borrow(64)
        val second = pool.borrow(64)

        pool.release(first)
        pool.release(first)
        pool.release(second)

        // FIFO reuse: the first released buffer comes back first, and the duplicate release did
        // not consume the second slot or hand the same buffer out twice.
        assertSame(first, pool.borrow(64))
        assertSame(second, pool.borrow(64))
        assertNotSame(first, pool.borrow(64))
    }

    @Test
    fun retentionIsBoundedPerCapacity() {
        val pool = DirectByteBufferPool(maximumPerCapacity = 2)
        val buffers = List(4) { pool.borrow(32) }
        buffers.forEach(pool::release)

        // Only the first two released buffers are retained; the rest were dropped.
        assertSame(buffers[0], pool.borrow(32))
        assertSame(buffers[1], pool.borrow(32))
        val fresh = pool.borrow(32)
        assertNotSame(buffers[2], fresh)
        assertNotSame(buffers[3], fresh)
    }

    @Test
    fun closedPoolAllocatesFreshBuffers() {
        val pool = DirectByteBufferPool()
        val buffer = pool.borrow(64)
        pool.release(buffer)

        pool.close()

        assertNotSame(buffer, pool.borrow(64))
    }

    @Test
    fun heapBuffersAreNeverPooled() {
        val pool = DirectByteBufferPool()
        val heap = ByteBuffer.allocate(64)

        pool.release(heap)

        assertTrue(pool.borrow(64).isDirect)
    }
}
