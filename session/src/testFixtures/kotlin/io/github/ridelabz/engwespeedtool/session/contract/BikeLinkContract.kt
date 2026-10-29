package io.github.ridelabz.engwespeedtool.session.contract

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.BikeGatt
import io.github.ridelabz.engwespeedtool.session.BikeLink
import io.github.ridelabz.engwespeedtool.session.BikeTransport
import io.github.ridelabz.engwespeedtool.session.CharacteristicProperty
import io.github.ridelabz.engwespeedtool.session.LinkEvent
import io.github.ridelabz.engwespeedtool.session.LinkException
import io.github.ridelabz.engwespeedtool.session.LinkFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The bike side of a [BikeTransport] under test: an implementation's test simulates bikes and reports
 * what they received, and [BikeLinkContract] checks the transport against them.
 */
public interface LinkHarness {
    public val transport: BikeTransport

    /**
     * A bike with the reference bike's services, plus a decoy service that also has characteristics with the
     * write and notify UUIDs, listed before the bike service. It advertises and accepts one connection.
     */
    public val bike: BikeAddress

    /** A bike like [bike] whose write characteristic offers only writes without response. */
    public val bikeWithoutAcknowledgedWrite: BikeAddress

    /** An address no bike answers. */
    public val absent: BikeAddress

    /** The connected bike sends [value] as one notification of its notify characteristic. */
    public suspend fun notify(value: ByteArray)

    /** The connected bike drops the connection. */
    public suspend fun drop()

    /** The bike rejects its next write with the Bluetooth status [status] (it still receives it). */
    public fun rejectNextWrite(status: Int)

    /** The bike receives its next write but never responds. */
    public fun withholdNextWriteResponse()

    /** Every write the bikes received, descriptor writes included, in order. */
    public fun received(): List<ReceivedWrite>

    /** Connections the phone side still holds, closed ones not counted. */
    public fun openLinks(): Int

    /** The most GATT operations the bikes saw in flight at the same time. */
    public fun maxConcurrentOperations(): Int
}

/** A write as the bike received it. */
public class ReceivedWrite(
    public val service: UUID,
    public val characteristic: UUID,
    /** Null for a write to the characteristic's value. */
    public val descriptor: UUID?,
    value: ByteArray,
    /** True for a Write Request (acknowledged), false for a Write Command. */
    public val acknowledged: Boolean,
) {
    private val copy = value.copyOf()

    public val value: ByteArray get() = copy.copyOf()

    override fun toString(): String =
        "ReceivedWrite($service, $characteristic, $descriptor, size=${copy.size}, acknowledged=$acknowledged)"
}

public class ContractCase(
    public val name: String,
    public val run: suspend (LinkHarness) -> Unit,
)

/**
 * What every [BikeTransport] and [BikeLink] must do. An implementation's test runs each case with a fresh
 * [LinkHarness]. Waiting is bounded, so a broken implementation fails instead of hanging.
 *
 * The bounds use the calling coroutine's clock. Run the cases on virtual time (`runTest`) only when the
 * implementation's callbacks run on the same test scheduler, as [io.github.ridelabz.engwespeedtool.session.FakeBike]'s
 * do; otherwise use real time (`runBlocking`), or every bound expires at once.
 */
public object BikeLinkContract {
    private val WAIT = 10.seconds
    private val SHORT = 1.seconds

    /** Bluetooth status "write not permitted". */
    private const val WRITE_NOT_PERMITTED = 3

    public val cases: List<ContractCase> =
        listOf(
            ContractCase("connects and discovers the bike service as the bike declares it") { h ->
                val link = h.transport.connect(h.bike)
                try {
                    val services = withTimeout(WAIT) { link.discoverServices() }
                    val service = services.singleOrNull { it.uuid == BikeGatt.SERVICE } ?: fail("bike service missing")
                    val write = service.characteristic(BikeGatt.WRITE) ?: fail("write characteristic missing")
                    expect(CharacteristicProperty.WRITE in write.properties, "acknowledged write property missing")
                    expect(
                        CharacteristicProperty.WRITE_WITHOUT_RESPONSE in write.properties,
                        "write-without-response property missing",
                    )
                    val notify = service.characteristic(BikeGatt.NOTIFY) ?: fail("notify characteristic missing")
                    expect(CharacteristicProperty.NOTIFY in notify.properties, "notify property missing")
                    expect(BikeGatt.CLIENT_CONFIG in notify.descriptors, "client configuration descriptor missing")
                } finally {
                    link.close()
                }
            },
            ContractCase("keeps notifications in order until they are collected") { h ->
                val link = ready(h)
                val values = listOf(byteArrayOf(1), byteArrayOf(2, 3), ByteArray(20) { it.toByte() })
                for (v in values) h.notify(v)
                val events = withTimeout(WAIT) { link.events.take(values.size).toList() }
                expect(
                    events.map { (it as? LinkEvent.Notification)?.value?.toList() } == values.map { it.toList() },
                    "notifications changed, lost or reordered",
                )
                link.close()
            },
            ContractCase("enables notifications in the notify characteristic's client configuration only") { h ->
                val link = ready(h)
                val received = h.received()
                expect(received.size == 1, "expected one write, got $received")
                expectClientConfig(received[0])
                link.close()
            },
            ContractCase(
                "writes exactly the frame, as an acknowledged write to the bike service's write characteristic",
            ) { h ->
                val link = ready(h)
                val frame = BikeCommand.ReadIdentity.toFrame()
                withTimeout(WAIT) { link.write(frame) }
                val received = h.received()
                expect(received.size == 2, "expected the client configuration and one frame, got $received")
                expectClientConfig(received[0])
                val w = received[1]
                expect(w.service == BikeGatt.SERVICE, "written to another service")
                expect(w.characteristic == BikeGatt.WRITE && w.descriptor == null, "written to another characteristic")
                expect(w.acknowledged, "not an acknowledged write")
                expect(w.value.contentEquals(frame.bytes), "written bytes differ from the frame")
                link.close()
            },
            ContractCase("fails a rejected write and doesn't repeat it") { h ->
                val link = ready(h)
                h.rejectNextWrite(WRITE_NOT_PERMITTED)
                val e = failure { link.write(BikeCommand.ReadIdentity.toFrame()) }
                expect(e.failure == LinkFailure.GATT_ERROR, "expected GATT_ERROR, got ${e.failure}")
                expect(e.status == WRITE_NOT_PERMITTED, "expected status $WRITE_NOT_PERMITTED, got ${e.status}")
                delay(SHORT)
                expect(frames(h).size == 1, "expected one write, got ${frames(h).size}")
                link.close()
            },
            ContractCase("waits for the bike's write response and doesn't repeat an unanswered write") { h ->
                val link = ready(h)
                h.withholdNextWriteResponse()
                coroutineScope {
                    val pending = async { runCatching { link.write(BikeCommand.ReadIdentity.toFrame()) } }
                    delay(SHORT)
                    val early = if (pending.isCompleted) pending.await().exceptionOrNull() else null
                    val timedOut = (early as? LinkException)?.failure == LinkFailure.TIMEOUT
                    expect(!pending.isCompleted || timedOut, "returned without a response")
                    link.close()
                    val outcome = withTimeout(WAIT) { pending.await() }.exceptionOrNull()
                    expect(outcome is LinkException, "an unanswered write succeeded")
                }
                expect(frames(h).size == 1, "expected one write, got ${frames(h).size}")
            },
            ContractCase("refuses to write before the services are discovered") { h ->
                val link = h.transport.connect(h.bike)
                val e = failure { link.write(BikeCommand.ReadIdentity.toFrame()) }
                expect(e.failure == LinkFailure.UNSUPPORTED, "expected UNSUPPORTED, got ${e.failure}")
                expect(h.received().isEmpty(), "something was written")
                link.close()
            },
            ContractCase("refuses to write when acknowledged writes aren't offered") { h ->
                val link = h.transport.connect(h.bikeWithoutAcknowledgedWrite)
                withTimeout(WAIT) { link.discoverServices() }
                val e = failure { link.write(BikeCommand.ReadIdentity.toFrame()) }
                expect(e.failure == LinkFailure.UNSUPPORTED, "expected UNSUPPORTED, got ${e.failure}")
                expect(h.received().isEmpty(), "something was written")
                link.close()
            },
            ContractCase("negotiates an MTU") { h ->
                val link = ready(h)
                val mtu = withTimeout(WAIT) { link.requestMtu(BikeGatt.MAX_MTU) }
                expect(mtu in BikeGatt.DEFAULT_MTU..BikeGatt.MAX_MTU, "MTU $mtu out of range")
                link.close()
            },
            ContractCase("reads the signal strength") { h ->
                val link = ready(h)
                val rssi = withTimeout(WAIT) { link.readRssi() }
                expect(rssi in -127..20, "implausible signal strength $rssi")
                link.close()
            },
            ContractCase("lets concurrent operations wait their turn") { h ->
                val link = ready(h)
                withTimeout(WAIT) {
                    coroutineScope {
                        val jobs =
                            listOf(
                                async { link.readRssi() },
                                async { link.requestMtu(BikeGatt.MAX_MTU) },
                                async { link.readRssi() },
                            )
                        jobs.awaitAll()
                    }
                }
                expect(h.maxConcurrentOperations() <= 1, "operations overlapped")
                link.close()
            },
            ContractCase("ends with one disconnect when the bike drops the link, and fails later operations") { h ->
                val link = ready(h)
                h.drop()
                val events = withTimeout(WAIT) { link.events.toList() }
                val last = events.lastOrNull() as? LinkEvent.Disconnected ?: fail("no disconnect event")
                expect(events.count { it is LinkEvent.Disconnected } == 1, "more than one disconnect event")
                expect(last.failure == LinkFailure.DISCONNECTED, "expected DISCONNECTED, got ${last.failure}")
                val e = failure { link.write(BikeCommand.ReadIdentity.toFrame()) }
                expect(e.failure == LinkFailure.DISCONNECTED, "expected DISCONNECTED, got ${e.failure}")
                expect(frames(h).isEmpty(), "something was written")
                expect(h.openLinks() == 0, "the dropped link is still open")
                link.close()
            },
            ContractCase("closes once, ends the events, and fails later operations") { h ->
                val link = ready(h)
                link.close()
                link.close()
                val events = withTimeout(WAIT) { link.events.toList() }
                val last = events.singleOrNull() as? LinkEvent.Disconnected ?: fail("expected only a disconnect event")
                expect(last.failure == LinkFailure.CLOSED, "expected CLOSED, got ${last.failure}")
                val e = failure { link.write(BikeCommand.ReadIdentity.toFrame()) }
                expect(e.failure == LinkFailure.CLOSED, "expected CLOSED, got ${e.failure}")
                expect(h.openLinks() == 0, "the closed link is still open")
            },
            ContractCase("doesn't connect to an absent bike and leaves nothing open") { h ->
                val outcome =
                    withTimeoutOrNull(WAIT) {
                        try {
                            h.transport.connect(h.absent)
                        } catch (_: LinkException) {
                            null
                        }
                    }
                if (outcome != null) {
                    outcome.close()
                    fail("connected to an absent bike")
                }
                expect(h.openLinks() == 0, "a failed connection attempt is still open")
            },
        )

    /** Writes of frames, without the client configuration. */
    private fun frames(h: LinkHarness): List<ReceivedWrite> = h.received().filter { it.descriptor == null }

    private fun expectClientConfig(w: ReceivedWrite) {
        expect(w.service == BikeGatt.SERVICE, "client configuration written in another service")
        expect(w.characteristic == BikeGatt.NOTIFY, "client configuration of another characteristic")
        expect(w.descriptor == BikeGatt.CLIENT_CONFIG, "written to another descriptor")
        expect(w.value.contentEquals(byteArrayOf(1, 0)), "client configuration isn't 'notifications on'")
        expect(w.acknowledged, "client configuration not an acknowledged write")
    }

    private suspend fun ready(h: LinkHarness): BikeLink {
        val link = h.transport.connect(h.bike)
        withTimeout(WAIT) {
            link.discoverServices()
            link.enableNotifications()
        }
        return link
    }

    private suspend fun failure(block: suspend () -> Unit): LinkException {
        try {
            withTimeout(WAIT) { block() }
        } catch (e: LinkException) {
            return e
        }
        fail("expected a link failure")
    }

    private fun expect(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw AssertionError(message)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
