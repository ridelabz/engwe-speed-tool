package io.github.ridelabz.engwespeedtool.protocol

/** Upper-case hex encoding for logs, test vectors and diagnostics. */
public object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    public fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /** Decodes hex digits; spaces are ignored. Throws [IllegalArgumentException] on anything else. */
    public fun decode(text: String): ByteArray {
        val digits = text.filterNot { it == ' ' }
        require(digits.length % 2 == 0) { "odd number of hex digits" }
        return ByteArray(digits.length / 2) { i ->
            val hi = asciiHexValue(digits[2 * i])
            val lo = asciiHexValue(digits[2 * i + 1])
            require(hi >= 0 && lo >= 0) { "not a hex digit at position ${2 * i}" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun asciiHexValue(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
}
