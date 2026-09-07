package ml.melun.mangaview.source.ntk

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceResponse
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class NtkBrowserDocumentPayload private constructor(
    val key: String,
    private val file: ParcelFileDescriptor,
    private val byteCount: Int,
    private val headers: Map<String, List<String>>,
) : Closeable {
    private var closed = false
    val cookies: List<String> = headers.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }

    @Synchronized
    fun writeTo(bundle: Bundle) {
        check(!closed) { "NTK document is closed" }
        bundle.putParcelable(DOCUMENT_FILE, file)
        bundle.putString(DOCUMENT_KEY, key)
        bundle.putInt(DOCUMENT_LENGTH, byteCount)
        bundle.putBundle(DOCUMENT_HEADERS, Bundle().apply {
            headers.forEach { (name, values) -> putStringArrayList(name, ArrayList(values)) }
        })
    }

    @Synchronized
    fun response(): WebResourceResponse {
        check(!closed) { "NTK document is closed" }
        val stream = PositionedDocumentStream(ParcelFileDescriptor.dup(file.fileDescriptor), byteCount)
        val responseHeaders = headers.filterKeys { it.lowercase() !in REENCODED_HEADERS }
            .mapValues { (_, values) -> values.joinToString(", ") }
            .plus("Content-Length" to byteCount.toString())
        return WebResourceResponse("text/html", "UTF-8", 200, "OK", responseHeaders, stream)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        file.close()
    }

    companion object {
        suspend fun create(root: File, document: NtkEpisodeDocument): NtkBrowserDocumentPayload {
            validateNtkDocumentIdentity(document, document.path)
            var payload: NtkBrowserDocumentPayload? = null
            try {
                withContext(Dispatchers.IO) { payload = stage(root, document) }
                return requireNotNull(payload)
            } catch (failure: Throwable) {
                payload?.close()
                throw failure
            }
        }

        private fun stage(root: File, document: NtkEpisodeDocument): NtkBrowserDocumentPayload {
            val bytes = document.html.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..MAX_DOCUMENT_BYTES) { "Invalid NTK document length" }
            val temporary = File.createTempFile("ntk-document-", ".html", root)
            var descriptor: ParcelFileDescriptor? = null
            try {
                temporary.outputStream().use { it.write(bytes) }
                descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
                check(temporary.delete()) { "Could not unlink NTK document staging file" }
                return NtkBrowserDocumentPayload(
                    validatedKey(document.origin, document.path), descriptor, bytes.size,
                    document.responseHeaders.mapValues { (_, values) -> values.toList() },
                )
            } catch (failure: Throwable) {
                descriptor?.close()
                temporary.delete()
                throw failure
            }
        }

        @Suppress("DEPRECATION")
        fun receive(bundle: Bundle): NtkBrowserDocumentPayload {
            val descriptor = requireNotNull(bundle.getParcelable<ParcelFileDescriptor>(DOCUMENT_FILE)) {
                "NTK document file is missing"
            }
            try {
                val key = bundle.requiredString(DOCUMENT_KEY)
                val length = bundle.getInt(DOCUMENT_LENGTH)
                require(length in 1..MAX_DOCUMENT_BYTES) { "Invalid NTK document length" }
                val headerBundle = bundle.getBundle(DOCUMENT_HEADERS) ?: Bundle.EMPTY
                val headers = headerBundle.keySet().associateWith { name ->
                    headerBundle.getStringArrayList(name)?.toList().orEmpty()
                }
                return NtkBrowserDocumentPayload(key, descriptor, length, headers)
            } catch (failure: Throwable) {
                descriptor.close()
                throw failure
            }
        }

        private const val DOCUMENT_FILE = "documentFile"
        private const val DOCUMENT_KEY = "documentKey"
        private const val DOCUMENT_LENGTH = "documentLength"
        private const val DOCUMENT_HEADERS = "documentHeaders"
        private const val MAX_DOCUMENT_BYTES = 32 * 1_024 * 1_024
        private val REENCODED_HEADERS = setOf(
            "content-encoding", "content-length", "transfer-encoding", "set-cookie",
        )
    }
}

private class PositionedDocumentStream(
    descriptor: ParcelFileDescriptor,
    private val byteCount: Int,
) : InputStream() {
    private val source = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    private var offset = 0L

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(destination: ByteArray, start: Int, length: Int): Int {
        require(start >= 0 && length >= 0 && start <= destination.size - length)
        if (length == 0) return 0
        if (offset >= byteCount) return -1
        val count = source.channel.read(
            ByteBuffer.wrap(destination, start, minOf(length.toLong(), byteCount - offset).toInt()),
            offset,
        )
        check(count > 0) { "NTK document ended before its declared length" }
        offset += count
        return count
    }

    override fun available(): Int = (byteCount - offset).coerceAtLeast(0L).toInt()
    override fun close() = source.close()
}
