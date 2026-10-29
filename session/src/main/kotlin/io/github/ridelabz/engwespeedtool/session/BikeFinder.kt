package io.github.ridelabz.engwespeedtool.session

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bikes nearby, for the find screen. It only scans; connecting is the session's job. Demo mode builds one
 * on a [FakeBikeTransport]; the Android one comes from the Bluetooth module.
 */
public class BikeFinder(
    private val transport: BikeTransport,
    /** A bike not heard for this long drops out of the list. The bike advertises slowly. */
    private val forgetAfter: Duration = 30.seconds,
) {
    public val radio: StateFlow<RadioState> get() = transport.radio

    /**
     * Scans while collected and emits the bikes heard so far, each with its latest advertisement: an
     * empty list first, then stronger signals first in steps of 10 dB, and within a step in the order the
     * bikes were first heard, so that the list doesn't jump while someone reaches for it. Bikes paired
     * with this phone come first, right away, before the scan has heard anything: as [Advertisement.paired]
     * while they aren't heard (the phone holds a link to them, so they usually don't advertise), with their
     * latest advertisement once they are. Either way a paired bike keeps its place at the top. Fails with
     * [LinkException] when scanning fails, for example with Bluetooth off.
     */
    public fun bikes(): Flow<List<Advertisement>> =
        channelFlow {
            val mutex = Mutex()
            val heard = LinkedHashMap<BikeAddress, Advertisement>() // first heard first
            val expiry = HashMap<BikeAddress, Job>()
            var paired = emptySet<BikeAddress>()

            // Changes and sends happen together, so a list never overtakes a newer one.
            suspend fun change(block: () -> Unit) =
                mutex.withLock {
                    block()
                    // A paired bike that starts or stops being heard stays where it is.
                    val first = paired.map { heard[it] ?: Advertisement.paired(it) }
                    val others =
                        heard.values
                            .filter { it.address !in paired }
                            .sortedByDescending { Math.floorDiv(it.rssi ?: Int.MIN_VALUE, SIGNAL_STEP) }
                    send(first + others)
                }

            send(emptyList())
            // Paired bikes need no scan: they are listed even while a scan waits for its slot.
            change { paired = transport.pairedBikes() }
            transport.scanSlot()
            transport.scan().collect { ad ->
                if (!ad.heard) return@collect // a transport reports only what it heard
                val forget =
                    launch(start = CoroutineStart.LAZY) {
                        delay(forgetAfter)
                        val me = currentCoroutineContext().job
                        change {
                            if (expiry[ad.address] === me) {
                                heard.remove(ad.address)
                                expiry.remove(ad.address)
                            }
                        }
                    }
                change {
                    heard[ad.address] = ad
                    expiry.put(ad.address, forget)?.cancel()
                }
                forget.start()
            }
        }

    private companion object {
        private const val SIGNAL_STEP = 10
    }
}
