package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The readings: all in one card, each tile read as one ("Odometer, 412.7 km"), the four further readings
 * (temperature, battery health, voltage, ride time) marked on their tiles where a verified bike's profile
 * doesn't verify them, and the ride time shown in hours and minutes and spoken in words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ReadingsTest {
    @get:Rule
    val screen = ScreenRule()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(
        minutes: Int,
        verification: Verification = Verification.VERIFIED,
    ) {
        val sample = state(verification = verification)
        screen.setScreen {
            DashboardScreen(sample.copy(more = sample.more?.copy(rideTimeMinutes = minutes)), DashboardActions.None)
        }
    }

    private fun count(label: String) =
        screen.compose
            .onAllNodesWithText(label, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    @Test
    fun everyReadingShowsInTheOneCardUnmarkedOnAVerifiedBike() {
        show(742)
        for ((label, value) in listOf(
            R.string.odometer to "412.7\u00A0km",
            R.string.reading_max_speed to "27.3\u00A0km/h",
            R.string.reading_speed to "0.0\u00A0km/h",
            R.string.more_temperature to "24\u00A0°C",
            R.string.more_battery_health to "96%",
            R.string.more_voltage to "41.8\u00A0V",
        )) {
            screen.compose
                .onNodeWithContentDescription(
                    text(R.string.reading_spoken, text(label), value),
                ).assertExists()
        }
        assertEquals(1, count(text(R.string.readings_title)))
        assertEquals(0, count(text(R.string.value_unverified)))
    }

    @Test
    fun furtherReadingsTheProfileDoesntVerifyAreMarkedOnTheirTiles() {
        val sample = state()
        screen.setScreen {
            DashboardScreen(
                sample.copy(more = sample.more?.copy(verified = false)),
                DashboardActions.None,
            )
        }
        // Temperature, battery health, voltage and ride time; nothing else.
        assertEquals(4, count(text(R.string.value_unverified)))
        screen.compose
            .onNodeWithContentDescription(
                text(R.string.reading_spoken, text(R.string.more_voltage), "41.8\u00A0V") +
                    text(R.string.list_separator_spoken) +
                    text(R.string.value_unverified),
            ).assertExists()
    }

    @Test
    fun rideTimeIsShownInHoursAndMinutesAndSpokenInWords() {
        show(742)
        screen.compose.onNodeWithText("12\u00A0h 22\u00A0min", useUnmergedTree = true).assertExists()
        screen.compose
            .onNodeWithContentDescription(
                text(R.string.reading_spoken, text(R.string.more_ride_time), "12 hours 22 minutes"),
            ).assertExists()
    }

    @Test
    fun oneHourAndOneMinuteAreSingular() {
        show(61)
        screen.compose.onNodeWithText("1\u00A0h 1\u00A0min", useUnmergedTree = true).assertExists()
        screen.compose
            .onNodeWithContentDescription(
                text(R.string.reading_spoken, text(R.string.more_ride_time), "1 hour 1 minute"),
            ).assertExists()
    }

    @Test
    fun anUnverifiedBikesValuesAreMarkedAVerifiedBikesAreNot() {
        var verification by mutableStateOf(Verification.UNVERIFIED)
        // As the app reads them: on an unverified bike no reading is verified.
        screen.setScreen { DashboardScreen(state(verification = verification), DashboardActions.None) }
        // The chip, the battery, the assist level, the lights and the readings (once for them all).
        assertEquals(5, count(text(R.string.value_unverified)))
        verification = Verification.VERIFIED
        screen.compose.waitForIdle()
        assertEquals(0, count(text(R.string.value_unverified)))
    }
}
