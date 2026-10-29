package io.github.ridelabz.engwespeedtool.ui.i18n

import androidx.compose.ui.test.hasText
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardActions
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardScreen
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsActions
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsScreen
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The worst case for clipping: long pseudo-localised text at 200 % font size. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rXA-$PHONE")
class PseudoLocaleLargeFontTest {
    @get:Rule
    val screen = ScreenRule(ScreenVariant.LIGHT_FONT_200)

    @Test
    fun unverifiedDashboard() {
        screen.setScreen {
            DashboardScreen(
                SampleDashboard.state(verification = Verification.UNVERIFIED, battery = 100),
                DashboardActions.None,
            )
        }
        screen.capture("pseudo_locales", "dashboard_unverified_en_xa_font200")
    }

    @Test
    fun dashboard() {
        screen.setScreen {
            DashboardScreen(SampleDashboard.state(choices = listOf(16, 25, 32, 45)), DashboardActions.None)
        }
        screen.capture("pseudo_locales", "dashboard_en_xa_font200")
    }

    @Test
    fun settings() {
        screen.setScreen {
            SettingsScreen(
                SettingsUiState(
                    bikeName = null,
                    keepSpeed = true,
                    activeKmh = 32,
                    keepScreenOn = true,
                    logSessions = 5,
                    keptKmh = 32,
                ),
                SettingsActions(onLanguage = {}),
                onBack = {},
            )
        }
        assertTrue(
            screen.compose
                .onAllNodes(hasText("[", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        screen.capture("pseudo_locales", "settings_en_xa_font200")
    }
}
