package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.UnverifiedAcknowledgement
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.HeroIcon
import io.github.ridelabz.engwespeedtool.ui.components.IconLine
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.components.SecondaryAction
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme

/**
 * Shown once per bike and text version before the first command to an unverified bike: what may happen,
 * including that a change might be permanent, and how to go back. The screen itself records the
 * acknowledgement when the rider continues, through [acknowledgement], asked at that moment: null means
 * it no longer applies (the bike's check changed), and nothing is recorded. Changing this screen needs a
 * maintainer's review, like its text.
 */
@Composable
fun UnverifiedAcknowledgementScreen(
    acknowledgement: () -> UnverifiedAcknowledgement?,
    /** After the acknowledgement was recorded; [reportResults]: the rider offered to report how it worked. */
    onAcknowledged: (reportResults: Boolean) -> Unit,
    /** Nothing was recorded: the acknowledgement no longer applies. */
    onNoLongerApplies: () -> Unit,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.unverified_title)
    val continueAndRecord = { reportResults: Boolean ->
        val handle = acknowledgement()
        if (handle == null) {
            onNoLongerApplies()
        } else {
            handle.recordUnverifiedAcknowledgement()
            onAcknowledged(reportResults)
        }
    }
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        bottomBar = {
            PrimaryAction(stringResource(R.string.unverified_continue), { continueAndRecord(false) })
            SecondaryAction(stringResource(R.string.unverified_continue_and_report), { continueAndRecord(true) })
        },
    ) {
        HeroIcon(
            R.drawable.ic_warning,
            SpeedToolTheme.status.cautionContainer,
            SpeedToolTheme.status.onCautionContainer,
        )
        ScreenTitle(title)
        Text(stringResource(R.string.unverified_untested), style = MaterialTheme.typography.bodyLarge)
        IconLine(
            R.drawable.ic_warning,
            stringResource(R.string.unverified_might_be_permanent),
            tint = SpeedToolTheme.status.caution,
        )
        IconLine(R.drawable.ic_settings_backup_restore, stringResource(R.string.unverified_restore))
        Text(
            text = stringResource(R.string.unverified_reports_help),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
