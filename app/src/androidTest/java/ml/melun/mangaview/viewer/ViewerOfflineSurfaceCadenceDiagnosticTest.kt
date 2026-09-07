package ml.melun.mangaview.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.ViewerApplication
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
import org.junit.Test
import org.junit.runner.RunWith

/** Separates Surface cadence from provider, ACK and network time; never counts as corpus evidence. */
@RunWith(AndroidJUnit4::class)
class ViewerOfflineSurfaceCadenceDiagnosticTest {
    @Test
    fun localVerifiedPagesKeepStrictRealGestureCadence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as ViewerApplication
        val sourceId = SourceId("ntk")
        val seriesId = SeriesId(sourceId, SERIES_KEY)
        // A diagnostic run must not inherit the reading anchor written by an earlier run.
        val episodeKey = "$EPISODE_PREFIX-${System.nanoTime()}"
        val episodeId = EpisodeId(seriesId, episodeKey)
        val scratch = File(context.cacheDir, "offline-surface-diagnostic").apply { mkdirs() }
        val pages = createPages(scratch, episodeId)
        val manifest = EpisodeManifest(
            id = episodeId,
            title = "Surface cadence diagnostic",
            pages = pages.mapIndexed { ordinal, page ->
                PageSpec(page.pageId, ordinal, page.dimensions, page.byteCount, page.sha256)
            },
        )
        runBlocking {
            application.graph.offlineStore.save(
                SourceSeries(seriesId, "Surface diagnostic"),
                SourceEpisode(episodeId, "Surface cadence diagnostic", pageCountHint = pages.size),
                manifest,
                pages,
            )
        }

        ViewerUxTestHarness(
            instrumentation = instrumentation,
            artifactPrefix = "offline-surface-cadence-diagnostic",
        ).run(LiveEpisode(sourceId.value, SERIES_KEY, episodeKey))
    }

    private fun createPages(root: File, episodeId: EpisodeId): List<CachedPage> =
        List(PAGE_COUNT) { ordinal ->
            val height = PAGE_HEIGHT + (ordinal % 4) * 240
            val dimensions = PageDimensions(PAGE_WIDTH, height)
            val file = File(root, "page-$ordinal.jpg")
            val bitmap = Bitmap.createBitmap(PAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(PALETTE[ordinal % PALETTE.size])
                drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 96f, HEADER_PAINT)
                drawText("PAGE ${ordinal + 1}", 44f, 68f, TEXT_PAINT)
                repeat(12) { stripe ->
                    val top = 140f + stripe * ((height - 180f) / 12f)
                    drawRect(36f, top, PAGE_WIDTH - 36f, top + 8f, STRIPE_PAINT)
                }
            }
            file.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
            }
            bitmap.recycle()
            val pageId = PageId(episodeId, "page-$ordinal")
            val hash = sha256(file)
            CachedPage(pageId, file, file.length(), hash, "image/jpeg", dimensions)
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SERIES_KEY = "diagnostic-offline-series"
        const val EPISODE_PREFIX = "diagnostic-offline-episode"
        const val PAGE_COUNT = 24
        const val PAGE_WIDTH = 1080
        const val PAGE_HEIGHT = 1680
        val PALETTE = intArrayOf(
            Color.rgb(238, 92, 92),
            Color.rgb(82, 161, 236),
            Color.rgb(96, 190, 138),
            Color.rgb(224, 167, 73),
        )
        val HEADER_PAINT = Paint().apply { color = Color.rgb(24, 28, 36) }
        val TEXT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
        }
        val STRIPE_PAINT = Paint().apply { color = Color.argb(130, 255, 255, 255) }
    }
}
