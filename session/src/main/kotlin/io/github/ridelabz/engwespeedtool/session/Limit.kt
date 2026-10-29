package io.github.ridelabz.engwespeedtool.session

import io.github.ridelabz.engwespeedtool.protocol.AssistLevel
import io.github.ridelabz.engwespeedtool.protocol.SpeedLimit

/** Why the session sent a speed (or an assist level) by itself, for "keep this speed" (or assist level). */
public enum class Reapply {
    /** Once on a new connection: the bike may have restarted while the app wasn't connected. */
    RECONNECT,

    /** Once after the session saw the bike switch on (only where [SessionConfig.restartsTrusted]). */
    RESTART,
}

/**
 * What the session knows about the bike's assistance limit on the current connection. The bike can't
 * report its limit, so this is only what the session sent and saw: nothing survives a new connection.
 */
public sealed interface LimitState {
    /** Nothing sent on this connection yet (or a command failed): the app can't tell the limit. */
    public data object Unknown : LimitState

    /**
     * The session saw the bike switch on (its verified power flags went off, then on) and has sent nothing
     * since. On the tested bike the limit was back to 25 km/h then, whether it was switched off at its display
     * or locked (a lock and an unlock look the same in the status) ([SessionConfig.restartsTrusted]).
     */
    public data object Restarted : LimitState

    /** [limit] was sent and the session waits for the echo; [reapply] says why if "keep this speed" sent it. */
    public data class Sending(
        val limit: SpeedLimit,
        val reapply: Reapply? = null,
    ) : LimitState

    /** The bike echoed [limit]. */
    public data class Confirmed(
        val limit: SpeedLimit,
        val reapply: Reapply? = null,
    ) : LimitState

    /** [limit] was written but not confirmed: the app can't tell whether it took effect. */
    public data class NotConfirmed(
        val limit: SpeedLimit,
        val reapply: Reapply? = null,
    ) : LimitState
}

/**
 * What the session sent and saw about the assist level on the current connection. The level the bike reports
 * is in the ride data; this only says what the app did: nothing survives a new connection or the bike
 * switching off, and a level changed on the bike afterwards ends [Confirmed].
 */
public sealed interface AssistState {
    /** Nothing sent on this connection, or nothing that still holds. */
    public data object Idle : AssistState

    /** [level] was sent and the session waits for the echo and the status; [reapply]: "keep" sent it, and why. */
    public data class Sending(
        val level: AssistLevel,
        val reapply: Reapply? = null,
    ) : AssistState

    /** The bike echoed [level] and its status showed it. */
    public data class Confirmed(
        val level: AssistLevel,
        val reapply: Reapply? = null,
    ) : AssistState

    /** [level] was written but not confirmed in time: the app can't tell whether it took effect. */
    public data class NotConfirmed(
        val level: AssistLevel,
        val reapply: Reapply? = null,
    ) : AssistState
}
