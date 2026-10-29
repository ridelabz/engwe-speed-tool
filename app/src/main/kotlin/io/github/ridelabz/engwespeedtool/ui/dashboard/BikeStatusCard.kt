package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.theme.NumberStyle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/** The battery gauge's height: readable at a glance. */
private val GAUGE_HEIGHT = 44.dp

/** The battery's cells, split by thin lines. */
private const val CELLS = 5

/** The power button's icon, or its spinner while a change is on its way. */
private val POWER_ICON_SIZE = 24.dp

/**
 * The bike first: its name with Turn off or Turn on at the end of the row where the bike's profile offers it,
 * then a pill saying whether it is on, off (or locked: the app can't tell the two apart), connecting or out of
 * reach, and the verification chip (which explains verification).
 */
@Composable
internal fun BikeStatusCard(
    state: DashboardUiState,
    name: String,
    onVerification: () -> Unit,
    onPower: (Boolean) -> Unit = {},
) {
    DashboardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(painterResource(R.drawable.ic_electric_bike), contentDescription = null)
            // The name gives way (it wraps); the button keeps its 48 dp at the end of the row.
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            state.power?.let { power -> PowerButton(state, power, onPower) }
        }
        Column {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(state.connection, state.reportsOn)
                // Only once this run checked the bike: until then there is nothing to claim either way.
                if (state.checked) VerificationChip(state.verified, onVerification)
            }
            state.power?.let { PowerStatus(state, it) }
        }
        // Off or locked: the pill says so; an unverified bike's limit may not have reset, said here.
        if (state.connection == Connection.BIKE_OFF && state.unverified) {
            StatusLine(stringResource(R.string.bike_off_unverified, BIKE_DEFAULT_KMH))
        }
    }
}

/**
 * The battery, in a card of its own: "Battery" and the percentage in large type, and the gauge as wide as the
 * card. Without a reading (also while the bike isn't connected) the gauge is an empty outline and the value a
 * dash.
 */
@Composable
internal fun BatteryCard(state: DashboardUiState) {
    DashboardCard {
        Battery(
            percent = state.ride?.batteryPercent?.takeIf { state.connected },
            unverified = state.unverified,
        )
    }
}

/**
 * Turn off while the bike reports on, Turn on while it reports off (or locked): a round icon button that locks
 * or unlocks the bike, filled while the bike is on and outlined while it is off or locked. Screen readers say
 * what a tap does ("Turn the bike off"), and a long press shows the same text. Disabled while a change is on
 * its way (then still in full colour, with a spinner, read out as "Turning off…") and whenever the bike reports
 * neither; then it looks and reads as for the bike's last reported state (Turn off before it reported any).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerButton(
    state: DashboardUiState,
    power: PowerUiState,
    onPower: (Boolean) -> Unit,
) {
    val (turnOn, enabled, sending) = powerButtonLook(state, power)
    val spoken =
        stringResource(
            when (power.switching) {
                true -> R.string.power_turning_on
                false -> R.string.power_turning_off
                null -> if (turnOn) R.string.power_turn_on_spoken else R.string.power_turn_off_spoken
            },
        )
    val colours = MaterialTheme.colorScheme
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(spoken) } },
        state = rememberTooltipState(),
    ) {
        // One button whose look changes, never two: screen readers keep their place when the bike turns off.
        val content = LocalContentColor.current
        OutlinedIconButton(
            onClick = { onPower(turnOn) },
            enabled = enabled,
            modifier = Modifier.size(TouchTargets.minimum).semantics { contentDescription = spoken },
            colors =
                when {
                    turnOn && sending -> {
                        IconButtonDefaults.outlinedIconButtonColors(disabledContentColor = content)
                    }

                    turnOn -> {
                        IconButtonDefaults.outlinedIconButtonColors()
                    }

                    sending -> {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            disabledContainerColor = colours.secondaryContainer,
                            disabledContentColor = colours.onSecondaryContainer,
                        )
                    }

                    else -> {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    }
                },
            // Outlined while the bike is off (in full colour while it is being turned on); filled while it is on.
            border = if (turnOn) IconButtonDefaults.outlinedIconButtonBorder(enabled || sending) else null,
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(POWER_ICON_SIZE),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Icon(painterResource(R.drawable.ic_power_settings_new), contentDescription = null)
            }
        }
    }
}

/** How the power button shows, from the bike's state and the last tap. */
internal data class PowerButtonLook(
    /** A tap turns the bike on, and the button is outlined; otherwise a tap turns it off, and it is filled. */
    val turnOn: Boolean,
    /** It takes a tap: the bike reports the state that the tap changes, and nothing is on its way. */
    val enabled: Boolean,
    /** A change is on its way: the button shows a spinner, in full colour. */
    val sending: Boolean,
)

/**
 * What the power button does and how it looks: Turn off while the bike reports on, Turn on while it reports
 * off; while a change is on its way, the one being sent; while it reports neither, as for its last reported
 * state (Turn off before it reported any).
 */
internal fun powerButtonLook(
    state: DashboardUiState,
    power: PowerUiState,
): PowerButtonLook {
    val on = state.connected && state.reportsOn
    val reportsOff = state.connection == Connection.BIKE_OFF && state.reportsOff
    val off = if (reportsOff || on) reportsOff else power.lastOff ?: false
    val sending = power.switching != null
    return PowerButtonLook(
        turnOn = power.switching ?: off,
        enabled = !sending && (reportsOff || on),
        sending = sending,
    )
}

/**
 * One line about the last Turn on or Turn off, if it wasn't sent or confirmed; never "not confirmed" once the
 * bike reports the state it was asked for. The line is always there, empty and skipped by screen readers while
 * there is nothing to say, so that a new note is read out.
 */
@Composable
private fun PowerStatus(
    state: DashboardUiState,
    power: PowerUiState,
) {
    val reachedAnyway =
        when (power.notConfirmed) {
            true -> state.connected && state.reportsOn
            false -> state.connection == Connection.BIKE_OFF && state.reportsOff
            null -> false
        }
    val line =
        when {
            power.notSent != null -> stringResource(powerNotSentText(power.notSent, power.notSentOn))
            power.notConfirmed == true && !reachedAnyway -> stringResource(R.string.power_on_not_confirmed)
            power.notConfirmed == false && !reachedAnyway -> stringResource(R.string.power_off_not_confirmed)
            else -> null
        }
    val hasLine = line != null
    // Read out when it has text; while it has none it takes almost no room and screen readers skip it.
    val lineModifier =
        if (hasLine) {
            Modifier.padding(top = Spacing.xs).semantics { liveRegion = LiveRegionMode.Polite }
        } else {
            Modifier.height(EMPTY_LINE_HEIGHT).clearAndSetSemantics {}
        }
    StatusLine(
        line.orEmpty(),
        modifier = Modifier.fillMaxWidth().then(lineModifier),
        colour = MaterialTheme.colorScheme.error,
    )
}

/** Why a tap on Turn on ([on]) or Turn off wasn't sent, in words. */
private fun powerNotSentText(
    reason: PowerNotSent,
    on: Boolean,
): Int =
    when (reason) {
        PowerNotSent.BIKE_NOT_ON -> {
            R.string.power_off_not_sent_bike_not_on
        }

        PowerNotSent.BIKE_NOT_OFF -> {
            R.string.power_on_not_sent_bike_not_off
        }

        PowerNotSent.MOVING -> {
            R.string.power_off_not_sent_moving
        }

        PowerNotSent.NOT_CONNECTED -> {
            if (on) R.string.power_on_not_sent_not_connected else R.string.power_off_not_sent_not_connected
        }

        PowerNotSent.BUSY -> {
            if (on) R.string.power_on_not_sent_busy else R.string.power_off_not_sent_busy
        }

        PowerNotSent.NOT_YET -> {
            if (on) R.string.power_on_not_sent_yet else R.string.power_off_not_sent_yet
        }
    }

/** How the pill shows a connection: its icon (null: a spinner), text and colours. */
private class PillLook(
    @param:DrawableRes val icon: Int?,
    @param:StringRes val text: Int,
    val container: Color,
    val content: Color,
)

@Composable
private fun pillLook(
    connection: Connection,
    reportsOn: Boolean,
): PillLook {
    val c = MaterialTheme.colorScheme
    val s = SpeedToolTheme.status
    return when (connection) {
        Connection.CONNECTING -> {
            PillLook(null, R.string.connection_connecting, c.secondaryContainer, c.onSecondaryContainer)
        }

        Connection.CONNECTED -> {
            if (reportsOn) {
                PillLook(
                    R.drawable.ic_check_circle,
                    R.string.connection_on,
                    s.confirmedContainer,
                    s.onConfirmedContainer,
                )
            } else {
                PillLook(
                    R.drawable.ic_bluetooth,
                    R.string.connection_connected,
                    c.secondaryContainer,
                    c.onSecondaryContainer,
                )
            }
        }

        Connection.RECONNECTING -> {
            PillLook(null, R.string.connection_not_connected, c.surfaceVariant, c.onSurfaceVariant)
        }

        Connection.BIKE_OFF -> {
            PillLook(
                R.drawable.ic_power_settings_new,
                R.string.connection_bike_off,
                c.inverseSurface,
                c.inverseOnSurface,
            )
        }

        Connection.BLUETOOTH_OFF -> {
            PillLook(
                R.drawable.ic_bluetooth_disabled,
                R.string.connection_bluetooth_off,
                c.errorContainer,
                c.onErrorContainer,
            )
        }

        Connection.NO_PERMISSION -> {
            PillLook(
                R.drawable.ic_bluetooth_disabled,
                R.string.connection_no_permission,
                c.errorContainer,
                c.onErrorContainer,
            )
        }

        Connection.INCOMPATIBLE -> {
            PillLook(R.drawable.ic_link_off, R.string.connection_incompatible, c.errorContainer, c.onErrorContainer)
        }
    }
}

/** Whether the bike is on, off or locked, connecting or out of reach: an icon and its words. */
@Composable
private fun StatusPill(
    connection: Connection,
    reportsOn: Boolean,
) {
    val look = pillLook(connection, reportsOn)
    val shape = MaterialTheme.shapes.small
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(look.container, shape)
                // Read out when it changes: the cards below only grey out.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (look.icon == null) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = look.content)
        } else {
            Icon(
                painterResource(look.icon),
                contentDescription = null,
                tint = look.content,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(stringResource(look.text), style = MaterialTheme.typography.labelLarge, color = look.content)
    }
}

/** Verified or Unverified; a tap explains what that means and offers a report. */
@Composable
private fun VerificationChip(
    verified: Boolean,
    onClick: () -> Unit,
) {
    val status = SpeedToolTheme.status
    val container = if (verified) status.confirmedContainer else status.cautionContainer
    val content = if (verified) status.onConfirmedContainer else status.onCautionContainer
    val shape = MaterialTheme.shapes.small
    val source = remember { MutableInteractionSource() }
    // The control is the 48 dp box around the pill; the ripple stays on the pill.
    Box(
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clickable(
                    interactionSource = source,
                    indication = null,
                    onClickLabel = stringResource(R.string.badge_explain),
                    role = Role.Button,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(shape)
                    .indication(source, ripple())
                    .background(container, shape)
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(
                painter = painterResource(if (verified) R.drawable.ic_verified else R.drawable.ic_warning),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(if (verified) R.string.badge_verified else R.string.badge_unverified),
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The battery: "Battery" and the percentage in large type on one row, and under it a gauge as wide as the
 * card, read together as "Battery 76 %". Without a reading the gauge is an empty outline and the value a dash.
 */
@Composable
private fun Battery(
    percent: Int?,
    unverified: Boolean,
) {
    val spoken =
        listOfNotNull(
            if (percent != null) {
                stringResource(R.string.battery_spoken, percent)
            } else {
                stringResource(R.string.battery_spoken_none)
            },
            if (unverified) stringResource(R.string.value_unverified) else null,
        ).joinToString(stringResource(R.string.list_separator_spoken))
    Column(
        // Read as one, and as the card's heading.
        modifier =
            Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription = spoken
                heading()
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(
                painterResource(R.drawable.ic_battery_full),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            // The label gives way (it may wrap), the percentage never does.
            Text(
                stringResource(R.string.battery_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    percent?.let { stringResource(R.string.battery_value, it) }
                        ?: stringResource(R.string.value_missing),
                style = MaterialTheme.typography.headlineMedium.merge(NumberStyle),
                softWrap = false,
            )
        }
        BatteryGauge(percent, background = MaterialTheme.colorScheme.surfaceContainer)
        if (unverified) UnverifiedMarker()
    }
}

/** The colour of a battery at [percent]: green from 50 %, yellow from 20 %, red below; null without a reading. */
@Composable
internal fun batteryColour(percent: Int?): Color? {
    val gauge = SpeedToolTheme.gauge
    return when {
        percent == null -> null
        percent >= BATTERY_GOOD_FROM -> gauge.batteryGood
        percent >= BATTERY_LOW_BELOW -> gauge.batteryMid
        else -> gauge.batteryLow
    }
}

/**
 * A horizontal battery as wide as it is given: an outline with the terminal nub at the end, split into 5
 * cells by thin lines, filled from the start by [percent] (the lines drawn over the fill, so 76 % fills three
 * and a half cells). Right-to-left layouts fill from the right, with the nub on the left.
 */
@Composable
internal fun BatteryGauge(
    percent: Int?,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val divider = MaterialTheme.colorScheme.outlineVariant
    val fill = batteryColour(percent)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier = modifier.fillMaxWidth().height(GAUGE_HEIGHT)) {
        val stroke = 2.dp.toPx()
        val gap = 3.dp.toPx()
        val nub = 6.dp.toPx()
        val corner = CornerRadius(8.dp.toPx())
        val bodyWidth = size.width - nub
        scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f) {
            drawRoundRect(
                color = outline,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(bodyWidth - stroke, size.height - stroke),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
            val nubHeight = size.height * 0.4f
            drawRoundRect(
                color = outline,
                topLeft = Offset(bodyWidth, (size.height - nubHeight) / 2),
                size = Size(nub, nubHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            val left = stroke + gap
            val top = stroke + gap
            val width = bodyWidth - 2 * (stroke + gap)
            val height = size.height - 2 * (stroke + gap)
            val filled = width * ((percent ?: 0).coerceIn(0, 100) / 100f)
            if (fill != null && filled > 0f) {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = Size(filled, height),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            for (i in 1 until CELLS) {
                val x = left + width * i / CELLS
                // A gap through the fill; a thin line across the empty part.
                val colour = if (x <= left + filled) background else divider
                drawLine(colour, Offset(x, top), Offset(x, top + height), strokeWidth = stroke)
            }
        }
    }
}
