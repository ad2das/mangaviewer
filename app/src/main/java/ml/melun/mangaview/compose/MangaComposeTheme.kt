package ml.melun.mangaview.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2D6CDF),
    secondary = Color(0xFF00796B),
    tertiary = Color(0xFF7B3F00),
    background = Color(0xFFF8F9FB),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF17191C),
    onSurface = Color(0xFF17191C),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8CB4FF),
    secondary = Color(0xFF66D6C3),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF101114),
    surface = Color(0xFF17191C),
    onPrimary = Color(0xFF0B1B37),
    onSecondary = Color(0xFF06231F),
    onTertiary = Color(0xFF351900),
    onBackground = Color(0xFFE9EAED),
    onSurface = Color(0xFFE9EAED),
)

@Composable
fun MangaComposeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
