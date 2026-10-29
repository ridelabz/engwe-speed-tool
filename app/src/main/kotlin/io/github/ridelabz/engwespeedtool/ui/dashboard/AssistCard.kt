package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.ui.theme.NumberStyle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme

/** The assist bar's segments stand this tall, so that each is easy to tap. */
private val SEGMENT_HEIGHT = 52.dp

/** The gap between segments: thin, so that six still fit 48 dp wide each on a 360 dp phone. */
private val SEGMENT_GAP = 1.dp
private const val DIMMED = 0.38f

/** Before the bike's first check, the greyed-out bar has the reference bike's levels. */
internal const val PLACEHOLDER_LEVELS: Int = AssistLevel.MAX

/**
 * The assist level: the number the bike reports, always written out, and, on a bike whose profile gives its
 * levels, a bar with one segment per level (0 grey, then blue to magenta) to set it with a tap, and the "keep"
 * switch. Other bikes show the number only. Until this run has [checked] the bike the card keeps its place,
 * greyed out like the other cards, with the bar of [AssistUiState.levels] or, before any check, of
 * [PLACEHOLDER_LEVELS].
 */
@Composable
internal fun AssistCard(
    state: AssistUiState,
    connected: Boolean,
    checked: Boolean,
    unverified: Boolean,
    onLevel: (Int) -> Unit,
    onKeep: (Boolean) -> Unit,
    onInfo: () -> Unit,
    onKeepInfo: () -> Unit,
) {
    val level = state.level?.takeIf { connected }
    val levels = state.levels ?: PLACEHOLDER_LEVELS.takeIf { !checked }
    AssistHaptics(state)
    // No line says it: the heading ("Assist 3 of 5") says so while the bike's confirmation of a level the app set
    // holds, and is read out when it comes. Its text may not change then (the reading can come first, or the level
    // was already shown), its state always does. Without a reading (for a moment, or while the bike isn't connected),
    // the last confirmation is kept. Only a level the bike reports right now counts as confirmed.
    val confirmed = state.confirmed?.takeIf { assistTakesTaps(connected, checked, state.settable) && it == level }
    val announce = isNewConfirmation(confirmed?.let { it to state.reapply }, hold = level == null)
    val confirmedText = stringResource(R.string.assist_heading_confirmed)
    DashboardCard {
        val spoken =
            when {
                level == null -> stringResource(R.string.assist_spoken_none)
                levels != null -> stringResource(R.string.assist_spoken, level, levels)
                else -> stringResource(R.string.assist_spoken_level, level)
            }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = spoken
                        heading()
                        if (confirmed != null) stateDescription = confirmedText
                        if (announce) liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Icon(painterResource(R.drawable.ic_electric_bike), contentDescription = null)
                Text(
                    stringResource(R.string.assist_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text =
                        when {
                            level == null -> stringResource(R.string.value_missing)
                            levels != null -> stringResource(R.string.assist_value, level, levels)
                            else -> stringResource(R.string.number, level)
                        },
                    style = MaterialTheme.typography.titleLarge.merge(NumberStyle),
                )
            }
            InfoButton(onInfo, R.string.assist_info)
        }
        if (unverified) UnverifiedMarker()
        if (levels == null) {
            StatusLine(stringResource(R.string.assist_status_shown_only))
        } else {
            // Only the levels of a checked bike's profile take taps: never the placeholder before the check.
            SettableAssist(
                state = state,
                level = level,
                levels = levels,
                enabled = assistTakesTaps(connected, checked, state.settable),
                placeholder = !checked,
                onLevel = onLevel,
                onKeep = onKeep,
                onKeepInfo = onKeepInfo,
            )
        }
    }
}

/**
 * The bar, its status line and the "keep" switch, for a bike whose profile gives its [levels], or the greyed-out
 * [placeholder] before the check (which screen readers skip: the card's heading says there is no reading).
 */
@Composable
private fun SettableAssist(
    state: AssistUiState,
    level: Int?,
    levels: Int,
    /** The bike is connected and checked, and its profile gives these levels. */
    enabled: Boolean,
    placeholder: Boolean,
    onLevel: (Int) -> Unit,
    onKeep: (Boolean) -> Unit,
    onKeepInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        val sending = state.sending
        // The bar and its line, without the spacing: an empty line takes almost no room.
        Column {
            AssistBar(
                level = level,
                levels = levels,
                sending = sending,
                active = enabled,
                onLevel = onLevel,
                modifier = if (placeholder) Modifier.clearAndSetSemantics {} else Modifier,
            )
            AssistStatus(state.takeIf { enabled })
        }
        KeepRow(
            title = stringResource(R.string.keep_assist_title),
            checked = state.keep,
            // Off is always allowed; on waits while a level is sent, so that it keeps the confirmed one.
            enabled = enabled && (sending == null || state.keep),
            onChange = onKeep,
            onInfo = onKeepInfo,
            infoLabel = R.string.keep_assist_info,
        )
    }
}

/**
 * One segment per level from 0 to [levels]: from 0 up to the bike's [level] filled in each level's colour (0
 * grey), the rest an outline; each shows its number and sets its level when tapped. Greyed out and disabled
 * while it isn't [active] (the bike not connected, or not checked), and while a level is being sent.
 */
@Composable
private fun AssistBar(
    level: Int?,
    levels: Int,
    sending: Int?,
    active: Boolean,
    onLevel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = active && sending == null
    val gauge = SpeedToolTheme.gauge
    val colours = MaterialTheme.colorScheme
    val sendingText = stringResource(R.string.assist_segment_sending)
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        for (i in 0..levels) {
            val filled = segmentFilled(i, level)
            val fill = if (filled) gauge.assistLevel(i, levels) else Color.Transparent
            val content =
                when {
                    !filled -> colours.onSurfaceVariant
                    i == 0 -> gauge.onAssistNone
                    else -> gauge.onAssist
                }
            val shape =
                RoundedCornerShape(
                    topStart = if (i == 0) 12.dp else 4.dp,
                    bottomStart = if (i == 0) 12.dp else 4.dp,
                    topEnd = if (i == levels) 12.dp else 4.dp,
                    bottomEnd = if (i == levels) 12.dp else 4.dp,
                )
            val label = stringResource(R.string.assist_segment, i)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = SEGMENT_HEIGHT)
                        .alpha(if (!active || (sending != null && sending != i)) DIMMED else 1f)
                        .clip(shape)
                        .then(
                            if (filled) {
                                Modifier.background(fill, shape)
                            } else {
                                Modifier.border(BorderStroke(1.dp, colours.outline), shape)
                            },
                        ).then(
                            if (i == level) Modifier.border(BorderStroke(2.dp, colours.onSurface), shape) else Modifier,
                        ).selectable(selected = i == level, enabled = enabled, role = Role.RadioButton) { onLevel(i) }
                        .semantics {
                            contentDescription = label
                            if (i == sending) stateDescription = sendingText
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.number, i),
                    style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
                    color = content,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/**
 * Whether the bar and the keep switch take taps: only for a connected bike this run checked, whose profile gives
 * its levels. Never the placeholder before the check, whatever the connection reads.
 */
internal fun assistTakesTaps(
    connected: Boolean,
    checked: Boolean,
    settable: Boolean,
): Boolean = connected && checked && settable

/** Whether the bar fills [segment] for the bike's [level]: every segment from 0 up to the level, 0 included. */
internal fun segmentFilled(
    segment: Int,
    level: Int?,
): Boolean = level != null && segment <= level

/**
 * The card's one line about the last level the app sent, when there is news: being sent, not confirmed or not
 * sent. A level the bike confirmed needs no line: the bar and the heading show it.
 */
@Composable
private fun AssistStatus(state: AssistUiState?) {
    val line =
        when {
            state == null -> {
                null
            }

            state.notSent != null -> {
                stringResource(
                    when (state.notSent) {
                        NotSent.BIKE_NOT_ON -> R.string.assist_not_sent_bike_not_on
                        NotSent.NOT_CONNECTED -> R.string.assist_not_sent_not_connected
                        NotSent.BUSY -> R.string.assist_not_sent_busy
                        NotSent.NOT_YET -> R.string.assist_not_sent_yet
                    },
                )
            }

            state.sending != null -> {
                stringResource(
                    when (state.reapply) {
                        Reapply.RESTART -> R.string.assist_status_reapplying_restart
                        Reapply.RECONNECT -> R.string.assist_status_reapplying
                        null -> R.string.assist_status_sending
                    },
                    state.sending,
                )
            }

            state.notConfirmed != null -> {
                stringResource(R.string.assist_status_not_confirmed, state.notConfirmed)
            }

            else -> {
                null
            }
        }
    val failed = state != null && (state.notSent != null || (state.sending == null && state.notConfirmed != null))
    // Always there, so that a new line is read out; without one it takes almost no room and is skipped.
    StatusLine(
        line.orEmpty(),
        modifier =
            if (line != null) {
                Modifier.padding(top = Spacing.m).semantics { liveRegion = LiveRegionMode.Polite }
            } else {
                Modifier.height(EMPTY_LINE_HEIGHT).clearAndSetSemantics {}
            },
        colour = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A light tap when the bike confirms a level the rider tapped, a double one when it doesn't. */
@Composable
private fun AssistHaptics(state: AssistUiState) {
    val haptics = LocalHapticFeedback.current
    val previous = remember { arrayOf(state.sending) }
    LaunchedEffect(state.sending, state.confirmed, state.notConfirmed) {
        val was = previous[0]
        previous[0] = state.sending
        if (was == null || state.sending != null || state.reapplied) return@LaunchedEffect
        when (was) {
            state.confirmed -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            state.notConfirmed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }
}
