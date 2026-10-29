package io.github.ridelabz.engwespeedtool.ui.about

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The licences the app shows are the licences, word for word. */
class LicenceFilesTest {
    private val raw = File("src/main/res/raw")

    @Test
    fun theAppsLicenceIsTheRepositorysLicence() {
        assertArrayEquals(File("../LICENSE").readBytes(), File(raw, "licence_gpl_3_0.txt").readBytes())
    }

    @Test
    fun theApacheLicenceIsTheOfficialText() {
        val digest = MessageDigest.getInstance("SHA-256").digest(File(raw, "licence_apache_2_0.txt").readBytes())
        // SHA-256 of https://www.apache.org/licenses/LICENSE-2.0.txt
        assertEquals(
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            digest.joinToString("") { "%02x".format(it) },
        )
    }
}
