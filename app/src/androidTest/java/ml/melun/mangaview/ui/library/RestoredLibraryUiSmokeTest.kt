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

        device.wait(Until.findObject(By.desc("하단 홈")), TIMEOUT)?.click()
        assertVisible(device, "MangaView")
        assertVisible(device, "읽던 작품으로 바로 이동")
        assertVisible(device, "웹툰")
        assertVisible(device, "만화")

        device.wait(Until.findObject(By.desc("하단 검색")), TIMEOUT)?.click()
        assertVisible(device, "전체 검색")
        device.waitForIdle(SEARCH_LAYOUT_SETTLE_MILLIS)
        changeControl(device, "검색 항목: 제목", "검색 항목: 작가")
        changeControl(device, "검색 범위: 전체", "검색 범위: 만화")

        device.wait(Until.findObject(By.desc("하단 보관함")), TIMEOUT)?.click()
        assertVisible(device, "내 보관함")
        assertVisible(device, "최근")
        assertVisible(device, "좋아요")
        assertVisible(device, "저장됨")

        device.wait(Until.findObject(By.desc("계정")), TIMEOUT)?.click()
        assertVisible(device, "계정으로 이어보기")
        assertVisible(device, "설정 열기")
        assertVisible(device, "업데이트 확인")
        device.wait(Until.findObject(By.text("설정 열기")), TIMEOUT)?.click()
        assertVisible(device, "기본 설정")
        assertVisible(device, "사이트 변경")
        assertVisible(device, "앱 시작시 탭 위치")
        assertVisible(device, "어두운 테마")
    }

    private fun assertVisible(device: UiDevice, text: String) {
        assertNotNull("Missing restored UI text: $text", device.wait(Until.findObject(By.text(text)), TIMEOUT))
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
        val control = device.wait(Until.findObject(By.desc(from)), TIMEOUT)
        assertNotNull("Missing restored UI control state: $from", control)
        val center = control.visibleCenter
        val output = device.executeShellCommand("input tap ${center.x} ${center.y}")
        check(output.isBlank()) { "Input tap failed at $center: $output" }
        device.waitForIdle(SEARCH_LAYOUT_SETTLE_MILLIS)
        assertDescription(device, to)
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val SEARCH_LAYOUT_SETTLE_MILLIS = 1_000L
    }
}
