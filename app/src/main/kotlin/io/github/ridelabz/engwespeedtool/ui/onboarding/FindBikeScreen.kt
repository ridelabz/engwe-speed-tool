package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ErrorKind
import io.github.ridelabz.engwespeedtool.ui.components.ErrorState
import io.github.ridelabz.engwespeedtool.ui.components.IconLine
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.components.TertiaryAction
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/** Bikes nearby, paired ones first, then strongest signal first, with the checklist; or why none can be found. */
@Composable
fun FindBikeScreen(
    state: FindBikeUiState,
    onBikeSelected: (FoundBike) -> Unit,
    onScanAgain: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onTryDemo: () -> Unit,
    onBack: () -> Unit,
    onAllowBluetooth: () -> Unit = {},
) {
    var showTips by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.find_title)
    // While an error shows, it names the screen, so screen readers announce it when it appears.
    val paneTitle =
        when (state) {
            FindBikeUiState.NothingFound -> stringResource(ErrorKind.BIKE_NOT_FOUND.title)
            FindBikeUiState.BluetoothOff -> stringResource(ErrorKind.BLUETOOTH_OFF.title)
            is FindBikeUiState.NoPermission -> stringResource(ErrorKind.NO_PERMISSION.title)
            is FindBikeUiState.Searching -> title
        }
    AppScaffold(onBack = onBack, paneTitle = paneTitle) {
        when (state) {
            is FindBikeUiState.Searching -> {
                ScreenTitle(title)
                FindChecklist()
                // One stop for screen readers, which also hear when bikes are found (heard ones: a paired
                // bike is listed even when it's away).
                val found = state.bikes.count { it.signal != null }
                val foundText = if (found > 0) pluralStringResource(R.plurals.find_found, found, found) else null
                Row(
                    modifier =
                        Modifier.semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            foundText?.let { stateDescription = it }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text(stringResource(R.string.find_searching), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = stringResource(R.string.find_searching_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // In the finder's order: paired bikes first (also once they are heard), then by signal.
                state.bikes.forEach { bike ->
                    key(bike.key) { FoundBikeRow(bike, onClick = { onBikeSelected(bike) }) }
                }
                // Only bikes that are heard: a paired one is listed wherever it is.
                if (state.bikes.count { it.signal != null } > 1) {
                    Text(
                        text = stringResource(R.string.find_several),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TertiaryAction(stringResource(R.string.find_not_listed), onClick = { showTips = true })
            }

            FindBikeUiState.NothingFound -> {
                ErrorState(
                    kind = ErrorKind.BIKE_NOT_FOUND,
                    onPrimary = onScanAgain,
                    onSecondary = { showTips = true },
                    announce = false,
                    details = { FindChecklist() },
                )
                TertiaryAction(stringResource(R.string.action_try_demo), onTryDemo)
            }

            FindBikeUiState.BluetoothOff -> {
                ErrorState(kind = ErrorKind.BLUETOOTH_OFF, onPrimary = onTurnOnBluetooth, announce = false)
            }

            is FindBikeUiState.NoPermission -> {
                ErrorState(
                    kind = if (state.inSettings) ErrorKind.NO_PERMISSION_SETTINGS else ErrorKind.NO_PERMISSION,
                    onPrimary = onAllowBluetooth,
                    announce = false,
                )
            }
        }
    }
    if (showTips) {
        TipsDialog(
            onDismiss = { showTips = false },
            onTryDemo = {
                showTips = false
                onTryDemo()
            },
        )
    }
}

/** What helps the app find the bike: switched on, the ENGWE app closed, the phone near. */
@Composable
internal fun FindChecklist() {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            IconLine(R.drawable.ic_power_settings_new, stringResource(R.string.find_check_bike_on))
            IconLine(R.drawable.ic_smartphone, stringResource(R.string.find_check_app_closed))
            IconLine(R.drawable.ic_near_me, stringResource(R.string.find_check_near))
        }
    }
}

@Composable
private fun FoundBikeRow(
    bike: FoundBike,
    onClick: () -> Unit,
) {
    val strength = bike.signal
    val detail = stringResource(if (strength == null) R.string.find_paired else signalText(strength))
    val shape = MaterialTheme.shapes.large
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClickLabel = stringResource(R.string.find_connect), role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.heightIn(min = TouchTargets.minimum).padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(painterResource(R.drawable.ic_electric_bike), contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(bike.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (strength == null) {
                Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null)
            } else {
                SignalIcon(strength)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

/** Signal bars. Decorative: the strength is also written out next to it. */
@Composable
private fun SignalIcon(strength: SignalStrength) {
    Icon(painterResource(signalIcon(strength)), contentDescription = null)
}

@DrawableRes
private fun signalIcon(strength: SignalStrength): Int =
    when (strength) {
        SignalStrength.WEAK -> R.drawable.ic_signal_cellular_alt_1_bar
        SignalStrength.FAIR -> R.drawable.ic_signal_cellular_alt_2_bar
        SignalStrength.STRONG -> R.drawable.ic_signal_cellular_alt
    }

@StringRes
private fun signalText(strength: SignalStrength): Int =
    when (strength) {
        SignalStrength.WEAK -> R.string.signal_weak
        SignalStrength.FAIR -> R.string.signal_fair
        SignalStrength.STRONG -> R.string.signal_strong
    }

/** Tips for when the bike can't be found or reached; with [onTryDemo], also a way to try the app without a bike. */
@Composable
fun TipsDialog(
    onDismiss: () -> Unit,
    onTryDemo: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton =
            onTryDemo?.let {
                {
                    TextButton(
                        onClick = it,
                    ) { Text(stringResource(R.string.action_try_demo)) }
                }
            },
        title = { Text(stringResource(R.string.tips_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                listOf(R.string.tips_display_on, R.string.tips_close_apps, R.string.tips_bluetooth, R.string.tips_near)
                    .forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
            }
        },
    )
}
