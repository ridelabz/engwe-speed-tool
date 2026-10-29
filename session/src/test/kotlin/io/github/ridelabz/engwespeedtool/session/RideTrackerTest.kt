package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.Frame
import io.github.ridelabz.engwespeedtool.protocol.FrameCodec
import io.github.ridelabz.engwespeedtool.protocol.Hex
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ride tracker on status payloads captured from the reference bike. */
class RideTrackerTest {
    private val vectors =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/test-vectors.json")).readText()).jsonObject
    private val frames: Map<String, Frame> =
        vectors.getValue("status_frames").jsonArray.associate {
            val o = it.jsonObject
            val payload = Hex.decode(o.getValue("payload").jsonPrimitive.content)
            o.getValue("name").jsonPrimitive.content to FrameCodec.decode(SimulatedFrames.encode(0x2A63, payload))
        }
    private val layout = ProfileRegistry.bundled().find(ProfileRegistry.REFERENCE_MODEL)!!.statusLayout

    @Test
    fun `the captured display off and on is one power-on`() {
        val sequence =
            vectors
                .getValue("status_sequences")
                .jsonArray
                .single()
                .jsonObject
                .getValue("frames")
                .jsonArray
                .map { frames.getValue(it.jsonPrimitive.content) }
        val tracker = RideTracker(layout, powerOns = 0)
        val seen =
            sequence.map {
                tracker.update(it)
                tracker.data.power to tracker.data.powerOns
            }
        assertEquals(
            listOf(PowerState.ON to 0, PowerState.OFF to 0, PowerState.POWERING_ON to 1, PowerState.ON to 1),
            seen,
        )
        assertTrue(tracker.data.powerVerified)
    }

    @Test
    fun `a power-on is only counted after the bike was seen off`() {
        val tracker = RideTracker(layout, powerOns = 3)
        for (name in listOf("display_powering_on", "display_on_again", "idle_display_on")) {
            tracker.update(frames.getValue(name))
        }
        assertEquals(3, tracker.data.powerOns, "joining a bike that is switching on isn't a power-on")
        tracker.update(frames.getValue("display_off"))
        assertTrue(tracker.data.bikeOff)
        tracker.update(frames.getValue("display_off"))
        tracker.update(frames.getValue("display_on_again"))
        assertEquals(4, tracker.data.powerOns)
    }

    @Test
    fun `values come from the latest frame, and a frame of another length shows no data`() {
        val tracker = RideTracker(layout, powerOns = 0)
        tracker.update(frames.getValue("wheel_spinning"))
        assertEquals(
            10.2,
            tracker.data.status!!
                .speedKmh!!
                .value,
        )
        assertEquals(
            10.9,
            tracker.data.status!!
                .maxSpeedKmh!!
                .value,
        )
        assertFalse(tracker.data.stale)
        tracker.stale()
        assertTrue(tracker.data.stale)
        assertEquals(
            10.2,
            tracker.data.status!!
                .speedKmh!!
                .value,
            "stale keeps the last values, marked",
        )
        val short = FrameCodec.decode(SimulatedFrames.encode(0x2A63, ByteArray(50)))
        tracker.update(short)
        assertTrue(tracker.data.unreadable)
        assertNull(tracker.data.status)
        assertEquals(PowerState.UNKNOWN, tracker.data.power)
    }
}
