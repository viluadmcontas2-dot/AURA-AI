package ai.aura.app.ui.screens.chat

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ImproveResult
import ai.aura.app.ui.engine.SessionLine
import ai.aura.app.ui.engine.SessionLineRole
import ai.aura.app.ui.screens.home.HomeContent
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.voice.LocalVoiceInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Abrir uma sessão do Claude Code no chat e "Melhorar pedido", clicando de verdade (Compose + Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // sem isso o Robolectric descarta toques em balões de cantos desiguais
class ChatSessionImproveTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val navigated = mutableListOf<Pair<AppTarget, String>>()
    private var engineStarted: OfflineReason? = null

    private fun open(
        gw: ScriptedChatGateway,
        entry: ChatEntry = ChatEntry(open = true),
        link: LinkState = LinkState.Online,
        voice: ((String) -> Unit) -> Unit = {},
        withVoice: Boolean = false,
    ) {
        rule.setContent {
            AuraTheme {
                CompositionLocalProvider(LocalVoiceInput provides (if (withVoice) voice else null)) {
                    ChatScreen(
                        gateway = gw, entry = entry, onEntryChange = {}, onClose = {},
                        link = link, store = LocalStore(tmp.root),
                        onNavigate = { t, r -> navigated += t to r },
                        onStartEngine = { engineStarted = it },
                    )
                }
            }
        }
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitText(text: String, substring: Boolean = false) {
        val end = System.currentTimeMillis() + 10_000
        while (!has(text, substring)) {
            check(System.currentTimeMillis() < end) { "não apareceu: $text" }
            rule.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun gone(text: String) {
        val end = System.currentTimeMillis() + 10_000
        while (has(text)) {
            check(System.currentTimeMillis() < end) { "não sumiu: $text" }
            rule.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun typeOnly(text: String) {
        rule.onNodeWithContentDescription("Mensagem para o AURA").performTextInput(text)
    }

    private fun field() = rule.onNodeWithContentDescription("Mensagem para o AURA")

    // ---- abrir uma sessão ----

    @Test fun `abrir sessao do terminal carrega o historico dela e a proxima mensagem continua com o id`() {
        // O chat guardado no disco é de OUTRA conversa: não pode aparecer dentro da sessão escolhida.
        val store = LocalStore(tmp.root)
        store.saveChat(listOf(ChatMessage(0, true, "Conversa antiga do disco"), ChatMessage(1, false, "Resposta antiga")))
        store.saveSessionId("outra-sessao")
        val gw = ScriptedChatGateway(sessionLines = listOf(
            SessionLine(SessionLineRole.User, "Oi vindo do terminal"),
            SessionLine(SessionLineRole.Tool, "Lendo arquivos…"),
            SessionLine(SessionLineRole.Assistant, "Resposta que o Claude deu no terminal"),
        ))
        open(gw, entry = ChatEntry(open = true, sessionId = "term-1"))

        waitText("Resposta que o Claude deu no terminal")
        assertTrue(has("Oi vindo do terminal"))
        assertTrue("a linha de ferramenta aparece discreta", has("Lendo arquivos…"))
        assertFalse("conversa de outra sessão não vaza", has("Conversa antiga do disco"))
        assertEquals(listOf("term-1"), gw.sessionsRead.toList())

        field().performTextInput("Continue daqui")
        rule.onNodeWithContentDescription("Enviar mensagem").performClick()
        rule.waitUntil(10_000) { gw.started.isNotEmpty() }
        assertEquals(listOf("Continue daqui" to "term-1"), gw.started.toList())
    }

    @Test fun `sessao aberta sem motor nao fica em branco e mostra Iniciar o motor`() {
        val gw = ScriptedChatGateway(down = true)
        open(gw, entry = ChatEntry(open = true, sessionId = "term-1"), link = LinkState.Offline(OfflineReason.EngineStopped))

        waitText("O motor está desligado.", substring = true)
        rule.onNodeWithText("Iniciar o motor").performClick()
        assertEquals(OfflineReason.EngineStopped, engineStarted)
        field().assertExists() // dá para escrever mesmo assim
    }

    // ---- Melhorar pedido ----

    @Test fun `Melhorar pedido so aparece com mais de 160 caracteres`() {
        open(ScriptedChatGateway())
        typeOnly("a".repeat(160))
        rule.waitForIdle()
        assertFalse("com 160 não aparece", has("Melhorar pedido"))

        typeOnly("b")
        waitText("Melhorar pedido")
    }

    @Test fun `tocar troca o texto, mostra Melhorando e Desfazer restaura sem enviar nada`() {
        val gate = CompletableDeferred<Unit>()
        val original = "quero um app " + "x".repeat(170)
        val gw = ScriptedChatGateway(improve = { gate.await(); ImproveResult.Improved("Pedido melhorado e claro") })
        open(gw)
        typeOnly(original)
        waitText("Melhorar pedido")
        rule.onNodeWithText("Melhorar pedido").performClick()

        waitText("Melhorando…")
        rule.onNodeWithText("Melhorando…").assertIsNotEnabled()
        assertEquals(listOf(original), gw.improved.toList())

        gate.complete(Unit)
        waitText("Desfazer")
        field().assert(hasText("Pedido melhorado e claro"))
        assertFalse(has("Melhorando…"))
        assertTrue("melhorar nunca envia sozinho", gw.started.isEmpty())

        rule.onNodeWithText("Desfazer").performClick()
        field().assert(hasText(original))
        assertFalse(has("Desfazer"))
        assertTrue(gw.started.isEmpty())
    }

    @Test fun `sem chave do Gemini mostra o aviso e Abrir Conexoes leva la`() {
        val gw = ScriptedChatGateway(improve = {
            ImproveResult.Failed("gemini_key_missing", "Cole a chave do Gemini em Conexões para melhorar pedidos.", ChatAction.OpenConnections)
        })
        val original = "z".repeat(200)
        open(gw)
        typeOnly(original)
        waitText("Melhorar pedido")
        rule.onNodeWithText("Melhorar pedido").performClick()

        waitText("Cole a chave do Gemini em Conexões para melhorar pedidos.")
        field().assert(hasText(original)) // o texto não muda
        rule.onNodeWithText("Abrir Conexões").performClick()
        assertEquals(listOf(AppTarget.Connections to ""), navigated)
    }

    @Test fun `erro de rede ou limite mostra Tentar de novo e a segunda tentativa melhora`() {
        var tries = 0
        val gw = ScriptedChatGateway(improve = {
            tries++
            if (tries == 1) ImproveResult.Failed("rate_limited", "O Gemini pediu para esperar um pouco.", ChatAction.Retry)
            else ImproveResult.Improved("Texto melhor")
        })
        open(gw)
        typeOnly("w".repeat(200))
        waitText("Melhorar pedido")
        rule.onNodeWithText("Melhorar pedido").performClick()

        waitText("O Gemini pediu para esperar um pouco.")
        rule.onNodeWithText("Tentar de novo").performClick()
        waitText("Desfazer")
        field().assert(hasText("Texto melhor"))
        assertEquals(2, gw.improved.size)
    }

    @Test fun `texto vindo da voz tambem ganha Melhorar pedido`() {
        val spoken = "falei um pedido muito longo ".repeat(8)
        open(ScriptedChatGateway(), withVoice = true, voice = { onText -> onText(spoken) })
        rule.onNodeWithContentDescription("Falar um pedido pelo microfone").performClick()

        waitText("Melhorar pedido")
        rule.onNodeWithText("Melhorar pedido").performClick()
        waitText("Desfazer")
        field().assert(hasText("melhorado: ${spoken.trim()}"))
    }

    // ---- o mesmo botão no campo do Início ----

    @Test fun `o campo do Inicio melhora o pedido, desfaz e nunca abre o chat sozinho`() {
        val opened = mutableListOf<ChatEntry>()
        val gw = ScriptedChatGateway()
        rule.setContent { AuraTheme { HomeContent(gw, onOpenChat = { opened += it }) } }
        val original = "pedido longo ".repeat(15)
        typeOnly(original)
        waitText("Melhorar pedido")
        rule.onNodeWithText("Melhorar pedido").performClick()

        waitText("Desfazer")
        field().assert(hasText("melhorado: ${original.trim()}"))
        rule.onNodeWithText("Desfazer").performClick()
        field().assert(hasText(original))
        assertTrue("melhorar não envia", opened.isEmpty())
    }

    @Test fun `no Inicio sem chave do Gemini o aviso leva a Conexoes`() {
        var connections = 0
        val gw = ScriptedChatGateway(improve = {
            ImproveResult.Failed("gemini_key_missing", "Cole a chave do Gemini em Conexões para melhorar pedidos.", ChatAction.OpenConnections)
        })
        rule.setContent { AuraTheme { HomeContent(gw, onOpenChat = {}, onOpenConnections = { connections++ }) } }
        typeOnly("k".repeat(200))
        waitText("Melhorar pedido")
        rule.onNodeWithText("Melhorar pedido").performClick()

        waitText("Cole a chave do Gemini em Conexões para melhorar pedidos.")
        rule.onNodeWithText("Abrir Conexões").performClick()
        assertEquals(1, connections)
    }
}
