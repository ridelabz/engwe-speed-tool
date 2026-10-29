package io.github.ridelabz.engwespeedtool.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.session.BikeGatt
import io.github.ridelabz.engwespeedtool.session.CharacteristicProperty
import io.github.ridelabz.engwespeedtool.session.CheckResult
import io.github.ridelabz.engwespeedtool.session.CheckStep
import io.github.ridelabz.engwespeedtool.session.CheckedField
import io.github.ridelabz.engwespeedtool.session.CompatibilityOutcome
import io.github.ridelabz.engwespeedtool.session.CompatibilityProgress
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import io.github.ridelabz.engwespeedtool.session.FieldCheck
import io.github.ridelabz.engwespeedtool.session.FieldResult
import io.github.ridelabz.engwespeedtool.session.GattService
import io.github.ridelabz.engwespeedtool.session.ProtocolCheck
import io.github.ridelabz.engwespeedtool.session.StopReason
import io.github.ridelabz.engwespeedtool.ui.CompatibilityUiState
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ButtonPair
import io.github.ridelabz.engwespeedtool.ui.components.ChecklistRow
import io.github.ridelabz.engwespeedtool.ui.components.ContentCard
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.components.SecondaryAction
import io.github.ridelabz.engwespeedtool.ui.components.StepState
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import java.util.UUID

/**
 * The read-only compatibility check: where it is while it runs, then what it found (the protocol checks,
 * the bike's model and verification, and how plausible each status value was over the sample). A finished
 * check can be reported ([onReport]) or shared as text: [shareText] is exactly what [onShare] sends, shown
 * first; a demo check shares nothing. Where the check never reached the bike's own answers (not found, the
 * connection failed, Bluetooth off, the risks not accepted, interrupted, too long), running it again is the
 * main action.
 */
@Composable
fun CompatibilityCheckScreen(
    state: CompatibilityUiState,
    shareText: String?,
    onRunAgain: () -> Unit,
    onReport: () -> Unit,
    onShare: (String) -> Unit,
    onBack: () -> Unit,
) {
    val report = state.report
    val title = stringResource(if (report == null) R.string.compat_title else R.string.compat_title_done)
    var sharing by rememberSaveable { mutableStateOf(false) }
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        bottomBar =
            if (report == null) {
                null
            } else {
                {
                    val share = @Composable { modifier: Modifier ->
                        SecondaryAction(stringResource(R.string.compat_share), { sharing = true }, modifier)
                    }
                    if (reachedTheBike(report)) {
                        PrimaryAction(stringResource(R.string.compat_report), onReport)
                        ButtonPair(
                            secondary = { modifier ->
                                SecondaryAction(stringResource(R.string.compat_run_again), onRunAgain, modifier)
                            },
                            primary = share,
                        )
                    } else {
                        PrimaryAction(stringResource(R.string.compat_run_again), onRunAgain)
                        ButtonPair(
                            secondary = { modifier ->
                                SecondaryAction(stringResource(R.string.compat_report), onReport, modifier)
                            },
                            primary = share,
                        )
                    }
                }
            },
    ) {
        ScreenTitle(title)
        Text(stringResource(R.string.compat_read_only), style = MaterialTheme.typography.bodyLarge)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (report == null) Progress(state.progress) else Result(report)
        }
    }
    if (sharing && shareText != null) {
        ShareDialog(
            text = shareText,
            withModel = report?.classification?.modelIdentity != null,
            demo = state.demo,
            onShare = {
                sharing = false
                onShare(shareText)
            },
            onDismiss = { sharing = false },
        )
    }
}

/**
 * Whether the check got the bike's own answers: it connected and ran its checks (passed or not), or lost the
 * connection on the way. Otherwise it stopped at the phone's side (not found, the connection failed,
 * Bluetooth, the risks not accepted, interrupted, too long).
 */
private fun reachedTheBike(report: CompatibilityReport): Boolean =
    when (val outcome = report.outcome) {
        CompatibilityOutcome.Checked, CompatibilityOutcome.LinkLost, is CompatibilityOutcome.Incompatible -> true
        CompatibilityOutcome.Interrupted -> false
        is CompatibilityOutcome.NotConnected -> outcome.reason == StopReason.LINK_LOST
    }

/** What a share sends, shown in full before it goes: the text, and the model to check. */
@Composable
private fun ShareDialog(
    text: String,
    withModel: Boolean,
    demo: Boolean,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compat_share_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(stringResource(if (demo) R.string.compat_share_demo else R.string.compat_share_intro))
                if (withModel && !demo) Text(stringResource(R.string.report_check_model))
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(Spacing.m),
                    )
                }
            }
        },
        // A demo check has nothing to share: one button, Close.
        confirmButton = {
            if (demo) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            } else {
                TextButton(onClick = onShare) { Text(stringResource(R.string.compat_share_send)) }
            }
        },
        dismissButton =
            if (demo) {
                null
            } else {
                { TextButton(onClick = onDismiss) { Text(stringResource(R.string.warning_cancel)) } }
            },
    )
}

@Composable
private fun Progress(progress: CompatibilityProgress?) {
    val text =
        when (progress) {
            null -> {
                stringResource(R.string.compat_step_connecting)
            }

            is CompatibilityProgress.Reading -> {
                stringResource(R.string.compat_step_reading, progress.second, progress.of)
            }

            is CompatibilityProgress.Step -> {
                stringResource(
                    when (progress.step) {
                        CheckStep.SEARCHING -> R.string.compat_step_searching
                        CheckStep.CONNECTING -> R.string.compat_step_connecting
                        CheckStep.SERVICES -> R.string.compat_step_services
                        CheckStep.NOTIFICATIONS -> R.string.compat_step_notifications
                        CheckStep.STATUS -> R.string.compat_step_status
                        CheckStep.IDENTITY -> R.string.compat_step_identity
                    },
                )
            }
        }
    // Screen readers hear each step; the seconds only on the bar, so they aren't announced every second.
    val spoken =
        if (progress is CompatibilityProgress.Reading) {
            stringResource(R.string.compat_step_reading_status, progress.of)
        } else {
            text
        }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            },
    )
    if (progress is CompatibilityProgress.Reading && progress.of > 0) {
        LinearProgressIndicator(
            progress = { progress.second.toFloat() / progress.of },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Result(report: CompatibilityReport) {
    val outcome = report.outcome
    Text(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleMedium,
        text =
            stringResource(
                when (outcome) {
                    CompatibilityOutcome.Checked -> {
                        R.string.compat_outcome_checked
                    }

                    CompatibilityOutcome.LinkLost -> {
                        R.string.compat_outcome_link_lost
                    }

                    CompatibilityOutcome.Interrupted -> {
                        R.string.compat_outcome_interrupted
                    }

                    is CompatibilityOutcome.Incompatible -> {
                        R.string.compat_outcome_incompatible
                    }

                    is CompatibilityOutcome.NotConnected -> {
                        when (outcome.reason) {
                            StopReason.NOT_FOUND -> R.string.compat_outcome_not_found

                            StopReason.CONNECT_FAILED -> R.string.compat_outcome_not_connected

                            // Connected, and lost before the checks ended: as far as it got.
                            StopReason.LINK_LOST -> R.string.compat_outcome_link_lost

                            StopReason.BLUETOOTH_UNAVAILABLE -> R.string.compat_outcome_bluetooth

                            StopReason.NOT_ACCEPTED -> R.string.compat_outcome_not_accepted

                            null -> R.string.compat_outcome_too_long
                        }
                    }
                },
            ),
    )
    report.classification?.let { classification ->
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.l).semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(R.string.compat_model),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    classification.modelIdentity ?: stringResource(R.string.compat_model_not_read),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    stringResource(
                        when (classification.verification) {
                            Verification.VERIFIED -> R.string.compat_verified
                            Verification.UNVERIFIED -> R.string.compat_unverified
                            Verification.INCOMPATIBLE -> R.string.compat_incompatible
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
    Heading(stringResource(R.string.compat_checks))
    ContentCard {
        for ((check, result) in report.checks) {
            ChecklistRow(stringResource(checkName(check)), stepState(result))
        }
        report.sampledSeconds?.let { seconds ->
            ChecklistRow(
                pluralStringResource(R.plurals.compat_status_frames, report.statusFrames, report.statusFrames, seconds),
                if (outcome == CompatibilityOutcome.Checked) StepState.DONE else StepState.FAILED,
            )
        }
    }
    if (report.unreadableFrames) {
        Text(
            stringResource(R.string.compat_unreadable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (report.fields.isNotEmpty()) {
        Heading(stringResource(R.string.compat_values))
        for (field in report.fields) FieldRow(field)
    }
    if (report.services.isNotEmpty()) {
        Heading(stringResource(R.string.compat_services))
        Text(
            stringResource(R.string.compat_services_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (service in report.services) ServiceBlock(service)
    }
}

/** One service as the bike declared it: its UUID, then each characteristic with what it offers. */
@Composable
private fun ServiceBlock(service: GattService) {
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    // One screen-reader item per line, not one long item per service.
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(uuidText(service.uuid), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
        for (c in service.characteristics) {
            val properties =
                c.properties
                    .sortedBy { it.ordinal }
                    .map { propertyText(it) }
                    .joinToString(", ")
                    .ifEmpty { stringResource(R.string.compat_property_none) }
            val descriptors = c.descriptors.joinToString(", ") { uuidText(it) }
            Text(
                stringResource(
                    if (descriptors.isEmpty()) {
                        R.string.compat_characteristic
                    } else {
                        R.string.compat_characteristic_descriptors
                    },
                    uuidText(c.uuid),
                    properties,
                    descriptors,
                ),
                style = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.l),
            )
        }
    }
}

private fun uuidText(uuid: UUID): String = BikeGatt.text(uuid)

@Composable
private fun propertyText(property: CharacteristicProperty): String =
    stringResource(
        when (property) {
            CharacteristicProperty.BROADCAST -> R.string.compat_property_broadcast
            CharacteristicProperty.READ -> R.string.compat_property_read
            CharacteristicProperty.WRITE_WITHOUT_RESPONSE -> R.string.compat_property_write_no_response
            CharacteristicProperty.WRITE -> R.string.compat_property_write
            CharacteristicProperty.NOTIFY -> R.string.compat_property_notify
            CharacteristicProperty.INDICATE -> R.string.compat_property_indicate
            CharacteristicProperty.SIGNED_WRITE -> R.string.compat_property_signed_write
            CharacteristicProperty.EXTENDED_PROPERTIES -> R.string.compat_property_extended
        },
    )

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Spacing.m).semantics { heading() },
    )
}

@Composable
private fun FieldRow(check: FieldCheck) {
    val result =
        stringResource(
            when (check.result) {
                FieldResult.PLAUSIBLE -> R.string.compat_value_plausible
                FieldResult.OUT_OF_RANGE -> R.string.compat_value_out_of_range
                FieldResult.WENT_BACK -> R.string.compat_value_went_back
                FieldResult.NOT_READ -> R.string.compat_value_not_read
            },
        )
    val text =
        if (check.result != FieldResult.NOT_READ && !check.verified) {
            stringResource(R.string.compat_value_unverified, result)
        } else {
            result
        }
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(stringResource(fieldName(check.field)), style = MaterialTheme.typography.bodyLarge)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color =
                with(MaterialTheme.colorScheme) {
                    if (check.result == FieldResult.OUT_OF_RANGE ||
                        check.result == FieldResult.WENT_BACK
                    ) {
                        error
                    } else {
                        onSurfaceVariant
                    }
                },
        )
    }
}

private fun stepState(result: CheckResult): StepState =
    when (result) {
        CheckResult.PASSED -> StepState.DONE
        CheckResult.FAILED -> StepState.FAILED
        CheckResult.NOT_RUN -> StepState.NOT_RUN
    }

private fun checkName(check: ProtocolCheck): Int =
    when (check) {
        ProtocolCheck.SERVICE -> R.string.compat_check_service
        ProtocolCheck.NOTIFY -> R.string.compat_check_notify
        ProtocolCheck.ACKNOWLEDGED_WRITE -> R.string.compat_check_write
        ProtocolCheck.STATUS -> R.string.compat_check_status
        ProtocolCheck.IDENTITY -> R.string.compat_check_identity
    }

private fun fieldName(field: CheckedField): Int =
    when (field) {
        CheckedField.SPEED -> R.string.compat_field_speed
        CheckedField.MAX_SPEED -> R.string.compat_field_max_speed
        CheckedField.ODOMETER -> R.string.compat_field_odometer
        CheckedField.ASSIST_LEVEL -> R.string.compat_field_assist
        CheckedField.BATTERY -> R.string.compat_field_battery
        CheckedField.LIGHTS -> R.string.compat_field_lights
        CheckedField.POWER -> R.string.compat_field_power
        CheckedField.VOLTAGE -> R.string.compat_field_voltage
        CheckedField.RIDE_TIME -> R.string.compat_field_ride_time
    }
