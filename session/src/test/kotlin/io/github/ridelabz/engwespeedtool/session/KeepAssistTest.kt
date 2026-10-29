@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * "Keep this assist level": the kept level is set again once per new connection and, where restarts are
 * trusted (as in the app), once per power-on, after the bike has been on a while, only while it reads 0 km/h,
 * at most once a minute, never retried, never in a check, and after the kept speed when both are kept.
 */
class KeepAssistTest {
    private val trusted = SessionConfig(restartsTrusted = true)

    /** A session that doesn't take a power-on for a restart: it sets nothing again after one. */
    private val untrusted = SessionConfig(restartsTrusted = false)

    private suspend fun Rig.ready() {
        session.state.first { it is SessionState.Ready }
        session.ride.drop(1).first { it.status != null && !it.stale }
    }

    private fun FakeBike.levels() = commands.filterIsInstance<BikeCommand.SetAssistLevel>().map { it.level.level }

    private fun Rig.reapplied() = values("state", "reapply_assist")

    private fun Rig.skipped() = values("state", "reapply_assist_skipped")

    private fun level(n: Int) = checkNotNull(AssistLevel.of(n))

    private fun TestScope.rig(
        bike: FakeBike = FakeBike(backgroundScope),
        config: SessionConfig = SessionConfig(),
    ) = Rig(this, bike, config = config)

    /** The times (s since the session started) at which the log records [command] written. */
    private fun Rig.written(command: String) =
        records().filter { it.s("event") == "tx" && it.s("command") == command }.map { it.s("t").toDouble() }

    @Test
    fun `the kept level is set again once on a new connection, once the bike has been on a while`() =
        runTest {
            val rig = rig()
            rig.session.keepAssistLevel(rig.bike.address, level(3))
            rig.session.start(rig.bike.address)
            rig.ready()
            assertTrue(rig.bike.levels().isEmpty(), "not at once: the bike must stay on for a moment")
            rig.session.assist.first { it == AssistState.Confirmed(level(3), Reapply.RECONNECT) }
            delay(5.minutes)
            assertEquals(listOf(3), rig.bike.levels(), "once per connection")
            assertEquals(listOf("reconnect"), rig.reapplied())
            assertEquals(3, rig.bike.ride.assistLevel)
        }

    @Test
    fun `with the kept speed too, the speed goes first, then the level, one after the other`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoDelay = 1500.milliseconds
            val rig = rig(bike)
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
            rig.session.keepAssistLevel(bike.address, level(4))
            rig.session.start(bike.address)
            rig.ready()
            delay(1.minutes)
            assertEquals(
                listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32), BikeCommand.SetAssistLevel(level(4))),
                bike.commands.filterNot { it == BikeCommand.ReadIdentity },
            )
            assertTrue(rig.values("state", "refused").isEmpty(), "the second waited for the first: not busy")
            val speed = rig.written("set_speed_limit_32").single()
            val assist = rig.written("set_assist_level_4").single()
            assertTrue(assist - speed >= 1.5, "after the speed's echo: $speed, $assist")
        }

    @Test
    fun `nothing is set again without a kept level, or after the rider chose one in the app on this connection`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            delay(1.minutes)
            assertTrue(rig.bike.levels().isEmpty())

            val chose = rig(FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2))
            chose.session.keepAssistLevel(chose.bike.address, level(3))
            chose.session.start(chose.bike.address)
            chose.ready()
            chose.session.setAssistLevel(level(1))
            delay(1.minutes)
            assertEquals(listOf(1), chose.bike.levels())
            assertEquals(listOf("rider_chose"), chose.skipped())
        }

    @Test
    fun `a level the rider sets in the app becomes the kept one, a level changed on the bike doesn't`() =
        runTest {
            val rig = rig()
            rig.session.keepAssistLevel(rig.bike.address, level(3))
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.setAssistLevel(level(2))
            delay(2.minutes)
            // On the display: no command, and nothing the app keeps.
            rig.bike.ride = rig.bike.ride.copy(assistLevel = 5)
            delay(10.seconds)
            rig.bike.dropLink()
            rig.session.state.first { it !is SessionState.Ready }
            rig.ready()
            delay(2.minutes)
            assertEquals(listOf(2, 2), rig.bike.levels(), "2 again, never 3 or 5")
        }

    @Test
    fun `a moving bike isn't set, the re-apply is skipped, not delayed`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride = bike.ride.copy(speedKmh = 14.2)
            val rig = rig(bike)
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_25)
            rig.session.keepAssistLevel(bike.address, level(4))
            rig.session.start(bike.address)
            rig.ready()
            delay(30.seconds)
            bike.ride = bike.ride.copy(speedKmh = 0.0)
            delay(3.minutes)
            assertEquals(listOf(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25)), bike.commands.drop(1), "the speed only")
            assertEquals(listOf("moving"), rig.skipped())
        }

    @Test
    fun `a speed reading whose meaning isn't verified for the bike never counts as standing still`() =
        runTest {
            val bike = FakeBike(backgroundScope, model = "ENGWE_Other_model")
            val reference = File("../protocol/src/main/resources/profiles/engwe-e26-3-pro.json").readText()
            val other =
                reference
                    .replace("ENGWE_Omni_ebike1100EU317", "ENGWE_Other_model")
                    .replace("\"verified\": true", "\"verified\": false")
                    .replace("  \"tested_firmware\": [\"218CAN\"],\n", "")
                    .replace("  \"lock_control\": true,\n", "")
            val profiles = ProfileRegistry(ProfileRegistry.bundled().profiles + ProfileRegistry.parse(other))
            val rig = Rig(this, bike, profiles = profiles)
            rig.acknowledged += bike.address
            rig.session.keepAssistLevel(bike.address, level(3))
            rig.session.start(bike.address)
            rig.ready()
            delay(2.minutes)
            assertTrue(bike.levels().isEmpty(), "never on an unverified speed reading")
            assertEquals(listOf("no_speed"), rig.skipped())
            assertEquals(CommandOutcome.Confirmed, rig.session.setAssistLevel(level(3)), "the rider's tap still works")
        }

    @Test
    fun `a bike that is off when the app connects isn't set until it is switched on, then once`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.switchOff()
            val rig = rig(bike, trusted)
            rig.session.keepAssistLevel(bike.address, level(3))
            rig.session.start(bike.address)
            rig.session.state.first { it is SessionState.Ready }
            delay(10.seconds)
            assertTrue(bike.levels().isEmpty(), "nothing while it is off")
            bike.switchOn()
            rig.session.assist.first { it == AssistState.Confirmed(level(3), Reapply.RESTART) }
            delay(2.minutes)
            assertEquals(listOf(3), bike.levels())
            assertEquals(listOf("restart"), rig.reapplied())
        }

    @Test
    fun `with trusted restarts, a power-on sets the kept level again once, after the speed, the bike standing still`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike, trusted)
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
            rig.session.keepAssistLevel(bike.address, level(3))
            rig.session.start(bike.address)
            rig.ready()
            rig.session.assist.first { it is AssistState.Confirmed }
            delay(2.minutes) // past the wait between re-applies
            bike.switchOff()
            delay(5.seconds)
            bike.switchOn()
            assertEquals(0, bike.ride.assistLevel, "the bike starts again from 0")
            rig.session.assist.first { it == AssistState.Confirmed(level(3), Reapply.RESTART) }
            delay(3.minutes)
            assertEquals(listOf(3, 3), bike.levels(), "once on connecting, once after the power-on")
            assertEquals(listOf("reconnect", "restart"), rig.reapplied())
            assertEquals(listOf("reconnect", "restart"), rig.values("state", "reapply"))
            val speed = rig.written("set_speed_limit_32")
            val assist = rig.written("set_assist_level_3")
            assertEquals(2, speed.size)
            assertTrue(assist[1] > speed[1], "the speed first: $speed, $assist")
            assertEquals(3, bike.ride.assistLevel)
        }

    @Test
    fun `after a power-on the level the rider set last is kept, and a moving bike isn't set`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike, trusted)
            rig.session.keepAssistLevel(bike.address, level(3))
            rig.session.start(bike.address)
            rig.ready()
            rig.session.assist.first { it is AssistState.Confirmed }
            rig.session.setAssistLevel(level(1))
            delay(2.minutes)
            bike.switchOff()
            delay(5.seconds)
            bike.switchOn()
            rig.session.assist.first { it == AssistState.Confirmed(level(1), Reapply.RESTART) }
            delay(2.minutes)
            // Off, then on and riding off at once: skipped, not delayed until it stops.
            bike.switchOff()
            delay(5.seconds)
            bike.switchOn()
            bike.ride = bike.ride.copy(speedKmh = 9.5)
            delay(1.minutes)
            bike.ride = bike.ride.copy(speedKmh = 0.0)
            delay(2.minutes)
            assertEquals(listOf(3, 1, 1), bike.levels())
            assertEquals(listOf("moving"), rig.skipped())
        }

    @Test
    fun `without trusted restarts, a power-on sets nothing again`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = rig(bike, untrusted)
            rig.session.keepAssistLevel(bike.address, level(3))
            rig.session.start(bike.address)
            rig.ready()
            rig.session.assist.first { it is AssistState.Confirmed }
            delay(2.minutes)
            bike.switchOff()
            delay(5.seconds)
            bike.switchOn()
            delay(3.minutes)
            assertEquals(listOf(3), bike.levels(), "only on connecting")
            assertEquals(listOf("reconnect"), rig.reapplied())
        }

    @Test
    fun `power flags that flap, and links that drop, never set the level more than once a minute`() =
        runTest {
            val rig = rig(config = trusted)
            rig.session.keepAssistLevel(rig.bike.address, level(2))
            rig.session.start(rig.bike.address)
            rig.ready()
            repeat(20) {
                delay(6.seconds)
                rig.bike.switchOff()
                delay(4.seconds)
                rig.bike.switchOn()
                if (it % 3 == 0) rig.bike.dropLink()
            }
            repeat(8) {
                delay(15.seconds)
                rig.bike.dropLink()
            }
            delay(1.minutes)
            val sent = rig.written("set_assist_level_2")
            assertTrue(sent.isNotEmpty())
            assertTrue(sent.zipWithNext().all { (a, b) -> b - a >= 60.0 }, "a minute apart: $sent")
            assertTrue("recent" in rig.skipped())
            // At most one per connection, before any power-on seen on it, and one per power-on after that.
            var attempts = 0
            var restarts = 0
            var power: String? = null
            var powerOns = 0
            for (record in rig.records()) {
                if (record.s("event") == "state" && record.s("name") == "check" && record.s("value") == "connecting") {
                    attempts = 0
                    restarts = 0
                    powerOns = 0
                    power = null
                }
                if (record.s("event") == "status" && "power" in record) {
                    if (power == "off" && record.s("power") != "off") powerOns++
                    power = record.s("power")
                }
                if (record.s("event") == "state" && record.s("name") == "reapply_assist") {
                    if (record.s("value") == "reconnect") {
                        assertTrue(++attempts <= 1, "a second assist re-apply on connecting")
                        assertEquals(0, powerOns, "the connection's assist re-apply after a power-on")
                    } else {
                        assertEquals("restart", record.s("value"))
                        assertTrue(++restarts <= powerOns, "$restarts assist re-applies for $powerOns power-ons")
                    }
                    assertEquals("on", power)
                }
            }
        }

    @Test
    fun `a re-apply that isn't confirmed, or isn't allowed, is not tried again`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.acts = false
            val rig = rig(bike)
            rig.session.keepAssistLevel(bike.address, level(5))
            rig.session.start(bike.address)
            rig.ready()
            rig.session.assist.first { it == AssistState.NotConfirmed(level(5), Reapply.RECONNECT) }
            delay(5.minutes)
            assertEquals(listOf(5), bike.levels())

            // A newer risk text not accepted yet: the re-apply is refused, and not tried again.
            val later = rig(FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2))
            later.accepted = false
            later.acceptedEarlier = true
            later.session.keepAssistLevel(later.bike.address, level(2))
            later.session.start(later.bike.address)
            later.ready()
            delay(5.minutes)
            assertTrue(later.bike.levels().isEmpty())
            assertEquals(listOf("not_accepted"), later.values("state", "refused"))
            assertEquals(listOf("reconnect"), later.reapplied())

            // A bike whose profile offers no assist levels.
            val unsupported = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, model = "ENGWE_Other_model")
            val other = rig(unsupported)
            other.acknowledged += unsupported.address
            other.session.keepAssistLevel(unsupported.address, level(2))
            other.session.start(unsupported.address)
            other.ready()
            delay(5.minutes)
            assertTrue(unsupported.levels().isEmpty())
            assertEquals(listOf("not_supported"), other.skipped())
        }

    @Test
    fun `the kept level goes only to the kept bike, never in a check, and stops when it is no longer kept`() =
        runTest {
            val kept = FakeBike(backgroundScope)
            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2)
            val rig = Rig(this, kept, other)
            rig.session.keepAssistLevel(kept.address, level(3))
            rig.session.start(Advertisement(other.address, -60))
            rig.ready()
            delay(2.minutes)
            assertTrue(other.levels().isEmpty())
            rig.session.start(Advertisement(kept.address, -60))
            rig.ready()
            delay(2.minutes)
            assertTrue(kept.levels().isEmpty(), "a check of a found bike")
            rig.session.check(kept.address)
            rig.ready()
            delay(2.minutes)
            assertTrue(kept.levels().isEmpty(), "the compatibility check")
            rig.session.start(kept.address)
            rig.session.state.first { it is SessionState.Ready }
            rig.session.keepAssistLevel(kept.address, null)
            delay(2.minutes)
            assertTrue(kept.levels().isEmpty(), "no longer kept")
            rig.session.keepAssistLevel(kept.address, level(3))
            rig.session.start(kept.address)
            rig.ready()
            delay(2.minutes)
            assertEquals(listOf(3), kept.levels())
        }

    @Test
    fun `a level tapped while the re-apply is sent is refused, but becomes the kept one`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoDelay = 1500.milliseconds
            val rig = rig(bike)
            rig.session.keepAssistLevel(bike.address, level(4))
            rig.session.start(bike.address)
            rig.ready()
            rig.session.assist.first { it is AssistState.Sending }
            assertEquals(CommandOutcome.Refused(Refusal.BUSY), rig.session.setAssistLevel(level(1)))
            rig.session.assist.first { it is AssistState.Confirmed }
            delay(2.minutes)
            bike.dropLink()
            rig.session.state.first { it !is SessionState.Ready }
            rig.ready()
            delay(1.minutes)
            assertEquals(listOf(4, 1), bike.levels())
        }
}
