package io.github.ridelabz.engwespeedtool.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.reporting.SessionLog
import io.github.ridelabz.engwespeedtool.session.Advertisement
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.BikeFinder
import io.github.ridelabz.engwespeedtool.session.BikeSession
import io.github.ridelabz.engwespeedtool.session.Consent
import io.github.ridelabz.engwespeedtool.session.LinkException
import io.github.ridelabz.engwespeedtool.session.LinkFailure
import io.github.ridelabz.engwespeedtool.session.RadioState
import io.github.ridelabz.engwespeedtool.session.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Bluetooth for the app: the phone's Bluetooth state, a finder for the find screen, and sessions with a
 * bike. It never hands out a link or a transport, so app code can reach a bike only through a session.
 * There is one per app process ([get]): Android limits scan starts per app, and one limit must count them.
 */
public class AndroidBluetooth private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val radioSource = AndroidRadio(app)
    private val limiter = ScanStartLimiter({ SystemClock.elapsedRealtime() })
    private val scanner = AndroidScanner(app, radioSource.state)
    private val transport =
        GattTransport(
            radioSource.state,
            { address -> scanner.scan(address) },
            { address -> AndroidGattPort(app, adapter(app).getRemoteDevice(address.value)) },
            { limiter.acquire() },
            { pairedBikes(app, radioSource.state.value) },
        )

    /** Whether Bluetooth can be used: on, off, not permitted, or missing. */
    public val radio: StateFlow<RadioState> get() = radioSource.state

    /** Reads the Bluetooth state and permissions again, for example after the rider answered the system prompt. */
    public fun refresh() {
        radioSource.refresh()
    }

    public fun finder(): BikeFinder = BikeFinder(transport)

    /** Asks Android to switch Bluetooth on; it shows its own prompt. Needs the Bluetooth permission. */
    public fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** A session on this phone's Bluetooth. */
    public fun session(
        scope: CoroutineScope,
        profiles: ProfileRegistry,
        log: SessionLog,
        consent: Consent,
        config: SessionConfig = SessionConfig(),
    ): BikeSession = BikeSession(scope, transport, profiles, log, consent, config)

    public companion object {
        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AndroidBluetooth? = null

        /** The app's one instance. */
        public fun get(context: Context): AndroidBluetooth =
            instance ?: synchronized(this) { instance ?: AndroidBluetooth(context).also { instance = it } }

        private fun adapter(context: Context): BluetoothAdapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw LinkException(LinkFailure.UNSUPPORTED)

        /** Bikes named `ENGWE…` paired with this phone; empty while Bluetooth is off or not permitted. Nothing is logged. */
        @SuppressLint("MissingPermission")
        private fun pairedBikes(
            context: Context,
            radio: RadioState,
        ): Set<BikeAddress> {
            if (radio != RadioState.ON) return emptySet()
            return try {
                val devices =
                    context
                        .getSystemService(BluetoothManager::class.java)
                        ?.adapter
                        ?.bondedDevices
                        .orEmpty()
                devices.mapNotNullTo(mutableSetOf()) { ScanResults.pairedBike(it.name, it.address, it.type) }
            } catch (_: SecurityException) {
                emptySet() // the permission went away since the radio was read
            }
        }
    }
}

/** The phone's Bluetooth state, updated when the adapter turns on or off and on [refresh]. */
internal class AndroidRadio(
    private val context: Context,
) {
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<RadioState> = mutable.asStateFlow()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                refresh()
            }
        }

    init {
        // A protected system broadcast: only the system can send it. Not exported where Android can say so.
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /** From the receiver (main thread) and the app (any thread): one at a time, so the last read wins. */
    fun refresh() {
        synchronized(this) { mutable.value = read() }
    }

    private fun read(): RadioState {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return RadioState.UNAVAILABLE
        }
        val granted =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        if (!granted) return RadioState.NOT_PERMITTED
        return if (adapter.isEnabled) RadioState.ON else RadioState.OFF
    }
}

/**
 * Scans for bikes named `ENGWE…`. Callers take a scan slot from the transport first; Bluetooth switched
 * off ends a scan. Nothing is logged here: the advertised name, the address and the advertisement's data never
 * leave this class except as an [Advertisement], which keeps only the address (for connecting) and the signal
 * strength.
 */
@SuppressLint("MissingPermission")
internal class AndroidScanner(
    private val context: Context,
    private val radio: StateFlow<RadioState>,
) {
    fun scan(address: BikeAddress?): Flow<Advertisement> =
        callbackFlow {
            GattTransport.radioFailure(radio.value)?.let { throw LinkException(it) }
            val scanner =
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                    ?: throw LinkException(LinkFailure.BLUETOOTH_OFF)
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        advertisement(result)?.let { trySend(it) }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        for (result in results) advertisement(result)?.let { trySend(it) }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        close(LinkException(LinkFailure.GATT_ERROR, errorCode))
                    }
                }
            val filters = address?.let { listOf(ScanFilter.Builder().setDeviceAddress(it.value).build()) }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                scanner.startScan(filters, settings, callback)
            } catch (_: SecurityException) {
                throw LinkException(LinkFailure.NOT_PERMITTED)
            } catch (_: IllegalStateException) {
                // Bluetooth went off since the check above.
                throw LinkException(LinkFailure.BLUETOOTH_OFF)
            }
            val watch =
                launch {
                    val off = radio.first { it != RadioState.ON }
                    close(LinkException(GattTransport.radioFailure(off) ?: LinkFailure.BLUETOOTH_OFF))
                }
            awaitClose {
                watch.cancel()
                try {
                    scanner.stopScan(callback)
                } catch (_: RuntimeException) {
                    // Bluetooth already off or the permission gone: the scan has stopped anyway.
                }
            }
        }

    private fun advertisement(result: ScanResult): Advertisement? {
        val record = result.scanRecord ?: return null
        return ScanResults.advertisement(record.deviceName, result.device.address, result.rssi)
    }
}
