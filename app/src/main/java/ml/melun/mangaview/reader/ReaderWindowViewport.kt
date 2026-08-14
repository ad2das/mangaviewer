package ml.melun.mangaview.reader

import android.app.Activity
import android.os.Build
import android.view.View
import kotlin.math.roundToInt

/** Resolves the current app window instead of accidentally promoting it to the full display. */
object ReaderWindowViewport {
    @JvmStatic
    fun width(activity: Activity, preferredView: View? = null): Int {
        return resolve(
            viewPixels = preferredView?.width ?: 0,
            decorPixels = activity.window.decorView.width,
            configurationDp = activity.resources.configuration.screenWidthDp,
            density = activity.resources.displayMetrics.density,
            windowPixels = currentWindowWidth(activity),
            displayFallbackPixels = activity.resources.displayMetrics.widthPixels,
        )
    }

    @JvmStatic
    fun height(activity: Activity, preferredView: View? = null): Int {
        return resolve(
            viewPixels = preferredView?.height ?: 0,
            decorPixels = activity.window.decorView.height,
            configurationDp = activity.resources.configuration.screenHeightDp,
            density = activity.resources.displayMetrics.density,
            windowPixels = currentWindowHeight(activity),
            displayFallbackPixels = activity.resources.displayMetrics.heightPixels,
        )
    }

    @JvmStatic
    fun resolve(
        viewPixels: Int,
        decorPixels: Int,
        configurationDp: Int,
        density: Float,
        windowPixels: Int,
        displayFallbackPixels: Int,
    ): Int {
        if (viewPixels > 0) return viewPixels
        if (decorPixels > 0) return decorPixels
        val configurationPixels = if (configurationDp > 0 && density > 0f) {
            (configurationDp * density).roundToInt()
        } else {
            0
        }
        if (configurationPixels > 0) return configurationPixels
        if (windowPixels > 0) return windowPixels
        return displayFallbackPixels.coerceAtLeast(1)
    }

    private fun currentWindowWidth(activity: Activity): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.width()
        } else {
            0
        }
    }

    private fun currentWindowHeight(activity: Activity): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.height()
        } else {
            0
        }
    }
}
