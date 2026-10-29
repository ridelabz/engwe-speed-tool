package io.github.ridelabz.engwespeedtool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Which permanent banner, if any, marks this build as an unreviewed test build. */
sealed interface TestBuildLabel {
    data object None : TestBuildLabel

    data object Local : TestBuildLabel

    data class PullRequest(
        val number: Int,
    ) : TestBuildLabel

    companion object {
        fun of(
            isTestBuild: Boolean,
            pullRequest: Int,
        ): TestBuildLabel =
            when {
                !isTestBuild -> None
                pullRequest > 0 -> PullRequest(pullRequest)
                else -> Local
            }

        val current: TestBuildLabel get() = of(BuildConfig.IS_TEST_BUILD, BuildConfig.TEST_BUILD_PR)
    }
}

/**
 * The root of every screen. In a test build it puts the banner above [content], so no screen can leave
 * it out, and screen content can't draw over it; the banner keeps its own colours, whatever the theme.
 * (Dialogs and pop-ups open in windows of their own and can cover it.)
 */
@Composable
fun TestBuildFrame(content: @Composable () -> Unit) {
    val label = TestBuildLabel.current
    if (label == TestBuildLabel.None) {
        content()
        return
    }
    val bannerInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    // The banner takes the top inset; the content keeps its side insets.
    val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    Column(modifier = Modifier.fillMaxSize()) {
        TestBuildBanner(label, Modifier.windowInsetsPadding(bannerInsets))
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .consumeWindowInsets(top),
        ) { content() }
    }
}

@Composable
fun TestBuildBanner(
    label: TestBuildLabel,
    modifier: Modifier = Modifier,
) {
    val text =
        when (label) {
            TestBuildLabel.None -> return
            TestBuildLabel.Local -> stringResource(R.string.test_build_banner_local)
            is TestBuildLabel.PullRequest -> stringResource(R.string.test_build_banner_pr, label.number)
        }
    Text(
        text = text,
        color = Color.Black,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFB300))
                .then(modifier)
                .padding(vertical = 8.dp, horizontal = 16.dp),
    )
}
