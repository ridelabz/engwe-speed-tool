@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.UnverifiedReason
import io.github.ridelabz.engwespeedtool.protocol.Verification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A bike paired with the phone: Android holds a link to it, so it isn't heard advertising. */
class PairedBikeTest {
    private suspend fun Rig.ready(): SessionState.Ready =
        withTimeout(2.minutes) {
            session.state.first { it is SessionState.Ready } as SessionState.Ready
        }

    /** Counts scan slots taken and scans started, so a test can tell that the session didn't search. */
    private class CountingTransport(
        inner: BikeTransport,
    ) : WrappedTransport(inner) {
        val slots = AtomicInteger()
        val scans = AtomicInteger()

        override suspend fun scanSlot() {
            slots.incrementAndGet()
            super.scanSlot()
        }

        override fun scan(address: BikeAddress?): Flow<Advertisement> {
            scans.incrementAndGet()
            return super.scan(address)
        }
    }

    /** The session-relative times of the log's `connecting` steps, in seconds. */
    private fun Rig.connectingTimes(): List<Double> =
        records()
            .filter { it.s("event") == "state" && it.s("name") == "check" && it.s("value") == "connecting" }
            .map { it.s("t").toDouble() }

    private fun gaps(times: List<Double>): List<Double> =
        times.zipWithNext { a, b ->
            Math.round((b - a) * 1000) /
                1000.0
        }

    @Test
    fun `a paired bike is connected to directly, at once, and verified by its identity`() =
        runTest {
            val bike =
                FakeBike(backgroundScope, BikeAddress("C3:5A:9E:71:2B:D4"), serial = "QZXKWJVPYBGHMNQZXKW")
                    .apply { pairedWithPhone = true }
            var counting: CountingTransport? = null
            val rig = Rig(this, bike, wrap = { CountingTransport(it).also { t -> counting = t } })
            rig.session.start(bike.address)
            val ready = rig.ready()
            assertEquals(Verification.VERIFIED, ready.classification.verification)
            assertEquals(listOf(BikeCommand.ReadIdentity), bike.commands, "still only the identity read")
            val transport = checkNotNull(counting)
            assertEquals(0, transport.slots.get(), "no scan slot for a paired bike")
            assertEquals(0, transport.scans.get(), "no scan for a paired bike")
            Secrets.assertAbsent(rig.out.toString(), Secrets.address(bike.address) + Secrets.serial(bike.serial))
            assertFalse("searching" in rig.steps())
            assertEquals(listOf("paired_direct"), rig.values("state", "search"))
            assertTrue(rig.records().none { it.s("event") == "scan_result" })
            assertTrue(
                testScheduler.currentTime < 3_000,
                "it connects at once: ${testScheduler.currentTime} ms",
            )
        }

    @Test
    fun `a paired bike of another model is unverified like any other`() =
        runTest {
            val bike = FakeBike(backgroundScope, model = "ENGWE_Other_model").apply { pairedWithPhone = true }
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            val ready = rig.ready()
            assertEquals(Verification.UNVERIFIED, ready.classification.verification)
            assertEquals(UnverifiedReason.UNKNOWN_MODEL, ready.classification.unverifiedReason)
        }

    @Test
    fun `a bike that isn't paired and isn't heard is still not found`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { advertises = false }
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            val state = withTimeout(2.minutes) { rig.session.state.first { it is SessionState.Retrying } }
            assertEquals(StopReason.NOT_FOUND, (state as SessionState.Retrying).reason)
            assertFalse(bike.connected)
            assertEquals(listOf("searching"), rig.steps().distinct().take(1))
        }

    @Test
    fun `after a drop, a paired bike is connected to directly again, with one identity read per connection`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { pairedWithPhone = true }
            val rig = Rig(this, bike)
            rig.session.start(bike.address)
            rig.ready()
            bike.dropLink()
            withTimeout(2.minutes) { rig.session.state.first { it is SessionState.Retrying } }
            rig.ready()
            assertEquals(listOf(1, 1), bike.writesPerLink)
            assertEquals(listOf("paired_direct", "paired_direct"), rig.values("state", "search"))
        }

    @Test
    fun `a paired bike from the find list is connected to directly`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { pairedWithPhone = true }
            val rig = Rig(this, bike)
            rig.session.start(Advertisement.paired(bike.address))
            val ready = rig.ready()
            assertEquals(Verification.VERIFIED, ready.classification.verification)
            assertEquals(listOf(BikeCommand.ReadIdentity), bike.commands)
            assertFalse("searching" in rig.steps())
        }

    @Test
    fun `a paired bike from the find list whose pairing was removed is searched for and heard`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            // Listed as paired; the rider then removed the pairing and checks it again.
            rig.session.start(Advertisement.paired(bike.address))
            val ready = rig.ready()
            assertEquals(Verification.VERIFIED, ready.classification.verification)
            assertTrue("searching" in rig.steps())
            assertEquals(1, rig.records().count { it.s("event") == "scan_result" })
            assertTrue(rig.values("state", "search").isEmpty())
        }

    @Test
    fun `without the rider's acceptance a paired bike gets no write either`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { pairedWithPhone = true }
            val rig = Rig(this, bike, accepted = false)
            rig.session.start(bike.address)
            val state = rig.settled()
            assertEquals(SessionState.Stopped(StopReason.NOT_ACCEPTED), state)
            assertTrue(bike.writes.isEmpty())
            assertTrue(rig.records().none { it.s("event") == "classification" }, "nothing classified")
        }

    @Test
    fun `a paired bike that is out of range is tried again with growing waits, not in a tight loop`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { pairedWithPhone = true }
            bike.inRange = false
            val config = SessionConfig()
            val rig = Rig(this, bike, config = config)
            rig.session.start(bike.address)
            val state = withTimeout(2.minutes) { rig.session.state.first { it is SessionState.Retrying } }
            assertEquals(StopReason.CONNECT_FAILED, (state as SessionState.Retrying).reason)
            delay(10.minutes)
            val times = rig.connectingTimes()
            val expected =
                gaps(times).indices.map {
                    (config.connectTimeout + config.retryDelay(it + 1)).inWholeMilliseconds /
                        1000.0
                }
            assertEquals(expected, gaps(times))
            assertEquals(30.seconds, config.retryDelay(gaps(times).size), "the waits reached their longest")
            assertEquals(times.size, rig.transport.connectAttempts)
            assertTrue(bike.commands.isEmpty())
        }

    @Test
    fun `a paired bike that refuses connections is tried again with growing waits, not in a tight loop`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { pairedWithPhone = true }
            bike.refusesConnections = true
            val config = SessionConfig()
            val rig = Rig(this, bike, config = config)
            rig.session.start(bike.address)
            delay(10.minutes)
            val times = rig.connectingTimes()
            val connect = FakeTiming().connect
            val expected =
                gaps(
                    times,
                ).indices.map { (connect + config.retryDelay(it + 1)).inWholeMilliseconds / 1000.0 }
            assertEquals(expected, gaps(times))
            assertEquals(30.seconds, config.retryDelay(gaps(times).size))
            assertEquals(times.size, rig.transport.connectAttempts)
            assertTrue(bike.commands.isEmpty())
        }

    @Test
    fun `the find list shows a paired bike that isn't heard first, and its advertisement once it is`() =
        runTest {
            val paired = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS).apply { pairedWithPhone = true }
            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, rssi = -40)
            val finder = BikeFinder(FakeBikeTransport(paired, other))
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { finder.bikes().collect { lists += it } }
            delay(3.seconds)
            val now = lists.last()
            assertEquals(listOf(paired.address, other.address), now.map { it.address })
            assertEquals(listOf(false, true), now.map { it.heard })
            assertNull(now.first().rssi)

            paired.pairedWithPhone = false
            delay(2.seconds)
            val heard = lists.last().single { it.address == paired.address }
            assertTrue(heard.heard)
            assertEquals(2, lists.last().size, "listed once")
            job.cancel()
        }

    @Test
    fun `the find list shows paired bikes at once, even while a scan has to wait`() =
        runTest {
            val paired = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS).apply { pairedWithPhone = true }
            val transport = FakeBikeTransport(paired)
            repeat(4) { transport.scanSlot() } // Android's scan starts for the next 30 s are used up
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { BikeFinder(transport).bikes().collect { lists += it } }
            delay(1.seconds)
            assertEquals(listOf(paired.address), lists.last().map { it.address })
            assertFalse(lists.last().single().heard)
            job.cancel()
        }

    @Test
    fun `nothing paired shows while Bluetooth is off`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { pairedWithPhone = true }
            val transport = FakeBikeTransport(bike)
            assertEquals(setOf(bike.address), transport.pairedBikes())
            transport.setRadio(RadioState.OFF)
            assertTrue(transport.pairedBikes().isEmpty())
        }
}
