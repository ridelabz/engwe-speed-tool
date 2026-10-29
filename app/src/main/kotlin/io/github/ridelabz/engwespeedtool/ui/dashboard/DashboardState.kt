package io.github.ridelabz.engwespeedtool.ui.dashboard

import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.Verification

/** How the app is connected to the saved bike. */
enum class Connection {
    /** Connecting and checking the bike: nothing is shown about it until the check passes. */
    CONNECTING,

    CONNECTED,

    /** The link dropped; the app is trying to reconnect. */
    RECONNECTING,

    /** The bike reports that it is switched off. */
    BIKE_OFF,

    BLUETOOTH_OFF,

    /** The rider hasn't allowed the app to use Bluetooth (Android's "Nearby devices" permission). */
    NO_PERMISSION,

    /** The bike stopped answering as expected; the app sends it nothing. */
    INCOMPATIBLE,
}

/** Values from the bike's status messages. Null where the bike's profile has no such field. */
data class RideData(
    val speedKmh: Double,
    val maxSpeedKmh: Double?,
    val assistLevel: Int?,
    val batteryPercent: Int?,
    val odometerKm: Double?,
)

/** Further values from the status messages, shown with the others in the readings. */
data class MoreData(
    val temperatureC: Int?,
    val batteryHealthPercent: Int?,
    /** The bike's total ride time, in minutes. */
    val rideTimeMinutes: Int?,
    val voltage: Double?,
    /** The meaning of every one of these values is verified for this bike (by its profile). */
    val verified: Boolean,
)

enum class Lights { ON, OFF, UNKNOWN }

data class LightsUiState(
    val lights: Lights,
    /** A change was sent and the bike hasn't confirmed it yet. */
    val sending: Boolean = false,
    /** While [sending]: whether the lights are being switched on (or off). */
    val switchingOn: Boolean? = null,
    /** The last change wasn't confirmed by the bike. */
    val notConfirmed: Boolean = false,
    /** Why the last change wasn't sent, if it wasn't. */
    val notSent: NotSent? = null,
)

/**
 * The assist level: what the bike reports, and what the app sent. The app sets it only on a bike whose
 * profile gives its number of levels ([levels]); otherwise the level is only shown.
 */
data class AssistUiState(
    /** The level the bike reports; null without a reading. */
    val level: Int?,
    /** The highest level the app can set (from 0), or null: the level is shown, not set. */
    val levels: Int?,
    /** The level being sent, while the app waits for the bike's echo and status. */
    val sending: Int? = null,
    /** The level the app set on this connection, confirmed by the bike, while the bike still shows it. */
    val confirmed: Int? = null,
    /** [sending] or [confirmed] came from "keep this assist level", and why: on connecting or after a restart. */
    val reapply: Reapply? = null,
    /** The last level sent wasn't confirmed by the bike. */
    val notConfirmed: Int? = null,
    /** Why the last tap sent nothing, if it didn't. */
    val notSent: NotSent? = null,
    /** "Keep this assist level" is on. */
    val keep: Boolean = false,
) {
    /** Whether the app can set the level on this bike. */
    val settable: Boolean get() = levels != null

    /** [sending] or [confirmed] came from "keep this assist level". */
    val reapplied: Boolean get() = reapply != null
}

/**
 * Switching the bike on and off from the app, which unlocks and locks it, on a bike whose profile offers it:
 * what is being sent, and how the last tap ended.
 */
data class PowerUiState(
    /** While a change is sent: true when the bike is being switched on, false when off. */
    val switching: Boolean? = null,
    /** The last change wasn't confirmed by the bike: true when it was switching on, false when off. */
    val notConfirmed: Boolean? = null,
    /** Why the last tap sent nothing, if it didn't. */
    val notSent: PowerNotSent? = null,
    /** With [notSent]: true when that tap was to switch the bike on, false off. */
    val notSentOn: Boolean = false,
    /** The bike's last state this run's statuses reported: true off, false on; null before either. */
    val lastOff: Boolean? = null,
)

/** Why a tap on Turn on or Turn off sent nothing. */
enum class PowerNotSent {
    /** Turning off: the bike wasn't reporting that it was on. */
    BIKE_NOT_ON,

    /** Turning on: the bike wasn't reporting that it was off. */
    BIKE_NOT_OFF,

    /** Turning off: the bike wasn't reporting that it stood still. */
    MOVING,

    /** The bike isn't connected (anymore). */
    NOT_CONNECTED,

    /** Another change is still being sent. */
    BUSY,

    /** It waited for the rider's acceptance, and isn't sent by itself after it. */
    NOT_YET,
}

/** Why "keep this speed" sends a speed again: the bike restarted, or the connection came back. */
enum class Reapply { RESTART, RECONNECT }

/** What the app knows about the assistance limit. The bike can't report it; this is what the app set. */
sealed interface SpeedLimitStatus {
    /** The app saw the bike switch on and has set nothing since: the bike's own 25 km/h. */
    data object BikeDefault : SpeedLimitStatus

    /** The app can't tell what the limit is, for example after the connection dropped. */
    data object Unknown : SpeedLimitStatus

    /** A speed was sent and the app waits for the bike's echo. [reapply]: "keep this speed" sent it, and why. */
    data class Sending(
        val kmh: Int,
        val reapply: Reapply? = null,
    ) : SpeedLimitStatus

    /** The bike echoed the command. */
    data class Confirmed(
        val kmh: Int,
        val reapply: Reapply? = null,
    ) : SpeedLimitStatus

    /** No echo within the time limit: the app can't tell whether the limit changed. */
    data class NotConfirmed(
        val kmh: Int,
    ) : SpeedLimitStatus

    /** The bike was switched off and on; nothing was re-applied. */
    data object BikeRestarted : SpeedLimitStatus
}

/** A speed the session didn't send ([kmh]), and why. */
data class SpeedNotSent(
    val kmh: Int,
    val reason: NotSent,
)

/** Why the last tap sent nothing. */
enum class NotSent {
    /** The bike doesn't report that it is on, or not recently. */
    BIKE_NOT_ON,

    /** The bike isn't connected (anymore). */
    NOT_CONNECTED,

    /** Another change is still being sent. */
    BUSY,

    /** It waited for the rider's acknowledgement of an unverified bike, and isn't sent by itself after it. */
    NOT_YET,
}

data class SpeedLimitUiState(
    /** The speeds offered, in km/h. */
    val choices: List<Int>,
    val status: SpeedLimitStatus,
    val keepSpeed: Boolean,
    /**
     * Whether a restart the app sees counts as one, and "keep this speed" sets the speed again after it: the
     * checked bike's power flags are verified, so the app sees it switch on (off at its display or locked, then
     * on). Only then may a verified bike's card say what the project's tests showed after a restart; otherwise it
     * only says that the bike reported one, and "keep this speed" promises nothing after a restart. Off unless
     * the app knows it, so that a forgotten path errs on the cautious side.
     */
    val restartsTrusted: Boolean = false,
    /** The speed the last tap didn't send, and why, if it didn't. */
    val notSent: SpeedNotSent? = null,
)

data class DashboardUiState(
    /** The rider's name for the bike; null shows the default, "My bike". */
    val bikeName: String?,
    val verification: Verification,
    val connection: Connection,
    /** Null until the first status message arrives. */
    val ride: RideData?,
    val more: MoreData?,
    val lights: LightsUiState,
    val speedLimit: SpeedLimitUiState,
    val assist: AssistUiState = AssistUiState(level = null, levels = null),
    /** This run checked the bike; until then nothing is claimed about it (verified or not). */
    val checked: Boolean = false,
    /** Android won't ask for the Bluetooth permission again: "Allow" opens the app's settings. */
    val permissionInSettings: Boolean = false,
    /** The last attempt didn't find or reach the bike; the app keeps trying. */
    val cantReach: Boolean = false,
    /**
     * A fresh status reports the bike on, by power flags verified for it: only then does the dashboard say
     * "On"; connected without it, "Connected".
     */
    val reportsOn: Boolean = false,
    /** A fresh, readable status reports the bike off (or locked), by power flags verified for it. */
    val reportsOff: Boolean = false,
    /** The model identity this run's check read, for the verification sheet; null before the check. */
    val model: String? = null,
    /** The IoT firmware versions the project tested this model with (verified models only). */
    val testedFirmware: List<String> = emptyList(),
    /** Turning the bike on and off; null where the checked bike's profile doesn't offer it, or before the check. */
    val power: PowerUiState? = null,
) {
    val verified: Boolean get() = checked && verification == Verification.VERIFIED

    /** Checked, and not verified: the screens say so. Before the check, neither applies. */
    val unverified: Boolean get() = checked && verification != Verification.VERIFIED
    val connected: Boolean get() = connection == Connection.CONNECTED
}

/** The battery gauge is green from this charge (%) on. */
const val BATTERY_GOOD_FROM: Int = 50

/** The battery gauge is red below this charge (%); yellow in between. */
const val BATTERY_LOW_BELOW: Int = 20

/** The bike's own limit after it is switched on. */
val BIKE_DEFAULT_KMH: Int = SpeedLimit.BIKE_DEFAULT.kmh

/** The limit for e-bikes in the EU. */
val LEGAL_LIMIT_KMH: Int = SpeedLimit.LEGAL_LIMIT_KMH

/** True above the 25 km/h limit for e-bikes in the EU. */
fun isAboveLegalLimit(kmh: Int): Boolean = kmh > LEGAL_LIMIT_KMH
