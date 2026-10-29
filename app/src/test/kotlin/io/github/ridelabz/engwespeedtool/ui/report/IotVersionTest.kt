package io.github.ridelabz.engwespeedtool.ui.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IotVersionTest {
    @Test
    fun `takes letters, digits, dots and dashes, up to eleven, without spaces`() {
        assertEquals("218CAN", IotVersion.accepted("218CAN"))
        assertEquals("V1.2-3", IotVersion.accepted("V1.2-3"))
        assertEquals("218can", IotVersion.accepted(" 218 can "))
        assertEquals("", IotVersion.accepted(""))
        assertEquals("123456CANXY", IotVersion.accepted("123456CANXY"))
        assertNull("twelve is too long", IotVersion.accepted("123456CANXYZ"))
        assertEquals("eight hex digits pass", "218CA0FE", IotVersion.accepted("218CA0FE"))
    }

    @Test
    fun `refuses anything else whole instead of trimming it into shape`() {
        val refused =
            listOf(
                "D4:2B:71:9E:5A:C3", // an address, with separators
                "D42B719E5AC3", // an address, without
                "D42B719E5AC", // most of an address, typed by hand
                "ABCD1234EFGH5678", // a long serial
                "+34 612 345 678", // a phone number
                "612345678", // a phone number without separators
                "1234567", // seven digits in a row
                "rider85@x.com", // an e-mail address
                "２１８CAN", // full-width digits
                "218CAN✓",
                "218/CAN",
            )
        for (input in refused) {
            assertNull("accepted \"$input\"", IotVersion.accepted(input))
            assertNull("shared \"$input\"", IotVersion.shared(input))
        }
    }

    @Test
    fun `shares capitals, and nothing without a digit`() {
        assertEquals("218CAN", IotVersion.shared("218can"))
        assertEquals("V1.2.3", IotVersion.shared(" v1.2.3 "))
        assertNull(IotVersion.shared(""))
        assertNull(IotVersion.shared("   "))
        assertNull(IotVersion.shared("latest"))
    }

    @Test
    fun `says when something typed won't be shared`() {
        assertTrue(IotVersion.unusable("CAN"))
        assertFalse(IotVersion.unusable(""))
        assertFalse(IotVersion.unusable("218CAN"))
    }
}
