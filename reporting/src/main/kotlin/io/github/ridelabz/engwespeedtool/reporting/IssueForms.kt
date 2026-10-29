package io.github.ridelabz.engwespeedtool.reporting

/**
 * The repository's issue forms (`.github/ISSUE_TEMPLATE/`) and the fields of each that the app fills in
 * through the form's URL (`…/issues/new?template=<file>&<field id>=<value>`). The field IDs are a contract
 * with the forms: a test checks that each form has every field listed here, as one the URL can fill in.
 */
public enum class IssueForm(
    /** The form's file name, the `template` parameter. */
    public val template: String,
    /** The fields the app fills in. */
    public val fields: Set<IssueField>,
) {
    COMPATIBILITY(
        "compatibility_report.yml",
        setOf(
            IssueField.MODEL_IDENTITY,
            IssueField.VERIFICATION,
            IssueField.APP_VERSION,
            IssueField.ANDROID,
            IssueField.BUILD_TESTED,
            IssueField.PROTOCOL_CHECKS,
            IssueField.SPEEDS_CONFIRMED,
            IssueField.STOP_SPEED_16,
            IssueField.STOP_SPEED_25,
            IssueField.STOP_SPEED_32,
            IssueField.STOP_SPEED_45,
            IssueField.BACK_TO_25,
            IssueField.IOT_FIRMWARE,
            IssueField.ASSIST_CONFIRMED,
        ),
    ),
    PROBLEM(
        "bug_report.yml",
        setOf(
            IssueField.APP_VERSION,
            IssueField.ANDROID,
            IssueField.BUILD_TESTED,
            IssueField.MODEL_IDENTITY,
            IssueField.VERIFICATION,
            IssueField.IOT_FIRMWARE,
            IssueField.APP_STATE,
            IssueField.ERRORS,
        ),
    ),
    IDEA("feature_request.yml", setOf(IssueField.APP_VERSION)),
}

/** A form field the app fills in, by its ID in the forms. */
public enum class IssueField(
    public val id: String,
    /** A multi-line field (a textarea in the forms): line breaks are kept. */
    public val multiline: Boolean = false,
) {
    MODEL_IDENTITY("model_identity"),
    VERIFICATION("verification"),
    APP_VERSION("app_version"),

    /** The Android version and the phone's maker and model. */
    ANDROID("android"),

    /** The release version, or `PR #n` for a test build (the test-build bot links this field too). */
    BUILD_TESTED("build_tested"),
    PROTOCOL_CHECKS("protocol_checks", multiline = true),
    SPEEDS_CONFIRMED("speeds_confirmed", multiline = true),

    /** Where assistance stopped with each speed set, as the rider typed it. */
    STOP_SPEED_16("stop_speed_16"),
    STOP_SPEED_25("stop_speed_25"),
    STOP_SPEED_32("stop_speed_32"),
    STOP_SPEED_45("stop_speed_45"),

    /** Whether assistance was back to stopping at 25 km/h after the bike was switched off and on. */
    BACK_TO_25("back_to_25"),

    /** The IoT firmware version as the rider typed it from the ENGWE app. */
    IOT_FIRMWARE("iot_firmware"),

    /** Assist levels set from the app and whether the bike confirmed them. */
    ASSIST_CONFIRMED("assist_confirmed", multiline = true),
    APP_STATE("app_state", multiline = true),
    ERRORS("errors", multiline = true),
}

/** A link to an issue form, and the fields left out of it (too long, or no room left). */
public data class IssueLink(
    val url: String,
    val omitted: List<IssueField>,
)

/**
 * The link that opens [form] on GitHub with [values] filled in. The browser opens it: the app itself sends
 * nothing.
 *
 * Only the form's own fields go in, by the priority of [IssueForm.fields] (short fields first): a field that
 * doesn't fit the link's [maxLength] is left out and the next ones are still tried. A single-line field is
 * one line (line breaks become spaces) and goes in whole or not at all: cut, it would be a different value.
 * A multi-line field longer than [MAX_VALUE] is cut at a line break and ends with "…". Left-out fields are
 * listed, so that the report can say so.
 */
public object IssueUrl {
    public const val MAX_VALUE: Int = 300
    public const val MAX_LENGTH: Int = 2000
    private const val MORE = "…"

    public fun of(
        repository: String,
        form: IssueForm,
        values: Map<IssueField, String>,
        maxLength: Int = MAX_LENGTH,
    ): IssueLink {
        val url = StringBuilder(repository.trimEnd('/')).append("/issues/new?template=").append(encode(form.template))
        val omitted = mutableListOf<IssueField>()
        for (field in form.fields) {
            val raw = values[field]?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val value =
                if (field.multiline) {
                    cut(raw)
                } else {
                    raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").takeIf {
                        it.length <=
                            MAX_VALUE
                    }
                }
            val parameter = value?.let { "&" + field.id + "=" + encode(it) }
            val fits = parameter != null && url.length + parameter.length <= maxLength
            if (fits) url.append(parameter) else omitted += field
        }
        return IssueLink(url.toString(), omitted)
    }

    /** [text] within [MAX_VALUE], cut after its last whole line that fits, then "…"; null if no line fits. */
    private fun cut(text: String): String? {
        if (text.length <= MAX_VALUE) return text
        val room = MAX_VALUE - "\n$MORE".length
        val end = text.lastIndexOf('\n', room)
        return if (end <= 0) null else text.substring(0, end).trimEnd() + "\n" + MORE
    }

    /** Percent-encoding of the UTF-8 bytes, all but the unreserved characters (`A-Z a-z 0-9 - . _ ~`). */
    private fun encode(value: String): String {
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
