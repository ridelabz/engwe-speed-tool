package io.github.ridelabz.engwespeedtool.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Screen titles (28 sp, also the battery's percentage), sheet and dialog titles (24 sp), card titles (22 sp),
// body (16 sp) and small text for labels and notes (14 sp). Every Material style maps onto one of them.

private val screenTitle = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 36.sp)
private val sheetTitle = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, lineHeight = 32.sp)
private val large = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, lineHeight = 28.sp)
private val medium = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp)
private val small = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp)

/** OpenType feature for tabular figures, so digits keep their width while the speed changes. */
private const val TABULAR_FIGURES = "tnum"

/** Numbers that sit next to each other or change, such as the speed choices, the battery and the readings. */
val NumberStyle: TextStyle = TextStyle(fontFeatureSettings = TABULAR_FIGURES)

val AppTypography: Typography =
    Typography(
        displayLarge = screenTitle.copy(fontWeight = FontWeight.Normal),
        displayMedium = screenTitle.copy(fontWeight = FontWeight.Normal),
        displaySmall = screenTitle.copy(fontWeight = FontWeight.Normal),
        headlineLarge = screenTitle.copy(fontWeight = FontWeight.Normal),
        headlineMedium = screenTitle.copy(fontWeight = FontWeight.Normal),
        headlineSmall = sheetTitle.copy(fontWeight = FontWeight.Normal),
        titleLarge = large.copy(fontWeight = FontWeight.Medium),
        titleMedium = medium.copy(fontWeight = FontWeight.Medium),
        titleSmall = small.copy(fontWeight = FontWeight.Medium),
        bodyLarge = medium,
        bodyMedium = small,
        bodySmall = small,
        labelLarge = small.copy(fontWeight = FontWeight.Medium),
        labelMedium = small.copy(fontWeight = FontWeight.Medium),
        labelSmall = small,
    )
