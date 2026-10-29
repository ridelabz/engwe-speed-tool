package io.github.ridelabz.engwespeedtool.ui.dashboard

import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import io.github.ridelabz.engwespeedtool.testing.ScreenVariant
import io.github.ridelabz.engwespeedtool.ui.dashboard.SampleDashboard.state
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A phone on its side: the content keeps its maximum width, centred, and the battery fills the card's width. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w891dp-h411dp-land-xhdpi")
class LandscapeScreenshotTest {
    @get:Rule
    val screen = ScreenRule(ScreenVariant.DARK)

    @Test
    fun dashboard() {
        screen.setScreen { DashboardScreen(state(battery = 18), DashboardActions.None) }
        screen.capture("dashboard", "45_landscape_dark")
    }
}
