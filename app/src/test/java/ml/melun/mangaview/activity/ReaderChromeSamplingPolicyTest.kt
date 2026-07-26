package ml.melun.mangaview.activity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChromeSamplingPolicyTest {
    private val chromeSource = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderChromeStyler.kt"
    ).readText()
    private val activitySource = File(
        "src/main/java/ml/melun/mangaview/activity/ReaderV2Activity.kt"
    ).readText()

    @Test
    fun readerDisablesSystemBarContrastSamplingAndUsesStableImmersiveChrome() {
        assertTrue(chromeSource.contains("window.isStatusBarContrastEnforced = false"))
        assertTrue(chromeSource.contains("window.isNavigationBarContrastEnforced = false"))
        assertTrue(chromeSource.contains("View.SYSTEM_UI_FLAG_HIDE_NAVIGATION"))
        assertTrue(chromeSource.contains("View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY"))
    }

    @Test
    fun readerRestoresImmersiveChromeWheneverWindowFocusReturns() {
        val focusCallback = activitySource.substringAfter(
            "override fun onWindowFocusChanged(hasFocus: Boolean)"
        ).substringBefore("override fun ", missingDelimiterValue = activitySource)

        assertTrue(focusCallback.contains("if (hasFocus)"))
        assertTrue(focusCallback.contains("ReaderChromeStyler.applyReaderSystemUi(this)"))
    }
}
