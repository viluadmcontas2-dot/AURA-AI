package ai.aura.app.ui.screens.sessions

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.SessionLine
import ai.aura.app.ui.engine.SessionLineRole
import ai.aura.app.ui.screens.HomeScreen
import ai.aura.app.ui.screens.chat.AppTarget
import ai.aura.app.ui.screens.chat.ChatEntry
import ai.aura.app.ui.screens.chat.ChatScreen
import ai.aura.app.ui.screens.chat.ScriptedChatGateway
import ai.aura.app.ui.screens.home.HomeContent
import ai.aura.app.ui.screens.settings.SettingsScreen
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tela Sessões abre do cabeçalho do Início, do cabeçalho do Chat e de Ajustes (nada de botão mudo). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionsEntryTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `o cabecalho do Inicio tem Abrir sessoes`() {
        var opened = 0
        rule.setContent {
            AuraTheme {
                HomeContent(ScriptedChatGateway(), onOpenChat = {}, onOpenSessions = { opened++ })
            }
        }
        rule.onNodeWithContentDescription("Abrir sessões").performClick()
        assertEquals(1, opened)
    }

    @Test fun `o cabecalho do Chat tem Sessoes mesmo com a conversa em branco`() {
        val navigated = mutableListOf<Pair<AppTarget, String>>()
        rule.setContent {
            AuraTheme {
                ChatScreen(
                    gateway = ScriptedChatGateway(), entry = ChatEntry(open = true), onEntryChange = {}, onClose = {},
                    store = LocalStore(tmp.root), onNavigate = { t, r -> navigated += t to r },
                )
            }
        }
        rule.onNodeWithText("Sessões").performClick()
        assertEquals(listOf(AppTarget.Sessions to ""), navigated)
    }

    @Test fun `Ajustes tem Ver sessoes`() {
        val opened = mutableListOf<String>()
        rule.setContent {
            AuraTheme { SettingsScreen(ScriptedChatGateway(), onClose = {}, onOpenScreen = { opened += it }) }
        }
        rule.onNodeWithText("Ver sessões").performScrollTo().performClick()
        assertEquals(listOf("sessoes"), opened)
    }

    @Test fun `o Inicio abre o chat na sessao escolhida e avisa que tratou o pedido`() {
        val gw = ScriptedChatGateway(sessionLines = listOf(SessionLine(SessionLineRole.Assistant, "Histórico da sessão escolhida")))
        var handled = 0
        rule.setContent {
            AuraTheme {
                HomeScreen(gw, openSessionId = "term-9", onOpenSessionHandled = { handled++ }, store = LocalStore(tmp.root))
            }
        }
        val end = System.currentTimeMillis() + 10_000
        while (rule.onAllNodesWithText("Histórico da sessão escolhida").fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "o chat não abriu na sessão" }
            rule.waitForIdle()
            Thread.sleep(20)
        }
        assertEquals(listOf("term-9"), gw.sessionsRead.toList())
        assertEquals(1, handled)
    }
}
