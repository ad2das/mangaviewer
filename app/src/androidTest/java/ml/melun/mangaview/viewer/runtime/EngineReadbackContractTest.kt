package ml.melun.mangaview.viewer.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class EngineReadbackContractTest {
    @Test
    fun parsesLittleEndianTwoByTwoRgbaPacket() {
        val rgba = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            8, 9, 10, 11,
            12, 13, 14, 15,
        )
        val parsed = EngineReadbackPacket.parse(packet(payload = rgba))

        assertEquals(EngineReadbackPacket.Status.OK, parsed.status)
        assertEquals(17L, parsed.sessionId)
        assertEquals(19L, parsed.rendererEpoch)
        assertEquals(23L, parsed.surfaceEpoch)
        assertEquals(29L, parsed.token)
        assertEquals(2L, parsed.width)
        assertEquals(0L, parsed.top)
        assertEquals(2L, parsed.bottom)
        assertEquals(16L, parsed.rgbaByteCount)
        assertEquals(false, parsed.physicalPresentationVerified)
        assertArrayEquals(rgba, parsed.rgbaBytes)
    }

    @Test
    fun rejectsTruncatedHeaderAndBody() {
        assertRejected(ByteArray(EngineReadbackPacket.HEADER_BYTES - 1))
        assertRejected(packet(payload = ByteArray(15)).copyOf(EngineReadbackPacket.HEADER_BYTES))
    }

    @Test
    fun rejectsIncorrectLength() {
        val truncated = packet().copyOf(packet().size - 1)
        assertRejected(truncated)

        val extended = packet() + byteArrayOf(42)
        assertRejected(extended)
    }

    @Test
    fun rejectsWrongVersionStatusAndPhysicalFlag() {
        val wrongVersion = packet().also { it.putLong(8, 2L) }
        val wrongStatus = packet().also { it.putLong(16, 99L) }
        val physicalProof = packet().also { it.putLong(15 * Long.SIZE_BYTES, 1L) }

        assertRejected(wrongVersion)
        assertRejected(wrongStatus)
        assertRejected(physicalProof)
    }

    @Test
    fun rejectsImpossibleBoundaries() {
        val zeroWidth = packet(width = 0L)
        val reversedRows = packet(top = 2L, bottom = 1L)
        val negativeTop = packet(top = -1L, bottom = 2L)

        assertRejected(zeroWidth)
        assertRejected(reversedRows)
        assertRejected(negativeTop)
    }

    @Test
    fun rejectsTimestampInversion() {
        assertRejected(packet(issued = 20L, ready = 19L, swapCompleted = 30L))
        assertRejected(packet(issued = 20L, ready = 30L, swapCompleted = 19L))
        assertRejected(packet(issued = 0L, ready = 0L, swapCompleted = 0L))
    }

    private fun packet(
        sessionId: Long = 17L,
        rendererEpoch: Long = 19L,
        surfaceEpoch: Long = 23L,
        token: Long = 29L,
        width: Long = 2L,
        top: Long = 0L,
        bottom: Long = 2L,
        issued: Long = 10L,
        ready: Long = 30L,
        swapCompleted: Long = 20L,
        status: Long = 1L,
        physicalFlag: Long = 0L,
        payload: ByteArray = ByteArray(16) { it.toByte() },
        declaredBytes: Long = payload.size.toLong(),
    ): ByteArray = ByteBuffer.allocate(EngineReadbackPacket.HEADER_BYTES + payload.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            putLong(EngineReadbackPacket.MAGIC)
            putLong(EngineReadbackPacket.VERSION)
            putLong(status)
            putLong(sessionId)
            putLong(rendererEpoch)
            putLong(surfaceEpoch)
            putLong(token)
            putLong(0L)
            putLong(width)
            putLong(top)
            putLong(bottom)
            putLong(issued)
            putLong(ready)
            putLong(swapCompleted)
            putLong(declaredBytes)
            putLong(physicalFlag)
            put(payload)
        }
        .array()

    private fun assertRejected(packet: ByteArray) {
        try {
            EngineReadbackPacket.parse(packet)
            fail("Malformed readback packet was accepted")
        } catch (_: IllegalArgumentException) {
            // Expected contract rejection.
        }
    }

    private fun ByteArray.putLong(offset: Int, value: Long) {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value)
    }
}
