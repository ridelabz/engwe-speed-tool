package io.github.ridelabz.engwespeedtool.protocol

import java.util.Locale
import java.util.UUID

/**
 * Bytes or text that may be written to a log. [hex] is null when only the size may be recorded.
 * [masked] is true when some or all bytes were withheld.
 */
public class Loggable internal constructor(
    public val hex: String?,
    public val size: Int,
    public val masked: Boolean,
)

/**
 * The rules that keep identifying data out of logs and reports. The session log applies them to
 * every record at capture time; nothing reaches storage unsanitised.
 *
 * - Outgoing frames come from the closed command set and are logged in full.
 * - Incoming frames are logged in full only when the CRC is valid and the command and payload length
 *   are known to carry no identifiers: status frames of the reference length, and one-byte wheel-motion,
 *   speed, light, assist-level, lock and lock-state frames. A bad CRC means the bytes may not be what they claim
 *   (for example parts of an identity reply behind a truncated frame), so those are logged by size only.
 * - Identity replies keep only the model string; everything after it, and the CRC (which depends on
 *   the serial), is replaced by `2A` bytes.
 * - Frames with any other command, and bytes outside complete frames, are logged by size only.
 * - Free text loses Bluetooth addresses (any separator, including Android's `XX:XX:…` form), long hex
 *   runs, serial-like tokens, `ENGWE-` name suffixes and e-mail addresses.
 * - Bluetooth UUIDs other than the Bluetooth SIG's 16-bit numbers keep only their first 8 hex digits.
 */
public object Sanitiser {
    public const val MASK_BYTE: Byte = 0x2A
    public const val NAME: String = "ENGWE-**"

    /** Status payload length of the reference layout. Status frames of other lengths are logged by size only. */
    public const val STATUS_PAYLOAD_LENGTH: Int = 54
    private const val OTHER_NAME = "**"
    private val ONE_BYTE_COMMANDS =
        setOf(
            Opcodes.WHEEL_MOTION,
            Opcodes.SPEED_LIMIT,
            Opcodes.LIGHTS,
            Opcodes.ASSIST_LEVEL,
            Opcodes.LOCK,
            Opcodes.LOCK_STATE,
        )
    private val KNOWN_COMMANDS = ONE_BYTE_COMMANDS + Opcodes.STATUS + Opcodes.IDENTITY

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val ADDRESS = Regex("[0-9A-Fa-fXx]{2}(?:[:\\-. ][0-9A-Fa-fXx]{2}){5}")
    private val ADDRESS_DOTTED = Regex("[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}")
    private val HEX_RUN = Regex("[0-9A-Fa-f]{12,}")
    private val ALNUM_RUN = Regex("[A-Za-z0-9]{12,}")
    private val ENGWE_SUFFIX = Regex("(?i)ENGWE[-_ ]?[0-9a-z]{1,4}(?![0-9a-z])")
    private val ENGWE_HEX_SUFFIX = Regex("(?i)ENGWE[- ]?[0-9a-f]{1,4}(?![0-9a-z])")

    public fun outgoing(frame: OutgoingFrame): Loggable =
        frame.bytes.let {
            Loggable(Hex.encode(it), it.size, masked = false)
        }

    public fun incoming(event: StreamEvent): Loggable =
        when (event) {
            is StreamEvent.Decoded -> incoming(event.frame)
            else -> Loggable(null, event.size, masked = true)
        }

    public fun incoming(frame: Frame): Loggable {
        val raw = frame.raw
        val full =
            frame.crcOk &&
                (
                    (frame.command == Opcodes.STATUS && frame.payloadSize == STATUS_PAYLOAD_LENGTH) ||
                        (frame.command in ONE_BYTE_COMMANDS && frame.payloadSize == 1)
                )
        return when {
            frame.command == Opcodes.IDENTITY -> Loggable(Hex.encode(maskIdentity(frame)), raw.size, masked = true)
            full -> Loggable(Hex.encode(raw), raw.size, masked = false)
            else -> Loggable(null, raw.size, masked = true)
        }
    }

    /** Command label for logs: a known command code, or `other` (an unknown code could come from data). */
    public fun commandLabel(frame: Frame): String =
        if (frame.command in KNOWN_COMMANDS) String.format(Locale.ROOT, "%04X", frame.command) else "other"

    public fun advertisedName(name: String?): String =
        if (name != null &&
            name.startsWith("ENGWE")
        ) {
            NAME
        } else {
            OTHER_NAME
        }

    /**
     * [uuid] for logs and reports: a 16-bit Bluetooth SIG number as `0x1820`; any other UUID with only its first
     * 8 hex digits, where makers put the short number of a service or characteristic
     * (`6e400001-****-****-****-************`). The rest may be chosen per device: some put their address in
     * the last 12 digits, and a maker could put an address or a serial anywhere else.
     */
    public fun uuid(uuid: UUID): String {
        val full = uuid.toString()
        val sig = full.startsWith("0000") && full.endsWith(SIG_BASE)
        return if (sig) "0x" + full.substring(4, 8).uppercase(Locale.ROOT) else full.substring(0, 8) + MASKED_UUID_REST
    }

    private const val MASKED_UUID_REST = "-****-****-****-************"

    private const val SIG_BASE = "-0000-1000-8000-00805f9b34fb"

    public fun text(text: String): String =
        text
            .replace(EMAIL, "<e-mail>")
            .replace(ADDRESS, "<address>")
            .replace(ADDRESS_DOTTED, "<address>")
            .replace(HEX_RUN, "<hex>")
            .replace(ALNUM_RUN) { if (looksLikeId(it.value)) "<id>" else it.value }
            .replace(ENGWE_SUFFIX, NAME)

    /**
     * True for text that contains a Bluetooth address (any separator), a run of 12 or more hex digits, an
     * e-mail address, or an advertised-name suffix (`ENGWE`, then `-`, a space or nothing, then up to 4 hex
     * digits; models use `_`, as in `ENGWE_E26`). For text kept as it is, such as a bike's model identity,
     * where [text] would garble legitimate values.
     */
    internal fun looksIdentifying(text: String): Boolean =
        ADDRESS.containsMatchIn(text) ||
            ADDRESS_DOTTED.containsMatchIn(text) ||
            HEX_RUN.containsMatchIn(text) ||
            EMAIL.containsMatchIn(text) ||
            ENGWE_HEX_SUFFIX.containsMatchIn(text)

    /** 12+ letters or digits that mix in a digit or are all capitals: serials and similar identifiers. */
    private fun looksLikeId(token: String): Boolean = token.any(Char::isDigit) || token.none(Char::isLowerCase)

    private fun maskIdentity(frame: Frame): ByteArray {
        val raw = frame.raw
        val payloadStart = FrameCodec.PREFIX_LENGTH
        val n = frame.payloadSize
        // Keep the model and its NUL terminator only when the model parses cleanly (valid CRC included).
        val keep =
            if (IdentityParser.modelOf(frame) !=
                null
            ) {
                (0 until n).first { frame.payloadByte(it) == 0 } + 1
            } else {
                0
            }
        for (i in keep until n) raw[payloadStart + i] = MASK_BYTE
        raw[payloadStart + n] = MASK_BYTE
        raw[payloadStart + n + 1] = MASK_BYTE
        return raw
    }
}
