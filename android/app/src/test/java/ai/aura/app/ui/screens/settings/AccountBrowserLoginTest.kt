package ai.aura.app.ui.screens.settings

import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountBrowserLoginTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `entrar abre cada pagina oficial mesmo sem motor`() {
        val gateway = object : EngineGateway {
            override val events = emptyFlow<EngineEvent>()
            override suspend fun jobs(): Nothing = error("Sem motor")
            override suspend fun approve(id: String): Nothing = error("Sem motor")
            override suspend fun cancel(id: String): Nothing = error("Sem motor")
            override suspend fun route(text: String): Nothing = error("Sem motor")
            override suspend fun submit(requestId: String, request: JobRequest): Nothing = error("Sem motor")
            override suspend fun capabilities(): Nothing = error("Sem motor")
            override suspend fun authStatus(): Nothing = error("Sem motor")
        }
        val opened = mutableListOf<String>()
        compose.setContent {
            AuraTheme { ConnectAccountsScreen(gateway, onClose = {}, onOpenUrl = { opened += it }) }
        }
        listOf(
            "Claude" to "https://claude.ai/login",
            "Codex" to "https://chatgpt.com/auth/login",
            "Gemini" to "https://accounts.google.com",
            "GitHub" to "https://github.com/login",
        ).forEach { (name, url) ->
            compose.onNodeWithContentDescription("Entrar no $name pelo navegador").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(url, opened.last()) }
            compose.onNodeWithText("Entre no navegador e volte ao AURA").performScrollTo().assertIsDisplayed()
        }
    }
}
