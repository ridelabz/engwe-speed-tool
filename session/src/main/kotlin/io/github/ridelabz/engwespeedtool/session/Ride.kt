package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.Frame
import io.github.ridelabz.engwespeedtool.protocol.PowerState
import io.github.ridelabz.engwespeedtool.protocol.StatusDecoder
import io.github.ridelabz.engwespeedtool.protocol.StatusLayout
import io.github.ridelabz.engwespeedtool.protocol.StatusReport

/** Live values from the bike's status frames, with what the app can tell about the bike's power. */
public class RideData internal constructor(
    /** The latest status that fits the bike's layout, with a verified flag per value; null before the first. */
    public val status: StatusReport?,
    /** Status frames arrive but don't fit the bike's layout: nothing is shown rather than wrong values. */
    public val unreadable: Boolean,
    /** No readable status for a while, or no connection: the values may be out of date. */
    public val stale: Boolean,
    /** From the latest readable status's power flags; [PowerState.UNKNOWN] when the layout has none. */
    public val power: PowerState,
    /** Whether the power flags' meaning is verified for this bike. */
    public val powerVerified: Boolean,
    /**
     * Power-ons seen since the session started: the flags went from off to on on one connection. A power-on
     * while the app wasn't connected can't be seen. One status frame with both flags off arms it: on the
     * reference bike both are zero only with the display off, and the CRC makes accidental zeros implausible.
     * Use it only where [powerVerified] holds.
     */
    public val powerOns: Int,
) {
    /** True when the bike reports that it is switched off. */
    public val bikeOff: Boolean get() = power == PowerState.OFF

    internal fun stale(): RideData = RideData(status, unreadable, stale = true, power, powerVerified, powerOns)

    override fun toString(): String =
        "RideData(status=${status != null}, unreadable=$unreadable, stale=$stale, power=$power, powerOns=$powerOns)"

    public companion object {
        /** Nothing known yet. */
        public val NONE: RideData = RideData(null, unreadable = false, stale = true, PowerState.UNKNOWN, false, 0)
    }
}

/** Turns one connection's status frames into [RideData]. Not thread-safe. */
internal class RideTracker(
    private val layout: StatusLayout,
    private var powerOns: Int,
) {
    private var current = RideData(null, unreadable = false, stale = false, PowerState.UNKNOWN, false, powerOns)
    private var sawOff = false

    val data: RideData get() = current

    /** Takes one status frame (CRC already checked). Returns the report when it fits the layout. */
    fun update(frame: Frame): StatusReport? {
        val report = StatusDecoder.decode(frame, layout)
        if (report == null) {
            current = RideData(null, unreadable = true, stale = false, PowerState.UNKNOWN, false, powerOns)
            return null
        }
        val power = report.power
        val state = power?.value
        if (state == PowerState.OFF) {
            sawOff = true
        } else if ((state == PowerState.ON || state == PowerState.POWERING_ON) && sawOff) {
            sawOff = false
            powerOns++
        }
        current =
            RideData(
                report,
                unreadable = false,
                stale = false,
                power?.value ?: PowerState.UNKNOWN,
                power?.verified ?: false,
                powerOns,
            )
        return report
    }

    fun stale() {
        current = current.stale()
    }
}
