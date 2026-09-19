package ml.melun.mangaview.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.activity.EngineViewerScreen
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.activity.withEngineCaptureViewer
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineReaderZoomDeviceTest {
    private val series = SeriesId(SourceId("wfwf"), "reader-zoom-device")
    private val episode = SourceEpisode(EpisodeId(series, "reader-zoom-ep"), "reader zoom episode")

    @Test fun settingsSurviveBackgroundAndBackDismissesReaderOverlays() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val library = (instrumentation.targetContext.applicationContext as ViewerApplication).graph.userLibrary
        val before = library.snapshot.first().settings
        library.updateSettings { it.copy(immersiveMode = false, volumeKeyNavigation = false, keepScreenOn = false) }
        try {
            withReader { _, device, screen ->
                val x = device.displayWidth / 2
                val y = device.displayHeight / 2
                await("reader ready", { screen.viewerSurfaceTapEligible(x.toFloat(), y.toFloat()) }, { it })
                device.click(x, y)
                requireNotNull(device.wait(Until.findObject(By.text("설정")), 5_000)).click()
                assertTrue(device.wait(Until.hasObject(By.text("뷰어 설정")), 5_000))
                requireNotNull(device.findObject(By.desc("볼륨 버튼으로 이동"))).click()
                withTimeout(5_000) { library.snapshot.first { it.settings.volumeKeyNavigation } }
                requireNotNull(device.findObject(By.desc("화면 꺼짐 방지"))).click()
                withTimeout(5_000) { library.snapshot.first { it.settings.keepScreenOn } }
                instrumentation.runOnMainSync { assertTrue(screen.handleBack()) }
                assertTrue(device.wait(Until.gone(By.text("뷰어 설정")), 5_000))
                instrumentation.runOnMainSync {
                    screen.enterBackground()
                    assertEquals(0, screen.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    screen.enterForeground()
                }
                await("restored keep-screen-on preference", {
                    screen.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                }, { it != 0 })
                instrumentation.runOnMainSync {
                    assertTrue(screen.handleBack()) // Chrome dismisses before the reader closes.
                    assertTrue(screen.handleVolumeKey(true))
                }
                await("resumed renderer", { screen.viewerEngineFrameSnapshot()?.swapSucceeded == true }, { it })
                assertEquals(null, screen.viewerFailureSnapshot())
            }
        } finally { library.updateSettings { before } }
    }

    @Test fun doubleTapMagnifiesAndAnchorsTheRealReader() = runBlocking {
        withReader { instrumentation, device, screen ->
            val base = requireNotNull(await("first presented page",
                read = { screen.viewerEngineFrameSnapshot() },
                accept = { frame ->
                    frame?.scene?.placements?.any { it.topPx == 0 && it.texture.tile.pageId.episodeId == episode.id } == true
                }))
            val baseTop = base.scene.placements.first().topPx
            note("base scale=${screen.viewerSurfaceZoomScale()} top=$baseTop token=${base.identity.token}")
            assertEquals(1f, screen.viewerSurfaceZoomScale(), 0.05f)
            assertEquals(0, baseTop)

            val x = device.displayWidth / 2f
            val y = device.displayHeight / 2f
            await("surface taps to become eligible",
                read = { screen.viewerSurfaceTapEligible(x, y) },
                accept = { it },
                context = { "scale=${screen.viewerSurfaceZoomScale()}" })
            val trace = StringBuilder()
            injectDoubleTap(instrumentation, x, y) { trace.append(it).append(' ') }

            val applied = await("zoom applied",
                read = { screen.viewerSurfaceZoomScale() },
                accept = { it > 1.5f },
                context = { "tapTrace=$trace" })
            note("double tap applied scale=$applied")
            assertEquals(2f, applied, 0.1f)

            // The magnification anchors the tapped row: a zoom-in scrolls the engine forward.
            val zoomed = requireNotNull(await("anchored zoom-in frame",
                read = { screen.viewerEngineFrameSnapshot() },
                accept = { frame -> (frame?.scene?.placements?.firstOrNull()?.topPx ?: Int.MAX_VALUE) < baseTop - MIN_ANCHOR_UNITS }))
            val zoomedTop = zoomed.scene.placements.first().topPx
            note("zoomed top=$zoomedTop")
            assertTrue("zooming in must anchor the tapped row, base=$baseTop zoomed=$zoomedTop",
                baseTop - zoomedTop > MIN_ANCHOR_UNITS)

            injectDoubleTap(instrumentation, x, y)
            await("zoom restored",
                read = { screen.viewerSurfaceZoomScale() },
                accept = { it < 1.5f })
            await("anchored zoom-out frame",
                read = { screen.viewerEngineFrameSnapshot() },
                accept = { frame ->
                    abs((frame?.scene?.placements?.firstOrNull()?.topPx ?: Int.MAX_VALUE) - baseTop) <=
                        RESTORED_TOLERANCE_UNITS
                })
            note("restored scale=${screen.viewerSurfaceZoomScale()} top=${zoomedTop}")
            assertEquals(1f, screen.viewerSurfaceZoomScale(), 0.05f)
        }
    }

    @Test fun pinchMagnifiesAndRestoresTheRealReaderSurface() = runBlocking {
        withReader { instrumentation, device, screen ->
            await("first presented page",
                read = { screen.viewerEngineFrameSnapshot() },
                accept = { frame ->
                    frame?.scene?.placements?.any { it.texture.tile.pageId.episodeId == episode.id } == true
                })
            val x = device.displayWidth / 2f
            val y = device.displayHeight / 2f
            await("surface input to become touchable",
                read = { screen.viewerSurfaceTapEligible(x, y) },
                accept = { it })
            note("pinch start scale=${screen.viewerSurfaceZoomScale()}")

            // Every pointer has to start inside the window or the dispatcher drops it: on small
            // displays a 720px half-span is off-screen, so the whole gesture collapses to a drag.
            val maxHalfSpan = minOf(device.displayHeight, device.displayWidth) / 2f - 48f
            val smallSpan = maxHalfSpan / 3f
            injectPinch(instrumentation, x, y, startHalfSpan = smallSpan, endHalfSpan = maxHalfSpan)
            val magnified = await("pinch magnification",
                read = { screen.viewerSurfaceZoomScale() },
                accept = { it > 1.5f })
            note("pinched out scale=$magnified")

            injectPinch(instrumentation, x, y, startHalfSpan = maxHalfSpan, endHalfSpan = smallSpan)
            val restored = await("pinch restore",
                read = { screen.viewerSurfaceZoomScale() },
                accept = { it < 1.2f })
            note("pinched in scale=$restored")
            await("post zoom frame",
                read = { screen.viewerEngineFrameSnapshot() },
                accept = { frame: EngineSurfacePresentation? -> frame?.swapSucceeded == true })
        }
    }

    private suspend fun withReader(block: suspend (Instrumentation, UiDevice, EngineViewerScreen) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = (context.applicationContext as ViewerApplication).graph.offlineStore
        val root = File(context.cacheDir, "reader-zoom-${System.nanoTime()}").apply { check(mkdirs()) }
        val output = File(context.cacheDir, "reader-zoom-out-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            val cached = listOf(0, 1).map { index -> page(root, index) }
            val pages = cached.mapIndexed { index, page ->
                PageSpec(page.pageId, index, page.dimensions, page.byteCount, page.sha256)
            }
            store.save(SourceSeries(series, "reader zoom device"), episode,
                EpisodeManifest(episode.id, episode.title, pages), cached)
            store.load()
            withEngineCaptureViewer(instrumentation, output, episode.id, SeriesKind.WEBTOON, catalogUi = false) { screen ->
                withTimeout(READ_TIMEOUT_MILLIS) {
                    while (!screen.isViewerInputSurfaceReady()) delay(50)
                }
                block(instrumentation, UiDevice.getInstance(instrumentation), screen)
            }
        } finally {
            store.remove(episode.id)
            root.deleteRecursively()
            output.deleteRecursively()
        }
    }

    private suspend fun <T> await(
        what: String,
        read: () -> T,
        accept: (T) -> Boolean,
        context: () -> String = { "" },
    ): T {
        val deadline = SystemClock.uptimeMillis() + READ_TIMEOUT_MILLIS
        var value = read()
        while (!accept(value)) {
            check(SystemClock.uptimeMillis() <= deadline) { "timed out waiting for $what; last=$value ${context()}" }
            delay(50)
            value = read()
        }
        return value
    }

    private fun note(message: String) = System.out.println("[reader-zoom] $message")

    private fun injectDoubleTap(
        instrumentation: Instrumentation,
        x: Float,
        y: Float,
        observe: (String) -> Unit = {},
    ) {
        // The window is 300 ms of real dispatch time; keep the two taps on the platform queue.
        val start = SystemClock.uptimeMillis()
        send(instrumentation, MotionEvent.ACTION_DOWN, x, y, start, start, observe)
        send(instrumentation, MotionEvent.ACTION_UP, x, y, start, start + 5, observe)
        send(instrumentation, MotionEvent.ACTION_DOWN, x, y, start + 10, start + 10, observe)
        send(instrumentation, MotionEvent.ACTION_UP, x, y, start + 10, start + 15, observe)
    }

    private fun injectPinch(
        instrumentation: Instrumentation,
        centerX: Float,
        centerY: Float,
        startHalfSpan: Float,
        endHalfSpan: Float,
        steps: Int = 12,
    ) {
        val downTime = SystemClock.uptimeMillis()
        val properties = arrayOf(pointer(0), pointer(1))
        fun coordinates(halfSpan: Float) = arrayOf(
            pointerCoords(centerX, centerY - halfSpan),
            pointerCoords(centerX, centerY + halfSpan),
        )
        val first = coordinates(startHalfSpan)
        send(instrumentation, MotionEvent.ACTION_DOWN, downTime, downTime,
            arrayOf(properties[0]), arrayOf(first[0]))
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        send(instrumentation, pointerDown, downTime, downTime + 20, properties, first)
        for (step in 1..steps) {
            val half = startHalfSpan + (endHalfSpan - startHalfSpan) * step / steps.toFloat()
            send(instrumentation, MotionEvent.ACTION_MOVE, downTime, downTime + 20 + step * 20L,
                properties, coordinates(half))
        }
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val at = downTime + 20 + (steps + 1) * 20L
        val last = coordinates(endHalfSpan)
        send(instrumentation, pointerUp, downTime, at, properties, last)
        send(instrumentation, MotionEvent.ACTION_UP, downTime, at + 20, arrayOf(properties[0]), arrayOf(last[0]))
    }

    private fun pointer(id: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun pointerCoords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }

    private fun send(
        instrumentation: Instrumentation,
        action: Int,
        downTime: Long,
        eventTime: Long,
        properties: Array<MotionEvent.PointerProperties>,
        coordinates: Array<MotionEvent.PointerCoords>,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, properties.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "touch injection rejected" }
        } finally {
            event.recycle()
        }
    }

    private fun send(
        instrumentation: Instrumentation,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        observe: (String) -> Unit = {},
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val queuedAt = SystemClock.uptimeMillis()
        try {
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "touch injection rejected" }
        } finally {
            event.recycle()
        }
        observe("${actionName(action)}@${SystemClock.uptimeMillis() - queuedAt}ms")
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "down"
        MotionEvent.ACTION_UP -> "up"
        MotionEvent.ACTION_MOVE -> "move"
        else -> "action$action"
    }

    private fun page(root: File, index: Int): CachedPage {
        val id = PageId(episode.id, "p${index + 1}")
        val file = File(root, "page-$index.png")
        val bitmap = Bitmap.createBitmap(800, 3_000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24 + index * 60, 32, 48))
        canvas.drawRect(0f, 200f * (index + 1), 800f, 200f * (index + 1) + 400f,
            Paint().apply { color = Color.WHITE })
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).lowerHex()
        return CachedPage(id, file, file.length(), sha, "image/png", PageDimensions(800, 3_000))
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 60_000L
        const val MIN_ANCHOR_UNITS = 1024 * 100
        const val RESTORED_TOLERANCE_UNITS = 1024 * 20
    }
}
