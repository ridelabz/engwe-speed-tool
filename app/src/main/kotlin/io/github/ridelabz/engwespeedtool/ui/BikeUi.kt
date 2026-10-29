package io.github.ridelabz.engwespeedtool.ui

import io.github.ridelabz.engwespeedtool.protocol.Classification
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.session.AssistState
import io.github.ridelabz.engwespeedtool.session.CheckFailure
import io.github.ridelabz.engwespeedtool.session.CheckStep
import io.github.ridelabz.engwespeedtool.session.LimitState
import io.github.ridelabz.engwespeedtool.session.RadioState
import io.github.ridelabz.engwespeedtool.session.SessionState
import io.github.ridelabz.engwespeedtool.session.StopReason
import io.github.ridelabz.engwespeedtool.ui.components.StepState
import io.github.ridelabz.engwespeedtool.ui.dashboard.AssistUiState
import io.github.ridelabz.engwespeedtool.ui.dashboard.Connection
import io.github.ridelabz.engwespeedtool.ui.dashboard.Lights
import io.github.ridelabz.engwespeedtool.ui.dashboard.MoreData
import io.github.ridelabz.engwespeedtool.ui.dashboard.Reapply
import io.github.ridelabz.engwespeedtool.ui.dashboard.RideData
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedLimitStatus
import io.github.ridelabz.engwespeedtool.ui.onboarding.CheckResult
import io.github.ridelabz.engwespeedtool.ui.onboarding.ConnectCheckUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.SignalStrength
import java.time.Instant
import io.github.ridelabz.engwespeedtool.protocol.Lights as BikeLights
import io.github.ridelabz.engwespeedtool.session.Reapply as SessionReapply
import io.github.ridelabz.engwespeedtool.session.RideData as SessionRide

/** How the session's state, the ride data and the Bluetooth state read on screen. Pure, so it can be tested. */
object BikeUi {
    /** The connect-and-check screen for a session started from the find screen. */
    fun check(
        state: SessionState,
        radio: RadioState = RadioState.ON,
    ): ConnectCheckUiState {
        val run = StepState.RUNNING
        val done = StepState.DONE
        val wait = StepState.WAITING
        val failed = StepState.FAILED
        return when (state) {
            SessionState.Idle -> {
                ConnectCheckUiState.Starting
            }

            is SessionState.Checking -> {
                when (state.step) {
                    CheckStep.SEARCHING, CheckStep.CONNECTING -> {
                        ConnectCheckUiState(run, wait, wait, null)
                    }

                    CheckStep.SERVICES, CheckStep.NOTIFICATIONS, CheckStep.STATUS -> {
                        ConnectCheckUiState(
                            done,
                            run,
                            wait,
                            null,
                        )
                    }

                    CheckStep.IDENTITY -> {
                        ConnectCheckUiState(done, done, run, null)
                    }
                }
            }

            is SessionState.Ready -> {
                ConnectCheckUiState(done, done, done, result(state.classification))
            }

            is SessionState.Incompatible -> {
                if (state.failure == CheckFailure.NO_IDENTITY) {
                    ConnectCheckUiState(done, done, failed, CheckResult.Incompatible)
                } else {
                    ConnectCheckUiState(done, failed, wait, CheckResult.Incompatible)
                }
            }

            is SessionState.Stopped -> {
                val result =
                    when (state.reason) {
                        StopReason.NOT_FOUND -> {
                            CheckResult.NotFound
                        }

                        StopReason.NOT_ACCEPTED -> {
                            CheckResult.NotAccepted
                        }

                        StopReason.BLUETOOTH_UNAVAILABLE -> {
                            if (radio ==
                                RadioState.NOT_PERMITTED
                            ) {
                                CheckResult.NoPermission
                            } else {
                                CheckResult.BluetoothOff
                            }
                        }

                        StopReason.CONNECT_FAILED, StopReason.LINK_LOST -> {
                            CheckResult.ConnectedElsewhere
                        }
                    }
                ConnectCheckUiState(failed, wait, wait, result)
            }

            is SessionState.Retrying -> {
                ConnectCheckUiState(run, wait, wait, null)
            }
        }
    }

    private fun result(c: Classification): CheckResult {
        val name = c.profile?.displayName ?: c.modelIdentity
        return if (c.verification == Verification.VERIFIED && name != null) {
            CheckResult.Verified(name)
        } else {
            CheckResult.Unverified(name, checkNotNull(c.unverifiedReason))
        }
    }

    /**
     * The dashboard's connection line. A switched-off bike shows only where its power flags are verified.
     * Until this run has [checked] the bike, it is only connecting: nothing is claimed about it yet.
     */
    fun connection(
        radio: RadioState,
        state: SessionState,
        ride: SessionRide,
        checked: Boolean = true,
    ): Connection =
        when {
            radio == RadioState.NOT_PERMITTED -> {
                Connection.NO_PERMISSION
            }

            radio != RadioState.ON -> {
                Connection.BLUETOOTH_OFF
            }

            state is SessionState.Incompatible -> {
                Connection.INCOMPATIBLE
            }

            !checked -> {
                Connection.CONNECTING
            }

            state is SessionState.Ready -> {
                if (ride.bikeOff && ride.powerVerified && !ride.stale) Connection.BIKE_OFF else Connection.CONNECTED
            }

            else -> {
                Connection.RECONNECTING
            }
        }

    /** Live values, or null when there are none to trust (none yet, stale, or not in the bike's layout). */
    fun ride(ride: SessionRide): RideData? {
        val s = ride.status ?: return null
        if (ride.stale || ride.unreadable) return null
        val speed = s.speedKmh?.value ?: return null
        return RideData(
            speedKmh = speed,
            maxSpeedKmh = s.maxSpeedKmh?.value,
            assistLevel = s.assistLevel?.value,
            batteryPercent = s.batteryPercent?.value,
            odometerKm = s.odometerKm?.value,
        )
    }

    /** Further values; [MoreData.verified] only if the bike's profile verifies the meaning of each. */
    fun more(ride: SessionRide): MoreData? {
        val s = ride.status ?: return null
        if (ride.stale || ride.unreadable) return null
        val readings = listOfNotNull(s.temperatureRaw, s.batteryHealthRaw, s.rideTimeMinutes, s.voltageV)
        return MoreData(
            temperatureC = s.temperatureRaw?.value,
            batteryHealthPercent = s.batteryHealthRaw?.value,
            rideTimeMinutes = s.rideTimeMinutes?.value,
            voltage = s.voltageV?.value,
            verified = readings.all { it.verified },
        )
    }

    /**
     * The assist card: the level the bike reports (none while the values are stale or unreadable), whether the
     * app can set it ([levels] from the bike's profile), and what the app sent ([assist]).
     */
    fun assist(
        ride: SessionRide,
        assist: AssistState,
        levels: Int?,
    ): AssistUiState {
        val level =
            ride.status
                ?.assistLevel
                ?.value
                ?.takeUnless { ride.stale || ride.unreadable }
        return AssistUiState(
            level = level,
            levels = levels,
            sending = (assist as? AssistState.Sending)?.level?.level,
            // Only while the bike shows it: never a level the screen can't back with a reading.
            confirmed = (assist as? AssistState.Confirmed)?.level?.level?.takeIf { it == level },
            reapply =
                reapply(
                    when (assist) {
                        is AssistState.Sending -> assist.reapply
                        is AssistState.Confirmed -> assist.reapply
                        else -> null
                    },
                ),
            notConfirmed = (assist as? AssistState.NotConfirmed)?.level?.level,
        )
    }

    /** Whether the dashboard may say the bike is on: a fresh, readable status with verified power flags reading on. */
    fun reportsOn(ride: SessionRide): Boolean =
        ride.status != null && !ride.stale && !ride.unreadable && ride.powerVerified && ride.power == PowerState.ON

    /**
     * Whether the bike reports off (or locked), by the same rule as the session's for an unlock: a fresh, readable
     * status with verified power flags reading off.
     */
    fun reportsOff(ride: SessionRide): Boolean =
        ride.status != null && !ride.stale && !ride.unreadable && ride.powerVerified && ride.power == PowerState.OFF

    fun lights(ride: SessionRide): Lights =
        when (
            ride.status
                ?.lights
                ?.value
                ?.takeUnless { ride.stale }
        ) {
            BikeLights.ON -> Lights.ON
            BikeLights.OFF -> Lights.OFF
            else -> Lights.UNKNOWN
        }

    /**
     * What the card says about the limit, from what the session sent and saw on this connection. A restart
     * the session saw reads as the bike's default only on a verified bike, where restarts are [trusted], and
     * when the app hadn't set a speed before it ([applied]); otherwise as a restart, worded by the card.
     * [confirmedAt] is when the app saw the confirmation.
     */
    fun limit(
        state: LimitState,
        verified: Boolean,
        trusted: Boolean,
        applied: Boolean,
        confirmedAt: Instant,
    ): SpeedLimitStatus =
        when (state) {
            LimitState.Unknown -> {
                SpeedLimitStatus.Unknown
            }

            LimitState.Restarted -> {
                if (verified && trusted && !applied) SpeedLimitStatus.BikeDefault else SpeedLimitStatus.BikeRestarted
            }

            is LimitState.Sending -> {
                SpeedLimitStatus.Sending(state.limit.kmh, reapply(state.reapply))
            }

            is LimitState.Confirmed -> {
                SpeedLimitStatus.Confirmed(state.limit.kmh, confirmedAt, reapply(state.reapply))
            }

            is LimitState.NotConfirmed -> {
                SpeedLimitStatus.NotConfirmed(state.limit.kmh)
            }
        }

    private fun reapply(reapply: SessionReapply?): Reapply? =
        when (reapply) {
            SessionReapply.RECONNECT -> Reapply.RECONNECT
            SessionReapply.RESTART -> Reapply.RESTART
            null -> null
        }

    /** Signal strength in three steps. */
    fun signal(rssi: Int): SignalStrength =
        when {
            rssi >= STRONG_DBM -> SignalStrength.STRONG
            rssi >= FAIR_DBM -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }

    // On the 10 dB steps the finder sorts by, so that a weaker level never shows above a stronger one.
    private const val STRONG_DBM = -70
    private const val FAIR_DBM = -80
}
