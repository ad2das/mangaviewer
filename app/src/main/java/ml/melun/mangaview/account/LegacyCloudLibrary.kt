package ml.melun.mangaview.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import ml.melun.mangaview.data.db.BookmarkEntity
import ml.melun.mangaview.data.db.LibraryEntryEntity
import ml.melun.mangaview.data.db.ReadingProgressEntity
import ml.melun.mangaview.source.SourceEpisode

internal data class LegacyCloudImport(val records: List<CloudLibraryRecord>, val unresolvedRecords: Int,
    val unresolvedSeries: Set<String> = emptySet())

/** Reads the original users/{uid}/mangaView/state fields without rewriting or removing them. */
internal object LegacyCloudLibrary {
    fun decode(fields: Map<String, Any?>, catalogs: Map<String, List<SourceEpisode>> = emptyMap()): LegacyCloudImport {
        val titles = linkedMapOf<String, Title>()
        var unresolved = 0
        val pendingSeries = mutableSetOf<String>()
        val selected = objectField(fields, "bookmarkJson")
        val pages = objectField(fields, "pageBookmarkJson")
        val at = (fields["updatedAt"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L
        for ((field, favorite) in listOf("recentJson" to false, "favoriteJson" to true)) {
            val text = fields[field] as? String ?: continue
            val scopeAt = (fields[if (favorite) "favoriteUpdatedAt" else "recentUpdatedAt"] as? Number)?.toLong() ?: at
            for ((index, item) in JsonParser.parseString(text).asJsonArray.withIndex()) {
                val title = title(item.asJsonObject, favorite, selected, pages)?.copy(savedAt = (scopeAt - index).coerceAtLeast(0))
                if (title == null) { unresolved++; continue }
                val old = titles[title.key]
                titles[title.key] = if (old == null) title else old.copy(favorite = old.favorite || favorite,
                    lastEpisodeId = old.lastEpisodeId ?: title.lastEpisodeId,
                    resume = old.resume.ifBlank { title.resume })
            }
        }
        val records = buildList {
            titles.values.forEach { title ->
                val entry = LibraryEntryEntity(title.source, title.series, title.name,
                    title.thumbnail, title.favorite, title.savedAt)
                add(CloudLibraryCodec.series(entry))
                if (title.favorite) add(CloudLibraryCodec.favorite(entry.copy(updatedAtEpochMillis =
                    (fields["favoriteUpdatedAt"] as? Number)?.toLong()?.coerceAtLeast(0) ?: at)))
                val uniqueLegacy = titles.values.count { it.legacyKey == title.legacyKey } == 1
                val episodeId = selected.number(title.key) ?: (if (uniqueLegacy) selected.number(title.legacyKey) else null) ?: title.lastEpisodeId
                if (episodeId != null && episodeId >= 0) {
                    val episode = title.episode(episodeId, catalogs[title.series].orEmpty())
                    if (episode == null) { unresolved++; pendingSeries += title.series } else {
                        val prefix = pagePrefix(pages, title, episodeId, uniqueLegacy)
                        val page = pages.number(prefix)?.coerceAtLeast(0) ?: 0
                        val offset = Math.multiplyExact((pages.number("$prefix.offset") ?: 0).coerceAtLeast(0), 1024)
                        add(CloudLibraryCodec.progress(ReadingProgressEntity(title.source, title.series, episode,
                            pageKey(page), offset, at)))
                    }
                }
                pages.keySet().filter { (it.startsWith(title.key + ".") ||
                    (it.startsWith(title.legacyKey + ".") && titles.values.count { t -> t.legacyKey == title.legacyKey } == 1)) &&
                    !it.endsWith(".offset") && !it.endsWith(".side") }
                    .forEach page@ { key ->
                        val id = key.substringAfterLast('.').toLongOrNull() ?: return@page
                        val episode = title.episode(id, catalogs[title.series].orEmpty())
                        if (episode == null) { unresolved++; pendingSeries += title.series } else {
                            val page = pages.number(key)?.coerceAtLeast(0) ?: 0
                            val offset = Math.multiplyExact((pages.number("$key.offset") ?: 0).coerceAtLeast(0), 1024)
                            add(CloudLibraryCodec.bookmark(BookmarkEntity(title.source, title.series, episode,
                                pageKey(page), offset, at)))
                        }
                    }
            }
        }
        return LegacyCloudImport(CloudLibraryRecords.merge(records), unresolved, pendingSeries)
    }

    private fun title(p: JsonObject, favorite: Boolean, selected: JsonObject, pages: JsonObject): Title? {
        val id = p.number("id")?.takeIf { it > 0 } ?: return null
        val mode = p.number("baseMode") ?: 1
        val resume = p.text("resumeNtkEpisodePath")
        val source = source(p, resume, "$mode.$id", selected, pages) ?: return null
        val series = if (source == "ntk") {
            val prefix = if (mode == 2L) "/webtoon" else "/manhwa"
            val path = runCatching { URI(resume.ifBlank { p.text("path") }).path }.getOrNull().orEmpty()
            if (path.startsWith("$prefix/") && path.split('/').size >= 3) path.split('/').take(3).joinToString("/")
            else "$prefix/$id"
        }
            else "${if (mode == 2L) "webtoon" else "comic"}:$id"
        val name = p.text("name").takeIf { it.isNotBlank() } ?: return null
        return Title(source, series, "$mode.$id", name, p.text("thumb").takeIf { it.isNotBlank() },
            favorite, p.number("bookmarkEpisodeId")?.takeIf { it >= 0 }, resume)
    }

    private fun source(p: JsonObject, resume: String, key: String, selected: JsonObject, pages: JsonObject): String? {
        knownSource(p.text("sourceSite"))?.let { return it }
        val path = runCatching { URI(resume).path }.getOrNull().orEmpty()
        if (path.startsWith("/webtoon/") || path.startsWith("/manhwa/")) return "ntk"
        knownSource(p.text("thumb") + " " + p.text("path"))?.let { return it }
        return listOf("ntk", "wfwf").filter { source ->
            selected.has("$source.$key") || pages.keySet().any { page -> page.startsWith("$source.$key.") }
        }.singleOrNull()
    }

    private fun knownSource(value: String): String? {
        val text = value.lowercase()
        return when {
            listOf("ntk", "sbxh", "toonflix", "toki").any(text::contains) -> "ntk"
            listOf("wfwf", "wolf", "vcloud", "v12st", "ao9cloud").any(text::contains) -> "wfwf"
            else -> null
        }
    }

    private fun objectField(fields: Map<String, Any?>, key: String): JsonObject {
        val original = (fields[key] as? String)?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
        return original.deepCopy().apply {
            original.entrySet().forEach { (name, value) ->
                val source = knownSource(name.substringBefore('.'))
                if (source != null) {
                    val canonical = "$source.${name.substringAfter('.')}"
                    if (!has(canonical)) add(canonical, value)
                }
            }
        }
    }

    private fun pagePrefix(pages: JsonObject, title: Title, id: Long, uniqueLegacy: Boolean): String =
        (listOf("${title.key}.$id") + if (uniqueLegacy) listOf("${title.legacyKey}.$id", "${title.legacyKey.substringBefore('.')}.$id") else emptyList())
            .firstOrNull(pages::has) ?: "${title.key}.$id"

    private fun pageKey(index: Long): String = "p${index.toString().padStart(4, '0')}"
    private fun JsonObject.number(key: String): Long? = get(key)?.takeUnless { it.isJsonNull }?.asLong
    private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private data class Title(val source: String, val series: String, val legacyKey: String, val name: String,
        val thumbnail: String?, val favorite: Boolean, val lastEpisodeId: Long?, val resume: String, val savedAt: Long = 0) {
        val key: String get() = "$source.$legacyKey"
        fun episode(id: Long, catalog: List<SourceEpisode>): String? {
            if (source == "wfwf") return id.toString()
            if (id == lastEpisodeId && resume.isNotBlank()) {
                val path = runCatching { URI(resume).path }.getOrNull().orEmpty()
                if (path.startsWith("$series/")) return path
            }
            // Old numeric IDs were sometimes a display number, sometimes a provider ID.
            // Resolve against the real catalog and reject conflicting matches.
            return catalog.filter { it.id.remoteKey.substringAfterLast('/').toLongOrNull() == id ||
                it.sequenceNumber == id.toDouble() }.map { it.id.remoteKey }.distinct().singleOrNull()
        }
    }
}
