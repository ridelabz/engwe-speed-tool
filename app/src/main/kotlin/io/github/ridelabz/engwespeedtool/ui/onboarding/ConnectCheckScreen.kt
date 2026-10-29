package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.UnverifiedReason
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ChecklistRow
import io.github.ridelabz.engwespeedtool.ui.components.ContentCard
import io.github.ridelabz.engwespeedtool.ui.components.ErrorKind
import io.github.ridelabz.engwespeedtool.ui.components.ErrorState
import io.github.ridelabz.engwespeedtool.ui.components.IconLine
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme

/** The connect-and-check checklist, ending in Verified, Unverified or an error state. */
@Composable
fun ConnectCheckScreen(
    state: ConnectCheckUiState,
    onContinue: (CheckResult) -> Unit,
    onRetry: () -> Unit,
    onReport: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
    /** Back to the find screen, which searches again. */
    onScanAgain: () -> Unit = onBack,
    onTurnOnBluetooth: () -> Unit = {},
    onAllowBluetooth: () -> Unit = {},
    /** To the risk acceptance, when the rider hasn't accepted the current text. */
    onAccept: () -> Unit = {},
    /** Android won't ask for the permission again: allowing it means the app's settings. */
    permissionInSettings: Boolean = false,
) {
    var showTips by rememberSaveable { mutableStateOf(false) }
    val result = state.result
    val title =
        stringResource(
            when (result) {
                null -> R.string.check_title
                is CheckResult.Verified, is CheckResult.Unverified -> R.string.check_title_done
                else -> R.string.check_title_stopped
            },
        )
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        bottomBar =
            if (result is CheckResult.Verified || result is CheckResult.Unverified) {
                { PrimaryAction(stringResource(R.string.action_continue), { onContinue(result) }) }
            } else {
                null
            },
    ) {
        ScreenTitle(title)
        // Steps that were ticked before can untick: say why.
        if (state.restarted) IconLine(R.drawable.ic_restart_alt, stringResource(R.string.check_restarted))
        ContentCard {
            ChecklistRow(stringResource(R.string.check_step_connected), state.connected)
            ChecklistRow(stringResource(R.string.check_step_responds), state.responds)
            ChecklistRow(stringResource(R.string.check_step_model), state.model)
        }
        // Always present, so screen readers announce the result when it appears.
        Column(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            AnimatedVisibility(visible = result != null, enter = fadeIn(), exit = fadeOut()) {
                when (result) {
                    is CheckResult.Verified, is CheckResult.Unverified -> {
                        CheckResultCard(result)
                    }

                    CheckResult.Incompatible -> {
                        ErrorState(
                            ErrorKind.INCOMPATIBLE,
                            onPrimary = onReport,
                            onSecondary = onDiagnostics,
                            announce = false,
                        )
                    }

                    CheckResult.NotFound -> {
                        ErrorState(
                            ErrorKind.BIKE_NOT_FOUND,
                            onPrimary = onScanAgain,
                            onSecondary = { showTips = true },
                            announce = false,
                            details = { FindChecklist() },
                        )
                    }

                    CheckResult.ConnectedElsewhere -> {
                        ErrorState(
                            ErrorKind.CONNECTED_ELSEWHERE,
                            onPrimary = onRetry,
                            onSecondary = { showTips = true },
                            announce = false,
                        )
                    }

                    CheckResult.BluetoothOff -> {
                        ErrorState(ErrorKind.BLUETOOTH_OFF, onPrimary = onTurnOnBluetooth, announce = false)
                    }

                    CheckResult.NoPermission -> {
                        ErrorState(
                            if (permissionInSettings) ErrorKind.NO_PERMISSION_SETTINGS else ErrorKind.NO_PERMISSION,
                            onPrimary = onAllowBluetooth,
                            announce = false,
                        )
                    }

                    CheckResult.NotAccepted -> {
                        ErrorState(ErrorKind.NOT_ACCEPTED, onPrimary = onAccept, announce = false)
                    }

                    null -> {}
                }
            }
        }
    }
    if (showTips) TipsDialog(onDismiss = { showTips = false })
}

@Composable
private fun CheckResultCard(result: CheckResult) {
    val status = SpeedToolTheme.status
    val (container, content) =
        when (result) {
            is CheckResult.Verified -> status.confirmedContainer to status.onConfirmedContainer
            else -> status.cautionContainer to status.onCautionContainer
        }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (result is CheckResult.Verified) R.drawable.ic_verified else R.drawable.ic_warning,
                        ),
                    contentDescription = null,
                    tint = content,
                )
                val verified = result is CheckResult.Verified
                val title = if (verified) R.string.check_result_verified else R.string.check_result_unverified
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(text = resultDetail(result), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun resultDetail(result: CheckResult): String =
    when (result) {
        is CheckResult.Verified -> {
            stringResource(R.string.check_result_verified_detail, result.modelName)
        }

        is CheckResult.Unverified -> {
            val name = result.modelName
            when (result.reason) {
                UnverifiedReason.UNKNOWN_MODEL -> {
                    stringResource(R.string.check_result_unverified_model)
                }

                UnverifiedReason.PROFILE_NOT_VERIFIED -> {
                    name?.let { stringResource(R.string.check_result_unverified_profile, it) }
                        ?: stringResource(R.string.check_result_unverified_model)
                }
            }
        }

        else -> {
            ""
        }
    }
