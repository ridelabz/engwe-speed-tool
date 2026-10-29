package io.github.ridelabz.engwespeedtool.protocol

/** One piece of a byte stream after reassembly. Every input byte ends up in exactly one event. */
public sealed class StreamEvent(
    bytes: ByteArray,
) {
    private val bytesCopy = bytes.copyOf()

    public val data: ByteArray get() = bytesCopy.copyOf()

    public val size: Int get() = bytesCopy.size

    /** Short name used in logs: `frame`, `unframed`, `malformed` or `incomplete`. */
    public abstract val kind: String

    /** A complete, delimited frame (its CRC may still be wrong; see [Frame.crcOk]). */
    public class Decoded internal constructor(
        public val frame: Frame,
    ) : StreamEvent(frame.raw) {
        override val kind: String get() = "frame"
    }

    /** Bytes outside any frame. */
    public class Unframed internal constructor(
        bytes: ByteArray,
    ) : StreamEvent(bytes) {
        override val kind: String get() = "unframed"
    }

    /** Bytes that started like a frame but could not be one. */
    public class Malformed internal constructor(
        bytes: ByteArray,
        public val reason: MalformedReason,
    ) : StreamEvent(bytes) {
        override val kind: String get() = "malformed"
    }

    /** Bytes still buffered when the stream ended. */
    public class Incomplete internal constructor(
        bytes: ByteArray,
    ) : StreamEvent(bytes) {
        override val kind: String get() = "incomplete"
    }
}

/** Why bytes were malformed. Fixed codes only: values read from the stream could be parts of identifiers. */
public enum class MalformedReason(
    public val code: String,
) {
    LENGTH_OVER_LIMIT("length_over_limit"),
    TRUNCATED("truncated"),
    FOOTER_MISMATCH("footer_mismatch"),
    BAD_CRC_WITH_INNER_FRAME("bad_crc_with_inner_frame"),
}
