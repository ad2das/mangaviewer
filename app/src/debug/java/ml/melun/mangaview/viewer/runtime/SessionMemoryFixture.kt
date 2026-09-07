package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import ml.melun.mangaview.content.EncodedPageRef
import ml.melun.mangaview.content.RawPagePort
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.source.ContentSource
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageFetchPriority
import ml.melun.mangaview.source.PageValidation
import ml.melun.mangaview.source.PreparationIntent
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourcePage
import ml.melun.mangaview.source.SourceSeries

/** Private fixture files only; no graph, provider delegate, production cache, or network client. */
internal class SessionMemoryFixture(private val directory: File) : ContentSource, RawPagePort {
    override val id = SourceId("memory-fixture")
    val episode = EpisodeId(SeriesId(id, "pressure"), "episode")
    val dimensions = PageDimensions(256, 1024)
    val pages = List(6) { PageSpec(PageId.at(episode, it), it, dimensions) }
    val manifestCalls = AtomicInteger()
    val fetchCalls = AtomicInteger()

    fun writeImages() {
        check(directory.mkdirs() || directory.isDirectory)
        pages.forEachIndexed { index, page ->
            val bitmap = Bitmap.createBitmap(dimensions.widthPx, dimensions.heightPx, Bitmap.Config.ARGB_8888)
            try {
                val pixels = IntArray(dimensions.widthPx * dimensions.heightPx) { pixel ->
                    color(index, pixel / dimensions.widthPx)
                }
                bitmap.setPixels(pixels, 0, dimensions.widthPx, 0, 0, dimensions.widthPx, dimensions.heightPx)
                file(page.id).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    override suspend fun prepare(episodeId: EpisodeId, intent: PreparationIntent) { check(episodeId == episode) }
    override suspend fun manifest(episodeId: EpisodeId): EpisodeManifest {
        check(episodeId == episode)
        manifestCalls.incrementAndGet()
        return EpisodeManifest(episode, "Memory fixture", pages)
    }

    override suspend fun find(pageId: PageId): EncodedPageRef? = null
    override suspend fun fetch(pageId: PageId, priority: PageFetchPriority, responseStarted: () -> Unit): EncodedPageRef {
        fetchCalls.incrementAndGet()
        responseStarted()
        val image = file(pageId)
        return EncodedPageRef(pageId, image.absolutePath, image.length(), "memory-${pageId.remoteKey}", dimensions)
    }

    override suspend fun search(query: String, cursor: String?): SourcePage<SourceSeries> = error("Unused fixture search")
    override suspend fun episodes(seriesId: SeriesId, cursor: String?): SourcePage<SourceEpisode> = error("Unused fixture episodes")
    override suspend fun adjacent(episodeId: EpisodeId) = AdjacentEpisodes(null, null)
    override suspend fun openPage(pageId: PageId, validation: PageValidation?): OpenedPage = error("Fixture raw port owns image reads")

    private fun file(pageId: PageId): File {
        check(pages.any { it.id == pageId })
        return File(directory, "page-${pageId.remoteKey}.png")
    }

    companion object {
        private val COLORS = intArrayOf(Color.RED, Color.GREEN, Color.CYAN, Color.MAGENTA, Color.BLUE, Color.YELLOW)
        fun color(page: Int, sourceRow: Int): Int = if (sourceRow / 128 % 2 == 0) {
            COLORS[page]
        } else Color.WHITE
    }
}
