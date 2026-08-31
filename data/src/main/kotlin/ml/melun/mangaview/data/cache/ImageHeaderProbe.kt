package ml.melun.mangaview.data.cache

import ml.melun.mangaview.core.PageDimensions

data class ImageHeader(
    val mediaType: String,
    val dimensions: PageDimensions,
)

object ImageHeaderProbe {
    fun inspect(bytes: ByteArray, count: Int = bytes.size): ImageHeader {
        require(count in 0..bytes.size) { "Header byte count is invalid" }
        return inspectPng(bytes, count)
            ?: inspectJpeg(bytes, count)
            ?: inspectWebp(bytes, count)
            ?: throw IllegalArgumentException("Encoded page is not a supported image")
    }

    private fun inspectPng(bytes: ByteArray, count: Int): ImageHeader? {
        if (count < 24 || !matches(bytes, PNG_SIGNATURE, 0)) return null
        val width = int32(bytes, 16)
        val height = int32(bytes, 20)
        if (width <= 0 || height <= 0) return null
        return ImageHeader("image/png", PageDimensions(width, height))
    }

    private fun inspectJpeg(bytes: ByteArray, count: Int): ImageHeader? {
        if (!hasJpegSignature(bytes, count)) return null
        var cursor = 2
        while (cursor + 3 < count) {
            while (cursor < count && u8(bytes, cursor) == 0xff) cursor += 1
            if (cursor >= count) return null
            val marker = u8(bytes, cursor++)
            if (isTerminalJpegMarker(marker)) return null
            if (isStandaloneJpegMarker(marker)) continue
            if (cursor + 1 >= count) return null
            val segmentLength = u16(bytes, cursor)
            if (!isValidJpegSegment(cursor, segmentLength, count)) return null
            jpegDimensions(bytes, cursor, marker, segmentLength)?.let { return it }
            cursor += segmentLength
        }
        return null
    }

    private fun hasJpegSignature(bytes: ByteArray, count: Int): Boolean =
        count >= 4 && u8(bytes, 0) == 0xff && u8(bytes, 1) == 0xd8

    private fun isTerminalJpegMarker(marker: Int): Boolean = marker == 0xd9 || marker == 0xda

    private fun isStandaloneJpegMarker(marker: Int): Boolean = marker == 0x01 || marker in 0xd0..0xd7

    private fun isValidJpegSegment(cursor: Int, length: Int, count: Int): Boolean =
        length >= 2 && cursor + length <= count

    private fun jpegDimensions(
        bytes: ByteArray,
        cursor: Int,
        marker: Int,
        segmentLength: Int,
    ): ImageHeader? {
        if (marker !in JPEG_DIMENSION_MARKERS || segmentLength < 7) return null
        val height = u16(bytes, cursor + 3)
        val width = u16(bytes, cursor + 5)
        if (width <= 0 || height <= 0) return null
        return ImageHeader("image/jpeg", PageDimensions(width, height))
    }

    private fun inspectWebp(bytes: ByteArray, count: Int): ImageHeader? {
        if (count < 30 || !matches(bytes, RIFF, 0) || !matches(bytes, WEBP, 8)) return null
        return when {
            matches(bytes, VP8X, 12) -> webpExtended(bytes)
            matches(bytes, VP8, 12) -> webpLossy(bytes)
            matches(bytes, VP8L, 12) -> webpLossless(bytes)
            else -> null
        }
    }

    private fun webpExtended(bytes: ByteArray): ImageHeader {
        val width = little24(bytes, 24) + 1
        val height = little24(bytes, 27) + 1
        return ImageHeader("image/webp", PageDimensions(width, height))
    }

    private fun webpLossy(bytes: ByteArray): ImageHeader? {
        if (u8(bytes, 23) != 0x9d || u8(bytes, 24) != 0x01 || u8(bytes, 25) != 0x2a) return null
        val width = little16(bytes, 26) and 0x3fff
        val height = little16(bytes, 28) and 0x3fff
        return ImageHeader("image/webp", PageDimensions(width, height))
    }

    private fun webpLossless(bytes: ByteArray): ImageHeader? {
        if (u8(bytes, 20) != 0x2f) return null
        val bits = u8(bytes, 21) or (u8(bytes, 22) shl 8) or
            (u8(bytes, 23) shl 16) or (u8(bytes, 24) shl 24)
        val width = (bits and 0x3fff) + 1
        val height = ((bits ushr 14) and 0x3fff) + 1
        return ImageHeader("image/webp", PageDimensions(width, height))
    }

    private fun matches(bytes: ByteArray, expected: ByteArray, offset: Int): Boolean =
        expected.indices.all { index -> bytes[offset + index] == expected[index] }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int = u8(bytes, offset) shl 8 or u8(bytes, offset + 1)

    private fun int32(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) shl 24 or u8(bytes, offset + 1) shl 16 or
            (u8(bytes, offset + 2) shl 8) or u8(bytes, offset + 3)

    private fun little16(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    private fun little24(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8) or (u8(bytes, offset + 2) shl 16)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val VP8X = "VP8X".toByteArray(Charsets.US_ASCII)
    private val VP8 = byteArrayOf(0x56, 0x50, 0x38, 0x20)
    private val VP8L = "VP8L".toByteArray(Charsets.US_ASCII)
    private val JPEG_DIMENSION_MARKERS = setOf(
        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
    )
}
