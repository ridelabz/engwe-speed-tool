@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Hex
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Speed and light commands: the write gate, one write per command, and the echo within 2 s. */
class CommandTest {
    private suspend fun Rig.ready() {
        session.state.first { it is SessionState.Ready }
        // A status that arrived on this connection after the checks (the one kept from them doesn't count).
        session.ride.drop(1).first { it.status != null && !it.stale }
    }

    /** The frames written after the identity read. */
    private fun FakeBike.sent() = commands.filterNot { it == BikeCommand.ReadIdentity }

    private fun Rig.outcomes() = values("state", "command")

    private fun Rig.refusals() = values("state", "refused")

    @Test
    fun `a speed change on a verified bike is written once and confirmed by its echo`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32)), rig.bike.sent())
            assertEquals(SpeedLimit.KMH_32, rig.bike.speedLimit)
            assertEquals(
                listOf("set_speed_limit_32"),
                rig
                    .records()
                    .filter {
                        it.s("event") == "tx"
                    }.map { it.s("command") }
                    .drop(1),
            )
            assertEquals(listOf("confirmed"), rig.outcomes())
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_25))
            assertEquals(SpeedLimit.KMH_25, rig.bike.speedLimit)
            assertEquals(2, rig.bike.sent().size)
        }

    @Test
    fun `an echo counts within 2 seconds of sending, not later`() =
        runTest {
            for ((echoAfter, expected) in listOf(
                100.milliseconds to CommandOutcome.Confirmed,
                1900.milliseconds to CommandOutcome.Confirmed,
                2100.milliseconds to CommandOutcome.NotConfirmed,
            )) {
                val bike = FakeBike(backgroundScope)
                bike.echoDelay = echoAfter
                val rig = Rig(this, bike)
                rig.session.start(bike.address)
                rig.ready()
                assertEquals(expected, rig.session.setSpeedLimit(SpeedLimit.KMH_32), "echo after $echoAfter")
                assertEquals(1, bike.sent().size, "never sent again")
                rig.session.stop()
            }
        }

    @Test
    fun `an echo of another value, or none, is not a confirmation`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.echoes = false
            val sending = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(100.milliseconds)
            // Another value's echo (25), as if from an earlier command.
            bike.notifyRaw(Hex.decode("456E2A6500011950E37765"))
            assertEquals(CommandOutcome.NotConfirmed, sending.await())
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setLights(true))
            assertEquals(listOf("not_confirmed", "not_confirmed"), rig.outcomes())
            assertEquals(2, bike.sent().size, "no retry")
        }

    @Test
    fun `a second command while one is being sent is refused, not queued`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoDelay = 1.seconds
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val first = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(10.milliseconds)
            assertEquals(CommandOutcome.Refused(Refusal.BUSY), rig.session.setSpeedLimit(SpeedLimit.KMH_16))
            assertEquals(CommandOutcome.Refused(Refusal.BUSY), rig.session.setLights(true))
            assertEquals(CommandOutcome.Confirmed, first.await())
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32)), bike.sent())
            assertEquals(listOf("busy", "busy"), rig.refusals())
        }

    @Test
    fun `a link that drops during the write fails the command, and nothing is sent again after reconnecting`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.withholdNextWriteResponse = true
            val sending = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(200.milliseconds)
            bike.dropLink()
            assertEquals(CommandOutcome.Failed, sending.await())
            rig.ready()
            delay(10.seconds)
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32)), bike.sent())
            assertEquals(listOf(2, 1), bike.writesPerLink.take(2), "the identity and the command, then the identity")
        }

    @Test
    fun `a link that drops while waiting for the echo fails the command`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoes = false
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setLights(true) }
            delay(500.milliseconds)
            bike.dropLink()
            assertEquals(CommandOutcome.Failed, sending.await())
        }

    @Test
    fun `lights on a verified bike are confirmed only when the status follows the echo`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setLights(true))
            assertEquals(CommandOutcome.Confirmed, rig.session.setLights(false))
            // Echoed, but the lights stay as they were.
            bike.acts = false
            assertEquals(CommandOutcome.NotConfirmed, rig.session.setLights(true))
            assertEquals(3, bike.sent().size)
        }

    @Test
    fun `lights on an unverified bike are confirmed by the echo, its lights reading can't tell`() =
        runTest {
            val bike = FakeBike(backgroundScope, model = "ENGWE_Other_model")
            bike.acts = false
            val rig = Rig(this, bike)
            rig.acknowledged += bike.address
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setLights(true))
        }

    @Test
    fun `nothing is sent without the rider's consent, and each refusal says what is missing`() =
        runTest {
            val verified = FakeBike(backgroundScope)
            val rig = Rig(this, verified)
            rig.session.start(verified.address)
            rig.ready()
            rig.caveatSeen = false
            assertEquals(CommandOutcome.Refused(Refusal.CAVEAT_NOT_SEEN), rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            assertEquals(CommandOutcome.Confirmed, rig.session.setLights(true), "lights need no caveat")
            rig.accepted = false
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACCEPTED), rig.session.setLights(false))
            assertEquals(listOf(BikeCommand.SetLights(true)), verified.sent())

            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, model = "ENGWE_Other_model")
            val unverified = Rig(this, other)
            unverified.session.start(other.address)
            unverified.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACKNOWLEDGED), unverified.session.setLights(true))
            assertEquals(
                CommandOutcome.Refused(Refusal.NOT_ACKNOWLEDGED),
                unverified.session.setSpeedLimit(SpeedLimit.KMH_25),
            )
            // Acknowledging another bike doesn't count for this one.
            unverified.acknowledged += FakeBike.DEMO_ADDRESS
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACKNOWLEDGED), unverified.session.setLights(true))
            unverified.acknowledged += other.address
            unverified.caveatSeen = false
            assertEquals(CommandOutcome.Confirmed, unverified.session.setSpeedLimit(SpeedLimit.KMH_25))
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25)), other.sent())
            assertEquals(listOf("not_acknowledged", "not_acknowledged", "not_acknowledged"), unverified.refusals())
        }

    @Test
    fun `nothing is sent unless a connected bike reports that it is on`() =
        runTest {
            // Its second power flag comes back late, so that the half-on state can be seen.
            val bike = FakeBike(backgroundScope, timing = FakeTiming(poweringOn = 10.seconds))
            val rig = Rig(this, bike)
            val notReady = CommandOutcome.Refused(Refusal.NOT_READY)
            assertEquals(notReady, rig.session.setSpeedLimit(SpeedLimit.KMH_32), "not started")
            rig.session.start(bike.address)
            assertEquals(notReady, rig.session.setSpeedLimit(SpeedLimit.KMH_32), "still checking")
            rig.ready()
            val off = CommandOutcome.Refused(Refusal.BIKE_NOT_ON)
            bike.switchOff()
            rig.session.ride.first { it.bikeOff }
            assertEquals(off, rig.session.setSpeedLimit(SpeedLimit.KMH_32), "display off")
            bike.switchOn()
            rig.session.ride.first { it.power == PowerState.POWERING_ON }
            assertEquals(off, rig.session.setSpeedLimit(SpeedLimit.KMH_32), "only one flag back")
            rig.session.ride.first { it.power == PowerState.ON }
            bike.sendsStatus = false
            rig.session.ride.first { it.stale }
            assertEquals(off, rig.session.setLights(true), "no recent status")
            bike.sendsStatus = true
            rig.session.ride.first { !it.stale }
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            bike.dropLink()
            rig.session.state.first { it is SessionState.Retrying }
            assertEquals(notReady, rig.session.setSpeedLimit(SpeedLimit.KMH_25), "reconnecting")
            rig.ready()
            rig.session.stop()
            assertEquals(notReady, rig.session.setSpeedLimit(SpeedLimit.KMH_25), "stopped")
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32)), bike.sent())
        }

    @Test
    fun `an incompatible bike gets nothing`() =
        runTest {
            val bike = FakeBike(backgroundScope, services = emptyList())
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.settled()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_READY), rig.session.setLights(true))
            assertTrue(bike.writes.isEmpty())
        }

    @Test
    fun `a command whose session is replaced while it waits for its echo fails, and the new run gets nothing`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoes = false
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(50.milliseconds) // written (the fake acknowledges within 20 ms), now waiting for the echo
            rig.session.start(bike.address)
            assertEquals(CommandOutcome.Failed, sending.await())
            rig.ready()
            delay(5.seconds)
            assertEquals(listOf(2, 1), bike.writesPerLink.take(2), "the identity and the command, then the identity")
        }

    @Test
    fun `a command whose session stops before the write's response fails`() =
        runTest {
            val bike = FakeBike(backgroundScope, timing = FakeTiming(operation = 500.milliseconds))
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(10.milliseconds)
            rig.session.stop()
            assertEquals(CommandOutcome.Failed, sending.await())
            assertTrue(bike.sent().isEmpty(), "the fake records a write only once it is answered")
            assertEquals(CommandOutcome.Refused(Refusal.NOT_READY), rig.session.setLights(true))
        }

    @Test
    fun `a caller that is already cancelled writes nothing`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val cancelled = Job().apply { cancel() }
            val outcome = runCatching { withContext(cancelled) { rig.session.setSpeedLimit(SpeedLimit.KMH_32) } }
            assertTrue(outcome.isFailure)
            delay(5.seconds)
            assertTrue(bike.sent().isEmpty())
            assertTrue(rig.records().none { it.s("event") == "tx" && it.s("command") != "read_identity" })
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_32), "the slot is free")
        }

    @Test
    fun `the status kept from the checks doesn't count as recent`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.session.state.first { it is SessionState.Ready }
            assertEquals(CommandOutcome.Refused(Refusal.BIKE_NOT_ON), rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            rig.session.ride
                .drop(1)
                .first { it.status != null }
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_32))
        }

    @Test
    fun `a lights command whose link drops while it waits for the status fails at once`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.acts = false
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setLights(true) }
            delay(500.milliseconds) // echoed, waiting for a status that never shows the lights on
            val dropped = testScheduler.currentTime
            bike.dropLink()
            assertEquals(CommandOutcome.Failed, sending.await())
            assertTrue(testScheduler.currentTime - dropped < 1000, "the slot is freed when the link ends")
        }

    @Test
    fun `an echo that comes before the write's response counts`() =
        runTest {
            val bike = FakeBike(backgroundScope, timing = FakeTiming(operation = 500.milliseconds))
            bike.echoDelay = 10.milliseconds
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_32))
        }

    @Test
    fun `a cancelled command frees the slot and is logged as failed`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoes = false
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = launch { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(500.milliseconds)
            sending.cancel()
            sending.join()
            assertEquals(listOf("failed"), rig.outcomes())
            rig.ready()
            bike.echoes = true
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_25))
        }

    @Test
    fun `the display switched off while a command waits for its echo doesn't send it again`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoDelay = 1.seconds
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(200.milliseconds)
            bike.switchOff()
            sending.await()
            delay(10.seconds)
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32)), bike.sent())
        }

    @Test
    fun `lights already in the wanted state are confirmed by a status after the echo`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride = bike.ride.copy(lightsOn = true)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setLights(true))
        }

    @Test
    fun `going back to 25 needs no caveat, other speeds do`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.caveatSeen = false
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_25))
            assertEquals(CommandOutcome.Refused(Refusal.CAVEAT_NOT_SEEN), rig.session.setSpeedLimit(SpeedLimit.KMH_16))
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25)), bike.sent())
        }

    @Test
    fun `a status older than a few seconds is not enough for a command`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.sendsStatus = false
            delay(4.seconds)
            assertTrue(!rig.session.ride.value.stale, "still shown: the values go stale only after 10 s")
            assertEquals(CommandOutcome.Refused(Refusal.BIKE_NOT_ON), rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            bike.sendsStatus = true
            delay(2.seconds)
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_32))
        }

    @Test
    fun `an unreadable status refuses commands`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.statusPayloadLength = 20
            rig.session.ride.first { it.unreadable }
            assertEquals(CommandOutcome.Refused(Refusal.BIKE_NOT_ON), rig.session.setLights(true))
            assertTrue(bike.sent().isEmpty())
        }

    @Test
    fun `with only an earlier text accepted, the bike connects and only going back to 25 is sent`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope), accepted = false)
            rig.acceptedEarlier = true
            rig.session.start(rig.bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACCEPTED), rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACCEPTED), rig.session.setLights(true))
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_25))
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25)), rig.bike.sent())
        }

    @Test
    fun `an unverified bike acknowledged for an earlier text can go back to 25, nothing else`() =
        runTest {
            val bike = FakeBike(backgroundScope, model = "ENGWE_Other_model")
            val rig = Rig(this, bike)
            rig.acknowledgedEarlier += bike.address
            rig.session.start(bike.address)
            rig.ready()
            assertEquals(CommandOutcome.Refused(Refusal.NOT_ACKNOWLEDGED), rig.session.setSpeedLimit(SpeedLimit.KMH_16))
            assertEquals(CommandOutcome.Confirmed, rig.session.setSpeedLimit(SpeedLimit.KMH_25))
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25)), bike.sent())
        }

    @Test
    fun `never accepted at all, the bike's identity isn't even read`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope), accepted = false)
            rig.session.start(rig.bike.address)
            assertEquals(
                SessionState.Stopped(StopReason.NOT_ACCEPTED),
                rig.session.state.first { it is SessionState.Stopped },
            )
            assertTrue(rig.bike.writes.isEmpty())
        }

    @Test
    fun `with only an earlier text accepted, keep-speed re-applies 25 but never another speed`() =
        runTest {
            for ((kept, written) in listOf(
                SpeedLimit.KMH_32 to emptyList(),
                SpeedLimit.KMH_25 to listOf(SpeedLimit.KMH_25),
            )) {
                val rig = Rig(this, FakeBike(backgroundScope), accepted = false)
                rig.acceptedEarlier = true
                rig.session.keepSpeed(rig.bike.address, kept)
                rig.session.start(rig.bike.address)
                rig.ready()
                delay(30.seconds)
                assertEquals(written.map { BikeCommand.SetSpeedLimit(it) }, rig.bike.sent(), "kept $kept")
                assertEquals(1, rig.values("state", "reapply").size, "one attempt for the connection")
                rig.session.stop()
            }
        }

    @Test
    fun `checking another bike than the saved one needs the current text`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope), accepted = false)
            rig.acceptedEarlier = true
            rig.startOnce()
            assertEquals(
                SessionState.Stopped(StopReason.NOT_ACCEPTED),
                rig.session.state.first { it is SessionState.Stopped },
            )
            assertTrue(rig.bike.writes.isEmpty())
        }
}
