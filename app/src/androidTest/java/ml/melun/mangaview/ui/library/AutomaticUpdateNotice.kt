package ml.melun.mangaview.ui.library

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Local APK version can trail CI. The automatic check only raises a non-modal notice now, so there
 * is no dialog to clear; a manually opened one (from an earlier step) is still closed here.
 */
internal fun dismissAutomaticUpdateNotice(device: UiDevice) {
    if (device.findObject(By.text("나중에")) != null && device.findObject(By.text("새 버전이 나왔어요")) != null) {
        checkNotNull(device.findObject(By.text("나중에"))).click()
        check(device.wait(Until.gone(By.text("나중에")), 3000))
    }
}
