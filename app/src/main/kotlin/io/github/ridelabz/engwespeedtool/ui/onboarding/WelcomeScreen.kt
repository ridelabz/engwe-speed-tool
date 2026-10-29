package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.HeroIcon
import io.github.ridelabz.engwespeedtool.ui.components.IconLine
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing

/** First screen of the first run: what the app does, that it is unofficial, and that 25 is the default. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val title = stringResource(R.string.welcome_title)
    AppScaffold(
        onBack = null,
        paneTitle = title,
        bottomBar = { PrimaryAction(stringResource(R.string.welcome_start), onStart) },
    ) {
        HeroIcon(
            R.drawable.ic_electric_bike,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        ScreenTitle(title)
        Text(
            text = stringResource(R.string.welcome_unofficial_tag),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        )
        IconLine(R.drawable.ic_speed, stringResource(R.string.welcome_line_what))
        IconLine(R.drawable.ic_info, stringResource(R.string.welcome_line_unofficial))
        IconLine(R.drawable.ic_settings_backup_restore, stringResource(R.string.welcome_line_default))
    }
}
