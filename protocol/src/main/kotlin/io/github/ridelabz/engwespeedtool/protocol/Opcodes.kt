package io.github.ridelabz.engwespeedtool.protocol

/** Command codes seen on the wire. Only [BikeCommand] decides which of them may be sent. */
internal object Opcodes {
    const val SPEED_LIMIT: Int = 0x2A65
    const val LIGHTS: Int = 0x0597
    const val ASSIST_LEVEL: Int = 0x1009
    const val LOCK: Int = 0x0108

    /** The lock state: how the bike answers a lock or an unlock. */
    const val LOCK_STATE: Int = 0x0109
    const val READ: Int = 0x2411
    const val IDENTITY: Int = 0xFF01
    const val STATUS: Int = 0x2A63
    const val WHEEL_MOTION: Int = 0x2A64
}
