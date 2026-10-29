package io.github.ridelabz.engwespeedtool.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class RiskAcceptanceTest {
    @get:Rule
    val screen = ScreenRule()

    private val statements get() = riskStatements(RuntimeEnvironment.getApplication().resources)

    @Test
    fun theFiveStatementsInOrder() {
        assertEquals(
            listOf(
                R.string.legal_statement_legal_limit,
                R.string.legal_statement_where_legal,
                R.string.legal_statement_responsibility,
                R.string.legal_statement_warranty,
                R.string.legal_statement_unofficial,
            ),
            statements,
        )
    }

    private fun text(
        @StringRes id: Int,
    ) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun acceptStaysDisabledUntilAllFiveAreTicked() {
        var accepted = 0
        screen.setScreen { RiskAcceptanceScreen(onAccept = { accepted++ }, onBack = {}) }
        val accept = screen.compose.onNodeWithText(text(R.string.legal_accept))
        for (statement in statements) {
            accept.assertIsNotEnabled()
            screen.compose
                .onNodeWithText(text(statement))
                .performScrollTo()
                .performClick()
        }
        accept.assertIsEnabled()

        // Unticking any statement disables it again.
        screen.compose
            .onNodeWithText(text(statements[2]))
            .performScrollTo()
            .performClick()
        accept.assertIsNotEnabled().performClick()
        assertEquals(0, accepted)

        screen.compose
            .onNodeWithText(text(statements[2]))
            .performScrollTo()
            .performClick()
        accept.assertIsEnabled().performClick()
        assertEquals(1, accepted)
    }
}
