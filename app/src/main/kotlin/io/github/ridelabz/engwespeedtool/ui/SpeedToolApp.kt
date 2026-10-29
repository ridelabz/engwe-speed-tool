package io.github.ridelabz.engwespeedtool.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ridelabz.engwespeedtool.BuildConfig
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.TestBuildLabel
import io.github.ridelabz.engwespeedtool.bike.BikeServices
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.session.CheckedField
import io.github.ridelabz.engwespeedtool.session.CompatibilityOutcome
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import io.github.ridelabz.engwespeedtool.session.FieldResult
import io.github.ridelabz.engwespeedtool.session.ProtocolCheck
import io.github.ridelabz.engwespeedtool.session.StopReason
import io.github.ridelabz.engwespeedtool.ui.about.AboutScreen
import io.github.ridelabz.engwespeedtool.ui.about.CompatibilityRow
import io.github.ridelabz.engwespeedtool.ui.about.LicencesScreen
import io.github.ridelabz.engwespeedtool.ui.components.DemoBanner
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardActions
import io.github.ridelabz.engwespeedtool.ui.dashboard.DashboardScreen
import io.github.ridelabz.engwespeedtool.ui.dashboard.MenuEntry
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedWarningSheet
import io.github.ridelabz.engwespeedtool.ui.diagnostics.CompatibilityCheckScreen
import io.github.ridelabz.engwespeedtool.ui.diagnostics.DiagnosticsScreen
import io.github.ridelabz.engwespeedtool.ui.diagnostics.LogPreviewScreen
import io.github.ridelabz.engwespeedtool.ui.legal.FirmwareCaveatScreen
import io.github.ridelabz.engwespeedtool.ui.legal.LegalScreen
import io.github.ridelabz.engwespeedtool.ui.nav.Navigator
import io.github.ridelabz.engwespeedtool.ui.nav.Route
import io.github.ridelabz.engwespeedtool.ui.onboarding.BluetoothScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.CheckResult
import io.github.ridelabz.engwespeedtool.ui.onboarding.ConnectCheckScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.DoneScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.FindBikeScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.PermissionUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.RiskAcceptanceScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.UnverifiedAcknowledgementScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.WelcomeScreen
import io.github.ridelabz.engwespeedtool.ui.report.CheckText
import io.github.ridelabz.engwespeedtool.ui.report.CheckWords
import io.github.ridelabz.engwespeedtool.ui.report.LogSaved
import io.github.ridelabz.engwespeedtool.ui.report.ReportAnswers
import io.github.ridelabz.engwespeedtool.ui.report.ReportLabels
import io.github.ridelabz.engwespeedtool.ui.report.ReportPreview
import io.github.ridelabz.engwespeedtool.ui.report.ReportSending
import io.github.ridelabz.engwespeedtool.ui.report.ReportSheet
import io.github.ridelabz.engwespeedtool.ui.report.ReportType
import io.github.ridelabz.engwespeedtool.ui.report.RideTestRestart
import io.github.ridelabz.engwespeedtool.ui.report.RideTestSheet
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsActions
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsScreen
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import io.github.ridelabz.engwespeedtool.session.CheckResult as ProtocolCheckResult

/** Keeps the app's state across configuration changes, such as switching to dark mode. */
class AppViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val controller =
        AppController(
            viewModelScope,
            BikeServices.get(application),
            bikeLabel = application.getString(R.string.find_bike_name),
            downloads = MediaStoreDownloads(application.contentResolver),
        )
}

/** The app. */
@Composable
fun SpeedToolApp() {
    val controller = viewModel<AppViewModel>().controller
    val navigator = rememberSaveable(saver = Navigator.Saver) { Navigator(controller.startRoutes()) }
    SpeedToolApp(controller = controller, navigator = navigator)
}

/** Android's Bluetooth permissions ("Nearby devices"). */
private val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private fun Context.bluetoothAllowed() =
    BLUETOOTH_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Whether Android would explain a permission request: only after a refusal it may still ask about again. */
private fun Activity.bluetoothRationale() = BLUETOOTH_PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }

/** What screens can ask of Android: permissions, switching Bluetooth on, the app's settings, a file. */
private class SystemActions(
    val requestBluetooth: () -> Unit,
    val turnOnBluetooth: () -> Unit,
    val openAppSettings: () -> Unit,
    /** Android's language settings for the app (Android 13 on); null before. */
    val openLanguageSettings: (() -> Unit)?,
    /** Asks for the permission, or opens the app's settings where Android won't ask again. */
    val allowBluetooth: () -> Unit,
    /** Saves the log open in its preview: in Downloads, or where the rider picks if Downloads can't take it. */
    val saveLog: () -> Unit,
    /** How the last save from a log's preview ended; null before the first. */
    val logSaved: MutableState<LogSaved?>,
    /** Sends the open report: its log (as the sheet showed it) saved in Downloads, then the form in the browser. */
    val sendReport: (ReportPreview) -> Unit,
    /** Opens the sent report's form again. */
    val openReportAgain: (ReportPreview) -> Unit,
    /** A save from a log's preview is on its way. */
    val logSaving: MutableState<Boolean>,
)

/** Leaves demo mode and goes back to the screen it was started from. */
private fun exitDemo(
    controller: AppController,
    navigator: Navigator,
) {
    controller.leaveDemo()
    val before = navigator.routes.takeWhile { it != Route.DASHBOARD }
    if (before.isEmpty()) navigator.reset(Route.WELCOME) else navigator.reset(*before.toTypedArray())
}

@Composable
fun SpeedToolApp(
    controller: AppController,
    navigator: Navigator,
) {
    // Each screen keeps its saved state (ticked boxes, scroll position) while it is on the back stack.
    val screenStates = rememberSaveableStateHolder()
    val stack = navigator.routes
    LaunchedEffect(stack) {
        (Route.entries - stack.toSet()).forEach { screenStates.removeState(it.name) }
        // Demo mode lasts while its dashboard is open.
        if (controller.demo && Route.DASHBOARD !in stack) controller.leaveDemo()
    }
    val context = LocalContext.current
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        // The rider may have allowed Bluetooth in the app's settings meanwhile, or refused it for good earlier.
        val allowed = context.bluetoothAllowed()
        if (controller.onPermissionChecked(allowed, activity?.bluetoothRationale() ?: true) &&
            navigator.current == Route.BLUETOOTH
        ) {
            navigator.push(Route.FIND_BIKE)
        }
        controller.onForeground()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // A rotation or a switch to dark mode isn't the rider leaving: the connection stays.
        if (activity?.isChangingConfigurations != true) controller.onBackground()
    }
    val system = rememberSystemActions(controller, navigator)
    // A command needs consent the rider hasn't given (for the current text): open where it is given.
    val consent = controller.consentNeeded
    LaunchedEffect(consent) {
        when (consent) {
            ConsentNeeded.RISK_ACCEPTANCE -> {
                screenStates.removeState(Route.RISK_ACCEPTANCE.name)
                navigator.push(Route.RISK_ACCEPTANCE)
            }

            ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT -> {
                navigator.push(Route.UNVERIFIED_ACKNOWLEDGEMENT)
            }

            // Shown at setup; only if it was skipped there.
            ConsentNeeded.FIRMWARE_CAVEAT -> {
                navigator.push(Route.FIRMWARE_CAVEAT)
            }

            null -> {}
        }
        if (consent != null) controller.consentOpened()
    }
    SpeedToolTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (controller.demo) DemoBanner(onExit = { exitDemo(controller, navigator) })
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // The demo banner takes the top inset.
                            .then(
                                if (controller.demo) {
                                    Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                                } else {
                                    Modifier
                                },
                            ),
                ) {
                    AnimatedContent(
                        targetState = navigator.current,
                        // Material's fade-through: the old screen fades out quickly, then the new one fades and
                        // scales in, so the two never show on top of each other.
                        transitionSpec = {
                            (
                                fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS)) +
                                    scaleIn(
                                        tween(FADE_IN_MS, delayMillis = FADE_OUT_MS),
                                        initialScale = FADE_THROUGH_SCALE,
                                    )
                            ) togetherWith fadeOut(tween(FADE_OUT_MS))
                        },
                        label = "",
                    ) { route ->
                        screenStates.SaveableStateProvider(
                            route.name,
                        ) {
                            RouteContent(
                                route,
                                controller,
                                navigator,
                                system,
                                forget = { screenStates.removeState(it.name) },
                            )
                        }
                    }
                    controller.report?.let { request ->
                        ReportSheet(
                            preview = reportPreview(controller),
                            request = request,
                            // Every line of it shows in the sheet before it is saved.
                            log = controller.reportLog,
                            onDismiss = controller::closeReport,
                            onSend = system.sendReport,
                            onOpenAgain = system.openReportAgain,
                        )
                    }
                    // On the dashboard, after an unverified bike confirmed a speed in an earlier run: how it worked.
                    controller.rideTest?.let { test ->
                        // Never over the speed buttons while riding.
                        val moving = (controller.dashboard.ride?.speedKmh ?: 0.0) >= 1.0
                        if (navigator.current == Route.DASHBOARD &&
                            !controller.demo &&
                            !moving &&
                            controller.report == null &&
                            controller.warning == null
                        ) {
                            RideTestSheet(
                                test = test,
                                onDone = controller::rideTestDone,
                                onLater = controller::rideTestLater,
                            )
                        }
                    }
                    // Before a speed above 25 is sent: the legal warning.
                    controller.warning?.let { open ->
                        SpeedWarningSheet(
                            kmh = open.kmh,
                            purpose = open.purpose,
                            onConfirm = controller::confirmWarning,
                            onCancel = controller::cancelWarning,
                            restartsTrusted = controller.dashboard.speedLimit.restartsTrusted,
                        )
                    }
                }
            }
        }
    }
}

private const val FADE_OUT_MS = 90
private const val FADE_IN_MS = 210
private const val FADE_THROUGH_SCALE = 0.92f

@Composable
private fun rememberSystemActions(
    controller: AppController,
    navigator: Navigator,
): SystemActions {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val requestPermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.isNotEmpty() && result.values.all { it }
            // After a refusal, Android offers no rationale once it won't ask again.
            val canAskAgain = activity?.bluetoothRationale() ?: true
            if (controller.onPermissionResult(granted, canAskAgain)) resumeAfterBluetooth(controller, navigator)
        }
    val enableBluetooth =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            controller.refreshBluetooth()
            resumeAfterBluetooth(controller, navigator)
        }
    val logSaved = remember { mutableStateOf<LogSaved?>(null) }
    // A save from a log's preview is on its way: the button waits.
    val logSaving = remember { mutableStateOf(false) }
    val resources = LocalResources.current
    // The date (epoch day) and number of the file a preview saves through the picker, counted once its name is taken.
    val logDay = rememberSaveable { mutableLongStateOf(NO_LOG_FILE) }
    val logNumber = rememberSaveable { mutableIntStateOf(0) }
    // The picker is open: its answer comes even if the screen is recreated meanwhile.
    val pickerOpen = rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The log a preview saves through the picker, taken when Save was tapped: the file holds exactly that text.
    val savingLog = remember { mutableStateOf<LogPreview?>(null) }
    val pickLogFile =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            pickerOpen.value = false
            if (controller.report?.sending?.stage == ReportSending.Stage.PICK) {
                // The report's log, as the sheet showed it; the controller writes it and opens the form next.
                val text = controller.reportLog?.text
                // The application's resolver: the write may outlive this screen.
                val resolver = context.applicationContext.contentResolver
                controller.reportLogPicked(uri?.let { picked -> { writeLog(resolver, picked, text) } })
            } else {
                val text = savingLog.value?.text
                val day = logDay.longValue
                logDay.longValue = NO_LOG_FILE
                savingLog.value = null
                scope.launch {
                    val (saved, removed) =
                        if (uri == null) {
                            false to true
                        } else {
                            withContext(Dispatchers.IO) { writeLog(context.contentResolver, uri, text) }
                        }
                    // Unless the file is gone again its name is taken: count it, so the next one doesn't clash.
                    if (!removed && day != NO_LOG_FILE) {
                        controller.logSaved(LocalDate.ofEpochDay(day), logNumber.intValue)
                    }
                    logSaving.value = false
                    if (uri != null) logSaved.value = if (saved) LogSaved.Chosen else LogSaved.NotSaved
                }
            }
        }

    /** Opens the file picker with [name]; false if the phone has none. */
    fun pick(name: String): Boolean {
        val launched = runCatching { pickLogFile.launch(name) }.isSuccess
        pickerOpen.value = launched
        return launched
    }

    /** Opens a report's issue form, filled in, in the browser; false if nothing could open it. */
    fun openForm(report: ReportPreview): Boolean =
        report.link(resources.getString(R.string.report_repository))?.url?.let { openInBrowser(context, it) } == true

    // The report's steps that need the screen: the picker, then the browser. They run again after the screen is
    // recreated, unless the picker is still open.
    val sending = controller.report?.sending
    LaunchedEffect(sending?.stage) {
        when (sending?.stage) {
            ReportSending.Stage.PICK -> {
                val name = sending.file?.name
                // Only while the report still waits for it (the live stage), and not twice.
                val waiting = controller.report?.sending?.stage == ReportSending.Stage.PICK
                if (waiting && !pickerOpen.value && (name == null || !pick(name))) controller.reportLogPicked(null)
            }

            ReportSending.Stage.OPEN -> {
                controller.reportOpened(openForm(sending.preview))
            }

            ReportSending.Stage.SAVING, ReportSending.Stage.WRITING, null -> {}
        }
    }

    /**
     * Saves [log], from its preview, in Downloads under its date and the next number for that date (no time of
     * day); if Downloads can't take it, the picker opens with the same name.
     */
    fun saveLogFile(log: LogPreview) {
        if (logSaving.value) return
        logSaving.value = true
        val date = log.date ?: controller.logDate()
        val number = controller.nextLogNumber(date)
        val name = logFileName(resources, date, number)
        scope.launch {
            val saved = controller.downloads?.let { withContext(Dispatchers.IO) { it.save(name, log.text) } }
            if (saved != null) {
                controller.logSaved(date, number)
                logSaving.value = false
                logSaved.value = LogSaved.InDownloads(saved)
            } else {
                savingLog.value = log
                logDay.longValue = date.toEpochDay()
                logNumber.intValue = number
                if (!pick(name)) {
                    logSaving.value = false
                    logSaved.value = LogSaved.NotSaved
                }
            }
        }
    }
    return remember(controller, navigator, context, resources) {
        val request = { requestPermissions.launch(BLUETOOTH_PERMISSIONS) }
        val openSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
        SystemActions(
            requestBluetooth = request,
            turnOnBluetooth = {
                val intent = controller.enableBluetoothIntent()
                when {
                    !context.bluetoothAllowed() -> request()
                    intent != null -> enableBluetooth.launch(intent)
                }
            },
            openAppSettings = openSettings,
            openLanguageSettings =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    {
                        // Some phones have no such screen: the app's details have a language entry too.
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_LOCALE_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        } catch (e: ActivityNotFoundException) {
                            openSettings()
                        }
                    }
                } else {
                    null
                },
            allowBluetooth = {
                if (controller.permission == PermissionUiState.DENIED_PERMANENTLY) openSettings() else request()
            },
            saveLog = {
                logSaved.value = null
                controller.previewLog?.let { saveLogFile(it) }
            },
            logSaved = logSaved,
            sendReport = { preview ->
                controller.sendReport(preview) { date, number -> logFileName(resources, date, number) }
            },
            openReportAgain = { preview -> controller.reportOpenedAgain(openForm(preview)) },
            logSaving = logSaving,
        )
    }
}

/** No session-log file is being saved. */
private const val NO_LOG_FILE = Long.MIN_VALUE

/**
 * Writes [text] to the file at [uri], exactly: the content only, never the stored file itself. A file cut
 * short is no use: it goes. So does the empty file the picker made when there is no text any more (Android
 * ended the app while the picker was open). A file that couldn't be opened is left alone. Returns whether
 * it was saved, and whether it went. Blocking I/O.
 */
private fun writeLog(
    resolver: ContentResolver,
    uri: Uri,
    text: String?,
): Pair<Boolean, Boolean> {
    if (text == null) {
        val empty = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize == 0L } }.getOrNull()
        return false to (empty == true && delete(resolver, uri))
    }
    // "wt": an existing file chosen for overwriting keeps nothing of its old content.
    val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: return false to false
    val saved = runCatching { out.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
    return saved to (!saved && delete(resolver, uri))
}

private fun delete(
    resolver: ContentResolver,
    uri: Uri,
): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

/**
 * The name to suggest for a saved session log: its [date] and [number], in ASCII digits whatever the
 * phone's language (formatting a number would use the language's own digits, which would tell it).
 */
internal fun logFileName(
    resources: Resources,
    date: LocalDate,
    number: Int,
): String = resources.getString(R.string.diagnostics_log_file_name, date.toString(), number.toString())

/** Bluetooth became usable: go on with what the current screen was waiting for. */
private fun resumeAfterBluetooth(
    controller: AppController,
    navigator: Navigator,
) {
    when (navigator.current) {
        Route.BLUETOOTH -> navigator.push(Route.FIND_BIKE)
        Route.FIND_BIKE -> controller.startScan()
        Route.CONNECT_CHECK -> controller.retryCheck()
        else -> Unit
    }
}

@Composable
private fun RouteContent(
    route: Route,
    controller: AppController,
    navigator: Navigator,
    system: SystemActions,
    /** Drops what a screen kept (ticks, scroll position) before it opens again. */
    forget: (Route) -> Unit,
) {
    val back: () -> Unit = { navigator.pop() }
    when (route) {
        Route.WELCOME -> {
            WelcomeScreen(onStart = { navigator.push(Route.RISK_ACCEPTANCE) })
        }

        Route.RISK_ACCEPTANCE -> {
            RiskAcceptanceScreen(
                // The screen records the acceptance itself.
                onAccept = {
                    when {
                        // Came from a check that stopped for want of it: check again.
                        Route.CONNECT_CHECK in navigator.routes -> {
                            navigator.pop()
                            controller.retryCheck()
                        }

                        // A new text accepted for the saved bike: back to it, and connect; the caveat of the new
                        // text once, over it.
                        controller.hasSavedBike -> {
                            navigator.reset(Route.DASHBOARD)
                            if (controller.caveatNeeded) navigator.push(Route.FIRMWARE_CAVEAT)
                            controller.connectToSavedBike()
                        }

                        else -> {
                            navigator.push(Route.BLUETOOTH)
                        }
                    }
                },
                onBack = back,
                acceptance = controller.riskAcceptance,
                changed = controller.acceptanceOutdated,
            )
        }

        Route.BLUETOOTH -> {
            BluetoothScreen(
                onContinue = system.requestBluetooth,
                onBack = back,
                permission = controller.permission,
                onOpenSettings = system.openAppSettings,
            )
        }

        Route.FIND_BIKE -> {
            // Scans while this screen shows and the app is in front.
            LifecycleStartEffect(Unit) {
                controller.startScan()
                onStopOrDispose { controller.stopScan() }
            }
            FindBikeScreen(
                state = controller.findBike,
                onBikeSelected = { bike ->
                    controller.startCheck(bike)
                    navigator.push(Route.CONNECT_CHECK)
                },
                onScanAgain = controller::startScan,
                onTurnOnBluetooth = system.turnOnBluetooth,
                onTryDemo = {
                    controller.startDemo()
                    navigator.push(Route.DASHBOARD)
                },
                onBack = back,
                onAllowBluetooth = system.allowBluetooth,
            )
        }

        Route.CONNECT_CHECK -> {
            ConnectCheckScreen(
                state = controller.check,
                onContinue = { result ->
                    // The bike is saved once it is checked (and acknowledged): back doesn't undo that.
                    when {
                        !controller.confirmBike(result) -> Unit
                        result is CheckResult.Unverified -> navigator.push(Route.UNVERIFIED_ACKNOWLEDGEMENT)
                        controller.caveatNeeded -> navigator.push(Route.FIRMWARE_CAVEAT)
                        else -> navigator.reset(Route.DONE)
                    }
                },
                onRetry = controller::retryCheck,
                onReport = { controller.openReport(ReportType.COMPATIBILITY) },
                onDiagnostics = { navigator.push(Route.DIAGNOSTICS) },
                onBack = {
                    controller.cancelCheck()
                    back()
                },
                onScanAgain = {
                    controller.cancelCheck()
                    back()
                },
                onTurnOnBluetooth = system.turnOnBluetooth,
                onAllowBluetooth = system.allowBluetooth,
                onAccept = {
                    // A fresh screen: nothing ticked from an earlier visit.
                    forget(Route.RISK_ACCEPTANCE)
                    navigator.push(Route.RISK_ACCEPTANCE)
                },
                permissionInSettings = controller.permission == PermissionUiState.DENIED_PERMANENTLY,
            )
        }

        Route.FIRMWARE_CAVEAT -> {
            FirmwareCaveatScreen(
                // Recorded by the screen.
                caveat = controller.setupCaveat,
                onContinue = {
                    // At setup, setup ends; over the dashboard, back to it.
                    if (Route.DASHBOARD in navigator.routes) navigator.pop() else navigator.reset(Route.DONE)
                },
                onBack = back,
            )
        }

        Route.UNVERIFIED_ACKNOWLEDGEMENT -> {
            val result = controller.check.result
            val fromDashboard = controller.acknowledgingSavedBike
            // The check ran again (the app was behind another one): its new result shows on the check
            // screen, not under this screen's wording.
            LaunchedEffect(result) {
                if (!fromDashboard &&
                    result !is CheckResult.Unverified &&
                    navigator.current == Route.UNVERIFIED_ACKNOWLEDGEMENT
                ) {
                    navigator.pop()
                }
            }
            UnverifiedAcknowledgementScreen(
                // Recorded by the screen, only while the bike still shows as unverified.
                acknowledgement = controller::unverifiedAcknowledgement,
                onAcknowledged = { reportResults ->
                    controller.unverifiedAcknowledged(reportResults)
                    when {
                        fromDashboard -> navigator.pop()
                        controller.caveatNeeded -> navigator.push(Route.FIRMWARE_CAVEAT)
                        else -> navigator.reset(Route.DONE)
                    }
                },
                onNoLongerApplies = {
                    controller.acknowledgementDismissed()
                    navigator.pop()
                },
                onBack = {
                    controller.acknowledgementDismissed()
                    back()
                },
            )
        }

        Route.DONE -> {
            DoneScreen(onOpen = { navigator.reset(Route.DASHBOARD) })
        }

        Route.DASHBOARD -> {
            val actions =
                remember(controller, navigator, system) {
                    DashboardActions(
                        // One command per tap; the session decides, with the rider's consent, whether to send.
                        onSpeed = controller::applySpeed,
                        onKeepSpeed = controller::setKeepSpeed,
                        onLights = controller::setLights,
                        onAssist = controller::setAssistLevel,
                        onKeepAssist = controller::setKeepAssist,
                        onPower = controller::setPower,
                        onReport = {
                            controller.openReport(if (controller.dashboard.verified) null else ReportType.COMPATIBILITY)
                        },
                        onReportCompatibility = { controller.openReport(ReportType.COMPATIBILITY) },
                        onMenu = { entry ->
                            val target = menuRoute(entry)
                            if (target != null) navigator.push(target) else controller.openReport(null)
                        },
                        onTurnOnBluetooth = system.turnOnBluetooth,
                        onAllowBluetooth = system.allowBluetooth,
                        onRetry = controller::retryConnection,
                    )
                }
            val demoBack = remember(controller, navigator) { { exitDemo(controller, navigator) } }
            // "Keep the screen on": only while the dashboard shows.
            val view = LocalView.current
            val keepOn = controller.keepScreenOn
            DisposableEffect(view, keepOn) {
                view.keepScreenOn = keepOn
                onDispose { view.keepScreenOn = false }
            }
            DashboardScreen(
                state = controller.dashboard,
                actions = if (controller.demo) actions.copy(onBack = demoBack) else actions,
            )
        }

        Route.DIAGNOSTICS -> {
            // Read again each time the screen shows, off the main thread; the last list shows meanwhile. A
            // save message here is about a log whose preview Android closed while the picker was open.
            LaunchedEffect(Unit) { controller.refreshLogs() }
            DisposableEffect(Unit) { onDispose { system.logSaved.value = null } }
            DiagnosticsScreen(
                onReport = { controller.openReport(ReportType.PROBLEM) },
                onBack = back,
                logs = controller.storedLogs,
                onOpenLog = { number ->
                    controller.openLog(number) {
                        (navigator.current == Route.DIAGNOSTICS).also { if (it) navigator.push(Route.LOG_PREVIEW) }
                    }
                },
                logNotSaved = system.logSaved.value == LogSaved.NotSaved,
                onRunCheck =
                    if (controller.canCheckCompatibility) {
                        {
                            controller.runCompatibilityCheck()
                            navigator.push(Route.COMPATIBILITY_CHECK)
                        }
                    } else {
                        null
                    },
            )
        }

        Route.LOG_PREVIEW -> {
            val preview = controller.previewLog
            // The save message belongs to this visit; the preview goes with it.
            DisposableEffect(preview) {
                onDispose {
                    system.logSaved.value = null
                    controller.closeLog(preview)
                }
            }
            if (preview == null) {
                LaunchedEffect(Unit) { if (navigator.current == Route.LOG_PREVIEW) navigator.pop() }
            } else {
                LogPreviewScreen(
                    preview = preview,
                    onSave = system.saveLog,
                    saved = system.logSaved.value,
                    saving = system.logSaving.value,
                    // The message goes before Diagnostics shows again, which also shows a failed save.
                    onBack = {
                        system.logSaved.value = null
                        back()
                    },
                )
            }
        }

        Route.COMPATIBILITY_CHECK -> {
            val state = controller.compatibility
            if (state == null) {
                LaunchedEffect(Unit) { if (navigator.current == Route.COMPATIBILITY_CHECK) navigator.pop() }
            } else {
                val close = {
                    controller.closeCompatibility()
                    back()
                }
                val context = LocalContext.current
                val resources = LocalResources.current
                val shareText =
                    remember(state.report, resources) { state.report?.let { checkShareText(resources, it) } }
                CompatibilityCheckScreen(
                    state = state,
                    shareText = shareText,
                    onRunAgain = controller::runCompatibilityCheck,
                    onReport = controller::reportCheck,
                    onShare = { shareAsText(context, it, resources.getString(R.string.compat_share_chooser)) },
                    onBack = close,
                )
            }
        }

        Route.SETTINGS -> {
            SettingsScreen(
                state = controller.settings,
                actions =
                    SettingsActions(
                        onRename = controller::rename,
                        onForget = { controller.forgetBike(navigator) },
                        onKeepSpeed = controller::setKeepSpeed,
                        onDeleteLogs = controller::deleteStoredLogs,
                        onLanguage = system.openLanguageSettings,
                        onKeepScreenOn = controller::setKeepScreenOn,
                        onResetWarnings = controller::resetWarnings,
                        onLegal = { navigator.push(Route.LEGAL) },
                    ),
                onBack = back,
            )
        }

        Route.LEGAL -> {
            LegalScreen(acceptance = controller.acceptance, onBack = back)
        }

        Route.ABOUT -> {
            val context = LocalContext.current
            val repository = stringResource(R.string.report_repository)
            // The table comes from the bike profiles the app carries, then every other model.
            val rows =
                controller.bundledProfiles.map { profile ->
                    CompatibilityRow(
                        model = profile.displayName,
                        detail =
                            when {
                                profile.testedFirmware.isNotEmpty() -> {
                                    stringResource(
                                        R.string.about_tested_firmware,
                                        profile.testedFirmware.joinToString(", "),
                                    )
                                }

                                profile.verified -> {
                                    stringResource(R.string.about_verified)
                                }

                                else -> {
                                    stringResource(R.string.about_not_tested)
                                }
                            },
                        verified = profile.verified,
                    )
                } +
                    CompatibilityRow(
                        model = stringResource(R.string.about_other_models),
                        detail = stringResource(R.string.about_not_tested),
                        verified = false,
                    )
            AboutScreen(
                version = BuildConfig.VERSION_NAME,
                compatibility = rows,
                onBack = back,
                onSource = { openInBrowser(context, repository) },
                onLicences = { navigator.push(Route.LICENCES) },
            )
        }

        Route.LICENCES -> {
            LicencesScreen(onBack = back)
        }
    }
}

/** What a report would share, with this phone's versions and model. */
@Composable
private fun reportPreview(controller: AppController): (ReportType, ReportAnswers) -> ReportPreview {
    val resources = LocalResources.current
    val labels =
        ReportLabels(
            app = stringResource(R.string.report_item_app),
            android = stringResource(R.string.report_item_android_phone),
            model = stringResource(R.string.report_item_model),
            verification = stringResource(R.string.report_item_verification),
            build = stringResource(R.string.report_item_build),
            checks = stringResource(R.string.report_item_checks),
            results = stringResource(R.string.report_item_results),
            errors = stringResource(R.string.report_item_errors),
            stopSpeed = { resources.getString(R.string.report_item_stop_speed_at, it) },
            iotVersion = stringResource(R.string.report_item_iot_version),
            state = stringResource(R.string.report_item_state),
            // The values go to the issue tracker in English, with plain digits, whatever the app's language.
            verified = resources.getString(R.string.report_value_verified),
            unverified = resources.getString(R.string.report_value_unverified),
            checksPassed = resources.getString(R.string.report_value_checks_passed),
            confirmed = { english(resources, R.string.report_value_confirmed, it) },
            notConfirmed = { english(resources, R.string.report_value_not_confirmed, it) },
            errorCount = {
                when (it) {
                    0 -> resources.getString(R.string.report_value_no_errors)
                    1 -> resources.getString(R.string.report_value_one_error)
                    else -> english(resources, R.string.report_value_errors, it)
                }
            },
            kmh = { english(resources, R.string.report_value_kmh, it) },
            assistResults = stringResource(R.string.report_item_assist_results),
            assistConfirmed = { english(resources, R.string.report_value_assist_confirmed, it) },
            assistNotConfirmed = { english(resources, R.string.report_value_assist_not_confirmed, it) },
            backTo25 = stringResource(R.string.report_item_back_to_25),
            check = { CheckText.of(it, checkWords(resources)) },
            restart = {
                resources.getString(
                    when (it) {
                        RideTestRestart.YES -> R.string.report_value_restart_yes
                        RideTestRestart.NO -> R.string.report_value_restart_no
                        RideTestRestart.NOT_CHECKED -> R.string.report_value_restart_not_checked
                    },
                )
            },
            buildName =
                when (val build = TestBuildLabel.current) {
                    is TestBuildLabel.PullRequest -> {
                        english(
                            resources,
                            R.string.report_value_pull_request,
                            build.number,
                        )
                    }

                    TestBuildLabel.Local -> {
                        english(
                            resources,
                            R.string.report_value_local_build,
                            BuildConfig.VERSION_NAME,
                        )
                    }

                    TestBuildLabel.None -> {
                        BuildConfig.VERSION_NAME
                    }
                },
        )
    return { type, answers ->
        controller.reportPreview(type, answers, BuildConfig.VERSION_NAME, labels)
    }
}

/** A report's value: [id]'s English text with [args], in plain digits. */
private fun english(
    resources: Resources,
    @StringRes id: Int,
    vararg args: Any,
): String = String.format(Locale.ROOT, resources.getString(id), *args)

/** The English words of a compatibility check's summary, for a report's form and a share. */
internal fun checkWords(resources: Resources): CheckWords =
    CheckWords(
        outcome = { outcome, frames, seconds ->
            when (outcome) {
                CompatibilityOutcome.Checked -> {
                    english(resources, R.string.report_check_checked, frames, seconds ?: 0)
                }

                CompatibilityOutcome.LinkLost -> {
                    english(resources, R.string.report_check_link_lost, frames, seconds ?: 0)
                }

                is CompatibilityOutcome.Incompatible -> {
                    resources.getString(R.string.report_check_incompatible)
                }

                CompatibilityOutcome.Interrupted -> {
                    resources.getString(R.string.report_check_interrupted)
                }

                is CompatibilityOutcome.NotConnected -> {
                    resources.getString(
                        when (outcome.reason) {
                            StopReason.NOT_FOUND -> R.string.report_check_not_found
                            StopReason.CONNECT_FAILED -> R.string.report_check_connect_failed
                            StopReason.LINK_LOST -> R.string.report_check_lost_early
                            StopReason.BLUETOOTH_UNAVAILABLE -> R.string.report_check_bluetooth
                            StopReason.NOT_ACCEPTED -> R.string.report_check_not_accepted
                            null -> R.string.report_check_too_long
                        },
                    )
                }
            }
        },
        check = {
            resources.getString(
                when (it) {
                    ProtocolCheck.SERVICE -> R.string.report_check_service
                    ProtocolCheck.NOTIFY -> R.string.report_check_notify
                    ProtocolCheck.ACKNOWLEDGED_WRITE -> R.string.report_check_write
                    ProtocolCheck.STATUS -> R.string.report_check_status
                    ProtocolCheck.IDENTITY -> R.string.report_check_identity
                },
            )
        },
        field = {
            resources.getString(
                when (it) {
                    CheckedField.SPEED -> R.string.report_check_speed
                    CheckedField.MAX_SPEED -> R.string.report_check_max_speed
                    CheckedField.ODOMETER -> R.string.report_check_odometer
                    CheckedField.ASSIST_LEVEL -> R.string.report_check_assist
                    CheckedField.BATTERY -> R.string.report_check_battery
                    CheckedField.LIGHTS -> R.string.report_check_lights
                    CheckedField.POWER -> R.string.report_check_power
                    CheckedField.VOLTAGE -> R.string.report_check_voltage
                    CheckedField.RIDE_TIME -> R.string.report_check_ride_time
                },
            )
        },
        checks = { result, names ->
            resources.getString(
                when (result) {
                    ProtocolCheckResult.PASSED -> R.string.report_check_passed
                    ProtocolCheckResult.FAILED -> R.string.report_check_failed
                    ProtocolCheckResult.NOT_RUN -> R.string.report_check_not_run
                },
                names,
            )
        },
        values = { result, names ->
            resources.getString(
                when (result) {
                    FieldResult.PLAUSIBLE -> R.string.report_check_plausible
                    FieldResult.OUT_OF_RANGE -> R.string.report_check_out_of_range
                    FieldResult.WENT_BACK -> R.string.report_check_went_back
                    FieldResult.NOT_READ -> R.string.report_check_not_read
                },
                names,
            )
        },
        unverified = { resources.getString(R.string.report_check_unverified, it) },
        allValues = resources.getString(R.string.report_check_all_values),
        unreadable = resources.getString(R.string.report_check_unreadable),
    )

/** What sharing a compatibility check's result sends: the app's version, the bike's model and verification, the checks. */
private fun checkShareText(
    resources: Resources,
    report: CompatibilityReport,
): String {
    val classification = report.classification
    return CheckText.share(
        title = resources.getString(R.string.report_share_title, BuildConfig.VERSION_NAME),
        model = classification?.modelIdentity?.let { resources.getString(R.string.report_share_model, it) },
        verification =
            when (classification?.verification) {
                Verification.VERIFIED -> R.string.report_value_verified
                Verification.UNVERIFIED -> R.string.report_value_unverified
                Verification.INCOMPATIBLE, null -> null
            }?.let { resources.getString(R.string.report_share_verification, resources.getString(it)) },
        summary = CheckText.of(report, checkWords(resources)),
    )
}

/**
 * Hands [text] to an app the rider picks, through Android's share sheet, which shows [title] above its preview.
 * The app itself sends nothing. The share sheet is part of Android, so it is always there to pick from.
 */
private fun shareAsText(
    context: Context,
    text: String,
    title: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_TITLE, title)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/**
 * Opens [url] in a browser: the intent is matched as a link any browser opens, so an app that claims
 * github.com links (which may drop the form's filled-in fields) doesn't take it; without a browser, as a
 * plain link. False if nothing could open it. The browser sends it; the app has no internet access.
 */
internal fun openInBrowser(
    context: Context,
    url: String,
): Boolean {
    val link = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val inBrowser =
        Intent(link).apply {
            selector = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
        }
    return runCatching { context.startActivity(inBrowser) }.isSuccess ||
        runCatching { context.startActivity(link) }.isSuccess
}

private fun menuRoute(entry: MenuEntry): Route? =
    when (entry) {
        MenuEntry.REPORT -> null
        MenuEntry.DIAGNOSTICS -> Route.DIAGNOSTICS
        MenuEntry.SETTINGS -> Route.SETTINGS
        MenuEntry.LEGAL -> Route.LEGAL
        MenuEntry.ABOUT -> Route.ABOUT
    }
