package io.github.ridelabz.engwespeedtool.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Deleting the stored logs asks first; the language row shows only where Android has the settings for it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SettingsScreenTest {
    @get:Rule
    val screen = ScreenRule()

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private val state =
        SettingsUiState(
            bikeName = null,
            keepSpeed = false,
            activeKmh = null,
            keepScreenOn = true,
            logSessions = 5,
        )

    private fun count(id: Int) =
        screen.compose
            .onAllNodesWithText(text(id))
            .fetchSemanticsNodes()
            .size

    @Test
    fun deletingStoredLogsAsksFirst() {
        var deleted = 0
        screen.setScreen { SettingsScreen(state, SettingsActions(onDeleteLogs = { deleted++ }), onBack = {}) }

        fun openDialog() {
            screen.compose
                .onNodeWithText(text(R.string.settings_logs_delete))
                .performScrollTo()
                .performClick()
            screen.compose.waitForIdle()
        }
        openDialog()
        screen.compose.onNodeWithText(text(R.string.warning_cancel)).performClick()
        screen.compose.waitForIdle()
        assertEquals("cancel deletes nothing", 0, deleted)
        openDialog()
        screen.compose.onNodeWithText(text(R.string.settings_logs_delete_confirm)).performClick()
        screen.compose.waitForIdle()
        assertEquals(1, deleted)
        assertEquals(1, count(R.string.settings_logs_deleted))
    }

    @Test
    fun theLanguageRowShowsOnlyWithAndroidsLanguageSettings() {
        var withLanguage by mutableStateOf(false)
        screen.setScreen {
            SettingsScreen(state, SettingsActions(onLanguage = if (withLanguage) ({}) else null), onBack = {})
        }
        assertEquals(0, count(R.string.settings_language))
        withLanguage = true
        screen.compose.waitForIdle()
        assertEquals(1, count(R.string.settings_language))
    }
}
