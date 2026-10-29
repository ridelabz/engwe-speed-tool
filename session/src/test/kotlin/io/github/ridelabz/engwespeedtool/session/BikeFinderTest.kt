package io.github.ridelabz.engwespeedtool.session

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BikeFinderTest {
    @Test
    fun `lists bikes strongest first, and drops one that went quiet`() =
        runTest {
            val near = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS, rssi = -55)
            val far = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, rssi = -88)
            val finder = BikeFinder(FakeBikeTransport(near, far))
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { finder.bikes().collect { lists += it } }
            delay(3.seconds)
            assertEquals(emptyList<Advertisement>(), lists.first(), "an empty list first, while searching")
            val now = lists.last()
            assertEquals(listOf(near.address, far.address), now.map { it.address })
            assertEquals(listOf(-55, -88), now.map { it.rssi })

            near.advertises = false
            delay(32.seconds)
            assertEquals(listOf(far.address), lists.last().map { it.address })
            near.advertises = true
            delay(2.seconds)
            assertEquals(listOf(near.address, far.address), lists.last().map { it.address })
            job.cancel()
        }

    @Test
    fun `a bike heard again keeps its place`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val finder = BikeFinder(FakeBikeTransport(bike), forgetAfter = 3.seconds)
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { finder.bikes().collect { lists += it } }
            delay(60.seconds)
            assertTrue(lists.drop(2).all { it.size == 1 }, "never dropped while it keeps advertising")
            job.cancel()
        }

    @Test
    fun `a bike that advertises slowly stays listed`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val finder = BikeFinder(FakeBikeTransport(listOf(bike), advertisingInterval = 8.seconds))
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { finder.bikes().collect { lists += it } }
            delay(2.minutes)
            assertTrue(lists.drop(2).all { it.size == 1 }, "never dropped between its advertisements")
            job.cancel()
        }

    @Test
    fun `bikes of similar signal keep the order they were first heard in`() =
        runTest {
            val first = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS, rssi = -58)
            val second = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, rssi = -52)
            val finder = BikeFinder(FakeBikeTransport(first, second))
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { finder.bikes().collect { lists += it } }
            delay(5.seconds)
            assertEquals(listOf(first.address, second.address), lists.last().map { it.address })
            job.cancel()
        }

    @Test
    fun `a paired bike stays at the top when it starts and stops being heard`() =
        runTest {
            val paired = FakeBike.DEMO_ADDRESS
            val other = FakeBike.DEMO_ADDRESS_2
            var pairedHeard = false
            val transport =
                object : BikeTransport {
                    override val radio = MutableStateFlow(RadioState.ON)

                    override suspend fun scanSlot() = Unit

                    override fun pairedBikes() = setOf(paired)

                    override fun scan(address: BikeAddress?): Flow<Advertisement> =
                        flow {
                            while (true) {
                                emit(Advertisement(other, -50))
                                if (pairedHeard) emit(Advertisement(paired, -80))
                                delay(1.seconds)
                            }
                        }

                    override suspend fun connect(address: BikeAddress): BikeLink = awaitCancellation()
                }
            val finder = BikeFinder(transport, forgetAfter = 5.seconds)
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { finder.bikes().collect { lists += it } }

            fun last() = lists.last().map { it.address to it.rssi }
            delay(3.seconds)
            assertEquals(listOf(paired to null, other to -50), last(), "not heard: listed as paired, first")
            pairedHeard = true
            delay(3.seconds)
            assertEquals(listOf(paired to -80, other to -50), last(), "heard: still first, though weaker")
            pairedHeard = false
            delay(10.seconds)
            assertEquals(listOf(paired to null, other to -50), last(), "quiet again: back to paired, same place")
            assertTrue(lists.drop(1).all { it.firstOrNull()?.address == paired }, "never moved")
            assertTrue(lists.all { l -> l.map { it.address }.toSet().size == l.size }, "never listed twice")
            job.cancel()
        }

    @Test
    fun `two paired bikes keep their order when only the first is heard`() =
        runTest {
            val first = FakeBike.DEMO_ADDRESS
            val second = FakeBike.DEMO_ADDRESS_2
            val transport =
                object : BikeTransport {
                    override val radio = MutableStateFlow(RadioState.ON)

                    override suspend fun scanSlot() = Unit

                    override fun pairedBikes() = linkedSetOf(first, second)

                    override fun scan(address: BikeAddress?): Flow<Advertisement> =
                        flow {
                            while (true) {
                                emit(Advertisement(first, -40))
                                delay(1.seconds)
                            }
                        }

                    override suspend fun connect(address: BikeAddress): BikeLink = awaitCancellation()
                }
            val lists = mutableListOf<List<Advertisement>>()
            val job = launch { BikeFinder(transport).bikes().collect { lists += it } }
            delay(3.seconds)
            assertEquals(listOf(first to -40, second to null), lists.last().map { it.address to it.rssi })
            assertTrue(lists.drop(1).all { l -> l.map { it.address } == listOf(first, second) })
            job.cancel()
        }

    @Test
    fun `Bluetooth off fails the search`() =
        runTest {
            val transport = FakeBikeTransport(FakeBike(backgroundScope))
            transport.setRadio(RadioState.OFF)
            val e = assertThrows<LinkException> { BikeFinder(transport).bikes().first { it.isNotEmpty() } }
            assertEquals(LinkFailure.BLUETOOTH_OFF, e.failure)
        }
}
