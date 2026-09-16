package ml.melun.mangaview.data.cache

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceSeries

/** Last home catalogs a provider delivered, so the home tab can paint before the network answers. */
data class HomeCatalogSnapshot(
    val popular: List<SourceSeries>,
    val latest: List<SourceSeries>,
    val new: List<SourceSeries>,
    val savedAtEpochMillis: Long,
)

/**
 * One file per (source, kind). Writes go through a staging file so a killed process can never
 * leave a half-written snapshot that would decode into the wrong cards.
 */
class HomeCatalogSnapshotStore(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun load(sourceId: SourceId, kind: SeriesKind): HomeCatalogSnapshot? =
        withContext(ioDispatcher) {
            mutex.withLock { runCatching { read(file(sourceId, kind)) }.getOrNull() }
        }

    suspend fun save(
        sourceId: SourceId,
        kind: SeriesKind,
        popular: List<SourceSeries>,
        latest: List<SourceSeries>,
        new: List<SourceSeries>,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureRoot()
            val destination = file(sourceId, kind)
            val stage = File(root, "${destination.name}.tmp")
            stage.delete()
            try {
                write(stage, HomeCatalogSnapshot(popular, latest, new, clock()))
                if (destination.exists() && !destination.delete()) {
                    error("Cannot replace the home snapshot")
                }
                require(stage.renameTo(destination)) { "Cannot publish the home snapshot" }
            } finally {
                stage.delete()
            }
        }
    }

    private fun file(sourceId: SourceId, kind: SeriesKind): File =
        File(root, "${sourceId.value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}" +
            "-${kind.name.lowercase()}.bin")

    private fun ensureRoot() {
        require(root.isDirectory || root.mkdirs()) { "Home snapshot storage is unavailable" }
    }

    private fun read(file: File): HomeCatalogSnapshot? {
        if (!file.isFile) return null
        return DataInputStream(FileInputStream(file).buffered()).use { data ->
            if (data.readInt() != MAGIC) return null
            val version = data.readInt()
            if (version != VERSION) return null
            val savedAt = data.readLong()
            HomeCatalogSnapshot(
                popular = readSeriesList(data),
                latest = readSeriesList(data),
                new = readSeriesList(data),
                savedAtEpochMillis = savedAt,
            )
        }
    }

    private fun write(file: File, snapshot: HomeCatalogSnapshot) {
        FileOutputStream(file).use { output ->
            DataOutputStream(BufferedOutputStream(output)).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeLong(snapshot.savedAtEpochMillis)
                writeSeriesList(data, snapshot.popular)
                writeSeriesList(data, snapshot.latest)
                writeSeriesList(data, snapshot.new)
                data.flush()
                output.fd.sync()
            }
        }
    }

    private fun writeSeriesList(data: DataOutputStream, items: List<SourceSeries>) {
        data.writeInt(items.size)
        items.forEach { item ->
            data.writeUTF(item.id.sourceId.value)
            data.writeUTF(item.id.remoteKey)
            data.writeUTF(item.title)
            data.writeNullable(item.subtitle)
            data.writeNullable(item.thumbnailKey)
            data.writeInt(item.status?.ordinal ?: -1)
        }
    }

    private fun readSeriesList(data: DataInputStream): List<SourceSeries> {
        val count = data.readInt()
        require(count in 0..MAX_SERIES_PER_ROW) { "Home snapshot row size is invalid" }
        return List(count) {
            val sourceId = SourceId(data.readUTF())
            val remoteKey = data.readUTF()
            val title = data.readUTF()
            val subtitle = data.readNullable()
            val thumbnailKey = data.readNullable()
            val statusOrdinal = data.readInt()
            val status = statusOrdinal.takeIf { it >= 0 }?.let { ordinal ->
                SeriesStatus.entries.getOrNull(ordinal)
            }
            SourceSeries(SeriesId(sourceId, remoteKey), title, subtitle, thumbnailKey, status)
        }
    }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private companion object {
        const val MAGIC = 0x4D56484D
        const val VERSION = 1
        const val MAX_SERIES_PER_ROW = 5_000
    }
}
