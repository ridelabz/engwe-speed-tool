package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.OutgoingFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * A bike's Bluetooth address. It identifies the bike, so it must never reach logs, exports or text meant
 * for sharing: [toString] hides it. Only the saved-bike storage and the Bluetooth adapter read [value].
 */
public class BikeAddress(
    value: String,
) {
    /** Upper case, `XX:XX:XX:XX:XX:XX`, as Android reports addresses. */
    public val value: String = value.uppercase(Locale.ROOT)

    init {
        // The message never repeats the value.
        require(FORMAT.matches(this.value)) { "not a Bluetooth address" }
    }

    override fun equals(other: Any?): Boolean = other is BikeAddress && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "BikeAddress(**)"

    private companion object {
        val FORMAT = Regex("[0-9A-F]{2}(?::[0-9A-F]{2}){5}")
    }
}

/**
 * One advertisement of a bike whose name starts with `ENGWE`, or ([paired]) a bike paired with this phone,
 * which needs none: Android keeps a link to a paired bike while Bluetooth is on, a bike with a link doesn't
 * advertise, and the app connects to it directly. Nothing else of the advertisement is kept, not even the name.
 */
public class Advertisement(
    public val address: BikeAddress,
    /** Signal strength in dBm; null for a paired bike that wasn't heard. */
    public val rssi: Int?,
) {
    /** True for a real advertisement, false for a paired bike that wasn't heard. */
    public val heard: Boolean get() = rssi != null

    override fun toString(): String = "Advertisement(rssi=$rssi)"

    public companion object {
        /** A bike paired with this phone, to connect to directly. */
        public fun paired(address: BikeAddress): Advertisement = Advertisement(address, null)
    }
}

/** Whether the phone's Bluetooth can be used. */
public enum class RadioState {
    ON,
    OFF,

    /** The app may not use Bluetooth (the rider hasn't granted the permission). */
    NOT_PERMITTED,

    /** The phone has no Bluetooth LE. */
    UNAVAILABLE,
}

/** Why a link operation failed or a link ended. */
public enum class LinkFailure {
    /** The bike or the phone's Bluetooth stack didn't answer in time. */
    TIMEOUT,

    /** The Bluetooth stack reported an error ([LinkException.status] has its code). */
    GATT_ERROR,

    /** The connection dropped. */
    DISCONNECTED,

    /** This app closed the link. */
    CLOSED,

    BLUETOOTH_OFF,
    NOT_PERMITTED,

    /**
     * The bike lacks what the operation needs: the services weren't discovered yet, or the bike service,
     * a characteristic or a property (such as acknowledged writes) is missing.
     */
    UNSUPPORTED,
}

/** A failed link operation. [status] is the Bluetooth stack's status code, when it gave one. */
public class LinkException(
    public val failure: LinkFailure,
    public val status: Int? = null,
) : Exception("link failure: ${failure.name.lowercase(Locale.ROOT)}${status?.let { " (status $it)" } ?: ""}")

/** What arrives on a link. */
public sealed class LinkEvent {
    /** One notification value from the bike's notify characteristic, exactly as received. */
    public class Notification(
        value: ByteArray,
    ) : LinkEvent() {
        private val copy = value.copyOf()

        public val value: ByteArray get() = copy.copyOf()

        /** Never includes the bytes: some frames carry identifiers. */
        override fun toString(): String = "Notification(size=${copy.size})"
    }

    /** The link ended. Nothing arrives after this. */
    public class Disconnected(
        public val failure: LinkFailure,
        /** The Bluetooth stack's status code, when it gave one. */
        public val status: Int? = null,
    ) : LinkEvent() {
        override fun toString(): String = "Disconnected($failure, status=$status)"
    }
}

/**
 * Bluetooth as the session needs it: the radio's state, scanning and connecting. Implementations: the
 * Android adapter, and [FakeBike]'s transport for demo mode and tests.
 */
public interface BikeTransport {
    public val radio: StateFlow<RadioState>

    /**
     * Waits until a scan may start, and counts it. Android silently stops an app's scans after 5 starts
     * in 30 s, so every scan start of the app goes through one limit; call this right before collecting
     * [scan], so that a wait doesn't count as search time.
     */
    public suspend fun scanSlot()

    /**
     * Scans while collected and emits every advertisement of a bike whose name starts with `ENGWE`,
     * repeats included, so that signal strengths stay current. With [address], only that bike's. Each
     * collection starts a scan and cancelling stops it; take a [scanSlot] first. Fails with [LinkException]
     * when Bluetooth is off or not permitted.
     */
    public fun scan(address: BikeAddress? = null): Flow<Advertisement>

    /**
     * Bikes named `ENGWE…` that are paired with this phone. Android keeps a link to a paired bike while
     * Bluetooth is on, so such a bike may never be heard advertising; the app connects to it directly.
     * Empty when Bluetooth is off or not permitted.
     */
    public fun pairedBikes(): Set<BikeAddress>

    /**
     * Connects to the bike at [address] over Bluetooth LE and returns the connected link. It never creates
     * a pairing; a bike already paired with the phone is used over the link Android keeps to it. Throws
     * [LinkException] when connecting fails; it may also keep waiting for the bike, so callers set a
     * timeout. Cancelling closes the attempt.
     */
    public suspend fun connect(address: BikeAddress): BikeLink
}

/**
 * One connection to a bike, from connecting until it drops or is closed. Reconnecting needs a new link.
 *
 * A link talks only to the bike service ([BikeGatt]): it receives the notify characteristic's
 * notifications and writes [OutgoingFrame]s, which only exist for the app's fixed commands, to the write
 * characteristic with acknowledged writes. It has no other write. GATT operations run one at a time;
 * concurrent calls wait their turn.
 */
public interface BikeLink {
    /**
     * Notification values in arrival order, then one [LinkEvent.Disconnected] when the link ends for any
     * reason, including [close]; then the flow completes. Values are kept until collected. For a single
     * collector.
     */
    public val events: Flow<LinkEvent>

    /** Discovers the bike's services. Writes and notifications need this first. */
    public suspend fun discoverServices(): List<GattService>

    /**
     * Asks for [mtu], which must be within [BikeGatt.DEFAULT_MTU]..[BikeGatt.MAX_MTU] (otherwise
     * [IllegalArgumentException]), and returns the MTU in effect afterwards.
     */
    public suspend fun requestMtu(mtu: Int): Int

    /** Enables the notify characteristic's notifications, on the phone and in the bike's client configuration. */
    public suspend fun enableNotifications()

    /**
     * Writes [frame] to the write characteristic as an acknowledged write (a Write Request) and returns
     * once the bike acknowledged it. The acknowledgement shows that the frame arrived, not that the bike
     * acted on it. Never retried. Fails with [LinkFailure.UNSUPPORTED] before [discoverServices] or when
     * the characteristic doesn't offer acknowledged writes, with [LinkFailure.GATT_ERROR] when the bike
     * rejects the write, and with [LinkFailure.TIMEOUT] when it doesn't respond. A write still waiting
     * for its response fails when the link ends.
     */
    public suspend fun write(frame: OutgoingFrame)

    /** The connection's current signal strength in dBm. */
    public suspend fun readRssi(): Int

    /** Disconnects and releases the link. Calling it again does nothing. */
    public fun close()
}
