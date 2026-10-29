package io.github.ridelabz.engwespeedtool.reporting

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Classification
import io.github.ridelabz.engwespeedtool.protocol.Frame
import io.github.ridelabz.engwespeedtool.protocol.IdentityParser
import io.github.ridelabz.engwespeedtool.protocol.OutgoingFrame
import io.github.ridelabz.engwespeedtool.protocol.Reading
import io.github.ridelabz.engwespeedtool.protocol.Sanitiser
import io.github.ridelabz.engwespeedtool.protocol.StatusReport
import io.github.ridelabz.engwespeedtool.protocol.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * App and phone facts recorded once per session. Pass `Build.MANUFACTURER`, `Build.MODEL`,
 * `Build.VERSION.RELEASE` and `Build.VERSION.SDK_INT` only; never `Build.FINGERPRINT`, `DISPLAY`,
 * `HOST`, `USER`, `ID`, serials, the Bluetooth adapter name or anything the user typed (custom ROM
 * builds can embed the builder's user and host names). Values that don't look like plain versions or
 * model names are recorded as `unknown`.
 */
public class SessionMeta(
    appVersion: String,
    androidRelease: String,
    public val sdkInt: Int,
    manufacturer: String,
    model: String,
) {
    public val appVersion: String = clean(appVersion, VERSION)
    public val androidRelease: String = clean(androidRelease, RELEASE)
    public val phone: String = clean("$manufacturer $model", PHONE)

    private companion object {
        val VERSION = Regex("[0-9A-Za-z.+-]{1,40}")
        val RELEASE = Regex("[0-9.]{1,10}")
        val PHONE = Regex("[A-Za-z0-9 ._()+-]{1,60}")

        fun clean(
            value: String,
            pattern: Regex,
        ): String = value.trim().takeIf { pattern.matches(it) && Sanitiser.text(it) == it } ?: "unknown"
    }
}

/**
 * Writes one JSON object per line. This is the single place where log data is produced, and every
 * record passes through [Sanitiser] here, at capture time.
 *
 * There is no way to record free text: states, actions and error locations are enum constants defined
 * in code, errors record only the exception's class name, and frames go through the sanitiser rules.
 * There is no field for a Bluetooth address, advertisement data or a time of day. The model identity is
 * recorded only as [IdentityParser] reads it from an identity reply.
 */
public class SessionLog(
    private val sink: Appendable,
    private val clock: SessionClock,
    meta: SessionMeta,
) {
    private var seq = 0

    init {
        write("session_start") {
            put("date", clock.today().toString())
            put("app_version", meta.appVersion)
            put("android", meta.androidRelease)
            put("sdk", meta.sdkInt)
            put("phone", meta.phone)
        }
    }

    public fun incoming(event: StreamEvent) {
        val logged = Sanitiser.incoming(event)
        write("rx") {
            put("kind", event.kind)
            if (event is StreamEvent.Decoded) frameFields(event.frame)
            if (event is StreamEvent.Malformed) put("reason", event.reason.code)
            put("length", logged.size)
            logged.hex?.let { put("raw_hex", it) }
            if (logged.masked) put("sanitised", true)
        }
    }

    public fun outgoing(frame: OutgoingFrame) {
        val logged = Sanitiser.outgoing(frame)
        write("tx") {
            put("command", commandToken(frame.command))
            put("length", logged.size)
            put("raw_hex", logged.hex)
        }
    }

    public fun status(report: StatusReport) {
        val values =
            listOf(
                "speed_kmh" to report.speedKmh,
                "max_speed_kmh" to report.maxSpeedKmh,
                "odometer_km" to report.odometerKm,
                "assist_level" to report.assistLevel,
                "battery_pct" to report.batteryPercent,
                "lights" to report.lights,
                "power" to report.power,
                "temperature_raw" to report.temperatureRaw,
                "battery_health_raw" to report.batteryHealthRaw,
                "voltage_v" to report.voltageV,
                "ride_time_min" to report.rideTimeMinutes,
            ).mapNotNull { (key, reading) -> reading?.let { key to it } }
        write("status") {
            for ((key, reading) in values) putReading(key, reading)
            put("unverified", buildJsonArray { values.filterNot { it.second.verified }.forEach { add(it.first) } })
        }
    }

    public fun scanResult(
        advertisedName: String?,
        rssi: Int,
    ) {
        write("scan_result") {
            put("name", Sanitiser.advertisedName(advertisedName))
            put("rssi", rssi)
        }
    }

    /**
     * The model identity of an identity reply, parsed here from the frame, so that no other text can be
     * recorded as a model. Nothing else of the reply is recorded; an unusable reply is recorded as such.
     */
    public fun identity(reply: Frame) {
        val model = IdentityParser.modelOf(reply)
        write("identity") {
            if (model != null) put("model", model) else put("usable", false)
        }
    }

    /** How the bike was classified: the verification, and the reason it isn't verified. */
    public fun classification(result: Classification) {
        write("classification") {
            put("verification", token(result.verification))
            result.unverifiedReason?.let { put("reason", token(it)) }
        }
    }

    /**
     * The result of a compatibility check: its outcome, each protocol check, how many status frames it read
     * and in how many seconds, how plausible each status value was (never the values) and which were read
     * with an unverified meaning, and the services and characteristics the bike declared, with their UUIDs
     * through [Sanitiser.uuid].
     */
    public fun compatibilityCheck(record: CheckRecord) {
        write("compatibility_check") {
            put("outcome", token(record.outcome))
            record.reason?.let { put("reason", token(it)) }
            putJsonObject("checks") { for (line in record.checks) put(token(line.check), token(line.result)) }
            put("status_frames", record.statusFrames)
            record.sampledSeconds?.let { put("sampled_s", it) }
            put("unreadable_frames", record.unreadableFrames)
            putJsonObject("values") { for (line in record.values) put(token(line.value), token(line.result)) }
            put(
                "unverified",
                buildJsonArray { record.values.filter { it.unverified }.forEach { add(token(it.value)) } },
            )
            putJsonArray("services") {
                for (service in record.services) {
                    addJsonObject {
                        put("uuid", Sanitiser.uuid(service.uuid))
                        putJsonArray("characteristics") {
                            for (c in service.characteristics) {
                                addJsonObject {
                                    put("uuid", Sanitiser.uuid(c.uuid))
                                    put("properties", tokens(c.properties))
                                    put("descriptors", uuids(c.descriptors))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public fun state(
        name: Enum<*>,
        value: Enum<*>,
    ) {
        write("state") {
            put("name", token(name))
            put("value", token(value))
        }
    }

    public fun state(
        name: Enum<*>,
        value: Int,
    ) {
        write("state") {
            put("name", token(name))
            put("value", value)
        }
    }

    public fun action(name: Enum<*>) {
        write("action") { put("name", token(name)) }
    }

    public fun error(
        where: Enum<*>,
        code: Enum<*>,
    ) {
        write("error") {
            put("where", token(where))
            put("code", token(code))
        }
    }

    /** Records only the exception's class name; its message could contain anything. */
    public fun error(
        where: Enum<*>,
        cause: Throwable,
    ) {
        write("error") {
            put("where", token(where))
            put("exception", Sanitiser.text(cause::class.simpleName ?: "anonymous"))
        }
    }

    private fun JsonObjectBuilder.frameFields(frame: Frame) {
        put("command", Sanitiser.commandLabel(frame))
        put("crc_ok", frame.crcOk)
    }

    private fun JsonObjectBuilder.putReading(
        key: String,
        reading: Reading<*>,
    ) {
        when (val v = reading.value) {
            is Double -> put(key, v)
            is Int -> put(key, v)
            is Enum<*> -> put(key, token(v))
            else -> put(key, "unsupported")
        }
    }

    private fun token(value: Enum<*>): String = value.name.lowercase(Locale.ROOT)

    private fun tokens(values: List<Enum<*>>): JsonArray = JsonArray(values.map { JsonPrimitive(token(it)) })

    private fun uuids(values: List<UUID>): JsonArray = JsonArray(values.map { JsonPrimitive(Sanitiser.uuid(it)) })

    private fun commandToken(command: BikeCommand): String =
        when (command) {
            is BikeCommand.SetSpeedLimit -> "set_speed_limit_${command.limit.kmh}"
            is BikeCommand.SetLights -> if (command.on) "set_lights_on" else "set_lights_off"
            is BikeCommand.SetAssistLevel -> "set_assist_level_${command.level.level}"
            is BikeCommand.SetLocked -> if (command.locked) "lock" else "unlock"
            BikeCommand.ReadIdentity -> "read_identity"
        }

    @Synchronized
    private fun write(
        event: String,
        body: JsonObjectBuilder.() -> Unit,
    ) {
        val t = (clock.elapsedNanos() / 1_000_000.0).roundToLong() / 1000.0
        val record =
            buildJsonObject {
                put("schema", SCHEMA)
                put("seq", seq++)
                put("t", t)
                put("event", event)
                body()
            }
        sink.append(JSON.encodeToString(record)).append('\n')
    }

    public companion object {
        public const val SCHEMA: Int = 2
        private val JSON = Json
    }
}
