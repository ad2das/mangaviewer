package ml.melun.mangaview.activity

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.WindowInsets
import kotlin.math.max

internal data class ViewerSafeInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun Activity.configureViewerWindowInsets() {
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}

internal fun WindowInsets.viewerSafeDrawingInsets(): ViewerSafeInsets {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val safe = getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return ViewerSafeInsets(safe.left, safe.top, safe.right, safe.bottom)
    }
    @Suppress("DEPRECATION")
    var left = systemWindowInsetLeft
    @Suppress("DEPRECATION")
    var top = systemWindowInsetTop
    @Suppress("DEPRECATION")
    var right = systemWindowInsetRight
    @Suppress("DEPRECATION")
    var bottom = systemWindowInsetBottom
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        displayCutout?.let { cutout ->
            left = max(left, cutout.safeInsetLeft)
            top = max(top, cutout.safeInsetTop)
            right = max(right, cutout.safeInsetRight)
            bottom = max(bottom, cutout.safeInsetBottom)
        }
    }
    return ViewerSafeInsets(left, top, right, bottom)
}
