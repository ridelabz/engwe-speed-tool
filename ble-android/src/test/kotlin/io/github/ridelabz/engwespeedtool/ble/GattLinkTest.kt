package io.github.ridelabz.engwespeedtool.ble

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.session.BikeGatt
import io.github.ridelabz.engwespeedtool.session.LinkEvent
import io.github.ridelabz.engwespeedtool.session.LinkException
import io.github.ridelabz.engwespeedtool.session.LinkFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The GATT queue's handling of what an Android stack does, beyond the link contract. */
class GattLinkTest {
    private class Setup(
        scope: CoroutineScope,
        present: Boolean = true,
    ) {
        val bike = FakeGattBike(scope, FakeGattBike.services(), present)
        val port = bike.newPort()
        val link = GattLink(port, GattTransport.OPERATION_TIMEOUT)

        suspend fun ready(): GattLink {
            link.connect()
            link.discoverServices()
            link.enableNotifications()
            return link
        }
    }

    private suspend fun failure(block: suspend () -> Unit): LinkException {
        try {
            block()
        } catch (e: LinkException) {
            return e
        }
        fail("expected a link failure")
        throw AssertionError()
    }

    @Test
    fun `answers that arrive before the call returns complete the operation`() =
        runTest {
            val s = Setup(backgroundScope)
            s.bike.synchronous = true
            s.ready()
            assertEquals(512, s.link.requestMtu(BikeGatt.MAX_MTU))
            s.link.write(BikeCommand.ReadIdentity.toFrame())
            assertEquals(-60, s.link.readRssi())
        }

    @Test
    fun `an operation the stack never answers ends the link and releases the connection`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.bike.withholdNextWrite = true
            val e = failure { s.link.write(BikeCommand.ReadIdentity.toFrame()) }
            assertEquals(LinkFailure.TIMEOUT, e.failure)
            assertTrue(s.port.closed)
            assertEquals(1, s.port.disconnects)
            val end =
                s.link.events
                    .toList()
                    .last() as LinkEvent.Disconnected
            assertEquals(LinkFailure.TIMEOUT, end.failure)
        }

    @Test
    fun `a call the stack refuses to start fails without ending the link`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.port.accepts = false
            assertEquals(LinkFailure.GATT_ERROR, failure { s.link.readRssi() }.failure)
            s.port.accepts = true
            assertEquals(-60, s.link.readRssi())
            assertFalse(s.port.closed)
        }

    @Test
    fun `a missing permission is reported as not permitted`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.port.throwsSecurity = true
            assertEquals(LinkFailure.NOT_PERMITTED, failure { s.link.readRssi() }.failure)
        }

    @Test
    fun `a connection that fails reports the stack's status and releases the attempt`() =
        runTest {
            val s = Setup(backgroundScope, present = false)
            val e = failure { s.link.connect() }
            assertEquals(LinkFailure.GATT_ERROR, e.failure)
            assertEquals(133, e.status)
            assertTrue(s.port.closed)
        }

    @Test
    fun `a connection that fails inside the call reports the stack's status`() =
        runTest {
            val bike = FakeGattBike(backgroundScope, FakeGattBike.services())
            val port =
                object : GattPort by bike.newPort() {
                    override fun connect(callbacks: GattCallbacks): Boolean {
                        callbacks.onConnectionStateChange(133, false)
                        return false
                    }
                }
            val e = failure { GattLink(port, GattTransport.OPERATION_TIMEOUT).connect() }
            assertEquals(LinkFailure.GATT_ERROR, e.failure)
            assertEquals(133, e.status)
        }

    @Test
    fun `cancelling a connection attempt releases it`() =
        runTest {
            val s = Setup(backgroundScope, present = false)
            assertNull(withTimeoutOrNull(1.seconds) { s.link.connect() })
            assertTrue(s.port.closed)
            assertEquals(1, s.port.disconnects)
        }

    @Test
    fun `a drop releases the connection, a close disconnects first`() =
        runTest {
            val dropped = Setup(backgroundScope)
            dropped.ready()
            dropped.port.drop()
            assertTrue(dropped.port.closed)
            assertEquals(0, dropped.port.disconnects)
            assertEquals(
                8,
                (
                    dropped.link.events
                        .toList()
                        .last() as LinkEvent.Disconnected
                ).status,
            )

            val closed = Setup(backgroundScope)
            closed.ready()
            closed.link.close()
            assertTrue(closed.port.closed)
            assertEquals(1, closed.port.disconnects)
        }

    @Test
    fun `answers of another kind don't complete the pending operation`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.bike.withholdNextWrite = true
            val pending = async { s.link.write(BikeCommand.ReadIdentity.toFrame()) }
            delay(100.milliseconds)
            val cb = s.port.callbacks!!
            cb.onMtuChanged(517, 0) // Android 14 and later exchange the MTU by themselves
            cb.onDescriptorWrite(0)
            cb.onReadRemoteRssi(-50, 0)
            delay(100.milliseconds)
            assertFalse(pending.isCompleted)
            cb.onCharacteristicWrite(0)
            pending.await()
        }

    @Test
    fun `a write rejected by the bike reports its status`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.bike.rejectNextWrite = 3
            val e = failure { s.link.write(BikeCommand.ReadIdentity.toFrame()) }
            assertEquals(LinkFailure.GATT_ERROR, e.failure)
            assertEquals(3, e.status)
            assertFalse(s.port.closed)
        }

    @Test
    fun `an operation cancelled while in flight closes the link`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.bike.withholdNextWrite = true
            assertNull(withTimeoutOrNull(1.seconds) { s.link.write(BikeCommand.ReadIdentity.toFrame()) })
            assertTrue(s.port.closed)
            assertEquals(
                LinkFailure.CLOSED,
                (
                    s.link.events
                        .toList()
                        .last() as LinkEvent.Disconnected
                ).failure,
            )
        }

    @Test
    fun `a drop right after the discovery answer is a lost link, not a bike without services`() =
        runTest {
            val s = Setup(backgroundScope)
            s.link.connect()
            s.bike.dropAfterNextAnswer = true
            assertEquals(LinkFailure.DISCONNECTED, failure { s.link.discoverServices() }.failure)
        }

    @Test
    fun `a write refused because the link just dropped is a lost link, not a refusal`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.bike.dropAtNextWrite = true
            assertEquals(LinkFailure.DISCONNECTED, failure { s.link.write(BikeCommand.ReadIdentity.toFrame()) }.failure)
            assertTrue(s.bike.received.none { it.descriptor == null })
        }

    @Test
    fun `when the phone refuses to register for notifications, nothing is written to the bike`() =
        runTest {
            val s = Setup(backgroundScope)
            s.link.connect()
            s.link.discoverServices()
            s.bike.refusesNotification = true
            assertEquals(LinkFailure.GATT_ERROR, failure { s.link.enableNotifications() }.failure)
            assertTrue(s.bike.received.isEmpty())
        }

    @Test
    fun `changed services end the link, so that a new connection discovers them again`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            s.port.callbacks!!.onServiceChanged()
            assertTrue(s.port.closed)
            assertEquals(1, s.port.disconnects)
            assertEquals(
                LinkFailure.DISCONNECTED,
                (
                    s.link.events
                        .toList()
                        .last() as LinkEvent.Disconnected
                ).failure,
            )
        }

    @Test
    fun `only the bike service's notify characteristic reaches the events`() =
        runTest {
            val s = Setup(backgroundScope)
            s.ready()
            val cb = s.port.callbacks!!
            cb.onCharacteristicChanged(BikeGatt.SERVICE, BikeGatt.WRITE, byteArrayOf(1))
            cb.onCharacteristicChanged(BikeGatt.standard(0xFFF0), BikeGatt.NOTIFY, byteArrayOf(2))
            cb.onCharacteristicChanged(BikeGatt.SERVICE, BikeGatt.NOTIFY, byteArrayOf(3))
            s.link.close()
            val values =
                s.link.events
                    .toList()
                    .filterIsInstance<LinkEvent.Notification>()
                    .map { it.value.toList() }
            assertEquals(listOf(listOf<Byte>(3)), values)
        }
}
