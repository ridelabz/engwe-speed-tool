package io.github.ridelabz.engwespeedtool.ui.report

import androidx.annotation.StringRes
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.ridelabz.engwespeedtool.R
import io.github.ridelabz.engwespeedtool.testing.PHONE
import io.github.ridelabz.engwespeedtool.testing.ScreenRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The report sheet asks for the IoT version only where the report is about the bike, and shares only a version. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ReportContentTest {
    @get:Rule
    val screen = ScreenRule()

    private fun text(
        @StringRes id: Int,
    ) = RuntimeEnvironment.getApplication().getString(id)

    private val answers = mutableListOf<Pair<ReportType, ReportAnswers>>()

    private fun show() =
        screen.setScreen {
            ReportContent(
                preview = { type, typed ->
                    answers += type to typed
                    val version = IotVersion.shared(typed.iotVersion)?.takeIf { type.aboutTheBike }
                    ReportPreview(listOfNotNull(version?.let { SharedItem("IoT", it) }), demo = true, needsLog = true)
                },
                initialType = null,
                onClose = {},
            )
        }

    private fun field() = hasSetTextAction() and hasText(text(R.string.report_iot_version))

    private fun fields() =
        screen.compose
            .onAllNodes(field())
            .fetchSemanticsNodes()
            .size

    @Test
    fun aVersionIsAskedForWhereTheReportIsAboutTheBike() {
        show()
        assertEquals("no field before a type is chosen", 0, fields())
        screen.compose.onNodeWithText(text(R.string.report_type_problem)).performClick()
        assertEquals(1, fields())
        screen.compose.onNodeWithText(text(R.string.report_type_idea)).performClick()
        assertEquals("an idea doesn't ask for it", 0, fields())
        screen.compose.onNodeWithText(text(R.string.report_type_compatibility)).performClick()
        assertEquals(1, fields())
    }

    @Test
    fun aTypedVersionIsSharedInCapitalsAndAPastedAddressIsRefused() {
        show()
        screen.compose.onNodeWithText(text(R.string.report_type_compatibility)).performClick()
        screen.compose.onNode(field()).performTextInput("218can")
        screen.compose.onNode(field()).performTextReplacement("D4:2B:71:9E:5A:C3")
        screen.compose.waitForIdle()
        assertEquals("the field keeps what it had", "218can", answers.last().second.iotVersion)
        screen.compose.onNodeWithText(text(R.string.action_next)).performClick()
        screen.compose.onNodeWithText("218CAN").assertExists()
        // The sheet's flow: back, another type, and the preview is asked again with that type.
        screen.compose.onNodeWithText(text(R.string.action_back)).performClick()
        screen.compose.onNodeWithText(text(R.string.report_type_idea)).performClick()
        screen.compose.onNodeWithText(text(R.string.action_next)).performClick()
        assertEquals(
            0,
            screen.compose
                .onAllNodes(hasText("218CAN"))
                .fetchSemanticsNodes()
                .size,
        )
    }
}
