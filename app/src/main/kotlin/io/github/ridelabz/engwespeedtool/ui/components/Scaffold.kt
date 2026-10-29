package io.github.ridelabz.engwespeedtool.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.ui.theme.Layout
import io.github.ridelabz.engwespeedtool.ui.theme.Spacing
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets
import io.github.ridelabz.engwespeedtool.ui.theme.onTopBar
import io.github.ridelabz.engwespeedtool.ui.theme.topBar

/**
 * A screen: an app bar pinned at the top, in its own colour, with a back button, a short [title] (one line,
 * read as a heading; most screens put their title in the content instead, where long ones wrap at large
 * font sizes) and actions; scrolling content; and optional actions pinned at the bottom, within reach of
 * the thumb. [onBack] also handles the system back gesture; without it, a title or actions, there is no
 * app bar. [paneTitle] is announced by screen readers when the screen appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    paneTitle: String? = null,
    title: String? = null,
    scrollable: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    val layoutDirection = LocalLayoutDirection.current
    val hasTopBar = onBack != null || actions != null || title != null
    val colours = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.semantics { if (paneTitle != null) this.paneTitle = paneTitle },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (hasTopBar) {
                TopAppBar(
                    title = {
                        if (title != null) {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = actions ?: {},
                    // Clear of a camera cutout too (in landscape), as the content is.
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = colours.topBar,
                            scrolledContainerColor = colours.topBar,
                            titleContentColor = colours.onTopBar,
                            navigationIconContentColor = colours.onTopBar,
                            actionIconContentColor = colours.onTopBar,
                        ),
                )
            }
        },
        bottomBar = {
            if (bottomBar != null) {
                // A Surface, not just a background: content scrolls behind the bar, and touches on the bar
                // must never reach it (such as a statement on the risk screen).
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    // A hairline above the actions, where content scrolls under the bar (a column: Surface
                    // stacks its content); on wide screens the actions keep the content's width, centred.
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Column(
                                modifier =
                                    Modifier
                                        .widthIn(max = Layout.maxContentWidth)
                                        .fillMaxWidth()
                                        .windowInsetsPadding(
                                            WindowInsets.safeDrawing.only(
                                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                                            ),
                                        ).padding(horizontal = Spacing.l, vertical = Spacing.m),
                                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                                content = bottomBar,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // The bottom inset goes inside the scrolling content, so it can scroll behind the gesture bar. On
        // wide screens (tablets, landscape) the content keeps a readable width, centred.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        top = padding.calculateTopPadding(),
                        start = padding.calculateStartPadding(layoutDirection),
                        end = padding.calculateEndPadding(layoutDirection),
                    ).consumeWindowInsets(padding)
                    // Scrolls across the whole width, also in the margins of a wide screen.
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = Layout.maxContentWidth)
                        .fillMaxWidth()
                        // A fixed height for screens that lay out their own list (weight), none when scrolling.
                        .then(if (scrollable) Modifier else Modifier.fillMaxHeight())
                        .padding(horizontal = Spacing.l, vertical = Spacing.s)
                        .padding(
                            top = if (hasTopBar) Spacing.s else Spacing.xl,
                            bottom = padding.calculateBottomPadding(),
                        ),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
                content = content,
            )
        }
    }
}

/** The title of a screen, announced as a heading. */
@Composable
fun ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = TouchTargets.minimum),
    ) { Text(text, textAlign = TextAlign.Center) }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = TouchTargets.minimum),
    ) { Text(text, textAlign = TextAlign.Center) }
}

@Composable
fun TertiaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = TouchTargets.minimum),
    ) { Text(text, textAlign = TextAlign.Center) }
}

/** Progress of one step in a checklist; [NOT_RUN] for a step a finished checklist never reached. */
enum class StepState { WAITING, RUNNING, DONE, FAILED, NOT_RUN }

/** A checklist line: an icon (or a spinner) that shows the state, and the text. */
@Composable
fun ChecklistRow(
    text: String,
    state: StepState,
    modifier: Modifier = Modifier,
) {
    val description =
        when (state) {
            StepState.WAITING -> stringResource(R.string.step_waiting)
            StepState.RUNNING -> stringResource(R.string.step_running)
            StepState.DONE -> stringResource(R.string.step_done)
            StepState.FAILED -> stringResource(R.string.step_failed)
            StepState.NOT_RUN -> stringResource(R.string.step_not_run)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = TouchTargets.minimum)
                .semantics(mergeDescendants = true) { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        when (state) {
            StepState.RUNNING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).padding(2.dp).clearAndSetSemantics {},
                    strokeWidth = 2.5.dp,
                )
            }

            else -> {
                Icon(
                    painter = painterResource(stepIcon(state)),
                    contentDescription = null,
                    tint =
                        when (state) {
                            StepState.DONE -> SpeedToolTheme.status.confirmed
                            StepState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@DrawableRes
private fun stepIcon(state: StepState): Int =
    when (state) {
        StepState.DONE -> R.drawable.ic_check_circle

        StepState.FAILED -> R.drawable.ic_error

        // Never reached: not the same as still waiting.
        StepState.NOT_RUN -> R.drawable.ic_remove

        else -> R.drawable.ic_radio_button_unchecked
    }

/** An icon next to text, for short points and tips. */
@Composable
fun IconLine(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** A sheet's drag handle with a 48 dp touch target (Material's own is 32 dp wide). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDragHandle() {
    BottomSheetDefaults.DragHandle(modifier = Modifier.minimumInteractiveComponentSize())
}

/** The large round icon at the top of a first-run screen: [icon] on [container], drawn in [content]. */
@Composable
fun HeroIcon(
    @DrawableRes icon: Int,
    container: Color,
    content: Color,
) {
    Box(
        modifier = Modifier.size(72.dp).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * A card for a screen's content (a list, a checklist), in the content-card colour. Rows that highlight
 * when tapped pass [contentPadding] without the sides, so the highlight reaches the card's edges.
 */
@Composable
fun ContentCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.l, vertical = Spacing.xs),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A row that opens something: its [text], then a chevron for a screen of the app or, with [external], an
 * "open in new" mark for something outside it (the browser). Pads its own sides, for a card's full width.
 */
@Composable
fun NavigationRow(
    text: String,
    onClick: () -> Unit,
    external: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTargets.minimum)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            painterResource(if (external) R.drawable.ic_open_in_new else R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** From this font scale on, two buttons side by side stack: their labels would wrap. */
private const val STACK_BUTTONS_FROM_FONT_SCALE = 1.3f

/**
 * Two buttons, the [primary] one at the end: side by side, or stacked (primary first) at large font sizes.
 * Each gets the modifier for its place; [secondary] may be absent.
 */
@Composable
fun ButtonPair(
    secondary: (@Composable (Modifier) -> Unit)?,
    primary: @Composable (Modifier) -> Unit,
) {
    if (LocalDensity.current.fontScale >= STACK_BUTTONS_FROM_FONT_SCALE) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            primary(Modifier.fillMaxWidth())
            secondary?.invoke(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            secondary?.invoke(Modifier.weight(1f))
            primary(Modifier.weight(1f))
        }
    }
}
