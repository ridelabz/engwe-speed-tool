package io.github.ridelabz.engwespeedtool.protocol

import io.github.ridelabz.engwespeedtool.protocol.Vectors.str
import io.github.ridelabz.engwespeedtool.protocol.Vectors.strings
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class FrameReassemblerTest {
    private val s25 = Vectors.commandFrame("speed_limit_25")
    private val s32 = Vectors.commandFrame("speed_limit_32")
    private val status = FrameCodec.encode(0x2A63, Hex.decode(Vectors.list("status_frames").first().str("payload")))

    private fun feedAll(
        chunks: List<ByteArray>,
        r: FrameReassembler = FrameReassembler(),
    ): List<StreamEvent> = chunks.flatMap { r.feed(it) }

    private fun kinds(events: List<StreamEvent>) = events.map { it.kind }

    @Test
    fun `single complete frame`() {
        val r = FrameReassembler()
        val events = r.feed(s25)
        assertEquals(listOf("frame"), kinds(events))
        assertEquals(0, r.pending.size)
    }

    @Test
    fun `frame split at every position`() {
        for (split in 1 until status.size) {
            val r = FrameReassembler()
            assertTrue(r.feed(status.copyOfRange(0, split)).isEmpty(), "split $split")
            val second = r.feed(status.copyOfRange(split, status.size))
            assertEquals(listOf("frame"), kinds(second), "split $split")
            assertArrayEquals(status, second.single().data)
        }
    }

    @Test
    fun `byte by byte input`() {
        val events = feedAll(status.map { byteArrayOf(it) })
        assertEquals(listOf("frame"), kinds(events))
    }

    @Test
    fun `concatenated frames in one value`() {
        val events = feedAll(listOf(s25 + status + s32))
        assertEquals(listOf("frame", "frame", "frame"), kinds(events))
        assertEquals(listOf(0x2A65, 0x2A63, 0x2A65), events.map { (it as StreamEvent.Decoded).frame.command })
    }

    @Test
    fun `noise around a frame is kept as unframed`() {
        val events = feedAll(listOf(byteArrayOf(1, 2) + s25 + byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
        assertEquals(listOf("unframed", "frame", "unframed"), kinds(events))
        assertArrayEquals(byteArrayOf(1, 2), events[0].data)
    }

    @Test
    fun `header split across values is not lost`() {
        val r = FrameReassembler()
        assertEquals(listOf("unframed"), kinds(r.feed(byteArrayOf(0x99.toByte(), s25[0]))))
        assertArrayEquals(byteArrayOf(s25[0]), r.pending)
        assertEquals(listOf("frame"), kinds(r.feed(s25.copyOfRange(1, s25.size))))
    }

    @Test
    fun `bad crc frame is kept and flagged`() {
        val bad = s25.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0xFF).toByte() }
        val events = feedAll(listOf(bad + s32))
        assertEquals(listOf("frame", "frame"), kinds(events))
        assertFalse((events[0] as StreamEvent.Decoded).frame.crcOk)
        assertTrue((events[1] as StreamEvent.Decoded).frame.crcOk)
    }

    @Test
    fun `footer mismatch resyncs to the next frame`() {
        val broken = s25.copyOf().also { it[it.size - 1] = 0 }
        val events = feedAll(listOf(broken + s32))
        assertEquals(listOf("malformed", "frame"), kinds(events))
        assertArrayEquals(broken, events[0].data)
    }

    @Test
    fun `lost tail recovers on a later valid frame`() {
        val big = FrameCodec.encode(0x0001, ByteArray(30))
        val events = feedAll(listOf(big.copyOf(12), s25))
        assertEquals(listOf("malformed", "frame"), kinds(events))
    }

    @Test
    fun `oversized declared length is rejected`() {
        val junk = byteArrayOf(0x45, 0x6E, 0x00, 0x01, 0xFF.toByte(), 0xFF.toByte())
        val events = feedAll(listOf(junk + s25), FrameReassembler(maxPayload = 64))
        assertEquals("malformed", events.first().kind)
        assertEquals("frame", events.last().kind)
    }

    @Test
    fun `a bad-crc frame does not swallow valid frames inside it`() {
        // A status frame cut to 20 bytes, then one value of exactly the missing 44 bytes: four echoes.
        val echoes = s25 + s32 + s25 + s32
        assertEquals(status.size - 20, echoes.size)
        val events = feedAll(listOf(status.copyOf(20), echoes))
        assertEquals(listOf("malformed", "frame", "frame", "frame", "frame"), kinds(events))
        assertEquals(MalformedReason.BAD_CRC_WITH_INNER_FRAME, (events[0] as StreamEvent.Malformed).reason)
        assertTrue(events.drop(1).all { (it as StreamEvent.Decoded).frame.crcOk })
    }

    @Test
    fun `the payload limit is validated`() {
        assertThrows<IllegalArgumentException> { FrameReassembler(maxPayload = -1) }
        assertThrows<IllegalArgumentException> { FrameReassembler(maxPayload = 0x10000) }
    }

    @Test
    fun `flush reports the incomplete tail once`() {
        val r = FrameReassembler()
        r.feed(s32.copyOf(5))
        assertEquals(listOf("incomplete"), kinds(r.flush()))
        assertTrue(r.flush().isEmpty())
    }

    @Test
    fun `captured stream cases`() {
        for (case in Vectors.list("stream_cases")) {
            val r = FrameReassembler()
            val events = case.strings("chunks").flatMap { r.feed(Hex.decode(it)) } + r.flush()
            assertEquals(case.strings("expected_kinds"), kinds(events), case.str("name"))
        }
    }

    @Test
    fun `every input byte is accounted for under random chunking`() {
        val rnd = Random(20261014)
        repeat(200) { round ->
            val parts = mutableListOf<ByteArray>()
            var complete = 0
            repeat(rnd.nextInt(1, 6)) {
                when (rnd.nextInt(4)) {
                    0 -> parts += rnd.nextBytes(rnd.nextInt(0, 8))
                    1 -> parts += status.also { complete++ }
                    2 -> parts += s25.also { complete++ }
                    else -> parts += s32.copyOf(rnd.nextInt(1, s32.size))
                }
            }
            val stream = parts.fold(ByteArray(0)) { acc, p -> acc + p }
            val r = FrameReassembler()
            val events = mutableListOf<StreamEvent>()
            var i = 0
            while (i < stream.size) {
                val n = rnd.nextInt(1, 25)
                events += r.feed(stream.copyOfRange(i, minOf(stream.size, i + n)))
                i += n
            }
            events += r.flush()
            val joined = events.fold(ByteArray(0)) { acc, e -> acc + e.data }
            assertArrayEquals(stream, joined, "round $round")
            val valid = events.count { it is StreamEvent.Decoded && it.frame.crcOk }
            assertEquals(complete, valid, "every complete frame is recovered, round $round")
        }
    }
}
