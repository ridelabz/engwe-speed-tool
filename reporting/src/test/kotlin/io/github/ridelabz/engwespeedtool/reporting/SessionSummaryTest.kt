package io.github.ridelabz.engwespeedtool.reporting

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Frame
import io.github.ridelabz.engwespeedtool.protocol.FrameReassembler
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.StreamEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SessionSummaryTest {
    private enum class Name { COMMAND, REFUSED, RETRYING }

    private enum class Outcome { CONFIRMED, NOT_CONFIRMED }

    private enum class Reason { BIKE_NOT_ON, LINK_LOST }

    private enum class Where { CRASH }

    private val meta = SessionMeta("0.2.0", "15", 35, "Google", "Pixel 9")

    private class Clock : SessionClock {
        var nanos = 0L

        override fun today(): LocalDate = LocalDate.of(2026, 10, 16)

        override fun elapsedNanos(): Long = nanos
    }

    @Test
    fun `reads back what the writer recorded`() {
        val out = StringBuilder()
        val clock = Clock()
        val log = SessionLog(out, clock, meta)
        log.outgoing(BikeCommand.ReadIdentity.toFrame())
        val reply = ByteArray(54)
        "ENGWE_Test_model1".toByteArray().copyInto(reply)
        "FAKESERIAL0000001".toByteArray().copyInto(reply, 34)
        val frame =
            FrameReassembler()
                .feed(TestFrames.encode(0xFF01, reply))
                .filterIsInstance<StreamEvent.Decoded>()
                .single()
                .frame
        log.identity(frame)
        clock.nanos = 2_000_000_000
        log.outgoing(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32).toFrame())
        log.state(Name.COMMAND, Outcome.CONFIRMED)
        log.state(Name.REFUSED, Reason.BIKE_NOT_ON)
        log.outgoing(BikeCommand.SetLights(true).toFrame())
        log.state(Name.COMMAND, Outcome.NOT_CONFIRMED)
        log.outgoing(BikeCommand.SetAssistLevel(AssistLevel.LEVEL_3).toFrame())
        log.state(Name.COMMAND, Outcome.CONFIRMED)
        log.outgoing(BikeCommand.SetLocked(true).toFrame())
        log.state(Name.COMMAND, Outcome.CONFIRMED)
        log.outgoing(BikeCommand.SetLocked(false).toFrame())
        log.state(Name.COMMAND, Outcome.NOT_CONFIRMED)
        log.outgoing(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25).toFrame())
        log.state(Name.RETRYING, Reason.LINK_LOST)
        clock.nanos = 61_500_000_000
        log.error(Where.CRASH, IllegalStateException("message never recorded"))

        val summary = SessionSummary.of(out.toString())
        assertEquals("2026-10-16", summary.date)
        assertEquals("0.2.0", summary.appVersion)
        assertEquals("15", summary.android)
        assertEquals("Google Pixel 9", summary.phone)
        assertEquals(
            listOf(
                SentCommand(32, null, SentCommand.Outcome.CONFIRMED),
                SentCommand(null, true, SentCommand.Outcome.NOT_CONFIRMED),
                SentCommand(null, null, SentCommand.Outcome.CONFIRMED, assistLevel = 3),
                SentCommand(null, null, SentCommand.Outcome.CONFIRMED, locked = true),
                SentCommand(null, null, SentCommand.Outcome.NOT_CONFIRMED, locked = false),
                SentCommand(25, null, null),
            ),
            summary.commands,
            "the identity read isn't a command",
        )
        assertEquals(listOf("bike_not_on"), summary.refused)
        assertEquals(1, summary.retries)
        assertEquals(1, summary.errors)
        assertEquals(61.5, summary.seconds)
        assertEquals(0, summary.unreadable)
        assertEquals(listOf("ENGWE_Test_model1"), summary.models, "the model as the log carries it, nothing after it")
    }

    @Test
    fun `lines that aren't records are counted, not read`() {
        val summary =
            SessionSummary.of(
                "not json\n{\"no_event\":1}\n\n{\"event\":\"classification\",\"verification\":\"verified\"}\n",
            )
        assertEquals(2, summary.unreadable)
        assertEquals(1, summary.records)
        assertEquals("verified", summary.verification)
        assertNull(summary.seconds)
    }

    private fun identity(model: String): Frame {
        val reply = ByteArray(54)
        model.toByteArray().copyInto(reply)
        return FrameReassembler()
            .feed(TestFrames.encode(0xFF01, reply))
            .filterIsInstance<StreamEvent.Decoded>()
            .single()
            .frame
    }

    @Test
    fun `every model the log carries is shown, each once, in the order read`() {
        val out = StringBuilder()
        val log = SessionLog(out, Clock(), meta)
        log.identity(identity("ENGWE_Test_model1"))
        log.identity(identity("ENGWE_Other_model"))
        log.identity(identity("ENGWE_Test_model1"))
        assertEquals(listOf("ENGWE_Test_model1", "ENGWE_Other_model"), SessionSummary.of(out.toString()).models)
    }

    @Test
    fun `an outcome belongs to the last command sent, never to an earlier one`() {
        val out = StringBuilder()
        val log = SessionLog(out, Clock(), meta)
        // Stopped before its outcome; then a confirmed one.
        log.outgoing(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32).toFrame())
        log.outgoing(BikeCommand.SetSpeedLimit(SpeedLimit.KMH_16).toFrame())
        log.state(Name.COMMAND, Outcome.CONFIRMED)
        // A second outcome for the same command isn't credited elsewhere.
        log.state(Name.COMMAND, Outcome.NOT_CONFIRMED)
        // After a write the summary doesn't know (the identity read), an outcome belongs to nothing it shows.
        log.outgoing(BikeCommand.SetLights(false).toFrame())
        log.outgoing(BikeCommand.ReadIdentity.toFrame())
        log.state(Name.COMMAND, Outcome.CONFIRMED)
        assertEquals(
            listOf(
                SentCommand(32, null, null),
                SentCommand(16, null, SentCommand.Outcome.CONFIRMED),
                SentCommand(null, false, null),
            ),
            SessionSummary.of(out.toString()).commands,
        )
    }

    @Test
    fun `a log without its start still reads`() {
        val out = StringBuilder()
        val log = SessionLog(out, Clock(), meta)
        log.state(Name.REFUSED, Reason.LINK_LOST)
        val withoutStart = out.lines().drop(1).joinToString("\n")
        val summary = SessionSummary.of(withoutStart)
        assertNull(summary.date)
        assertNull(summary.phone)
        assertEquals(listOf("link_lost"), summary.refused)
    }
}
