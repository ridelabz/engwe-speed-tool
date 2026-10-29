package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.SheetDragHandle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/**
 * Why the warning is shown: to apply a speed now, or to apply it while "keep this speed" is on, so the app
 * will also set it again by itself.
 */
enum class WarningPurpose { APPLY, APPLY_KEEPING }

/** From this speed on, the warning uses its stronger wording. */
const val STRONG_WARNING_FROM_KMH = 45

/**
 * The warning before a speed above the legal limit: Cancel has the focus once the sheet is open, and the
 * rider can turn the warning off for this speed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedWarningSheet(
    kmh: Int,
    purpose: WarningPurpose,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
    onCancel: () -> Unit,
    /** Restarts are trusted, so "keep this speed" also sets the speed again after one. */
    restartsTrusted: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cancelFocus = remember { FocusRequester() }
    // After the sheet has settled: the sheet itself may take the focus while it opens.
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == SheetValue.Expanded) runCatching { cancelFocus.requestFocus() }
    }
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        SpeedWarningContent(kmh, purpose, onConfirm, onCancel, cancelFocus, restartsTrusted)
    }
}

@Composable
fun SpeedWarningContent(
    kmh: Int,
    purpose: WarningPurpose,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
    onCancel: () -> Unit,
    cancelFocus: FocusRequester = remember { FocusRequester() },
    restartsTrusted: Boolean = false,
) {
    val strong = kmh >= STRONG_WARNING_FROM_KMH
    // A new warning (another speed or purpose) starts unticked.
    var dontShowAgain by rememberSaveable(kmh, purpose) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = SpeedToolTheme.status.caution,
            )
            Text(
                text = stringResource(if (strong) R.string.warning_title_strong else R.string.warning_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Text(
            text =
                stringResource(
                    if (strong) R.string.warning_body_strong else R.string.warning_body,
                    kmh,
                    LEGAL_LIMIT_KMH,
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (purpose != WarningPurpose.APPLY) {
            Text(
                stringResource(
                    if (restartsTrusted) R.string.warning_keep_detail else R.string.warning_keep_detail_reconnect,
                    kmh,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTargets.minimum)
                    .toggleable(
                        value = dontShowAgain,
                        role = Role.Checkbox,
                        onValueChange = { dontShowAgain = it },
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Checkbox(checked = dontShowAgain, onCheckedChange = null)
            Text(stringResource(R.string.warning_dont_show_again, kmh), style = MaterialTheme.typography.bodyLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTargets.minimum).focusRequester(cancelFocus),
            ) { Text(stringResource(R.string.warning_cancel)) }
            Button(
                onClick = { onConfirm(dontShowAgain) },
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTargets.minimum),
            ) { Text(stringResource(R.string.warning_set, kmh)) }
        }
    }
}
