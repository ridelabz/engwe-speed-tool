package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.runtime.Composable
import io.github.ridelabz.engwespeedtool.bike.FirmwareCaveat
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.at
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.state
import io.github.ridelabz.engwespeedtool.ui.legal.FirmwareCaveatScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class DashboardScreenshotTest(
    variant: ScreenVariant,
) {
    @get:Rule
    val screen = ScreenRule(variant)

    private fun shot(
        name: String,
        state: DashboardUiState,
    ) {
        screen.setScreen { DashboardScreen(state, DashboardActions.None) }
        screen.capture("dashboard", name)
    }

    /** The speed warning, open over the dashboard. */
    private fun sheet(
        name: String,
        kmh: Int,
        purpose: WarningPurpose,
    ) {
        screen.setScreen {
            DashboardScreen(state(choices = listOf(16, 25, 32, 45)), DashboardActions.None)
            SpeedWarningSheet(kmh = kmh, purpose = purpose, onConfirm = {}, onCancel = {}, restartsTrusted = true)
        }
        screen.captureScreen("dashboard", name)
    }

    @Test
    fun bikeDefault() = shot("01_default_trusted_restarts", state(restartsTrusted = true))

    @Test
    fun active() =
        shot(
            "02_active_32_keep_trusted_restarts",
            state(status = SpeedLimitStatus.Confirmed(32, at), keepSpeed = true, restartsTrusted = true),
        )

    @Test
    fun sending() = shot("03_sending_32", state(status = SpeedLimitStatus.Sending(32)))

    @Test
    fun notConfirmed() = shot("04_not_confirmed_32", state(status = SpeedLimitStatus.NotConfirmed(32)))

    @Test
    fun restarted() =
        shot(
            "05_restarted_reapplying_trusted_restarts",
            state(status = SpeedLimitStatus.Sending(32, Reapply.RESTART), keepSpeed = true, restartsTrusted = true),
        )

    @Test
    fun restartedWithoutKeep() =
        shot("06_restarted_trusted_restarts", state(status = SpeedLimitStatus.BikeRestarted, restartsTrusted = true))

    @Test
    fun bikeOff() =
        shot("07_bike_off", state(status = SpeedLimitStatus.Confirmed(32, at), connection = Connection.BIKE_OFF))

    @Test
    fun reapplyAfterReconnect() =
        shot(
            "08c_reconnected_reapplying",
            state(status = SpeedLimitStatus.Sending(32, Reapply.RECONNECT), keepSpeed = true),
        )

    @Test
    fun unknownAfterReconnect() = shot("08b_unknown", state(status = SpeedLimitStatus.Unknown))

    @Test
    fun reconnecting() =
        shot(
            "08_reconnecting",
            state(status = SpeedLimitStatus.Confirmed(32, at), connection = Connection.RECONNECTING),
        )

    @Test
    fun bluetoothOff() = shot("09_bluetooth_off", state(connection = Connection.BLUETOOTH_OFF))

    @Test
    fun unverifiedDefault() =
        shot("10_unverified_default", state(verification = Verification.UNVERIFIED, lights = Lights.UNKNOWN))

    @Test
    fun unverifiedActive() =
        shot(
            "11_unverified_active_32",
            state(verification = Verification.UNVERIFIED, status = SpeedLimitStatus.Confirmed(32, at)),
        )

    @Test
    fun fourSpeeds() =
        shot("12_four_speeds", state(choices = listOf(16, 25, 32, 45), status = SpeedLimitStatus.Confirmed(45, at)))

    @Test
    fun restored() = shot("13_restored_25", state(status = SpeedLimitStatus.Confirmed(25, at)))

    @Test
    fun noPermission() = shot("19_no_permission", state(connection = Connection.NO_PERMISSION))

    @Test
    fun incompatible() = shot("20_incompatible", state(connection = Connection.INCOMPATIBLE))

    @Test
    fun connecting() =
        shot(
            "21_connecting",
            state(status = SpeedLimitStatus.Unknown, connection = Connection.CONNECTING)
                .copy(checked = false, ride = null, more = null, assist = AssistUiState(level = null, levels = null)),
        )

    @Test
    fun cantReach() =
        shot(
            "23_cant_reach",
            state(status = SpeedLimitStatus.Unknown, connection = Connection.CONNECTING)
                .copy(
                    checked = false,
                    ride = null,
                    more = null,
                    cantReach = true,
                    assist = AssistUiState(level = null, levels = null),
                ),
        )

    @Test
    fun warning() = sheet("14_warning_32", 32, WarningPurpose.APPLY)

    @Test
    fun warningStrong() = sheet("15_warning_45", 45, WarningPurpose.APPLY)

    @Test
    fun warningKeeping() = sheet("16_warning_keeping_32", 32, WarningPurpose.APPLY_KEEPING)

    @Test
    fun restartedUntrusted() =
        shot("24_restarted_untrusted", state(status = SpeedLimitStatus.BikeRestarted, restartsTrusted = false))

    @Test
    fun notSent() =
        shot(
            "25_not_sent_bike_not_on",
            state(status = SpeedLimitStatus.Unknown, notSent = SpeedNotSent(32, NotSent.BIKE_NOT_ON)),
        )

    @Test
    fun restartedUnverifiedUntrusted() =
        shot(
            "28_restarted_unverified_untrusted",
            state(verification = Verification.UNVERIFIED, status = SpeedLimitStatus.BikeRestarted),
        )

    @Test
    fun lightsNotSent() =
        shot("29_lights_not_sent", state().copy(lights = LightsUiState(Lights.OFF, notSent = NotSent.NOT_CONNECTED)))

    @Test
    fun lightsSwitching() =
        shot(
            "30_lights_switching_on",
            state().copy(lights = LightsUiState(Lights.OFF, sending = true, switchingOn = true)),
        )

    @Test
    fun keepWhenConnecting() =
        shot(
            "26_active_keep_untrusted",
            state(status = SpeedLimitStatus.Confirmed(32, at), keepSpeed = true, restartsTrusted = false),
        )

    @Test
    fun firmwareCaveat() {
        screen.setScreen { FirmwareCaveatScreen(caveat = NO_CAVEAT, onContinue = {}, onBack = {}) }
        screen.capture("dashboard", "27_firmware_caveat")
    }

    @Test
    fun batteryHalf() = shot("31_battery_35", state(battery = 35))

    @Test
    fun batteryLow() = shot("32_battery_12", state(battery = 12))

    @Test
    fun batteryFull() = shot("33_battery_100", state(battery = 100))

    @Test
    fun noBatteryReading() = shot("34_battery_none", state(battery = null))

    @Test
    fun assistSending() = shot("35_assist_sending_4", state(assist = AssistUiState(level = 2, levels = 5, sending = 4)))

    @Test
    fun assistConfirmedKept() =
        shot(
            "36_assist_confirmed_3_keep",
            state(assist = AssistUiState(level = 3, levels = 5, confirmed = 3, keep = true)),
        )

    @Test
    fun assistNotConfirmed() =
        shot("37_assist_not_confirmed_5", state(assist = AssistUiState(level = 2, levels = 5, notConfirmed = 5)))

    @Test
    fun assistZero() = shot("38_assist_0", state(assist = AssistUiState(level = 0, levels = 5)))

    @Test
    fun assistTop() = shot("39_assist_5", state(assist = AssistUiState(level = 5, levels = 5)))

    @Test
    fun turningOff() = shot("48_turning_off", state(power = PowerUiState(switching = false)))

    @Test
    fun turnOnNotConfirmed() =
        shot(
            "49_turn_on_not_confirmed",
            state(connection = Connection.BIKE_OFF, power = PowerUiState(notConfirmed = true)),
        )

    @Test
    fun turnOffWhileMoving() = shot("50_turn_off_moving", state(power = PowerUiState(notSent = PowerNotSent.MOVING)))

    @Test
    fun longNameWithPower() = shot("52_long_name", state().copy(bikeName = "Trail bike with a long name 30"))

    @Test
    fun bikeOffTurnOn() = shot("56_bike_off_turn_on", state(connection = Connection.BIKE_OFF))

    @Test
    fun reconnectingLastOff() =
        shot(
            "57_reconnecting_last_off",
            state(connection = Connection.RECONNECTING, power = PowerUiState(lastOff = true)),
        )

    @Test
    fun turningOn() =
        shot("58_turning_on", state(connection = Connection.BIKE_OFF, power = PowerUiState(switching = true)))

    @Test
    fun noNews() = shot("59_no_news", state(status = SpeedLimitStatus.Unknown))

    @Test
    fun readingsNotVerifiedByTheProfile() =
        state().let { s -> shot("40_readings_not_verified", s.copy(more = s.more?.copy(verified = false))) }

    @Test
    fun verificationSheet() =
        sheetOver("41_verification_verified", state()) { s, close ->
            VerificationSheet(s.verified, s.model, s.testedFirmware, onReport = {}, onDismiss = close)
        }

    @Test
    fun verificationSheetUnverified() =
        sheetOver("42_verification_unverified", state(verification = Verification.UNVERIFIED)) { s, close ->
            VerificationSheet(s.verified, s.model, s.testedFirmware, onReport = {}, onDismiss = close)
        }

    @Test
    fun speedInfo() = sheetOver("43_speed_info", state()) { s, close -> CardInfoSheet(DashboardSheet.SPEED, s, close) }

    @Test
    fun assistInfo() =
        sheetOver("44_assist_info", state()) { s, close -> CardInfoSheet(DashboardSheet.ASSIST, s, close) }

    @Test
    fun keepSpeedInfo() =
        sheetOver("53_keep_speed_info", state()) { s, close -> CardInfoSheet(DashboardSheet.KEEP_SPEED, s, close) }

    @Test
    fun keepAssistInfo() =
        sheetOver("54_keep_assist_info", state()) { s, close -> CardInfoSheet(DashboardSheet.KEEP_ASSIST, s, close) }

    @Test
    fun assistReappliedAfterRestart() =
        shot(
            "55_assist_reapplied_after_restart",
            state(
                assist = AssistUiState(level = 3, levels = 5, confirmed = 3, reapply = Reapply.RESTART, keep = true),
            ),
        )

    /** A sheet open over the dashboard of [state]. */
    private fun sheetOver(
        name: String,
        state: DashboardUiState,
        sheet: @Composable (DashboardUiState, () -> Unit) -> Unit,
    ) {
        screen.setScreen {
            DashboardScreen(state, DashboardActions.None)
            sheet(state) {}
        }
        screen.captureScreen("dashboard", name)
    }

    companion object {
        /** A caveat that records nothing, for screenshots. */
        private val NO_CAVEAT = FirmwareCaveat {}

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = ScreenVariant.parameters()
    }
}
