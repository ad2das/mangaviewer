package ml.melun.mangaview.ui.library

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Dismisses every launch dialog that can cover the library before fixtures interact with it.
 *
 * A pending crash report (left after an abnormal exit) is offered on the next launch and covers
 * the whole library, including the provider chips `CorpusUiEntry` selects. The delayed update
 * notice does the same. Both are dismissed here; closing the crash report consumes it for good.
 */
internal fun dismissBlockingDialogs(device: UiDevice) {
    dismissCrashReport(device)
    dismissAutomaticUpdateNotice(device)
    dismissCrashReport(device)
}

private const val CRASH_TITLE = "앱 오류 리포트"
private const val CLOSE_LABEL = "닫기"

/** The report can coexist with the update notice; retry while buttons keep disappearing. */
private fun dismissCrashReport(device: UiDevice, attempts: Int = 3) {
    repeat(attempts) {
        if (device.wait(Until.findObject(By.text(CRASH_TITLE)), 2_000) == null) return
        device.findObjects(By.text(CLOSE_LABEL)).firstOrNull()?.click() ?: return
        if (device.wait(Until.gone(By.text(CRASH_TITLE)), 2_000)) return
    }
    check(!device.hasObject(By.text(CRASH_TITLE))) { "Crash report dialog stayed above the library" }
}
