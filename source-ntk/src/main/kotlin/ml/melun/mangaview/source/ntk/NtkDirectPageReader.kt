package ml.melun.mangaview.source.ntk

import org.json.JSONArray
import org.json.JSONObject

/** Page entries own their alternatives; arbitrary nested strings never create extra pages. */
internal object NtkDirectPageReader {
    fun read(owner: JSONObject, resolve: (String) -> String?): List<NtkPageRequest> {
        val representations = listOf("images", "imageMetas", "pages").mapNotNull { key ->
            owner.optJSONArray(key)?.let { readArray(it, resolve) }?.takeIf(List<NtkPageRequest>::isNotEmpty)
        }
        val selected = representations.firstOrNull() ?: return emptyList()
        require(representations.all { it == selected }) { "NTK document contains conflicting page sequences" }
        return selected
    }

    private fun readArray(array: JSONArray, resolve: (String) -> String?): List<NtkPageRequest> {
        val pages = sortedMapOf<Int, NtkPageRequest>()
        var numbered: Boolean? = null
        for (index in 0 until array.length()) {
            val entry = array.opt(index)
            val objectEntry = entry as? JSONObject
            val hasNumber = objectEntry?.has("page") == true
            require(numbered == null || numbered == hasNumber) { "NTK document mixes numbered and positional pages" }
            numbered = hasNumber
            val page = if (hasNumber) requireNotNull(objectEntry).optInt("page", 0) else index + 1
            require(page > 0) { "NTK document page number is invalid" }
            val urls = when (entry) {
                is String -> listOfNotNull(resolve(entry))
                is JSONObject -> candidates(entry).mapNotNull(resolve).distinct()
                else -> emptyList()
            }
            // Metadata-only or incomplete direct representations must use the image API.
            if (urls.isEmpty()) return emptyList()
            require(pages.put(page, NtkPageRequest(urls.first(), urls.drop(1))) == null) {
                "NTK document contains duplicate page $page"
            }
        }
        if (pages.keys.toList() != (1..array.length()).toList()) return emptyList()
        return pages.values.toList()
    }

    private fun candidates(entry: JSONObject): List<String> = buildList {
        listOf("src", "url").forEach { key ->
            (entry.opt(key) as? String)?.takeIf(String::isNotBlank)?.let(::add)
        }
        listOf("srcCandidates", "alternateUrls").forEach { key ->
            val array = entry.optJSONArray(key) ?: return@forEach
            for (index in 0 until array.length()) (array.opt(index) as? String)?.let(::add)
        }
    }
}
