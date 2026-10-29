package io.github.ridelabz.engwespeedtool.protocol

/** CRC-16/MODBUS: initial value 0xFFFF, reflected polynomial 0xA001. */
public object Crc16Modbus {
    public fun compute(
        data: ByteArray,
        fromIndex: Int = 0,
        toIndex: Int = data.size,
    ): Int {
        var crc = 0xFFFF
        for (i in fromIndex until toIndex) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc
    }
}
