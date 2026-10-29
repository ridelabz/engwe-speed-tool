@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.AppStore
import io.github.ridelabz.engwespeedtool.bike.BikeServices
import io.github.ridelabz.engwespeedtool.session.CompatibilityOutcome
import io.github.ridelabz.engwespeedtool.session.CompatibilityReport
import io.github.ridelabz.engwespeedtool.session.FakeBike
import io.github.ridelabz.engwespeedtool.session.FakeBikeTransport
import io.github.ridelabz.engwespeedtool.session.StopReason
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.ui.diagnostics.CompatibilityCheckScreen
import io.github.ridelabz.engwespeedtool.ui.nav.Navigator
import io.github.ridelabz.engwespeedtool.ui.nav.Route
import io.github.ridelabz.engwespeedtool.ui.onboarding.riskStatements
import io.github.ridelabz.engwespeedtool.ui.report.ReportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The dashboard of a saved bike and the screens behind its menu, on a simulated bike behind the real session. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SecondaryFlowTest {
    @get:Rule
    val screen = ScreenRule()

    private val app = RuntimeEnvironment.getApplication()
    private val scheduler = TestCoroutineScheduler()
    private val scope = TestScope(StandardTestDispatcher(scheduler) + SupervisorJob())
    private val version = app.resources.getInteger(R.integer.legal_text_version)
    private val bike = FakeBike(scope, FakeBike.DEMO_ADDRESS, rssi = -55)
    private val store = AppStore(app.getSharedPreferences("secondary-${System.nanoTime()}", Context.MODE_PRIVATE))
    private val services = BikeServices(app, store) { s -> simulated(FakeBikeTransport(bike), s) }

    /** Logs saved in Downloads: their names and texts. */
    private val downloads = mutableListOf<Pair<String, String>>()
    private val controller =
        AppController(
            scope,
            services,
            bikeLabel = app.getString(R.string.find_bike_name),
            downloads = { name, text -> name.also { synchronized(downloads) { downloads += name to text } } },
        )
    private lateinit var navigator: Navigator

    @After
    fun stop() = scope.cancel()

    private fun text(
        @StringRes id: Int,
    ) = app.getString(id)

    private fun pass(seconds: Int) {
        repeat(seconds * 10) {
            scheduler.advanceTimeBy(100)
            scheduler.runCurrent()
        }
        screen.compose.waitForIdle()
    }

    private fun waitFor(label: String) {
        repeat(120) {
            if (screen.compose
                    .onAllNodesWithText(label)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                return
            }
            pass(1)
        }
        throw AssertionError("\"$label\" never showed")
    }

    private fun click(label: String) {
        waitFor(label)
        val node = screen.compose.onAllNodes(hasText(label) and hasClickAction()).onLast()
        runCatching { node.performScrollTo() }
        node.performClick()
        pass(1)
    }

    /** A saved bike, the risks accepted and set up (the caveat seen): the app opens on the dashboard and connects. */
    private fun show() {
        services.acceptRisk(LocalDate.of(2026, 10, 15))
        services.firmwareCaveat.recordFirmwareCaveat()
        store.saveBike(bike.address)
        navigator = Navigator(controller.startRoutes())
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        pass(5)
        assertEquals(Route.DASHBOARD, navigator.current)
    }

    private fun menu(
        @StringRes entry: Int,
    ) {
        screen.compose.onNodeWithContentDescription(text(R.string.menu_open)).performClick()
        pass(1)
        // The menu opens in a window of its own, after the screen's nodes.
        screen.compose
            .onAllNodes(hasText(text(entry)))
            .onLast()
            .performClick()
        pass(1)
    }

    @Test
    fun everyMenuEntryLeadsSomewhereAndBack() {
        show()
        for ((entry, route) in listOf(
            R.string.menu_diagnostics to Route.DIAGNOSTICS,
            R.string.menu_settings to Route.SETTINGS,
            R.string.menu_legal to Route.LEGAL,
            R.string.menu_about to Route.ABOUT,
        )) {
            menu(entry)
            assertEquals(route, navigator.current)
            screen.compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
            pass(1)
            assertEquals(Route.DASHBOARD, navigator.current)
        }
    }

    @Test
    fun theReportNeedsATypeAndWalksThreeSteps() {
        show()
        menu(R.string.menu_report)
        screen.compose.onNodeWithText(text(R.string.action_next)).assertIsNotEnabled()
        click(text(R.string.report_type_problem))
        screen.compose.onNodeWithText(text(R.string.action_next)).assertIsEnabled()
        click(text(R.string.action_next))
        screen.compose.onNodeWithText(text(R.string.report_nothing_identifying)).assertExists()
        // The model the bike sent, in what is shared and in the log's summary.
        assertEquals(
            2,
            screen.compose
                .onAllNodesWithText("ENGWE_Omni_ebike1100EU317")
                .fetchSemanticsNodes()
                .size,
        )
        // What opening GitHub sends, and that the rider posts it under their own account.
        screen.compose
            .onNodeWithText(text(R.string.report_own_account))
            .performScrollTo()
            .assertExists()
        // The whole log the report attaches, before anything is saved: its summary and every line.
        waitFor(text(R.string.report_log_title))
        val log = checkNotNull(controller.reportLog)
        val lines = log.text.lines().filter { it.isNotBlank() }
        screen.compose
            .onNodeWithText(app.resources.getQuantityString(R.plurals.log_preview_lines, lines.size, lines.size))
            .performScrollTo()
            .assertExists()
        screen.compose.onNodeWithText(lines.first()).assertExists()
        click(text(R.string.action_next))
        // No Close before the form opened: only Back and the one button that saves and sends.
        assertTrue(
            screen.compose
                .onAllNodesWithText(text(R.string.action_close))
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        screen.compose.onNodeWithText(text(R.string.action_back)).assertExists()
        click(text(R.string.report_send))
        val name = "engwe-speed-tool-${controller.logDate()}-1.log"
        waitFor(app.getString(R.string.report_sent_downloads, name))
        // Exactly the text the sheet showed, saved under the log's date and number.
        assertEquals(listOf(name to log.text), synchronized(downloads) { downloads.toList() })
        val opened = checkNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertTrue(opened.dataString!!.startsWith(text(R.string.report_repository) + "/issues/new?template=bug_report"))
        screen.compose.onNodeWithText(app.getString(R.string.report_attach_downloads, name)).assertExists()
        click(text(R.string.action_close))
        assertNull(controller.report)
    }

    @Test
    fun aCheckIsReportedWithItsResultAndSharedAsTheTextShown() {
        show()
        menu(R.string.menu_diagnostics)
        click(text(R.string.diagnostics_run_check))
        waitFor(text(R.string.compat_title_done))
        // Share: the dialog shows the text in full, and exactly that text goes to the share sheet.
        click(text(R.string.compat_share))
        val shown =
            screen.compose
                .onNode(hasText("Bike model: ENGWE_Omni_ebike1100EU317", substring = true))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("")
        for (line in listOf(
            "ENGWE Speed Tool (Unofficial) ",
            "Verification: Verified",
            "Complete; status messages read: ",
            "Passed: bike service, notifications, confirmed writes, status, model",
            "Plausible: speed, max speed, odometer, assist level, battery, lights, on/off, voltage, ride time",
        )) {
            assertTrue(line, line in shown)
        }
        // Every value read has a meaning verified for the bike.
        assertTrue(shown, "Meaning not verified" !in shown)
        screen.compose.onNodeWithText(text(R.string.report_check_model)).assertExists()
        // The dialog's button, not the screen's.
        screen.compose
            .onAllNodes(hasText(text(R.string.compat_share_send)) and hasClickAction())
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()
        pass(1)
        val chooser = checkNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)

        @Suppress("DEPRECATION")
        val send = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(shown, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(text(R.string.compat_share_chooser), send.getStringExtra(Intent.EXTRA_TITLE))
        screen.compose.onNodeWithText(text(R.string.compat_share_title)).assertDoesNotExist()

        // Report this result: a compatibility report whose checks are the check's result.
        click(text(R.string.compat_report))
        assertEquals(ReportType.COMPATIBILITY, controller.report?.type)
        click(text(R.string.action_next))
        screen.compose
            .onNode(hasText("Complete; status messages read: ", substring = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aCheckThatNeverReachedTheBikeOffersRunAgainFirst() {
        var outcome by mutableStateOf<CompatibilityOutcome>(CompatibilityOutcome.NotConnected(StopReason.NOT_FOUND))
        screen.compose.setContent {
            CompatibilityCheckScreen(
                CompatibilityUiState(null, CompatibilityReport(outcome, null, emptyList(), 0, false, null)),
                shareText = "text",
                onRunAgain = {},
                onReport = {},
                onShare = {},
                onBack = {},
            )
        }

        fun top(
            @StringRes label: Int,
        ) = screen.compose
            .onNode(hasText(text(label)) and hasClickAction())
            .fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue("Run again is the main button", top(R.string.compat_run_again) < top(R.string.compat_report))
        screen.compose.onNodeWithText(text(R.string.compat_share)).assertExists()
        // Connected, then lost before the checks ended: it reached the bike, and says so.
        outcome = CompatibilityOutcome.NotConnected(StopReason.LINK_LOST)
        screen.compose.waitForIdle()
        assertTrue(
            "Report this result is the main button",
            top(R.string.compat_report) < top(R.string.compat_run_again),
        )
        screen.compose.onNodeWithText(text(R.string.compat_outcome_link_lost)).assertExists()
    }

    @Test
    fun aDemoCheckSharesNothing() {
        var shared = 0
        val report =
            CompatibilityReport(
                CompatibilityOutcome.Checked,
                null,
                emptyList(),
                30,
                unreadableFrames = false,
                sampledSeconds = 30,
            )
        screen.compose.setContent {
            CompatibilityCheckScreen(
                CompatibilityUiState(null, report, demo = true),
                shareText = "simulated",
                onRunAgain = {},
                onReport = {},
                onShare = { shared++ },
                onBack = {},
            )
        }
        click(text(R.string.compat_share))
        screen.compose.onNodeWithText(text(R.string.compat_share_demo)).assertExists()
        screen.compose.onNodeWithText(text(R.string.report_check_model)).assertDoesNotExist()
        val inDialog = hasAnyAncestor(isDialog())
        screen.compose.onAllNodes(hasText(text(R.string.compat_share_send)) and inDialog).assertCountEquals(0)
        screen.compose
            .onAllNodes(hasText(text(R.string.action_close)) and hasClickAction())
            .filterToOne(inDialog)
            .performClick()
        pass(1)
        screen.compose.onNodeWithText(text(R.string.compat_share_title)).assertDoesNotExist()
        assertEquals(0, shared)
    }

    @Test
    fun aboutListsTheBundledProfilesAndOpensTheLicences() {
        show()
        menu(R.string.menu_about)
        waitFor("ENGWE E26 3.0 Pro")
        waitFor(app.getString(R.string.about_tested_firmware, "218CAN"))
        click(text(R.string.about_licences))
        assertEquals(Route.LICENCES, navigator.current)
        waitFor(text(R.string.licences_app))
        // Read off the main thread.
        var tries = 0
        while (screen.compose
                .onAllNodes(hasText("GNU GENERAL PUBLIC LICENSE", substring = true))
                .fetchSemanticsNodes()
                .isEmpty() && tries++ < 200
        ) {
            pass(1)
            Thread.sleep(5)
        }
        screen.compose.onAllNodes(hasText("GNU GENERAL PUBLIC LICENSE", substring = true))[0].assertExists()
    }

    @Test
    fun anIdeaSharesOnlyTheAppVersionAndNoLog() {
        show()
        menu(R.string.menu_report)
        click(text(R.string.report_type_idea))
        click(text(R.string.action_next))
        for (absent in listOf(
            "ENGWE_Omni_ebike1100EU317",
            text(R.string.report_item_android),
            text(R.string.report_item_phone),
            text(R.string.report_item_errors),
        )) {
            assertEquals(
                "an idea shares \"$absent\"",
                0,
                screen.compose
                    .onAllNodes(hasText(absent))
                    .fetchSemanticsNodes()
                    .size,
            )
        }
        screen.compose.onNodeWithText(text(R.string.report_item_app)).assertExists()
    }

    @Test
    fun theReportNeverSharesTheBikesName() {
        show()
        controller.rename("Trail bike")
        pass(1)

        fun count() =
            screen.compose
                .onAllNodes(hasText("Trail bike", substring = true))
                .fetchSemanticsNodes()
                .size
        // The dashboard behind the sheet shows the name itself; the sheet must not add it anywhere.
        val before = count()
        menu(R.string.menu_report)
        click(text(R.string.report_type_compatibility))
        click(text(R.string.action_next))
        assertEquals(before, count())
    }

    @Test
    fun forgettingAsksFirstAndThenStartsOver() {
        show()
        controller.rename("Trail bike")
        pass(1)
        menu(R.string.menu_settings)
        click(text(R.string.settings_forget))
        click(text(R.string.warning_cancel))
        assertEquals(Route.SETTINGS, navigator.current)
        assertEquals("Trail bike", controller.settings.bikeName)
        click(text(R.string.settings_forget))
        click(text(R.string.settings_forget_confirm))
        assertEquals(Route.BLUETOOTH, navigator.current)
        assertNull(store.data.value.bike)
        assertNull(controller.settings.bikeName)
    }

    @Test
    fun theLegalScreenShowsWhatWasAccepted() {
        show()
        menu(R.string.menu_legal)
        for (statement in riskStatements(app.resources)) screen.compose.onNodeWithText(text(statement)).assertExists()
        val day =
            DateTimeFormatter
                .ofLocalizedDate(
                    FormatStyle.MEDIUM,
                ).withLocale(Locale.getDefault())
                .format(LocalDate.of(2026, 10, 15))
        screen.compose.onNodeWithText(app.getString(R.string.legal_accepted_on, day, version)).assertExists()
    }

    @Test
    fun aNewNameShowsOnTheDashboard() {
        // The rename dialog's text field never lets Robolectric go idle, so the name is set directly.
        show()
        controller.rename("  Trail bike  ")
        pass(1)
        screen.compose.onNodeWithText("Trail bike").assertExists()
        controller.rename(" ")
        pass(1)
        screen.compose.onNodeWithText(text(R.string.bike_default_name)).assertExists()
    }

    @Test
    fun speedChoicesAreLargeButtons() {
        show()
        for (kmh in listOf(16, 25, 32, 45)) {
            screen.compose
                .onNodeWithContentDescription(app.getString(R.string.speed_chip_label, kmh))
                .assertHeightIsAtLeast(56.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertIsEnabled()
        }
    }

    @Test
    fun backGoesToThePreviousScreen() {
        navigator = Navigator(listOf(Route.WELCOME))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        click(text(R.string.welcome_start))
        assertEquals(Route.RISK_ACCEPTANCE, navigator.current)
        screen.compose.activityRule.scenario
            .onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pass(1)
        assertEquals(Route.WELCOME, navigator.current)
    }
}
