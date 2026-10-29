package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.BikeClassifier
import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Classification
import io.github.ridelabz.engwespeedtool.protocol.Frame
import io.github.ridelabz.engwespeedtool.protocol.FrameReassembler
import io.github.ridelabz.engwespeedtool.protocol.IdentityParser
import io.github.ridelabz.engwespeedtool.protocol.Lights
import io.github.ridelabz.engwespeedtool.protocol.OutgoingFrame
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.ProtocolChecks
import io.github.ridelabz.engwespeedtool.protocol.Sanitiser
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.StatusDecoder
import io.github.ridelabz.engwespeedtool.protocol.StatusField
import io.github.ridelabz.engwespeedtool.protocol.StatusLayout
import io.github.ridelabz.engwespeedtool.protocol.StatusReport
import io.github.ridelabz.engwespeedtool.protocol.StreamEvent
import io.github.ridelabz.engwespeedtool.protocol.Verification
import io.github.ridelabz.engwespeedtool.reporting.CheckRecord
import io.github.ridelabz.engwespeedtool.reporting.SessionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Timeouts for finding, connecting and checking a bike, in one place so that they can be tuned on a real bike. */
public data class SessionConfig(
    /** How long to look for the bike's advertisement. The bike advertises slowly. A paired bike isn't looked for. */
    val searchTimeout: Duration = 20.seconds,
    val connectTimeout: Duration = 20.seconds,
    /** Service discovery, the MTU exchange, enabling notifications, and the identity read's acknowledgement. */
    val operationTimeout: Duration = 10.seconds,
    /**
     * The first valid status frame: about every 5 s with the display off or the bike locked, so this leaves
     * room for one lost or cut-short frame.
     */
    val statusTimeout: Duration = 12.seconds,
    /** The identity reply, after the bike acknowledged the read. */
    val identityTimeout: Duration = 3.seconds,
    /** A speed or light command counts as confirmed when the bike echoes it this soon after it was sent. */
    val echoTimeout: Duration = 2.seconds,
    /**
     * A command needs a readable status this recent that reports the bike on (a status arrives about once
     * a second with the display on), stricter than [staleAfter], which only marks the shown values.
     */
    val commandStatusMaxAge: Duration = 3.seconds,
    /**
     * After the echo of a light command, how long the status may take to show the lights' new state, on a
     * bike whose lights reading is verified (a status arrives about once a second with the display on).
     */
    val lightsStatusTimeout: Duration = 3.seconds,
    /**
     * After the echo of an assist-level command, how long the status may take to show the new level (on the
     * tested bike it showed it about a second after the echo; a status arrives about once a second).
     */
    val assistStatusTimeout: Duration = 3.seconds,
    /**
     * After the answer to a lock or an unlock, how long the status may take to show the bike off or on: the
     * reference bike sent one at once, but with the bike off a status arrives only about every 5 s.
     */
    val powerStatusTimeout: Duration = 6.seconds,
    /**
     * Whether a power-on seen in the status (the verified power flags off, then on) is taken as a restart
     * of the bike, after which "keep this speed" and "keep this assist level" set the kept speed and level
     * again. On the tested bike both kinds of power-on reset the limit to 25 km/h: switching off and on at the
     * display, and a lock and an unlock (which look the same in the status), tried with this app's own.
     * Without it the session still reports [LimitState.Restarted], but sends nothing by itself after one.
     */
    val restartsTrusted: Boolean = true,
    /** "Keep this speed" waits until the bike has reported on this long before it sends anything. */
    val reapplySettle: Duration = 3.seconds,
    /** After a power-on, how long "keep this speed" waits for the bike to report on before it gives up. */
    val reapplyOnWait: Duration = 30.seconds,
    /**
     * "Keep this speed" (and, separately, "keep this assist level") writes at most once in this long, whatever
     * the triggers (a flapping flag, reconnects); at least a minute, so that no setting can turn it into a loop.
     */
    val reapplyMinInterval: Duration = 60.seconds,
    /** Ride data counts as stale after this long without a readable status frame (every 5 s with the display off). */
    val staleAfter: Duration = 10.seconds,
    /** Waits before reconnecting, after the 1st, 2nd, … failed attempt in a row; the last one repeats. */
    val retryDelays: List<Duration> = listOf(1, 2, 5, 10, 20, 30).map { it.seconds },
    /** A connection that drops sooner than this still counts as failed, so the waits keep growing. */
    val stableAfter: Duration = 30.seconds,
) {
    init {
        require(retryDelays.isNotEmpty() && retryDelays.all { it.isPositive() }) { "retry delays must be positive" }
        require(echoTimeout.isPositive() && echoTimeout <= 2.seconds) { "the echo counts within 2 s at most" }
        require(
            lightsStatusTimeout.isPositive() &&
                assistStatusTimeout.isPositive() &&
                powerStatusTimeout.isPositive() &&
                commandStatusMaxAge.isPositive(),
        ) { "timeouts must be positive" }
        require(reapplySettle.isPositive() && reapplyOnWait.isPositive()) { "keep-speed waits must be positive" }
        require(reapplyMinInterval >= 60.seconds) { "keep-speed writes at most once a minute" }
    }

    /** The wait after [failures] failed attempts in a row. */
    public fun retryDelay(failures: Int): Duration =
        retryDelays[(failures.coerceAtLeast(1) - 1).coerceAtMost(retryDelays.size - 1)]
}

/** The steps of finding, connecting and checking a bike, in order. */
public enum class CheckStep { SEARCHING, CONNECTING, SERVICES, NOTIFICATIONS, STATUS, IDENTITY }

/** The protocol check a bike failed. Such a bike gets no frames, at most the one identity read. */
public enum class CheckFailure {
    /** No bike service. */
    NO_SERVICE,

    /** No notify characteristic with notifications. */
    NO_NOTIFY,

    /** No write characteristic with acknowledged writes, which the app needs to know that a command arrived. */
    NO_ACKNOWLEDGED_WRITE,

    /** No valid status frame in time. */
    NO_STATUS,

    /** No usable identity reply. */
    NO_IDENTITY,
}

/** Why an attempt ended without a usable connection. */
public enum class StopReason {
    /**
     * The bike didn't advertise within the search time: off, out of range, or connected to another phone
     * or app (the bike doesn't advertise while connected, so the app can't tell these apart).
     */
    NOT_FOUND,

    /**
     * The bike advertised, or is paired with this phone, but the connection failed: possibly held by
     * another app or phone, or a paired bike that is off or out of range.
     */
    CONNECT_FAILED,

    /** The connection dropped during the checks or afterwards. */
    LINK_LOST,

    /** Bluetooth is off or not permitted (the session waits for it), or the phone has none (the session ends). */
    BLUETOOTH_UNAVAILABLE,

    /** The rider hasn't accepted the risks, so not even the identity is read. */
    NOT_ACCEPTED,
}

/**
 * The session's [state] with the number of the [run] it belongs to: [BikeSession.start] returns the number of
 * the run it starts, and [BikeSession.stop] ends it with a higher one. A state of an earlier run (still shown
 * right after a new start, until the new run reports) has a lower number. A run's reconnections share its
 * number. Not a data class on purpose: every status is a new value, so a repeated state is still emitted.
 */
public class SessionStatus internal constructor(
    public val run: Int,
    public val state: SessionState,
) {
    override fun toString(): String = "SessionStatus(run=$run, $state)"
}

/** What a [BikeSession] is doing. */
public sealed interface SessionState {
    public data object Idle : SessionState

    public data class Checking(
        val step: CheckStep,
    ) : SessionState

    /** Connected and checked. */
    public class Ready internal constructor(
        public val classification: Classification,
    ) : SessionState {
        override fun toString(): String = "Ready(${classification.verification})"
    }

    /** A protocol check failed. The session writes nothing to this bike and doesn't reconnect. */
    public data class Incompatible(
        val failure: CheckFailure,
    ) : SessionState

    /**
     * The session ended: the first attempt of [BikeSession.start] with an advertisement failed, or the rider
     * hasn't accepted the risks. Start it again to retry.
     */
    public data class Stopped(
        val reason: StopReason,
        val failure: LinkFailure? = null,
    ) : SessionState

    /**
     * An attempt failed or the connection dropped; the session tries again after a wait, and once Bluetooth
     * is back. [attempt] counts failed attempts and short-lived connections in a row. Nothing that was
     * pending is sent again: each new connection runs the checks from the start.
     */
    public data class Retrying(
        val reason: StopReason,
        val failure: LinkFailure?,
        val attempt: Int,
    ) : SessionState
}

/**
 * Finds one bike, connects, checks that it speaks the protocol, reads its identity once and classifies it.
 *
 * The checks: the bike service with a notify characteristic and an acknowledged-write characteristic, an MTU
 * exchange, notifications, a valid status frame. Only then, and only while the rider has accepted the risks
 * ([consent]), the session writes the checks' one frame per connection: the identity read. A bike that fails
 * a check gets no frame at all (enabling notifications writes the bike's client configuration, nothing else).
 * The identity reply is reduced to its model on arrival. Every step goes to [log], which sanitises at
 * capture time.
 *
 * Once connected and checked, the session decodes the status frames into [ride] and reconnects by itself
 * when the connection drops, with growing waits, for as long as it runs. An incompatible bike ends it.
 *
 * A bike paired with the phone may never be heard advertising, because Android keeps a link to it, so the
 * session connects to a paired bike directly, without searching. Only a bike that isn't paired is searched
 * for by its address first.
 *
 * Once a bike is connected and checked, [setSpeedLimit] and [setLights] each send one command, never
 * repeated, if [WriteGate] allows it: the rider accepted the risks ([consent]), acknowledged an unverified
 * bike or, for a speed change other than back to 25 on a verified one, saw the firmware caveat; a status from
 * the last few seconds reports the bike on; and no other command is being sent. [limit] says what the
 * session sent and saw on the current connection.
 *
 * "Keep this speed" ([keepSpeed]): the session sets the kept speed of the kept bike again by itself, through
 * the same gate, only in a run started for that bike by its address ([start] with an address, never a check
 * of a found bike): at most once per connection (soon after it connects, if the bike reported on from the
 * start and stays on), and, only where [SessionConfig.restartsTrusted], once per power-on it sees; never
 * more often than [SessionConfig.reapplyMinInterval] (counted from the last re-apply that was written, for
 * as long as this session object lives), and never retried. A speed the rider sends to the kept bike
 * becomes the kept speed.
 *
 * [setAssistLevel] sends one assist-level command through the same gate (no firmware caveat: it isn't a speed
 * change), only to a bike whose profile offers that level. "Keep this assist level" ([keepAssistLevel]) sets
 * the kept level of the kept bike again, right after "keep this speed" (one command after the other), on the
 * same occasions (once per connection, and where restarts are trusted, once per power-on) and the same
 * conditions plus one: the bike reads 0 km/h. Never in a check, at most once a minute (on its own count),
 * never retried; a level the rider sends from the app becomes the kept one, and a level changed on the bike's
 * display changes nothing.
 *
 * [setLocked] locks or unlocks the bike, which switches it off or on, through the same gate, only on a bike whose
 * profile offers it: a lock needs the bike reported on and standing still (a verified 0 km/h), an unlock needs
 * it reported off. Nothing ever sends one by itself. An unlock that switches the bike on is a power-on like
 * any other.
 */
public class BikeSession(
    private val scope: CoroutineScope,
    private val transport: BikeTransport,
    private val profiles: ProfileRegistry,
    private val log: SessionLog,
    private val consent: Consent,
    private val config: SessionConfig = SessionConfig(),
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val mutableStatus = MutableStateFlow(SessionStatus(0, SessionState.Idle))
    private val mutableRide = MutableStateFlow(RideData.NONE)
    private val mutableLimit = MutableStateFlow<LimitState>(LimitState.Unknown)
    private val mutableAssist = MutableStateFlow<AssistState>(AssistState.Idle)
    private val mutableServices = MutableStateFlow<List<GattService>>(emptyList())
    private val lock = Any()
    private var job: Job? = null
    private var generation = 0

    // The connection that passed its checks, while it lasts; commands go to it only.
    private var ready: Connection? = null
    private val sending = AtomicBoolean(false)

    // "Keep this speed": the bike and the speed to set again.
    @Volatile
    private var kept: Pair<BikeAddress, SpeedLimit>? = null

    // "Keep this assist level": the bike and the level to set again.
    @Volatile
    private var keptAssist: Pair<BikeAddress, AssistLevel>? = null

    // The bike of the current run, for the rider's speeds to become the kept one.
    @Volatile
    private var runBike: BikeAddress? = null

    // The current run is the compatibility check's: nothing is sent on it.
    private var readOnly = false

    // "Keep this speed" sent something lately: it waits before it sends again.
    private val reapplyCooling = AtomicBoolean(false)

    // The same for "keep this assist level", on its own.
    private val assistCooling = AtomicBoolean(false)

    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** [state] with the number of its run, to tell the states of a run that was just started from earlier ones. */
    public val status: StateFlow<SessionStatus> = mutableStatus.asStateFlow()

    /** Values from the status frames of the current or last connection. */
    public val ride: StateFlow<RideData> = mutableRide.asStateFlow()

    /**
     * The services and characteristics the bike offered on the current run's last connection, as discovered
     * (UUIDs, properties and descriptors; no value is read); empty until they are discovered and after a
     * stop. For the compatibility check to show.
     */
    public val services: StateFlow<List<GattService>> = mutableServices.asStateFlow()

    /** What the session sent and saw about the assistance limit on the current connection. */
    public val limit: StateFlow<LimitState> = mutableLimit.asStateFlow()

    /**
     * What the session sent and saw about the assist level on the current connection: the level the bike
     * reports is in [ride]; this says whether the app is sending one, and how its last one ended.
     */
    public val assist: StateFlow<AssistState> = mutableAssist.asStateFlow()

    /** Whether a power-on seen in the status counts as a restart of the bike ([SessionConfig.restartsTrusted]). */
    public val restartsTrusted: Boolean get() = config.restartsTrusted

    /**
     * "Keep this speed": the speed the session sets again by itself on [bike] (see the class description),
     * or null for none. The caller passes only a speed the rider chose and confirmed any warning for.
     */
    public fun keepSpeed(
        bike: BikeAddress,
        limit: SpeedLimit?,
    ) {
        synchronized(lock) { kept = limit?.let { bike to it } }
    }

    /**
     * "Keep this assist level": the level the session sets again by itself on [bike], once per connection and
     * once per power-on it sees (see the class description), or null for none. The caller passes only a level
     * the rider chose in the app.
     */
    public fun keepAssistLevel(
        bike: BikeAddress,
        level: AssistLevel?,
    ) {
        synchronized(lock) { keptAssist = level?.let { bike to it } }
    }

    /**
     * Connects to the saved bike at [address] (directly if it is paired with this phone, otherwise once its
     * advertisement is heard), checks it, and keeps trying until it works. Replaces a session already running.
     * Returns the number of the new run ([status]).
     */
    public fun start(address: BikeAddress): Int = launch(address, seen = null, keepTrying = true)

    /**
     * Connects to and checks the bike of [advertisement], which was just seen while scanning, or a paired
     * bike ([Advertisement.paired]). A failed first attempt ends the session ([SessionState.Stopped]); once
     * the bike checked out, it reconnects like `start(address)`. Returns the number of the new run ([status]).
     */
    public fun start(advertisement: Advertisement): Int =
        launch(advertisement.address, advertisement, keepTrying = false)

    /**
     * Connects to and checks the bike at [address] once, read-only, for the compatibility check: directly if
     * it is paired with this phone, otherwise once its advertisement is heard. The only frame the run writes
     * is the checks' identity read: commands are refused ([Refusal.NOT_READY]), "keep this speed" never acts
     * on it, and it never reconnects (a failed attempt or a lost link ends it). Returns the number of the new
     * run ([status]).
     */
    public fun check(address: BikeAddress): Int = launch(address, seen = null, keepTrying = false, readOnly = true)

    /** Stops the session, like [stop], only if [run] is still the current run. */
    public fun stop(run: Int) {
        synchronized(lock) { if (run != generation) return }
        stop()
    }

    /**
     * Sets the bike's assistance limit to [limit]: one command, sent only if [WriteGate] allows it, and
     * never repeated. Returns once the bike echoed it, the time for the echo ran out, or the write failed.
     */
    public suspend fun setSpeedLimit(limit: SpeedLimit): CommandOutcome = send(BikeCommand.SetSpeedLimit(limit))

    /** Switches the bike's lights [on] or off, like [setSpeedLimit]. */
    public suspend fun setLights(on: Boolean): CommandOutcome = send(BikeCommand.SetLights(on))

    /**
     * Sets the bike's assist level to [level], like [setLights]: one command, never repeated, refused
     * ([Refusal.NOT_SUPPORTED]) unless the connected bike's profile offers that level. Confirmed only when the
     * bike echoes it in time and a later status of the same connection shows the level.
     */
    public suspend fun setAssistLevel(level: AssistLevel): CommandOutcome = send(BikeCommand.SetAssistLevel(level))

    /**
     * Locks the bike ([locked]), which switches it off, or unlocks it, which switches it on: one command, never
     * repeated, refused ([Refusal.NOT_SUPPORTED]) unless the connected bike's profile offers it. A lock needs the
     * bike reported on and standing still, an unlock needs it reported off (and, like going back to 25 km/h, an
     * earlier acceptance of the risks is enough for it). Confirmed only when the bike answers with the lock state
     * in time and a status of the same connection then shows it off or on.
     */
    public suspend fun setLocked(locked: Boolean): CommandOutcome = send(BikeCommand.SetLocked(locked))

    private suspend fun send(command: BikeCommand): CommandOutcome {
        check(command !is BikeCommand.ReadIdentity) { "the identity is read by the checks only" }
        // A read-only run (the compatibility check) sends nothing, and the rider's choice doesn't count.
        if (synchronized(lock) { readOnly }) return refused(Refusal.NOT_READY)
        // The rider's latest choice counts for "keep this speed" (or assist level) even if this command isn't sent.
        if (command is BikeCommand.SetSpeedLimit) riderChose(command.limit)
        if (command is BikeCommand.SetAssistLevel) riderChoseAssist(command.level)
        // One command at a time, whichever caller: a second one is refused, never queued.
        if (!sending.compareAndSet(false, true)) return refused(Refusal.BUSY)
        try {
            val connection = synchronized(lock) { ready }
            if (connection == null) {
                val refusal =
                    WriteGate.refusal(
                        needsCaveat = needsCaveat(command),
                        backToDefault = backToDefault(command),
                        accepted = consent.riskAccepted(),
                        everAccepted = consent.riskEverAccepted(),
                        verification = null,
                        supported = true,
                        acknowledged = false,
                        everAcknowledged = false,
                        caveatSeen = false,
                        bikeOn = false,
                        turnsOn = turnsOn(command),
                        bikeOff = false,
                        turnsOff = turnsOff(command),
                        standingStill = false,
                    )
                return refused(checkNotNull(refusal))
            }
            return connection.send(command, reapply = null)
        } finally {
            sending.set(false)
        }
    }

    /**
     * The rider sent or tried [limit] in the current run: the kept speed becomes [limit] if it is kept for
     * this run's bike, and the ready connection gets no re-apply.
     */
    private fun riderChose(limit: SpeedLimit) {
        synchronized(lock) {
            kept?.let { (bike, _) -> if (bike == runBike) kept = bike to limit }
            ready
        }?.riderChose()
    }

    /**
     * The rider sent or tried [level] in the current run: the kept assist level becomes [level] if one is kept
     * for this run's bike, and the ready connection gets no assist re-apply.
     */
    private fun riderChoseAssist(level: AssistLevel) {
        synchronized(lock) {
            keptAssist?.let { (bike, _) -> if (bike == runBike) keptAssist = bike to level }
            ready
        }?.riderChoseAssist()
    }

    /**
     * How a "keep this speed" (or assist level) attempt ended: not sent, because another command was being sent or the connection
     * is gone, or sent, with the command's outcome.
     */
    private sealed interface ReapplyResult {
        data object Busy : ReapplyResult

        data object Gone : ReapplyResult

        data class Sent(
            val outcome: CommandOutcome,
        ) : ReapplyResult
    }

    /**
     * "Keep this speed" (or assist level) sends [command] by itself on [connection], for [reason]: like the
     * rider's command, through the same gate, but only while that connection is the current one and nothing
     * else is being sent. Logs the attempt under [key] once it has the sending slot.
     */
    private suspend fun reapply(
        connection: Connection,
        gen: Int,
        command: BikeCommand,
        reason: Reapply,
        key: LogKey,
    ): ReapplyResult {
        if (!sending.compareAndSet(false, true)) return ReapplyResult.Busy
        try {
            if (synchronized(lock) { ready } !== connection || !current(gen)) return ReapplyResult.Gone
            log.state(key, reason)
            return ReapplyResult.Sent(connection.send(command, reason))
        } finally {
            sending.set(false)
        }
    }

    /** Publishes [state] unless a newer start or stop has replaced attempt [gen]. */
    private fun publishLimit(
        gen: Int,
        state: LimitState,
    ) {
        synchronized(lock) { if (gen == generation) mutableLimit.value = state }
    }

    /** Publishes [state] unless a newer start or stop has replaced attempt [gen]. */
    private fun publishAssist(
        gen: Int,
        state: AssistState,
    ) {
        synchronized(lock) { if (gen == generation) mutableAssist.value = state }
    }

    /** Replaces [expected] with [state], like [replaceLimit]. */
    private fun replaceAssist(
        gen: Int,
        expected: AssistState,
        state: AssistState,
    ) {
        synchronized(lock) { if (gen == generation && mutableAssist.value == expected) mutableAssist.value = state }
    }

    /**
     * Replaces [expected] with [state], only if nothing newer (a restart, the connection's end) was
     * published since, and attempt [gen] is still the current one.
     */
    private fun replaceLimit(
        gen: Int,
        expected: LimitState,
        state: LimitState,
    ) {
        synchronized(lock) { if (gen == generation && mutableLimit.value == expected) mutableLimit.value = state }
    }

    private fun refused(reason: Refusal): CommandOutcome {
        log.state(LogKey.REFUSED, reason)
        return CommandOutcome.Refused(reason)
    }

    /**
     * A speed change other than back to the bike's default. Going back to 25 must stay one tap away, and
     * leaves the bike as it starts anyway, so it needs no caveat.
     */
    private fun needsCaveat(command: BikeCommand): Boolean =
        command is BikeCommand.SetSpeedLimit && command.limit != SpeedLimit.BIKE_DEFAULT

    /** Back to the bike's default (25 km/h): allowed on an earlier acceptance and acknowledgement too. */
    private fun backToDefault(command: BikeCommand): Boolean =
        command is BikeCommand.SetSpeedLimit && command.limit == SpeedLimit.BIKE_DEFAULT

    /** An unlock, which switches the bike on: it needs the bike off; like going back to 25, no newer acceptance. */
    private fun turnsOn(command: BikeCommand): Boolean = command is BikeCommand.SetLocked && !command.locked

    /** A lock, which switches the bike off: it needs the bike on and standing still. */
    private fun turnsOff(command: BikeCommand): Boolean = command is BikeCommand.SetLocked && command.locked

    /** Disconnects and stays idle. */
    public fun stop() {
        val gen =
            synchronized(lock) {
                job?.cancel() // kept, so that the next start waits until its link is closed
                ready = null
                mutableRide.value = mutableRide.value.stale()
                mutableLimit.value = LimitState.Unknown
                mutableAssist.value = AssistState.Idle
                mutableServices.value = emptyList()
                ++generation
            }
        set(gen, SessionState.Idle)
    }

    private fun current(gen: Int): Boolean = synchronized(lock) { gen == generation }

    private fun launch(
        address: BikeAddress,
        seen: Advertisement?,
        keepTrying: Boolean,
        readOnly: Boolean = false,
    ): Int =
        synchronized(lock) {
            val previous = job
            previous?.cancel()
            ready = null
            val gen = ++generation
            runBike = address
            this.readOnly = readOnly
            mutableRide.value = RideData.NONE
            mutableLimit.value = LimitState.Unknown
            mutableAssist.value = AssistState.Idle
            mutableServices.value = emptyList()
            job =
                scope.launch {
                    previous?.join() // its link is closed before a new one opens
                    run(gen, address, seen, keepTrying, readOnly)
                }
            gen
        }

    private suspend fun run(
        gen: Int,
        address: BikeAddress,
        first: Advertisement?,
        keepTrying: Boolean,
        readOnly: Boolean,
    ) {
        var seen = first
        val run = RunState(retry = keepTrying, savedBike = keepTrying, readOnly = readOnly)
        while (true) {
            val result =
                try {
                    attempt(gen, address, seen, run)
                } catch (e: Exception) {
                    // A bug or a broken transport: end the attempt instead of hanging in a check. A
                    // cancellation counts only when this attempt was cancelled, not a transport's own.
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    if (current(gen)) log.error(LogKey.SESSION, e)
                    SessionState.Stopped(StopReason.LINK_LOST)
                }
            seen = null
            val final =
                result !is SessionState.Stopped ||
                    !run.retry ||
                    result.reason == StopReason.NOT_ACCEPTED ||
                    // No Bluetooth LE (only scanning and connecting report it so): it won't come back.
                    (result.reason == StopReason.BLUETOOTH_UNAVAILABLE && result.failure == LinkFailure.UNSUPPORTED)
            if (final) {
                set(gen, result)
                return
            }
            val failures = ++run.failures
            set(gen, SessionState.Retrying(result.reason, result.failure, failures))
            if (result.reason == StopReason.BLUETOOTH_UNAVAILABLE) transport.radio.first { it == RadioState.ON }
            // Always wait, even when the radio reads on: a refused scan must not turn into a busy loop.
            delay(config.retryDelay(failures))
        }
    }

    /** What one run of the session carries from connection to connection. */
    private class RunState(
        @Volatile var retry: Boolean,
        /** Started for a saved bike by its address, not as a check of a found bike: "keep this speed" may act. */
        val savedBike: Boolean,
        /** The compatibility check's run: one connection, nothing sent but the identity read. */
        val readOnly: Boolean,
        @Volatile var failures: Int = 0,
        @Volatile var powerOns: Int = 0,
    )

    /** Sets [state] unless a newer start or stop has replaced attempt [gen]. */
    private fun set(
        gen: Int,
        state: SessionState,
    ) {
        synchronized(lock) {
            if (gen != generation) return
            mutableState.value = state
            mutableStatus.value = SessionStatus(gen, state)
            when (state) {
                SessionState.Idle -> {
                    log.state(LogKey.SESSION, Phase.IDLE)
                }

                is SessionState.Checking -> {
                    log.state(LogKey.CHECK, state.step)
                }

                is SessionState.Ready -> {
                    log.state(LogKey.SESSION, Phase.READY)
                }

                is SessionState.Incompatible -> {
                    log.state(LogKey.INCOMPATIBLE, state.failure)
                }

                is SessionState.Stopped -> {
                    log.state(LogKey.STOPPED, state.reason)
                    state.failure?.let { log.state(LogKey.LINK_FAILURE, it) }
                }

                is SessionState.Retrying -> {
                    log.state(LogKey.RETRYING, state.reason)
                    state.failure?.let { log.state(LogKey.LINK_FAILURE, it) }
                    log.state(LogKey.ATTEMPT, state.attempt)
                }
            }
        }
    }

    /** Publishes [data] unless a newer start or stop has replaced attempt [gen]. */
    private fun publish(
        gen: Int,
        data: RideData,
    ) {
        synchronized(lock) { if (gen == generation) mutableRide.value = data }
    }

    private suspend fun attempt(
        gen: Int,
        address: BikeAddress,
        seen: Advertisement?,
        run: RunState,
    ): SessionState {
        val ad =
            when {
                seen != null && seen.heard -> {
                    seen
                }

                // A paired bike usually can't be heard (Android holds a link to it), and connecting to it
                // needs no advertisement: connect directly, at once.
                address in transport.pairedBikes() -> {
                    Advertisement.paired(address)
                }

                else -> {
                    val found =
                        try {
                            // The search time starts once a scan may start, and the screen says so only then.
                            transport.scanSlot()
                            set(gen, SessionState.Checking(CheckStep.SEARCHING))
                            withTimeoutOrNull(config.searchTimeout) { transport.scan(address).first() }
                        } catch (e: LinkException) {
                            return ended(e, StopReason.NOT_FOUND, radioCall = true)
                        }
                    found ?: return SessionState.Stopped(StopReason.NOT_FOUND)
                }
            }
        val rssi = ad.rssi
        if (rssi != null) {
            // Transports report only bikes named ENGWE.
            log.scanResult(Sanitiser.NAME, rssi)
        } else {
            log.state(LogKey.SEARCH, Search.PAIRED_DIRECT)
        }
        set(gen, SessionState.Checking(CheckStep.CONNECTING))
        var opened: BikeLink? = null
        try {
            try {
                withTimeoutOrNull(config.connectTimeout) { opened = transport.connect(address) }
            } catch (e: LinkException) {
                return ended(e, StopReason.CONNECT_FAILED, radioCall = true)
            }
            // A link that arrived as the timeout or a stop hit is still closed below.
            val link = opened ?: return SessionState.Stopped(StopReason.CONNECT_FAILED, LinkFailure.TIMEOUT)
            return Connection(gen, address, link, run).run()
        } finally {
            opened?.close()
        }
    }

    /**
     * The session state after a link failure: [reason], unless Bluetooth itself is the problem. From
     * scanning or connecting ([radioCall]), [LinkFailure.UNSUPPORTED] means the phone has no Bluetooth LE.
     */
    private fun ended(
        e: LinkException,
        reason: StopReason = StopReason.LINK_LOST,
        radioCall: Boolean = false,
    ): SessionState {
        e.status?.let { log.state(LogKey.GATT_STATUS, it) }
        val radio =
            e.failure == LinkFailure.BLUETOOTH_OFF ||
                e.failure == LinkFailure.NOT_PERMITTED ||
                (radioCall && e.failure == LinkFailure.UNSUPPORTED)
        return SessionState.Stopped(if (radio) StopReason.BLUETOOTH_UNAVAILABLE else reason, e.failure)
    }

    /** One connection: reads its notifications while the checks run, then until it ends. */
    private inner class Connection(
        private val gen: Int,
        private val address: BikeAddress,
        private val link: BikeLink,
        private val runState: RunState,
    ) {
        private val reassembler = FrameReassembler()
        private val statusSeen = CompletableDeferred<Unit>()
        private val identity = CompletableDeferred<String?>()
        private val disconnected = CompletableDeferred<LinkEvent.Disconnected>()

        @Volatile
        private var awaitingIdentity = false
        private var wrote = false

        @Volatile
        private var verification: Verification? = null

        @Volatile
        private var layout: StatusLayout? = null

        // How many assist levels the bike's profile offers, with a reading to confirm them; null: none.
        @Volatile
        private var assistLevels: Int? = null

        // The bike's profile offers locking and unlocking it, with verified power flags to confirm it.
        @Volatile
        private var lockControl = false

        // The command waiting for its echo, and what completes when it arrives.
        @Volatile
        private var awaitingEcho: Pair<OutgoingFrame, CompletableDeferred<Unit>>? = null

        // Set once the link ended or the run was cancelled: no command may use this connection any more.
        @Volatile
        private var over = false

        // A readable status arrived within the last [SessionConfig.commandStatusMaxAge].
        @Volatile
        private var recentStatus = false

        // True once the connection ended, for waits that must end with it.
        private val ended = MutableStateFlow(false)

        // The readable statuses counted when the ride started: the one kept from the checks, if any.
        @Volatile
        private var replayedStatuses = 0

        // Power-ons with verified flags seen on this connection, for "keep this speed".
        private val powerOns = MutableStateFlow(0)

        // The rider sent a speed on this connection since it was made or since the last power-on seen.
        @Volatile
        private var riderSent = false

        // The rider sent an assist level on this connection since it was made or since the last power-on seen.
        @Volatile
        private var riderSentAssist = false

        // As the ride started on this connection: the power-ons counted, and whether the status kept from the
        // checks reported the bike on.
        @Volatile
        private var powerOnsAtStart = 0

        @Volatile
        private var startedOn = false

        // Ride data: before the checks end only the latest status frame is kept; then a tracker decodes them.
        private val rideLock = Any()
        private var latestStatus: Frame? = null
        private var tracker: RideTracker? = null
        private var lastLogged: List<Any?>? = null
        private val readable = MutableStateFlow(0)

        suspend fun run(): SessionState =
            try {
                runChecked()
            } finally {
                // Also when the run is cancelled, which skips the link's end: nothing may wait on it.
                close()
            }

        private fun close() {
            over = true
            ended.value = true
            synchronized(lock) { if (ready === this@Connection) ready = null }
            awaitingEcho?.second?.completeExceptionally(LinkException(LinkFailure.CLOSED))
            // Whatever was sent on this connection, the next one starts knowing nothing.
            publishLimit(gen, LimitState.Unknown)
            publishAssist(gen, AssistState.Idle)
        }

        private suspend fun runChecked(): SessionState =
            coroutineScope {
                val reader = launch { read() }
                val result =
                    try {
                        checks()
                    } catch (e: LinkException) {
                        ended(e)
                    }
                if (result is SessionState.Ready) {
                    startRide(checkNotNull(result.classification.layout))
                    if (!runState.readOnly) runState.retry = true
                    verification = result.classification.verification
                    layout = result.classification.layout
                    // Only with a reading of the level: a change is confirmed by the status showing it.
                    assistLevels =
                        result.classification.profile
                            ?.assistLevels
                            ?.takeIf { layout?.spec(StatusField.ASSIST_LEVEL) != null }
                    lockControl =
                        result.classification.profile?.lockControl == true &&
                        listOf(StatusField.POWER_FLAG_A, StatusField.POWER_FLAG_B).all {
                            layout?.spec(it)?.verified == true
                        }
                    publishLimit(gen, LimitState.Unknown)
                    publishAssist(gen, AssistState.Idle)
                    set(gen, result)
                    synchronized(lock) { if (gen == generation) ready = this@Connection }
                    val keeper = launch { keepOnConnect() }
                    val watch = launch { readable.collectLatest { delay(config.staleAfter).also { markStale() } } }
                    // Only statuses after the one kept from the checks count as recent: it may be old.
                    val replayed = replayedStatuses
                    val recent =
                        launch {
                            readable.collectLatest { n ->
                                if (n > replayed) {
                                    recentStatus = true
                                    delay(config.commandStatusMaxAge)
                                    recentStatus = false
                                }
                            }
                        }
                    val stable =
                        launch {
                            delay(config.stableAfter)
                            runState.failures = 0
                        }
                    val end = disconnected.await()
                    close()
                    keeper.cancel()
                    watch.cancel()
                    recent.cancel()
                    stable.cancel()
                    reader.cancel() // in case the events never complete after the disconnect
                    markStale()
                    ended(LinkException(end.failure, end.status))
                } else {
                    reader.cancel()
                    result
                }
            }

        private fun startRide(layout: StatusLayout) {
            synchronized(rideLock) {
                val t = RideTracker(layout, runState.powerOns)
                tracker = t
                val waiting = latestStatus
                latestStatus = null
                if (waiting != null) track(t, waiting, live = false) else publish(gen, t.data)
                replayedStatuses = readable.value
                powerOnsAtStart = powerOns.value
                startedOn = WriteGate.reportsOn(t.data)
            }
        }

        /** Called with [rideLock] held. */
        private fun track(
            t: RideTracker,
            frame: Frame,
            /** False for the status kept from the checks, which may be older than it looks. */
            live: Boolean = true,
        ) {
            val before = t.data.powerOns
            val report = t.update(frame)
            if (t.data.powerOns > before) {
                poweredOn(t.data.powerVerified)
            } else if (report?.power?.let { it.value != PowerState.ON } == true) {
                // The flags don't read on (off, powering on, or values not seen before, a lock?): whatever was
                // set may not hold any more.
                synchronized(lock) {
                    val held = mutableLimit.value
                    val speedHeld =
                        held is LimitState.Sending || held is LimitState.Confirmed || held is LimitState.NotConfirmed
                    if (gen == generation && speedHeld) mutableLimit.value = LimitState.Unknown
                    // The bike starts again from its own level: what the app set says nothing any more.
                    if (gen == generation) mutableAssist.value = AssistState.Idle
                }
            }
            // A level the app set, now changed on the bike (its display's buttons): no longer the app's.
            if (report != null) {
                val level = report.assistLevel?.value
                synchronized(lock) {
                    val shown = mutableAssist.value
                    if (gen == generation && shown is AssistState.Confirmed && level != shown.level.level) {
                        mutableAssist.value = AssistState.Idle
                    }
                }
            }
            if (report != null) {
                val values = report.values()
                if (values != lastLogged) {
                    log.status(report)
                    lastLogged = values
                }
                // At once, not when a watcher gets to it; the watcher clears it once it is too old.
                if (live) recentStatus = true
                readable.value++
            }
            runState.powerOns = t.data.powerOns
            publish(gen, t.data)
        }

        /**
         * The status shows the bike switched on. With verified power flags the limit is the bike's own again
         * on the tested bike ([LimitState.Restarted]); with others the app can't tell. Either way the rider's
         * earlier choices on this connection no longer hold. Called with [rideLock] held.
         */
        private fun poweredOn(verified: Boolean) {
            riderSent = false
            riderSentAssist = false
            publishLimit(gen, if (verified) LimitState.Restarted else LimitState.Unknown)
            if (verified) powerOns.value++
        }

        /**
         * The rider sent or tried a speed on this connection: "keep this speed" leaves it alone until the next
         * power-on.
         */
        fun riderChose() {
            riderSent = true
        }

        /**
         * The rider sent or tried an assist level on this connection: "keep this assist level" leaves it alone
         * until the next power-on.
         */
        fun riderChoseAssist() {
            riderSentAssist = true
        }

        /**
         * "Keep this speed" and "keep this assist level" on this connection, for a run started for the kept
         * bike: once soon after it was made, if the bike reported on from the start and stays on; and, where
         * restarts are trusted, once after each power-on, once the bike is on again. Each time the speed first,
         * then the assist level (only while the bike reads 0 km/h).
         */
        private suspend fun keepOnConnect() {
            if (!runState.savedBike) return
            // As the ride started: a power-on seen since is a restart, not this connection's re-apply.
            var seen = powerOnsAtStart
            val startedOn = startedOn
            val first = withTimeoutOrNull(config.commandStatusMaxAge) { readable.first { recentStatus } }
            if (startedOn && first != null && bikeOnNow() && staysOn() && powerOns.value == seen) {
                reapplyBoth(Reapply.RECONNECT, seen)
            }
            if (!config.restartsTrusted) return
            while (true) {
                seen = powerOns.first { it > seen }
                // The second power flag comes back a little later; then the bike must stay on for a while.
                val on = withTimeoutOrNull(config.reapplyOnWait) { readable.first { bikeOnNow() } } != null
                if (on && staysOn() && powerOns.value == seen) reapplyBoth(Reapply.RESTART, seen)
            }
        }

        /**
         * The kept speed, then the kept assist level, for [reason]: one after the other (the speed's command has
         * ended, or wasn't sent, before the level's), the level only while the bike is still on and no power-on
         * came after the [seen] ones.
         */
        private suspend fun reapplyBoth(
            reason: Reapply,
            seen: Int,
        ) {
            reapplyKept(reason)
            if (bikeOnNow() && powerOns.value == seen) reapplyKeptAssist(reason)
        }

        private fun bikeOnNow(): Boolean {
            val ride = synchronized(rideLock) { tracker?.data }
            return recentStatus && ride != null && WriteGate.reportsOn(ride)
        }

        /** Whether the bike keeps reporting on for [SessionConfig.reapplySettle]. */
        private suspend fun staysOn(): Boolean =
            withTimeoutOrNull(config.reapplySettle) { readable.first { !bikeOnNow() } } == null && bikeOnNow()

        private suspend fun reapplyKept(reason: Reapply) {
            val (bike, limit) = kept ?: return
            if (bike != address) return
            // The rider already chose a speed on this connection (or since this power-on).
            if (riderSent) return skipped(LogKey.REAPPLY_SKIPPED, Skip.RIDER_CHOSE)
            reapplyOnce(
                BikeCommand.SetSpeedLimit(limit),
                reason,
                reapplyCooling,
                LogKey.REAPPLY,
                LogKey.REAPPLY_SKIPPED,
            )
        }

        /**
         * "Keep this assist level": the kept level once, for [reason], if the bike stands still (0 km/h) and
         * offers it.
         */
        private suspend fun reapplyKeptAssist(reason: Reapply) {
            val (bike, level) = keptAssist ?: return
            if (bike != address) return
            val skippedKey = LogKey.REAPPLY_ASSIST_SKIPPED
            // The rider already chose a level in the app on this connection (or since this power-on).
            if (riderSentAssist) return skipped(skippedKey, Skip.RIDER_CHOSE)
            // A bike whose profile doesn't offer the level: nothing to try (the gate would refuse it anyway).
            if (!supports(BikeCommand.SetAssistLevel(level))) return skipped(skippedKey, Skip.NOT_SUPPORTED)
            // Only while the bike reports standing still, by a verified speed: never a change of assistance while
            // riding, nor on a speed reading nobody has checked for this model.
            val speed = verifiedSpeedNow() ?: return skipped(skippedKey, Skip.NO_SPEED)
            if (speed != 0.0) return skipped(skippedKey, Skip.MOVING)
            reapplyOnce(
                BikeCommand.SetAssistLevel(level),
                reason,
                assistCooling,
                LogKey.REAPPLY_ASSIST,
                skippedKey,
            )
        }

        /**
         * The speed in this connection's latest readable status, if it is recent and its meaning is verified for
         * this bike; null otherwise.
         */
        private fun verifiedSpeedNow(): Double? =
            synchronized(rideLock) {
                tracker
                    ?.data
                    ?.takeIf { !it.stale && !it.unreadable }
                    ?.status
                    ?.speedKmh
                    ?.takeIf { it.verified }
                    ?.value
            }?.takeIf { recentStatus }

        /**
         * Sends [command] by itself once, for [reason], unless one was written through [cooling] less than
         * [SessionConfig.reapplyMinInterval] ago; never retried.
         */
        private suspend fun reapplyOnce(
            command: BikeCommand,
            reason: Reapply,
            cooling: AtomicBoolean,
            key: LogKey,
            skippedKey: LogKey,
        ) {
            if (!cooling.compareAndSet(false, true)) return skipped(skippedKey, Skip.RECENT)
            // Counts as written unless it surely wasn't: a cancelled re-apply may have reached the bike.
            var written = true
            try {
                // Like the rider's command, through the same gate, and never retried.
                val result = reapply(this@Connection, gen, command, reason, key)
                if (result == ReapplyResult.Busy) skipped(skippedKey, Skip.BUSY)
                written = result is ReapplyResult.Sent && result.outcome !is CommandOutcome.Refused
            } finally {
                // Only a re-apply that was written waits a minute before the next one.
                if (written) {
                    scope.launch {
                        delay(config.reapplyMinInterval)
                        cooling.set(false)
                    }
                } else {
                    cooling.set(false)
                }
            }
        }

        private fun skipped(
            key: LogKey,
            why: Skip,
        ) {
            if (current(gen)) log.state(key, why)
        }

        private fun markStale() {
            synchronized(rideLock) {
                tracker?.let {
                    it.stale()
                    publish(gen, it.data)
                }
            }
        }

        private suspend fun read() {
            try {
                link.events.collect { event ->
                    when (event) {
                        is LinkEvent.Notification -> {
                            reassembler.feed(event.value).forEach(::handle)
                        }

                        is LinkEvent.Disconnected -> {
                            reassembler.flush().forEach(::handle)
                            end(event)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                if (current(gen)) log.error(LogKey.LINK_FAILURE, e)
            }
            end(LinkEvent.Disconnected(LinkFailure.DISCONNECTED)) // the stream ended without saying why
        }

        private fun end(event: LinkEvent.Disconnected) {
            val failure = LinkException(event.failure, event.status)
            // First, so that a command waiting for its echo sees the link as gone.
            over = true
            ended.value = true
            disconnected.complete(event)
            statusSeen.completeExceptionally(failure)
            identity.completeExceptionally(failure)
            awaitingEcho?.second?.completeExceptionally(failure)
        }

        private fun handle(event: StreamEvent) {
            if (!current(gen)) return // a replaced attempt doesn't log into the new one
            log.incoming(event)
            val frame = (event as? StreamEvent.Decoded)?.frame ?: return
            if (!frame.crcOk) return
            // A late echo of the same command sent earlier would count too: harmless, its payload is the same.
            awaitingEcho?.let { (sent, echoed) -> if (sent.isEchoedBy(frame)) echoed.complete(Unit) }
            when {
                StatusDecoder.isStatus(frame) -> {
                    statusSeen.complete(Unit)
                    synchronized(rideLock) {
                        val t = tracker
                        if (t == null) latestStatus = frame else track(t, frame)
                    }
                }

                IdentityParser.isIdentityReply(frame) && awaitingIdentity -> {
                    // Only the model is kept; the frame, which also carries the serial, is dropped here.
                    awaitingIdentity = false
                    log.identity(frame)
                    identity.complete(IdentityParser.modelOf(frame))
                }
            }
        }

        private suspend fun checks(): SessionState {
            set(gen, SessionState.Checking(CheckStep.SERVICES))
            val discovered = timed { link.discoverServices() }
            synchronized(lock) { if (gen == generation) mutableServices.value = discovered }
            serviceFailure(discovered)?.let { return incompatible(it) }

            val mtu =
                try {
                    timed { link.requestMtu(BikeGatt.MAX_MTU) }
                } catch (e: LinkException) {
                    if (e.failure != LinkFailure.GATT_ERROR) throw e
                    linkError(LogKey.MTU, e)
                    BikeGatt.DEFAULT_MTU
                }
            log.state(LogKey.MTU, mtu)

            set(gen, SessionState.Checking(CheckStep.NOTIFICATIONS))
            try {
                timed { link.enableNotifications() }
            } catch (e: LinkException) {
                when (e.failure) {
                    LinkFailure.UNSUPPORTED -> return incompatible(CheckFailure.NO_NOTIFY)

                    // The status check decides: the bike may send its frames anyway.
                    LinkFailure.GATT_ERROR -> linkError(CheckStep.NOTIFICATIONS, e)

                    else -> throw e
                }
            }

            set(gen, SessionState.Checking(CheckStep.STATUS))
            withTimeoutOrNull(config.statusTimeout) { statusSeen.await() }
                ?: return incompatible(CheckFailure.NO_STATUS)

            set(gen, SessionState.Checking(CheckStep.IDENTITY))
            // For the saved bike, an earlier acceptance is enough to connect: going back to 25 km/h stays
            // possible. Checking any other bike needs the current text.
            val accepted = consent.riskAccepted() || (runState.savedBike && consent.riskEverAccepted())
            if (!accepted) return SessionState.Stopped(StopReason.NOT_ACCEPTED)
            val model = readIdentity() ?: return incompatible(CheckFailure.NO_IDENTITY)
            val classification = BikeClassifier.classify(ProtocolChecks(true, true, model), profiles)
            log.classification(classification)
            if (classification.verification == Verification.INCOMPATIBLE) return incompatible(CheckFailure.NO_IDENTITY)
            return SessionState.Ready(classification)
        }

        /** The checks' only write. Returns the model, or null when the bike gave no usable reply. */
        private suspend fun readIdentity(): String? {
            check(!wrote) { "one write per connection" }
            wrote = true
            awaitingIdentity = true
            val frame = BikeCommand.ReadIdentity.toFrame()
            // A stopped or replaced attempt writes nothing, even if the link doesn't check.
            currentCoroutineContext().ensureActive()
            check(current(gen)) { "replaced attempt" }
            // No read on a link whose events already ended: its reply couldn't arrive.
            if (disconnected.isCompleted) throw LinkException(LinkFailure.DISCONNECTED)
            log.outgoing(frame)
            try {
                timed { link.write(frame) }
            } catch (e: LinkException) {
                if (e.failure != LinkFailure.GATT_ERROR && e.failure != LinkFailure.UNSUPPORTED) throw e
                linkError(CheckStep.IDENTITY, e)
                return null
            }
            return withTimeoutOrNull(config.identityTimeout) { identity.await() }
        }

        /**
         * Sends one speed, light, assist-level or lock [command] on this connection, if [WriteGate] allows it at the
         * moment of writing. The caller holds the session's one sending slot.
         */
        suspend fun send(
            command: BikeCommand,
            reapply: Reapply?,
        ): CommandOutcome {
            // The compatibility check's connection writes nothing but the identity read, whoever asks.
            if (runState.readOnly) return refused(Refusal.NOT_READY)
            val frame = command.toFrame()
            val echoed = CompletableDeferred<Unit>()
            awaitingEcho = frame to echoed
            var outcome: CommandOutcome = CommandOutcome.Failed
            var written = false
            val speed = (command as? BikeCommand.SetSpeedLimit)?.limit
            val level = (command as? BikeCommand.SetAssistLevel)?.level
            var sendingState: LimitState = LimitState.Unknown
            var sendingAssist: AssistState = AssistState.Idle
            try {
                outcome =
                    coroutineScope {
                        // The echo's time runs from here, before the write: it may even arrive before the
                        // write's response.
                        val echo =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    withTimeoutOrNull(config.echoTimeout) { echoed.await() } != null
                                } catch (e: LinkException) {
                                    false
                                }
                            }
                        // The gate is the last thing before the write, with nothing suspended in between; the
                        // link's operation queue is idle after the checks, so the write starts at once. A caller
                        // that is already cancelled writes nothing.
                        currentCoroutineContext().ensureActive()
                        gate(command)?.let { refusal ->
                            echo.cancel()
                            return@coroutineScope CommandOutcome.Refused(refusal)
                        }
                        written = true
                        // Statuses from here on count for a lock or an unlock: the gate saw the bike in the other
                        // state, and the reference bike sent its new state at once, even before its answer.
                        val beforeWrite = readable.value
                        if (speed != null) {
                            sendingState = LimitState.Sending(speed, reapply)
                            publishLimit(gen, sendingState)
                        }
                        if (level != null) {
                            sendingAssist = AssistState.Sending(level, reapply)
                            publishAssist(gen, sendingAssist)
                        }
                        log.outgoing(frame)
                        try {
                            timed { link.write(frame) }
                        } catch (e: LinkException) {
                            echo.cancel()
                            linkError(LogKey.COMMAND, e)
                            return@coroutineScope CommandOutcome.Failed
                        }
                        val seen = readable.value
                        when {
                            !echo.await() -> {
                                if (live()) CommandOutcome.NotConfirmed else CommandOutcome.Failed
                            }

                            command is BikeCommand.SetLights && !lightsFollowed(command.on, seen) -> {
                                if (live()) CommandOutcome.NotConfirmed else CommandOutcome.Failed
                            }

                            command is BikeCommand.SetAssistLevel && !assistFollowed(command.level, seen) -> {
                                if (live()) CommandOutcome.NotConfirmed else CommandOutcome.Failed
                            }

                            command is BikeCommand.SetLocked && !powerFollowed(command.locked, beforeWrite) -> {
                                if (live()) CommandOutcome.NotConfirmed else CommandOutcome.Failed
                            }

                            else -> {
                                CommandOutcome.Confirmed
                            }
                        }
                    }
            } catch (e: Exception) {
                // The caller's own cancellation goes on; any other is a failure of this command.
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                if (current(gen)) log.error(LogKey.COMMAND, e)
                outcome = CommandOutcome.Failed
            } finally {
                awaitingEcho = null
                if (speed != null && written) {
                    val after =
                        when (outcome) {
                            CommandOutcome.Confirmed -> LimitState.Confirmed(speed, reapply)
                            CommandOutcome.NotConfirmed -> LimitState.NotConfirmed(speed, reapply)
                            else -> LimitState.Unknown
                        }
                    // Not over what came since (a restart, the connection's end).
                    replaceLimit(gen, sendingState, after)
                }
                if (level != null && written) {
                    val after =
                        when (outcome) {
                            CommandOutcome.Confirmed -> AssistState.Confirmed(level, reapply)
                            CommandOutcome.NotConfirmed -> AssistState.NotConfirmed(level, reapply)
                            else -> AssistState.Idle
                        }
                    // Not over what came since (the bike switched off, the connection's end).
                    replaceAssist(gen, sendingAssist, after)
                }
                // A replaced run doesn't log into the new one. A cancelled command is recorded as failed.
                // An outcome follows its tx record only; a caller cancelled before the gate leaves neither.
                if (current(gen)) {
                    when (val o = outcome) {
                        is CommandOutcome.Refused -> log.state(LogKey.REFUSED, o.reason)
                        else -> if (written) log.state(LogKey.COMMAND, o.token())
                    }
                }
            }
            return outcome
        }

        /** This connection is the session's current one, and its link hasn't ended. */
        private fun live(): Boolean = !over && current(gen) && !disconnected.isCompleted

        private fun gate(command: BikeCommand): Refusal? {
            val known = verification.takeIf { live() }
            val ride = synchronized(rideLock) { tracker?.data }
            return WriteGate.refusal(
                needsCaveat = needsCaveat(command),
                backToDefault = backToDefault(command),
                accepted = consent.riskAccepted(),
                everAccepted = consent.riskEverAccepted(),
                verification = known,
                supported = supports(command),
                acknowledged = known == Verification.UNVERIFIED && consent.unverifiedAcknowledged(address),
                everAcknowledged = known == Verification.UNVERIFIED && consent.unverifiedEverAcknowledged(address),
                caveatSeen = consent.firmwareCaveatSeen(),
                bikeOn = recentStatus && ride != null && WriteGate.reportsOn(ride),
                turnsOn = turnsOn(command),
                bikeOff = ride != null && WriteGate.reportsOff(ride),
                turnsOff = turnsOff(command),
                standingStill = verifiedSpeedNow() == 0.0,
            )
        }

        /**
         * Whether this connection's bike offers [command]: an assist level only up to its profile's number, a lock
         * or an unlock only where its profile offers them.
         */
        private fun supports(command: BikeCommand): Boolean =
            when (command) {
                is BikeCommand.SetAssistLevel -> assistLevels?.let { command.level.level <= it } == true
                is BikeCommand.SetLocked -> lockControl
                else -> true
            }

        /**
         * Whether a status that arrived on this connection after the write's response (after [seen] readable
         * ones), reporting the bike on, showed the assist [level] in time, before the connection ended. Whatever
         * the bike's verification: without a status that shows the level, the change isn't confirmed.
         */
        private suspend fun assistFollowed(
            level: AssistLevel,
            seen: Int,
        ): Boolean {
            val followed =
                withTimeoutOrNull(config.assistStatusTimeout) {
                    combine(readable, ended) { n, gone -> gone || (n > seen && assistShown() == level.level) }
                        .first { it }
                }
            return followed != null && !ended.value
        }

        /**
         * The assist level in this connection's latest readable status, if recent enough to show and reporting
         * the bike on (a bike switching off or locked shows 0, which says nothing about the command).
         */
        private fun assistShown(): Int? =
            synchronized(rideLock) {
                tracker
                    ?.data
                    ?.takeIf { WriteGate.reportsOn(it) }
                    ?.status
                    ?.assistLevel
                    ?.value
            }

        /**
         * Whether a status that arrived on this connection after [seen] readable ones (from just before the write)
         * showed the bike off ([locked]) or on by its verified power flags, in time, before the connection ended.
         * An unlock may pass through "switching on" (one flag back) first; only both flags on count.
         */
        private suspend fun powerFollowed(
            locked: Boolean,
            seen: Int,
        ): Boolean {
            val wanted = if (locked) PowerState.OFF else PowerState.ON
            val followed =
                withTimeoutOrNull(config.powerStatusTimeout) {
                    combine(readable, ended) { n, gone -> gone || (n > seen && powerShown() == wanted) }.first { it }
                }
            return followed != null && !ended.value
        }

        /** The power state in this connection's latest readable status, if up to date and its flags verified. */
        private fun powerShown(): PowerState? =
            synchronized(rideLock) {
                tracker
                    ?.data
                    ?.takeIf { !it.stale && !it.unreadable && it.status != null && it.powerVerified }
                    ?.power
            }

        /**
         * On a bike whose lights reading is verified, whether a status that arrived on this connection after
         * the write's response (after [seen] readable ones) reported the lights [on] (or off) in time, before
         * the connection ended. Without a verified reading the echo counts.
         */
        private suspend fun lightsFollowed(
            on: Boolean,
            seen: Int,
        ): Boolean {
            val verifiedReading = layout?.spec(StatusField.LIGHTS)?.verified == true
            if (verification != Verification.VERIFIED || !verifiedReading) return true
            val wanted = if (on) Lights.ON else Lights.OFF
            val followed =
                withTimeoutOrNull(config.lightsStatusTimeout) {
                    combine(readable, ended) { n, gone -> gone || (n > seen && lights() == wanted) }.first { it }
                }
            return followed != null && !ended.value
        }

        /** The lights in this connection's latest readable status, if recent enough to show. */
        private fun lights(): Lights? =
            synchronized(rideLock) {
                tracker
                    ?.data
                    ?.takeIf { !it.stale && !it.unreadable }
                    ?.status
                    ?.lights
                    ?.value
            }

        private fun serviceFailure(services: List<GattService>): CheckFailure? {
            val service = services.firstOrNull { it.uuid == BikeGatt.SERVICE } ?: return CheckFailure.NO_SERVICE
            val notify = service.characteristic(BikeGatt.NOTIFY)
            if (notify == null || CharacteristicProperty.NOTIFY !in notify.properties) return CheckFailure.NO_NOTIFY
            val write = service.characteristic(BikeGatt.WRITE)
            if (write == null || CharacteristicProperty.WRITE !in write.properties) {
                return CheckFailure.NO_ACKNOWLEDGED_WRITE
            }
            return null
        }

        private fun incompatible(failure: CheckFailure): SessionState = SessionState.Incompatible(failure)

        private fun linkError(
            where: Enum<*>,
            e: LinkException,
        ) {
            log.error(where, e.failure)
            e.status?.let { log.state(LogKey.GATT_STATUS, it) }
        }

        private suspend fun <T : Any> timed(block: suspend () -> T): T =
            withTimeoutOrNull(config.operationTimeout) { block() } ?: throw LinkException(LinkFailure.TIMEOUT)
    }

    /** The decoded values, for telling whether they changed. */
    private fun StatusReport.values(): List<Any?> =
        listOf(
            speedKmh,
            maxSpeedKmh,
            odometerKm,
            assistLevel,
            batteryPercent,
            lights,
            power,
            temperatureRaw,
            batteryHealthRaw,
            voltageV,
            rideTimeMinutes,
        )

    /** Records a compatibility check's result in the session log. */
    internal fun logCheck(record: CheckRecord) {
        log.compatibilityCheck(record)
    }

    /** Names of session log records. */
    private enum class LogKey {
        SESSION,
        COMMAND,
        REFUSED,
        REAPPLY,
        REAPPLY_SKIPPED,
        REAPPLY_ASSIST,
        REAPPLY_ASSIST_SKIPPED,
        SEARCH,
        CHECK,
        MTU,
        INCOMPATIBLE,
        STOPPED,
        RETRYING,
        ATTEMPT,
        LINK_FAILURE,
        GATT_STATUS,
    }

    private enum class Phase { IDLE, READY }

    /** Why "keep this speed" (or assist level) sent nothing when it might have. */
    private enum class Skip { RIDER_CHOSE, RECENT, BUSY, MOVING, NO_SPEED, NOT_SUPPORTED }

    /** How a command ended, as the log records it. */
    private enum class Outcome { CONFIRMED, NOT_CONFIRMED, FAILED }

    private fun CommandOutcome.token(): Outcome =
        when (this) {
            CommandOutcome.Confirmed -> Outcome.CONFIRMED
            CommandOutcome.NotConfirmed -> Outcome.NOT_CONFIRMED
            CommandOutcome.Failed, is CommandOutcome.Refused -> Outcome.FAILED
        }

    /** Connected to a bike paired with the phone directly, without searching. */
    private enum class Search { PAIRED_DIRECT, }
}
