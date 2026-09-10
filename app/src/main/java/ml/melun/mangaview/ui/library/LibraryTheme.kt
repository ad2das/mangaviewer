package ml.melun.mangaview.ui.library

import androidx.compose.ui.graphics.Brush
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
    val elevatedCard: Color = card,
    val accentGradientStart: Color = accent,
    val accentGradientEnd: Color = accent,
    val surfaceGlass: Color = mutedSurface,
    val tagBackground: Color = mutedSurface,
    val tagText: Color = secondary,
    val favoriteActive: Color = Color(0xFFFF2A66),
    val gold: Color = Color(0xFFFFB800),
    val silver: Color = Color(0xFFCBD5E1),
    val bronze: Color = Color(0xFFE29578),
    val dark: Boolean = false,
) {
    val accentGradient: Brush get() = Brush.horizontalGradient(listOf(accentGradientStart, accentGradientEnd))
    val goldGradient: Brush get() = Brush.horizontalGradient(listOf(Color(0xFFFFD700), Color(0xFFFF9100)))
    val fireGradient: Brush get() = Brush.horizontalGradient(listOf(Color(0xFFFF416C), Color(0xFFFF4B2B)))
    val upGradient: Brush get() = Brush.horizontalGradient(listOf(Color(0xFFFF2A66), Color(0xFFFF6584)))
    val newGradient: Brush get() = Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00B0FF)))
    val vipGradient: Brush get() = Brush.horizontalGradient(listOf(Color(0xFF7C5CFF), Color(0xFF00F2FE)))
    val cardBorder: Color get() = if (dark) Color(0x28FFFFFF) else Color(0x0E000000)
    val cardHighlight: Color get() = if (dark) Color(0x18FFFFFF) else Color(0x40FFFFFF)
    val heroOverlayGradient: Brush get() = Brush.verticalGradient(
        listOf(
            Color.Black.copy(alpha = 0.18f),
            Color.Transparent,
            Color.Black.copy(alpha = 0.45f),
            Color.Black.copy(alpha = 0.72f),
            Color.Black.copy(alpha = 0.96f),
        ),
    )
}

internal fun libraryColors(dark: Boolean): LibraryColors = if (dark) {
    LibraryColors(
        background = Color(0xFF090A10),
        card = Color(0xFF121622),
        mutedSurface = Color(0xFF1A202E),
        accentSurface = Color(0xFF26214B),
        outline = Color(0xFF242C3E),
        accent = Color(0xFF7C5CFF),
        text = Color(0xFFF8FAFC),
        secondary = Color(0xFF94A3B8),
        muted = Color(0xFF64748B),
        elevatedCard = Color(0xFF161B2A),
        accentGradientStart = Color(0xFF7C5CFF),
        accentGradientEnd = Color(0xFF4F46E5),
        surfaceGlass = Color(0xF2121622),
        tagBackground = Color(0xFF1E2638),
        tagText = Color(0xFFCBD5E1),
        favoriteActive = Color(0xFFFF2A66),
        gold = Color(0xFFFFB800),
        silver = Color(0xFFCBD5E1),
        bronze = Color(0xFFE29578),
        dark = true,
    )
} else {
    LibraryColors(
        background = Color(0xFFF5F7FA),
        card = Color.White,
        mutedSurface = Color(0xFFEDF1F7),
        accentSurface = Color(0xFFECEBFF),
        outline = Color(0xFFE2E8F0),
        accent = Color(0xFF6045FF),
        text = Color(0xFF0F172A),
        secondary = Color(0xFF475569),
        muted = Color(0xFF94A3B8),
        elevatedCard = Color.White,
        accentGradientStart = Color(0xFF6045FF),
        accentGradientEnd = Color(0xFF7C5CFF),
        surfaceGlass = Color(0xF2FFFFFF),
        tagBackground = Color(0xFFEDF1F7),
        tagText = Color(0xFF334155),
        favoriteActive = Color(0xFFFF2E63),
        gold = Color(0xFFF59E0B),
        silver = Color(0xFF94A3B8),
        bronze = Color(0xFFD97706),
        dark = false,
    )
}

internal fun displayStyle(colors: LibraryColors, size: Int = 26) = TextStyle(
    color = colors.text,
    fontSize = size.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = (-0.6).sp,
)

internal fun titleStyle(colors: LibraryColors, size: Int = 24) = TextStyle(
    color = colors.text,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.4).sp,
)

internal fun sectionStyle(colors: LibraryColors, size: Int = 19) = TextStyle(
    color = colors.text,
    fontSize = size.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = (-0.3).sp,
)

internal fun bodyStyle(colors: LibraryColors, size: Int = 15) = TextStyle(
    color = colors.text,
    fontSize = size.sp,
    letterSpacing = (-0.2).sp,
)

internal fun labelStyle(colors: LibraryColors, selected: Boolean = false) = TextStyle(
    color = if (selected) colors.accent else colors.secondary,
    fontSize = 13.sp,
    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
    letterSpacing = (-0.2).sp,
)

internal fun hintStyle(colors: LibraryColors, size: Int = 13) = TextStyle(
    color = colors.secondary,
    fontSize = size.sp,
    letterSpacing = (-0.2).sp,
)

internal fun badgeStyle(colors: LibraryColors, size: Int = 11) = TextStyle(
    color = colors.accent,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp,
)

internal fun microBadgeStyle(colors: LibraryColors, size: Int = 10) = TextStyle(
    color = Color.White,
    fontSize = size.sp,
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = (-0.2).sp,
)