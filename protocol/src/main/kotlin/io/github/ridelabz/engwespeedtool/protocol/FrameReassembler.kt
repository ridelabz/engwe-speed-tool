package io.github.ridelabz.engwespeedtool.protocol

/**
 * Reassembles frames from notification values. A frame may be split across several values and one
 * value may carry several frames or stray bytes. Nothing is dropped silently: every byte is either
 * emitted in an event or still [pending].
 *
 * Not thread-safe; use one instance per stream.
 */
public class FrameReassembler(
    private val maxPayload: Int = DEFAULT_MAX_PAYLOAD,
) {
    init {
        require(maxPayload in 0..FrameCodec.MAX_PAYLOAD) { "maxPayload out of range" }
    }

    private var buf = ByteArray(256)
    private var len = 0

    public val pending: ByteArray get() = buf.copyOf(len)

    public fun feed(chunk: ByteArray): List<StreamEvent> {
        append(chunk)
        val events = mutableListOf<StreamEvent>()
        while (len > 0) {
            val start = indexOfHeader(0)
            if (start < 0) {
                // Keep a trailing 0x45: it may be the first half of a header split across values.
                val keep = if (buf[len - 1] == FrameCodec.HEADER_0) 1 else 0
                val cut = len - keep
                if (cut > 0) events += StreamEvent.Unframed(take(cut))
                break
            }
            if (start > 0) events += StreamEvent.Unframed(take(start))
            if (len < FrameCodec.PREFIX_LENGTH) break
            val length = FrameCodec.u16(buf, 4)
            if (length > maxPayload) {
                events += StreamEvent.Malformed(take(2), MalformedReason.LENGTH_OVER_LIMIT)
                continue
            }
            val total = FrameCodec.OVERHEAD + length
            if (len < total) {
                val later = laterValidFrame() ?: break
                events += StreamEvent.Malformed(take(later), MalformedReason.TRUNCATED)
                continue
            }
            if (buf[total - 2] != FrameCodec.FOOTER_0 || buf[total - 1] != FrameCodec.FOOTER_1) {
                val next = indexOfHeader(2, total + 1)
                events +=
                    StreamEvent.Malformed(take(if (next < 0) total else next), MalformedReason.FOOTER_MISMATCH)
                continue
            }
            val frame = FrameCodec.decode(buf.copyOf(total))
            if (!frame.crcOk) {
                // e.g. a truncated frame followed by a value that happens to end in a footer:
                // don't let it swallow complete, valid frames inside it.
                val inner = laterValidFrame(limit = total)
                if (inner != null) {
                    events += StreamEvent.Malformed(take(inner), MalformedReason.BAD_CRC_WITH_INNER_FRAME)
                    continue
                }
            }
            take(total)
            events += StreamEvent.Decoded(frame)
        }
        return events
    }

    /** Emits buffered bytes as [StreamEvent.Incomplete]; call when the stream ends (e.g. on disconnect). */
    public fun flush(): List<StreamEvent> = if (len == 0) emptyList() else listOf(StreamEvent.Incomplete(take(len)))

    /** Offset of a later header (before [limit]) that starts a complete frame with a valid CRC. */
    private fun laterValidFrame(limit: Int = len): Int? {
        var pos = indexOfHeader(2)
        while (pos in 0 until limit) {
            if (len - pos >= FrameCodec.OVERHEAD) {
                val length = FrameCodec.u16(buf, pos + 4)
                val end = pos + FrameCodec.OVERHEAD + length
                if (length <= maxPayload && end <= len) {
                    val ok =
                        try {
                            FrameCodec.decode(buf.copyOfRange(pos, end), requireValidCrc = true)
                            true
                        } catch (_: FrameException) {
                            false
                        }
                    if (ok) return pos
                }
            }
            pos = indexOfHeader(pos + 1)
        }
        return null
    }

    private fun indexOfHeader(
        from: Int,
        until: Int = len,
    ): Int {
        val last = minOf(until, len) - 2
        for (i in from..last) {
            if (buf[i] == FrameCodec.HEADER_0 && buf[i + 1] == FrameCodec.HEADER_1) return i
        }
        return -1
    }

    private fun append(chunk: ByteArray) {
        if (len + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + chunk.size))
        chunk.copyInto(buf, len)
        len += chunk.size
    }

    private fun take(n: Int): ByteArray {
        val out = buf.copyOf(n)
        buf.copyInto(buf, 0, n, len)
        // Bytes handed out don't linger in the buffer (an identity reply carries a serial).
        buf.fill(0, len - n, len)
        len -= n
        return out
    }

    public companion object {
        public const val DEFAULT_MAX_PAYLOAD: Int = 1024
    }
}
