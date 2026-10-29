package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.BikeCommand
import io.github.ridelabz.engwespeedtool.protocol.Crc16Modbus
import io.github.ridelabz.engwespeedtool.protocol.FieldSpec
import io.github.ridelabz.engwespeedtool.protocol.OutgoingFrame
import io.github.ridelabz.engwespeedtool.protocol.ProfileRegistry
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit
import io.github.ridelabz.engwespeedtool.protocol.StatusField
import io.github.ridelabz.engwespeedtool.protocol.StatusLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Values a [FakeBike] reports, in the display's units. */
public data class FakeRide(
    val speedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val odometerKm: Double = 412.7,
    val assistLevel: Int = 2,
    val batteryPercent: Int = 76,
    val lightsOn: Boolean = false,
    val temperature: Int = 24,
    val batteryHealth: Int = 96,
    val voltage: Double = 41.8,
    /** The bike's total ride time, in minutes. */
    val rideTimeMinutes: Int = 742,
)

/** A [FakeBike]'s display: on, off, or switching on (one power flag is back, the other follows). */
public enum class FakePower { ON, OFF, POWERING_ON }

/** How long a [FakeBike] takes, and how often it reports. */
public data class FakeTiming(
    val connect: Duration = 300.milliseconds,
    val operation: Duration = 20.milliseconds,
    val reply: Duration = 80.milliseconds,
    val statusOn: Duration = 1.seconds,
    val statusOff: Duration = 5.seconds,
    val poweringOn: Duration = 2.seconds,
    /** How long an operation without a response stays pending before it fails, as on Android. */
    val operationTimeout: Duration = 10.seconds,
)

/**
 * A simulated bike for demo mode and tests. It behaves like the project's reference bike: a status frame
 * about once a second (every 5 seconds with the display off), echoes of speed, light and assist-level
 * commands (an assist level shows in the status one frame late, as on the reference bike, where the status
 * right after the echo still showed the old level; the bike starts at 0), an
 * identity reply with a made-up serial, display off and on, and notifications cut to 20 bytes before the
 * MTU exchange. A lock or an unlock is answered with the lock state and switches it off or on at once (both
 * power flags together, as on the reference bike), with a status at once: before the answer after a lock,
 * right after it after an unlock, as seen. It records every write in [writes]. Nothing here uses Bluetooth.
 *
 * It holds one connection at a time and doesn't advertise while connected. Its status frames always use
 * the reference bike's layout. It accepts and echoes commands with the display off too; whether the real
 * bike does is not known, so tests shouldn't rely on it. As on the reference bike, an unlock starts it again
 * from 25 km/h, like a power-on, and also switches on a bike switched off at its display. It runs in [scope]
 * until the scope ends.
 */
public class FakeBike(
    internal val scope: CoroutineScope,
    public val address: BikeAddress = DEMO_ADDRESS,
    /** The model identity it reports, at most 27 characters. */
    public val model: String = ProfileRegistry.REFERENCE_MODEL,
    /** The made-up serial in its identity reply, at most 19 characters. */
    public val serial: String = DEMO_SERIAL,
    /** The services it reports. */
    public val services: List<GattService> = REFERENCE_SERVICES,
    /** Signal strength in dBm. */
    public val rssi: Int = -60,
    /**
     * Moves the speed, distance and ride time values as if someone were riding (for demo mode): standing still
     * for 20 status frames (about 20 s), then riding for 40, again and again, from standing still after every
     * power-on.
     */
    private val riding: Boolean = false,
    private val timing: FakeTiming = FakeTiming(),
) {
    init {
        require(model.length < MODEL_FIELD && model.all { it.code in 0x21..0x7E }) {
            "model: at most 27 printable ASCII characters"
        }
        require(serial.length <= SERIAL_FIELD && serial.all { it.code in 0x21..0x7E }) {
            "serial: at most 19 printable ASCII characters"
        }
    }

    private val lock = Any()
    private val layout: StatusLayout =
        checkNotNull(ProfileRegistry.bundled().find(ProfileRegistry.REFERENCE_MODEL)).statusLayout
    private var link: FakeLink? = null
    private val recorded = mutableListOf<FakeGattWrite>()
    private var currentRide = FakeRide()
    private var currentPower = FakePower.ON
    private var powerChanges = 0
    private var limit: SpeedLimit = SpeedLimit.BIKE_DEFAULT
    private var lockedNow = false

    // An assist level accepted but not shown yet, and how many status frames still show the old one.
    private var pendingAssist: Pair<Int, Int>? = null
    private var ticks = 0
    private var operationsInFlight = 0
    private var mostOperationsInFlight = 0

    /** Every frame written to this bike, in order. */
    public val writes: List<OutgoingFrame> get() = synchronized(lock) { recorded.mapNotNull { it.frame } }

    /** Every GATT write, client configuration included, in order. For tests of the link. */
    internal val gattWrites: List<FakeGattWrite> get() = synchronized(lock) { recorded.toList() }

    /** The most GATT operations that were ever in flight at once. For tests of the link. */
    internal val maxConcurrentOperations: Int get() = synchronized(lock) { mostOperationsInFlight }

    /** The next write is received but rejected with this Bluetooth status. For tests of the link. */
    @Volatile
    internal var rejectNextWrite: Int? = null

    /** The next write is received but never answered. For tests of the link. */
    @Volatile
    internal var withholdNextWriteResponse: Boolean = false

    /** Whether it sends status frames. For tests of the session. */
    @Volatile
    internal var sendsStatus: Boolean = true

    /** Status payloads cut or padded to this length (null: the reference length). For tests of the session. */
    @Volatile
    internal var statusPayloadLength: Int? = null

    /** Whether it advertises (a bike held by another phone may not). For tests of the session. */
    @Volatile
    internal var advertises: Boolean = true

    /**
     * Paired with the phone: Android then keeps a link to it while Bluetooth is on, so it isn't heard
     * advertising, but it can still be connected to directly.
     */
    @Volatile
    public var pairedWithPhone: Boolean = false

    /** False while it is switched off or out of range: it is neither heard nor reachable. */
    @Volatile
    public var inRange: Boolean = true

    /** Whether it refuses connections although it advertises. For tests of the session. */
    @Volatile
    internal var refusesConnections: Boolean = false

    private val opened = mutableListOf<FakeLink>()

    /** Frames written on each link so far, in the order the links were opened. For tests of the session. */
    internal val writesPerLink: List<Int> get() = synchronized(lock) { opened.map { it.frames } }

    /** Links opened so far. For tests of the session. */
    internal val links: Int get() = synchronized(lock) { opened.size }

    /** The commands of [writes]. */
    public val commands: List<BikeCommand> get() = writes.map { it.command }

    /** The values it reports; setting them takes effect with the next status frame. */
    public var ride: FakeRide
        get() = synchronized(lock) { currentRide }
        set(value) = synchronized(lock) { currentRide = value }

    public val power: FakePower get() = synchronized(lock) { currentPower }

    /** The last speed limit it accepted since it was switched on. */
    public val speedLimit: SpeedLimit get() = synchronized(lock) { limit }

    /** True while a link is open. */
    public val connected: Boolean get() = synchronized(lock) { link != null }

    /** Whether it answers the identity read. For tests of the session. */
    @Volatile
    internal var answersIdentity: Boolean = true

    /** Whether it echoes speed, light and assist-level commands, and answers locks. For tests of the session. */
    @Volatile
    internal var echoes: Boolean = true

    /** Whether it acts on speed, light, assist-level and lock commands (it still answers). For tests of the session. */
    @Volatile
    internal var acts: Boolean = true

    /** How long it takes to echo a speed or light command (the identity reply takes the timing's reply). */
    @Volatile
    internal var echoDelay: Duration? = null

    // Counts unlocks: each sends its status at once and starts the rhythm of status frames again from there.
    private val rhythm = MutableStateFlow(0)

    init {
        scope.launch {
            while (isActive) {
                val since = rhythm.value
                val period = if (power == FakePower.OFF) timing.statusOff else timing.statusOn
                if (withTimeoutOrNull(period) { rhythm.first { it != since } } == null) tick()
            }
        }
    }

    /** Switches the display off: both power flags drop, status frames slow down. */
    public fun switchOff() {
        synchronized(lock) {
            currentPower = FakePower.OFF
            powerChanges++
        }
    }

    /** True from a lock until an unlock. */
    public val locked: Boolean get() = synchronized(lock) { lockedNow }

    /** A lock: switched off like the display. Called with [lock] held. */
    private fun lockNow() {
        lockedNow = true
        currentPower = FakePower.OFF
        currentRide = currentRide.copy(speedKmh = 0.0, assistLevel = 0)
        pendingAssist = null
        powerChanges++
    }

    /**
     * An unlock: on again at once, both power flags together, starting again like a power-on (max speed,
     * lights, the assist level and the speed limit). Called with [lock] held.
     */
    private fun unlockNow() {
        lockedNow = false
        if (currentPower == FakePower.ON) return
        currentPower = FakePower.ON
        currentRide = currentRide.copy(speedKmh = 0.0, maxSpeedKmh = 0.0, lightsOn = false, assistLevel = 0)
        pendingAssist = null
        limit = SpeedLimit.BIKE_DEFAULT
        ticks = 0
        powerChanges++
        // On again: a status about every second from now on, as after the reference bike's unlock.
        rhythm.value++
    }

    /**
     * Switches the display on. One power flag returns at once, the other about 2 seconds later; max speed,
     * lights, the assist level (0) and the speed limit start again from the bike's defaults.
     */
    public fun switchOn() {
        val change =
            synchronized(lock) {
                if (currentPower != FakePower.OFF) return
                currentPower = FakePower.POWERING_ON
                currentRide = currentRide.copy(speedKmh = 0.0, maxSpeedKmh = 0.0, lightsOn = false, assistLevel = 0)
                pendingAssist = null
                limit = SpeedLimit.BIKE_DEFAULT
                ticks = 0
                ++powerChanges
            }
        scope.launch {
            delay(timing.poweringOn)
            synchronized(lock) { if (powerChanges == change) currentPower = FakePower.ON }
        }
    }

    /** Reports [power] from the next status on, as is, without the usual sequence. For tests of the session. */
    internal fun showPower(power: FakePower) {
        synchronized(lock) {
            currentPower = power
            powerChanges++
        }
    }

    /** The connection drops, as when the phone goes out of range. */
    public fun dropLink() {
        endLink(LinkFailure.DISCONNECTED, STATUS_CONNECTION_TIMEOUT)
    }

    internal fun endLink(
        failure: LinkFailure,
        status: Int? = null,
    ) {
        synchronized(lock) { link }?.end(failure, status)
    }

    /** Sends [value] as one notification, as the bike would. For tests of the link. */
    internal fun notifyRaw(value: ByteArray) {
        synchronized(lock) { link }?.deliver(value)
    }

    internal fun advertisement(): Advertisement? =
        synchronized(lock) {
            if (link != null || !advertises || pairedWithPhone || !inRange) return null
            Advertisement(address, rssi)
        }

    internal suspend fun accept(transport: FakeBikeTransport): BikeLink {
        // Not advertising: a connection attempt never gets an answer, unless the phone holds a link to it.
        if (synchronized(lock) { link != null || !inRange || !(advertises || pairedWithPhone) }) awaitCancellation()
        delay(timing.connect)
        transport.requireRadio()
        if (refusesConnections) throw LinkException(LinkFailure.GATT_ERROR, STATUS_GATT_ERROR)
        return synchronized(lock) {
            if (link != null) {
                null
            } else {
                FakeLink(this, transport, timing).also {
                    link = it
                    opened += it
                }
            }
        } ?: awaitCancellation()
    }

    internal fun linkEnded(ended: FakeLink) {
        synchronized(lock) { if (link === ended) link = null }
    }

    internal fun receivedDescriptorWrite(
        characteristic: UUID,
        descriptor: UUID,
        value: ByteArray,
    ) {
        synchronized(lock) { recorded += FakeGattWrite(characteristic, descriptor, null, value) }
    }

    internal fun operationStarted() {
        synchronized(lock) { mostOperationsInFlight = max(mostOperationsInFlight, ++operationsInFlight) }
    }

    internal fun operationEnded() {
        synchronized(lock) { operationsInFlight-- }
    }

    /** A write arrived over [from]: record it, then answer like the reference bike, unless a test hook says otherwise. */
    internal fun received(
        from: FakeLink,
        frame: OutgoingFrame,
    ): FakeResponse {
        synchronized(lock) {
            recorded += FakeGattWrite(BikeGatt.WRITE, null, frame, null)
            from.frames++
        }
        rejectNextWrite?.let {
            rejectNextWrite = null
            return FakeResponse.Rejected(it)
        }
        if (withholdNextWriteResponse) {
            withholdNextWriteResponse = false
            return FakeResponse.None
        }
        scope.launch {
            delay(if (frame.command is BikeCommand.ReadIdentity) timing.reply else echoDelay ?: timing.reply)
            val reply =
                synchronized(lock) {
                    when (val command = frame.command) {
                        is BikeCommand.SetSpeedLimit -> {
                            if (acts) limit = command.limit
                            val echo = SimulatedFrames.encode(SPEED_LIMIT, byteArrayOf(command.limit.kmh.toByte()))
                            echo.takeIf { echoes }
                        }

                        is BikeCommand.SetLights -> {
                            if (acts) currentRide = currentRide.copy(lightsOn = command.on)
                            val echo = SimulatedFrames.encode(LIGHTS, byteArrayOf(if (command.on) ON_BYTE else 0))
                            echo.takeIf { echoes }
                        }

                        is BikeCommand.SetAssistLevel -> {
                            if (acts) pendingAssist = command.level.level to ASSIST_SHOWN_LATE
                            val echo = SimulatedFrames.encode(ASSIST, byteArrayOf(command.level.level.toByte()))
                            echo.takeIf { echoes }
                        }

                        is BikeCommand.SetLocked -> {
                            if (acts) {
                                if (command.locked) {
                                    lockNow()
                                } else {
                                    unlockNow()
                                }
                            }
                            val state = SimulatedFrames.encode(LOCK_STATE, byteArrayOf(if (command.locked) 2 else 1))
                            state.takeIf { echoes }
                        }

                        BikeCommand.ReadIdentity -> {
                            if (answersIdentity) SimulatedFrames.encode(IDENTITY, identityPayload()) else null
                        }
                    }
                }
            // As the reference bike: its new state at once, not at the next tick; after a lock even before its
            // answer, after an unlock right after it.
            val locking = (frame.command as? BikeCommand.SetLocked)?.takeIf { acts }
            if (locking?.locked == true) statusNow()
            reply?.let { from.deliver(it) }
            if (locking?.locked == false) statusNow()
        }
        return FakeResponse.Ack
    }

    /** One status frame now, out of the usual rhythm. */
    private fun statusNow() {
        val payload = synchronized(lock) { statusPayload().let { p -> statusPayloadLength?.let { p.copyOf(it) } ?: p } }
        if (sendsStatus) synchronized(lock) { link }?.deliver(SimulatedFrames.encode(STATUS, payload))
    }

    private fun tick() {
        val payload =
            synchronized(lock) {
                if (riding && currentPower == FakePower.ON) ride(ticks++)
                pendingAssist?.let { (level, late) ->
                    if (late > 0) {
                        pendingAssist = level to late - 1
                    } else {
                        currentRide = currentRide.copy(assistLevel = level)
                        pendingAssist = null
                    }
                }
                statusPayload().let { p -> statusPayloadLength?.let { p.copyOf(it) } ?: p }
            }
        if (sendsStatus) synchronized(lock) { link }?.deliver(SimulatedFrames.encode(STATUS, payload))
    }

    private fun ride(t: Int) {
        val speed = demoSpeed(t)
        val km = speed * timing.statusOn.inWholeMilliseconds / 3_600_000.0
        val ticksPerMinute = max(1L, 60_000L / timing.statusOn.inWholeMilliseconds.coerceAtLeast(1L))
        currentRide =
            currentRide.copy(
                speedKmh = speed,
                maxSpeedKmh = max(currentRide.maxSpeedKmh, speed),
                odometerKm = currentRide.odometerKm + km,
                rideTimeMinutes = currentRide.rideTimeMinutes + if ((t + 1) % ticksPerMinute == 0L) 1 else 0,
            )
    }

    private fun statusPayload(): ByteArray {
        val r = currentRide
        val out = ByteArray(layout.payloadLength)
        val flags =
            when (currentPower) {
                FakePower.ON -> 1 to 1
                FakePower.OFF -> 0 to 0
                FakePower.POWERING_ON -> 0 to 1
            }
        val values =
            mapOf(
                StatusField.SPEED to r.speedKmh,
                StatusField.MAX_SPEED to r.maxSpeedKmh,
                StatusField.ODOMETER to r.odometerKm,
                StatusField.ASSIST_LEVEL to r.assistLevel.toDouble(),
                StatusField.BATTERY to r.batteryPercent.toDouble(),
                StatusField.LIGHTS to if (r.lightsOn) 255.0 else 0.0,
                StatusField.TEMPERATURE to r.temperature.toDouble(),
                StatusField.BATTERY_HEALTH to r.batteryHealth.toDouble(),
                StatusField.POWER_FLAG_A to flags.first.toDouble(),
                StatusField.POWER_FLAG_B to flags.second.toDouble(),
                StatusField.VOLTAGE to r.voltage,
                StatusField.RIDE_TIME to r.rideTimeMinutes.toDouble(),
            )
        for ((field, value) in values) layout.spec(field)?.let { put(out, it, value) }
        return out
    }

    private fun put(
        out: ByteArray,
        spec: FieldSpec,
        value: Double,
    ) {
        val maxRaw = (1L shl (8 * spec.size)) - 1
        var raw = (value / spec.scale).roundToLong().coerceIn(0, maxRaw)
        for (i in spec.size - 1 downTo 0) {
            out[spec.offset + i] = raw.toByte()
            raw = raw ushr 8
        }
    }

    /** The reference bike's layout: model, NUL padding, a short field, more padding, the serial, a NUL. */
    private fun identityPayload(): ByteArray {
        val out = ByteArray(MODEL_FIELD + SHORT_FIELD + SERIAL_FIELD + 1)
        model.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[MODEL_FIELD] = '1'.code.toByte()
        serial.toByteArray(Charsets.US_ASCII).copyInto(out, MODEL_FIELD + SHORT_FIELD)
        return out
    }

    public companion object {
        /**
         * The riding bike's speed at status frame [t] since its power-on: 0 for the first [STANDING_TICKS] of every
         * [RIDE_CYCLE] frames, then a ride that eases in and out over [EASE_TICKS] frames, in 0.1 km/h steps.
         */
        internal fun demoSpeed(t: Int): Double {
            val phase = t % RIDE_CYCLE
            if (phase < STANDING_TICKS) return 0.0
            val riding = phase - STANDING_TICKS
            val ease = min(1.0, min(riding + 1, RIDE_CYCLE - STANDING_TICKS - riding) / EASE_TICKS.toDouble())
            return (ease * (RIDE_BASE_KMH + RIDE_SWING_KMH * sin(riding / RIDE_PERIOD)) * 10).roundToInt() / 10.0
        }

        /** Made-up addresses for demo bikes. */
        public val DEMO_ADDRESS: BikeAddress = BikeAddress("F0:00:00:00:00:01")
        public val DEMO_ADDRESS_2: BikeAddress = BikeAddress("F0:00:00:00:00:02")
        public const val DEMO_SERIAL: String = "DEMOSERIAL000000000"

        /** A made-up model identity that no profile lists, for an unverified demo bike. */
        public const val DEMO_OTHER_MODEL: String = "ENGWE_Demo_ebike"

        /** The reference bike's services, with the bike service's characteristics as it declares them. */
        public val REFERENCE_SERVICES: List<GattService> =
            listOf(
                GattService(
                    BikeGatt.standard(GENERIC_ACCESS),
                    listOf(
                        GattCharacteristic(
                            BikeGatt.standard(DEVICE_NAME),
                            setOf(CharacteristicProperty.READ),
                            emptyList(),
                        ),
                    ),
                ),
                GattService(
                    BikeGatt.SERVICE,
                    listOf(
                        GattCharacteristic(
                            BikeGatt.WRITE,
                            setOf(CharacteristicProperty.WRITE, CharacteristicProperty.WRITE_WITHOUT_RESPONSE),
                            emptyList(),
                        ),
                        GattCharacteristic(
                            BikeGatt.NOTIFY,
                            setOf(CharacteristicProperty.NOTIFY),
                            listOf(BikeGatt.CLIENT_CONFIG),
                        ),
                    ),
                ),
            )

        /** Android's status code for a connection that timed out (the phone lost the bike). */
        public const val STATUS_CONNECTION_TIMEOUT: Int = 8

        /** Android's generic GATT error status, as when a connection attempt fails. */
        public const val STATUS_GATT_ERROR: Int = 133

        private const val GENERIC_ACCESS = 0x1800
        private const val DEVICE_NAME = 0x2A00
        private const val STATUS = 0x2A63
        private const val SPEED_LIMIT = 0x2A65
        private const val LIGHTS = 0x0597
        private const val ASSIST = 0x1009
        private const val LOCK_STATE = 0x0109

        /** Status frames that still show the old assist level after the command. */
        private const val ASSIST_SHOWN_LATE = 1
        private const val IDENTITY = 0xFF01
        private const val ON_BYTE: Byte = -1
        private const val MODEL_FIELD = 28
        private const val SHORT_FIELD = 6
        private const val SERIAL_FIELD = 19
        private const val RIDE_BASE_KMH = 22.0
        private const val RIDE_SWING_KMH = 6.0
        private const val RIDE_PERIOD = 6.0
        private const val RIDE_CYCLE = 60
        private const val STANDING_TICKS = 20
        private const val EASE_TICKS = 4
    }
}

/** Bluetooth for [FakeBike]s: they advertise, and connections reach them, without any radio. */
public class FakeBikeTransport(
    bikes: List<FakeBike>,
    private val advertisingInterval: Duration = 1.seconds,
) : BikeTransport {
    public constructor(vararg bikes: FakeBike) : this(bikes.toList())

    private val bikes = bikes.toList()
    private val radioState = MutableStateFlow(RadioState.ON)

    override val radio: StateFlow<RadioState> = radioState.asStateFlow()

    private val attempts = AtomicInteger()
    private val scanSlots = Semaphore(SCAN_STARTS)

    /** Number of connection attempts so far. */
    public val connectAttempts: Int get() = attempts.get()

    /** Simulates Bluetooth being switched off or on; switching off ends every link. */
    public fun setRadio(state: RadioState) {
        radioState.value = state
        radioFailure(state)?.let { failure -> for (bike in bikes) bike.endLink(failure) }
    }

    /**
     * Like Android: at most 4 scan starts in any 30 s, so that tests see the limit the app lives with. The
     * starts are given back on the first bike's scope; without bikes there is nothing to scan for.
     */
    override suspend fun scanSlot() {
        val scope = bikes.firstOrNull()?.scope ?: return
        scanSlots.acquire()
        scope.launch {
            delay(SCAN_PERIOD)
            scanSlots.release()
        }
    }

    override fun pairedBikes(): Set<BikeAddress> =
        if (radioState.value ==
            RadioState.ON
        ) {
            bikes.filter { it.pairedWithPhone }.map { it.address }.toSet()
        } else {
            emptySet()
        }

    override fun scan(address: BikeAddress?): Flow<Advertisement> =
        flow {
            while (true) {
                requireRadio()
                for (bike in bikes) {
                    if (address != null && bike.address != address) continue
                    bike.advertisement()?.let { emit(it) }
                }
                delay(advertisingInterval)
            }
        }

    override suspend fun connect(address: BikeAddress): BikeLink {
        attempts.incrementAndGet()
        requireRadio()
        val bike = bikes.firstOrNull { it.address == address } ?: awaitCancellation()
        return bike.accept(this)
    }

    internal fun requireRadio() {
        radioFailure(radioState.value)?.let { throw LinkException(it) }
    }

    private companion object {
        private const val SCAN_STARTS = 4
        private val SCAN_PERIOD = 30.seconds
    }

    private fun radioFailure(state: RadioState): LinkFailure? =
        when (state) {
            RadioState.ON -> null
            RadioState.OFF -> LinkFailure.BLUETOOTH_OFF
            RadioState.NOT_PERMITTED -> LinkFailure.NOT_PERMITTED
            RadioState.UNAVAILABLE -> LinkFailure.UNSUPPORTED
        }
}

/** One connection to a [FakeBike]. */
internal class FakeLink(
    private val bike: FakeBike,
    private val transport: FakeBikeTransport,
    private val timing: FakeTiming,
) : BikeLink {
    private val channel = Channel<LinkEvent>(Channel.UNLIMITED)
    private val operations = Mutex()
    private val lock = Any()
    private val endedSignal = CompletableDeferred<Unit>()
    private var ended: LinkFailure? = null
    private var discovered = false
    private var notifying = false
    private var mtu = BikeGatt.DEFAULT_MTU

    /** Frames written on this link; counted by the bike, under its lock. */
    var frames = 0

    override val events: Flow<LinkEvent> = channel.receiveAsFlow()

    override suspend fun discoverServices(): List<GattService> =
        operation {
            discovered = true
            bike.services
        }

    override suspend fun requestMtu(mtu: Int): Int =
        operation {
            require(mtu in BikeGatt.DEFAULT_MTU..BikeGatt.MAX_MTU) { "MTU out of range" }
            this.mtu = min(mtu, BIKE_MTU)
            this.mtu
        }

    override suspend fun enableNotifications() {
        operation {
            val notify = characteristic(BikeGatt.NOTIFY)
            if (notify == null ||
                CharacteristicProperty.NOTIFY !in notify.properties ||
                BikeGatt.CLIENT_CONFIG !in notify.descriptors
            ) {
                throw LinkException(LinkFailure.UNSUPPORTED)
            }
            bike.receivedDescriptorWrite(BikeGatt.NOTIFY, BikeGatt.CLIENT_CONFIG, ENABLE_NOTIFICATIONS)
            notifying = true
        }
    }

    override suspend fun write(frame: OutgoingFrame) {
        operations.withLock {
            val response =
                step {
                    val write = characteristic(BikeGatt.WRITE)
                    if (write == null || CharacteristicProperty.WRITE !in write.properties) {
                        throw LinkException(LinkFailure.UNSUPPORTED)
                    }
                    bike.received(this, frame)
                }
            when (response) {
                FakeResponse.Ack -> {
                    Unit
                }

                is FakeResponse.Rejected -> {
                    throw LinkException(LinkFailure.GATT_ERROR, response.status)
                }

                FakeResponse.None -> {
                    // Like the Android stack: the operation stays pending until its timeout or the link ends.
                    withTimeoutOrNull(timing.operationTimeout) { endedSignal.await() }
                    ensureOpen()
                    throw LinkException(LinkFailure.TIMEOUT)
                }
            }
        }
    }

    override suspend fun readRssi(): Int = operation { bike.rssi }

    override fun close() {
        end(LinkFailure.CLOSED)
    }

    fun end(
        failure: LinkFailure,
        status: Int? = null,
    ) {
        synchronized(lock) {
            if (ended != null) return
            ended = failure
            // Under the lock, so that no notification can follow the disconnect.
            channel.trySend(LinkEvent.Disconnected(failure, status))
            channel.close()
        }
        endedSignal.complete(Unit)
        bike.linkEnded(this)
    }

    /** Delivers a notification value, cut to what fits the current MTU. Dropped unless notifications are on. */
    fun deliver(value: ByteArray) {
        synchronized(lock) {
            if (ended != null || !notifying) return
            val room = mtu - ATT_HEADER
            channel.trySend(LinkEvent.Notification(if (value.size > room) value.copyOf(room) else value))
        }
    }

    private fun characteristic(uuid: UUID): GattCharacteristic? {
        if (!discovered) return null
        return bike.services.firstOrNull { it.uuid == BikeGatt.SERVICE }?.characteristic(uuid)
    }

    private suspend fun <T> operation(block: () -> T): T = operations.withLock { step(block) }

    /** One GATT operation, with the queue held: it takes a moment, and the link must be open before and after. */
    private suspend fun <T> step(block: () -> T): T {
        ensureOpen()
        bike.operationStarted()
        try {
            delay(timing.operation)
            return synchronized(lock) {
                ensureOpen()
                block()
            }
        } finally {
            bike.operationEnded()
        }
    }

    private fun ensureOpen() {
        transport.requireRadio()
        synchronized(lock) { ended }?.let { throw LinkException(it) }
    }

    private companion object {
        const val ATT_HEADER = 3
        const val BIKE_MTU = 512
        val ENABLE_NOTIFICATIONS = byteArrayOf(0x01, 0x00)
    }
}

/** How the fake bike answers a write. */
internal sealed class FakeResponse {
    data object Ack : FakeResponse()

    data class Rejected(
        val status: Int,
    ) : FakeResponse()

    /** No response at all. */
    data object None : FakeResponse()
}

/** A GATT write as the fake bike received it: a frame to the write characteristic, or a descriptor value. */
internal class FakeGattWrite(
    val characteristic: UUID,
    val descriptor: UUID?,
    val frame: OutgoingFrame?,
    value: ByteArray?,
) {
    private val copy = value?.copyOf()

    val value: ByteArray? get() = copy?.copyOf()
}

/** Frames the simulated bike sends. Kept here: the protocol module only builds frames for the app's commands. */
internal object SimulatedFrames {
    fun encode(
        command: Int,
        payload: ByteArray,
    ): ByteArray {
        val body =
            byteArrayOf(
                (command ushr 8).toByte(),
                command.toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ) +
                payload
        val crc = Crc16Modbus.compute(body)
        return byteArrayOf(0x45, 0x6E) + body + byteArrayOf((crc ushr 8).toByte(), crc.toByte(), 0x77, 0x65)
    }
}
