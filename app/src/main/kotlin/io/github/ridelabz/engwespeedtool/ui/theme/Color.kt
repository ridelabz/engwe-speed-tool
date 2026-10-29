package io.github.ridelabz.engwespeedtool.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

// A fixed palette: teal accent on neutral surfaces. There is no wallpaper-based dynamic colour, so that
// amber, green and red keep their meaning and contrast on every phone. The unit tests check the
// text/background pairs for WCAG AA contrast.

val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF006A67),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF9DF2EC),
        onPrimaryContainer = Color(0xFF00201F),
        inversePrimary = Color(0xFF80D5D0),
        secondary = Color(0xFF4A6361),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE8E5),
        onSecondaryContainer = Color(0xFF051F1E),
        tertiary = Color(0xFF4A607C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD2E4FF),
        onTertiaryContainer = Color(0xFF031C35),
        background = Color(0xFFF4FBF9),
        onBackground = Color(0xFF161D1C),
        surface = Color(0xFFF4FBF9),
        onSurface = Color(0xFF161D1C),
        surfaceVariant = Color(0xFFDAE5E2),
        onSurfaceVariant = Color(0xFF3F4947),
        surfaceTint = Color(0xFF006A67),
        inverseSurface = Color(0xFF2B3231),
        inverseOnSurface = Color(0xFFECF2F0),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF6F7977),
        outlineVariant = Color(0xFFBEC9C6),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFF4FBF9),
        surfaceContainer = Color(0xFFE8EFED),
        surfaceContainerHigh = Color(0xFFE3EAE8),
        surfaceContainerHighest = Color(0xFFDDE4E2),
        surfaceContainerLow = Color(0xFFEEF5F3),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFD5DBD9),
    )

val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF80D5D0),
        onPrimary = Color(0xFF003735),
        primaryContainer = Color(0xFF00504D),
        onPrimaryContainer = Color(0xFF9DF2EC),
        inversePrimary = Color(0xFF006A67),
        secondary = Color(0xFFB0CCC9),
        onSecondary = Color(0xFF1C3533),
        secondaryContainer = Color(0xFF324B49),
        onSecondaryContainer = Color(0xFFCCE8E5),
        tertiary = Color(0xFFB2C8E8),
        onTertiary = Color(0xFF1B324B),
        tertiaryContainer = Color(0xFF334863),
        onTertiaryContainer = Color(0xFFD2E4FF),
        background = Color(0xFF0E1514),
        onBackground = Color(0xFFDDE4E2),
        surface = Color(0xFF0E1514),
        onSurface = Color(0xFFDDE4E2),
        surfaceVariant = Color(0xFF3F4947),
        onSurfaceVariant = Color(0xFFBEC9C6),
        surfaceTint = Color(0xFF80D5D0),
        inverseSurface = Color(0xFFDDE4E2),
        inverseOnSurface = Color(0xFF2B3231),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8F9996),
        outlineVariant = Color(0xFF3F4947),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF343B3A),
        surfaceContainer = Color(0xFF1A2120),
        surfaceContainerHigh = Color(0xFF252B2A),
        surfaceContainerHighest = Color(0xFF2F3635),
        surfaceContainerLow = Color(0xFF161D1C),
        surfaceContainerLowest = Color(0xFF090F0F),
        surfaceDim = Color(0xFF0E1514),
    )

/**
 * The pinned top bar's background: a container tone of the brand colour, set apart from the page (the
 * scheme's surface), in light and dark.
 */
val ColorScheme.topBar: Color get() = primaryContainer

/** The top bar's title and icons, on [topBar]. */
val ColorScheme.onTopBar: Color get() = onPrimaryContainer

/**
 * Colours with a fixed meaning that Material's scheme has no role for. Each comes with the colours to
 * draw on it; colour never carries a meaning on its own, so every use also has an icon or text.
 *
 * - caution: above the 25 km/h legal limit, and unverified bikes or values.
 * - confirmed: the bike confirmed a setting.
 */
@Immutable
data class StatusColors(
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val confirmed: Color,
    val onConfirmed: Color,
    val confirmedContainer: Color,
    val onConfirmedContainer: Color,
)

val LightStatusColors: StatusColors =
    StatusColors(
        caution = Color(0xFF855300),
        onCaution = Color(0xFFFFFFFF),
        cautionContainer = Color(0xFFFFDDB7),
        onCautionContainer = Color(0xFF2A1700),
        confirmed = Color(0xFF186C31),
        onConfirmed = Color(0xFFFFFFFF),
        confirmedContainer = Color(0xFFA3F5A8),
        onConfirmedContainer = Color(0xFF002109),
    )

val DarkStatusColors: StatusColors =
    StatusColors(
        caution = Color(0xFFFFB95D),
        onCaution = Color(0xFF462A00),
        cautionContainer = Color(0xFF653E00),
        onCautionContainer = Color(0xFFFFDDB7),
        confirmed = Color(0xFF88D98D),
        onConfirmed = Color(0xFF003913),
        confirmedContainer = Color(0xFF00531F),
        onConfirmedContainer = Color(0xFFA3F5A8),
    )

/**
 * Colours of the dashboard's gauges. The battery's fill by charge (good from 50 %, low below 20 %) and the
 * assist bar's segments by level (blue for 1 to magenta for the highest; [assistNone] for level 0), each with
 * the colour of a number drawn on it. The percentage and the level's number are always shown as text too.
 */
@Immutable
data class GaugeColors(
    val batteryGood: Color,
    val batteryMid: Color,
    val batteryLow: Color,
    /** Levels 1 to 5, in order. */
    val assist: List<Color>,
    val onAssist: Color,
    val assistNone: Color,
    val onAssistNone: Color,
) {
    /** The colour of assist [level] (1 or more) when the bike offers [levels] of them: blue to magenta. */
    fun assistLevel(
        level: Int,
        levels: Int,
    ): Color {
        if (level <= 0) return assistNone
        if (levels <= 1) return assist.last()
        // Spread over the palette, so that level 1 is always blue and the highest always magenta.
        val index = ((level - 1) * assist.lastIndex.toFloat() / (levels - 1)).roundToInt()
        return assist[index.coerceIn(0, assist.lastIndex)]
    }
}

val LightGaugeColors: GaugeColors =
    GaugeColors(
        batteryGood = Color(0xFF1E7A34),
        batteryMid = Color(0xFF8C6A00),
        batteryLow = Color(0xFFBA1A1A),
        assist = listOf(Color(0xFF1F5FAD), Color(0xFF3F4FB8), Color(0xFF5E43B5), Color(0xFF7E39A8), Color(0xFF9B2F8F)),
        onAssist = Color(0xFFFFFFFF),
        assistNone = Color(0xFF5D6664),
        onAssistNone = Color(0xFFFFFFFF),
    )

val DarkGaugeColors: GaugeColors =
    GaugeColors(
        batteryGood = Color(0xFF7EDB86),
        batteryMid = Color(0xFFF2C94C),
        batteryLow = Color(0xFFFF8A80),
        assist = listOf(Color(0xFFA6C8FF), Color(0xFFBCC2FF), Color(0xFFD3B9FF), Color(0xFFEBB0F4), Color(0xFFFFAEDC)),
        onAssist = Color(0xFF141522),
        assistNone = Color(0xFFAEB8B5),
        onAssistNone = Color(0xFF141522),
    )
