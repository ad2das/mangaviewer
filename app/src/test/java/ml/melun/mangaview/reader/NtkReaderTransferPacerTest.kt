package ml.melun.mangaview.reader

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkReaderTransferPacerTest {
    private val firstOwner = Any()
    private val secondOwner = Any()

    @After
    fun tearDown() {
        NtkReaderTransferPacer.release(firstOwner)
        NtkReaderTransferPacer.release(secondOwner)
    }

    @Test
    fun ownerSafeMotionStateRequiresEverySignalToBecomeIdle() {
        NtkReaderTransferPacer.noteTouch(firstOwner, true)
        NtkReaderTransferPacer.noteViewportMotion(firstOwner, true)
        NtkReaderTransferPacer.noteTouch(firstOwner, false)
        assertTrue(NtkReaderTransferPacer.isActiveForTest())

        NtkReaderTransferPacer.noteViewportMotion(firstOwner, false)
        assertFalse(NtkReaderTransferPacer.isActiveForTest())
    }

    @Test
    fun lateOldOwnerReleaseCannotClearNewForegroundMotion() {
        NtkReaderTransferPacer.noteTouch(firstOwner, true)
        NtkReaderTransferPacer.noteViewportMotion(secondOwner, true)

        NtkReaderTransferPacer.release(firstOwner)
        assertTrue(NtkReaderTransferPacer.isActiveForTest())
        NtkReaderTransferPacer.release(secondOwner)
        assertFalse(NtkReaderTransferPacer.isActiveForTest())
    }

    @Test
    fun completedChunkPacingPreservesBytes() {
        val expected = ByteArray(1024) { it.toByte() }
        val actual = ByteArray(expected.size)
        NtkReaderTransferPacer.noteViewportMotion(firstOwner, true)

        val count = NtkReaderTransferPacer.readChunk(
            ByteArrayInputStream(expected),
            actual,
        )

        assertTrue(count == expected.size)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun blockedSocketReadDoesNotOwnTheCompletedChunkGate() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                buffer[offset] = 1
                return 1
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        NtkReaderTransferPacer.noteViewportMotion(firstOwner, true)
        try {
            val slow = executor.submit<Int> {
                NtkReaderTransferPacer.readChunk(blocked, ByteArray(1))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val fast = executor.submit<Int> {
                NtkReaderTransferPacer.readChunk(
                    ByteArrayInputStream(byteArrayOf(2)),
                    ByteArray(1),
                )
            }
            assertTrue(fast.get(250, TimeUnit.MILLISECONDS) == 1)
            release.countDown()
            assertTrue(slow.get(1, TimeUnit.SECONDS) == 1)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun physicalMotionBoundsHealthyTlsReadsButStalledSocketsCannotStarveAnotherResponse() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val thirdEntered = AtomicBoolean(false)
        val blocking = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                buffer[offset] = 1
                return 1
            }
        }
        val observedThird = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                thirdEntered.set(true)
                buffer[offset] = 1
                return 1
            }
        }
        val executor = Executors.newFixedThreadPool(3)
        NtkReaderTransferPacer.noteViewportMotion(firstOwner, true)
        try {
            val first = executor.submit<Int> {
                NtkReaderTransferPacer.readChunk(blocking, ByteArray(1))
            }
            val second = executor.submit<Int> {
                NtkReaderTransferPacer.readChunk(blocking, ByteArray(1))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val third = executor.submit<Int> {
                NtkReaderTransferPacer.readChunk(observedThird, ByteArray(1))
            }
            Thread.sleep(12L)
            assertFalse(thirdEntered.get())
            assertTrue(third.get(250, TimeUnit.MILLISECONDS) == 1)
            assertTrue(thirdEntered.get())
            release.countDown()
            assertTrue(first.get(1, TimeUnit.SECONDS) == 1)
            assertTrue(second.get(1, TimeUnit.SECONDS) == 1)
        } finally {
            release.countDown()
            NtkReaderTransferPacer.noteViewportMotion(firstOwner, false)
            executor.shutdownNow()
        }
    }

    @Test
    fun allocationWindowWaitsForMotionWithoutOwningTransferGate() {
        val returned = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        NtkReaderTransferPacer.noteViewportMotion(firstOwner, true)
        try {
            val wait = executor.submit {
                NtkReaderTransferPacer.awaitMotionIdle { }
                returned.set(true)
            }
            Thread.sleep(20L)
            assertFalse(returned.get())
            NtkReaderTransferPacer.noteViewportMotion(firstOwner, false)
            wait.get(1, TimeUnit.SECONDS)
            assertTrue(returned.get())
        } finally {
            NtkReaderTransferPacer.noteViewportMotion(firstOwner, false)
            executor.shutdownNow()
        }
    }

    @Test
    fun optionalOffscreenReadKeepsItsLongIdleGraceAndForegroundPromotionPreservesBytes() {
        val expected = byteArrayOf(7, 8, 9)
        val actual = ByteArray(expected.size)
        val optional = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        NtkReaderTransferPacer.noteViewportMotion(firstOwner, true)
        try {
            val read = executor.submit<Int> {
                NtkReaderTransferPacer.readOptionalChunk(
                    ByteArrayInputStream(expected),
                    actual,
                    shouldRemainDeferred = optional::get,
                    stillOwned = { },
                )
            }
            Thread.sleep(20L)
            assertFalse(read.isDone)
            NtkReaderTransferPacer.noteViewportMotion(firstOwner, false)
            Thread.sleep(350L)
            assertFalse(read.isDone)
            optional.set(false)
            assertTrue(read.get(1, TimeUnit.SECONDS) == expected.size)
            assertArrayEquals(expected, actual)
        } finally {
            NtkReaderTransferPacer.noteViewportMotion(firstOwner, false)
            executor.shutdownNow()
        }
    }

    @Test
    fun optionalReadPromotesImmediatelyWhenEpisodeBecomesForeground() {
        val optional = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        NtkReaderTransferPacer.noteViewportMotion(firstOwner, true)
        try {
            val read = executor.submit<Int> {
                NtkReaderTransferPacer.readOptionalChunk(
                    ByteArrayInputStream(byteArrayOf(4)),
                    ByteArray(1),
                    shouldRemainDeferred = optional::get,
                    stillOwned = { },
                )
            }
            Thread.sleep(20L)
            assertFalse(read.isDone)
            optional.set(false)
            assertTrue(read.get(1, TimeUnit.SECONDS) == 1)
        } finally {
            NtkReaderTransferPacer.noteViewportMotion(firstOwner, false)
            executor.shutdownNow()
        }
    }

    @Test
    fun compositorEpisodeOwnershipPromotesOnlyTheCurrentSessionPath() {
        NtkReaderTransferPacer.notePhysicalForegroundEpisode(firstOwner, "/manhwa/1")
        assertTrue(NtkReaderTransferPacer.isPhysicalForegroundEpisode("/manhwa/1"))
        assertFalse(NtkReaderTransferPacer.isPhysicalForegroundEpisode("/manhwa/2"))

        NtkReaderTransferPacer.notePhysicalForegroundEpisode(secondOwner, "/manhwa/2")
        NtkReaderTransferPacer.release(firstOwner)
        assertTrue(NtkReaderTransferPacer.isPhysicalForegroundEpisode("/manhwa/2"))

        NtkReaderTransferPacer.release(secondOwner)
        assertFalse(NtkReaderTransferPacer.isPhysicalForegroundEpisode("/manhwa/2"))
    }
}
