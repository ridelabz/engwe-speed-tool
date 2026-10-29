package io.github.ridelabz.engwespeedtool.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.BIKE_NAME_MAX
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.dashboard.BIKE_DEFAULT_KMH
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/** The settings the app keeps on the phone. */
data class SettingsUiState(
    val bikeName: String?,
    val keepSpeed: Boolean,
    /** The speed the bike confirmed and still uses, if the app knows it. */
    val activeKmh: Int?,
    val keepScreenOn: Boolean,
    val logSessions: Int,
    /** Restarts are trusted, so "keep this speed" also sets the speed again after one. */
    val restartsTrusted: Boolean = false,
    /** The speed "keep this speed" keeps, if any. */
    val keptKmh: Int? = null,
    /** False while a speed is being sent: turning keep-speed on then waits. */
    val keepSpeedSwitchable: Boolean = true,
)

data class SettingsActions(
    val onRename: (String) -> Unit = {},
    val onForget: () -> Unit = {},
    val onKeepSpeed: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onResetWarnings: () -> Unit = {},
    val onLegal: () -> Unit = {},
    /** Deletes the stored session logs. */
    val onDeleteLogs: () -> Unit = {},
    /** Opens Android's language settings for the app; null where Android has none (before Android 13). */
    val onLanguage: (() -> Unit)? = null,
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    var warningsReset by rememberSaveable { mutableStateOf(false) }
    var deletingLogs by rememberSaveable { mutableStateOf(false) }
    var logsDeleted by rememberSaveable { mutableStateOf(false) }
    val name = state.bikeName ?: stringResource(R.string.bike_default_name)
    val title = stringResource(R.string.settings_title)
    AppScaffold(onBack = onBack, paneTitle = title) {
        ScreenTitle(title)
        Section(R.string.settings_section_bike) {
            LinkRow(R.drawable.ic_electric_bike, stringResource(R.string.settings_name), name) { renaming = true }
            LinkRow(R.drawable.ic_delete, stringResource(R.string.settings_forget), null, danger = true) {
                forgetting =
                    true
            }
        }
        Section(R.string.settings_section_riding) {
            SwitchRow(
                R.drawable.ic_restart_alt,
                state.keptKmh?.let { stringResource(R.string.settings_keep_speed_kmh, it) }
                    ?: stringResource(R.string.settings_keep_speed_none),
                stringResource(
                    if (state.restartsTrusted) R.string.keep_speed_detail else R.string.keep_speed_detail_reconnect,
                ),
                state.keepSpeed,
                actions.onKeepSpeed,
                enabled = state.keepSpeedSwitchable,
            )
            LinkRow(
                R.drawable.ic_warning,
                stringResource(R.string.settings_reset_warnings),
                stringResource(
                    if (warningsReset) R.string.settings_reset_warnings_done else R.string.settings_reset_detail,
                ),
                announceValue = true,
                onClick = {
                    actions.onResetWarnings()
                    warningsReset = true
                },
            )
        }
        Section(R.string.settings_section_display) {
            SwitchRow(
                R.drawable.ic_brightness_high,
                stringResource(R.string.settings_keep_screen_on),
                stringResource(R.string.settings_keep_screen_on_detail),
                state.keepScreenOn,
                actions.onKeepScreenOn,
            )
            actions.onLanguage?.let { open ->
                LinkRow(
                    R.drawable.ic_translate,
                    stringResource(R.string.settings_language),
                    stringResource(R.string.settings_language_detail),
                    navigates = true,
                    onClick = open,
                )
            }
        }
        Section(R.string.settings_section_privacy) {
            LinkRow(
                R.drawable.ic_history,
                stringResource(R.string.settings_logs),
                if (logsDeleted) {
                    stringResource(R.string.settings_logs_deleted)
                } else {
                    pluralStringResource(R.plurals.settings_logs_detail, state.logSessions, state.logSessions)
                },
                announceValue = true,
                onClick = null,
            )
            LinkRow(
                R.drawable.ic_delete,
                stringResource(R.string.settings_logs_delete),
                null,
                danger = true,
                onClick = { deletingLogs = true },
            )
            LinkRow(
                R.drawable.ic_gavel,
                stringResource(R.string.menu_legal),
                null,
                navigates = true,
                onClick = actions.onLegal,
            )
        }
    }
    if (renaming) {
        RenameDialog(
            current = state.bikeName.orEmpty(),
            onRename = {
                renaming = false
                actions.onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (deletingLogs) {
        AlertDialog(
            onDismissRequest = { deletingLogs = false },
            title = { Text(stringResource(R.string.settings_logs_delete_title)) },
            text = { Text(stringResource(R.string.settings_logs_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingLogs = false
                    actions.onDeleteLogs()
                    logsDeleted = true
                }) { Text(stringResource(R.string.settings_logs_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingLogs = false }) { Text(stringResource(R.string.warning_cancel)) }
            },
        )
    }
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.settings_forget_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(R.string.settings_forget_body))
                    state.activeKmh?.takeIf { it != BIKE_DEFAULT_KMH }?.let { kmh ->
                        Text(
                            stringResource(R.string.settings_forget_active, kmh, BIKE_DEFAULT_KMH),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = false
                    actions.onForget()
                }) { Text(stringResource(R.string.settings_forget_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { forgetting = false },
                ) { Text(stringResource(R.string.warning_cancel)) }
            },
        )
    }
}

@Composable
private fun Section(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = Spacing.xs)) { content() }
        }
    }
}

@Composable
private fun LinkRow(
    @DrawableRes icon: Int,
    title: String,
    value: String?,
    danger: Boolean = false,
    clickLabel: String? = null,
    announceValue: Boolean = false,
    /** It opens another screen: a chevron says so. */
    navigates: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTargets.minimum)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier.semantics(mergeDescendants = true) {}
                    },
                ).then(if (announceValue) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (danger) colour else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colour)
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (navigates) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    @DrawableRes icon: Int,
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTargets.minimum)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(BIKE_NAME_MAX) },
                    label = { Text(stringResource(R.string.settings_name)) },
                    placeholder = { Text(stringResource(R.string.bike_default_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_rename_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(text.trim()) }) { Text(stringResource(R.string.settings_rename_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.warning_cancel)) } },
    )
}
