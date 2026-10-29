package io.github.ridelabz.engwespeedtool.ui.report

import io.github.ridelabz.engwespeedtool.reporting.IssueField
import io.github.ridelabz.engwespeedtool.reporting.IssueForm
import io.github.ridelabz.engwespeedtool.reporting.IssueLink
import io.github.ridelabz.engwespeedtool.reporting.IssueUrl
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import java.time.LocalDate
import java.util.Locale

/** What a report is about, and the issue form it opens. */
enum class ReportType(
    val form: IssueForm,
) {
    COMPATIBILITY(IssueForm.COMPATIBILITY),
    PROBLEM(IssueForm.PROBLEM),
    IDEA(IssueForm.IDEA),
    ;

    /** Whether the report is about the bike, so the IoT firmware version is asked for. */
    val aboutTheBike: Boolean get() = this != IDEA
}

/** What the rider typed in the report's first step. */
data class ReportAnswers(
    /** Where assistance stopped, in km/h. */
    val stopSpeedKmh: Int? = null,
    /** The IoT firmware version as typed (see [IotVersion]). */
    val iotVersion: String = "",
    /** The ride test's answers, when the report comes from it: about an earlier run. */
    val rideTest: RideTest? = null,
    /** The compatibility check's result, when the report comes from its result screen. */
    val check: CompatibilityReport? = null,
)

/** Did assistance stop at the speed set? */
enum class RideTestStop {
    YES,
    NO,
    NOT_RIDDEN,
}

/** After the bike was switched off and on, was assistance back to stopping at 25 km/h? */
enum class RideTestRestart {
    YES,
    NO,
    NOT_CHECKED,
}

/**
 * The ride test's answers about [kmh], a speed the saved bike confirmed in an earlier run (whose stored log
 * is [log], if any). Unanswered questions are null and aren't shared.
 */
data class RideTest(
    val kmh: Int,
    val log: Int?,
    val stopped: RideTestStop? = null,
    /** Where assistance stopped instead, when it didn't stop at [kmh]. */
    val stoppedAtKmh: Int? = null,
    val restart: RideTestRestart? = null,
) {
    /** Where assistance stopped, as the report's answer: [kmh] for yes, the speed typed for no. */
    val stopSpeedKmh: Int?
        get() =
            when (stopped) {
                RideTestStop.YES -> kmh
                RideTestStop.NO -> stoppedAtKmh?.takeIf { it > 0 }
                RideTestStop.NOT_RIDDEN, null -> null
            }
}

/**
 * The bike's IoT firmware version, which the app can't read: the rider types it as the ENGWE app shows it.
 * Input that doesn't look like a version is refused as a whole, never trimmed into shape, so that nothing
 * else the rider types or pastes (an address, a serial, a phone number) reaches a report, even in part.
 */
object IotVersion {
    /** Shorter than any run the session log treats as an identifier. */
    const val MAX_LENGTH: Int = 11

    /** Longer runs of digits could be a phone number. */
    private const val MAX_DIGIT_RUN = 6
    private val LONG_DIGIT_RUN = Regex("[0-9]{${MAX_DIGIT_RUN + 1},}")

    /** Only hex digits, longer than this: could be most of a Bluetooth address typed by hand. */
    private const val MAX_HEX_ONLY = 8

    private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun allowed(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-'

    /**
     * What the text field takes from [input] (spaces dropped): ASCII letters, digits, dots and dashes, at
     * most [MAX_LENGTH], with no run of more than 6 digits and not 9 or more hex digits alone. Null refuses
     * the input: the field keeps its old value.
     */
    fun accepted(input: String): String? {
        val v = input.filterNot { it.isWhitespace() }
        val ok =
            v.length <= MAX_LENGTH &&
                v.all(::allowed) &&
                !LONG_DIGIT_RUN.containsMatchIn(v) &&
                !(v.length > MAX_HEX_ONLY && v.all(::hex))
        return v.takeIf { ok }
    }

    /** The version to share, in capitals; null when nothing usable was typed (it needs a digit). */
    fun shared(input: String): String? =
        accepted(input)?.uppercase(Locale.ROOT)?.takeIf { v -> v.any { it in '0'..'9' } }

    /** True when [input] holds something that won't be shared, so the field can say why. */
    fun unusable(input: String): Boolean = input.isNotBlank() && shared(input) == null
}

/**
 * One line of what a report shares, as the rider sees it before anything leaves the phone; [field] is the
 * issue form's field it fills in (exactly [value]).
 */
data class SharedItem(
    val label: String,
    val value: String,
    val field: IssueField? = null,
)

/** What the report sheet shows in its preview step. Nothing in it identifies the rider, the phone or the bike. */
data class ReportPreview(
    val items: List<SharedItem>,
    /** Demo mode saves and opens nothing; the sheet says so. */
    val demo: Boolean,
    /** Whether the report comes with the session log (problems and compatibility reports; not ideas). */
    val needsLog: Boolean,
    /** The issue form it opens; null in previews that open nothing. */
    val form: IssueForm? = null,
    /** The speed (km/h) the bike confirmed last in this run, which the "assistance stopped at" answer is about. */
    val stopSpeedFor: Int? = null,
    /** This run's log is still being read: the speeds and errors aren't known yet. */
    val loading: Boolean = false,
) {
    /** The link that opens [form] with exactly the items shown filled in. */
    fun link(repository: String): IssueLink? =
        form?.let { f ->
            IssueUrl.of(repository, f, items.mapNotNull { item -> item.field?.let { it to item.value } }.toMap())
        }
}

/**
 * A report sheet the rider opened, possibly with its type already chosen and [answers] given; then, once the
 * rider sends it, whether it is [sending] and how it went ([sent]).
 */
data class ReportRequest(
    val type: ReportType?,
    val answers: ReportAnswers = ReportAnswers(),
    val sending: ReportSending? = null,
    val sent: ReportSent? = null,
)

/** A log file's name, with the date and number that make it. */
data class LogFile(
    val name: String,
    val date: LocalDate,
    val number: Int,
)

/**
 * A report on its way: the [preview] it opens, where it is ([stage]), the file its log goes to ([file]), and
 * once known, how the log was saved ([log]).
 */
data class ReportSending(
    val preview: ReportPreview,
    val stage: Stage,
    val file: LogFile? = null,
    val log: LogSaved? = null,
) {
    enum class Stage {
        /** Saving the log in Downloads. */
        SAVING,

        /** Downloads couldn't take it: the file picker opens, with [file]'s name. */
        PICK,

        /** The picker answered: the log is written to the file picked. */
        WRITING,

        /** The form opens in the browser. */
        OPEN,
    }
}

/** How saving a session log ended. */
sealed interface LogSaved {
    /** In the phone's Downloads folder, as [name]. */
    data class InDownloads(
        val name: String,
    ) : LogSaved

    /** Where the rider chose in Android's file picker (Downloads couldn't take it). */
    data object Chosen : LogSaved

    /** Not saved. */
    data object NotSaved : LogSaved
}

/** How sending a report went: where its log is, if it has one ([log]), and whether the form [opened]. */
data class ReportSent(
    val log: LogSaved?,
    val opened: Boolean,
    /** Demo mode: nothing was saved or opened. */
    val demo: Boolean = false,
)

/** Labels for the report preview, from resources. */
data class ReportLabels(
    val app: String,
    val android: String,
    val model: String,
    val verification: String,
    val build: String,
    val checks: String,
    val results: String,
    val errors: String,
    val stopSpeed: (Int) -> String,
    val iotVersion: String,
    val state: String,
    val verified: String,
    val unverified: String,
    val checksPassed: String,
    val confirmed: (Int) -> String,
    val notConfirmed: (Int) -> String,
    val errorCount: (Int) -> String,
    val kmh: (Int) -> String,
    /** The build tested: the release version, or the test build's pull request. */
    val buildName: String,
    val backTo25: String = "",
    /** The ride test's answer about the restart, as the form takes it. */
    val restart: (RideTestRestart) -> String = { it.name },
    /** A compatibility check's result, as the form takes it ([CheckText]). */
    val check: (CompatibilityReport) -> String,
    /** The assist levels set from the app, and how each ended, as the form takes them. */
    val assistResults: String,
    val assistConfirmed: (Int) -> String,
    val assistNotConfirmed: (Int) -> String,
)
