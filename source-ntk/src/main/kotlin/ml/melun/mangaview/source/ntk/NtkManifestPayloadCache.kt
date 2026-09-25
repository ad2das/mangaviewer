package ml.melun.mangaview.source.ntk

import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for the provider's native manifest payload (the protected page list). The payload
 * exchange is a single protected POST whose round trip lands on the first-image critical path
 * (measured ~0.5s), and the payload itself is immutable for a given document and viewer token.
 * Entries expire after [ttlMillis]; a stale entry simply fails the caller's own proof check and
 * the normal exchange runs.
 */
class NtkManifestPayloadCache(
    private val directory: File,
    private val ttlMillis: Long,
) {
    data class Payload(val body: String, val finalUrl: String)

    fun load(documentSha256: String, token: String): Payload? {
        val file = fileFor(documentSha256, token)
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            val first = text.indexOf('\n')
            if (first <= 0) return null
            val second = text.indexOf('\n', first + 1)
            if (second < 0) return null
            val age = System.currentTimeMillis() - text.substring(0, first).toLong()
            if (age < 0 || age > ttlMillis) return null
            val body = text.substring(second + 1)
            if (body.isBlank()) return null
            Payload(body, text.substring(first + 1, second))
        }.getOrNull().also { loaded ->
            if (loaded == null) runCatching { file.delete() }
        }
    }

    fun save(documentSha256: String, token: String, body: String, finalUrl: String) {
        if (body.isBlank()) return
        runCatching {
            directory.mkdirs()
            fileFor(documentSha256, token)
                .writeText("${System.currentTimeMillis()}\n$finalUrl\n$body")
        }
    }

    private fun fileFor(documentSha256: String, token: String): File =
        File(directory, sha256Hex("$documentSha256:$token".toByteArray(Charsets.UTF_8)) + ".txt")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
