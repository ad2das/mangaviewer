package ml.melun.mangaview.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ml.melun.mangaview.ui.AppFonts
import ml.melun.mangaview.ui.library.libraryColors

/**
 * Reader chrome colors, derived from the library's own tokens so the reader and the library share
 * one accent, one text ramp and one light/dark switch instead of hard-coded look-alikes.
 */
internal data class ViewerPalette(
    val dark: Boolean,
    val bar: Int,
    val sheet: Int,
    val surface: Int,
    val outline: Int,
    val text: Int,
    val secondary: Int,
    val accent: Int,
    val accentSurface: Int,
    val onAccent: Int,
    val track: Int,
    val scrim: Int,
    val ripple: Int,
    val toastSurface: Int,
    val success: Int,
    val error: Int,
) {
    companion object {
        fun of(dark: Boolean): ViewerPalette {
            val colors = libraryColors(dark)
            return ViewerPalette(
                dark = dark,
                // Opaque: a translucent bar reads as two colors where it spans page and gutter.
                bar = colors.card.toArgb(),
                sheet = colors.card.toArgb(),
                surface = colors.mutedSurface.toArgb(),
                outline = colors.cardBorder.toArgb(),
                text = colors.text.toArgb(),
                secondary = colors.secondary.toArgb(),
                accent = colors.accent.toArgb(),
                accentSurface = colors.accentSurface.toArgb(),
                onAccent = Color.White.toArgb(),
                track = colors.text.copy(alpha = if (dark) 0.18f else 0.12f).toArgb(),
                scrim = Color.Black.copy(alpha = if (dark) 0.62f else 0.42f).toArgb(),
                ripple = (if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)).toArgb(),
                toastSurface = (if (dark) Color(0xFF232A3B) else Color(0xFF1B2030)).toArgb(),
                success = Color(0xFF34D399).toArgb(),
                error = Color(0xFFFF6B81).toArgb(),
            )
        }
    }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
internal fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

internal fun roundedFill(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** Rounded surface with a bounded press ripple, the reader's equivalent of the library veil. */
internal fun pressable(palette: ViewerPalette, fill: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): Drawable {
    val content = roundedFill(fill, radius, strokeWidth, strokeColor)
    val mask = roundedFill(android.graphics.Color.WHITE, radius)
    return RippleDrawable(ColorStateList.valueOf(palette.ripple), content, mask)
}

internal fun TextView.style(size: Float, weight: Int, color: Int) {
    textSize = size
    setTextColor(color)
    AppFonts.apply(this, weight)
    includeFontPadding = false
}

internal fun ImageView.tint(color: Int) {
    imageTintList = ColorStateList.valueOf(color)
}
