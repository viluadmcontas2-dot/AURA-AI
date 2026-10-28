package ai.aura.app.ui.screens.settings

import ai.aura.app.integration.Autonomy
import ai.aura.app.integration.FakeSettingsGateway
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutonomySectionTest {
    @get:Rule val compose = createComposeRule()

    private fun show(gw: FakeSettingsGateway) {
        compose.setContent {
            AuraTheme {
                val scope = rememberCoroutineScope()
                val state = remember { AutonomyState(gw, MemorySettingsCache(), scope).also { it.reload() } }
                androidx.compose.foundation.layout.Column { AutonomySection(state) }
            }
        }
    }

    @Test fun `mostra tres opcoes em portugues e a trava de custo`() {
        show(FakeSettingsGateway())
        compose.waitForIdle()
        compose.onNodeWithText("AUTONOMIA").assertIsDisplayed()
        compose.onNodeWithText("Total (padrão)").assertIsDisplayed()
        compose.onNodeWithText("Perguntar antes de apagar").assertIsDisplayed()
        compose.onNodeWithText("Perguntar tudo").assertIsDisplayed()
        compose.onNodeWithText("Trava de custo").assertIsDisplayed()
        compose.onNodeWithText("Nunca usar API paga sem a sua aprovação.").assertIsDisplayed()
    }

    @Test fun `tocar numa opcao grava no motor`() {
        val gw = FakeSettingsGateway()
        show(gw)
        compose.waitForIdle()
        compose.onNodeWithText("Perguntar tudo").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Autonomy.AskAll, runBlocking { gw.autonomy() }) }
    }

    @Test fun `funciona sem motor e avisa que esta offline`() {
        val gw = FakeSettingsGateway().apply { down = true }
        show(gw)
        compose.waitForIdle()
        compose.onNodeWithText("Perguntar antes de apagar").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sem motor: salvo no aparelho e enviado quando ele voltar.").assertIsDisplayed()
    }
}
