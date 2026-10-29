package io.github.ridelabz.engwespeedtool.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyTest {
    private val styles =
        with(AppTypography) {
            listOf(
                displayLarge,
                displayMedium,
                displaySmall,
                headlineLarge,
                headlineMedium,
                headlineSmall,
                titleLarge,
                titleMedium,
                titleSmall,
                bodyLarge,
                bodyMedium,
                bodySmall,
                labelLarge,
                labelMedium,
                labelSmall,
            )
        }

    @Test
    fun materialStylesUseTheAppsFiveSizes() {
        assertEquals(setOf(28.sp, 24.sp, 22.sp, 16.sp, 14.sp), styles.map { it.fontSize }.toSet())
    }

    @Test
    fun numbersUseTabularFigures() {
        assertEquals("tnum", NumberStyle.fontFeatureSettings)
    }
}
