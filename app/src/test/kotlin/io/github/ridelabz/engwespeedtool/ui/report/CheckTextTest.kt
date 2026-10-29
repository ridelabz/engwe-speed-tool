package io.github.ridelabz.engwespeedtool.ui.report

import io.github.ridelabz.engwespeedtool.protocol.BikeClassifier
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.ProtocolChecks
import io.github.ridelabz.engwespeedtool.reporting.IssueField
import io.github.ridelabz.engwespeedtool.reporting.IssueForm
import io.github.ridelabz.engwespeedtool.reporting.IssueUrl
import io.github.ridelabz.engwespeedtool.session.CheckFailure
import io.github.ridelabz.engwespeedtool.session.CheckedField
import io.github.ridelabz.engwespeedtool.session.CompatibilityOutcome
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import io.github.ridelabz.engwespeedtool.session.FieldCheck
import io.github.ridelabz.engwespeedtool.session.FieldResult
import io.github.ridelabz.engwespeedtool.session.StopReason
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.ui.checkWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class CheckTextTest {
    private val words =
        CheckWords(
            outcome = { outcome, frames, seconds -> "$outcome $frames/$seconds" },
            check = { it.name.lowercase() },
            field = { it.name.lowercase() },
            checks = { result, names -> "$result: $names" },
            values = { result, names -> "$result: $names" },
            unverified = { "UNVERIFIED: $it" },
            allValues = "all",
            unreadable = "unreadable",
        )

    private val english get() = checkWords(RuntimeEnvironment.getApplication().resources)

    private val verified =
        BikeClassifier.classify(ProtocolChecks(true, true, ProfileRegistry.REFERENCE_MODEL), ProfileRegistry.bundled())

    private fun field(
        field: CheckedField,
        result: FieldResult,
        verified: Boolean = true,
    ) = FieldCheck(field, result, verified)

    @Test
    fun `problems come first, then the unverified values, then what passed`() {
        val report =
            CompatibilityReport(
                CompatibilityOutcome.Checked,
                verified,
                listOf(
                    field(CheckedField.SPEED, FieldResult.PLAUSIBLE),
                    field(CheckedField.ODOMETER, FieldResult.WENT_BACK),
                    field(CheckedField.BATTERY, FieldResult.OUT_OF_RANGE),
                    field(CheckedField.VOLTAGE, FieldResult.PLAUSIBLE, verified = false),
                    // A value never read isn't listed as unverified: its meaning didn't matter.
                    field(CheckedField.RIDE_TIME, FieldResult.NOT_READ, verified = false),
                ),
                29,
                unreadableFrames = true,
                sampledSeconds = 30,
            )
        assertEquals(
            listOf(
                "Checked 29/30",
                "unreadable",
                "OUT_OF_RANGE: battery",
                "WENT_BACK: odometer",
                "UNVERIFIED: voltage",
                "PASSED: service, notify, acknowledged_write, status, identity",
                "PLAUSIBLE: speed, voltage",
                "NOT_READ: ride_time",
            ).joinToString("\n"),
            CheckText.of(report, words),
        )
    }

    @Test
    fun `when every value read is unverified, the line says so in short`() {
        val report =
            CompatibilityReport(
                CompatibilityOutcome.Checked,
                verified,
                CheckedField.entries.map { field(it, FieldResult.PLAUSIBLE, verified = false) },
                30,
                unreadableFrames = false,
                sampledSeconds = 30,
            )
        assertTrue(CheckText.of(report, words).lines().contains("UNVERIFIED: all"))
    }

    @Test
    fun `a failed check lists what failed and what never ran, and no values`() {
        val report =
            CompatibilityReport(
                CompatibilityOutcome.Incompatible(CheckFailure.NO_NOTIFY),
                null,
                emptyList(),
                0,
                false,
                null,
            )
        assertEquals(
            listOf(
                "Incompatible(failure=NO_NOTIFY) 0/null",
                "FAILED: notify",
                "NOT_RUN: status, identity",
                "PASSED: service, acknowledged_write",
            ).joinToString("\n"),
            CheckText.of(report, words),
        )
    }

    @Test
    fun `the reference bike's summary goes into the form whole`() {
        val report =
            CompatibilityReport(
                CompatibilityOutcome.Checked,
                verified,
                CheckedField.entries.map {
                    // As the reference profile marks them: voltage and ride time unverified.
                    field(
                        it,
                        FieldResult.PLAUSIBLE,
                        verified =
                            it != CheckedField.VOLTAGE && it != CheckedField.RIDE_TIME,
                    )
                },
                30,
                unreadableFrames = false,
                sampledSeconds = 30,
            )
        val text = CheckText.of(report, english)
        assertEquals(
            listOf(
                "Complete; status messages read: 30 in 30 s",
                "Meaning not verified: voltage, ride time",
                "Passed: bike service, notifications, confirmed writes, status, model",
                "Plausible: speed, max speed, odometer, assist level, battery, lights, on/off, voltage, ride time",
            ).joinToString("\n"),
            text,
        )
        assertTrue("${text.length} characters", text.length <= IssueUrl.MAX_VALUE)
    }

    @Test
    fun `however long the summary, a link to the form keeps every problem line`() {
        // Every result group present, with three-digit counts and the longest outcome, for each split of the
        // values between out of range and gone down.
        for (split in 1 until CheckedField.entries.size - 2) {
            val fields =
                CheckedField.entries.mapIndexed { i, f ->
                    when {
                        i < split -> field(f, FieldResult.OUT_OF_RANGE, verified = false)
                        i == split -> field(f, FieldResult.WENT_BACK, verified = false)
                        i == split + 1 -> field(f, FieldResult.NOT_READ)
                        else -> field(f, FieldResult.PLAUSIBLE, verified = i % 2 == 0)
                    }
                }
            val report =
                CompatibilityReport(
                    CompatibilityOutcome.LinkLost,
                    verified,
                    fields,
                    999,
                    unreadableFrames = true,
                    sampledSeconds = 999,
                )
            val text = CheckText.of(report, english)
            val link =
                IssueUrl.of(
                    "https://github.com/owner/repo",
                    IssueForm.COMPATIBILITY,
                    mapOf(IssueField.PROTOCOL_CHECKS to text),
                )
            val sent =
                java.net.URLDecoder.decode(
                    link.url.substringAfter(IssueField.PROTOCOL_CHECKS.id + "="),
                    Charsets.UTF_8,
                )
            val problems = text.lines().takeWhile { !it.startsWith("Meaning not verified") && !it.startsWith("Passed") }
            assertEquals("outcome, unreadable, out of range, gone down", 4, problems.size)
            for (line in problems) assertTrue("$split: $line\n$sent", line in sent.lines())
        }
    }

    @Test
    fun `a share holds the title, the model and verification found, and the summary`() {
        assertEquals("T\nM\nV\nS", CheckText.share("T", "M", "V", "S"))
        assertEquals("T\nS", CheckText.share("T", null, null, "S"))
        val notFound =
            CompatibilityReport(
                CompatibilityOutcome.NotConnected(StopReason.NOT_FOUND),
                null,
                emptyList(),
                0,
                false,
                null,
            )
        assertEquals(
            "Not connected: the bike wasn't found\nNot run: bike service, notifications, confirmed writes, status, model",
            CheckText.of(notFound, english),
        )
        // A connection lost before the checks ended doesn't read as never connected.
        val lost =
            CompatibilityReport(
                CompatibilityOutcome.NotConnected(StopReason.LINK_LOST),
                null,
                emptyList(),
                0,
                false,
                null,
            )
        assertTrue(CheckText.of(lost, english).startsWith("Connection lost before the checks finished"))
    }
}
