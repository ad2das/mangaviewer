package ml.melun.mangaview.data.engine

import java.io.*
import java.net.URI
import java.security.MessageDigest
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*

/** Checksummed, bounded metadata; original bytes remain solely in EngineRawStorage. */
internal object EngineEpisodePlanCodec {
    fun write(file: File, plan: EpisodeAccessPlan) {
        require(!plan.localOnly && plan.prerequisites.isEmpty())
        val payload = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            val manifest = plan.manifest
            out.writeUTF(manifest.id.seriesId.sourceId.value)
            out.writeUTF(manifest.id.seriesId.remoteKey)
            out.writeUTF(manifest.id.remoteKey)
            out.writeUTF(manifest.title)
            out.optional(manifest.previousEpisodeId?.remoteKey)
            out.optional(manifest.nextEpisodeId?.remoteKey)
            out.optional(manifest.revision)
            out.writeUTF(plan.contentRevision)
            out.writeUTF(plan.documentSha256)
            out.writeUTF(plan.finalDocumentUrl.toString())
            out.writeLong(plan.authEpoch)
            out.writeBoolean(plan.navigationKnown)
            require(manifest.pages.size in 1..MAX_PAGES)
            out.writeInt(manifest.pages.size)
            manifest.pages.forEachIndexed { index, page ->
                out.writeUTF(page.id.remoteKey)
                out.writeBoolean(page.dimensions != null)
                page.dimensions?.let { out.writeInt(it.widthPx); out.writeInt(it.heightPx) }
                out.writeBoolean(page.encodedLength != null)
                page.encodedLength?.let(out::writeLong)
                out.optional(page.fingerprint)
                val access = plan.pages[index]
                out.writeUTF(access.sourceRecord)
                require(access.candidates.size in 1..MAX_CANDIDATES)
                out.writeInt(access.candidates.size)
                access.candidates.forEach { out.writeUTF(it.toString()) }
            }
        } }.toByteArray()
        require(payload.size in 1..MAX_BYTES)
        file.outputStream().use { stream ->
            val out = DataOutputStream(stream)
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(payload.size)
            out.write(MessageDigest.getInstance("SHA-256").digest(payload))
            out.write(payload)
            out.flush()
            stream.fd.sync()
        }
    }

    fun read(file: File): EpisodeAccessPlan = DataInputStream(file.inputStream().buffered()).use { input ->
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val size = input.readInt()
        require(size in 1..MAX_BYTES)
        val hash = ByteArray(32).also(input::readFully)
        val payload = ByteArray(size).also(input::readFully)
        require(input.read() == -1)
        require(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(payload)))
        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val series = SeriesId(SourceId(data.readUTF()), data.readUTF())
            val episode = EpisodeId(series, data.readUTF())
            val title = data.readUTF()
            val previous = data.optional()?.let { EpisodeId(series, it) }
            val next = data.optional()?.let { EpisodeId(series, it) }
            val revision = data.optional()
            val content = data.readUTF()
            val document = data.readUTF()
            val url = URI(data.readUTF())
            val epoch = data.readLong()
            val navigation = data.readBoolean()
            val count = data.readInt().also { require(it in 1..MAX_PAGES) }
            val accesses = ArrayList<PageAccessPlan>(count)
            val pages = List(count) { ordinal ->
                val id = PageId(episode, data.readUTF())
                val dimensions = if (data.readBoolean()) PageDimensions(data.readInt(), data.readInt()) else null
                val length = if (data.readBoolean()) data.readLong() else null
                val fingerprint = data.optional()
                val record = data.readUTF()
                val alternatives = data.readInt().also { require(it in 1..MAX_CANDIDATES) }
                accesses += PageAccessPlan(id, record, List(alternatives) { URI(data.readUTF()) })
                PageSpec(id, ordinal, dimensions, length, fingerprint)
            }
            require(data.read() == -1)
            EpisodeAccessPlan(EpisodeManifest(episode, title, pages, previous, next, revision), content,
                document, url, epoch, accesses, navigationKnown = navigation, localOnly = true)
        }
    }

    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); value?.let(::writeUTF) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null
    private const val MAGIC = 0x4d564543
    private const val VERSION = 1
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_PAGES = 10_000
    private const val MAX_CANDIDATES = 64
}
