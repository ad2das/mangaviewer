package ml.melun.mangaview.viewer.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Parsed wire packet from the owned renderer's asynchronous strip readback. */
internal data class EngineReadbackPacket(
    val status: Status,
    val sessionId: Long,
    val rendererEpoch: Long,
    val surfaceEpoch: Long,
    val token: Long,
    val eglFrameId: Long,
    val width: Long,
    val top: Long,
    val bottom: Long,
    val captureIssuedMonotonicNs: Long,
    val captureReadyMonotonicNs: Long,
    val swapCompletedMonotonicNs: Long,
    val rgbaByteCount: Long,
    val physicalPresentationVerified: Boolean,
    val rgbaBytes: ByteArray,
) {
    private var nativeHeader: ByteArray? = null
    private var nativePayloadDigest: ByteArray? = null

    /** Export only bytes preserved from parsing; copied/model-created packets are not native evidence. */
    fun nativePacketBytes(): ByteArray {
        val header = checkNotNull(nativeHeader) { "No original native packet header" }
        check(MessageDigest.isEqual(nativePayloadDigest,
            MessageDigest.getInstance("SHA-256").digest(rgbaBytes))) { "Native pixel payload was modified" }
        return header + rgbaBytes
    }

    internal enum class Status(val wireValue: Long) {
        OK(1L),
        CANCELLED(2L),
        CONTEXT_LOST(3L),
        GL_ERROR(4L),
        SWAP_FAILED(5L),
        ;

        companion object {
            fun fromWire(value: Long): Status = entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown readback status: $value")
        }
    }

    companion object {
        const val MAGIC: Long = 0x4552474253545250L
        const val VERSION: Long = 1L
        const val HEADER_BYTES: Int = 16 * Long.SIZE_BYTES

        private data class Header(
            val status: Status,
            val sessionId: Long,
            val rendererEpoch: Long,
            val surfaceEpoch: Long,
            val token: Long,
            val eglFrameId: Long,
            val width: Long,
            val top: Long,
            val bottom: Long,
            val issued: Long,
            val ready: Long,
            val swapCompleted: Long,
            val rgbaByteCount: Long,
            val physicalFlag: Long,
        )

        fun parse(packet: ByteArray): EngineReadbackPacket {
            val header = decodeHeader(packet)
            val expectedPayload = validateHeader(header)
            require(HEADER_BYTES.toLong() + expectedPayload == packet.size.toLong()) {
                "Readback packet length does not match its header"
            }
            val payload = packet.copyOfRange(HEADER_BYTES, packet.size)
            return EngineReadbackPacket(
                header.status, header.sessionId, header.rendererEpoch, header.surfaceEpoch,
                header.token, header.eglFrameId, header.width, header.top, header.bottom,
                header.issued, header.ready, header.swapCompleted, header.rgbaByteCount,
                false, payload,
            ).also {
                it.nativeHeader = packet.copyOfRange(0, HEADER_BYTES)
                it.nativePayloadDigest = MessageDigest.getInstance("SHA-256").digest(payload)
            }
        }

        private fun decodeHeader(packet: ByteArray): Header {
            require(packet.size >= HEADER_BYTES) { "Readback packet header is truncated" }
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.long == MAGIC) { "Readback packet magic is invalid" }
            require(buffer.long == VERSION) { "Readback packet version is unsupported" }
            return Header(
                Status.fromWire(buffer.long), buffer.long, buffer.long, buffer.long, buffer.long,
                buffer.long, buffer.long, buffer.long, buffer.long, buffer.long, buffer.long,
                buffer.long, buffer.long, buffer.long,
            )
        }

        private fun validateHeader(header: Header): Long {
            validateIdentityAndGeometry(header)
            return validateTimestampsAndPayload(header)
        }

        private fun validateIdentityAndGeometry(header: Header) {
            require(header.sessionId > 0L && header.rendererEpoch > 0L &&
                header.surfaceEpoch > 0L && header.token > 0L) {
                "Readback packet identity is not positive"
            }
            require(header.eglFrameId >= 0L) { "Readback EGL frame id is negative" }
            require(header.width >= 0L && header.width <= Int.MAX_VALUE.toLong()) {
                "Readback width is impossible"
            }
            require(header.top >= 0L && header.bottom > header.top &&
                header.bottom <= Int.MAX_VALUE.toLong()) {
                "Readback boundaries are impossible"
            }
        }

        private fun validateTimestampsAndPayload(header: Header): Long {
            require(header.issued >= 0L && header.ready >= 0L && header.swapCompleted >= 0L) {
                "Readback timestamps are negative"
            }
            if (header.status == Status.OK) {
                require(header.issued > 0L && header.ready >= header.issued &&
                    header.swapCompleted >= header.issued && header.ready >= header.swapCompleted) {
                    "Successful readback timestamps are inverted or unavailable"
                }
            } else if (header.issued > 0L) {
                require(header.ready == 0L || header.ready >= header.issued) {
                    "Readback ready time precedes capture issue"
                }
                require(header.swapCompleted == 0L || header.swapCompleted >= header.issued) {
                    "Readback swap time precedes capture issue"
                }
            }
            require(header.rgbaByteCount >= 0L) { "Readback byte count is negative" }
            require(header.physicalFlag == 0L) { "Physical presentation proof is unavailable" }
            val payload = expectedPayloadBytes(header)
            require(payload <= Int.MAX_VALUE.toLong()) {
                "Readback payload is too large for a byte array"
            }
            return payload
        }

        private fun expectedPayloadBytes(header: Header): Long {
            if (header.status != Status.OK) {
                require(header.rgbaByteCount == 0L) {
                    "Failure packet must not contain RGBA bytes"
                }
                return 0L
            }
            require(header.width > 0L) { "Successful readback width is not positive" }
            return try {
                val rows = Math.subtractExact(header.bottom, header.top)
                val pixels = Math.multiplyExact(header.width, rows)
                Math.multiplyExact(pixels, 4L).also {
                    require(it == header.rgbaByteCount) {
                        "Successful readback payload length is incorrect"
                    }
                }
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Readback payload size overflows", overflow)
            }
        }
    }
}
