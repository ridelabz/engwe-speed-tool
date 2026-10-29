@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Lights
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RideDataTest {
    private suspend fun Rig.ready() = session.state.first { it is SessionState.Ready } as SessionState.Ready

    @Test
    fun `ride data follows the bike, with a verified flag per value`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride =
                FakeRide(speedKmh = 23.4, maxSpeedKmh = 31.8, odometerKm = 93.4, batteryPercent = 80, lightsOn = true)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            delay(2.seconds)
            val ride = rig.session.ride.value
            val s = ride.status!!
            assertEquals(23.4, s.speedKmh!!.value)
            assertTrue(s.speedKmh!!.verified)
            assertEquals(31.8, s.maxSpeedKmh!!.value)
            assertEquals(93.4, s.odometerKm!!.value)
            assertEquals(80, s.batteryPercent!!.value)
            assertEquals(Lights.ON, s.lights!!.value)
            assertEquals(41.8, s.voltageV!!.value)
            assertTrue(s.voltageV!!.verified)
            assertEquals(PowerState.ON, ride.power)
            assertTrue(ride.powerVerified)
            assertFalse(ride.stale)
            assertFalse(ride.bikeOff)
            assertFalse(ride.unreadable)
        }

    @Test
    fun `on an unverified bike every value is unverified`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope, model = "ENGWE_Other_model"))
            rig.session.start(rig.bike.address)
            rig.ready()
            delay(2.seconds)
            val ride = rig.session.ride.value
            val s = ride.status!!
            assertTrue(listOfNotNull(s.speedKmh, s.maxSpeedKmh, s.odometerKm, s.voltageV).none { it.verified })
            assertFalse(s.batteryPercent!!.verified)
            assertFalse(ride.powerVerified)
        }

    @Test
    fun `the display going off and on shows as bike off, then one power-on with max speed reset`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride = FakeRide(maxSpeedKmh = 18.2)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            delay(2.seconds)
            assertEquals(
                18.2,
                rig.session.ride.value.status!!
                    .maxSpeedKmh!!
                    .value,
            )
            bike.switchOff()
            delay(4.seconds)
            assertTrue(rig.session.ride.value.bikeOff)
            assertEquals(0, rig.session.ride.value.powerOns)
            bike.switchOn()
            // The first frame after switching on may come while one flag is still off, or after both are on.
            rig.session.ride.first { it.power != PowerState.OFF }
            assertEquals(1, rig.session.ride.value.powerOns)
            rig.session.ride.first { it.power == PowerState.ON }
            delay(3.seconds)
            val ride = rig.session.ride.value
            assertEquals(PowerState.ON, ride.power)
            assertEquals(1, ride.powerOns, "counted once, not again when the second flag returns")
            assertEquals(0.0, ride.status!!.maxSpeedKmh!!.value)
            assertEquals(listOf(BikeCommand.ReadIdentity), bike.commands, "nothing sent at the power-on")
        }

    @Test
    fun `no status for a while makes the data stale, and fresh frames clear it`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            rig.ready()
            delay(2.seconds)
            rig.bike.sendsStatus = false
            delay(9.seconds)
            assertFalse(rig.session.ride.value.stale, "frames every 3 s with the display off aren't stale")
            delay(2.seconds)
            assertTrue(rig.session.ride.value.stale)
            assertTrue(rig.session.state.value is SessionState.Ready, "stale data doesn't end the connection")
            rig.bike.sendsStatus = true
            delay(1.5.seconds)
            assertFalse(rig.session.ride.value.stale)
        }

    @Test
    fun `status frames that don't fit the layout show no data`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { statusPayloadLength = 50 }
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            delay(3.seconds)
            val ride = rig.session.ride.value
            assertTrue(ride.unreadable)
            assertNull(ride.status)
            assertEquals(PowerState.UNKNOWN, ride.power)
            assertTrue(rig.records().none { it.s("event") == "status" })
        }

    @Test
    fun `decoded values are logged when they change, not with every frame`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            rig.ready()
            delay(10.seconds)
            assertEquals(1, rig.records().count { it.s("event") == "status" })
            rig.bike.ride = rig.bike.ride.copy(speedKmh = 12.3)
            delay(5.seconds)
            assertEquals(2, rig.records().count { it.s("event") == "status" })
            assertTrue(rig.records().count { it.s("event") == "rx" } >= 15)
        }

    @Test
    fun `a power-on while the app wasn't connected isn't counted`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.switchOff()
            delay(4.seconds)
            assertTrue(rig.session.ride.value.bikeOff)
            bike.advertises = false
            bike.dropLink()
            delay(100.milliseconds)
            assertTrue(rig.session.ride.value.stale, "a dropped link leaves stale data")
            bike.switchOn()
            delay(5.seconds)
            bike.advertises = true
            rig.ready()
            delay(2.seconds)
            assertEquals(PowerState.ON, rig.session.ride.value.power)
            assertEquals(0, rig.session.ride.value.powerOns)
        }

    @Test
    fun `stop leaves the last values, marked stale`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            rig.ready()
            delay(2.seconds)
            rig.session.stop()
            assertTrue(rig.session.ride.value.stale)
            assertTrue(rig.session.ride.value.status != null)
        }

    @Test
    fun `power-ons keep counting across reconnects, and a new start begins afresh`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.switchOff()
            delay(4.seconds)
            bike.switchOn()
            delay(4.seconds)
            assertEquals(1, rig.session.ride.value.powerOns)
            bike.dropLink()
            rig.session.state.first { it is SessionState.Retrying }
            rig.ready()
            delay(2.seconds)
            assertEquals(1, rig.session.ride.value.powerOns)
            rig.session.stop()
            rig.session.start(bike.address)
            assertNull(rig.session.ride.value.status)
            assertEquals(0, rig.session.ride.value.powerOns)
        }
}

class ReconnectTest {
    @Test
    fun `in a storm of drops each connection reads once, and the session recovers`() =
        runTest {
            val rnd = Random(2026)
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            repeat(60) {
                delay(rnd.nextLong(0, 8_000).milliseconds)
                bike.dropLink()
            }
            rig.session.state.first { it is SessionState.Ready }
            delay(1.minutes)
            assertTrue(rig.session.state.value is SessionState.Ready)
            assertTrue(bike.writesPerLink.all { it <= 1 }, "${bike.writesPerLink}")
            assertTrue(bike.commands.all { it == BikeCommand.ReadIdentity })
            assertEquals(bike.links, rig.transport.connectAttempts, "every attempt either connected or was refused")
            assertTrue(bike.links > 5, "the storm reached several connections: ${bike.links}")
            val reads = rig.records().filter { it.s("event") == "tx" }
            assertEquals(bike.commands.size, reads.size, "every write is logged")
        }

    @Test
    fun `waits grow after each failed attempt, then repeat`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { advertises = false }
            val config = SessionConfig()
            val rig = Rig(this, bike, config = config)
            rig.session.start(bike.address)
            delay(10.minutes)
            val searches =
                rig
                    .records()
                    .filter { it.s("event") == "state" && it.s("name") == "check" && it.s("value") == "searching" }
                    .map { it.s("t").toDouble() }
            val gaps = searches.zipWithNext { a, b -> b - a }
            val expected =
                gaps.indices.map {
                    (config.searchTimeout + config.retryDelay(it + 1)).inWholeMilliseconds /
                        1000.0
                }
            assertEquals(expected, gaps)
            assertEquals(30.seconds, config.retryDelay(gaps.size))
            assertEquals(0, rig.transport.connectAttempts)
        }

    @Test
    fun `a bike that advertises but refuses connections may be connected elsewhere`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { refusesConnections = true }
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            assertEquals(SessionState.Retrying(StopReason.CONNECT_FAILED, LinkFailure.GATT_ERROR, 1), rig.settled())
            rig.session.state.first { it is SessionState.Retrying && it.attempt == 3 }
            assertTrue(bike.writes.isEmpty())
            assertTrue(rig.values("state", "gatt_status").all { it == "133" })
            bike.refusesConnections = false
            rig.session.state.first { it is SessionState.Ready }
        }

    @Test
    fun `an incompatible bike isn't tried again`() =
        runTest {
            val bike =
                FakeBike(backgroundScope, services = FakeBike.REFERENCE_SERVICES.filter { it.uuid != BikeGatt.SERVICE })
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            assertEquals(SessionState.Incompatible(CheckFailure.NO_SERVICE), rig.settled())
            delay(10.minutes)
            assertEquals(1, rig.transport.connectAttempts)
        }

    @Test
    fun `a failed first attempt from the find screen isn't retried, a checked bike is reconnected`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { refusesConnections = true }
            val rig = Rig(this, bike)
            rig.startOnce()
            assertEquals(SessionState.Stopped(StopReason.CONNECT_FAILED, LinkFailure.GATT_ERROR), rig.settled())
            delay(5.minutes)
            assertEquals(1, rig.transport.connectAttempts)

            bike.refusesConnections = false
            rig.startOnce()
            rig.session.state.first { it is SessionState.Ready }
            bike.dropLink()
            assertEquals(
                SessionState.Retrying(StopReason.LINK_LOST, LinkFailure.DISCONNECTED, 1),
                rig.session.state.first { it is SessionState.Retrying },
            )
            rig.session.state.first { it is SessionState.Ready }
            assertEquals(listOf(1, 1), bike.writesPerLink)
        }

    @Test
    fun `Bluetooth switched off while connected makes the session wait, then reconnect`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.session.state.first { it is SessionState.Ready }
            rig.transport.setRadio(RadioState.OFF)
            assertEquals(
                SessionState.Retrying(StopReason.BLUETOOTH_UNAVAILABLE, LinkFailure.BLUETOOTH_OFF, 1),
                rig.session.state.first { it is SessionState.Retrying },
            )
            assertTrue(rig.session.ride.value.stale)
            delay(5.minutes)
            assertEquals(1, rig.transport.connectAttempts)
            rig.transport.setRadio(RadioState.ON)
            rig.session.state.first { it is SessionState.Ready }
            assertEquals(listOf(1, 1), bike.writesPerLink)
        }

    /** Scan starts, by virtual time, through a transport that counts them. */
    private class CountingScans(
        inner: BikeTransport,
        private val now: () -> Long,
        private val refuse: Boolean = false,
    ) : WrappedTransport(inner) {
        val starts = mutableListOf<Long>()

        override fun scan(address: BikeAddress?): Flow<Advertisement> {
            val upstream = super.scan(address)
            return flow {
                starts += now()
                if (refuse) throw LinkException(LinkFailure.NOT_PERMITTED)
                emitAll(upstream)
            }
        }

        fun mostIn30s(): Int = starts.maxOfOrNull { t -> starts.count { it in t until t + 30_000 } } ?: 0
    }

    @Test
    fun `a refused scan while the radio reads on is retried after a wait, not in a loop`() =
        runTest {
            lateinit var scans: CountingScans
            val rig =
                Rig(this, FakeBike(backgroundScope)) { inner ->
                    CountingScans(inner, { testScheduler.currentTime }, refuse = true).also { scans = it }
                }
            rig.session.start(rig.bike.address)
            delay(2.minutes)
            assertTrue(scans.starts.size in 4..10, "${scans.starts}")
            assertTrue(scans.mostIn30s() <= 4)
            val state = rig.session.state.value as SessionState.Retrying
            assertEquals(StopReason.BLUETOOTH_UNAVAILABLE, state.reason)
            assertEquals(LinkFailure.NOT_PERMITTED, state.failure)
        }

    @Test
    fun `quick drops after each check make the waits grow and keep scans within Android's limit`() =
        runTest {
            lateinit var scans: CountingScans
            val bike = FakeBike(backgroundScope)
            val rig =
                Rig(this, bike) { inner -> CountingScans(inner, { testScheduler.currentTime }).also { scans = it } }
            rig.session.start(bike.address)
            val attempts = mutableListOf<Int>()
            repeat(8) {
                rig.session.state.first { it is SessionState.Ready }
                delay(500.milliseconds)
                bike.dropLink()
                attempts += (rig.session.state.first { it is SessionState.Retrying } as SessionState.Retrying).attempt
            }
            assertEquals((1..8).toList(), attempts, "a connection that drops at once doesn't reset the waits")
            assertTrue(scans.mostIn30s() <= 4, "${scans.starts}")
            assertTrue(bike.writesPerLink.all { it <= 1 })
        }

    @Test
    fun `a connection that stayed up resets the waits`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.session.state.first { it is SessionState.Ready }
            bike.dropLink()
            assertEquals(1, (rig.session.state.first { it is SessionState.Retrying } as SessionState.Retrying).attempt)
            rig.session.state.first { it is SessionState.Ready }
            delay(31.seconds)
            bike.dropLink()
            assertEquals(1, (rig.session.state.first { it is SessionState.Retrying } as SessionState.Retrying).attempt)
        }

    @Test
    fun `in the storm of drops scans stay within Android's limit`() =
        runTest {
            lateinit var scans: CountingScans
            val rnd = Random(2026)
            val bike = FakeBike(backgroundScope)
            val rig =
                Rig(this, bike) { inner -> CountingScans(inner, { testScheduler.currentTime }).also { scans = it } }
            rig.session.start(bike.address)
            repeat(60) {
                delay(rnd.nextLong(0, 8_000).milliseconds)
                bike.dropLink()
            }
            assertTrue(scans.mostIn30s() <= 4, "${scans.starts}")
        }

    @Test
    fun `a disconnect whose events never end still leads to a reconnect`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig =
                Rig(this, bike) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink {
                            val link = super.connect(address)
                            return object : BikeLink by link {
                                override val events: Flow<LinkEvent> =
                                    flow {
                                        link.events.collect {
                                            emit(it)
                                            if (it is LinkEvent.Disconnected) awaitCancellation()
                                        }
                                    }
                            }
                        }
                    }
                }
            rig.session.start(bike.address)
            rig.session.state.first { it is SessionState.Ready }
            bike.dropLink()
            rig.session.state.first { it is SessionState.Retrying }
            rig.session.state.first { it is SessionState.Ready }
        }

    @Test
    fun `a characteristic problem on one connection is retried, not taken for missing Bluetooth`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            var connections = 0
            val rig =
                Rig(this, bike) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink {
                            val link = super.connect(address)
                            if (++connections > 1) return link
                            return object : BikeLink by link {
                                override suspend fun requestMtu(mtu: Int): Int =
                                    throw LinkException(LinkFailure.UNSUPPORTED)
                            }
                        }
                    }
                }
            rig.session.start(bike.address)
            assertEquals(SessionState.Retrying(StopReason.LINK_LOST, LinkFailure.UNSUPPORTED, 1), rig.settled())
            rig.session.state.first { it is SessionState.Ready }
        }

    @Test
    fun `without acceptance the session doesn't retry either`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope), accepted = false)
            rig.session.start(rig.bike.address)
            assertEquals(SessionState.Stopped(StopReason.NOT_ACCEPTED), rig.settled())
            delay(5.minutes)
            assertEquals(1, rig.transport.connectAttempts)
            assertTrue(rig.bike.writes.isEmpty())
        }
}
