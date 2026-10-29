package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Frame
import io.github.ridelabz.engwespeedtool.protocol.FrameReassembler
import io.github.ridelabz.engwespeedtool.protocol.IdentityParser
import io.github.ridelabz.engwespeedtool.protocol.Lights
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.StatusDecoder
import io.github.ridelabz.engwespeedtool.protocol.StatusReport
import io.github.ridelabz.engwespeedtool.protocol.StreamEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeBikeTest {
    private val layout = ProfileRegistry.bundled().find(ProfileRegistry.REFERENCE_MODEL)!!.statusLayout

    /** What a connected test sees: raw notification values and the stream events they reassemble into. */
    private class Received(
        val link: BikeLink,
    ) {
        val values = mutableListOf<ByteArray>()
        val events = mutableListOf<StreamEvent>()
        val frames: List<Frame> get() = events.filterIsInstance<StreamEvent.Decoded>().map { it.frame }
    }

    private suspend fun TestScope.connect(
        bike: FakeBike,
        exchangeMtu: Boolean = true,
    ): Received {
        val link = FakeBikeTransport(bike).connect(bike.address)
        link.discoverServices()
        if (exchangeMtu) link.requestMtu(BikeGatt.MAX_MTU)
        link.enableNotifications()
        val received = Received(link)
        val reassembler = FrameReassembler()
        backgroundScope.launch {
            link.events.collect {
                if (it is LinkEvent.Notification) {
                    received.values += it.value
                    received.events += reassembler.feed(it.value)
                }
            }
        }
        return received
    }

    private fun Received.statuses(): List<StatusReport> =
        frames.filter { StatusDecoder.isStatus(it) }.map { checkNotNull(StatusDecoder.decode(it, layout)) }

    @Test
    fun `reports its values in a status frame about once a second`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride =
                FakeRide(
                    speedKmh = 23.4,
                    maxSpeedKmh = 31.8,
                    odometerKm = 93.4,
                    assistLevel = 3,
                    batteryPercent = 80,
                    lightsOn = true,
                )
            val rx = connect(bike)
            delay(10.seconds)
            val statuses = rx.statuses()
            assertEquals(10, statuses.size)
            assertTrue(rx.frames.all { it.crcOk })
            val s = statuses.last()
            assertEquals(23.4, s.speedKmh!!.value)
            assertEquals(31.8, s.maxSpeedKmh!!.value)
            assertEquals(93.4, s.odometerKm!!.value)
            assertEquals(3, s.assistLevel!!.value)
            assertEquals(80, s.batteryPercent!!.value)
            assertEquals(Lights.ON, s.lights!!.value)
            assertEquals(PowerState.ON, s.power!!.value)
            assertEquals(41.8, s.voltageV!!.value)
            assertEquals(742, s.rideTimeMinutes!!.value)
        }

    @Test
    fun `display off slows the status down, and switching on resets max speed and the limit`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.ride = FakeRide(maxSpeedKmh = 18.2)
            val rx = connect(bike)
            rx.link.write(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32).toFrame())
            delay(500.milliseconds)
            assertEquals(SpeedLimit.KMH_32, bike.speedLimit)

            bike.switchOff()
            val before = rx.statuses().size
            // About every 5 s while off (seen on the bike, also while locked).
            delay(15.seconds)
            val off = rx.statuses().drop(before)
            assertEquals(3, off.size)
            assertTrue(off.all { it.power!!.value == PowerState.OFF })

            bike.switchOn()
            val atSwitchOn = rx.statuses().size
            delay(4.seconds)
            val on = rx.statuses().drop(atSwitchOn)
            assertEquals(PowerState.POWERING_ON, on.first().power!!.value)
            assertEquals(PowerState.ON, on.last().power!!.value)
            assertTrue(on.all { it.maxSpeedKmh!!.value == 0.0 })
            assertEquals(SpeedLimit.BIKE_DEFAULT, bike.speedLimit)
        }

    @Test
    fun `after an unlock its status comes at once, then about every second again`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rx = connect(bike)
            rx.link.write(BikeCommand.SetLocked(true).toFrame())
            delay(7.seconds) // into the 5 s rhythm of a locked bike
            val before = rx.statuses().size
            rx.link.write(BikeCommand.SetLocked(false).toFrame())
            delay(3500.milliseconds)
            val on = rx.statuses().drop(before)
            // The one at once, and one a second after it, not 5 s later.
            assertTrue(on.size >= 3, "${on.size}")
            assertTrue(on.all { it.power!!.value == PowerState.ON })
        }

    @Test
    fun `echoes speed and light commands, and the lights show in the status`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rx = connect(bike)
            val commands =
                listOf(
                    BikeCommand.SetSpeedLimit(SpeedLimit.KMH_16),
                    BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32),
                    BikeCommand.SetLights(true),
                )
            for (command in commands) {
                val frame = command.toFrame()
                rx.link.write(frame)
                delay(200.milliseconds)
                assertTrue(rx.frames.any { frame.isEchoedBy(it) }, "no echo of $command")
            }
            delay(1.seconds)
            assertEquals(
                Lights.ON,
                rx
                    .statuses()
                    .last()
                    .lights!!
                    .value,
            )
            assertEquals(commands, bike.commands)
        }

    @Test
    fun `answers the identity read with the model, padding and its made-up serial`() =
        runTest {
            val bike = FakeBike(backgroundScope, model = "ENGWE_Other_model", serial = "TESTSERIAL123")
            val rx = connect(bike)
            rx.link.write(BikeCommand.ReadIdentity.toFrame())
            delay(200.milliseconds)
            val reply = rx.frames.single { IdentityParser.isIdentityReply(it) }
            assertTrue(reply.crcOk)
            assertEquals("ENGWE_Other_model", IdentityParser.modelOf(reply))
            assertEquals(54, reply.payloadSize)
            assertEquals('1'.code, reply.payloadByte(28))
            val serial = String(reply.payload.copyOfRange(34, 34 + 13), Charsets.US_ASCII)
            assertEquals("TESTSERIAL123", serial)
            assertEquals(listOf(BikeCommand.ReadIdentity), bike.commands)
        }

    @Test
    fun `cuts notifications to 20 bytes until the MTU is exchanged`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rx = connect(bike, exchangeMtu = false)
            delay(3.seconds)
            assertEquals(3, rx.values.size)
            assertTrue(rx.values.all { it.size == 20 })
            assertTrue(rx.frames.isEmpty())

            assertEquals(512, rx.link.requestMtu(BikeGatt.MAX_MTU))
            delay(1.seconds)
            assertEquals(64, rx.values.last().size)
            assertEquals(1, rx.statuses().size)
            val malformed = rx.events.filterIsInstance<StreamEvent.Malformed>()
            assertEquals(60, malformed.sumOf { it.size }, "the three cut values are reported as malformed")
        }

    @Test
    fun `advertises, and not while connected`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val transport = FakeBikeTransport(bike)
            val ad = transport.scan().first()
            assertEquals(bike.address, ad.address)
            assertEquals(-60, ad.rssi)

            val link = transport.connect(bike.address)
            assertNull(withTimeoutOrNull(5.seconds) { transport.scan().first() })
            assertNull(withTimeoutOrNull(5.seconds) { transport.connect(bike.address) }, "second connection")
            assertEquals(2, transport.connectAttempts)
            link.close()
            assertNotNull(transport.scan().first())
        }

    @Test
    fun `scans for one address only when asked`() =
        runTest {
            val a = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS)
            val b = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, rssi = -85)
            val transport = FakeBikeTransport(a, b)
            val seen = mutableListOf<Advertisement>()
            val job = launch { transport.scan(b.address).collect { seen += it } }
            delay(3.5.seconds)
            job.cancel()
            assertEquals(4, seen.size)
            assertTrue(seen.all { it.address == b.address && it.rssi == -85 })
        }

    @Test
    fun `Bluetooth switched off ends the link and stops scans and connections`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val transport = FakeBikeTransport(bike)
            val link = transport.connect(bike.address)
            link.discoverServices()
            transport.setRadio(RadioState.OFF)
            val end = link.events.toList().single() as LinkEvent.Disconnected
            assertEquals(LinkFailure.BLUETOOTH_OFF, end.failure)
            assertFalse(bike.connected)
            assertEquals(
                LinkFailure.BLUETOOTH_OFF,
                assertThrows<LinkException> { link.write(BikeCommand.ReadIdentity.toFrame()) }.failure,
            )
            assertEquals(LinkFailure.BLUETOOTH_OFF, assertThrows<LinkException> { transport.scan().first() }.failure)
            assertEquals(
                LinkFailure.BLUETOOTH_OFF,
                assertThrows<LinkException> { transport.connect(bike.address) }.failure,
            )
            assertTrue(bike.writes.isEmpty())
        }

    @Test
    fun `riding moves the speed, the distance and, once a minute, the ride time`() =
        runTest {
            val bike = FakeBike(backgroundScope, riding = true)
            val rx = connect(bike)
            delay(70.seconds)
            val statuses = rx.statuses()
            val speeds = statuses.map { it.speedKmh!!.value }
            assertTrue(speeds.all { it in 0.0..28.0 }, "$speeds")
            assertTrue(speeds.filter { it > 0.0 }.distinct().size > 5)
            assertTrue(statuses.all { it.maxSpeedKmh!!.value >= it.speedKmh!!.value })
            assertTrue(statuses.last().odometerKm!!.value > statuses.first().odometerKm!!.value)
            assertEquals(1, statuses.last().rideTimeMinutes!!.value - statuses.first().rideTimeMinutes!!.value)
        }

    @Test
    fun `the riding bike stands still for a while every minute and after each power-on`() =
        runTest {
            // Every minute: 20 status frames at 0 km/h, then a ride of 40 that eases in and out.
            val minute = (0 until 60).map { FakeBike.demoSpeed(it) }
            assertEquals(List(20) { 0.0 }, minute.take(20))
            assertTrue(minute.drop(20).all { it > 0.0 }, "$minute")
            assertTrue(minute.drop(24).dropLast(4).all { it in 16.0..28.0 }, "$minute")
            assertEquals(minute, (60 until 120).map { FakeBike.demoSpeed(it) })

            val bike = FakeBike(backgroundScope, riding = true)
            val rx = connect(bike)
            delay(30.seconds)
            assertTrue(bike.ride.speedKmh > 0.0)
            bike.switchOff()
            delay(10.seconds)
            bike.switchOn()
            delay(15.seconds)
            assertEquals(0.0, bike.ride.speedKmh, "standing still after the power-on")
            delay(10.seconds)
            assertTrue(bike.ride.speedKmh > 0.0, "riding again")
            assertTrue(rx.statuses().any { it.speedKmh!!.value == 0.0 })
        }

    @Test
    fun `a quick off and on again keeps the second power-on's timing`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            bike.switchOff()
            bike.switchOn()
            delay(1.5.seconds)
            bike.switchOff()
            bike.switchOn()
            delay(1.seconds)
            assertEquals(FakePower.POWERING_ON, bike.power, "the first power-on's delay doesn't end the second")
            delay(1.5.seconds)
            assertEquals(FakePower.ON, bike.power)
        }

    @Test
    fun `Bluetooth switched off while connecting leaves no link`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val transport = FakeBikeTransport(bike)
            val attempt = async { runCatching { transport.connect(bike.address) } }
            delay(100.milliseconds)
            transport.setRadio(RadioState.OFF)
            val e = attempt.await().exceptionOrNull() as LinkException
            assertEquals(LinkFailure.BLUETOOTH_OFF, e.failure)
            assertFalse(bike.connected)
        }

    @Test
    fun `addresses and notifications never show their content`() {
        val address = BikeAddress("f0:12:34:56:78:9a")
        assertEquals("F0:12:34:56:78:9A", address.value)
        assertEquals(BikeAddress("F0:12:34:56:78:9A"), address)
        assertFalse(address.toString().contains("78"))
        val e = assertThrows<IllegalArgumentException> { BikeAddress("F0-12-34-56-78-9A") }
        assertFalse(e.message!!.contains("78"))
        assertEquals("Notification(size=3)", LinkEvent.Notification(byteArrayOf(0x45, 0x6E, 0x2A)).toString())
    }
}
