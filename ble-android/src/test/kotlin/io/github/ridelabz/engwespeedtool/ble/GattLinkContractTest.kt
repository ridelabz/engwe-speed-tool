package io.github.ridelabz.engwespeedtool.ble

import io.github.ridelabz.engwespeedtool.session.contract.BikeLinkContract
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The link contract on [GattLink], with a simulated stack answering on the test's clock. */
@RunWith(Parameterized::class)
class GattLinkContractTest(
    private val name: String,
) {
    @Test
    fun contract() =
        runTest {
            BikeLinkContract.cases.single { it.name == name }.run(FakeGattHarness(backgroundScope))
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = BikeLinkContract.cases.map { arrayOf(it.name) }
    }
}
