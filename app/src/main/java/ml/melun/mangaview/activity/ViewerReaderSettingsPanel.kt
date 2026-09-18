package ml.melun.mangaview.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import ml.melun.mangaview.data.settings.ViewerSettings

/** Reader-local overrides that need to stay reachable without leaving the page. */
internal class ViewerReaderSettingsPanel(context: Context) : FrameLayout(context) {
    var onDimChanged: (Int) -> Unit = {}
    var onDimCommitted: (Int) -> Unit = {}
    var onKeepScreenOn: (Boolean) -> Unit = {}
    var onVolumeKeys: (Boolean) -> Unit = {}
    var onClose: () -> Unit = {}

    val visible: Boolean get() = visibility == View.VISIBLE

    private val dim = SeekBar(context).apply { max = MAX_DIM_PERCENT }
    private val dimValue = label(13f, Typeface.BOLD).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
    private val keepScreenOn = toggle()
    private val volumeKeys = toggle()
    private lateinit var card: LinearLayout
    private var binding = false

    init {
        isClickable = true
        setBackgroundColor(0xB0000000.toInt())
        setOnClickListener { onClose() }

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF0181A22.toInt(), dp(20).toFloat(), dp(1), 0x33FFFFFF.toInt())
            val pad = dp(20)
            setPadding(pad, dp(18), pad, dp(16))
        }
        card.setOnClickListener { }
        card.addView(label(16f, Typeface.BOLD).apply { text = "뷰어 설정" })
        card.addView(divider())
        card.addView(dimRow())
        card.addView(toggleRow("화면 꺼짐 방지", keepScreenOn))
        card.addView(toggleRow("볼륨 버튼으로 이동", volumeKeys))
        card.addView(closeRow())
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            val margin = dp(20)
            setMargins(margin, margin, margin, margin)
        })
        visibility = View.GONE

        keepScreenOn.setOnCheckedChangeListener { _, checked -> if (!binding) onKeepScreenOn(checked) }
        volumeKeys.setOnCheckedChangeListener { _, checked -> if (!binding) onVolumeKeys(checked) }
        dim.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    dimValue.text = dimLabel(progress)
                    onDimChanged(progress)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                onDimCommitted(bar.progress)
                onDimChanged(bar.progress)
            }
        })
    }

    fun open(settings: ViewerSettings) {
        binding = true
        keepScreenOn.isChecked = settings.keepScreenOn
        volumeKeys.isChecked = settings.volumeKeyNavigation
        dim.progress = settings.readerDimPercent
        dimValue.text = dimLabel(settings.readerDimPercent)
        binding = false
        animate().cancel()
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        card.animate().cancel()
        card.alpha = 0f
        card.translationY = dp(28).toFloat()
        animate().alpha(1f).setDuration(FADE_MS).start()
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    fun dismiss() {
        if (visibility != View.VISIBLE) return
        animate().cancel()
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .translationY(dp(20).toFloat())
            .setDuration(EXIT_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .start()
        animate()
            .alpha(0f)
            .setDuration(EXIT_MS)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                card.alpha = 1f
                card.translationY = 0f
            }
            .start()
    }

    private fun dimRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, 0)
        addView(label(14f).apply { text = "화면 어둡게" }, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(dimValue, LinearLayout.LayoutParams(dp(44), dp(36)))
        addView(dim, LinearLayout.LayoutParams(0, dp(36), 1.2f))
    }

    private fun toggleRow(text: String, control: Switch): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        control.contentDescription = text
        addView(label(14f).apply { this.text = text }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(control, LinearLayout.LayoutParams(dp(52), dp(48)))
    }

    private fun closeRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(0, dp(14), 0, 0)
        addView(TextView(context).apply {
            text = "닫기"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = rounded(0xFF7C5CFF.toInt(), dp(12).toFloat(), dp(1), 0x669080FF.toInt())
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(dp(84), dp(44)))
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(0x22FFFFFF.toInt())
    }.also { line ->
        line.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12)
        }
    }

    private fun toggle() = Switch(context).apply {
        showText = false
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFB6BDCC.toInt()),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF7C5CFF.toInt(), 0xFF3A4256.toInt()),
        )
    }

    private fun label(size: Float, style: Int = Typeface.NORMAL) = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, style)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private fun dimLabel(percent: Int): String = if (percent <= 0) "꺼짐" else "$percent%"

    private fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_DIM_PERCENT = 70
        const val FADE_MS = 160L
        const val ENTER_MS = 200L
        const val EXIT_MS = 140L
    }
}
