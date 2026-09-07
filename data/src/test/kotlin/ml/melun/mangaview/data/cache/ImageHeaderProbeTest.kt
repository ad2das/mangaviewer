package ml.melun.mangaview.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageHeaderProbeTest {
    @Test
    fun readsJpegPngAndWebpDimensionsWithoutContentTypeHints() {
        assertHeader(png(1_080, 12_000), "image/png", 1_080, 12_000)
        assertHeader(jpeg(800, 1_200), "image/jpeg", 800, 1_200)
        assertHeader(webpExtended(2_400, 900), "image/webp", 2_400, 900)
    }

    @Test
    fun rejectsHtmlEvenWhenServerClaimsItIsAnImage() {
        val html = "<html><body>challenge</body></html>".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { ImageHeaderProbe.inspect(html) }
    }

    @Test
    fun onlyNonInterlacedEightBitPngHasAProvableIncompleteDecodeBoundary() {
        val opaquePng = png(1_080, 1_920)
        val alphaPng = png(1_080, 1_920).also { it[25] = 6 }
        val interlacedPng = png(1_080, 1_920).also { it[28] = 1 }

        assertTrue(ImageHeaderProbe.inspect(opaquePng).supportsVerifiedPrefixDecode)
        assertTrue(ImageHeaderProbe.inspect(alphaPng).supportsVerifiedPrefixDecode)
        assertFalse(ImageHeaderProbe.inspect(interlacedPng).supportsVerifiedPrefixDecode)
        assertFalse(ImageHeaderProbe.inspect(jpeg(1_080, 1_920)).supportsVerifiedPrefixDecode)
        assertFalse(ImageHeaderProbe.inspect(webpExtended(1_080, 1_920)).supportsVerifiedPrefixDecode)
    }

    private fun assertHeader(bytes: ByteArray, type: String, width: Int, height: Int) {
        val header = ImageHeaderProbe.inspect(bytes)
        assertEquals(type, header.mediaType)
        assertEquals(width, header.dimensions.widthPx)
        assertEquals(height, header.dimensions.heightPx)
    }

    companion object {
        fun png(width: Int, height: Int): ByteArray = ByteArray(32).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                .copyInto(bytes)
            "IHDR".toByteArray().copyInto(bytes, 12)
            putBigEndian(bytes, 16, width)
            putBigEndian(bytes, 20, height)
            bytes[24] = 8
        }

        fun jpeg(width: Int, height: Int): ByteArray = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
            0x00, 0x11, 0x08,
            (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(),
            0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
        )

        fun webpExtended(width: Int, height: Int): ByteArray = ByteArray(30).also { bytes ->
            "RIFF".toByteArray().copyInto(bytes, 0)
            "WEBP".toByteArray().copyInto(bytes, 8)
            "VP8X".toByteArray().copyInto(bytes, 12)
            putLittle24(bytes, 24, width - 1)
            putLittle24(bytes, 27, height - 1)
        }

        private fun putBigEndian(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }

        private fun putLittle24(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
        }
    }
}
