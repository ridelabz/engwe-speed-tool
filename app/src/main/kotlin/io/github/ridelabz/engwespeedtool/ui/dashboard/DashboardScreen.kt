package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ErrorCard
import io.github.ridelabz.engwespeedtool.ui.components.ErrorKind
import io.github.ridelabz.engwespeedtool.ui.onboarding.TipsDialog
import io.github.ridelabz.engwespeedtool.ui.theme.NumberStyle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/** Entries of the dashboard's menu. */
enum class MenuEntry(
    @param:StringRes val label: Int,
) {
    REPORT(R.string.menu_report),
    DIAGNOSTICS(R.string.menu_diagnostics),
    SETTINGS(R.string.menu_settings),
    LEGAL(R.string.menu_legal),
    ABOUT(R.string.menu_about),
}

/** What the dashboard can ask for. */
data class DashboardActions(
    val onSpeed: (Int) -> Unit,
    val onKeepSpeed: (Boolean) -> Unit,
    val onLights: (Boolean) -> Unit,
    val onReport: () -> Unit,
    val onMenu: (MenuEntry) -> Unit,
    val onTurnOnBluetooth: () -> Unit,
    /** The rider tapped an assist level. */
    val onAssist: (Int) -> Unit = {},
    val onKeepAssist: (Boolean) -> Unit = {},
    /** The rider tapped Turn on (true) or Turn off. */
    val onPower: (Boolean) -> Unit = {},
    /** A compatibility report, from the verification sheet. */
    val onReportCompatibility: () -> Unit = {},
    val onAllowBluetooth: () -> Unit = {},
    /** Connects to the saved bike again after it stopped responding as expected. */
    val onRetry: () -> Unit = {},
    /** Back from the dashboard, where there is somewhere to go back to (demo mode); null otherwise. */
    val onBack: (() -> Unit)? = null,
) {
    companion object {
        /** Does nothing, for previews and screenshots. */
        val None =
            DashboardActions(
                onSpeed = {},
                onKeepSpeed = {},
                onLights = {},
                onReport = {},
                onMenu = {},
                onTurnOnBluetooth = {},
            )
    }
}

/** The small sheets that explain a card, opened by its ⓘ (or, for verification, by the chip). */
enum class DashboardSheet { VERIFICATION, SPEED, KEEP_SPEED, ASSIST, KEEP_ASSIST }

/**
 * The home screen, under a top bar with the app's name and the menu: the bike (its name, Turn off or Turn
 * on, whether it is on), any connection problem, the battery, then the speed limit, the assist level, the
 * lights and the readings. Each card shows at most one line of status; explanations open from its ⓘ. The
 * cards keep their place whatever the connection: while the bike isn't connected they are greyed out.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
) {
    var sheet by rememberSaveable { mutableStateOf<DashboardSheet?>(null) }
    var explainNotConfirmed by rememberSaveable { mutableStateOf(false) }
    var showTips by rememberSaveable { mutableStateOf(false) }
    val status = state.speedLimit.status
    // The explanation belongs to this failure only; it never reopens by itself at the next one.
    LaunchedEffect(status) { if (status !is SpeedLimitStatus.NotConfirmed) explainNotConfirmed = false }
    // Verification is about the bike this run checked: its sheet goes with the check.
    LaunchedEffect(state.checked) { if (!state.checked && sheet == DashboardSheet.VERIFICATION) sheet = null }
    val name = state.bikeName ?: stringResource(R.string.bike_default_name)
    AppScaffold(
        onBack = actions.onBack,
        paneTitle = name,
        title = stringResource(R.string.dashboard_title),
        actions = { DashboardMenu(actions.onMenu) },
    ) {
        BikeStatusCard(
            state,
            name,
            onVerification = { sheet = DashboardSheet.VERIFICATION },
            onPower = actions.onPower,
        )
        if (state.connection == Connection.NO_PERMISSION) {
            ErrorCard(
                if (state.permissionInSettings) ErrorKind.NO_PERMISSION_SETTINGS else ErrorKind.NO_PERMISSION,
                onPrimary = actions.onAllowBluetooth,
            )
        }
        if (state.connection == Connection.INCOMPATIBLE) {
            ErrorCard(ErrorKind.NOT_RESPONDING, onPrimary = actions.onRetry, onSecondary = actions.onReport)
        }
        if (state.connection == Connection.BLUETOOTH_OFF) {
            ErrorCard(ErrorKind.BLUETOOTH_OFF, onPrimary = actions.onTurnOnBluetooth)
        }
        val waiting = state.connection == Connection.CONNECTING || state.connection == Connection.RECONNECTING
        if (waiting && state.cantReach) CantReachCard(onTips = { showTips = true })
        BatteryCard(state)
        SpeedLimitCard(
            state = state.speedLimit,
            verified = state.verified,
            connection = state.connection,
            onSpeed = actions.onSpeed,
            onKeepSpeed = actions.onKeepSpeed,
            onNotConfirmedHelp = { explainNotConfirmed = true },
            onInfo = { sheet = DashboardSheet.SPEED },
            onKeepInfo = { sheet = DashboardSheet.KEEP_SPEED },
        )
        AssistCard(
            state = state.assist,
            connected = state.connected,
            checked = state.checked,
            unverified = state.unverified,
            onLevel = actions.onAssist,
            onKeep = actions.onKeepAssist,
            onInfo = { sheet = DashboardSheet.ASSIST },
            onKeepInfo = { sheet = DashboardSheet.KEEP_ASSIST },
        )
        LightsTile(state, actions.onLights)
        ReadingsCard(state)
        Footer(actions.onReport)
    }
    when (val open = sheet) {
        null -> {}

        DashboardSheet.VERIFICATION -> {
            VerificationSheet(
                verified = state.verified,
                model = state.model,
                testedFirmware = state.testedFirmware,
                onReport = {
                    sheet = null
                    actions.onReportCompatibility()
                },
                onDismiss = { sheet = null },
            )
        }

        else -> {
            CardInfoSheet(open, state, onDismiss = { sheet = null })
        }
    }
    if (showTips) TipsDialog(onDismiss = { showTips = false })
    if (explainNotConfirmed && status is SpeedLimitStatus.NotConfirmed) {
        NotConfirmedDialog(
            onRetry = {
                explainNotConfirmed = false
                actions.onSpeed(status.kmh)
            },
            onReport = {
                explainNotConfirmed = false
                actions.onReport()
            },
            onDismiss = { explainNotConfirmed = false },
        )
    }
}

@Composable
private fun DashboardMenu(onMenu: (MenuEntry) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_open))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (entry in MenuEntry.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label)) },
                    onClick = {
                        open = false
                        onMenu(entry)
                    },
                )
            }
        }
    }
}

/** A dashboard card: the cards' colour and shape, and their padding and spacing. */
@Composable
internal fun DashboardCard(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Spacing.l,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            content = content,
        )
    }
}

/** A card's title, read as a heading, with an optional value at the end and its ⓘ. */
@Composable
internal fun CardTitle(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    onInfo: (() -> Unit)? = null,
    @StringRes infoLabel: Int = R.string.action_more_info,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
        if (onInfo != null) InfoButton(onInfo, infoLabel)
    }
}

/** The ⓘ that opens a card's explanation. */
@Composable
internal fun InfoButton(
    onClick: () -> Unit,
    @StringRes label: Int = R.string.action_more_info,
) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_info),
            contentDescription = stringResource(label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Unverified": next to a value or a group whose meaning isn't verified for this bike. Never colour alone. */
@Composable
internal fun UnverifiedMarker(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = SpeedToolTheme.status.caution,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.value_unverified),
            style = MaterialTheme.typography.labelMedium,
            color = SpeedToolTheme.status.caution,
        )
    }
}

/**
 * The height of a status line with nothing to say: invisible, but not none, so that screen readers know the line
 * and read it out once it has something to say (a node without any area isn't given to them at all).
 */
internal val EMPTY_LINE_HEIGHT = 1.dp

/**
 * Whether [confirmed] differs from what this place showed before it came, and still holds: no line says it in
 * words, so the control that shows it is then read out, once. A confirmation shown when the screen opens (back
 * from another screen) isn't new; null is no confirmation. While [hold] (for example no reading for a moment), the
 * last confirmation shown is kept, so that it isn't new again when it comes back. The control stays a live region
 * for as long as this holds, on purpose: screen readers look at it only after the change has been passed on, so
 * clearing it right away could lose the announcement.
 */
@Composable
internal fun isNewConfirmation(
    confirmed: Any?,
    hold: Boolean = false,
): Boolean {
    val shown = remember { arrayOf(confirmed) }
    // Not only after a "Sending" this place showed: a quick echo may come within one frame.
    val new = remember(confirmed) { confirmed != null && confirmed != shown[0] }
    SideEffect { if (!hold) shown[0] = confirmed }
    return new
}

/** One line of a card's status: a note, in [colour]. */
@Composable
internal fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    colour: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colour, modifier = modifier)
}

/** The bike wasn't found or reached yet: what usually helps, and the tips. */
@Composable
private fun CantReachCard(onTips: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Icon(painterResource(R.drawable.ic_search_off), contentDescription = null)
                Text(
                    text = stringResource(R.string.dashboard_cant_reach),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = onTips,
                modifier = Modifier.align(Alignment.End).heightIn(min = TouchTargets.minimum),
            ) {
                Text(stringResource(R.string.tips_title))
            }
        }
    }
}

/** The report button, at the end of the screen. */
@Composable
private fun Footer(onReport: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onReport, modifier = Modifier.heightIn(min = TouchTargets.minimum)) {
            Icon(
                painterResource(R.drawable.ic_feedback),
                contentDescription = null,
                modifier = Modifier.padding(end = Spacing.s).size(18.dp),
            )
            Text(stringResource(R.string.footer_report))
        }
    }
}

@Composable
private fun NotConfirmedDialog(
    onRetry: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kind = ErrorKind.NOT_CONFIRMED
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(kind.icon), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(kind.title), modifier = Modifier.semantics { heading() }) },
        text = { Text(stringResource(kind.cause)) },
        confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(kind.primaryAction)) } },
        dismissButton = {
            kind.secondaryAction?.let { TextButton(onClick = onReport) { Text(stringResource(it)) } }
        },
    )
}

/** A reading, or a dash read out as "No reading" when there is none. */
@Composable
internal fun ReadingText(
    value: String?,
    modifier: Modifier = Modifier,
) {
    val missing = stringResource(R.string.value_missing_spoken)
    Text(
        text = value ?: stringResource(R.string.value_missing),
        style = MaterialTheme.typography.titleLarge.merge(NumberStyle),
        modifier = if (value == null) modifier.semantics { contentDescription = missing } else modifier,
    )
}
