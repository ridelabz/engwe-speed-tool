package io.github.ridelabz.engwespeedtool.bike

import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.ridelabz.engwespeedtool.BuildConfig
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ble.AndroidBluetooth
import io.github.ridelabz.engwespeedtool.protocol.BikeProfile
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.reporting.SessionClock
import io.github.ridelabz.engwespeedtool.reporting.SessionLog
import io.github.ridelabz.engwespeedtool.reporting.SessionLogFiles
import io.github.ridelabz.engwespeedtool.reporting.SessionMeta
import io.github.ridelabz.engwespeedtool.reporting.StoredLog
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.BikeFinder
import io.github.ridelabz.engwespeedtool.session.BikeSession
import io.github.ridelabz.engwespeedtool.session.Consent
import io.github.ridelabz.engwespeedtool.session.FakeBike
import io.github.ridelabz.engwespeedtool.session.FakeBikeTransport
import io.github.ridelabz.engwespeedtool.session.RadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * One way to reach bikes: a finder, a session, and the Bluetooth state. The app never gets a link, a
 * transport or a way to hand a session another answer about the rider's consent: the session reads it
 * itself, from the store (or, in demo mode, from memory).
 */
class BikeConnection internal constructor(
    val finder: BikeFinder,
    val session: BikeSession,
    /**
     * The firmware caveat for this connection's bikes, for the caveat sheet to record: in the store, or in
     * demo mode's memory.
     */
    val firmwareCaveat: FirmwareCaveat,
    /** The acknowledgement that a bike is unverified, for the acknowledgement screen to record. */
    val unverifiedAcknowledgement: (BikeAddress) -> UnverifiedAcknowledgement,
    /** Whether the rider saw the firmware caveat for the current text, so the app can show it first. */
    val firmwareCaveatSeen: () -> Boolean,
    /** Whether the rider acknowledged a bike as unverified for the current text, so the app can ask first. */
    val unverifiedAcknowledged: (BikeAddress) -> Boolean,
    /** Whether the rider acknowledged a bike as unverified for some text: enough to go back to 25. */
    val unverifiedEverAcknowledged: (BikeAddress) -> Boolean,
    val radio: StateFlow<RadioState>,
    /** Reads the Bluetooth state and permissions again. */
    val refresh: () -> Unit,
    /** Asks Android to switch Bluetooth on; null where that doesn't apply (demo mode). */
    val enableIntent: (() -> Intent)?,
    val demo: Boolean,
    /** Made-up bikes of demo mode, empty otherwise. */
    val demoBikes: List<FakeBike> = emptyList(),
)

/**
 * The only place the app creates bike sessions and changes what it keeps. Each session asks the store
 * itself for the rider's consent (the current risk text accepted, the firmware caveat seen, an unverified
 * bike acknowledged) before every write; nothing outside this package can answer for it, and only the
 * risk-acceptance screen records the acceptance. Demo mode never touches Android's Bluetooth, and what
 * the rider agrees to there stays in memory.
 */
class BikeServices internal constructor(
    context: Context,
    private val store: AppStore,
    /** Makes the real connection; on a phone, from Android's Bluetooth ([get]). */
    private val makeReal: BikeServices.(CoroutineScope) -> BikeConnection,
) {
    private val app = context.applicationContext
    private val profiles: ProfileRegistry = ProfileRegistry.bundled()

    /** The bike profiles the app carries, for the compatibility table. */
    val bundledProfiles: List<BikeProfile> get() = profiles.profiles

    /** The risk text's version, from the protected legal resources. */
    val textVersion: Int = app.resources.getInteger(R.integer.legal_text_version)
    private val meta =
        SessionMeta(
            BuildConfig.VERSION_NAME,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            Build.MANUFACTURER,
            Build.MODEL,
        )

    /** This run's session log, sanitised as it is written; the oldest lines go once it is full. */
    val logText = BoundedText(LOG_LIMIT, keepFirstLine = true)
    private val clock = SessionClock.system()

    /** The date this run's session log records (the day the app started), which also names its exported files. */
    val logDate: LocalDate = clock.today()
    private val log =
        SessionLog(
            logText,
            object : SessionClock by clock {
                override fun today(): LocalDate = logDate
            },
            meta,
        )

    // The stored session logs, in the app's private files (excluded from backup and device transfer): this
    // run's, written now and then, and those of the last runs, kept across app updates. Nothing works
    // without storage: then none.
    private val logFiles = SessionLogFiles(File(app.filesDir, LOG_DIR))
    private val storedLog: StoredLog? =
        runCatching {
            // Earlier test builds kept their logs in a directory per install: those go.
            File(
                app.filesDir,
                LOG_DIR,
            ).listFiles().orEmpty().filter { it.isDirectory }.forEach { it.deleteRecursively() }
            logFiles.start()
        }.onFailure { log.error(AppError.LOG_STORAGE, it) }.getOrNull()

    // What the stored file of this run holds (BoundedText's version), and whether a failure was recorded.
    private var savedVersion = -1L
    private var failureRecorded = false

    /**
     * Writes this run's session log to its stored file (already sanitised; the last runs' stay), unless it
     * didn't change since the last time. Blocking file I/O: call it off the main thread, except on a crash.
     */
    fun saveLog() {
        val stored = storedLog ?: return
        synchronized(logFiles) {
            val (text, version) = logText.snapshot()
            if (version == savedVersion) return
            try {
                logFiles.save(stored, text)
                savedVersion = version
            } catch (e: Exception) {
                // Once per run: a missing stored log can then be explained. The class name only, as always.
                if (!failureRecorded) {
                    failureRecorded = true
                    log.error(AppError.LOG_STORAGE, e)
                }
            }
        }
    }

    /** The stored session logs, newest first, this run's written just before (all already sanitised). */
    fun storedLogs(): List<StoredLogInfo> {
        saveLog()
        return synchronized(logFiles) {
            runCatching {
                logFiles.list().map { StoredLogInfo(it.number, logFiles.size(it), current = it == storedLog) }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Deletes the stored session logs, the earlier runs' and this run's: this run's log starts again from its
     * first record (the date, the app and the phone), and is written again from then on.
     */
    fun deleteStoredLogs() {
        synchronized(logFiles) {
            runCatching { logFiles.deleteAll() }
            logText.restart()
            savedVersion = -1L
        }
    }

    /** This run's stored log number; null when nothing can be stored. */
    fun currentLogNumber(): Int? = storedLog?.number

    /** The content of stored log [number] (this run's as it is now); empty if it is gone. */
    fun readStoredLog(number: Int): String {
        if (number == storedLog?.number) return logText.toString()
        return synchronized(logFiles) { runCatching { logFiles.read(StoredLog(number)) }.getOrDefault("") }
    }

    /**
     * Records an uncaught exception in the session log (its class name only, as every error) and saves the
     * log before the app ends; then Android's own handling goes on.
     */
    internal fun captureCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                log.error(AppError.CRASH, e)
                saveLog()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** What the app keeps (see [Stored]): the saved bike and what goes with it, the acceptance, the settings. */
    val data: StateFlow<Stored> get() = store.data

    /** The phone facts the session log records, cleaned the same way, for the report preview. */
    val android: String get() = meta.androidRelease
    val phone: String get() = meta.phone

    /** True when the rider accepted the risk text the app shows now. */
    fun accepted(): Boolean =
        store.data.value.acceptance
            ?.version == textVersion

    /** True when the rider accepted some version of the risk text: enough to connect and go back to 25 km/h. */
    fun everAccepted(): Boolean = store.data.value.acceptance != null

    /** True when the rider saw the firmware caveat of the current text (once, at setup). */
    fun caveatSeen(): Boolean = store.data.value.caveatVersion == textVersion

    /** Where the caveat screen at setup records that the rider saw it, and nothing else. */
    val firmwareCaveat: FirmwareCaveat get() = storedCaveat

    /** The rider accepted the current risk text on [date]. Only the risk-acceptance screen calls this. */
    fun acceptRisk(date: LocalDate) = store.accept(textVersion, date)

    /** A handle for the risk-acceptance screen that records the acceptance and reaches nothing else. */
    val riskAcceptance: RiskAcceptance = RiskAcceptance(this)

    /** The rider's consent as the store keeps it, read by the real sessions before every write. */
    private val storedConsent =
        object : Consent {
            override fun riskAccepted(): Boolean = accepted()

            override fun riskEverAccepted(): Boolean = everAccepted()

            override fun firmwareCaveatSeen(): Boolean = caveatSeen()

            override fun unverifiedAcknowledged(bike: BikeAddress): Boolean =
                store.data.value.let { it.bike == bike && it.acknowledgedVersion == textVersion }

            override fun unverifiedEverAcknowledged(bike: BikeAddress): Boolean =
                store.data.value.let { it.bike == bike && it.acknowledgedVersion != null }
        }

    private val storedCaveat = FirmwareCaveat { store.seeCaveat(textVersion) }

    // Acknowledging a bike saves it as "My bike": the store keeps acknowledgements for the saved bike only.
    private val storedAcknowledgement = { bike: BikeAddress ->
        UnverifiedAcknowledgement {
            store.saveBike(bike)
            store.acknowledge(bike, textVersion)
        }
    }

    /** Saves [address] as "My bike". */
    fun saveBike(address: BikeAddress) = store.saveBike(address)

    /** "Keep this speed" for the saved bike: [on] or off, and the speed it keeps, if any. */
    fun keepSpeed(
        on: Boolean,
        kmh: Int?,
    ) {
        store.data.value.bike
            ?.let { store.keepSpeed(it, on, kmh) }
    }

    /** "Keep this assist level" for the saved bike: [on] or off, and the level it keeps, if any. */
    fun keepAssist(
        on: Boolean,
        level: Int?,
    ) {
        store.data.value.bike
            ?.let { store.keepAssist(it, on, level) }
    }

    /** Speeds whose warning the rider turned off, under the current text (a new text shows them all again). */
    fun warningsOff(): Set<Int> =
        store.data.value.let {
            if (it.warningsVersion ==
                textVersion
            ) {
                it.warningsOff
            } else {
                emptySet()
            }
        }

    /** The rider turned the warning for [kmh] off ("Don't show again"). */
    fun warningOff(kmh: Int) = store.warningOff(kmh, textVersion)

    /** Every speed warning shows again. */
    fun resetWarnings() = store.resetWarnings()

    fun keepScreenOn(on: Boolean) = store.keepScreenOn(on)

    /** On acknowledging the saved (unverified) bike, the rider offered to report how it worked. */
    fun reportResults() {
        store.data.value.bike
            ?.let { store.acknowledgeReport(it) }
    }

    /**
     * [bike] confirmed [kmh], a speed other than its default, at the rider's tap: if it is the saved bike and
     * the rider offered to report results, the next app open asks how it worked.
     */
    fun rideTest(
        bike: BikeAddress,
        kmh: Int,
    ) = store.rideTest(bike, kmh, currentLogNumber())

    fun rideTestDone(
        kmh: Int,
        log: Int?,
    ) = store.rideTestDone(kmh, log)

    fun rename(
        address: BikeAddress,
        name: String,
    ) = store.rename(address, name)

    fun forgetBike() = store.forgetBike()

    /** The rider refused the Bluetooth permission at Android's prompt, or ([refused] false) it was granted. */
    fun permissionRefused(refused: Boolean) = store.permissionRefused(refused)

    /** The number for the next file of a session log dated [date] (from 1 for each date). */
    fun nextLogNumber(date: LocalDate): Int = store.nextLogNumber(date)

    /** A file of a session log dated [date] took [number]. */
    fun logSaved(
        date: LocalDate,
        number: Int,
    ) = store.logSaved(date, number)

    private var realConnection: Pair<CoroutineScope, BikeConnection>? = null

    /**
     * Real Bluetooth: created on first use only, never in demo mode, and only once while its scope lasts, so
     * that two sessions never talk to the same bike (each has its own sending slot).
     */
    fun real(scope: CoroutineScope): BikeConnection =
        synchronized(this) {
            realConnection?.takeIf { it.first.isActive }?.second
                ?: makeReal(scope).also { realConnection = scope to it }
        }

    private fun android(
        bt: AndroidBluetooth,
        scope: CoroutineScope,
    ) = BikeConnection(
        finder = bt.finder(),
        session = bt.session(scope, profiles, log, storedConsent),
        firmwareCaveat = storedCaveat,
        unverifiedAcknowledgement = storedAcknowledgement,
        firmwareCaveatSeen = storedConsent::firmwareCaveatSeen,
        unverifiedAcknowledged = storedConsent::unverifiedAcknowledged,
        unverifiedEverAcknowledged = storedConsent::unverifiedEverAcknowledged,
        radio = bt.radio,
        refresh = bt::refresh,
        enableIntent = bt::enableIntent,
        demo = false,
    )

    /** A connection on simulated bikes that the app treats as real; for tests of the app. */
    internal fun simulated(
        transport: FakeBikeTransport,
        scope: CoroutineScope,
    ) = BikeConnection(
        finder = BikeFinder(transport),
        session = BikeSession(scope, transport, profiles, log, storedConsent),
        firmwareCaveat = storedCaveat,
        unverifiedAcknowledgement = storedAcknowledgement,
        firmwareCaveatSeen = storedConsent::firmwareCaveatSeen,
        unverifiedAcknowledged = storedConsent::unverifiedAcknowledged,
        unverifiedEverAcknowledged = storedConsent::unverifiedEverAcknowledged,
        radio = transport.radio,
        refresh = {},
        enableIntent = null,
        demo = false,
    )

    /** Two made-up bikes: the near one verified, the far one an untested model. Nothing leaves the phone. */
    fun demo(scope: CoroutineScope): BikeConnection {
        val near = FakeBike(scope, FakeBike.DEMO_ADDRESS, rssi = NEAR_RSSI, riding = true)
        val far =
            FakeBike(scope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL, rssi = FAR_RSSI, riding = true)
        val transport = FakeBikeTransport(near, far)
        val demoLog = SessionLog(BoundedText(LOG_LIMIT), SessionClock.system(), meta)
        // What the rider agrees to on simulated bikes says nothing about a real one: kept in memory only.
        val demoConsent = DemoConsent(::accepted)
        return BikeConnection(
            finder = BikeFinder(transport),
            session = BikeSession(scope, transport, profiles, demoLog, demoConsent),
            firmwareCaveat = FirmwareCaveat(demoConsent::seeCaveat),
            unverifiedAcknowledgement = { bike -> UnverifiedAcknowledgement { demoConsent.acknowledge(bike) } },
            firmwareCaveatSeen = demoConsent::firmwareCaveatSeen,
            unverifiedAcknowledged = demoConsent::unverifiedAcknowledged,
            unverifiedEverAcknowledged = demoConsent::unverifiedEverAcknowledged,
            radio = MutableStateFlow(RadioState.ON),
            refresh = {},
            enableIntent = null,
            demo = true,
            demoBikes = listOf(near, far),
        )
    }

    companion object {
        // A run's log is at most as large as a stored run may be.
        private const val LOG_LIMIT = SessionLogFiles.DEFAULT_RUN_BYTES.toInt()
        private const val LOG_DIR = "session-logs"
        private const val NEAR_RSSI = -55
        private const val FAR_RSSI = -85

        @Volatile
        private var instance: BikeServices? = null

        /** The app's one instance. */
        fun get(context: Context): BikeServices =
            instance ?: synchronized(this) {
                instance
                    ?: BikeServices(
                        context,
                        AppStore.get(context),
                    ) { scope -> android(AndroidBluetooth.get(context), scope) }
                        .also {
                            it.captureCrashes()
                            instance = it
                        }
            }
    }
}

/**
 * Records that the rider saw the firmware caveat (speed changes were tested on one model and firmware
 * only), and nothing more. Only the caveat sheet records it (a source check enforces this).
 */
class FirmwareCaveat internal constructor(
    private val record: () -> Unit,
) {
    fun recordFirmwareCaveat() = record()
}

/**
 * Records that the rider acknowledged one bike as unverified (and saves it as "My bike"), and nothing more.
 * Only the acknowledgement screen records it (a source check enforces this).
 */
class UnverifiedAcknowledgement internal constructor(
    private val record: () -> Unit,
) {
    fun recordUnverifiedAcknowledgement() = record()
}

/**
 * Demo mode's consent: the acceptance from the store, the rest in memory for this run of the demo. The
 * firmware caveat counts as seen: the simulated bikes have no firmware to be wary of, and the caveat shows
 * at setup only, never on a tap.
 */
private class DemoConsent(
    private val accepted: () -> Boolean,
) : Consent {
    @Volatile
    private var caveat = true
    private val acknowledged = ConcurrentHashMap.newKeySet<BikeAddress>()

    override fun riskAccepted(): Boolean = accepted()

    override fun firmwareCaveatSeen(): Boolean = caveat

    override fun unverifiedAcknowledged(bike: BikeAddress): Boolean = bike in acknowledged

    fun seeCaveat() {
        caveat = true
    }

    fun acknowledge(bike: BikeAddress) {
        acknowledged += bike
    }
}

/** Records the rider's acceptance of the current risk text, and nothing more. */
class RiskAcceptance internal constructor(
    private val services: BikeServices,
) {
    fun acceptRisk(date: LocalDate) = services.acceptRisk(date)
}

/**
 * Text that keeps only its last [limit] characters, dropping whole lines; with [keepFirstLine], its first
 * line stays too (a session log's start record, which says what the log is), within the same limit.
 * Thread-safe.
 */
class BoundedText(
    private val limit: Int,
    private val keepFirstLine: Boolean = false,
) : Appendable {
    private val first = StringBuilder()
    private var firstDone = !keepFirstLine
    private val text = StringBuilder()
    private var skipping = false

    // Grows with every change, so that a reader can tell whether the text changed.
    private var version = 0L

    /** Starts again: everything goes but the first line, if it is kept. */
    @Synchronized
    fun restart() {
        // The rest of a record cut by the restart goes too.
        skipping = skipping || (text.isNotEmpty() && text.last() != '\n')
        text.setLength(0)
        version++
    }

    /** The text and its version, read together. */
    @Synchronized
    fun snapshot(): Pair<String, Long> = toString() to version

    @Synchronized
    override fun append(csq: CharSequence?): Appendable = also { add(csq ?: "null") }

    @Synchronized
    override fun append(
        csq: CharSequence?,
        start: Int,
        end: Int,
    ): Appendable = also { add((csq ?: "null").subSequence(start, end)) }

    @Synchronized
    override fun append(c: Char): Appendable = also { add(c.toString()) }

    @Synchronized
    override fun toString(): String = if (first.isEmpty()) text.toString() else "$first$text"

    private fun add(s: CharSequence) {
        version++
        var chunk = s
        if (!firstDone) {
            val end = chunk.indexOf('\n')
            val part = if (end < 0) chunk.length else end + 1
            if (first.length + part > limit) {
                // A first line longer than the limit isn't kept: it goes like any record that doesn't fit.
                first.setLength(0)
                firstDone = true
                if (end < 0) {
                    skipping = true
                    return
                }
            } else {
                first.append(chunk, 0, part)
                if (end < 0) return
                firstDone = true
            }
            chunk = chunk.subSequence(part, chunk.length)
        }
        val limit = (limit - first.length).coerceAtLeast(0)
        if (skipping) {
            // The rest of a record whose start was dropped: it goes too.
            val end = chunk.indexOf('\n')
            if (end < 0) return
            skipping = false
            chunk = chunk.subSequence(end + 1, chunk.length)
        }
        text.append(chunk)
        if (text.length > limit) {
            // Whole records only: without a line end in the part that stays, nothing stays.
            val cut = text.indexOf("\n", text.length - limit)
            if (cut < 0) {
                text.setLength(0)
                skipping = true
            } else {
                text.delete(0, cut + 1)
            }
        }
    }
}

/** One stored session log: its number, size in bytes, and whether it is this run's. */
data class StoredLogInfo(
    val number: Int,
    val size: Long,
    val current: Boolean,
)

/** Where the app itself records an error in the session log. */
private enum class AppError { CRASH, LOG_STORAGE }
