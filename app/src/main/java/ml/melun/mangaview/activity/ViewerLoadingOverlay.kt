package ml.melun.mangaview.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Owns touches on the loading UI until a complete original viewport has been submitted. */
internal class ViewerLoadingOverlay(context: Context) : FrameLayout(context) {
    private var failing = false
    private var completing = false
    val active: Boolean get() = visibility == VISIBLE && !failing && !completing

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

    /**
     * A new gesture after the first complete frame must reach the reader immediately, while a
     * gesture that started on the loading UI stays owned here so it cannot join the reader
     * halfway through. The fade itself is visual only.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (completing && event.actionMasked == MotionEvent.ACTION_DOWN) return false
        return super.dispatchTouchEvent(event)
    }

    /** Cross-fades into the first complete frame; touches are released immediately. */
    fun complete() {
        if (visibility != VISIBLE) return
        completing = true
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                if (completing) {
                    visibility = GONE
                    alpha = 1f
                }
            }
            .start()
    }

    /** Failure UI lives outside this overlay, so release touches instead of blanking the spinner. */
    fun failed() {
        failing = true
        completing = false
        animate().cancel()
        visibility = GONE
        alpha = 1f
    }

    fun restart() {
        failing = false
        completing = false
        getChildAt(0).visibility = VISIBLE
        animate().cancel()
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
            animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
    }

    private companion object {
        const val FADE_IN_MS = 160L
        const val FADE_OUT_MS = 180L
    }
}
