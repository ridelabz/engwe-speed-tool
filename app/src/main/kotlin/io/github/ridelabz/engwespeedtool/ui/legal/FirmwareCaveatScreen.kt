package io.github.ridelabz.engwespeedtool.ui.legal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.FirmwareCaveat
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.HeroIcon
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme

/**
 * Once, at setup (and again after the legal text changes): the caveat that speed changes were tested on one
 * model and firmware only. Continuing records that the rider saw it ([caveat]), then [onContinue]. It never
 * shows on a speed tap. Changing this screen needs a maintainer's review.
 */
@Composable
fun FirmwareCaveatScreen(
    caveat: FirmwareCaveat,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.legal_caveat_title)
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        bottomBar = {
            PrimaryAction(stringResource(R.string.legal_caveat_sheet_next), {
                caveat.recordFirmwareCaveat()
                onContinue()
            })
        },
    ) {
        HeroIcon(
            R.drawable.ic_warning,
            SpeedToolTheme.status.cautionContainer,
            SpeedToolTheme.status.onCautionContainer,
        )
        ScreenTitle(title)
        Text(stringResource(R.string.legal_caveat), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.legal_caveat_sheet_version),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
