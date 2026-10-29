package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.at
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.state
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The dashboard claims no more than the app knows: an unverified bike never gets the wording that relies
 * on verified behaviour, and while the bike isn't connected no speed is shown as active.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class HonestWordingTest {
    @get:Rule
    val screen = ScreenRule()

    private val statuses =
        listOf(
            SpeedLimitStatus.BikeDefault,
            SpeedLimitStatus.Unknown,
            SpeedLimitStatus.Sending(32),
            SpeedLimitStatus.Sending(32, Reapply.RESTART),
            SpeedLimitStatus.Sending(32, Reapply.RECONNECT),
            SpeedLimitStatus.Confirmed(32, at),
            SpeedLimitStatus.Confirmed(25, at),
            SpeedLimitStatus.NotConfirmed(32),
            SpeedLimitStatus.BikeRestarted,
        )

    private fun text(
        id: Int,
        vararg args: Any,
    ) = RuntimeEnvironment.getApplication().getString(id, *args)

    /** A chip whose state starts with [claim] (it may go on with "Above the legal limit"). */
    private fun stateStartsWith(claim: String) =
        SemanticsMatcher("state starts with $claim") { node ->
            node.config.getOrNull(SemanticsProperties.StateDescription)?.startsWith(claim) == true
        }

    private var current by mutableStateOf(state())

    private fun forEveryState(
        verification: Verification,
        connections: List<Connection>,
        check: () -> Unit,
    ) {
        for (connection in connections) {
            for (status in statuses) {
                // Whether restarts count or not (on an unverified bike they never do in the app: its power flags
                // aren't verified).
                for (trusted in listOf(false, true)) {
                    val keep = status is SpeedLimitStatus.Sending
                    current =
                        state(
                            verification = verification,
                            status = status,
                            connection = connection,
                            keepSpeed = keep,
                            restartsTrusted = trusted,
                        )
                    screen.compose.waitForIdle()
                    check()
                }
            }
        }
    }

    private fun show() = screen.setScreen { DashboardScreen(current, DashboardActions.None) }

    @Test
    fun unverifiedBikesNeverGetVerifiedClaims() {
        show()
        val verifiedOnly =
            listOf(
                text(R.string.speed_status_until_off),
                text(R.string.speed_status_restarted, 25),
                text(R.string.speed_status_default, 25),
                text(R.string.verification_title_verified),
            )
        forEveryState(Verification.UNVERIFIED, Connection.entries) {
            // What the project's tests showed is about the tested model: never said of another.
            val tests = screen.compose.onAllNodes(hasText("project’s tests", substring = true)).fetchSemanticsNodes()
            assertTrue("the project's tests quoted for an unverified bike", tests.isEmpty())
            for (part in verifiedOnly) {
                val found = screen.compose.onAllNodes(hasText(part, substring = true)).fetchSemanticsNodes()
                assertTrue("verified-only wording on an unverified bike: \"$part\"", found.isEmpty())
            }
            val active =
                screen.compose
                    .onAllNodes(
                        stateStartsWith(text(R.string.speed_chip_active)),
                    ).fetchSemanticsNodes()
            assertTrue("an unverified chip says Active", active.isEmpty())
        }
    }

    @Test
    fun untrustedRestartsAreNeverClaimedOrPromised() {
        show()
        // Where restarts don't count (a bike whose power flags aren't verified, or a session told not to trust
        // them), nothing says what a restart did to the limit (the keep sheets' wording has its own test).
        val claims =
            listOf(
                text(R.string.speed_status_restarted, 25),
                text(R.string.speed_status_default, 25),
            )
        for (verification in listOf(Verification.VERIFIED, Verification.UNVERIFIED)) {
            for (status in statuses) {
                for ((connection, keep) in Connection.entries.flatMap { c -> listOf(c to false, c to true) }) {
                    current = state(verification, status, connection, keepSpeed = keep, restartsTrusted = false)
                    screen.compose.waitForIdle()
                    for (claim in claims) {
                        val found = screen.compose.onAllNodes(hasText(claim, substring = true)).fetchSemanticsNodes()
                        assertTrue("\"$claim\" without trusted restarts ($status)", found.isEmpty())
                    }
                    val default =
                        screen.compose
                            .onAllNodes(stateStartsWith(text(R.string.speed_chip_default)))
                            .fetchSemanticsNodes()
                    assertTrue("a chip says Bike default without trusted restarts", default.isEmpty())
                }
            }
        }
    }

    @Test
    fun aVerifiedBikesTrustedRestartsSayWhatTheTestsShowedAndKeepPromisesThem() {
        show()
        current = state(Verification.VERIFIED, status = SpeedLimitStatus.BikeDefault, restartsTrusted = true)
        screen.compose.waitForIdle()
        assertTrue(shows(text(R.string.speed_status_default, 25)))
        val default =
            screen.compose
                .onAllNodes(
                    hasContentDescription(text(R.string.speed_chip_label, 25)) and
                        stateStartsWith(text(R.string.speed_chip_default)),
                ).fetchSemanticsNodes()
        assertTrue("25 marked as the bike's default", default.size == 1)
        current = state(Verification.VERIFIED, status = SpeedLimitStatus.BikeRestarted, restartsTrusted = true)
        screen.compose.waitForIdle()
        assertTrue(shows(text(R.string.speed_status_restarted, 25)))
        assertTrue(!shows(text(R.string.speed_status_restarted_unsure, 25)))
        current = state(Verification.VERIFIED, status = SpeedLimitStatus.Sending(32, Reapply.RESTART), keepSpeed = true)
        screen.compose.waitForIdle()
        assertTrue(shows(text(R.string.speed_status_reapplying, 32)))
        current = state(Verification.VERIFIED, keepSpeed = true, restartsTrusted = true)
        screen.compose.waitForIdle()
        for ((info, expected) in listOf(
            R.string.keep_speed_info to R.string.keep_speed_detail,
            R.string.keep_assist_info to R.string.keep_assist_detail,
        )) {
            click(text(info))
            assertTrue(text(info), shows(text(expected), substring = false))
            click(text(R.string.action_close))
        }
    }

    @Test
    fun noSpeedIsActiveWhileTheBikeIsNotConnected() {
        show()
        val claims =
            listOf(
                R.string.speed_chip_active,
                R.string.speed_chip_confirmed,
                R.string.speed_chip_default,
            ).map {
                text(it)
            }
        for (verification in listOf(Verification.VERIFIED, Verification.UNVERIFIED)) {
            forEveryState(verification, Connection.entries - Connection.CONNECTED) {
                for (claim in claims) {
                    val nodes = screen.compose.onAllNodes(stateStartsWith(claim)).fetchSemanticsNodes()
                    assertTrue("a chip says $claim while the bike isn't connected", nodes.isEmpty())
                }
            }
        }
    }

    @Test
    fun aReconnectNeverClaimsARestart() {
        show()
        for (verification in listOf(Verification.VERIFIED, Verification.UNVERIFIED)) {
            current =
                state(
                    verification = verification,
                    status = SpeedLimitStatus.Sending(32, Reapply.RECONNECT),
                    keepSpeed = true,
                )
            screen.compose.waitForIdle()
            val restarted = text(R.string.speed_status_restarted_unsure, 25).substringBefore(".")
            val found = screen.compose.onAllNodes(hasText(restarted, substring = true)).fetchSemanticsNodes()
            assertTrue("a reconnect is shown as a restart", found.isEmpty())
        }
    }

    @Test
    fun nothingIsClaimedBeforeTheBikeIsChecked() {
        show()
        for (verification in listOf(Verification.VERIFIED, Verification.UNVERIFIED)) {
            current =
                state(verification, connection = Connection.CONNECTING)
                    .copy(checked = false, assist = AssistUiState(level = null, levels = null))
            screen.compose.waitForIdle()
            for (claim in listOf(R.string.badge_verified, R.string.badge_unverified, R.string.value_unverified)) {
                // Also inside the tiles, which are read out as one.
                val found =
                    screen.compose.onAllNodes(hasText(text(claim)), useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("\"${text(claim)}\" before the check", found.isEmpty())
            }
            // Not even that the level can't be set: the card waits for the check, greyed out.
            assertTrue(!shows(text(R.string.assist_status_shown_only), substring = false))
            screen.compose.onNode(hasContentDescription(text(R.string.assist_spoken_none))).assertExists()
        }
    }

    @Test
    fun everyCardKeepsItsPlaceWhateverTheConnection() {
        show()
        for (verification in listOf(Verification.VERIFIED, Verification.UNVERIFIED)) {
            for (connection in Connection.entries) {
                for (checked in listOf(true, false)) {
                    current = state(verification, connection = connection).copy(checked = checked)
                    screen.compose.waitForIdle()
                    for (title in listOf(R.string.speed_title, R.string.lights_title, R.string.readings_title)) {
                        val found = screen.compose.onAllNodes(hasText(text(title))).fetchSemanticsNodes()
                        assertTrue("${text(title)} ($verification, $connection, $checked)", found.isNotEmpty())
                    }
                    // The battery card reads as one: "Battery 76 %", or "Battery, no reading".
                    val battery = screen.compose.onAllNodes(batteryCard()).fetchSemanticsNodes()
                    assertTrue("battery ($verification, $connection, $checked)", battery.isNotEmpty())
                    // The assist card reads its title with its level, as one heading.
                    val assist =
                        screen.compose
                            .onAllNodes(
                                SemanticsMatcher("the assist card's heading") { node ->
                                    node.config
                                        .getOrNull(SemanticsProperties.ContentDescription)
                                        ?.any { it.startsWith(text(R.string.assist_title)) } == true
                                },
                            ).fetchSemanticsNodes()
                    assertTrue("assist ($verification, $connection, $checked)", assist.isNotEmpty())
                }
            }
        }
    }

    /** The battery card, by what it reads out (the sample's 76 %, or no reading). */
    private fun batteryCard() =
        SemanticsMatcher("the battery card") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                it.startsWith(text(R.string.battery_spoken, 76)) || it.startsWith(text(R.string.battery_spoken_none))
            } == true
        }

    @Test
    fun theSpeedCardLeavesTheConnectionToTheStatusCardAndSaysNothingWithoutNews() {
        show()
        val confirmed = text(R.string.speed_status_active, 32, "").substringBefore(" · ")
        for (connection in Connection.entries) {
            // Nothing sent, nothing seen: no line, and no room for one.
            current = state(Verification.VERIFIED, status = SpeedLimitStatus.Unknown, connection = connection)
            screen.compose.waitForIdle()
            val box =
                screen.compose
                    .onNode(hasContentDescription(text(R.string.speed_chip_label, 25)))
                    .getBoundsInRoot()
            val keep = screen.compose.onNode(hasText(text(R.string.keep_speed_title))).getBoundsInRoot()
            val segment =
                screen.compose
                    .onNode(hasContentDescription(text(R.string.assist_segment, 0)))
                    .getBoundsInRoot()
            val keepAssist = screen.compose.onNode(hasText(text(R.string.keep_assist_title))).getBoundsInRoot()
            assertTrue(
                "$connection: as far from the boxes as the assist card's switch from its bar",
                abs((keep.top - box.bottom).value - (keepAssist.top - segment.bottom).value) < 1f,
            )
            // News shows only while connected; the state at the top says the rest.
            current = state(Verification.VERIFIED, status = SpeedLimitStatus.Confirmed(32, at), connection = connection)
            screen.compose.waitForIdle()
            assertTrue("$connection", shows(confirmed) == (connection == Connection.CONNECTED))
        }
    }

    @Test
    fun theCardsComeInTheirOrderUnderTheTopBar() {
        current = state(Verification.VERIFIED, connection = Connection.BLUETOOTH_OFF)
        show()
        val title = screen.compose.onNode(hasText(text(R.string.dashboard_title)))
        assertTrue(
            "the top bar's title is a heading",
            SemanticsProperties.Heading in title.fetchSemanticsNode().config,
        )
        val name = hasText(text(R.string.bike_default_name))
        assertTrue(
            title.getBoundsInRoot().bottom <=
                screen.compose
                    .onNode(name)
                    .getBoundsInRoot()
                    .top,
        )
        assertTrue(
            "the battery card reads as one heading",
            SemanticsProperties.Heading in
                screen.compose
                    .onNode(batteryCard())
                    .fetchSemanticsNode()
                    .config,
        )
        // My bike, the connection's card, the battery, then the speed limit and the rest as before.
        val order =
            listOf(
                name,
                hasText(text(R.string.error_bluetooth_off_title)),
                batteryCard(),
                hasText(text(R.string.speed_title)),
                hasText(text(R.string.lights_title)),
                hasText(text(R.string.readings_title)),
            )
        for ((upper, lower) in order.zipWithNext()) {
            screen.compose.onNode(lower).performScrollTo()
            val above = screen.compose.onNode(upper).getBoundsInRoot()
            val below = screen.compose.onNode(lower).getBoundsInRoot()
            assertTrue("$upper above $lower", above.bottom <= below.top)
        }
    }

    /** Clicks the control labelled [label] (by its text or its description) and lets the screen settle. */
    private fun click(label: String) {
        val node =
            screen.compose
                .onAllNodes(
                    (hasText(label) or hasContentDescription(label)) and hasClickAction(),
                    useUnmergedTree = false,
                ).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        screen.compose.waitForIdle()
    }

    /** Taps the Verified or Unverified chip, by what its tap does. */
    private fun openVerification() {
        screen.compose
            .onNode(
                SemanticsMatcher("explains verification") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.badge_explain)
                },
            ).performClick()
        screen.compose.waitForIdle()
    }

    private fun shows(
        part: String,
        substring: Boolean = true,
    ) = screen.compose
        .onAllNodes(hasText(part, substring = substring))
        .fetchSemanticsNodes()
        .isNotEmpty()

    @Test
    fun theExplanationsOfAnUnverifiedBikeNeverQuoteTheTests() {
        current = state(Verification.UNVERIFIED, status = SpeedLimitStatus.Confirmed(32, at))
        show()
        val verifiedOnly =
            listOf(
                "project’s tests",
                text(R.string.speed_status_until_off),
                text(R.string.verification_title_verified),
                text(R.string.verification_matched, "ENGWE_Demo_ebike"),
                text(R.string.verification_firmware, "218CAN", 25),
            )
        // The speed card's ⓘ, then the chip.
        click(text(R.string.speed_info))
        for (part in verifiedOnly) assertTrue("\"$part\" in the speed sheet", !shows(part))
        assertTrue(shows(text(R.string.speed_status_unverified_caveat, 25)))
        click(text(R.string.action_close))
        openVerification()
        for (part in verifiedOnly) assertTrue("\"$part\" in the verification sheet", !shows(part))
        assertTrue("the acknowledgement's caveat", shows(text(R.string.verification_unverified_caveat, 25)))
        assertTrue(shows(text(R.string.verification_not_matched, "ENGWE_Demo_ebike")))
        assertTrue(shows(text(R.string.verification_report)))
    }

    @Test
    fun theVerificationSheetOfAVerifiedBikeNamesItsModelAndTestedFirmware() {
        current = state(Verification.VERIFIED)
        show()
        openVerification()
        assertTrue(shows(text(R.string.verification_matched, "ENGWE_Omni_ebike1100EU317")))
        assertTrue(shows(text(R.string.verification_firmware, "218CAN", 25)))
        assertTrue("no unverified caveat", !shows(text(R.string.verification_unverified_caveat, 25)))
        assertTrue(shows(text(R.string.verification_report)))
    }

    @Test
    fun keepSpeedNeverPromisesRestartsWithoutTrustedRestarts() {
        current = state(Verification.VERIFIED, keepSpeed = true, restartsTrusted = false)
        show()
        click(text(R.string.keep_speed_info))
        assertTrue(shows(text(R.string.keep_speed_detail_reconnect)))
        assertTrue(!shows(text(R.string.keep_speed_detail), substring = false))
        click(text(R.string.action_close))
        click(text(R.string.keep_assist_info))
        assertTrue(shows(text(R.string.keep_assist_detail_reconnect)))
        assertTrue(!shows(text(R.string.keep_assist_detail), substring = false))
    }

    @Test
    fun aBikeThatIsOffOrLockedSaysSoAndNothingIsActive() {
        for (verification in listOf(Verification.VERIFIED, Verification.UNVERIFIED)) {
            current = state(verification, status = SpeedLimitStatus.Confirmed(32, at), connection = Connection.BIKE_OFF)
            if (verification == Verification.VERIFIED) show() else screen.compose.waitForIdle()
            assertTrue("$verification", shows(text(R.string.connection_bike_off), substring = false))
            // Off may also be a lock: the app says it can't tell, and never "On".
            assertTrue(!shows(text(R.string.connection_on), substring = false))
        }
        current = state(Verification.UNVERIFIED, connection = Connection.BIKE_OFF)
        screen.compose.waitForIdle()
        assertTrue("may not have reset", shows(text(R.string.bike_off_unverified, 25)))
    }

    @Test
    fun theAssistBarFillsEverySegmentUpToTheLevelZeroIncluded() {
        for (level in 0..5) {
            assertTrue("$level", (0..5).filter { segmentFilled(it, level) } == (0..level).toList())
        }
        assertTrue("no reading", (0..5).none { segmentFilled(it, null) })
    }

    @Test
    fun theAssistControlExistsOnlyWhereTheProfileGivesIt() {
        current = state(Verification.VERIFIED)
        show()
        val segments = screen.compose.onAllNodes(hasContentDescription(text(R.string.assist_segment, 3)))
        assertTrue(segments.fetchSemanticsNodes().isNotEmpty())
        current = state(Verification.UNVERIFIED)
        screen.compose.waitForIdle()
        assertTrue(shows(text(R.string.assist_status_shown_only), substring = false))
        assertTrue(
            screen.compose
                .onAllNodes(hasContentDescription(text(R.string.assist_segment, 3)))
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun speedsAboveTheLimitKeepTheirMarkInEveryState() {
        show()
        for (status in statuses) {
            current = state(choices = listOf(16, 25, 32, 45), status = status)
            screen.compose.waitForIdle()
            for (kmh in listOf(32, 45)) {
                val marked =
                    screen.compose
                        .onAllNodes(
                            hasContentDescription(text(R.string.speed_chip_label, kmh)) and
                                SemanticsMatcher("marked") { node ->
                                    node.config
                                        .getOrNull(SemanticsProperties.StateDescription)
                                        ?.contains(text(R.string.speed_chip_above_limit)) == true
                                },
                        ).fetchSemanticsNodes()
                assertTrue("$kmh km/h unmarked ($status)", marked.isNotEmpty())
            }
        }
    }

    @Test
    fun aSpeedAboveTheLimitKeepsItsVisibleMarkInEveryLook() {
        for (look in ChipLook.entries) {
            val above = chipMarks(look, above = true)
            assertTrue("$look", ChipMark.Icon(R.drawable.ic_warning) in above)
            assertTrue("$look", ChipMark.Icon(R.drawable.ic_warning) !in chipMarks(look, above = false))
        }
    }

    @Test
    fun anUnverifiedBikesConfirmedSpeedSaysItMayNotReset() {
        // That it may not reset, and how to go back: the footer no longer says it.
        val caveat = text(R.string.speed_status_confirmed_unverified, 32, "", 25).substringAfter(". ")
        check(caveat.endsWith(text(R.string.speed_status_confirmed_unverified, 32, "", 25).substringAfter(": ")))
        check(caveat.isNotBlank())
        current = state(Verification.UNVERIFIED, status = SpeedLimitStatus.Confirmed(32, at))
        show()
        assertTrue(shows(caveat))
        current = state(Verification.VERIFIED, status = SpeedLimitStatus.Confirmed(32, at))
        screen.compose.waitForIdle()
        assertTrue("not on the tested model", !shows(caveat))
    }

    @Test
    fun noSheetOfAnUnverifiedBikeQuotesTheTestsOrPromisesRestarts() {
        current = state(Verification.UNVERIFIED, keepSpeed = true)
        show()
        for ((info, expected) in listOf(
            R.string.keep_speed_info to text(R.string.keep_speed_detail_reconnect),
            R.string.assist_info to text(R.string.assist_status_shown_only),
        )) {
            click(text(info))
            assertTrue(text(info), shows(expected))
            assertTrue(text(info), !shows("project’s tests"))
            assertTrue(text(info), !shows(text(R.string.keep_speed_detail), substring = false))
            assertTrue(text(info), !shows(text(R.string.keep_assist_detail), substring = false))
            click(text(R.string.action_close))
        }
    }

    @Test
    fun theAssistCardClaimsNoLevelWhileTheBikeIsNotConnected() {
        for (connection in Connection.entries - Connection.CONNECTED) {
            current =
                state(
                    Verification.VERIFIED,
                    connection = connection,
                    assist = AssistUiState(level = 3, levels = 5, confirmed = 3),
                ).copy(checked = connection != Connection.CONNECTING)
            if (connection ==
                Connection.entries.first { it != Connection.CONNECTED }
            ) {
                show()
            } else {
                screen.compose.waitForIdle()
            }
            assertTrue("$connection", !shows(text(R.string.assist_status_confirmed, 3)))
            assertTrue("$connection", !shows(text(R.string.assist_value, 3, 5)))
            screen.compose.onNode(hasContentDescription(text(R.string.assist_spoken_none))).assertExists()
            // Greyed out and disabled: no segment and not the switch takes a tap.
            for (level in 0..5) {
                screen.compose
                    .onAllNodes(hasContentDescription(text(R.string.assist_segment, level)))
                    .fetchSemanticsNodes()
                    .forEach { assertTrue("$connection $level", SemanticsProperties.Disabled in it.config) }
            }
            screen.compose.onNode(hasText(text(R.string.keep_assist_title))).assertIsNotEnabled()
        }
        // Connected and checked: every segment and the switch take taps.
        current = state(Verification.VERIFIED, assist = AssistUiState(level = 3, levels = 5))
        screen.compose.waitForIdle()
        for (level in 0..5) {
            screen.compose.onNode(hasContentDescription(text(R.string.assist_segment, level))).assertIsEnabled()
        }
        screen.compose.onNode(hasText(text(R.string.keep_assist_title))).assertIsEnabled()
    }

    @Test
    fun beforeTheCheckTheAssistBarIsAPlaceholderThatTakesNoTapWhateverTheConnection() {
        show()
        for (connection in Connection.entries) {
            current =
                state(Verification.VERIFIED, connection = connection)
                    .copy(checked = false, assist = AssistUiState(level = null, levels = null))
            screen.compose.waitForIdle()
            // Screen readers skip it; the heading says there is no reading.
            val segments = screen.compose.onAllNodes(hasContentDescription(text(R.string.assist_segment, 3)))
            assertTrue("$connection", segments.fetchSemanticsNodes().isEmpty())
            screen.compose.onNode(hasContentDescription(text(R.string.assist_spoken_none))).assertExists()
            screen.compose.onNode(hasText(text(R.string.keep_assist_title))).assertIsNotEnabled()
        }
        // Whatever the connection reads, only a checked bike whose profile gives its levels takes taps.
        for (connected in listOf(false, true)) {
            assertTrue(!assistTakesTaps(connected, checked = false, settable = true))
            assertTrue(!assistTakesTaps(connected, checked = true, settable = false))
        }
        assertTrue(!assistTakesTaps(connected = false, checked = true, settable = true))
        assertTrue(assistTakesTaps(connected = true, checked = true, settable = true))
    }
}
