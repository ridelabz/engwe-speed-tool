package io.github.ridelabz.engwespeedtool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets

/** Shown on top of every screen while the app runs on simulated data; with [onExit], it also ends demo mode. */
@Composable
fun DemoBanner(
    modifier: Modifier = Modifier,
    onExit: (() -> Unit)? = null,
) {
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ).padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            text = stringResource(R.string.demo_banner),
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = if (onExit == null) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.xs),
        )
        if (onExit != null) {
            TextButton(onClick = onExit, modifier = Modifier.heightIn(min = TouchTargets.minimum)) {
                Text(stringResource(R.string.action_exit_demo), color = content)
            }
        }
    }
}
