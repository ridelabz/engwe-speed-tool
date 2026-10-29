package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.compose.runtime.Composable
import io.github.ridelabz.engwespeedtool.protocol.UnverifiedReason
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import io.github.ridelabz.engwespeedtool.ui.components.StepState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class OnboardingScreenshotTest(
    variant: ScreenVariant,
) {
    @get:Rule
    val screen = ScreenRule(variant)

    private val bikes =
        listOf(
            // In the finder's order: a paired bike first, then by signal.
            FoundBike(3, "ENGWE bike", null),
            FoundBike(1, "ENGWE bike", SignalStrength.STRONG),
            FoundBike(2, "ENGWE bike", SignalStrength.WEAK),
        )

    private fun shot(
        name: String,
        content: @Composable () -> Unit,
    ) {
        screen.setScreen(content)
        screen.capture("onboarding", name)
    }

    @Test
    fun welcome() = shot("01_welcome") { WelcomeScreen(onStart = {}) }

    @Test
    fun riskAcceptance() = shot("02_risk_acceptance") { RiskAcceptanceScreen(onAccept = {}, onBack = {}) }

    @Test
    fun bluetooth() = shot("03_bluetooth") { BluetoothScreen(onContinue = {}, onBack = {}) }

    @Test
    fun findSearching() = shot("04_find_searching") { Find(FindBikeUiState.Searching(bikes)) }

    @Test
    fun findNothing() = shot("05_find_nothing") { Find(FindBikeUiState.NothingFound) }

    @Test
    fun findBluetoothOff() = shot("06_find_bluetooth_off") { Find(FindBikeUiState.BluetoothOff) }

    @Test
    fun findNoPermission() = shot("06b_find_no_permission") { Find(FindBikeUiState.NoPermission(inSettings = true)) }

    @Test
    fun checkRunning() =
        shot("07_check_running") {
            Check(ConnectCheckUiState(StepState.DONE, StepState.RUNNING, StepState.WAITING, result = null))
        }

    @Test
    fun checkRestarted() =
        shot("07b_check_restarted") {
            Check(ConnectCheckUiState(StepState.RUNNING, StepState.WAITING, StepState.WAITING, null, restarted = true))
        }

    @Test
    fun checkVerified() =
        shot("08_check_verified") {
            Check(done(CheckResult.Verified("ENGWE E26 3.0 Pro")))
        }

    @Test
    fun checkUnverified() =
        shot("09_check_unverified") {
            Check(done(CheckResult.Unverified("ENGWE_Demo_ebike", UnverifiedReason.UNKNOWN_MODEL)))
        }

    @Test
    fun checkUnverifiedProfile() =
        shot("09b_check_unverified_profile") {
            Check(done(CheckResult.Unverified("ENGWE Community Bike", UnverifiedReason.PROFILE_NOT_VERIFIED)))
        }

    @Test
    fun checkIncompatible() =
        shot("10_check_incompatible") {
            Check(ConnectCheckUiState(StepState.DONE, StepState.DONE, StepState.FAILED, CheckResult.Incompatible))
        }

    @Test
    fun checkConnectedElsewhere() =
        shot("11_check_connected_elsewhere") {
            Check(
                ConnectCheckUiState(
                    StepState.FAILED,
                    StepState.WAITING,
                    StepState.WAITING,
                    CheckResult.ConnectedElsewhere,
                ),
            )
        }

    @Test
    fun checkNotFound() =
        shot("11b_check_not_found") {
            Check(ConnectCheckUiState(StepState.FAILED, StepState.WAITING, StepState.WAITING, CheckResult.NotFound))
        }

    @Test
    fun checkNotAccepted() =
        shot("11c_check_not_accepted") {
            Check(ConnectCheckUiState(StepState.FAILED, StepState.WAITING, StepState.WAITING, CheckResult.NotAccepted))
        }

    @Test
    fun checkBluetoothOff() =
        shot("11d_check_bluetooth_off") {
            Check(ConnectCheckUiState(StepState.FAILED, StepState.WAITING, StepState.WAITING, CheckResult.BluetoothOff))
        }

    @Test
    fun bluetoothDenied() =
        shot("04b_bluetooth_denied") {
            BluetoothScreen(onContinue = {}, onBack = {}, permission = PermissionUiState.DENIED)
        }

    @Test
    fun bluetoothDeniedPermanently() =
        shot("04c_bluetooth_denied_permanently") {
            BluetoothScreen(onContinue = {}, onBack = {}, permission = PermissionUiState.DENIED_PERMANENTLY)
        }

    @Test
    fun unverifiedAcknowledgement() =
        shot("12_unverified_acknowledgement") {
            UnverifiedAcknowledgementScreen(
                acknowledgement = { null },
                onAcknowledged = {},
                onNoLongerApplies = {},
                onBack = {},
            )
        }

    @Test
    fun done() = shot("13_done") { DoneScreen(onOpen = {}) }

    @Test
    fun tips() {
        screen.setScreen { TipsDialog(onDismiss = {}, onTryDemo = {}) }
        screen.captureScreen("onboarding", "14_tips_dialog")
    }

    @Composable
    private fun Find(state: FindBikeUiState) =
        FindBikeScreen(
            state,
            onBikeSelected = {},
            onScanAgain = {},
            onTurnOnBluetooth = {},
            onTryDemo = {},
            onBack = {},
        )

    @Composable
    private fun Check(state: ConnectCheckUiState) =
        ConnectCheckScreen(state, onContinue = {}, onRetry = {}, onReport = {}, onDiagnostics = {}, onBack = {})

    private fun done(result: CheckResult) = ConnectCheckUiState(StepState.DONE, StepState.DONE, StepState.DONE, result)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = ScreenVariant.parameters()
    }
}
