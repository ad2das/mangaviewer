package ml.melun.mangaview.source.ntk

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Decodes JSON strings once, joining Flight chunks before inspecting their complete records. */
internal object NtkDocumentJsonReader {
    fun read(scripts: List<String>): List<Any> {
        val roots = mutableListOf<Any>()
        val flight = StringBuilder()
        scripts.forEach { script ->
            if (script.contains(FLIGHT_PUSH)) appendFlight(script, flight) else if (hasViewerFields(script)) {
                readJsonContainers(script, roots)
            }
        }
        readFlight(flight.toString(), roots)
        return roots
    }

    private fun appendFlight(script: String, flight: StringBuilder) {
        var cursor = 0
        while (cursor < script.length) {
            val push = script.indexOf(FLIGHT_PUSH, cursor)
            if (push < 0) return
            val start = script.indexOf('[', push + FLIGHT_PUSH.length)
            if (start < 0) return
            val end = containerEnd(script, start) ?: return
            val value = parse(script.substring(start, end)) as? JSONArray
            if (value?.optInt(0) == 1 && value.opt(1) is String) flight.append(value.getString(1))
            cursor = end
        }
    }

    private fun readFlight(text: String, roots: MutableList<Any>) {
        var cursor = 0
        while (cursor < text.length) {
            val colon = text.indexOf(':', cursor)
            if (colon < 0) return
            val id = text.substring(cursor, colon)
            if (id.any { it.digitToIntOrNull(16) == null }) {
                cursor = nextLine(text, cursor)
                continue
            }
            val start = colon + 1
            if (text.getOrNull(start) == 'T') {
                val comma = text.indexOf(',', start)
                require(comma > start) { "NTK Flight text record has no length" }
                val bytes = text.substring(start + 1, comma).toIntOrNull(16)
                    ?: error("NTK Flight text record has an invalid length")
                cursor = skipUtf8(text, comma + 1, bytes)
            } else {
                val end = if (text.getOrNull(start) in listOf('{', '[')) containerEnd(text, start) else null
                if (end != null) {
                    val json = text.substring(start, end)
                    if (hasViewerFields(json)) parse(json)?.let(roots::add)
                    cursor = end
                } else {
                    cursor = nextLine(text, start)
                }
            }
            if (text.getOrNull(cursor) == '\n') cursor++
        }
    }

    private fun readJsonContainers(text: String, roots: MutableList<Any>) {
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOfAny(charArrayOf('{', '['), cursor)
            if (start < 0) return
            val end = containerEnd(text, start) ?: return
            val root = parse(text.substring(start, end))
            if (root == null) cursor = start + 1 else {
                roots += root
                cursor = end
            }
        }
    }

    private fun containerEnd(text: String, start: Int): Int? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return index + 1
            }
        }
        return null
    }

    private fun skipUtf8(text: String, start: Int, bytes: Int): Int {
        require(bytes >= 0) { "NTK Flight text length is negative" }
        var consumed = 0
        var cursor = start
        while (consumed < bytes && cursor < text.length) {
            val point = Character.codePointAt(text, cursor)
            consumed += when {
                point <= 0x7f -> 1
                point <= 0x7ff -> 2
                point <= 0xffff -> 3
                else -> 4
            }
            cursor += Character.charCount(point)
        }
        require(consumed == bytes) { "NTK Flight text length is incomplete or splits a character" }
        return cursor
    }

    private fun parse(text: String): Any? = runCatching {
        JSONTokener(text).nextValue().takeIf { it is JSONObject || it is JSONArray }
    }.getOrNull()

    private fun nextLine(text: String, start: Int): Int =
        text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }

    private fun hasViewerFields(text: String): Boolean =
        text.contains("\"imagesToken\"") || text.contains("\"imageApiPath\"") ||
            (text.contains("\"token\"") &&
                listOf("episodeId", "sourceEpisodeId", "e").any { text.contains("\"$it\"") })

    private const val FLIGHT_PUSH = "self.__next_f.push("
}
