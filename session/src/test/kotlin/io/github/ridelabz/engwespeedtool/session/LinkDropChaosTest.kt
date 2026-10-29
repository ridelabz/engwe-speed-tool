@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Links that drop at any moment (also right after a tap, during its write or its echo), echoes that come
 * late or never, rejected writes, the display going off and on, Bluetooth switched off and on, and taps in
 * between, for an hour of riding: every frame is accounted for. Each tap that isn't refused writes exactly
 * once, at the tap, and a refused one never; nothing is repeated or queued. The only other frames are the
 * identity read (at most once per connection), "keep this speed" (the speed tapped last, written at most
 * once a minute) and "keep this assist level" (the level tapped last, at most once per connection and once per
 * power-on, and once a minute, only standing still). Some taps lock or unlock the bike. Half the seeds trust
 * restarts (as the app does), so the kept speed and level are also set again after a power-on; the other half
 * don't, and nothing is set again after one; a third ride a bike paired with the phone.
 *
 * The simulated bike's display can stay off for minutes: switching it on does nothing unless it is off, and
 * switching it off while it powers on cancels the "on". That gives plenty of refusals.
 */
class LinkDropChaosTest {
    private val speeds = SpeedLimit.entries.toList()
    private val levels = AssistLevel.entries.toList()

    private class Tap(
        val ms: Long,
        val command: String,
        val outcome: CommandOutcome,
    ) {
        var matched = false
    }

    private fun JsonObject.ms(): Long = (s("t").toDouble() * 1000).roundToLong()

    private fun token(command: BikeCommand): String =
        when (command) {
            is BikeCommand.SetSpeedLimit -> "set_speed_limit_${command.limit.kmh}"
            is BikeCommand.SetLights -> if (command.on) "set_lights_on" else "set_lights_off"
            is BikeCommand.SetAssistLevel -> "set_assist_level_${command.level.level}"
            is BikeCommand.SetLocked -> if (command.locked) "lock" else "unlock"
            BikeCommand.ReadIdentity -> "read_identity"
        }

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 4, 5, 6])
    fun `an hour of drops and taps writes each tap at most once, and nothing else`(seed: Long) =
        runTest {
            // Separate generators: a change in when the session suspends doesn't reshuffle the other.
            val taps = Random(seed)
            val chaos = Random(seed * 7919 + 13)
            val faults = Random(seed * 104_729 + 7)
            // Locks and unlocks on their own generator, so that the other taps stay as they were.
            val locks = Random(seed * 31 + 5)
            val trusted = seed % 2 == 0L
            val bike = FakeBike(backgroundScope).apply { pairedWithPhone = seed % 3 == 0L }
            val rig = Rig(this, bike, config = SessionConfig(restartsTrusted = trusted))
            val kept = speeds[taps.nextInt(speeds.size)]
            rig.session.keepSpeed(bike.address, kept)
            val keptLevel = levels[taps.nextInt(levels.size)]
            rig.session.keepAssistLevel(bike.address, keptLevel)
            rig.session.start(bike.address)

            backgroundScope.launch {
                while (true) {
                    delay(chaos.nextLong(200, 120_000).milliseconds)
                    when (chaos.nextInt(6)) {
                        0, 1 -> {
                            bike.dropLink()
                        }

                        2 -> {
                            bike.switchOff()
                        }

                        3, 4 -> {
                            bike.switchOn()
                        }

                        else -> {
                            rig.transport.setRadio(RadioState.OFF)
                            delay(chaos.nextLong(1, 30).seconds)
                            rig.transport.setRadio(RadioState.ON)
                        }
                    }
                }
            }

            val tapped = mutableListOf<Tap>()
            // The kept speed over time: a tapped speed becomes the kept one, refused or not.
            val keptAt = mutableListOf(0L to kept)
            val keptLevelAt = mutableListOf(0L to keptLevel)
            val end = testScheduler.currentTime + 60.minutes.inWholeMilliseconds
            while (testScheduler.currentTime < end) {
                delay(taps.nextLong(1, 90).seconds)
                // Echoes late or missing, writes rejected, and links dropped during the command.
                bike.echoDelay = faults.nextLong(0, 2500).milliseconds
                when (faults.nextInt(8)) {
                    0 -> bike.withholdNextWriteResponse = true
                    1 -> bike.rejectNextWrite = 133
                }
                if (faults.nextInt(3) == 0) {
                    val after = faults.nextLong(0, 300).milliseconds
                    backgroundScope.launch {
                        delay(after)
                        bike.dropLink()
                    }
                }
                val at = testScheduler.currentTime
                val drawn =
                    when (taps.nextInt(4)) {
                        0 -> BikeCommand.SetLights(taps.nextBoolean())
                        1 -> BikeCommand.SetAssistLevel(levels[taps.nextInt(levels.size)])
                        else -> BikeCommand.SetSpeedLimit(speeds[taps.nextInt(speeds.size)])
                    }
                val command = if (locks.nextInt(6) == 0) BikeCommand.SetLocked(locks.nextBoolean()) else drawn
                val outcome =
                    when (command) {
                        is BikeCommand.SetLights -> {
                            rig.session.setLights(command.on)
                        }

                        is BikeCommand.SetSpeedLimit -> {
                            keptAt += at to command.limit
                            rig.session.setSpeedLimit(command.limit)
                        }

                        is BikeCommand.SetAssistLevel -> {
                            keptLevelAt += at to command.level
                            rig.session.setAssistLevel(command.level)
                        }

                        is BikeCommand.SetLocked -> {
                            rig.session.setLocked(command.locked)
                        }

                        BikeCommand.ReadIdentity -> {
                            error("not a tap")
                        }
                    }
                tapped += Tap(at, token(command), outcome)
                // A fault meant for this tap never hits a later write (such as the next identity read).
                bike.withholdNextWriteResponse = false
                bike.rejectNextWrite = null
            }
            bike.echoDelay = null
            bike.switchOn()
            delay(2.minutes)
            rig.session.stop()

            val records = rig.records()
            val txs = mutableListOf<String>()
            val reapplied = mutableListOf<Long>()
            val assistReapplied = mutableListOf<Long>()
            var identityReads = 0
            // Per connection: re-applies after reconnecting (at most one) and after restarts (at most one per
            // power-on the status showed: off, then on again).
            var reconnectReapplies = 0
            var restartReapplies = 0
            var assistReconnects = 0
            var assistRestarts = 0
            var speed: Double? = null
            var powerOns = 0
            var power: String? = null
            for ((i, record) in records.withIndex()) {
                if (record.s("event") == "state" && record.s("name") == "check" && record.s("value") == "connecting") {
                    identityReads = 0
                    reconnectReapplies = 0
                    restartReapplies = 0
                    assistReconnects = 0
                    assistRestarts = 0
                    speed = null
                    powerOns = 0
                    power = null
                }
                if (record.s("event") == "status" && "power" in record) {
                    val now = record.s("power")
                    if (power == "off" && now != "off") powerOns++
                    power = now
                }
                if (record.s("event") == "state" && record.s("name") == "reapply") {
                    when (record.s("value")) {
                        "reconnect" -> reconnectReapplies++
                        "restart" -> restartReapplies++
                    }
                    assertTrue(trusted || restartReapplies == 0, "a re-apply after a power-on, without trusting it")
                    assertTrue(reconnectReapplies <= 1, "a second re-apply for one connection")
                    assertTrue(
                        restartReapplies <= powerOns,
                        "$restartReapplies restart re-applies for $powerOns power-ons",
                    )
                }
                if (record.s("event") == "status" && "speed_kmh" in record) speed = record.s("speed_kmh").toDouble()
                if (record.s("event") == "state" && record.s("name") == "reapply_assist") {
                    // On connecting, before any power-on seen on this connection; after a power-on only where
                    // restarts are trusted, at most once per power-on; only while the bike reported on and
                    // standing still.
                    when (record.s("value")) {
                        "reconnect" -> {
                            assistReconnects++
                            assertEquals(0, powerOns, "the connection's assist re-apply after a power-on")
                        }

                        "restart" -> {
                            assistRestarts++
                        }

                        else -> {
                            error("unknown reason: $record")
                        }
                    }
                    assertTrue(assistReconnects <= 1, "a second assist re-apply for one connection")
                    assertTrue(
                        trusted || assistRestarts == 0,
                        "an assist re-apply after a power-on, without trusting it",
                    )
                    assertTrue(assistRestarts <= powerOns, "$assistRestarts assist re-applies for $powerOns power-ons")
                    assertEquals("on", power, "an assist re-apply while the bike wasn't on")
                    assertEquals(0.0, speed, "an assist re-apply while moving")
                }
                if (record.s("event") != "tx") continue
                val command = record.s("command")
                txs += command
                // A lock only with the bike on and standing still, an unlock only with it off.
                if (command == "lock") {
                    assertEquals("on", power, "a lock while the bike wasn't on")
                    assertEquals(0.0, speed, "a lock while moving")
                }
                if (command == "unlock") assertEquals("off", power, "an unlock while the bike wasn't off")
                if (command == "read_identity") {
                    identityReads++
                    assertTrue(identityReads <= 1, "a second identity read on one connection")
                    continue
                }
                val before = records.getOrNull(i - 1)
                if (before != null && before.s("event") == "state" && before.s("name") == "reapply_assist") {
                    val at = record.ms()
                    val allowed =
                        setOf(keptLevelAt.last { it.first < at }.second) +
                            keptLevelAt.filter { it.first == at }.map { it.second }
                    assertTrue(
                        command in allowed.map { "set_assist_level_${it.level}" },
                        "re-applied $command at $at, kept $allowed",
                    )
                    assistReapplied += at
                    continue
                }
                if (before != null && before.s("event") == "state" && before.s("name") == "reapply") {
                    // A re-apply, written at once: the kept speed of that moment.
                    val at = record.ms()
                    val allowed =
                        setOf(keptAt.last { it.first < at }.second) + keptAt.filter { it.first == at }.map { it.second }
                    assertTrue(
                        command in allowed.map { "set_speed_limit_${it.kmh}" },
                        "re-applied $command at $at, kept $allowed",
                    )
                    reapplied += at
                    continue
                }
                // Otherwise it is exactly one tap, at that moment, that wasn't refused.
                val tap = tapped.singleOrNull { !it.matched && it.ms == record.ms() && it.command == command }
                assertTrue(
                    tap != null && tap.outcome !is CommandOutcome.Refused,
                    "$command at ${record.ms()} matches no tap",
                )
                tap!!.matched = true
            }
            for (tap in tapped) {
                assertEquals(
                    tap.outcome !is CommandOutcome.Refused,
                    tap.matched,
                    "${tap.command} at ${tap.ms}: ${tap.outcome}",
                )
            }
            for ((a, b) in reapplied.zipWithNext()) assertTrue(b - a >= 60_000, "re-applies written at $a and $b")
            for ((a, b) in assistReapplied.zipWithNext()) {
                assertTrue(b - a >= 60_000, "assist re-applies written at $a and $b")
            }
            // What the bike received is what was written, in order, less what didn't reach it.
            var next = 0
            for (received in bike.commands.map(::token)) {
                while (next < txs.size && txs[next] != received) next++
                assertTrue(next < txs.size, "the bike received $received, which wasn't written then")
                next++
            }

            // The hour went through many connections, writes, refusals, failures and re-applies.
            val outcomes = tapped.map { it.outcome }
            assertTrue(bike.links >= 10, "${bike.links} links")
            assertTrue(outcomes.count { it is CommandOutcome.Refused } >= 10, "refusals")
            assertTrue(outcomes.count { it !is CommandOutcome.Refused } >= 5, "writes")
            assertTrue(outcomes.any { it == CommandOutcome.Failed }, "a failed command")
            assertTrue(reapplied.isNotEmpty(), "a re-apply")
            assertTrue(assistReapplied.isNotEmpty(), "an assist re-apply")
            assertTrue(tapped.any { it.command.startsWith("set_assist_level_") && it.matched }, "an assist tap written")
            assertTrue(
                tapped.any { it.command in setOf("lock", "unlock") && it.matched },
                "a lock or an unlock written",
            )
            if (trusted) {
                for (name in listOf("reapply", "reapply_assist")) {
                    assertTrue(
                        records.any { it.s("event") == "state" && it.s("name") == name && it.s("value") == "restart" },
                        "$name after a restart",
                    )
                }
            }
        }
}
