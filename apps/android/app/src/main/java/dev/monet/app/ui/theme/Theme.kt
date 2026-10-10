package dev.monet.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

object Spacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x12 = 48.dp
    val gutter = 20.dp
}

object Radius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 20.dp
}

private val LocalColors = staticCompositionLocalOf { LightMonetColors }
private val LocalType = staticCompositionLocalOf { monetTypography() }

/** Access to Monet tokens: `Monet.colors.seal`, `Monet.type.reading`. */
object Monet {
    val colors: MonetColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: MonetTypography
        @Composable @ReadOnlyComposable get() = LocalType.current
}

private fun MonetColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = seal,
        onPrimary = paper,
        background = paper,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = inkMuted,
        outline = inkFaint,
        outlineVariant = hairline,
        error = danger,
    )
}

@Composable
fun MonetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fonts: MonetFonts = MonetFonts.System,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkMonetColors else LightMonetColors
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides monetTypography(fonts),
    ) {
        MaterialTheme(colorScheme = colors.toMaterial(), content = content)
    }
}
