package io.github.ridelabz.engwespeedtool.testing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** The screen rule really applies each variant, so the screenshots differ for a reason. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ScreenRuleTest(
    private val variant: ScreenVariant,
) {
    @get:Rule
    val screen = ScreenRule(variant)

    @Test
    fun variantReachesCompose() {
        var dark: Boolean? = null
        var fontScale: Float? = null
        screen.compose.setContent {
            dark = isSystemInDarkTheme()
            fontScale = LocalDensity.current.fontScale
        }
        screen.compose.waitForIdle()
        assertEquals(variant.dark, dark)
        assertEquals(variant.fontScale, fontScale)
    }

    @Test
    fun controlsSmallerThan48DpOrWithoutALabelFail() {
        var size by mutableStateOf(20.dp)
        var label by mutableStateOf<String?>(null)
        screen.setScreen {
            // In a Box: the screen's Surface would stretch its direct child to the whole screen.
            Box {
                // A new node for each label, so its semantics are read afresh.
                key(label) {
                    Box(
                        Modifier
                            .size(size)
                            .semantics { label?.let { contentDescription = it } }
                            .clickable {},
                    )
                }
            }
        }
        assertThrows(AssertionError::class.java) { screen.checkControls() }
        label = "Open"
        // Labelled, but small: Compose's 48 dp touch area doesn't hide it.
        assertThrows(AssertionError::class.java) { screen.checkControls() }
        size = 48.dp
        label = null
        assertThrows(AssertionError::class.java) { screen.checkControls() }
        label = "Open"
        screen.checkControls()
    }

    @Test
    fun materialIconButtonsCountTheirTouchArea() {
        screen.setScreen { Box { IconButton(onClick = {}) { Text("X") } } }
        screen.checkControls()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = ScreenVariant.parameters()
    }
}
