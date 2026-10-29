package io.github.ridelabz.engwespeedtool.protocol

import java.util.Locale

/**
 * One decoded frame. A frame whose CRC does not match is still returned (with [crcOk] false)
 * so that corrupted traffic stays visible in logs instead of disappearing.
 */
public class Frame internal constructor(
    public val command: Int,
    private val payloadBytes: ByteArray,
    public val crcReceived: Int,
    public val crcComputed: Int,
    private val rawBytes: ByteArray,
) {
    public val crcOk: Boolean get() = crcReceived == crcComputed

    public val payload: ByteArray get() = payloadBytes.copyOf()

    public val payloadSize: Int get() = payloadBytes.size

    public val raw: ByteArray get() = rawBytes.copyOf()

    /** Unsigned payload byte at [index]. */
    public fun payloadByte(index: Int): Int = payloadBytes[index].toInt() and 0xFF

    /** True when both frames carry the same command and payload (how the bike echoes an accepted command). */
    public fun sameCommandAndPayload(other: Frame): Boolean =
        command == other.command && payloadBytes.contentEquals(other.payloadBytes)

    override fun equals(other: Any?): Boolean = other is Frame && rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = rawBytes.contentHashCode()

    /** Never includes the payload: some replies carry identifiers. */
    override fun toString(): String =
        String.format(Locale.ROOT, "Frame(command=%04X, payloadSize=%d, crcOk=%s)", command, payloadBytes.size, crcOk)
}
