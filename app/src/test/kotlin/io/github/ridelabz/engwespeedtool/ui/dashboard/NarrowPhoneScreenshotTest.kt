package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.state
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Four speeds on a narrow phone at 200 % font, on Android 13, which still scales text linearly (newer
 * versions scale large text less): the tightest fit the speed choices must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi", sdk = [33])
class NarrowPhoneScreenshotTest {
    @get:Rule
    val screen = ScreenRule(ScreenVariant.LIGHT_FONT_200)

    @Test
    fun fourSpeeds() {
        screen.setScreen {
            DashboardScreen(
                state(choices = listOf(16, 25, 32, 45), status = SpeedLimitStatus.Confirmed(32)),
                DashboardActions.None,
            )
        }
        screen.capture("dashboard", "18_narrow_four_speeds")
    }

    @Test
    fun unverifiedFullBattery() {
        screen.setScreen {
            DashboardScreen(
                state(
                    verification = Verification.UNVERIFIED,
                    battery = 100,
                    status = SpeedLimitStatus.Confirmed(45),
                ),
                DashboardActions.None,
            )
        }
        screen.capture("dashboard", "18b_narrow_unverified")
    }

    @Test
    fun assistSending() {
        screen.setScreen {
            DashboardScreen(state(assist = AssistUiState(level = 5, levels = 5, sending = 0)), DashboardActions.None)
        }
        screen.capture("dashboard", "18c_narrow_assist")
    }

    @Test
    fun turningOffWithALongName() {
        screen.setScreen {
            DashboardScreen(
                state(power = PowerUiState(switching = false))
                    .copy(bikeName = "Trail bike with a long name 30"),
                DashboardActions.None,
            )
        }
        screen.capture("dashboard", "18d_narrow_turning_off")
    }

    @Test
    fun theTopBarsTitleStaysOnOneLine() {
        screen.setScreen { DashboardScreen(state(), DashboardActions.None) }
        val title = RuntimeEnvironment.getApplication().getString(R.string.dashboard_title)
        val layouts = mutableListOf<TextLayoutResult>()
        screen.compose
            .onNode(hasText(title))
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        // One line, an ellipsis at worst (whether it fits shows on the screenshots: text isn't measured for real
        // without them).
        assertEquals(1, layouts.single().layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
    }

    @Test
    fun bikeOffWithALongName() {
        screen.setScreen {
            DashboardScreen(
                state(connection = Connection.BIKE_OFF, status = SpeedLimitStatus.Unknown)
                    .copy(bikeName = "Trail bike with a long name 30"),
                DashboardActions.None,
            )
        }
        screen.capture("dashboard", "18e_narrow_bike_off_long_name")
    }
}
