package ml.melun.mangaview.data.network

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpEngineBodyPageStreamTest {
    @Test
    fun totalTimeoutIncludesSetupTimeAndCannotOverflow() {
        assertEquals(45_000_000_000L, httpEngineRemainingTimeoutNanos(45_000L, 10L, 10L))
        assertEquals(1L, httpEngineRemainingTimeoutNanos(1L, 10L, 1_000_009L))
        assertEquals(0L, httpEngineRemainingTimeoutNanos(1L, 10L, 1_000_010L))
        assertTrue(httpEngineRemainingTimeoutNanos(Long.MAX_VALUE, 10L, 11L) > 0L)
    }

    @Test
    fun contentLengthHeadersAreExactBoundedAndUnambiguous() {
        assertNull(HttpEngineResponseHeaders.contentLength(emptyMap()))
        assertEquals(
            512L * 1_024L * 1_024L,
            HttpEngineResponseHeaders.contentLength(
                mapOf("content-length" to listOf("536870912", "536870912")),
            ),
        )
        assertNull(HttpEngineResponseHeaders.contentLength(mapOf(
            "Content-Length" to listOf("100"),
            "Content-Encoding" to listOf("br"),
        )))
        assertThrows(IOException::class.java) {
            HttpEngineResponseHeaders.contentLength(mapOf("Content-Length" to listOf("0")))
        }
        assertThrows(IOException::class.java) {
            HttpEngineResponseHeaders.contentLength(mapOf("Content-Length" to listOf("3, 4")))
        }
        assertThrows(IOException::class.java) {
            HttpEngineResponseHeaders.contentLength(mapOf("Content-Length" to listOf("536870913")))
        }
    }

    @Test
    fun eachConsumerReadPermitsExactlyOneBoundedDirectRead() = runTest {
        val fixture = Fixture(expectedLength = 6L)
        val destination = ByteArray(6)

        val first = async { fixture.stream.readAtMost(destination, 0, 3) }
        runCurrent()
        assertEquals(1, fixture.driver.readCount)
        assertTrue(requireNotNull(fixture.driver.buffer).isDirect)
        assertEquals(3, requireNotNull(fixture.driver.buffer).limit())
        fixture.deliver("abc".toByteArray())
        assertEquals(3, first.await())
        assertEquals(1, fixture.driver.readCount)

        val second = async { fixture.stream.readAtMost(destination, 3, 3) }
        runCurrent()
        fixture.deliver("def".toByteArray())
        assertEquals(3, second.await())
        val end = async { fixture.stream.readAtMost(destination, 0, destination.size) }
        runCurrent()
        fixture.stream.completeSuccess()

        assertEquals(-1, end.await())
        assertArrayEquals("abcdef".toByteArray(), destination)
        assertEquals(0, fixture.cancelCount)
        fixture.stream.close()
        assertEquals(1, fixture.finishedCount)
    }

    @Test
    fun successRejectsADeclaredLengthMismatchAndAnEmptyBody() = runTest {
        val mismatch = Fixture(expectedLength = 4L)
        val read = async { mismatch.stream.readAtMost(ByteArray(3), 0, 3) }
        runCurrent()
        mismatch.deliver(byteArrayOf(1, 2, 3))
        assertEquals(3, read.await())
        val end = async(SupervisorJob()) { mismatch.stream.readAtMost(ByteArray(1), 0, 1) }
        runCurrent()
        mismatch.stream.completeSuccess()
        assertTrue(runCatching { end.await() }.exceptionOrNull() is IOException)
        mismatch.stream.close()

        val empty = Fixture(expectedLength = null)
        val emptyRead = async(SupervisorJob()) { empty.stream.readAtMost(ByteArray(1), 0, 1) }
        runCurrent()
        empty.stream.completeSuccess()
        assertTrue(runCatching { emptyRead.await() }.exceptionOrNull() is IOException)
        empty.stream.close()
    }

    @Test
    fun zeroChunkAndSizeLimitViolationCancelTheSingleExchange() = runTest {
        val zero = Fixture(expectedLength = null)
        val zeroRead = async(SupervisorJob()) { zero.stream.readAtMost(ByteArray(1), 0, 1) }
        runCurrent()
        zero.deliver(byteArrayOf())
        assertTrue(runCatching { zeroRead.await() }.exceptionOrNull() is IOException)
        assertEquals(1, zero.cancelCount)
        zero.stream.close()

        val limited = Fixture(expectedLength = null, maximumBytes = 4L)
        val first = async { limited.stream.readAtMost(ByteArray(3), 0, 3) }
        runCurrent()
        limited.deliver(byteArrayOf(1, 2, 3))
        assertEquals(3, first.await())
        val overflow = async(SupervisorJob()) { limited.stream.readAtMost(ByteArray(2), 0, 2) }
        runCurrent()
        limited.deliver(byteArrayOf(4, 5))
        assertTrue(runCatching { overflow.await() }.exceptionOrNull() is IOException)
        assertEquals(1, limited.cancelCount)
        limited.stream.close()
    }

    @Test
    fun continuationCancellationAndReadCompletionRaceCannotLeakOrDoubleCancel() = runBlocking {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(300) {
                val fixture = Fixture(expectedLength = null)
                val read = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.stream.readAtMost(ByteArray(3), 0, 3)
                }
                val buffer = fixture.driver.takeBuffer()
                val gate = CountDownLatch(1)
                val cancel = executor.submit { gate.await(); read.cancel() }
                val callback = executor.submit {
                    gate.await()
                    buffer.put(byteArrayOf(1, 2, 3))
                    fixture.stream.onReadCompleted(buffer)
                }
                gate.countDown()
                cancel.get()
                callback.get()
                read.cancelAndJoin()
                fixture.stream.close()
                assertEquals(1, fixture.cancelCount)
                assertEquals(1, fixture.finishedCount)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun requestSuccessAndConsumerCloseRaceFinishesExactlyOnce() = runBlocking {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(300) {
                val fixture = Fixture(expectedLength = null)
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.stream.readAtMost(ByteArray(1), 0, 1)
                }
                fixture.deliver(byteArrayOf(1))
                assertEquals(1, first.await())
                val end = async(SupervisorJob(), start = CoroutineStart.UNDISPATCHED) {
                    fixture.stream.readAtMost(ByteArray(1), 0, 1)
                }
                val gate = CountDownLatch(1)
                val success = executor.submit { gate.await(); fixture.stream.completeSuccess() }
                val close = executor.submit { gate.await(); fixture.stream.close() }
                gate.countDown()
                success.get()
                close.get()
                runCatching { end.await() }
                assertEquals(1, fixture.finishedCount)
                assertTrue(fixture.cancelCount in 0..1)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentConsumerCannotCreateASecondNetworkRead() = runTest {
        val fixture = Fixture(expectedLength = null)
        val first = async { fixture.stream.readAtMost(ByteArray(1), 0, 1) }
        runCurrent()
        val second = async(SupervisorJob()) { fixture.stream.readAtMost(ByteArray(1), 0, 1) }
        runCurrent()

        assertTrue(runCatching { second.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, fixture.driver.readCount)
        fixture.deliver(byteArrayOf(1))
        assertEquals(1, first.await())
        fixture.stream.close()
        assertEquals(1, fixture.cancelCount)
    }

    private class Fixture(expectedLength: Long?, maximumBytes: Long = 512L * 1_024L * 1_024L) {
        val driver = ReadDriver()
        var cancelCount = 0
        var finishedCount = 0
        lateinit var stream: HttpEngineBodyPageStream

        init {
            stream = HttpEngineBodyPageStream(
                expectedLength = expectedLength,
                requestRead = driver::read,
                cancelExchange = { cause ->
                    cancelCount += 1
                    stream.fail(cause)
                },
                finished = { finishedCount += 1 },
                maximumBytes = maximumBytes,
            )
        }

        fun deliver(bytes: ByteArray) {
            val buffer = driver.takeBuffer()
            buffer.put(bytes)
            stream.onReadCompleted(buffer)
        }
    }

    private class ReadDriver {
        var readCount = 0
        var buffer: ByteBuffer? = null

        fun read(destination: ByteBuffer) {
            check(buffer == null) { "A second network read started before callback completion" }
            readCount += 1
            buffer = destination
        }

        fun takeBuffer(): ByteBuffer = requireNotNull(buffer).also { buffer = null }
    }
}
