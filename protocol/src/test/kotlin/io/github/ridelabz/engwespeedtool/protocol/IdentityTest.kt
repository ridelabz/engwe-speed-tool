package io.github.ridelabz.engwespeedtool.protocol

import io.github.ridelabz.engwespeedtool.protocol.Vectors.str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityTest {
    private val vector = Vectors.obj("identity_reply")
    private val reply = FrameCodec.decode(Hex.decode(vector.str("frame")))
    private val model = vector.str("model")
    private val registry = ProfileRegistry.bundled()

    private fun replyWith(payload: ByteArray) = FrameCodec.decode(FrameCodec.encode(0xFF01, payload))

    @Test
    fun `model is read and nothing after it is returned`() {
        val parsed = IdentityParser.modelOf(reply)
        assertEquals(model, parsed)
        assertFalse(parsed!!.contains("FAKESERIAL"))
        assertTrue(IdentityParser.isIdentityReply(reply))
    }

    @Test
    fun `unusable replies give no identity`() {
        val cases =
            mapOf(
                "no NUL terminator" to model.toByteArray(),
                "empty model" to byteArrayOf(0) + model.toByteArray(),
                "empty payload" to ByteArray(0),
                "too long" to ByteArray(65) { 'A'.code.toByte() } + byteArrayOf(0),
                "NUL after byte 31" to ByteArray(32) { 'A'.code.toByte() } + byteArrayOf(0),
                "serial glued to the model" to "ENGWE_XSERIAL1234567890".toByteArray() + byteArrayOf(0),
                "space inside" to "ENGWE Omni".toByteArray() + byteArrayOf(0),
                "non-ASCII" to byteArrayOf(0x45, 0xC3.toByte(), 0x96.toByte(), 0),
                "control character" to byteArrayOf(0x45, 0x09, 0x45, 0),
            )
        for ((name, payload) in cases) assertNull(IdentityParser.modelOf(replyWith(payload)), name)
        val badCrc = reply.raw.also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertNull(IdentityParser.modelOf(FrameCodec.decode(badCrc)), "bad CRC")
        val otherCommand = FrameCodec.decode(FrameCodec.encode(0xFF04, reply.payload))
        assertNull(IdentityParser.modelOf(otherCommand), "other command")
    }

    @Test
    fun `models that look like an address, a name suffix or an e-mail give no identity`() {
        val hostile =
            listOf(
                "F0:12:34:56:78:9A",
                "f0-12-34-56-78-9a",
                "F0 12 34 56 78 9A",
                "F012.3456.789A",
                "F0AB3456CDEF",
                "ENGWE_F0AB3456CDEF",
                "ENGWE-7C",
                "engwe 1e7c",
                "ENGWE9",
                "rider@mail.example",
            )
        for (m in hostile) {
            val frame = replyWith(m.toByteArray() + ByteArray(8) + "FAKESERIAL".toByteArray() + ByteArray(1))
            assertNull(IdentityParser.modelOf(frame), m)
            val logged = checkNotNull(Sanitiser.incoming(frame).hex)
            assertFalse(logged.contains(Hex.encode(m.toByteArray())), "$m kept in the log")
        }
        for (m in listOf(model, "ENGWE_Other_model", "ENGWE_Omni", "ENGWE_E26", "ENGWE_EP2_Pro")) {
            assertEquals(m, IdentityParser.modelOf(replyWith(m.toByteArray() + ByteArray(1))), m)
        }
    }

    @Test
    fun `a 31-character model is the longest accepted`() {
        val longest = "ENGWE_" + "Z".repeat(25)
        assertEquals(longest, IdentityParser.modelOf(replyWith(longest.toByteArray() + ByteArray(1))))
    }

    @Test
    fun `classification follows the profile registry`() {
        val ok = ProtocolChecks(serviceFound = true, statusFramesValid = true, modelIdentity = model)
        val verified = BikeClassifier.classify(ok, registry)
        assertEquals(Verification.VERIFIED, verified.verification)
        assertNull(verified.unverifiedReason)
        assertSame(registry.find(model), verified.profile)
        assertSame(registry.find(model)!!.statusLayout, verified.layout)
        assertTrue(verified.layout!!.spec(StatusField.SPEED)!!.verified)

        val other = ProtocolChecks(true, true, "ENGWE_Other_model")
        val unverified = BikeClassifier.classify(other, registry)
        assertEquals(Verification.UNVERIFIED, unverified.verification)
        assertEquals(UnverifiedReason.UNKNOWN_MODEL, unverified.unverifiedReason)
        assertTrue(unverified.layout!!.fields.none { it.verified })
        assertNull(unverified.profile)
    }

    @Test
    fun `only the whole identity, exactly, makes a bike verified`() {
        val nearMisses =
            listOf(
                model.lowercase(),
                model.dropLast(1),
                model.dropLast(1) + "8",
                model + "0",
                model.substring(0, model.length - 3),
                "X$model".take(model.length),
            )
        for (m in nearMisses) {
            val c = BikeClassifier.classify(ProtocolChecks(true, true, m), registry)
            assertEquals(Verification.UNVERIFIED, c.verification, m)
            assertEquals(UnverifiedReason.UNKNOWN_MODEL, c.unverifiedReason, m)
            assertTrue(c.layout!!.fields.none { it.verified }, m)
        }
    }

    @Test
    fun `any failed check makes the bike incompatible`() {
        val cases =
            listOf(
                ProtocolChecks(serviceFound = false, statusFramesValid = true, modelIdentity = model),
                ProtocolChecks(serviceFound = true, statusFramesValid = false, modelIdentity = model),
                ProtocolChecks(serviceFound = true, statusFramesValid = true, modelIdentity = null),
            )
        for (checks in cases) {
            val c = BikeClassifier.classify(checks, registry)
            assertEquals(Verification.INCOMPATIBLE, c.verification)
            assertNull(c.layout)
        }
    }

    @Test
    fun `a profile marked unverified does not make a bike verified`() {
        val text =
            java.io
                .File("src/main/resources/profiles/engwe-e26-3-pro.json")
                .readText()
                .replaceOnce("\"verified\": true,\n  \"status_layout\"", "\"verified\": false,\n  \"status_layout\"")
                .replace("\"verified\": true }", "\"verified\": false }")
                .replaceOnce("  \"tested_firmware\": [\"218CAN\"],\n", "")
                .replaceOnce("  \"lock_control\": true,\n", "")
                .replaceOnce(model, "ENGWE_Community_model")
        val community = ProfileRegistry.parse(text)
        val reg = ProfileRegistry(registry.profiles + community)
        val c = BikeClassifier.classify(ProtocolChecks(true, true, "ENGWE_Community_model"), reg)
        assertEquals(Verification.UNVERIFIED, c.verification)
        assertEquals(UnverifiedReason.PROFILE_NOT_VERIFIED, c.unverifiedReason)
        assertSame(community, c.profile)
        assertTrue(c.layout!!.fields.none { it.verified })
    }

    @Test
    fun `incompatible bikes carry no reason`() {
        val c = BikeClassifier.classify(ProtocolChecks(false, true, model), registry)
        assertNull(c.unverifiedReason)
        assertEquals(model, c.modelIdentity)
    }
}
