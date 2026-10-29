package io.github.ridelabz.engwespeedtool.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.session.BikeGatt
import io.github.ridelabz.engwespeedtool.session.CharacteristicProperty
import io.github.ridelabz.engwespeedtool.session.GattCharacteristic
import io.github.ridelabz.engwespeedtool.session.GattService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** The Android glue against Robolectric's Bluetooth: the platform calls and callbacks map as intended. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S, Build.VERSION_CODES.TIRAMISU])
class AndroidGattPortTest {
    private class Recording : GattCallbacks {
        val log = mutableListOf<String>()
        val values = mutableListOf<ByteArray>()

        override fun onConnectionStateChange(
            status: Int,
            connected: Boolean,
        ) {
            log += "connection $status $connected"
        }

        override fun onServicesDiscovered(status: Int) {
            log += "services $status"
        }

        override fun onMtuChanged(
            mtu: Int,
            status: Int,
        ) {
            log += "mtu $mtu $status"
        }

        override fun onDescriptorWrite(status: Int) {
            log += "descriptor $status"
        }

        override fun onCharacteristicWrite(status: Int) {
            log += "write $status"
        }

        override fun onCharacteristicChanged(
            service: UUID,
            characteristic: UUID,
            value: ByteArray,
        ) {
            log += "changed ${service == BikeGatt.SERVICE} ${characteristic == BikeGatt.NOTIFY}"
            values += value
        }

        override fun onReadRemoteRssi(
            rssi: Int,
            status: Int,
        ) {
            log += "rssi $status"
        }

        override fun onServiceChanged() {
            log += "service changed"
        }
    }

    @Test
    fun `connects, maps the services, writes with response and enables notifications`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("F0:00:00:00:00:01")
        val port = AndroidGattPort(app, device)
        val events = Recording()
        assertTrue(port.connect(events))
        val gatt = port.gatt!!
        val shadow = shadowOf(gatt)
        shadow.notifyConnection(device.address)
        assertEquals(listOf("connection 0 true"), events.log)

        val write =
            BluetoothGattCharacteristic(
                BikeGatt.WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        val notify =
            BluetoothGattCharacteristic(BikeGatt.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply {
                addDescriptor(BluetoothGattDescriptor(BikeGatt.CLIENT_CONFIG, BluetoothGattDescriptor.PERMISSION_WRITE))
            }
        val service =
            BluetoothGattService(BikeGatt.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(write)
                addCharacteristic(notify)
            }
        shadow.addDiscoverableService(service)
        assertTrue(port.discoverServices())
        assertEquals(
            listOf(
                GattService(
                    BikeGatt.SERVICE,
                    listOf(
                        GattCharacteristic(
                            BikeGatt.WRITE,
                            setOf(CharacteristicProperty.WRITE, CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                            emptyList(),
                        ),
                        GattCharacteristic(
                            BikeGatt.NOTIFY,
                            setOf(CharacteristicProperty.NOTIFY),
                            listOf(BikeGatt.CLIENT_CONFIG),
                        ),
                    ),
                ),
            ),
            port.services(),
        )

        val frame = BikeCommand.ReadIdentity.toFrame().bytes
        assertTrue(port.writeCharacteristic(BikeGatt.SERVICE, BikeGatt.WRITE, frame))
        assertArrayEquals(frame, shadow.latestWrittenBytes)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, write.writeType)
        }

        shadow.allowCharacteristicNotification(notify)
        assertTrue(port.setNotification(BikeGatt.SERVICE, BikeGatt.NOTIFY))
        assertTrue(port.writeDescriptor(BikeGatt.SERVICE, BikeGatt.NOTIFY, BikeGatt.CLIENT_CONFIG, byteArrayOf(1, 0)))
        assertTrue(port.requestMtu(BikeGatt.MAX_MTU))

        // A notification, through the callback this Android version calls.
        val value = byteArrayOf(0x45, 0x6E, 0x2A, 0x63)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shadow.gattCallback.onCharacteristicChanged(gatt, notify, value)
            // The older callback, should a stack call it too, must not deliver the value twice.
            @Suppress("DEPRECATION")
            notify.value = value
            @Suppress("DEPRECATION")
            shadow.gattCallback.onCharacteristicChanged(gatt, notify)
        } else {
            @Suppress("DEPRECATION")
            notify.value = value
            @Suppress("DEPRECATION")
            shadow.gattCallback.onCharacteristicChanged(gatt, notify)
        }
        assertArrayEquals(value, events.values.single())

        assertEquals(
            listOf(
                "connection 0 true",
                "services 0",
                "write 0",
                "descriptor 0",
                "mtu 517 0",
                "changed true true",
            ),
            events.log,
        )
        val callback = shadow.gattCallback
        port.close()
        assertTrue(shadow.isClosed)
        callback.onCharacteristicWrite(gatt, write, 0)
        callback.onConnectionStateChange(gatt, 0, 0)
        assertEquals("nothing reaches the link after close", 6, events.log.size)
    }

    @Test
    fun `notifications can't be enabled when the phone refuses to register them`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("F0:00:00:00:00:03")
        val port = AndroidGattPort(app, device)
        port.connect(Recording())
        val shadow = shadowOf(port.gatt!!)
        shadow.notifyConnection(device.address)
        val notify = BluetoothGattCharacteristic(BikeGatt.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        shadow.addDiscoverableService(
            BluetoothGattService(BikeGatt.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(notify)
            },
        )
        port.discoverServices()
        assertEquals(false, port.setNotification(BikeGatt.SERVICE, BikeGatt.NOTIFY))
    }

    @Test
    fun `a characteristic is only looked up within its service`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("F0:00:00:00:00:02")
        val port = AndroidGattPort(app, device)
        port.connect(Recording())
        val shadow = shadowOf(port.gatt!!)
        shadow.notifyConnection(device.address)
        val decoy =
            BluetoothGattService(BikeGatt.standard(0xFFF0), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(
                    BluetoothGattCharacteristic(
                        BikeGatt.WRITE,
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE,
                    ),
                )
            }
        shadow.addDiscoverableService(decoy)
        port.discoverServices()
        assertEquals(false, port.writeCharacteristic(BikeGatt.SERVICE, BikeGatt.WRITE, byteArrayOf(1)))
    }
}
