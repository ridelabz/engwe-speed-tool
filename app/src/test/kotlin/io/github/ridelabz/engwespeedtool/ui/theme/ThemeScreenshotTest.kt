package io.github.ridelabz.engwespeedtool.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** The design tokens on one page, for review, and the screens that use them. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ThemeScreenshotTest(
    variant: ScreenVariant,
) {
    @get:Rule
    val screen = ScreenRule(variant)

    @Test
    fun tokens() {
        screen.compose.setContent { SpeedToolTheme { TokenGallery() } }
        screen.capture("theme", "tokens")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = ScreenVariant.parameters()
    }
}

@Composable
private fun TokenGallery() {
    val c = MaterialTheme.colorScheme
    val s = SpeedToolTheme.status
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text("76 %", style = MaterialTheme.typography.headlineMedium.merge(NumberStyle))
            Text("Title · large", style = MaterialTheme.typography.titleLarge)
            Text("Body · medium", style = MaterialTheme.typography.bodyLarge)
            Text("Label · small", style = MaterialTheme.typography.labelLarge)
            Swatch("primary", c.primary, c.onPrimary)
            Swatch("primaryContainer", c.primaryContainer, c.onPrimaryContainer)
            Swatch("caution", s.caution, s.onCaution)
            Swatch("cautionContainer", s.cautionContainer, s.onCautionContainer)
            Swatch("confirmed", s.confirmed, s.onConfirmed)
            val g = SpeedToolTheme.gauge
            Swatch("batteryGood", g.batteryGood, c.surface)
            Swatch("batteryMid", g.batteryMid, c.surface)
            Swatch("batteryLow", g.batteryLow, c.surface)
            g.assist.forEachIndexed { i, colour -> Swatch("assist${i + 1}", colour, g.onAssist) }
            Swatch("assistNone", g.assistNone, g.onAssistNone)
            Swatch("confirmedContainer", s.confirmedContainer, s.onConfirmedContainer)
            Swatch("error", c.error, c.onError)
            Swatch("errorContainer", c.errorContainer, c.onErrorContainer)
            Swatch("surfaceContainerLow", c.surfaceContainerLow, c.onSurface)
            Swatch("surfaceContainerHigh", c.surfaceContainerHigh, c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                for (shape in listOf(
                    MaterialTheme.shapes.medium,
                    MaterialTheme.shapes.large,
                    MaterialTheme.shapes.extraLarge,
                )) {
                    Box(Modifier.size(TouchTargets.speedChoice).border(1.dp, c.outline, shape))
                }
            }
        }
    }
}

@Composable
private fun Swatch(
    name: String,
    background: Color,
    foreground: Color,
) {
    Text(
        text = name,
        color = foreground,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background, MaterialTheme.shapes.small)
                .padding(Spacing.m),
    )
}
