@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.bike

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ridelabz.engwespeedtool.reporting.SessionLogFiles
import io.github.ridelabz.engwespeedtool.session.FakeBike
import io.github.ridelabz.engwespeedtool.session.FakeBikeTransport
import io.github.ridelabz.engwespeedtool.session.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * An hour of riding on the simulated bike, with the session log stored every 30 seconds as the app does:
 * the log in memory and its stored file stay within their limit all along and hold the same text, keep
 * their start record and the newest records, and the coroutines running don't pile up with time.
 */
@RunWith(RobolectricTestRunner::class)
class LogSoakTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = Regex("session-([0-9]+)\\.jsonl")

    private fun seq(line: String): Int =
        Regex("\"seq\":([0-9]+)")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 0

    /** Every coroutine running under [job], however deep. */
    private fun jobs(job: Job): Int = 1 + job.children.sumOf { jobs(it) }

    @Test
    fun `an hour of riding keeps the log within its limit, with its start and the newest records`() =
        runTest(timeout = 10.minutes) {
            val store = AppStore(context.getSharedPreferences("soak-${System.nanoTime()}", Context.MODE_PRIVATE))
            val bike = FakeBike(backgroundScope, riding = true)
            val services = BikeServices(context, store) { s -> simulated(FakeBikeTransport(bike), s) }
            services.acceptRisk(LocalDate.of(2026, 10, 15))
            val session = services.real(backgroundScope).session
            session.start(bike.address)
            withTimeout(2.minutes) { session.state.first { it is SessionState.Ready } }

            val limit = SessionLogFiles.DEFAULT_RUN_BYTES
            val dir = File(context.filesDir, "session-logs")

            // This run's file: the highest number.
            fun stored(): File =
                checkNotNull(
                    dir.walk().filter { it.isFile && name.matches(it.name) }.maxByOrNull {
                        name.matchEntire(it.name)!!.groupValues[1].toInt()
                    },
                )

            val root = checkNotNull(backgroundScope.coroutineContext[Job])
            var jobsAt10 = 0
            var largest = 0L
            var lastSeq = 0
            repeat(120) { i ->
                delay(30.seconds)
                services.saveLog()
                val text = services.logText.toString()
                val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
                largest = maxOf(largest, bytes)
                assertTrue("the log in memory: $bytes bytes", bytes <= limit)
                // The save happened: the stored file holds the log as it is now.
                assertEquals(text, stored().readText())
                assertTrue(stored().length() <= limit)
                if (i == 19) jobsAt10 = jobs(root)
                val seq = seq(text.lines().last { it.isNotBlank() })
                // Status kept coming until the end: at least a record a second.
                if (i > 0) assertTrue("records at ${i / 2} min: $lastSeq to $seq", seq >= lastSeq + 30)
                lastSeq = seq
            }
            assertTrue("coroutines at 10 min: $jobsAt10, at 60: ${jobs(root)}", jobs(root) <= jobsAt10)

            val text = services.logText.toString()
            assertTrue("the hour filled the log: $largest", largest > limit * 9 / 10)
            assertTrue("no storage failure", !text.contains("\"where\":\"log_storage\""))
            val lines = text.lines().filter { it.isNotBlank() }
            assertTrue(lines.first(), lines.first().contains("\"event\":\"session_start\""))
            assertTrue("older records went", seq(lines[1]) > 100)
            assertTrue("still connected", session.state.value is SessionState.Ready)
            session.stop()
        }
}
