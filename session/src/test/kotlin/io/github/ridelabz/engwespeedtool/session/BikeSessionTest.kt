@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Hex
import io.github.ridelabz.engwespeedtool.protocol.UnverifiedReason
import io.github.ridelabz.engwespeedtool.protocol.Verification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BikeSessionTest {
    private val allSteps = listOf("searching", "connecting", "services", "notifications", "status", "identity")

    private fun ready(state: SessionState) = (state as SessionState.Ready).classification

    @Test
    fun `checks the reference bike, reads its identity once and finds it verified`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            val c = ready(rig.settled())
            assertEquals(Verification.VERIFIED, c.verification)
            assertEquals("ENGWE_Omni_ebike1100EU317", c.modelIdentity)
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
            assertEquals(allSteps, rig.steps())
            assertEquals(listOf("512"), rig.values("state", "mtu"))
            val classification = rig.records().single { it.s("event") == "classification" }
            assertEquals("verified", classification.s("verification"))
            assertEquals(1, rig.records().count { it.s("event") == "tx" })
            assertTrue(rig.bike.connected)
        }

    @Test
    fun `each start numbers its run, and a stop ends it with a higher number`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            val first = rig.session.start(rig.bike.address)
            rig.settled()
            assertEquals(first, rig.session.status.value.run)
            assertTrue(rig.session.status.value.state is SessionState.Ready)
            rig.session.stop()
            val stopped = rig.session.status.value
            assertTrue(stopped.run > first && stopped.state == SessionState.Idle)
            val second = rig.session.start(rig.bike.address)
            assertTrue(second > stopped.run)
            // Until the new run reports, the status is still the stop's: an earlier number.
            assertTrue(rig.session.status.value.run < second)
            val firstOfSecond = rig.session.status.first { it.run == second }
            assertTrue(firstOfSecond.state is SessionState.Checking, "a new run starts checking")
            rig.settled()
            assertEquals(second, rig.session.status.value.run)
            assertEquals(rig.session.state.value, rig.session.status.value.state)
        }

    @Test
    fun `a bike whose identity differs at all is unverified`() =
        runTest {
            val cases =
                listOf(
                    FakeBike(backgroundScope, model = "ENGWE_Other_model") to UnverifiedReason.UNKNOWN_MODEL,
                    FakeBike(backgroundScope, model = "ENGWE_Omni_ebike1100EU318") to UnverifiedReason.UNKNOWN_MODEL,
                    FakeBike(backgroundScope, model = "ENGWE_Omni_ebike1100EU") to UnverifiedReason.UNKNOWN_MODEL,
                )
            for ((bike, reason) in cases) {
                val rig = Rig(this, bike)
                rig.session.start(bike.address)
                val c = ready(rig.settled())
                assertEquals(Verification.UNVERIFIED, c.verification, "$reason")
                assertEquals(reason, c.unverifiedReason)
                assertTrue(c.layout!!.fields.none { it.verified }, "every field unverified")
                assertEquals(listOf(BikeCommand.ReadIdentity), bike.commands)
                rig.session.stop()
            }
        }

    @Test
    fun `connects with the advertisement it was given instead of searching again`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(Advertisement(rig.bike.address, -70))
            val c = ready(rig.settled())
            assertEquals(Verification.VERIFIED, c.verification)
            assertEquals(allSteps.drop(1), rig.steps())
            val scan = rig.records().single { it.s("event") == "scan_result" }
            assertEquals("-70", scan.s("rssi"))
        }

    @Test
    fun `a bike failing a check before the identity read gets no write at all`() =
        runTest {
            fun services(
                uuid: java.util.UUID,
                change: (GattCharacteristic) -> GattCharacteristic?,
            ) = FakeBike.REFERENCE_SERVICES.map { s ->
                s.copy(characteristics = s.characteristics.mapNotNull { if (it.uuid == uuid) change(it) else it })
            }
            val silent = FakeTiming(statusOn = Duration.INFINITE, statusOff = Duration.INFINITE)
            val cases =
                listOf(
                    FakeBike(
                        backgroundScope,
                        services =
                            FakeBike.REFERENCE_SERVICES.filter {
                                it.uuid !=
                                    BikeGatt.SERVICE
                            },
                    )
                        to CheckFailure.NO_SERVICE,
                    FakeBike(backgroundScope, services = services(BikeGatt.NOTIFY) { null }) to CheckFailure.NO_NOTIFY,
                    FakeBike(
                        backgroundScope,
                        services = services(BikeGatt.NOTIFY) { it.copy(descriptors = emptyList()) },
                    )
                        to CheckFailure.NO_NOTIFY,
                    FakeBike(
                        backgroundScope,
                        services =
                            services(
                                BikeGatt.NOTIFY,
                            ) { it.copy(properties = setOf(CharacteristicProperty.READ)) },
                    ) to CheckFailure.NO_NOTIFY,
                    FakeBike(backgroundScope, services = services(BikeGatt.WRITE) { null }) to
                        CheckFailure.NO_ACKNOWLEDGED_WRITE,
                    FakeBike(
                        backgroundScope,
                        services =
                            services(BikeGatt.WRITE) {
                                it.copy(properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE))
                            },
                    ) to CheckFailure.NO_ACKNOWLEDGED_WRITE,
                    FakeBike(backgroundScope, timing = silent) to CheckFailure.NO_STATUS,
                )
            for ((bike, failure) in cases) {
                val rig = Rig(this, bike)
                rig.session.start(bike.address)
                assertEquals(SessionState.Incompatible(failure), rig.settled())
                assertTrue(bike.writes.isEmpty(), "$failure: wrote ${bike.commands}")
                val descriptorWrites = if (failure == CheckFailure.NO_STATUS) 1 else 0
                assertEquals(descriptorWrites, bike.gattWrites.size, "$failure: only the client configuration")
                assertTrue(rig.records().none { it.s("event") == "tx" })
                delay(100.milliseconds)
                assertFalse(bike.connected, "$failure: link left open")
            }
        }

    @Test
    fun `a bike without a usable identity reply is incompatible after the one read`() =
        runTest {
            val silent = FakeBike(backgroundScope).apply { answersIdentity = false }
            val serialLike = FakeBike(backgroundScope, model = "ENGWE_12345678")
            val addressLike = FakeBike(backgroundScope, model = "F0:12:34:56:78:9A")
            val nameLike = FakeBike(backgroundScope, model = "ENGWE-7C")
            for (bike in listOf(silent, serialLike, addressLike, nameLike)) {
                val rig = Rig(this, bike)
                rig.session.start(bike.address)
                assertEquals(SessionState.Incompatible(CheckFailure.NO_IDENTITY), rig.settled())
                assertEquals(listOf(BikeCommand.ReadIdentity), bike.commands)
                delay(100.milliseconds)
                assertFalse(bike.connected)
                if (bike !== silent) {
                    assertEquals("false", rig.records().single { it.s("event") == "identity" }.s("usable"))
                    val text = rig.out.toString()
                    for (secret in listOf(bike.model, Hex.encode(bike.model.toByteArray()))) {
                        assertFalse(text.contains(secret, ignoreCase = true), "${bike.model} logged")
                    }
                }
            }
        }

    @Test
    fun `without the rider's acceptance nothing is written`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope), accepted = false)
            rig.session.start(rig.bike.address)
            assertEquals(SessionState.Stopped(StopReason.NOT_ACCEPTED), rig.settled())
            assertTrue(rig.bike.writes.isEmpty())
        }

    @Test
    fun `a bike that isn't advertising is not found and not connected`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(BikeAddress("F0:00:00:00:00:99"))
            assertEquals(SessionState.Retrying(StopReason.NOT_FOUND, null, 1), rig.settled())
            assertEquals(0, rig.transport.connectAttempts)
            assertEquals(20_000, testScheduler.currentTime)
        }

    @Test
    fun `Bluetooth off makes the session wait for it`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.transport.setRadio(RadioState.OFF)
            rig.session.start(rig.bike.address)
            assertEquals(
                SessionState.Retrying(StopReason.BLUETOOTH_UNAVAILABLE, LinkFailure.BLUETOOTH_OFF, 1),
                rig.settled(),
            )
            delay(5.minutes)
            assertEquals(0, rig.transport.connectAttempts)
            rig.transport.setRadio(RadioState.ON)
            ready(rig.session.state.first { it is SessionState.Ready })
        }

    @Test
    fun `a drop during the checks ends the session before any write`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.startOnce()
            rig.session.state.first { it == SessionState.Checking(CheckStep.NOTIFICATIONS) }
            rig.bike.dropLink()
            assertEquals(SessionState.Stopped(StopReason.LINK_LOST, LinkFailure.DISCONNECTED), rig.settled())
            assertTrue(rig.bike.writes.isEmpty())
        }

    @Test
    fun `a drop after the checks reconnects, with one read per connection`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            ready(rig.settled())
            delay(5.seconds)
            rig.bike.dropLink()
            val retry = rig.session.state.first { it is SessionState.Retrying }
            assertEquals(SessionState.Retrying(StopReason.LINK_LOST, LinkFailure.DISCONNECTED, 1), retry)
            assertEquals(listOf("8"), rig.values("state", "gatt_status"))
            ready(rig.session.state.first { it is SessionState.Ready })
            delay(30.seconds)
            assertEquals(listOf(1, 1), rig.bike.writesPerLink)
        }

    @Test
    fun `stop disconnects, and a new start replaces the running one`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            ready(rig.settled())
            rig.session.stop()
            assertEquals(SessionState.Idle, rig.session.state.value)
            delay(100.milliseconds)
            assertFalse(rig.bike.connected)

            rig.session.start(rig.bike.address)
            rig.session.state.first { it == SessionState.Checking(CheckStep.STATUS) }
            rig.session.start(rig.bike.address)
            ready(rig.settled())
            assertEquals(2, rig.bike.commands.size, "one identity read per connection: the first and the last")
            assertTrue(rig.bike.connected)
            assertEquals(3, rig.transport.connectAttempts)
        }

    @Test
    fun `a rejected identity read leaves the bike incompatible after that one write`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope).apply { rejectNextWrite = 3 })
            rig.session.start(rig.bike.address)
            assertEquals(SessionState.Incompatible(CheckFailure.NO_IDENTITY), rig.settled())
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
            assertEquals(2, rig.bike.gattWrites.size, "the client configuration and the read")
            assertEquals(listOf("3"), rig.values("state", "gatt_status"))
        }

    @Test
    fun `an unanswered identity read ends the connection, and nothing follows it`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope).apply { withholdNextWriteResponse = true })
            rig.startOnce()
            assertEquals(SessionState.Stopped(StopReason.LINK_LOST, LinkFailure.TIMEOUT), rig.settled())
            delay(100.milliseconds)
            assertFalse(rig.bike.connected)
            delay(30.seconds)
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
        }

    @Test
    fun `an identity reply nobody asked for is ignored`() =
        runTest {
            val silent = FakeTiming(statusOn = Duration.INFINITE, statusOff = Duration.INFINITE)
            val rig = Rig(this, FakeBike(backgroundScope, timing = silent))
            rig.session.start(rig.bike.address)
            rig.session.state.first { it == SessionState.Checking(CheckStep.STATUS) }
            val other = "ENGWE_Other_model".toByteArray() + ByteArray(37)
            rig.bike.notifyRaw(SimulatedFrames.encode(0xFF01, other))
            rig.bike.notifyRaw(SimulatedFrames.encode(0x2A63, ByteArray(54)))
            val c = ready(rig.settled())
            assertEquals(Verification.VERIFIED, c.verification, "the model comes from the reply to the read")
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
            assertEquals(1, rig.records().count { it.s("event") == "identity" })
        }

    @Test
    fun `status frames with a bad CRC don't pass the status check`() =
        runTest {
            val silent = FakeTiming(statusOn = Duration.INFINITE, statusOff = Duration.INFINITE)
            val rig = Rig(this, FakeBike(backgroundScope, timing = silent))
            rig.session.start(rig.bike.address)
            rig.session.state.first { it == SessionState.Checking(CheckStep.STATUS) }
            val corrupt = SimulatedFrames.encode(0x2A63, ByteArray(54)).also { it[it.size - 3] = 0x5A }
            val sender =
                launch {
                    repeat(15) {
                        rig.bike.notifyRaw(corrupt)
                        delay(1.seconds)
                    }
                }
            assertEquals(SessionState.Incompatible(CheckFailure.NO_STATUS), rig.settled())
            sender.cancel()
            assertTrue(rig.bike.writes.isEmpty())
            assertEquals(1, rig.bike.gattWrites.size, "only the client configuration")
        }

    @Test
    fun `stop during the identity step leaves nothing behind`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.start(rig.bike.address)
            rig.session.state.first { it == SessionState.Checking(CheckStep.IDENTITY) }
            rig.session.stop()
            assertEquals(SessionState.Idle, rig.session.state.value)
            delay(30.seconds)
            assertEquals(SessionState.Idle, rig.session.state.value)
            assertFalse(rig.bike.connected)
            assertTrue(rig.bike.commands.size <= 1)
        }

    @Test
    fun `a link that arrives as the session stops is closed`() =
        runTest {
            lateinit var rig: Rig
            rig =
                Rig(this, FakeBike(backgroundScope)) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink =
                            super.connect(address).also { rig.session.stop() }
                    }
                }
            rig.session.start(rig.bike.address)
            delay(30.seconds)
            assertEquals(SessionState.Idle, rig.session.state.value)
            assertFalse(rig.bike.connected, "the link was left open")
            assertTrue(rig.bike.writes.isEmpty())
        }

    @Test
    fun `a stop and an immediate start never hold two links`() =
        runTest {
            var open = 0
            var most = 0
            val rig =
                Rig(this, FakeBike(backgroundScope)) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink {
                            val link = super.connect(address)
                            most = maxOf(most, ++open)
                            // An old attempt that takes a while to finish, as a real stack may.
                            return object : BikeLink by link {
                                private var closed = false

                                override suspend fun discoverServices(): List<GattService> {
                                    withContext(NonCancellable) { delay(5.seconds) }
                                    return link.discoverServices()
                                }

                                override fun close() {
                                    if (!closed) open--
                                    closed = true
                                    link.close()
                                }
                            }
                        }
                    }
                }
            rig.session.start(rig.bike.address)
            rig.session.state.first { it == SessionState.Checking(CheckStep.SERVICES) }
            rig.session.stop()
            rig.session.start(Advertisement(rig.bike.address, -60))
            ready(rig.settled())
            assertEquals(1, most, "two links at once")
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
        }

    @Test
    fun `a transport's own timeout ends the attempt like any failure`() =
        runTest {
            val rig =
                Rig(this, FakeBike(backgroundScope)) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink =
                            withTimeout(1.seconds) { awaitCancellation() }
                    }
                }
            rig.startOnce()
            assertEquals(SessionState.Stopped(StopReason.LINK_LOST), rig.settled())
            assertEquals(
                "TimeoutCancellationException",
                rig.records().single { it.s("event") == "error" }.s("exception"),
            )
        }

    @Test
    fun `no identity read once the link's events have ended`() =
        runTest {
            val status = SimulatedFrames.encode(0x2A63, ByteArray(54))
            val rig =
                Rig(this, FakeBike(backgroundScope)) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink {
                            val link = super.connect(address)
                            return object : BikeLink by link {
                                override val events: Flow<LinkEvent> =
                                    flow {
                                        emit(LinkEvent.Notification(status))
                                        throw IOException("broken")
                                    }
                            }
                        }
                    }
                }
            rig.startOnce()
            assertEquals(SessionState.Stopped(StopReason.LINK_LOST, LinkFailure.DISCONNECTED), rig.settled())
            assertTrue(rig.bike.writes.isEmpty())
        }

    @Test
    fun `an unexpected failure ends the attempt instead of hanging`() =
        runTest {
            val rig =
                Rig(this, FakeBike(backgroundScope)) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink {
                            val link = super.connect(address)
                            return object : BikeLink by link {
                                override val events: Flow<LinkEvent> = flow { throw IOException("broken") }
                            }
                        }
                    }
                }
            rig.startOnce()
            assertEquals(SessionState.Stopped(StopReason.LINK_LOST, LinkFailure.DISCONNECTED), rig.settled())
            assertTrue(rig.bike.writes.isEmpty())
            assertEquals("IOException", rig.records().single { it.s("event") == "error" }.s("exception"))
            assertFalse(rig.out.contains("broken"))

            val crash =
                Rig(this, FakeBike(backgroundScope)) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink = throw IllegalStateException("x")
                    }
                }
            crash.startOnce()
            assertEquals(SessionState.Stopped(StopReason.LINK_LOST), crash.settled())
            assertEquals("IllegalStateException", crash.records().single { it.s("event") == "error" }.s("exception"))
        }

    @Test
    fun `a phone without Bluetooth LE is reported as Bluetooth unavailable`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.transport.setRadio(RadioState.UNAVAILABLE)
            rig.session.start(rig.bike.address)
            assertEquals(SessionState.Stopped(StopReason.BLUETOOTH_UNAVAILABLE, LinkFailure.UNSUPPORTED), rig.settled())
            delay(5.minutes)
            assertEquals(
                SessionState.Stopped(StopReason.BLUETOOTH_UNAVAILABLE, LinkFailure.UNSUPPORTED),
                rig.session.state.value,
            )
        }

    @Test
    fun `the log holds the model and the one read, never a tag, the serial or the address`() =
        runTest {
            val bike = FakeBike(backgroundScope, BikeAddress("F0:12:34:56:78:9A"), serial = "QZXKWJVPYBGHMNQZXKW")
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            ready(rig.settled())
            delay(10.seconds)
            val text = rig.out.toString()
            assertTrue(text.contains("\"model\":\"ENGWE_Omni_ebike1100EU317\""))
            assertFalse(text.contains("firmware"))
            assertFalse(text.contains("OMS"))
            assertEquals(
                listOf("read_identity"),
                rig.records().filter { it.s("event") == "tx" }.map { it.s("command") },
            )
            Secrets.assertAbsent(text, Secrets.address(bike.address) + Secrets.serial(bike.serial))
            assertTrue(rig.records().any { it.s("event") == "rx" && it["sanitised"] != null }, "identity reply masked")
        }
}
