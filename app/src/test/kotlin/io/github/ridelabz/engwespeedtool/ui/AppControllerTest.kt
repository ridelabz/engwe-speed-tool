@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.AppStore
import io.github.ridelabz.engwespeedtool.bike.BikeServices
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.UnverifiedReason
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.reporting.IssueField
import io.github.ridelabz.engwespeedtool.session.BikeAddress
import io.github.ridelabz.engwespeedtool.session.CompatibilityOutcome
import io.github.ridelabz.engwespeedtool.session.FakeBike
import io.github.ridelabz.engwespeedtool.session.FakeBikeTransport
import io.github.ridelabz.engwespeedtool.session.RadioState
import io.github.ridelabz.engwespeedtool.ui.dashboard.Connection
import io.github.ridelabz.engwespeedtool.ui.dashboard.Lights
import io.github.ridelabz.engwespeedtool.ui.dashboard.NotSent
import io.github.ridelabz.engwespeedtool.ui.dashboard.PowerNotSent
import io.github.ridelabz.engwespeedtool.ui.dashboard.Reapply
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedLimitStatus
import io.github.ridelabz.engwespeedtool.ui.dashboard.SpeedNotSent
import io.github.ridelabz.engwespeedtool.ui.dashboard.WarningPurpose
import io.github.ridelabz.engwespeedtool.ui.nav.Navigator
import io.github.ridelabz.engwespeedtool.ui.nav.Route
import io.github.ridelabz.engwespeedtool.ui.onboarding.CheckResult
import io.github.ridelabz.engwespeedtool.ui.onboarding.FindBikeUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.PermissionUiState
import io.github.ridelabz.engwespeedtool.ui.onboarding.SignalStrength
import io.github.ridelabz.engwespeedtool.ui.report.LogSaved
import io.github.ridelabz.engwespeedtool.ui.report.ReportAnswers
import io.github.ridelabz.engwespeedtool.ui.report.ReportLabels
import io.github.ridelabz.engwespeedtool.ui.report.ReportPreview
import io.github.ridelabz.engwespeedtool.ui.report.ReportSending
import io.github.ridelabz.engwespeedtool.ui.report.ReportSent
import io.github.ridelabz.engwespeedtool.ui.report.ReportType
import io.github.ridelabz.engwespeedtool.ui.report.RideTest
import io.github.ridelabz.engwespeedtool.ui.report.RideTestRestart
import io.github.ridelabz.engwespeedtool.ui.report.RideTestStop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** The app's state on simulated bikes behind the real session, as the screens see it. */
@RunWith(RobolectricTestRunner::class)
class AppControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val version = context.resources.getInteger(R.integer.legal_text_version)

    private inner class Setup(
        val scope: TestScope,
        noBikes: Boolean = false,
        /** The near bike lacks the bike service, so it fails the protocol checks. */
        incompatible: Boolean = false,
        nearAddress: BikeAddress = FakeBike.DEMO_ADDRESS,
        downloads: LogDownloads? = null,
    ) {
        val store = AppStore(context.getSharedPreferences("test-${System.nanoTime()}", Context.MODE_PRIVATE))
        val near =
            if (incompatible) {
                FakeBike(scope.backgroundScope, nearAddress, rssi = -55, services = emptyList())
            } else {
                FakeBike(scope.backgroundScope, nearAddress, rssi = -55)
            }
        val far =
            FakeBike(scope.backgroundScope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL, rssi = -88)
        val transport = if (noBikes) FakeBikeTransport() else FakeBikeTransport(near, far)
        var realCreated = 0
        val services =
            BikeServices(context, store) { s ->
                realCreated++
                simulated(transport, s)
            }

        // Logs are written and read on virtual time too, so a wait never depends on a real thread.
        val controller =
            AppController(
                scope.backgroundScope,
                services,
                bikeLabel = "ENGWE bike",
                clock = ::at,
                storage = StandardTestDispatcher(scope.testScheduler),
                downloads = downloads,
            )

        /** The time the controller's clock always shows. */
        fun at(): Instant = Instant.parse("2026-10-16T10:30:00Z")

        /** What the risk-acceptance screen records. */
        fun accept() = services.acceptRisk(LocalDate.of(2026, 10, 15))

        /** Lets virtual time pass until [done], at most [seconds]. */
        fun until(
            seconds: Int = 60,
            done: () -> Boolean,
        ) {
            repeat(seconds * 10) {
                if (done()) return
                scope.testScheduler.advanceTimeBy(100)
                scope.testScheduler.runCurrent()
            }
            assertTrue("didn't happen in $seconds s", done())
        }

        fun pass(seconds: Int) {
            scope.testScheduler.advanceTimeBy(seconds * 1000L)
            scope.testScheduler.runCurrent()
        }

        fun found() = (controller.findBike as FindBikeUiState.Searching).bikes

        /** What the acknowledgement screen does when the rider continues. */
        fun acknowledge(): Boolean {
            val handle = controller.unverifiedAcknowledgement() ?: return false
            handle.recordUnverifiedAcknowledgement()
            controller.unverifiedAcknowledged(reportResults = false)
            return true
        }

        /** First run up to a checked bike: accept, find, pick the bike at [index]. */
        fun checkBike(index: Int): CheckResult {
            accept()
            controller.startScan()
            until { (controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            controller.startCheck(found()[index])
            until { controller.check.result != null }
            return checkNotNull(controller.check.result)
        }
    }

    @Test
    fun `opens on the first run, and on the dashboard once a bike is saved and the risks accepted`() =
        runTest {
            val s = Setup(this)
            assertEquals(listOf(Route.WELCOME), s.controller.startRoutes())
            s.store.saveBike(s.near.address)
            assertEquals("the risks first, then back to it", listOf(Route.RISK_ACCEPTANCE), s.controller.startRoutes())
            s.accept()
            s.pass(0)
            assertEquals(
                "the caveat once, over it",
                listOf(Route.DASHBOARD, Route.FIRMWARE_CAVEAT),
                s.controller.startRoutes(),
            )
            s.store.seeCaveat(version)
            s.pass(0)
            assertEquals(listOf(Route.DASHBOARD), s.controller.startRoutes())
        }

    @Test
    fun `finds bikes strongest first, checks the verified one with one identity read and saves it`() =
        runTest {
            val s = Setup(this)
            val result = s.checkBike(0)
            assertEquals(listOf(SignalStrength.STRONG, SignalStrength.WEAK), s.found().map { it.signal })
            assertEquals(CheckResult.Verified("ENGWE E26 3.0 Pro"), result)
            s.controller.confirmBike(result)
            assertEquals(s.near.address, s.store.data.value.bike)
            assertEquals(listOf(BikeCommand.ReadIdentity), s.near.commands)
            assertTrue(s.far.commands.isEmpty())
            s.until { s.controller.dashboard.ride != null }
            val d = s.controller.dashboard
            assertEquals(Connection.CONNECTED, d.connection)
            assertEquals(Verification.VERIFIED, d.verification)
            assertEquals(SpeedLimitStatus.Unknown, d.speedLimit.status)
        }

    @Test
    fun `a bike of an untested model is saved only after the acknowledgement`() =
        runTest {
            val s = Setup(this)
            val result = s.checkBike(1)
            assertEquals(CheckResult.Unverified(FakeBike.DEMO_OTHER_MODEL, UnverifiedReason.UNKNOWN_MODEL), result)
            s.controller.confirmBike(result)
            assertNull(s.store.data.value.bike)
            assertTrue(s.acknowledge())
            assertEquals(s.far.address, s.store.data.value.bike)
            assertEquals(version, s.store.data.value.acknowledgedVersion)
            assertEquals(Verification.UNVERIFIED, s.controller.dashboard.verification)
        }

    @Test
    fun `without the rider's acceptance the check writes nothing`() =
        runTest {
            val s = Setup(this)
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            s.controller.startCheck(s.found()[0])
            s.until { s.controller.check.result != null }
            assertEquals(CheckResult.NotAccepted, s.controller.check.result)
            assertTrue(s.near.commands.isEmpty())
        }

    @Test
    fun `nothing found after 20 seconds, and Bluetooth off, show as such`() =
        runTest {
            val s = Setup(this, noBikes = true)
            s.controller.startScan()
            s.pass(19)
            assertTrue(s.controller.findBike is FindBikeUiState.Searching)
            s.pass(2)
            assertEquals(FindBikeUiState.NothingFound, s.controller.findBike)
            s.transport.setRadio(RadioState.OFF)
            s.controller.startScan()
            s.pass(1)
            assertEquals(FindBikeUiState.BluetoothOff, s.controller.findBike)
            assertEquals(Connection.BLUETOOTH_OFF, s.controller.dashboard.connection)
        }

    @Test
    fun `the saved bike connects while the app is in front, and the connection ends behind it`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.store.saveBike(s.near.address)
            s.pass(0)
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            s.controller.onBackground()
            s.pass(1)
            assertFalse(s.near.connected)
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertEquals("one identity read per connection", 2, s.near.commands.size)
        }

    @Test
    fun `display off shows the bike off, and after it switched on the card says what a restart did to the limit`() =
        runTest {
            val s = Setup(this)
            s.controller.confirmBike(s.checkBike(0))
            s.until { s.controller.dashboard.ride != null }
            assertTrue("a verified bike's restarts count", s.controller.dashboard.speedLimit.restartsTrusted)
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.near.switchOn()
            // Nothing set on this connection: the bike's own limit, as the project's tests showed.
            s.until(10) { s.controller.dashboard.speedLimit.status == SpeedLimitStatus.BikeDefault }
            assertEquals(Connection.CONNECTED, s.controller.dashboard.connection)
            // After a speed was set, a restart reads as such.
            s.store.seeCaveat(version)
            s.controller.applySpeed(16)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.near.switchOn()
            s.until(10) { s.controller.dashboard.speedLimit.status == SpeedLimitStatus.BikeRestarted }
            assertEquals("nothing set again without keep-speed", listOf(16), s.near.speeds())
        }

    @Test
    fun `restarts count for the wording only once a verified bike is checked, and not during a compatibility check`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.store.seeCaveat(version)
            s.store.saveBike(s.near.address)
            s.pass(0)
            assertFalse("nothing known before connecting", s.controller.settings.restartsTrusted)
            s.controller.onForeground()
            s.until { s.controller.dashboard.checked && s.controller.dashboard.connection == Connection.CONNECTED }
            assertTrue(s.controller.settings.restartsTrusted)
            s.controller.runCompatibilityCheck()
            s.pass(0)
            assertFalse("during the check", s.controller.settings.restartsTrusted)
        }

    @Test
    fun `an untested model's restarts don't count, as its power flags aren't verified`() =
        runTest {
            val s = Setup(this)
            s.onDashboard(s.far)
            s.until { s.controller.dashboard.checked && s.controller.dashboard.connection == Connection.CONNECTED }
            assertFalse(s.controller.dashboard.speedLimit.restartsTrusted)
            assertFalse(s.controller.settings.restartsTrusted)
        }

    @Test
    fun `demo mode runs on simulated bikes without touching the phone's Bluetooth`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startDemo()
            assertTrue(s.controller.demo)
            s.until { s.controller.dashboard.ride != null }
            assertEquals(Connection.CONNECTED, s.controller.dashboard.connection)
            assertEquals(0, s.realCreated)
            assertNull("no Bluetooth prompt in demo mode", s.controller.enableBluetoothIntent())
            s.controller.leaveDemo()
            assertFalse(s.controller.demo)
            assertEquals(0, s.realCreated)
        }

    @Test
    fun `rename and forget change the saved bike, and forgetting ends its connection`() =
        runTest {
            val s = Setup(this)
            s.controller.confirmBike(s.checkBike(0))
            s.controller.rename("Trail bike")
            s.pass(0)
            assertEquals("Trail bike", s.controller.settings.bikeName)
            val navigator = Navigator(listOf(Route.DASHBOARD, Route.SETTINGS))
            s.controller.forgetBike(navigator)
            s.pass(1)
            assertNull(s.store.data.value.bike)
            assertFalse(s.near.connected)
            assertEquals(listOf(Route.WELCOME, Route.RISK_ACCEPTANCE, Route.BLUETOOTH), navigator.routes)
            assertNotNull("the acceptance stays", s.controller.acceptance)
        }

    @Test
    fun `the find list names every bike alike and gives each a key it keeps while it is heard`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            val first = s.found().associate { it.signal to it.key }
            assertEquals(listOf("ENGWE bike", "ENGWE bike"), s.found().map { it.name })
            s.pass(5)
            assertEquals(first, s.found().associate { it.signal to it.key })
            s.controller.stopScan()
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            assertEquals("keys start over with a new search", setOf(1, 2), s.found().map { it.key }.toSet())
        }

    @Test
    fun `going behind another app during a check ends it, and coming back runs it again`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            s.controller.startCheck(s.found()[0])
            s.pass(0)
            s.controller.onBackground()
            s.pass(2)
            assertFalse(s.near.connected)
            assertNull("no result from a check that was cut short", s.controller.check.result)
            s.controller.onForeground()
            s.until { s.controller.check.result != null }
            assertEquals(CheckResult.Verified("ENGWE E26 3.0 Pro"), s.controller.check.result)
            assertTrue(s.near.connected)
        }

    @Test
    fun `retry checks the same bike again, and back from the check ends its connection and forgets the bike`() =
        runTest {
            val s = Setup(this)
            s.checkBike(0)
            s.controller.retryCheck()
            s.pass(0)
            assertNull("a new run", s.controller.check.result)
            s.until { s.controller.check.result != null }
            assertEquals(CheckResult.Verified("ENGWE E26 3.0 Pro"), s.controller.check.result)
            assertEquals("one identity read per connection", 2, s.near.commands.size)
            s.controller.cancelCheck()
            s.pass(1)
            assertFalse(s.near.connected)
            s.controller.retryCheck()
            s.pass(5)
            assertFalse("nothing left to retry", s.near.connected)
        }

    @Test
    fun `coming back to the front claims nothing about the bike until it is checked again`() =
        runTest {
            val s = Setup(this)
            s.controller.confirmBike(s.checkBike(0))
            s.until { s.controller.dashboard.ride != null }
            assertTrue(s.controller.dashboard.verified)
            s.controller.onBackground()
            s.pass(1)
            s.controller.onForeground()
            val d = s.controller.dashboard
            assertEquals(Connection.CONNECTING, d.connection)
            assertFalse("not checked yet", d.checked)
            assertFalse(d.verified)
            assertFalse(d.unverified)
            assertNull(d.ride)
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertTrue(s.controller.dashboard.verified)
        }

    @Test
    fun `a saved bike that stops responding as expected is sent nothing until the rider tries again`() =
        runTest {
            val s = Setup(this, incompatible = true)
            s.accept()
            s.store.saveBike(s.near.address)
            s.pass(0)
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.INCOMPATIBLE }
            assertNull(s.controller.dashboard.ride)
            assertFalse(s.controller.dashboard.checked)
            assertTrue(s.near.commands.isEmpty())
            s.pass(60)
            assertEquals("no reconnecting by itself", Connection.INCOMPATIBLE, s.controller.dashboard.connection)
            s.controller.retryConnection()
            s.pass(0)
            assertEquals(Connection.CONNECTING, s.controller.dashboard.connection)
            s.until { s.controller.dashboard.connection == Connection.INCOMPATIBLE }
            assertTrue(s.near.commands.isEmpty())
        }

    @Test
    fun `a saved bike that can't be found yet is said so while the app keeps trying`() =
        runTest {
            val s = Setup(this, noBikes = true)
            s.accept()
            s.store.saveBike(FakeBike.DEMO_ADDRESS)
            s.pass(0)
            s.controller.onForeground()
            s.pass(0)
            assertFalse(s.controller.dashboard.cantReach)
            s.until { s.controller.dashboard.cantReach }
            assertEquals(Connection.CONNECTING, s.controller.dashboard.connection)
            assertFalse(s.controller.dashboard.checked)
            // Through the waits and the searches that follow, the hint stays put.
            repeat(90) {
                s.pass(1)
                assertTrue("the hint went away after ${it + 1} s", s.controller.dashboard.cantReach)
            }
        }

    @Test
    fun `the can't-reach hint goes once the bike is reached`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.store.saveBike(s.near.address)
            // Another phone holds the bike, so it doesn't advertise.
            val held = s.transport.connect(s.near.address)
            s.pass(0)
            s.controller.onForeground()
            s.until { s.controller.dashboard.cantReach }
            held.close()
            s.until(90) { s.controller.dashboard.connection == Connection.CONNECTED }
            assertFalse(s.controller.dashboard.cantReach)
        }

    @Test
    fun `a saved bike later paired with the phone is connected to directly and stays verified`() =
        runTest {
            val s = Setup(this, nearAddress = BikeAddress("C3:5A:9E:71:2B:D4"))
            s.controller.confirmBike(s.checkBike(0))
            s.controller.onBackground()
            s.pass(1)
            // Paired in Android's settings: the phone holds a link to it, so it no longer advertises.
            s.near.pairedWithPhone = true
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertTrue(s.controller.dashboard.verified)
            assertEquals(2, s.near.commands.size)
            val log = s.controller.sessionLog()
            assertTrue("paired_direct" in log)
            for (part in listOf(
                "c3:5a:9e:71:2b:d4",
                "c3-5a-9e-71-2b-d4",
                "c35a9e712bd4",
                "d42b719e5ac3",
                "d4:2b:71:9e:5a:c3",
                "d4-2b-71-9e-5a-c3",
            )) {
                assertNull("the log holds \"$part\"", log.lines().firstOrNull { part in it.lowercase() })
            }
        }

    @Test
    fun `a paired bike that isn't heard is listed, connected to directly and verified by its identity`() =
        runTest {
            val s = Setup(this)
            s.near.pairedWithPhone = true
            s.accept()
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            val paired = s.found().single { it.signal == null }
            s.controller.startCheck(paired)
            s.until { s.controller.check.result != null }
            assertEquals(CheckResult.Verified("ENGWE E26 3.0 Pro"), s.controller.check.result)
            assertTrue("no acknowledgement needed", s.controller.confirmBike(checkNotNull(s.controller.check.result)))
            assertEquals(s.near.address, s.store.data.value.bike)
            assertEquals(listOf(BikeCommand.ReadIdentity), s.near.commands)
        }

    @Test
    fun `a paired bike that can't be reached is reported as not found, not as held by another app`() =
        runTest {
            val s = Setup(this)
            s.near.pairedWithPhone = true
            s.accept()
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            // Pairing doesn't depend on presence: the bike is listed, but it is away and never answers.
            s.near.inRange = false
            s.controller.startCheck(s.found().single { it.signal == null })
            s.until(90) { s.controller.check.result != null }
            assertEquals(CheckResult.NotFound, s.controller.check.result)
            assertTrue(s.near.commands.isEmpty())
        }

    @Test
    fun `the saved bike, paired with the phone, is connected to at once and shown verified`() =
        runTest {
            val s = Setup(this)
            s.near.pairedWithPhone = true
            s.accept()
            s.store.saveBike(s.near.address)
            s.pass(0)
            s.controller.onForeground()
            s.until(5) { s.controller.dashboard.connection == Connection.CONNECTED }
            assertTrue(s.controller.dashboard.verified)
        }

    /** The saved bike [bike] on the dashboard, connected, with a status after the checks; set up (caveat seen). */
    private fun Setup.onDashboard(
        bike: FakeBike = near,
        caveatSeen: Boolean = true,
    ) {
        accept()
        if (caveatSeen) store.seeCaveat(version)
        store.saveBike(bike.address)
        pass(0)
        controller.onForeground()
        until { controller.dashboard.connection == Connection.CONNECTED && controller.dashboard.ride != null }
        pass(2)
    }

    private fun FakeBike.speeds() = commands.filterIsInstance<BikeCommand.SetSpeedLimit>().map { it.limit.kmh }

    @Test
    fun `a checked bike stays checked while the link drops and comes back, and while it is off`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            assertTrue(s.controller.dashboard.checked)
            s.near.dropLink()
            s.until { s.controller.dashboard.connection == Connection.RECONNECTING }
            assertTrue("still checked while reconnecting", s.controller.dashboard.checked)
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertTrue(s.controller.dashboard.checked)
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            assertTrue("still checked while the bike is off", s.controller.dashboard.checked)
        }

    @Test
    fun `a speed above the limit needs only the warning (the caveat was seen at setup), then it is sent once`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(32)
            assertEquals(PendingWarning(32, WarningPurpose.APPLY), s.controller.warning)
            assertTrue("nothing sent before the warning", s.near.speeds().isEmpty())
            s.controller.confirmWarning(dontShowAgain = true)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            assertEquals(listOf(32), s.near.speeds())
            assertEquals(version, s.store.data.value.caveatVersion)
            // The warning stays off for 32, the caveat is seen: the next 32 is one tap.
            s.controller.applySpeed(16)
            s.until { (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh == 16 }
            s.controller.applySpeed(32)
            assertNull(s.controller.warning)
            s.until { (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh == 32 }
            assertEquals(listOf(32, 16, 32), s.near.speeds())
            assertEquals(32, s.controller.settings.activeKmh)
            s.controller.resetWarnings()
            s.controller.applySpeed(32)
            assertEquals("warnings show again after a reset", 32, s.controller.warning?.kmh)
            s.controller.cancelWarning()
            assertEquals(3, s.near.speeds().size)
        }

    @Test
    fun `45 asks first with the stronger warning, then is sent once, and its warning can be turned off`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(45)
            assertEquals(PendingWarning(45, WarningPurpose.APPLY), s.controller.warning)
            assertTrue("nothing sent before the warning", s.near.speeds().isEmpty())
            s.controller.confirmWarning(dontShowAgain = true)
            s.until { (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh == 45 }
            assertEquals(listOf(45), s.near.speeds())
            s.controller.applySpeed(25)
            s.until { (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh == 25 }
            s.controller.applySpeed(45)
            assertNull("turned off", s.controller.warning)
            s.until { (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh == 45 }
            assertEquals(listOf(45, 25, 45), s.near.speeds())
        }

    @Test
    fun `a warning turned off under an earlier legal text shows again`() =
        runTest {
            val s = Setup(this)
            s.store.warningOff(32, version - 1)
            s.onDashboard()
            s.controller.applySpeed(32)
            assertEquals(
                "turned off for the old text only",
                PendingWarning(32, WarningPurpose.APPLY),
                s.controller.warning,
            )
            s.controller.cancelWarning()
            s.store.warningOff(32, version)
            s.controller.applySpeed(32)
            assertNull("turned off for this text", s.controller.warning)
        }

    @Test
    fun `the caveat shows once at setup, over the dashboard for riders set up before, never on a tap`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.store.saveBike(s.near.address)
            s.pass(0)
            assertTrue(s.controller.caveatNeeded)
            assertEquals(listOf(Route.DASHBOARD, Route.FIRMWARE_CAVEAT), s.controller.startRoutes())
            // What the caveat screen does when the rider continues.
            s.controller.setupCaveat.recordFirmwareCaveat()
            s.pass(0)
            assertEquals(version, s.store.data.value.caveatVersion)
            assertFalse(s.controller.caveatNeeded)
            assertEquals(listOf(Route.DASHBOARD), s.controller.startRoutes())
        }

    @Test
    fun `if the caveat was skipped, a speed other than 25 isn't sent and asks for it, 25 goes`() =
        runTest {
            val s = Setup(this)
            s.onDashboard(caveatSeen = false)
            s.controller.applySpeed(32)
            s.controller.confirmWarning(dontShowAgain = false)
            s.until { s.controller.consentNeeded == ConsentNeeded.FIRMWARE_CAVEAT }
            assertEquals(SpeedNotSent(32, NotSent.NOT_YET), s.controller.dashboard.speedLimit.notSent)
            s.controller.consentOpened()
            s.controller.applySpeed(25)
            s.until { s.near.speeds() == listOf(25) }
            assertNull(s.controller.consentNeeded)
        }

    @Test
    fun `after a new legal text, the dashboard still connects and goes back to 25, other speeds ask for the text`() =
        runTest {
            val s = Setup(this)
            s.store.accept(version - 1, LocalDate.of(2026, 1, 2))
            s.store.seeCaveat(version)
            s.store.saveBike(s.near.address)
            s.pass(0)
            assertEquals(listOf(Route.DASHBOARD, Route.RISK_ACCEPTANCE), s.controller.startRoutes())
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED && s.controller.dashboard.ride != null }
            s.pass(2)
            s.controller.applySpeed(16)
            s.until { s.controller.consentNeeded == ConsentNeeded.RISK_ACCEPTANCE }
            s.controller.consentOpened()
            s.controller.applySpeed(25)
            s.until { s.near.speeds() == listOf(25) }
            assertEquals(
                listOf(BikeCommand.ReadIdentity, BikeCommand.SetSpeedLimit(SpeedLimit.KMH_25)),
                s.near.commands,
            )
        }

    @Test
    fun `an unverified bike acknowledged for an earlier text goes back to 25 at once, other speeds ask again`() =
        runTest {
            val s = Setup(this)
            s.store.saveBike(s.far.address)
            s.store.acknowledge(s.far.address, version - 1)
            s.onDashboard(s.far)
            s.controller.applySpeed(25)
            s.until { s.far.speeds() == listOf(25) }
            assertNull(s.controller.consentNeeded)
            s.controller.applySpeed(16)
            assertEquals(ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT, s.controller.consentNeeded)
            assertEquals(listOf(25), s.far.speeds())
        }

    @Test
    fun `back to 25 is one tap, without a sheet`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(25)
            assertNull(s.controller.warning)
            s.until { s.controller.dashboard.speedLimit.status == SpeedLimitStatus.Confirmed(25, s.at(), null) }
            assertEquals(listOf(25), s.near.speeds())
        }

    @Test
    fun `lights switch with one command each, and a bike that is off gets nothing and says so`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.setLights(true)
            s.until { s.controller.dashboard.lights.lights == Lights.ON && !s.controller.dashboard.lights.sending }
            assertFalse(s.controller.dashboard.lights.notConfirmed)
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.controller.applySpeed(25)
            s.until {
                s.controller.dashboard.speedLimit.notSent
                    ?.reason == NotSent.BIKE_NOT_ON
            }
            assertEquals(listOf(BikeCommand.ReadIdentity, BikeCommand.SetLights(true)), s.near.commands)
        }

    private fun FakeBike.levels() = commands.filterIsInstance<BikeCommand.SetAssistLevel>().map { it.level.level }

    @Test
    fun `an assist level is one command, shown sending, then confirmed with the level the bike reports`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            val before = s.controller.dashboard.assist
            assertEquals(5, before.levels)
            assertEquals(2, before.level)
            s.controller.setAssistLevel(4)
            s.pass(0)
            assertEquals(4, s.controller.dashboard.assist.sending)
            s.controller.setAssistLevel(1) // while one is sent: nothing
            s.until { s.controller.dashboard.assist.confirmed == 4 }
            assertEquals(4, s.controller.dashboard.assist.level)
            assertNull(s.controller.dashboard.assist.sending)
            assertEquals(listOf(4), s.near.levels())
            s.controller.setAssistLevel(9) // not a level: nothing
            s.controller.setAssistLevel(0)
            s.until { s.controller.dashboard.assist.confirmed == 0 }
            assertEquals(listOf(4, 0), s.near.levels())
            // The rider changes it on the display: the app no longer claims it.
            s.near.ride = s.near.ride.copy(assistLevel = 3)
            s.until { s.controller.dashboard.assist.level == 3 }
            assertNull(s.controller.dashboard.assist.confirmed)
            assertEquals(listOf(4, 0), s.near.levels())
        }

    @Test
    fun `the dashboard says On only from a fresh status with power flags verified for the bike`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.until { s.controller.dashboard.reportsOn }
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            assertFalse(s.controller.dashboard.reportsOn)
            s.near.switchOn()
            s.until { s.controller.dashboard.reportsOn }
            s.near.dropLink()
            s.until { s.controller.dashboard.connection == Connection.RECONNECTING }
            assertFalse(s.controller.dashboard.reportsOn)

            // An untested model's power flags aren't verified for it: connected, never "On".
            val other = Setup(this)
            other.onDashboard(other.far)
            assertTrue(other.acknowledge())
            other.pass(5)
            assertEquals(Connection.CONNECTED, other.controller.dashboard.connection)
            assertFalse(other.controller.dashboard.reportsOn)
        }

    @Test
    fun `before the check no assist level is sent, and the readings say if the profile verifies them`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            assertTrue(
                "the reference profile verifies them",
                s.controller.dashboard.more!!
                    .verified,
            )
            s.controller.onBackground()
            s.pass(1)
            s.controller.onForeground()
            assertFalse(s.controller.dashboard.checked)
            assertNull(s.controller.dashboard.assist.levels)
            s.controller.setAssistLevel(3)
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            s.pass(5)
            assertTrue(s.near.levels().isEmpty())

            val other = Setup(this)
            other.onDashboard(other.far)
            assertTrue(other.acknowledge())
            other.pass(2)
            assertFalse(
                "an untested model's",
                other.controller.dashboard.more!!
                    .verified,
            )
        }

    private fun FakeBike.locks() = commands.filterIsInstance<BikeCommand.SetLocked>().map { it.locked }

    @Test
    fun `the bike turns off and on from the app, a lock and an unlock, each confirmed`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.until { s.controller.dashboard.reportsOn }
            assertNotNull(s.controller.dashboard.power)
            s.controller.setPower(false)
            s.pass(0)
            assertEquals(
                false,
                s.controller.dashboard.power
                    ?.switching,
            )
            s.controller.setPower(true) // while one is sent: nothing
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.until {
                s.controller.dashboard.power
                    ?.switching == null
            }
            assertNull(
                s.controller.dashboard.power
                    ?.notConfirmed,
            )
            assertEquals(listOf(true), s.near.locks())
            assertTrue(s.near.locked)
            s.controller.setPower(true)
            s.until { s.controller.dashboard.reportsOn }
            s.until {
                s.controller.dashboard.power
                    ?.switching == null
            }
            assertNull(
                s.controller.dashboard.power
                    ?.notConfirmed,
            )
            assertEquals(listOf(true, false), s.near.locks())
            // Nothing sends either again by itself.
            s.pass(120)
            assertEquals(listOf(true, false), s.near.locks())
        }

    @Test
    fun `turning off while the bike moves, or on while it is on, sends nothing and says why`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.until { s.controller.dashboard.reportsOn }
            s.controller.setPower(true)
            s.until {
                s.controller.dashboard.power
                    ?.notSent == PowerNotSent.BIKE_NOT_OFF
            }
            s.near.ride = s.near.ride.copy(speedKmh = 12.0)
            s.until {
                s.controller.dashboard.ride
                    ?.speedKmh == 12.0
            }
            s.controller.setPower(false)
            s.until {
                s.controller.dashboard.power
                    ?.notSent == PowerNotSent.MOVING
            }
            assertTrue(s.near.locks().isEmpty())
        }

    @Test
    fun `the bike's last state labels the button while the link is down`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.until { s.controller.dashboard.reportsOn }
            assertEquals(
                false,
                s.controller.dashboard.power
                    ?.lastOff,
            )
            s.controller.setPower(false)
            s.until { s.controller.dashboard.reportsOff }
            assertEquals(
                true,
                s.controller.dashboard.power
                    ?.lastOff,
            )
            s.near.dropLink()
            s.until { s.controller.dashboard.connection == Connection.RECONNECTING }
            assertEquals(
                "still known off",
                true,
                s.controller.dashboard.power
                    ?.lastOff,
            )
        }

    @Test
    fun `a result that comes for an earlier run says nothing, and the demo's riding bike isn't turned off`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.until { s.controller.dashboard.reportsOn }
            s.controller.setPower(false)
            s.controller.onBackground()
            s.pass(5)
            s.controller.onForeground()
            // Whether the lock reached the bike before the stop or not, its result belongs to the run before.
            s.until { s.controller.dashboard.checked && s.controller.dashboard.connection != Connection.CONNECTING }
            s.pass(3)
            assertNull(
                s.controller.dashboard.power
                    ?.notConfirmed,
            )
            assertNull(
                s.controller.dashboard.power
                    ?.notSent,
            )

            val demo = Setup(this)
            demo.accept()
            demo.controller.startDemo()
            // The simulated bike stands still for a while, then rides: never turned off while it moves.
            demo.until(30) {
                demo.controller.dashboard.reportsOn &&
                    (
                        demo.controller.dashboard.ride
                            ?.speedKmh ?: 0.0
                    ) > 0.0
            }
            demo.controller.setPower(false)
            demo.until {
                demo.controller.dashboard.power
                    ?.notSent == PowerNotSent.MOVING
            }
            assertTrue(demo.controller.dashboard.reportsOn)
            // Standing still again: Turn off and Turn on work in demo mode too.
            demo.until(90) {
                demo.controller.dashboard.ride
                    ?.speedKmh == 0.0
            }
            demo.controller.setPower(false)
            demo.until { demo.controller.dashboard.connection == Connection.BIKE_OFF }
            demo.controller.setPower(true)
            demo.until { demo.controller.dashboard.reportsOn }
            assertEquals(0, demo.realCreated)
        }

    @Test
    fun `an untested model, and any bike before its check, get no power control`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            assertNotNull(s.controller.dashboard.power)
            s.controller.onBackground()
            s.pass(1)
            s.controller.onForeground()
            assertNull("not checked yet", s.controller.dashboard.power)
            s.controller.setPower(false)
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            s.pass(3)
            assertTrue(s.near.locks().isEmpty())

            val other = Setup(this)
            other.onDashboard(other.far)
            assertTrue(other.acknowledge())
            other.pass(2)
            assertNull(other.controller.dashboard.power)
            other.controller.setPower(false)
            other.pass(5)
            assertTrue(other.far.locks().isEmpty())
        }

    @Test
    fun `a bike whose profile gives no assist levels shows its level and offers no control`() =
        runTest {
            val s = Setup(this)
            s.onDashboard(s.far)
            assertTrue(s.acknowledge())
            s.pass(2)
            val assist = s.controller.dashboard.assist
            assertEquals(2, assist.level)
            assertNull(assist.levels)
            assertFalse(assist.settable)
            s.controller.setAssistLevel(3)
            s.pass(10)
            assertTrue(s.far.levels().isEmpty())
        }

    @Test
    fun `a bike that is off gets no assist level and the card says so`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.controller.setAssistLevel(3)
            s.until { s.controller.dashboard.assist.notSent == NotSent.BIKE_NOT_ON }
            assertTrue(s.near.levels().isEmpty())
        }

    @Test
    fun `keep this assist level keeps the level set in the app and sets it again after a reconnection`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.setKeepAssist(true)
            assertTrue(s.store.data.value.keepAssist)
            assertNull("nothing set from the app yet: the next tap is kept", s.store.data.value.keptAssistLevel)
            s.controller.setAssistLevel(3)
            assertEquals("kept at the tap", 3, s.store.data.value.keptAssistLevel)
            s.until { s.controller.dashboard.assist.confirmed == 3 }
            s.pass(0)
            assertTrue(s.controller.dashboard.assist.keep)
            s.pass(60) // past the minute between re-applies
            s.near.ride = s.near.ride.copy(assistLevel = 1) // changed on the display: not kept
            s.near.dropLink()
            s.until(60) {
                s.controller.dashboard.assist.confirmed == 3 && s.controller.dashboard.assist.reapplied
            }
            assertEquals(listOf(3, 3), s.near.levels())
            assertEquals(3, s.store.data.value.keptAssistLevel)
            s.controller.setKeepAssist(false)
            assertFalse(s.store.data.value.keepAssist)
            assertNull(s.store.data.value.keptAssistLevel)
            s.pass(60)
            s.near.dropLink()
            s.pass(60)
            assertEquals("nothing kept: nothing sent again", listOf(3, 3), s.near.levels())
        }

    @Test
    fun `turning keep on keeps the level confirmed from the app, and forgetting the bike clears it`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.setAssistLevel(5)
            s.until { s.controller.dashboard.assist.confirmed == 5 }
            s.controller.setKeepAssist(true)
            assertEquals(5, s.store.data.value.keptAssistLevel)
            s.controller.forgetBike(Navigator(listOf(Route.DASHBOARD)))
            assertFalse(s.store.data.value.keepAssist)
            assertNull(s.store.data.value.keptAssistLevel)
        }

    @Test
    fun `demo mode keeps its assist level in memory only`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startDemo()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED && s.controller.dashboard.ride != null }
            s.pass(2)
            s.controller.setAssistLevel(4)
            s.until { s.controller.dashboard.assist.confirmed == 4 }
            s.controller.setKeepAssist(true)
            assertTrue(s.controller.dashboard.assist.keep)
            assertFalse(s.store.data.value.keepAssist)
            assertEquals(0, s.realCreated)
        }

    @Test
    fun `keep this speed keeps the confirmed speed, without a warning, and sets it again after a reconnection`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(32)
            s.controller.confirmWarning(dontShowAgain = false)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            s.controller.setKeepSpeed(true)
            assertNull("no warning for keeping a speed", s.controller.warning)
            assertTrue(s.store.data.value.keepSpeed)
            assertEquals(32, s.store.data.value.keptKmh)
            s.pass(0)
            assertTrue(s.controller.dashboard.speedLimit.keepSpeed)
            s.near.dropLink()
            s.until(60) {
                (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.reapply == Reapply.RECONNECT
            }
            assertEquals(listOf(32, 32), s.near.speeds())
            s.controller.setKeepSpeed(false)
            assertFalse(s.store.data.value.keepSpeed)
            s.near.dropLink()
            s.pass(60)
            assertEquals("nothing kept: nothing sent again", listOf(32, 32), s.near.speeds())
        }

    @Test
    fun `with keep-speed on, Turn off and Turn on set the kept speed again, once, and the card says so`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(32)
            s.controller.confirmWarning(dontShowAgain = false)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            s.controller.setKeepSpeed(true)
            s.pass(60) // past the minute between re-applies
            s.controller.setPower(false)
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.pass(5)
            s.controller.setPower(true)
            s.until(10) {
                (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Sending)?.reapply == Reapply.RESTART
            }
            s.until(10) {
                (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.reapply == Reapply.RESTART
            }
            s.pass(120)
            assertEquals(listOf(32, 32), s.near.speeds())
            assertEquals(listOf(true, false), s.near.locks())
            assertEquals(SpeedLimit.KMH_32, s.near.speedLimit)
        }

    @Test
    fun `without keep-speed, Turn off and Turn on leave the card saying the limit is back to 25`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(32)
            s.controller.confirmWarning(dontShowAgain = false)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            s.controller.setPower(false)
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.controller.setPower(true)
            s.until(10) { s.controller.dashboard.speedLimit.status == SpeedLimitStatus.BikeRestarted }
            s.pass(120)
            assertEquals(listOf(32), s.near.speeds())
            assertEquals(SpeedLimit.KMH_25, s.near.speedLimit)
        }

    @Test
    fun `with a newer risk text not yet accepted, Turn on still goes, Turn off asks for the text first`() =
        runTest {
            val s = Setup(this)
            s.store.accept(version - 1, LocalDate.of(2026, 10, 1))
            s.store.saveBike(s.near.address)
            s.store.seeCaveat(version)
            s.pass(0)
            s.controller.onForeground()
            s.until { s.controller.dashboard.reportsOn }
            s.controller.setPower(false)
            s.until { s.controller.consentNeeded == ConsentNeeded.RISK_ACCEPTANCE }
            s.controller.consentOpened()
            assertTrue(s.near.locks().isEmpty())
            // Switched off at its display meanwhile: turning it on needs no newer acceptance, as 25 doesn't.
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.controller.setPower(true)
            s.until { s.controller.dashboard.reportsOn }
            assertEquals(listOf(false), s.near.locks())
            assertNull(s.controller.consentNeeded)
        }

    @Test
    fun `an unverified saved bike without an acknowledgement for this text asks for it, then takes commands`() =
        runTest {
            val s = Setup(this)
            s.onDashboard(s.far)
            s.controller.setLights(true)
            s.until { s.controller.consentNeeded == ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT }
            s.controller.consentOpened()
            assertTrue(s.controller.acknowledgingSavedBike)
            assertTrue(s.far.commands.none { it is BikeCommand.SetLights })
            // What the acknowledgement screen does when the rider continues and offers to report.
            checkNotNull(s.controller.unverifiedAcknowledgement()).recordUnverifiedAcknowledgement()
            s.controller.unverifiedAcknowledged(reportResults = true)
            assertEquals(version, s.store.data.value.acknowledgedVersion)
            assertTrue(s.store.data.value.reportResults)
            s.controller.setLights(true)
            s.until { s.far.commands.any { it is BikeCommand.SetLights } }
        }

    @Test
    fun `demo mode keeps its warnings and kept speed in memory only, and never asks for the caveat`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startDemo()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED && s.controller.dashboard.ride != null }
            s.pass(2)
            assertFalse(s.controller.caveatNeeded)
            s.controller.applySpeed(32)
            s.controller.confirmWarning(dontShowAgain = true)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            s.controller.setKeepSpeed(true)
            assertNull(s.controller.warning)
            assertTrue(s.controller.dashboard.speedLimit.keepSpeed)
            val kept = s.store.data.value
            assertNull(kept.caveatVersion)
            assertFalse(kept.keepSpeed)
            assertTrue(kept.warningsOff.isEmpty())
            assertEquals(0, s.realCreated)
        }

    @Test
    fun `with keep-speed on, a tapped speed becomes the kept one even when it isn't sent`() =
        runTest {
            val s = Setup(this)
            s.store.saveBike(s.near.address)
            s.store.keepSpeed(s.near.address, true, 32)
            s.store.seeCaveat(version)
            s.onDashboard()
            s.until(10) { s.near.speeds() == listOf(32) } // the connection's re-apply
            s.pass(60) // past the minute between re-applies
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.controller.applySpeed(25)
            s.until { s.controller.dashboard.speedLimit.notSent != null }
            assertEquals(25, s.store.data.value.keptKmh)
            s.near.switchOn()
            // A new run reads the kept speed from the store.
            s.controller.onBackground()
            s.pass(1)
            s.controller.onForeground()
            s.pass(90)
            assertEquals("25 again, never 32", listOf(32, 25), s.near.speeds())
        }

    @Test
    fun `keep-speed keeps only a speed confirmed on this connection, and forgets it when turned off`() =
        runTest {
            val s = Setup(this)
            s.store.saveBike(s.near.address)
            s.store.keepSpeed(s.near.address, false, 32)
            s.onDashboard()
            s.controller.setKeepSpeed(true)
            assertNull("nothing confirmed here: no old speed, no warning", s.controller.warning)
            assertTrue(s.store.data.value.keepSpeed)
            assertNull(s.store.data.value.keptKmh)
            s.controller.setKeepSpeed(false)
            assertNull(s.store.data.value.keptKmh)
            assertTrue(s.near.speeds().isEmpty())
        }

    @Test
    fun `the keep switch waits while a speed is being sent`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(25)
            s.pass(0) // written, the echo not yet back
            assertTrue(s.controller.sendingSpeed)
            assertFalse(s.controller.settings.keepSpeedSwitchable)
            s.controller.setKeepSpeed(true)
            assertFalse(s.store.data.value.keepSpeed)
            s.until { !s.controller.sendingSpeed }
        }

    @Test
    fun `an unverified bike's acknowledgement comes first, then that tap goes on, through the warning`() =
        runTest {
            val s = Setup(this)
            s.onDashboard(s.far)
            s.controller.applySpeed(32)
            assertEquals(
                "asked before anything else",
                ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT,
                s.controller.consentNeeded,
            )
            assertNull(s.controller.warning)
            s.controller.consentOpened()
            checkNotNull(s.controller.unverifiedAcknowledgement()).recordUnverifiedAcknowledgement()
            s.controller.unverifiedAcknowledged(reportResults = false)
            assertEquals(PendingWarning(32, WarningPurpose.APPLY), s.controller.warning)
            assertTrue(s.far.speeds().isEmpty())
            s.controller.confirmWarning(dontShowAgain = false)
            s.until { s.far.speeds() == listOf(32) }
        }

    @Test
    fun `a lights tap drops a speed tap waiting at the acknowledgement, and says the lights weren't switched`() =
        runTest {
            val s = Setup(this)
            s.onDashboard(s.far)
            s.controller.applySpeed(16)
            s.controller.consentOpened()
            // The rider doesn't acknowledge, goes back some other way, and switches the lights.
            s.controller.setLights(true)
            s.until { s.controller.dashboard.lights.notSent == NotSent.NOT_YET }
            s.controller.consentOpened()
            checkNotNull(s.controller.unverifiedAcknowledgement()).recordUnverifiedAcknowledgement()
            s.controller.unverifiedAcknowledged(reportResults = false)
            s.pass(5)
            assertTrue("the 16 tapped before the lights is never sent", s.far.speeds().isEmpty())
            assertTrue(s.far.commands.none { it is BikeCommand.SetLights })
            s.controller.setLights(true)
            s.until { s.far.commands.any { it is BikeCommand.SetLights } }
        }

    @Test
    fun `a tap left at the acknowledgement is dropped, and a later one never sends it`() =
        runTest {
            val s = Setup(this)
            s.store.saveBike(s.far.address)
            s.store.keepSpeed(s.far.address, true, null)
            s.onDashboard(s.far)
            s.controller.applySpeed(32)
            s.controller.consentOpened()
            // Back from the acknowledgement screen.
            s.controller.acknowledgementDismissed()
            s.controller.setKeepSpeed(false)
            s.controller.setLights(true)
            s.until { s.controller.consentNeeded == ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT }
            s.controller.consentOpened()
            checkNotNull(s.controller.unverifiedAcknowledgement()).recordUnverifiedAcknowledgement()
            s.controller.unverifiedAcknowledged(reportResults = false)
            s.pass(5)
            assertNull(s.controller.warning)
            assertTrue("the old 32 is never sent", s.far.speeds().isEmpty())
            assertFalse("keep-speed stays off", s.store.data.value.keepSpeed)
        }

    @Test
    fun `with a newer risk text not yet accepted, the saved bike connects, and still after accepting it`() =
        runTest {
            val s = Setup(this)
            s.store.accept(version - 1, LocalDate.of(2026, 10, 1))
            s.store.saveBike(s.near.address)
            s.pass(0)
            assertEquals(listOf(Route.DASHBOARD, Route.RISK_ACCEPTANCE), s.controller.startRoutes())
            assertTrue(s.controller.acceptanceOutdated)
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertEquals("only the identity read", listOf(BikeCommand.ReadIdentity), s.near.commands)
            s.accept()
            s.controller.connectToSavedBike()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
        }

    @Test
    fun `a not-sent note goes once the state it was said in changes`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.near.switchOff()
            s.until { s.controller.dashboard.connection == Connection.BIKE_OFF }
            s.controller.applySpeed(25)
            s.until { s.controller.dashboard.speedLimit.notSent == SpeedNotSent(25, NotSent.BIKE_NOT_ON) }
            s.near.switchOn()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertNull(s.controller.dashboard.speedLimit.notSent)
        }

    @Test
    fun `the session log is stored every 30 seconds and when the app goes behind another one`() =
        runTest {
            val s = Setup(this)
            val controller =
                AppController(
                    backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    storage = StandardTestDispatcher(testScheduler),
                )
            val dir = File(context.filesDir, "session-logs")

            fun storedText() =
                dir
                    .walk()
                    .filter { it.isFile }
                    .maxByOrNull {
                        it.name
                            .removePrefix("session-")
                            .removeSuffix(".jsonl")
                            .toInt()
                    }?.readText()
                    .orEmpty()
            s.accept()
            s.store.saveBike(s.near.address)
            s.pass(0)
            controller.onForeground()
            s.until { controller.dashboard.connection == Connection.CONNECTED }
            s.pass(31)
            assertTrue(storedText().contains("\"event\":\"identity\""))
            controller.onBackground()
            s.pass(0)
            assertEquals(s.services.logText.toString(), storedText())
        }

    @Test
    fun `an earlier run's log opens in its preview, and leaving the preview closes it`() =
        runTest {
            val earlier =
                BikeServices(
                    context,
                    AppStore(context.getSharedPreferences("earlier", Context.MODE_PRIVATE)),
                ) { s -> error("no bike $s") }
            earlier.saveLog()
            val s = Setup(this)
            val controller =
                AppController(
                    backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    storage = StandardTestDispatcher(testScheduler),
                )
            backgroundScope.launch { controller.refreshLogs() }
            s.pass(0)
            val logs = checkNotNull(controller.storedLogs)
            assertEquals(listOf(true, false), logs.map { it.current })
            var opened = 0
            controller.openLog(logs[1].number) {
                opened++
                true
            }
            s.pass(0)
            val preview = checkNotNull(controller.previewLog)
            assertEquals(1, opened)
            assertEquals(earlier.logText.toString(), preview.text)
            assertEquals(earlier.logDate, preview.date)
            // A second log opened at once takes the place of the first; the first never opens.
            controller.openLog(logs[0].number) {
                opened += 10
                true
            }
            controller.openLog(logs[1].number) {
                opened += 100
                true
            }
            s.pass(0)
            assertEquals(101, opened)
            // Closing an older preview leaves the one shown.
            controller.closeLog(preview)
            assertTrue(controller.previewLog != null)
            controller.closeLog(controller.previewLog)
            assertEquals(null, controller.previewLog)
            // Opened after the rider left Diagnostics: not shown, so it goes.
            controller.openLog(logs[1].number) { false }
            s.pass(0)
            assertEquals(null, controller.previewLog)
        }

    @Test
    fun `the compatibility check reads only, and the dashboard connects again afterwards`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.store.saveBike(s.near.address)
            s.store.keepSpeed(s.near.address, on = true, kmh = 32)
            s.pass(0)
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            val before = s.near.commands.size
            assertTrue(s.controller.canCheckCompatibility)
            s.controller.runCompatibilityCheck()
            s.until(120) { s.controller.compatibility?.report != null }
            val report = checkNotNull(s.controller.compatibility?.report)
            assertEquals(CompatibilityOutcome.Checked, report.outcome)
            assertEquals(listOf(BikeCommand.ReadIdentity), s.near.commands.drop(before))
            val afterCheck = s.near.commands.size
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            s.pass(90)
            // The reconnection afterwards reads the identity, and may set the kept speed again, as any does.
            val allowed = setOf(BikeCommand.ReadIdentity, BikeCommand.SetSpeedLimit(SpeedLimit.KMH_32))
            assertTrue(
                s.near.commands
                    .drop(afterCheck)
                    .toString(),
                allowed.containsAll(s.near.commands.drop(afterCheck)),
            )
            s.controller.closeCompatibility()
            assertEquals(null, s.controller.compatibility)
        }

    @Test
    fun `a check's result is reported with its model, verification and summary, a demo check's isn't`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.store.saveBike(s.near.address)
            s.pass(0)
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            s.controller.runCompatibilityCheck()
            s.until(120) { s.controller.compatibility?.report != null }
            val report = checkNotNull(s.controller.compatibility?.report)
            s.controller.reportCheck()
            val request = checkNotNull(s.controller.report)
            assertEquals(ReportType.COMPATIBILITY, request.type)
            assertSame(report, request.answers.check)
            val items =
                s.controller
                    .reportPreview(ReportType.COMPATIBILITY, request.answers, "1.0", labels)
                    .items
                    .associate { it.label to it.value }
            assertEquals("check Checked, ${report.statusFrames} frames", items["Checks"])
            assertEquals(s.near.model, items["Model"])
            assertEquals("verified", items["Verification"])
            // From the menu, the report is about the run, not the check.
            s.controller.openReport(ReportType.COMPATIBILITY)
            assertEquals(
                null,
                s.controller.report
                    ?.answers
                    ?.check,
            )
            s.controller.closeReport()
            s.controller.closeCompatibility()
            s.controller.reportCheck()
            assertEquals("no check on screen, no report", null, s.controller.report)

            s.controller.startDemo()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            s.controller.runCompatibilityCheck()
            s.until(120) { s.controller.compatibility?.report != null }
            s.controller.reportCheck()
            val demo =
                s.controller.reportPreview(
                    ReportType.COMPATIBILITY,
                    checkNotNull(s.controller.report).answers,
                    "1.0",
                    labels,
                )
            assertTrue(demo.demo)
            assertEquals(
                "nothing of a simulated bike",
                setOf("App", "Build", "Android"),
                demo.items.map { it.label }.toSet(),
            )
        }

    @Test
    fun `in demo mode the check runs on the simulated bike, and the demo goes on afterwards`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startDemo()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertTrue(s.controller.canCheckCompatibility)
            s.controller.runCompatibilityCheck()
            s.until(120) { s.controller.compatibility?.report != null }
            assertEquals(
                CompatibilityOutcome.Checked,
                s.controller.compatibility
                    ?.report
                    ?.outcome,
            )
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            assertEquals(0, s.realCreated)
        }

    @Test
    fun `leaving a running compatibility check stops it, and the app going away doesn't reconnect`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.store.saveBike(s.near.address)
            s.pass(0)
            s.controller.onForeground()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }
            s.controller.runCompatibilityCheck()
            s.pass(5)
            assertTrue(s.controller.compatibility?.running == true)
            s.controller.closeCompatibility()
            assertEquals(null, s.controller.compatibility)
            s.until { s.controller.dashboard.connection == Connection.CONNECTED }

            s.controller.runCompatibilityCheck()
            s.pass(5)
            s.controller.onBackground()
            assertEquals(null, s.controller.compatibility)
            s.pass(60)
            assertTrue("nothing connects while the app is away", !s.near.connected)
        }

    @Test
    fun `the session log of a paired bike names no address either`() =
        runTest {
            val s = Setup(this, nearAddress = BikeAddress("C3:5A:9E:71:2B:D4"))
            s.near.pairedWithPhone = true
            s.accept()
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            s.controller.startCheck(s.found().single { it.signal == null })
            s.until { s.controller.check.result != null }
            val log = s.controller.sessionLog()
            for (part in listOf(
                "c3:5a:9e:71:2b:d4",
                "c3-5a-9e-71-2b-d4",
                "c35a9e712bd4",
                "d42b719e5ac3",
                "d4:2b:71:9e:5a:c3",
                "d4-2b-71-9e-5a-c3",
            )) {
                assertNull("the log holds \"$part\"", log.lines().firstOrNull { part in it.lowercase() })
            }
            val classification = log.lines().single { "\"classification\"" in it }
            assertTrue(classification, "\"verification\":\"verified\"" in classification)
            assertFalse(log, "firmware" in log)
            assertTrue(log.lines().any { "paired_direct" in it })
        }

    @Test
    fun `the name the rider gives the bike never reaches the session log`() =
        runTest {
            val s = Setup(this)
            val name = "Qwvzx bike r@mail.example"
            s.controller.confirmBike(s.checkBike(0))
            s.until { s.controller.dashboard.ride != null }
            s.controller.rename(name)
            s.pass(0)
            assertEquals(name, s.store.data.value.bikeName)
            // Away and back: a new connection, with the name in place.
            s.controller.onBackground()
            s.pass(1)
            s.controller.onForeground()
            s.until { s.controller.dashboard.ride != null }
            val log = s.controller.sessionLog()
            for (part in listOf("Qwvzx", "r@mail.example")) {
                assertNull("the log holds \"$part\"", log.lines().firstOrNull { part in it })
            }
        }

    @Test
    fun `without the Bluetooth permission the dashboard and the search say so`() =
        runTest {
            val s = Setup(this)
            s.controller.confirmBike(s.checkBike(0))
            s.until { s.controller.dashboard.ride != null }
            s.transport.setRadio(RadioState.NOT_PERMITTED)
            s.until { s.controller.dashboard.connection == Connection.NO_PERMISSION }
            assertFalse(s.controller.dashboard.permissionInSettings)
            s.controller.onPermissionResult(granted = false, canAskAgain = true)
            s.controller.onPermissionResult(granted = false, canAskAgain = false)
            s.pass(0)
            assertTrue("a second refusal sends the rider to settings", s.controller.dashboard.permissionInSettings)
            s.controller.startScan()
            s.pass(1)
            assertEquals(FindBikeUiState.NoPermission(inSettings = true), s.controller.findBike)
        }

    @Test
    fun `a first refusal without a rationale can still be asked again`() =
        runTest {
            val s = Setup(this)
            s.controller.onPermissionResult(granted = false, canAskAgain = false)
            assertEquals(PermissionUiState.DENIED, s.controller.permission)
            s.controller.onPermissionAllowed()
            assertEquals(PermissionUiState.NOT_ASKED, s.controller.permission)
        }

    @Test
    fun `after a restart, a refusal Android won't ask about again leads straight to the app's settings`() =
        runTest {
            val s = Setup(this)
            s.controller.onPermissionResult(granted = false, canAskAgain = true)
            assertTrue("a refusal Android would explain is remembered", s.store.data.value.permissionRefused)
            // The app is closed and opened again: a new controller on the same storage.
            val again =
                AppController(
                    backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    storage = StandardTestDispatcher(testScheduler),
                )
            assertEquals(PermissionUiState.NOT_ASKED, again.permission)
            assertFalse(again.onPermissionChecked(allowed = false, rationale = true))
            assertEquals("Android would still ask, with an explanation", PermissionUiState.DENIED, again.permission)
            again.onPermissionChecked(allowed = false, rationale = false)
            assertEquals("refused, and no explanation now", PermissionUiState.DENIED_PERMANENTLY, again.permission)
            assertTrue("allowed in the app's settings", again.onPermissionChecked(allowed = true, rationale = false))
            assertEquals(PermissionUiState.NOT_ASKED, again.permission)
            assertFalse("granted: nothing left to remember", s.store.data.value.permissionRefused)
            assertFalse("allowed and not refused before: nothing changes", again.onPermissionChecked(true, false))
        }

    @Test
    fun `a dismissed prompt is asked again, also after a restart`() =
        runTest {
            val s = Setup(this)
            // Dismissed: denied, but Android gives no explanation and would still show the prompt.
            s.controller.onPermissionResult(granted = false, canAskAgain = false)
            assertEquals(PermissionUiState.DENIED, s.controller.permission)
            assertFalse(s.store.data.value.permissionRefused)
            s.controller.onPermissionChecked(allowed = false, rationale = false)
            assertEquals(PermissionUiState.NOT_ASKED, s.controller.permission)
            val again =
                AppController(
                    backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    storage = StandardTestDispatcher(testScheduler),
                )
            again.onPermissionChecked(allowed = false, rationale = false)
            assertEquals(PermissionUiState.NOT_ASKED, again.permission)
        }

    @Test
    fun `a final refusal in this run stays final when the app comes back`() =
        runTest {
            val s = Setup(this)
            s.controller.onPermissionResult(granted = false, canAskAgain = false)
            s.controller.onPermissionResult(granted = false, canAskAgain = false)
            assertEquals(PermissionUiState.DENIED_PERMANENTLY, s.controller.permission)
            s.controller.onPermissionChecked(allowed = false, rationale = false)
            assertEquals(PermissionUiState.DENIED_PERMANENTLY, s.controller.permission)
        }

    @Test
    fun `a permission revoked in the settings after a grant is asked again, then sent to settings`() =
        runTest {
            val s = Setup(this)
            s.controller.onPermissionResult(granted = false, canAskAgain = true)
            assertTrue(s.controller.onPermissionResult(granted = true, canAskAgain = false))
            assertFalse("granted at the prompt: forgotten", s.store.data.value.permissionRefused)
            // Revoked later in Android's settings: Android explains the next request.
            val again =
                AppController(
                    backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    storage = StandardTestDispatcher(testScheduler),
                )
            again.onPermissionChecked(allowed = false, rationale = true)
            assertEquals(PermissionUiState.DENIED, again.permission)
            again.onPermissionResult(granted = false, canAskAgain = false)
            assertEquals(PermissionUiState.DENIED_PERMANENTLY, again.permission)
            // And after another restart, too.
            val third =
                AppController(
                    backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    storage = StandardTestDispatcher(testScheduler),
                )
            third.onPermissionChecked(allowed = false, rationale = false)
            assertEquals(PermissionUiState.DENIED_PERMANENTLY, third.permission)
        }

    @Test
    fun `a remembered refusal found allowed is forgotten without moving on`() =
        runTest {
            val s = Setup(this)
            s.store.permissionRefused(true)
            val again =
                AppController(
                    backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    storage = StandardTestDispatcher(testScheduler),
                )
            assertFalse("not refused in this run: no screen to leave", again.onPermissionChecked(true, true))
            assertFalse(s.store.data.value.permissionRefused)
        }

    @Test
    fun `a check that starts again after the app was in the background says so`() =
        runTest {
            val s = Setup(this)
            s.checkBike(1)
            assertFalse(s.controller.check.restarted)
            s.controller.onBackground()
            s.pass(1)
            s.controller.onForeground()
            assertTrue(s.controller.check.restarted)
            s.until { s.controller.check.result != null }
            assertTrue("still said with the result", s.controller.check.restarted)
            s.controller.retryCheck()
            assertFalse("a retry the rider asked for isn't a restart", s.controller.check.restarted)
            s.until { s.controller.check.result != null }
            assertFalse(s.controller.check.restarted)
            // Back to the list and another pick: no note either.
            s.controller.onBackground()
            s.controller.onForeground()
            assertTrue(s.controller.check.restarted)
            s.controller.cancelCheck()
            s.controller.startScan()
            s.until { s.found().size == 2 }
            s.controller.startCheck(s.found()[1])
            assertFalse(s.controller.check.restarted)
            s.until { s.controller.check.result != null }
            assertFalse(s.controller.check.restarted)
        }

    @Test
    fun `Bluetooth switched off after the find, the check says so instead of running forever`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startScan()
            s.until { s.found().size == 2 }
            val bike = s.found()[0]
            s.transport.setRadio(RadioState.OFF)
            s.controller.startCheck(bike)
            s.until { s.controller.check.result != null }
            assertEquals(CheckResult.BluetoothOff, s.controller.check.result)
        }

    @Test
    fun `leaving demo mode goes back to the real search`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startDemo()
            s.until { s.controller.dashboard.ride != null }
            s.controller.leaveDemo()
            assertFalse(s.controller.demo)
            assertNull("nothing of the demo stays on the dashboard", s.controller.dashboard.ride)
            assertEquals(0, s.realCreated)
            s.controller.startScan()
            s.until { (s.controller.findBike as? FindBikeUiState.Searching)?.bikes?.size == 2 }
            assertEquals(1, s.realCreated)
        }

    @Test
    fun `the session log names no bike, no rider's name and nothing of demo mode`() =
        runTest {
            // A distinctive address, so that any form of it would show.
            val s = Setup(this, nearAddress = BikeAddress("C3:5A:9E:71:2B:D4"))
            s.controller.confirmBike(s.checkBike(0))
            s.until { s.controller.dashboard.ride != null }
            s.controller.rename("Trail bike")
            s.controller.startDemo()
            s.pass(1)
            val before = s.controller.sessionLog()
            s.until { s.controller.dashboard.ride != null }
            s.pass(5)
            s.controller.leaveDemo()
            s.pass(1)
            assertEquals("demo mode writes to its own log", before, s.controller.sessionLog())
            assertTrue(before.isNotEmpty())
            val hex = { text: String -> text.toByteArray().joinToString("") { "%02x".format(it) } }
            val forbidden =
                listOf(
                    "c3:5a:9e:71:2b:d4",
                    "c3-5a-9e-71-2b-d4",
                    "c35a9e712bd4",
                    "d42b719e5ac3",
                    "d4:2b:71:9e:5a:c3",
                    "d4-2b-71-9e-5a-c3",
                    "trail bike",
                    hex("Trail bike"),
                    FakeBike.DEMO_SERIAL.lowercase(),
                    hex(FakeBike.DEMO_SERIAL),
                )
            for (part in forbidden) {
                val line = before.lines().firstOrNull { part in it.lowercase() }
                assertNull("the log holds \"$part\"", line)
            }
            // Advertised names are logged masked, without the part that comes from the address.
            val named = Regex("ENGWE-[0-9A-Fa-f]", RegexOption.IGNORE_CASE)
            assertNull(before.lines().firstOrNull { named.containsMatchIn(it) })
        }

    private val labels =
        ReportLabels(
            app = "App",
            android = "Android",
            model = "Model",
            verification = "Verification",
            build = "Build",
            checks = "Checks",
            results = "Results",
            errors = "Errors",
            stopSpeed = { "Stop at $it" },
            iotVersion = "IoT",
            state = "State",
            verified = "verified",
            unverified = "unverified",
            checksPassed = "passed",
            confirmed = { "$it confirmed" },
            notConfirmed = { "$it not confirmed" },
            errorCount = { "$it errors" },
            kmh = { "$it km/h" },
            buildName = "PR #1",
            backTo25 = "Back to 25",
            restart = { "restart ${it.name}" },
            check = { "check ${it.outcome}, ${it.statusFrames} frames" },
            assistResults = "Assist",
            assistConfirmed = { "assist $it confirmed" },
            assistNotConfirmed = { "assist $it not confirmed" },
        )

    @Test
    fun `the report preview shares the log's phone facts and neither the bike's name nor a demo model`() =
        runTest {
            val s = Setup(this)
            s.controller.confirmBike(s.checkBike(0))
            s.controller.rename("Trail bike")
            s.pass(0)
            s.controller.openReport(ReportType.COMPATIBILITY)
            val preview = s.controller.reportPreview(ReportType.COMPATIBILITY, ReportAnswers(27), "1.0", labels)
            val items = preview.items
            assertEquals("${s.services.android} · ${s.services.phone}", items.single { it.label == "Android" }.value)
            assertEquals(s.near.model, items.single { it.label == "Model" }.value)
            assertEquals("verified", items.single { it.label == "Verification" }.value)
            assertEquals("PR #1", items.single { it.label == "Build" }.value)
            assertTrue(items.none { "Trail bike" in it.value })
            assertFalse(preview.demo)
            // The link holds exactly the items shown, each in its field.
            val link = checkNotNull(preview.link("https://github.com/owner/repo"))
            assertTrue(
                link.url.startsWith("https://github.com/owner/repo/issues/new?template=compatibility_report.yml"),
            )
            val query =
                java.net
                    .URI(link.url)
                    .rawQuery
                    .split('&')
                    .drop(1)
                    .associate {
                        it.substringBefore('=') to
                            java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                    }
            assertEquals(items.filter { it.field != null }.associate { checkNotNull(it.field).id to it.value }, query)
            s.controller.startDemo()
            s.until { s.controller.dashboard.ride != null }
            val demo = s.controller.reportPreview(ReportType.COMPATIBILITY, ReportAnswers(), "1.0", labels)
            assertTrue("no model in demo mode", demo.items.none { it.label == "Model" })
            assertTrue(demo.demo)
        }

    @Test
    fun `reports about the bike share the typed IoT version, an idea shares nothing typed`() =
        runTest {
            val s = Setup(this)
            s.controller.confirmBike(s.checkBike(0))
            s.controller.openReport(null)
            val answers = ReportAnswers(stopSpeedKmh = 26, iotVersion = " 218can ")

            fun shared(type: ReportType) =
                s.controller
                    .reportPreview(type, answers, "1.0", labels)
                    .items
                    .associate { it.label to it.value }
            assertEquals("218CAN", shared(ReportType.COMPATIBILITY)["IoT"])
            assertTrue(
                "no speed confirmed in this run: where assistance stopped belongs to none",
                shared(
                    ReportType.COMPATIBILITY,
                ).keys.none {
                    it.startsWith("Stop")
                },
            )
            assertEquals("218CAN", shared(ReportType.PROBLEM)["IoT"])
            s.until { shared(ReportType.PROBLEM)["Errors"] != null }
            assertEquals("0 errors", shared(ReportType.PROBLEM)["Errors"])
            assertEquals(mapOf("App" to "1.0"), shared(ReportType.IDEA))
            val nothing = s.controller.reportPreview(ReportType.COMPATIBILITY, ReportAnswers(0, "  "), "1.0", labels)
            assertTrue(nothing.items.none { it.label == "IoT" })
            // Like the model: a demo bike has no firmware to report.
            s.controller.startDemo()
            s.until { s.controller.dashboard.ride != null }
            assertNull(shared(ReportType.COMPATIBILITY)["IoT"])
        }

    @Test
    fun `a compatibility report shares the speeds sent and where assistance stopped at the last confirmed one`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.applySpeed(25)
            s.until { s.near.speeds() == listOf(25) }
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            s.controller.openReport(ReportType.COMPATIBILITY)

            fun preview() = s.controller.reportPreview(ReportType.COMPATIBILITY, ReportAnswers(26), "1.0", labels)
            // This run's log is read when the report opens.
            s.until { preview().stopSpeedFor != null }
            val preview = preview()
            assertEquals(25, preview.stopSpeedFor)
            val stop = preview.items.single { it.label == "Stop at 25" }
            assertEquals("26 km/h", stop.value)
            assertEquals(IssueField.STOP_SPEED_25, stop.field)
            assertEquals("25 confirmed", preview.items.single { it.label == "Results" }.value)
            assertEquals("passed", preview.items.single { it.label == "Checks" }.value)
        }

    @Test
    fun `a compatibility report shares the assist levels set from the app and how each ended`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.setAssistLevel(3)
            s.until { s.controller.dashboard.assist.confirmed == 3 }
            s.controller.setAssistLevel(5)
            s.until { s.controller.dashboard.assist.confirmed == 5 }
            s.controller.openReport(ReportType.COMPATIBILITY)

            fun preview() = s.controller.reportPreview(ReportType.COMPATIBILITY, ReportAnswers(), "1.0", labels)
            s.until { !preview().loading }
            val item = preview().items.single { it.label == "Assist" }
            assertEquals("assist 3 confirmed\nassist 5 confirmed", item.value)
            assertEquals(IssueField.ASSIST_CONFIRMED, item.field)
            // The link holds exactly what the preview shows.
            val link = checkNotNull(preview().link("https://example.invalid/repo")).url
            assertTrue(link, link.contains("&assist_confirmed=assist%203%20confirmed%0Aassist%205%20confirmed"))
            val problem = s.controller.reportPreview(ReportType.PROBLEM, ReportAnswers(), "1.0", labels)
            assertTrue(problem.items.none { it.field == IssueField.ASSIST_CONFIRMED })
        }

    @Test
    fun `a demo report shares no assist level`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startDemo()
            s.until { s.controller.dashboard.connection == Connection.CONNECTED && s.controller.dashboard.ride != null }
            s.pass(2)
            s.controller.setAssistLevel(4)
            s.until { s.controller.dashboard.assist.confirmed == 4 }
            s.controller.openReport(ReportType.COMPATIBILITY)
            s.pass(1)
            val preview = s.controller.reportPreview(ReportType.COMPATIBILITY, ReportAnswers(), "1.0", labels)
            assertTrue(preview.demo)
            assertTrue(preview.items.none { it.field == IssueField.ASSIST_CONFIRMED })
        }

    @Test
    fun `an unverified bike's confirmed speed is asked about on the next app open, the answers fill a report`() =
        runTest {
            val s = Setup(this)
            s.onDashboard(s.far)
            s.controller.applySpeed(32)
            s.until { s.controller.consentNeeded == ConsentNeeded.UNVERIFIED_ACKNOWLEDGEMENT }
            s.controller.consentOpened()
            checkNotNull(s.controller.unverifiedAcknowledgement()).recordUnverifiedAcknowledgement()
            s.controller.unverifiedAcknowledged(reportResults = true)
            s.controller.applySpeed(32)
            s.controller.confirmWarning(dontShowAgain = false)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            assertNull("asked on the next app open, not now", s.controller.rideTest)
            val log = checkNotNull(s.services.currentLogNumber())
            assertEquals(32, s.store.data.value.rideTestKmh)
            assertEquals(log, s.store.data.value.rideTestLog)
            // Back to 25 isn't a speed to ask about.
            s.controller.applySpeed(25)
            s.until { (s.controller.dashboard.speedLimit.status as? SpeedLimitStatus.Confirmed)?.kmh == 25 }
            assertEquals(32, s.store.data.value.rideTestKmh)

            // The next app open, before the bike is connected again.
            val next =
                AppController(
                    s.scope.backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    clock = s::at,
                    storage = StandardTestDispatcher(s.scope.testScheduler),
                )
            assertEquals(RideTest(32, log), next.rideTest)
            next.rideTestDone(RideTest(32, log, RideTestStop.NO, stoppedAtKmh = 28, restart = RideTestRestart.YES))
            assertNull(next.rideTest)
            assertNull("not asked again", s.store.data.value.rideTestKmh)
            val request = checkNotNull(next.report)
            assertEquals(ReportType.COMPATIBILITY, request.type)
            assertEquals(28, request.answers.stopSpeedKmh)

            fun preview() = next.reportPreview(ReportType.COMPATIBILITY, request.answers, "1.0", labels)
            s.until { !preview().loading }
            val preview = preview()
            val items = preview.items.associate { it.field to it.value }
            // From that run's log: this one hasn't connected.
            assertEquals(s.far.model, items[IssueField.MODEL_IDENTITY])
            assertEquals("unverified", items[IssueField.VERIFICATION])
            assertEquals("passed", items[IssueField.PROTOCOL_CHECKS])
            assertEquals("the earlier run's speed", "32 confirmed", items[IssueField.SPEEDS_CONFIRMED])
            assertEquals("28 km/h", items[IssueField.STOP_SPEED_32])
            assertEquals("restart YES", items[IssueField.BACK_TO_25])
            assertEquals(32, preview.stopSpeedFor)
            // Its log is that run's.
            s.until { next.reportLog != null }
            assertEquals(log, next.reportLog?.number)
            assertFalse(checkNotNull(next.reportLog).replaced)
        }

    @Test
    fun `a kept speed set again on connecting isn't a ride test`() =
        runTest {
            val s = Setup(this)
            s.store.saveBike(s.far.address)
            s.store.acknowledge(s.far.address, version)
            s.store.acknowledgeReport(s.far.address)
            s.store.keepSpeed(s.far.address, true, 32)
            s.onDashboard(s.far)
            s.until(10) { s.far.speeds() == listOf(32) }
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            assertNull(s.store.data.value.rideTestKmh)
        }

    @Test
    fun `no ride test for a verified bike, without the offer to report, or when skipped`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.store.acknowledgeReport(s.near.address)
            s.controller.applySpeed(32)
            s.controller.confirmWarning(dontShowAgain = false)
            s.until { s.controller.dashboard.speedLimit.status is SpeedLimitStatus.Confirmed }
            assertNull("a verified bike", s.store.data.value.rideTestKmh)

            s.store.forgetBike()
            s.store.saveBike(s.far.address)
            s.store.rideTest(s.far.address, 32, 1)
            assertNull("the rider didn't offer to report", s.store.data.value.rideTestKmh)
            s.store.acknowledgeReport(s.far.address)
            s.store.rideTest(s.far.address, 32, 1)
            val next =
                AppController(
                    s.scope.backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    clock = s::at,
                    storage = StandardTestDispatcher(s.scope.testScheduler),
                )
            next.rideTestLater()
            assertNull(next.rideTest)
            assertEquals("put away: asked again next time", 32, s.store.data.value.rideTestKmh)
            val again =
                AppController(
                    s.scope.backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    clock = s::at,
                    storage = StandardTestDispatcher(s.scope.testScheduler),
                )
            again.rideTestDone(null)
            assertNull(again.report)
            assertNull("skipped: not asked again", s.store.data.value.rideTestKmh)
        }

    @Test
    fun `a report attaches this run's log as it was when the report opened, and says how sending went`() =
        runTest {
            val s = Setup(this)
            s.onDashboard()
            s.controller.openReport(ReportType.PROBLEM)
            s.until { s.controller.reportLog != null }
            val log = checkNotNull(s.controller.reportLog)
            assertTrue(log.text.contains("\"event\":\"session_start\""))
            assertEquals("this run's log, all of it", s.controller.sessionLog(), log.text)
            // The run goes on; what the report attaches stays as it was shown.
            s.controller.setLights(true)
            s.pass(5)
            assertEquals(log.text, s.controller.reportLog?.text)
            assertTrue(s.controller.sessionLog().length > log.text.length)

            s.controller.closeReport()
            assertNull(s.controller.report)
            assertNull(s.controller.reportLog)
        }

    /** Downloads that take a log only while [works]; what they took. */
    private class FakeDownloads : LogDownloads {
        var works = true
        val saved = mutableListOf<Pair<String, String>>()

        override fun save(
            name: String,
            text: String,
        ): String? = if (works) name.also { saved += it to text } else null
    }

    private fun Setup.openedReport(): ReportPreview {
        controller.openReport(ReportType.PROBLEM)
        until { controller.reportLog != null }
        return controller.reportPreview(ReportType.PROBLEM, ReportAnswers(), "1.0", labels)
    }

    private fun name(
        date: LocalDate,
        number: Int,
    ) = "log-$date-$number.log"

    @Test
    fun `sending saves the shown log in Downloads once, then the form opens, and a second tap does nothing`() =
        runTest {
            val downloads = FakeDownloads()
            val s = Setup(this, downloads = downloads)
            s.onDashboard()
            val preview = s.openedReport()
            val log = checkNotNull(s.controller.reportLog)
            s.controller.setLights(true) // the run goes on
            s.controller.sendReport(preview, ::name)
            s.controller.sendReport(preview, ::name)
            assertEquals(
                ReportSending.Stage.SAVING,
                s.controller.report
                    ?.sending
                    ?.stage,
            )
            s.until {
                s.controller.report
                    ?.sending
                    ?.stage == ReportSending.Stage.OPEN
            }
            val file = name(s.services.logDate, 1)
            assertEquals("exactly the text shown, once", listOf(file to log.text), downloads.saved)
            s.controller.reportOpened(true)
            assertEquals(ReportSent(LogSaved.InDownloads(file), opened = true), s.controller.report?.sent)
            assertNull(s.controller.report?.sending)
            s.controller.reportOpenedAgain(false)
            assertEquals(
                "still opened once",
                true,
                s.controller.report
                    ?.sent
                    ?.opened,
            )
            s.controller.sendReport(preview, ::name)
            assertEquals("sent: nothing more", 1, downloads.saved.size)
            // The file's number is taken: the next file gets the next one.
            s.controller.closeReport()
            s.controller.reportOpened(true)
            assertNull("a result for a closed report changes nothing", s.controller.report)
            s.controller.sendReport(s.openedReport(), ::name)
            s.until {
                s.controller.report
                    ?.sending
                    ?.stage == ReportSending.Stage.OPEN
            }
            assertEquals(name(s.services.logDate, 2), downloads.saved.last().first)
        }

    @Test
    fun `a log Downloads can't take goes to the picker, and the form opens whether or not it was saved`() =
        runTest {
            val downloads = FakeDownloads().apply { works = false }
            val s = Setup(this, downloads = downloads)
            s.onDashboard()
            s.controller.sendReport(s.openedReport(), ::name)
            s.until {
                s.controller.report
                    ?.sending
                    ?.stage == ReportSending.Stage.PICK
            }
            assertEquals(
                name(s.services.logDate, 1),
                s.controller.report
                    ?.sending
                    ?.file
                    ?.name,
            )
            s.controller.reportLogPicked(null) // cancelled
            s.until {
                s.controller.report
                    ?.sending
                    ?.stage == ReportSending.Stage.OPEN
            }
            s.controller.reportOpened(false)
            assertEquals(ReportSent(LogSaved.NotSaved, opened = false), s.controller.report?.sent)
            s.controller.reportOpenedAgain(true)
            assertEquals(
                true,
                s.controller.report
                    ?.sent
                    ?.opened,
            )

            // Cancelled: the number wasn't used. Saved through the picker: it is.
            s.controller.closeReport()
            s.controller.sendReport(s.openedReport(), ::name)
            s.until {
                s.controller.report
                    ?.sending
                    ?.stage == ReportSending.Stage.PICK
            }
            assertEquals(
                name(s.services.logDate, 1),
                s.controller.report
                    ?.sending
                    ?.file
                    ?.name,
            )
            var written = false
            s.controller.reportLogPicked {
                written = true
                true to false
            }
            // Out of the picker at once: a recreated screen doesn't open it again.
            assertEquals(
                ReportSending.Stage.WRITING,
                s.controller.report
                    ?.sending
                    ?.stage,
            )
            s.until {
                s.controller.report
                    ?.sending
                    ?.stage == ReportSending.Stage.OPEN
            }
            assertTrue(written)
            s.controller.reportOpened(true)
            assertEquals(ReportSent(LogSaved.Chosen, opened = true), s.controller.report?.sent)
            assertEquals(2, s.services.nextLogNumber(s.services.logDate))
        }

    @Test
    fun `a report closed while its log is saved neither opens the form nor comes back`() =
        runTest {
            val downloads = FakeDownloads()
            val s = Setup(this, downloads = downloads)
            s.onDashboard()
            s.controller.sendReport(s.openedReport(), ::name)
            s.controller.closeReport()
            s.pass(2)
            assertNull(s.controller.report)
            // Its file was saved all the same: its number is taken.
            assertEquals(listOf(name(s.services.logDate, 1)), downloads.saved.map { it.first })
            assertEquals(2, s.services.nextLogNumber(s.services.logDate))
        }

    @Test
    fun `a ride test's report attaches this run's log when that run's is gone, and says so`() =
        runTest {
            val s = Setup(this)
            s.store.saveBike(s.far.address)
            s.store.acknowledge(s.far.address, version)
            s.store.acknowledgeReport(s.far.address)
            s.store.rideTest(s.far.address, 32, 999)
            val next =
                AppController(
                    s.scope.backgroundScope,
                    s.services,
                    bikeLabel = "ENGWE bike",
                    clock = s::at,
                    storage = StandardTestDispatcher(s.scope.testScheduler),
                )
            next.rideTestDone(RideTest(32, 999, RideTestStop.YES))
            s.until { next.reportLog != null }
            assertTrue(checkNotNull(next.reportLog).replaced)
            assertEquals(next.sessionLog(), next.reportLog?.text)
        }

    @Test
    fun `demo mode attaches no log`() =
        runTest {
            val s = Setup(this)
            s.accept()
            s.controller.startDemo()
            s.pass(2)
            s.controller.openReport(ReportType.PROBLEM)
            s.pass(2)
            assertNull(s.controller.reportLog)
        }

    @Test
    fun `an acknowledgement counts only while this run's check shows the bike unverified`() =
        runTest {
            val s = Setup(this)
            val result = s.checkBike(1)
            assertTrue(s.controller.confirmBike(result))
            assertFalse("a result the screen no longer shows", s.controller.confirmBike(CheckResult.NotFound))
            // The phone is locked on the acknowledgement screen, then unlocked: the check runs again.
            s.controller.onBackground()
            s.pass(1)
            s.controller.onForeground()
            assertFalse(s.acknowledge())
            assertNull("nothing saved before the new check shows its result", s.store.data.value.bike)
            s.until { s.controller.check.result != null }
            assertTrue(s.acknowledge())
            assertEquals(s.far.address, s.store.data.value.bike)
        }
}
