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
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        var visibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            visibility = visibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        if (window.decorView.systemUiVisibility != visibility) {
            window.decorView.systemUiVisibility = visibility
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
