package io.github.ridelabz.engwespeedtool.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The warning above 25: the stronger one at 45, each with "Don't show again" for its speed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SpeedWarningTest {
    @get:Rule
    val screen = ScreenRule()

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun theStrongerWarningAt45() {
        var kmh by mutableIntStateOf(32)
        screen.setScreen {
            SpeedWarningContent(kmh = kmh, purpose = WarningPurpose.APPLY, onConfirm = {}, onCancel = {})
        }
        screen.compose.onNodeWithText(app.getString(R.string.warning_title)).assertExists()
        screen.compose.onNodeWithText(app.getString(R.string.warning_dont_show_again, 32)).assertExists()
        kmh = 45
        screen.compose.onNodeWithText(app.getString(R.string.warning_title_strong)).assertExists()
        screen.compose.onNodeWithText(app.getString(R.string.warning_dont_show_again, 45)).assertExists()
        screen.capture("dashboard", "28_warning_45")
    }
}
