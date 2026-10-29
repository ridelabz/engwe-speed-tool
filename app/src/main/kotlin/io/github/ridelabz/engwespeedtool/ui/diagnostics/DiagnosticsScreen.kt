package io.github.ridelabz.engwespeedtool.ui.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.StoredLogInfo
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ContentCard
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.components.SecondaryAction
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/**
 * Diagnostics, in two sections: the read-only compatibility check, with its button ([onRunCheck]; null without
 * a bike to check), and the stored session logs ([logs], newest first; null while they are read), each opened
 * in a preview before it is saved ([onOpenLog]). [logNotSaved]: a save whose preview Android closed while the
 * picker was open didn't happen. Report stays at the bottom.
 */
@Composable
fun DiagnosticsScreen(
    onReport: () -> Unit,
    onBack: () -> Unit,
    logs: List<StoredLogInfo>? = emptyList(),
    onOpenLog: (Int) -> Unit = {},
    logNotSaved: Boolean = false,
    onRunCheck: (() -> Unit)? = null,
) {
    val title = stringResource(R.string.diagnostics_title)
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        bottomBar = { SecondaryAction(stringResource(R.string.action_report), onReport) },
    ) {
        ScreenTitle(title)
        Section(stringResource(R.string.compat_title)) {
            ContentCard(contentPadding = PaddingValues(Spacing.l)) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(R.string.diagnostics_check), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text =
                            stringResource(
                                if (onRunCheck != null) {
                                    R.string.diagnostics_check_disconnects
                                } else {
                                    R.string.diagnostics_check_needs_bike
                                },
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryAction(
                        stringResource(R.string.diagnostics_run_check),
                        onClick = { onRunCheck?.invoke() },
                        enabled = onRunCheck != null,
                    )
                }
            }
        }
        Section(stringResource(R.string.diagnostics_logs_title)) {
            Text(stringResource(R.string.diagnostics_log), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.diagnostics_logs_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (logNotSaved) {
                Text(
                    text = stringResource(R.string.diagnostics_log_not_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (logs != null && logs.isEmpty()) {
                Text(stringResource(R.string.diagnostics_logs_none), style = MaterialTheme.typography.bodyLarge)
            }
            if (!logs.isNullOrEmpty()) {
                ContentCard(contentPadding = PaddingValues(vertical = Spacing.s)) {
                    logs.forEachIndexed { index, log ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.l))
                        LogRow(log, onClick = { onOpenLog(log.number) })
                    }
                }
            }
        }
    }
}

/** A section of the screen: its heading, then its content, closer together than the sections are. */
@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun LogRow(
    log: StoredLogInfo,
    onClick: () -> Unit,
) {
    val size = Formatter.formatShortFileSize(LocalContext.current, log.size)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTargets.minimum)
                .clickable(
                    onClickLabel = stringResource(R.string.diagnostics_log_open),
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(painterResource(R.drawable.ic_history), contentDescription = null)
        Text(
            text =
                if (log.current) {
                    stringResource(R.string.diagnostics_log_this_run, size)
                } else {
                    stringResource(R.string.diagnostics_log_earlier, log.number, size)
                },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
    }
}
