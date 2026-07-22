package ml.melun.mangaview.reader

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStreamingImageHeaderParserTest {
    @Test
    fun jpegSkipsLargeMarkerWithoutRetainingPrefix() {
        val bytes = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
            write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0xff.toByte(), 0xff.toByte()))
            write(ByteArray(65_533) { 7 })
            write(byteArrayOf(
                0xff.toByte(), 0xc0.toByte(), 0x00, 0x11,
                0x08, 0x00, 0x20, 0x00, 0x10, 0x03
            ))
        }.toByteArray()

        val exact = feedInChunks(bytes, 257)

        assertEquals(16, exact.width)
        assertEquals(32, exact.height)
        assertEquals(NtkExactImageHeaderParser.FORMAT_JPEG, exact.format)
        assertTrue(exact.consumedBytes > 65_536L)
    }

    @Test
    fun pngAndGifProduceExactGeometryAtFixedHeaderSize() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x02, 0xb2.toByte(), 0x00, 0x00, 0x06, 0x40
        )
        val gif = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            0x20, 0x03, 0x58, 0x02
        )

        val pngExact = feedInChunks(png, 3)
        val gifExact = feedInChunks(gif, 2)

        assertEquals(690, pngExact.width)
        assertEquals(1600, pngExact.height)
        assertEquals(800, gifExact.width)
        assertEquals(600, gifExact.height)
    }

    @Test
    fun webpSkipsUnknownChunkBeforeDimensionChunk() {
        val junkSize = 20_000
        val payloadSize = 4 + 8 + junkSize + 8 + 10
        val bytes = ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeLe32(payloadSize)
            writeAscii("WEBP")
            writeAscii("JUNK")
            writeLe32(junkSize)
            write(ByteArray(junkSize) { 1 })
            writeAscii("VP8X")
            writeLe32(10)
            write(byteArrayOf(0, 0, 0, 0))
            writeLe24(689)
            writeLe24(1599)
        }.toByteArray()

        val exact = feedInChunks(bytes, 113)

        assertEquals(690, exact.width)
        assertEquals(1600, exact.height)
        assertEquals(NtkExactImageHeaderParser.FORMAT_WEBP, exact.format)
    }

    @Test
    fun invalidMagicFailsImmediately() {
        val result = NtkStreamingImageHeaderParser().feed(byteArrayOf(0x01, 0x02))
        assertTrue(result is NtkStreamingImageHeaderParser.Result.Invalid)
    }

    private fun feedInChunks(
        bytes: ByteArray,
        chunkSize: Int
    ): NtkStreamingImageHeaderParser.Result.Exact {
        val parser = NtkStreamingImageHeaderParser()
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(chunkSize, bytes.size - offset)
            when (val result = parser.feed(bytes, offset, count)) {
                is NtkStreamingImageHeaderParser.Result.Exact -> return result
                is NtkStreamingImageHeaderParser.Result.Invalid -> error(result.reason)
                NtkStreamingImageHeaderParser.Result.NeedMore -> Unit
            }
            offset += count
        }
        error("No exact geometry")
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        repeat(4) { shift -> write((value ushr (shift * 8)) and 0xff) }
    }

    private fun ByteArrayOutputStream.writeLe24(value: Int) {
        repeat(3) { shift -> write((value ushr (shift * 8)) and 0xff) }
    }
}
