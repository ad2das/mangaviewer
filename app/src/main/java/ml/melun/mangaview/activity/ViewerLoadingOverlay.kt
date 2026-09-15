package ml.melun.mangaview.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Owns touches on the loading UI until a complete original viewport has been submitted. */
internal class ViewerLoadingOverlay(context: Context) : FrameLayout(context) {
    private var failing = false
    val active: Boolean get() = visibility == VISIBLE && !failing

    init {
        contentDescription = "viewer-loading"
        isClickable = true
        isFocusable = false
        val density = resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xE0181A22.toInt())
            }
        }
        content.addView(ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(0xFF6C5CE7.toInt())
        }, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        content.addView(TextView(context).apply {
            text = "페이지를 불러오는 중…"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun complete() { visibility = GONE }

    /** Failure UI lives outside this overlay, so release touches instead of blanking the spinner. */
    fun failed() {
        failing = true
        visibility = GONE
    }

    fun restart() {
        failing = false
        getChildAt(0).visibility = VISIBLE
        visibility = VISIBLE
    }
}
