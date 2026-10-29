package io.github.ridelabz.engwespeedtool.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class ProfileRegistryTest {
    private val registry = ProfileRegistry.bundled()
    private val reference = "ENGWE_Omni_ebike1100EU317"
    private val referenceText = File("src/main/resources/profiles/engwe-e26-3-pro.json").readText()

    @Test
    fun `bundled reference profile is verified with the known layout`() {
        val profile = checkNotNull(registry.find(reference))
        assertTrue(profile.verified)
        val layout = profile.statusLayout
        assertEquals(54, layout.payloadLength)
        val offsets = layout.fields.associate { it.field to it.offset }
        assertEquals(
            mapOf(
                StatusField.SPEED to 0,
                StatusField.MAX_SPEED to 2,
                StatusField.ODOMETER to 16,
                StatusField.ASSIST_LEVEL to 18,
                StatusField.BATTERY to 21,
                StatusField.LIGHTS to 28,
                StatusField.TEMPERATURE to 36,
                StatusField.BATTERY_HEALTH to 37,
                StatusField.POWER_FLAG_A to 39,
                StatusField.POWER_FLAG_B to 40,
                StatusField.VOLTAGE to 42,
                StatusField.RIDE_TIME to 48,
            ),
            offsets,
        )
        assertTrue(layout.fields.all { it.verified }, "every reading compared with the display or the ENGWE app")
    }

    @Test
    fun `a profile names the firmware it was tested with, versions only`() {
        assertEquals(listOf("218CAN"), checkNotNull(registry.find(reference)).testedFirmware)
        val bundled = ProfileRegistryTest::class.java.getResource("/profiles/engwe-e26-3-pro.json")!!.readText()
        assertEquals(
            emptyList<String>(),
            ProfileRegistry.parse(bundled.replaceOnce("  \"tested_firmware\": [\"218CAN\"],\n", "")).testedFirmware,
            "optional",
        )
        val unverified =
            bundled
                .replaceOnce("\"verified\": true,\n  \"status_layout\"", "\"verified\": false,\n  \"status_layout\"")
                .replace("\"verified\": true", "\"verified\": false")
                .replaceOnce("  \"lock_control\": true,\n", "")
        assertThrows<ProfileException>(
            "an unverified profile naming tested firmware",
        ) { ProfileRegistry.parse(unverified) }
        assertEquals(
            emptyList<String>(),
            ProfileRegistry.parse(unverified.replaceOnce("  \"tested_firmware\": [\"218CAN\"],\n", "")).testedFirmware,
        )
        for (bad in listOf("\"\"", "\"218 CAN\"", "\"https://x\"", "\"123456789012\"", "\"218CAN\", \"218CAN\"")) {
            assertThrows<ProfileException>(bad) {
                ProfileRegistry.parse(bundled.replaceOnce("[\"218CAN\"]", "[$bad]"))
            }
        }
    }

    @Test
    fun `a profile gives the number of assist levels, which only enables the fixed command`() {
        assertEquals(5, checkNotNull(registry.find(reference)).assistLevels)
        assertNull(
            ProfileRegistry.parse(referenceText.replaceOnce("  \"assist_levels\": 5,\n", "")).assistLevels,
            "optional: without it, the level is shown but can't be set",
        )
        for (bad in listOf("5.5", "-1", "true", "null, \"assist_command\": \"1009\"")) {
            assertThrows<ProfileException>(bad) {
                ProfileRegistry.parse(referenceText.replaceOnce("\"assist_levels\": 5", "\"assist_levels\": $bad"))
            }
        }
    }

    @Test
    fun `a profile can offer lock control, which only enables the fixed lock command`() {
        assertTrue(checkNotNull(registry.find(reference)).lockControl)
        assertFalse(
            ProfileRegistry.parse(referenceText.replaceOnce("  \"lock_control\": true,\n", "")).lockControl,
            "optional: without it, the app can't switch the bike off or on",
        )
        for (bad in listOf("1", "\"yes\"", "null", "{\"command\": \"0108\"}")) {
            assertThrows<ProfileException>(bad) {
                ProfileRegistry.parse(referenceText.replaceOnce("\"lock_control\": true", "\"lock_control\": $bad"))
            }
        }
    }

    @Test
    fun `lookup is exact`() {
        for (near in listOf(reference.lowercase(), "$reference ", " $reference", reference.dropLast(1), "")) {
            assertNull(registry.find(near), "'$near'")
        }
    }

    @Test
    fun `unverified layout keeps offsets but marks every field unverified`() {
        val layout = registry.unverifiedLayout
        assertEquals(54, layout.payloadLength)
        assertTrue(layout.fields.none { it.verified })
        assertEquals(0, layout.spec(StatusField.SPEED)?.offset)
    }

    @Test
    fun `profiles cannot carry commands or unknown keys`() {
        val withCommands = referenceText.replaceFirst("{", """{ "commands": [{"code": "2A66", "payload": "FF"}],""")
        assertThrows<ProfileException> { ProfileRegistry.parse(withCommands) }
        val unknownFieldKey =
            referenceText.replaceFirst(
                "\"verified\": true }",
                "\"verified\": true, \"write\": true }",
            )
        assertThrows<ProfileException> { ProfileRegistry.parse(unknownFieldKey) }
    }

    @Test
    fun `invalid layouts are rejected for the stated reason`() {
        check(referenceText.contains("\"evidence\""))
        val cases =
            mapOf(
                "unknown field name" to
                    (
                        referenceText.replaceOnce("\"name\": \"ride_time\"", "\"name\": \"current_limit\"") to
                            "unknown field"
                    ),
                "outside payload" to
                    (referenceText.replaceOnce("\"offset\": 48", "\"offset\": 53") to "outside the payload"),
                "overlap" to (
                    referenceText.replaceOnce(
                        "\"offset\": 48",
                        "\"offset\": 42",
                    ) to "overlaps another field"
                ),
                "bad size" to
                    (
                        referenceText.replaceOnce("\"offset\": 48, \"size\": 2", "\"offset\": 48, \"size\": 3") to
                            "size must be 1 or 2"
                    ),
                "duplicate field" to
                    (referenceText.replaceOnce("\"name\": \"ride_time\"", "\"name\": \"speed\"") to "duplicate fields"),
                "unverified profile with verified fields" to
                    (
                        referenceText.replaceOnce(
                            "\"verified\": true,\n  \"status_layout\"",
                            "\"verified\": false,\n  \"status_layout\"",
                        ) to "can't have verified fields"
                    ),
                "old schema" to (referenceText.replaceOnce("\"schema\": 5", "\"schema\": 4") to "unsupported schema 4"),
                "lock control without verified power flags" to
                    (
                        referenceText.replaceOnce(
                            "\"name\": \"power_flag_b\", \"offset\": 40, \"size\": 1, \"scale\": 1, \"verified\": true",
                            "\"name\": \"power_flag_b\", \"offset\": 40, \"size\": 1, \"scale\": 1, \"verified\": false",
                        ) to "lock control without verified power flags"
                    ),
                "lock control without power flags" to
                    (
                        referenceText.replaceOnce(
                            "      { \"name\": \"power_flag_a\", \"offset\": 39, \"size\": 1, \"scale\": 1, \"verified\": true },\n",
                            "",
                        ) to "lock control without verified power flags"
                    ),
                "assist levels above the closed set" to
                    (referenceText.replaceOnce("\"assist_levels\": 5", "\"assist_levels\": 6") to "assist levels"),
                "no assist levels" to
                    (referenceText.replaceOnce("\"assist_levels\": 5", "\"assist_levels\": 0") to "assist levels"),
                "assist levels without the reading that confirms them" to
                    (
                        referenceText.replaceOnce(
                            "      { \"name\": \"assist_level\", \"offset\": 18, \"size\": 1, \"scale\": 1, \"verified\": true },\n",
                            "",
                        ) to "without an assist level reading"
                    ),
                "firmware tags, no longer part of a profile" to
                    (
                        referenceText.replaceOnce(
                            "\"verified\": true,\n",
                            "\"verified\": true,\n  \"firmware_tags\": [\"OMS1218\"],\n",
                        ) to "firmware_tags"
                    ),
                "non-ASCII identity" to (referenceText.replaceOnce(reference, "ENGWE_Ömni") to "printable ASCII"),
                "verified without evidence" to
                    (
                        referenceText.substringBefore("\"evidence\"") + "\"evidence\": []\n}" to
                            "a verified profile needs evidence"
                    ),
                "not JSON" to ("nope" to "JSON"),
            )
        for ((name, case) in cases) {
            val (text, reason) = case
            val e = assertThrows<ProfileException>(name) { ProfileRegistry.parse(text) }
            assertTrue(e.message!!.contains(reason), "$name: ${e.message}")
        }
    }

    @Test
    fun `the generic layout comes from the named reference profile`() {
        val other = ProfileRegistry.parse(referenceText.replace(reference, "ENGWE_Other"))
        assertThrows<ProfileException> { ProfileRegistry(listOf(other)) }
        assertThrows<ProfileException> { ProfileRegistry(emptyList()) }
        val reg = ProfileRegistry(listOf(other, ProfileRegistry.parse(referenceText)))
        assertEquals(54, reg.unverifiedLayout.payloadLength)
    }

    @Test
    fun `hand-built layouts and profiles are validated too`() {
        assertThrows<IllegalArgumentException> { FieldSpec(StatusField.SPEED, 0, 3, 1.0, true) }
        assertThrows<IllegalArgumentException> { FieldSpec(StatusField.SPEED, -1, 1, 1.0, true) }
        assertThrows<IllegalArgumentException> { FieldSpec(StatusField.SPEED, 0, 1, 0.0, true) }
        assertThrows<IllegalArgumentException> { FieldSpec(StatusField.SPEED, Int.MAX_VALUE, 2, 0.1, true) }
        assertThrows<IllegalArgumentException> {
            StatusLayout(
                54,
                listOf(FieldSpec(StatusField.SPEED, 1023, 2, 0.1, true)),
            )
        }
        val speed = FieldSpec(StatusField.SPEED, 0, 2, 0.1, true)
        assertThrows<IllegalArgumentException> { StatusLayout(1, listOf(speed)) }
        assertThrows<IllegalArgumentException> {
            StatusLayout(4, listOf(speed, FieldSpec(StatusField.BATTERY, 1, 1, 1.0, true)))
        }
        val layout = StatusLayout(4, listOf(speed))
        assertThrows<IllegalArgumentException> { BikeProfile("ENGWE_X", "X", verified = true, layout, emptyList()) }
        assertThrows<IllegalArgumentException> { BikeProfile("ENGWE_X", "X", verified = false, layout, emptyList()) }
        assertThrows<IllegalArgumentException> {
            BikeProfile("ENGWE X", "X", verified = false, layout.asUnverified(), emptyList())
        }
        BikeProfile("ENGWE_X", "X", verified = true, layout, listOf("tested"))
        BikeProfile("ENGWE_X", "X", verified = false, layout.asUnverified(), emptyList())
    }

    @Test
    fun `every bundled layout decodes a payload of its own length without errors`() {
        val layouts = registry.profiles.map { it.statusLayout } + registry.unverifiedLayout
        for (layout in layouts) {
            val frame = FrameCodec.decode(FrameCodec.encode(0x2A63, ByteArray(layout.payloadLength)))
            checkNotNull(StatusDecoder.decode(frame, layout))
        }
    }

    @Test
    fun `duplicate identities are rejected`() {
        val p = ProfileRegistry.parse(referenceText)
        assertThrows<ProfileException> { ProfileRegistry(listOf(p, p)) }
    }

    @Test
    fun `every profile file is listed in the index`() {
        val dir = File("src/main/resources/profiles")
        val listed =
            File(dir, "index.txt")
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        val files =
            dir
                .listFiles()!!
                .map { it.name }
                .filter { it.endsWith(".json") }
                .toSet()
        assertEquals(files, listed)
        assertFalse(registry.profiles.isEmpty())
    }
}

/** Like [String.replace], but fails when [old] doesn't occur exactly once, so a test can't silently test nothing. */
internal fun String.replaceOnce(
    old: String,
    new: String,
): String {
    check(split(old).size == 2) { "expected exactly one occurrence of: $old" }
    return replace(old, new)
}
