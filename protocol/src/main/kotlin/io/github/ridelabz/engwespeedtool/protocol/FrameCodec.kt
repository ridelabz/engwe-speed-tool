package io.github.ridelabz.engwespeedtool.protocol

/** Thrown when bytes are not a structurally valid frame. */
public class FrameException(
    message: String,
) : Exception(message)

/**
 * The frame format, all multi-byte fields big-endian:
 *
 * `45 6E | command (2) | length (2) | payload | CRC (2) | 77 65`
 *
 * The CRC is CRC-16/MODBUS over command, length and payload, serialised high byte first.
 */
public object FrameCodec {
    public const val OVERHEAD: Int = 10
    public const val MAX_PAYLOAD: Int = 0xFFFF
    internal const val PREFIX_LENGTH: Int = 6
    internal const val HEADER_0: Byte = 0x45
    internal const val HEADER_1: Byte = 0x6E
    internal const val FOOTER_0: Byte = 0x77
    internal const val FOOTER_1: Byte = 0x65

    /** Encodes [payload] under [command]. Internal: outside this module only [BikeCommand] produces frames. */
    internal fun encode(
        command: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        require(command in 0..0xFFFF) { "command must fit in 2 bytes" }
        require(payload.size <= MAX_PAYLOAD) { "payload too long" }
        val n = payload.size
        val out = ByteArray(OVERHEAD + n)
        out[0] = HEADER_0
        out[1] = HEADER_1
        out[2] = (command ushr 8).toByte()
        out[3] = command.toByte()
        out[4] = (n ushr 8).toByte()
        out[5] = n.toByte()
        payload.copyInto(out, PREFIX_LENGTH)
        val crc = Crc16Modbus.compute(out, 2, PREFIX_LENGTH + n)
        out[PREFIX_LENGTH + n] = (crc ushr 8).toByte()
        out[PREFIX_LENGTH + n + 1] = crc.toByte()
        out[PREFIX_LENGTH + n + 2] = FOOTER_0
        out[PREFIX_LENGTH + n + 3] = FOOTER_1
        return out
    }

    /**
     * Decodes exactly one frame. Structural problems throw [FrameException]; a CRC mismatch is
     * reported through [Frame.crcOk] unless [requireValidCrc] is set.
     */
    public fun decode(
        bytes: ByteArray,
        requireValidCrc: Boolean = false,
    ): Frame {
        if (bytes.size < OVERHEAD) throw FrameException("too short")
        if (bytes[0] != HEADER_0 || bytes[1] != HEADER_1) throw FrameException("bad header")
        val length = u16(bytes, 4)
        if (bytes.size != OVERHEAD + length) {
            throw FrameException("length field doesn't match the frame size")
        }
        if (bytes[bytes.size - 2] != FOOTER_0 || bytes[bytes.size - 1] != FOOTER_1) throw FrameException("bad footer")
        val command = u16(bytes, 2)
        val payload = bytes.copyOfRange(PREFIX_LENGTH, PREFIX_LENGTH + length)
        val crcReceived = u16(bytes, PREFIX_LENGTH + length)
        val crcComputed = Crc16Modbus.compute(bytes, 2, PREFIX_LENGTH + length)
        if (requireValidCrc && crcReceived != crcComputed) throw FrameException("CRC mismatch")
        return Frame(command, payload, crcReceived, crcComputed, bytes.copyOf())
    }

    internal fun u16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
