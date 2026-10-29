package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.Sanitiser
import java.util.Locale
import java.util.UUID

/** The bike service and its characteristics. Always found by UUID: handle numbers differ between platforms. */
public object BikeGatt {
    public val SERVICE: UUID = standard(0x1820)

    /** The bike sends its frames as notifications of this characteristic. */
    public val NOTIFY: UUID = standard(0x1821)

    /** The app writes its frames to this characteristic. */
    public val WRITE: UUID = standard(0x1822)

    /** The client configuration descriptor that enables notifications. */
    public val CLIENT_CONFIG: UUID = standard(0x2902)

    /** The MTU before an exchange: notification values are cut to 20 bytes. */
    public const val DEFAULT_MTU: Int = 23

    /** The largest MTU Android asks for. */
    public const val MAX_MTU: Int = 517

    /** [uuid] as text, by the sanitiser's rule ([Sanitiser.uuid]): the same as the session log records it. */
    public fun text(uuid: UUID): String = Sanitiser.uuid(uuid)

    /** The UUID of a 16-bit Bluetooth SIG number. */
    public fun standard(short: Int): UUID {
        require(short in 0..0xFFFF) { "not a 16-bit UUID" }
        return UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))
    }
}

/** What a characteristic offers, as declared by the bike. */
public enum class CharacteristicProperty {
    BROADCAST,
    READ,
    WRITE_WITHOUT_RESPONSE,

    /** Acknowledged writes (Write Request and Write Response). */
    WRITE,
    NOTIFY,
    INDICATE,
    SIGNED_WRITE,
    EXTENDED_PROPERTIES,
}

public data class GattCharacteristic(
    val uuid: UUID,
    val properties: Set<CharacteristicProperty>,
    val descriptors: List<UUID>,
)

public data class GattService(
    val uuid: UUID,
    val characteristics: List<GattCharacteristic>,
) {
    public fun characteristic(uuid: UUID): GattCharacteristic? = characteristics.firstOrNull { it.uuid == uuid }
}
