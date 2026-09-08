package ml.melun.mangaview.data.network

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/** Changes TLS record boundaries only. The handshake bytes and end-to-end TLS stay identical. */
internal object TlsClientHelloFragmenter {
    fun forwardFirstRecord(input: InputStream, output: OutputStream) {
        val source = DataInputStream(input)
        val header = ByteArray(5)
        source.readFully(header)
        val length = unsigned16(header, 3)
        require(length in 1..18_432) { "Invalid TLS record length" }
        val body = ByteArray(length)
        source.readFully(body)
        val split = if (header[0].toInt() == 22 && body[0].toInt() == 1) sniSplit(body) else null
        if (split == null) {
            output.write(header)
            output.write(body)
        } else {
            writeRecord(output, header, body, 0, split)
            output.flush()
            writeRecord(output, header, body, split, body.size - split)
        }
        output.flush()
    }

    private fun writeRecord(output: OutputStream, header: ByteArray, body: ByteArray, offset: Int, length: Int) {
        output.write(header, 0, 3)
        output.write(length ushr 8)
        output.write(length and 255)
        output.write(body, offset, length)
    }

    /** Offset inside the first DNS hostname, after the ClientHello's variable-length fields. */
    internal fun sniSplit(body: ByteArray): Int? = try {
        var cursor = 4 + 2 + 32
        cursor += 1 + (body[cursor].toInt() and 255)
        cursor += 2 + unsigned16(body, cursor)
        cursor += 1 + (body[cursor].toInt() and 255)
        val end = cursor + 2 + unsigned16(body, cursor)
        cursor += 2
        require(end <= body.size)
        var split: Int? = null
        while (cursor + 4 <= end && split == null) {
            val type = unsigned16(body, cursor)
            val length = unsigned16(body, cursor + 2)
            val value = cursor + 4
            require(value + length <= end)
            if (type == 0 && length >= 6 && body[value + 2].toInt() == 0) {
                val hostnameLength = unsigned16(body, value + 3)
                require(hostnameLength > 1 && value + 5 + hostnameLength <= value + length)
                split = value + 5 + hostnameLength / 2
            }
            cursor = value + length
        }
        split
    } catch (_: IndexOutOfBoundsException) { null }
      catch (_: IllegalArgumentException) { null }

    private fun unsigned16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
}
