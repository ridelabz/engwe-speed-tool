package io.github.ridelabz.engwespeedtool.ui.onboarding

import io.github.ridelabz.engwespeedtool.protocol.UnverifiedReason
import io.github.ridelabz.engwespeedtool.ui.components.StepState

/** Signal strength of a bike found while scanning, in three steps. */
enum class SignalStrength { WEAK, FAIR, STRONG }

/**
 * A bike found while scanning. [key] identifies it to the caller; [name] is how it is listed. [signal] is
 * null for a bike paired with this phone that isn't heard: the phone holds a link to it, so it doesn't advertise.
 */
data class FoundBike(
    val key: Int,
    val name: String,
    val signal: SignalStrength?,
)

sealed interface FindBikeUiState {
    /** Still searching; [bikes] are those found so far, in the order to show them. */
    data class Searching(
        val bikes: List<FoundBike>,
    ) : FindBikeUiState

    /** No bike answered within the search time. */
    data object NothingFound : FindBikeUiState

    data object BluetoothOff : FindBikeUiState

    /** The app may not use Bluetooth. [inSettings]: Android won't ask again, so only the app's settings can allow it. */
    data class NoPermission(
        val inSettings: Boolean,
    ) : FindBikeUiState
}

/** The connect-and-check sequence: three steps, then a result. */
data class ConnectCheckUiState(
    val connected: StepState,
    val responds: StepState,
    val model: StepState,
    val result: CheckResult?,
    /** The check started again by itself because the app had been in the background. */
    val restarted: Boolean = false,
) {
    companion object {
        val Starting = ConnectCheckUiState(StepState.RUNNING, StepState.WAITING, StepState.WAITING, result = null)
    }
}

sealed interface CheckResult {
    /** The bike's model identity matches a profile the project verified. */
    data class Verified(
        val modelName: String,
    ) : CheckResult

    /** The bike passed the protocol checks, but the project hasn't verified it. */
    data class Unverified(
        val modelName: String?,
        val reason: UnverifiedReason,
    ) : CheckResult

    /** A protocol check failed: the app won't send this bike anything. */
    data object Incompatible : CheckResult

    /** The connection failed, most likely because another app or phone holds the bike. */
    data object ConnectedElsewhere : CheckResult

    /** The bike didn't show up: off, out of range, or connected to another app or phone. */
    data object NotFound : CheckResult

    /** Bluetooth was switched off during the check. */
    data object BluetoothOff : CheckResult

    /** The app may no longer use Bluetooth. */
    data object NoPermission : CheckResult

    /** The rider hasn't accepted the risks, so the app didn't read the bike's model. */
    data object NotAccepted : CheckResult
}
