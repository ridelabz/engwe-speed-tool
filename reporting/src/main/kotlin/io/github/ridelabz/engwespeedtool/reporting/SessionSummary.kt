package io.github.ridelabz.engwespeedtool.reporting

import io.github.ridelabz.engwespeedtool.reporting.SentCommand.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A readable summary of one session log, read back from its records ([SessionLog]'s format): what a
 * person needs at a glance before sharing the log, the bike's model identity among it, so that the rider
 * sees exactly the model text the log carries. Lines that aren't records of this format are counted, not read.
 */
public class SessionSummary private constructor(
    public val date: String?,
    public val appVersion: String?,
    public val android: String?,
    public val phone: String?,
    /**
     * Every model identity the log records, as it records them (distinct, in the order read): the only text
     * the bike sent, which the rider checks before sharing. Empty if no bike's identity was read.
     */
    public val models: List<String>,
    /** `verified`, `unverified` or `incompatible`, the last classification in the log. */
    public val verification: String?,
    /** Speed, light, assist-level and lock commands in the order sent, each with how it ended. */
    public val commands: List<SentCommand>,
    /** Commands that weren't sent, with the reason. */
    public val refused: List<String>,
    public val errors: Int,
    /** Connection attempts tried again (a lost link, a bike not found, a connection that failed). */
    public val retries: Int,
    public val records: Int,
    /** Lines that aren't records of the log's format. */
    public val unreadable: Int,
    /** Seconds from the first record to the last. */
    public val seconds: Double?,
) {
    public companion object {
        private val JSON = Json

        public fun of(log: String): SessionSummary {
            var date: String? = null
            var appVersion: String? = null
            var android: String? = null
            var phone: String? = null
            val models = LinkedHashSet<String>()
            var verification: String? = null
            val commands = mutableListOf<SentCommand>()
            // The command the next outcome belongs to: the last one sent, if it is one the summary knows.
            var latest = -1
            val refused = mutableListOf<String>()
            var errors = 0
            var retries = 0
            var records = 0
            var unreadable = 0
            var first: Double? = null
            var last: Double? = null
            for (line in log.lineSequence()) {
                if (line.isBlank()) continue
                val record = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull()
                val event = record?.text("event")
                if (record == null || event == null) {
                    unreadable++
                    continue
                }
                records++
                record.number("t")?.let { t ->
                    if (first == null) first = t
                    last = t
                }
                when (event) {
                    "session_start" -> {
                        date = record.text("date")
                        appVersion = record.text("app_version")
                        android = record.text("android")
                        phone = record.text("phone")
                    }

                    "identity" -> {
                        record.text("model")?.let { models += it }
                    }

                    "classification" -> {
                        verification = record.text("verification")
                    }

                    "tx" -> {
                        val command = record.text("command")?.let(::sent)
                        latest =
                            if (command == null) {
                                -1
                            } else {
                                commands += command
                                commands.lastIndex
                            }
                    }

                    "state" -> {
                        when (record.text("name")) {
                            "command" -> {
                                if (latest >= 0 && commands[latest].outcome == null) {
                                    commands[latest] = commands[latest].copy(outcome = outcome(record.text("value")))
                                }
                            }

                            "refused" -> {
                                record.text("value")?.let { refused += it }
                            }

                            "retrying" -> {
                                retries++
                            }
                        }
                    }

                    "error" -> {
                        errors++
                    }
                }
            }
            val span = first?.let { f -> last?.let { it - f } }
            return SessionSummary(
                date,
                appVersion,
                android,
                phone,
                models.toList(),
                verification,
                commands,
                refused,
                errors,
                retries,
                records,
                unreadable,
                span,
            )
        }

        private fun sent(command: String): SentCommand? {
            val kmh = command.removePrefix(SPEED).takeIf { it != command }?.toIntOrNull()
            val level = command.removePrefix(ASSIST).takeIf { it != command }?.toIntOrNull()
            return when {
                kmh != null -> SentCommand(speedKmh = kmh, lightsOn = null, outcome = null)
                level != null -> SentCommand(speedKmh = null, lightsOn = null, outcome = null, assistLevel = level)
                command == LIGHTS_ON -> SentCommand(speedKmh = null, lightsOn = true, outcome = null)
                command == LIGHTS_OFF -> SentCommand(speedKmh = null, lightsOn = false, outcome = null)
                command == LOCK -> SentCommand(speedKmh = null, lightsOn = null, outcome = null, locked = true)
                command == UNLOCK -> SentCommand(speedKmh = null, lightsOn = null, outcome = null, locked = false)
                else -> null // the identity read isn't a command of the rider's
            }
        }

        private fun outcome(value: String?): Outcome =
            when (value) {
                "confirmed" -> Outcome.CONFIRMED
                "not_confirmed" -> Outcome.NOT_CONFIRMED
                "failed" -> Outcome.FAILED
                else -> Outcome.OTHER
            }

        private const val SPEED = "set_speed_limit_"
        private const val ASSIST = "set_assist_level_"
        private const val LIGHTS_ON = "set_lights_on"
        private const val LIGHTS_OFF = "set_lights_off"
        private const val LOCK = "lock"
        private const val UNLOCK = "unlock"

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
    }
}

/**
 * A speed ([speedKmh]), lights ([lightsOn]), assist-level ([assistLevel]) or lock ([locked]: a lock, or an unlock)
 * command a log records, and how it ended (null: not recorded).
 */
public data class SentCommand(
    val speedKmh: Int?,
    val lightsOn: Boolean?,
    val outcome: Outcome?,
    val assistLevel: Int? = null,
    val locked: Boolean? = null,
) {
    public enum class Outcome { CONFIRMED, NOT_CONFIRMED, FAILED, OTHER }
}
