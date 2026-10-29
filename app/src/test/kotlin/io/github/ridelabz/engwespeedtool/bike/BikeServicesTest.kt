@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.bike

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.session.Advertisement
import io.github.ridelabz.engwespeedtool.session.BikeSession
import io.github.ridelabz.engwespeedtool.session.CommandOutcome
import io.github.ridelabz.engwespeedtool.session.FakeBike
import io.github.ridelabz.engwespeedtool.session.FakeBikeTransport
import io.github.ridelabz.engwespeedtool.session.Refusal
import io.github.ridelabz.engwespeedtool.session.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/** Waits for Ready, then for a status that arrived after the checks: commands need one. */
private suspend fun BikeSession.liveStatus() =
    withTimeout(2.minutes) {
        state.first { it is SessionState.Ready }
        ride.drop(1).first { it.status != null && !it.stale }
    }

/** What the bike services keep of a connection: only the saved bike's address, nothing it heard. */
@RunWith(RobolectricTestRunner::class)
class BikeServicesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private inner class Setup(
        scope: TestScope,
    ) {
        val name = "services-${System.nanoTime()}"
        val store = AppStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        val bike = FakeBike(scope.backgroundScope)
        val services = BikeServices(context, store) { s -> simulated(FakeBikeTransport(bike), s) }
        val session = services.real(scope.backgroundScope).session

        init {
            services.acceptRisk(LocalDate.of(2026, 10, 15))
        }

        suspend fun ready(): SessionState.Ready {
            val ready = withTimeout(2.minutes) { session.state.first { it is SessionState.Ready } }
            session.stop()
            return ready as SessionState.Ready
        }

        fun keys(): Set<String> = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.keys
    }

    @Test
    fun `a heard bike and a paired one are verified by their identity, and only the address is kept`() =
        runTest {
            val s = Setup(this)
            s.session.start(s.bike.address)
            assertEquals(Verification.VERIFIED, s.ready().classification.verification)
            s.services.saveBike(s.bike.address)
            s.bike.pairedWithPhone = true
            s.session.start(Advertisement.paired(s.bike.address))
            assertEquals(Verification.VERIFIED, s.ready().classification.verification)
            assertEquals(setOf("bike", "accepted_version", "accepted_on"), s.keys())
        }

    @Test
    fun `a speed change waits for the firmware caveat, recorded in the store for the current text`() =
        runTest {
            val s = Setup(this)
            val connection = s.services.real(backgroundScope)
            connection.session.start(s.bike.address)
            connection.session.liveStatus()
            assertEquals(
                CommandOutcome.Refused(Refusal.CAVEAT_NOT_SEEN),
                connection.session.setSpeedLimit(SpeedLimit.KMH_32),
            )
            connection.firmwareCaveat.recordFirmwareCaveat()
            assertEquals(s.services.textVersion, s.store.data.value.caveatVersion)
            assertEquals(CommandOutcome.Confirmed, connection.session.setSpeedLimit(SpeedLimit.KMH_32))
            connection.session.stop()
        }

    @Test
    fun `an unverified bike needs its own acknowledgement, and the demo's consent never reaches the store`() =
        runTest {
            val name = "services-${System.nanoTime()}"
            val store = AppStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            val other = FakeBike(backgroundScope, FakeBike.DEMO_ADDRESS_2, model = FakeBike.DEMO_OTHER_MODEL)
            val services = BikeServices(context, store) { sc -> simulated(FakeBikeTransport(other), sc) }
            services.acceptRisk(LocalDate.of(2026, 10, 15))
            val connection = services.real(backgroundScope)
            connection.session.start(other.address)
            connection.session.liveStatus()
            val notAcknowledged = CommandOutcome.Refused(Refusal.NOT_ACKNOWLEDGED)
            assertEquals(notAcknowledged, connection.session.setLights(true))
            // Acknowledging another bike doesn't count for this one.
            connection.unverifiedAcknowledgement(FakeBike.DEMO_ADDRESS).recordUnverifiedAcknowledgement()
            assertEquals(notAcknowledged, connection.session.setLights(true))
            // Acknowledging this one saves it as "My bike", for the current text.
            connection.unverifiedAcknowledgement(other.address).recordUnverifiedAcknowledgement()
            assertEquals(other.address, store.data.value.bike)
            assertEquals(CommandOutcome.Confirmed, connection.session.setLights(true))
            // An acknowledgement of an older text version doesn't count.
            store.acknowledge(other.address, services.textVersion - 1)
            assertEquals(notAcknowledged, connection.session.setLights(false))
            connection.session.stop()
        }

    @Test
    fun `an older text version's caveat doesn't count`() =
        runTest {
            val s = Setup(this)
            s.store.seeCaveat(s.services.textVersion - 1)
            val connection = s.services.real(backgroundScope)
            connection.session.start(s.bike.address)
            connection.session.liveStatus()
            assertEquals(
                CommandOutcome.Refused(Refusal.CAVEAT_NOT_SEEN),
                connection.session.setSpeedLimit(SpeedLimit.KMH_32),
            )
            connection.session.stop()
        }

    @Test
    fun `demo mode asks for its own consent, keeps it in memory, and reaches only the simulated bike`() =
        runTest {
            val s = Setup(this)
            val demo = s.services.demo(backgroundScope)
            demo.session.start(FakeBike.DEMO_ADDRESS)
            demo.session.liveStatus()
            // Simulated bikes have no firmware to be wary of: no caveat in demo mode.
            assertEquals(CommandOutcome.Confirmed, demo.session.setSpeedLimit(SpeedLimit.KMH_32))
            assertEquals(SpeedLimit.KMH_32, demo.demoBikes.first().speedLimit)
            demo.unverifiedAcknowledgement(FakeBike.DEMO_ADDRESS_2).recordUnverifiedAcknowledgement()
            assertEquals("the demo's consent stays in memory", null, s.store.data.value.caveatVersion)
            assertEquals(null, s.store.data.value.bike)
            assertEquals(null, s.store.data.value.acknowledgedVersion)
            // The real session doesn't see the demo's caveat.
            val real = s.services.real(backgroundScope)
            real.session.start(s.bike.address)
            real.session.liveStatus()
            assertEquals(CommandOutcome.Refused(Refusal.CAVEAT_NOT_SEEN), real.session.setSpeedLimit(SpeedLimit.KMH_32))
            assertEquals(0, s.bike.writes.count { it.command is BikeCommand.SetSpeedLimit })
            demo.session.stop()
            real.session.stop()
        }

    @Test
    fun `the session log is stored privately, already sanitised, and a crash is recorded by its class name`() =
        runTest {
            val s = Setup(this)
            s.session.start(s.bike.address)
            s.ready()
            s.services.saveLog()
            val dir = File(context.filesDir, "session-logs")
            val stored =
                dir.walk().filter { it.isFile }.maxByOrNull {
                    it.name
                        .removePrefix("session-")
                        .removeSuffix(".jsonl")
                        .toInt()
                }
            checkNotNull(stored)
            assertTrue(stored.name.matches(Regex("session-[0-9]+\\.jsonl")))
            assertEquals(s.services.logText.toString(), stored.readText())
            assertTrue(stored.readText().contains("\"event\":\"identity\""))

            val previous = Thread.getDefaultUncaughtExceptionHandler()
            var passedOn = false
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> passedOn = true }
            try {
                s.services.captureCrashes()
                Thread
                    .getDefaultUncaughtExceptionHandler()
                    ?.uncaughtException(Thread.currentThread(), IllegalStateException("secret text"))
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
            assertTrue("Android's own handling goes on", passedOn)
            val crash = stored.readText().lines().last { it.contains("\"event\":\"error\"") }
            assertTrue(crash.contains("IllegalStateException"))
            assertTrue(crash.contains("\"where\":\"crash\""))
            assertFalse("never the message", stored.readText().contains("secret"))
            // The demo's log is its own, never stored.
            val demo = s.services.demo(backgroundScope)
            demo.session.start(FakeBike.DEMO_ADDRESS)
            withTimeout(2.minutes) { demo.session.state.first { it is SessionState.Ready } }
            demo.session.stop()
            s.services.saveLog()
            assertEquals(1, stored.readText().lines().count { it.contains("\"event\":\"session_start\"") })
        }

    @Test
    fun `logs of earlier runs stay, also from before an update, and can all be deleted`() =
        runTest {
            val dir = File(context.filesDir, "session-logs").apply { mkdirs() }
            // An earlier run's log, of an earlier version of the app.
            File(dir, "session-1.jsonl").writeText("{\"event\":\"session_start\",\"app_version\":\"0.0.1\"}\n")
            // Earlier test builds kept their logs in a directory per install: those go.
            val perInstall = File(dir, "abc").apply { mkdirs() }
            File(perInstall, "session-1.jsonl").writeText("{\"event\":\"session_start\"}\n")
            val s = Setup(this)
            s.session.start(s.bike.address)
            s.ready()
            s.services.saveLog()
            assertFalse(perInstall.exists())
            assertEquals(listOf(2, 1), s.services.storedLogs().map { it.number })
            val start =
                s.services.logText
                    .toString()
                    .lines()
                    .first()
            s.services.deleteStoredLogs()
            // Only this run's file, written again; its log starts again from its first record.
            assertEquals(listOf(2), s.services.storedLogs().map { it.number })
            assertEquals(start + "\n", s.services.logText.toString())
            assertEquals(s.services.logText.toString(), File(dir, "session-2.jsonl").readText())
        }

    @Test
    fun `a log that can't be stored is said once in the log itself, and nothing fails`() =
        runTest {
            val s = Setup(this)
            val dir = File(context.filesDir, "session-logs")
            dir.deleteRecursively()
            // The directory turned into a file: nothing can be written there.
            dir.writeText("in the way")
            try {
                s.session.start(s.bike.address)
                s.ready()
                s.services.saveLog()
                s.services.saveLog()
                val errors =
                    s.services.logText
                        .toString()
                        .lines()
                        .filter { it.contains("\"where\":\"log_storage\"") }
                assertEquals(1, errors.size)
            } finally {
                dir.delete()
            }
        }
}
