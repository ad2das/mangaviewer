package ml.melun.mangaview.activity

import android.app.Instrumentation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.data.engine.EnginePositionStore
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.ui.library.LibraryViewModel
import org.json.JSONObject
import org.junit.Assert.*

/** The actual home/card/back/bookmark controls, plus Android background and recreation callbacks. */
internal suspend fun verifyEmbeddedReaderLifecycle(
    instrumentation: Instrumentation,
    home: ActivityScenario<MainActivity>,
    initial: EngineViewerScreen,
    expected: SourceAnchor,
    output: File,
): SourceAnchor {
    val device = UiDevice.getInstance(instrumentation)
    val graph = (instrumentation.targetContext.applicationContext as ViewerApplication).graph
    val originalWindow = initial.window
    fun mark(step: String) {
        File(output, "lifecycle-steps.txt").appendText("$step atNanos=${System.nanoTime()} " +
            "reader=${reader(home)?.launchSpec?.episodeId} anchor=${reader(home)?.viewerEngineFrameSnapshot()?.scene?.anchor}\n")
    }
    mark("begin")
    var selectedSource: ml.melun.mangaview.core.SourceId? = null
    home.onActivity { selectedSource = ViewModelProvider(it)[LibraryViewModel::class.java].state.value.selectedSourceId }
    val surface = requireNotNull(device.wait(Until.findObject(By.desc("viewer-surface")), 5000)).visibleBounds
    val priorBookmark = graph.userLibrary.snapshot.first().bookmarks.firstOrNull { it.pageId == expected.pageId }
    device.click(surface.centerX(), surface.centerY())
    requireNotNull(device.wait(Until.findObject(By.text("책갈피")), 5000)).click()
    withTimeout(5000) { graph.userLibrary.snapshot.first { it.bookmarks.any { bookmark ->
        bookmark.pageId == expected.pageId && bookmark.createdAtEpochMillis != priorBookmark?.createdAtEpochMillis
    } } }
    assertEquals("Bookmark rounded the source anchor", expected,
        (graph.engine.positions as EnginePositionStore).loadBookmark(expected.pageId))

    device.pressBack()
    withTimeout(15_000) {
        while (reader(home) != null) delay(10)
        initial.awaitEngineClosed()
    }
    assertTrue(device.wait(Until.hasObject(By.desc("하단 홈")), 5000))
    assertFalse(device.hasObject(By.desc("viewer-surface")))
    home.onActivity {
        assertSame(originalWindow, it.window)
        assertEquals(selectedSource, ViewModelProvider(it)[LibraryViewModel::class.java].state.value.selectedSourceId)
    }
    assertEquals(expected, graph.engine.positions.load(expected.pageId.episodeId))
    assertTrue(device.takeScreenshot(File(output, "returned-library.png")))

    val currentTitle = graph.userLibrary.snapshot.first().recent.first { it.series.id == expected.pageId.episodeId.seriesId }.series.title
    val row = requireNotNull(device.wait(Until.findObject(By.desc("홈 이어보기 목록")), 5000))
    row.scroll(Direction.LEFT, 0.8f)
    requireNotNull(device.wait(Until.findObject(By.desc("이어보기: $currentTitle")), 10_000)).click()
    var reopened = awaitReader(home)
    mark("reopened")
    assertSame(originalWindow, reopened.window)
    awaitAnchor(reopened, expected)

    val frameBeforeBackground = reopened.engineFramesSince(0).latestOrdinal
    home.moveToState(Lifecycle.State.CREATED)
    home.moveToState(Lifecycle.State.RESUMED)
    assertSame(reopened, reader(home))
    awaitAnchor(reopened, expected, frameBeforeBackground)
    mark("background-resumed")
    assertNull(reopened.viewerFailureSnapshot())

    home.recreate()
    val restored = awaitReader(home)
    assertNotSame(reopened, restored)
    assertEquals("Saved-instance state changed the fractional source row", expected, restored.launchSpec.initialAnchor)
    awaitAnchor(restored, expected)
    mark("recreated-exact")
    withTimeout(30_000) { reopened.awaitEngineClosed() }
    assertTrue(device.takeScreenshot(File(output, "restored-reader.png")))
    assertEquals(expected, graph.engine.positions.load(expected.pageId.episodeId))
    val navigationWindow = restored.window
    reopened = restored
    val originalEpisode = expected.pageId.episodeId
    val originalTitle = requireNotNull(reopened.viewerEngineSnapshot()?.plans?.get(originalEpisode)).manifest.title
    val nextEpisode = withTimeout(30_000) {
        var next = reopened.viewerEngineSnapshot()?.plans?.get(originalEpisode)?.manifest?.nextEpisodeId
        while (next == null) {
            assertNull(reopened.viewerFailureSnapshot())
            delay(10)
            next = reopened.viewerEngineSnapshot()?.plans?.get(originalEpisode)?.manifest?.nextEpisodeId
        }
        next
    }
    mark("next-resolved-$nextEpisode")
    device.click(surface.centerX(), surface.centerY())
    requireNotNull(device.wait(Until.findObject(By.text("다음")), 5000)).click()
    val adjacent = withTimeout(15_000) {
        var found = reader(home)
        while (found?.launchSpec?.episodeId != nextEpisode) { delay(10); found = reader(home) }
        requireNotNull(found)
    }
    assertSame(navigationWindow, adjacent.window)
    mark("next-attached")
    withTimeout(30_000) { reopened.awaitEngineClosed() }
    mark("previous-closed")
    withTimeout(30_000) {
        while (adjacent.engineFramesSince(0).observations.none { it.presentation.swapSucceeded &&
            it.presentation.scene.completeCoverage && it.presentation.scene.anchor?.pageId?.episodeId == nextEpisode }) {
            assertNull(adjacent.viewerFailureSnapshot())
            delay(10)
        }
    }
    mark("next-filled")
    device.click(surface.centerX(), surface.centerY())
    requireNotNull(device.wait(Until.findObject(By.text("회차")), 5000)).click()
    assertTrue(adjacent.episodePickerFailureSnapshot()?.stackTraceToString() ?: "Episode picker did not open",
        device.wait(Until.hasObject(By.text("회차 선택")), 30_000))
    requireNotNull(device.wait(Until.findObject(By.text(originalTitle)), 5000)).click()
    mark("picker-selected-$originalTitle")
    reopened = withTimeout(15_000) {
        var found = reader(home)
        while (found?.launchSpec?.episodeId != originalEpisode) { delay(10); found = reader(home) }
        requireNotNull(found)
    }
    assertSame(navigationWindow, reopened.window)
    mark("picker-original-attached")
    // Reading progress is one last-read position per series; reading the next episode
    // replaces it. Selecting the earlier episode then correctly starts at page zero.
    val selectedPlan = withTimeout(30_000) {
        var plan = reopened.viewerEngineSnapshot()?.plans?.get(originalEpisode)
        while (plan == null) { delay(10); plan = reopened.viewerEngineSnapshot()?.plans?.get(originalEpisode) }
        plan
    }
    val afterNavigation = SourceAnchor(selectedPlan.manifest.pages.first().id, 0, 0)
    awaitAnchor(reopened, afterNavigation)
    withTimeout(30_000) { adjacent.awaitEngineClosed() }
    mark("picker-original-exact")

    device.click(surface.centerX(), surface.centerY())
    assertTrue(device.wait(Until.hasObject(By.text("다음")), 5000))
    home.onActivity { activity ->
        val buttons = arrayListOf<android.view.View>()
        activity.window.decorView.findViewsWithText(buttons, "다음", android.view.View.FIND_VIEWS_WITH_TEXT)
        val button = buttons.filterIsInstance<android.widget.TextView>().single { it.isShown && it.text == "다음" }
        // Exercise the actual control and save in this same UI turn, before old-reader retirement
        // can complete. This isolates the otherwise timing-dependent rotation-during-navigation case.
        assertTrue(button.performClick())
        assertNull("Navigation did not enter its retirement interval", activity.readerScreen())
        val host = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }
            .get(activity) as MainReaderHost
        val saved = android.os.Bundle().also(host::saveState)
        val pending = requireNotNull(saved.getBundle("main.reader.session")) { "Pending reader disappeared from saved state" }
        assertEquals(nextEpisode, ViewerScreenState.read(pending).episodeId)
    }
    val pendingTarget = withTimeout(15_000) {
        var found = reader(home)
        while (found?.launchSpec?.episodeId != nextEpisode) { delay(10); found = reader(home) }
        requireNotNull(found)
    }
    val finalAnchor = SourceAnchor(ml.melun.mangaview.core.PageId(nextEpisode, "p0000"), 0, 0)
    awaitAnchor(pendingTarget, finalAnchor)
    withTimeout(30_000) { reopened.awaitEngineClosed() }

    device.pressBack()
    withTimeout(30_000) { pendingTarget.awaitEngineClosed() }
    assertNull(reader(home))
    assertEquals(finalAnchor, graph.engine.positions.load(nextEpisode))
    assertEquals(expected, (graph.engine.positions as EnginePositionStore).loadBookmark(expected.pageId))
    File(output, "lifecycle.json").writeText(JSONObject().put("bookmarkExact", true)
        .put("backReturnedToSameWindow", true).put("reopenExact", true)
        .put("nextButtonSameWindow", true).put("episodePickerSelectedRequestedEpisode", true)
        .put("pendingNavigationSaved", true)
        .put("backgroundResumeExact", true).put("recreationExact", true)
        .put("savedAnchorBeforeNavigation", expected.toString())
        .put("savedAnchorAfterNavigation", finalAnchor.toString()).toString(2))
    return finalAnchor
}

private fun reader(home: ActivityScenario<MainActivity>): EngineViewerScreen? {
    var result: EngineViewerScreen? = null
    home.onActivity { result = it.readerScreen() }
    return result
}

private suspend fun awaitReader(home: ActivityScenario<MainActivity>): EngineViewerScreen = withTimeout(15_000) {
    var found = reader(home)
    while (found == null) { delay(10); found = reader(home) }
    found
}

private suspend fun awaitAnchor(screen: EngineViewerScreen, expected: SourceAnchor, since: Long = 0) = withTimeout(30_000) {
    while (screen.engineFramesSince(since).observations.none { it.presentation.swapSucceeded &&
        it.presentation.scene.completeCoverage && it.presentation.scene.anchor == expected }) {
        assertNull(screen.viewerFailureSnapshot())
        delay(10)
    }
}
