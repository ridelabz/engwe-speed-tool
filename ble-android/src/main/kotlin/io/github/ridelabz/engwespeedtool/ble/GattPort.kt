package io.github.ridelabz.engwespeedtool.ble

import io.github.ridelabz.engwespeedtool.session.GattService
import java.util.UUID

/**
 * The GATT calls a [GattLink] needs, one Bluetooth connection each. The Android implementation wraps
 * `BluetoothGatt`; tests simulate a bike. Calls return false when the stack refused to start them; their
 * results arrive later through [GattCallbacks], on any thread, possibly before the call returns.
 *
 * There is only one kind of characteristic write: an acknowledged write (Write Request).
 */
internal interface GattPort {
    fun connect(callbacks: GattCallbacks): Boolean

    fun discoverServices(): Boolean

    /** The services found by the last discovery. */
    fun services(): List<GattService>

    fun requestMtu(mtu: Int): Boolean

    /** Enables notifications of [characteristic] on the phone only (no write to the bike). */
    fun setNotification(
        service: UUID,
        characteristic: UUID,
    ): Boolean

    fun writeDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        value: ByteArray,
    ): Boolean

    /** Writes [value] as an acknowledged write (Write Request). */
    fun writeCharacteristic(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    ): Boolean

    fun readRemoteRssi(): Boolean

    fun disconnect()

    /** Releases the connection. No callbacks follow. */
    fun close()
}

/** Results of [GattPort] calls. Status 0 is success, as in Android's `BluetoothGatt.GATT_SUCCESS`. */
internal interface GattCallbacks {
    fun onConnectionStateChange(
        status: Int,
        connected: Boolean,
    )

    fun onServicesDiscovered(status: Int)

    fun onMtuChanged(
        mtu: Int,
        status: Int,
    )

    fun onDescriptorWrite(status: Int)

    fun onCharacteristicWrite(status: Int)

    fun onCharacteristicChanged(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    )

    fun onReadRemoteRssi(
        rssi: Int,
        status: Int,
    )

    fun onServiceChanged()
}
