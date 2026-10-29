package io.github.ridelabz.engwespeedtool.ble

import io.github.ridelabz.engwespeedtool.protocol.OutgoingFrame
import io.github.ridelabz.engwespeedtool.session.BikeGatt
import io.github.ridelabz.engwespeedtool.session.BikeLink
import io.github.ridelabz.engwespeedtool.session.CharacteristicProperty
import io.github.ridelabz.engwespeedtool.session.GattCharacteristic
import io.github.ridelabz.engwespeedtool.session.GattService
import io.github.ridelabz.engwespeedtool.session.LinkEvent
import io.github.ridelabz.engwespeedtool.session.LinkException
import io.github.ridelabz.engwespeedtool.session.LinkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration

/**
 * A [BikeLink] over one GATT connection. GATT operations run one at a time, each with [operationTimeout];
 * an operation the stack never answers ends the link, because the stack then won't run another one. A
 * connection that drops is released at once, and so is one that is closed.
 *
 * Writes go only to the bike service's write characteristic, only as acknowledged writes, and only when
 * the bike declared acknowledged writes for it.
 */
internal class GattLink(
    private val port: GattPort,
    private val operationTimeout: Duration,
) : BikeLink {
    private val lock = Any()
    private val channel = Channel<LinkEvent>(Channel.UNLIMITED)
    private val operations = Mutex()
    private val connected = CompletableDeferred<Unit>()
    private var ended: LinkFailure? = null
    private var endError: LinkException? = null
    private var pending: Pending? = null
    private var services: List<GattService>? = null

    override val events: Flow<LinkEvent> = channel.receiveAsFlow()

    private enum class Kind { DISCOVER, MTU, DESCRIPTOR, WRITE, RSSI }

    private class Result(
        val status: Int,
        val value: Int,
    )

    private class Pending(
        val kind: Kind,
        val result: CompletableDeferred<Result>,
    )

    /** Connects; returns once connected. Cancelling (for example by a timeout) closes the attempt. */
    suspend fun connect() {
        val started =
            try {
                port.connect(callbacks)
            } catch (_: SecurityException) {
                end(LinkFailure.NOT_PERMITTED, null)
                throw LinkException(LinkFailure.NOT_PERMITTED)
            }
        if (!started) {
            // Ended meanwhile (a failure answered inside the call, or a stop): report what ended it.
            synchronized(lock) { endError }?.let { throw it }
            end(LinkFailure.GATT_ERROR, null)
            throw LinkException(LinkFailure.GATT_ERROR)
        }
        try {
            connected.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }
    }

    override suspend fun discoverServices(): List<GattService> =
        operation(Kind.DISCOVER, { port.discoverServices() }) {
            val found = port.services()
            synchronized(lock) {
                // A drop between the answer and here would leave an empty list: never take that for the bike's.
                ended?.let { throw LinkException(it) }
                services = found
            }
            found
        }

    override suspend fun requestMtu(mtu: Int): Int {
        require(mtu in BikeGatt.DEFAULT_MTU..BikeGatt.MAX_MTU) { "MTU out of range" }
        return operation(Kind.MTU, { port.requestMtu(mtu) }) { it.value }
    }

    override suspend fun enableNotifications() {
        val notify = characteristic(BikeGatt.NOTIFY, CharacteristicProperty.NOTIFY)
        if (BikeGatt.CLIENT_CONFIG !in notify.descriptors) throw LinkException(LinkFailure.UNSUPPORTED)
        operation(Kind.DESCRIPTOR, {
            port.setNotification(BikeGatt.SERVICE, BikeGatt.NOTIFY) &&
                port.writeDescriptor(BikeGatt.SERVICE, BikeGatt.NOTIFY, BikeGatt.CLIENT_CONFIG, ENABLE_NOTIFICATIONS)
        }) { }
    }

    override suspend fun write(frame: OutgoingFrame) {
        characteristic(BikeGatt.WRITE, CharacteristicProperty.WRITE)
        val bytes = frame.bytes
        operation(Kind.WRITE, { port.writeCharacteristic(BikeGatt.SERVICE, BikeGatt.WRITE, bytes) }) { }
    }

    override suspend fun readRssi(): Int = operation(Kind.RSSI, { port.readRemoteRssi() }) { it.value }

    override fun close() {
        end(LinkFailure.CLOSED, null)
    }

    /** The bike service's [uuid] characteristic with [property], or [LinkFailure.UNSUPPORTED]. */
    private fun characteristic(
        uuid: UUID,
        property: CharacteristicProperty,
    ): GattCharacteristic {
        val found =
            synchronized(lock) {
                ended?.let { throw LinkException(it) }
                services
            }?.firstOrNull { it.uuid == BikeGatt.SERVICE }?.characteristic(uuid)
        if (found == null || property !in found.properties) throw LinkException(LinkFailure.UNSUPPORTED)
        return found
    }

    private suspend fun <T> operation(
        kind: Kind,
        start: () -> Boolean,
        finish: (Result) -> T,
    ): T =
        operations.withLock {
            val result = CompletableDeferred<Result>()
            synchronized(lock) {
                ended?.let { throw LinkException(it) }
                pending = Pending(kind, result)
            }
            val answer =
                try {
                    val started =
                        try {
                            start()
                        } catch (_: SecurityException) {
                            throw LinkException(LinkFailure.NOT_PERMITTED)
                        }
                    // A call refused because the link just ended is a lost link, not a refusal by the bike.
                    if (!started) throw LinkException(synchronized(lock) { ended } ?: LinkFailure.GATT_ERROR)
                    withTimeoutOrNull(operationTimeout) { result.await() }
                } catch (e: CancellationException) {
                    // The stack may still be busy with it, so this link can't be trusted with another.
                    if (!result.isCompleted) end(LinkFailure.CLOSED, null)
                    throw e
                } finally {
                    synchronized(lock) { if (pending?.result === result) pending = null }
                }
            if (answer == null) {
                end(LinkFailure.TIMEOUT, null)
                throw LinkException(LinkFailure.TIMEOUT)
            }
            if (answer.status != GATT_SUCCESS) throw LinkException(LinkFailure.GATT_ERROR, answer.status)
            finish(answer)
        }

    /** Ends the link once: emits the disconnect, fails what is pending, releases the connection. */
    private fun end(
        failure: LinkFailure,
        status: Int?,
        disconnectFirst: Boolean = false,
    ) {
        val waiting: Pending?
        synchronized(lock) {
            if (ended != null) return
            ended = failure
            endError = LinkException(failure, status)
            // Under the lock, so that no notification can follow the disconnect.
            channel.trySend(LinkEvent.Disconnected(failure, status))
            channel.close()
            waiting = pending
            pending = null
        }
        val error = LinkException(failure, status)
        waiting?.result?.completeExceptionally(error)
        connected.completeExceptionally(error)
        try {
            if (failure != LinkFailure.DISCONNECTED || disconnectFirst) port.disconnect()
        } finally {
            port.close()
        }
    }

    private fun complete(
        kind: Kind,
        status: Int,
        value: Int = 0,
    ) {
        val p = synchronized(lock) { pending?.takeIf { it.kind == kind } }
        p?.result?.complete(Result(status, value))
    }

    private val callbacks =
        object : GattCallbacks {
            override fun onConnectionStateChange(
                status: Int,
                connected: Boolean,
            ) {
                if (connected && status == GATT_SUCCESS) {
                    // After a close, the deferred has already failed and the port is released.
                    this@GattLink.connected.complete(Unit)
                } else {
                    val wasConnected = this@GattLink.connected.isCompleted
                    end(if (wasConnected) LinkFailure.DISCONNECTED else LinkFailure.GATT_ERROR, status)
                }
            }

            override fun onServicesDiscovered(status: Int) = complete(Kind.DISCOVER, status)

            override fun onMtuChanged(
                mtu: Int,
                status: Int,
            ) = complete(Kind.MTU, status, mtu)

            override fun onDescriptorWrite(status: Int) = complete(Kind.DESCRIPTOR, status)

            override fun onCharacteristicWrite(status: Int) = complete(Kind.WRITE, status)

            override fun onReadRemoteRssi(
                rssi: Int,
                status: Int,
            ) = complete(Kind.RSSI, status, rssi)

            // The bike's services changed: what was discovered no longer holds, so a fresh connection must start over.
            override fun onServiceChanged() = end(LinkFailure.DISCONNECTED, null, disconnectFirst = true)

            override fun onCharacteristicChanged(
                service: UUID,
                characteristic: UUID,
                value: ByteArray,
            ) {
                if (service != BikeGatt.SERVICE || characteristic != BikeGatt.NOTIFY) return
                synchronized(lock) {
                    if (ended == null) channel.trySend(LinkEvent.Notification(value))
                }
            }
        }

    private companion object {
        const val GATT_SUCCESS = 0
        val ENABLE_NOTIFICATIONS = byteArrayOf(0x01, 0x00)
    }
}
