package io.github.ridelabz.engwespeedtool.ui.report

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The ride test's two questions, both optional; Skip answers nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RideTestContentTest {
    @get:Rule
    val screen = ScreenRule()

    private val app = RuntimeEnvironment.getApplication()
    private val done = mutableListOf<RideTest?>()

    private fun show() = screen.setScreen { RideTestContent(RideTest(32, log = 3), onDone = { done += it }) }

    private fun click(text: String) =
        screen.compose
            .onNodeWithText(text)
            .performScrollTo()
            .performClick()

    @Test
    fun theAnswersGoToTheReport() {
        show()
        click(app.getString(R.string.ride_test_stopped_no))
        screen.compose.onNode(hasSetTextAction()).performTextInput("28x")
        click(app.getString(R.string.ride_test_restart_no, 32))
        click(app.getString(R.string.ride_test_report))
        screen.compose.waitForIdle()
        assertEquals(
            listOf(RideTest(32, 3, RideTestStop.NO, stoppedAtKmh = 28, restart = RideTestRestart.NO)),
            done,
        )
        assertEquals(28, done.single()?.stopSpeedKmh)
    }

    @Test
    fun unansweredQuestionsStayUnanswered() {
        show()
        click(app.getString(R.string.ride_test_report))
        screen.compose.waitForIdle()
        assertEquals(listOf(RideTest(32, 3)), done)
        assertEquals(null, done.single()?.stopSpeedKmh)
    }

    @Test
    fun yesMeansTheSpeedSet() {
        show()
        // The first question's Yes.
        screen.compose
            .onAllNodesWithText(app.getString(R.string.ride_test_yes))[0]
            .performScrollTo()
            .performClick()
        click(app.getString(R.string.ride_test_report))
        screen.compose.waitForIdle()
        assertEquals(32, done.single()?.stopSpeedKmh)
    }

    @Test
    fun skipAnswersNothing() {
        show()
        click(app.getString(R.string.ride_test_stopped_no))
        click(app.getString(R.string.ride_test_skip))
        screen.compose.waitForIdle()
        assertEquals(listOf<RideTest?>(null), done)
    }
}
