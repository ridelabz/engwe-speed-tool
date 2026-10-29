package io.github.ridelabz.engwespeedtool.reporting

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeClassifier
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.FrameCodec
import io.github.ridelabz.engwespeedtool.protocol.FrameReassembler
import io.github.ridelabz.engwespeedtool.protocol.Hex
import io.github.ridelabz.engwespeedtool.protocol.IdentityParser
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.ProtocolChecks
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.StatusDecoder
import io.github.ridelabz.engwespeedtool.protocol.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

private class FakeClock : SessionClock {
    var nanos = 0L

    override fun today(): LocalDate = LocalDate.of(2026, 1, 2)

    override fun elapsedNanos(): Long = nanos
}

private enum class Where { GATT, SCAN }

private enum class Code { WRITE_FAILED }

private enum class Name { CONNECTION, TAP_SPEED_32 }

private enum class Value { CONNECTED }

private enum class Outcome { CHECKED, NOT_CONNECTED }

private enum class Check { SERVICE, IDENTITY }

private enum class Result { PASSED, NOT_RUN, PLAUSIBLE, NOT_READ }

private enum class Field { SPEED, VOLTAGE, RIDE_TIME }

private enum class Property { READ, WRITE, NOTIFY }

private enum class Why { NOT_FOUND }

class SessionLogTest {
    private val rnd = Random(1414)
    private val meta = SessionMeta("0.1.0", "15", 35, "Google", "Pixel 9")
    private val registry = ProfileRegistry.bundled()
    private val statusPayload =
        Hex.decode(
            "0000006D00000000000000000000FF00C170000000500000000000000000000000000001186410010100148B000000003B9E00000001",
        )

    private fun records(text: String): List<JsonObject> =
        text
            .lines()
            .filter {
                it.isNotBlank()
            }.map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `records carry date only and session-relative time`() {
        val out = StringBuilder()
        val clock = FakeClock()
        val log = SessionLog(out, clock, meta)
        clock.nanos = 1_234_567_890
        log.action(Name.TAP_SPEED_32)
        val recs = records(out.toString())
        assertEquals("session_start", recs[0].s("event"))
        assertEquals("2026-01-02", recs[0].s("date"))
        assertEquals("Google Pixel 9", recs[0].s("phone"))
        assertEquals("1.235", recs[1].s("t"))
        assertEquals("tap_speed_32", recs[1].s("name"))
        assertEquals(listOf("0", "1"), recs.map { it.s("seq") })
        assertFalse(Regex("""\d{2}:\d{2}""").containsMatchIn(out), "no time of day")
    }

    @Test
    fun `traffic, state and error records`() {
        val out = StringBuilder()
        val log = SessionLog(out, FakeClock(), meta)
        val frame = BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32).toFrame()
        log.outgoing(frame)
        FrameReassembler().feed(frame.bytes).forEach { log.incoming(it) }
        val status = FrameCodec.decode(TestFrames.encode(0x2A63, statusPayload))
        log.status(
            checkNotNull(
                StatusDecoder.decode(
                    status,
                    ProfileRegistry
                        .bundled()
                        .profiles
                        .first()
                        .statusLayout,
                ),
            ),
        )
        log.state(Name.CONNECTION, Value.CONNECTED)
        log.error(Where.GATT, IllegalStateException("anything at all"))
        log.error(Where.GATT, Code.WRITE_FAILED)
        val (tx, rx, st, state, err1, err2) = records(out.toString()).drop(1)
        assertEquals("set_speed_limit_32", tx.s("command"))
        assertEquals("456E2A6500012042237765", tx.s("raw_hex"))
        assertEquals("2A65", rx.s("command"))
        assertEquals("80", st.s("battery_pct"))
        assertEquals("on", st.s("power"))
        assertEquals("[]", st.getValue("unverified").toString(), "every value verified for the reference bike")
        assertEquals("connected", state.s("value"))
        assertEquals("IllegalStateException", err1.s("exception"))
        assertFalse(err1.toString().contains("anything"))
        assertEquals("write_failed", err2.s("code"))
        // A bike without a verified profile: every value is listed as unverified.
        val unverifiedLayout = ProfileRegistry.bundled().unverifiedLayout
        log.status(checkNotNull(StatusDecoder.decode(status, unverifiedLayout)))
        val unverified = records(out.toString()).last().getValue("unverified").toString()
        assertTrue(listOf("speed_kmh", "battery_pct", "voltage_v").all { it in unverified }, unverified)
    }

    @Test
    fun `every command has its own token, and an assist echo is logged in full`() {
        val out = StringBuilder()
        val log = SessionLog(out, FakeClock(), meta)
        for (command in BikeCommand.all()) log.outgoing(command.toFrame())
        val tokens = records(out.toString()).drop(1).map { it.s("command") }
        assertEquals(tokens.toSet().size, tokens.size, "no two commands share a token")
        assertTrue((0..5).all { "set_assist_level_$it" in tokens }, "$tokens")
        val echo = BikeCommand.SetAssistLevel(AssistLevel.LEVEL_4).toFrame()
        FrameReassembler().feed(echo.bytes).forEach(log::incoming)
        val rx = records(out.toString()).last()
        assertEquals("1009", rx.s("command"))
        assertEquals("456E10090001040CE67765", rx.s("raw_hex"))
        assertTrue("lock" in tokens && "unlock" in tokens, "$tokens")
        // The bike's answer to a lock: its lock state, one byte, logged in full.
        FrameReassembler().feed(TestFrames.encode(0x0109, byteArrayOf(2))).forEach(log::incoming)
        val answer = records(out.toString()).last()
        assertEquals("0109", answer.s("command"))
        assertEquals("456E01090001020D9A7765", answer.s("raw_hex"))
    }

    @Test
    fun `identity and classification records keep only the model and the verdict`() {
        val out = StringBuilder()
        val log = SessionLog(out, FakeClock(), meta)
        val payload =
            "ENGWE_Omni_ebike1100EU317".toByteArray() + ByteArray(3) + "1".toByteArray() + ByteArray(5) +
                "SECRETSERIAL0000000".toByteArray() + ByteArray(1)
        val reply = FrameCodec.decode(TestFrames.encode(0xFF01, payload))
        log.identity(reply)
        log.identity(FrameCodec.decode(TestFrames.encode(0xFF01, "SECRETSERIAL0000000".toByteArray())))
        val model = IdentityParser.modelOf(reply)
        log.classification(BikeClassifier.classify(ProtocolChecks(true, true, "ENGWE_Other_model"), registry))
        log.classification(BikeClassifier.classify(ProtocolChecks(true, false, model), registry))
        log.classification(BikeClassifier.classify(ProtocolChecks(true, true, model), registry))
        val (good, unusable, unverified, incompatible) = records(out.toString()).drop(1)
        val verified = records(out.toString()).last()
        assertEquals("verified", verified.s("verification"))
        assertEquals(setOf("schema", "seq", "t", "event", "verification"), verified.keys)
        assertEquals("ENGWE_Omni_ebike1100EU317", good.s("model"))
        assertEquals(setOf("schema", "seq", "t", "event", "model"), good.keys)
        assertEquals("false", unusable.s("usable"))
        assertEquals(setOf("schema", "seq", "t", "event", "usable"), unusable.keys)
        assertEquals("unverified", unverified.s("verification"))
        assertEquals("unknown_model", unverified.s("reason"))
        assertEquals(setOf("schema", "seq", "t", "event", "verification", "reason"), unverified.keys)
        assertEquals("incompatible", incompatible.s("verification"))
        assertFalse(out.contains("SECRET"))
    }

    @Test
    fun `a scan result records only the masked name and the signal strength`() {
        val out = StringBuilder()
        SessionLog(out, FakeClock(), meta).scanResult("ENGWE-7C", -70)
        val record = records(out.toString()).last()
        assertEquals(setOf("schema", "seq", "t", "event", "name", "rssi"), record.keys)
        assertEquals("ENGWE-**", record.s("name"))
        assertEquals("-70", record.s("rssi"))
    }

    @Test
    fun `an exception without a class name is recorded as anonymous`() {
        val out = StringBuilder()
        SessionLog(out, FakeClock(), meta).error(Where.GATT, object : RuntimeException("anything at all") {})
        assertEquals("anonymous", records(out.toString()).last().s("exception"))
    }

    @Test
    fun `phone facts that don't look like plain values are not recorded`() {
        val odd = SessionMeta("0.1.0 built by alice@host", "15", 35, "CustomRom-ish", "build user@host 123456789012")
        assertEquals("unknown", odd.appVersion)
        assertEquals("unknown", odd.phone)
    }

    @Test
    fun `a compatibility check records names, counts and the declared services, never a value`() {
        val out = StringBuilder()
        val log = SessionLog(out, FakeClock(), meta)
        // A custom UUID whose last 12 digits hold an address, as some devices do.
        val custom = UUID.fromString("6e400001-b5a3-f393-e0a9-4f2a91c07e3b")
        log.compatibilityCheck(
            CheckRecord(
                Outcome.CHECKED,
                null,
                listOf(CheckLine(Check.SERVICE, Result.PASSED), CheckLine(Check.IDENTITY, Result.PASSED)),
                statusFrames = 30,
                sampledSeconds = 30,
                unreadableFrames = false,
                values =
                    listOf(
                        ValueLine(Field.SPEED, Result.PLAUSIBLE, unverified = false),
                        ValueLine(Field.VOLTAGE, Result.PLAUSIBLE, unverified = true),
                        ValueLine(Field.RIDE_TIME, Result.NOT_READ, unverified = false),
                    ),
                services =
                    listOf(
                        DeclaredService(
                            uuid(0x1820),
                            listOf(
                                DeclaredCharacteristic(uuid(0x1821), listOf(Property.NOTIFY), listOf(uuid(0x2902))),
                                DeclaredCharacteristic(uuid(0x1822), listOf(Property.WRITE), emptyList()),
                            ),
                        ),
                        DeclaredService(
                            custom,
                            listOf(DeclaredCharacteristic(custom, listOf(Property.READ), listOf(custom))),
                        ),
                    ),
            ),
        )
        log.compatibilityCheck(
            CheckRecord(Outcome.NOT_CONNECTED, Why.NOT_FOUND, emptyList(), 0, null, false, emptyList(), emptyList()),
        )
        val lines = out.toString().lines().filter { it.isNotBlank() }
        assertEquals(
            """{"schema":2,"seq":1,"t":0.0,"event":"compatibility_check","outcome":"checked",""" +
                """"checks":{"service":"passed","identity":"passed"},"status_frames":30,"sampled_s":30,""" +
                """"unreadable_frames":false,""" +
                """"values":{"speed":"plausible","voltage":"plausible","ride_time":"not_read"},""" +
                """"unverified":["voltage"],"services":[{"uuid":"0x1820","characteristics":[""" +
                """{"uuid":"0x1821","properties":["notify"],"descriptors":["0x2902"]},""" +
                """{"uuid":"0x1822","properties":["write"],"descriptors":[]}]},""" +
                """{"uuid":"6e400001-****-****-****-************","characteristics":[""" +
                """{"uuid":"6e400001-****-****-****-************","properties":["read"],""" +
                """"descriptors":["6e400001-****-****-****-************"]}]}]}""",
            lines[1],
        )
        assertEquals(
            """{"schema":2,"seq":2,"t":0.0,"event":"compatibility_check","outcome":"not_connected",""" +
                """"reason":"not_found","checks":{},"status_frames":0,"unreadable_frames":false,"values":{},""" +
                """"unverified":[],"services":[]}""",
            lines[2],
        )
        assertFalse(out.contains("4f2a91c07e3b", ignoreCase = true), "the address in the UUID")
    }

    private fun uuid(short: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))

    @Test
    fun `nothing identifying survives any record`() {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        repeat(300) {
            val out = StringBuilder()
            val mac = List(6) { "%02X".format(rnd.nextInt(256)) }
            val macForms =
                listOf(":", "-", ".", " ", "").map { mac.joinToString(it) } + "XX:XX:XX:XX:" +
                    mac.takeLast(2).joinToString(":")
            val serialChars = CharArray(19) { alphabet.random(rnd) }
            val at = rnd.nextInt(0, 17)
            "weEn".substring(rnd.nextInt(2) * 2).take(2).forEachIndexed { i, c -> serialChars[at + i] = c }
            val serial = String(serialChars)
            val suffix = "ENGWE-" + (1..2).map { alphabet.random(rnd) }.joinToString("")
            val email = "user${rnd.nextInt(1000)}@mail${rnd.nextInt(1000)}.example"
            val secretText =
                listOf(
                    macForms.random(rnd),
                    serial,
                    Hex.encode(serial.toByteArray()),
                    suffix,
                    email,
                ).joinToString(" ")
            val log =
                SessionLog(out, FakeClock(), SessionMeta(secretText, secretText, 35, secretText, macForms.random(rnd)))
            val identity =
                "ENGWE_Omni_ebike1100EU317".toByteArray() + ByteArray(3) + "1".toByteArray() + ByteArray(5) +
                    serial.toByteArray() +
                    ByteArray(1)
            val reply = TestFrames.encode(0xFF01, identity)
            val badCrc = reply.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
            val r = FrameReassembler()
            val events =
                r.feed(reply) + r.feed(badCrc) + r.feed(reply.copyOfRange(30, 60)) + r.flush() +
                    r.feed(TestFrames.encode(0x2A63, statusPayload).copyOf(20)) + r.feed(reply) + r.flush() +
                    r.feed(TestFrames.encode(0xFF04, serial.toByteArray())) + r.flush()
            events.forEach { log.incoming(it) }
            events.filterIsInstance<StreamEvent.Decoded>().forEach { log.identity(it.frame) }
            val model = IdentityParser.modelOf(FrameCodec.decode(reply))
            log.classification(BikeClassifier.classify(ProtocolChecks(true, true, model), registry))
            log.scanResult(suffix, -70)
            log.scanResult(secretText, -70)
            log.error(Where.SCAN, RuntimeException(secretText))
            val text = out.toString()
            assertTrue(text.contains("\"model\":\"ENGWE_Omni_ebike1100EU317\""))
            val secrets = macForms + listOf(serial, Hex.encode(serial.toByteArray()), suffix, email)
            for (secret in secrets) assertFalse(text.contains(secret, ignoreCase = true), "leaked: $secret")
            for (part in serial.windowed(4)) {
                assertFalse(text.contains(part), "serial fragment $part")
                assertFalse(
                    text.contains(Hex.encode(part.toByteArray()), ignoreCase = true),
                    "serial hex fragment $part",
                )
            }
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
