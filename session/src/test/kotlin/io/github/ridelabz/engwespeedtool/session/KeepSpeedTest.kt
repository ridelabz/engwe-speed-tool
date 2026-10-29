@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the session knows about the limit, and "keep this speed": bounded, never retried, through the gate. */
class KeepSpeedTest {
    private val trusted = SessionConfig(restartsTrusted = true)

    /** A session that doesn't take a power-on for a restart: it still says so, but sends nothing after one. */
    private val untrusted = SessionConfig(restartsTrusted = false)

    @Test
    fun `restarts are trusted unless a session is told otherwise`() {
        assertTrue(SessionConfig().restartsTrusted)
    }

    private suspend fun Rig.ready() {
        session.state.first { it is SessionState.Ready }
        session.ride.drop(1).first { it.status != null && !it.stale }
    }

    private fun FakeBike.speeds() = commands.filterIsInstance<BikeCommand.SetSpeedLimit>().map { it.limit }

    private fun Rig.reapplied() = values("state", "reapply")

    private fun TestScope.rig(
        bike: FakeBike = FakeBike(backgroundScope),
        config: SessionConfig = SessionConfig(),
    ) = Rig(this, bike, config = config)

    @Test
    fun `the limit is what was sent and echoed on this connection, and nothing after it ends`() =
        runTest {
            val rig = rig()
            assertEquals(LimitState.Unknown, rig.session.limit.value)
            rig.session.start(rig.bike.address)
            rig.ready()
            assertEquals(LimitState.Unknown, rig.session.limit.value, "connected: the app can't tell")
            rig.session.setSpeedLimit(SpeedLimit.KMH_32)
            assertEquals(LimitState.Confirmed(SpeedLimit.KMH_32), rig.session.limit.value)
            rig.bike.echoes = false
            rig.session.setSpeedLimit(SpeedLimit.KMH_16)
            assertEquals(LimitState.NotConfirmed(SpeedLimit.KMH_16), rig.session.limit.value)
            rig.caveatSeen = false
            rig.session.setSpeedLimit(SpeedLimit.KMH_32)
            assertEquals(
                LimitState.NotConfirmed(SpeedLimit.KMH_16),
                rig.session.limit.value,
                "a refusal changes nothing",
            )
            rig.bike.dropLink()
            rig.session.state.first { it !is SessionState.Ready }
            assertEquals(LimitState.Unknown, rig.session.limit.value, "a new connection knows nothing")
            rig.ready()
            assertEquals(LimitState.Unknown, rig.session.limit.value)
            rig.session.stop()
            assertEquals(LimitState.Unknown, rig.session.limit.value)
        }

    @Test
    fun `a power-on seen on a verified bike is a restart, on an unverified one the limit is unknown`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.setSpeedLimit(SpeedLimit.KMH_32)
            rig.bike.switchOff()
            delay(5.seconds)
            assertEquals(LimitState.Unknown, rig.session.limit.value, "off: the speed set may not hold any more")
            rig.bike.switchOn()
            rig.session.limit.first { it == LimitState.Restarted }

            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, model = "ENGWE_Other_model")
            val unverified = rig(other)
            unverified.acknowledged += other.address
            unverified.session.start(other.address)
            unverified.ready()
            unverified.session.setSpeedLimit(SpeedLimit.KMH_32)
            other.switchOff()
            delay(5.seconds)
            other.switchOn()
            delay(5.seconds)
            assertEquals(LimitState.Unknown, unverified.session.limit.value)
        }

    @Test
    fun `kept speed is set again once on a new connection, once the bike has been on a while`() =
        runTest {
            val rig = rig()
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.start(rig.bike.address)
            rig.ready()
            assertTrue(rig.bike.speeds().isEmpty(), "not at once: the bike must stay on for a moment")
            rig.session.limit.first { it == LimitState.Confirmed(SpeedLimit.KMH_32, Reapply.RECONNECT) }
            delay(5.minutes)
            assertEquals(listOf(SpeedLimit.KMH_32), rig.bike.speeds(), "once per connection")
            assertEquals(listOf("reconnect"), rig.reapplied())
        }

    @Test
    fun `nothing is set again without a kept speed, or after the rider chose one on this connection`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            delay(1.minutes)
            assertTrue(rig.bike.speeds().isEmpty())

            val chose = rig(FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2))
            chose.session.keepSpeed(chose.bike.address, SpeedLimit.KMH_32)
            chose.session.start(chose.bike.address)
            chose.ready()
            chose.session.setSpeedLimit(SpeedLimit.KMH_16)
            delay(1.minutes)
            assertEquals(listOf(SpeedLimit.KMH_16), chose.bike.speeds())
        }

    @Test
    fun `a bike that is off when the app connects isn't set by the connection's re-apply, only after it is on`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.switchOff()
            val rig = rig(bike)
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
            rig.session.start(bike.address)
            rig.session.state.first { it is SessionState.Ready }
            delay(10.seconds)
            assertTrue(bike.speeds().isEmpty(), "nothing while it is off")
            bike.switchOn()
            rig.session.limit.first { it == LimitState.Confirmed(SpeedLimit.KMH_32, Reapply.RESTART) }
            delay(2.minutes)
            assertEquals(listOf(SpeedLimit.KMH_32), bike.speeds(), "once, after it switched on")
            assertEquals(listOf("restart"), rig.reapplied())

            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2)
            other.switchOff()
            val pinned = rig(other, untrusted)
            pinned.session.keepSpeed(other.address, SpeedLimit.KMH_32)
            pinned.session.start(other.address)
            pinned.session.state.first { it is SessionState.Ready }
            delay(10.seconds)
            other.switchOn()
            delay(2.minutes)
            assertTrue(other.speeds().isEmpty(), "restarts not trusted: nothing sent by itself")
            assertEquals(LimitState.Restarted, pinned.session.limit.value)
        }

    @Test
    fun `with trusted restarts, a power-on sets the kept speed again once`() =
        runTest {
            val rig = rig(config = trusted)
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.limit.first { it is LimitState.Confirmed }
            delay(2.minutes) // past the wait between re-applies
            rig.bike.switchOff()
            delay(5.seconds)
            rig.bike.switchOn()
            rig.session.limit.first { it == LimitState.Confirmed(SpeedLimit.KMH_32, Reapply.RESTART) }
            delay(5.minutes)
            assertEquals(listOf(SpeedLimit.KMH_32, SpeedLimit.KMH_32), rig.bike.speeds())
            assertEquals(listOf("reconnect", "restart"), rig.reapplied())
        }

    @Test
    fun `an untested model's power-ons aren't restarts, so its kept speed is set again only on connecting`() =
        runTest {
            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL)
            val rig = rig(other)
            assertTrue(SessionConfig().restartsTrusted, "the default config")
            rig.acknowledged += other.address
            rig.session.keepSpeed(other.address, SpeedLimit.KMH_32)
            rig.session.start(other.address)
            rig.ready()
            rig.session.limit.first { it == LimitState.Confirmed(SpeedLimit.KMH_32, Reapply.RECONNECT) }
            delay(2.minutes)
            other.switchOff()
            delay(5.seconds)
            other.switchOn()
            delay(3.minutes)
            assertEquals(listOf(SpeedLimit.KMH_32), other.speeds())
            assertEquals(listOf("reconnect"), rig.reapplied())
            assertFalse(rig.session.ride.value.powerVerified, "its power flags aren't verified")
        }

    @Test
    fun `power flags that flap, and links that drop, never set the speed more than once a minute`() =
        runTest {
            val rig = rig(config = trusted)
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.start(rig.bike.address)
            rig.ready()
            repeat(20) {
                delay(6.seconds)
                rig.bike.switchOff()
                delay(4.seconds)
                rig.bike.switchOn()
                if (it % 3 == 0) rig.bike.dropLink()
            }
            delay(1.minutes)
            val sent =
                rig
                    .records()
                    .filter { it.s("event") == "tx" && it.s("command") == "set_speed_limit_32" }
                    .map { it.s("t").toDouble() }
            assertTrue(sent.isNotEmpty())
            assertTrue(sent.zipWithNext().all { (a, b) -> b - a >= 60.0 }, "a minute apart: $sent")
        }

    @Test
    fun `a re-apply that isn't confirmed, or isn't allowed, is not tried again`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoes = false
            val rig = rig(bike)
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
            rig.session.start(bike.address)
            rig.ready()
            rig.session.limit.first { it == LimitState.NotConfirmed(SpeedLimit.KMH_32, Reapply.RECONNECT) }
            delay(5.minutes)
            assertEquals(listOf(SpeedLimit.KMH_32), bike.speeds())

            val later = rig(FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2))
            later.caveatSeen = false
            later.session.keepSpeed(later.bike.address, SpeedLimit.KMH_32)
            later.session.start(later.bike.address)
            later.ready()
            delay(5.minutes)
            assertTrue(later.bike.speeds().isEmpty())
            assertEquals(listOf("caveat_not_seen"), later.values("state", "refused"))
        }

    @Test
    fun `keeping 25 needs no caveat, and stopping keep-speed stops the re-apply`() =
        runTest {
            val rig = rig()
            rig.caveatSeen = false
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_25)
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.limit.first { it == LimitState.Confirmed(SpeedLimit.KMH_25, Reapply.RECONNECT) }

            val off = rig(FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2))
            off.session.keepSpeed(off.bike.address, SpeedLimit.KMH_32)
            off.session.start(off.bike.address)
            off.session.state.first { it is SessionState.Ready }
            off.session.keepSpeed(off.bike.address, null)
            delay(1.minutes)
            assertTrue(off.bike.speeds().isEmpty())
        }

    @Test
    fun `the kept speed goes only to the kept bike, and never in a check of a found bike`() =
        runTest {
            val kept = FakeBike(backgroundScope)
            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2)
            val rig = Rig(this, kept, other)
            rig.session.keepSpeed(kept.address, SpeedLimit.KMH_32)
            // A check of another bike found nearby, on the same session.
            rig.session.start(Advertisement(other.address, -60))
            rig.ready()
            delay(2.minutes)
            assertTrue(other.speeds().isEmpty())
            // A check of the kept bike itself, found nearby: a check, not the saved bike's run.
            rig.session.start(Advertisement(kept.address, -60))
            rig.ready()
            delay(2.minutes)
            assertTrue(kept.speeds().isEmpty())
            // Its own run, by its address: once.
            rig.session.start(kept.address)
            rig.ready()
            delay(2.minutes)
            assertEquals(listOf(SpeedLimit.KMH_32), kept.speeds())
        }

    @Test
    fun `a speed the rider sends becomes the kept one`() =
        runTest {
            val rig = rig()
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.setSpeedLimit(SpeedLimit.KMH_25)
            delay(2.minutes)
            rig.bike.dropLink()
            rig.session.state.first { it !is SessionState.Ready }
            rig.ready()
            delay(2.minutes)
            assertEquals(listOf(SpeedLimit.KMH_25, SpeedLimit.KMH_25), rig.bike.speeds(), "25 again, never 32")
        }

    @Test
    fun `a bike switched on during the checks is a restart, not the connection's re-apply`() =
        runTest {
            for (config in listOf(untrusted, trusted)) {
                val bike = FakeBike(backgroundScope, timing = FakeTiming(statusOff = 500.milliseconds))
                bike.switchOff()
                val rig = rig(bike, config)
                rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
                rig.session.start(bike.address)
                rig.session.state.first { it == SessionState.Checking(CheckStep.IDENTITY) }
                bike.switchOn()
                rig.session.state.first { it is SessionState.Ready }
                delay(2.minutes)
                if (config.restartsTrusted) {
                    assertEquals(listOf(SpeedLimit.KMH_32), bike.speeds())
                    assertEquals(listOf("restart"), rig.reapplied())
                } else {
                    assertTrue(bike.speeds().isEmpty())
                    assertEquals(LimitState.Restarted, rig.session.limit.value)
                }
                rig.session.stop()
            }
        }

    @Test
    fun `a flag that drops without the bike going off makes the limit unknown`() =
        runTest {
            val rig = rig()
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.setSpeedLimit(SpeedLimit.KMH_32)
            assertEquals(LimitState.Confirmed(SpeedLimit.KMH_32), rig.session.limit.value)
            rig.bike.showPower(FakePower.POWERING_ON) // 1/1, then 0/1, as a lock might
            rig.session.limit.first { it == LimitState.Unknown }
            rig.bike.showPower(FakePower.ON)
            delay(5.seconds)
            assertEquals(LimitState.Unknown, rig.session.limit.value, "not a restart: no off was seen")
        }

    @Test
    fun `a late confirmation doesn't hide a power change that came after the write`() =
        runTest {
            val bike = FakeBike(backgroundScope, timing = FakeTiming(operation = 5.seconds))
            bike.echoDelay = 10.milliseconds
            val rig = rig(bike)
            rig.session.start(bike.address)
            rig.ready()
            val sending = async { rig.session.setSpeedLimit(SpeedLimit.KMH_32) }
            delay(1.seconds)
            bike.switchOff()
            rig.session.limit.first { it == LimitState.Unknown }
            sending.await()
            assertEquals(LimitState.Unknown, rig.session.limit.value, "the outcome doesn't overwrite it")
        }

    @Test
    fun `without trusted restarts, power cycles and drops bring only the connection re-applies, a minute apart`() =
        runTest {
            val rig = rig(config = untrusted)
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.start(rig.bike.address)
            rig.ready()
            repeat(12) {
                delay(20.seconds)
                rig.bike.switchOff()
                delay(5.seconds)
                rig.bike.switchOn()
                delay(10.seconds)
                rig.bike.dropLink()
            }
            delay(2.minutes)
            assertTrue(rig.reapplied().all { it == "reconnect" })
            val sent =
                rig
                    .records()
                    .filter { it.s("event") == "tx" && it.s("command") == "set_speed_limit_32" }
                    .map { it.s("t").toDouble() }
            assertTrue(sent.size >= 2, "$sent")
            assertTrue(sent.zipWithNext().all { (a, b) -> b - a >= 60.0 }, "a minute apart: $sent")
            assertTrue("recent" in rig.values("state", "reapply_skipped"))
        }

    @Test
    fun `the minute between re-applies spans a stop and a new start`() =
        runTest {
            val rig = rig()
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.start(rig.bike.address)
            rig.ready()
            rig.session.limit.first { it is LimitState.Confirmed }
            rig.session.stop()
            rig.session.start(rig.bike.address)
            rig.ready()
            delay(20.seconds)
            assertEquals(1, rig.bike.speeds().size)
            assertEquals(listOf("recent"), rig.values("state", "reapply_skipped"))
        }

    @Test
    fun `a new kept speed during the wait is the one sent, and the rider's tap during it wins`() =
        runTest {
            val rig = rig()
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            rig.session.start(rig.bike.address)
            rig.session.state.first { it is SessionState.Ready }
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_16)
            delay(1.minutes)
            assertEquals(listOf(SpeedLimit.KMH_16), rig.bike.speeds())

            val tapped = rig(FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2))
            tapped.session.keepSpeed(tapped.bike.address, SpeedLimit.KMH_32)
            tapped.session.start(tapped.bike.address)
            tapped.ready()
            tapped.session.setSpeedLimit(SpeedLimit.KMH_25)
            delay(1.minutes)
            assertEquals(listOf(SpeedLimit.KMH_25), tapped.bike.speeds())
            assertEquals(listOf("rider_chose"), tapped.values("state", "reapply_skipped"))
        }

    @Test
    fun `a re-apply whose link drops fails and isn't tried again on that connection`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoes = false
            val rig = rig(bike)
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
            rig.session.start(bike.address)
            rig.ready()
            rig.session.limit.first { it is LimitState.Sending }
            delay(500.milliseconds) // written, waiting for an echo that doesn't come
            bike.dropLink()
            rig.session.state.first { it !is SessionState.Ready }
            rig.ready()
            delay(50.seconds) // within the minute: the next connection's re-apply waits
            assertEquals(listOf(SpeedLimit.KMH_32), bike.speeds())
            assertEquals(listOf("failed"), rig.values("state", "command"))
        }

    @Test
    fun `a speed the rider taps while a re-apply is being sent is refused, but becomes the kept one`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.echoDelay = 1500.milliseconds
            val rig = rig(bike)
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
            rig.session.start(bike.address)
            rig.ready()
            rig.session.limit.first { it is LimitState.Sending }
            assertEquals(CommandOutcome.Refused(Refusal.BUSY), rig.session.setSpeedLimit(SpeedLimit.KMH_25))
            rig.session.limit.first { it is LimitState.Confirmed }
            delay(2.minutes)
            bike.dropLink()
            rig.session.state.first { it !is SessionState.Ready }
            rig.ready()
            delay(1.minutes)
            assertEquals(listOf(SpeedLimit.KMH_32, SpeedLimit.KMH_25), bike.speeds())
        }
}
