package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.Lights
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.Reading
import io.github.ridelabz.engwespeedtool.protocol.StatusReport
import io.github.ridelabz.engwespeedtool.protocol.Verification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The write gate: every combination of its inputs, against the rule written as conditions that must all hold. */
class WriteGateTest {
    private val verifications = listOf(null, Verification.VERIFIED, Verification.UNVERIFIED, Verification.INCOMPATIBLE)
    private val flags = listOf(false, true)

    /** A command the gate sees: a speed other than 25, back to 25, the lights, an assist level, a lock or an unlock. */
    private enum class Kind(
        val needsCaveat: Boolean,
        val backToDefault: Boolean,
        val turnsOn: Boolean = false,
        val turnsOff: Boolean = false,
    ) {
        OTHER_SPEED(true, false),
        BACK_TO_25(false, true),
        LIGHTS(false, false),
        ASSIST(false, false),
        LOCK(false, false, turnsOff = true),
        UNLOCK(false, false, turnsOn = true),
    }

    @Test
    fun `allows a command exactly when every condition holds`() {
        var allowed = 0
        var cases = 0
        for (kind in Kind.entries) {
            for (accepted in flags) {
                // An acceptance of the current text is also one of some text.
                for (everAccepted in flags.filter { it || !accepted }) {
                    for (verification in verifications) {
                        for (acknowledged in flags) {
                            for (everAcknowledged in flags.filter { it || !acknowledged }) {
                                for (caveatSeen in flags) {
                                    for (bikeOn in flags) {
                                        for ((supported, bikeOff, still) in states) {
                                            // Written independently of the gate's order: a conjunction of what must hold.
                                            // Back to 25 and an unlock leave the bike as it starts: an earlier
                                            // acceptance or acknowledgement is enough for them.
                                            val back = kind.backToDefault || kind.turnsOn
                                            val consented =
                                                (accepted || (back && everAccepted)) &&
                                                    when (verification) {
                                                        Verification.VERIFIED -> {
                                                            !kind.needsCaveat || caveatSeen
                                                        }

                                                        Verification.UNVERIFIED -> {
                                                            acknowledged ||
                                                                (back && everAcknowledged)
                                                        }

                                                        Verification.INCOMPATIBLE, null -> {
                                                            false
                                                        }
                                                    }
                                            // An unlock needs the bike off, everything else needs it on; a lock
                                            // also needs it standing still.
                                            val power = if (kind.turnsOn) bikeOff else bikeOn
                                            val expectAllowed =
                                                consented && supported && power && (!kind.turnsOff || still)
                                            val refusal =
                                                WriteGate.refusal(
                                                    kind.needsCaveat,
                                                    kind.backToDefault,
                                                    accepted,
                                                    everAccepted,
                                                    verification,
                                                    supported,
                                                    acknowledged,
                                                    everAcknowledged,
                                                    caveatSeen,
                                                    bikeOn,
                                                    turnsOn = kind.turnsOn,
                                                    bikeOff = bikeOff,
                                                    turnsOff = kind.turnsOff,
                                                    standingStill = still,
                                                )
                                            val case =
                                                "$kind accepted=$accepted/$everAccepted $verification " +
                                                    "ack=$acknowledged/$everAcknowledged caveat=$caveatSeen " +
                                                    "on=$bikeOn off=$bikeOff still=$still supported=$supported"
                                            assertEquals(expectAllowed, refusal == null, case)
                                            // The reason names a condition that indeed fails.
                                            when (refusal) {
                                                null -> {}

                                                Refusal.NOT_ACCEPTED -> {
                                                    assertFalse(accepted || (back && everAccepted), case)
                                                }

                                                Refusal.NOT_READY -> {
                                                    assertTrue(
                                                        verification == null ||
                                                            verification == Verification.INCOMPATIBLE,
                                                        case,
                                                    )
                                                }

                                                Refusal.NOT_SUPPORTED -> {
                                                    assertFalse(supported, case)
                                                }

                                                Refusal.NOT_ACKNOWLEDGED -> {
                                                    assertTrue(
                                                        verification == Verification.UNVERIFIED &&
                                                            !(acknowledged || (back && everAcknowledged)),
                                                        case,
                                                    )
                                                }

                                                Refusal.CAVEAT_NOT_SEEN -> {
                                                    assertTrue(
                                                        verification == Verification.VERIFIED && kind.needsCaveat &&
                                                            !caveatSeen,
                                                        case,
                                                    )
                                                }

                                                Refusal.BIKE_NOT_ON -> {
                                                    assertTrue(!kind.turnsOn && !bikeOn, case)
                                                }

                                                Refusal.BIKE_NOT_OFF -> {
                                                    assertTrue(kind.turnsOn && !bikeOff, case)
                                                }

                                                Refusal.MOVING -> {
                                                    assertTrue(kind.turnsOff && !still, case)
                                                }

                                                Refusal.BUSY -> {
                                                    error("the gate never says busy: the sending slot does")
                                                }
                                            }
                                            cases++
                                            if (refusal == null) allowed++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 6 kinds x 3 acceptance states x 4 verifications x 3 acknowledgement states x 2 x 2 x 2 x 2 x 2.
        assertEquals(6 * 3 * 4 * 3 * 2 * 2 * 2 * 2 * 2, cases)
        assertTrue(allowed > 0)
    }

    /** Supported, reported off, standing still: every combination. */
    private val states = flags.flatMap { a -> flags.flatMap { b -> flags.map { c -> Triple(a, b, c) } } }

    @Test
    fun `an unlock needs the bike off, a lock needs it on and standing still, after the consent`() {
        fun gate(
            kind: Kind,
            bikeOn: Boolean,
            bikeOff: Boolean,
            still: Boolean = true,
            accepted: Boolean = true,
        ) = WriteGate.refusal(
            kind.needsCaveat,
            kind.backToDefault,
            accepted,
            accepted,
            Verification.VERIFIED,
            supported = true,
            acknowledged = false,
            everAcknowledged = false,
            caveatSeen = true,
            bikeOn = bikeOn,
            turnsOn = kind.turnsOn,
            bikeOff = bikeOff,
            turnsOff = kind.turnsOff,
            standingStill = still,
        )
        assertEquals(null, gate(Kind.UNLOCK, bikeOn = false, bikeOff = true))
        assertEquals(Refusal.BIKE_NOT_OFF, gate(Kind.UNLOCK, bikeOn = true, bikeOff = false))
        assertEquals(Refusal.BIKE_NOT_OFF, gate(Kind.UNLOCK, bikeOn = false, bikeOff = false), "nothing known")
        assertEquals(null, gate(Kind.LOCK, bikeOn = true, bikeOff = false))
        assertEquals(Refusal.BIKE_NOT_ON, gate(Kind.LOCK, bikeOn = false, bikeOff = true))
        assertEquals(Refusal.MOVING, gate(Kind.LOCK, bikeOn = true, bikeOff = false, still = false))
        assertEquals(Refusal.BIKE_NOT_ON, gate(Kind.LIGHTS, bikeOn = false, bikeOff = true), "only an unlock")
        assertEquals(null, gate(Kind.LIGHTS, bikeOn = true, bikeOff = false, still = false), "only a lock")
        for (kind in listOf(Kind.LOCK, Kind.UNLOCK)) {
            assertEquals(Refusal.NOT_ACCEPTED, gate(kind, bikeOn = false, bikeOff = false, accepted = false))
        }
    }

    @Test
    fun `an unlock goes on an earlier acceptance or acknowledgement, as going back to 25 does, a lock doesn't`() {
        fun gate(
            kind: Kind,
            verification: Verification,
        ) = WriteGate.refusal(
            kind.needsCaveat,
            kind.backToDefault,
            accepted = false,
            everAccepted = true,
            verification = verification,
            supported = true,
            acknowledged = false,
            everAcknowledged = true,
            caveatSeen = true,
            bikeOn = !kind.turnsOn,
            turnsOn = kind.turnsOn,
            bikeOff = kind.turnsOn,
            turnsOff = kind.turnsOff,
            standingStill = true,
        )
        for (verification in listOf(Verification.VERIFIED, Verification.UNVERIFIED)) {
            assertEquals(null, gate(Kind.UNLOCK, verification), "$verification")
            assertEquals(null, gate(Kind.BACK_TO_25, verification), "$verification")
            for (kind in listOf(Kind.LOCK, Kind.LIGHTS, Kind.ASSIST, Kind.OTHER_SPEED)) {
                assertEquals(Refusal.NOT_ACCEPTED, gate(kind, verification), "$kind $verification")
            }
        }
    }

    @Test
    fun `going back to 25 needs only an earlier acceptance and acknowledgement, no other speed or setting does`() {
        fun gate(
            kind: Kind,
            accepted: Boolean = false,
            everAccepted: Boolean = true,
            verification: Verification? = Verification.VERIFIED,
            acknowledged: Boolean = false,
            everAcknowledged: Boolean = true,
        ) = WriteGate.refusal(
            kind.needsCaveat,
            kind.backToDefault,
            accepted,
            everAccepted,
            verification,
            supported = true,
            acknowledged,
            everAcknowledged,
            caveatSeen = true,
            bikeOn = true,
            turnsOn = false,
            bikeOff = false,
            turnsOff = false,
            standingStill = false,
        )
        assertEquals(null, gate(Kind.BACK_TO_25), "a new text not yet accepted")
        assertEquals(null, gate(Kind.BACK_TO_25, verification = Verification.UNVERIFIED), "nor acknowledged again")
        assertEquals(Refusal.NOT_ACCEPTED, gate(Kind.OTHER_SPEED))
        assertEquals(Refusal.NOT_ACCEPTED, gate(Kind.LIGHTS))
        assertEquals(Refusal.NOT_ACCEPTED, gate(Kind.ASSIST))
        assertEquals(Refusal.NOT_ACCEPTED, gate(Kind.BACK_TO_25, everAccepted = false), "never accepted at all")
        assertEquals(
            Refusal.NOT_ACKNOWLEDGED,
            gate(Kind.BACK_TO_25, verification = Verification.UNVERIFIED, everAcknowledged = false),
            "never acknowledged",
        )
        assertEquals(
            Refusal.NOT_ACKNOWLEDGED,
            gate(Kind.LIGHTS, accepted = true, verification = Verification.UNVERIFIED),
        )
    }

    @Test
    fun `the consent is asked for before the bike's state, so the app can ask for what is missing`() {
        fun gate(
            needsCaveat: Boolean = true,
            accepted: Boolean = true,
            verification: Verification? = Verification.VERIFIED,
            acknowledged: Boolean = false,
            caveatSeen: Boolean = true,
            bikeOn: Boolean = true,
            supported: Boolean = true,
        ) = WriteGate.refusal(
            needsCaveat,
            backToDefault = false,
            accepted = accepted,
            everAccepted = accepted,
            verification = verification,
            supported = supported,
            acknowledged = acknowledged,
            everAcknowledged = acknowledged,
            caveatSeen = caveatSeen,
            bikeOn = bikeOn,
            turnsOn = false,
            bikeOff = false,
            turnsOff = false,
            standingStill = false,
        )

        assertEquals(null, gate(), "verified, caveat seen, bike on")
        assertEquals(Refusal.CAVEAT_NOT_SEEN, gate(caveatSeen = false), "verified isn't 'firmware tested'")
        assertEquals(Refusal.CAVEAT_NOT_SEEN, gate(caveatSeen = false, bikeOn = false))
        assertEquals(null, gate(needsCaveat = false, caveatSeen = false), "lights")
        assertEquals(Refusal.NOT_ACKNOWLEDGED, gate(verification = Verification.UNVERIFIED))
        assertEquals(Refusal.NOT_ACKNOWLEDGED, gate(needsCaveat = false, verification = Verification.UNVERIFIED))
        assertEquals(null, gate(verification = Verification.UNVERIFIED, acknowledged = true, caveatSeen = false))
        assertEquals(Refusal.NOT_READY, gate(verification = Verification.INCOMPATIBLE, acknowledged = true))
        assertEquals(Refusal.NOT_READY, gate(verification = null))
        assertEquals(Refusal.NOT_ACCEPTED, gate(accepted = false, verification = null))
        assertEquals(Refusal.BIKE_NOT_ON, gate(bikeOn = false))
        // A command the bike doesn't offer is refused as such, before asking for a consent it wouldn't need.
        assertEquals(Refusal.NOT_SUPPORTED, gate(needsCaveat = false, supported = false, bikeOn = false))
        assertEquals(
            Refusal.NOT_SUPPORTED,
            gate(needsCaveat = false, verification = Verification.UNVERIFIED, supported = false),
        )
        assertEquals(Refusal.NOT_READY, gate(needsCaveat = false, verification = null, supported = false))
    }

    @Test
    fun `the bike reports on only with a recent readable status and both power flags on`() {
        val status =
            StatusReport(
                speedKmh = null,
                maxSpeedKmh = null,
                odometerKm = null,
                assistLevel = null,
                batteryPercent = null,
                lights = Reading(Lights.OFF, true),
                power = Reading(PowerState.ON, true),
                temperatureRaw = null,
                batteryHealthRaw = null,
                voltageV = null,
                rideTimeMinutes = null,
            )

        fun ride(
            status: StatusReport? = null,
            unreadable: Boolean = false,
            stale: Boolean = false,
            power: PowerState = PowerState.ON,
            powerVerified: Boolean = true,
        ) = RideData(status, unreadable, stale, power, powerVerified, 0)

        assertTrue(WriteGate.reportsOn(ride(status)))
        assertTrue(WriteGate.reportsOn(ride(status, powerVerified = false)), "unverified flags can still say on")
        assertFalse(WriteGate.reportsOn(ride()), "no status yet")
        assertFalse(WriteGate.reportsOn(ride(status, stale = true)))
        assertFalse(WriteGate.reportsOn(ride(null, unreadable = true)))
        assertFalse(WriteGate.reportsOn(ride(status, unreadable = true)), "frames that don't fit the layout")
        for (power in PowerState.entries - PowerState.ON) {
            assertFalse(WriteGate.reportsOn(ride(status, power = power)), "$power")
        }
        assertFalse(WriteGate.reportsOn(RideData.NONE))
    }

    @Test
    fun `the bike reports off only with an up-to-date readable status and both verified power flags off`() {
        val status =
            StatusReport(
                speedKmh = null,
                maxSpeedKmh = null,
                odometerKm = null,
                assistLevel = null,
                batteryPercent = null,
                lights = null,
                power = Reading(PowerState.OFF, true),
                temperatureRaw = null,
                batteryHealthRaw = null,
                voltageV = null,
                rideTimeMinutes = null,
            )

        fun ride(
            status: StatusReport? = null,
            unreadable: Boolean = false,
            stale: Boolean = false,
            power: PowerState = PowerState.OFF,
            powerVerified: Boolean = true,
        ) = RideData(status, unreadable, stale, power, powerVerified, 0)

        assertTrue(WriteGate.reportsOff(ride(status)))
        assertFalse(WriteGate.reportsOff(ride(status, powerVerified = false)), "unverified flags never say off")
        assertFalse(WriteGate.reportsOff(ride()), "no status yet")
        assertFalse(WriteGate.reportsOff(ride(status, stale = true)))
        assertFalse(WriteGate.reportsOff(ride(null, unreadable = true)))
        assertFalse(WriteGate.reportsOff(ride(status, unreadable = true)), "frames that don't fit the layout")
        for (power in PowerState.entries - PowerState.OFF) {
            assertFalse(WriteGate.reportsOff(ride(status, power = power)), "$power")
        }
        assertFalse(WriteGate.reportsOff(RideData.NONE))
    }
}
