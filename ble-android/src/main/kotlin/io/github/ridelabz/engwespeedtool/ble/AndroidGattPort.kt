package io.github.ridelabz.engwespeedtool.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import io.github.ridelabz.engwespeedtool.session.CharacteristicProperty
import io.github.ridelabz.engwespeedtool.session.GattCharacteristic
import io.github.ridelabz.engwespeedtool.session.GattService
import java.util.UUID
import java.util.concurrent.Executor

/**
 * [GattPort] on Android's `BluetoothGatt`: Bluetooth LE transport, no bonding, and characteristic writes
 * only as Write Requests. Characteristics are found within their service, so a characteristic with the
 * same UUID in another service is never used. The caller checks the Bluetooth permission; without it the
 * platform throws [SecurityException], which [GattLink] reports as not permitted.
 *
 * Android itself repeats a write with encryption, which starts pairing, when a bike answers "insufficient
 * authentication or encryption"; no public API turns that off. The reference bike doesn't ask for it.
 */
@SuppressLint("MissingPermission")
internal class AndroidGattPort(
    private val context: Context,
    private val device: BluetoothDevice,
) : GattPort {
    @Volatile
    internal var gatt: BluetoothGatt? = null
        private set

    @Volatile
    private var callbacks: GattCallbacks? = null
    private val lock = Any()
    private var closed = false

    override fun connect(callbacks: GattCallbacks): Boolean {
        this.callbacks = callbacks
        val opened =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                val settings =
                    BluetoothGattConnectionSettings
                        .Builder()
                        .setTransport(BluetoothDevice.TRANSPORT_LE)
                        .setAutoConnectEnabled(false)
                        // The link asks for the MTU itself and waits for the answer, as before Android 17.
                        .setAutomaticMtuEnabled(false)
                        .build()
                // Callbacks run on the stack's thread, as before Android 17; GattLink only takes a lock and queues.
                device.connectGatt(settings, Executor { it.run() }, gattCallback)
            } else {
                connectBefore37()
            }
        synchronized(lock) {
            // Closed while connecting (a failure answered before the call returned): release this one too.
            if (closed) {
                opened?.close()
                return false
            }
            gatt = opened
        }
        return opened != null
    }

    @Suppress("DEPRECATION")
    private fun connectBefore37(): BluetoothGatt? =
        device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

    override fun discoverServices(): Boolean = gatt?.discoverServices() ?: false

    override fun services(): List<GattService> =
        gatt?.services.orEmpty().map { service ->
            GattService(service.uuid, service.characteristics.map { it.model() })
        }

    override fun requestMtu(mtu: Int): Boolean = gatt?.requestMtu(mtu) ?: false

    override fun setNotification(
        service: UUID,
        characteristic: UUID,
    ): Boolean {
        val c = characteristic(service, characteristic) ?: return false
        return gatt?.setCharacteristicNotification(c, true) ?: false
    }

    override fun writeDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        value: ByteArray,
    ): Boolean {
        val g = gatt ?: return false
        val d = characteristic(service, characteristic)?.getDescriptor(descriptor) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            writeDescriptorBefore33(g, d, value)
        }
    }

    override fun writeCharacteristic(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    ): Boolean {
        val g = gatt ?: return false
        val c = characteristic(service, characteristic) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            writeCharacteristicBefore33(g, c, value)
        }
    }

    override fun readRemoteRssi(): Boolean = gatt?.readRemoteRssi() ?: false

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun close() {
        val g =
            synchronized(lock) {
                closed = true
                gatt.also { gatt = null }
            }
        callbacks = null
        g?.close()
    }

    private fun characteristic(
        service: UUID,
        characteristic: UUID,
    ): BluetoothGattCharacteristic? = gatt?.getService(service)?.getCharacteristic(characteristic)

    @Suppress("DEPRECATION")
    private fun writeCharacteristicBefore33(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = value
        return g.writeCharacteristic(c)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorBefore33(
        g: BluetoothGatt,
        d: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        d.value = value
        return g.writeDescriptor(d)
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                callbacks?.onConnectionStateChange(status, newState == BluetoothProfile.STATE_CONNECTED)
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                callbacks?.onServicesDiscovered(status)
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                callbacks?.onMtuChanged(mtu, status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                callbacks?.onDescriptorWrite(status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                callbacks?.onCharacteristicWrite(status)
            }

            // Android 13 and later.
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                callbacks?.onCharacteristicChanged(characteristic.service.uuid, characteristic.uuid, value.copyOf())
            }

            // Android 12: the value is read from the characteristic, at once.
            @Deprecated("Called before Android 13 only")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                val value = characteristic.value?.copyOf() ?: return
                callbacks?.onCharacteristicChanged(characteristic.service.uuid, characteristic.uuid, value)
            }

            override fun onReadRemoteRssi(
                gatt: BluetoothGatt,
                rssi: Int,
                status: Int,
            ) {
                callbacks?.onReadRemoteRssi(rssi, status)
            }

            override fun onServiceChanged(gatt: BluetoothGatt) {
                callbacks?.onServiceChanged()
            }
        }

    private companion object {
        private val PROPERTIES =
            listOf(
                BluetoothGattCharacteristic.PROPERTY_BROADCAST to CharacteristicProperty.BROADCAST,
                BluetoothGattCharacteristic.PROPERTY_READ to CharacteristicProperty.READ,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to CharacteristicProperty.WRITE_WITHOUT_RESPONSE,
                BluetoothGattCharacteristic.PROPERTY_WRITE to CharacteristicProperty.WRITE,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY to CharacteristicProperty.NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_INDICATE to CharacteristicProperty.INDICATE,
                BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE to CharacteristicProperty.SIGNED_WRITE,
                BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS to CharacteristicProperty.EXTENDED_PROPERTIES,
            )

        fun BluetoothGattCharacteristic.model(): GattCharacteristic =
            GattCharacteristic(
                uuid,
                PROPERTIES.filter { (bit, _) -> properties and bit != 0 }.map { it.second }.toSet(),
                descriptors.map { it.uuid },
            )
    }
}
