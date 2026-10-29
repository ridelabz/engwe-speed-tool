package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.HeroIcon
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme

/** End of the first run: the bike is saved as "My bike". */
@Composable
fun DoneScreen(onOpen: () -> Unit) {
    val title = stringResource(R.string.done_title)
    AppScaffold(
        onBack = null,
        paneTitle = title,
        bottomBar = { PrimaryAction(stringResource(R.string.done_open), onOpen) },
    ) {
        HeroIcon(
            R.drawable.ic_check_circle,
            SpeedToolTheme.status.confirmedContainer,
            SpeedToolTheme.status.onConfirmedContainer,
        )
        ScreenTitle(title)
        Text(
            stringResource(R.string.done_saved, stringResource(R.string.bike_default_name)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.done_rename_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
