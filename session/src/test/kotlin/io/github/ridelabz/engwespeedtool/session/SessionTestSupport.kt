@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.Hex
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.reporting.SessionClock
import io.github.ridelabz.engwespeedtool.reporting.SessionLog
import io.github.ridelabz.engwespeedtool.reporting.SessionMeta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/** A session on fake bikes, with its log. [wrap] can put a misbehaving transport around the fake one. */
internal class Rig(
    val scope: TestScope,
    vararg bikes: FakeBike,
    accepted: Boolean = true,
    config: SessionConfig = SessionConfig(),
    profiles: ProfileRegistry = ProfileRegistry.bundled(),
    wrap: (BikeTransport) -> BikeTransport = { it },
) {
    val bike = bikes.first()
    val transport = FakeBikeTransport(*bikes)
    val out = StringBuilder()
    var accepted = accepted

    /** The rider saw the firmware caveat. */
    var caveatSeen = true

    /** Bikes the rider acknowledged as unverified. */
    val acknowledged = mutableSetOf<BikeAddress>()

    /** The rider accepted an earlier text version (the current one only if [accepted]). */
    var acceptedEarlier = false

    /** Bikes the rider acknowledged for an earlier text version. */
    val acknowledgedEarlier = mutableSetOf<BikeAddress>()
    val consent =
        object : Consent {
            override fun riskAccepted() = this@Rig.accepted

            override fun riskEverAccepted() = this@Rig.accepted || acceptedEarlier

            override fun firmwareCaveatSeen() = caveatSeen

            override fun unverifiedAcknowledged(bike: BikeAddress) = bike in acknowledged

            override fun unverifiedEverAcknowledged(bike: BikeAddress) =
                bike in acknowledged || bike in acknowledgedEarlier
        }
    private val clock =
        object : SessionClock {
            override fun today(): LocalDate = LocalDate.of(2026, 1, 2)

            override fun elapsedNanos(): Long = scope.testScheduler.currentTime * 1_000_000
        }
    val log = SessionLog(out, clock, SessionMeta("0.1.0", "15", 35, "Google", "Pixel 9"))
    val session =
        BikeSession(
            scope.backgroundScope,
            wrap(transport),
            profiles,
            log,
            consent,
            config,
        )

    fun records(): List<JsonObject> =
        out.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    fun values(
        event: String,
        name: String,
    ): List<String> =
        records()
            .filter { it.s("event") == event && it["name"]?.jsonPrimitive?.content == name }
            .map { it.s("value") }

    /** The steps the session went through, from the log (a state flow may skip quick changes). */
    fun steps(): List<String> = values("state", "check")

    /** Starts as the onboarding does, with a fresh advertisement: a failed first attempt isn't retried. */
    suspend fun startOnce() {
        session.start(transport.scan(bike.address).first())
    }

    /** Waits for the first state that isn't idle or checking. */
    suspend fun settled(): SessionState =
        withTimeout(2.minutes) {
            session.state.first { it !is SessionState.Idle && it !is SessionState.Checking }
        }
}

internal fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content

/** A transport that behaves like [inner], except where a test overrides it. */
internal open class WrappedTransport(
    private val inner: BikeTransport,
) : BikeTransport {
    override val radio = inner.radio

    override suspend fun scanSlot() = inner.scanSlot()

    override fun scan(address: BikeAddress?): Flow<Advertisement> = inner.scan(address)

    override fun pairedBikes(): Set<BikeAddress> = inner.pairedBikes()

    override suspend fun connect(address: BikeAddress): BikeLink = inner.connect(address)
}

/** Every form in which an address or a serial could leak into text. */
internal object Secrets {
    fun address(address: BikeAddress): List<String> {
        val b = address.value.split(":")
        // Little-endian, as an address usually sits in a frame's payload.
        val r = b.reversed()
        val plain = b.joinToString("")
        return listOf(":", "-", ".", " ", "").flatMap { listOf(b.joinToString(it), r.joinToString(it)) } +
            listOf(
                plain.chunked(4).joinToString("."),
                // As it could sit in a UUID's middle groups.
                plain.chunked(4).joinToString("-"),
                b.takeLast(2).joinToString(":"),
                b.takeLast(3).joinToString(""),
                r.take(3).joinToString(""),
                // An advertised name's suffix comes from the address.
                "ENGWE-" + b.last(),
                "ENGWE-" + b.takeLast(2).joinToString(""),
            )
    }

    fun serial(serial: String): List<String> =
        listOf(serial, Hex.encode(serial.toByteArray())) +
            serial.windowed(4) +
            serial.windowed(4).map { Hex.encode(it.toByteArray()) }

    /**
     * Fails if [text] holds any of [secrets]. Hex forms are matched in any case; others exactly, so that the
     * log's own lowercase words (`link`, `stop`) can't match a serial's letters.
     */
    fun assertAbsent(
        text: String,
        secrets: List<String>,
    ) {
        for (secret in secrets) {
            val hexForm = secret.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' || it in ":-. " }
            assertFalse(text.contains(secret, ignoreCase = hexForm), "leaked $secret")
        }
    }
}
