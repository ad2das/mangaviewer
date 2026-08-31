package ml.melun.mangaview.data.network

import java.io.IOException

internal object HttpEngineResponseHeaders {
    fun contentLength(headers: Map<String, List<String>>): Long? {
        val values = headers.entries
            .filter { it.key.equals("Content-Length", ignoreCase = true) }
            .flatMap { entry -> entry.value.flatMap { it.split(',') } }
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (values.isEmpty()) return null
        val parsed = values.map(::parseLength)
        val length = parsed.first()
        if (parsed.any { it != length }) {
            throw IOException("HTTP engine content lengths conflict")
        }
        val encoding = value(headers, "Content-Encoding")?.trim()
        return length.takeIf { encoding.isNullOrEmpty() || encoding.equals("identity", true) }
    }

    fun value(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.lastOrNull()

    private fun parseLength(value: String): Long {
        val length = value.toLongOrNull()?.takeIf { it > 0L }
            ?: throw IOException("HTTP engine content length is invalid")
        if (length > HttpEngineBodyPageStream.MAX_BODY_BYTES) {
            throw IOException("HTTP engine response exceeds the body limit")
        }
        return length
    }
}
