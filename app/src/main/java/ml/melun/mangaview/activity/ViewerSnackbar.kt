package ml.melun.mangaview.activity

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ml.melun.mangaview.R
import ml.melun.mangaview.ui.AppFonts

/**
 * The reader's floating message bar, matching the library's: one message at a time, an optional
 * action, a timed exit, announced to TalkBack. It never takes touches outside its own bounds.
 */
internal class ViewerSnackbar(context: Context) : LinearLayout(context) {
    enum class Tone { INFO, SUCCESS, ERROR }

    private var palette = ViewerPalette.of(dark = true)
    private val mark = ImageView(context)
    private val message = TextView(context)
    private val action = TextView(context)
    private val dismiss = Runnable { hide() }
    private var onAction: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        isClickable = true
        contentDescription = null
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        minimumHeight = context.dp(52)
        setPadding(context.dp(14), 0, context.dp(6), 0)
        elevation = context.dpf(8f)
        addView(mark, LayoutParams(context.dp(20), context.dp(20)))
        addView(message, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(10)
            topMargin = context.dp(14)
            bottomMargin = context.dp(14)
        })
        addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(44)))
        action.gravity = Gravity.CENTER
        action.setPadding(context.dp(12), 0, context.dp(12), 0)
        action.setOnClickListener { onAction?.invoke(); hide() }
        applyPalette(palette)
    }

    fun applyPalette(value: ViewerPalette) {
        palette = value
        background = roundedFill(value.toastSurface, context.dpf(16f), context.dp(1), 0x14FFFFFF)
        message.style(14f, AppFonts.MEDIUM, Color.WHITE)
        action.style(14f, AppFonts.BOLD, 0xFFA997FF.toInt())
        action.background = pressable(value.copy(ripple = 0x29FFFFFF), Color.TRANSPARENT, context.dpf(12f))
    }

    fun show(text: String, tone: Tone = Tone.INFO, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        removeCallbacks(dismiss)
        this.onAction = onAction
        message.text = text
        action.text = actionLabel.orEmpty()
        action.visibility = if (actionLabel != null) View.VISIBLE else View.GONE
        setPaddingRelative(paddingStart, 0, if (actionLabel != null) context.dp(6) else context.dp(16), 0)
        mark.setImageResource(
            when (tone) {
                Tone.INFO -> R.drawable.ic_info
                Tone.SUCCESS -> R.drawable.ic_check_circle_fill1
                Tone.ERROR -> R.drawable.ic_error_fill1
            },
        )
        mark.tint(
            when (tone) {
                Tone.INFO -> 0xB8FFFFFF.toInt()
                Tone.SUCCESS -> palette.success
                Tone.ERROR -> palette.error
            },
        )
        enter()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        postDelayed(dismiss, if (actionLabel != null) LONG_MILLIS else SHORT_MILLIS)
    }

    private fun enter() {
        animate().cancel()
        if (visibility != View.VISIBLE) {
            alpha = 0f
            translationY = context.dpf(24f)
            visibility = View.VISIBLE
        }
        animate().alpha(1f).translationY(0f).setDuration(ENTER_MILLIS).setInterpolator(EMPHASIZED).start()
    }

    fun hide() {
        removeCallbacks(dismiss)
        if (visibility != View.VISIBLE) return
        animate().cancel()
        animate().alpha(0f).translationY(context.dpf(16f)).setDuration(EXIT_MILLIS)
            .withEndAction { visibility = View.GONE }
            .start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dismiss)
        super.onDetachedFromWindow()
    }

    fun containsPoint(x: Float, y: Float): Boolean =
        visibility == View.VISIBLE && x >= left && x < right && y >= top && y < bottom

    private companion object {
        const val SHORT_MILLIS = 2_600L
        const val LONG_MILLIS = 4_500L
        const val ENTER_MILLIS = 240L
        const val EXIT_MILLIS = 160L
        val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
