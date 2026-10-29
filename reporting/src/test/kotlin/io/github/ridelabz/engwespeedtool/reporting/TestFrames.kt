package io.github.ridelabz.engwespeedtool.reporting

import io.github.ridelabz.engwespeedtool.protocol.Crc16Modbus

/** Builds frame bytes for tests (the protocol module keeps its encoder internal). */
internal object TestFrames {
    fun encode(
        command: Int,
        payload: ByteArray,
    ): ByteArray {
        val body =
            byteArrayOf(
                (command ushr 8).toByte(),
                command.toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ) +
                payload
        val crc = Crc16Modbus.compute(body)
        return byteArrayOf(0x45, 0x6E) + body + byteArrayOf((crc ushr 8).toByte(), crc.toByte(), 0x77, 0x65)
    }
}
