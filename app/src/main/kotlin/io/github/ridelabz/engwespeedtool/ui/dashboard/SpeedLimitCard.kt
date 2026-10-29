package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.theme.NumberStyle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/** How one speed choice looks right now. */
internal enum class ChipLook { PLAIN, DEFAULT, SENDING, ACTIVE, NOT_CONFIRMED }

private const val DIMMED = 0.38f
private const val MOTION_MS = 200

/**
 * The speed-limit card, the dashboard's main control: one tap on a speed applies it. The box of a speed the
 * bike confirmed shows it (green, with a ✓); a line under the boxes says what else the app knows when there is
 * news (a speed being sent or not confirmed, a restart), and no more: the bike can't report its limit, and while
 * it isn't connected no choice is shown as active. Its ⓘ explains the rest; speeds above the legal limit carry
 * their mark on the box.
 */
@Composable
fun SpeedLimitCard(
    state: SpeedLimitUiState,
    verified: Boolean,
    connection: Connection,
    onSpeed: (Int) -> Unit,
    onKeepSpeed: (Boolean) -> Unit,
    onNotConfirmedHelp: () -> Unit,
    modifier: Modifier = Modifier,
    onInfo: () -> Unit = {},
    onKeepInfo: () -> Unit = {},
) {
    val connected = connection == Connection.CONNECTED
    val status = state.status
    val sending = status is SpeedLimitStatus.Sending
    Haptics(status)
    NotSentHaptics(state.notSent)
    DashboardCard(modifier) {
        CardTitle(
            stringResource(R.string.speed_title),
            icon = R.drawable.ic_speed,
            onInfo = onInfo,
            infoLabel = R.string.speed_info,
        )
        // The boxes and their line, without the card's spacing: an empty line takes almost no room. The card grows
        // and shrinks smoothly as a line comes and goes ("Sending…" only for a moment), so what is below doesn't jump.
        Column(modifier = Modifier.animateContentSize()) {
            SpeedChoices(state, verified, connected, onSpeed)
            StatusText(
                status,
                verified,
                state.restartsTrusted,
                connection,
                onNotConfirmedHelp,
                notSent = state.notSent,
            )
        }
        // Not while a speed is sent: turning it on then would keep that speed without its warning.
        KeepRow(
            title = stringResource(R.string.keep_speed_title),
            checked = state.keepSpeed,
            // Off is always allowed.
            enabled = connected && (!sending || state.keepSpeed),
            onChange = onKeepSpeed,
            onInfo = onKeepInfo,
            infoLabel = R.string.keep_speed_info,
        )
    }
}

/** The speed boxes in a row: tapping one applies it; none is shown as active while the bike isn't connected. */
@Composable
private fun SpeedChoices(
    state: SpeedLimitUiState,
    verified: Boolean,
    connected: Boolean,
    onSpeed: (Int) -> Unit,
) {
    val status = state.status
    val sending = status is SpeedLimitStatus.Sending
    val announce = newlyConfirmed(status)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        for (kmh in state.choices) {
            val look = if (connected) lookOf(kmh, status, verified && state.restartsTrusted) else ChipLook.PLAIN
            SpeedChip(
                kmh = kmh,
                look = look,
                verified = verified,
                enabled = connected && !sending,
                announce = announce && look == ChipLook.ACTIVE,
                modifier =
                    Modifier
                        .weight(1f)
                        .alpha(if (!connected || (sending && look != ChipLook.SENDING)) DIMMED else 1f),
                onClick = { onSpeed(kmh) },
            )
        }
    }
}

/**
 * Whether [status] is a confirmation the card didn't show before: no line says it in words, so the confirmed box
 * is then read out, once. A speed confirmed before the card showed (back from another screen) isn't. The box stays
 * a live region for as long as that status holds, on purpose: nothing on it changes meanwhile, and screen readers
 * look at it only after the change has been passed on, so clearing it right away could lose the announcement.
 */
@Composable
private fun newlyConfirmed(status: SpeedLimitStatus): Boolean {
    val shown = remember { arrayOf(status) }
    // Not only after a "Sending" the card showed: a quick echo may come within one frame.
    val confirmed = remember(status) { status is SpeedLimitStatus.Confirmed && status != shown[0] }
    SideEffect { shown[0] = status }
    return confirmed
}

private fun notSentText(reason: NotSent): Int =
    when (reason) {
        NotSent.BIKE_NOT_ON -> R.string.speed_not_sent_bike_not_on
        NotSent.NOT_CONNECTED -> R.string.speed_not_sent_not_connected
        NotSent.BUSY -> R.string.speed_not_sent_busy
        NotSent.NOT_YET -> R.string.speed_not_sent_yet
    }

/** A double tap when a tapped speed wasn't sent, once: not again when the card shows the same note again. */
@Composable
private fun NotSentHaptics(notSent: SpeedNotSent?) {
    val haptics = LocalHapticFeedback.current
    val previous = remember { arrayOf(notSent) }
    LaunchedEffect(notSent) {
        val new = notSent != null && notSent !== previous[0]
        previous[0] = notSent
        if (new) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }
}

private fun lookOf(
    kmh: Int,
    status: SpeedLimitStatus,
    /** A verified bike whose restarts the app trusts: only there does a restart mean 25 again. */
    restartMeansDefault: Boolean,
): ChipLook =
    when (status) {
        SpeedLimitStatus.BikeDefault, SpeedLimitStatus.BikeRestarted -> {
            if (restartMeansDefault && kmh == BIKE_DEFAULT_KMH) ChipLook.DEFAULT else ChipLook.PLAIN
        }

        SpeedLimitStatus.Unknown -> {
            ChipLook.PLAIN
        }

        is SpeedLimitStatus.Sending -> {
            if (kmh == status.kmh) ChipLook.SENDING else ChipLook.PLAIN
        }

        is SpeedLimitStatus.Confirmed -> {
            if (kmh == status.kmh) ChipLook.ACTIVE else ChipLook.PLAIN
        }

        is SpeedLimitStatus.NotConfirmed -> {
            if (kmh == status.kmh) ChipLook.NOT_CONFIRMED else ChipLook.PLAIN
        }
    }

@Composable
private fun SpeedChip(
    kmh: Int,
    look: ChipLook,
    verified: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Read out when its state changes: the speed the bike just confirmed. */
    announce: Boolean = false,
) {
    val colours = MaterialTheme.colorScheme
    val statusColours = SpeedToolTheme.status
    val above = isAboveLegalLimit(kmh)
    val plainContent = if (above) statusColours.caution else colours.onSurface
    val container =
        when (look) {
            ChipLook.ACTIVE -> statusColours.confirmedContainer
            else -> Color.Transparent
        }
    val content =
        when (look) {
            ChipLook.ACTIVE -> statusColours.onConfirmedContainer
            ChipLook.NOT_CONFIRMED -> colours.error
            else -> plainContent
        }
    val border =
        when (look) {
            ChipLook.ACTIVE -> {
                null
            }

            ChipLook.NOT_CONFIRMED -> {
                BorderStroke(2.dp, colours.error)
            }

            ChipLook.DEFAULT -> {
                BorderStroke(2.dp, colours.primary)
            }

            ChipLook.SENDING, ChipLook.PLAIN -> {
                BorderStroke(
                    1.dp,
                    if (above) statusColours.caution else colours.outline,
                )
            }
        }
    val animatedContainer by animateColorAsState(container, tween(MOTION_MS), label = "")
    val animatedContent by animateColorAsState(content, tween(MOTION_MS), label = "")
    val label = stringResource(R.string.speed_chip_label, kmh)
    val action = stringResource(R.string.speed_chip_action, kmh)
    val stateText =
        listOfNotNull(
            when (look) {
                ChipLook.ACTIVE -> {
                    stringResource(
                        if (verified) R.string.speed_chip_active else R.string.speed_chip_confirmed,
                    )
                }

                ChipLook.SENDING -> {
                    stringResource(R.string.speed_chip_sending)
                }

                ChipLook.NOT_CONFIRMED -> {
                    stringResource(R.string.speed_chip_not_confirmed)
                }

                ChipLook.DEFAULT -> {
                    stringResource(R.string.speed_chip_default)
                }

                ChipLook.PLAIN -> {
                    null
                }
            },
            if (above) stringResource(R.string.speed_chip_above_limit) else null,
        ).joinToString(stringResource(R.string.list_separator_spoken))
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = animatedContainer,
        contentColor = animatedContent,
        border = border,
        modifier =
            modifier
                .heightIn(min = TouchTargets.speedChoice)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    if (stateText.isNotEmpty()) stateDescription = stateText
                    if (announce) liveRegion = LiveRegionMode.Polite
                    if (enabled) {
                        onClick(label = action) {
                            onClick()
                            true
                        }
                    }
                },
    ) {
        // The chip reads as one button: "32 km/h", its state, and what tapping does. The icon sits under
        // the number, so it keeps its room with four chips at large font sizes.
        Column(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.s).clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.number, kmh),
                style = MaterialTheme.typography.titleLarge.merge(NumberStyle),
            )
            ChipIcon(look, above)
        }
    }
}

/** A mark under a speed box's number: an icon, or the spinner while it is sent. */
internal sealed interface ChipMark {
    data class Icon(
        @param:DrawableRes val icon: Int,
    ) : ChipMark

    data object Spinner : ChipMark
}

/**
 * The marks under a box's number: its state (✓ confirmed, ! not confirmed, the spinner while sending), and
 * for a speed above the legal limit the warning, in every state, so the mark never goes from the card.
 */
internal fun chipMarks(
    look: ChipLook,
    above: Boolean,
): List<ChipMark> =
    listOfNotNull(
        when (look) {
            ChipLook.SENDING -> ChipMark.Spinner
            ChipLook.ACTIVE -> ChipMark.Icon(R.drawable.ic_check)
            ChipLook.NOT_CONFIRMED -> ChipMark.Icon(R.drawable.ic_error)
            ChipLook.PLAIN, ChipLook.DEFAULT -> null
        },
        if (above) ChipMark.Icon(R.drawable.ic_warning) else null,
    )

@Composable
private fun ChipIcon(
    look: ChipLook,
    above: Boolean,
) {
    Row(
        modifier = Modifier.heightIn(min = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (mark in chipMarks(look, above)) {
            when (mark) {
                ChipMark.Spinner -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                }

                is ChipMark.Icon -> {
                    Icon(
                        painter = painterResource(mark.icon),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The card's line of status, only when there is news and only while the bike is connected: the status card at
 * the top already says when it isn't (connecting, off, out of reach). A tapped speed that wasn't sent says so
 * instead. Without news the line takes almost no room (the keep switch follows the boxes) and screen readers
 * skip it; it is always there, so that a new line is read out.
 */
@Composable
private fun StatusText(
    status: SpeedLimitStatus,
    verified: Boolean,
    trusted: Boolean,
    connection: Connection,
    onNotConfirmedHelp: () -> Unit,
    notSent: SpeedNotSent? = null,
) {
    val notSentLine = notSent?.let { stringResource(notSentText(it.reason), it.kmh) }
    val connected = connection == Connection.CONNECTED
    val line = notSentLine ?: if (connected) statusLine(status, verified, trusted) else null
    val notConfirmed = status is SpeedLimitStatus.NotConfirmed && connected
    // The only line under a confirmed box is an untested model's caveat. Read out, it names the speed: the box
    // and the line may be heard in either order, and when swiping, the box just before the line may be another one.
    val spoken =
        if (line != null && notSentLine == null && status is SpeedLimitStatus.Confirmed) {
            stringResource(R.string.speed_status_confirmed_unverified_spoken, status.kmh, BIKE_DEFAULT_KMH)
        } else {
            null
        }
    val lineModifier =
        if (line != null) {
            // Read out when it changes, as far below the boxes as the card's spacing.
            Modifier.padding(top = Spacing.m).semantics {
                liveRegion = LiveRegionMode.Polite
                spoken?.let { contentDescription = it }
            }
        } else {
            Modifier.height(EMPTY_LINE_HEIGHT).clearAndSetSemantics {}
        }
    Text(
        text = line.orEmpty(),
        style = MaterialTheme.typography.bodyLarge,
        color =
            if (notSentLine != null || notConfirmed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        modifier = Modifier.fillMaxWidth().then(lineModifier),
    )
    if (notConfirmed) {
        TextButton(onClick = onNotConfirmedHelp, modifier = Modifier.heightIn(min = TouchTargets.minimum)) {
            Text(stringResource(R.string.speed_not_confirmed_help))
        }
    }
}

/** The line for [status], or null when there is no news: the app can't tell the limit and has sent nothing. */
@Composable
private fun statusLine(
    status: SpeedLimitStatus,
    verified: Boolean,
    trusted: Boolean,
): String? =
    when (status) {
        SpeedLimitStatus.BikeDefault -> {
            when {
                verified && trusted -> stringResource(R.string.speed_status_default, BIKE_DEFAULT_KMH)
                verified -> restartedLine(verified = true, trusted = false)
                else -> stringResource(R.string.speed_status_none_unverified)
            }
        }

        SpeedLimitStatus.Unknown -> {
            null
        }

        is SpeedLimitStatus.Sending -> {
            when (status.reapply) {
                // Switched off and on at the display, or locked and unlocked: the same to the app.
                Reapply.RESTART -> stringResource(R.string.speed_status_reapplying, status.kmh)

                // The app can't tell whether the bike restarted while the connection was down.
                Reapply.RECONNECT -> stringResource(R.string.speed_status_reapplying_reconnected, status.kmh)

                null -> stringResource(R.string.speed_status_sending, status.kmh)
            }
        }

        is SpeedLimitStatus.Confirmed -> {
            if (verified || status.kmh == BIKE_DEFAULT_KMH) {
                // The box says it: green, with a ✓, read out as Active (Confirmed on an untested model).
                null
            } else {
                // How long it lasts on an untested model isn't known: said under the box.
                stringResource(R.string.speed_status_confirmed_unverified, BIKE_DEFAULT_KMH)
            }
        }

        is SpeedLimitStatus.NotConfirmed -> {
            stringResource(R.string.speed_status_not_confirmed, status.kmh)
        }

        SpeedLimitStatus.BikeRestarted -> {
            restartedLine(verified, trusted)
        }
    }

/**
 * After a restart the app saw: what the project's tests showed, only on a verified bike whose restarts the
 * app trusts (its power flags are verified); on a verified bike otherwise (only where the session is told not
 * to trust restarts), that the bike reported one and how to be sure; on an unverified bike, that the limit may
 * not have reset.
 */
@Composable
private fun restartedLine(
    verified: Boolean,
    trusted: Boolean,
): String =
    when {
        verified && trusted -> stringResource(R.string.speed_status_restarted, BIKE_DEFAULT_KMH)
        verified -> stringResource(R.string.speed_status_restarted_unsure, BIKE_DEFAULT_KMH)
        else -> stringResource(R.string.speed_status_restarted_unverified, BIKE_DEFAULT_KMH)
    }

/** A compact "keep" switch: its title and the switch, with its ⓘ outside the switch's touch area. */
@Composable
internal fun KeepRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    onInfo: () -> Unit,
    @StringRes infoLabel: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = TouchTargets.minimum)
                    .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(
                painterResource(R.drawable.ic_restart_alt),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        InfoButton(onInfo, infoLabel)
    }
}

/**
 * A light tap when the bike confirms a speed the rider tapped, a double one when it doesn't (also for an
 * automatic re-apply). Only on a change after sending, never on first show.
 */
@Composable
private fun Haptics(status: SpeedLimitStatus) {
    val haptics = LocalHapticFeedback.current
    val previous = remember { arrayOf<SpeedLimitStatus>(status) }
    LaunchedEffect(status) {
        val after = previous[0] is SpeedLimitStatus.Sending
        previous[0] = status
        val feedback =
            when {
                !after -> null
                status is SpeedLimitStatus.Confirmed && status.reapply == null -> HapticFeedbackType.Confirm
                status is SpeedLimitStatus.NotConfirmed -> HapticFeedbackType.Reject
                else -> null
            }
        feedback?.let(haptics::performHapticFeedback)
    }
}
