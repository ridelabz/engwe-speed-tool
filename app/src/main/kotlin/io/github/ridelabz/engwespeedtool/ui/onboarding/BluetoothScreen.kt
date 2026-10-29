package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.HeroIcon
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle

/** Where the rider stands with Android's Bluetooth ("Nearby devices") permission. */
enum class PermissionUiState {
    /** Not asked yet, or allowed before. */
    NOT_ASKED,

    /** Refused; Android will ask again. */
    DENIED,

    /** Refused for good: only the app's settings can change it. */
    DENIED_PERMANENTLY,
}

/**
 * Explains why the app needs Bluetooth before Android asks for the permission, and what to do when the
 * rider refused it.
 */
@Composable
fun BluetoothScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
    permission: PermissionUiState = PermissionUiState.NOT_ASKED,
    onOpenSettings: () -> Unit = {},
) {
    val title = stringResource(R.string.bluetooth_title)
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        bottomBar = {
            when (permission) {
                PermissionUiState.NOT_ASKED -> {
                    PrimaryAction(stringResource(R.string.action_continue), onContinue)
                }

                PermissionUiState.DENIED -> {
                    PrimaryAction(stringResource(R.string.action_allow), onContinue)
                }

                PermissionUiState.DENIED_PERMANENTLY -> {
                    PrimaryAction(stringResource(R.string.action_open_settings), onOpenSettings)
                }
            }
        },
    ) {
        HeroIcon(
            R.drawable.ic_bluetooth,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        ScreenTitle(title)
        Text(stringResource(R.string.bluetooth_why), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.bluetooth_privacy), style = MaterialTheme.typography.bodyLarge)
        val next =
            when (permission) {
                PermissionUiState.NOT_ASKED -> R.string.bluetooth_next
                PermissionUiState.DENIED -> R.string.bluetooth_denied
                PermissionUiState.DENIED_PERMANENTLY -> R.string.bluetooth_denied_permanently
            }
        Text(
            text = stringResource(next),
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (permission == PermissionUiState.NOT_ASKED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
