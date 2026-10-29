package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.Classification
import io.github.ridelabz.engwespeedtool.protocol.Lights
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.Reading
import io.github.ridelabz.engwespeedtool.protocol.StatusField
import io.github.ridelabz.engwespeedtool.protocol.StatusLayout
import io.github.ridelabz.engwespeedtool.protocol.StatusReport
import io.github.ridelabz.engwespeedtool.reporting.CheckLine
import io.github.ridelabz.engwespeedtool.reporting.CheckRecord
import io.github.ridelabz.engwespeedtool.reporting.DeclaredCharacteristic
import io.github.ridelabz.engwespeedtool.reporting.DeclaredService
import io.github.ridelabz.engwespeedtool.reporting.ValueLine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The read-only compatibility check: connects to a bike once ([BikeSession.check]), runs the protocol checks
 * and reads its identity (the only frame written), then reads [sample] of status frames and checks that each
 * value is plausible. Writes nothing else: no speed, no lights, no "keep this speed". Stops the session at the
 * end, whatever the outcome.
 */
public class CompatibilityCheck(
    private val session: BikeSession,
    private val sample: Duration = 30.seconds,
    private val settleTimeout: Duration = 3.minutes,
) {
    /** Runs the check on the bike at [address]; [progress] hears each step and the seconds of status read. */
    public suspend fun run(
        address: BikeAddress,
        progress: (CompatibilityProgress) -> Unit = {},
    ): CompatibilityReport {
        val run = session.check(address)
        try {
            var step: CheckStep? = null
            val settled =
                withTimeoutOrNull(settleTimeout) {
                    session.status
                        .first { status ->
                            val state = status.state
                            // Something else started or stopped the session: the check is over.
                            if (status.run > run) return@first true
                            if (status.run < run) return@first false
                            if (state is SessionState.Checking && state.step != step) {
                                step = state.step
                                progress(CompatibilityProgress.Step(state.step))
                            }
                            state is SessionState.Ready || state is SessionState.Incompatible ||
                                state is SessionState.Stopped
                        }
                }
            val state = settled?.state.takeIf { settled?.run == run }
            // What the bike offered, as the check's own run discovered it: read now, while the run is its own.
            val offered = if (session.status.value.run == run) session.services.value else emptyList()
            val report =
                when {
                    settled == null -> {
                        CompatibilityReport.failed(CompatibilityOutcome.NotConnected(null), step)
                    }

                    state is SessionState.Ready -> {
                        sampleStatus(run, state.classification, progress)
                    }

                    state is SessionState.Incompatible -> {
                        CompatibilityReport.failed(
                            CompatibilityOutcome.Incompatible(state.failure),
                            step,
                        )
                    }

                    state is SessionState.Stopped -> {
                        CompatibilityReport.failed(
                            CompatibilityOutcome.NotConnected(state.reason),
                            step,
                        )
                    }

                    else -> {
                        CompatibilityReport.failed(CompatibilityOutcome.Interrupted, step)
                    }
                }
            // An interrupted check has no services of its own.
            val result =
                if (report.outcome ==
                    CompatibilityOutcome.Interrupted
                ) {
                    report
                } else {
                    report.withServices(offered)
                }
            session.logCheck(result.record())
            return result
        } finally {
            // Only the check's own run: never one that replaced it.
            session.stop(run)
        }
    }

    private suspend fun sampleStatus(
        run: Int,
        classification: Classification,
        progress: (CompatibilityProgress) -> Unit,
    ): CompatibilityReport {
        val reports = mutableListOf<StatusReport>()
        var unreadable = false
        var lost = false
        val seconds = sample.inWholeSeconds.toInt()
        var sampled = 0
        coroutineScope {
            val reader =
                launch {
                    withTimeoutOrNull(sample) {
                        var last: StatusReport? = null
                        session.ride.collect { ride ->
                            val status = ride.status
                            if (status != null && status !== last) {
                                reports += status
                                last = status
                            }
                            if (ride.unreadable) unreadable = true
                        }
                    }
                }
            val watcher =
                launch {
                    // The connection ended, or something else started or stopped the session: the sample ends.
                    session.status.first { it.run != run || it.state !is SessionState.Ready }
                    lost = true
                    reader.cancel()
                }
            val ticker =
                launch {
                    for (second in 0 until seconds) {
                        sampled = second
                        progress(CompatibilityProgress.Reading(second, seconds))
                        delay(1.seconds)
                    }
                }
            reader.join()
            watcher.cancel()
            ticker.cancel()
        }
        if (!lost) {
            sampled = seconds
            progress(CompatibilityProgress.Reading(seconds, seconds))
        }
        return CompatibilityReport(
            if (lost) CompatibilityOutcome.LinkLost else CompatibilityOutcome.Checked,
            classification,
            FieldSanity.check(reports, classification.layout),
            reports.size,
            unreadable,
            sampledSeconds = sampled,
        )
    }
}

/** Where a compatibility check is. */
public sealed interface CompatibilityProgress {
    public data class Step(
        val step: CheckStep,
    ) : CompatibilityProgress

    /** Reading status frames: [second] of [of]. */
    public data class Reading(
        val second: Int,
        val of: Int,
    ) : CompatibilityProgress
}

/** How a compatibility check ended. */
public sealed interface CompatibilityOutcome {
    /** Connected, checked, and status read for the whole sample. */
    public data object Checked : CompatibilityOutcome

    /** Connected and checked, but the connection ended during the sample. */
    public data object LinkLost : CompatibilityOutcome

    /** A protocol check failed. */
    public data class Incompatible(
        val failure: CheckFailure,
    ) : CompatibilityOutcome

    /** Something else started or stopped the session before the checks ended. */
    public data object Interrupted : CompatibilityOutcome

    /** Never connected and checked; [reason] is null when it took too long. */
    public data class NotConnected(
        val reason: StopReason?,
    ) : CompatibilityOutcome
}

/**
 * The result of a compatibility check: the outcome, the protocol checks it implies, the bike's classification
 * (its model identity and verification), the value checks over the sample, the statuses read in
 * [sampledSeconds] (null when no status was sampled), and whether status frames that don't fit the bike's
 * layout came.
 */
public class CompatibilityReport(
    public val outcome: CompatibilityOutcome,
    public val classification: Classification?,
    public val fields: List<FieldCheck>,
    public val statusFrames: Int,
    public val unreadableFrames: Boolean,
    public val sampledSeconds: Int?,
    /** The last check step the session reached, for the protocol checks of a check that ended early. */
    private val lastStep: CheckStep? = null,
    /** The services and characteristics the bike offered; empty if the check didn't get that far. */
    public val services: List<GattService> = emptyList(),
) {
    internal fun withServices(services: List<GattService>): CompatibilityReport =
        CompatibilityReport(
            outcome,
            classification,
            fields,
            statusFrames,
            unreadableFrames,
            sampledSeconds,
            lastStep,
            services,
        )

    /** Each protocol check, in the order the session runs them, and how it went. */
    public val checks: List<Pair<ProtocolCheck, CheckResult>> =
        ProtocolCheck.entries.map { check -> check to result(check) }

    private fun result(check: ProtocolCheck): CheckResult {
        val failed = (outcome as? CompatibilityOutcome.Incompatible)?.failure?.let(ProtocolCheck::of)
        // The step reached; if none was seen, the failed check's own step. Never more than was seen: a check
        // whose step was skipped by the progress reads as not run rather than as passed.
        val reached = lastStep ?: failed?.step
        return when {
            outcome == CompatibilityOutcome.Checked || outcome == CompatibilityOutcome.LinkLost -> CheckResult.PASSED

            check == failed -> CheckResult.FAILED

            reached == null -> CheckResult.NOT_RUN

            check.step < reached -> CheckResult.PASSED

            // Checks of the same step run in order: those before the failed one passed.
            failed != null && check.step == reached && check < failed -> CheckResult.PASSED

            else -> CheckResult.NOT_RUN
        }
    }

    /** The report as the session log records it: names, counts and the declared services, never a value. */
    internal fun record(): CheckRecord =
        CheckRecord(
            outcome =
                when (outcome) {
                    CompatibilityOutcome.Checked -> LoggedOutcome.CHECKED
                    CompatibilityOutcome.LinkLost -> LoggedOutcome.LINK_LOST
                    CompatibilityOutcome.Interrupted -> LoggedOutcome.INTERRUPTED
                    is CompatibilityOutcome.Incompatible -> LoggedOutcome.INCOMPATIBLE
                    is CompatibilityOutcome.NotConnected -> LoggedOutcome.NOT_CONNECTED
                },
            reason =
                when (outcome) {
                    is CompatibilityOutcome.Incompatible -> outcome.failure
                    is CompatibilityOutcome.NotConnected -> outcome.reason ?: TooLong.TOO_LONG
                    else -> null
                },
            checks = checks.map { (check, result) -> CheckLine(check, result) },
            statusFrames = statusFrames,
            sampledSeconds = sampledSeconds,
            unreadableFrames = unreadableFrames,
            values =
                fields.map {
                    ValueLine(it.field, it.result, unverified = it.result != FieldResult.NOT_READ && !it.verified)
                },
            services =
                services.map { service ->
                    DeclaredService(
                        service.uuid,
                        service.characteristics.map { c ->
                            DeclaredCharacteristic(c.uuid, c.properties.sortedBy { it.ordinal }, c.descriptors)
                        },
                    )
                },
        )

    /** How a check ended, as the session log names it. */
    private enum class LoggedOutcome { CHECKED, LINK_LOST, INCOMPATIBLE, INTERRUPTED, NOT_CONNECTED }

    /** A check that never connected within its time. */
    private enum class TooLong { TOO_LONG, }

    internal companion object {
        fun failed(
            outcome: CompatibilityOutcome,
            lastStep: CheckStep?,
        ) = CompatibilityReport(outcome, null, emptyList(), 0, false, sampledSeconds = null, lastStep = lastStep)
    }
}

/** The protocol checks, in the order the session runs them, each with the check step that ends it. */
public enum class ProtocolCheck(
    internal val step: CheckStep,
) {
    /** The bike service. */
    SERVICE(CheckStep.SERVICES),

    /** A notify characteristic, with notifications (enabled in their own step). */
    NOTIFY(CheckStep.NOTIFICATIONS),

    /** A write characteristic with acknowledged writes. */
    ACKNOWLEDGED_WRITE(CheckStep.SERVICES),

    /** Valid status frames. */
    STATUS(CheckStep.STATUS),

    /** A usable identity reply. */
    IDENTITY(CheckStep.IDENTITY),
    ;

    internal companion object {
        fun of(failure: CheckFailure): ProtocolCheck =
            when (failure) {
                CheckFailure.NO_SERVICE -> SERVICE
                CheckFailure.NO_NOTIFY -> NOTIFY
                CheckFailure.NO_ACKNOWLEDGED_WRITE -> ACKNOWLEDGED_WRITE
                CheckFailure.NO_STATUS -> STATUS
                CheckFailure.NO_IDENTITY -> IDENTITY
            }
    }
}

public enum class CheckResult { PASSED, FAILED, NOT_RUN }

/** One value of the status frames over the sample. */
public data class FieldCheck(
    val field: CheckedField,
    val result: FieldResult,
    /** Whether the value's meaning is verified for this bike (from its profile). */
    val verified: Boolean,
)

public enum class CheckedField { SPEED, MAX_SPEED, ODOMETER, ASSIST_LEVEL, BATTERY, LIGHTS, POWER, VOLTAGE, RIDE_TIME }

public enum class FieldResult {
    /** Every value was plausible. */
    PLAUSIBLE,

    /** A value was outside what the field can hold on an e-bike, or one the app knows by no name. */
    OUT_OF_RANGE,

    /** A total (odometer, ride time) went down. */
    WENT_BACK,

    /** The field's value was never known (not in the layout, or no status frame read). */
    NOT_READ,
}

/**
 * Plausibility of status values: the ranges an e-bike's values can't leave. A value the [layout] has but a
 * report doesn't (the decoder drops impossible values, such as a battery above 100 %) counts as out of range.
 */
public object FieldSanity {
    public fun check(
        reports: List<StatusReport>,
        layout: StatusLayout?,
    ): List<FieldCheck> {
        fun dropped(
            field: StatusField,
            value: (StatusReport) -> Any?,
        ): Boolean = layout?.spec(field) != null && reports.any { value(it) == null }
        return listOf(
            range(CheckedField.SPEED, reports.map { it.speedKmh }, 0.0..80.0),
            range(CheckedField.MAX_SPEED, reports.map { it.maxSpeedKmh }, 0.0..80.0),
            total(CheckedField.ODOMETER, reports.map { it.odometerKm }, 0.0..1_000_000.0),
            range(CheckedField.ASSIST_LEVEL, reports.map { it.assistLevel?.toDouble() }, 0.0..9.0),
            range(CheckedField.BATTERY, reports.map { it.batteryPercent?.toDouble() }, 0.0..100.0)
                .outIf(dropped(StatusField.BATTERY) { it.batteryPercent }),
            known(CheckedField.LIGHTS, reports.map { it.lights }, Lights.UNKNOWN),
            known(CheckedField.POWER, reports.map { it.power }, PowerState.UNKNOWN),
            range(CheckedField.VOLTAGE, reports.map { it.voltageV }, 20.0..90.0),
            total(CheckedField.RIDE_TIME, reports.map { it.rideTimeMinutes?.toDouble() }, 0.0..10_000_000.0),
        )
    }

    private fun FieldCheck.outIf(dropped: Boolean): FieldCheck =
        if (dropped) copy(result = FieldResult.OUT_OF_RANGE) else this

    private fun Reading<Int>.toDouble(): Reading<Double> = Reading(value.toDouble(), verified)

    private fun range(
        field: CheckedField,
        readings: List<Reading<Double>?>,
        range: ClosedFloatingPointRange<Double>,
    ): FieldCheck {
        val known = readings.filterNotNull()
        val result =
            when {
                known.isEmpty() -> FieldResult.NOT_READ
                known.all { it.value in range } -> FieldResult.PLAUSIBLE
                else -> FieldResult.OUT_OF_RANGE
            }
        return FieldCheck(field, result, known.isNotEmpty() && known.all { it.verified })
    }

    private fun total(
        field: CheckedField,
        readings: List<Reading<Double>?>,
        range: ClosedFloatingPointRange<Double>,
    ): FieldCheck {
        val checked = range(field, readings, range)
        val values = readings.filterNotNull().map { it.value }
        val back = values.zipWithNext().any { (a, b) -> b < a }
        return if (checked.result == FieldResult.PLAUSIBLE &&
            back
        ) {
            checked.copy(result = FieldResult.WENT_BACK)
        } else {
            checked
        }
    }

    /** A field of named values: the app knows [unknown] by no name. */
    private fun <T> known(
        field: CheckedField,
        readings: List<Reading<T>?>,
        unknown: T,
    ): FieldCheck {
        val known = readings.filterNotNull()
        val result =
            when {
                known.isEmpty() -> FieldResult.NOT_READ
                known.any { it.value == unknown } -> FieldResult.OUT_OF_RANGE
                else -> FieldResult.PLAUSIBLE
            }
        return FieldCheck(field, result, known.isNotEmpty() && known.all { it.verified })
    }
}
