package io.github.ridelabz.engwespeedtool.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ridelabz.engwespeedtool.bike.BikeConnection
import io.github.ridelabz.engwespeedtool.bike.BikeServices
import io.github.ridelabz.engwespeedtool.bike.FirmwareCaveat
import io.github.ridelabz.engwespeedtool.bike.RiskAcceptance
import io.github.ridelabz.engwespeedtool.bike.Stored
import io.github.ridelabz.engwespeedtool.bike.StoredLogInfo
import io.github.ridelabz.engwespeedtool.bike.UnverifiedAcknowledgement
import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeProfile
import io.github.ridelabz.engwespeedtool.protocol.Classification
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.StatusField
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.reporting.IssueField
import io.github.ridelabz.engwespeedtool.reporting.SentCommand
import io.github.ridelabz.engwespeedtool.reporting.SessionSummary
import io.github.ridelabz.engwespeedtool.session.Advertisement
import io.github.ridelabz.engwespeedtool.session.AssistState
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.BikeSession
import io.github.ridelabz.engwespeedtool.session.CheckStep
import io.github.ridelabz.engwespeedtool.session.CommandOutcome
import io.github.ridelabz.engwespeedtool.session.CompatibilityCheck
import io.github.ridelabz.engwespeedtool.session.CompatibilityProgress
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import io.github.ridelabz.engwespeedtool.session.LimitState
import io.github.ridelabz.engwespeedtool.session.LinkException
import io.github.ridelabz.engwespeedtool.session.LinkFailure
import io.github.ridelabz.engwespeedtool.session.RadioState
import io.github.ridelabz.engwespeedtool.session.Refusal
import io.github.ridelabz.engwespeedtool.session.SessionState
import io.github.ridelabz.engwespeedtool.session.SessionStatus
import io.github.ridelabz.engwespeedtool.session.StopReason
import io.github.ridelabz.engwespeedtool.ui.dashboard.AssistUiState
import io.github.ridelabz.engwespeedtool.ui.dashboard.Connection
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardUiState
import io.github.ridelabz.engwespeedtool.ui.dashboard.Lights
import io.github.ridelabz.engwespeedtool.ui.dashboard.LightsUiState
import io.github.ridelabz.engwespeedtool.ui.dashboard.NotSent
import io.github.ridelabz.engwespeedtool.ui.dashboard.PowerNotSent
import io.github.ridelabz.engwespeedtool.ui.dashboard.PowerUiState
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedLimitStatus
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedLimitUiState
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedNotSent
import io.github.ridelabz.engwespeedtool.ui.dashboard.WarningPurpose
import io.github.ridelabz.engwespeedtool.ui.legal.Acceptance
import io.github.ridelabz.engwespeedtool.ui.nav.Navigator
import io.github.ridelabz.engwespeedtool.ui.nav.Route
import io.github.ridelabz.engwespeedtool.ui.onboarding.CheckResult
import io.github.ridelabz.engwespeedtool.ui.onboarding.ConnectCheckUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.FindBikeUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.FoundBike
import io.github.ridelabz.engwespeedtool.ui.onboarding.PermissionUiState
import io.github.ridelabz.engwespeedtool.ui.report.IotVersion
import io.github.ridelabz.engwespeedtool.ui.report.LogFile
import io.github.ridelabz.engwespeedtool.ui.report.LogSaved
import io.github.ridelabz.engwespeedtool.ui.report.ReportAnswers
import io.github.ridelabz.engwespeedtool.ui.report.ReportLabels
import io.github.ridelabz.engwespeedtool.ui.report.ReportPreview
import io.github.ridelabz.engwespeedtool.ui.report.ReportRequest
import io.github.ridelabz.engwespeedtool.ui.report.ReportSending
import io.github.ridelabz.engwespeedtool.ui.report.ReportSent
import io.github.ridelabz.engwespeedtool.ui.report.ReportType
import io.github.ridelabz.engwespeedtool.ui.report.RideTest
import io.github.ridelabz.engwespeedtool.ui.report.SharedItem
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.github.ridelabz.engwespeedtool.session.RideData as SessionRide

/**
 * The app's state, driven by the real session (or the simulated bikes in demo mode). It shows what the
 * session reports, asks it to connect, and passes the rider's speed and light taps on to it, one command
 * per tap; the session decides, with the rider's consent it reads itself, whether to send. The controller
 * records no consent: only the risk-acceptance screen, the acknowledgement screen and the caveat sheet do.
 */
@Stable
class AppController(
    private val scope: CoroutineScope,
    private val services: BikeServices,
    /** How every found bike is named on screen: the rest of an advertised name comes from its address. */
    private val bikeLabel: String,
    private val nothingFoundAfter: Duration = 20.seconds,
    /** The time of day a confirmation is shown with. */
    private val clock: () -> Instant = Instant::now,
    /** Where the session log is written to storage, off the main thread. */
    private val storage: CoroutineDispatcher = Dispatchers.IO,
    /** The phone's Downloads, where saved logs go; null: none, so the file picker opens instead. */
    val downloads: LogDownloads? = null,
) {
    // For the screens; decisions read the store itself, which is always current.
    private var stored: Stored by mutableStateOf(services.data.value)
    private val saved: Stored get() = services.data.value

    var findBike: FindBikeUiState by mutableStateOf(FindBikeUiState.Searching(emptyList()))
        private set
    var check: ConnectCheckUiState by mutableStateOf(ConnectCheckUiState.Starting)
        private set
    private var live: DashboardUiState by mutableStateOf(emptyDashboard())

    /** The dashboard: the session's state, where "Allow" leads, and the rider's last speed and light taps. */
    val dashboard: DashboardUiState
        get() =
            live.copy(
                permissionInSettings = permission == PermissionUiState.DENIED_PERMANENTLY,
                lights =
                    live.lights.copy(
                        sending = lightsSwitching != null,
                        switchingOn = lightsSwitching,
                        notConfirmed = lightsNotConfirmed,
                        notSent = lightsNotSent,
                    ),
                speedLimit = live.speedLimit.copy(keepSpeed = keepShown, notSent = notSent),
                assist = live.assist.copy(notSent = assistNotSent, keep = keepAssistShown),
                power =
                    live.power?.copy(
                        switching = powerSwitching,
                        notConfirmed = powerNotConfirmed,
                        notSent = powerNotSent,
                        notSentOn = powerNotSentOn,
                        lastOff = lastOff,
                    ),
            )

    /** A speed warning to show before sending ([PendingWarning.kmh]); null when none is open. */
    var warning: PendingWarning? by mutableStateOf(null)
        private set
    private var warningTap: SpeedTap? = null

    // A tap the session refused for want of an acknowledgement: sent once the rider gave it.
    private var afterAcknowledgement: SpeedTap? = null

    /** What the rider must agree to first, as a screen to open; the app clears it once it opened it. */
    var consentNeeded: ConsentNeeded? by mutableStateOf(null)
        private set

    private var notSent: SpeedNotSent? by mutableStateOf(null)
    private var lightsNotSent: NotSent? by mutableStateOf(null)
    private var assistNotSent: NotSent? by mutableStateOf(null)

    // The lights state being switched to, while a command is on its way.
    private var lightsSwitching: Boolean? by mutableStateOf(null)
    private var lightsNotConfirmed by mutableStateOf(false)

    // Turning the bike on (true) or off, while the unlock or lock is on its way; how the last one ended.
    private var powerSwitching: Boolean? by mutableStateOf(null)
    private var powerNotConfirmed: Boolean? by mutableStateOf(null)
    private var powerNotSent: PowerNotSent? by mutableStateOf(null)
    private var powerNotSentOn by mutableStateOf(false)

    // The bike's last state this run reported (true off, false on), for the button while it reports neither.
    private var lastOff: Boolean? by mutableStateOf(null)

    // "Keep this speed": stored for the saved bike, in memory in demo mode.
    private var demoKeep by mutableStateOf(false)
    private var demoKept: Int? = null
    private val demoWarningsOff = mutableSetOf<Int>()

    // "Keep this assist level", the same way.
    private var demoKeepAssist by mutableStateOf(false)
    private var demoKeptAssist: Int? = null

    // For the screens (follows the store's updates) and for decisions (the store itself, always current).
    private val keepShown: Boolean get() = if (demo) demoKeep else stored.keepSpeed
    private val keepOn: Boolean get() = if (demo) demoKeep else saved.keepSpeed
    private val keepAssistShown: Boolean get() = if (demo) demoKeepAssist else stored.keepAssist
    private val keepAssistOn: Boolean get() = if (demo) demoKeepAssist else saved.keepAssist

    // The limit as the session reports it, with when the app saw it confirmed, and whether the app set a
    // speed on this connection (a restart then isn't the bike's default "with nothing set").
    private var limitSeen: LimitState = LimitState.Unknown
    private var confirmedAt: Instant = Instant.EPOCH
    private var applied = false
    var report: ReportRequest? by mutableStateOf(null)
        private set
    var permission: PermissionUiState by mutableStateOf(PermissionUiState.NOT_ASKED)
        private set

    /** True while the simulated bikes stand in for a real one. */
    var demo: Boolean by mutableStateOf(false)
        private set

    val acceptance: Acceptance? get() = stored.acceptance

    /** Where the risk-acceptance screen records the rider's acceptance; nothing else uses it. */
    val riskAcceptance: RiskAcceptance get() = services.riskAcceptance

    val settings: SettingsUiState
        get() =
            SettingsUiState(
                bikeName = stored.bikeName,
                keepSpeed = keepShown,
                activeKmh = (dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh,
                keepScreenOn = stored.keepScreenOn,
                logSessions = LOG_SESSIONS,
                restartsTrusted = live.speedLimit.restartsTrusted,
                keptKmh = (if (demo) demoKept else stored.keptKmh).takeIf { keepShown },
                keepSpeedSwitchable = !sendingSpeed || keepShown,
            )

    // Real Bluetooth is created on first use only, so demo mode never touches it.
    private var realConnection: BikeConnection? = null
    private var demoConnection: BikeConnection? = null
    private var observer: Job? = null
    private var scanJob: Job? = null

    // Bikes heard in the current search, with a number each keeps while it is heard.
    private var found: Map<Int, Advertisement> = emptyMap()
    private val keys = mutableMapOf<BikeAddress, Int>()

    /** The bike picked on the find screen; its check is pending until the rider continues or goes back. */
    private var checking: Advertisement? = null
    private var checkPending = false

    // The session numbers its runs. States numbered above [since], the stop before this controller's last
    // start, belong to that start (or to a stop after it); earlier ones (a Ready replayed, an Idle) don't.
    // Taken before the start, because a session on the main thread may report before start() returns.
    private var since = NO_RUN

    // Once the check screen shows a result, it stays: later reconnect states don't replace it.
    private var settled = false

    // This run's check started by itself when the app came back to the front; the check screen says so.
    private var checkResumed = false
    private var classification: Classification? = null

    // The bike the current run is for.
    private var runBike: BikeAddress? = null

    // This run didn't find or reach the bike yet; it stays so through the next searches until one connects.
    private var unreachable = false

    /** What to resume when the app comes back to the front. */
    private enum class Resume { NOTHING, CHECK, DEMO }

    private var resume = Resume.NOTHING

    init {
        scope.launch { services.data.collect { stored = it } }
        // The session log is stored now and then (only if it changed), and when the app goes behind another one.
        scope.launch(storage) {
            while (true) {
                delay(LOG_SAVE_EVERY)
                services.saveLog()
            }
        }
    }

    private fun real(): BikeConnection = realConnection ?: services.real(scope).also { realConnection = it }

    private val current: BikeConnection get() = if (demo) checkNotNull(demoConnection) else real()

    // The connection on screen, if one exists, without creating the real one.
    private val shown: BikeConnection? get() = if (demo) demoConnection else realConnection

    /**
     * Where the app opens: the dashboard for a saved bike the rider accepted the risks for; for a saved bike
     * and a newer risk text, that text first; else the first run.
     */
    fun startRoutes(): List<Route> =
        when {
            saved.bike == null -> listOf(Route.WELCOME)

            // The firmware caveat once, over the dashboard, for riders who set up before it showed at setup.
            services.accepted() -> listOfNotNull(Route.DASHBOARD, Route.FIRMWARE_CAVEAT.takeIf { caveatNeeded })

            // A newer text first; back from it, the dashboard still connects and allows going back to 25.
            services.everAccepted() -> listOf(Route.DASHBOARD, Route.RISK_ACCEPTANCE)

            else -> listOf(Route.RISK_ACCEPTANCE)
        }

    /** A bike is saved as "My bike". */
    val hasSavedBike: Boolean get() = saved.bike != null

    // Permission and Bluetooth

    /**
     * Android answered the permission prompt; [canAskAgain] is false when it offers no explanation. A first
     * refusal (or a dismissed prompt) can be asked again; only a refusal after that sends the rider to the
     * app's settings. A refusal Android would explain is remembered across restarts (see [onPermissionChecked]).
     */
    fun onPermissionResult(
        granted: Boolean,
        canAskAgain: Boolean,
    ): Boolean {
        when {
            granted -> services.permissionRefused(false)
            canAskAgain -> services.permissionRefused(true)
        }
        permission =
            when {
                granted -> PermissionUiState.NOT_ASKED
                !canAskAgain && permission != PermissionUiState.NOT_ASKED -> PermissionUiState.DENIED_PERMANENTLY
                else -> PermissionUiState.DENIED
            }
        refreshBluetooth()
        return granted
    }

    /** The rider allowed Bluetooth outside the prompt, for example in the app's settings. */
    fun onPermissionAllowed() {
        services.permissionRefused(false)
        permission = PermissionUiState.NOT_ASKED
        refreshBluetooth()
    }

    /**
     * What Android says about the permission as the app comes to the front: whether it is [allowed], and
     * whether Android would explain a request ([rationale]). Android explains only after a refusal it may
     * still ask about. Without an explanation, a refusal remembered from this run or an earlier one means
     * Android won't ask again, so "Allow" opens the app's settings at once; with none remembered (never
     * asked, or a dismissed prompt) Android shows the prompt. Returns true when the permission is allowed
     * now after it had been refused.
     */
    fun onPermissionChecked(
        allowed: Boolean,
        rationale: Boolean,
    ): Boolean {
        if (allowed) {
            val wasRefused = permission != PermissionUiState.NOT_ASKED
            if (wasRefused || saved.permissionRefused) onPermissionAllowed()
            return wasRefused
        }
        // An explanation means Android has a refusal on record (also one made before a revoke in the
        // settings): the next silent refusal is final, also after a restart.
        if (rationale && !saved.permissionRefused) services.permissionRefused(true)
        permission =
            when {
                rationale -> PermissionUiState.DENIED

                saved.permissionRefused -> PermissionUiState.DENIED_PERMANENTLY

                // This run's own answer stands: a second silent refusal here already said "for good".
                permission == PermissionUiState.DENIED_PERMANENTLY -> PermissionUiState.DENIED_PERMANENTLY

                else -> PermissionUiState.NOT_ASKED
            }
        return false
    }

    /** Reads Bluetooth's state and the permission again, for example when the app comes back. */
    fun refreshBluetooth() {
        if (demo) return
        realConnection?.refresh?.invoke()
    }

    /** The Android intent that asks to switch Bluetooth on, if it applies. */
    fun enableBluetoothIntent() = if (demo) null else real().enableIntent?.invoke()

    // Find and check

    /** Searches for bikes; the list updates as they are heard. */
    fun startScan() {
        val connection = current
        observe(connection)
        scanJob?.cancel()
        found = emptyMap()
        keys.clear()
        findBike = FindBikeUiState.Searching(emptyList())
        scanJob =
            scope.launch {
                val empty =
                    launch {
                        delay(nothingFoundAfter)
                        if ((findBike as? FindBikeUiState.Searching)?.bikes?.isEmpty() == true) {
                            findBike = FindBikeUiState.NothingFound
                        }
                    }
                try {
                    connection.finder.bikes().collect { ads ->
                        for (ad in ads) keys.getOrPut(ad.address) { keys.size + 1 }
                        found = ads.associateBy { checkNotNull(keys[it.address]) }
                        if (ads.isNotEmpty()) empty.cancel()
                        if (findBike !is FindBikeUiState.NothingFound || ads.isNotEmpty()) {
                            findBike =
                                FindBikeUiState.Searching(
                                    found.map { (key, ad) -> FoundBike(key, bikeLabel, ad.rssi?.let(BikeUi::signal)) },
                                )
                        }
                    }
                } catch (e: LinkException) {
                    empty.cancel()
                    findBike =
                        if (e.failure == LinkFailure.NOT_PERMITTED) {
                            FindBikeUiState.NoPermission(permission == PermissionUiState.DENIED_PERMANENTLY)
                        } else {
                            FindBikeUiState.BluetoothOff
                        }
                }
            }
    }

    /** Stops searching and forgets the bikes heard: other people's bikes aren't kept. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        found = emptyMap()
        keys.clear()
    }

    /** Connects to [bike] from the list and checks it; the session reads its identity once. */
    fun startCheck(bike: FoundBike) {
        val ad = found[bike.key] ?: return
        stopScan()
        checking = ad
        checkPending = true
        runCheck(ad)
    }

    /** Checks the picked bike again; false when no bike is picked. */
    fun retryCheck(): Boolean {
        val ad = checking ?: return false
        checkPending = true
        runCheck(ad)
        return true
    }

    private fun runCheck(ad: Advertisement) {
        startRun(current, ad.address) { it.start(ad) }
    }

    /**
     * The rider continues from [result], which the check screen showed: a verified bike becomes "My bike",
     * an unverified one goes on to the acknowledgement. False when [result] is no longer this run's result
     * (for example, the check ran again meanwhile): the screen then stays where it is.
     */
    fun confirmBike(result: CheckResult): Boolean {
        val ad = checking ?: return false
        if (check.result != result) return false
        return when (result) {
            is CheckResult.Verified -> {
                services.saveBike(ad.address)
                checkPending = false
                true
            }

            is CheckResult.Unverified -> {
                true
            }

            else -> {
                false
            }
        }
    }

    /**
     * For the acknowledgement screen, which records it: the acknowledgement that the checked bike is
     * unverified (recording it also saves the bike as "My bike"), or, from the dashboard, that the saved
     * one is. Null, so nothing can be recorded, unless this run's check still shows the bike unverified (it
     * may have run again while the app was behind another one): the rider then goes back.
     */
    fun unverifiedAcknowledgement(): UnverifiedAcknowledgement? {
        if (!checkPending) {
            val bike = saved.bike ?: return null
            if (classification?.verification != Verification.UNVERIFIED || demo) return null
            return current.unverifiedAcknowledgement(bike)
        }
        val ad = checking ?: return null
        if (check.result !is CheckResult.Unverified) return null
        return current.unverifiedAcknowledgement(ad.address)
    }

    /** Whether the acknowledgement screen was opened from the dashboard, not from the first run's check. */
    val acknowledgingSavedBike: Boolean get() = !checkPending

    /**
     * The acknowledgement screen recorded the acknowledgement: the checked bike is "My bike" now. With
     * [reportResults], the rider offered to report how it worked.
     */
    fun unverifiedAcknowledged(reportResults: Boolean) {
        checkPending = false
        if (reportResults) services.reportResults()
        // The tap that asked for it goes on (the warning above 25 still comes).
        afterAcknowledgement?.let {
            afterAcknowledgement = null
            next(it.copy(acknowledgementDone = true))
        }
    }

    /** The acknowledgement screen was left without acknowledging: the tap that asked for it is dropped. */
    fun acknowledgementDismissed() {
        afterAcknowledgement = null
    }

    // Speed and lights

    /**
     * The rider tapped [kmh]: one command, after what must come first: on an unverified bike, its
     * acknowledgement (not for back to 25 if it was acknowledged for an earlier text); above the legal limit,
     * the warning (unless turned off for that speed). The firmware caveat was shown at setup, never here.
     * What the session still refuses for want of consent opens what is missing.
     */
    fun applySpeed(kmh: Int) {
        if (SpeedLimit.ofKmh(kmh) == null) return
        notSent = null
        afterAcknowledgement = null
        next(SpeedTap(kmh, keeping = keepOn, connection = current, since = since))
    }

    private fun next(tap: SpeedTap) {
        if (!tap.current()) return
        val limit = checkNotNull(SpeedLimit.ofKmh(tap.kmh))
        val unverified = classification?.verification == Verification.UNVERIFIED
        val bike = saved.bike
        when {
            // An unverified bike's acknowledgement first, so that the sheets' "Set" is what sends.
            !tap.acknowledgementDone && unverified && !tap.connection.demo && bike != null &&
                !tap.connection.unverifiedAcknowledged(bike) &&
                !(limit == SpeedLimit.BIKE_DEFAULT && tap.connection.unverifiedEverAcknowledged(bike)) -> {
                afterAcknowledgement = tap
                consentNeeded = ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT
            }

            !tap.warningDone && limit.isAboveLegalLimit && tap.kmh !in warningsOff(tap.connection) -> {
                warningTap = tap
                warning =
                    PendingWarning(tap.kmh, if (tap.keeping) WarningPurpose.APPLY_KEEPING else WarningPurpose.APPLY)
            }

            else -> {
                send(tap)
            }
        }
    }

    /** The rider confirmed the open warning, possibly turning it off for its speed. */
    fun confirmWarning(dontShowAgain: Boolean) {
        val open = warning ?: return
        val tap = warningTap
        warning = null
        warningTap = null
        val connection = tap?.connection ?: current
        if (dontShowAgain) {
            if (connection.demo) demoWarningsOff += open.kmh else services.warningOff(open.kmh)
        }
        if (tap != null) next(tap.copy(warningDone = true))
    }

    fun cancelWarning() {
        warning = null
        warningTap = null
    }

    /** The firmware caveat is still to be shown (once, at setup, for the current text); never in demo mode. */
    val caveatNeeded: Boolean get() = !demo && !services.caveatSeen()

    /** Where the caveat screen at setup records that the rider saw it. */
    val setupCaveat: FirmwareCaveat get() = services.firmwareCaveat

    /** The app opened the screen [consentNeeded] asked for. */
    fun consentOpened() {
        consentNeeded = null
    }

    private fun warningsOff(connection: BikeConnection): Set<Int> =
        if (connection.demo) demoWarningsOff else services.warningsOff()

    private fun send(tap: SpeedTap) {
        if (!tap.current()) return
        val limit = checkNotNull(SpeedLimit.ofKmh(tap.kmh))
        // As the session does: a speed the rider taps becomes the kept one, if keep-speed was on at the tap.
        if (tap.keeping) keep(tap.connection, true, tap.kmh)
        scope.launch {
            val outcome = tap.connection.session.setSpeedLimit(limit)
            // A result for another connection or run than the one on screen counts for nothing here.
            if (!tap.current()) return@launch
            when ((outcome as? CommandOutcome.Refused)?.reason) {
                // Speeds are offered on every bike; only a lock or an unlock needs the bike off or standing still.
                null, Refusal.NOT_SUPPORTED, Refusal.BIKE_NOT_OFF, Refusal.MOVING -> {}

                Refusal.CAVEAT_NOT_SEEN -> {
                    // Shown at setup; if it was skipped there, it comes now, and the rider taps again after it.
                    notSent = SpeedNotSent(tap.kmh, NotSent.NOT_YET)
                    consentNeeded = ConsentNeeded.FIRMWARE_CAVEAT
                }

                Refusal.NOT_ACKNOWLEDGED -> {
                    // Asked for first (see next); if the session still refuses, the rider taps again after it.
                    if (!tap.connection.demo) {
                        notSent = SpeedNotSent(tap.kmh, NotSent.NOT_YET)
                        consentNeeded = ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT
                    }
                }

                Refusal.NOT_ACCEPTED -> {
                    // A newer text to accept first (25 goes meanwhile); the rider taps again after it.
                    notSent = SpeedNotSent(tap.kmh, NotSent.NOT_YET)
                    consentNeeded = ConsentNeeded.RISK_ACCEPTANCE
                }

                Refusal.BIKE_NOT_ON -> {
                    notSent = SpeedNotSent(tap.kmh, NotSent.BIKE_NOT_ON)
                }

                Refusal.NOT_READY -> {
                    notSent = SpeedNotSent(tap.kmh, NotSent.NOT_CONNECTED)
                }

                Refusal.BUSY -> {
                    notSent = SpeedNotSent(tap.kmh, NotSent.BUSY)
                }
            }
        }
    }

    /** The rider switched the lights [on] or off: one command. */
    fun setLights(on: Boolean) {
        if (lightsSwitching != null) return
        lightsNotSent = null
        afterAcknowledgement = null
        lightsSwitching = on
        lightsNotConfirmed = false
        val connection = current
        val run = since
        scope.launch {
            val outcome =
                try {
                    connection.session.setLights(on)
                } finally {
                    lightsSwitching = null
                }
            if (shown !== connection || since != run) return@launch
            lightsNotConfirmed = outcome == CommandOutcome.NotConfirmed || outcome == CommandOutcome.Failed
            when ((outcome as? CommandOutcome.Refused)?.reason) {
                Refusal.NOT_ACKNOWLEDGED -> {
                    if (!connection.demo) {
                        // Not switched afterwards by itself: the lights row says so, and a tap switches them.
                        lightsNotSent = NotSent.NOT_YET
                        consentNeeded = ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT
                    }
                }

                Refusal.NOT_ACCEPTED -> {
                    lightsNotSent = NotSent.NOT_YET
                    consentNeeded = ConsentNeeded.RISK_ACCEPTANCE
                }

                Refusal.BIKE_NOT_ON -> {
                    lightsNotSent = NotSent.BIKE_NOT_ON
                }

                Refusal.NOT_READY -> {
                    lightsNotSent = NotSent.NOT_CONNECTED
                }

                Refusal.BUSY -> {
                    lightsNotSent = NotSent.BUSY
                }

                Refusal.CAVEAT_NOT_SEEN, Refusal.NOT_SUPPORTED, Refusal.BIKE_NOT_OFF, Refusal.MOVING, null -> {}
            }
        }
    }

    /**
     * The rider tapped Turn on ([on]) or Turn off: one unlock or lock, on a bike whose profile offers it. Like
     * the lights, it needs the risk acceptance, never a warning; turning off also needs the bike standing still,
     * turning on needs it off. Nothing sends it again by itself.
     */
    fun setPower(on: Boolean) {
        if (live.power == null || powerSwitching != null) return
        powerNotSent = null
        powerNotSentOn = on
        powerNotConfirmed = null
        afterAcknowledgement = null
        powerSwitching = on
        val connection = current
        val run = since
        scope.launch {
            val outcome =
                try {
                    connection.session.setLocked(!on)
                } finally {
                    powerSwitching = null
                }
            if (shown !== connection || since != run) return@launch
            if (outcome == CommandOutcome.NotConfirmed || outcome == CommandOutcome.Failed) powerNotConfirmed = on
            when ((outcome as? CommandOutcome.Refused)?.reason) {
                Refusal.NOT_ACKNOWLEDGED -> {
                    if (!connection.demo) {
                        powerNotSent = PowerNotSent.NOT_YET
                        consentNeeded = ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT
                    }
                }

                Refusal.NOT_ACCEPTED -> {
                    powerNotSent = PowerNotSent.NOT_YET
                    consentNeeded = ConsentNeeded.RISK_ACCEPTANCE
                }

                Refusal.BIKE_NOT_ON -> {
                    powerNotSent = PowerNotSent.BIKE_NOT_ON
                }

                Refusal.BIKE_NOT_OFF -> {
                    powerNotSent = PowerNotSent.BIKE_NOT_OFF
                }

                Refusal.MOVING -> {
                    powerNotSent = PowerNotSent.MOVING
                }

                Refusal.NOT_READY -> {
                    powerNotSent = PowerNotSent.NOT_CONNECTED
                }

                Refusal.BUSY -> {
                    powerNotSent = PowerNotSent.BUSY
                }

                // Offered only where the profile gives it; no caveat for switching the bike on or off.
                Refusal.NOT_SUPPORTED, Refusal.CAVEAT_NOT_SEEN, null -> {}
            }
        }
    }

    /**
     * The rider tapped assist [level]: one command, on a bike whose profile offers it. Like the lights, it
     * needs the risk acceptance (and an unverified bike's acknowledgement), not the firmware caveat or a
     * warning. With "keep this assist level" on, the tapped level becomes the kept one at the tap.
     */
    fun setAssistLevel(level: Int) {
        val target = AssistLevel.of(level) ?: return
        val shownAssist = live.assist
        // Only the levels the bike's profile offers; nothing while another level is being sent.
        val levels = shownAssist.levels ?: return
        if (level > levels || shownAssist.sending != null) return
        assistNotSent = null
        afterAcknowledgement = null
        val connection = current
        val run = since
        // As the session does: a level the rider taps becomes the kept one, if it is kept.
        if (keepAssistOn) keepAssist(connection, true, level)
        scope.launch {
            val outcome = connection.session.setAssistLevel(target)
            if (shown !== connection || since != run) return@launch
            when ((outcome as? CommandOutcome.Refused)?.reason) {
                Refusal.NOT_ACKNOWLEDGED -> {
                    if (!connection.demo) {
                        // Not sent afterwards by itself: the card says so, and a tap sends it.
                        assistNotSent = NotSent.NOT_YET
                        consentNeeded = ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT
                    }
                }

                Refusal.NOT_ACCEPTED -> {
                    assistNotSent = NotSent.NOT_YET
                    consentNeeded = ConsentNeeded.RISK_ACCEPTANCE
                }

                Refusal.BIKE_NOT_ON -> {
                    assistNotSent = NotSent.BIKE_NOT_ON
                }

                Refusal.NOT_READY -> {
                    assistNotSent = NotSent.NOT_CONNECTED
                }

                Refusal.BUSY -> {
                    assistNotSent = NotSent.BUSY
                }

                // The card offers only the levels the bike's profile gives; no caveat for an assist level.
                Refusal.NOT_SUPPORTED, Refusal.CAVEAT_NOT_SEEN, Refusal.BIKE_NOT_OFF, Refusal.MOVING, null -> {}
            }
        }
    }

    /**
     * The rider turned "keep this assist level" [on] or off. On, it keeps the level the bike confirmed from the
     * app on this connection; without one, the next level the rider taps. Off, it forgets the kept level. A
     * level changed on the bike's display is never kept.
     */
    fun setKeepAssist(on: Boolean) {
        val connection = current
        if (!on) return keepAssist(connection, false, null)
        if (live.assist.sending != null) return
        keepAssist(connection, true, live.assist.confirmed)
    }

    private fun keepAssist(
        connection: BikeConnection,
        on: Boolean,
        level: Int?,
    ) {
        if (connection.demo) {
            demoKeepAssist = on
            demoKeptAssist = level.takeIf { on }
        } else {
            services.keepAssist(on, level.takeIf { on })
        }
        applyKeep(connection)
    }

    /**
     * The rider turned "keep this speed" [on] or off. On, it keeps the speed the bike confirmed on this
     * connection; without one, it keeps the next speed the rider taps. Off, it forgets the kept speed.
     */
    fun setKeepSpeed(on: Boolean) {
        val connection = current
        // Off is always allowed; on waits while a speed is being sent, so that it keeps the confirmed one.
        if (!on) return keep(connection, false, null)
        if (sendingSpeed) return
        keep(connection, true, (dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh)
    }

    /** A speed is being sent: the keep switch waits until the bike confirmed it or not. */
    val sendingSpeed: Boolean get() = live.speedLimit.status is SpeedLimitStatus.Sending

    private fun keep(
        connection: BikeConnection,
        on: Boolean,
        kmh: Int?,
    ) {
        if (connection.demo) {
            demoKeep = on
            demoKept = kmh.takeIf { on }
        } else {
            services.keepSpeed(on, kmh.takeIf { on })
        }
        applyKeep(connection)
    }

    /**
     * Tells [connection]'s session what to keep for its bike ("My bike", or the demo's): the kept speed while
     * "keep this speed" is on, the kept assist level while "keep this assist level" is on, else nothing.
     */
    private fun applyKeep(connection: BikeConnection) {
        val bike = if (connection.demo) connection.demoBikes.firstOrNull()?.address else saved.bike
        val kept = if (connection.demo) demoKept.takeIf { demoKeep } else saved.keptKmh.takeIf { saved.keepSpeed }
        val keptLevel =
            if (connection.demo) {
                demoKeptAssist.takeIf { demoKeepAssist }
            } else {
                saved.keptAssistLevel.takeIf { saved.keepAssist }
            }
        bike?.let {
            connection.session.keepSpeed(it, kept?.let(SpeedLimit::ofKmh))
            connection.session.keepAssistLevel(it, keptLevel?.let(AssistLevel::of))
        }
    }

    /** One tap on a speed, on its way through the sheets to the session. */
    private data class SpeedTap(
        val kmh: Int,
        /** "Keep this speed" was on at the tap: the speed becomes the kept one. */
        val keeping: Boolean,
        val connection: BikeConnection,
        val since: Int,
        val acknowledgementDone: Boolean = false,
        val warningDone: Boolean = false,
    )

    /** Whether [this] tap still belongs to the connection and run on screen. */
    private fun SpeedTap.current(): Boolean = connection === shown && since == this@AppController.since

    /** Every speed warning shows again. */
    fun resetWarnings() {
        demoWarningsOff.clear()
        services.resetWarnings()
    }

    /** Back from the check without continuing: the connection ends, and the bike is forgotten. */
    fun cancelCheck() {
        if (checkPending) current.session.stop()
        checkPending = false
        checking = null
    }

    // Dashboard and its connection

    /** The app is in front: resume what was running, or connect to the saved bike. */
    fun onForeground() {
        away = false
        val what = resume
        resume = Resume.NOTHING
        when (what) {
            Resume.DEMO -> {
                demoConnection?.let { startOn(it, it.demoBikes.first().address) }
            }

            Resume.CHECK -> {
                if (retryCheck()) {
                    checkResumed = true
                    check = check.copy(restarted = true)
                }
            }

            Resume.NOTHING -> {
                if (!demo && !checkPending) connectSaved()
            }
        }
    }

    /** Connects to the saved bike, for example once the rider accepted a new risk text. */
    fun connectToSavedBike() = connectSaved()

    /** The rider accepted an older risk text, so the risk text shows as changed. */
    val acceptanceOutdated: Boolean get() = saved.acceptance != null && !services.accepted()

    private fun connectSaved() {
        refreshBluetooth()
        val bike = saved.bike ?: return
        // An earlier acceptance is enough to connect: going back to 25 stays possible until the new text is.
        if (!services.everAccepted()) return
        val connection = real()
        if (connection.session.state.value == SessionState.Idle) startOn(connection, bike)
    }

    /** The app went behind something else: every connection ends until it comes back. */
    @OptIn(DelicateCoroutinesApi::class) // ATOMIC: the save starts even if the scope ends right after
    fun onBackground() {
        away = true
        // A compatibility check ends with the connection: it can be run again.
        compatibilityJob?.cancel()
        compatibilityJob = null
        if (compatibility?.report == null) compatibility = null
        stopScan()
        resume =
            when {
                demo -> Resume.DEMO
                checkPending -> Resume.CHECK
                else -> Resume.NOTHING
            }
        demoConnection?.session?.stop()
        realConnection?.session?.stop()
        // After the stops, so that their records are in it; started even if the screen goes right after.
        scope.launch(storage, start = CoroutineStart.ATOMIC) { services.saveLog() }
    }

    /** Try the saved bike again, for example after it stopped answering as expected. */
    fun retryConnection() {
        val bike = saved.bike ?: return
        if (demo) return
        startOn(real(), bike)
    }

    private fun startOn(
        connection: BikeConnection,
        bike: BikeAddress,
    ) {
        startRun(connection, bike) { it.start(bike) }
    }

    /** Stops [connection]'s session, forgets the last run, follows the session from its stop on, and starts it. */
    private fun startRun(
        connection: BikeConnection,
        bike: BikeAddress,
        start: (BikeSession) -> Int,
    ) {
        if (checkRunning) return
        val session = connection.session
        session.stop()
        newRun()
        runBike = bike
        since = session.status.value.run
        observe(connection)
        applyKeep(connection)
        start(session)
    }

    /** Opens the dashboard on the simulated bikes. Nothing reaches Android's Bluetooth. */
    fun startDemo() {
        stopScan()
        realConnection?.session?.stop()
        val connection = demoConnection ?: services.demo(scope).also { demoConnection = it }
        demo = true
        startOn(connection, connection.demoBikes.first().address)
    }

    /** Ends demo mode; the next search uses the real Bluetooth again. */
    fun leaveDemo() {
        if (!demo) return
        compatibilityJob?.cancel()
        compatibilityJob = null
        compatibility = null
        stopScan()
        demoConnection?.session?.stop()
        demo = false
        observer?.cancel()
        newRun()
    }

    /** A new run starts: nothing of an earlier one (its model, its verification, its values) carries over. */
    private fun newRun() {
        since = NO_RUN
        settled = false
        checkResumed = false
        classification = null
        unreachable = false
        limitSeen = LimitState.Unknown
        applied = false
        notSent = null
        lightsNotSent = null
        lightsNotConfirmed = false
        assistNotSent = null
        powerNotSent = null
        powerNotConfirmed = null
        lastOff = null
        warning = null
        warningTap = null
        afterAcknowledgement = null
        check = ConnectCheckUiState.Starting
        live = emptyDashboard()
    }

    private fun observe(connection: BikeConnection) {
        observer?.cancel()
        observer =
            scope.launch {
                combine(
                    connection.session.status,
                    connection.session.ride,
                    connection.radio,
                    connection.session.limit,
                    connection.session.assist,
                ) { s, r, radio, limit, assist -> Observed(s, r, radio, limit, assist) }.collect { observed ->
                    val (status, ride, radio, limit, assist) = observed
                    val state = status.state
                    // An earlier run's states (a Ready replayed, an Idle) don't count for this one.
                    val fresh = since != NO_RUN && status.run > since
                    if (fresh && !settled) {
                        val mapped = BikeUi.check(state, radio)
                        // A paired bike that wasn't heard and couldn't be reached is most likely off or away.
                        val pairedAway = mapped.result == CheckResult.ConnectedElsewhere && checking?.heard == false
                        check =
                            (if (pairedAway) mapped.copy(result = CheckResult.NotFound) else mapped)
                                .copy(restarted = checkResumed)
                        settled = check.result != null
                    }
                    if (fresh && state is SessionState.Ready && state.classification !== classification) {
                        // A new connection: nothing set on the last one counts.
                        classification = state.classification
                        applied = false
                    }
                    if (limit !== limitSeen) {
                        // A "not sent" note belongs to the state it was said in.
                        notSent = null
                        if (limit is LimitState.Confirmed) confirmedAt = clock()
                        // On an unverified bike, a speed other than the default that the rider set (not a
                        // re-apply): the next app open asks how it worked, if the rider offered to report results.
                        val bike = runBike
                        if (limit is LimitState.Confirmed &&
                            limit.reapply == null &&
                            !demo &&
                            bike != null &&
                            limit.limit != SpeedLimit.KMH_25 &&
                            classification?.verification == Verification.UNVERIFIED
                        ) {
                            services.rideTest(bike, limit.limit.kmh)
                        }
                        if (limit is LimitState.Sending || limit is LimitState.Confirmed) applied = true
                        limitSeen = limit
                    }
                    if (state is SessionState.Incompatible) classification = null
                    if (fresh) unreachable = stillUnreachable(state)
                    val known = classification
                    val verified = known?.verification == Verification.VERIFIED
                    val connection =
                        BikeUi.connection(
                            radio,
                            if (fresh) state else SessionState.Idle,
                            ride,
                            checked =
                                known != null,
                        )
                    val lightsNow = if (known != null) BikeUi.lights(ride) else Lights.UNKNOWN
                    if (connection != live.connection || lightsNow != live.lights.lights) lightsNotSent = null
                    if (connection != live.connection) notSent = null
                    val assistNow = BikeUi.assist(ride, assist, assistLevels(known)).takeIf { known != null }
                    if (connection != live.connection || assistNow?.level != live.assist.level) assistNotSent = null
                    // A note about turning the bike on or off belongs to the state it was said in.
                    if (connection != live.connection) {
                        powerNotSent = null
                        powerNotConfirmed = null
                    }
                    if (known != null) {
                        if (BikeUi.reportsOff(ride)) lastOff = true
                        if (connection == Connection.CONNECTED && BikeUi.reportsOn(ride)) lastOff = false
                    }
                    live =
                        DashboardUiState(
                            bikeName = stored.bikeName,
                            verification = if (verified) Verification.VERIFIED else Verification.UNVERIFIED,
                            connection = connection,
                            ride = BikeUi.ride(ride).takeIf { known != null },
                            more = BikeUi.more(ride).takeIf { known != null },
                            lights = LightsUiState(if (known != null) BikeUi.lights(ride) else Lights.UNKNOWN),
                            speedLimit =
                                SpeedLimitUiState(
                                    choices = SPEEDS,
                                    status =
                                        if (connection == Connection.CONNECTED) {
                                            BikeUi.limit(limit, verified, connectionTrusts(), applied, confirmedAt)
                                        } else {
                                            SpeedLimitStatus.Unknown
                                        },
                                    keepSpeed = false,
                                    restartsTrusted = connectionTrusts() && powerFlagsVerified(known),
                                ),
                            assist = assistNow ?: AssistUiState(level = null, levels = null),
                            power = PowerUiState().takeIf { lockControl(known) },
                            checked = known != null,
                            cantReach = unreachable,
                            // "On" only from a fresh, readable status whose power flags are verified for this bike.
                            reportsOn = BikeUi.reportsOn(ride),
                            reportsOff = BikeUi.reportsOff(ride),
                            model = known?.modelIdentity,
                            testedFirmware =
                                known
                                    ?.profile
                                    ?.testedFirmware
                                    .orEmpty()
                                    .takeIf { verified }
                                    .orEmpty(),
                        )
                }
            }
    }

    private fun connectionTrusts(): Boolean = current.session.restartsTrusted

    /**
     * Whether a bike classified as [known] has power flags verified for it: only then does the session see it switch
     * on, and only then may "keep this speed" promise to set the speed again after a restart.
     */
    private fun powerFlagsVerified(known: Classification?): Boolean =
        known != null &&
            listOf(StatusField.POWER_FLAG_A, StatusField.POWER_FLAG_B).all { known.layout?.spec(it)?.verified == true }

    /** Whether the app can switch a bike classified as [known] on and off: its profile offers it, with verified flags. */
    private fun lockControl(known: Classification?): Boolean =
        known?.profile?.lockControl == true && powerFlagsVerified(known)

    /** The assist levels the app can set on a bike classified as [known]: from its profile, with a reading. */
    private fun assistLevels(known: Classification?): Int? =
        known?.profile?.assistLevels?.takeIf { known.layout?.spec(StatusField.ASSIST_LEVEL) != null }

    /** Whether the bike counts as not found or reached yet, after [state]. */
    private fun stillUnreachable(state: SessionState): Boolean =
        when (state) {
            is SessionState.Retrying -> {
                when (state.reason) {
                    StopReason.NOT_FOUND, StopReason.CONNECT_FAILED -> true

                    // A dropped link isn't "can't reach" yet; Bluetooth off says so itself.
                    else -> unreachable && state.reason != StopReason.BLUETOOTH_UNAVAILABLE
                }
            }

            // Searching or connecting again: as before, so that the hint doesn't come and go. Once
            // connected, the bike was reached.
            is SessionState.Checking -> {
                unreachable && (state.step == CheckStep.SEARCHING || state.step == CheckStep.CONNECTING)
            }

            else -> {
                false
            }
        }

    private fun emptyDashboard() =
        DashboardUiState(
            bikeName = stored.bikeName,
            verification = Verification.UNVERIFIED,
            connection = Connection.CONNECTING,
            ride = null,
            more = null,
            lights = LightsUiState(Lights.UNKNOWN),
            // Nothing known about the bike yet: no promise about restarts.
            speedLimit =
                SpeedLimitUiState(
                    SPEEDS,
                    SpeedLimitStatus.Unknown,
                    keepSpeed = false,
                    restartsTrusted = false,
                ),
            checked = false,
        )

    // Report and settings

    fun openReport(type: ReportType?) {
        report = ReportRequest(type)
        readReportSummary()
    }

    fun closeReport() {
        summaryReading?.cancel()
        report = null
        reportLog = null
        reportLogs = null
    }

    /**
     * "Report this result": a compatibility report about the check on screen, filled in with its result; for a
     * demo check, a report like any in demo mode, which shares nothing of the simulated bike.
     */
    fun reportCheck() {
        val state = compatibility ?: return
        val check = state.report ?: return
        report = ReportRequest(ReportType.COMPATIBILITY, ReportAnswers(check = check.takeUnless { state.demo }))
        readReportSummary()
    }

    /**
     * What a report of [type] would share, each item with the issue form's field it fills in: the app, the
     * build, Android and the phone (as the log records them), the model this run checked and its
     * verification, what the rider typed ([answers]), and from this run's log the speeds sent and how they
     * ended, or the errors. A report about a compatibility check ([ReportAnswers.check]) takes the model and
     * verification the check found, and shares its result in place of the checks. Nothing else goes into the
     * form's link.
     */
    fun reportPreview(
        type: ReportType,
        answers: ReportAnswers,
        app: String,
        labels: ReportLabels,
    ): ReportPreview {
        val appItem = SharedItem(labels.app, app, IssueField.APP_VERSION)
        if (!type.aboutTheBike) return ReportPreview(listOf(appItem), demo = demo, needsLog = false, form = type.form)
        // A demo check is of a simulated bike: like the demo's model, nothing of it is shared.
        val check = answers.check.takeIf { type == ReportType.COMPATIBILITY && !demo }
        val checked = if (check != null) check.classification else classification.takeUnless { demo }
        // This run's log, read when the report opened: the speeds the rider sent, and the errors. The demo's
        // log isn't this one.
        val logs = reportLogs.takeUnless { demo }
        val summary = logs?.current
        // A report from the ride test is about the earlier run in which the bike confirmed its speed: the
        // model and verification come from that run's log when this run has none yet.
        val rideTest = answers.rideTest.takeUnless { demo }
        val earlier = logs?.earlier.takeIf { rideTest != null }
        val model = checked?.modelIdentity ?: earlier?.models?.lastOrNull()
        // The log records the class as its name in lower case.
        val verification =
            checked?.verification
                ?: Verification.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == earlier?.verification }
        val speeds =
            if (rideTest != null) {
                listOf(rideTest.kmh to SentCommand.Outcome.CONFIRMED)
            } else {
                summary?.commands.orEmpty().mapNotNull { sent -> sent.speedKmh?.let { it to sent.outcome } }
            }
        val lastConfirmed = speeds.lastOrNull { it.second == SentCommand.Outcome.CONFIRMED }?.first
        // The assist levels this run sent (a ride test's report is about an earlier run's speed only).
        val levels =
            summary?.commands.orEmpty().takeIf { rideTest == null }.orEmpty().mapNotNull { sent ->
                sent.assistLevel?.let { it to sent.outcome }
            }
        val items =
            listOfNotNull(
                appItem,
                SharedItem(labels.build, labels.buildName, IssueField.BUILD_TESTED),
                SharedItem(labels.android, "${services.android} · ${services.phone}", IssueField.ANDROID),
                model?.let { SharedItem(labels.model, it, IssueField.MODEL_IDENTITY) },
                verification?.let { v ->
                    when (v) {
                        Verification.VERIFIED -> labels.verified
                        Verification.UNVERIFIED -> labels.unverified
                        Verification.INCOMPATIBLE -> null
                    }?.let { SharedItem(labels.verification, it, IssueField.VERIFICATION) }
                },
                // Like the model: a demo bike has no firmware to report.
                answers.iotVersion.takeUnless { demo }?.let(IotVersion::shared)?.let {
                    SharedItem(labels.iotVersion, it, IssueField.IOT_FIRMWARE)
                },
            ) +
                when (type) {
                    ReportType.COMPATIBILITY -> {
                        listOfNotNull(
                            if (check != null) {
                                SharedItem(labels.checks, labels.check(check), IssueField.PROTOCOL_CHECKS)
                            } else {
                                // A classified bike passed the protocol checks (an incompatible one has no class).
                                verification?.takeIf { it != Verification.INCOMPATIBLE }?.let {
                                    SharedItem(labels.checks, labels.checksPassed, IssueField.PROTOCOL_CHECKS)
                                }
                            },
                            speeds
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString("\n") { (kmh, outcome) ->
                                    if (outcome ==
                                        SentCommand.Outcome.CONFIRMED
                                    ) {
                                        labels.confirmed(kmh)
                                    } else {
                                        labels.notConfirmed(kmh)
                                    }
                                }?.let { SharedItem(labels.results, it, IssueField.SPEEDS_CONFIRMED) },
                            // Where assistance stopped belongs to the speed the bike confirmed last.
                            lastConfirmed?.let { kmh ->
                                val field = stopSpeedField(kmh)
                                answers.stopSpeedKmh?.takeIf { it > 0 && field != null }?.let {
                                    SharedItem(labels.stopSpeed(kmh), labels.kmh(it), field)
                                }
                            },
                            rideTest?.restart?.let {
                                SharedItem(
                                    labels.backTo25,
                                    labels.restart(it),
                                    IssueField.BACK_TO_25,
                                )
                            },
                            levels
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString("\n") { (level, outcome) ->
                                    if (outcome == SentCommand.Outcome.CONFIRMED) {
                                        labels.assistConfirmed(level)
                                    } else {
                                        labels.assistNotConfirmed(level)
                                    }
                                }?.let { SharedItem(labels.assistResults, it, IssueField.ASSIST_CONFIRMED) },
                        )
                    }

                    ReportType.PROBLEM -> {
                        listOfNotNull(
                            SharedItem(labels.state, stateText(live.connection), IssueField.APP_STATE),
                            summary?.let { SharedItem(labels.errors, labels.errorCount(it.errors), IssueField.ERRORS) },
                        )
                    }

                    ReportType.IDEA -> {
                        emptyList()
                    }
                }
        return ReportPreview(
            items,
            demo = demo,
            needsLog = true,
            form = type.form,
            stopSpeedFor = lastConfirmed?.takeIf { type == ReportType.COMPATIBILITY && stopSpeedField(it) != null },
            // Until the logs are read, what they add isn't known: nothing opens yet.
            loading = !demo && logs == null,
        )
    }

    /** The open report's logs, summarised: this run's, and the ride test's earlier run's if it has one. */
    private class ReportLogs(
        val current: SessionSummary,
        val earlier: SessionSummary?,
    )

    // Read off the main thread when the report opens.
    private var reportLogs: ReportLogs? by mutableStateOf(null)

    /**
     * The log a report attaches, as it was when the report opened: the sheet shows every line of it, and
     * exactly this text is saved. This run's log, or for a ride test the log of the run it is about while it
     * is still stored ([LogPreview.replaced] when it is gone). Null before it is read, and in demo mode.
     */
    var reportLog: LogPreview? by mutableStateOf(null)
        private set

    private var summaryReading: Job? = null

    private fun readReportSummary() {
        summaryReading?.cancel()
        reportLogs = null
        reportLog = null
        if (demo) return
        val earlier = report?.answers?.rideTest?.log
        summaryReading =
            scope.launch {
                val (logs, log) =
                    withContext(storage) {
                        val now = services.logText.toString()
                        val nowSummary = SessionSummary.of(now)
                        val then = earlier?.let { services.readStoredLog(it) }?.takeIf { it.isNotEmpty() }
                        val thenSummary = then?.let { SessionSummary.of(it) }
                        val current = services.currentLogNumber() ?: 0
                        val attached =
                            when {
                                earlier == null -> {
                                    LogPreview(current, now, nowSummary)
                                }

                                then == null || thenSummary == null -> {
                                    LogPreview(
                                        current,
                                        now,
                                        nowSummary,
                                        replaced = true,
                                    )
                                }

                                else -> {
                                    LogPreview(earlier, then, thenSummary)
                                }
                            }
                        ReportLogs(nowSummary, thenSummary) to attached
                    }
                reportLogs = logs
                reportLog = log
            }
    }

    private fun stopSpeedField(kmh: Int): IssueField? =
        when (kmh) {
            16 -> IssueField.STOP_SPEED_16
            25 -> IssueField.STOP_SPEED_25
            32 -> IssueField.STOP_SPEED_32
            45 -> IssueField.STOP_SPEED_45
            else -> null
        }

    /** The dashboard's connection, as plain words for a report (the issue tracker reads English). */
    private fun stateText(connection: Connection): String = connection.name.lowercase(Locale.ROOT).replace('_', ' ')

    /**
     * The ride test this app open asks about: a speed the saved bike confirmed in an earlier run, after the
     * rider offered to report results. Read once, when the app opens.
     */
    var rideTest: RideTest? by mutableStateOf(
        services.data.value.let { s -> s.rideTestKmh?.let { RideTest(it, s.rideTestLog) } },
    )
        private set

    /** The ride test was put away without an answer: the next app open asks again. */
    fun rideTestLater() {
        rideTest = null
    }

    /**
     * The rider answered the ride test ([answers]) or skipped it (null): it isn't asked again. Answers open a
     * compatibility report, filled in with them.
     */
    fun rideTestDone(answers: RideTest?) {
        val asked = rideTest ?: return
        rideTest = null
        services.rideTestDone(asked.kmh, asked.log)
        if (answers == null) return
        report =
            ReportRequest(
                ReportType.COMPATIBILITY,
                ReportAnswers(stopSpeedKmh = answers.stopSpeedKmh, rideTest = answers),
            )
        readReportSummary()
    }

    /**
     * The rider sends the open report ([preview], as the sheet showed it): its log ([reportLog], exactly the
     * text shown) is saved in Downloads as [fileName] of the log's date and the next number for that date;
     * where Downloads can't take it, the sheet's screen opens the file picker ([ReportSending.Stage.PICK]);
     * then the form opens ([ReportSending.Stage.OPEN]). Once per report: a second tap does nothing. Demo mode
     * saves and opens nothing. The work runs here, so it survives the screen being recreated.
     */
    fun sendReport(
        preview: ReportPreview,
        fileName: (LocalDate, Int) -> String,
    ) {
        val open = report ?: return
        // Once per report, and only once its log is read (the sheet waits for it too).
        if (open.sending != null || open.sent != null || preview.loading) return
        if (preview.demo) {
            report = open.copy(sent = ReportSent(log = null, opened = false, demo = true))
            return
        }
        val log = reportLog?.takeIf { preview.needsLog && it.text.isNotBlank() }
        if (log == null) {
            report = open.copy(sending = ReportSending(preview, ReportSending.Stage.OPEN))
            return
        }
        val date = log.date ?: services.logDate
        val number = services.nextLogNumber(date)
        val name = fileName(date, number)
        val sending = ReportSending(preview, ReportSending.Stage.SAVING, file = LogFile(name, date, number))
        report = open.copy(sending = sending)
        scope.launch {
            val saved = downloads?.let { withContext(storage) { it.save(name, log.text) } }
            // The file is there whatever happens next: its number is taken.
            if (saved != null) services.logSaved(date, number)
            // Closed meanwhile (or another report opened): nothing more happens for it.
            val now = report?.takeIf { it.sending === sending } ?: return@launch
            report =
                if (saved != null) {
                    now.copy(
                        sending = sending.copy(stage = ReportSending.Stage.OPEN, log = LogSaved.InDownloads(saved)),
                    )
                } else {
                    now.copy(sending = sending.copy(stage = ReportSending.Stage.PICK))
                }
        }
    }

    /**
     * The file picker for the report's log answered: [write] writes the log to the file the rider picked
     * (null: cancelled) and says whether it was saved and whether the file went again. The report leaves the
     * picker at once, so a recreated screen never opens it again; the write runs here, then the form opens.
     */
    fun reportLogPicked(write: (() -> Pair<Boolean, Boolean>)?) {
        val open = report ?: return
        val sending = open.sending?.takeIf { it.stage == ReportSending.Stage.PICK } ?: return
        val writing = sending.copy(stage = ReportSending.Stage.WRITING)
        report = open.copy(sending = writing)
        scope.launch {
            val (saved, removed) = if (write == null) false to true else withContext(storage) { write() }
            // Unless the file is gone again its name is taken: count it, so the next one doesn't clash.
            if (!removed) writing.file?.let { services.logSaved(it.date, it.number) }
            val now = report?.takeIf { it.sending === writing } ?: return@launch
            val log = if (saved) LogSaved.Chosen else LogSaved.NotSaved
            report = now.copy(sending = writing.copy(stage = ReportSending.Stage.OPEN, log = log))
        }
    }

    /** The screen opened the report's form in the browser, or couldn't ([opened]): the sheet says how it went. */
    fun reportOpened(opened: Boolean) {
        val open = report ?: return
        val sending = open.sending?.takeIf { it.stage == ReportSending.Stage.OPEN } ?: return
        report = open.copy(sending = null, sent = ReportSent(sending.log, opened))
    }

    /** The form was opened again ([opened]: it worked this time). */
    fun reportOpenedAgain(opened: Boolean) {
        val open = report ?: return
        val sent = open.sent ?: return
        report = open.copy(sent = sent.copy(opened = sent.opened || opened))
    }

    /** The bike profiles the app carries, for the compatibility table. */
    val bundledProfiles: List<BikeProfile> get() = services.bundledProfiles

    fun rename(name: String) {
        saved.bike?.let { services.rename(it, name) }
    }

    /** Forgets the bike and its name, ends its connection, and goes back to finding one. */
    fun forgetBike(navigator: Navigator) {
        stopScan()
        realConnection?.session?.let { session ->
            session.stop()
            // Nothing kept for a bike the app forgot.
            saved.bike?.let {
                session.keepSpeed(it, null)
                session.keepAssistLevel(it, null)
            }
        }
        services.forgetBike()
        checking = null
        checkPending = false
        newRun()
        navigator.reset(Route.WELCOME, Route.RISK_ACCEPTANCE, Route.BLUETOOTH)
    }

    /** Deletes the stored session logs; this run's starts again. Started even if the screen goes right after. */
    @OptIn(DelicateCoroutinesApi::class)
    fun deleteStoredLogs() {
        scope.launch(storage, start = CoroutineStart.ATOMIC) { services.deleteStoredLogs() }
    }

    /** A display setting, kept on the phone: the screen stays on with the dashboard. */
    val keepScreenOn: Boolean get() = stored.keepScreenOn

    fun setKeepScreenOn(on: Boolean) = services.keepScreenOn(on)

    /** The session log of this run as it is now (for tests). */
    fun sessionLog(): String = services.logText.toString()

    /** The stored session logs as last read, newest first; null before the first read. */
    var storedLogs: List<StoredLogInfo>? by mutableStateOf(null)
        private set

    /** Reads the stored session logs again, off the main thread (this run's written just before). */
    suspend fun refreshLogs() {
        storedLogs = withContext(storage) { services.storedLogs() }
    }

    /** The log the preview shows and the file saves: exactly the same text. */
    var previewLog: LogPreview? by mutableStateOf(null)
        private set
    private var opening: Job? = null

    /**
     * Opens stored log [number] for its preview, read off the main thread; then [opened], which says whether
     * the preview is shown (if not, it goes). A log opened before this one finished opening is dropped.
     */
    fun openLog(
        number: Int,
        opened: () -> Boolean,
    ) {
        opening?.cancel()
        opening =
            scope.launch {
                previewLog =
                    withContext(storage) {
                        val text = services.readStoredLog(number)
                        LogPreview(number, text, SessionSummary.of(text))
                    }
                if (!opened()) previewLog = null
            }
    }

    /** The preview of [preview] closed: it goes, unless another one took its place. */
    fun closeLog(preview: LogPreview?) {
        if (preview != null && previewLog === preview) previewLog = null
    }

    // The app is behind another one: nothing connects until it comes back.
    private var away = false

    /** The compatibility check of this visit to it: running, or its report; null before it starts. */
    var compatibility: CompatibilityUiState? by mutableStateOf(null)
        private set
    private var compatibilityJob: Job? = null

    // A compatibility check holds the connection: nothing else starts a run until it is over.
    private var checkRunning = false

    /** The compatibility check needs a bike: the saved one, or in demo mode the simulated one. */
    val canCheckCompatibility: Boolean get() = if (demo) demoConnection != null else saved.bike != null

    /**
     * Runs the read-only compatibility check on the saved bike (in demo mode, the simulated one): the
     * connection on screen ends for it, and comes back once the check is over. The check writes only the
     * identity read; "keep this speed" doesn't act on it.
     */
    fun runCompatibilityCheck() {
        val connection = (if (demo) demoConnection else real()) ?: return
        val bike = (if (demo) connection.demoBikes.firstOrNull()?.address else saved.bike) ?: return
        if (checkRunning) return
        checkRunning = true
        stopScan()
        // The dashboard's run ends: its taps, warnings and state don't carry over into the check.
        observer?.cancel()
        newRun()
        val demoCheck = demo
        compatibility = CompatibilityUiState(progress = null, report = null, demo = demoCheck)
        compatibilityJob =
            scope
                .launch {
                    val report =
                        try {
                            CompatibilityCheck(connection.session).run(bike) { progress ->
                                compatibility = CompatibilityUiState(progress, report = null, demo = demoCheck)
                            }
                        } finally {
                            checkRunning = false
                        }
                    compatibility = CompatibilityUiState(progress = null, report, demo = demoCheck)
                    compatibilityJob = null
                    reconnectAfterCheck()
                }.also { job ->
                    // Also when the job is cancelled before it starts.
                    job.invokeOnCompletion { checkRunning = false }
                }
    }

    /** The rider left the compatibility check: a running one stops, and the bike connects as before. */
    fun closeCompatibility() {
        val job = compatibilityJob
        compatibilityJob = null
        compatibility = null
        if (job == null) return
        scope.launch {
            // Its end stops the session: only then may the connection start again.
            job.cancelAndJoin()
            reconnectAfterCheck()
        }
    }

    private fun reconnectAfterCheck() {
        if (away) return
        if (demo) {
            demoConnection?.let { startOn(it, it.demoBikes.first().address) }
        } else {
            connectSaved()
        }
    }

    /** The date the session log records, which also names its saved files. */
    fun logDate(): LocalDate = services.logDate

    /**
     * The number for the next session-log file of [date], so that saved files don't clash: the file name
     * holds the date and this number, never a time of day.
     */
    fun nextLogNumber(date: LocalDate): Int = services.nextLogNumber(date)

    /** A file of the session log dated [date] took [number]. */
    fun logSaved(
        date: LocalDate,
        number: Int,
    ) = services.logSaved(date, number)

    companion object {
        val SPEEDS: List<Int> = SpeedLimit.entries.map { it.kmh }
        private const val NO_RUN = -1
        private val LOG_SAVE_EVERY = 30.seconds
        private const val LOG_SESSIONS = 5
    }
}

/** One update of what the controller follows. */
private data class Observed(
    val status: SessionStatus,
    val ride: SessionRide,
    val radio: RadioState,
    val limit: LimitState,
    val assist: AssistState,
)

/** A speed warning waiting for the rider: [kmh], and why it shows. */
data class PendingWarning(
    val kmh: Int,
    val purpose: WarningPurpose,
)

/** Consent the rider must give before a command, as a screen to open. */
enum class ConsentNeeded { RISK_ACCEPTANCE, UNVERIFIED_ACKNOWLEDGEMENT, FIRMWARE_CAVEAT }

/** A stored session log opened for its preview: the [text] the file will hold, and its [summary]. */
class LogPreview(
    val number: Int,
    val text: String,
    val summary: SessionSummary,
    /** The log asked for was gone: this is this run's instead. */
    val replaced: Boolean = false,
) {
    /** The date the log records (YYYY-MM-DD), which names its file. */
    val date: LocalDate? get() = summary.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The size of the file it makes, in bytes. */
    val fileSize: Long = text.toByteArray(Charsets.UTF_8).size.toLong()
}

/** A compatibility check on screen: where it is ([progress]) while it runs, then its [report]. */
data class CompatibilityUiState(
    val progress: CompatibilityProgress?,
    val report: CompatibilityReport?,
    /** A check of demo mode's simulated bike: nothing of it is reported or shared. */
    val demo: Boolean = false,
) {
    val running: Boolean get() = report == null
}
