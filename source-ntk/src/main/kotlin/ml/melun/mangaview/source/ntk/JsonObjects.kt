package ml.melun.mangaview.source.ntk

import org.json.JSONArray
import org.json.JSONObject

internal object JsonObjects {
    fun embedded(text: String): List<JSONObject> {
        val normalized = normalizeEscapes(text)
        val results = mutableListOf<JSONObject>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        normalized.forEachIndexed { index, character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = index
                        depth += 1
                    }
                    '}' -> {
                        depth -= 1
                        if (depth == 0 && start >= 0) {
                            runCatching { JSONObject(normalized.substring(start, index + 1)) }
                                .getOrNull()?.let(results::add)
                            start = -1
                        }
                        if (depth < 0) depth = 0
                    }
                }
            }
        }
        return results
    }

    fun walk(root: Any?, visit: (JSONObject) -> Unit) {
        when (root) {
            is JSONObject -> {
                visit(root)
                root.keys().forEachRemaining { key -> walk(root.opt(key), visit) }
            }
            is JSONArray -> (0 until root.length()).forEach { index -> walk(root.opt(index), visit) }
        }
    }

    fun strings(root: Any?): Sequence<String> = sequence {
        when (root) {
            is String -> yield(root)
            is JSONObject -> {
                val keys = root.keys()
                while (keys.hasNext()) yieldAll(strings(root.opt(keys.next())))
            }
            is JSONArray -> {
                for (index in 0 until root.length()) yieldAll(strings(root.opt(index)))
            }
        }
    }

    fun normalizeEscapes(text: String): String {
        var slash = text.indexOf('\\')
        if (slash < 0) return text
        return buildString(text.length) {
            var copied = 0
            while (slash >= 0) {
                val escape = ESCAPES.firstOrNull { (encoded, _) ->
                    text.regionMatches(slash, encoded, 0, encoded.length, ignoreCase = true)
                }
                val next = if (escape == null) slash + 1 else {
                    append(text, copied, slash)
                    append(escape.second)
                    copied = slash + escape.first.length
                    copied
                }
                slash = text.indexOf('\\', next)
            }
            append(text, copied, text.length)
        }
    }

    private val ESCAPES = listOf(
        "\\u003c" to "<", "\\u003e" to ">", "\\u0026" to "&", "\\/" to "/", "\\\"" to "\"",
    )
}
