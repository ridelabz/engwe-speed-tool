package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/** The lights as a tile: its icon lit while they are on, their state, and the switch. */
@Composable
internal fun LightsTile(
    state: DashboardUiState,
    onLights: (Boolean) -> Unit,
) {
    val lights = state.lights.takeIf { state.connected } ?: LightsUiState(Lights.UNKNOWN)
    val on = lights.lights == Lights.ON
    val enabled = state.connected && !lights.sending
    val value =
        when {
            lights.sending -> {
                stringResource(
                    if (lights.switchingOn == false) R.string.lights_switching_off else R.string.lights_switching_on,
                )
            }

            lights.notSent != null -> {
                stringResource(
                    when (lights.notSent) {
                        NotSent.BIKE_NOT_ON -> R.string.lights_not_sent_bike_not_on
                        NotSent.NOT_CONNECTED -> R.string.lights_not_sent_not_connected
                        NotSent.BUSY -> R.string.lights_not_sent_busy
                        NotSent.NOT_YET -> R.string.lights_not_sent_yet
                    },
                )
            }

            lights.notConfirmed -> {
                stringResource(R.string.lights_not_confirmed)
            }

            lights.lights == Lights.ON -> {
                stringResource(R.string.lights_on)
            }

            lights.lights == Lights.OFF -> {
                stringResource(R.string.lights_off)
            }

            else -> {
                stringResource(R.string.lights_unknown)
            }
        }
    val failed = lights.notConfirmed || lights.notSent != null
    val colours = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colours.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTargets.minimum)
                    .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onLights)
                    .semantics {
                        stateDescription = value
                        // Read out when a switch wasn't sent or confirmed; other changes only on focus.
                        if (failed) liveRegion = LiveRegionMode.Polite
                    }.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(if (on) colours.primaryContainer else colours.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_lightbulb),
                    contentDescription = null,
                    tint = if (on) colours.onPrimaryContainer else colours.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.lights_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) colours.error else colours.onSurfaceVariant,
                    // The row already reads it as its state.
                    modifier = Modifier.clearAndSetSemantics {},
                )
                if (state.unverified) UnverifiedMarker()
            }
            Switch(checked = on, onCheckedChange = null, enabled = enabled)
        }
    }
}

/** One reading: an icon, what it is, and its value (or a dash), read as one. */
private class Reading(
    @param:DrawableRes val icon: Int,
    val label: String,
    val value: String?,
    /** How a screen reader says the value, if not as shown. */
    val spoken: String? = null,
    /** Its meaning isn't verified for this (verified) bike: the tile says so. */
    val unverified: Boolean = false,
)

/**
 * The bike's readings as tiles: odometer, max speed, speed, temperature, battery health, voltage and total
 * ride time. On an unverified bike every reading is unverified: the card says so once. On a verified bike a
 * reading its profile doesn't verify is marked on its tile.
 */
@Composable
internal fun ReadingsCard(state: DashboardUiState) {
    val ride = state.ride?.takeIf { state.connected }
    val more = state.more?.takeIf { state.connected }
    // Marked on each tile only where the card doesn't already say it of them all.
    val moreUnverified = more != null && !more.verified && !state.unverified
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.readings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (state.unverified) UnverifiedMarker()
        }
        val rideTime = more?.rideTimeMinutes
        Tiles(
            listOf(
                Reading(
                    R.drawable.ic_route,
                    stringResource(R.string.odometer),
                    ride?.odometerKm?.let { stringResource(R.string.odometer_value, it) },
                ),
                Reading(
                    R.drawable.ic_trending_up,
                    stringResource(R.string.reading_max_speed),
                    ride?.maxSpeedKmh?.let { stringResource(R.string.reading_speed_value, it) },
                ),
                Reading(
                    R.drawable.ic_speed,
                    stringResource(R.string.reading_speed),
                    ride?.speedKmh?.let { stringResource(R.string.reading_speed_value, it) },
                ),
                Reading(
                    R.drawable.ic_device_thermostat,
                    stringResource(R.string.more_temperature),
                    more?.temperatureC?.let { stringResource(R.string.more_temperature_value, it) },
                    unverified = moreUnverified,
                ),
                Reading(
                    R.drawable.ic_battery_status_good,
                    stringResource(R.string.more_battery_health),
                    more?.batteryHealthPercent?.let { stringResource(R.string.more_battery_health_value, it) },
                    unverified = moreUnverified,
                ),
                Reading(
                    R.drawable.ic_bolt,
                    stringResource(R.string.more_voltage),
                    more?.voltage?.let { stringResource(R.string.more_voltage_value, it) },
                    unverified = moreUnverified,
                ),
                Reading(
                    R.drawable.ic_timer,
                    stringResource(R.string.more_ride_time),
                    rideTime?.let {
                        stringResource(R.string.more_ride_time_value, it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR)
                    },
                    rideTime?.let { spokenRideTime(it) },
                    unverified = moreUnverified,
                ),
            ),
        )
    }
}

/** Readings two to a row, each half the width; one to a row at large font sizes, so values never break. */
@Composable
private fun Tiles(readings: List<Reading>) {
    val perRow = if (LocalDensity.current.fontScale >= ONE_TILE_FROM_FONT_SCALE) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        for (row in readings.chunked(perRow)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                for (reading in row) ReadingTile(reading, Modifier.weight(1f))
                if (row.size < perRow) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReadingTile(
    reading: Reading,
    modifier: Modifier = Modifier,
) {
    val value = reading.spoken ?: reading.value ?: stringResource(R.string.value_missing_spoken)
    val spoken =
        listOfNotNull(
            stringResource(R.string.reading_spoken, reading.label, value),
            if (reading.unverified) stringResource(R.string.value_unverified) else null,
        ).joinToString(stringResource(R.string.list_separator_spoken))
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(
                painterResource(reading.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                reading.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ReadingText(reading.value)
        if (reading.unverified) UnverifiedMarker()
    }
}

/** "12 hours 22 minutes" rather than "12 h 22 min", which screen readers may spell out. */
@Composable
private fun spokenRideTime(minutes: Int): String {
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return stringResource(
        R.string.more_ride_time_spoken,
        pluralStringResource(R.plurals.more_ride_time_hours_spoken, hours, hours),
        pluralStringResource(R.plurals.more_ride_time_minutes_spoken, rest, rest),
    )
}

private const val MINUTES_PER_HOUR = 60

/** From this font scale on, a reading tile takes the whole row. */
private const val ONE_TILE_FROM_FONT_SCALE = 1.5f
