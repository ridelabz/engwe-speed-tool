package io.github.ridelabz.engwespeedtool.ui

import io.github.ridelabz.engwespeedtool.testing.PHONE
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** A saved session log's suggested name: the log's date and a number, in ASCII digits in every language. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class LogFileNameTest {
    private val date = LocalDate.of(2026, 10, 15)

    private fun name(number: Int) = logFileName(RuntimeEnvironment.getApplication().resources, date, number)

    @Test
    fun `holds the date and the number, no time of day`() {
        assertEquals("engwe-speed-tool-2026-10-15-2.log", name(2))
        assertEquals("engwe-speed-tool-2026-10-15-12.log", name(12))
    }

    @Test
    @Config(qualifiers = "fa-$PHONE")
    fun `uses ASCII digits on a phone whose language has its own`() {
        assertEquals("engwe-speed-tool-2026-10-15-12.log", name(12))
    }

    @Test
    @Config(qualifiers = "ar-$PHONE")
    fun `uses ASCII digits in Arabic too`() {
        assertEquals("engwe-speed-tool-2026-10-15-12.log", name(12))
    }
}
