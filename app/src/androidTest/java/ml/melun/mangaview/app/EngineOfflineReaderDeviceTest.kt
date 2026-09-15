package ml.melun.mangaview.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.activity.withEngineCaptureViewer
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.EpisodeManifest
import ml.melun.mangaview.core.PageDimensions
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.PageSpec
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.core.lowerHex
import ml.melun.mangaview.data.cache.CachedPage
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.viewer.runtime.EngineSurfacePresentation
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOfflineReaderDeviceTest {
    private val series = SeriesId(SourceId("wfwf"), "offline-reader-device")
    private val episode = SourceEpisode(EpisodeId(series, "offline-reader-ep"), "offline reader episode")

    @Test fun theEngineReaderRendersADownloadedEpisodeFromDisk() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = (context.applicationContext as ViewerApplication).graph.offlineStore
        val root = File(context.cacheDir, "offline-reader-${System.nanoTime()}").apply { check(mkdirs()) }
        val output = File(context.cacheDir, "offline-reader-out-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            val cached = listOf(0, 1).map { index -> page(root, index) }
            val seededIds = cached.map { it.pageId }.toSet()
            val pages = cached.mapIndexed { index, page ->
                PageSpec(page.pageId, index, page.dimensions, page.byteCount, page.sha256)
            }
            store.save(SourceSeries(series, "offline reader device"), episode,
                EpisodeManifest(episode.id, episode.title, pages), cached)
            store.load()

            withEngineCaptureViewer(instrumentation, output, episode.id, SeriesKind.WEBTOON, catalogUi = false) { screen ->
                val frame = requireNotNull(withTimeout(READ_TIMEOUT_MILLIS) {
                    var snapshot = screen.viewerEngineFrameSnapshot()
                    while (!snapshot.presentsOnly(seededIds)) {
                        delay(50)
                        snapshot = screen.viewerEngineFrameSnapshot()
                    }
                    snapshot
                })
                val presentedIds = frame.scene.placements.map { it.texture.tile.pageId }.toSet()
                assertTrue("reader must render only downloaded pages, saw $presentedIds",
                    seededIds.containsAll(presentedIds))

                val plan = screen.viewerEngineSnapshot()?.plans?.get(episode.id)
                assertTrue("the reader must run on the downloaded plan, not a live one", plan?.localOnly == true)
                assertTrue("the reader must serve the offline revision",
                    plan?.contentRevision?.startsWith("offline:") == true)
            }
        } finally {
            store.remove(episode.id)
            root.deleteRecursively()
            output.deleteRecursively()
        }
    }

    private fun EngineSurfacePresentation?.presentsOnly(seeded: Set<PageId>): Boolean = this != null &&
        swapSucceeded && scene.placements.isNotEmpty() &&
        scene.placements.all { it.texture.tile.pageId in seeded }

    private fun page(root: File, index: Int): CachedPage {
        val id = PageId(episode.id, "p${index + 1}")
        val file = File(root, "page-$index.png")
        val bitmap = Bitmap.createBitmap(800, 1_200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24 + index * 60, 32, 48))
        canvas.drawRect(0f, if (index == 0) 0f else 1_000f, 800f, 1_200f, Paint().apply { color = Color.WHITE })
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).lowerHex()
        return CachedPage(id, file, file.length(), sha, "image/png", PageDimensions(800, 1_200))
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 90_000L
    }
}
