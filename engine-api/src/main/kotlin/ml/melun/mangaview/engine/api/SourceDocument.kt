package ml.melun.mangaview.engine.api

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

/** Immutable response, including cookies needed to replay its document in a browser. */
class SourceDocument(val finalUrl: URI, body: ByteArray, responseHeaders: Map<String, List<String>> = emptyMap()) {
    private val bytes = body.copyOf()
    val byteCount: Int get() = bytes.size
    val responseHeaders: Map<String, List<String>> = Collections.unmodifiableMap(
        responseHeaders.mapValues { (_, values) -> Collections.unmodifiableList(values.toList()) },
    )
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    /** Replay identity includes response cookies and final origin as well as the body digest. */
    val replaySha256: String = MessageDigest.getInstance("SHA-256").digest(
        (listOf(finalUrl.toString(), sha256) + this.responseHeaders.entries.sortedBy { it.key }.flatMap {
            listOf(it.key, it.value.size.toString()) + it.value
        }).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8),
    ).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    init {
        require(finalUrl.scheme in setOf("https", "http") && !finalUrl.host.isNullOrBlank())
        require(bytes.isNotEmpty()) { "Source document is empty" }
    }

    fun openBody(): InputStream = ByteArrayInputStream(bytes)

    override fun toString(): String = "SourceDocument(sha256=" + sha256 + ", bytes=" + bytes.size + ")"
}
