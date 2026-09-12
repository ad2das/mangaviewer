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

/** Diagnostic: drive the real search UI with a newxtoon provider. */
@RunWith(AndroidJUnit4::class)
class NewxtoonSearchUiDeviceTest {
    @Test fun searchThroughUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(instrumentation.targetContext, MainActivity::class.java),
        )
        val result = JSONObject()
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
            input.text = "로맨스"
            SystemClock.sleep(500)
            val inputBounds = input.visibleBounds
            val submit = device.findObjects(By.text("검색")).mapNotNull(::clickableAncestor)
                .filter { it.visibleBounds.centerY() in inputBounds.top..inputBounds.bottom }
                .single()
            submit.click()
            val resultDeadline = SystemClock.elapsedRealtime() + 30_000
            var settled: LibraryState? = null
            while (SystemClock.elapsedRealtime() < resultDeadline) {
                val current = state()
                if (current.content is LibraryContent.Series || current.content is LibraryContent.Failure) {
                    settled = current
                    break
                }
                SystemClock.sleep(200)
            }
            val content = settled?.content
            result.put("contentType", content?.javaClass?.simpleName)
            when (content) {
                is LibraryContent.Series -> {
                    result.put("count", content.items.size)
                    result.put("titles", content.items.take(5).joinToString(" | ") { it.title })
                }
                is LibraryContent.Failure -> result.put("failure", content.message)
                else -> {}
            }
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "newxtoon-search-ui")
            output.mkdirs()
            device.takeScreenshot(output.resolve("screen.png"))
            device.dumpWindowHierarchy(output.resolve("hierarchy.xml"))
            device.findObjects(By.pkg("ml.melun.mangaview"))
                .mapNotNull { node -> runCatching { node.text }.getOrNull() }
                .take(40)
                .forEachIndexed { index, text -> result.put("text$index", text) }
        } catch (failure: Throwable) {
            result.put("testError", failure.stackTraceToString())
        } finally {
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "newxtoon-search-ui")
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
