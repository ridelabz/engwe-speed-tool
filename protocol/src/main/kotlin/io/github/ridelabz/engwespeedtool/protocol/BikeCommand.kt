package io.github.ridelabz.engwespeedtool.protocol

/**
 * The complete set of commands this app can send to a bike. The class is sealed, so no other
 * command can be created outside this module, and each command maps to exactly one frame.
 */
public sealed class BikeCommand {
    internal abstract val opcode: Int

    /** The command code of the bike's answer, with the same payload: its own (an echo), unless said otherwise. */
    internal open val replyOpcode: Int get() = opcode

    internal abstract fun payload(): ByteArray

    /** The bytes to write for this command. */
    public fun toFrame(): OutgoingFrame = OutgoingFrame(this)

    /** Sets the motor-assistance cutoff. The bike echoes the frame when it accepts it. */
    public data class SetSpeedLimit(
        val limit: SpeedLimit,
    ) : BikeCommand() {
        override val opcode: Int get() = Opcodes.SPEED_LIMIT

        override fun payload(): ByteArray = byteArrayOf(limit.kmh.toByte())
    }

    /** Switches the lights on or off. The bike echoes the frame when it accepts it. */
    public data class SetLights(
        val on: Boolean,
    ) : BikeCommand() {
        override val opcode: Int get() = Opcodes.LIGHTS

        override fun payload(): ByteArray = byteArrayOf(if (on) 0xFF.toByte() else 0x00)
    }

    /**
     * Sets the assist level. The bike echoes the frame when it accepts it, and its status then shows the new
     * level. Levels 0 to 5 were set on the reference bike and showed on its display.
     */
    public data class SetAssistLevel(
        val level: AssistLevel,
    ) : BikeCommand() {
        override val opcode: Int get() = Opcodes.ASSIST_LEVEL

        override fun payload(): ByteArray = byteArrayOf(level.level.toByte())
    }

    /**
     * Locks or unlocks the bike, as the ENGWE app's lock button does: on the reference bike a lock switched the
     * display off and its status showed both power flags off; an unlock switched it on again. The bike answers
     * with its lock state (command `0109`) carrying the same value, `02` locked or `01` unlocked, rather than an
     * echo.
     */
    public data class SetLocked(
        val locked: Boolean,
    ) : BikeCommand() {
        override val opcode: Int get() = Opcodes.LOCK

        override val replyOpcode: Int get() = Opcodes.LOCK_STATE

        override fun payload(): ByteArray = byteArrayOf(if (locked) LOCK_STATE_LOCKED else LOCK_STATE_UNLOCKED)
    }

    /** Asks for the identity string. The bike replies with a frame of its own, not an echo. */
    public data object ReadIdentity : BikeCommand() {
        override val opcode: Int get() = Opcodes.READ

        override fun payload(): ByteArray = byteArrayOf((Opcodes.IDENTITY ushr 8).toByte(), Opcodes.IDENTITY.toByte())
    }

    public companion object {
        /** Every command that can exist. */
        public fun all(): List<BikeCommand> =
            SpeedLimit.entries.map { SetSpeedLimit(it) } +
                listOf(SetLights(true), SetLights(false)) +
                AssistLevel.entries.map { SetAssistLevel(it) } +
                listOf(SetLocked(true), SetLocked(false)) +
                ReadIdentity
    }
}

/** The lock state's values, in a lock command and in the bike's answer. */
private const val LOCK_STATE_UNLOCKED: Byte = 0x01
private const val LOCK_STATE_LOCKED: Byte = 0x02

/**
 * Bytes that may be written to a bike. The only constructor takes a [BikeCommand] and derives the bytes
 * from it, so no caller (Kotlin or Java) can wrap other bytes. Only the Bluetooth adapter reads [bytes].
 */
public class OutgoingFrame internal constructor(
    public val command: BikeCommand,
) {
    private val bytesCopy = FrameCodec.encode(command.opcode, command.payload())

    public val bytes: ByteArray get() = bytesCopy.copyOf()

    /**
     * True when [frame] is the bike's answer to this frame, which is how it confirms it: the same payload under
     * the command's reply code. For most commands that is the frame repeated (an echo); a lock or an unlock is
     * answered with the lock state.
     */
    public fun isEchoedBy(frame: Frame): Boolean =
        frame.crcOk &&
            frame.command == command.replyOpcode &&
            FrameCodec.decode(bytesCopy).payload.contentEquals(frame.payload)

    override fun equals(other: Any?): Boolean = other is OutgoingFrame && bytesCopy.contentEquals(other.bytesCopy)

    override fun hashCode(): Int = bytesCopy.contentHashCode()

    /** Names the command only: the bytes reach logs through the sanitiser and the radio through ble-android. */
    override fun toString(): String = "OutgoingFrame($command)"
}
