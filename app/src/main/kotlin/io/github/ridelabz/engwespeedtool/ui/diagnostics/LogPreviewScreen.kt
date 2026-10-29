package io.github.ridelabz.engwespeedtool.ui.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.reporting.SentCommand
import io.github.ridelabz.engwespeedtool.ui.LogPreview
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.report.LogSaved
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * A stored session log before it is saved: a readable summary, with the bike model the log carries, and
 * then every line exactly as the file will hold it. [onSave] saves exactly that text in Downloads (or where
 * the rider picks, if Downloads can't take it); [saved] says how the last save went.
 */
@Composable
fun LogPreviewScreen(
    preview: LogPreview,
    onSave: () -> Unit,
    saved: LogSaved?,
    onBack: () -> Unit,
    /** A save is on its way: the button waits, so a second tap makes no second copy. */
    saving: Boolean = false,
) {
    val title = stringResource(R.string.log_preview_title)
    val lines = remember(preview) { preview.text.lines().filter { it.isNotBlank() } }
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        scrollable = false,
        bottomBar = {
            if (saved != null) {
                Text(
                    text =
                        when (saved) {
                            is LogSaved.InDownloads -> {
                                stringResource(
                                    R.string.diagnostics_log_saved_downloads,
                                    saved.name,
                                )
                            }

                            LogSaved.Chosen -> {
                                stringResource(R.string.diagnostics_log_saved)
                            }

                            LogSaved.NotSaved -> {
                                stringResource(R.string.diagnostics_log_not_saved)
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        with(MaterialTheme.colorScheme) { if (saved == LogSaved.NotSaved) error else onSurfaceVariant },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // A log pruned between the list and its preview: nothing to save.
            Text(
                stringResource(R.string.downloads_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryAction(
                stringResource(R.string.diagnostics_save_log),
                onSave,
                enabled = lines.isNotEmpty() && !saving,
            )
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.weight(1f)) {
            item { ScreenTitle(title) }
            item {
                Text(
                    stringResource(R.string.log_preview_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = Spacing.s),
                )
            }
            if (preview.replaced) {
                item { Text(stringResource(R.string.log_preview_replaced), style = MaterialTheme.typography.bodyLarge) }
            }
            if (lines.isEmpty()) {
                item { Text(stringResource(R.string.log_preview_gone), style = MaterialTheme.typography.bodyLarge) }
            } else {
                item { LogSummaryCard(preview) }
                item {
                    Text(
                        pluralStringResource(R.plurals.log_preview_lines, lines.size, lines.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.xs).semantics { heading() },
                    )
                }
            }
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

/** A log's readable summary: the bike model it carries first and set apart, then the rest. */
@Composable
internal fun LogSummaryCard(preview: LogPreview) {
    val s = preview.summary
    val missing = stringResource(R.string.log_summary_not_recorded)
    val commands =
        if (s.commands.isEmpty()) {
            stringResource(R.string.log_summary_none)
        } else {
            s.commands.map { sent -> commandLine(sent) }.joinToString("\n")
        }
    val length =
        s.seconds?.let {
            if (it < 60) {
                stringResource(R.string.log_summary_under_minute)
            } else {
                stringResource(R.string.log_summary_minutes, (it / 60).roundToInt())
            }
        } ?: missing
    // Counts in the rider's digits.
    val count = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val rows =
        listOf(
            R.string.log_summary_date to (s.date ?: missing),
            R.string.report_item_app to (s.appVersion ?: missing),
            R.string.report_item_android to (s.android ?: missing),
            R.string.report_item_phone to (s.phone ?: missing),
            R.string.log_summary_commands to commands,
            R.string.log_summary_not_sent to count.format(s.refused.size),
            R.string.log_summary_retries to count.format(s.retries),
            R.string.report_item_errors to count.format(s.errors),
            R.string.log_summary_length to length,
            R.string.log_summary_size to Formatter.formatShortFileSize(LocalContext.current, preview.fileSize),
        )
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            // First and set apart: the only text the bike sent, exactly as the file carries it.
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(R.string.log_summary_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (s.models.isEmpty()) {
                        stringResource(R.string.log_summary_model_not_read)
                    } else {
                        s.models.joinToString("\n")
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            HorizontalDivider()
            for ((label, value) in rows) {
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** One command of the log and how it ended, in words. */
@Composable
private fun commandLine(sent: SentCommand): String {
    val kmh = sent.speedKmh
    val level = sent.assistLevel
    val what =
        when {
            kmh != null -> stringResource(R.string.speed_chip_label, kmh)
            level != null -> stringResource(R.string.log_summary_assist, level)
            sent.locked == true -> stringResource(R.string.log_summary_lock)
            sent.locked == false -> stringResource(R.string.log_summary_unlock)
            sent.lightsOn == true -> stringResource(R.string.log_summary_lights_on)
            else -> stringResource(R.string.log_summary_lights_off)
        }
    val how =
        stringResource(
            when (sent.outcome) {
                SentCommand.Outcome.CONFIRMED -> R.string.log_summary_confirmed
                SentCommand.Outcome.NOT_CONFIRMED -> R.string.log_summary_not_confirmed
                SentCommand.Outcome.FAILED -> R.string.log_summary_failed
                SentCommand.Outcome.OTHER, null -> R.string.log_summary_no_outcome
            },
        )
    return stringResource(R.string.log_summary_command, what, how)
}
