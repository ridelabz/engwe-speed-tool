package io.github.ridelabz.engwespeedtool.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.ridelabz.engwespeedtool.session.RadioState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** On Android 12 the adapter receiver is registered without the export flag, and still hears the system. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AndroidRadio31Test {
    @Test
    fun `the radio state follows the adapter on Android 12`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val radio = AndroidRadio(app)
        assertEquals(RadioState.ON, radio.state.value)
        shadowOf(adapter).setEnabled(false)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RadioState.OFF, radio.state.value)
    }
}
