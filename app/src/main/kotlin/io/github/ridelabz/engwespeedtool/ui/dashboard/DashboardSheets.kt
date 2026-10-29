package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.SecondaryAction
import io.github.ridelabz.engwespeedtool.ui.components.SheetDragHandle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme

/** A small sheet that explains something: a title, a few short paragraphs, its actions and Close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoSheet(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, dragHandle = { SheetDragHandle() }) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            content()
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                actions()
                SecondaryAction(stringResource(R.string.action_close), onDismiss)
            }
        }
    }
}

/** A paragraph of a sheet, with an icon in front where it warns. */
@Composable
internal fun SheetText(
    text: String,
    @DrawableRes icon: Int? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (icon == null) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        return
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** What a card's ⓘ explains, for [state]'s bike (an unverified bike never gets what the tests showed). */
@Composable
internal fun CardInfoSheet(
    sheet: DashboardSheet,
    state: DashboardUiState,
    onDismiss: () -> Unit,
) {
    val caution = SpeedToolTheme.status.caution
    when (sheet) {
        DashboardSheet.SPEED -> {
            InfoSheet(stringResource(R.string.speed_title), onDismiss) {
                SheetText(stringResource(R.string.speed_cannot_report), R.drawable.ic_info)
                SheetText(stringResource(R.string.speed_after_reconnect, BIKE_DEFAULT_KMH), R.drawable.ic_history)
                SheetText(
                    stringResource(R.string.speed_above_limit_note, LEGAL_LIMIT_KMH),
                    R.drawable.ic_warning,
                    caution,
                )
                if (state.verified) {
                    SheetText(stringResource(R.string.speed_status_until_off), R.drawable.ic_power_settings_new)
                    // Where the app turns the bike off and on: that counts as switching it off and on.
                    if (state.power != null) {
                        SheetText(stringResource(R.string.speed_turn_off_resets, BIKE_DEFAULT_KMH))
                    }
                } else if (state.checked) {
                    SheetText(
                        stringResource(R.string.speed_status_unverified_caveat, BIKE_DEFAULT_KMH),
                        R.drawable.ic_warning,
                        caution,
                    )
                }
            }
        }

        DashboardSheet.KEEP_SPEED -> {
            InfoSheet(stringResource(R.string.keep_speed_title), onDismiss) {
                SheetText(
                    stringResource(
                        if (state.speedLimit.restartsTrusted) {
                            R.string.keep_speed_detail
                        } else {
                            R.string.keep_speed_detail_reconnect
                        },
                    ),
                )
            }
        }

        DashboardSheet.ASSIST -> {
            val about =
                when {
                    // Before the check the bike's levels aren't known.
                    !state.checked -> R.string.assist_about_before_check

                    state.assist.settable -> R.string.assist_about

                    else -> R.string.assist_status_shown_only
                }
            InfoSheet(stringResource(R.string.assist_title), onDismiss) { SheetText(stringResource(about)) }
        }

        DashboardSheet.KEEP_ASSIST -> {
            InfoSheet(stringResource(R.string.keep_assist_title), onDismiss) {
                SheetText(
                    stringResource(
                        if (state.speedLimit.restartsTrusted) {
                            R.string.keep_assist_detail
                        } else {
                            R.string.keep_assist_detail_reconnect
                        },
                    ),
                )
            }
        }

        DashboardSheet.VERIFICATION -> {}
    }
}

/**
 * What Verified or Unverified means: the model identity the bike reported, that the app can't read the IoT
 * firmware, how a model gets verified, and a report of how it worked. The unverified variant keeps the
 * acknowledgement's caveat (a change might not reset; tap 25).
 */
@Composable
internal fun VerificationSheet(
    verified: Boolean,
    model: String?,
    testedFirmware: List<String>,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val caution = SpeedToolTheme.status.caution
    InfoSheet(
        title =
            stringResource(
                if (verified) R.string.verification_title_verified else R.string.verification_title_unverified,
            ),
        onDismiss = onDismiss,
        actions = { PrimaryAction(stringResource(R.string.verification_report), onReport) },
    ) {
        if (model != null) {
            SheetText(
                stringResource(
                    if (verified) R.string.verification_matched else R.string.verification_not_matched,
                    model,
                ),
                if (verified) R.drawable.ic_verified else R.drawable.ic_warning,
                if (verified) SpeedToolTheme.status.confirmed else caution,
            )
        }
        if (verified && testedFirmware.isNotEmpty()) {
            SheetText(
                stringResource(
                    R.string.verification_firmware,
                    testedFirmware.joinToString(stringResource(R.string.list_separator_spoken)),
                    BIKE_DEFAULT_KMH,
                ),
                R.drawable.ic_info,
            )
        } else {
            SheetText(stringResource(R.string.verification_firmware_unverified), R.drawable.ic_info)
        }
        if (!verified) {
            SheetText(
                stringResource(R.string.verification_unverified_caveat, BIKE_DEFAULT_KMH),
                R.drawable.ic_warning,
                caution,
            )
        }
        SheetText(stringResource(R.string.verification_how))
    }
}
