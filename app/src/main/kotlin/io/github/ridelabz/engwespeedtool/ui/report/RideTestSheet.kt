package io.github.ridelabz.engwespeedtool.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.ButtonPair
import io.github.ridelabz.engwespeedtool.ui.components.SheetDragHandle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets
import kotlinx.coroutines.launch

private const val MAX_SPEED_DIGITS = 2

/**
 * The ride test, on the app open after an unverified bike confirmed [test]'s speed: two questions, both
 * optional. [onDone] gets the answers (they open a compatibility report) or null for "Skip"; both end the
 * test. [onLater] (the sheet swiped away) asks again on the next app open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideTestSheet(
    test: RideTest,
    onDone: (RideTest?) -> Unit,
    onLater: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onLater, sheetState = sheetState, dragHandle = { SheetDragHandle() }) {
        RideTestContent(
            test = test,
            onDone = { answers -> scope.launch { sheetState.hide() }.invokeOnCompletion { onDone(answers) } },
        )
    }
}

@Composable
fun RideTestContent(
    test: RideTest,
    onDone: (RideTest?) -> Unit,
) {
    var stopped by rememberSaveable { mutableStateOf<RideTestStop?>(null) }
    var stoppedAt by rememberSaveable { mutableStateOf("") }
    var restart by rememberSaveable { mutableStateOf<RideTestRestart?>(null) }
    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text(
            text = stringResource(R.string.ride_test_title, test.kmh),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.ride_test_intro), style = MaterialTheme.typography.bodyLarge)
        Question(
            title = stringResource(R.string.ride_test_stopped, test.kmh),
            options =
                listOf(
                    RideTestStop.YES to stringResource(R.string.ride_test_yes),
                    RideTestStop.NO to stringResource(R.string.ride_test_stopped_no),
                    RideTestStop.NOT_RIDDEN to stringResource(R.string.ride_test_not_ridden),
                ),
            selected = stopped,
            onSelect = { stopped = it },
        )
        if (stopped == RideTestStop.NO) {
            OutlinedTextField(
                value = stoppedAt,
                onValueChange = { value -> stoppedAt = value.filter(Char::isDigit).take(MAX_SPEED_DIGITS) },
                label = { Text(stringResource(R.string.ride_test_stopped_at)) },
                suffix = { Text(stringResource(R.string.unit_kmh)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Question(
            title = stringResource(R.string.ride_test_restart),
            options =
                listOf(
                    RideTestRestart.YES to stringResource(R.string.ride_test_yes),
                    RideTestRestart.NO to stringResource(R.string.ride_test_restart_no, test.kmh),
                    RideTestRestart.NOT_CHECKED to stringResource(R.string.ride_test_not_checked),
                ),
            selected = restart,
            onSelect = { restart = it },
        )
        Text(
            text = stringResource(R.string.ride_test_next),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ButtonPair(
            secondary = { place ->
                OutlinedButton(
                    onClick = { onDone(null) },
                    modifier = place.heightIn(min = TouchTargets.minimum),
                ) { Text(stringResource(R.string.ride_test_skip)) }
            },
        ) { place ->
            Button(
                onClick = {
                    onDone(
                        test.copy(
                            stopped = stopped,
                            stoppedAtKmh = stoppedAt.toIntOrNull().takeIf { stopped == RideTestStop.NO },
                            restart = restart,
                        ),
                    )
                },
                modifier = place.heightIn(min = TouchTargets.minimum),
            ) { Text(stringResource(R.string.ride_test_report)) }
        }
    }
}

@Composable
private fun <T> Question(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Column(modifier = Modifier.selectableGroup()) {
            for ((value, label) in options) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = TouchTargets.minimum)
                            .selectable(
                                selected = selected == value,
                                role = Role.RadioButton,
                                onClick = { onSelect(value) },
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    RadioButton(selected = selected == value, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
