package io.github.ridelabz.engwespeedtool.ui.i18n

import android.view.View
import androidx.compose.ui.test.hasText
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardActions
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardScreen
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedLimitStatus
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsActions
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsScreen
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val LONG = "en-rXA-$PHONE"
private const val RTL = "ar-rXB-$PHONE"

/**
 * The main screens in the pseudo-locales: en-XA (text about a third longer, accented) and ar-XB (right to
 * left). They lay out without crashing, in the pseudo-locale's text and direction; with `-Pscreenshots` they
 * are recorded for a look at clipping and mirroring.
 */
@RunWith(RobolectricTestRunner::class)
class PseudoLocaleScreenshotTest {
    @get:Rule
    val screen = ScreenRule()

    private fun dashboard() =
        screen.setScreen {
            DashboardScreen(
                SampleDashboard.state(status = SpeedLimitStatus.Confirmed(32, SampleDashboard.at), keepSpeed = true),
                DashboardActions.None,
            )
        }

    private fun settings() =
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

    private fun assertPseudoText() =
        assertTrue(
            "pseudo-localised text on screen",
            screen.compose
                .onAllNodes(hasText("[", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

    private fun assertRightToLeft() {
        screen.compose.activityRule.scenario.onActivity {
            assertEquals(View.LAYOUT_DIRECTION_RTL, it.resources.configuration.layoutDirection)
        }
        // ar-XB's text, not only the language's direction.
        assertTrue(
            "ar-XB text on screen",
            screen.compose
                .onAllNodes(hasText("\u202E", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    @Config(qualifiers = LONG)
    fun dashboardLong() {
        dashboard()
        assertPseudoText()
        screen.capture("pseudo_locales", "dashboard_en_xa")
    }

    @Test
    @Config(qualifiers = RTL)
    fun dashboardRightToLeft() {
        dashboard()
        assertRightToLeft()
        screen.capture("pseudo_locales", "dashboard_ar_xb")
    }

    @Test
    @Config(qualifiers = LONG)
    fun settingsLong() {
        settings()
        assertPseudoText()
        screen.capture("pseudo_locales", "settings_en_xa")
    }

    @Test
    @Config(qualifiers = RTL)
    fun settingsRightToLeft() {
        settings()
        assertRightToLeft()
        screen.capture("pseudo_locales", "settings_ar_xb")
    }
}
