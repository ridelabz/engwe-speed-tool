package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import io.github.ridelabz.engwespeedtool.testing.recordingScreenshots
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * At 200 % font the statements scroll behind the pinned "I accept" bar; taps on the bar must not tick
 * them. Needs real text layout (Robolectric's native graphics, as in CI); with legacy graphics the text is
 * too small to reach the bar and the test is skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RiskAcceptanceLargeFontTest {
    @get:Rule
    val screen = ScreenRule(ScreenVariant.LIGHT_FONT_200)

    private fun text(
        @StringRes id: Int,
    ) = RuntimeEnvironment.getApplication().getString(id)

    private fun bounds(label: String): Rect =
        screen.compose
            .onNodeWithText(label)
            .fetchSemanticsNode()
            .boundsInRoot

    @Test
    fun theBottomBarDoesNotPassTapsToStatementsBehindIt() {
        screen.setScreen { RiskAcceptanceScreen(onAccept = {}, onBack = {}) }
        screen.compose.waitForIdle()
        val statements = riskStatements(RuntimeEnvironment.getApplication().resources).map(::text)
        val bar = listOf(R.string.legal_accept_hint, R.string.legal_accept).map { bounds(text(it)) }
        val behindBar =
            statements.flatMap { statement -> bar.map { it.intersect(bounds(statement)) } }.filter {
                it.width > 2f &&
                    it.height > 2f
            }
        val message = "no statement lies behind the bar with this text layout"
        // Screenshots mean native graphics, where the layout is real: then the setup must hold.
        if (recordingScreenshots) {
            assertTrue(
                message,
                behindBar.isNotEmpty(),
            )
        } else {
            assumeTrue(message, behindBar.isNotEmpty())
        }
        for (area in behindBar) screen.compose.onRoot().performTouchInput { click(area.center) }
        for (statement in statements) screen.compose.onNodeWithText(statement).assertIsOff()
    }
}
