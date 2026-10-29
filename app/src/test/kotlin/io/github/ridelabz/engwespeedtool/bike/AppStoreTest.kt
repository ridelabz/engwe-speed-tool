package io.github.ridelabz.engwespeedtool.bike

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.ui.legal.Acceptance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class AppStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bike = BikeAddress("F0:12:34:56:78:9A")
    private val other = BikeAddress("F0:12:34:56:78:9B")
    private val day = LocalDate.of(2026, 10, 15)

    private fun prefs(name: String = "test"): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun store(name: String = "test") = AppStore(prefs(name))

    @Test
    fun `remembers a permission refusal until it is granted, also after forgetting the bike`() {
        val s = store()
        s.saveBike(bike)
        s.permissionRefused(true)
        s.forgetBike()
        assertEquals(true, store().data.value.permissionRefused)
        s.permissionRefused(false)
        assertEquals(false, store().data.value.permissionRefused)
    }

    @Test
    fun `a damaged permission refusal reads as none`() {
        prefs("damaged-permission").edit().putString("bluetooth_permission_refused", "yes").commit()
        assertEquals(Stored.EMPTY, store("damaged-permission").data.value)
    }

    @Test
    fun `keeps the firmware caveat's text version for every bike, also after forgetting one`() {
        val s = store()
        s.saveBike(bike)
        s.seeCaveat(1)
        s.forgetBike()
        s.saveBike(other)
        assertEquals(1, store().data.value.caveatVersion)
    }

    @Test
    fun `keeps a ride test only for the saved bike after the rider offered to report, until done or forgotten`() {
        val s = store()
        s.saveBike(bike)
        s.rideTest(bike, 32, 4)
        assertNull("not offered to report", s.data.value.rideTestKmh)
        s.acknowledgeReport(bike)
        s.rideTest(other, 32, 4)
        assertNull("another bike", s.data.value.rideTestKmh)
        s.rideTest(bike, 32, 4)
        assertEquals(32, store().data.value.rideTestKmh)
        assertEquals(4, store().data.value.rideTestLog)
        s.rideTest(bike, 16, null)
        assertEquals(16, s.data.value.rideTestKmh)
        assertNull("no log of that run", s.data.value.rideTestLog)
        s.rideTestDone(32, 4)
        assertEquals("a newer test stays", 16, s.data.value.rideTestKmh)
        s.rideTestDone(16, null)
        assertNull(s.data.value.rideTestKmh)
        s.rideTest(bike, 32, 5)
        s.rideTest(bike, 16, 5)
        assertNull("each speed is asked about once", s.data.value.rideTestKmh)
        s.forgetBike()
        s.saveBike(bike)
        s.acknowledgeReport(bike)
        assertNull("forgetting the bike drops it", s.data.value.rideTestKmh)
        s.rideTest(bike, 32, 6)
        assertEquals("and what was asked", 32, s.data.value.rideTestKmh)
    }

    @Test
    fun `starts empty`() {
        assertEquals(Stored.EMPTY, store().data.value)
    }

    @Test
    fun `keeps the acceptance, the bike, its name and its acknowledgement`() {
        val s = store()
        s.accept(1, day)
        s.saveBike(bike)
        s.rename(bike, "  Trail bike  ")
        s.acknowledge(bike, 1)
        assertEquals(Stored(bike, "Trail bike", Acceptance(day, 1), 1), s.data.value)
        assertEquals("read again from the file", s.data.value, store().data.value)
    }

    @Test
    fun `another bike starts without the old name and acknowledgement, the same bike keeps them`() {
        val s = store()
        s.saveBike(bike)
        s.rename(bike, "Trail bike")
        s.acknowledge(bike, 1)
        s.saveBike(bike)
        assertEquals("Trail bike", s.data.value.bikeName)
        s.saveBike(other)
        assertEquals(Stored(other, null, null, null), s.data.value)
    }

    @Test
    fun `keep this assist level is kept per saved bike, cleared for another bike and when forgotten`() {
        val s = store()
        s.saveBike(bike)
        assertFalse(s.data.value.keepAssist)
        assertNull(s.data.value.keptAssistLevel)
        s.keepAssist(bike, true, 3)
        assertTrue(s.data.value.keepAssist)
        assertEquals(3, s.data.value.keptAssistLevel)
        assertEquals("read again from the file", s.data.value, store().data.value)
        s.keepAssist(other, true, 5)
        assertEquals("not for another bike", 3, s.data.value.keptAssistLevel)
        s.keepAssist(bike, true, null)
        assertNull(s.data.value.keptAssistLevel)
        s.keepAssist(bike, true, 2)
        s.saveBike(other)
        assertFalse(s.data.value.keepAssist)
        assertNull(s.data.value.keptAssistLevel)
        s.keepAssist(other, true, 4)
        s.forgetBike()
        assertFalse(s.data.value.keepAssist)
        assertNull(s.data.value.keptAssistLevel)
        assertFalse("nothing kept without a bike", store().data.value.keepAssist)
    }

    @Test
    fun `a name or acknowledgement meant for another bike isn't kept`() {
        val s = store()
        s.saveBike(other)
        s.rename(bike, "Trail bike")
        s.acknowledge(bike, 1)
        assertEquals(Stored(other, null, null, null), s.data.value)
    }

    @Test
    fun `a blank name goes back to My bike, a long one is cut`() {
        val s = store()
        s.saveBike(bike)
        s.rename(bike, "x".repeat(100))
        assertEquals(
            BIKE_NAME_MAX,
            s.data.value.bikeName!!
                .length,
        )
        s.rename(bike, "   ")
        assertNull(s.data.value.bikeName)
    }

    @Test
    fun `forgetting the bike keeps the acceptance, and no acknowledgement without a bike`() {
        val s = store()
        s.accept(1, day)
        s.saveBike(bike)
        s.rename(bike, "Trail bike")
        s.acknowledge(bike, 1)
        s.forgetBike()
        s.acknowledge(bike, 1)
        assertEquals(Stored(null, null, Acceptance(day, 1), null), s.data.value)
    }

    @Test
    fun `damaged values count as missing`() {
        prefs("damaged")
            .edit()
            .putString("bike", "not an address")
            .putString("bike_name", "Trail bike")
            .putInt("acknowledged_version", 1)
            .putString("accepted_version", "one")
            .putLong("accepted_on", 1L)
            .putString("keep_screen_on", "no")
            .commit()
        assertEquals(Stored.EMPTY, store("damaged").data.value)
        prefs("half").edit().putInt("accepted_version", 1).commit()
        assertNull("an acceptance without its date isn't one", store("half").data.value.acceptance)
        prefs("far")
            .edit()
            .putInt("accepted_version", 1)
            .putLong("accepted_on", Long.MAX_VALUE)
            .commit()
        assertNull("a date out of range isn't one", store("far").data.value.acceptance)
    }

    @Test
    fun `a damaged bike entry doesn't stop a new bike from being saved`() {
        prefs("wrong type").edit().putInt("bike", 1).commit()
        val s = store("wrong type")
        s.saveBike(bike)
        s.rename(bike, "Trail bike")
        s.acknowledge(bike, 1)
        assertEquals(Stored(bike, "Trail bike", null, 1), s.data.value)
    }

    @Test
    fun `printing what is stored shows neither the address nor the name`() {
        val s = store()
        s.saveBike(bike)
        s.rename(bike, "Trail bike")
        val text = s.data.value.toString()
        assertFalse(text.contains("12:34"))
        assertFalse(text.contains("Trail"))
    }

    @Test
    fun `values kept by earlier versions are deleted, and the rest stays`() {
        for (old in listOf<SharedPreferences.Editor.() -> Unit>(
            { putString("firmware_tag", "OMS1218") },
            { putInt("firmware_tag", 7) },
            { putBoolean("show_more_data", false) },
            { putBoolean("lock_explained", true) },
            {
                putString("firmware_tag", "OMS1218")
                putBoolean("show_more_data", true)
                putBoolean("lock_explained", false)
            },
        )) {
            prefs("old")
                .edit()
                .clear()
                .putString("bike", bike.value)
                .putInt("acknowledged_version", 1)
                .putBoolean("keep_screen_on", false)
                .putInt("log_saved_number", 3)
                .apply(old)
                .commit()
            val s = store("old")
            assertEquals(
                setOf("bike", "acknowledged_version", "keep_screen_on", "log_saved_number"),
                prefs("old").all.keys,
            )
            assertEquals(Stored(bike, null, null, 1, keepScreenOn = false), s.data.value)
        }
    }

    @Test
    fun `session-log files are numbered from 1 each day, counted only once saved`() {
        val s = store("logs")
        val other = day.plusDays(1)
        assertEquals(1, s.nextLogNumber(day))
        assertEquals("asking again counts nothing", 1, s.nextLogNumber(day))
        s.logSaved(day, 1)
        assertEquals(2, s.nextLogNumber(day))
        s.logSaved(day, 2)
        assertEquals("read again from the file", 3, store("logs").nextLogNumber(day))
        assertEquals("a new day starts at 1", 1, s.nextLogNumber(other))
        s.forgetBike()
        assertEquals("forgetting the bike keeps the count", 3, s.nextLogNumber(day))
        assertEquals("nothing about logs in what the app shows", Stored.EMPTY, s.data.value)
        assertEquals(setOf("log_saved_on", "log_saved_number"), prefs("logs").all.keys)
    }

    @Test
    fun `a damaged or exhausted log count starts again at 1`() {
        for (count in listOf<SharedPreferences.Editor.() -> Unit>(
            { putString("log_saved_number", "7") },
            { putInt("log_saved_number", 0) },
            { putInt("log_saved_number", -3) },
            { putInt("log_saved_number", Int.MAX_VALUE) },
        )) {
            prefs("bad log")
                .edit()
                .clear()
                .putLong("log_saved_on", day.toEpochDay())
                .apply(count)
                .commit()
            assertEquals(1, store("bad log").nextLogNumber(day))
        }
        prefs("bad day")
            .edit()
            .putString("log_saved_on", "x")
            .putInt("log_saved_number", 4)
            .commit()
        assertEquals(1, store("bad day").nextLogNumber(day))
    }

    @Test
    fun `the app has one store`() {
        assertSame(AppStore.get(context), AppStore.get(context))
    }

    @Test
    fun `the display setting is on until turned off, and stays when the bike is forgotten`() {
        val store = store()
        assertTrue(store.data.value.keepScreenOn)
        store.saveBike(bike)
        store.keepScreenOn(false)
        store.forgetBike()
        assertFalse(store.data.value.keepScreenOn)
        val again = store()
        assertFalse(again.data.value.keepScreenOn)
        again.keepScreenOn(true)
        assertTrue(again.data.value.keepScreenOn)
    }
}
