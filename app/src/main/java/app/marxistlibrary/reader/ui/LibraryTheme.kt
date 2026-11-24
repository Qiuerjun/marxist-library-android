package app.marxistlibrary.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.marxistlibrary.reader.settings.Appearance

data class ReaderColors(val paper: String, val ink: String, val accent: String, val rule: String, val dark: Boolean)

private val Light = ReaderColors("#FBF8F2", "#28231E", "#8B3E36", "#DDD1C2", false)
private val Warm = ReaderColors("#F2E7D4", "#3A2D25", "#7F392E", "#D4C1AC", false)
private val Night = ReaderColors("#1D1A18", "#E9E0D8", "#E8A49B", "#594942", true)

val LocalReaderColors = staticCompositionLocalOf { Light }

@Composable
fun LibraryTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val palette = when (appearance) {
        Appearance.SYSTEM -> if (isSystemInDarkTheme()) Night else Light
        Appearance.LIGHT -> Light
        Appearance.PAPER -> Warm
        Appearance.DARK -> Night
    }
    fun color(hex: String) = Color(android.graphics.Color.parseColor(hex))
    val scheme = if (palette.dark) {
        darkColorScheme(
            primary = color(palette.accent), onPrimary = Color(0xFF4B1B18),
            primaryContainer = Color(0xFF64312B), onPrimaryContainer = Color(0xFFFFE1D8),
            secondary = Color(0xFFDAC5AB), secondaryContainer = Color(0xFF47382E),
            onSecondaryContainer = Color(0xFFF5DEC7), surface = color(palette.paper),
            background = color(palette.paper), onSurface = color(palette.ink),
            onSurfaceVariant = Color(0xFFC9B7AA), surfaceContainer = Color(0xFF29221E),
            surfaceContainerHigh = Color(0xFF362B26), outline = color(palette.rule),
            outlineVariant = color(palette.rule)
        )
    } else {
        lightColorScheme(
            primary = color(palette.accent), onPrimary = Color.White,
            primaryContainer = Color(0xFFF0DBCE), onPrimaryContainer = Color(0xFF561D18),
            secondary = Color(0xFF6F5743), secondaryContainer = Color(0xFFEDE0CD),
            onSecondaryContainer = Color(0xFF443124), surface = color(palette.paper),
            background = color(palette.paper), onSurface = color(palette.ink),
            onSurfaceVariant = Color(0xFF67594E),
            surfaceContainer = if (palette == Warm) Color(0xFFE8D9C3) else Color(0xFFF3EBE0),
            surfaceContainerHigh = if (palette == Warm) Color(0xFFE4D1BA) else Color(0xFFEFE3D6),
            outline = color(palette.rule), outlineVariant = color(palette.rule)
        )
    }
    CompositionLocalProvider(LocalReaderColors provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
                headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold),
                titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
            ),
            content = content
        )
    }
}

