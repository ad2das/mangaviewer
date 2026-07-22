package ml.melun.mangaview.reader

/**
 * Incremental, pixel-decode-free image geometry parser for strict NTK source metadata.
 *
 * This parser reads only encoded container headers. It never delegates to a platform image
 * decoder, and therefore cannot allocate or decode pixels. Range callers own their 512 KiB probe
 * ceiling; a retained full body may continue feeding header bytes beyond that cap.
 */
class NtkExactImageHeaderParser {
    sealed interface Result {
        data class Exact(
            val width: Int,
            val height: Int,
            val format: String,
            val consumedBytes: Int
        ) : Result

        data class NeedMore(val minimumBytes: Int) : Result

        data class Invalid(val reason: String) : Result
    }

    fun feed(prefix: ByteArray): Result {
        if (prefix.isEmpty()) return Result.NeedMore(1)
        return when (u8(prefix, 0)) {
            JPEG_MAGIC_0 -> {
                if (prefix.size < JPEG_MAGIC.size) {
                    need(prefix, JPEG_MAGIC.size)
                } else if (!matches(prefix, 0, JPEG_MAGIC)) {
                    invalid("unsupported_magic")
                } else {
                    parseJpeg(prefix)
                }
            }
            PNG_SIGNATURE[0] -> parseSignatureThen(prefix, PNG_SIGNATURE, ::parsePng)
            WEBP_RIFF[0] -> parseWebpSignature(prefix)
            GIF_87A[0] -> parseGifSignature(prefix)
            else -> invalid("unsupported_magic")
        }
    }

    private fun parseJpeg(prefix: ByteArray): Result {
        var markerStart = JPEG_MAGIC.size
        while (true) {
            if (prefix.size <= markerStart) return need(prefix, markerStart + 1)
            if (u8(prefix, markerStart) != JPEG_MARKER_PREFIX) {
                return invalid("jpeg_expected_marker")
            }

            var markerCodeOffset = markerStart + 1
            while (true) {
                if (prefix.size <= markerCodeOffset) return need(prefix, markerCodeOffset + 1)
                if (u8(prefix, markerCodeOffset) != JPEG_MARKER_PREFIX) break
                markerCodeOffset++
            }
            val marker = u8(prefix, markerCodeOffset)
            if (marker == 0x00) return invalid("jpeg_stuffed_byte_before_sof")
            if (marker in JPEG_SOF_MARKERS) {
                val lengthOffset = markerCodeOffset + 1
                if (prefix.size < lengthOffset + 2) return need(prefix, lengthOffset + 2)
                val segmentLength = be16(prefix, lengthOffset)
                if (segmentLength < JPEG_MIN_SOF_SEGMENT_LENGTH) {
                    return invalid("jpeg_invalid_sof_length")
                }
                val componentCountOffset = lengthOffset + 7
                if (prefix.size <= componentCountOffset) {
                    return need(prefix, componentCountOffset + 1)
                }
                val componentCount = u8(prefix, componentCountOffset)
                if (componentCount <= 0 || segmentLength != 8 + 3 * componentCount) {
                    return invalid("jpeg_invalid_sof_components")
                }
                val height = be16(prefix, lengthOffset + 3)
                val width = be16(prefix, lengthOffset + 5)
                if (width <= 0 || height <= 0) return invalid("jpeg_invalid_dimensions")
                return Result.Exact(
                    width = width,
                    height = height,
                    format = FORMAT_JPEG,
                    consumedBytes = componentCountOffset + 1
                )
            }

            when {
                marker == JPEG_START_OF_SCAN -> return invalid("jpeg_scan_before_sof")
                marker == JPEG_END_OF_IMAGE -> return invalid("jpeg_end_before_sof")
                marker == JPEG_START_OF_IMAGE -> return invalid("jpeg_duplicate_soi")
                marker in JPEG_RESTART_MARKERS ->
                    return invalid("jpeg_restart_before_sof")
                marker == JPEG_TEMPORARY -> {
                    markerStart = markerCodeOffset + 1
                    continue
                }
            }

            val lengthOffset = markerCodeOffset + 1
            if (prefix.size < lengthOffset + 2) return need(prefix, lengthOffset + 2)
            val segmentLength = be16(prefix, lengthOffset)
            if (segmentLength < 2) return invalid("jpeg_invalid_segment_length")
            val segmentEnd = checkedOffset(lengthOffset, segmentLength)
                ?: return invalid("jpeg_segment_offset_overflow")
            if (prefix.size < segmentEnd) return need(prefix, segmentEnd)
            markerStart = segmentEnd
        }
    }

    private fun parsePng(prefix: ByteArray): Result {
        if (prefix.size < PNG_IHDR_PREFIX_BYTES) return need(prefix, PNG_IHDR_PREFIX_BYTES)
        val ihdrLength = be32(prefix, PNG_SIGNATURE.size)
        if (ihdrLength != PNG_IHDR_DATA_BYTES.toLong()) return invalid("png_invalid_ihdr_length")
        if (!matches(prefix, PNG_SIGNATURE.size + 4, PNG_IHDR)) {
            return invalid("png_ihdr_not_first")
        }
        if (prefix.size < PNG_DIMENSION_BYTES) return need(prefix, PNG_DIMENSION_BYTES)
        val width = positiveIntDimension(be32(prefix, 16))
            ?: return invalid("png_invalid_width")
        val height = positiveIntDimension(be32(prefix, 20))
            ?: return invalid("png_invalid_height")
        return Result.Exact(width, height, FORMAT_PNG, PNG_DIMENSION_BYTES)
    }

    private fun parseWebpSignature(prefix: ByteArray): Result {
        if (prefix.size < WEBP_RIFF.size) {
            return if (matchesAvailable(prefix, WEBP_RIFF)) {
                need(prefix, WEBP_RIFF.size)
            } else {
                invalid("unsupported_magic")
            }
        }
        if (!matches(prefix, 0, WEBP_RIFF)) return invalid("unsupported_magic")
        if (prefix.size < WEBP_CONTAINER_HEADER_BYTES) {
            return need(prefix, WEBP_CONTAINER_HEADER_BYTES)
        }
        if (!matches(prefix, 8, WEBP_MAGIC)) return invalid("riff_is_not_webp")
        return parseWebp(prefix)
    }

    private fun parseWebp(prefix: ByteArray): Result {
        val riffPayloadBytes = le32(prefix, 4)
        val declaredEnd = riffPayloadBytes + 8L
        if (declaredEnd < WEBP_CONTAINER_HEADER_BYTES.toLong()) {
            return invalid("webp_invalid_riff_length")
        }

        var chunkOffset = WEBP_CONTAINER_HEADER_BYTES
        while (true) {
            if (chunkOffset.toLong() == declaredEnd) return invalid("webp_missing_dimension_chunk")
            if (chunkOffset.toLong() > declaredEnd) return invalid("webp_chunk_past_riff")
            val chunkHeaderEnd = chunkOffset.toLong() + WEBP_CHUNK_HEADER_BYTES
            if (chunkHeaderEnd > declaredEnd) return invalid("webp_truncated_declared_chunk_header")
            if (prefix.size.toLong() < chunkHeaderEnd) return need(prefix, chunkHeaderEnd.toInt())

            val chunkSize = le32(prefix, chunkOffset + 4)
            val dataOffset = chunkOffset + WEBP_CHUNK_HEADER_BYTES
            val paddedChunkSize = chunkSize + (chunkSize and 1L)
            val nextChunk = dataOffset.toLong() + paddedChunkSize
            if (nextChunk > declaredEnd) return invalid("webp_chunk_exceeds_riff")

            val type = ascii4(prefix, chunkOffset)
            when (type) {
                WEBP_CHUNK_VP8 -> return parseWebpVp8(prefix, dataOffset, chunkSize, declaredEnd)
                WEBP_CHUNK_VP8L -> return parseWebpVp8l(prefix, dataOffset, chunkSize, declaredEnd)
                WEBP_CHUNK_VP8X -> return parseWebpVp8x(prefix, dataOffset, chunkSize, declaredEnd)
            }

            if (nextChunk > Int.MAX_VALUE.toLong()) return invalid("webp_chunk_offset_overflow")
            if (prefix.size.toLong() < nextChunk) return need(prefix, nextChunk.toInt())
            chunkOffset = nextChunk.toInt()
        }
    }

    private fun parseWebpVp8(
        prefix: ByteArray,
        dataOffset: Int,
        chunkSize: Long,
        declaredEnd: Long
    ): Result {
        if (chunkSize < WEBP_VP8_HEADER_BYTES) return invalid("webp_vp8_chunk_too_small")
        val required = dataOffset.toLong() + WEBP_VP8_HEADER_BYTES
        if (required > declaredEnd) return invalid("webp_vp8_header_exceeds_riff")
        if (prefix.size.toLong() < required) return need(prefix, required.toInt())
        val frameTag = u8(prefix, dataOffset) or
            (u8(prefix, dataOffset + 1) shl 8) or
            (u8(prefix, dataOffset + 2) shl 16)
        if ((frameTag and 1) != 0) return invalid("webp_vp8_not_key_frame")
        if (!matches(prefix, dataOffset + 3, WEBP_VP8_FRAME_MAGIC)) {
            return invalid("webp_vp8_bad_frame_magic")
        }
        val width = le16(prefix, dataOffset + 6) and 0x3fff
        val height = le16(prefix, dataOffset + 8) and 0x3fff
        if (width <= 0 || height <= 0) return invalid("webp_vp8_invalid_dimensions")
        return Result.Exact(width, height, FORMAT_WEBP, required.toInt())
    }

    private fun parseWebpVp8l(
        prefix: ByteArray,
        dataOffset: Int,
        chunkSize: Long,
        declaredEnd: Long
    ): Result {
        if (chunkSize < WEBP_VP8L_HEADER_BYTES) return invalid("webp_vp8l_chunk_too_small")
        val required = dataOffset.toLong() + WEBP_VP8L_HEADER_BYTES
        if (required > declaredEnd) return invalid("webp_vp8l_header_exceeds_riff")
        if (prefix.size.toLong() < required) return need(prefix, required.toInt())
        if (u8(prefix, dataOffset) != WEBP_VP8L_MAGIC) {
            return invalid("webp_vp8l_bad_magic")
        }
        val packed = le32(prefix, dataOffset + 1)
        val version = (packed ushr 29) and 0x7L
        if (version != 0L) return invalid("webp_vp8l_unsupported_version")
        val width = ((packed and 0x3fffL) + 1L).toInt()
        val height = (((packed ushr 14) and 0x3fffL) + 1L).toInt()
        return Result.Exact(width, height, FORMAT_WEBP, required.toInt())
    }

    private fun parseWebpVp8x(
        prefix: ByteArray,
        dataOffset: Int,
        chunkSize: Long,
        declaredEnd: Long
    ): Result {
        if (chunkSize < WEBP_VP8X_HEADER_BYTES) return invalid("webp_vp8x_chunk_too_small")
        val required = dataOffset.toLong() + WEBP_VP8X_HEADER_BYTES
        if (required > declaredEnd) return invalid("webp_vp8x_header_exceeds_riff")
        if (prefix.size.toLong() < required) return need(prefix, required.toInt())
        if (u8(prefix, dataOffset + 1) != 0 || u8(prefix, dataOffset + 2) != 0 ||
            u8(prefix, dataOffset + 3) != 0
        ) {
            return invalid("webp_vp8x_reserved_bytes_nonzero")
        }
        val width = le24(prefix, dataOffset + 4) + 1
        val height = le24(prefix, dataOffset + 7) + 1
        return Result.Exact(width, height, FORMAT_WEBP, required.toInt())
    }

    private fun parseGifSignature(prefix: ByteArray): Result {
        val possible = matchesAvailable(prefix, GIF_87A) || matchesAvailable(prefix, GIF_89A)
        if (!possible) return invalid("unsupported_magic")
        if (prefix.size < GIF_87A.size) return need(prefix, GIF_87A.size)
        if (!matches(prefix, 0, GIF_87A) && !matches(prefix, 0, GIF_89A)) {
            return invalid("gif_invalid_version")
        }
        if (prefix.size < GIF_DIMENSION_BYTES) return need(prefix, GIF_DIMENSION_BYTES)
        val width = le16(prefix, 6)
        val height = le16(prefix, 8)
        if (width <= 0 || height <= 0) return invalid("gif_invalid_dimensions")
        return Result.Exact(width, height, FORMAT_GIF, GIF_DIMENSION_BYTES)
    }

    private fun parseSignatureThen(
        prefix: ByteArray,
        signature: IntArray,
        parser: (ByteArray) -> Result
    ): Result {
        if (!matchesAvailable(prefix, signature)) return invalid("unsupported_magic")
        if (prefix.size < signature.size) return need(prefix, signature.size)
        return parser(prefix)
    }

    private fun matchesAvailable(bytes: ByteArray, signature: IntArray): Boolean {
        val compared = minOf(bytes.size, signature.size)
        for (index in 0 until compared) {
            if (u8(bytes, index) != signature[index]) return false
        }
        return true
    }

    private fun matches(bytes: ByteArray, offset: Int, signature: IntArray): Boolean {
        if (offset < 0 || bytes.size - offset < signature.size) return false
        for (index in signature.indices) {
            if (u8(bytes, offset + index) != signature[index]) return false
        }
        return true
    }

    private fun need(prefix: ByteArray, minimumBytes: Int): Result.NeedMore =
        Result.NeedMore(minimumBytes.coerceAtLeast(prefix.size + 1))

    private fun invalid(reason: String): Result.Invalid = Result.Invalid(reason)

    private fun positiveIntDimension(value: Long): Int? =
        value.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    private fun checkedOffset(offset: Int, length: Int): Int? {
        val result = offset.toLong() + length.toLong()
        return result.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    private fun be16(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    private fun le24(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8) or
            (u8(bytes, offset + 2) shl 16)

    private fun be32(bytes: ByteArray, offset: Int): Long =
        (u8(bytes, offset).toLong() shl 24) or
            (u8(bytes, offset + 1).toLong() shl 16) or
            (u8(bytes, offset + 2).toLong() shl 8) or
            u8(bytes, offset + 3).toLong()

    private fun le32(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or
            (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or
            (u8(bytes, offset + 3).toLong() shl 24)

    private fun ascii4(bytes: ByteArray, offset: Int): String = buildString(4) {
        repeat(4) { append(u8(bytes, offset + it).toChar()) }
    }

    companion object {
        const val FORMAT_JPEG = "JPEG"
        const val FORMAT_PNG = "PNG"
        const val FORMAT_WEBP = "WEBP"
        const val FORMAT_GIF = "GIF"

        @JvmField
        val SUPPORTED_FORMATS: Set<String> = linkedSetOf(
            FORMAT_JPEG,
            FORMAT_PNG,
            FORMAT_WEBP,
            FORMAT_GIF
        )

        private val JPEG_MAGIC = intArrayOf(0xff, 0xd8)
        private const val JPEG_MAGIC_0 = 0xff
        private const val JPEG_MARKER_PREFIX = 0xff
        private const val JPEG_START_OF_IMAGE = 0xd8
        private const val JPEG_END_OF_IMAGE = 0xd9
        private const val JPEG_START_OF_SCAN = 0xda
        private const val JPEG_TEMPORARY = 0x01
        private const val JPEG_MIN_SOF_SEGMENT_LENGTH = 11
        private val JPEG_RESTART_MARKERS = 0xd0..0xd7
        private val JPEG_SOF_MARKERS = setOf(
            0xc0, 0xc1, 0xc2, 0xc3,
            0xc5, 0xc6, 0xc7,
            0xc9, 0xca, 0xcb,
            0xcd, 0xce, 0xcf
        )

        private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val PNG_IHDR = intArrayOf(0x49, 0x48, 0x44, 0x52)
        private const val PNG_IHDR_DATA_BYTES = 13
        private const val PNG_IHDR_PREFIX_BYTES = 16
        private const val PNG_DIMENSION_BYTES = 24

        private val WEBP_RIFF = intArrayOf(0x52, 0x49, 0x46, 0x46)
        private val WEBP_MAGIC = intArrayOf(0x57, 0x45, 0x42, 0x50)
        private val WEBP_VP8_FRAME_MAGIC = intArrayOf(0x9d, 0x01, 0x2a)
        private const val WEBP_CONTAINER_HEADER_BYTES = 12
        private const val WEBP_CHUNK_HEADER_BYTES = 8
        private const val WEBP_VP8_HEADER_BYTES = 10L
        private const val WEBP_VP8L_HEADER_BYTES = 5L
        private const val WEBP_VP8X_HEADER_BYTES = 10L
        private const val WEBP_VP8L_MAGIC = 0x2f
        private const val WEBP_CHUNK_VP8 = "VP8 "
        private const val WEBP_CHUNK_VP8L = "VP8L"
        private const val WEBP_CHUNK_VP8X = "VP8X"

        private val GIF_87A = intArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
        private val GIF_89A = intArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        private const val GIF_DIMENSION_BYTES = 10
    }
}
