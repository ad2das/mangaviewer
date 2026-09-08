package ml.melun.mangaview.viewer.runtime

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.*
import ml.melun.mangaview.core.*
import ml.melun.mangaview.engine.api.*
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.AdjacentEpisodes
import ml.melun.mangaview.viewer.FixedPx
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineViewerReadinessPositionTest {
    @Test fun closingWhileTheNextOriginalIsBlockedSavesTheSuccessfullySubmittedAnchor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("readiness-position-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(100, 300, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff82b447.toInt())
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val consumer = SurfaceTexture(false).apply { setDefaultBufferSize(100, 100) }
        val surface = Surface(consumer)
        try {
            withContext(Dispatchers.Main.immediate) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val coordinator = WorkCoordinator(scope)
                val source = Source(file)
                val positions = Positions()
                val failures = mutableListOf<Throwable>()
                val runtime = EngineViewerRuntime(context, scope, coordinator, source, positions, source.episode,
                    EngineViewport(100, 100), Dispatchers.IO, {}, {}, { failures += it })
                try {
                    runtime.open()
                    runtime.surfaceAvailable(surface, 100, 100, 60F)
                    withTimeout(10_000) {
                        while (runtime.bookmarkSnapshot() == null) {
                            assertTrue(failures.toString(), failures.isEmpty())
                            delay(10)
                        }
                    }
                    val displayed = SourceAnchor(PageId.at(source.episode, 0), 0)
                    assertEquals(displayed, runtime.bookmarkSnapshot()!!.first)
                    assertTrue(runtime.userScroll(FixedPx.fromPixels(400), 0f, System.nanoTime(), 0, 0))
                    assertTrue(runtime.userScroll(FixedPx.fromPixels(-50), 0f, System.nanoTime(), 0, 0))
                    assertEquals(SourceAnchor(PageId.at(source.episode, 1),
                        50L * SourceAnchor.SOURCE_UNITS_PER_PIXEL), runtime.snapshot().session.anchor)
                    assertEquals(0, runtime.snapshot().session.pendingInputCount)
                    assertEquals(displayed, runtime.bookmarkSnapshot()!!.first)
                    withTimeout(5_000) {
                        while (positions.saved == null) delay(10)
                    }
                    assertEquals(displayed to 0L, positions.saved)
                    runtime.close()
                    assertEquals(displayed to 0L, positions.saved)
                    assertTrue(failures.toString(), failures.isEmpty())
                } finally {
                    runtime.close()
                    coordinator.close()
                    scope.cancel()
                }
                assertEquals(0, coordinator.snapshot().subscribers)
            }
        } finally {
            surface.release()
            consumer.release()
            assertTrue(file.delete())
        }
    }

    private class Positions : EnginePositionPort {
        var saved: Pair<SourceAnchor, Long>? = null
        override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) {
            saved = anchor to legacyScreenOffsetUnits
        }
        override suspend fun load(episodeId: EpisodeId): SourceAnchor? = saved?.first
    }

    private class Source(private val file: File) : EngineSessionWork {
        val episode = EpisodeId(SeriesId(SourceId("fixture"), "readiness"), "1")
        private val dimensions = PageDimensions(100, 300)
        private val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private val secondOriginal = CompletableDeferred<Unit>()
        private val manifest = EpisodeManifest(episode, "Readiness position",
            (0..1).map { PageSpec(PageId.at(episode, it), it, dimensions) })
        private val plan = EpisodeAccessPlan(manifest, "1", "0".repeat(64), URI("https://fixture.example/read"), 0,
            manifest.pages.map { PageAccessPlan(it.id, it.ordinal.toString(), listOf(URI("https://fixture.example/page.png"))) })

        override fun position(episodeId: EpisodeId) = WorkRequest(
            WorkKey("fixture", "position", "load", "1", SessionPosition::class.java),
            WorkDomain.STORAGE, WorkPriority.FOCUS, execute = { SessionPosition(null) })

        override fun episode(episodeId: EpisodeId, priority: WorkPriority) = WorkRequest(
            WorkKey("fixture", "episode", "load", "1", EpisodeAccessPlan::class.java),
            WorkDomain.CONTROL, priority, execute = { plan })

        override fun navigation(episodeId: EpisodeId, priority: WorkPriority) = WorkRequest(
            WorkKey("fixture", "navigation", "load", "1", AdjacentEpisodes::class.java),
            WorkDomain.CONTROL, priority, execute = { AdjacentEpisodes(null, null) })

        override fun page(plan: EpisodeAccessPlan, pageId: PageId, priority: WorkPriority) = WorkRequest(
            WorkKey("fixture", pageId.toString(), "load", "1", StoredPage::class.java),
            WorkDomain.BODY, priority, execute = {
                if (pageId == PageId.at(episode, 1)) secondOriginal.await()
                StoredPage(pageId, "1", file, file.length(), sha, dimensions, "image/png")
            })
    }
}
