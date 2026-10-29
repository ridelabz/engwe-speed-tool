package io.github.ridelabz.engwespeedtool.ui.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.onboarding.riskStatements
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** When the rider accepted the legal text, and which version. */
data class Acceptance(
    val date: LocalDate,
    val version: Int,
)

/** The legal and safety text the rider accepted, always available from the menu. */
@Composable
fun LegalScreen(
    acceptance: Acceptance?,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.legal_screen_title)
    AppScaffold(onBack = onBack, paneTitle = title) {
        ScreenTitle(title)
        if (acceptance != null) {
            Text(
                text = stringResource(R.string.legal_accepted_on, formatDate(acceptance.date), acceptance.version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            riskStatements().forEachIndexed { index, statement ->
                Row(
                    modifier =
                        Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    Text(
                        stringResource(R.string.legal_statement_number, index + 1),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(statement),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Card(
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.cardColors(
                    containerColor = SpeedToolTheme.status.cautionContainer,
                    contentColor = SpeedToolTheme.status.onCautionContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null)
                    Text(
                        text = stringResource(R.string.legal_caveat_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Text(stringResource(R.string.legal_caveat), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A date in the phone's language, with the month written out, so it can't be misread or misheard. */
@Composable
private fun formatDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    return format.format(date)
}
