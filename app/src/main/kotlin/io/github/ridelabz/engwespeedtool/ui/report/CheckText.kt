package io.github.ridelabz.engwespeedtool.ui.report

import io.github.ridelabz.engwespeedtool.session.CheckResult
import io.github.ridelabz.engwespeedtool.session.CheckedField
import io.github.ridelabz.engwespeedtool.session.CompatibilityOutcome
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import io.github.ridelabz.engwespeedtool.session.FieldResult
import io.github.ridelabz.engwespeedtool.session.ProtocolCheck

/**
 * The words of a check's summary, from string resources. They are English whatever the app's language: the
 * summary goes to the issue tracker.
 */
class CheckWords(
    /** The outcome line, with the status frames read and the seconds they were read in (null: none). */
    val outcome: (CompatibilityOutcome, frames: Int, seconds: Int?) -> String,
    val check: (ProtocolCheck) -> String,
    val field: (CheckedField) -> String,
    /** A line of names: "Passed: …", "Failed: …" and so on, by what they share. */
    val checks: (CheckResult, names: String) -> String,
    val values: (FieldResult, names: String) -> String,
    /** The line of values read whose meaning isn't verified for the bike. */
    val unverified: (names: String) -> String,
    /** In that line, every value read. */
    val allValues: String,
    val unreadable: String,
)

/**
 * A compatibility check's result as a few lines of text, for a report's form and for sharing: how it ended,
 * the protocol checks by result, the status values by how plausible they were, never the values themselves.
 * Problems come first, then the values read with an unverified meaning, then what passed: the form's field
 * is cut at a line when it is too long, and keeps the problems.
 */
object CheckText {
    fun of(
        report: CompatibilityReport,
        words: CheckWords,
    ): String {
        fun checks(result: CheckResult): String? =
            report.checks
                .filter { it.second == result }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { words.check(it.first) }
                ?.let { words.checks(result, it) }

        fun values(result: FieldResult): String? =
            report.fields
                .filter { it.result == result }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { words.field(it.field) }
                ?.let { words.values(result, it) }
        val read = report.fields.filter { it.result != FieldResult.NOT_READ }
        val unverified = read.filterNot { it.verified }
        val unverifiedLine =
            when {
                unverified.isEmpty() -> null
                unverified.size == read.size && read.size > 1 -> words.unverified(words.allValues)
                else -> words.unverified(unverified.joinToString(", ") { words.field(it.field) })
            }
        return listOfNotNull(
            words.outcome(report.outcome, report.statusFrames, report.sampledSeconds),
            checks(CheckResult.FAILED),
            checks(CheckResult.NOT_RUN),
            words.unreadable.takeIf { report.unreadableFrames },
            values(FieldResult.OUT_OF_RANGE),
            values(FieldResult.WENT_BACK),
            unverifiedLine,
            checks(CheckResult.PASSED),
            values(FieldResult.PLAUSIBLE),
            values(FieldResult.NOT_READ),
        ).joinToString("\n")
    }

    /**
     * The text shared from the check's result screen: a [title] (the app and its version), the bike's [model]
     * and [verification] lines as the check found them (absent when it didn't get that far), then [summary].
     */
    fun share(
        title: String,
        model: String?,
        verification: String?,
        summary: String,
    ): String = listOfNotNull(title, model, verification, summary).joinToString("\n")
}
