package ml.melun.mangaview.viewer

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice

internal object ViewerUiConditions {
    fun waitForSurface(device: UiDevice, timeoutMillis: Long): Boolean =
        device.wait(
            Condition<UiDevice, Boolean> { current ->
                current.hasObject(By.desc(SURFACE_DESCRIPTION)) ||
                    current.hasObject(By.descStartsWith(FRAME_DESCRIPTION_PREFIX))
            },
            timeoutMillis,
        ) == true

    const val SURFACE_DESCRIPTION = "viewer-surface"
    const val FRAME_DESCRIPTION_PREFIX = "viewer-frame-presented:"
}
