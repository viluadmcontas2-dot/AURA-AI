package ai.aura.app.ui

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import ai.aura.app.ui.engine.FakeEngineGateway
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Emulator-only UI smoke tests. Engine integration is tested separately, never faked in MainActivity. */
@RunWith(AndroidJUnit4::class)
class AuraNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun homeRendersApprovedNavigationAndGreeting() {
        compose.setContent { AuraApp(FakeEngineGateway(seed = emptyList())) }
        compose.onNodeWithContentDescription("Navegação principal").assertExists()
        compose.onNodeWithText("Início").assertExists()
        compose.onNodeWithText("O que vamos criar hoje?").assertExists()
    }

    @Test
    fun bottomNavigationCanChangeToProjects() {
        compose.setContent { AuraApp(FakeEngineGateway(seed = emptyList())) }
        compose.onNodeWithText("Projetos").performClick()
        compose.onNodeWithContentDescription("Navegação principal").assertExists()
    }
}
