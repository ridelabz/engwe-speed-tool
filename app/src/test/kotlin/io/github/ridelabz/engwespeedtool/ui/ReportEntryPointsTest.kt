package io.github.ridelabz.engwespeedtool.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.ui.components.StepState
import io.github.ridelabz.engwespeedtool.ui.dashboard.Connection
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardActions
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardScreen
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedLimitStatus
import io.github.ridelabz.engwespeedtool.ui.diagnostics.DiagnosticsScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.CheckResult
import io.github.ridelabz.engwespeedtool.ui.onboarding.ConnectCheckScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.ConnectCheckUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Report is offered where the app promises it: the dashboard (its footer; the menu is tested elsewhere), the
 * verification sheet (opened by the Verified or Unverified chip), every failure state (a bike that stops
 * responding as expected, a check that fails) and Diagnostics. States with their own fix-it action don't
 * offer it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ReportEntryPointsTest {
    @get:Rule
    val screen = ScreenRule()

    private val app = RuntimeEnvironment.getApplication()
    private var reports = 0

    /** Clicks every control labelled [label]; how many there were. */
    private fun clickAll(label: String): Int {
        val matcher = hasText(label) and hasClickAction()
        val count =
            screen.compose
                .onAllNodes(matcher)
                .fetchSemanticsNodes()
                .size
        repeat(count) { i ->
            val node = screen.compose.onAllNodes(matcher)[i]
            // Controls in a bottom bar don't scroll.
            runCatching { node.performScrollTo() }
            node.performClick()
        }
        screen.compose.waitForIdle()
        return count
    }

    /** A control whose click action is labelled [label] (what TalkBack says it does). */
    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label $label") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == label
        }

    @Test
    fun theDashboardOffersItInTheFooterTheVerificationSheetAndAFailure() {
        var state by mutableStateOf(SampleDashboard.state(verification = Verification.UNVERIFIED))
        var compatibility = 0
        screen.setScreen {
            DashboardScreen(
                state,
                DashboardActions.None.copy(onReport = { reports++ }, onReportCompatibility = { compatibility++ }),
            )
        }
        // Tapping Unverified explains it, with a report of how it worked: the compatibility report.
        screen.compose.onNode(hasClickLabel(app.getString(R.string.badge_explain))).performClick()
        screen.compose.waitForIdle()
        assertEquals("the verification sheet", 1, clickAll(app.getString(R.string.verification_report)))
        assertEquals(1, compatibility)
        assertEquals(0, reports)
        assertEquals("the footer", 1, clickAll(app.getString(R.string.footer_report)))
        assertEquals(1, reports)
        state = SampleDashboard.state(connection = Connection.INCOMPATIBLE)
        assertEquals("the failure and the footer", 2, clickAll(app.getString(R.string.action_report)))
        assertEquals(3, reports)
        // A speed the bike didn't confirm: its explanation offers Report.
        state = SampleDashboard.state(status = SpeedLimitStatus.NotConfirmed(32))
        assertEquals(1, clickAll(app.getString(R.string.speed_not_confirmed_help)))
        screen.compose.onNodeWithText(app.getString(R.string.error_not_confirmed_title)).assertExists()
        val inDialog =
            hasText(app.getString(R.string.action_report)) and hasClickAction() and hasAnyAncestor(isDialog())
        screen.compose.onNode(inDialog).performClick()
        screen.compose.waitForIdle()
        assertEquals(4, reports)
        // Bluetooth off and no permission have their own fix-it action instead: only the footer reports.
        for ((connection, fix) in listOf(
            Connection.BLUETOOTH_OFF to R.string.action_turn_on,
            Connection.NO_PERMISSION to R.string.action_allow,
        )) {
            state = SampleDashboard.state(connection = connection)
            screen.compose.onNode(hasText(app.getString(fix)) and hasClickAction()).assertExists()
            assertEquals(connection.name, 1, clickAll(app.getString(R.string.action_report)))
        }
    }

    @Test
    fun aFailedCheckAndDiagnosticsOfferIt() {
        var diagnostics by mutableStateOf(false)
        screen.setScreen {
            if (diagnostics) {
                DiagnosticsScreen(onReport = { reports++ }, onBack = {})
            } else {
                ConnectCheckScreen(
                    state =
                        ConnectCheckUiState(
                            StepState.DONE,
                            StepState.FAILED,
                            StepState.NOT_RUN,
                            CheckResult.Incompatible,
                        ),
                    onContinue = {},
                    onRetry = {},
                    onReport = { reports++ },
                    onDiagnostics = {},
                    onBack = {},
                )
            }
        }
        assertEquals(1, clickAll(app.getString(R.string.action_report)))
        diagnostics = true
        assertEquals(1, clickAll(app.getString(R.string.action_report)))
        assertEquals(2, reports)
    }
}
