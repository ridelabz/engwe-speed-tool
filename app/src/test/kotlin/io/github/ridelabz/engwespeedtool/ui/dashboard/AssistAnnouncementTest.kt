package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import io.github.ridelabz.engwespeedtool.R
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
import kotlin.math.abs

/**
 * What the assist card says about a level the app set: a level the bike confirmed shows on the bar and in the
 * heading, with no line under the bar, so the heading is read out as the confirmation comes, once; every other
 * line is read out as it comes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class AssistAnnouncementTest {
    @get:Rule
    val screen = ScreenRule()

    private var current by mutableStateOf(state())

    private fun text(
        id: Int,
        vararg args: Any,
    ) = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show() = screen.setScreen { DashboardScreen(current, DashboardActions.None) }

    private fun change(assist: AssistUiState) {
        current = state(assist = assist)
        screen.compose.waitForIdle()
    }

    /** The card's heading, read out as "Assist 3 of 5". */
    private fun heading() =
        SemanticsMatcher("the assist card's heading") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it.startsWith(text(R.string.assist_title))
            } == true
        }

    /** Whether the heading is read out when it changes. */
    private fun announced(): Boolean =
        screen.compose
            .onNode(heading())
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite

    /** The room between the bar and the keep switch, in pixels. */
    private fun gap(): Float {
        val segment =
            screen.compose
                .onNode(
                    hasContentDescription(text(R.string.assist_segment, 0)),
                ).fetchSemanticsNode()
        val keep = screen.compose.onNode(hasText(text(R.string.keep_assist_title))).fetchSemanticsNode()
        return keep.positionInRoot.y - (segment.positionInRoot.y + segment.size.height)
    }

    @Test
    fun aConfirmedLevelShowsWithoutALineAndItsHeadingIsReadOutOnceItComes() {
        current = state(assist = AssistUiState(level = 2, levels = 5))
        show()
        val none = gap()
        assertTrue(!announced())
        // While it is sent, the line says so.
        change(AssistUiState(level = 2, levels = 5, sending = 3))
        assertTrue(!announced())
        change(AssistUiState(level = 3, levels = 5, confirmed = 3))
        assertTrue("confirmed", announced())
        screen.compose.onNode(heading() and hasContentDescription(text(R.string.assist_spoken, 3, 5))).assertExists()
        assertTrue("no line, and no room for one", abs(gap() - none) < 1f)
        // The level changed at the display: the heading goes quiet again.
        change(AssistUiState(level = 4, levels = 5))
        assertTrue(!announced())
        // Not confirmed: the line says it.
        change(AssistUiState(level = 4, levels = 5, notConfirmed = 1))
        assertTrue(!announced())
        // Set again by "keep this assist level", once the bike confirms it; also straight after another confirmation.
        change(AssistUiState(level = 4, levels = 5, sending = 3, reapply = Reapply.RECONNECT, keep = true))
        assertTrue(!announced())
        change(AssistUiState(level = 3, levels = 5, confirmed = 3, reapply = Reapply.RECONNECT, keep = true))
        assertTrue("re-applied", announced())
        assertTrue("no line, and no room for one", abs(gap() - none) < 1f)
        change(AssistUiState(level = 3, levels = 5, confirmed = 3, reapply = Reapply.RESTART, keep = true))
        assertTrue("re-applied after a restart", announced())
    }

    @Test
    fun aLevelConfirmedBeforeTheCardShowedIsNotReadOutAgain() {
        current = state(assist = AssistUiState(level = 3, levels = 5, confirmed = 3))
        show()
        assertTrue(!announced())
    }

    @Test
    fun everyLineThatStaysIsReadOutAsItComes() {
        show()
        val lines =
            listOf(
                AssistUiState(level = 2, levels = 5, sending = 3) to text(R.string.assist_status_sending, 3),
                AssistUiState(level = 2, levels = 5, sending = 3, reapply = Reapply.RECONNECT, keep = true) to
                    text(R.string.assist_status_reapplying, 3),
                AssistUiState(level = 2, levels = 5, sending = 3, reapply = Reapply.RESTART, keep = true) to
                    text(R.string.assist_status_reapplying_restart, 3),
                AssistUiState(level = 2, levels = 5, notConfirmed = 3) to text(R.string.assist_status_not_confirmed, 3),
                AssistUiState(level = 2, levels = 5, notSent = NotSent.BIKE_NOT_ON) to
                    text(R.string.assist_not_sent_bike_not_on),
            )
        for ((assist, line) in lines) {
            change(assist)
            val node = screen.compose.onNode(hasText(line)).fetchSemanticsNode()
            assertEquals(line, LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        }
    }
}
