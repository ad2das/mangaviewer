package ml.melun.mangaview.app

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import ml.melun.mangaview.activity.MainActivity
import ml.melun.mangaview.ui.library.LibraryContent
import ml.melun.mangaview.ui.library.LibraryState
import ml.melun.mangaview.ui.library.LibraryViewModel
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: open a newxtoon series detail and record the rendered status badge/authors. */
@RunWith(AndroidJUnit4::class)
class NewxtoonDetailStatusUiDeviceTest {
    @Test fun detailShowsStatusBadge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(instrumentation.targetContext, MainActivity::class.java),
        )
        val result = JSONObject()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "newxtoon-detail-ui")
        try {
            ml.melun.mangaview.ui.library.dismissAutomaticUpdateNotice(device)
            fun state(): LibraryState {
                lateinit var current: LibraryState
                scenario.onActivity { current = ViewModelProvider(it)[LibraryViewModel::class.java].state.value }
                return current
            }
            val targetLabel = "뉴엑스툰"
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val shown = state().sources.map { it.label }.firstOrNull { device.findObject(By.desc(it)) != null }
                if (shown == targetLabel) break
                val chip = shown?.let { device.findObject(By.desc(it)) } ?: break
                (clickableAncestor(chip) ?: chip).click()
                SystemClock.sleep(500)
            }
            result.put("selectedSource", state().selectedSourceId.value)
            requireNotNull(device.wait(Until.findObject(By.desc("하단 검색")), 5_000)).click()
            val input = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000))
            input.text = "선생님과 산다"
            SystemClock.sleep(500)
            val inputBounds = input.visibleBounds
            val submit = device.findObjects(By.text("검색")).mapNotNull(::clickableAncestor)
                .filter { it.visibleBounds.centerY() in inputBounds.top..inputBounds.bottom }
                .single()
            submit.click()
            val searchDeadline = SystemClock.elapsedRealtime() + 30_000
            var searched: LibraryState? = null
            while (SystemClock.elapsedRealtime() < searchDeadline) {
                val current = state()
                if (current.content is LibraryContent.Series || current.content is LibraryContent.Failure) {
                    searched = current
                    break
                }
                SystemClock.sleep(200)
            }
            val content = searched?.content
            if (content !is LibraryContent.Series || content.items.isEmpty()) {
                result.put("searchOutcome", content?.javaClass?.simpleName ?: "unknown")
                if (content is LibraryContent.Failure) result.put("searchFailure", content.message)
                return
            }
            result.put("searchCount", content.items.size)
            val card = requireNotNull(device.wait(Until.findObject(By.text("선생님과 산다!")), 5_000))
            (clickableAncestor(card) ?: card).click()
            val detailDeadline = SystemClock.elapsedRealtime() + 30_000
            var detailed: LibraryState? = null
            while (SystemClock.elapsedRealtime() < detailDeadline) {
                val current = state()
                if (current.content is LibraryContent.Episodes) {
                    detailed = current
                    if (current.activeSeriesDetails != null) break
                }
                SystemClock.sleep(200)
            }
            val state = detailed ?: state()
            result.put("detailContent", state.content.javaClass.simpleName)
            result.put("status", state.activeSeriesDetails?.status?.name ?: "null")
            result.put("authors", state.activeSeriesDetails?.authors ?: "null")
            result.put("descriptionPresent", state.activeSeriesDetails?.description?.isNotBlank() == true)
            SystemClock.sleep(1_500)
            output.mkdirs()
            device.takeScreenshot(output.resolve("detail.png"))
            device.dumpWindowHierarchy(output.resolve("detail.xml"))
            val texts = device.findObjects(By.pkg("ml.melun.mangaview"))
                .mapNotNull { node -> runCatching { node.text }.getOrNull() }
            result.put("badgeVisible", texts.any { it == "연재중" || it == "완결" })
            result.put("authorVisible", texts.any { it.contains("평형") })
            result.put("sampleTexts", texts.filter(String::isNotBlank).take(30).joinToString(" | "))
        } catch (failure: Throwable) {
            result.put("testError", failure.stackTraceToString())
        } finally {
            output.mkdirs()
            output.resolve("result.json").writeText(result.toString(2))
            scenario.close()
        }
    }

    private fun clickableAncestor(node: UiObject2): UiObject2? {
        var current: UiObject2? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }
}
