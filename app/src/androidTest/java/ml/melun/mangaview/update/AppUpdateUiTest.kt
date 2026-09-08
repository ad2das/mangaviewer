package ml.melun.mangaview.update

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import ml.melun.mangaview.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    @Test fun settingsChecksTheLiveReleaseInsideTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            requireNotNull(device.wait(Until.findObject(By.desc("계정")), 15_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text(Pattern.compile("업데이트 확인|새 업데이트 있음"))), 5_000)).click()
            assertTrue("The live release check did not complete inside the app",
                device.wait(Until.hasObject(By.text(Pattern.compile("최신 버전입니다|새 업데이트가 있습니다"))), 30_000))
            assertEquals(instrumentation.targetContext.packageName, device.currentPackageName)
            assertTrue(device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "app-update-ui.png")))
            requireNotNull(device.findObject(By.text("닫기"))).click()
        }
    }
}
