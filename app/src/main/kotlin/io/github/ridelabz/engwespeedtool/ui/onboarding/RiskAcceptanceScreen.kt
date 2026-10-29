package io.github.ridelabz.engwespeedtool.ui.onboarding

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.bike.BikeServices
import io.github.ridelabz.engwespeedtool.bike.RiskAcceptance
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.PrimaryAction
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets
import java.time.LocalDate

/** The statements a rider accepts before first use, in order (string resource IDs, from `legal.xml`). */
@Composable
fun riskStatements(): List<Int> {
    val resources = LocalResources.current
    return remember(resources) { riskStatements(resources) }
}

fun riskStatements(resources: Resources): List<Int> =
    resources.obtainTypedArray(R.array.legal_statements).use { array ->
        List(array.length()) { array.getResourceId(it, 0) }.also { ids -> check(ids.isNotEmpty() && 0 !in ids) }
    }

/**
 * Risk acceptance: "I accept" is enabled only when every statement is ticked, and records the acceptance
 * with [acceptance] before [onAccept]. [changed]: the rider accepted an earlier text, which says so.
 */
@Composable
fun RiskAcceptanceScreen(
    onAccept: () -> Unit,
    onBack: () -> Unit,
    acceptance: RiskAcceptance = BikeServices.get(LocalContext.current).riskAcceptance,
    changed: Boolean = false,
) {
    val statements = riskStatements()
    var saved by rememberSaveable { mutableStateOf(List(statements.size) { false }) }
    // Ticks restored for a different list of statements count for nothing.
    val ticked = saved.takeIf { it.size == statements.size } ?: List(statements.size) { false }
    val all = ticked.all { it }
    val title = stringResource(R.string.legal_title)
    AppScaffold(
        onBack = onBack,
        paneTitle = title,
        bottomBar = {
            if (!all) {
                Text(
                    text = stringResource(R.string.legal_accept_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryAction(
                stringResource(R.string.legal_accept),
                {
                    // Recorded here, in protected code, and only once every statement is ticked.
                    if (all) {
                        acceptance.acceptRisk(LocalDate.now())
                        onAccept()
                    }
                },
                enabled = all,
            )
        },
    ) {
        ScreenTitle(title)
        Text(
            stringResource(if (changed) R.string.legal_intro_changed else R.string.legal_intro),
            style = MaterialTheme.typography.bodyLarge,
        )
        statements.forEachIndexed { index, statement ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTargets.minimum)
                        .toggleable(
                            value = ticked[index],
                            role = Role.Checkbox,
                            onValueChange = { value -> saved = ticked.toMutableList().also { it[index] = value } },
                        ).padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Checkbox(checked = ticked[index], onCheckedChange = null)
                Text(
                    stringResource(statement),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
