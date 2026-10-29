@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Hex
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Assist-level commands: only levels the bike's profile offers, through the write gate, echo and status. */
class AssistLevelTest {
    private suspend fun Rig.ready() {
        session.state.first { it is SessionState.Ready }
        // A status that arrived on this connection after the checks (the one kept from them doesn't count).
        session.ride.drop(1).first { it.status != null && !it.stale }
    }

    /** The frames written after the identity read. */
    private fun FakeBike.sent() = commands.filterNot { it == BikeCommand.ReadIdentity }

    private fun Rig.outcomes() = values("state", "command")

    private fun Rig.refusals() = values("state", "refused")

    private fun level(n: Int) = checkNotNull(AssistLevel.of(n))

    /** The bundled profiles plus an unverified one for [model] offering [levels] assist levels. */
    private fun withUnverified(
        model: String,
        levels: Int,
    ): ProfileRegistry {
        val reference = File("../protocol/src/main/resources/profiles/engwe-e26-3-pro.json").readText()
        val unverified =
            reference
                .replace("ENGWE_Omni_ebike1100EU317", model)
                .replace("\"verified\": true", "\"verified\": false")
                .replace("  \"tested_firmware\": [\"218CAN\"],\n", "")
                .replace("  \"lock_control\": true,\n", "")
                .replace("\"assist_levels\": 5", "\"assist_levels\": $levels")
        return ProfileRegistry(ProfileRegistry.bundled().profiles + ProfileRegistry.parse(unverified))
    }

    private fun TestScope.rig(bike: FakeBike = FakeBike(backgroundScope)) = Rig(this, bike)

    @Test
    fun `a level is written once and confirmed by its echo and the status that follows`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            assertEquals(AssistState.Idle, rig.session.assist.value)
            val sending = async { rig.session.setAssistLevel(level(4)) }
            rig.session.assist.first { it == AssistState.Sending(level(4)) }
            assertEquals(CommandOutcome.Confirmed, sending.await())
            assertEquals(AssistState.Confirmed(level(4)), rig.session.assist.value)
            assertEquals(listOf(BikeCommand.SetAssistLevel(level(4))), rig.bike.sent())
            assertEquals(
                4,
                rig.session.ride.value.status!!
                    .assistLevel!!
                    .value,
            )
            val tx =
                rig
                    .records()
                    .filter { it.s("event") == "tx" }
                    .map { it.s("command") }
                    .drop(1)
            assertEquals(listOf("set_assist_level_4"), tx)
            assertEquals(listOf("confirmed"), rig.outcomes())
            // The bike's echo is logged in full: a one-byte frame of a known command.
            val echoes = rig.records().filter { it.s("event") == "rx" && "command" in it && it.s("command") == "1009" }
            assertEquals(listOf("456E10090001040CE67765"), echoes.map { it.s("raw_hex") })
            assertEquals(CommandOutcome.Confirmed, rig.session.setAssistLevel(level(0)))
            assertEquals(2, rig.bike.sent().size)
        }

    @Test
    fun `an echo without the status showing the level, or no echo, is not a confirmation, and nothing is retried`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.acts = false // echoes, but the level stays
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setAssistLevel(level(5)))
            assertEquals(AssistState.NotConfirmed(level(5)), rig.session.assist.value)
            bike.acts = true
            bike.echoes = false // acts, but no echo
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setAssistLevel(level(3)))
            bike.echoes = true
            bike.echoDelay = 2100.milliseconds
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setAssistLevel(level(1)), "echo too late")
            delay(1.minutes)
            assertEquals(3, bike.sent().size, "never sent again")
            assertEquals(listOf("not_confirmed", "not_confirmed", "not_confirmed"), rig.outcomes())
        }

    @Test
    fun `a level the bike already shows is confirmed by a status after the write`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride = bike.ride.copy(assistLevel = 3)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.acts = false
            // The level was already 3: a later status shows it, so the bike is at the level asked for.
            assertEquals(CommandOutcome.Confirmed, rig.session.setAssistLevel(level(3)))
            bike.sendsStatus = false
            delay(4.seconds)
            assertEquals(
                CommandOutcome.Refused(Refusal.BIKE_NOT_ON),
                rig.session.setAssistLevel(level(2)),
                "no recent status",
            )
        }

    @Test
    fun `the status from before the write never confirms it, even when it shows the level`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride = bike.ride.copy(assistLevel = 3)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.acts = false
            // The last status (still recent) shows 3; no status comes after the write.
            bike.sendsStatus = false
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setAssistLevel(level(3)))
            assertEquals(AssistState.NotConfirmed(level(3)), rig.session.assist.value)
            assertEquals(listOf(BikeCommand.SetAssistLevel(level(3))), bike.sent(), "echoed, written once")
        }

    @Test
    fun `a status still showing the old level doesn't end the wait, as on the bike`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            val old =
                checkNotNull(
                    rig.session.ride.value.status
                        ?.assistLevel
                        ?.value,
                )
            assertEquals(CommandOutcome.Confirmed, rig.session.setAssistLevel(level(5)))
            // In the log: between the bike's echo and the outcome, a status frame that still shows the old level
            // (payload byte 18, after the 6 bytes of the frame's start).
            val records = rig.records()
            val echo = records.indexOfFirst { it.s("event") == "rx" && "command" in it && it.s("command") == "1009" }
            val outcome = records.indexOfFirst { it.s("event") == "state" && it.s("value") == "confirmed" }
            assertTrue(echo in 0 until outcome, "echo $echo, outcome $outcome")
            val levels =
                records
                    .subList(echo, outcome)
                    .filter { it.s("event") == "rx" && "command" in it && it.s("command") == "2A63" }
                    .map { it.s("raw_hex").substring(48, 50).toInt(16) }
            assertTrue(old in levels, "a status after the echo still showed $old: $levels")
            assertEquals(5, levels.last())
        }

    @Test
    fun `a status of a bike that isn't on doesn't confirm a level`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setAssistLevel(level(4)) }
            rig.session.assist.first { it is AssistState.Sending }
            delay(200.milliseconds) // echoed; the level shows from the second status after it
            bike.showPower(FakePower.POWERING_ON)
            assertEquals(CommandOutcome.NotConfirmed, sending.await())
            assertEquals(AssistState.Idle, rig.session.assist.value, "the bike isn't on: nothing held")
        }

    @Test
    fun `a bike whose profile offers no assist levels gets no assist command, whatever the rider agreed to`() =
        runTest {
            val bike = FakeBike(backgroundScope, model = "ENGWE_Other_model")
            val rig = rig(bike)
            rig.acknowledged += bike.address
            rig.session.start(bike.address)
            rig.ready()
            for (n in 0..5) {
                assertEquals(CommandOutcome.Refused(Refusal.NOT_SUPPORTED), rig.session.setAssistLevel(level(n)))
            }
            assertEquals(CommandOutcome.Confirmed, rig.session.setLights(true), "the rest works")
            assertEquals(listOf(BikeCommand.SetLights(true)), bike.sent())
            assertEquals(List(6) { "not_supported" }, rig.refusals())
        }

    @Test
    fun `a profile with fewer levels caps them, and an unverified bike needs its acknowledgement`() =
        runTest {
            val bike = FakeBike(backgroundScope, model = "ENGWE_Other_model")
            val rig = Rig(this, bike, profiles = withUnverified("ENGWE_Other_model", levels = 3))
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACKNOWLEDGED), rig.session.setAssistLevel(level(2)))
            rig.acknowledged += bike.address
            assertEquals(CommandOutcome.Refused(Refusal.NOT_SUPPORTED), rig.session.setAssistLevel(level(4)))
            assertEquals(CommandOutcome.Confirmed, rig.session.setAssistLevel(level(3)))
            assertEquals(listOf(BikeCommand.SetAssistLevel(level(3))), bike.sent())
        }

    @Test
    fun `an assist level goes through the same gate as the lights, without the speed caveat`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.caveatSeen = false
            assertEquals(CommandOutcome.Refused(Refusal.NOT_READY), rig.session.setAssistLevel(level(2)), "not started")
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setAssistLevel(level(2)), "no caveat needed")
            rig.accepted = false
            rig.acceptedEarlier = true
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACCEPTED), rig.session.setAssistLevel(level(0)))
            rig.accepted = true
            bike.switchOff()
            rig.session.ride.first { it.bikeOff }
            assertEquals(CommandOutcome.Refused(Refusal.BIKE_NOT_ON), rig.session.setAssistLevel(level(1)))
            assertEquals(listOf(BikeCommand.SetAssistLevel(level(2))), bike.sent())
        }

    @Test
    fun `one command at a time, a level while a speed is sent is refused, not queued`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoDelay = 1.seconds
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            val speed = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(10.milliseconds)
            assertEquals(CommandOutcome.Refused(Refusal.BUSY), rig.session.setAssistLevel(level(3)))
            assertEquals(CommandOutcome.Confirmed, speed.await())
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32)), bike.sent())
        }

    @Test
    fun `what the app set ends when the bike changes it, switches off, or the connection ends`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            rig.session.setAssistLevel(level(3))
            assertEquals(AssistState.Confirmed(level(3)), rig.session.assist.value)
            // The rider presses the display's buttons: no command, the status shows another level.
            bike.ride = bike.ride.copy(assistLevel = 2)
            rig.session.assist.first { it == AssistState.Idle }
            assertTrue(bike.sent().size == 1, "nothing sent in reply to a change on the bike")

            rig.session.setAssistLevel(level(4))
            bike.switchOff()
            rig.session.assist.first { it == AssistState.Idle }
            bike.switchOn()
            rig.ready()
            rig.session.ride.first { it.power == PowerState.ON && it.status?.assistLevel?.value == 0 }

            rig.session.setAssistLevel(level(1))
            assertEquals(AssistState.Confirmed(level(1)), rig.session.assist.value)
            bike.dropLink()
            rig.session.state.first { it !is SessionState.Ready }
            assertEquals(AssistState.Idle, rig.session.assist.value)
        }

    @Test
    fun `a link that drops while the level waits for its status fails the command`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.acts = false
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setAssistLevel(level(5)) }
            delay(500.milliseconds) // echoed, waiting for a status that never shows 5
            bike.dropLink()
            assertEquals(CommandOutcome.Failed, sending.await())
            assertEquals(AssistState.Idle, rig.session.assist.value)
        }

    @Test
    fun `a compatibility check's run sends no assist level`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.keepAssistLevel(bike.address, level(3))
            rig.session.check(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_READY), rig.session.setAssistLevel(level(2)))
            delay(2.minutes)
            assertTrue(bike.sent().isEmpty())
        }

    @Test
    fun `the simulated bike echoes an assist level and shows it from the next status`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.notifyRaw(Hex.decode("456E10090001040CE67765")) // an echo nobody waits for changes nothing
            delay(3.seconds)
            assertEquals(AssistState.Idle, rig.session.assist.value)
            assertTrue(bike.sent().isEmpty())
            assertEquals(CommandOutcome.Confirmed, rig.session.setAssistLevel(level(5)))
            assertEquals(5, bike.ride.assistLevel)
        }
}
