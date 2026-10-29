@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Lights
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.Reading
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.StatusLayout
import io.github.ridelabz.engwespeedtool.protocol.StatusReport
import io.github.ridelabz.engwespeedtool.protocol.Verification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CompatibilityCheckTest {
    private fun passedAll(report: CompatibilityReport) = report.checks.all { it.second == CheckResult.PASSED }

    /** The session log's records of compatibility checks. */
    private fun Rig.checkRecords(): List<JsonObject> = records().filter { it.s("event") == "compatibility_check" }

    @Test
    fun `a check reads the identity and 30 s of status, writes nothing else, and stops`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            // Even with "keep this speed" set for this bike, the check never sends it.
            rig.session.keepSpeed(rig.bike.address, SpeedLimit.KMH_32)
            val progress = mutableListOf<CompatibilityProgress>()
            val report = CompatibilityCheck(rig.session).run(rig.bike.address) { progress += it }
            assertEquals(CompatibilityOutcome.Checked, report.outcome)
            assertTrue(passedAll(report))
            assertEquals(Verification.VERIFIED, report.classification?.verification)
            assertTrue(report.statusFrames >= 20, "${report.statusFrames} status frames")
            assertEquals(false, report.unreadableFrames)
            assertTrue(report.fields.all { it.result == FieldResult.PLAUSIBLE }, report.fields.toString())
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
            assertEquals(SessionState.Idle, rig.session.state.value)
            assertEquals(CompatibilityProgress.Reading(30, 30), progress.last())
            assertEquals(FakeBike.REFERENCE_SERVICES, report.services, "what the bike offered")
            assertEquals(30, report.sampledSeconds)
            assertTrue(CompatibilityProgress.Step(CheckStep.CONNECTING) in progress)
            // One record of the result: names and counts, never a value the bike sent.
            val record = rig.checkRecords().single()
            assertEquals("checked", record.s("outcome"))
            assertEquals(null, record["reason"])
            assertEquals(
                ProtocolCheck.entries.associate { it.name.lowercase() to "passed" },
                record.getValue("checks").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
            assertEquals(report.statusFrames.toString(), record.s("status_frames"))
            assertEquals("30", record.s("sampled_s"))
            assertEquals(
                CheckedField.entries.associate { it.name.lowercase() to "plausible" },
                record.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
            assertEquals(
                emptyList<String>(),
                record.getValue("unverified").jsonArray.map { it.jsonPrimitive.content },
                "every value read has a meaning verified for the bike",
            )
            val services = record.getValue("services").jsonArray.map { it.jsonObject }
            assertEquals(FakeBike.REFERENCE_SERVICES.map { BikeGatt.text(it.uuid) }, services.map { it.s("uuid") })
            val bikeService = services.single { it.s("uuid") == "0x1820" }
            assertEquals(
                listOf(
                    """{"uuid":"0x1822","properties":["write_without_response","write"],"descriptors":[]}""",
                    """{"uuid":"0x1821","properties":["notify"],"descriptors":["0x2902"]}""",
                ),
                bikeService.getValue("characteristics").jsonArray.map { it.toString() },
            )
            // Nothing after it either.
            delay(300.seconds)
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
        }

    @Test
    fun `an unverified bike's check lists every value it read as unverified`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope, model = FakeBike.DEMO_OTHER_MODEL))
            val report = CompatibilityCheck(rig.session).run(rig.bike.address) {}
            assertEquals(Verification.UNVERIFIED, report.classification?.verification)
            assertEquals(
                CheckedField.entries.map { it.name.lowercase() },
                rig
                    .checkRecords()
                    .single()
                    .getValue("unverified")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun `a paired bike is checked directly`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope).apply { pairedWithPhone = true })
            val report = CompatibilityCheck(rig.session).run(rig.bike.address)
            assertEquals(CompatibilityOutcome.Checked, report.outcome)
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
        }

    @Test
    fun `a bike without the service fails the first check and gets no frame`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope, services = emptyList()))
            val report = CompatibilityCheck(rig.session).run(rig.bike.address)
            assertEquals(CompatibilityOutcome.Incompatible(CheckFailure.NO_SERVICE), report.outcome)
            assertEquals(emptyList<GattService>(), report.services, "a bike that offers nothing lists nothing")
            val record = rig.checkRecords().single()
            assertEquals("incompatible", record.s("outcome"))
            assertEquals("no_service", record.s("reason"))
            assertEquals(
                "failed",
                record
                    .getValue("checks")
                    .jsonObject
                    .getValue("service")
                    .jsonPrimitive.content,
            )
            assertEquals("[]", record.getValue("services").toString())
            assertEquals(
                listOf(CheckResult.FAILED) + List(ProtocolCheck.entries.size - 1) { CheckResult.NOT_RUN },
                report.checks.map { it.second },
            )
            assertTrue(rig.bike.writes.isEmpty())
            assertEquals(SessionState.Idle, rig.session.state.value)
        }

    @Test
    fun `a bike that isn't found is said so, once`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { inRange = false }
            val rig = Rig(this, bike)
            val report = CompatibilityCheck(rig.session).run(bike.address)
            assertEquals(CompatibilityOutcome.NotConnected(StopReason.NOT_FOUND), report.outcome)
            assertTrue(report.checks.all { it.second == CheckResult.NOT_RUN })
            assertTrue(bike.writes.isEmpty())
            val record = rig.checkRecords().single()
            assertEquals("not_connected", record.s("outcome"))
            assertEquals("not_found", record.s("reason"))
            assertEquals(null, record["sampled_s"], "no status read")
        }

    @Test
    fun `a connection lost during the sample ends it at once, without a reconnection`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            val check = async { CompatibilityCheck(rig.session).run(rig.bike.address) }
            delay(20.seconds)
            rig.bike.dropLink()
            val report = check.await()
            assertEquals(CompatibilityOutcome.LinkLost, report.outcome)
            assertTrue(passedAll(report), "the checks had passed")
            assertEquals("link_lost", rig.checkRecords().single().s("outcome"))
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
            delay(300.seconds)
            assertEquals(1, rig.bike.links, "no reconnection")
        }

    private fun status(
        speed: Double = 12.0,
        odometer: Double = 100.0,
        battery: Int = 80,
        lights: Lights = Lights.OFF,
        verified: Boolean = true,
    ) = StatusReport(
        Reading(speed, verified),
        Reading(30.0, verified),
        Reading(odometer, verified),
        Reading(2, verified),
        Reading(battery, verified),
        Reading(lights, verified),
        Reading(PowerState.ON, verified),
        Reading(20, verified),
        Reading(90, verified),
        Reading(41.0, verified),
        Reading(100, verified),
    )

    private fun result(
        reports: List<StatusReport>,
        field: CheckedField,
        layout: StatusLayout? = null,
    ) = FieldSanity.check(reports, layout).single { it.field == field }

    @Test
    fun `values are checked against what an e-bike can show`() {
        assertEquals(FieldResult.PLAUSIBLE, result(listOf(status(), status(speed = 25.0)), CheckedField.SPEED).result)
        assertEquals(
            FieldResult.OUT_OF_RANGE,
            result(listOf(status(), status(battery = 120)), CheckedField.BATTERY).result,
        )
        assertEquals(FieldResult.OUT_OF_RANGE, result(listOf(status(speed = 250.0)), CheckedField.SPEED).result)
        assertEquals(
            FieldResult.WENT_BACK,
            result(listOf(status(odometer = 100.2), status(odometer = 100.1)), CheckedField.ODOMETER).result,
        )
        assertEquals(
            FieldResult.OUT_OF_RANGE,
            result(listOf(status(lights = Lights.UNKNOWN)), CheckedField.LIGHTS).result,
        )
        assertEquals(FieldResult.NOT_READ, result(emptyList(), CheckedField.VOLTAGE).result)
        assertEquals(false, result(listOf(status(verified = false)), CheckedField.SPEED).verified)
        // The decoder drops a battery above 100 %: in the layout but not in the status, it is out of range.
        val layout = ProfileRegistry.bundled().find(ProfileRegistry.REFERENCE_MODEL)!!.statusLayout
        val dropped = StatusReport(null, null, null, null, null, null, null, null, null, null, null)
        assertEquals(FieldResult.OUT_OF_RANGE, result(listOf(status(), dropped), CheckedField.BATTERY, layout).result)
        assertEquals(FieldResult.PLAUSIBLE, result(listOf(status()), CheckedField.BATTERY, layout).result)
    }

    @Test
    fun `a check's run sends nothing, whoever asks, and never reconnects`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            rig.session.check(rig.bike.address)
            withTimeout(2.minutes) { rig.session.state.first { it is SessionState.Ready } }
            assertEquals(CommandOutcome.Refused(Refusal.NOT_READY), rig.session.setSpeedLimit(SpeedLimit.KMH_32))
            assertEquals(CommandOutcome.Refused(Refusal.NOT_READY), rig.session.setLights(true))
            rig.bike.dropLink()
            delay(300.seconds)
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
            assertEquals(1, rig.bike.links)
            assertTrue(rig.session.state.value is SessionState.Stopped, "${rig.session.state.value}")
        }

    @Test
    fun `a bike whose notifications can't be enabled passed the earlier checks`() =
        runTest {
            val services =
                FakeBike.REFERENCE_SERVICES.map { s ->
                    s.copy(
                        characteristics =
                            s.characteristics.map {
                                if (it.uuid == BikeGatt.NOTIFY) it.copy(descriptors = emptyList()) else it
                            },
                    )
                }
            val rig = Rig(this, FakeBike(backgroundScope, services = services))
            val report = CompatibilityCheck(rig.session).run(rig.bike.address)
            assertEquals(CompatibilityOutcome.Incompatible(CheckFailure.NO_NOTIFY), report.outcome)
            assertEquals(
                listOf(
                    CheckResult.PASSED,
                    CheckResult.FAILED,
                    CheckResult.PASSED,
                    CheckResult.NOT_RUN,
                    CheckResult.NOT_RUN,
                ),
                report.checks.map { it.second },
            )
            assertEquals(null, report.sampledSeconds)
        }

    @Test
    fun `a bike without a notify characteristic never ran the write check`() =
        runTest {
            val services =
                FakeBike.REFERENCE_SERVICES.map { s ->
                    s.copy(characteristics = s.characteristics.filter { it.uuid != BikeGatt.NOTIFY })
                }
            val rig = Rig(this, FakeBike(backgroundScope, services = services))
            val report = CompatibilityCheck(rig.session).run(rig.bike.address)
            assertEquals(CompatibilityOutcome.Incompatible(CheckFailure.NO_NOTIFY), report.outcome)
            assertEquals(
                listOf(
                    CheckResult.PASSED,
                    CheckResult.FAILED,
                    CheckResult.NOT_RUN,
                    CheckResult.NOT_RUN,
                    CheckResult.NOT_RUN,
                ),
                report.checks.map { it.second },
            )
            assertTrue(rig.bike.writes.isEmpty())
        }

    @Test
    fun `the services listed are the check's own, even when its run ended during the sample`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            val check = async { CompatibilityCheck(rig.session).run(rig.bike.address) }
            withTimeout(2.minutes) { rig.session.state.first { it is SessionState.Ready } }
            delay(5.seconds)
            rig.session.stop()
            val report = check.await()
            assertEquals(CompatibilityOutcome.LinkLost, report.outcome)
            assertEquals(FakeBike.REFERENCE_SERVICES, report.services)
            assertEquals(emptyList<GattService>(), rig.session.services.value, "cleared by the stop")
        }

    @Test
    fun `a bike without the bike service still lists what it offered`() =
        runTest {
            val offered = FakeBike.REFERENCE_SERVICES.filter { it.uuid != BikeGatt.SERVICE }
            val rig = Rig(this, FakeBike(backgroundScope, services = offered))
            val report = CompatibilityCheck(rig.session).run(rig.bike.address)
            assertEquals(CompatibilityOutcome.Incompatible(CheckFailure.NO_SERVICE), report.outcome)
            assertEquals(offered, report.services)
            assertTrue(rig.bike.writes.isEmpty())
        }

    @Test
    fun `without the rider's acceptance the checks before the identity passed, and nothing is written`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope), accepted = false)
            val report = CompatibilityCheck(rig.session).run(rig.bike.address)
            assertEquals(CompatibilityOutcome.NotConnected(StopReason.NOT_ACCEPTED), report.outcome)
            assertEquals(
                listOf(
                    CheckResult.PASSED,
                    CheckResult.PASSED,
                    CheckResult.PASSED,
                    CheckResult.PASSED,
                    CheckResult.NOT_RUN,
                ),
                report.checks.map { it.second },
            )
            assertTrue(rig.bike.writes.isEmpty())
            val record = rig.checkRecords().single()
            assertEquals("not_connected", record.s("outcome"))
            assertEquals("not_accepted", record.s("reason"))
            assertEquals(
                listOf("passed", "passed", "passed", "passed", "not_run"),
                record
                    .getValue("checks")
                    .jsonObject.values
                    .map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun `a check that takes too long is recorded as such`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { inRange = false }
            // Still searching when the time is up: the session keeps trying a bike it hasn't found.
            val rig = Rig(this, bike)
            val report = CompatibilityCheck(rig.session, settleTimeout = 1.seconds).run(bike.address)
            assertEquals(CompatibilityOutcome.NotConnected(null), report.outcome)
            val record = rig.checkRecords().single()
            assertEquals("not_connected", record.s("outcome"))
            assertEquals("too_long", record.s("reason"))
        }

    @Test
    fun `a check left before it ends records nothing and stops its own run`() =
        runTest {
            val rig = Rig(this, FakeBike(backgroundScope))
            val check = launch { CompatibilityCheck(rig.session).run(rig.bike.address) }
            delay(15.seconds)
            check.cancelAndJoin()
            assertEquals(emptyList<JsonObject>(), rig.checkRecords())
            assertEquals(SessionState.Idle, rig.session.state.value)
            assertEquals(listOf(BikeCommand.ReadIdentity), rig.bike.commands)
        }

    @Test
    fun `status that doesn't fit the layout is said so`() =
        runTest {
            val bike = FakeBike(backgroundScope)
            val rig = Rig(this, bike)
            val check = async { CompatibilityCheck(rig.session).run(bike.address) }
            withTimeout(2.minutes) { rig.session.state.first { it is SessionState.Ready } }
            bike.statusPayloadLength = 50
            val report = check.await()
            assertEquals(CompatibilityOutcome.Checked, report.outcome)
            assertTrue(report.unreadableFrames)
            assertEquals(30, report.sampledSeconds)
        }

    @Test
    fun `a check replaced by another run ends at once and leaves that run alone`() =
        runTest {
            val bike = FakeBike(backgroundScope).apply { inRange = false }
            val rig = Rig(this, bike)
            val check = async { CompatibilityCheck(rig.session).run(bike.address) }
            delay(2.seconds)
            bike.inRange = true
            rig.session.start(bike.address)
            val report = check.await()
            assertEquals(CompatibilityOutcome.Interrupted, report.outcome)
            withTimeout(2.minutes) { rig.session.state.first { it is SessionState.Ready } }
            val record = rig.checkRecords().single()
            assertEquals("interrupted", record.s("outcome"))
            assertEquals("[]", record.getValue("services").toString(), "the other run's services aren't the check's")
        }

    @Test
    fun `a Bluetooth number reads short, any other UUID by its first 8 digits`() {
        assertEquals("0x1820", BikeGatt.text(BikeGatt.SERVICE))
        assertEquals("0x2902", BikeGatt.text(BikeGatt.CLIENT_CONFIG))
        val vendor = java.util.UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        assertEquals(
            "6e400001-****-****-****-************",
            BikeGatt.text(vendor),
            "the parts a device may fill with its own values",
        )
    }
}
