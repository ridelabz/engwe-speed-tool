package io.github.ridelabz.engwespeedtool.ble

import io.github.ridelabz.engwespeedtool.session.Advertisement
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.BikeLink
import io.github.ridelabz.engwespeedtool.session.BikeTransport
import io.github.ridelabz.engwespeedtool.session.LinkException
import io.github.ridelabz.engwespeedtool.session.LinkFailure
import io.github.ridelabz.engwespeedtool.session.RadioState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [BikeTransport] whose links are [GattLink]s on ports made by [ports]. Internal to this module: the
 * app reaches bikes only through a session.
 */
internal class GattTransport(
    override val radio: StateFlow<RadioState>,
    private val scanner: (BikeAddress?) -> Flow<Advertisement>,
    private val ports: (BikeAddress) -> GattPort,
    /** The app's scan-start limit; required, so that no transport can forget it. */
    private val slots: suspend () -> Unit,
    /** Bikes paired with the phone; required, so that a paired bike, which may never advertise, isn't forgotten. */
    private val paired: () -> Set<BikeAddress>,
    private val operationTimeout: Duration = OPERATION_TIMEOUT,
) : BikeTransport {
    override suspend fun scanSlot() = slots()

    override fun scan(address: BikeAddress?): Flow<Advertisement> = scanner(address)

    override fun pairedBikes(): Set<BikeAddress> = paired()

    override suspend fun connect(address: BikeAddress): BikeLink {
        radioFailure(radio.value)?.let { throw LinkException(it) }
        val link = GattLink(ports(address), operationTimeout)
        link.connect()
        return link
    }

    companion object {
        /** Shorter than the session's own operation timeout, so that a stuck operation ends the link first. */
        val OPERATION_TIMEOUT: Duration = 8.seconds

        fun radioFailure(state: RadioState): LinkFailure? =
            when (state) {
                RadioState.ON -> null
                RadioState.OFF -> LinkFailure.BLUETOOTH_OFF
                RadioState.NOT_PERMITTED -> LinkFailure.NOT_PERMITTED
                RadioState.UNAVAILABLE -> LinkFailure.UNSUPPORTED
            }
    }
}
