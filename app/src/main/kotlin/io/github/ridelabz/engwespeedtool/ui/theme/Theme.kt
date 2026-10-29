package io.github.ridelabz.engwespeedtool.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
private val LocalGaugeColors = staticCompositionLocalOf { LightGaugeColors }

/**
 * The app's theme. It follows the system's light or dark setting and uses the fixed palette only:
 * wallpaper-based dynamic colour would leave the status colours on surfaces nobody checked.
 */
@Composable
fun SpeedToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
        LocalGaugeColors provides if (darkTheme) DarkGaugeColors else LightGaugeColors,
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object SpeedToolTheme {
    val status: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current

    val gauge: GaugeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGaugeColors.current
}
