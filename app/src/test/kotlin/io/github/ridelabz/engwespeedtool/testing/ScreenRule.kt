package io.github.ridelabz.engwespeedtool.testing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.ridelabz.engwespeedtool.ui.theme.SpeedToolTheme
import io.github.ridelabz.engwespeedtool.ui.theme.TouchTargets
import org.junit.Assert.assertTrue
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.io.File

/** Phone screen used for every screenshot: a common 411 x 891 dp phone at 2x, to keep files small. */
const val PHONE = "w411dp-h891dp-xhdpi"

/** Each screen is captured in light and dark, at 100 % and 200 % font size. */
enum class ScreenVariant(
    val dark: Boolean,
    val fontScale: Float,
    val suffix: String,
) {
    LIGHT(dark = false, fontScale = 1f, suffix = "light"),
    DARK(dark = true, fontScale = 1f, suffix = "dark"),
    LIGHT_FONT_200(dark = false, fontScale = 2f, suffix = "light_font200"),
    DARK_FONT_200(dark = true, fontScale = 2f, suffix = "dark_font200"),
    ;

    companion object {
        /** Parameters for ParameterizedRobolectricTestRunner. */
        fun parameters(): List<Array<Any>> = entries.map { arrayOf(it) }
    }
}

/** True when the build asked for screenshots (`-Pscreenshots`); otherwise screens are composed but not captured. */
val recordingScreenshots: Boolean get() = System.getProperty("screenshots.record") == "true"

/**
 * A compose rule for screen tests. Before the host activity starts it applies the [variant]'s night mode
 * and font scale to the whole configuration (so dialogs and sheets get them too), and registers the
 * plain host activity, which the app's manifest doesn't declare.
 */
class ScreenRule(
    private val variant: ScreenVariant = ScreenVariant.LIGHT,
) : TestRule {
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule<ComponentActivity>()

    private val configure =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    RuntimeEnvironment.setQualifiers(if (variant.dark) "+night" else "+notnight")
                    RuntimeEnvironment.setFontScale(variant.fontScale)
                    val app = RuntimeEnvironment.getApplication()
                    Shadows
                        .shadowOf(app.packageManager)
                        .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                    base.evaluate()
                }
            }
        }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement = RuleChain.outerRule(configure).around(compose).apply(base, description)

    /** Shows [content] as a whole screen in the app's theme. */
    fun setScreen(content: @Composable () -> Unit) {
        compose.setContent {
            SpeedToolTheme { Surface(modifier = Modifier.fillMaxSize(), content = content) }
        }
    }

    /** Captures the screen as `build/outputs/roborazzi/<group>/<name>_<variant>.png` when recording. */
    fun capture(
        group: String,
        name: String,
    ) {
        compose.waitForIdle()
        checkControls()
        if (!recordingScreenshots) return
        val path = "build/outputs/roborazzi/$group/${name}_${variant.suffix}.png"
        compose.onRoot().captureRoboImage(path)
        assertTrue("screenshot not written: $path", File(path).isFile)
    }

    /** Like [capture], but the whole screen including dialogs and sheets, which open in windows of their own. */
    @OptIn(ExperimentalRoborazziApi::class)
    fun captureScreen(
        group: String,
        name: String,
    ) {
        compose.waitForIdle()
        checkControls()
        if (!recordingScreenshots) return
        val path = "build/outputs/roborazzi/$group/${name}_${variant.suffix}.png"
        captureScreenRoboImage(path)
        assertTrue("screenshot not written: $path", File(path).isFile)
    }

    /**
     * Every control (anything clicked, toggled or selected, in every window) is laid out at least 48 dp each
     * way and says what it is (text or a content description), so screen readers and large fingers can use
     * it. Every screen test runs it on every state and variant
     * it captures, recorded or not. The layout size counts, not where touches land: Compose stretches every
     * clickable's touch area to 48 dp, so only the size laid out tells a small control apart. That size
     * includes room reserved around the control on its own layout node, as Material's minimum interactive size
     * does; Compose's touch area covers that room.
     */
    fun checkControls() {
        val minimum = with(compose.density) { TouchTargets.minimum.toPx() } - 0.5f
        val controls =
            compose
                .onAllNodes(hasClickAction() or isToggleable() or isSelectable())
                .fetchSemanticsNodes()
                .mapNotNull { node ->
                    val config = node.config
                    val label =
                        listOfNotNull(
                            config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
                            config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
                        ).joinToString(" ").trim()
                    val layout = node.layoutInfo
                    when {
                        layout.width < minimum || layout.height < minimum -> {
                            "under 48 dp (${layout.width} x ${layout.height} px): \"$label\""
                        }

                        label.isEmpty() -> {
                            "no label: $config"
                        }

                        else -> {
                            null
                        }
                    }
                }
        assertTrue(controls.joinToString("\n"), controls.isEmpty())
    }
}
