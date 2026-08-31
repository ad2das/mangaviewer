package ml.melun.mangaview.data.offline

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries

internal object OfflineEpisodeCodec {
    fun write(file: File, episode: StoredEpisode) {
        FileOutputStream(file).use { output ->
            DataOutputStream(BufferedOutputStream(output)).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                writeSeries(data, episode.summary.series)
                writeEpisode(data, episode.summary.episode)
                data.writeLong(episode.summary.savedAtEpochMillis)
                writeAdjacent(data, episode.manifest)
                data.writeInt(episode.manifest.pages.size)
                episode.manifest.pages.forEach { page -> writePage(data, page, episode.pages.getValue(page.id)) }
                data.flush()
                output.fd.sync()
            }
        }
    }

    fun read(directory: File): StoredEpisode {
        val file = File(directory, MANIFEST_NAME)
        return DataInputStream(FileInputStream(file).buffered()).use { data ->
            require(data.readInt() == MAGIC) { "Offline manifest has an invalid signature" }
            require(data.readInt() == VERSION) { "Offline manifest version is unsupported" }
            val series = readSeries(data)
            val episode = readEpisode(data, series)
            val savedAt = data.readLong()
            val previous = readOptionalEpisode(data, series.id)
            val next = readOptionalEpisode(data, series.id)
            val pages = readPages(data, directory, episode.id)
            val manifest = EpisodeManifest(episode.id, episode.title, pages.map(StoredPage::spec), previous, next)
            StoredEpisode(
                DownloadedEpisode(series, episode, pages.size, pages.sumOf { it.cached.byteCount }, savedAt),
                manifest,
                pages.associate { it.spec.id to it.cached },
            )
        }
    }

    private fun writeSeries(data: DataOutputStream, series: SourceSeries) {
        data.writeUTF(series.id.sourceId.value)
        data.writeUTF(series.id.remoteKey)
        data.writeUTF(series.title)
        data.writeNullable(series.subtitle)
        data.writeNullable(series.thumbnailKey)
    }

    private fun readSeries(data: DataInputStream): SourceSeries = SourceSeries(
        SeriesId(SourceId(data.readUTF()), data.readUTF()),
        data.readUTF(),
        data.readNullable(),
        data.readNullable(),
    )

    private fun writeEpisode(data: DataOutputStream, episode: SourceEpisode) {
        data.writeUTF(episode.id.remoteKey)
        data.writeUTF(episode.title)
        data.writeNullableLong(episode.publishedAtEpochMillis)
    }

    private fun readEpisode(data: DataInputStream, series: SourceSeries): SourceEpisode = SourceEpisode(
        EpisodeId(series.id, data.readUTF()),
        data.readUTF(),
        data.readNullableLong(),
    )

    private fun writeAdjacent(data: DataOutputStream, manifest: EpisodeManifest) {
        data.writeNullable(manifest.previousEpisodeId?.remoteKey)
        data.writeNullable(manifest.nextEpisodeId?.remoteKey)
    }

    private fun writePage(data: DataOutputStream, spec: PageSpec, page: CachedPage) {
        data.writeUTF(spec.id.remoteKey)
        data.writeLong(page.byteCount)
        data.writeUTF(page.sha256)
        data.writeUTF(page.mediaType)
        data.writeInt(page.dimensions.widthPx)
        data.writeInt(page.dimensions.heightPx)
    }

    private fun readPages(data: DataInputStream, directory: File, episodeId: EpisodeId): List<StoredPage> {
        val count = data.readInt()
        require(count in 1..MAX_PAGE_COUNT) { "Offline manifest page count is invalid" }
        return List(count) { ordinal ->
            val id = PageId(episodeId, data.readUTF())
            val byteCount = data.readLong()
            val sha = data.readUTF()
            val mediaType = data.readUTF()
            val dimensions = PageDimensions(data.readInt(), data.readInt())
            val file = File(directory, pageName(ordinal))
            require(file.isFile && file.length() == byteCount) { "Offline page is missing or truncated" }
            val cached = CachedPage(id, file, byteCount, sha, mediaType, dimensions)
            StoredPage(PageSpec(id, ordinal, dimensions, byteCount, sha), cached)
        }
    }

    private fun readOptionalEpisode(data: DataInputStream, seriesId: SeriesId): EpisodeId? =
        data.readNullable()?.let { EpisodeId(seriesId, it) }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    fun pageName(ordinal: Int): String = "p${ordinal.toString().padStart(4, '0')}.page"
    const val MANIFEST_NAME = "manifest.bin"
    private const val MAGIC = 0x4D564F46
    private const val VERSION = 1
    private const val MAX_PAGE_COUNT = 10_000
}

private data class StoredPage(val spec: PageSpec, val cached: CachedPage)
