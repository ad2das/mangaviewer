package ml.melun.mangaview.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import ml.melun.mangaview.R
import ml.melun.mangaview.data.settings.MAX_AUTO_SCROLL_SPEED
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.ui.AppFonts

/** Reader-local overrides in a bottom sheet that stays reachable without leaving the page. */
internal class ViewerReaderSettingsPanel(context: Context) : FrameLayout(context) {
    var onDimChanged: (Int) -> Unit = {}
    var onDimCommitted: (Int) -> Unit = {}
    var onKeepScreenOn: (Boolean) -> Unit = {}
    var onVolumeKeys: (Boolean) -> Unit = {}
    var onDarkTheme: (Boolean) -> Unit = {}
    var onTapPaging: (Boolean) -> Unit = {}
    var onAutoScrollSpeed: (Int) -> Unit = {}
    var onClose: () -> Unit = {}

    val visible: Boolean get() = visibility == View.VISIBLE

    private var palette = ViewerPalette.of(dark = true)
    private val dim = SeekBar(context).apply { max = MAX_DIM_PERCENT }
    private val dimValue = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        fontFeatureSettings = "tnum"
    }
    private val darkTheme = toggle("어두운 테마")
    private val tapPaging = toggle("화면 위·아래 탭으로 넘기기")
    private val speed = SeekBar(context).apply { max = MAX_AUTO_SCROLL_SPEED - 1 }
    private val speedValue = TextView(context).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
    private val keepScreenOn = toggle("화면 꺼짐 방지")
    private val volumeKeys = toggle("볼륨 버튼으로 이동")
    private val heading = TextView(context).apply { text = "뷰어 설정" }
    private val handle = View(context)
    private val labels = mutableListOf<TextView>()
    private val icons = mutableListOf<ImageView>()
    private val card = LinearLayout(context)
    private var bottomInset = 0
    private var binding = false

    init {
        isClickable = true
        setOnClickListener { onClose() }
        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        card.setOnClickListener { }
        card.addView(handle, LinearLayout.LayoutParams(context.dp(36), context.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(14)
        })
        card.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(6)
        })
        card.addView(sliderRow(R.drawable.ic_brightness_medium, "화면 어둡게", dimValue, dim))
        card.addView(sliderRow(R.drawable.ic_swipe_down, "자동 스크롤 속도", speedValue, speed))
        card.addView(toggleRow(R.drawable.ic_touch_app, "화면 위·아래 탭으로 넘기기", tapPaging))
        card.addView(toggleRow(R.drawable.ic_dark_mode, "어두운 테마", darkTheme))
        card.addView(toggleRow(R.drawable.ic_screen_lock_portrait, "화면 꺼짐 방지", keepScreenOn))
        card.addView(toggleRow(R.drawable.ic_volume_up, "볼륨 버튼으로 이동", volumeKeys))
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        visibility = View.GONE
        bindListeners()
        applyPalette(palette)
    }

    private fun bindListeners() {
        darkTheme.setOnCheckedChangeListener { _, checked -> if (!binding) onDarkTheme(checked) }
        keepScreenOn.setOnCheckedChangeListener { _, checked -> if (!binding) onKeepScreenOn(checked) }
        volumeKeys.setOnCheckedChangeListener { _, checked -> if (!binding) onVolumeKeys(checked) }
        tapPaging.setOnCheckedChangeListener { _, checked -> if (!binding) onTapPaging(checked) }
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                speedValue.text = speedLabel(progress + 1)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = onAutoScrollSpeed(bar.progress + 1)
        })
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

    fun applyPalette(value: ViewerPalette) {
        palette = value
        setBackgroundColor(value.scrim)
        card.background = GradientDrawable().apply {
            setColor(value.sheet)
            val radius = context.dpf(24f)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        handle.background = roundedFill(value.track, context.dpf(2f))
        heading.style(18f, AppFonts.BOLD, value.text)
        dimValue.style(13f, AppFonts.SEMIBOLD, value.accent)
        speedValue.style(13f, AppFonts.SEMIBOLD, value.accent)
        labels.forEach { it.style(15f, AppFonts.MEDIUM, value.text) }
        icons.forEach { it.tint(value.secondary) }
        listOf(dim, speed).forEach { bar ->
            bar.progressTintList = ColorStateList.valueOf(value.accent)
            bar.thumbTintList = ColorStateList.valueOf(value.accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(value.track)
        }
        listOf(darkTheme, tapPaging, keepScreenOn, volumeKeys).forEach { tintSwitch(it, value) }
        updateCardPadding()
    }

    /** The sheet runs under the navigation bar so its surface, not a black strip, meets the edge. */
    fun applyInsets(bottom: Int) {
        if (bottomInset == bottom) return
        bottomInset = bottom
        (card.layoutParams as? LayoutParams)?.let { it.bottomMargin = -bottom; card.layoutParams = it }
        updateCardPadding()
    }

    private fun updateCardPadding() {
        val pad = context.dp(22)
        card.setPadding(pad, context.dp(10), pad, context.dp(18) + bottomInset)
    }

    fun open(settings: ViewerSettings) {
        binding = true
        darkTheme.isChecked = settings.darkTheme
        keepScreenOn.isChecked = settings.keepScreenOn
        volumeKeys.isChecked = settings.volumeKeyNavigation
        tapPaging.isChecked = settings.tapPaging
        speed.progress = settings.autoScrollSpeed - 1
        speedValue.text = speedLabel(settings.autoScrollSpeed)
        dim.progress = settings.readerDimPercent
        dimValue.text = dimLabel(settings.readerDimPercent)
        binding = false
        animate().cancel()
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        card.animate().cancel()
        card.translationY = (card.height.takeIf { it > 0 } ?: context.dp(360)).toFloat()
        animate().alpha(1f).setDuration(FADE_MS).start()
        card.animate().translationY(0f).setDuration(ENTER_MS).setInterpolator(EMPHASIZED).start()
    }

    fun dismiss() {
        if (visibility != View.VISIBLE) return
        animate().cancel()
        card.animate().cancel()
        card.animate().translationY(card.height.toFloat()).setDuration(EXIT_MS).setInterpolator(ACCELERATE).start()
        animate()
            .alpha(0f)
            .setDuration(EXIT_MS)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                card.translationY = 0f
            }
            .start()
    }

    private fun sliderRow(icon: Int, text: String, value: TextView, bar: SeekBar): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(52)
        bar.contentDescription = text
        addView(rowIcon(icon), LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
        addView(rowLabel(text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(14)
        })
        addView(value, LinearLayout.LayoutParams(context.dp(76), context.dp(52)))
        addView(bar, LinearLayout.LayoutParams(0, context.dp(52), 1.1f))
    }

    private fun speedLabel(step: Int): String = SPEED_LABELS.getOrElse(step - 1) { "보통" }

    private fun toggleRow(icon: Int, text: String, control: Switch): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(rowIcon(icon), LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
        minimumHeight = context.dp(52)
        addView(rowLabel(text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(14)
        })
        addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(52)))
        // The whole row toggles, not just the thumb: a far larger target for the same choice.
        setOnClickListener { control.toggle() }
    }

    private fun rowLabel(text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        labels += this
    }

    private fun rowIcon(icon: Int) = ImageView(context).apply {
        setImageResource(icon)
        icons += this
    }

    private fun toggle(label: String) = Switch(context).apply {
        showText = false
        contentDescription = label
    }

    private fun tintSwitch(control: Switch, value: ViewerPalette) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        control.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, if (value.dark) 0xFFB6BDCC.toInt() else Color.WHITE))
        control.trackTintList = ColorStateList(states, intArrayOf(value.accent, value.track))
        control.trackTintMode = android.graphics.PorterDuff.Mode.SRC
    }

    private fun dimLabel(percent: Int): String = if (percent <= 0) "꺼짐" else "$percent%"

    private companion object {
        const val MAX_DIM_PERCENT = 70
        val SPEED_LABELS = listOf("느리게", "조금 느리게", "보통", "조금 빠르게", "빠르게")
        const val FADE_MS = 180L
        const val ENTER_MS = 280L
        const val EXIT_MS = 180L
        val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
