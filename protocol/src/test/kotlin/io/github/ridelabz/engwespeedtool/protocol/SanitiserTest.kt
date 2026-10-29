package io.github.ridelabz.engwespeedtool.protocol

import io.github.ridelabz.engwespeedtool.protocol.Vectors.str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class SanitiserTest {
    private val rnd = Random(4242)
    private val model = "ENGWE_Omni_ebike1100EU317"
    private val statusPayload = Hex.decode(Vectors.list("status_frames")[0].str("payload"))

    private fun randomSerial(alphabet: List<Char> = ('A'..'Z') + ('0'..'9')): String =
        String(
            CharArray(19) {
                alphabet[rnd.nextInt(alphabet.size)]
            },
        )

    private fun identityPayload(serial: String) =
        model.toByteArray() + ByteArray(3) + "1".toByteArray() + ByteArray(5) + serial.toByteArray() + ByteArray(1)

    private fun identityReply(serial: String) = FrameCodec.decode(FrameCodec.encode(0xFF01, identityPayload(serial)))

    private fun assertNoSerial(
        logged: List<Loggable>,
        serial: String,
    ) {
        val text = logged.mapNotNull { it.hex }.joinToString(" ")
        for (part in serial.windowed(
            4,
        )) {
            assertFalse(text.contains(Hex.encode(part.toByteArray())), "serial fragment $part")
        }
    }

    @Test
    fun `identity replies keep the model and mask the serial and crc`() {
        repeat(500) {
            val serial = randomSerial()
            val frame = identityReply(serial)
            val logged = Sanitiser.incoming(frame)
            assertTrue(logged.masked)
            assertNoSerial(listOf(logged), serial)
            val bytes = Hex.decode(checkNotNull(logged.hex))
            assertEquals(frame.raw.size, bytes.size)
            // The masked CRC no longer matches, so re-frame the masked payload to read the model back.
            val reframed = FrameCodec.decode(FrameCodec.encode(0xFF01, FrameCodec.decode(bytes).payload))
            assertEquals(model, IdentityParser.modelOf(reframed))
            val tail = bytes.copyOfRange(6 + model.length + 1, bytes.size - 2)
            assertTrue(tail.all { it == Sanitiser.MASK_BYTE }, "everything after the model and the CRC is masked")
        }
    }

    @Test
    fun `identity replies with a bad crc or no clean model are masked completely`() {
        val serial = randomSerial()
        val badCrc = identityReply(serial).raw.also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        val glued = FrameCodec.encode(0xFF01, (model + serial).toByteArray())
        for (raw in listOf(badCrc, glued)) {
            val bytes = Hex.decode(checkNotNull(Sanitiser.incoming(FrameCodec.decode(raw)).hex))
            assertTrue(bytes.copyOfRange(6, bytes.size - 2).all { it == Sanitiser.MASK_BYTE })
        }
    }

    @Test
    fun `a truncated status frame followed by an identity reply leaks nothing`() {
        // Serials containing the footer bytes "we" or header bytes "En" can make the reassembler cut a fake frame.
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        repeat(2000) {
            val base = randomSerial(alphabet).toCharArray()
            val at = rnd.nextInt(0, 17)
            val pair = if (rnd.nextBoolean()) "we" else "En"
            base[at] = pair[0]
            base[at + 1] = pair[1]
            val serial = String(base)
            val status = FrameCodec.encode(0x2A63, statusPayload)
            val r = FrameReassembler()
            val events = r.feed(status.copyOf(20)) + r.feed(identityReply(serial).raw) + r.flush()
            assertNoSerial(events.map { Sanitiser.incoming(it) }, serial)
        }
    }

    @Test
    fun `only valid frames of known commands and lengths are logged in full`() {
        val status = FrameCodec.decode(FrameCodec.encode(0x2A63, statusPayload))
        assertEquals(Hex.encode(status.raw), Sanitiser.incoming(status).hex)
        for (vector in Vectors.list("echoes") + Vectors.list("replies") + Vectors.list("other_bike_frames")) {
            val hex = vector["reply"]?.let { vector.str("reply") } ?: vector.str("frame")
            assertEquals(hex, Sanitiser.incoming(FrameCodec.decode(Hex.decode(hex))).hex)
        }
        val withheld =
            listOf(
                FrameCodec.encode(0xFF04, "SECRETSERIAL".toByteArray()),
                FrameCodec.encode(0x2A63, statusPayload.copyOf(40)),
                FrameCodec.encode(0x2A65, "AB".toByteArray()),
                FrameCodec.encode(0x1009, "AB".toByteArray()),
                FrameCodec.encode(0x1009, ByteArray(0)),
                FrameCodec.encode(0x0109, "AB".toByteArray()),
                FrameCodec.encode(0x0109, "SECRETSERIAL".toByteArray()),
                FrameCodec.encode(0x0108, "SECRETSERIAL".toByteArray()),
                FrameCodec.encode(0x0109, byteArrayOf(2)).also {
                    it[it.size - 3] =
                        (it[it.size - 3].toInt() xor 1).toByte()
                },
                FrameCodec.encode(0x1009, byteArrayOf(3)).also {
                    it[it.size - 3] =
                        (it[it.size - 3].toInt() xor 1).toByte()
                },
                FrameCodec.encode(0x2A63, statusPayload).also {
                    it[it.size - 3] =
                        (it[it.size - 3].toInt() xor 1).toByte()
                },
            )
        for (raw in withheld) {
            val logged = Sanitiser.incoming(FrameCodec.decode(raw))
            assertNull(logged.hex)
            assertEquals(raw.size, logged.size)
        }
        assertEquals("other", Sanitiser.commandLabel(FrameCodec.decode(withheld[0])))
        assertEquals("2A63", Sanitiser.commandLabel(status))
        assertEquals("1009", Sanitiser.commandLabel(FrameCodec.decode(withheld[3])), "known, logged by size")
    }

    @Test
    fun `bytes outside complete frames are logged by size only`() {
        val r = FrameReassembler()
        val reply = identityReply(randomSerial()).raw
        val events = r.feed(reply.copyOfRange(30, 60)) + r.feed(reply.copyOfRange(0, 45)) + r.flush()
        assertTrue(events.isNotEmpty())
        for (event in events) {
            val logged = Sanitiser.incoming(event)
            assertNull(logged.hex)
            assertTrue(logged.masked)
        }
    }

    @Test
    fun `outgoing frames are logged in full`() {
        for (command in BikeCommand.all()) {
            val frame = command.toFrame()
            assertEquals(Hex.encode(frame.bytes), Sanitiser.outgoing(frame).hex)
        }
    }

    @Test
    fun `advertised names are reduced`() {
        assertEquals("ENGWE-**", Sanitiser.advertisedName("ENGWE-Z9"))
        assertEquals("ENGWE-**", Sanitiser.advertisedName("ENGWE"))
        assertEquals("**", Sanitiser.advertisedName("Someone's phone"))
        assertEquals("**", Sanitiser.advertisedName(null))
    }

    @Test
    fun `free text loses addresses in every form`() {
        repeat(300) {
            val bytes = List(6) { "%02X".format(rnd.nextInt(256)) }
            val forms =
                listOf(
                    bytes.joinToString(":"),
                    bytes.joinToString("-"),
                    bytes.joinToString("."),
                    bytes.joinToString(" "),
                    bytes.joinToString(""),
                    bytes.reversed().joinToString(":"),
                    "XX:XX:XX:XX:" + bytes.takeLast(2).joinToString(":"),
                    bytes.chunked(2).joinToString(".") { it.joinToString("") },
                ).flatMap { listOf(it, it.lowercase()) }
            for (form in forms) {
                for (wrapped in listOf("device $form failed", "addr_$form", "x$form", "${form}y")) {
                    val clean = Sanitiser.text(wrapped)
                    assertFalse(clean.contains(form, ignoreCase = true), clean)
                    assertFalse(clean.contains(bytes.takeLast(2).joinToString(":"), ignoreCase = true), clean)
                }
            }
        }
    }

    @Test
    fun `free text loses ids, name suffixes and e-mail addresses`() {
        val serial = randomSerial()
        assertEquals("id <id> end", Sanitiser.text("id $serial end"))
        assertEquals("android <hex>", Sanitiser.text("android 9774d56d682e549c"))
        assertEquals("frame <hex>", Sanitiser.text("frame 456E2A6500012042237765"))
        for (name in listOf("ENGWE-Z9", "ENGWE_B7", "xENGWE-C3", "engwe-b1")) {
            assertFalse(Sanitiser.text("bike $name gone").contains(name.takeLast(2), ignoreCase = false), name)
        }
        assertEquals("mail <e-mail> now", Sanitiser.text("mail some.one+x@example.org now"))
        assertEquals("speed 32 km/h, ENGWE Speed Tool", Sanitiser.text("speed 32 km/h, ENGWE Speed Tool"))
    }

    @Test
    fun `uuids keep a Bluetooth number, and only the first 8 digits of any other`() {
        assertEquals("0x1820", Sanitiser.uuid(UUID.fromString("00001820-0000-1000-8000-00805f9b34fb")))
        assertEquals("0x2A63", Sanitiser.uuid(UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")))
        // Not a 16-bit number on the Bluetooth base: the rest may be chosen per device.
        assertEquals(
            "00001820-****-****-****-************",
            Sanitiser.uuid(UUID.fromString("00001820-0000-1000-8000-4f2a91c07e3b")),
        )
        assertEquals(
            "12345678-****-****-****-************",
            Sanitiser.uuid(UUID.fromString("12345678-0000-1000-8000-00805f9b34fb")),
        )
        // An address in the last 12 digits, or spread over the middle groups.
        assertEquals(
            "6e400001-****-****-****-************",
            Sanitiser.uuid(UUID.fromString("6e400001-b5a3-f393-e0a9-4f2a91c07e3b")),
        )
        assertEquals(
            "6e400003-****-****-****-************",
            Sanitiser.uuid(UUID.fromString("6e400003-4f2a-91c0-7e3b-e50e24dcca9e")),
        )
    }
}
