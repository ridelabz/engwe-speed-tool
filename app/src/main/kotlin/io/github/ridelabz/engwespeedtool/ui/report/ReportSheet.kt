package io.github.ridelabz.engwespeedtool.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.reporting.IssueField
import io.github.ridelabz.engwespeedtool.ui.LogPreview
import io.github.ridelabz.engwespeedtool.ui.components.ButtonPair
import io.github.ridelabz.engwespeedtool.ui.components.SheetDragHandle
import io.github.ridelabz.engwespeedtool.ui.diagnostics.LogSummaryCard
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets
import kotlinx.coroutines.launch

private const val STEPS = 3
private const val MAX_SPEED_DIGITS = 2

/**
 * The report sheet: what happened, what will be shared (with every line of the log it attaches, [log]), then
 * one step that saves the log in Downloads and opens the filled-in form in the browser ([onSend]). [request]
 * is where it opens (a type chosen) and, once sent, how it went; [onOpenAgain] opens the form again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    preview: (ReportType, ReportAnswers) -> ReportPreview,
    request: ReportRequest,
    log: LogPreview?,
    onDismiss: () -> Unit,
    onSend: (ReportPreview) -> Unit = {},
    onOpenAgain: (ReportPreview) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        ReportContent(
            preview = preview,
            initialType = request.type,
            onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
            initialAnswers = request.answers,
            log = log,
            sending = request.sending != null,
            sent = request.sent,
            onSend = onSend,
            onOpenAgain = onOpenAgain,
        )
    }
}

@Composable
fun ReportContent(
    preview: (ReportType, ReportAnswers) -> ReportPreview,
    initialType: ReportType?,
    onClose: () -> Unit,
    initialAnswers: ReportAnswers = ReportAnswers(),
    /** Where it opens: the first step, unless a screen test shows another. */
    initialStep: Int = 1,
    log: LogPreview? = null,
    sending: Boolean = false,
    sent: ReportSent? = null,
    onSend: (ReportPreview) -> Unit = {},
    onOpenAgain: (ReportPreview) -> Unit = {},
) {
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    var type by rememberSaveable { mutableStateOf(initialType) }
    var stopSpeed by rememberSaveable { mutableStateOf(initialAnswers.stopSpeedKmh?.toString().orEmpty()) }
    var iotVersion by rememberSaveable { mutableStateOf(initialAnswers.iotVersion) }
    val chosen = type
    val answers = ReportAnswers(stopSpeed.toIntOrNull(), iotVersion, initialAnswers.rideTest, initialAnswers.check)
    val shared = chosen?.let { preview(it, answers) }
    // Once sent, the sheet stays on its last step: it says what happened and how to attach the log.
    val shownStep = if (sent != null || sending) STEPS else step
    // The steps scroll; the buttons stay in reach below them, also past a long log.
    Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = Spacing.xl, vertical = Spacing.s)) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // Read out when the step changes.
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.report_step, shownStep, STEPS),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text =
                        stringResource(
                            when (shownStep) {
                                1 -> R.string.report_step_what
                                2 -> R.string.report_step_shared
                                else -> R.string.report_step_send
                            },
                        ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            when {
                shownStep == 1 || shared == null -> {
                    WhatHappened(
                        type,
                        { type = it },
                        stopSpeed,
                        { stopSpeed = it },
                        shared?.stopSpeedFor,
                        shared?.loading ?: true,
                        iotVersion,
                        { iotVersion = it },
                    )
                }

                shownStep == 2 -> {
                    WhatIsShared(shared)
                    // The whole log, line by line, before anything is saved: what the file will hold.
                    if (shared.needsLog && !shared.demo) LogSection(log)
                }

                else -> {
                    Send(shared, log, sending, sent)
                }
            }
        }
        Column(modifier = Modifier.padding(top = Spacing.l)) {
            ReportButtons(
                step = shownStep,
                shared = shared,
                log = log,
                sending = sending,
                sent = sent,
                onBack = { step-- },
                onNext = { step++ },
                onClose = onClose,
                onSend = onSend,
                onOpenAgain = onOpenAgain,
            )
        }
    }
}

/**
 * The sheet's buttons. Steps 1 and 2: Cancel or Back, and Next. Step 3: the send button over Back, both full
 * width. Sent: Close once the form opened (Open GitHub again beside it); if it didn't open, Open GitHub and
 * Cancel.
 */
@Composable
private fun ReportButtons(
    step: Int,
    shared: ReportPreview?,
    log: LogPreview?,
    sending: Boolean,
    sent: ReportSent?,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onSend: (ReportPreview) -> Unit,
    onOpenAgain: (ReportPreview) -> Unit,
) {
    val tall = Modifier.heightIn(min = TouchTargets.minimum)
    when {
        sent != null && (sent.opened || sent.demo) -> {
            ButtonPair(
                secondary =
                    if (!sent.demo) {
                        { place ->
                            OutlinedButton(onClick = { shared?.let(onOpenAgain) }, modifier = place.then(tall)) {
                                Text(stringResource(R.string.report_open_again))
                            }
                        }
                    } else {
                        null
                    },
            ) { place ->
                Button(onClick = onClose, modifier = place.then(tall)) { Text(stringResource(R.string.action_close)) }
            }
        }

        sent != null -> {
            ButtonPair(
                secondary = { place ->
                    OutlinedButton(onClick = onClose, modifier = place.then(tall)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            ) { place ->
                Button(onClick = { shared?.let(onOpenAgain) }, modifier = place.then(tall)) {
                    Text(stringResource(R.string.report_open_github_only))
                }
            }
        }

        step < STEPS -> {
            ButtonPair(
                secondary = { place ->
                    OutlinedButton(onClick = if (step > 1) onBack else onClose, modifier = place.then(tall)) {
                        Text(stringResource(if (step > 1) R.string.action_back else R.string.action_cancel))
                    }
                },
            ) { place ->
                Button(
                    onClick = onNext,
                    // The log must be read before it can be shown in full.
                    enabled = shared != null && !(step == 2 && shared.loading),
                    modifier = place.then(tall),
                ) { Text(stringResource(R.string.action_next)) }
            }
        }

        else -> {
            // In demo mode the button reads as it would, though it saves and opens nothing.
            val attaches = shared != null && shared.needsLog && (shared.demo || log?.text?.isNotBlank() == true)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(
                    onClick = { shared?.let(onSend) },
                    enabled = shared != null && !shared.loading && !sending,
                    modifier = Modifier.fillMaxWidth().then(tall),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = Spacing.s).size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    }
                    Text(stringResource(if (attaches) R.string.report_send else R.string.report_open_github_only))
                }
                OutlinedButton(onClick = onBack, enabled = !sending, modifier = Modifier.fillMaxWidth().then(tall)) {
                    Text(stringResource(R.string.action_back))
                }
            }
        }
    }
}

@Composable
private fun WhatHappened(
    type: ReportType?,
    onType: (ReportType) -> Unit,
    stopSpeed: String,
    onStopSpeed: (String) -> Unit,
    stopSpeedFor: Int?,
    loading: Boolean,
    iotVersion: String,
    onIotVersion: (String) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        for (option in ReportType.entries) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTargets.minimum)
                        .selectable(selected = type == option, role = Role.RadioButton, onClick = { onType(option) })
                        .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                RadioButton(selected = type == option, onClick = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(typeTitle(option)), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(typeDetail(option)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (type == ReportType.COMPATIBILITY) {
        OutlinedTextField(
            value = stopSpeed,
            onValueChange = { value -> onStopSpeed(value.filter(Char::isDigit).take(MAX_SPEED_DIGITS)) },
            // The answer belongs to the speed the bike confirmed last in this run; without one it isn't shared.
            label = {
                Text(
                    if (stopSpeedFor != null) {
                        stringResource(R.string.report_stop_speed_with, stopSpeedFor)
                    } else {
                        stringResource(R.string.report_stop_speed)
                    },
                )
            },
            suffix = { Text(stringResource(R.string.unit_kmh)) },
            // Without a speed confirmed in this run, the answer wouldn't be shared.
            enabled = stopSpeedFor != null || loading,
            supportingText = {
                val hint = stopSpeedFor != null || loading
                Text(stringResource(if (hint) R.string.report_stop_speed_hint else R.string.report_stop_speed_no_speed))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // The app can't read the firmware; the ENGWE app shows it. Only the version is asked for.
    if (type?.aboutTheBike == true) {
        // Marked only once the rider has left the field, not while a version is being typed.
        var editing by remember { mutableStateOf(false) }
        val unusable = !editing && IotVersion.unusable(iotVersion)
        OutlinedTextField(
            value = iotVersion,
            // Anything that isn't a version (for example a pasted address) is refused whole: the field
            // keeps what it had.
            onValueChange = { value -> IotVersion.accepted(value)?.let(onIotVersion) },
            label = { Text(stringResource(R.string.report_iot_version)) },
            isError = unusable,
            supportingText = {
                Text(
                    stringResource(
                        if (unusable) {
                            R.string.report_iot_version_needs_digit
                        } else {
                            R.string.report_iot_version_hint
                        },
                    ),
                )
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused },
        )
    }
}

private fun typeTitle(type: ReportType): Int =
    when (type) {
        ReportType.COMPATIBILITY -> R.string.report_type_compatibility
        ReportType.PROBLEM -> R.string.report_type_problem
        ReportType.IDEA -> R.string.report_type_idea
    }

private fun typeDetail(type: ReportType): Int =
    when (type) {
        ReportType.COMPATIBILITY -> R.string.report_type_compatibility_detail
        ReportType.PROBLEM -> R.string.report_type_problem_detail
        ReportType.IDEA -> R.string.report_type_idea_detail
    }

@Composable
private fun WhatIsShared(preview: ReportPreview) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            for (item in preview.items) {
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(item.value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(
            painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            tint = SpeedToolTheme.status.confirmed,
        )
        Text(
            stringResource(R.string.report_nothing_identifying),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
    // The model is the bike's own text: the rider looks at it before it goes.
    if (preview.items.any { it.field == IssueField.MODEL_IDENTITY }) {
        Text(
            text = stringResource(R.string.report_check_model),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        text = stringResource(R.string.report_own_account),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The log the report attaches: the same summary and lines as its preview in Diagnostics, every line, in a
 * box of its own height.
 */
@Composable
private fun LogSection(log: LogPreview?) {
    Text(
        stringResource(R.string.report_log_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    if (log == null) {
        Text(stringResource(R.string.report_log_reading), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val lines = remember(log) { log.text.lines().filter { it.isNotBlank() } }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.report_no_log), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(stringResource(R.string.log_preview_intro), style = MaterialTheme.typography.bodyMedium)
    if (log.replaced) Text(stringResource(R.string.log_preview_replaced), style = MaterialTheme.typography.bodyMedium)
    LogSummaryCard(log)
    Text(
        pluralStringResource(R.plurals.log_preview_lines, lines.size, lines.size),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = LOG_BOX_HEIGHT),
            contentPadding = PaddingValues(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Send(
    preview: ReportPreview,
    log: LogPreview?,
    sending: Boolean,
    sent: ReportSent?,
) {
    if (preview.demo) {
        // Said up front, so nobody believes a log was saved or a report sent.
        Note(
            R.drawable.ic_info,
            stringResource(if (sent != null) R.string.report_demo_done else R.string.report_demo_note),
        )
    }
    if (sent == null) {
        val attaches = preview.needsLog && log?.text?.isNotBlank() == true
        Text(
            text =
                stringResource(
                    when {
                        !preview.needsLog -> R.string.report_open_github_detail_idea
                        attaches || preview.demo -> R.string.report_send_detail
                        else -> R.string.report_no_log
                    },
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (attaches) {
            Text(
                stringResource(R.string.downloads_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sending) {
            Text(
                stringResource(R.string.report_sending),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // What didn't fit in the link: the rider adds it in the form if it matters (the log has it).
        val omitted = preview.link(stringResource(R.string.report_repository))?.omitted.orEmpty()
        if (!preview.demo && omitted.isNotEmpty()) {
            Text(
                text = stringResource(R.string.report_link_omitted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (sent.demo) return
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        val saved = sent.log
        when (saved) {
            is LogSaved.InDownloads -> {
                Note(R.drawable.ic_check_circle, stringResource(R.string.report_sent_downloads, saved.name), ok = true)
                Text(
                    stringResource(R.string.report_attach_downloads, saved.name),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            LogSaved.Chosen -> {
                Note(R.drawable.ic_check_circle, stringResource(R.string.report_sent_chosen), ok = true)
                Text(stringResource(R.string.report_attach_chosen), style = MaterialTheme.typography.bodyLarge)
            }

            LogSaved.NotSaved -> {
                Note(R.drawable.ic_error, stringResource(R.string.report_sent_not_saved), error = true)
            }

            null -> {
                if (preview.needsLog) Note(R.drawable.ic_info, stringResource(R.string.report_no_log))
            }
        }
        if (sent.opened) {
            Text(stringResource(R.string.report_sent_opened), style = MaterialTheme.typography.bodyMedium)
            if (saved is LogSaved.InDownloads) {
                Text(stringResource(R.string.report_delete_after), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Note(R.drawable.ic_error, stringResource(R.string.report_open_failed), error = true)
        }
    }
}

/** A line with an icon in front: done ([ok]), failed ([error]) or for information. */
@Composable
private fun Note(
    icon: Int,
    text: String,
    ok: Boolean = false,
    error: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint =
                when {
                    ok -> SpeedToolTheme.status.confirmed
                    error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The log box's height: tall enough to read, short enough to keep the sheet's buttons in reach. */
private val LOG_BOX_HEIGHT = 320.dp
