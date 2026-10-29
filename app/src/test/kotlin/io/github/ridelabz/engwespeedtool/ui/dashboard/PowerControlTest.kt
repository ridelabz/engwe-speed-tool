package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
 * Turn off and Turn on: a round icon button at the end of the bike's name row, offered only where the bike's
 * profile gives it, Turn off (filled) while the bike reports on, Turn on (outlined) while it reports off,
 * disabled otherwise and while a change is on its way, read out (and shown on a long press) as what a tap does,
 * sent at once, and one line when a tap wasn't sent or confirmed. The speed card's sheet says what Turn off does
 * to the limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class PowerControlTest {
    @get:Rule
    val screen = ScreenRule()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = RuntimeEnvironment.getApplication().getString(id, *args)

    private var current by mutableStateOf(state())
    private val taps = mutableListOf<Boolean>()

    private fun show() =
        screen.setScreen {
            DashboardScreen(
                current,
                DashboardActions.None.copy(onPower = { taps += it }),
            )
        }

    private fun count(
        label: Int,
        vararg args: Any,
    ) = screen.compose
        .onAllNodes(hasText(text(label, *args)))
        .fetchSemanticsNodes()
        .size

    /** The power button, by what a screen reader says (an error card may have a "Turn on" of its own). */
    private fun power(spoken: Int) = screen.compose.onNode(hasContentDescription(text(spoken)) and hasClickAction())

    /** How many controls read as the power button, in any of its states. */
    private fun powerButtons() =
        listOf(
            R.string.power_turn_off_spoken,
            R.string.power_turn_on_spoken,
            R.string.power_turning_off,
            R.string.power_turning_on,
        ).sumOf {
            screen.compose
                .onAllNodes(hasContentDescription(text(it)) and hasClickAction())
                .fetchSemanticsNodes()
                .size
        }

    private fun set(state: DashboardUiState) {
        current = state
        screen.compose.waitForIdle()
    }

    private fun bikeOff(power: PowerUiState = PowerUiState()) = state(connection = Connection.BIKE_OFF, power = power)

    @Test
    fun turnOffWhileOnTurnOnWhileOffEachOneTapAndReadOutWithTheBike() {
        show()
        power(R.string.power_turn_off_spoken).assertIsEnabled().performClick()
        set(bikeOff())
        power(R.string.power_turn_on_spoken).assertIsEnabled().performClick()
        assertEquals(listOf(false, true), taps)
        assertEquals(1, powerButtons())
    }

    @Test
    fun itStaysOneControlWhenTheBikeTurnsOffAndOnSoScreenReadersKeepTheirPlace() {
        show()
        val id = power(R.string.power_turn_off_spoken).fetchSemanticsNode().id
        set(state(power = PowerUiState(switching = false)))
        assertEquals(id, power(R.string.power_turning_off).fetchSemanticsNode().id)
        set(bikeOff())
        assertEquals(id, power(R.string.power_turn_on_spoken).fetchSemanticsNode().id)
        set(bikeOff(PowerUiState(switching = true)))
        assertEquals(id, power(R.string.power_turning_on).fetchSemanticsNode().id)
        set(state())
        assertEquals(id, power(R.string.power_turn_off_spoken).fetchSemanticsNode().id)
    }

    @Test
    fun aSpinnerShowsOnlyWhileAChangeIsOnItsWay() {
        fun spinners() =
            screen.compose
                .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        show()
        assertEquals(0, spinners())
        set(state(power = PowerUiState(switching = false)))
        assertEquals(1, spinners())
        set(bikeOff(PowerUiState(switching = true)))
        assertEquals(1, spinners())
        set(bikeOff())
        assertEquals(0, spinners())
    }

    @Test
    fun turnOffGoesAtOnceWithNothingToConfirmFirst() {
        show()
        power(R.string.power_turn_off_spoken).performClick()
        screen.compose.waitForIdle()
        assertEquals(listOf(false), taps)
        // No sheet opened.
        assertEquals(0, count(R.string.action_close))
    }

    @Test
    fun aLongPressShowsWhatATapDoes() {
        show()
        assertEquals("no tooltip before", 0, count(R.string.power_turn_off_spoken))
        power(R.string.power_turn_off_spoken).performTouchInput { longClick() }
        screen.compose.waitForIdle()
        assertEquals(1, count(R.string.power_turn_off_spoken))
        assertTrue("a long press sends nothing", taps.isEmpty())
    }

    @Test
    fun itSitsAtTheEndOfTheBikesNameRowAs48DpRoundButton() {
        set(state().copy(bikeName = "Trail bike"))
        show()
        val button = power(R.string.power_turn_off_spoken).getBoundsInRoot()
        val name = screen.compose.onNode(hasText("Trail bike")).getBoundsInRoot()
        assertEquals(48f, button.width.value, 0.5f)
        assertEquals(48f, button.height.value, 0.5f)
        assertTrue("after the name", button.left >= name.right)
        assertTrue("on its row", button.top < name.bottom && button.bottom > name.top)
        // The pill sits below, on a row of its own with the chip.
        val pill =
            screen.compose
                .onNode(
                    hasText(text(R.string.connection_on)),
                    useUnmergedTree = true,
                ).getBoundsInRoot()
        assertTrue(pill.top >= button.bottom)
    }

    @Test
    fun filledWhileTheBikeIsOnOutlinedWhileOffAsForItsLastStateOtherwise() {
        val on = PowerUiState()
        assertEquals(PowerButtonLook(turnOn = false, enabled = true, sending = false), powerButtonLook(state(), on))
        assertEquals(PowerButtonLook(turnOn = true, enabled = true, sending = false), powerButtonLook(bikeOff(), on))
        // Sending: the change on its way, in full colour, no tap.
        assertEquals(
            PowerButtonLook(turnOn = false, enabled = false, sending = true),
            powerButtonLook(state(), PowerUiState(switching = false)),
        )
        assertEquals(
            PowerButtonLook(turnOn = true, enabled = false, sending = true),
            powerButtonLook(bikeOff(), PowerUiState(switching = true)),
        )
        // Neither reported: as for the last state, Turn off before any.
        for (connection in Connection.entries - Connection.CONNECTED - Connection.BIKE_OFF) {
            for ((lastOff, turnOn) in listOf(null to false, false to false, true to true)) {
                assertEquals(
                    "$connection $lastOff",
                    PowerButtonLook(turnOn = turnOn, enabled = false, sending = false),
                    powerButtonLook(state(connection = connection), PowerUiState(lastOff = lastOff)),
                )
            }
        }
        // Connected without fresh flags reading on, or off without fresh flags reading off.
        assertEquals(false, powerButtonLook(state().copy(reportsOn = false), on).enabled)
        assertEquals(false, powerButtonLook(bikeOff().copy(reportsOff = false), on).enabled)
    }

    @Test
    fun theSpeedSheetSaysTurnOffResetsTheLimitWhereTheAppOffersIt() {
        show()
        screen.compose.onNode(hasContentDescription(text(R.string.speed_info))).performClick()
        screen.compose.waitForIdle()
        assertEquals(1, count(R.string.speed_turn_off_resets, 25))
        assertEquals(1, count(R.string.speed_after_reconnect, 25))
        screen.compose.onNode(hasText(text(R.string.action_close)) and hasClickAction()).performClick()
        screen.compose.waitForIdle()
        for (other in listOf(state(power = null), state(Verification.UNVERIFIED))) {
            set(other)
            screen.compose.onNode(hasContentDescription(text(R.string.speed_info))).performClick()
            screen.compose.waitForIdle()
            assertEquals("$other", 0, count(R.string.speed_turn_off_resets, 25))
            // Whatever the bike: nothing marked after a reconnect doesn't mean 25.
            assertEquals("$other", 1, count(R.string.speed_after_reconnect, 25))
            screen.compose.onNode(hasText(text(R.string.action_close)) and hasClickAction()).performClick()
            screen.compose.waitForIdle()
        }
    }

    @Test
    fun disabledWhenTheBikeReportsNeitherOnNorOffAndWhileAChangeIsOnItsWay() {
        show()
        for (connection in Connection.entries - Connection.CONNECTED - Connection.BIKE_OFF) {
            set(state(connection = connection))
            power(R.string.power_turn_off_spoken).assertIsNotEnabled()
        }
        // Connected, but the flags don't read on (switching on, or not fresh).
        set(state().copy(reportsOn = false))
        power(R.string.power_turn_off_spoken).assertIsNotEnabled()
        set(state(power = PowerUiState(switching = false)))
        power(R.string.power_turning_off).assertIsNotEnabled()
        set(bikeOff(PowerUiState(switching = true)))
        power(R.string.power_turning_on).assertIsNotEnabled()
        assertTrue(taps.isEmpty())
    }

    @Test
    fun aBikeLastReportedOffReadsTurnOnWhileTheLinkIsDown() {
        show()
        for (connection in listOf(Connection.RECONNECTING, Connection.BLUETOOTH_OFF)) {
            set(state(connection = connection, power = PowerUiState(lastOff = true)))
            power(R.string.power_turn_on_spoken).assertIsNotEnabled()
            set(state(connection = connection, power = PowerUiState(lastOff = false)))
            power(R.string.power_turn_off_spoken).assertIsNotEnabled()
        }
    }

    @Test
    fun onlyWhereTheBikesProfileOffersIt() {
        show()
        assertEquals(1, powerButtons())
        set(state(power = null))
        assertEquals(0, powerButtons())
        set(state(Verification.UNVERIFIED))
        assertEquals(0, powerButtons())
        // Before the check nothing is known about the bike's profile.
        set(state(connection = Connection.CONNECTING, power = null).copy(checked = false))
        assertEquals(0, powerButtons())
    }

    @Test
    fun aTapThatWasntSentOrConfirmedSaysSo() {
        show()
        for ((power, line) in listOf(
            PowerUiState(notSent = PowerNotSent.MOVING) to R.string.power_off_not_sent_moving,
            PowerUiState(notSent = PowerNotSent.BIKE_NOT_ON) to R.string.power_off_not_sent_bike_not_on,
            PowerUiState(notSent = PowerNotSent.BIKE_NOT_OFF, notSentOn = true) to
                R.string.power_on_not_sent_bike_not_off,
            PowerUiState(notSent = PowerNotSent.NOT_CONNECTED) to R.string.power_off_not_sent_not_connected,
            PowerUiState(notSent = PowerNotSent.NOT_CONNECTED, notSentOn = true) to
                R.string.power_on_not_sent_not_connected,
            PowerUiState(notSent = PowerNotSent.BUSY) to R.string.power_off_not_sent_busy,
            PowerUiState(notSent = PowerNotSent.BUSY, notSentOn = true) to R.string.power_on_not_sent_busy,
            PowerUiState(notSent = PowerNotSent.NOT_YET) to R.string.power_off_not_sent_yet,
            PowerUiState(notSent = PowerNotSent.NOT_YET, notSentOn = true) to R.string.power_on_not_sent_yet,
            PowerUiState(notConfirmed = false) to R.string.power_off_not_confirmed,
        )) {
            set(state(power = power))
            assertEquals("$power", 1, count(line))
        }
        set(bikeOff(PowerUiState(notConfirmed = true)))
        assertEquals(1, count(R.string.power_on_not_confirmed))
        set(state())
        for (line in listOf(R.string.power_off_not_confirmed, R.string.power_off_not_sent_moving)) {
            assertEquals(0, count(line))
        }
    }

    @Test
    fun notConfirmedIsntSaidOnceTheBikeReportsTheStateItWasAskedFor() {
        show()
        set(bikeOff(PowerUiState(notConfirmed = false)))
        assertEquals(0, count(R.string.power_off_not_confirmed))
        set(state(power = PowerUiState(notConfirmed = true)))
        assertEquals(0, count(R.string.power_on_not_confirmed))
    }
}
