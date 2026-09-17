package ml.melun.mangaview.data.cache

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.source.SeriesStatus
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeriesDetails

data class EpisodeCatalogSnapshot(
    val seriesId: SeriesId,
    val episodes: List<SourceEpisode>,
    val details: SourceSeriesDetails?,
    val savedAtEpochMillis: Long,
)

/** Complete episode lists only. Bounded, atomic snapshots survive activity and process restarts. */
class EpisodeCatalogSnapshotStore(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val publisher: AtomicFilePublisher = PosixAtomicFilePublisher(),
) {
    private val mutex = Mutex()

    suspend fun load(seriesId: SeriesId): EpisodeCatalogSnapshot? = withContext(ioDispatcher) {
        mutex.withLock {
            val file = file(seriesId)
            if (!file.isFile || file.length() > MAX_FILE_BYTES) return@withLock null
            // No suspend points inside decoding: malformed or interrupted files are cache misses.
            runCatching { read(file, seriesId) }.getOrNull()
        }
    }

    suspend fun save(snapshot: EpisodeCatalogSnapshot) = withContext(ioDispatcher) {
        require(snapshot.episodes.size <= MAX_EPISODES)
        require(snapshot.episodes.all { it.id.seriesId == snapshot.seriesId })
        require(snapshot.episodes.map { it.id }.distinct().size == snapshot.episodes.size)
        mutex.withLock {
            check(root.isDirectory || root.mkdirs()) { "Episode snapshot storage is unavailable" }
            val destination = file(snapshot.seriesId)
            val stage = File(root, "${destination.name}.tmp")
            try {
                write(stage, snapshot)
                check(stage.length() <= MAX_FILE_BYTES) { "Episode snapshot is too large" }
                publisher.publish(stage, destination)
                prune(destination)
            } finally {
                stage.delete()
            }
        }
    }

    private fun file(seriesId: SeriesId): File {
        val key = "${seriesId.sourceId.value.length}:${seriesId.sourceId.value}${seriesId.remoteKey}"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "$hash.bin")
    }

    private fun write(file: File, snapshot: EpisodeCatalogSnapshot) {
        FileOutputStream(file).use { output ->
            val data = DataOutputStream(output.buffered())
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeUTF(snapshot.seriesId.sourceId.value)
            data.writeUTF(snapshot.seriesId.remoteKey)
            data.writeLong(snapshot.savedAtEpochMillis)
            data.writeBoolean(snapshot.details != null)
            snapshot.details?.let {
                data.writeInt(it.status?.ordinal ?: -1)
                data.writeNullable(it.description)
                data.writeNullable(it.authors)
            }
            data.writeInt(snapshot.episodes.size)
            for (episode in snapshot.episodes) {
                data.writeUTF(episode.id.remoteKey)
                data.writeUTF(episode.title)
                data.writeLong(episode.publishedAtEpochMillis ?: Long.MIN_VALUE)
                data.writeInt(episode.pageCountHint ?: -1)
                data.writeDouble(episode.sequenceNumber ?: Double.NaN)
            }
            data.flush()
            output.fd.sync()
        }
    }

    private fun read(file: File, seriesId: SeriesId): EpisodeCatalogSnapshot? =
        DataInputStream(file.inputStream().buffered()).use { data ->
            if (data.readInt() != MAGIC || data.readInt() != VERSION) return null
            if (data.readUTF() != seriesId.sourceId.value || data.readUTF() != seriesId.remoteKey) return null
            val savedAt = data.readLong()
            val details = if (data.readBoolean()) SourceSeriesDetails(
                status = data.readInt().let { if (it < 0) null else SeriesStatus.entries[it] },
                description = data.readNullable(), authors = data.readNullable(),
            ) else null
            val count = data.readInt()
            require(count in 0..MAX_EPISODES)
            val episodes = List(count) {
                SourceEpisode(
                    EpisodeId(seriesId, data.readUTF()), data.readUTF(),
                    publishedAtEpochMillis = data.readLong().takeUnless { it == Long.MIN_VALUE },
                    pageCountHint = data.readInt().takeUnless { it == -1 },
                    sequenceNumber = data.readDouble().takeUnless(Double::isNaN),
                )
            }
            require(episodes.map { it.id }.distinct().size == episodes.size && data.read() == -1)
            EpisodeCatalogSnapshot(seriesId, episodes, details, savedAt)
        }

    private fun prune(keep: File) {
        val files = root.listFiles { file -> file.extension == "bin" }?.toList().orEmpty()
        var bytes = files.sumOf(File::length)
        var count = files.size
        for (file in files.filterNot { it == keep }.sortedBy(File::lastModified)) {
            if (count <= MAX_FILES && bytes <= MAX_TOTAL_BYTES) break
            val size = file.length()
            if (file.delete()) { count--; bytes -= size }
        }
    }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private companion object {
        const val MAGIC = 0x4D564543
        const val VERSION = 1
        const val MAX_EPISODES = 25_000
        const val MAX_FILES = 32
        const val MAX_FILE_BYTES = 4 * 1024 * 1024L
        const val MAX_TOTAL_BYTES = 16 * 1024 * 1024L
    }
}
