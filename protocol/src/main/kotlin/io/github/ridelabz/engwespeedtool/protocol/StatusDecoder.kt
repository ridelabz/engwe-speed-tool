package io.github.ridelabz.engwespeedtool.protocol

import kotlin.math.roundToLong

public enum class Lights { ON, OFF, UNKNOWN }

/**
 * Derived from the two power/display flags. Only the combinations seen on the reference bike are
 * named: both 0 (off), both 1 (on), and first 0 / second 1 (the second flag returns about 2 s before
 * the first at power-on). Anything else is [UNKNOWN].
 */
public enum class PowerState { ON, OFF, POWERING_ON, UNKNOWN }

/** A decoded value and whether its meaning is verified for this bike. */
public data class Reading<T>(
    val value: T,
    val verified: Boolean,
)

/** Values from one status frame. A value is null when the layout doesn't define it. */
public class StatusReport(
    public val speedKmh: Reading<Double>?,
    public val maxSpeedKmh: Reading<Double>?,
    public val odometerKm: Reading<Double>?,
    public val assistLevel: Reading<Int>?,
    public val batteryPercent: Reading<Int>?,
    public val lights: Reading<Lights>?,
    public val power: Reading<PowerState>?,
    /** The temperature the bike reports; on the reference bike, in °C. */
    public val temperatureRaw: Reading<Int>?,
    /** The battery health the bike reports; on the reference bike, in %. */
    public val batteryHealthRaw: Reading<Int>?,
    public val voltageV: Reading<Double>?,
    /** The bike's total ride time in minutes. */
    public val rideTimeMinutes: Reading<Int>?,
)

/** Decodes the status frame the bike sends about once a second. */
public object StatusDecoder {
    public fun isStatus(frame: Frame): Boolean = frame.command == Opcodes.STATUS

    /**
     * Returns null for anything that isn't a status frame with a valid CRC and exactly the layout's
     * payload length: showing no data is better than showing wrong data.
     */
    public fun decode(
        frame: Frame,
        layout: StatusLayout,
    ): StatusReport? {
        if (!isStatus(frame) || !frame.crcOk || frame.payloadSize != layout.payloadLength) return null

        fun raw(spec: FieldSpec): Long {
            var v = 0L
            for (i in 0 until spec.size) v = (v shl 8) or frame.payloadByte(spec.offset + i).toLong()
            return v
        }

        fun decimal(field: StatusField): Reading<Double>? =
            layout.spec(field)?.let { Reading((raw(it) * it.scale * 100).roundToLong() / 100.0, it.verified) }

        fun integer(field: StatusField): Reading<Int>? =
            layout.spec(field)?.let { Reading((raw(it) * it.scale).roundToLong().toInt(), it.verified) }

        val lights =
            layout.spec(StatusField.LIGHTS)?.let {
                val state =
                    when (raw(it)) {
                        0x00L -> Lights.OFF
                        0xFFL -> Lights.ON
                        else -> Lights.UNKNOWN
                    }
                Reading(state, it.verified)
            }
        val flagA = layout.spec(StatusField.POWER_FLAG_A)
        val flagB = layout.spec(StatusField.POWER_FLAG_B)
        val power =
            if (flagA != null && flagB != null) {
                val state =
                    when (raw(flagA) to raw(flagB)) {
                        0L to 0L -> PowerState.OFF
                        1L to 1L -> PowerState.ON
                        0L to 1L -> PowerState.POWERING_ON
                        else -> PowerState.UNKNOWN
                    }
                Reading(state, flagA.verified && flagB.verified)
            } else {
                null
            }
        return StatusReport(
            speedKmh = decimal(StatusField.SPEED),
            maxSpeedKmh = decimal(StatusField.MAX_SPEED),
            odometerKm = decimal(StatusField.ODOMETER),
            assistLevel = integer(StatusField.ASSIST_LEVEL),
            batteryPercent = integer(StatusField.BATTERY)?.takeIf { it.value in 0..100 },
            lights = lights,
            power = power,
            temperatureRaw = integer(StatusField.TEMPERATURE),
            batteryHealthRaw = integer(StatusField.BATTERY_HEALTH),
            voltageV = decimal(StatusField.VOLTAGE),
            rideTimeMinutes = integer(StatusField.RIDE_TIME),
        )
    }
}
