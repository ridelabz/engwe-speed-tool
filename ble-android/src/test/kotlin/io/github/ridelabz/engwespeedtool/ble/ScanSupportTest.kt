@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSupportTest {
    private val address = "F0:12:34:56:78:9A" // made up

    @Test
    fun `an ENGWE bike's advertisement keeps its address and signal only`() {
        val ad = ScanResults.advertisement("ENGWE-7C", address, -70)!!
        assertEquals(address, ad.address.value)
        assertEquals(-70, ad.rssi)
        assertTrue(ad.heard)
        assertEquals("Advertisement(rssi=-70)", ad.toString())
    }

    @Test
    fun `other devices, and names that aren't advertised, are ignored`() {
        assertNull(ScanResults.advertisement("Headphones", address, -40))
        assertNull(ScanResults.advertisement(null, address, -40))
        assertNull(ScanResults.advertisement("engwe-7c", address, -40))
        assertNull(ScanResults.advertisement("ENGWE-7C", "not an address", -40))
    }

    @Test
    fun `paired devices count as bikes only when named ENGWE and reachable over LE`() {
        val le = 2
        val dual = 3
        val unknown = 0
        val classic = 1
        for (type in listOf(le, dual, unknown)) {
            assertEquals(address, ScanResults.pairedBike("ENGWE-7C", address, type)?.value)
        }
        // The bike's own GATT name, which Android may cache instead of the advertised one.
        assertEquals(address, ScanResults.pairedBike("ENGWE", address, le)?.value)
        assertNull(ScanResults.pairedBike("ENGWE-7C", address, classic))
        assertNull(ScanResults.pairedBike("Headphones", address, le))
        assertNull(ScanResults.pairedBike(null, address, le))
        assertNull(ScanResults.pairedBike("engwe-7c", address, le))
        assertNull(ScanResults.pairedBike("ENGWE-7C", "not an address", le))
    }

    @Test
    fun `scan starts are spread so that no more than 4 fall in 30 seconds`() =
        runTest {
            val limiter = ScanStartLimiter({ testScheduler.currentTime })
            val times = mutableListOf<Long>()
            val job =
                launch {
                    repeat(10) {
                        limiter.acquire()
                        times += testScheduler.currentTime
                    }
                }
            job.join()
            assertEquals(listOf(0L, 0, 0, 0, 30_000, 30_000, 30_000, 30_000, 60_000, 60_000), times)
        }

    @Test
    fun `waiters take turns, and one that gives up uses no start`() =
        runTest {
            val limiter = ScanStartLimiter({ testScheduler.currentTime })
            repeat(4) { limiter.acquire() }
            val times = mutableListOf<Pair<String, Long>>()
            val quitter = launch { limiter.acquire().also { times += "quitter" to testScheduler.currentTime } }
            val a = launch { limiter.acquire().also { times += "a" to testScheduler.currentTime } }
            delay(10_000)
            quitter.cancel()
            val b = launch { limiter.acquire().also { times += "b" to testScheduler.currentTime } }
            a.join()
            b.join()
            assertEquals(listOf("a" to 30_000L, "b" to 30_000L), times)
        }
}
