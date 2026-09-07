package ml.melun.mangaview.viewer

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ml.melun.mangaview.activity.MainActivity
import ml.melun.mangaview.activity.ViewerActivity
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceEpisode
import ml.melun.mangaview.source.SourceSeries
import ml.melun.mangaview.ui.library.LibraryContent
import ml.melun.mangaview.ui.library.LibraryState
import ml.melun.mangaview.ui.library.LibraryViewModel
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec

/** Navigation invokes only UI actions. State reads verify identity; they never dispatch intents. */
internal class CorpusUiEntry(
    private val instrumentation: Instrumentation,
    timingOutput: java.io.File? = null,
    navigationIdleMillis: Long? = null,
    private val asynchronousNavigationMoves: Boolean = false,
) : AutoCloseable {
    private val timing = CorpusUiTiming(timingOutput).apply { mark("navigation-start") }
    private val configurator = Configurator.getInstance()
    private val originalIdleMillis = configurator.waitForIdleTimeout
    private val idleOverride = navigationIdleMillis?.also {
        require(it in 0L..10_000L)
    }
    private val device = UiDevice.getInstance(instrumentation)
    private val library = ActivityScenario.launch<MainActivity>(
        Intent(instrumentation.targetContext, MainActivity::class.java),
    )
    init {
        idleOverride?.let { configurator.waitForIdleTimeout = it }
        timing.mark("library-launched-idle-${configurator.waitForIdleTimeout}-original-$originalIdleMillis")
    }
    private var viewer: ViewerActivity? = null

    fun prepare(sample: CorpusSeriesSample, beforeOpeningSeries: () -> Unit = {}) =
        prepare(sample.kind, sample.series, sample.chain.first(), beforeOpeningSeries)

    fun prepare(regression: ResolvedSingleEpisodeRegression, beforeOpeningSeries: () -> Unit = {}) =
        prepare(regression.kind, regression.series, regression.episode, beforeOpeningSeries)

    /** Shared UI entry keeps single regressions on the same identity-checked path as corpus samples. */
    fun prepare(
        kind: SeriesKind,
        series: SourceSeries,
        episode: SourceEpisode,
        beforeOpeningSeries: () -> Unit = {},
    ) {
        await { it.sources.isNotEmpty() }
        timing.mark("sources-ready")
        repeat(state().sources.size) {
            val current = state()
            if (current.selectedSourceId == series.id.sourceId) return@repeat
            val label = current.sources.single { it.id == current.selectedSourceId }.label
            requireNotNull(device.wait(Until.findObject(By.desc(label)), 5_000)).click()
        }
        check(state().selectedSourceId == series.id.sourceId) { "UI source selection failed" }
        timing.mark("source-selected")
        requireNotNull(device.wait(Until.findObject(By.desc("하단 검색")), 5_000)).click()
        val desiredKind = if (kind == SeriesKind.COMIC) "만화" else "웹툰"
        repeat(3) {
            val node = requireNotNull(device.wait(Until.findObject(By.descStartsWith("검색 범위:")), 5_000))
            if (node.contentDescription != "검색 범위: $desiredKind") node.click()
        }
        val field = requireNotNull(device.wait(Until.findObject(By.descStartsWith("검색 항목:")), 5_000))
        if (field.contentDescription != "검색 항목: 제목") field.click()
        val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000))
        input.text = series.title
        timing.mark("search-form-filled")
        await { it.query == series.title }
        val inputBounds = input.visibleBounds
        val submit = device.findObjects(By.text("검색")).mapNotNull(::clickableAncestor)
            .filter { it.visibleBounds.centerY() in inputBounds.top..inputBounds.bottom }
            .single()
        submit.click()
        device.pressBack()
        await { it.content is LibraryContent.Series || it.content is LibraryContent.Failure }
        timing.mark("search-results-ready")
        val found = (state().content as? LibraryContent.Series)?.items ?: error("UI search failed: ${state().content}")
        check(found.count { it.title == series.title } == 1 && found.any { it.id == series.id }) {
            "UI search cannot uniquely identify sampled series ${series.id}; results=${found.size}; " +
                "titleMatches=${found.filter { it.title == series.title }.map { it.id }}; " +
                "identityTitles=${found.filter { it.id == series.id }.map { it.title }}"
        }
        // Measure while still on search results: the selected episode's detail warmer has not started.
        beforeOpeningSeries()
        findTextInList(series.title).let { requireNotNull(clickableAncestor(it)).click() }
        await { it.content is LibraryContent.Episodes || it.content is LibraryContent.Failure }
        timing.mark("episode-list-ready")
        val detail = state().content as? LibraryContent.Episodes ?: error("UI episode list failed")
        check(detail.series.id == series.id)
        check(detail.items.count { it.title == episode.title } == 1 && detail.items.any { it.id == episode.id }) {
            "UI episode list cannot uniquely identify sampled episode ${episode.id}; " +
                "titleMatches=${detail.items.filter { it.title == episode.title }.map { it.id }}; " +
                "identityTitles=${detail.items.filter { it.id == episode.id }.map { it.title }}"
        }
        requireNotNull(device.wait(Until.findObject(By.text("회차")), 5_000)).let {
            requireNotNull(clickableAncestor(it)).click()
        }
        findTextInList(episode.title)
        timing.mark("episode-row-ready")
    }

    fun open(sample: CorpusSeriesSample): ViewerUiLaunch =
        open(sample.series, sample.chain.first())

    fun open(regression: ResolvedSingleEpisodeRegression): ViewerUiLaunch =
        open(regression.series, regression.episode)

    fun open(series: SourceSeries, expected: SourceEpisode): ViewerUiLaunch {
        check(expected.id.seriesId == series.id)
        val episode = findTextInList(expected.title)
        timing.mark("episode-row-rechecked")
        restoreIdleTimeout()
        val startedMillis = SystemClock.elapsedRealtime()
        val startedNanos = System.nanoTime()
        requireNotNull(clickableAncestor(episode)).click()
        check(device.wait(Condition<UiDevice, Boolean> {
            instrumentation.runOnMainSync {
                viewer = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ViewerActivity>().singleOrNull()
            }
            viewer != null
        }, 5_000) == true) { "Real episode tap did not open viewer" }
        val opened = requireNotNull(viewer)
        val spec = ViewerLaunchSpec.from(opened.intent)
        check(spec.episodeId == expected.id) { "UI opened ${spec.episodeId} instead of ${expected.id}" }
        timing.mark("viewer-opened")
        return ViewerUiLaunch(opened, startedMillis, startedNanos)
    }

    fun closeViewer() {
        viewer?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
        instrumentation.waitForIdleSync()
        viewer = null
    }

    fun assertSeriesContext(series: SourceSeries) {
        val current = state()
        check(current.selectedSourceId == series.id.sourceId && current.activeSeries?.id == series.id) {
            "Catalog series context changed while the viewer was open"
        }
    }

    fun returnToSearch() {
        check(viewer == null)
        if (state().activeSeries != null) device.pressBack()
        await { it.activeSeries == null && it.content is LibraryContent.Series }
    }

    override fun close() {
        try {
            closeViewer()
            library.close()
        } finally {
            restoreIdleTimeout()
            timing.mark("navigation-closed")
            timing.save()
        }
    }

    private fun restoreIdleTimeout() {
        if (idleOverride != null) configurator.waitForIdleTimeout = originalIdleMillis
    }

    private fun state(): LibraryState {
        lateinit var current: LibraryState
        library.onActivity { current = ViewModelProvider(it)[LibraryViewModel::class.java].state.value }
        return current
    }

    private fun await(predicate: (LibraryState) -> Boolean) {
        check(device.wait(Condition<UiDevice, Boolean> { predicate(state()) }, 30_000) == true) {
            "Library UI did not reach required navigation state: ${state().content}"
        }
    }

    private fun findTextInList(title: String): UiObject2 {
        val exactRow = Regex("(?s)${Regex.escape(title)}(?:\\n.*)?")
        var previousRows: List<String>? = null
        var unchanged = 0
        for (gesture in 0 until 2_000) {
            try {
            timing.measure("find-target-nodes") { device.findObjects(By.textContains(title)) }.firstOrNull {
                it.className != "android.widget.EditText" && exactRow.matches(it.text.orEmpty()) && clickableAncestor(it) != null
            }?.let { return it }
            val rows = timing.measure("read-visible-rows") {
                device.findObjects(By.pkg(instrumentation.targetContext.packageName)
                    .clazz("android.widget.TextView")).map { "${it.text}:${it.visibleBounds}" }
            }
            unchanged = if (rows == previousRows) unchanged + 1 else 0
            if (unchanged >= 3) break
            previousRows = rows
            val list = timing.measure("find-scroll-container") {
                device.findObjects(By.scrollable(true)).singleOrNull()
            } ?: break
            val bounds = list.visibleBounds
            check(timing.measure("navigation-swipe") {
                if (asynchronousNavigationMoves) injectCorpusUiSwipe(instrumentation, bounds)
                else device.swipe(bounds.centerX(), bounds.bottom - bounds.height() / 5,
                    bounds.centerX(), bounds.top + bounds.height() / 5, 55)
            }) {
                "Library navigation swipe could not be injected"
            }
            } catch (_: StaleObjectException) {
                // LazyColumn can replace accessibility nodes during a swipe. Re-read the
                // hierarchy before deciding whether to issue another navigation gesture.
                continue
            }
        }
        val evidence = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ux-evidence"))
            .resolve("navigation-failure-${System.nanoTime()}").apply { check(mkdirs()) }
        device.dumpWindowHierarchy(evidence.resolve("hierarchy.xml"))
        device.takeScreenshot(evidence.resolve("screen.png"))
        error("Sampled UI row is unreachable: $title; evidence=$evidence")
    }

    private fun clickableAncestor(node: UiObject2): UiObject2? {
        var current: UiObject2? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }
}

internal data class ViewerUiLaunch(val activity: ViewerActivity, val startedMillis: Long, val startedNanos: Long)
