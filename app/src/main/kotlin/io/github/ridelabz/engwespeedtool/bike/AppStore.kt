package io.github.ridelabz.engwespeedtool.bike

import android.content.Context
import android.content.SharedPreferences
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.ui.legal.Acceptance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** The longest name a rider can give the bike. */
const val BIKE_NAME_MAX: Int = 30

/**
 * What the app keeps between runs. The bike's address is only used to reconnect: it is never shown,
 * logged, exported or put in a report. [toString] shows neither the address nor the name the rider typed.
 * It also keeps the date and number of the last saved session log ([AppStore.nextLogNumber]).
 */
data class Stored(
    /** The saved bike, "My bike". */
    val bike: BikeAddress?,
    /** The rider's name for the saved bike, if they gave one. */
    val bikeName: String?,
    /** The risk acceptance: the text version and the date. */
    val acceptance: Acceptance?,
    /** The text version of the unverified-bike acknowledgement given for the saved bike. */
    val acknowledgedVersion: Int?,
    /**
     * The rider refused the Bluetooth permission at Android's prompt (a refusal Android would still ask
     * about), and it hasn't been granted since. Android explains a request only after such a refusal; once it
     * stops explaining, a remembered refusal means it won't ask again. Not set by a dismissed prompt.
     */
    val permissionRefused: Boolean = false,
    /** The text version of the firmware caveat the rider saw before the first speed change. */
    val caveatVersion: Int? = null,
    /** "Keep this speed" is on for the saved bike. */
    val keepSpeed: Boolean = false,
    /** The speed "keep this speed" sets again for the saved bike, in km/h: the last one confirmed while it was on. */
    val keptKmh: Int? = null,
    /** "Keep this assist level" is on for the saved bike. */
    val keepAssist: Boolean = false,
    /** The level "keep this assist level" sets again for the saved bike: the last one chosen in the app while on. */
    val keptAssistLevel: Int? = null,
    /** Speeds (km/h) whose warning the rider turned off, for text version [warningsVersion]. */
    val warningsOff: Set<Int> = emptySet(),
    val warningsVersion: Int? = null,
    /** On acknowledging the saved (unverified) bike, the rider offered to report how it worked. */
    val reportResults: Boolean = false,
    /** The screen stays on while the dashboard is open. */
    val keepScreenOn: Boolean = true,
    /**
     * A speed (km/h) the saved bike confirmed after the rider offered to report results: the next app open
     * asks how it worked. With the number of the stored log of that run, if there was one.
     */
    val rideTestKmh: Int? = null,
    val rideTestLog: Int? = null,
) {
    override fun toString(): String =
        "Stored(bike=${bike != null}, named=${bikeName != null}, acceptance=$acceptance, " +
            "acknowledged=$acknowledgedVersion, permissionRefused=$permissionRefused, caveat=$caveatVersion, " +
            "keep=$keepSpeed/$keptKmh, warningsOff=$warningsOff/$warningsVersion, report=$reportResults, " +
            "keepAssist=$keepAssist/$keptAssistLevel, " +
            "screenOn=$keepScreenOn, rideTest=$rideTestKmh/$rideTestLog)"

    companion object {
        val EMPTY = Stored(null, null, null, null)
    }
}

/**
 * The app's private storage: a private preferences file in the app's own data, which the data-extraction
 * rules exclude from cloud backup and device transfer. It is the only place the bike's address is kept.
 * Unreadable values count as missing: an unreadable acceptance asks the rider again, an unreadable bike
 * is found again.
 */
class AppStore(
    private val prefs: SharedPreferences,
) {
    init {
        // Earlier versions kept a tag read from the bike's advertisement, whether the dashboard showed the
        // readings that weren't verified then, and whether the first Turn off had been explained; nothing uses
        // any of them any more.
        val old = OLD_KEYS.filter(prefs::contains)
        if (old.isNotEmpty()) {
            val editor = prefs.edit()
            for (key in old) editor.remove(key)
            editor.apply()
        }
    }

    private val state = MutableStateFlow(read())

    val data: StateFlow<Stored> = state.asStateFlow()

    /** The rider accepted the risk statements of text [version] on [date]. */
    fun accept(
        version: Int,
        date: LocalDate,
    ) = change {
        putInt(ACCEPTED_VERSION, version)
        putLong(ACCEPTED_ON, date.toEpochDay())
    }

    /** Saves [address] as "My bike". Another bike starts without what was kept for the last one. */
    fun saveBike(address: BikeAddress) =
        change {
            if (prefs.string(BIKE) != address.value) removeBikeValues()
            putString(BIKE, address.value)
        }

    /** Names the saved bike, if it is still [address]; blank goes back to "My bike". */
    fun rename(
        address: BikeAddress,
        name: String,
    ) = change {
        if (prefs.string(BIKE) != address.value) return@change
        val trimmed = name.trim().take(BIKE_NAME_MAX)
        if (trimmed.isEmpty()) remove(BIKE_NAME) else putString(BIKE_NAME, trimmed)
    }

    /** The rider acknowledged that [address], still the saved bike, is unverified, for text [version]. */
    fun acknowledge(
        address: BikeAddress,
        version: Int,
    ) = change {
        if (prefs.string(BIKE) == address.value) putInt(ACKNOWLEDGED_VERSION, version)
    }

    /** The rider offered to report how [address], still the saved bike, worked. */
    fun acknowledgeReport(address: BikeAddress) =
        change {
            if (prefs.string(BIKE) == address.value) putBoolean(REPORT_RESULTS, true)
        }

    /**
     * [address], still the saved bike, confirmed [kmh] after the rider offered to report results; [log] is the
     * stored log of that run. The next app open asks how it worked, unless it asked about [kmh] before.
     */
    fun rideTest(
        address: BikeAddress,
        kmh: Int,
        log: Int?,
    ) = change {
        if (prefs.string(BIKE) != address.value || !prefs.bool(REPORT_RESULTS)) return@change
        if (kmh in prefs.kmhSet(RIDE_TESTS_DONE)) return@change
        putInt(RIDE_TEST_KMH, kmh)
        if (log != null) putInt(RIDE_TEST_LOG, log) else remove(RIDE_TEST_LOG)
    }

    /**
     * The ride test about [kmh] (with [log]) was answered or skipped: [kmh] isn't asked about again for this
     * bike. A newer test, about another speed or run, stays.
     */
    fun rideTestDone(
        kmh: Int,
        log: Int?,
    ) = change {
        if (prefs.string(BIKE) == null) return@change
        putStringSet(RIDE_TESTS_DONE, (prefs.kmhSet(RIDE_TESTS_DONE) + kmh).map { it.toString() }.toSet())
        if (prefs.int(RIDE_TEST_KMH) == kmh && prefs.int(RIDE_TEST_LOG) == log) {
            remove(RIDE_TEST_KMH)
            remove(RIDE_TEST_LOG)
        }
    }

    /** "Keep this speed" for [address], still the saved bike: [on] or off, and the speed it sets again, if any. */
    fun keepSpeed(
        address: BikeAddress,
        on: Boolean,
        kmh: Int?,
    ) = change {
        if (prefs.string(BIKE) != address.value) return@change
        putBoolean(KEEP_SPEED, on)
        if (kmh != null) putInt(KEPT_KMH, kmh) else remove(KEPT_KMH)
    }

    /**
     * "Keep this assist level" for [address], still the saved bike: [on] or off, and the level it sets again,
     * if any.
     */
    fun keepAssist(
        address: BikeAddress,
        on: Boolean,
        level: Int?,
    ) = change {
        if (prefs.string(BIKE) != address.value) return@change
        putBoolean(KEEP_ASSIST, on)
        if (level != null) putInt(KEPT_ASSIST, level) else remove(KEPT_ASSIST)
    }

    /** The rider turned the warning for [kmh] off ("Don't show again"), under text [version]. */
    fun warningOff(
        kmh: Int,
        version: Int,
    ) = change {
        val current = if (prefs.int(WARNINGS_VERSION) == version) prefs.kmhSet(WARNINGS_OFF) else emptySet()
        putStringSet(WARNINGS_OFF, (current + kmh).map { it.toString() }.toSet())
        putInt(WARNINGS_VERSION, version)
    }

    /** Every speed warning shows again. */
    fun resetWarnings() =
        change {
            remove(WARNINGS_OFF)
            remove(WARNINGS_VERSION)
        }

    /** Display settings, which apply whatever the bike: forgetting it keeps them. */
    fun keepScreenOn(on: Boolean) = change { putBoolean(KEEP_SCREEN_ON, on) }

    /**
     * The number for the next file of a session log dated [date]: one more than the last one for that date,
     * else 1. Only the date and a count are kept, never a time of day.
     */
    fun nextLogNumber(date: LocalDate): Int {
        val last = if (prefs.long(LOG_SAVED_ON) == date.toEpochDay()) prefs.int(LOG_SAVED_NUMBER) else null
        return if (last != null && last in 1 until Int.MAX_VALUE) last + 1 else 1
    }

    /** A file of a session log dated [date] took [number]. */
    fun logSaved(
        date: LocalDate,
        number: Int,
    ) = change {
        putLong(LOG_SAVED_ON, date.toEpochDay())
        putInt(LOG_SAVED_NUMBER, number)
    }

    /** The rider refused the Bluetooth permission at the prompt ([refused]), or it was granted (not [refused]). */
    fun permissionRefused(refused: Boolean) =
        change {
            if (refused) putBoolean(PERMISSION_REFUSED, true) else remove(PERMISSION_REFUSED)
        }

    /**
     * The rider saw the firmware caveat of text [version]: speed changes were tested on one model and
     * firmware only. It applies to every bike, so forgetting a bike keeps it.
     */
    fun seeCaveat(version: Int) = change { putInt(CAVEAT_VERSION, version) }

    /**
     * Forgets the bike and what was kept for it (name, acknowledgement, "keep this speed", "keep this assist
     * level", the offer to report). The risk acceptance, a permission refusal, the firmware caveat, the speed
     * warnings, the display settings and the log count stay.
     */
    fun forgetBike() =
        change {
            remove(BIKE)
            removeBikeValues()
        }

    private fun SharedPreferences.Editor.removeBikeValues() {
        remove(BIKE_NAME)
        remove(ACKNOWLEDGED_VERSION)
        remove(KEEP_SPEED)
        remove(KEPT_KMH)
        remove(KEEP_ASSIST)
        remove(KEPT_ASSIST)
        remove(REPORT_RESULTS)
        remove(RIDE_TEST_KMH)
        remove(RIDE_TEST_LOG)
        remove(RIDE_TESTS_DONE)
    }

    /** One change at a time: written in memory at once (and to disk soon after), then published. */
    private fun change(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(this) {
            prefs.edit().apply(block).apply()
            state.value = read()
        }
    }

    private fun read(): Stored {
        val bike = prefs.string(BIKE)?.let { runCatching { BikeAddress(it) }.getOrNull() }
        val version = prefs.int(ACCEPTED_VERSION)
        val date = prefs.long(ACCEPTED_ON)?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
        return Stored(
            bike = bike,
            bikeName = if (bike != null) prefs.string(BIKE_NAME)?.take(BIKE_NAME_MAX) else null,
            acceptance = if (version != null && date != null) Acceptance(date, version) else null,
            acknowledgedVersion = if (bike != null) prefs.int(ACKNOWLEDGED_VERSION) else null,
            permissionRefused = runCatching { prefs.getBoolean(PERMISSION_REFUSED, false) }.getOrDefault(false),
            caveatVersion = prefs.int(CAVEAT_VERSION),
            keepSpeed = bike != null && prefs.bool(KEEP_SPEED),
            keptKmh = if (bike != null) prefs.int(KEPT_KMH) else null,
            keepAssist = bike != null && prefs.bool(KEEP_ASSIST),
            keptAssistLevel = if (bike != null) prefs.int(KEPT_ASSIST) else null,
            warningsOff = prefs.kmhSet(WARNINGS_OFF),
            warningsVersion = prefs.int(WARNINGS_VERSION),
            reportResults = bike != null && prefs.bool(REPORT_RESULTS),
            keepScreenOn = prefs.bool(KEEP_SCREEN_ON, default = true),
            rideTestKmh = if (bike != null && prefs.bool(REPORT_RESULTS)) prefs.int(RIDE_TEST_KMH) else null,
            rideTestLog = if (bike != null && prefs.bool(REPORT_RESULTS)) prefs.int(RIDE_TEST_LOG) else null,
        )
    }

    private fun SharedPreferences.bool(
        key: String,
        default: Boolean = false,
    ): Boolean = runCatching { getBoolean(key, default) }.getOrDefault(default)

    private fun SharedPreferences.kmhSet(key: String): Set<Int> =
        runCatching { getStringSet(key, null) }
            .getOrNull()
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    // A value of another type (an old or damaged file) counts as missing.
    private fun SharedPreferences.string(key: String): String? = runCatching { getString(key, null) }.getOrNull()

    private fun SharedPreferences.int(key: String): Int? =
        if (contains(key)) {
            runCatching {
                getInt(key, 0)
            }.getOrNull()
        } else {
            null
        }

    private fun SharedPreferences.long(key: String): Long? =
        if (contains(key)) {
            runCatching {
                getLong(key, 0)
            }.getOrNull()
        } else {
            null
        }

    companion object {
        private const val FILE = "app_store"
        private const val BIKE = "bike"
        private const val BIKE_NAME = "bike_name"
        private const val ACCEPTED_VERSION = "accepted_version"
        private const val ACCEPTED_ON = "accepted_on"
        private const val ACKNOWLEDGED_VERSION = "acknowledged_version"
        private val OLD_KEYS = listOf("firmware_tag", "show_more_data", "lock_explained")
        private const val LOG_SAVED_ON = "log_saved_on"
        private const val LOG_SAVED_NUMBER = "log_saved_number"
        private const val PERMISSION_REFUSED = "bluetooth_permission_refused"
        private const val CAVEAT_VERSION = "firmware_caveat_version"
        private const val KEEP_SPEED = "keep_speed"
        private const val KEPT_KMH = "kept_kmh"
        private const val KEEP_ASSIST = "keep_assist"
        private const val KEPT_ASSIST = "kept_assist_level"
        private const val WARNINGS_OFF = "warnings_off"
        private const val WARNINGS_VERSION = "warnings_version"
        private const val REPORT_RESULTS = "report_results"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val RIDE_TEST_KMH = "ride_test_kmh"
        private const val RIDE_TEST_LOG = "ride_test_log"
        private const val RIDE_TESTS_DONE = "ride_tests_done"

        @Volatile
        private var instance: AppStore? = null

        /** The app's one store, in its private data (never external storage). */
        fun get(context: Context): AppStore =
            instance ?: synchronized(this) {
                instance ?: AppStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
                    .also { instance = it }
            }
    }
}
