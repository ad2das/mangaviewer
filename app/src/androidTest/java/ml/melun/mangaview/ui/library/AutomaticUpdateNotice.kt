package ml.melun.mangaview.ui.library

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Local APK version can trail CI; dismiss the real delayed notice before interacting with fixtures. */
internal fun dismissAutomaticUpdateNotice(device: UiDevice) {
    if (device.wait(Until.findObject(By.text("새 업데이트가 있습니다")), 20_000) != null) {
        checkNotNull(device.findObject(By.text("닫기"))).click()
        check(device.wait(Until.gone(By.text("새 업데이트가 있습니다")), 3000))
    }
}
