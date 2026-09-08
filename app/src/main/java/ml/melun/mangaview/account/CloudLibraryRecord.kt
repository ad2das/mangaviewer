package ml.melun.mangaview.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class CloudLibraryRecord(
    val kind: String,
    val key: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val payload: JsonObject,
) {
    val identity: String get() = "$kind:$key"
}

/** Stable explicit JSON names keep release obfuscation out of the stored account format. */
internal object CloudLibraryRecords {
    const val FIELD = "viewerLibraryV2Json"

    fun encode(records: Collection<CloudLibraryRecord>): String = JsonObject().apply {
        addProperty("version", 1)
        add("records", JsonArray().apply {
            records.sortedBy { it.identity }.forEach { record -> add(JsonObject().apply {
                addProperty("kind", record.kind); addProperty("key", record.key)
                addProperty("updatedAt", record.updatedAt); addProperty("deleted", record.deleted)
                add("payload", record.payload)
            }) }
        })
    }.toString()

    fun decode(text: String): List<CloudLibraryRecord> {
        require(text.length <= 1_048_576) { "Account library exceeds its supported size" }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("version").asInt == 1) { "Unsupported account library version" }
        val rows = root.getAsJsonArray("records")
        require(rows.size() <= 20_000)
        return rows.map { element ->
            val row = element.asJsonObject
            CloudLibraryRecord(row.get("kind").asString, row.get("key").asString,
                row.get("updatedAt").asLong, row.get("deleted").asBoolean, row.getAsJsonObject("payload"))
                .also(CloudLibraryCodec::validate)
        }.also { require(it.map(CloudLibraryRecord::identity).distinct().size == it.size) }
    }

    /** Deterministic per-record conflict resolution preserves independent device edits. */
    fun merge(vararg versions: Collection<CloudLibraryRecord>): List<CloudLibraryRecord> =
        versions.flatMap { it }.groupBy { it.identity }.values.map { candidates ->
            candidates.maxWith(compareBy<CloudLibraryRecord> { it.updatedAt }
                .thenBy { it.deleted }.thenBy { it.payload.toString() })
        }.sortedBy { it.identity }

    fun localChanges(
        previous: Collection<CloudLibraryRecord>,
        current: Collection<CloudLibraryRecord>,
        now: Long,
    ): List<CloudLibraryRecord> {
        val prior = previous.associateBy { it.identity }
        val present = current.associateBy { it.identity }
        val changed = current.map { item ->
            val old = prior[item.identity]
            if (old != null && (old.deleted || old.payload != item.payload)) {
                item.copy(updatedAt = maxOf(item.updatedAt, Math.addExact(old.updatedAt, 1)))
            } else if (old != null) item.copy(updatedAt =
                if (item.kind == "favorite") old.updatedAt else maxOf(old.updatedAt, item.updatedAt)) else item
        }
        val removed = previous.filter { it.identity !in present }.map { old ->
            if (old.deleted) old else old.copy(updatedAt = maxOf(now, Math.addExact(old.updatedAt, 1)), deleted = true)
        }
        return merge(changed, removed)
    }

    fun mergeAfterDownload(remote: List<CloudLibraryRecord>, before: List<CloudLibraryRecord>,
        current: List<CloudLibraryRecord>, now: Long): List<CloudLibraryRecord> {
        val baseline = before.associateBy { it.identity }
        val downloaded = remote.associateBy { it.identity }
        // The exchange already merged the uploaded local baseline with the cloud. Replay only
        // edits made during that exchange; raw recency timestamps are not favorite revisions.
        val edits = localChanges(before, current, now).filter { it != baseline[it.identity] }.map { item ->
            val cloud = downloaded[item.identity]
            if (item != baseline[item.identity] && cloud != null) {
                item.copy(updatedAt = maxOf(item.updatedAt, Math.addExact(cloud.updatedAt, 1)))
            } else item
        }
        return merge(edits, remote)
    }
}
