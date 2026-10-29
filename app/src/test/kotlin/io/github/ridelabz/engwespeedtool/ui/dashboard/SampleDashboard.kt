package io.github.ridelabz.engwespeedtool.ui.dashboard

import io.github.ridelabz.engwespeedtool.protocol.Verification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Dashboard states for screen tests. */
object SampleDashboard {
    val at: Instant =
        LocalDate
            .of(2026, 10, 15)
            .atTime(12, 32)
            .atZone(ZoneId.systemDefault())
            .toInstant()

    fun state(
        verification: Verification = Verification.VERIFIED,
        status: SpeedLimitStatus = SpeedLimitStatus.BikeDefault,
        connection: Connection = Connection.CONNECTED,
        choices: List<Int> = listOf(16, 25, 32),
        keepSpeed: Boolean = false,
        lights: Lights = Lights.ON,
        // As in the app: restarts count where the bike's power flags are verified, as on the verified model.
        restartsTrusted: Boolean = verification == Verification.VERIFIED,
        notSent: SpeedNotSent? = null,
        assist: AssistUiState = AssistUiState(level = 2, levels = 5),
        battery: Int? = 76,
        power: PowerUiState? = PowerUiState(),
    ) = DashboardUiState(
        bikeName = null,
        verification = verification,
        connection = connection,
        ride =
            RideData(
                speedKmh = 0.0,
                maxSpeedKmh = 27.3,
                assistLevel = assist.level,
                batteryPercent = battery,
                odometerKm = 412.7,
            ),
        more =
            MoreData(
                temperatureC = 24,
                batteryHealthPercent = 96,
                rideTimeMinutes = 742,
                voltage = 41.8,
                verified = verification == Verification.VERIFIED,
            ),
        lights = LightsUiState(lights),
        speedLimit =
            SpeedLimitUiState(
                choices = choices,
                status = status,
                keepSpeed = keepSpeed,
                restartsTrusted = restartsTrusted,
                notSent = notSent,
            ),
        assist = if (verification == Verification.VERIFIED) assist else assist.copy(levels = null),
        checked = true,
        // Unverified bikes' power flags aren't verified: they read "Connected", never "On".
        reportsOn = connection == Connection.CONNECTED && verification == Verification.VERIFIED,
        reportsOff = connection == Connection.BIKE_OFF && verification == Verification.VERIFIED,
        model = if (verification == Verification.VERIFIED) "ENGWE_Omni_ebike1100EU317" else "ENGWE_Demo_ebike",
        testedFirmware = if (verification == Verification.VERIFIED) listOf("218CAN") else emptyList(),
        // The reference profile offers turning the bike on and off; an unverified bike has no profile for it.
        power = power.takeIf { verification == Verification.VERIFIED },
    )
}
