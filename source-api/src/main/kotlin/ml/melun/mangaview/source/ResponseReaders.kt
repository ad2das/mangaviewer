package ml.melun.mangaview.source

import java.io.ByteArrayOutputStream

suspend fun SourceResponse.readBytes(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "Response byte limit must be positive" }
    return try {
        val output = ByteArrayOutputStream(minOf(maxBytes, contentLength?.toInt() ?: 8_192))
        val buffer = ByteArray(16 * 1_024)
        while (true) {
            val count = body.readAtMost(buffer, 0, buffer.size)
            if (count < 0) break
            require(count > 0) { "Response stream returned zero bytes" }
            require(output.size() <= maxBytes - count) { "Response exceeds its byte limit" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } finally {
        close()
    }
}
