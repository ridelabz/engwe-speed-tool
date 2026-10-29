package io.github.ridelabz.engwespeedtool.protocol

import io.github.ridelabz.engwespeedtool.protocol.Vectors.str
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameCodecTest {
    @Test
    fun `crc matches the CRC-16 MODBUS check value`() {
        val vector = Vectors.list("crc").single()
        val crc = Crc16Modbus.compute(vector.str("ascii").toByteArray(Charsets.US_ASCII))
        assertEquals(vector.str("crc16_modbus"), "%04X".format(crc))
        assertEquals(0x4B37, crc)
    }

    @Test
    fun `every captured command frame decodes and re-encodes byte for byte`() {
        for (vector in Vectors.list("commands")) {
            val bytes = Hex.decode(vector.str("frame"))
            val frame = FrameCodec.decode(bytes, requireValidCrc = true)
            assertArrayEquals(bytes, FrameCodec.encode(frame.command, frame.payload), vector.str("name"))
        }
    }

    @Test
    fun `known command frames have the expected fields`() {
        val set32 = FrameCodec.decode(Vectors.commandFrame("speed_limit_32"))
        assertEquals(0x2A65, set32.command)
        assertEquals(1, set32.payloadSize)
        assertEquals(32, set32.payloadByte(0))
        assertEquals("456E2A6500012042237765", Hex.encode(FrameCodec.encode(0x2A65, byteArrayOf(0x20))))
    }

    @Test
    fun `bike frames from captures decode with a valid CRC`() {
        val frames =
            Vectors.list("echoes").flatMap { listOf(it.str("sent"), it.str("reply")) } +
                Vectors.list("not_echoes").map { it.str("reply") } +
                Vectors.list("replies").flatMap { listOf(it.str("sent"), it.str("reply")) } +
                Vectors.list("other_bike_frames").map { it.str("frame") } +
                Vectors.obj("identity_reply").str("frame")
        for (hex in frames) {
            assertTrue(FrameCodec.decode(Hex.decode(hex)).crcOk, hex)
        }
    }

    @Test
    fun `echo comparison uses command and payload`() {
        for (vector in Vectors.list("echoes")) {
            val sent = FrameCodec.decode(Hex.decode(vector.str("sent")))
            assertTrue(sent.sameCommandAndPayload(FrameCodec.decode(Hex.decode(vector.str("reply")))))
        }
        for (vector in Vectors.list("not_echoes")) {
            val sent = FrameCodec.decode(Hex.decode(vector.str("sent")))
            assertFalse(sent.sameCommandAndPayload(FrameCodec.decode(Hex.decode(vector.str("reply")))))
        }
    }

    @Test
    fun `empty payload frames are supported`() {
        val bytes = FrameCodec.encode(0x1234)
        val frame = FrameCodec.decode(bytes, requireValidCrc = true)
        assertEquals(0, frame.payloadSize)
        assertEquals(FrameCodec.OVERHEAD, bytes.size)
    }

    @Test
    fun `crc mismatch is kept and flagged unless a valid crc is required`() {
        val bytes = Vectors.commandFrame("speed_limit_25")
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0xFF).toByte()
        val frame = FrameCodec.decode(bytes)
        assertFalse(frame.crcOk)
        assertThrows<FrameException> { FrameCodec.decode(bytes, requireValidCrc = true) }
    }

    @Test
    fun `structural errors are rejected`() {
        val good = Vectors.commandFrame("lights_on")
        val cases =
            mapOf(
                "too short" to good.copyOf(9),
                "bad header" to good.copyOf().also { it[0] = 0x00 },
                "bad footer" to good.copyOf().also { it[it.size - 1] = 0x00 },
                "length mismatch" to good + byteArrayOf(0x00),
            )
        for ((name, bytes) in cases) {
            assertThrows<FrameException>(name) { FrameCodec.decode(bytes) }
        }
    }

    @Test
    fun `encode validates its arguments`() {
        assertThrows<IllegalArgumentException> { FrameCodec.encode(0x10000) }
        assertThrows<IllegalArgumentException> { FrameCodec.encode(-1) }
        assertThrows<IllegalArgumentException> { FrameCodec.encode(0x0001, ByteArray(FrameCodec.MAX_PAYLOAD + 1)) }
    }

    @Test
    fun `decoded frames are defensive copies`() {
        val bytes = Vectors.commandFrame("lights_on")
        val frame = FrameCodec.decode(bytes)
        bytes[6] = 0x00
        frame.payload[0] = 0x00
        assertEquals(0xFF, frame.payloadByte(0))
    }

    @Test
    fun `toString never shows the payload`() {
        val identity = FrameCodec.decode(Hex.decode(Vectors.obj("identity_reply").str("frame")))
        val text = identity.toString()
        assertFalse(text.contains("FAKESERIAL") || text.contains(Hex.encode("FAKESERIAL".toByteArray())), text)
        assertEquals("Frame(command=FF01, payloadSize=54, crcOk=true)", text)
    }

    @Test
    fun `hex round trips and rejects invalid input`() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007F80FF", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("00 7f 80 FF"))
        assertThrows<IllegalArgumentException> { Hex.decode("ABC") }
        assertThrows<IllegalArgumentException> { Hex.decode("ZZ") }
        assertThrows<IllegalArgumentException> { Hex.decode("\u0661\u0662") } // Arabic-Indic digits
        assertThrows<IllegalArgumentException> { Hex.decode("\uFF10\uFF11") } // fullwidth digits
    }
}
