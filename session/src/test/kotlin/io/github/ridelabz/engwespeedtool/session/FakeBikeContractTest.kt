package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.session.contract.BikeLinkContract
import io.github.ridelabz.engwespeedtool.session.contract.LinkHarness
import io.github.ridelabz.engwespeedtool.session.contract.ReceivedWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.time.Duration

class FakeBikeContractTest {
    @TestFactory
    fun contract(): List<DynamicTest> =
        BikeLinkContract.cases.map { case ->
            DynamicTest.dynamicTest(case.name) { runTest { case.run(FakeHarness(backgroundScope)) } }
        }
}

/** Fake bikes that send nothing on their own, so that only the contract's notifications arrive. */
private class FakeHarness(
    scope: CoroutineScope,
) : LinkHarness {
    private val silent = FakeTiming(statusOn = Duration.INFINITE, statusOff = Duration.INFINITE)

    /** Listed first, so that a link picking the first matching characteristic anywhere writes here. */
    private val decoy =
        GattService(
            BikeGatt.standard(0xFFF0),
            listOf(
                GattCharacteristic(BikeGatt.WRITE, setOf(CharacteristicProperty.WRITE), emptyList()),
                GattCharacteristic(
                    BikeGatt.NOTIFY,
                    setOf(CharacteristicProperty.NOTIFY),
                    listOf(BikeGatt.CLIENT_CONFIG),
                ),
            ),
        )
    private val reference =
        FakeBike(scope, FakeBike.DEMO_ADDRESS, services = listOf(decoy) + FakeBike.REFERENCE_SERVICES, timing = silent)
    private val withoutAck =
        FakeBike(
            scope,
            FakeBike.DEMO_ADDRESS_2,
            services =
                FakeBike.REFERENCE_SERVICES.map { service ->
                    service.copy(
                        characteristics =
                            service.characteristics.map {
                                if (it.uuid == BikeGatt.WRITE) {
                                    it.copy(properties = setOf(CharacteristicProperty.WRITE_WITHOUT_RESPONSE))
                                } else {
                                    it
                                }
                            },
                    )
                },
            timing = silent,
        )
    private val bikes = listOf(reference, withoutAck)

    override val transport: BikeTransport = FakeBikeTransport(bikes)
    override val bike: BikeAddress = reference.address
    override val bikeWithoutAcknowledgedWrite: BikeAddress = withoutAck.address
    override val absent: BikeAddress = BikeAddress("F0:00:00:00:00:FF")

    override suspend fun notify(value: ByteArray) = reference.notifyRaw(value)

    override suspend fun drop() = reference.dropLink()

    override fun rejectNextWrite(status: Int) {
        reference.rejectNextWrite = status
    }

    override fun withholdNextWriteResponse() {
        reference.withholdNextWriteResponse = true
    }

    // The fake resolves everything in the bike service and always writes with a response.
    override fun received(): List<ReceivedWrite> =
        bikes.flatMap { bike ->
            bike.gattWrites.map {
                ReceivedWrite(
                    BikeGatt.SERVICE,
                    it.characteristic,
                    it.descriptor,
                    it.frame?.bytes ?: checkNotNull(it.value),
                    acknowledged = true,
                )
            }
        }

    override fun openLinks(): Int = bikes.count { it.connected }

    override fun maxConcurrentOperations(): Int = bikes.maxOf { it.maxConcurrentOperations }
}
