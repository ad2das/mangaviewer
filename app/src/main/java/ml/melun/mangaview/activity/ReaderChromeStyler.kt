package ml.melun.mangaview.activity

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager

object ReaderChromeStyler {
    fun applyReaderWindow(activity: Activity) {
        val window = activity.window
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        applyReaderSystemUi(activity)
    }

    /**
     * Keep the reader in a stable immersive surface while it owns window focus.
     *
     * Leaving a transparent navigation region visible makes SystemUI sample the reader surface
     * repeatedly to choose light/dark navigation icons. On a host-GPU emulator those readbacks
     * contend with native page composition during the first fling. The reader chrome already
     * provides its own tap-controlled toolbar, so system navigation remains available through
     * the platform's transient edge gesture without continuously sampling the page surface.
     */
    fun applyReaderSystemUi(activity: Activity) {
        val decorView = activity.window.decorView
        var visibility = decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            visibility = visibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        if (decorView.systemUiVisibility != visibility) {
            decorView.systemUiVisibility = visibility
        }
    }

    fun roundedBackground(fill: Int, stroke: Int, radius: Int, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke((1 * density + 0.5f).toInt(), stroke)
        }
    }
}
