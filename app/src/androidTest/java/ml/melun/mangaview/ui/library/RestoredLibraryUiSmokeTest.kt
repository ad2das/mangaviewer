package ml.melun.mangaview.ui.library

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ml.melun.mangaview.activity.MainActivity
import ml.melun.mangaview.viewer.sourceChipDescription
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoredLibraryUiSmokeTest {
    @get:Rule val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null),
                "engine-capture-ui-smoke-${System.nanoTime()}").apply { check(mkdirs()) }
            val device = UiDevice.getInstance(instrumentation)
            freshAccessibility()
            device.dumpWindowHierarchy(output.resolve("hierarchy.xml"))
            device.takeScreenshot(output.resolve("screen.png"))
            instrumentation.runOnMainSync {
                val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
                val state = activity?.let { androidx.lifecycle.ViewModelProvider(it)[LibraryViewModel::class.java].state.value }
                output.resolve("state.txt").writeText("test=${description.methodName}\nerror=$error\n" +
                    "source=${state?.selectedSourceId} homeKind=${state?.homeKind} selectedGenre=${state?.selectedGenre}\n" +
                    "searchKind=${state?.searchKind} searchField=${state?.searchField} destination=${state?.destination}\n")
            }
        }
    }

    private fun freshAccessibility() {
        if (android.os.Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
    }

    @Test
    fun genreScrollLoadsThroughTheEndAndRetainsItsPositionAfterDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        device.wait(Until.findObject(By.desc("하단 홈")), TIMEOUT)!!.click()
        dismissAutomaticUpdateNotice(device)
        ensureNtkSelected(device)
        openGenreTab(device, "만화")
        repeat(4) {
            if (device.findObject(By.text("음악")) == null) scrollCatalog(device)
        }
        openDedicatedGenre(device, "음악")
        assertNotNull(device.wait(Until.findObject(By.descContains("작품:")), CATALOG_TIMEOUT))
        var reachedEnd = false
        for (step in 0 until 30) {
            if (device.findObject(By.textStartsWith("목록 끝 · ")) != null) { reachedEnd = true; break }
            scrollCatalog(device)
        }
        assertTrue("Genre did not reach the provider's final page", reachedEnd)
        val card = device.findObjects(By.descContains("작품:")).first()
        val description = card.contentDescription
        val bounds = card.visibleBounds
        card.click()
        assertVisible(device, "회차")
        device.pressBack()
        val returned = device.wait(Until.findObject(By.desc(description)), TIMEOUT)
        assertNotNull("Back lost the scrolled catalog position", returned)
        org.junit.Assert.assertEquals(bounds, returned!!.visibleBounds)
        assertNotNull(device.findObject(By.textStartsWith("목록 끝 · ")))
    }

    private fun scrollCatalog(device: UiDevice) {
        device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
            device.displayWidth / 2, device.displayHeight / 3, 30)
        device.waitForIdle(1000)
    }

    @Test
    fun adultAndYuriOpenAsImmediateDedicatedCatalogScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        device.wait(Until.findObject(By.desc("하단 홈")), TIMEOUT)?.click()
        dismissAutomaticUpdateNotice(device)
        ensureNtkSelected(device)
        openGenreTab(device, "웹툰")
        assertTrue(
            "Genre choices must not append a catalog below themselves",
            device.findObjects(By.descContains("작품:")).isEmpty(),
        )
        openDedicatedGenre(device, "성인")
        openFirstSeriesAndReturn(device, "성인")

        device.pressBack()
        assertVisible(device, "장르 둘러보기")
        openGenreTab(device, "만화")
        openDedicatedGenre(device, "백합")
        openFirstSeriesAndReturn(device, "백합")
    }

    @Test
    fun oldNavigationAndPrimaryScreensRemainVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        device.wait(Until.findObject(By.desc("하단 홈")), TIMEOUT)?.click()
        dismissAutomaticUpdateNotice(device)
        assertVisible(device, "MangaView")
        assertVisible(device, "읽던 작품으로 바로 이동")
        ensureNtkSelected(device)
        assertVisible(device, "웹툰")
        assertVisible(device, "만화")

        device.wait(Until.findObject(By.desc("하단 검색")), TIMEOUT)?.click()
        assertVisible(device, "전체 검색")
        device.waitForIdle(SEARCH_LAYOUT_SETTLE_MILLIS)
        changeControl(device, "검색 항목: 제목", "검색 항목: 작가")
        changeControl(device, "검색 범위: 전체", "검색 범위: 웹툰")
        changeControl(device, "검색 범위: 웹툰", "검색 범위: 만화")

        device.wait(Until.findObject(By.desc("하단 보관함")), TIMEOUT)?.click()
        assertVisible(device, "내 보관함")
        assertVisible(device, "최근")
        assertVisible(device, "좋아요")
        assertVisible(device, "저장됨")

        device.wait(Until.findObject(By.desc("계정")), TIMEOUT)?.click()
        assertVisible(device, "계정으로 이어보기")
        assertVisible(device, "설정 열기")
        assertNotNull("Missing update action", device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("업데이트 확인|새 업데이트 있음"))), TIMEOUT))
        device.wait(Until.findObject(By.text("설정 열기")), TIMEOUT)?.click()
        assertVisible(device, "기본 설정")
        assertVisible(device, "사이트 변경")
        assertVisible(device, "앱 시작시 탭 위치")
        assertVisible(device, "어두운 테마")
    }

    /**
     * Bounded poll: [TIMEOUT] is the start deadline for how long the fixture keeps looking for [text]
     * (and for the delayed update notice); it is not a guarantee that all OS UI calls complete within 5 s.
     */
    private fun assertVisible(device: UiDevice, text: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var found: androidx.test.uiautomator.UiObject2? = null
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            freshAccessibility()
            found = device.wait(Until.findObject(By.text(text)),
                (deadline - SystemClock.uptimeMillis()).coerceAtMost(500L).coerceAtLeast(1L))
            if (found != null || SystemClock.uptimeMillis() >= deadline) break
            freshAccessibility()
            dismissUpdateNoticeIfPresent(device)
        }
        if (found == null) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "navigation-failure.png"))
            device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "navigation-failure.xml"))
        }
        assertNotNull("Missing restored UI text: $text", found)
    }

    /**
     * The delayed automatic update notice can appear after the initial dismissal call and, being modal,
     * removes the home content from the accessibility tree. Dismiss it without blocking; the caller's
     * bounded poll remains the only deadline.
     */
    private fun dismissUpdateNoticeIfPresent(device: UiDevice) {
        try {
            if (device.findObject(By.text("새 업데이트가 있습니다")) == null) return
            val dismiss = device.findObject(By.text("닫기")) ?: return
            dismiss.click()
        } catch (_: StaleObjectException) {
            // Notice was replaced while resolving; the next poll reacquires it.
        }
    }

    private fun assertDescription(device: UiDevice, description: String) {
        val selector = By.desc(description)
        val found = device.wait(Until.findObject(selector), TIMEOUT)
            ?: device.findObjects(By.descContains(description.substringBefore(':')))
                .firstOrNull { it.contentDescription == description }
        assertNotNull(
            "Missing restored UI control state: $description; visible=" +
                device.findObjects(By.descContains(description.substringBefore(':')))
                    .joinToString { "${it.contentDescription}@${it.visibleBounds}" },
            found,
        )
    }

    private fun changeControl(device: UiDevice, from: String, to: String) {
        freshAccessibility()
        val control = device.wait(Until.findObject(By.desc(from)), TIMEOUT)
        assertNotNull("Missing restored UI control state: $from", control)
        val center = control.visibleCenter
        val output = device.executeShellCommand("input tap ${center.x} ${center.y}")
        check(output.isBlank()) { "Input tap failed at $center: $output" }
        device.waitForIdle(SEARCH_LAYOUT_SETTLE_MILLIS)
        freshAccessibility()
        assertDescription(device, to)
    }

    private fun ensureNtkSelected(device: UiDevice) {
        if (device.findObject(By.desc(sourceChipDescription("NTK"))) != null) return
        val current = device.findObject(By.desc(sourceChipDescription("WFWF")))
            ?: device.findObject(By.desc(sourceChipDescription("뉴엑스툰")))
        assertNotNull("No provider selector is visible", current)
        current.click()
        assertNotNull("Source picker did not open", device.wait(Until.findObject(By.text("사이트 선택")), TIMEOUT))
        assertTrue("NTK row was not present and stable in the open source picker", selectNtkFromPicker(device))
        assertNotNull("Could not switch to NTK", device.wait(Until.findObject(By.desc(sourceChipDescription("NTK"))), TIMEOUT))
    }

    /** Bounded within one [TIMEOUT]: fresh lookup, two stationary non-empty bounds, then a coordinate tap. */
    private fun selectNtkFromPicker(device: UiDevice): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var previous: android.graphics.Rect? = null
        var stationary = 0
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                freshAccessibility()
                if (device.findObject(By.desc(sourceChipDescription("NTK"))) != null) return true
                if (device.findObject(By.text("사이트 선택")) == null) return false
                val bounds = device.findObject(By.text("NTK"))?.visibleBounds
                stationary = if (bounds != null && !bounds.isEmpty && bounds == previous) stationary + 1 else 0
                if (stationary >= 2) {
                    val output = device.executeShellCommand("input tap ${bounds!!.centerX()} ${bounds.centerY()}")
                    check(output.isBlank()) { "Input tap failed at $bounds: $output" }
                    return true
                }
                previous = bounds
            } catch (_: StaleObjectException) {
                // Picker rows are replaced while settling; drop the stale sample and reacquire.
                previous = null
                stationary = 0
            }
            SystemClock.sleep(100)
        }
        return device.findObject(By.desc(sourceChipDescription("NTK"))) != null
    }

    private fun openGenreTab(device: UiDevice, kind: String) {
        freshAccessibility()
        val kindControl = device.wait(Until.findObject(By.text(kind)), TIMEOUT)
        assertNotNull("Missing kind control: $kind", kindControl)
        kindControl.click()
        freshAccessibility()
        val genreTab = device.wait(Until.findObject(By.text("장르")), TIMEOUT)
        assertNotNull("Missing genre tab", genreTab)
        genreTab.click()
        freshAccessibility()
        assertVisible(device, "장르 둘러보기")
    }

    private fun openDedicatedGenre(device: UiDevice, genre: String) {
        repeat(6) {
            freshAccessibility()
            if (device.findObject(By.text(genre)) == null) scrollCatalog(device)
        }
        val choice = settledGenreChoice(device, genre)
        assertNotNull("Missing actual genre: $genre", choice)
        val started = SystemClock.elapsedRealtime()
        requireNotNull(choice).click()
        freshAccessibility()
        assertNotNull(
            "Genre did not navigate to a dedicated screen: $genre",
            device.wait(Until.findObject(By.desc("장르 목록: $genre")), GENRE_NAVIGATION_TIMEOUT),
        )
        assertTrue(
            "Opening a genre screen was delayed: $genre",
            SystemClock.elapsedRealtime() - started <= GENRE_NAVIGATION_TIMEOUT,
        )
    }

    /** A tap during the preceding fling stops scrolling instead of activating the row. */
    private fun settledGenreChoice(device: UiDevice, genre: String): androidx.test.uiautomator.UiObject2? {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var previous: android.graphics.Rect? = null
        var stationary = 0
        while (SystemClock.uptimeMillis() < deadline) {
            freshAccessibility()
            val choice = device.findObject(By.text(genre))
            val bounds = choice?.visibleBounds
            stationary = if (bounds != null && bounds == previous) stationary + 1 else 0
            if (stationary >= 3) return choice
            previous = bounds
            SystemClock.sleep(100)
        }
        return null
    }

    private fun openFirstSeriesAndReturn(device: UiDevice, genre: String) {
        val card = device.wait(Until.findObject(By.descContains("작품:")), CATALOG_TIMEOUT)
        assertNotNull("Genre did not load real works: $genre", card)
        card.click()
        assertVisible(device, "회차")
        device.pressBack()
        assertNotNull(
            "Back did not restore the genre catalog: $genre",
            device.wait(Until.findObject(By.desc("장르 목록: $genre")), TIMEOUT),
        )
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val GENRE_NAVIGATION_TIMEOUT = 1_500L
        const val CATALOG_TIMEOUT = 12_000L
        const val SEARCH_LAYOUT_SETTLE_MILLIS = 1_000L
    }
}
