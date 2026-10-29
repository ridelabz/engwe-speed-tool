package io.github.ridelabz.engwespeedtool.protocol

import io.github.ridelabz.engwespeedtool.protocol.Vectors.str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val PKG = "io/github/ridelabz/engwespeedtool/protocol"

class BikeCommandTest {
    private val allFrames = BikeCommand.all().map { it.toFrame() }

    @Test
    fun `the set of buildable frames is exactly the allowed frames of the test vectors`() {
        // 45 km/h's was computed the same way (one byte, km/h) before the bike first echoed it; the assist
        // levels 2 to 5 were computed like the captured 0 and 1 before they were set on the bike; the lock and
        // the unlock are as captured from another app.
        val expected =
            listOf(
                "speed_limit_16",
                "speed_limit_25",
                "speed_limit_32",
                "speed_limit_45",
                "lights_on",
                "lights_off",
                "assist_level_0",
                "assist_level_1",
                "assist_level_2",
                "assist_level_3",
                "assist_level_4",
                "assist_level_5",
                "lock",
                "unlock",
                "read_identity",
            ).map { Hex.encode(Vectors.commandFrame(it)) }
                .toSet()
        assertEquals(expected, allFrames.map { Hex.encode(it.bytes) }.toSet())
        assertEquals(15, allFrames.size)
        assertEquals("456E0108000102F19B7765", Hex.encode(BikeCommand.SetLocked(true).toFrame().bytes))
        assertEquals("456E0108000101F0DB7765", Hex.encode(BikeCommand.SetLocked(false).toFrame().bytes))
        assertEquals(
            listOf(
                "456E1009000100CFE77765",
                "456E10090001010F267765",
                "456E10090001020E667765",
                "456E1009000103CEA77765",
                "456E10090001040CE67765",
                "456E1009000105CC277765",
            ),
            AssistLevel.entries.map { Hex.encode(BikeCommand.SetAssistLevel(it).toFrame().bytes) },
        )
    }

    @Test
    fun `blocked commands can never be built`() {
        val blocked = setOf(0x2A66, 0x8049, 0x0107, 0x010C, 0x08C3)
        for (frame in allFrames.map { FrameCodec.decode(it.bytes, requireValidCrc = true) }) {
            assertFalse(frame.command in blocked, frame.toString())
            if (frame.command == 0x2A65) {
                assertEquals(1, frame.payloadSize, "speed frames always carry one value")
                assertTrue(frame.payloadByte(0) in setOf(16, 25, 32, 45), frame.toString())
            }
            if (frame.command == 0x1009) {
                assertEquals(1, frame.payloadSize, "assist frames always carry one level")
                assertTrue(frame.payloadByte(0) in 0..5, frame.toString())
            }
            if (frame.command == 0x2411) {
                assertEquals("FF01", Hex.encode(frame.payload), "the only read is the identity read")
            }
            if (frame.command == 0x0108) {
                assertEquals(1, frame.payloadSize, "a lock frame carries one value")
                assertTrue(frame.payloadByte(0) in setOf(1, 2), "unlock or lock only: ${frame.payloadByte(0)}")
            }
        }
    }

    @Test
    fun `speed limits are a closed set without zero or free values`() {
        assertEquals(listOf(16, 25, 32, 45), SpeedLimit.entries.map { it.kmh })
        for (kmh in listOf(0, 1, 20, 26, 33, 44, 46, 255)) assertNull(SpeedLimit.ofKmh(kmh), "$kmh")
        assertEquals(SpeedLimit.KMH_25, SpeedLimit.BIKE_DEFAULT)
        assertEquals(listOf(false, false, true, true), SpeedLimit.entries.map { it.isAboveLegalLimit })
    }

    @Test
    fun `assist levels are a closed set from 0 to 5`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), AssistLevel.entries.map { it.level })
        for (level in listOf(-1, 6, 9, 16, 255)) assertNull(AssistLevel.of(level), "$level")
        assertEquals(AssistLevel.MAX, AssistLevel.entries.maxOf { it.level })
        val assist = allFrames.map { FrameCodec.decode(it.bytes) }.filter { it.command == 0x1009 }
        assertEquals((0..5).toList(), assist.map { it.payloadByte(0) })
        assertTrue(assist.all { it.payloadSize == 1 }, "no other payload length")
    }

    @Test
    fun `no constructor accepts bytes, from Kotlin or Java`() {
        val constructors = OutgoingFrame::class.java.declaredConstructors
        assertEquals(1, constructors.size)
        assertEquals(listOf(BikeCommand::class.java), constructors.single().parameterTypes.toList())
    }

    @Test
    fun `the command hierarchy is closed and all() covers it`() {
        val permitted =
            BikeCommand::class.java.permittedSubclasses
                .map { it.simpleName }
                .toSet()
        assertEquals(setOf("SetSpeedLimit", "SetLights", "SetAssistLevel", "SetLocked", "ReadIdentity"), permitted)
        assertEquals(permitted, BikeCommand.all().map { it::class.java.simpleName }.toSet())
    }

    @Test
    fun `the public API offers no other way to create outgoing frames`() {
        // Kotlin `internal` is the guarantee; the reviewed API dump proves nothing else is exposed.
        val api = File("api/protocol.api").readText()
        val outgoing = api.substringAfter("class $PKG/OutgoingFrame ").substringBefore("}")
        assertFalse("<init>" in outgoing, "OutgoingFrame must have no public constructor")
        val producers = api.lines().map { it.trim() }.filter { it.endsWith(")L$PKG/OutgoingFrame;") }
        assertEquals(listOf("public final fun toFrame ()L$PKG/OutgoingFrame;"), producers)
        // Encoding is internal (still callable from Java as a mangled method, but it only returns bytes).
        // The guarantee is that nothing but OutgoingFrame can be written, and OutgoingFrame can't carry other bytes.
        val codec = api.substringAfter("class $PKG/FrameCodec ").substringBefore("}")
        assertFalse(codec.contains(" encode "), "frame encoding is not part of the public Kotlin API")
    }

    @Test
    fun `echoes confirm only the frame they repeat`() {
        val set32 = BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32).toFrame()
        for (vector in Vectors.list("not_echoes")) {
            assertFalse(set32.isEchoedBy(FrameCodec.decode(Hex.decode(vector.str("reply")))))
        }
        assertTrue(set32.isEchoedBy(FrameCodec.decode(set32.bytes)))
        assertFalse(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25).toFrame().isEchoedBy(FrameCodec.decode(set32.bytes)))
        val assist3 = BikeCommand.SetAssistLevel(AssistLevel.LEVEL_3).toFrame()
        assertTrue(assist3.isEchoedBy(FrameCodec.decode(assist3.bytes)))
        assertFalse(
            assist3.isEchoedBy(FrameCodec.decode(BikeCommand.SetAssistLevel(AssistLevel.LEVEL_2).toFrame().bytes)),
        )
        for (echo in Vectors.list("echoes").filter { it.str("sent").startsWith("456E1009") }) {
            val sent = FrameCodec.decode(Hex.decode(echo.str("sent")))
            val frame = BikeCommand.SetAssistLevel(checkNotNull(AssistLevel.of(sent.payloadByte(0)))).toFrame()
            assertTrue(frame.isEchoedBy(FrameCodec.decode(Hex.decode(echo.str("reply")))), echo.toString())
        }
        val corrupted = set32.bytes.also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertFalse(set32.isEchoedBy(FrameCodec.decode(corrupted)), "a frame with a bad CRC confirms nothing")
    }

    @Test
    fun `a lock or an unlock is confirmed by the lock state with its value, never by its own frame repeated`() {
        val lock = BikeCommand.SetLocked(true).toFrame()
        val unlock = BikeCommand.SetLocked(false).toFrame()
        for (reply in Vectors.list("replies")) {
            val sent = Hex.encode(lock.bytes).takeIf { it == reply.str("sent") }?.let { lock } ?: unlock
            assertEquals(reply.str("sent"), Hex.encode(sent.bytes))
            val answer = FrameCodec.decode(Hex.decode(reply.str("reply")))
            assertTrue(sent.isEchoedBy(answer), reply.toString())
            assertFalse((if (sent === lock) unlock else lock).isEchoedBy(answer), "the other value")
        }
        assertFalse(lock.isEchoedBy(FrameCodec.decode(lock.bytes)), "its own frame repeated isn't the answer")
        val set32 = BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32).toFrame()
        for (reply in Vectors.list("replies")) {
            assertFalse(set32.isEchoedBy(FrameCodec.decode(Hex.decode(reply.str("reply")))))
        }
        val answer = Hex.decode(Vectors.list("replies").first().str("reply"))
        val corrupted = answer.also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertFalse(lock.isEchoedBy(FrameCodec.decode(corrupted)), "a frame with a bad CRC confirms nothing")
    }

    @Test
    fun `outgoing frame bytes are defensive copies`() {
        val frame = BikeCommand.SetLights(true).toFrame()
        frame.bytes[6] = 0
        assertEquals("456E05970001FFA4857765", Hex.encode(frame.bytes))
    }
}
