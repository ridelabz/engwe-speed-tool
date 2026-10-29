package io.github.ridelabz.engwespeedtool.protocol

/** Values the status frame can carry. Profiles map them to byte positions; they cannot add new kinds. */
public enum class StatusField(
    public val key: String,
) {
    SPEED("speed"),
    MAX_SPEED("max_speed"),
    ODOMETER("odometer"),
    ASSIST_LEVEL("assist_level"),
    BATTERY("battery"),
    LIGHTS("lights"),
    TEMPERATURE("temperature"),
    BATTERY_HEALTH("battery_health"),
    POWER_FLAG_A("power_flag_a"),
    POWER_FLAG_B("power_flag_b"),
    VOLTAGE("voltage"),

    /** The bike's total ride time, in minutes. */
    RIDE_TIME("ride_time"),
    ;

    public companion object {
        public fun ofKey(key: String): StatusField? = entries.firstOrNull { it.key == key }
    }
}

/** Where one value sits in the status payload. Multi-byte values are big-endian and unsigned. */
public class FieldSpec(
    public val field: StatusField,
    public val offset: Int,
    public val size: Int,
    public val scale: Double,
    public val verified: Boolean,
) {
    init {
        require(size in 1..2) { "${field.key}: size must be 1 or 2" }
        require(offset in 0 until StatusLayout.MAX_PAYLOAD_LENGTH) { "${field.key}: offset out of range" }
        require(scale > 0.0 && scale.isFinite()) { "${field.key}: scale must be positive" }
    }
}

/** Layout of the status payload for one bike model. Every field fits the payload and fields don't overlap. */
public class StatusLayout(
    public val payloadLength: Int,
    fields: List<FieldSpec>,
) {
    public val fields: List<FieldSpec> = fields.toList()

    init {
        require(payloadLength in 1..MAX_PAYLOAD_LENGTH) { "bad payload length $payloadLength" }
        require(
            this.fields
                .map { it.field }
                .toSet()
                .size == this.fields.size,
        ) { "duplicate fields" }
        val used = mutableSetOf<Int>()
        for (f in this.fields) {
            require(f.offset <= payloadLength - f.size) { "${f.field.key}: outside the payload" }
            for (i in f.offset until f.offset + f.size) {
                require(
                    used.add(i),
                ) { "${f.field.key}: overlaps another field" }
            }
        }
    }

    public fun spec(field: StatusField): FieldSpec? = fields.firstOrNull { it.field == field }

    /** The same layout with every field marked unverified, for bikes without a verified profile. */
    public fun asUnverified(): StatusLayout =
        StatusLayout(payloadLength, fields.map { FieldSpec(it.field, it.offset, it.size, it.scale, verified = false) })

    public companion object {
        public const val MAX_PAYLOAD_LENGTH: Int = 1024
    }
}

/**
 * A bike model the project knows, by its exact model identity. Profiles describe data only; they can never
 * add commands.
 */
public class BikeProfile(
    public val modelIdentity: String,
    public val displayName: String,
    public val verified: Boolean,
    public val statusLayout: StatusLayout,
    evidence: List<String>,
    testedFirmware: List<String> = emptyList(),
    /**
     * How many assist levels above 0 the bike has (5 on the reference bike), or null: then the app shows the
     * level but can't set it. The profile only says the number; the command itself is fixed in the app.
     */
    public val assistLevels: Int? = null,
    /**
     * Whether the app may lock and unlock the bike to switch it off and on. Only with verified power flags, which
     * confirm each change. The profile only says yes or no; the command itself is fixed in the app.
     */
    public val lockControl: Boolean = false,
) {
    public val evidence: List<String> = evidence.toList()

    /** The IoT firmware versions the evidence was gathered with, as the ENGWE app shows them. */
    public val testedFirmware: List<String> = testedFirmware.toList()

    init {
        require(modelIdentity.length in 1..IdentityParser.MAX_MODEL_LENGTH) { "bad model identity length" }
        require(modelIdentity.all { it.code in 0x21..0x7E }) { "model identity must be printable ASCII without spaces" }
        require(!verified || this.evidence.isNotEmpty()) { "a verified profile needs evidence" }
        require(
            verified || statusLayout.fields.none { it.verified },
        ) { "an unverified profile can't have verified fields" }
        // Tested firmware is a verified claim: only a verified profile makes it.
        require(verified || this.testedFirmware.isEmpty()) { "an unverified profile can't name tested firmware" }
        require(this.testedFirmware.all { FIRMWARE.matches(it) }) { "a tested firmware isn't a version" }
        require(this.testedFirmware.toSet().size == this.testedFirmware.size) { "a tested firmware repeats" }
        require(assistLevels == null || assistLevels in 1..AssistLevel.MAX) { "assist levels out of range" }
        // A level set from the app is confirmed by the status showing it: without that reading, no control.
        require(
            assistLevels == null || statusLayout.spec(StatusField.ASSIST_LEVEL) != null,
        ) { "assist levels without an assist level reading" }
        // A lock or an unlock is confirmed by the power flags showing the bike off or on: they must be verified.
        require(
            !lockControl ||
                listOf(
                    StatusField.POWER_FLAG_A,
                    StatusField.POWER_FLAG_B,
                ).all { statusLayout.spec(it)?.verified == true },
        ) { "lock control without verified power flags" }
    }

    private companion object {
        /** A version as the ENGWE app shows it, such as 218CAN. */
        val FIRMWARE = Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,10}")
    }
}

public class ProfileException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
