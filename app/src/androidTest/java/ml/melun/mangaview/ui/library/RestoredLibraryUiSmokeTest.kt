package ml.melun.mangaview.ui.library

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ml.melun.mangaview.activity.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoredLibraryUiSmokeTest {
    @Test
    fun oldNavigationAndPrimaryScreensRemainVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        assertVisible(device, "MangaView")
        assertVisible(device, "읽던 작품으로 바로 이동")
        assertVisible(device, "웹툰")
        assertVisible(device, "만화")

        device.wait(Until.findObject(By.desc("하단 검색")), TIMEOUT)?.click()
        assertVisible(device, "전체 검색")
        device.pressBack()

        device.wait(Until.findObject(By.desc("하단 보관함")), TIMEOUT)?.click()
        assertVisible(device, "내 보관함")
        assertVisible(device, "최근")
        assertVisible(device, "좋아요")
        assertVisible(device, "저장됨")
    }

    private fun assertVisible(device: UiDevice, text: String) {
        assertNotNull("Missing restored UI text: $text", device.wait(Until.findObject(By.text(text)), TIMEOUT))
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
