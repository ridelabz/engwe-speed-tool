@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.AppStore
import io.github.ridelabz.engwespeedtool.bike.BikeServices
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.session.FakeBike
import io.github.ridelabz.engwespeedtool.session.FakeBikeTransport
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.ui.nav.Navigator
import io.github.ridelabz.engwespeedtool.ui.nav.Route
import io.github.ridelabz.engwespeedtool.ui.onboarding.BluetoothScreen
import io.github.ridelabz.engwespeedtool.ui.onboarding.FindBikeUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.PermissionUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.riskStatements
import io.github.ridelabz.engwespeedtool.ui.report.LogSaved
import io.github.ridelabz.engwespeedtool.ui.report.ReportSent
import io.github.ridelabz.engwespeedtool.ui.report.ReportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/** The app clicked through as a rider would, on simulated bikes behind the real session. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class AppFlowTest {
    @get:Rule
    val screen = ScreenRule()

    private val app = RuntimeEnvironment.getApplication()
    private val scheduler = TestCoroutineScheduler()
    private val scope = TestScope(StandardTestDispatcher(scheduler) + SupervisorJob())

    @After
    fun stop() = scope.cancel()

    private fun text(
        @StringRes id: Int,
    ) = app.getString(id)

    private fun controller(
        vararg bikes: FakeBike,
        accepted: Boolean = true,
        downloads: LogDownloads? = null,
    ): AppController {
        val store = AppStore(app.getSharedPreferences("flow-${System.nanoTime()}", Context.MODE_PRIVATE))
        val transport = FakeBikeTransport(bikes.toList())
        val services = BikeServices(app, store) { s -> simulated(transport, s) }
        if (accepted) services.acceptRisk(LocalDate.of(2026, 10, 15))
        return AppController(scope, services, bikeLabel = text(R.string.find_bike_name), downloads = downloads)
    }

    private fun pass(seconds: Int) {
        repeat(seconds * 10) {
            scheduler.advanceTimeBy(100)
            scheduler.runCurrent()
        }
        screen.compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        repeat(600) {
            if (screen.compose
                    .onAllNodes(matcher)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                return
            }
            pass(1)
        }
        throw AssertionError("${matcher.description} never showed")
    }

    private fun waitFor(
        label: String,
        substring: Boolean = false,
    ) {
        repeat(600) {
            if (screen.compose
                    .onAllNodesWithText(label, substring = substring)
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
        val node = screen.compose.onAllNodes(hasText(label) and hasClickAction())[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        pass(1)
    }

    private fun click(
        @StringRes id: Int,
    ) = click(text(id))

    @Test
    fun `from the find screen to the dashboard of a verified bike, and 32 set after the warning and the caveat`() {
        val near = FakeBike(scope, FakeBike.DEMO_ADDRESS, rssi = -55)
        val far = FakeBike(scope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL, rssi = -88)
        val controller = controller(near, far)
        val navigator = Navigator(listOf(Route.FIND_BIKE))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        pass(3)
        assertEquals(
            2,
            screen.compose
                .onAllNodesWithText(text(R.string.find_bike_name))
                .fetchSemanticsNodes()
                .size,
        )
        click(R.string.find_bike_name)
        waitFor(text(R.string.check_result_verified))
        click(R.string.action_continue)
        // The firmware caveat, once, at setup.
        assertEquals(Route.FIRMWARE_CAVEAT, navigator.current)
        click(app.getString(R.string.legal_caveat_sheet_next))
        click(R.string.done_open)
        assertEquals(Route.DASHBOARD, navigator.current)
        // Connected and checked: the speeds take taps.
        waitFor(hasContentDescription(app.getString(R.string.speed_chip_label, 32)) and isEnabled())
        pass(2)
        assertEquals("nothing sent by itself", listOf(BikeCommand.ReadIdentity), near.commands)
        screen.compose.onNodeWithContentDescription(app.getString(R.string.speed_chip_label, 32)).performClick()
        pass(1)
        // Only the warning above 25: the caveat was seen at setup.
        click(app.getString(R.string.warning_set, 32))
        pass(3)
        assertEquals(listOf(BikeCommand.ReadIdentity, BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32)), near.commands)
        assertEquals(SpeedLimit.KMH_32, near.speedLimit)
        // The box says it, read out once as it changes; no line repeats it.
        val activeState = text(R.string.speed_chip_active)
        val active =
            hasContentDescription(app.getString(R.string.speed_chip_label, 32)) and
                SemanticsMatcher(activeState) {
                    it.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith(activeState) == true
                }
        waitFor(active)
        assertEquals(
            LiveRegionMode.Polite,
            screen.compose
                .onNode(active)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.LiveRegion),
        )
        // Back to 25: one tap, no sheet.
        screen.compose.onNodeWithContentDescription(app.getString(R.string.speed_chip_label, 25)).performClick()
        pass(3)
        assertEquals(SpeedLimit.KMH_25, near.speedLimit)
        assertEquals(3, near.commands.size)
        assertTrue(far.commands.isEmpty())
    }

    @Test
    fun `a bike of an untested model needs the acknowledgement before it is saved`() {
        val far = FakeBike(scope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL, rssi = -88)
        val controller = controller(far)
        val navigator = Navigator(listOf(Route.FIND_BIKE))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        click(R.string.find_bike_name)
        waitFor(text(R.string.check_result_unverified))
        click(R.string.action_continue)
        assertEquals(Route.UNVERIFIED_ACKNOWLEDGEMENT, navigator.current)
        click(R.string.unverified_continue)
        // The firmware caveat, once, at setup.
        assertEquals(Route.FIRMWARE_CAVEAT, navigator.current)
        click(app.getString(R.string.legal_caveat_sheet_next))
        assertEquals(Route.DONE, navigator.current)
    }

    @Test
    fun `after the app was behind another one, the acknowledgement saves the bike only after the new check`() {
        val far = FakeBike(scope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL, rssi = -88)
        val controller = controller(far)
        val navigator = Navigator(listOf(Route.FIND_BIKE))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        click(R.string.find_bike_name)
        waitFor(text(R.string.check_result_unverified))
        click(R.string.action_continue)
        assertEquals(Route.UNVERIFIED_ACKNOWLEDGEMENT, navigator.current)
        controller.onBackground()
        controller.onForeground()
        pass(0)
        assertEquals("back to the check, which runs again", Route.CONNECT_CHECK, navigator.current)
        assertEquals("nothing saved", listOf(Route.WELCOME), controller.startRoutes())
        waitFor(text(R.string.check_result_unverified))
        click(R.string.action_continue)
        click(R.string.unverified_continue)
        click(app.getString(R.string.legal_caveat_sheet_next))
        assertEquals(Route.DONE, navigator.current)
        assertEquals(listOf(Route.DASHBOARD), controller.startRoutes())
    }

    @Test
    fun `with no bike around, the demo shows a simulated one, and leaving it ends the demo`() {
        val controller = controller()
        val navigator = Navigator(listOf(Route.FIND_BIKE))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        pass(21)
        click(R.string.action_try_demo)
        assertTrue(controller.demo)
        waitFor(text(R.string.demo_banner))
        waitFor(text(R.string.keep_speed_title))
        navigator.pop()
        pass(1)
        assertFalse(controller.demo)
    }

    @Test
    fun `in the demo, a tap on an assist level sets it, and the heading is read out once the bike confirms it`() {
        val controller = controller()
        controller.startDemo()
        val navigator = Navigator(listOf(Route.DASHBOARD))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        pass(3)
        // Checked: the bar takes taps.
        waitFor(text(R.string.badge_verified))
        val segment = screen.compose.onNodeWithContentDescription(app.getString(R.string.assist_segment, 4))
        runCatching { segment.performScrollTo() }
        segment.performClick()
        // No line says it: the heading is read out, as a live region while the confirmation is new.
        val heading = hasContentDescription(app.getString(R.string.assist_spoken, 4, 5))
        waitFor(heading and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val confirmed = text(R.string.assist_heading_confirmed)
        screen.compose
            .onNode(
                heading and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, confirmed),
            ).assertExists()
        assertEquals(4, controller.dashboard.assist.level)
        assertEquals(4, controller.dashboard.assist.confirmed)
    }

    @Test
    fun `exiting the demo goes back to a new search for real bikes`() {
        val controller = controller()
        val navigator = Navigator(listOf(Route.FIND_BIKE))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        pass(21)
        click(R.string.action_try_demo)
        waitFor(text(R.string.demo_banner))
        click(R.string.action_exit_demo)
        assertFalse(controller.demo)
        assertEquals(listOf(Route.FIND_BIKE), navigator.routes)
        assertEquals("searching again", FindBikeUiState.Searching(emptyList()), controller.findBike)
        assertEquals(
            0,
            screen.compose
                .onAllNodesWithText(text(R.string.demo_banner))
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun `back from the demo dashboard leaves the demo too`() {
        val controller = controller()
        val navigator = Navigator(listOf(Route.FIND_BIKE))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        pass(21)
        click(R.string.action_try_demo)
        waitFor(text(R.string.demo_banner))
        screen.compose.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        pass(1)
        assertFalse(controller.demo)
        assertEquals(Route.FIND_BIKE, navigator.current)
    }

    @Test
    fun `a check without the rider's acceptance reads nothing and leads to the risks, then checks again`() {
        val near = FakeBike(scope, FakeBike.DEMO_ADDRESS, rssi = -55)
        val controller = controller(near, accepted = false)
        val navigator = Navigator(listOf(Route.FIND_BIKE))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        click(R.string.find_bike_name)
        waitFor(text(R.string.error_not_accepted_title))
        assertTrue(near.commands.isEmpty())
        click(R.string.action_review_risks)
        assertEquals(Route.RISK_ACCEPTANCE, navigator.current)
        for (statement in riskStatements(app.resources)) click(text(statement))
        click(R.string.legal_accept)
        assertEquals(Route.CONNECT_CHECK, navigator.current)
        waitFor(text(R.string.check_result_verified))
        assertEquals(listOf(BikeCommand.ReadIdentity), near.commands)
    }

    @Test
    fun `the screen stays on with the dashboard unless turned off in settings, and every reading shows`() {
        val controller = controller()
        controller.startDemo()
        val navigator = Navigator(listOf(Route.DASHBOARD))
        var view: View? = null
        screen.compose.setContent {
            view = LocalView.current
            SpeedToolApp(controller, navigator)
        }
        pass(2)
        assertTrue(checkNotNull(view).keepScreenOn)
        assertReadingShows(R.string.more_voltage)
        navigator.push(Route.SETTINGS)
        pass(1)
        assertFalse("only with the dashboard", checkNotNull(view).keepScreenOn)
        controller.setKeepScreenOn(false)
        navigator.pop()
        pass(2)
        assertFalse(checkNotNull(view).keepScreenOn)
        assertReadingShows(R.string.more_voltage)
    }

    /** The readings card shows the tile of [label], read out as one with its value. */
    private fun assertReadingShows(label: Int) {
        waitFor(text(R.string.readings_title))
        val tiles = screen.compose.onAllNodes(hasContentDescription(text(label), substring = true))
        assertTrue(text(label), tiles.fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the menu opens the secondary screens, and a test build can save the session log`() {
        val controller = controller()
        controller.startDemo()
        val navigator = Navigator(listOf(Route.DASHBOARD))
        screen.compose.setContent { SpeedToolApp(controller, navigator) }
        pass(2)
        for ((entry, route) in listOf(
            R.string.menu_settings to Route.SETTINGS,
            R.string.menu_legal to Route.LEGAL,
            R.string.menu_about to Route.ABOUT,
            R.string.menu_diagnostics to Route.DIAGNOSTICS,
        )) {
            screen.compose.onNodeWithContentDescription(text(R.string.menu_open)).performClick()
            pass(1)
            click(entry)
            assertEquals(route, navigator.current)
            if (route == Route.DIAGNOSTICS) waitFor(text(R.string.diagnostics_logs_title))
            navigator.pop()
            pass(1)
        }
    }

    /** Downloads that take a log only while [works]; what they took, by name. */
    private class FakeDownloads : LogDownloads {
        var works = true
        val saved = mutableListOf<Pair<String, String>>()

        override fun save(
            name: String,
            text: String,
        ): String? = synchronized(this) { if (works) name.also { saved += it to text } else null }
    }

    /** The app with a file picker that answers [answer] (null: cancelled); the names it was asked for. */
    private fun showWithFiles(
        controller: AppController,
        navigator: Navigator,
        answer: () -> Uri?,
    ): List<String> {
        val names = mutableListOf<String>()
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    names += input as String
                    dispatchResult(requestCode, answer())
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        screen.compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                SpeedToolApp(controller, navigator)
            }
        }
        return names
    }

    /** Lets the screen and the threads that save files settle until [done]. */
    private fun settle(done: () -> Boolean) {
        var tries = 0
        while (!done() && tries++ < 600) {
            scheduler.runCurrent()
            screen.compose.waitForIdle()
            Thread.sleep(5)
        }
        assertTrue("didn't settle", done())
    }

    @Test
    fun `saved session logs go to Downloads named by the log's date and a number, the picker if that fails`() {
        val downloads = FakeDownloads()
        val controller = controller(downloads = downloads)
        controller.startDemo()
        var answer: Uri? = null
        val navigator = Navigator(listOf(Route.DIAGNOSTICS))
        val picked = showWithFiles(controller, navigator) { answer }
        val date = controller.logDate()

        fun name(number: Int) = "engwe-speed-tool-$date-$number.log"

        // This run's log, opened in its preview: the summary, then every line.
        val thisRun = app.getString(R.string.diagnostics_log_this_run, "").removeSuffix(" · ").trim()
        val row = hasText(thisRun, substring = true) and hasClickAction()
        settle {
            screen.compose
                .onAllNodes(row)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        screen.compose.onAllNodes(row)[0].performClick()
        waitFor(text(R.string.log_preview_title))
        assertEquals(Route.LOG_PREVIEW, navigator.current)
        waitFor(controller.logDate().toString())

        // Saved in Downloads: exactly the previewed text, and the next save gets the next number.
        click(R.string.diagnostics_save_log)
        settle { downloads.saved.size == 1 }
        waitFor(app.getString(R.string.diagnostics_log_saved_downloads, name(1)))
        val (first, text) = downloads.saved.single()
        assertEquals(name(1), first)
        assertEquals("exactly the previewed text", controller.previewLog?.text, text)
        assertTrue(text.contains("\"event\":\"session_start\""))
        assertTrue("the name's date is the log's own", text.contains("\"date\":\"$date\""))

        // Downloads can't take it: the picker, with the same name. Cancelled: the number isn't used up.
        downloads.works = false
        click(R.string.diagnostics_save_log)
        settle { picked.size == 1 }
        assertEquals(listOf(name(2)), picked)
        // Saved through the picker: the file holds the log, and the number is taken.
        val file = File(app.cacheDir, "saved-log.log").apply { delete() }
        answer = Uri.fromFile(file)
        val written = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(answer, written)
        click(R.string.diagnostics_save_log)
        waitFor(text(R.string.diagnostics_log_saved))
        assertEquals(controller.previewLog?.text, written.toString(Charsets.UTF_8))
        downloads.works = true
        click(R.string.diagnostics_save_log)
        settle { downloads.saved.size == 2 }
        assertEquals(listOf(name(2), name(2)), picked)
        assertEquals(name(3), downloads.saved.last().first)
    }

    @Test
    fun `a report shows its whole log, then one tap saves it in Downloads and opens the filled-in form`() {
        val downloads = FakeDownloads()
        val controller = controller(downloads = downloads)
        val navigator = Navigator(listOf(Route.ABOUT))
        showWithFiles(controller, navigator) { null }
        controller.openReport(ReportType.PROBLEM)
        pass(1)
        click(R.string.action_next)
        // Every line before anything is saved.
        waitFor(text(R.string.report_log_title))
        click(R.string.action_next)
        settle {
            screen.compose
                .onAllNodes(
                    hasText(text(R.string.report_send)) and isEnabled(),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        click(R.string.report_send)
        settle { controller.report?.sent != null }
        val name = downloads.saved.single().first
        assertEquals(controller.reportLog?.text, downloads.saved.single().second)
        assertEquals(ReportSent(LogSaved.InDownloads(name), opened = true), controller.report?.sent)
        val opened = checkNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertTrue(
            opened.dataString,
            opened.dataString!!.startsWith(text(R.string.report_repository) + "/issues/new?template="),
        )
        assertEquals("matched as a link any browser opens", "https://", opened.selector?.dataString)
        waitFor(app.getString(R.string.report_sent_downloads, name))
        click(R.string.action_close)
        assertNull(controller.report)
    }

    @Test
    fun `a report whose log Downloads can't take is saved through the picker, then the form opens`() {
        val downloads = FakeDownloads().apply { works = false }
        val controller = controller(downloads = downloads)
        val file = File(app.cacheDir, "report-log.log").apply { delete() }
        val answer = Uri.fromFile(file)
        val written = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(answer, written)
        val navigator = Navigator(listOf(Route.ABOUT))
        val picked = showWithFiles(controller, navigator) { answer }
        controller.openReport(ReportType.PROBLEM)
        pass(1)
        click(R.string.action_next)
        waitFor(text(R.string.report_log_title))
        click(R.string.action_next)
        settle {
            screen.compose
                .onAllNodes(
                    hasText(text(R.string.report_send)) and isEnabled(),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        click(R.string.report_send)
        settle { controller.report?.sent != null }
        assertEquals(1, picked.size)
        assertEquals(controller.reportLog?.text, written.toString(Charsets.UTF_8))
        assertEquals(ReportSent(LogSaved.Chosen, opened = true), controller.report?.sent)
        waitFor(text(R.string.report_attach_chosen))
    }

    @Test
    fun `a report's picker cancelled still opens the form and says the log wasn't saved`() {
        val downloads = FakeDownloads().apply { works = false }
        val controller = controller(downloads = downloads)
        val navigator = Navigator(listOf(Route.ABOUT))
        val picked = showWithFiles(controller, navigator) { null }
        controller.openReport(ReportType.PROBLEM)
        pass(1)
        click(R.string.action_next)
        waitFor(text(R.string.report_log_title))
        click(R.string.action_next)
        settle {
            screen.compose
                .onAllNodes(
                    hasText(text(R.string.report_send)) and isEnabled(),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        click(R.string.report_send)
        settle { controller.report?.sent != null }
        assertEquals(1, picked.size)
        assertEquals(ReportSent(LogSaved.NotSaved, opened = true), controller.report?.sent)
        waitFor(text(R.string.report_sent_not_saved))
        assertNotNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `without a browser the sheet says so, offers to try again or cancel, and no Close`() {
        val downloads = FakeDownloads()
        val controller = controller(downloads = downloads)
        val navigator = Navigator(listOf(Route.ABOUT))
        showWithFiles(controller, navigator) { null }
        shadowOf(app).checkActivities(true) // nothing resolves: no browser on this phone
        controller.openReport(ReportType.PROBLEM)
        pass(1)
        click(R.string.action_next)
        waitFor(text(R.string.report_log_title))
        click(R.string.action_next)
        settle {
            screen.compose
                .onAllNodes(
                    hasText(text(R.string.report_send)) and isEnabled(),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        click(R.string.report_send)
        settle { controller.report?.sent != null }
        assertEquals(false, controller.report?.sent?.opened)
        waitFor(text(R.string.report_open_failed))
        assertTrue(
            screen.compose
                .onAllNodesWithText(text(R.string.action_close))
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        // Trying again opens nothing either, and saves nothing again.
        click(R.string.report_open_github_only)
        assertEquals(false, controller.report?.sent?.opened)
        click(R.string.action_cancel)
        assertNull(controller.report)
        assertEquals("saved once, not again on retry", 1, downloads.saved.size)
    }

    @Test
    fun `in demo mode a report saves and opens nothing, and says so`() {
        val downloads = FakeDownloads()
        val controller = controller(downloads = downloads)
        controller.startDemo()
        val navigator = Navigator(listOf(Route.DASHBOARD))
        val picked = showWithFiles(controller, navigator) { null }
        pass(2)
        controller.openReport(ReportType.PROBLEM)
        pass(1)
        click(R.string.action_next)
        click(R.string.action_next)
        click(R.string.report_send)
        waitFor(text(R.string.report_demo_done))
        assertTrue(downloads.saved.isEmpty())
        assertTrue("no picker either", picked.isEmpty())
        assertNull(shadowOf(app).nextStartedActivity)
        click(R.string.action_close)
        assertNull(controller.report)
    }

    @Test
    fun `a refused permission is explained, and a final refusal leads to the app's settings`() {
        var opened = 0
        var permission by mutableStateOf(PermissionUiState.DENIED)
        screen.compose.setContent {
            BluetoothScreen(onContinue = {}, onBack = {}, permission = permission, onOpenSettings = { opened++ })
        }
        waitFor(text(R.string.bluetooth_denied))
        waitFor(text(R.string.action_allow))
        permission = PermissionUiState.DENIED_PERMANENTLY
        waitFor(text(R.string.bluetooth_denied_permanently))
        click(R.string.action_open_settings)
        assertEquals(1, opened)
    }
}
