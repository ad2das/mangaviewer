package ml.melun.mangaview.data.cache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId

data class SnapshotPageBinding(
    val pageId: PageId,
    val byteCount: Long,
    val sha256: String,
    val dimensions: PageDimensions,
) {
    init {
        require(byteCount > 0L)
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

data class CompleteEpisodeSnapshot(val manifest: EpisodeManifest, val pages: List<SnapshotPageBinding>) {
    init {
        require(manifest.pages.map { it.id } == pages.map { it.pageId }) {
            "Snapshot byte identities must follow the exact validated manifest order"
        }
        manifest.pages.zip(pages).forEach { (spec, binding) ->
            require(spec.dimensions == null || spec.dimensions == binding.dimensions)
            require(spec.encodedLength == null || spec.encodedLength == binding.byteCount)
            require(spec.fingerprint == null || spec.fingerprint == binding.sha256)
        }
    }
}

internal object CompleteEpisodeSnapshotCodec {
    fun write(file: File, snapshot: CompleteEpisodeSnapshot) {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { writePayload(it, snapshot) }
        }.toByteArray()
        require(bytes.size <= MAX_BYTES)
        file.outputStream().use { output ->
            DataOutputStream(output).apply {
                writeInt(MAGIC)
                writeInt(VERSION)
                writeInt(bytes.size)
                write(MessageDigest.getInstance("SHA-256").digest(bytes))
                write(bytes)
                flush()
            }
            output.fd.sync()
        }
    }

    fun read(file: File): CompleteEpisodeSnapshot = DataInputStream(file.inputStream().buffered()).use { input ->
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val size = input.readInt()
        require(size in 1..MAX_BYTES)
        val expected = ByteArray(32).also(input::readFully)
        val payload = ByteArray(size).also(input::readFully)
        require(input.read() == -1)
        require(MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(payload)))
        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val result = readPayload(data)
            require(data.read() == -1)
            result
        }
    }

    private fun writePayload(data: DataOutputStream, snapshot: CompleteEpisodeSnapshot) {
        val manifest = snapshot.manifest
        require(manifest.pages.size in 1..MAX_PAGES)
        data.writeUTF(manifest.id.seriesId.sourceId.value)
        data.writeUTF(manifest.id.seriesId.remoteKey)
        data.writeUTF(manifest.id.remoteKey)
        data.writeUTF(manifest.title)
        data.optional(manifest.previousEpisodeId?.remoteKey)
        data.optional(manifest.nextEpisodeId?.remoteKey)
        data.optional(manifest.revision)
        data.writeInt(manifest.pages.size)
        manifest.pages.forEachIndexed { index, page ->
            data.writeUTF(page.id.remoteKey)
            data.dimensions(page.dimensions)
            data.writeBoolean(page.encodedLength != null)
            page.encodedLength?.let(data::writeLong)
            data.optional(page.fingerprint)
            val binding = snapshot.pages[index]
            data.writeLong(binding.byteCount)
            data.writeUTF(binding.sha256)
            data.dimensions(binding.dimensions)
        }
    }

    private fun readPayload(data: DataInputStream): CompleteEpisodeSnapshot {
        val series = SeriesId(SourceId(data.readUTF()), data.readUTF())
        val id = EpisodeId(series, data.readUTF())
        val title = data.readUTF()
        val previous = data.optional()?.let { EpisodeId(series, it) }
        val next = data.optional()?.let { EpisodeId(series, it) }
        val revision = data.optional()
        val count = data.readInt()
        require(count in 1..MAX_PAGES)
        val bindings = mutableListOf<SnapshotPageBinding>()
        val pages = List(count) { ordinal ->
            val pageId = PageId(id, data.readUTF())
            val page = PageSpec(pageId, ordinal, data.dimensions(),
                if (data.readBoolean()) data.readLong() else null, data.optional())
            bindings += SnapshotPageBinding(pageId, data.readLong(), data.readUTF(),
                requireNotNull(data.dimensions()))
            page
        }
        return CompleteEpisodeSnapshot(EpisodeManifest(id, title, pages, previous, next, revision), bindings)
    }

    private fun DataOutputStream.optional(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeUTF)
    }

    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.dimensions(value: PageDimensions?) {
        writeBoolean(value != null)
        value?.let { writeInt(it.widthPx); writeInt(it.heightPx) }
    }

    private fun DataInputStream.dimensions(): PageDimensions? =
        if (readBoolean()) PageDimensions(readInt(), readInt()) else null

    private const val MAGIC = 0x4d565253
    private const val VERSION = 1
    private const val MAX_BYTES = 4 * 1_024 * 1_024
    private const val MAX_PAGES = 10_000
}
