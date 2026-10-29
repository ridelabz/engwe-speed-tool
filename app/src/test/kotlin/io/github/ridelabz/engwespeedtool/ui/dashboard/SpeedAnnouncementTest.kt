package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.state
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What screen readers hear from the speed card: a speed the bike confirmed shows on its box, with no line under
 * it, so that box is read out as it turns confirmed, once; every other line is read out as it comes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SpeedAnnouncementTest {
    @get:Rule
    val screen = ScreenRule()

    private var current by mutableStateOf(state())

    private fun text(
        id: Int,
        vararg args: Any,
    ) = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show() = screen.setScreen { DashboardScreen(current, DashboardActions.None) }

    private fun change(state: DashboardUiState) {
        current = state
        screen.compose.waitForIdle()
    }

    /** The speeds whose boxes are read out when they change. */
    private fun announcedBoxes(): List<Int> =
        listOf(16, 25, 32, 45).filter { kmh ->
            screen.compose
                .onAllNodes(hasContentDescription(text(R.string.speed_chip_label, kmh)))
                .fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }
        }

    /** The state screen readers hear first for the box of [kmh] ("Active", before "Above the legal limit"). */
    private fun stateOf(kmh: Int): String? =
        screen.compose
            .onNode(hasContentDescription(text(R.string.speed_chip_label, kmh)))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)
            ?.substringBefore(text(R.string.list_separator_spoken))

    @Test
    fun aConfirmedSpeedIsReadOutOnItsBoxOnceItComes() {
        current = state(status = SpeedLimitStatus.BikeDefault)
        show()
        for ((verification, confirmed) in listOf(
            Verification.VERIFIED to text(R.string.speed_chip_active),
            Verification.UNVERIFIED to text(R.string.speed_chip_confirmed),
        )) {
            change(state(verification, status = SpeedLimitStatus.Unknown))
            assertEquals(emptyList<Int>(), announcedBoxes())
            // While it is sent, the line says so.
            change(state(verification, status = SpeedLimitStatus.Sending(32)))
            assertEquals(emptyList<Int>(), announcedBoxes())
            change(state(verification, status = SpeedLimitStatus.Confirmed(32)))
            assertEquals("$verification", listOf(32), announcedBoxes())
            assertEquals(confirmed, stateOf(32))
            // Anything else after it: the box goes quiet again.
            change(state(verification, status = SpeedLimitStatus.Sending(25)))
            assertEquals(emptyList<Int>(), announcedBoxes())
            // Not confirmed: the line says it, with its help.
            change(state(verification, status = SpeedLimitStatus.NotConfirmed(25)))
            assertEquals(emptyList<Int>(), announcedBoxes())
            // A speed set again, once the bike confirms it.
            change(state(verification, status = SpeedLimitStatus.Sending(32, Reapply.RECONNECT), keepSpeed = true))
            assertEquals(emptyList<Int>(), announcedBoxes())
            change(state(verification, status = SpeedLimitStatus.Confirmed(32, Reapply.RECONNECT), keepSpeed = true))
            assertEquals("$verification", listOf(32), announcedBoxes())
            // Going back to 25, straight from a confirmed speed (the echo may come within one frame).
            change(state(verification, status = SpeedLimitStatus.Confirmed(25)))
            assertEquals("$verification", listOf(25), announcedBoxes())
        }
    }

    @Test
    fun aSpeedConfirmedBeforeTheCardShowedIsNotReadOutAgain() {
        current = state(status = SpeedLimitStatus.Confirmed(32))
        show()
        assertEquals(emptyList<Int>(), announcedBoxes())
        assertEquals(text(R.string.speed_chip_active), stateOf(32))
    }

    @Test
    fun everyLineThatStaysIsReadOutAsItComes() {
        show()
        val lines =
            listOf(
                state(status = SpeedLimitStatus.Sending(32)) to text(R.string.speed_status_sending, 32),
                state(status = SpeedLimitStatus.Sending(32, Reapply.RESTART), keepSpeed = true) to
                    text(R.string.speed_status_reapplying, 32),
                state(status = SpeedLimitStatus.Sending(32, Reapply.RECONNECT), keepSpeed = true) to
                    text(R.string.speed_status_reapplying_reconnected, 32),
                state(status = SpeedLimitStatus.NotConfirmed(32)) to text(R.string.speed_status_not_confirmed, 32),
                state(notSent = SpeedNotSent(32, NotSent.BIKE_NOT_ON)) to text(R.string.speed_not_sent_bike_not_on, 32),
                state(status = SpeedLimitStatus.BikeRestarted) to text(R.string.speed_status_restarted, 25),
                state(Verification.UNVERIFIED, status = SpeedLimitStatus.BikeRestarted) to
                    text(R.string.speed_status_restarted_unverified, 25),
                state(Verification.UNVERIFIED, status = SpeedLimitStatus.Confirmed(32)) to
                    text(R.string.speed_status_confirmed_unverified, 25),
            )
        for ((state, line) in lines) {
            change(state)
            val node = screen.compose.onNode(hasText(line)).fetchSemanticsNode()
            assertEquals(line, LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        }
        // The caveat under a confirmed box names the speed when read out: the box may be heard after it, and when
        // swiping, the box just before it is 45.
        change(
            state(Verification.UNVERIFIED, choices = listOf(16, 25, 32, 45), status = SpeedLimitStatus.Confirmed(32)),
        )
        val caveat = screen.compose.onNode(hasText(text(R.string.speed_status_confirmed_unverified, 25)))
        assertEquals(
            listOf(text(R.string.speed_status_confirmed_unverified_spoken, 32, 25)),
            caveat.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
        // Not confirmed keeps its help.
        change(state(status = SpeedLimitStatus.NotConfirmed(32)))
        screen.compose.onNode(hasText(text(R.string.speed_not_confirmed_help))).assertExists()
    }
}
