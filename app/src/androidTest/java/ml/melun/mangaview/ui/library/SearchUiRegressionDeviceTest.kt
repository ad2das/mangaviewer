package ml.melun.mangaview.ui.library

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import ml.melun.mangaview.activity.MainActivity
import ml.melun.mangaview.app.SearchMode
import ml.melun.mangaview.core.SourceId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchUiRegressionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun ntkSurvivalFiltersDetailsAndTabsPreserveSearchResults() = launch { scenario ->
        select(scenario, "ntk")
        typeAndSearch(scenario, "생존")
        await(scenario) { (it.searchContent as? LibraryContent.Series)?.items?.any { series -> series.id.remoteKey == "/manhwa/3648" } == true }
        tap(By.desc("검색 범위: 만화"))
        val comics = await(scenario) { it.searchKind == ml.melun.mangaview.source.SeriesKind.COMIC &&
            (it.searchContent as? LibraryContent.Series)?.items?.let { items ->
                items.isNotEmpty() && items.all { item -> item.id.remoteKey.startsWith("/manhwa/") }
            } == true }
        assertEquals(4, (comics.searchContent as LibraryContent.Series).items.size)
        assertNotNull(find(By.text("생존게임")))
        screenshot("ntk-survival-comic-light")
        tap(By.text("생존게임"))
        await(scenario) { it.activeSeries?.id?.remoteKey == "/manhwa/3648" }
        device.pressBack()
        val back = await(scenario) { it.activeSeries == null }
        assertEquals(comics.searchContent, back.searchContent)
        tap(By.desc("하단 홈"))
        await(scenario) { it.destination == MainDestination.HOME }
        tap(By.desc("하단 검색"))
        await(scenario) { it.destination == MainDestination.SEARCH }
        assertEquals(comics.searchContent, state(scenario).searchContent)
        assertNotNull(find(By.text("생존게임")))
        intent(scenario, LibraryIntent.DarkThemeChanged(true))
        await(scenario) { it.saved.settings.darkTheme }
        screenshot("ntk-survival-comic-dark")
        tap(By.desc("검색어 지우기"))
        await(scenario) { it.query.isEmpty() && it.searchContent == LibraryContent.Empty }
        assertNull(device.findObject(By.text("생존게임")))
    }

    @Test fun eachProviderCanSearchThroughTheUiWithOnlySupportedControls() = launch { scenario ->
        for (provider in listOf("ntk", "wfwf", "newxtoon", "goodtoon")) {
            select(scenario, provider)
            typeAndSearch(scenario, "화산귀환")
            val settled = await(scenario) { it.submittedQuery == "화산귀환" && it.searchContent is LibraryContent.Series }
            assertTrue("$provider known title missing", (settled.searchContent as LibraryContent.Series).items.any { it.title.contains("화산귀환") })
            val mode = settled.sources.first { it.id.value == provider }.searchMode
            if (mode != SearchMode.FIELDS) assertNull(device.findObject(By.desc("검색 항목: 작가")))
            assertNotNull(find(By.text("화산귀환")))
            screenshot("$provider-title-search")
        }
    }

    @Test fun invalidAndAbsentQueriesHaveDifferentStatesAndCanRecover() = launch { scenario ->
        select(scenario, "newxtoon")
        typeAndSearch(scenario, "나")
        await(scenario) { it.searchContent is LibraryContent.Failure }
        assertNotNull(find(By.textContains("2~100자")))
        screenshot("newxtoon-short-query")
        typeAndSearch(scenario, "mv_no_match_20260917_8fd7")
        await(scenario) { (it.searchContent as? LibraryContent.Series)?.items?.isEmpty() == true }
        assertNotNull(find(By.text("검색 결과가 없습니다")))
        typeAndSearch(scenario, "화산귀환")
        await(scenario) { (it.searchContent as? LibraryContent.Series)?.items?.isNotEmpty() == true }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, provider: String) {
        intent(scenario, LibraryIntent.DestinationSelected(MainDestination.SEARCH))
        intent(scenario, LibraryIntent.QueryChanged(""))
        intent(scenario, LibraryIntent.SearchKindSelected(null))
        intent(scenario, LibraryIntent.SourceSelected(SourceId(provider)))
        await(scenario) { it.selectedSourceId.value == provider && it.destination == MainDestination.SEARCH }
        assertNotNull("$provider query reset must be rendered before typing",
            find(By.clazz("android.widget.EditText").text("")))
    }

    private fun typeAndSearch(scenario: ActivityScenario<MainActivity>, query: String) {
        find(By.clazz("android.widget.EditText"))!!.text = query
        await(scenario) { it.query == query }
        tap(By.desc("검색 실행"))
    }

    // Compose may reuse virtual node IDs across source/tab transitions. Refresh the test
    // automation cache before looking up a node, otherwise the previous field text survives.
    private fun find(selector: BySelector): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            device.findObject(selector)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun tap(selector: BySelector) {
        repeat(3) {
            try { requireNotNull(find(selector)).click(); return }
            catch (_: StaleObjectException) { /* Retry only a replaced accessibility node. */ }
        }
        error("UI node kept changing: $selector")
    }

    private fun launch(block: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java))
        val previous = state(scenario).saved.settings.darkTheme
        try {
            dismissAutomaticUpdateNotice(device)
            intent(scenario, LibraryIntent.DarkThemeChanged(false))
            block(scenario)
        } catch (failure: Throwable) {
            screenshot("failure-${System.currentTimeMillis()}")
            throw failure
        } finally {
            intent(scenario, LibraryIntent.DarkThemeChanged(previous))
            scenario.close()
        }
    }

    private fun intent(scenario: ActivityScenario<MainActivity>, intent: LibraryIntent) = scenario.onActivity {
        ViewModelProvider(it)[LibraryViewModel::class.java].accept(intent)
    }

    private fun state(scenario: ActivityScenario<MainActivity>): LibraryState {
        lateinit var state: LibraryState
        scenario.onActivity { state = ViewModelProvider(it)[LibraryViewModel::class.java].state.value }
        return state
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (LibraryState) -> Boolean): LibraryState {
        val deadline = SystemClock.uptimeMillis() + 40_000
        do {
            val current = state(scenario)
            if (condition(current)) {
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.clearCache()
                return current
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        val state = state(scenario)
        error("${state.selectedSourceId.value} search UI did not settle for '${state.query}': ${state.searchContent}")
    }

    private fun screenshot(name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "search-ui-evidence").apply { mkdirs() }
        device.waitForIdle(500)
        instrumentation.uiAutomation.clearCache()
        device.takeScreenshot(directory.resolve("$name.png"))
        device.dumpWindowHierarchy(directory.resolve("$name.xml"))
    }
}
