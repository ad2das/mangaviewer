package ml.melun.mangaview.account

import com.google.gson.JsonObject
import ml.melun.mangaview.data.db.*

internal object CloudLibraryCodec {
    fun snapshot(value: CloudLibrarySnapshot): List<CloudLibraryRecord> = buildList {
        value.series.forEach { item ->
            add(series(item))
            if (item.favorite) add(favorite(item))
        }
        val reading = value.readingAnchors.associateBy { listOf(it.sourceKey, it.seriesKey) }
        value.progress.forEach { item ->
            val anchor = reading[listOf(item.sourceKey, item.seriesKey)]?.takeIf {
                it.episodeKey == item.episodeKey && it.pageKey == item.pageKey &&
                    it.legacyScreenOffsetUnits == item.offsetInPageUnits && it.updatedAtEpochMillis == item.updatedAtEpochMillis
            }
            add(progress(item, anchor))
        }
        val bookmarks = value.bookmarkAnchors.associateBy { listOf(it.sourceKey, it.seriesKey, it.episodeKey, it.pageKey) }
        value.bookmarks.forEach { item ->
            val anchor = bookmarks[listOf(item.sourceKey, item.seriesKey, item.episodeKey, item.pageKey)]?.takeIf {
                it.legacyScreenOffsetUnits == item.offsetInPageUnits && it.createdAtEpochMillis == item.createdAtEpochMillis
            }
            add(bookmark(item, anchor))
        }
    }.sortedBy { it.identity }

    fun series(item: LibraryEntryEntity): CloudLibraryRecord = record("series", item.updatedAtEpochMillis,
        base(item.sourceKey, item.seriesKey).apply {
            addProperty("title", item.title); addProperty("thumbnail", item.thumbnailKey)
            addProperty("favorite", false)
        })

    fun favorite(item: LibraryEntryEntity): CloudLibraryRecord = record("favorite", item.updatedAtEpochMillis,
        base(item.sourceKey, item.seriesKey).apply { addProperty("favorite", true) })

    fun progress(item: ReadingProgressEntity, anchor: EngineReadingAnchorEntity? = null): CloudLibraryRecord =
        record("progress", item.updatedAtEpochMillis,
            position(item.sourceKey, item.seriesKey, item.episodeKey, item.pageKey, item.offsetInPageUnits).apply {
                if (anchor != null) native(anchor.sourceYQ32, anchor.viewportOffsetUnits)
            })

    fun bookmark(item: BookmarkEntity, anchor: EngineBookmarkAnchorEntity? = null): CloudLibraryRecord =
        record("bookmark", item.createdAtEpochMillis,
            position(item.sourceKey, item.seriesKey, item.episodeKey, item.pageKey, item.offsetInPageUnits).apply {
                if (anchor != null) native(anchor.sourceYQ32, anchor.viewportOffsetUnits)
            })

    fun series(record: CloudLibraryRecord): LibraryEntryEntity = record.payload.let { p ->
        LibraryEntryEntity(p.text("source"), p.text("series"), p.text("title"),
            p.get("thumbnail")?.takeUnless { it.isJsonNull }?.asString, p.get("favorite").asBoolean, record.updatedAt)
    }

    fun progress(record: CloudLibraryRecord): ReadingProgressEntity = record.payload.let { p ->
        ReadingProgressEntity(p.text("source"), p.text("series"), p.text("episode"), p.text("page"),
            p.get("offset").asLong, record.updatedAt)
    }

    fun bookmark(record: CloudLibraryRecord): BookmarkEntity = record.payload.let { p ->
        BookmarkEntity(p.text("source"), p.text("series"), p.text("episode"), p.text("page"),
            p.get("offset").asLong, record.updatedAt)
    }

    fun readingAnchor(record: CloudLibraryRecord): EngineReadingAnchorEntity? = record.payload.let { p ->
        if (!p.has("sourceYQ32")) return null
        EngineReadingAnchorEntity(p.text("source"), p.text("series"), p.text("episode"), p.text("page"),
            p.get("sourceYQ32").asLong, p.get("viewportOffset").asLong, p.get("offset").asLong, record.updatedAt)
    }

    fun bookmarkAnchor(record: CloudLibraryRecord): EngineBookmarkAnchorEntity? = record.payload.let { p ->
        if (!p.has("sourceYQ32")) return null
        EngineBookmarkAnchorEntity(p.text("source"), p.text("series"), p.text("episode"), p.text("page"),
            p.get("sourceYQ32").asLong, p.get("viewportOffset").asLong, p.get("offset").asLong, record.updatedAt)
    }

    fun validate(record: CloudLibraryRecord) {
        require(record.kind in setOf("series", "favorite", "progress", "bookmark") && record.updatedAt >= 0)
        val p = record.payload
        require(p.text("source") in setOf("ntk", "wfwf"))
        require(p.text("series").isNotBlank() && p.text("series").length <= 2048)
        require(record.key == key(record.kind, p))
        if (record.kind == "series") {
            require(p.text("title").isNotBlank() && p.text("title").length <= 4096)
            require(p.get("favorite").asJsonPrimitive.isBoolean)
        } else if (record.kind == "favorite") {
            require(p.get("favorite").asJsonPrimitive.isBoolean && p.get("favorite").asBoolean)
        } else {
            require(p.text("episode").isNotBlank() && p.text("page").isNotBlank())
            require(p.get("offset").asLong >= 0)
            if (p.has("sourceYQ32")) { require(p.get("sourceYQ32").asLong >= 0); p.get("viewportOffset").asLong }
        }
    }

    private fun record(kind: String, at: Long, payload: JsonObject) =
        CloudLibraryRecord(kind, key(kind, payload), at, false, payload).also(::validate)

    private fun key(kind: String, p: JsonObject): String {
        val fields = mutableListOf(p.text("source"), p.text("series"))
        if (kind == "bookmark") fields += listOf(p.text("episode"), p.text("page"))
        return fields.joinToString("") { "${it.length}:$it" }
    }

    private fun base(source: String, series: String) = JsonObject().apply {
        addProperty("source", source); addProperty("series", series)
    }

    private fun position(source: String, series: String, episode: String, page: String, offset: Long) =
        base(source, series).apply {
            addProperty("episode", episode); addProperty("page", page); addProperty("offset", offset)
        }

    private fun JsonObject.native(y: Long, viewport: Long) {
        addProperty("sourceYQ32", y); addProperty("viewportOffset", viewport)
    }

    private fun JsonObject.text(key: String): String = get(key).asString
}
