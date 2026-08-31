package ml.melun.mangaview.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal data class LibraryColors(
    val background: Color,
    val card: Color,
    val mutedSurface: Color,
    val accentSurface: Color,
    val outline: Color,
    val accent: Color,
    val text: Color,
    val secondary: Color,
    val muted: Color,
)

internal fun libraryColors(dark: Boolean): LibraryColors = if (dark) {
    LibraryColors(
        background = Color(0xFF0F172A),
        card = Color(0xFF1E293B),
        mutedSurface = Color(0xFF334155),
        accentSurface = Color(0xFF312E81),
        outline = Color(0xFF334155),
        accent = Color(0xFF818CF8),
        text = Color(0xFFF8FAFC),
        secondary = Color(0xFFCBD5E1),
        muted = Color(0xFF94A3B8),
    )
} else {
    LibraryColors(
        background = Color(0xFFF8FAFC),
        card = Color.White,
        mutedSurface = Color(0xFFF1F5F9),
        accentSurface = Color(0xFFE0E7FF),
        outline = Color(0xFFE2E8F0),
        accent = Color(0xFF6366F1),
        text = Color(0xFF0F172A),
        secondary = Color(0xFF64748B),
        muted = Color(0xFF94A3B8),
    )
}

internal fun titleStyle(colors: LibraryColors, size: Int = 24) = TextStyle(
    color = colors.text,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
)

internal fun sectionStyle(colors: LibraryColors, size: Int = 19) = TextStyle(
    color = colors.text,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
)

internal fun bodyStyle(colors: LibraryColors, size: Int = 15) = TextStyle(
    color = colors.text,
    fontSize = size.sp,
)

internal fun labelStyle(colors: LibraryColors, selected: Boolean = false) = TextStyle(
    color = if (selected) colors.accent else colors.secondary,
    fontSize = 13.sp,
    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
)

internal fun hintStyle(colors: LibraryColors, size: Int = 13) = TextStyle(
    color = colors.secondary,
    fontSize = size.sp,
)
