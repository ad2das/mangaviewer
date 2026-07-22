package ml.melun.mangaview.reader

/**
 * Fixed-memory encoded-container parser used while one strict full-body response is spooled.
 *
 * Unlike the prefix parser, this parser skips arbitrarily large JPEG/WebP metadata segments
 * without retaining them.  It reports the exact stream position at which immutable geometry was
 * proved, allowing that prefix to become the metadata byte witness while the same response keeps
 * flowing to the body spool.
 */
internal class NtkStreamingImageHeaderParser {
    sealed interface Result {
        data object NeedMore : Result
        data class Exact(
            val width: Int,
            val height: Int,
            val format: String,
            val consumedBytes: Long
        ) : Result

        data class Invalid(val reason: String) : Result
    }

    private enum class Container { UNKNOWN, JPEG, PNG, WEBP, GIF }
    private enum class JpegState { MARKER_PREFIX, MARKER_CODE, LENGTH_HIGH, LENGTH_LOW, SKIP, SOF }
    private enum class WebpState { CHUNK_HEADER, SKIP, TARGET }

    private var terminal: Result? = null
    private var consumed = 0L
    private var container = Container.UNKNOWN
    private val initial = ByteArray(24)
    private var initialSize = 0

    private var jpegState = JpegState.MARKER_PREFIX
    private var jpegMarker = 0
    private var jpegLengthHigh = 0
    private var jpegSkipRemaining = 0L
    private val jpegSof = ByteArray(6)
    private var jpegSofSize = 0
    private var jpegSegmentLength = 0

    private var webpDeclaredEnd = -1L
    private var webpState = WebpState.CHUNK_HEADER
    private val webpChunkHeader = ByteArray(8)
    private var webpChunkHeaderSize = 0
    private var webpSkipRemaining = 0L
    private var webpTarget = ""
    private val webpTargetPrefix = ByteArray(10)
    private var webpTargetSize = 0
    private var webpTargetRequired = 0

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Result {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        terminal?.let { return it }
        var index = offset
        val end = offset + length
        while (index < end) {
            val value = bytes[index].toInt() and 0xff
            consumed++
            val result = when (container) {
                Container.UNKNOWN -> acceptInitial(value)
                Container.JPEG -> acceptJpeg(value)
                Container.PNG -> acceptPng(value)
                Container.WEBP -> acceptWebp(value)
                Container.GIF -> acceptGif(value)
            }
            if (result !is Result.NeedMore) {
                terminal = result
                return result
            }
            index++
        }
        return Result.NeedMore
    }

    private fun acceptInitial(value: Int): Result {
        if (initialSize >= initial.size) return invalid("unsupported_magic")
        initial[initialSize++] = value.toByte()
        return when (initial[0].toInt() and 0xff) {
            0xff -> {
                if (initialSize < 2) Result.NeedMore
                else if ((initial[1].toInt() and 0xff) != 0xd8) invalid("unsupported_magic")
                else {
                    container = Container.JPEG
                    Result.NeedMore
                }
            }
            0x89 -> {
                container = Container.PNG
                acceptPngInitial()
            }
            0x52 -> {
                container = Container.WEBP
                acceptWebpInitial()
            }
            0x47 -> {
                container = Container.GIF
                acceptGifInitial()
            }
            else -> invalid("unsupported_magic")
        }
    }

    private fun acceptPng(value: Int): Result {
        if (initialSize >= initial.size) return invalid("png_header_overflow")
        initial[initialSize++] = value.toByte()
        return acceptPngInitial()
    }

    private fun acceptPngInitial(): Result {
        if (!matchesAvailable(initial, initialSize, PNG_SIGNATURE)) return invalid("unsupported_magic")
        if (initialSize < 24) return Result.NeedMore
        if (be32(initial, 8) != 13L) return invalid("png_invalid_ihdr_length")
        if (!matches(initial, 12, PNG_IHDR)) return invalid("png_ihdr_not_first")
        val width = positiveDimension(be32(initial, 16)) ?: return invalid("png_invalid_width")
        val height = positiveDimension(be32(initial, 20)) ?: return invalid("png_invalid_height")
        return exact(width, height, NtkExactImageHeaderParser.FORMAT_PNG)
    }

    private fun acceptGif(value: Int): Result {
        if (initialSize >= initial.size) return invalid("gif_header_overflow")
        initial[initialSize++] = value.toByte()
        return acceptGifInitial()
    }

    private fun acceptGifInitial(): Result {
        val possible = matchesAvailable(initial, initialSize, GIF_87A) ||
            matchesAvailable(initial, initialSize, GIF_89A)
        if (!possible) return invalid("gif_invalid_version")
        if (initialSize < 10) return Result.NeedMore
        val width = le16(initial, 6)
        val height = le16(initial, 8)
        if (width <= 0 || height <= 0) return invalid("gif_invalid_dimensions")
        return exact(width, height, NtkExactImageHeaderParser.FORMAT_GIF)
    }

    private fun acceptJpeg(value: Int): Result {
        return when (jpegState) {
        JpegState.MARKER_PREFIX -> {
            if (value != 0xff) invalid("jpeg_expected_marker")
            else {
                jpegState = JpegState.MARKER_CODE
                Result.NeedMore
            }
        }
        JpegState.MARKER_CODE -> when {
            value == 0xff -> Result.NeedMore
            value == 0x00 -> invalid("jpeg_stuffed_byte_before_sof")
            value == 0xda -> invalid("jpeg_scan_before_sof")
            value == 0xd9 -> invalid("jpeg_end_before_sof")
            value == 0xd8 -> invalid("jpeg_duplicate_soi")
            value in 0xd0..0xd7 -> invalid("jpeg_restart_before_sof")
            value == 0x01 -> {
                jpegState = JpegState.MARKER_PREFIX
                Result.NeedMore
            }
            else -> {
                jpegMarker = value
                jpegState = JpegState.LENGTH_HIGH
                Result.NeedMore
            }
        }
        JpegState.LENGTH_HIGH -> {
            jpegLengthHigh = value
            jpegState = JpegState.LENGTH_LOW
            Result.NeedMore
        }
        JpegState.LENGTH_LOW -> {
            jpegSegmentLength = (jpegLengthHigh shl 8) or value
            if (jpegSegmentLength < 2) return invalid("jpeg_invalid_segment_length")
            if (jpegMarker in JPEG_SOF_MARKERS) {
                if (jpegSegmentLength < 11) return invalid("jpeg_invalid_sof_length")
                jpegSofSize = 0
                jpegState = JpegState.SOF
            } else {
                jpegSkipRemaining = jpegSegmentLength.toLong() - 2L
                jpegState = if (jpegSkipRemaining == 0L) {
                    JpegState.MARKER_PREFIX
                } else {
                    JpegState.SKIP
                }
            }
            Result.NeedMore
        }
        JpegState.SKIP -> {
            jpegSkipRemaining--
            if (jpegSkipRemaining == 0L) jpegState = JpegState.MARKER_PREFIX
            Result.NeedMore
        }
        JpegState.SOF -> {
            jpegSof[jpegSofSize++] = value.toByte()
            if (jpegSofSize < jpegSof.size) return Result.NeedMore
            val components = jpegSof[5].toInt() and 0xff
            if (components <= 0 || jpegSegmentLength != 8 + 3 * components) {
                invalid("jpeg_invalid_sof_components")
            } else {
                val height = be16(jpegSof, 1)
                val width = be16(jpegSof, 3)
                if (width <= 0 || height <= 0) invalid("jpeg_invalid_dimensions")
                else exact(width, height, NtkExactImageHeaderParser.FORMAT_JPEG)
            }
        }
        }
    }

    private fun acceptWebpInitial(): Result {
        if (!matchesAvailable(initial, initialSize, WEBP_RIFF)) return invalid("unsupported_magic")
        if (initialSize < 12) return Result.NeedMore
        if (!matches(initial, 8, WEBP_MAGIC)) return invalid("riff_is_not_webp")
        webpDeclaredEnd = le32(initial, 4) + 8L
        if (webpDeclaredEnd < 12L) return invalid("webp_invalid_riff_length")
        return Result.NeedMore
    }

    private fun acceptWebp(value: Int): Result {
        if (initialSize < 12) {
            if (initialSize >= initial.size) return invalid("webp_header_overflow")
            initial[initialSize++] = value.toByte()
            return acceptWebpInitial()
        }
        if (consumed > webpDeclaredEnd) return invalid("webp_chunk_past_riff")
        return when (webpState) {
            WebpState.CHUNK_HEADER -> {
                webpChunkHeader[webpChunkHeaderSize++] = value.toByte()
                if (webpChunkHeaderSize < webpChunkHeader.size) return Result.NeedMore
                val chunkSize = le32(webpChunkHeader, 4)
                val padded = chunkSize + (chunkSize and 1L)
                if (consumed + padded > webpDeclaredEnd) {
                    return invalid("webp_chunk_exceeds_riff")
                }
                webpTarget = ascii4(webpChunkHeader, 0)
                webpChunkHeaderSize = 0
                webpTargetRequired = when (webpTarget) {
                    "VP8 " -> 10
                    "VP8L" -> 5
                    "VP8X" -> 10
                    else -> 0
                }
                if (webpTargetRequired > 0) {
                    if (chunkSize < webpTargetRequired.toLong()) {
                        return invalid("webp_dimension_chunk_too_small")
                    }
                    webpTargetSize = 0
                    webpState = WebpState.TARGET
                } else {
                    webpSkipRemaining = padded
                    webpState = if (webpSkipRemaining == 0L) {
                        WebpState.CHUNK_HEADER
                    } else {
                        WebpState.SKIP
                    }
                }
                Result.NeedMore
            }
            WebpState.SKIP -> {
                webpSkipRemaining--
                if (webpSkipRemaining == 0L) {
                    if (consumed == webpDeclaredEnd) return invalid("webp_missing_dimension_chunk")
                    webpState = WebpState.CHUNK_HEADER
                }
                Result.NeedMore
            }
            WebpState.TARGET -> {
                webpTargetPrefix[webpTargetSize++] = value.toByte()
                if (webpTargetSize < webpTargetRequired) return Result.NeedMore
                parseWebpTarget()
            }
        }
    }

    private fun parseWebpTarget(): Result {
        return when (webpTarget) {
        "VP8 " -> {
            val frameTag = (webpTargetPrefix[0].toInt() and 0xff) or
                ((webpTargetPrefix[1].toInt() and 0xff) shl 8) or
                ((webpTargetPrefix[2].toInt() and 0xff) shl 16)
            if ((frameTag and 1) != 0) return invalid("webp_vp8_not_key_frame")
            if (!matches(webpTargetPrefix, 3, WEBP_VP8_FRAME_MAGIC)) {
                return invalid("webp_vp8_bad_frame_magic")
            }
            val width = le16(webpTargetPrefix, 6) and 0x3fff
            val height = le16(webpTargetPrefix, 8) and 0x3fff
            if (width <= 0 || height <= 0) invalid("webp_vp8_invalid_dimensions")
            else exact(width, height, NtkExactImageHeaderParser.FORMAT_WEBP)
        }
        "VP8L" -> {
            if ((webpTargetPrefix[0].toInt() and 0xff) != 0x2f) {
                return invalid("webp_vp8l_bad_magic")
            }
            val packed = le32(webpTargetPrefix, 1)
            if (((packed ushr 29) and 0x7L) != 0L) {
                return invalid("webp_vp8l_unsupported_version")
            }
            exact(
                ((packed and 0x3fffL) + 1L).toInt(),
                (((packed ushr 14) and 0x3fffL) + 1L).toInt(),
                NtkExactImageHeaderParser.FORMAT_WEBP
            )
        }
        "VP8X" -> {
            if ((webpTargetPrefix[1].toInt() and 0xff) != 0 ||
                (webpTargetPrefix[2].toInt() and 0xff) != 0 ||
                (webpTargetPrefix[3].toInt() and 0xff) != 0
            ) return invalid("webp_vp8x_reserved_bytes_nonzero")
            exact(
                le24(webpTargetPrefix, 4) + 1,
                le24(webpTargetPrefix, 7) + 1,
                NtkExactImageHeaderParser.FORMAT_WEBP
            )
        }
        else -> invalid("webp_missing_dimension_chunk")
        }
    }

    private fun exact(width: Int, height: Int, format: String): Result.Exact =
        Result.Exact(width, height, format, consumed)

    private fun invalid(reason: String): Result.Invalid = Result.Invalid(reason)

    private fun matchesAvailable(bytes: ByteArray, size: Int, signature: IntArray): Boolean {
        val compared = minOf(size, signature.size)
        for (index in 0 until compared) {
            if ((bytes[index].toInt() and 0xff) != signature[index]) return false
        }
        return true
    }

    private fun matches(bytes: ByteArray, offset: Int, signature: IntArray): Boolean {
        if (offset < 0 || bytes.size - offset < signature.size) return false
        for (index in signature.indices) {
            if ((bytes[offset + index].toInt() and 0xff) != signature[index]) return false
        }
        return true
    }

    private fun positiveDimension(value: Long): Int? =
        value.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    private fun be16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)

    private fun be32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toInt() and 0xff).toLong() shl 24) or
            ((bytes[offset + 1].toInt() and 0xff).toLong() shl 16) or
            ((bytes[offset + 2].toInt() and 0xff).toLong() shl 8) or
            (bytes[offset + 3].toInt() and 0xff).toLong()

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toInt() and 0xff).toLong() or
            ((bytes[offset + 1].toInt() and 0xff).toLong() shl 8) or
            ((bytes[offset + 2].toInt() and 0xff).toLong() shl 16) or
            ((bytes[offset + 3].toInt() and 0xff).toLong() shl 24)

    private fun ascii4(bytes: ByteArray, offset: Int): String = buildString(4) {
        repeat(4) { append((bytes[offset + it].toInt() and 0xff).toChar()) }
    }

    private companion object {
        val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val PNG_IHDR = intArrayOf(0x49, 0x48, 0x44, 0x52)
        val GIF_87A = intArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
        val GIF_89A = intArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        val WEBP_RIFF = intArrayOf(0x52, 0x49, 0x46, 0x46)
        val WEBP_MAGIC = intArrayOf(0x57, 0x45, 0x42, 0x50)
        val WEBP_VP8_FRAME_MAGIC = intArrayOf(0x9d, 0x01, 0x2a)
        val JPEG_SOF_MARKERS = setOf(
            0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
            0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf
        )
    }
}
