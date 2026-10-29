package io.github.ridelabz.engwespeedtool.ui.i18n

import android.view.View
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.testing.PHONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The app's text comes from resources, so pseudo-locales reach it: long accented text, and right to left. */
@RunWith(RobolectricTestRunner::class)
class PseudoLocaleTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(qualifiers = "en-rXA-$PHONE")
    fun longAccentedText() {
        val title = app.getString(R.string.settings_title)
        assertNotEquals("Settings", title)
        assertTrue(title, title.startsWith("[") && title.endsWith("]"))
        // Placeholders and plurals still format.
        val sessions = app.resources.getQuantityString(R.plurals.settings_logs_detail, 5, 5)
        assertTrue(sessions, sessions.contains("5"))
    }

    @Test
    @Config(qualifiers = "ar-rXB-$PHONE")
    fun rightToLeft() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, app.resources.configuration.layoutDirection)
        // The direction comes with the language; the right-to-left override marks come with ar-XB's resources.
        val title = app.getString(R.string.settings_title)
        assertTrue(title, title.contains('\u202E'))
    }
}
