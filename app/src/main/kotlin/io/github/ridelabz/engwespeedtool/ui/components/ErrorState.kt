package io.github.ridelabz.engwespeedtool.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing

/** The situations in which the app can't go on by itself. Each names the next step. */
enum class ErrorKind(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val cause: Int,
    @param:StringRes val primaryAction: Int,
    @param:StringRes val secondaryAction: Int?,
    val isFailure: Boolean,
) {
    BLUETOOTH_OFF(
        R.drawable.ic_bluetooth_disabled,
        R.string.error_bluetooth_off_title,
        R.string.error_bluetooth_off_cause,
        R.string.action_turn_on,
        null,
        isFailure = false,
    ),
    NO_PERMISSION(
        R.drawable.ic_bluetooth_disabled,
        R.string.error_no_permission_title,
        R.string.error_no_permission_cause,
        R.string.action_allow,
        null,
        isFailure = false,
    ),

    /** Android won't ask for the permission again: only the app's settings can allow it. */
    NO_PERMISSION_SETTINGS(
        R.drawable.ic_bluetooth_disabled,
        R.string.error_no_permission_title,
        R.string.error_no_permission_settings_cause,
        R.string.action_open_settings,
        null,
        isFailure = false,
    ),
    BIKE_NOT_FOUND(
        R.drawable.ic_search_off,
        R.string.error_not_found_title,
        R.string.error_not_found_cause,
        R.string.action_scan_again,
        R.string.action_help,
        isFailure = false,
    ),
    CONNECTED_ELSEWHERE(
        R.drawable.ic_link_off,
        R.string.error_elsewhere_title,
        R.string.error_elsewhere_cause,
        R.string.action_retry,
        R.string.action_help,
        isFailure = false,
    ),
    INCOMPATIBLE(
        R.drawable.ic_error,
        R.string.error_incompatible_title,
        R.string.error_incompatible_cause,
        R.string.action_report,
        R.string.action_diagnostics,
        isFailure = true,
    ),

    /** The saved bike failed a check after it had passed: the app sends it nothing until the rider tries again. */
    NOT_RESPONDING(
        R.drawable.ic_error,
        R.string.error_not_responding_title,
        R.string.error_not_responding_cause,
        R.string.action_try_again,
        R.string.action_report,
        isFailure = true,
    ),

    /** The rider hasn't accepted the risks (for the current text), so the app read nothing. */
    NOT_ACCEPTED(
        R.drawable.ic_gavel,
        R.string.error_not_accepted_title,
        R.string.error_not_accepted_cause,
        R.string.action_review_risks,
        null,
        isFailure = false,
    ),
    NOT_CONFIRMED(
        R.drawable.ic_error,
        R.string.error_not_confirmed_title,
        R.string.error_not_confirmed_cause,
        R.string.action_retry,
        R.string.action_report,
        isFailure = true,
    ),
}

/**
 * An error state: icon, what happened, one sentence on why, and the actions (the second only with
 * [onSecondary]). With [announce], screen readers read the title when it changes; turn it off inside a
 * container that is already a live region.
 */
@Composable
fun ErrorState(
    kind: ErrorKind,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    onSecondary: (() -> Unit)? = null,
    announce: Boolean = true,
    details: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(
            painter = painterResource(kind.icon),
            contentDescription = null,
            tint = if (kind.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(kind.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier =
                Modifier.semantics {
                    heading()
                    if (announce) liveRegion = LiveRegionMode.Polite
                },
        )
        Text(
            text = stringResource(kind.cause),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        details()
        PrimaryAction(stringResource(kind.primaryAction), onPrimary)
        if (kind.secondaryAction != null &&
            onSecondary != null
        ) {
            SecondaryAction(stringResource(kind.secondaryAction), onSecondary)
        }
    }
}

/**
 * An error inside a screen that goes on around it (the dashboard): a card like the dashboard's other notices,
 * with the icon at the start, what happened and why, then the actions (the second only with [onSecondary]).
 * Screen readers read the title when it changes, as with [ErrorState].
 */
@Composable
fun ErrorCard(
    kind: ErrorKind,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    onSecondary: (() -> Unit)? = null,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Icon(
                    painter = painterResource(kind.icon),
                    contentDescription = null,
                    tint = if (kind.isFailure) MaterialTheme.colorScheme.error else LocalContentColor.current,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = stringResource(kind.title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier =
                            Modifier.semantics {
                                heading()
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                    Text(
                        text = stringResource(kind.cause),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val label = kind.secondaryAction
            val secondary: (@Composable (Modifier) -> Unit)? =
                if (label != null && onSecondary != null) {
                    @Composable { m: Modifier -> SecondaryAction(stringResource(label), onSecondary, m) }
                } else {
                    null
                }
            ButtonPair(
                secondary = secondary,
                primary = { m -> PrimaryAction(stringResource(kind.primaryAction), onPrimary, m) },
            )
        }
    }
}
