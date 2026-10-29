package io.github.ridelabz.engwespeedtool.ui.about

import android.content.res.Resources
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.components.AppScaffold
import io.github.ridelabz.engwespeedtool.ui.components.ScreenTitle
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A licence the app ships under or with, and who it covers. */
private class Licence(
    @StringRes val title: Int,
    @RawRes val text: Int,
)

private val LICENCES =
    listOf(
        Licence(R.string.licences_app, R.raw.licence_gpl_3_0),
        Licence(R.string.licences_libraries, R.raw.licence_apache_2_0),
    )

/** The licences in full: the app's own, then that of the libraries and icons it includes. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val title = stringResource(R.string.licences_title)
    val resources = LocalResources.current
    val texts by produceState<List<List<String>>?>(null, resources) {
        value = withContext(Dispatchers.IO) { LICENCES.map { paragraphs(resources, it.text) } }
    }
    AppScaffold(onBack = onBack, paneTitle = title, scrollable = false) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.weight(1f)) {
            item { ScreenTitle(title) }
            item { Text(stringResource(R.string.licences_intro), style = MaterialTheme.typography.bodyLarge) }
            LICENCES.forEachIndexed { index, licence ->
                item {
                    Text(
                        text = stringResource(licence.title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.l).semantics { heading() },
                    )
                }
                items(texts?.getOrNull(index).orEmpty()) { paragraph ->
                    Text(
                        paragraph,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The licence's paragraphs, each on one line so it wraps to the screen. */
private fun paragraphs(
    resources: Resources,
    @RawRes id: Int,
): List<String> =
    resources
        .openRawResource(id)
        .bufferedReader()
        .use { it.readLines() }
        .map { it.trim() }
        .fold(mutableListOf(mutableListOf<String>())) { paragraphs, line ->
            // A blank line ends a paragraph.
            if (line.isEmpty()) paragraphs.add(mutableListOf()) else paragraphs.last().add(line)
            paragraphs
        }.map { it.joinToString(" ") }
        .filter { it.isNotEmpty() }
