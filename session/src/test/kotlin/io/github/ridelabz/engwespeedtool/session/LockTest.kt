@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Locking and unlocking, which switch the bike off and on: only where the bike's profile offers it, through the
 * write gate (a lock with the bike on and standing still, an unlock with it off), confirmed by the lock state's
 * answer and a status showing the bike off or on. Nothing is retried, and nothing sends one by itself. An unlock
 * is a power-on like any other: "keep this speed" and "keep this assist level" set their values again after it.
 */
class LockTest {
    private suspend fun Rig.ready() {
        session.state.first { it is SessionState.Ready }
        // A status that arrived on this connection after the checks (the one kept from them doesn't count).
        session.ride.drop(1).first { it.status != null && !it.stale }
    }

    /** The frames written after the identity read. */
    private fun FakeBike.sent() = commands.filterNot { it == BikeCommand.ReadIdentity }

    private fun Rig.outcomes() = values("state", "command")

    private fun Rig.refusals() = values("state", "refused")

    private fun Rig.txs() =
        records()
            .filter { it.s("event") == "tx" }
            .map { it.s("command") }
            .filterNot { it == "read_identity" }

    private fun TestScope.rig(bike: FakeBike = FakeBike(backgroundScope)) = Rig(this, bike)

    /** Waits until the session's ride data shows [power] from a status that is up to date. */
    private suspend fun Rig.shows(power: PowerState) = session.ride.first { it.power == power && !it.stale }

    @Test
    fun `a lock is written once and confirmed by the lock state and a status showing the bike off`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(true))
            assertEquals(listOf(BikeCommand.SetLocked(true)), rig.bike.sent())
            assertTrue(rig.bike.locked)
            assertEquals(FakePower.OFF, rig.bike.power)
            assertEquals(PowerState.OFF, rig.session.ride.value.power)
            assertEquals(listOf("lock"), rig.txs())
            assertEquals(listOf("confirmed"), rig.outcomes())
            // The bike's answer is logged in full: a one-byte frame of a known command.
            val answers = rig.records().filter { it.s("event") == "rx" && "command" in it && it.s("command") == "0109" }
            assertEquals(listOf("456E01090001020D9A7765"), answers.map { it.s("raw_hex") })
            // Off: what was set says nothing any more.
            assertEquals(LimitState.Unknown, rig.session.limit.value)
            assertEquals(AssistState.Idle, rig.session.assist.value)
        }

    @Test
    fun `an unlock switches the bike on again, confirmed the same way, and the limit reads as after a restart`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(true))
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(false))
            assertFalse(rig.bike.locked)
            assertEquals(FakePower.ON, rig.bike.power)
            assertEquals(PowerState.ON, rig.session.ride.value.power)
            assertEquals(listOf("set_speed_limit_32", "lock", "unlock"), rig.txs())
            assertEquals(listOf("confirmed", "confirmed", "confirmed"), rig.outcomes())
            // The app claims nothing about the limit after an unlock; nothing is set again by itself.
            assertEquals(LimitState.Restarted, rig.session.limit.value)
            delay(2.minutes)
            assertEquals(3, rig.bike.sent().size)
        }

    @Test
    fun `a lock needs the bike on and standing still, an unlock needs it off, and a refusal writes nothing`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Refused(Refusal.BIKE_NOT_OFF), rig.session.setLocked(false))
            bike.ride = bike.ride.copy(speedKmh = 6.5)
            rig.session.ride.first { it.status?.speedKmh?.value == 6.5 }
            assertEquals(CommandOutcome.Refused(Refusal.MOVING), rig.session.setLocked(true))
            bike.ride = bike.ride.copy(speedKmh = 0.0)
            rig.session.ride.first { it.status?.speedKmh?.value == 0.0 }
            bike.switchOff()
            rig.shows(PowerState.OFF)
            assertEquals(CommandOutcome.Refused(Refusal.BIKE_NOT_ON), rig.session.setLocked(true))
            assertEquals(listOf("bike_not_off", "moving", "bike_not_on"), rig.refusals())
            assertTrue(bike.sent().isEmpty())
            // Off at the display: the unlock goes out (whether the real bike switches on then isn't known).
            assertFalse(rig.session.setLocked(false) is CommandOutcome.Refused)
            assertEquals(listOf(BikeCommand.SetLocked(false)), bike.sent())
        }

    @Test
    fun `a status that came before the bike's answer confirms a lock`() =
        runTest {
            // Off, the bike reports only every 30 s here: only the status it sends at once can confirm in time.
            val bike = FakeBike(backgroundScope, timing = FakeTiming(statusOff = 30.seconds))
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(true))
            val after =
                rig
                    .records()
                    .dropWhile { !(it.s("event") == "tx" && it.s("command") == "lock") }
                    .filter { it.s("event") == "rx" && "command" in it }
                    .map { it.s("command") }
            assertTrue(after.indexOf("2A63") in 0 until after.indexOf("0109"), "the status first: $after")
        }

    @Test
    fun `an unlock waits for a status that isn't out of date, even with the bike off`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.switchOff()
            rig.shows(PowerState.OFF)
            // With the bike off a status comes every 5 s: still up to date in between.
            delay(4.seconds)
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(false))
            assertTrue(rig.refusals().isEmpty())
            bike.switchOff()
            rig.shows(PowerState.OFF)
            bike.sendsStatus = false
            rig.session.ride.first { it.stale }
            assertEquals(CommandOutcome.Refused(Refusal.BIKE_NOT_OFF), rig.session.setLocked(false))
            assertEquals(1, bike.sent().size)
        }

    @Test
    fun `an answer without the status following, or no answer, is not a confirmation, and nothing is retried`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.acts = false // answers, but stays on
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setLocked(true))
            assertEquals(FakePower.ON, bike.power)
            bike.acts = true
            bike.echoes = false // switches off, but doesn't answer
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setLocked(true))
            assertEquals(FakePower.OFF, bike.power)
            bike.echoes = true
            bike.echoDelay = 2100.milliseconds
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setLocked(false), "answer too late")
            delay(1.minutes)
            assertEquals(3, bike.sent().size, "never sent again")
            assertEquals(listOf("not_confirmed", "not_confirmed", "not_confirmed"), rig.outcomes())
        }

    @Test
    fun `a bike whose profile doesn't offer it is refused, verified or not, and so is a check`() =
        runTest {
            val reference = File("../protocol/src/main/resources/profiles/engwe-e26-3-pro.json").readText()
            val without =
                ProfileRegistry(listOf(ProfileRegistry.parse(reference.replace("  \"lock_control\": true,\n", ""))))
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike, profiles = without)
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_SUPPORTED), rig.session.setLocked(true))

            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL)
            val unverified = Rig(this, other)
            unverified.acknowledged += other.address
            unverified.session.start(other.address)
            unverified.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_SUPPORTED), unverified.session.setLocked(true))

            val checked = rig(FakeBike(backgroundScope))
            checked.session.check(checked.bike.address)
            checked.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_READY), checked.session.setLocked(true))
            assertTrue(bike.sent().isEmpty() && other.sent().isEmpty() && checked.bike.sent().isEmpty())
        }

    @Test
    fun `without the rider's acceptance of the current text nothing is locked, but an unlock goes as 25 does`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.accepted = false
            rig.acceptedEarlier = true
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACCEPTED), rig.session.setLocked(true))
            assertTrue(rig.bike.sent().isEmpty())
            rig.bike.switchOff()
            rig.shows(PowerState.OFF)
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(false))
            assertEquals(listOf(BikeCommand.SetLocked(false)), rig.bike.sent())
            rig.acceptedEarlier = false
            rig.bike.switchOff()
            rig.shows(PowerState.OFF)
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACCEPTED), rig.session.setLocked(false), "never accepted")
            assertEquals(1, rig.bike.sent().size)
        }

    @Test
    fun `an unlock on an earlier acceptance sets nothing else again, as the kept values need the current text`() =
        runTest {
            val rig = rig()
            rig.accepted = false
            rig.acceptedEarlier = true
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.keepAssistLevel(rig.bike.address, checkNotNull(AssistLevel.of(2)))
            rig.bike.switchOff() // off at its display, before the app connects
            rig.session.start(rig.bike.address)
            rig.session.state.first { it is SessionState.Ready }
            rig.shows(PowerState.OFF)
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(false))
            delay(2.minutes)
            assertEquals(listOf("unlock"), rig.txs())
            assertEquals(listOf("restart"), rig.values("state", "reapply"))
            assertEquals(listOf("restart"), rig.values("state", "reapply_assist"))
            assertEquals(listOf("not_accepted", "not_accepted"), rig.refusals())
            assertEquals(SpeedLimit.KMH_25, rig.bike.speedLimit)
        }

    @Test
    fun `after an unlock the kept speed and assist level are set again once, as after any power-on`() =
        runTest {
            val rig = rig()
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.keepAssistLevel(rig.bike.address, checkNotNull(AssistLevel.of(2)))
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.assist.first { it is AssistState.Confirmed }
            delay(2.minutes) // past the wait between re-applies
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(true))
            assertEquals(LimitState.Unknown, rig.session.limit.value)
            delay(10.seconds)
            assertEquals(CommandOutcome.Confirmed, rig.session.setLocked(false))
            assertEquals(LimitState.Restarted, rig.session.limit.value, "back to the bike's own limit")
            rig.session.limit.first { it == LimitState.Confirmed(SpeedLimit.KMH_32, Reapply.RESTART) }
            rig.session.assist.first { it == AssistState.Confirmed(checkNotNull(AssistLevel.of(2)), Reapply.RESTART) }
            delay(3.minutes)
            assertEquals(
                listOf(
                    "set_speed_limit_32",
                    "set_assist_level_2",
                    "lock",
                    "unlock",
                    "set_speed_limit_32",
                    "set_assist_level_2",
                ),
                rig.txs(),
            )
            assertEquals(listOf("reconnect", "restart"), rig.values("state", "reapply"))
            assertEquals(listOf("reconnect", "restart"), rig.values("state", "reapply_assist"))
            assertEquals(SpeedLimit.KMH_32, rig.bike.speedLimit)
            // Nothing locks or unlocks by itself.
            assertEquals(1, rig.txs().count { it == "lock" })
            assertEquals(1, rig.txs().count { it == "unlock" })
        }

    @Test
    fun `a connection lost during a lock ends it as failed`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.echoes = false
            bike.acts = false
            bike.withholdNextWriteResponse = true
            val outcome = async { rig.session.setLocked(true) }
            delay(200.milliseconds)
            bike.dropLink()
            assertEquals(CommandOutcome.Failed, outcome.await())
            assertEquals(listOf(BikeCommand.SetLocked(true)), bike.sent())
        }
}
