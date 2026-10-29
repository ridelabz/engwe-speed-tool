package io.github.ridelabz.engwespeedtool.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x contrast of the theme's text and icon colours on the backgrounds they are drawn on. */
class ContrastTest {
    private class ContrastCheck(
        val name: String,
        val foreground: Color,
        val background: Color,
        val minimum: Double,
    )

    private fun pairs(
        c: ColorScheme,
        s: StatusColors,
        g: GaugeColors,
    ): List<ContrastCheck> {
        val text = AA_TEXT
        val result =
            mutableListOf(
                ContrastCheck("onPrimary/primary", c.onPrimary, c.primary, text),
                ContrastCheck("onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer, text),
                // The top bar's title, back arrow and menu icon.
                ContrastCheck("onTopBar/topBar", c.onTopBar, c.topBar, text),
                ContrastCheck("onSecondary/secondary", c.onSecondary, c.secondary, text),
                ContrastCheck(
                    "onSecondaryContainer/secondaryContainer",
                    c.onSecondaryContainer,
                    c.secondaryContainer,
                    text,
                ),
                ContrastCheck("onTertiary/tertiary", c.onTertiary, c.tertiary, text),
                ContrastCheck(
                    "onTertiaryContainer/tertiaryContainer",
                    c.onTertiaryContainer,
                    c.tertiaryContainer,
                    text,
                ),
                ContrastCheck("onError/error", c.onError, c.error, text),
                ContrastCheck("onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer, text),
                ContrastCheck("onSurface/surfaceVariant", c.onSurface, c.surfaceVariant, text),
                ContrastCheck("onSurfaceVariant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant, text),
                ContrastCheck("onBackground/background", c.onBackground, c.background, text),
                ContrastCheck("inverseOnSurface/inverseSurface", c.inverseOnSurface, c.inverseSurface, text),
                ContrastCheck("inversePrimary/inverseSurface", c.inversePrimary, c.inverseSurface, text),
                ContrastCheck("onCaution/caution", s.onCaution, s.caution, text),
                ContrastCheck("onCautionContainer/cautionContainer", s.onCautionContainer, s.cautionContainer, text),
                ContrastCheck("onConfirmed/confirmed", s.onConfirmed, s.confirmed, text),
                ContrastCheck(
                    "onConfirmedContainer/confirmedContainer",
                    s.onConfirmedContainer,
                    s.confirmedContainer,
                    text,
                ),
            )
        val surfaces =
            mapOf(
                "surface" to c.surface,
                "surfaceDim" to c.surfaceDim,
                "surfaceBright" to c.surfaceBright,
                "surfaceContainerLowest" to c.surfaceContainerLowest,
                "surfaceContainerLow" to c.surfaceContainerLow,
                "surfaceContainer" to c.surfaceContainer,
                "surfaceContainerHigh" to c.surfaceContainerHigh,
                "surfaceContainerHighest" to c.surfaceContainerHighest,
            )
        val onSurfaces =
            mapOf(
                "onSurface" to c.onSurface,
                "onSurfaceVariant" to c.onSurfaceVariant,
                "primary" to c.primary,
                "secondary" to c.secondary,
                "tertiary" to c.tertiary,
                "error" to c.error,
                "caution" to s.caution,
                "confirmed" to s.confirmed,
            )
        for ((surfaceName, surface) in surfaces) {
            for ((name, colour) in onSurfaces) result += ContrastCheck("$name/$surfaceName", colour, surface, text)
            // Outlines mark the edges of controls such as the speed choices: WCAG's 3:1 for non-text.
            result += ContrastCheck("outline/$surfaceName", c.outline, surface, AA_NON_TEXT)
        }
        result += ContrastCheck("outline/surfaceVariant", c.outline, c.surfaceVariant, AA_NON_TEXT)
        // The gauges: a fill must stand out from the empty part (the card behind it, 3:1 for non-text), and a
        // number drawn on a segment must be readable (text).
        val fills =
            mapOf("batteryGood" to g.batteryGood, "batteryMid" to g.batteryMid, "batteryLow" to g.batteryLow) +
                g.assist.mapIndexed { i, colour -> "assist${i + 1}" to colour } + ("assistNone" to g.assistNone)
        for ((surfaceName, surface) in surfaces) {
            for ((name, fill) in fills) result += ContrastCheck("$name/$surfaceName", fill, surface, AA_NON_TEXT)
        }
        g.assist.forEachIndexed { i, colour ->
            result +=
                ContrastCheck("onAssist/assist${i + 1}", g.onAssist, colour, text)
        }
        result += ContrastCheck("onAssistNone/assistNone", g.onAssistNone, g.assistNone, text)
        return result
    }

    @Test
    fun lightThemeMeetsAa() = assertAa(LightColors, LightStatusColors, LightGaugeColors)

    @Test
    fun darkThemeMeetsAa() = assertAa(DarkColors, DarkStatusColors, DarkGaugeColors)

    @Test
    fun theTopBarIsSetApartFromThePage() {
        for (c in listOf(LightColors, DarkColors)) {
            assertTrue(c.topBar != c.surface && c.topBar != c.background)
            // Not the cards' colour either, so the bar doesn't read as one of them.
            assertTrue(c.topBar != c.surfaceContainer && c.topBar != c.surfaceContainerHighest)
        }
    }

    @Test
    fun assistLevelsGoFromBlueToMagentaWhateverTheirNumber() {
        for (g in listOf(LightGaugeColors, DarkGaugeColors)) {
            assertTrue(g.assist.size == 5 && g.assist.toSet().size == 5)
            assertTrue(g.assistLevel(0, 5) == g.assistNone)
            assertTrue((1..5).map { g.assistLevel(it, 5) } == g.assist)
            for (levels in 1..5) {
                assertTrue("highest of $levels", g.assistLevel(levels, levels) == g.assist.last())
                if (levels > 1) assertTrue("lowest of $levels", g.assistLevel(1, levels) == g.assist.first())
            }
        }
    }

    @Test
    fun contrastFormulaMatchesKnownValues() {
        assertNear(21.0, contrast(Color.Black, Color.White))
        assertNear(1.0, contrast(Color.White, Color.White))
        assertNear(4.48, contrast(Color(0xFF777777), Color.White))
    }

    private fun assertAa(
        colours: ColorScheme,
        status: StatusColors,
        gauge: GaugeColors,
    ) {
        val failures =
            pairs(colours, status, gauge)
                .map { it to contrast(it.foreground, it.background) }
                .filter { (check, ratio) -> ratio < check.minimum }
                .map { (check, ratio) -> "${check.name}: %.2f < %.1f".format(ratio, check.minimum) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertNear(
        expected: Double,
        actual: Double,
    ) = assertTrue("expected $expected, got $actual", kotlin.math.abs(expected - actual) < 0.01)

    companion object {
        const val AA_TEXT = 4.5
        const val AA_NON_TEXT = 3.0

        fun contrast(
            a: Color,
            b: Color,
        ): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        /** Relative luminance of an opaque sRGB colour, as WCAG defines it. */
        private fun luminance(colour: Color): Double {
            val argb = colour.toArgb()
            require(argb ushr 24 == 0xFF) { "contrast is defined for opaque colours" }

            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
