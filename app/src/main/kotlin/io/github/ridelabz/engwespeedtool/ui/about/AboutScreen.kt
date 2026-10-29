package io.github.ridelabz.engwespeedtool.ui.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ContentCard
import io.github.ridelabz.engwespeedtool.ui.components.NavigationRow
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme

/** A line of the compatibility table. */
data class CompatibilityRow(
    val model: String,
    /** What the project knows about it, ready to show. */
    val detail: String,
    val verified: Boolean,
)

@Composable
fun AboutScreen(
    version: String,
    compatibility: List<CompatibilityRow>,
    onBack: () -> Unit,
    onSource: () -> Unit = {},
    onLicences: () -> Unit = {},
) {
    val title = stringResource(R.string.about_title)
    AppScaffold(onBack = onBack, paneTitle = title) {
        ScreenTitle(stringResource(R.string.app_name))
        Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.legal_about_unofficial), style = MaterialTheme.typography.bodyLarge)
        AboutSection(R.string.about_compatibility_title) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = Spacing.s)) {
                    compatibility.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.l))
                        CompatibilityLine(row)
                    }
                }
            }
            Text(
                text = stringResource(R.string.about_compatibility_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AboutSection(R.string.about_contribute_title) {
            Text(stringResource(R.string.about_contribute), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_support), style = MaterialTheme.typography.bodyLarge)
        }
        AboutSection(R.string.about_licence_title) {
            Text(stringResource(R.string.legal_about_copyright), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.legal_about_licence), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_prior_work), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.legal_about_third_party),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The source opens in the browser; the app has no internet access of its own.
            ContentCard(contentPadding = PaddingValues(vertical = Spacing.xs)) {
                NavigationRow(stringResource(R.string.about_source), onSource, external = true)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
                NavigationRow(stringResource(R.string.about_licences), onLicences)
            }
        }
    }
}

@Composable
private fun AboutSection(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun CompatibilityLine(row: CompatibilityRow) {
    val status = SpeedToolTheme.status
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                }.padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.model, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = row.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(
                painter = painterResource(if (row.verified) R.drawable.ic_verified else R.drawable.ic_warning),
                contentDescription = null,
                tint = if (row.verified) status.confirmed else status.caution,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(if (row.verified) R.string.badge_verified else R.string.value_unverified),
                style = MaterialTheme.typography.labelLarge,
                color = if (row.verified) status.confirmed else status.caution,
            )
        }
    }
}
