package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.Verification

/**
 * What the rider agreed to. A session asks it itself, right before every speed or light command; nothing
 * the caller passes can answer for it.
 */
public interface Consent {
    /**
     * The rider accepted the current risk text. Needed for every command but going back to 25 km/h and switching
     * the bike on (an unlock), which leave the bike as it starts.
     */
    public fun riskAccepted(): Boolean

    /**
     * The rider accepted the risk text, maybe an earlier version. Without it the session doesn't even read the
     * bike's identity; with it, going back to 25 km/h and switching the bike on stay possible until the current
     * text is accepted.
     */
    public fun riskEverAccepted(): Boolean = riskAccepted()

    /**
     * The rider saw, for the current text, that speed changes were tested on one model and firmware only.
     * Needed before a speed change on a verified bike: verified means the model matched, not the firmware.
     */
    public fun firmwareCaveatSeen(): Boolean

    /** The rider acknowledged, for the current text, that [bike] is unverified. Needed before any command to it. */
    public fun unverifiedAcknowledged(bike: BikeAddress): Boolean

    /**
     * The rider acknowledged [bike] as unverified for some text version: enough to go back to 25 km/h on it, or to
     * switch it on.
     */
    public fun unverifiedEverAcknowledged(bike: BikeAddress): Boolean = unverifiedAcknowledged(bike)
}

/** Why a speed, light, assist-level or lock command wasn't sent. Nothing was written. */
public enum class Refusal {
    /** The rider hasn't accepted the current risk text. */
    NOT_ACCEPTED,

    /** No connected and checked bike (connecting, reconnecting, stopped, or a bike that failed a check). */
    NOT_READY,

    /**
     * The connected bike's profile doesn't offer this command: no assist levels, or a level above them; no lock
     * control.
     */
    NOT_SUPPORTED,

    /** An unverified bike, and the rider hasn't acknowledged it for the current text. */
    NOT_ACKNOWLEDGED,

    /** A speed change on a verified bike, and the rider hasn't seen the firmware caveat for the current text. */
    CAVEAT_NOT_SEEN,

    /** The bike doesn't report that it is on: no recent readable status, or its power flags don't read "on". */
    BIKE_NOT_ON,

    /**
     * An unlock (switching the bike on), and the bike doesn't report that it is off: no readable status that isn't
     * out of date, or its verified power flags don't read "off".
     */
    BIKE_NOT_OFF,

    /** A lock (switching the bike off), and the bike doesn't report standing still by a recent, verified 0 km/h. */
    MOVING,

    /** Another command is being sent. */
    BUSY,
}

/** How a speed, light, assist-level or lock command ended. */
public sealed interface CommandOutcome {
    /**
     * The bike echoed the command within the time limit (for lights on a bike whose lights reading is
     * verified, and for every assist level, its status also followed; a lock or an unlock is answered with the
     * lock state, and its status must then show the bike off or on). An echo shows that the bike took the
     * command, not what it does.
     */
    public data object Confirmed : CommandOutcome

    /** Written and acknowledged, but not confirmed in time: the app can't tell whether it took effect. */
    public data object NotConfirmed : CommandOutcome

    /** The write failed or the connection ended during it: it may or may not have reached the bike. */
    public data object Failed : CommandOutcome

    /** Not sent. */
    public data class Refused(
        val reason: Refusal,
    ) : CommandOutcome
}

/**
 * The one rule for every speed, light, assist-level or lock command: all of it must hold right before the write.
 * The order decides which reason is reported when several apply; the rider's consent comes before the bike's
 * state, so that the app can ask for what is missing. That no other command is being sent is checked before
 * this, by the session's one sending slot. Every command needs the bike reported on, except an unlock, which
 * needs it reported off; a lock also needs it standing still. Going back to 25 km/h and an unlock leave the bike
 * as it starts (on, at 25 km/h): for them an earlier acceptance or acknowledgement is enough.
 */
internal object WriteGate {
    fun refusal(
        /** A speed change other than back to the bike's default (25 km/h). */
        needsCaveat: Boolean,
        /** Going back to the bike's default (25 km/h): an earlier acceptance or acknowledgement is enough. */
        backToDefault: Boolean,
        accepted: Boolean,
        /** The rider accepted some version of the risk text. */
        everAccepted: Boolean,
        /** The connected and checked bike's verification; null when there is none. */
        verification: Verification?,
        /** The connected bike's profile offers the command (an assist level up to its number of levels). */
        supported: Boolean,
        /** The rider acknowledged this (unverified) bike for the current text. */
        acknowledged: Boolean,
        /** The rider acknowledged this (unverified) bike for some text version. */
        everAcknowledged: Boolean,
        caveatSeen: Boolean,
        /** A recent readable status reports the bike on ([reportsOn]). */
        bikeOn: Boolean,
        /**
         * The command switches the bike on (an unlock): it needs the bike off instead of on, and, like going back to
         * 25 km/h, an earlier acceptance or acknowledgement is enough.
         */
        turnsOn: Boolean,
        /** A readable status that isn't out of date reports the bike off by verified power flags ([reportsOff]). */
        bikeOff: Boolean,
        /** The command switches the bike off (a lock): it also needs the bike standing still. */
        turnsOff: Boolean,
        /** A recent readable status reports 0 km/h by a verified speed reading. */
        standingStill: Boolean,
    ): Refusal? {
        // What leaves the bike as it starts needs no newer acceptance or acknowledgement.
        val restores = backToDefault || turnsOn
        return when {
            !accepted && !(restores && everAccepted) -> {
                Refusal.NOT_ACCEPTED
            }

            verification == null || verification == Verification.INCOMPATIBLE -> {
                Refusal.NOT_READY
            }

            !supported -> {
                Refusal.NOT_SUPPORTED
            }

            verification == Verification.UNVERIFIED && !acknowledged && !(restores && everAcknowledged) -> {
                Refusal.NOT_ACKNOWLEDGED
            }

            needsCaveat && verification == Verification.VERIFIED && !caveatSeen -> {
                Refusal.CAVEAT_NOT_SEEN
            }

            turnsOn && !bikeOff -> {
                Refusal.BIKE_NOT_OFF
            }

            !turnsOn && !bikeOn -> {
                Refusal.BIKE_NOT_ON
            }

            turnsOff && !standingStill -> {
                Refusal.MOVING
            }

            else -> {
                null
            }
        }
    }

    /**
     * Whether [ride] reports the bike on: a readable status, not out of date, both power flags on. On a bike
     * whose flags aren't verified this can only refuse more often, never less.
     */
    fun reportsOn(ride: RideData): Boolean =
        ride.status != null && !ride.stale && !ride.unreadable && ride.power == PowerState.ON

    /**
     * Whether [ride] reports the bike off: a readable status, not out of date (with the bike off a status arrives
     * only about every 5 s), both power flags off, and their meaning verified for this bike.
     */
    fun reportsOff(ride: RideData): Boolean =
        ride.status != null && !ride.stale && !ride.unreadable && ride.power == PowerState.OFF && ride.powerVerified
}
