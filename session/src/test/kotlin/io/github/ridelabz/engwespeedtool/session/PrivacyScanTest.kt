@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.Hex
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * End to end, what an exported session log could leak: whole sessions on simulated bikes with random
 * addresses, serials and models (a model with an e-mail address in it too), with searches, checks, status,
 * speed and light commands, a refusal, a rejected write, a failed connection whose exception names the
 * address and the serial, frames the app doesn't know (carrying the address and the serial), broken and cut
 * identity replies, stray bytes, odd-length status frames, display off and on, keep-speed, drops, reconnects,
 * a check of another bike, a stop, and a compatibility check of a bike whose own service UUIDs carry its
 * address. The log as it would be exported must hold none of the injected values,
 * nor anything shaped like a Bluetooth address, an e-mail address, a long hex run or a time of day.
 *
 * Names: an advertisement carries only an address and a signal strength, and the session logs every bike's
 * name as `ENGWE-**`, so no advertised name can reach the log; the scan checks that every `ENGWE-` in it is
 * that. The name a rider gives a bike stays in the app's store (the app's tests check its log).
 */
class PrivacyScanTest {
    private val mac =
        Regex("(?i)(?<![0-9a-fx])[0-9a-fx]{2}(?:[:\\-. ][0-9a-fx]{2}){5}(?![0-9a-fx])")
    private val dotted = Regex("(?i)(?<![0-9a-f])[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}(?![0-9a-f])")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z0-9.-]+")
    private val longHex = Regex("(?i)[0-9a-f]{12,}")
    private val timeOfDay = Regex("(?<![0-9])[0-9]{1,2}:[0-9]{2}(?![0-9])")
    private val epoch = Regex("(?<![0-9.])[0-9]{10,}")
    private val rawHex = Regex("\"raw_hex\":\"[0-9A-Fa-f]*\"")

    private fun Random.address(): BikeAddress =
        BikeAddress((1..6).joinToString(":") { String.format(Locale.ROOT, "%02X", nextInt(256)) })

    private fun Random.text(
        length: Int,
        chars: String,
    ): String = (1..length).map { chars[nextInt(chars.length)] }.joinToString("")

    private fun BikeAddress.bytes(): ByteArray = value.split(":").map { it.toInt(16).toByte() }.toByteArray()

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8])
    fun `an exported session log holds no address, serial, injected text, e-mail address or time of day`(seed: Long) =
        runTest {
            val rnd = Random(seed)
            // Letters that aren't hex digits: a serial can't turn up in a frame's hex by chance.
            val letters = "GHIJKLMNOPQRSTUVWXYZ"
            val serial = rnd.text(19, letters)
            val withEmail = seed % 3 == 1L
            val model =
                when (seed % 3) {
                    0L -> "ENGWE_" + rnd.text(10, "abcdefghijklmnopqrstuvwxyz_")
                    1L -> "rider" + rnd.nextInt(100) + "@mail.example"
                    else -> "ENGWE_Omni_ebike1100EU317"
                }
            val bike =
                FakeBike(
                    backgroundScope,
                    rnd.address(),
                    model = model,
                    serial = serial,
                    rssi =
                        -40 - rnd.nextInt(50),
                )
            val other = FakeBike(backgroundScope, rnd.address(), serial = rnd.text(19, letters))
            // A bike whose own services carry its address in their UUIDs' last 12 digits, as some devices do.
            val vendorAddress = rnd.address()
            val plain = vendorAddress.value.replace(":", "").lowercase(Locale.ROOT)
            val reversed =
                vendorAddress.value
                    .split(":")
                    .reversed()
                    .joinToString("")
                    .lowercase(Locale.ROOT)
            val vendorService = UUID.fromString("6e400001-b5a3-f393-e0a9-$plain")
            val vendorCharacteristic = UUID.fromString("6e400002-b5a3-f393-e0a9-$reversed")
            // And over the middle groups.
            val spread = plain.chunked(4).joinToString("-")
            val vendorDescriptor = UUID.fromString("6e400003-$spread-e50e24dcca9e")
            val vendor =
                FakeBike(
                    backgroundScope,
                    vendorAddress,
                    serial = rnd.text(19, letters),
                    services =
                        FakeBike.REFERENCE_SERVICES +
                            GattService(
                                vendorService,
                                listOf(
                                    GattCharacteristic(
                                        vendorCharacteristic,
                                        setOf(CharacteristicProperty.READ, CharacteristicProperty.NOTIFY),
                                        listOf(vendorService, vendorDescriptor),
                                    ),
                                ),
                            ),
                )
            var connects = 0
            val rig =
                Rig(this, bike, other, vendor) { inner ->
                    object : WrappedTransport(inner) {
                        override suspend fun connect(address: BikeAddress): BikeLink {
                            // The bike's second connection (its first reconnection) fails with a message naming them.
                            if (address == bike.address && ++connects == 2) {
                                error("no link to ${address.value} ($serial, $model)")
                            }
                            return super.connect(address)
                        }
                    }
                }
            rig.acknowledged += bike.address
            rig.session.keepSpeed(bike.address, SpeedLimit.KMH_32)
            rig.session.keepAssistLevel(bike.address, AssistLevel.LEVEL_2)

            rig.session.start(bike.address)
            val settled = rig.settled()
            if (withEmail) {
                assertEquals(SessionState.Incompatible(CheckFailure.NO_IDENTITY), settled)
            } else {
                assertTrue(settled is SessionState.Ready, "ready: $settled")
                rig.session.ride
                    .drop(1)
                    .first { it.status != null }
                rig.session.setSpeedLimit(SpeedLimit.KMH_16)
                bike.rejectNextWrite = 133
                rig.session.setLights(true)
                rig.session.setLights(true)
                // Sent to the reference model; refused to another, whose profile offers no assist levels.
                rig.session.setAssistLevel(AssistLevel.LEVEL_3)
                // Frames the app doesn't know, with the address (both byte orders) and the serial in them.
                val addressBytes = bike.address.bytes()
                bike.notifyRaw(
                    SimulatedFrames.encode(
                        0xFF04,
                        addressBytes + addressBytes.reversedArray() + serial.toByteArray(),
                    ),
                )
                // A known one-byte command with more than one byte: logged by its size only.
                bike.notifyRaw(SimulatedFrames.encode(0x1009, addressBytes))
                // An identity reply with a bad CRC, stray bytes between frames, a cut reply (the next frame ends it).
                val identity = model.toByteArray() + ByteArray(34 - model.length) + serial.toByteArray() + ByteArray(1)
                bike.notifyRaw(
                    SimulatedFrames.encode(0xFF01, identity).also {
                        it[it.size - 3] =
                            (it[it.size - 3] + 1).toByte()
                    },
                )
                bike.notifyRaw(addressBytes + serial.toByteArray())
                bike.notifyRaw(SimulatedFrames.encode(0xFF01, identity).copyOf(30))
                bike.statusPayloadLength = 51
                delay(3.seconds)
                bike.statusPayloadLength = null
                bike.switchOff()
                delay(5.seconds)
                rig.session.setSpeedLimit(SpeedLimit.KMH_25) // refused: the bike is off
                bike.switchOn()
                delay(5.seconds)
                // A reply the dropped link cuts short.
                bike.notifyRaw(SimulatedFrames.encode(0xFF01, identity).copyOf(40))
                bike.dropLink()
                // The first reconnection fails (the exception above), the next one re-applies the kept speed.
                withTimeout(5.minutes) {
                    rig.session.state
                        .drop(1)
                        .first { it is SessionState.Ready }
                }
                delay(10.seconds)
            }
            // A check of another bike, found by its advertisement.
            rig.session.start(rig.transport.scan(other.address).first())
            delay(20.seconds)
            rig.session.stop()
            // The compatibility check of the bike with its own services, recorded with them.
            val check = CompatibilityCheck(rig.session).run(vendor.address)
            assertEquals(CompatibilityOutcome.Checked, check.outcome)

            val exported = rig.out.toString()
            val events = rig.records().map { it.s("event") }
            if (!withEmail) {
                // What the scan must have seen, so that it can't pass on a session that did little.
                for (text in listOf(
                    "set_speed_limit_16",
                    "set_lights_on",
                    "\"name\":\"refused\"",
                    "\"name\":\"reapply\"",
                    "\"name\":\"reapply_assist_skipped\"",
                    "\"command\":\"1009\"",
                    if (model ==
                        ProfileRegistry.REFERENCE_MODEL
                    ) {
                        "set_assist_level_3"
                    } else {
                        "\"value\":\"not_supported\""
                    },
                    "\"name\":\"retrying\"",
                    "\"name\":\"gatt_status\"",
                    "\"command\":\"other\"",
                    "\"sanitised\":true",
                )) {
                    assertTrue(exported.contains(text), "the log has $text")
                }
                assertTrue(events.count { it == "identity" } >= 3, "identity records")
                val kinds =
                    rig
                        .records()
                        .filter { it.s("event") == "rx" }
                        .map { it.s("kind") }
                        .toSet()
                assertEquals(setOf("frame", "unframed", "malformed", "incomplete"), kinds)
                assertTrue(events.count { it == "error" } >= 2, "error records")
            } else {
                assertTrue(exported.contains("\"usable\":false"))
            }
            assertTrue(events.first() == "session_start" && "scan_result" in events)
            assertTrue(exported.contains("\"uuid\":\"6e400002-****-****-****-************\""), "the check's services")

            val secrets =
                Secrets.address(bike.address) +
                    Secrets.address(other.address) +
                    Secrets.address(vendor.address) +
                    Secrets.serial(vendor.serial) +
                    Secrets.serial(serial) +
                    Secrets.serial(other.serial) +
                    if (withEmail) listOf(model, Hex.encode(model.toByteArray())) else emptyList()
            // The address's short forms (its last bytes) could match a status frame's hex by chance: those
            // are looked for outside the frames' hex. Every other form is looked for everywhere.
            val outsideHex = exported.replace(rawHex, "")
            Secrets.assertAbsent(outsideHex, secrets)
            Secrets.assertAbsent(
                exported,
                Secrets.serial(serial) + Secrets.serial(other.serial) + secrets.filter { it.length >= 12 },
            )

            assertTrue(mac.find(exported) == null, "shaped like an address: ${mac.find(exported)?.value}")
            assertTrue(dotted.find(exported) == null, "shaped like an address: ${dotted.find(exported)?.value}")
            assertTrue(email.find(exported) == null, "shaped like an e-mail address")
            // As the sanitiser reads a name's suffix: ENGWE, a dash, a space or nothing, then up to 4 hex digits.
            val suffix = Regex("(?i)ENGWE[- ]?[0-9a-f]{1,4}(?![0-9a-z])")
            assertTrue(suffix.find(exported) == null, "an advertised name: ${suffix.find(exported)?.value}")

            // Long hex only in the frames' hex: every other value is checked, numbers apart, also inside the
            // compatibility check's nested lists.
            fun texts(element: JsonElement): List<String> =
                when (element) {
                    is JsonObject -> element.filterKeys { it != "raw_hex" }.values.flatMap(::texts)
                    is JsonArray -> element.flatMap(::texts)
                    is JsonPrimitive -> listOfNotNull(element.takeIf { it.isString }?.content)
                }
            for (record in rig.records()) {
                for (text in texts(record)) assertTrue(longHex.find(text) == null, "long hex: $text")
            }
            // No time of day: no clock times, no epoch-sized numbers, the injected date only, and every time
            // no later than the virtual time that passed.
            assertTrue(timeOfDay.find(outsideHex) == null, "a time of day: ${timeOfDay.find(outsideHex)?.value}")
            assertTrue(epoch.find(outsideHex) == null, "an epoch-sized number: ${epoch.find(outsideHex)?.value}")
            assertEquals(
                setOf("2026-01-02"),
                Regex("\\d{4}-\\d{2}-\\d{2}").findAll(outsideHex).map { it.value }.toSet(),
            )
            val elapsed = testScheduler.currentTime / 1000.0
            for (record in rig.records()) {
                val t = (record["t"] as? JsonPrimitive)?.doubleOrNull ?: continue
                assertTrue(t <= elapsed, "t $t after $elapsed s")
            }
        }
}
