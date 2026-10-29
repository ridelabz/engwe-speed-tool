package io.github.ridelabz.engwespeedtool.ui

import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.StoredLogInfo
import io.github.ridelabz.engwespeedtool.protocol.BikeClassifier
import io.github.ridelabz.engwespeedtool.protocol.Lights
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.ProtocolChecks
import io.github.ridelabz.engwespeedtool.protocol.Reading
import io.github.ridelabz.engwespeedtool.protocol.StatusReport
import io.github.ridelabz.engwespeedtool.reporting.SessionSummary
import io.github.ridelabz.engwespeedtool.session.CompatibilityOutcome
import io.github.ridelabz.engwespeedtool.session.CompatibilityProgress
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import io.github.ridelabz.engwespeedtool.session.FakeBike
import io.github.ridelabz.engwespeedtool.session.FieldSanity
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import io.github.ridelabz.engwespeedtool.ui.about.AboutScreen
import io.github.ridelabz.engwespeedtool.ui.about.CompatibilityRow
import io.github.ridelabz.engwespeedtool.ui.about.LicencesScreen
import io.github.ridelabz.engwespeedtool.ui.diagnostics.CompatibilityCheckScreen
import io.github.ridelabz.engwespeedtool.ui.diagnostics.DiagnosticsScreen
import io.github.ridelabz.engwespeedtool.ui.diagnostics.LogPreviewScreen
import io.github.ridelabz.engwespeedtool.ui.legal.Acceptance
import io.github.ridelabz.engwespeedtool.ui.legal.LegalScreen
import io.github.ridelabz.engwespeedtool.ui.report.CheckText
import io.github.ridelabz.engwespeedtool.ui.report.LogSaved
import io.github.ridelabz.engwespeedtool.ui.report.ReportContent
import io.github.ridelabz.engwespeedtool.ui.report.ReportPreview
import io.github.ridelabz.engwespeedtool.ui.report.ReportSent
import io.github.ridelabz.engwespeedtool.ui.report.ReportType
import io.github.ridelabz.engwespeedtool.ui.report.RideTest
import io.github.ridelabz.engwespeedtool.ui.report.RideTestContent
import io.github.ridelabz.engwespeedtool.ui.report.SharedItem
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsActions
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsScreen
import io.github.ridelabz.engwespeedtool.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SecondaryScreenshotTest(
    variant: ScreenVariant,
) {
    @get:Rule
    val screen = ScreenRule(variant)

    private val preview =
        ReportPreview(
            items =
                listOf(
                    SharedItem("App", "0.1.0"),
                    SharedItem("Android", "16"),
                    SharedItem("Phone", "Google Pixel 9"),
                    SharedItem("Bike model", "ENGWE_Omni_ebike1100EU317"),
                    SharedItem("IoT firmware", "218CAN"),
                    SharedItem("Speed changes", "32 km/h confirmed"),
                    SharedItem("Errors", "1 error, with details in the log"),
                ),
            demo = true,
            needsLog = true,
        )

    private fun report(
        name: String,
        step: Int,
    ) {
        screen.setScreen {
            ReportContent(
                { _, _ -> preview },
                initialType = ReportType.COMPATIBILITY,
                onClose = {},
                initialStep = step,
            )
        }
        screen.capture("screens", name)
    }

    @Test
    fun reportWhatHappened() = report("01_report_what", 1)

    @Test
    fun reportShared() = report("02_report_shared", 2)

    @Test
    fun reportSend() = report("03_report_send", 3)

    /** A report about a real bike: the log it attaches, and how sending went. */
    private fun realReport(
        name: String,
        step: Int,
        sent: ReportSent? = null,
    ) {
        val real = preview.copy(demo = false)
        val text =
            listOf(
                """{"schema":2,"seq":0,"t":0.0,"event":"session_start","date":"2026-10-16","app_version":"1.0.0","android":"15","sdk":35,"phone":"Google Pixel 9"}""",
                """{"schema":2,"seq":1,"t":2.1,"event":"identity","model":"ENGWE_Omni_ebike1100EU317"}""",
                """{"schema":2,"seq":2,"t":2.2,"event":"classification","verification":"verified"}""",
            ).joinToString("\n", postfix = "\n")
        screen.setScreen {
            ReportContent(
                { _, _ -> real },
                initialType = ReportType.COMPATIBILITY,
                onClose = {},
                initialStep = step,
                log = LogPreview(1, text, SessionSummary.of(text)),
                sent = sent,
            )
        }
        screen.capture("screens", name)
    }

    @Test
    fun reportSharedWithLog() = realReport("02b_report_shared_log", 2)

    @Test
    fun reportSent() =
        realReport(
            "03d_report_sent",
            3,
            ReportSent(LogSaved.InDownloads("engwe-speed-tool-2026-10-16-1.log"), opened = true),
        )

    @Test
    fun reportSentNotOpened() = realReport("03e_report_not_opened", 3, ReportSent(LogSaved.Chosen, opened = false))

    @Test
    fun reportIdea() {
        val idea = ReportPreview(items = listOf(SharedItem("App", "0.1.0")), demo = true, needsLog = false)
        screen.setScreen {
            ReportContent(
                { _, _ -> idea },
                initialType = ReportType.IDEA,
                onClose = {},
                initialStep = 3,
            )
        }
        screen.capture("screens", "03b_report_idea_send")
    }

    @Test
    fun rideTest() {
        screen.setScreen { RideTestContent(RideTest(32, log = 3), onDone = {}) }
        screen.capture("screens", "03c_ride_test")
    }

    @Test
    fun settings() {
        screen.setScreen {
            SettingsScreen(
                SettingsUiState(
                    bikeName = null,
                    keepSpeed = true,
                    activeKmh = 32,
                    keepScreenOn = true,
                    logSessions = 5,
                    // As for the verified model, whose restarts count.
                    restartsTrusted = true,
                    keptKmh = 32,
                ),
                SettingsActions(onLanguage = {}),
                onBack = {},
            )
        }
        screen.capture("screens", "04_settings")
        screen.compose
            .onNodeWithText(
                RuntimeEnvironment.getApplication().getString(R.string.settings_forget),
            ).performScrollTo()
            .performClick()
        screen.captureScreen("screens", "04b_forget_dialog")
    }

    @Test
    fun legal() {
        screen.setScreen { LegalScreen(Acceptance(LocalDate.of(2026, 10, 15), 1), onBack = {}) }
        screen.capture("screens", "05_legal")
    }

    @Test
    fun about() {
        screen.setScreen {
            AboutScreen(
                version = "0.1.0",
                compatibility =
                    listOf(
                        CompatibilityRow("ENGWE E26 3.0 Pro", "Tested with IoT firmware 218CAN", verified = true),
                        CompatibilityRow("Other ENGWE models", "Not tested yet", verified = false),
                    ),
                onBack = {},
            )
        }
        screen.capture("screens", "06_about")
    }

    @Test
    fun licences() {
        screen.setScreen { LicencesScreen(onBack = {}) }
        // Read off the main thread: wait for the text.
        var tries = 0
        while (screen.compose
                .onAllNodes(hasText("GNU GENERAL PUBLIC LICENSE", substring = true))
                .fetchSemanticsNodes()
                .isEmpty() && tries++ < 200
        ) {
            Thread.sleep(10)
            screen.compose.waitForIdle()
        }
        screen.capture("screens", "06b_licences")
    }

    @Test
    fun diagnostics() {
        screen.setScreen {
            DiagnosticsScreen(
                onReport = {},
                onBack = {},
                logs = listOf(StoredLogInfo(7, 42_000, current = true), StoredLogInfo(6, 640_000, current = false)),
            )
        }
        screen.capture("screens", "07_diagnostics")
    }

    @Test
    fun logPreview() {
        val text =
            listOf(
                """{"schema":2,"seq":0,"t":0.0,"event":"session_start","date":"2026-10-16","app_version":"0.3.0","android":"15","sdk":35,"phone":"Google Pixel 9"}""",
                """{"schema":2,"seq":1,"t":2.1,"event":"identity","model":"ENGWE_Omni_ebike1100EU317"}""",
                """{"schema":2,"seq":2,"t":2.2,"event":"classification","verification":"verified"}""",
                """{"schema":2,"seq":3,"t":30.5,"event":"tx","command":"set_speed_limit_32","length":11,"raw_hex":"456E2A6500012042237765"}""",
                """{"schema":2,"seq":4,"t":30.7,"event":"state","name":"command","value":"confirmed"}""",
            ).joinToString("\n", postfix = "\n")
        screen.setScreen {
            LogPreviewScreen(LogPreview(7, text, SessionSummary.of(text)), onSave = {}, saved = null, onBack = {})
        }
        screen.capture("screens", "08_log_preview")
    }

    @Test
    fun compatibilityCheck() {
        screen.setScreen {
            CompatibilityCheckScreen(
                CompatibilityUiState(CompatibilityProgress.Reading(12, 30), report = null),
                shareText = null,
                onRunAgain = {},
                onReport = {},
                onShare = {},
                onBack = {},
            )
        }
        screen.capture("screens", "07b_compatibility_running")
    }

    @Test
    fun compatibilityCheckDone() {
        val registry = ProfileRegistry.bundled()
        val classification =
            BikeClassifier.classify(ProtocolChecks(true, true, ProfileRegistry.REFERENCE_MODEL), registry)
        val status =
            StatusReport(
                Reading(18.4, true),
                Reading(31.0, true),
                Reading(412.7, true),
                Reading(2, true),
                Reading(76, true),
                Reading(Lights.OFF, true),
                Reading(PowerState.ON, true),
                Reading(24, false),
                Reading(96, false),
                Reading(41.8, true),
                Reading(742, true),
            )
        val report =
            CompatibilityReport(
                CompatibilityOutcome.Checked,
                classification,
                FieldSanity.check(List(29) { status }, classification.layout),
                29,
                unreadableFrames = false,
                sampledSeconds = 30,
                services = FakeBike.REFERENCE_SERVICES,
            )
        val shared = mutableListOf<String>()
        val app = RuntimeEnvironment.getApplication()
        // The real text, with its long lines, as the app builds it for this bike.
        val text =
            CheckText.share(
                "ENGWE Speed Tool (Unofficial) 1.0.0: compatibility check",
                "Bike model: ${ProfileRegistry.REFERENCE_MODEL}",
                "Verification: Verified",
                CheckText.of(report, checkWords(app.resources)),
            )
        screen.setScreen {
            CompatibilityCheckScreen(
                CompatibilityUiState(null, report),
                shareText = text,
                onRunAgain = {},
                onReport = {},
                onShare = { shared += it },
                onBack = {},
            )
        }
        screen.capture("screens", "07c_compatibility_done")
        screen.compose.onNodeWithText(app.getString(R.string.compat_share)).performClick()
        screen.compose.onNodeWithText(text).assertExists()
        screen.captureScreen("screens", "07d_compatibility_share")
        screen.compose
            .onAllNodesWithText(app.getString(R.string.compat_share_send))
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()
        screen.compose.waitForIdle()
        assertEquals("exactly the text shown", listOf(text), shared)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = ScreenVariant.parameters()
    }
}
