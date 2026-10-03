package ml.melun.mangaview.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import ml.melun.mangaview.ui.AppFonts

/** Square 48dp icon control for the reader's top bar; "active" marks a toggle that is on. */
internal class ChromeIconButton(
    context: Context,
    @DrawableRes private var icon: Int,
    label: String,
    private val click: () -> Unit,
) : ImageView(context) {
    private var palette = ViewerPalette.of(dark = true)
    var active = false
        private set

    init {
        contentDescription = label
        scaleType = ScaleType.CENTER_INSIDE
        val pad = context.dp(12)
        setPadding(pad, pad, pad, pad)
        setImageResource(icon)
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isEnabled) click() }
    }

    fun setActive(value: Boolean, @DrawableRes iconRes: Int = icon) {
        if (active == value && icon == iconRes) return
        active = value
        if (icon != iconRes) {
            icon = iconRes
            setImageResource(iconRes)
        }
        applyPalette(palette)
    }

    fun applyPalette(value: ViewerPalette) {
        palette = value
        background = pressable(value, if (active) value.accentSurface else Color.TRANSPARENT, context.dpf(14f))
        tint(if (active) value.accent else value.text)
    }

    fun enable(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }
}

/**
 * Bottom-bar action: icon over a short label. It stays a TextView carrying its label as text so
 * accessibility services and device tests find "다음", "회차" and friends by their visible words.
 */
internal class ChromeActionItem(
    context: Context,
    @DrawableRes icon: Int,
    label: String,
    private val emphasized: Boolean,
    private val click: () -> Unit,
) : TextView(context) {
    private var palette = ViewerPalette.of(dark = true)

    init {
        text = label
        gravity = Gravity.CENTER
        isSingleLine = true
        isClickable = true
        isFocusable = true
        compoundDrawablePadding = context.dp(3)
        setPadding(0, context.dp(6), 0, context.dp(6))
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
        setOnClickListener { if (isEnabled) click() }
        applyPalette(palette)
    }

    fun applyPalette(value: ViewerPalette) {
        palette = value
        val color = if (emphasized) value.accent else value.text
        style(11.5f, if (emphasized) AppFonts.BOLD else AppFonts.MEDIUM, color)
        compoundDrawableTintList = ColorStateList.valueOf(color)
        background = pressable(value, Color.TRANSPARENT, context.dpf(14f))
    }

    fun enable(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }
}

internal const val DISABLED_ALPHA = 0.32f
