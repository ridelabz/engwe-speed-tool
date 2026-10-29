package io.github.ridelabz.engwespeedtool.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a wide screen, a screen's content and its bottom actions stay at most 600 dp wide, centred. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h1280dp-xhdpi")
class ScaffoldLayoutTest {
    @get:Rule
    val screen = ScreenRule()

    @Test
    fun contentAndActionsKeepAReadableWidthCentred() {
        screen.setScreen {
            AppScaffold(onBack = {}, bottomBar = { PrimaryAction("Continue", onClick = {}) }) {
                Text("Body", modifier = Modifier.testTag("body"))
            }
        }
        val action = screen.compose.onNodeWithText("Continue").getBoundsInRoot()
        // 600 dp less the 16 dp side paddings.
        assertTrue("${action.width}", action.width <= 568.dp + 1.dp)
        val screenWidth = 960.dp
        assertEquals("centred", (screenWidth - action.right).value, action.left.value, 1f)
        val body = screen.compose.onNodeWithTag("body").getBoundsInRoot()
        assertEquals("the content starts where the actions do", action.left.value, body.left.value, 1f)
    }
}
