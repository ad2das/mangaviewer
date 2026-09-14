package ml.melun.mangaview.ui.library

import android.content.Intent
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import ml.melun.mangaview.activity.MainActivity
import ml.melun.mangaview.viewer.ViewerWindowFrameRecorder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Bounded P1 library measurement capture.
 *
 * S0 is the natural first cover load (no injected input). S1/S2 are steady forward/reverse and
 * S3 is a reversal oscillation; every measured stage accumulates at least 1000 frames from the
 * unchanged viewer FrameMetrics recorder. Raw injected MotionEvents are recorded with both the
 * uptime event time and the nanoTime send instant, list identity/positions are snapshotted, and
 * gfxinfo is dumped once per stage (never every 100 frames). Evidence counts fail closed: the
 * test still writes all artifacts, then fails so the collector cannot report success.
 *
 * The capture directory `library-qualification-<millis>` is pulled by
 * `.artifacts/scroll-performance-20260912/workers/library-qualification-collect.py`, which owns
 * device backup/restore and the optional trace. This test performs no trace processing and no
 * heavy host work.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScrollQualificationTest {
    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "library-qualification-failure-${System.nanoTime()}",
            ).apply { mkdirs() }
            val device = UiDevice.getInstance(instrumentation)
            if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            device.dumpWindowHierarchy(directory.resolve("hierarchy.xml"))
            device.takeScreenshot(directory.resolve("screen.png"))
            directory.resolve("error.txt").writeText("test=${description.methodName}\n$error")
        }
    }

    private data class StageOutcome(
        val name: String,
        val kind: String,
        val setupGestures: Int,
        val gestures: Int,
        val moveEvents: Int,
        val startFrame: Int,
        val endFrame: Int,
        val firstDownSentNanos: Long,
        val lastUpSentNanos: Long,
        val snapshots: JSONArray,
        val distinctStates: Int,
        val extentExhausted: Boolean,
        val budgetExhausted: Boolean,
    )

    @Test
    fun stagedLibraryScrollCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val ui = instrumentation.uiAutomation
        val arguments = InstrumentationRegistry.getArguments()
        val expectedSource = arguments.getString("librarySource") ?: "ntk"
        val expectedChip = requireNotNull(SOURCE_CHIPS[expectedSource]) { "unknown librarySource $expectedSource" }
        val expectedTab = arguments.getString("libraryTab") ?: "POPULAR"
        require(expectedTab == "POPULAR") {
            "only the POPULAR home tab is implemented in this capture; requested $expectedTab"
        }
        val output = File(context.getExternalFilesDir(null), "library-qualification-${System.currentTimeMillis()}")
            .apply { check(mkdirs()) { "cannot create capture directory $this" } }
        val startedAtEpochMillis = System.currentTimeMillis()
        val startedElapsedMillis = SystemClock.elapsedRealtime()
        val inputLines = mutableListOf<String>()
        val failures = mutableListOf<String>()

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val homeNav = device.wait(Until.findObject(By.desc("하단 홈")), READY_TIMEOUT)
        if (homeNav == null) failures += "bottom home navigation never appeared"
        val activity = awaitResumedMainActivity()
        val recorderStartedAtNanos = System.nanoTime()
        val recorder = ViewerWindowFrameRecorder(activity.window)

        fun freshAccessibility() {
            if (android.os.Build.VERSION.SDK_INT >= 34) ui.clearCache()
        }

        fun shell(command: String): String = device.executeShellCommand(command)

        fun cards(): List<Pair<String, Rect>> {
            freshAccessibility()
            return device.findObjects(By.descContains("작품:"))
                .mapNotNull { node -> node.contentDescription?.let { it to node.visibleBounds } }
                .sortedWith(compareBy({ it.second.top }, { it.second.left }))
                .take(MAX_SNAPSHOT_CARDS)
        }

        fun cardKey(list: List<Pair<String, Rect>>): String = list.joinToString("|") { it.first }

        fun gridRowsSeen(list: List<Pair<String, Rect>>): Boolean =
            list.map { it.second.top }.distinct().size >= 2

        fun snapshotJson(atGesture: Int, list: List<Pair<String, Rect>>): JSONObject = JSONObject().apply {
            put("atGesture", atGesture)
            put("cards", JSONArray().apply { list.forEach { put(it.first) } })
            put("top", list.firstOrNull()?.second?.top ?: -1)
            put("bottom", list.lastOrNull()?.second?.bottom ?: -1)
        }

        fun dismissUpdateDialog() {
            repeat(2) {
                val close = device.findObject(By.text("닫기")) ?: return@repeat
                close.click()
                SystemClock.sleep(300)
            }
        }

        fun ensureSource(chip: String) {
            val labels = SOURCE_CHIPS.values.toList()

            fun currentSelector(): UiObject2? {
                freshAccessibility()
                for (label in labels) {
                    device.findObject(By.desc(label))?.let { return it }
                }
                return null
            }

            freshAccessibility()
            if (device.findObject(By.desc(chip)) != null) return
            val current = currentSelector()
            if (current == null) {
                failures += "no source selector is visible"
                return
            }
            current.click()
            if (device.wait(Until.findObject(By.text("사이트 선택")), READY_TIMEOUT) == null) {
                failures += "source picker did not open"
                return
            }
            val target = device.wait(Until.findObject(By.text(chip)), READY_TIMEOUT)
            if (target == null) {
                failures += "source $chip is missing from the picker"
                return
            }
            target.click()
            if (device.wait(Until.findObject(By.desc(chip)), READY_TIMEOUT) == null) {
                failures += "source $chip was not selected"
                return
            }
            freshAccessibility()
            if (device.findObject(By.text("사이트 선택")) != null) {
                failures += "source picker overlay remained open after selecting $chip"
            }
            freshAccessibility()
            val visible = labels.filter { device.findObject(By.desc(it)) != null }
            if (visible != listOf(chip)) {
                failures += "active selector is $visible instead of [$chip]"
            }
        }

        fun selectPopularTab() {
            repeat(3) {
                dismissUpdateDialog()
                device.findObject(By.text("인기"))?.click()
                SystemClock.sleep(400)
                dismissUpdateDialog()
                if (gridRowsSeen(cards())) return
                SystemClock.sleep(1000)
            }
            failures += "the popular grid never loaded"
        }

        fun injectGesture(stage: String, gesture: Int, forward: Boolean, setup: Boolean): Pair<Long, Long> {
            val width = device.displayWidth.toFloat()
            val height = device.displayHeight.toFloat()
            val x = width / 2f
            val y1 = if (forward) height * 0.80f else height * 0.30f
            val y2 = if (forward) height * 0.30f else height * 0.80f
            val downTime = SystemClock.uptimeMillis()
            var firstDownSent = 0L
            var lastSent = 0L

            fun send(action: Int, y: Float): Long {
                val eventTime = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                val sent = System.nanoTime()
                check(ui.injectInputEvent(event, true)) { "injectInputEvent rejected action=$action" }
                event.recycle()
                lastSent = sent
                inputLines += JSONObject().apply {
                    put("stage", stage)
                    put("gesture", gesture)
                    put("setup", setup)
                    put("action", action)
                    put("downTimeMs", downTime)
                    put("eventTimeMs", eventTime)
                    put("x", x.toDouble())
                    put("y", y.toDouble())
                    put("sentNanos", sent)
                }.toString()
                return sent
            }

            firstDownSent = send(MotionEvent.ACTION_DOWN, y1)
            for (step in 1..STEADY_MOVE_STEPS) {
                SystemClock.sleep(STEP_INTERVAL_MILLIS)
                send(MotionEvent.ACTION_MOVE, y1 + (y2 - y1) * step / STEADY_MOVE_STEPS)
            }
            SystemClock.sleep(STEP_INTERVAL_MILLIS)
            send(MotionEvent.ACTION_UP, y2)
            SystemClock.sleep(GAP_AFTER_UP_MILLIS)
            return firstDownSent to lastSent
        }

        fun driveStage(
            name: String,
            kind: String,
            direction: () -> Boolean,
            setupGestures: Int = 0,
            minimumDirectionChanges: Int = 0,
            directionChangeTracker: (() -> Int)? = null,
            detectExtent: Boolean = true,
        ): StageOutcome {
            var setupIndex = 0
            repeat(setupGestures) {
                injectGesture(name, setupIndex, forward = true, setup = true)
                setupIndex += 1
            }
            val startFrame = recorder.snapshot().size / 2
            var gesture = 0
            var moveEvents = 0
            var firstDownSent = 0L
            var lastUpSent = 0L
            var extentExhausted = false
            var budgetExhausted = false
            var distinctStates = 0
            var lastKey = ""
            val keys = mutableListOf<String>()
            val snapshots = JSONArray()
            val deadline = SystemClock.elapsedRealtime() + STAGE_BUDGET_MILLIS
            while (true) {
                if (gesture % SNAPSHOT_EVERY_GESTURES == 0) {
                    val list = cards()
                    snapshots.put(snapshotJson(gesture, list))
                    val key = cardKey(list)
                    keys += key
                    if (key != lastKey) distinctStates += 1
                    lastKey = key
                }
                val frames = recorder.snapshot().size / 2 - startFrame
                val changes = directionChangeTracker?.invoke() ?: 0
                if (frames >= MIN_STAGE_FRAMES && changes >= minimumDirectionChanges) break
                if (gesture >= MAX_STAGE_GESTURES || SystemClock.elapsedRealtime() >= deadline) {
                    budgetExhausted = true
                    break
                }
                if (detectExtent && gesture >= EXTENT_CHECK_GESTURES && gesture % EXTENT_CHECK_GESTURES == 0 &&
                    keys.size >= 2 && keys[keys.size - 1] == keys[keys.size - 2]
                ) {
                    extentExhausted = true
                    break
                }
                val sent = injectGesture(name, gesture, direction(), setup = false)
                if (firstDownSent == 0L) firstDownSent = sent.first
                lastUpSent = sent.second
                moveEvents += STEADY_MOVE_STEPS + 1
                gesture += 1
            }
            return StageOutcome(
                name = name,
                kind = kind,
                setupGestures = setupGestures,
                gestures = gesture,
                moveEvents = moveEvents,
                startFrame = startFrame,
                endFrame = recorder.snapshot().size / 2,
                firstDownSentNanos = firstDownSent,
                lastUpSentNanos = lastUpSent,
                snapshots = snapshots,
                distinctStates = distinctStates,
                extentExhausted = extentExhausted,
                budgetExhausted = budgetExhausted,
            )
        }

        fun finishStage(outcome: StageOutcome): JSONObject {
            val frames = outcome.endFrame - outcome.startFrame
            File(output, "gfxinfo-${outcome.name}-summary.txt").writeText(shell("dumpsys gfxinfo $PACKAGE"))
            File(output, "gfxinfo-${outcome.name}-framestats.txt").writeText(shell("dumpsys gfxinfo $PACKAGE framestats"))
            if (frames < MIN_STAGE_FRAMES) failures += "${outcome.name}: $frames frames < $MIN_STAGE_FRAMES"
            if (outcome.distinctStates < 2) failures += "${outcome.name}: list movement not observed"
            if (outcome.extentExhausted) failures += "${outcome.name}: list extent exhausted before the frame minimum"
            if (outcome.budgetExhausted) failures += "${outcome.name}: stage budget exhausted"
            if (outcome.moveEvents == 0) failures += "${outcome.name}: no injected motion events"
            return JSONObject().apply {
                put("name", outcome.name)
                put("kind", outcome.kind)
                put("setupGestures", outcome.setupGestures)
                put("gestures", outcome.gestures)
                put("moveEvents", outcome.moveEvents)
                put("startFrame", outcome.startFrame)
                put("endFrame", outcome.endFrame)
                put("frames", frames)
                put("injectionWindow", JSONObject().apply {
                    put("firstDownSentNanos", outcome.firstDownSentNanos)
                    put("lastUpSentNanos", outcome.lastUpSentNanos)
                })
                put("snapshotDistinctStates", outcome.distinctStates)
                put("extentExhausted", outcome.extentExhausted)
                put("budgetExhausted", outcome.budgetExhausted)
                put("snapshots", outcome.snapshots)
                put("gfxinfoSummary", "gfxinfo-${outcome.name}-summary.txt")
                put("gfxinfoFramestats", "gfxinfo-${outcome.name}-framestats.txt")
            }
        }

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val identity = JSONObject()
        val milestones = JSONObject()
        var primaryFailure: Throwable? = null
        try {
            homeNav?.click()
            dismissUpdateDialog()
            ensureSource(expectedChip)
            selectPopularTab()

            val firstCard = device.wait(Until.findObject(By.descContains("작품:")), CATALOG_TIMEOUT)
            if (firstCard == null) failures += "no 작품 card became visible"
            val firstCardVisibleAtNanos = System.nanoTime()
            val firstCardDescription = firstCard?.contentDescription ?: ""
            val framesAtFirstCard = recorder.snapshot().size / 2
            milestones.put("recorderStartedAtNanos", recorderStartedAtNanos)
            milestones.put("firstCardVisibleAtNanos", firstCardVisibleAtNanos)
            milestones.put("firstCardDescription", firstCardDescription)
            milestones.put("firstCardFrameIndex", framesAtFirstCard)
            milestones.put(
                "startupProxyMillis",
                (firstCardVisibleAtNanos - recorderStartedAtNanos) / 1_000_000.0,
            )

            val identityCards = cards()
            identity.put("sourceChip", expectedChip)
            identity.put("expectedTab", expectedTab)
            identity.put("visibleCards", identityCards.size)
            identity.put("orderedCards", JSONArray().apply { identityCards.forEach { put(it.first) } })
            identity.put("orderedCardSha256", sha256(identityCards.joinToString("\n") { it.first }))
            if (identityCards.isEmpty()) failures += "identity capture saw no cards"
            if (!gridRowsSeen(identityCards)) failures += "identity capture is not a multi-row series grid"

            SystemClock.sleep(S0_SETTLE_MILLIS)
            val s0EndFrame = recorder.snapshot().size / 2

            shell("dumpsys gfxinfo $PACKAGE reset")

            val s1 = finishStage(driveStage("S1_STEADY_FORWARD", "STEADY_FORWARD", { true }))
            val s2 = finishStage(driveStage("S2_STEADY_REVERSE", "STEADY_REVERSE", { false }))

            var oscillationBlock = 0
            fun oscillationDirection(): Boolean {
                val forward = (oscillationBlock / OSCILLATION_BLOCK_GESTURES) % 2 == 0
                oscillationBlock += 1
                return forward
            }
            val s3 = finishStage(
                driveStage(
                    "S3_REVERSAL_OSCILLATION",
                    "REVERSAL_OSCILLATION",
                    { oscillationDirection() },
                    setupGestures = OSCILLATION_SETUP_GESTURES,
                    minimumDirectionChanges = MIN_OSCILLATION_DIRECTION_CHANGES,
                    directionChangeTracker = { oscillationBlock / OSCILLATION_BLOCK_GESTURES },
                    detectExtent = false,
                ),
            )

            SystemClock.sleep(STOPPED_SETTLE_MILLIS)
            val dropped = recorder.droppedReportCount()
            val packed = recorder.snapshot()
            if (dropped != 0) failures += "viewer frame recorder dropped $dropped samples"

            File(output, "frames.jsonl").bufferedWriter().use { writer ->
                var index = 0
                while (index * 2 + 1 < packed.size) {
                    writer.append("{\"index\":").append(index.toString())
                        .append(",\"intendedVsyncNanos\":").append(packed[index * 2].toString())
                        .append(",\"totalDurationNanos\":").append(packed[index * 2 + 1].toString())
                        .append("}\n")
                    index += 1
                }
            }
            File(output, "input-events.jsonl").writeText(
                inputLines.joinToString(separator = "\n", postfix = if (inputLines.isEmpty()) "" else "\n"),
            )

            val stages = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "S0_INITIAL_COVER_LOAD")
                    put("kind", "NATURAL_STARTUP")
                    put("setupGestures", 0)
                    put("gestures", 0)
                    put("moveEvents", 0)
                    put("startFrame", 0)
                    put("endFrame", s0EndFrame)
                    put("frames", s0EndFrame)
                    put("snapshotDistinctStates", 0)
                    put("extentExhausted", false)
                    put("budgetExhausted", false)
                    put("snapshots", JSONArray())
                })
                put(s1)
                put(s2)
                put(s3)
            }

            val summary = JSONObject().apply {
                put("scope", "LIBRARY_SCROLL_QUALIFICATION_P1")
                put("schemaVersion", SCHEMA_VERSION)
                put("state", if (failures.isEmpty()) "COMPLETE" else "FAILED")
                put("package", PACKAGE)
                put("processPid", Process.myPid())
                put("startedAtEpochMillis", startedAtEpochMillis)
                put("finishedAtEpochMillis", System.currentTimeMillis())
                put("startedElapsedMillis", startedElapsedMillis)
                put("instrumentationArguments", JSONObject().apply {
                    put("librarySource", expectedSource)
                    put("libraryTab", expectedTab)
                })
                put("identity", identity)
                put("milestones", milestones)
                put("recorder", JSONObject().apply {
                    put("samples", packed.size / 2)
                    put("droppedReportCount", dropped)
                })
                put("stages", stages)
                put("cache", JSONObject().apply {
                    put("testClearedAppData", false)
                    put("testClearedArtworkCache", false)
                    put("testOpenedDatabase", false)
                    put("collectorForceStoppedBeforeRun", true)
                })
                put("failures", JSONArray().apply { failures.forEach { put(it) } })
            }
            File(output, "summary.json").writeText(summary.toString(2))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                recorder.close()
            } catch (cleanup: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
            if (failures.isNotEmpty()) {
                val evidenceFailure = AssertionError("library qualification evidence failures: $failures")
                val primary = primaryFailure
                if (primary == null) {
                    throw evidenceFailure
                } else if (primary !== evidenceFailure) {
                    primary.addSuppressed(evidenceFailure)
                }
            }
        }
    }

    private fun awaitResumedMainActivity(): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + ACTIVITY_TIMEOUT
        while (SystemClock.elapsedRealtime() < deadline) {
            var resumed: MainActivity? = null
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .singleOrNull()
            }
            resumed?.let { return it }
            SystemClock.sleep(100)
        }
        error("MainActivity never became resumed")
    }

    private companion object {
        const val PACKAGE = "ml.melun.mangaview"
        const val SCHEMA_VERSION = 1
        const val READY_TIMEOUT = 20_000L
        const val CATALOG_TIMEOUT = 20_000L
        const val ACTIVITY_TIMEOUT = 15_000L
        const val MIN_STAGE_FRAMES = 1000
        const val MAX_STAGE_GESTURES = 200
        const val STAGE_BUDGET_MILLIS = 75_000L
        const val SNAPSHOT_EVERY_GESTURES = 6
        const val EXTENT_CHECK_GESTURES = 12
        const val STEADY_MOVE_STEPS = 30
        const val STEP_INTERVAL_MILLIS = 16L
        const val GAP_AFTER_UP_MILLIS = 40L
        const val OSCILLATION_BLOCK_GESTURES = 6
        const val OSCILLATION_SETUP_GESTURES = 12
        const val MIN_OSCILLATION_DIRECTION_CHANGES = 4
        const val MAX_SNAPSHOT_CARDS = 40
        const val S0_SETTLE_MILLIS = 500L
        const val STOPPED_SETTLE_MILLIS = 400L
        val SOURCE_CHIPS = mapOf("ntk" to "NTK", "wfwf" to "WFWF", "newxtoon" to "뉴엑스툰")
    }
}
