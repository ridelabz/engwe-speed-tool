package io.github.ridelabz.engwespeedtool.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.ridelabz.engwespeedtool.session.Advertisement
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.LinkException
import io.github.ridelabz.engwespeedtool.session.LinkFailure
import io.github.ridelabz.engwespeedtool.session.RadioState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/** Bluetooth state and scanning against Robolectric's Bluetooth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AndroidBluetoothTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val adapter = app.getSystemService(BluetoothManager::class.java).adapter

    @Before
    fun phoneWithBluetoothLe() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
    }

    @Test
    fun `a phone without Bluetooth LE reports it as unavailable`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, false)
        grant()
        assertEquals(RadioState.UNAVAILABLE, AndroidRadio(app).state.value)
    }

    private fun grant() =
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    @Test
    fun `the radio state follows permissions and the adapter`() {
        shadowOf(adapter).setEnabled(true)
        val radio = AndroidRadio(app)
        assertEquals(RadioState.NOT_PERMITTED, radio.state.value)
        grant()
        radio.refresh()
        assertEquals(RadioState.ON, radio.state.value)
        shadowOf(adapter).setEnabled(false)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(RadioState.OFF, radio.state.value)
    }

    @Test
    fun `a scan reports ENGWE bikes with their signal only, and stops when collection ends`() {
        grant()
        shadowOf(adapter).setEnabled(true)
        val radio = AndroidRadio(app)
        val scanner = AndroidScanner(app, radio.state)
        val leScanner = shadowOf(adapter.bluetoothLeScanner)
        runBlocking {
            val seen = mutableListOf<Advertisement>()
            val job = launch { scanner.scan(null).toList(seen) }
            withTimeout(5.seconds) { while (leScanner.scanCallbacks.isEmpty()) kotlinx.coroutines.delay(10) }
            val device = adapter.getRemoteDevice("F0:12:34:56:78:9A")
            val callback = leScanner.scanCallbacks.single()
            callback.onScanResult(CALLBACK_ALL_MATCHES, result(device, "ENGWE-7C", "OMS1218"))
            callback.onScanResult(CALLBACK_ALL_MATCHES, result(device, "Headphones", null))
            withTimeout(5.seconds) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }
            job.cancel()
            job.join()
            assertEquals(1, seen.size)
            assertEquals("F0:12:34:56:78:9A", seen.single().address.value)
            assertEquals(-65, seen.single().rssi)
            assertEquals("the manufacturer data isn't kept", "Advertisement(rssi=-65)", seen.single().toString())
            assertTrue(leScanner.scanCallbacks.isEmpty())
        }
    }

    @Test
    fun `scanning without permission fails`() {
        shadowOf(adapter).setEnabled(true)
        val scanner = AndroidScanner(app, AndroidRadio(app).state)
        runBlocking {
            try {
                scanner.scan(null).first()
                fail("scanned without permission")
            } catch (e: LinkException) {
                assertEquals(LinkFailure.NOT_PERMITTED, e.failure)
            }
        }
    }

    @Test
    fun `Bluetooth switched off ends a scan and stops it`() {
        grant()
        shadowOf(adapter).setEnabled(true)
        val radio = AndroidRadio(app)
        val scanner = AndroidScanner(app, radio.state)
        val leScanner = shadowOf(adapter.bluetoothLeScanner)
        runBlocking {
            val outcome = async { runCatching { scanner.scan(null).toList() } }
            withTimeout(5.seconds) { while (leScanner.scanCallbacks.isEmpty()) kotlinx.coroutines.delay(10) }
            shadowOf(adapter).setEnabled(false)
            radio.refresh()
            val e = withTimeout(5.seconds) { outcome.await() }.exceptionOrNull() as LinkException
            assertEquals(LinkFailure.BLUETOOTH_OFF, e.failure)
            assertTrue(leScanner.scanCallbacks.isEmpty())
        }
    }

    @Test
    fun `a failed scan reports the stack's code and stops`() {
        grant()
        shadowOf(adapter).setEnabled(true)
        val scanner = AndroidScanner(app, AndroidRadio(app).state)
        val leScanner = shadowOf(adapter.bluetoothLeScanner)
        runBlocking {
            val outcome = async { runCatching { scanner.scan(null).toList() } }
            withTimeout(5.seconds) { while (leScanner.scanCallbacks.isEmpty()) kotlinx.coroutines.delay(10) }
            leScanner.scanCallbacks.single().onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
            val e = withTimeout(5.seconds) { outcome.await() }.exceptionOrNull() as LinkException
            assertEquals(LinkFailure.GATT_ERROR, e.failure)
            assertEquals(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, e.status)
            assertTrue(leScanner.scanCallbacks.isEmpty())
        }
    }

    @Test
    fun `a scan for the saved bike is filtered to its address`() {
        grant()
        shadowOf(adapter).setEnabled(true)
        val scanner = AndroidScanner(app, AndroidRadio(app).state)
        val leScanner = shadowOf(adapter.bluetoothLeScanner)
        runBlocking {
            val job = launch { scanner.scan(BikeAddress("F0:12:34:56:78:9A")).toList() }
            withTimeout(5.seconds) { while (leScanner.activeScans.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(
                "F0:12:34:56:78:9A",
                leScanner.activeScans
                    .single()
                    .scanFilters()
                    .single()
                    .deviceAddress,
            )
            job.cancel()
        }
    }

    /** A scan result with the given advertised name and, if [tag] is set, manufacturer data like the bike's. */
    private fun result(
        device: android.bluetooth.BluetoothDevice,
        name: String,
        tag: String?,
    ): ScanResult {
        val nameBytes = name.toByteArray()
        var bytes = byteArrayOf((nameBytes.size + 1).toByte(), 0x09) + nameBytes
        if (tag != null) {
            // Manufacturer data: the device's address bytes (the first two read as the company ID), then the tag.
            val data = byteArrayOf(0xF0.toByte(), 0x12, 0x34, 0x56, 0x78, 0x9A.toByte()) + tag.toByteArray() + 0
            bytes += byteArrayOf((data.size + 1).toByte(), 0xFF.toByte()) + data
        }
        // ScanRecord's parser is hidden; tests may reach it.
        val parse = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        val record = parse.invoke(null, bytes) as ScanRecord
        return ScanResult(device, 0, 1, 0, 255, 127, -65, 0, record, 0L)
    }

    private companion object {
        const val CALLBACK_ALL_MATCHES = 1
    }
}
