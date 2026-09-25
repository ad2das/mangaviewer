package ml.melun.mangaview.app

import java.io.File
import java.net.URI
import java.security.MessageDigest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.engine.api.SourceDocument
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disk cache for NTK episode documents. A cold start that re-enters a recently seen episode
 * resolves its page plan from here instead of paying the provider's cold document round trip,
 * which dominates the first-tile demand path (measured ~1.9s headers on the GPU AVD).
 *
 * The cache trusts nothing: entries expire after [ttlMillis], and a stored digest must match the
 * body byte-for-byte or the entry is discarded and the normal network path runs.
 */
internal class NtkEpisodeDocumentCache(
    private val directory: File,
    private val ttlMillis: Long,
) {
    fun load(episodeId: EpisodeId): SourceDocument? {
        val name = nameFor(episodeId)
        val body = File(directory, "$name.bin")
        val meta = File(directory, "$name.json")
        if (!body.isFile || !meta.isFile) return null
        return runCatching {
            val json = JSONObject(meta.readText())
            val age = System.currentTimeMillis() - json.getLong("savedAtMillis")
            if (age < 0 || age > ttlMillis) return null
            val bytes = body.readBytes()
            if (bytes.isEmpty()) return null
            val digest = sha256Hex(bytes)
            if (digest != json.getString("sha256")) return null
            SourceDocument(URI(json.getString("finalUrl")), bytes, headersFrom(json.optJSONObject("headers")))
        }.getOrNull().also { loaded ->
            if (loaded == null) runCatching { body.delete(); meta.delete() }
        }
    }

    fun save(episodeId: EpisodeId, document: SourceDocument) {
        runCatching {
            directory.mkdirs()
            val name = nameFor(episodeId)
            val body = File(directory, "$name.bin")
            val meta = File(directory, "$name.json")
            val bytes = document.openBody().readBytes()
            val staged = File(directory, "$name.bin.tmp")
            staged.writeBytes(bytes)
            if (!staged.renameTo(body)) {
                body.writeBytes(bytes)
                staged.delete()
            }
            meta.writeText(JSONObject()
                .put("finalUrl", document.finalUrl.toString())
                .put("sha256", sha256Hex(bytes))
                .put("savedAtMillis", System.currentTimeMillis())
                .put("headers", JSONObject().apply {
                    document.responseHeaders.forEach { (key, values) -> put(key, JSONArray(values)) }
                })
                .toString())
        }
    }

    private fun headersFrom(json: JSONObject?): Map<String, List<String>> {
        if (json == null) return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                val values = json.optJSONArray(key) ?: return@forEach
                put(key, List(values.length()) { index -> values.optString(index) })
            }
        }
    }

    private fun nameFor(episodeId: EpisodeId): String =
        sha256Hex(episodeId.toString().toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
