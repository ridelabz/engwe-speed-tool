package io.github.ridelabz.engwespeedtool.ble

import io.github.ridelabz.engwespeedtool.session.Advertisement
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android stops an app's scans when it starts more than 5 in 30 s, without telling it. Every scan of this
 * module asks here first; [clock] is in milliseconds (elapsed real time).
 */
internal class ScanStartLimiter(
    private val clock: () -> Long,
    private val maxStarts: Int = 4,
    private val periodMillis: Long = 30_000,
) {
    private val mutex = Mutex()
    private val starts = ArrayDeque<Long>()

    /** Waits until a scan may start, and counts it. */
    suspend fun acquire() {
        mutex.withLock {
            while (true) {
                val now = clock()
                while (starts.isNotEmpty() && now - starts.first() >= periodMillis) starts.removeFirst()
                if (starts.size < maxStarts) {
                    starts.addLast(now)
                    return
                }
                delay(periodMillis - (now - starts.first()))
            }
        }
    }
}

/** What one scan result says, as plain values, so that turning it into an [Advertisement] can be tested. */
internal object ScanResults {
    const val NAME_PREFIX = "ENGWE"

    /**
     * The address of a paired device that is a bike named `ENGWE…` reachable over Bluetooth LE, or null
     * for anything else. [type] is Android's device type (1 classic, 2 LE, 3 dual, 0 unknown).
     */
    fun pairedBike(
        name: String?,
        address: String,
        type: Int,
    ): BikeAddress? {
        if (name == null || !name.startsWith(NAME_PREFIX) || type == DEVICE_TYPE_CLASSIC) return null
        return try {
            BikeAddress(address)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Android's `BluetoothDevice.DEVICE_TYPE_CLASSIC`. */
    private const val DEVICE_TYPE_CLASSIC = 1

    /**
     * An advertisement of a bike named `ENGWE…`, or null for anything else. Only the address (to connect)
     * and the signal strength are kept.
     */
    fun advertisement(
        name: String?,
        address: String,
        rssi: Int,
    ): Advertisement? {
        if (name == null || !name.startsWith(NAME_PREFIX)) return null
        val bike =
            try {
                BikeAddress(address)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return Advertisement(bike, rssi)
    }
}
