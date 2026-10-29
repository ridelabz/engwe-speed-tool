package io.github.ridelabz.engwespeedtool.protocol

import io.github.ridelabz.engwespeedtool.protocol.Vectors.str
import io.github.ridelabz.engwespeedtool.protocol.Vectors.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusDecoderTest {
    private val registry = ProfileRegistry.bundled()
    private val layout = checkNotNull(registry.find("ENGWE_Omni_ebike1100EU317")).statusLayout

    private fun frameOf(vector: JsonObject) =
        FrameCodec.decode(FrameCodec.encode(0x2A63, Hex.decode(vector.str("payload"))))

    private fun decode(vector: JsonObject) = checkNotNull(StatusDecoder.decode(frameOf(vector), layout))

    @Test
    fun `captured status frames decode to the values seen on the display`() {
        for (vector in Vectors.list("status_frames")) {
            val name = vector.str("name")
            val exp = vector.getValue("expected").jsonObject
            val r = decode(vector)
            assertEquals(exp.getValue("speed_kmh").jsonPrimitive.double, r.speedKmh?.value, name)
            assertEquals(exp.getValue("max_speed_kmh").jsonPrimitive.double, r.maxSpeedKmh?.value, name)
            assertEquals(exp.getValue("odometer_km").jsonPrimitive.double, r.odometerKm?.value, name)
            assertEquals(exp.getValue("assist_level").jsonPrimitive.int, r.assistLevel?.value, name)
            assertEquals(exp.getValue("battery_pct").jsonPrimitive.int, r.batteryPercent?.value, name)
            assertEquals(exp.str("lights").uppercase(), r.lights?.value?.name, name)
            val flags = exp.getValue("power_flags").jsonArray.map { it.jsonPrimitive.int }
            val power =
                when (flags) {
                    listOf(1, 1) -> PowerState.ON
                    listOf(0, 0) -> PowerState.OFF
                    listOf(0, 1) -> PowerState.POWERING_ON
                    else -> PowerState.UNKNOWN
                }
            assertEquals(power, r.power?.value, name)
            assertEquals(exp.getValue("temperature_raw").jsonPrimitive.int, r.temperatureRaw?.value, name)
            assertEquals(exp.getValue("battery_health_raw").jsonPrimitive.int, r.batteryHealthRaw?.value, name)
            assertEquals(exp.getValue("voltage_v").jsonPrimitive.double, r.voltageV?.value, name)
            assertEquals(exp.getValue("ride_time_min").jsonPrimitive.int, r.rideTimeMinutes?.value, name)
        }
    }

    @Test
    fun `verified flags follow the profile`() {
        val r = decode(Vectors.list("status_frames").first())
        assertTrue(listOf(r.speedKmh, r.maxSpeedKmh, r.odometerKm, r.batteryPercent).all { it!!.verified })
        assertTrue(r.assistLevel!!.verified && r.lights!!.verified && r.power!!.verified)
        assertTrue(listOf(r.temperatureRaw, r.batteryHealthRaw).all { it!!.verified })
        assertTrue(listOf(r.voltageV, r.rideTimeMinutes).all { it!!.verified })
    }

    @Test
    fun `unverified bikes get the same values all marked unverified`() {
        val frame = frameOf(Vectors.list("status_frames").first())
        val r = checkNotNull(StatusDecoder.decode(frame, registry.unverifiedLayout))
        assertEquals(80, r.batteryPercent?.value)
        assertFalse(r.batteryPercent!!.verified || r.speedKmh!!.verified || r.power!!.verified)
    }

    @Test
    fun `display off and on sequence`() {
        val byName = Vectors.list("status_frames").associateBy { it.str("name") }
        for (seq in Vectors.list("status_sequences")) {
            val states = seq.strings("frames").map { decode(byName.getValue(it)).power?.value }
            assertEquals(listOf(PowerState.ON, PowerState.OFF, PowerState.POWERING_ON, PowerState.ON), states)
        }
    }

    @Test
    fun `anything else gives no data`() {
        val payload = Hex.decode(Vectors.list("status_frames").first().str("payload"))
        val good = FrameCodec.encode(0x2A63, payload)
        val badCrc = good.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertNull(StatusDecoder.decode(FrameCodec.decode(badCrc), layout), "bad CRC")
        assertNull(StatusDecoder.decode(FrameCodec.decode(FrameCodec.encode(0x2A63, payload.copyOf(53))), layout))
        assertNull(StatusDecoder.decode(FrameCodec.decode(FrameCodec.encode(0x2A64, byteArrayOf(1))), layout))
        assertFalse(StatusDecoder.isStatus(FrameCodec.decode(Vectors.commandFrame("lights_on"))))
    }

    @Test
    fun `unobserved power flag combinations are unknown`() {
        val base = Hex.decode(Vectors.list("status_frames").first().str("payload"))
        for ((a, b) in listOf(1 to 0, 2 to 2, 0 to 2, 0xFF to 0xFF)) {
            val payload =
                base.copyOf().also {
                    it[39] = a.toByte()
                    it[40] = b.toByte()
                }
            val r = checkNotNull(StatusDecoder.decode(FrameCodec.decode(FrameCodec.encode(0x2A63, payload)), layout))
            assertEquals(PowerState.UNKNOWN, r.power?.value, "$a/$b")
        }
    }

    @Test
    fun `impossible battery values give no value`() {
        val payload = Hex.decode(Vectors.list("status_frames").first().str("payload")).also { it[21] = 101 }
        val r = checkNotNull(StatusDecoder.decode(FrameCodec.decode(FrameCodec.encode(0x2A63, payload)), layout))
        assertNull(r.batteryPercent)
    }

    @Test
    fun `unexpected light byte is unknown`() {
        val payload = Hex.decode(Vectors.list("status_frames").first().str("payload")).also { it[28] = 0x01 }
        val r = checkNotNull(StatusDecoder.decode(FrameCodec.decode(FrameCodec.encode(0x2A63, payload)), layout))
        assertEquals(Lights.UNKNOWN, r.lights?.value)
    }
}
