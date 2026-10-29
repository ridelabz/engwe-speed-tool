package io.github.ridelabz.engwespeedtool.ble

import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.BikeGatt
import io.github.ridelabz.engwespeedtool.session.BikeTransport
import io.github.ridelabz.engwespeedtool.session.CharacteristicProperty
import io.github.ridelabz.engwespeedtool.session.GattCharacteristic
import io.github.ridelabz.engwespeedtool.session.GattService
import io.github.ridelabz.engwespeedtool.session.RadioState
import io.github.ridelabz.engwespeedtool.session.contract.LinkHarness
import io.github.ridelabz.engwespeedtool.session.contract.ReceivedWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A simulated bike behind [FakeGattPort]s, answering like the Android stack does, on the test's clock. */
internal class FakeGattBike(
    val scope: CoroutineScope,
    val services: List<GattService>,
    val present: Boolean = true,
) {
    val received = mutableListOf<ReceivedWrite>()
    val ports = mutableListOf<FakeGattPort>()
    var rejectNextWrite: Int? = null
    var withholdNextWrite = false
    var inFlight = 0
    var maxInFlight = 0

    /** Answer delay of the simulated stack. */
    var latency: Duration = 5.milliseconds

    /** Answers inside the call, before it returns, as some stacks (and Robolectric) do. */
    var synchronous = false

    /** The connection drops right after the next answer, before the caller has seen it. */
    var dropAfterNextAnswer = false

    /** The next write finds the connection already dropped, and the stack refuses to start it. */
    var dropAtNextWrite = false

    /** The phone refuses to register for notifications. */
    var refusesNotification = false

    fun newPort(): FakeGattPort = FakeGattPort(this).also { ports += it }

    val current: FakeGattPort? get() = ports.lastOrNull { !it.closed }

    companion object {
        fun services(
            write: Set<CharacteristicProperty> =
                setOf(CharacteristicProperty.WRITE, CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
            decoy: Boolean = false,
        ): List<GattService> {
            val bike =
                GattService(
                    BikeGatt.SERVICE,
                    listOf(
                        GattCharacteristic(BikeGatt.WRITE, write, emptyList()),
                        GattCharacteristic(
                            BikeGatt.NOTIFY,
                            setOf(CharacteristicProperty.NOTIFY),
                            listOf(BikeGatt.CLIENT_CONFIG),
                        ),
                    ),
                )
            val other =
                GattService(
                    BikeGatt.standard(0xFFF0),
                    listOf(
                        GattCharacteristic(BikeGatt.WRITE, setOf(CharacteristicProperty.WRITE), emptyList()),
                        GattCharacteristic(
                            BikeGatt.NOTIFY,
                            setOf(CharacteristicProperty.NOTIFY),
                            listOf(BikeGatt.CLIENT_CONFIG),
                        ),
                    ),
                )
            return if (decoy) listOf(other, bike) else listOf(bike)
        }
    }
}

/** One connection to a [FakeGattBike]. */
internal class FakeGattPort(
    private val bike: FakeGattBike,
) : GattPort {
    var callbacks: GattCallbacks? = null
    var connected = false
    var closed = false
    var disconnects = 0
    var notifying = false

    /** When false, calls fail to start, as when the stack is busy. */
    var accepts = true

    /** Calls throw [SecurityException], as without the Bluetooth permission. */
    var throwsSecurity = false

    private fun later(
        after: Duration = bike.latency,
        block: GattCallbacks.() -> Unit,
    ) {
        bike.scope.launch {
            delay(after)
            val cb = callbacks
            if (!closed && cb != null) cb.block()
        }
    }

    private fun begin(): Boolean {
        if (throwsSecurity) throw SecurityException("no permission")
        if (!accepts || closed) return false
        bike.inFlight++
        bike.maxInFlight = maxOf(bike.maxInFlight, bike.inFlight)
        return true
    }

    private fun answer(block: GattCallbacks.() -> Unit) {
        if (bike.synchronous) {
            bike.inFlight--
            callbacks?.block()
            return
        }
        later {
            bike.inFlight--
            block()
            if (bike.dropAfterNextAnswer) {
                bike.dropAfterNextAnswer = false
                drop()
            }
        }
    }

    override fun connect(callbacks: GattCallbacks): Boolean {
        this.callbacks = callbacks
        if (bike.present) {
            later {
                connected = true
                onConnectionStateChange(0, true)
            }
        } else {
            later(30.seconds) { onConnectionStateChange(133, false) }
        }
        return true
    }

    override fun discoverServices(): Boolean = begin().also { if (it) answer { onServicesDiscovered(0) } }

    override fun services(): List<GattService> = bike.services

    override fun requestMtu(mtu: Int): Boolean = begin().also { if (it) answer { onMtuChanged(minOf(mtu, 512), 0) } }

    override fun setNotification(
        service: UUID,
        characteristic: UUID,
    ): Boolean {
        if (throwsSecurity) throw SecurityException("no permission")
        if (bike.refusesNotification) return false
        notifying = true
        return true
    }

    override fun writeDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        value: ByteArray,
    ): Boolean {
        if (!begin()) return false
        bike.received += ReceivedWrite(service, characteristic, descriptor, value, acknowledged = true)
        answer { onDescriptorWrite(0) }
        return true
    }

    override fun writeCharacteristic(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    ): Boolean {
        if (bike.dropAtNextWrite) {
            bike.dropAtNextWrite = false
            drop()
            return false
        }
        if (!begin()) return false
        bike.received += ReceivedWrite(service, characteristic, null, value, acknowledged = true)
        val reject = bike.rejectNextWrite
        when {
            reject != null -> {
                bike.rejectNextWrite = null
                answer { onCharacteristicWrite(reject) }
            }

            bike.withholdNextWrite -> {
                bike.withholdNextWrite = false
            }

            else -> {
                answer { onCharacteristicWrite(0) }
            }
        }
        return true
    }

    override fun readRemoteRssi(): Boolean = begin().also { if (it) answer { onReadRemoteRssi(-60, 0) } }

    override fun disconnect() {
        disconnects++
        if (connected) later { onConnectionStateChange(0, false) }
        connected = false
    }

    override fun close() {
        closed = true
        callbacks = null
    }

    /** The bike sends a notification of its notify characteristic. */
    fun notify(value: ByteArray) {
        if (notifying && !closed) callbacks?.onCharacteristicChanged(BikeGatt.SERVICE, BikeGatt.NOTIFY, value)
    }

    /** The connection drops, as when the phone goes out of range. */
    fun drop() {
        connected = false
        callbacks?.onConnectionStateChange(8, false)
    }
}

/** The contract's view of fake GATT bikes behind a [GattTransport]. */
internal class FakeGattHarness(
    scope: CoroutineScope,
) : LinkHarness {
    private val reference = FakeGattBike(scope, FakeGattBike.services(decoy = true))
    private val withoutAck =
        FakeGattBike(scope, FakeGattBike.services(write = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE)))
    private val missing = FakeGattBike(scope, emptyList(), present = false)
    private val bikes =
        mapOf(
            BikeAddress("F0:00:00:00:00:01") to reference,
            BikeAddress("F0:00:00:00:00:02") to withoutAck,
        )

    override val transport: BikeTransport =
        GattTransport(MutableStateFlow(RadioState.ON), {
            emptyFlow()
        }, { (bikes[it] ?: missing).newPort() }, {}, { emptySet() })
    override val bike: BikeAddress = BikeAddress("F0:00:00:00:00:01")
    override val bikeWithoutAcknowledgedWrite: BikeAddress = BikeAddress("F0:00:00:00:00:02")
    override val absent: BikeAddress = BikeAddress("F0:00:00:00:00:FF")

    override suspend fun notify(value: ByteArray) {
        reference.current?.notify(value)
    }

    override suspend fun drop() {
        reference.current?.drop()
    }

    override fun rejectNextWrite(status: Int) {
        reference.rejectNextWrite = status
    }

    override fun withholdNextWriteResponse() {
        reference.withholdNextWrite = true
    }

    override fun received(): List<ReceivedWrite> = (bikes.values + missing).flatMap { it.received }

    override fun openLinks(): Int = (bikes.values + missing).sumOf { b -> b.ports.count { !it.closed } }

    override fun maxConcurrentOperations(): Int = (bikes.values + missing).maxOf { it.maxInFlight }
}
